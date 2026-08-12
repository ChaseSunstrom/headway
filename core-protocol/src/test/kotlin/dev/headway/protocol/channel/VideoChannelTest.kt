/*
 * This file is part of Headway.
 * Copyright (C) 2026 The Headway Authors
 *
 * Headway is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * Headway is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * Headway. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.headway.protocol.channel

import aap_protobuf.service.media.shared.message.ConfigOuterClass.Config
import aap_protobuf.service.media.source.message.AckOuterClass.Ack
import aap_protobuf.service.media.video.message.VideoFocusModeOuterClass.VideoFocusMode
import aap_protobuf.service.media.video.message.VideoFocusNotificationOuterClass.VideoFocusNotification
import aap_protobuf.service.media.video.message.VideoFocusReasonOuterClass.VideoFocusReason
import dev.headway.protocol.framing.AapMessage
import dev.headway.protocol.framing.ChannelId
import dev.headway.protocol.io.Cryptor
import dev.headway.protocol.io.FramedConnection
import dev.headway.protocol.io.Transport
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.EOFException

/**
 * Unit-level tests for the phone side of the video channel.
 *
 * These pin the bytes Headway puts on the wire against the two payloads AACS
 * hand-encodes on the same side of the exchange (`08 03` for `Setup`,
 * `08 00 10 00` for `Start`), and pin the behaviour that a shared-code
 * round-trip test cannot catch: the timestamp header on `DATA` and its absence
 * on `CODEC_CONFIG`, and the acknowledgement window actually gating sends.
 */
class VideoChannelTest {

    // --- setup / config -----------------------------------------------------

    @Test
    fun `the setup request is the two bytes AACS puts on the wire`() = harness { h ->
        h.video.sendSetup()

        val sent = h.peer.receive()
        // AACS/AAServer/src/VideoChannelHandler.cpp L138-L145: field 1 varint 3,
        // i.e. MEDIA_CODEC_VIDEO_H264_BP.
        assertEquals(AvMessageId.SETUP, sent.messageId)
        assertEquals("08 03", sent.payload.hex())
        assertEquals(ChannelId.MEDIA_SINK_VIDEO.id, sent.channelId)
        assertTrue(sent.encrypted, "every video message travels inside the TLS session")
        assertFalse(sent.control, "video messages are MessageType::SPECIFIC, not CONTROL")
    }

    @Test
    fun `the config response yields status, window and offered indices`() = harness { h ->
        h.peerSendConfig(Config.Status.STATUS_READY, maxUnacked = 4, indices = listOf(0, 2))

        val response = h.video.awaitConfig()
        assertTrue(response.ready)
        assertEquals(4, response.maxUnacked)
        assertEquals(listOf(0, 2), response.configurationIndices)
        assertEquals(response, h.video.setupResponse)
    }

    @Test
    fun `a WAIT status is reported rather than treated as ready`() = harness { h ->
        // openauto sends STATUS_WAIT when its video output fails to initialise
        // (VideoMediaSinkService.cpp L108-L112). No reference says what follows,
        // so the phone must be able to see it.
        h.peerSendConfig(Config.Status.STATUS_WAIT, maxUnacked = 1, indices = listOf(0))

        val response = h.video.awaitConfig()
        assertFalse(response.ready)
        assertEquals(Config.Status.STATUS_WAIT, response.status)
    }

    @Test
    fun `an unstated window is reported as unstated, not as zero`() = harness { h ->
        // max_unacked is optional; a head unit that omits it must not be read as
        // advertising a window of 0, which would stall every send.
        h.peerSend(
            AvMessageId.CONFIG,
            Config.newBuilder()
                .setStatus(Config.Status.STATUS_READY)
                .addConfigurationIndices(0)
                .build()
                .toByteArray(),
        )

        assertNull(h.video.awaitConfig().maxUnacked)
    }

    @Test
    fun `a focus notification arriving before the config is not mistaken for it`() = harness { h ->
        // openauto chains the focus notification onto the config's send
        // (VideoMediaSinkService.cpp L120-L125), but nothing guarantees the
        // order in which the phone reads them.
        h.peerSendFocus(VideoFocusMode.VIDEO_FOCUS_PROJECTED, unsolicited = true)
        h.peerSendConfig(Config.Status.STATUS_READY, maxUnacked = 1, indices = listOf(0))

        val response = h.video.awaitConfig()
        assertTrue(response.ready)
        assertEquals(VideoFocusMode.VIDEO_FOCUS_PROJECTED, h.video.videoFocus)
    }

    // --- start / stop -------------------------------------------------------

    @Test
    fun `the start indication is the four bytes AACS puts on the wire`() = harness { h ->
        h.peerSendConfig(Config.Status.STATUS_READY, maxUnacked = 1, indices = listOf(0))
        h.video.awaitConfig()

        h.video.sendStart(sessionId = 0, configurationIndex = 0)

        val sent = h.peer.receive()
        // AACS/AAServer/src/VideoChannelHandler.cpp L152-L161: field 1 varint 0,
        // field 2 varint 0.
        assertEquals(AvMessageId.START, sent.messageId)
        assertEquals("08 00 10 00", sent.payload.hex())
        assertEquals(0, h.video.sessionId)
        assertEquals(0, h.video.configurationIndex)
    }

    @Test
    fun `a configuration index the head unit never offered is refused`() {
        val error = assertThrows(VideoChannelException::class.java) {
            harness { h ->
                h.peerSendConfig(Config.Status.STATUS_READY, maxUnacked = 1, indices = listOf(0))
                h.video.awaitConfig()
                h.video.sendStart(sessionId = 1, configurationIndex = 3)
            }
        }
        assertTrue(error.message!!.contains("was not offered"), error.message)
    }

    @Test
    fun `stop carries the empty Stop message`() = harness { h ->
        h.video.sendStop()

        val sent = h.peer.receive()
        assertEquals(AvMessageId.STOP, sent.messageId)
        assertEquals(0, sent.payload.size)
    }

    // --- media --------------------------------------------------------------

    @Test
    fun `codec config carries the SPS PPS bytes with no timestamp header`() = harness { h ->
        // aasdk routes MEDIA_MESSAGE_CODEC_CONFIG straight to onMediaIndication
        // with no offset (VideoMediaSinkService.cpp L121-L123). An 8-byte header
        // here would eat the first eight bytes of the SPS.
        val spsPps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0, 0, 1, 0x68.toByte(), 0xCE.toByte())
        h.video.sendCodecConfig(spsPps)

        val sent = h.peer.receive()
        assertEquals(AvMessageId.CODEC_CONFIG, sent.messageId)
        assertArrayEquals(spsPps, sent.payload)
    }

    @Test
    fun `a frame is a big endian microsecond timestamp followed by the media bytes`() = harness { h ->
        val media = byteArrayOf(0, 0, 0, 1, 0x65, 0x11, 0x22)
        h.video.sendFrame(media, timestampMicros = 0x0102030405060708L)

        val sent = h.peer.receive()
        assertEquals(AvMessageId.DATA, sent.messageId)
        assertEquals(MediaFrame.TIMESTAMP_SIZE + media.size, sent.payload.size)
        assertEquals("01 02 03 04 05 06 07 08", sent.payload.copyOf(8).hex())
        assertArrayEquals(media, sent.payload.copyOfRange(8, sent.payload.size))
    }

    @Test
    fun `an acknowledgement reduces the outstanding count`() = harness { h ->
        h.peerSendConfig(Config.Status.STATUS_READY, maxUnacked = 4, indices = listOf(0))
        h.video.awaitConfig()

        h.video.sendFrame(byteArrayOf(1), 0)
        h.video.sendFrame(byteArrayOf(2), 33_333)
        assertEquals(2, h.video.unacknowledgedMessages)

        h.peerSendAck(sessionId = 7, ack = 1)
        val ack = h.video.awaitAck()
        assertEquals(7, ack.sessionId)
        assertEquals(1, ack.ack)
        assertEquals(1, h.video.unacknowledgedMessages)
        assertEquals(1L, h.video.acksReceived)
    }

    @Test
    fun `a full window blocks the next frame until an acknowledgement arrives`() = runBlocking {
        Harness().use { h ->
            // openauto's window of exactly 1 (VideoMediaSinkService.cpp L117)
            // makes the channel lock-step, which is the case worth pinning: a
            // phone that ignores the window overruns such a head unit.
            h.peerSendConfig(Config.Status.STATUS_READY, maxUnacked = 1, indices = listOf(0))
            h.video.awaitConfig()

            h.video.sendFrame(byteArrayOf(1), 0)
            assertEquals(1, h.video.unacknowledgedMessages)

            val blocked = async { h.video.sendFrame(byteArrayOf(2), 33_333) }
            repeat(8) { yield() }
            assertFalse(blocked.isCompleted, "the second frame must wait for the first ack")

            h.peerSendAck(sessionId = 0, ack = 1)
            withTimeout(5_000) { blocked.await() }
            assertEquals(1, h.video.unacknowledgedMessages)
            assertEquals(2L, h.video.mediaMessagesSent)
        }
    }

    @Test
    fun `an unstated window does not gate sends`() = harness { h ->
        h.peerSend(
            AvMessageId.CONFIG,
            Config.newBuilder().setStatus(Config.Status.STATUS_READY).build().toByteArray(),
        )
        h.video.awaitConfig()

        // Would deadlock if an absent max_unacked were read as a window of 0.
        repeat(5) { h.video.sendFrame(byteArrayOf(it.toByte()), it * 33_333L) }
        assertEquals(5L, h.video.mediaMessagesSent)
    }

    // --- video focus --------------------------------------------------------

    @Test
    fun `a focus request carries mode and reason and leaves the deprecated field unset`() =
        harness { h ->
            h.video.requestVideoFocus(
                VideoFocusMode.VIDEO_FOCUS_NATIVE,
                VideoFocusReason.LAUNCH_NATIVE,
            )

            val sent = h.peer.receive()
            assertEquals(AvMessageId.VIDEO_FOCUS_REQUEST, sent.messageId)
            // Field 2 varint 2 (VIDEO_FOCUS_NATIVE), field 3 varint 2
            // (LAUNCH_NATIVE). Field 1 is deprecated in the schema and openauto
            // only logs it, so it is absent.
            assertEquals("10 02 18 02", sent.payload.hex())
        }

    @Test
    fun `a focus notification updates the channel's focus state`() = harness { h ->
        h.peerSendFocus(VideoFocusMode.VIDEO_FOCUS_NATIVE_TRANSIENT, unsolicited = true)

        val event = h.video.awaitFocus()
        assertEquals(VideoFocusMode.VIDEO_FOCUS_NATIVE_TRANSIENT, event.focus)
        assertTrue(event.unsolicited)
        assertEquals(VideoFocusMode.VIDEO_FOCUS_NATIVE_TRANSIENT, h.video.videoFocus)
    }

    // --- unknown and misaddressed messages ----------------------------------

    @Test
    fun `an unimplemented media message id is surfaced rather than failing the channel`() =
        harness { h ->
            // 0x800B AUDIO_UNDERFLOW_NOTIFICATION: a real id this channel has no
            // use for. aasdk logs and re-arms (VideoMediaSinkService.cpp L128-L132).
            h.peerSend(0x800B, byteArrayOf(0x08, 0x01))

            val event = h.video.receiveEvent()
            assertTrue(event is VideoChannel.Event.Unhandled, "got $event")
            assertEquals(0x800B, (event as VideoChannel.Event.Unhandled).messageId)
        }

    @Test
    fun `a message addressed to another channel is refused`() {
        val error = assertThrows(VideoChannelException::class.java) {
            harness { h ->
                h.peer.send(
                    AapMessage(
                        channelId = ChannelId.MEDIA_SINK_MEDIA_AUDIO.id,
                        control = false,
                        encrypted = true,
                        messageId = AvMessageId.ACK,
                        payload = byteArrayOf(0x08, 0x00),
                    )
                )
                h.video.receiveEvent()
            }
        }
        assertTrue(error.message!!.contains("router"), error.message)
    }

    // --- harness ------------------------------------------------------------

    private fun harness(block: suspend (Harness) -> Unit) = runBlocking {
        Harness().use { block(it) }
    }

    /**
     * A [VideoChannel] wired to a peer that speaks raw messages.
     *
     * The peer is a plain [FramedConnection] rather than the emulated sink: these
     * tests are about what one side puts on the wire, so the other side has to
     * be able to send anything, including sequences no head unit would.
     */
    private class Harness : AutoCloseable {
        private val phoneToPeer = Channel<ByteArray>(Channel.UNLIMITED)
        private val peerToPhone = Channel<ByteArray>(Channel.UNLIMITED)

        private val phoneConnection = FramedConnection(ChannelTransport(peerToPhone, phoneToPeer))
            .apply { cryptor = PassThroughCryptor }
        val peer = FramedConnection(ChannelTransport(phoneToPeer, peerToPhone))
            .apply { cryptor = PassThroughCryptor }

        val video = VideoChannel(phoneConnection)

        suspend fun peerSend(messageId: Int, payload: ByteArray) {
            peer.send(
                AapMessage(
                    channelId = ChannelId.MEDIA_SINK_VIDEO.id,
                    control = false,
                    encrypted = true,
                    messageId = messageId,
                    payload = payload,
                )
            )
        }

        suspend fun peerSendConfig(status: Config.Status, maxUnacked: Int, indices: List<Int>) {
            peerSend(
                AvMessageId.CONFIG,
                Config.newBuilder()
                    .setStatus(status)
                    .setMaxUnacked(maxUnacked)
                    .addAllConfigurationIndices(indices)
                    .build()
                    .toByteArray(),
            )
        }

        suspend fun peerSendAck(sessionId: Int, ack: Int) {
            peerSend(
                AvMessageId.ACK,
                Ack.newBuilder().setSessionId(sessionId).setAck(ack).build().toByteArray(),
            )
        }

        suspend fun peerSendFocus(focus: VideoFocusMode, unsolicited: Boolean) {
            peerSend(
                AvMessageId.VIDEO_FOCUS_NOTIFICATION,
                VideoFocusNotification.newBuilder()
                    .setFocus(focus)
                    .setUnsolicited(unsolicited)
                    .build()
                    .toByteArray(),
            )
        }

        override fun close() {
            phoneToPeer.close()
            peerToPhone.close()
        }
    }

    /**
     * A [Transport] over two coroutine channels.
     *
     * `core-transport`'s `LoopbackTransport` would do the same job, but
     * `core-protocol` does not depend on it — the dependency runs the other way.
     */
    private class ChannelTransport(
        private val incoming: Channel<ByteArray>,
        private val outgoing: Channel<ByteArray>,
    ) : Transport {
        private var pending = ByteArray(0)

        override suspend fun readFully(length: Int): ByteArray {
            while (pending.size < length) {
                val chunk = incoming.receiveCatching().getOrNull()
                    ?: throw EOFException("peer closed with ${pending.size} of $length bytes read")
                pending += chunk
            }
            val out = pending.copyOf(length)
            pending = pending.copyOfRange(length, pending.size)
            return out
        }

        override suspend fun write(bytes: ByteArray, offset: Int, length: Int) {
            outgoing.send(bytes.copyOfRange(offset, offset + length))
        }

        override fun close() {
            outgoing.close()
        }
    }

    /** Stands in for the TLS session so these tests can read the plaintext they sent. */
    private object PassThroughCryptor : Cryptor {
        override fun encrypt(plaintext: ByteArray): ByteArray = plaintext
        override fun decrypt(ciphertext: ByteArray): ByteArray = ciphertext
    }

    private companion object {
        fun ByteArray.hex(): String = joinToString(" ") { "%02x".format(it) }
    }
}

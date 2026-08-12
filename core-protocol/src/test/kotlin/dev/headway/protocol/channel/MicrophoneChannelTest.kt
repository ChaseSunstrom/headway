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
import aap_protobuf.service.media.source.message.MicrophoneRequestOuterClass.MicrophoneRequest
import aap_protobuf.service.media.source.message.MicrophoneResponseOuterClass.MicrophoneResponse
import dev.headway.protocol.framing.AapMessage
import dev.headway.protocol.framing.ChannelId
import dev.headway.protocol.io.Cryptor
import dev.headway.protocol.io.FramedConnection
import dev.headway.protocol.io.Transport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.EOFException

/**
 * Unit-level tests for the phone side of the car-microphone channel.
 *
 * Three things here cannot be caught by a round trip through
 * `EmulatedMicrophone`, because both ends of that round trip share this code:
 *
 * - the **bytes** of the microphone open and close requests, pinned against the
 *   sequences aa-proxy-rs extracted from a decompiled Gearhead;
 * - the **sample byte order**, pinned against a hand-written little-endian
 *   payload and explicitly asserted *not* to be the big-endian reading — a
 *   symmetric encoder/decoder pair would agree with each other while producing
 *   noise in a car;
 * - the **tolerance of aasdk's reply ids**, which by construction only one of
 *   the two possible peers exercises at a time.
 */
class MicrophoneChannelTest {

    // --- setup --------------------------------------------------------------

    @Test
    fun `the setup request names the PCM codec`() = harness { h ->
        h.peerSendConfig(Config.Status.STATUS_READY, maxUnacked = 1, indices = listOf(0))
        h.mic.open()

        val sent = h.peer.receive()
        assertEquals(AvMessageId.SETUP, sent.messageId)
        // Setup { type = MEDIA_CODEC_AUDIO_PCM }: field 1, varint 1.
        assertEquals("08 01", sent.payload.hex())
        assertEquals(ChannelId.MEDIA_SOURCE_MICROPHONE.id, sent.channelId)
        assertTrue(sent.encrypted, "every media message travels inside the TLS session")
        assertFalse(sent.control, "media messages are MessageType::SPECIFIC, not CONTROL")
    }

    @Test
    fun `the config response yields status, window and offered indices`() = harness { h ->
        h.peerSendConfig(Config.Status.STATUS_READY, maxUnacked = 1, indices = listOf(0))

        val response = h.mic.open()
        assertTrue(response.ready)
        assertEquals(1, response.maxUnacked)
        assertEquals(listOf(0), response.configurationIndices)
    }

    @Test
    fun `the config is accepted on 0x8000, the id aasdk actually sends it on`() = harness { h ->
        // aasdk/src/Channel/MediaSource/MediaSourceService.cpp L67 sends the
        // Config as MEDIA_MESSAGE_SETUP. openauto inherits that, so this is what
        // a Raspberry Pi head unit puts on the wire. Rejecting it would hang the
        // channel against the most widely deployed open head unit there is.
        h.peerSend(AvMessageId.SETUP, config(Config.Status.STATUS_READY, 1, listOf(0)))

        val response = h.mic.open()
        assertTrue(response.ready)
        assertEquals(1, response.maxUnacked)
    }

    @Test
    fun `an unstated window is reported as unstated, not as zero`() = harness { h ->
        h.peerSend(
            AvMessageId.CONFIG,
            Config.newBuilder()
                .setStatus(Config.Status.STATUS_READY)
                .addConfigurationIndices(0)
                .build()
                .toByteArray(),
        )

        assertNull(h.mic.open().maxUnacked)
    }

    // --- opening and closing the microphone ---------------------------------

    @Test
    fun `the open request is the byte sequence a real phone sends`() = harness { h ->
        h.peerSendCaptureResponse(status = 0, sessionId = -1)
        h.mic.startCapture()

        val sent = h.peer.receive()
        assertEquals(AvMessageId.MICROPHONE_REQUEST, sent.messageId)
        // aa-proxy-rs/src/bt_sco_media_bridge.rs L825-L836, from decompiled
        // Gearhead: open=true, anc=false, ec=false, field 4 = 2. The two false
        // fields are present, not elided.
        assertEquals("08 01 10 00 18 00 20 02", sent.payload.hex())
    }

    @Test
    fun `the close request carries only the open flag`() = harness { h ->
        h.mic.stopCapture()

        val sent = h.peer.receive()
        assertEquals(AvMessageId.MICROPHONE_REQUEST, sent.messageId)
        // Same source: the close request sets field 1 false and stops there.
        assertEquals("08 00", sent.payload.hex())
        assertFalse(MicrophoneRequest.parseFrom(sent.payload).open)
    }

    @Test
    fun `the microphone response is accepted on 0x8005, the id aasdk sends it on`() = harness { h ->
        // aasdk/src/Channel/MediaSource/MediaSourceService.cpp L105-L106 labels
        // the MicrophoneResponse with MEDIA_MESSAGE_MICROPHONE_REQUEST.
        h.peerSend(
            AvMessageId.MICROPHONE_REQUEST,
            MicrophoneResponse.newBuilder().setStatus(0).setSessionId(7).build().toByteArray(),
        )

        val response = h.mic.startCapture()
        assertTrue(response.ok)
        assertEquals(7, response.sessionId)
        assertTrue(response.opening)
        assertTrue(h.mic.capturing)
    }

    @Test
    fun `openauto's session id of minus one is reported, not treated as a failure`() = harness { h ->
        // openauto never assigns session_ (MediaSourceService.cpp L28 initialises
        // it to -1; L190/L208/L229 are the only reads), so every real openauto
        // head unit answers with -1.
        h.peerSendCaptureResponse(status = 0, sessionId = -1)

        val response = h.mic.startCapture()
        assertTrue(response.ok, "status 0 is STATUS_SUCCESS regardless of the session id")
        assertEquals(-1, response.sessionId)
        assertTrue(h.mic.capturing)
    }

    @Test
    fun `a failed open leaves the channel not capturing`() = harness { h ->
        // openauto answers STATUS_INTERNAL_ERROR (-7) when its audio input fails
        // to start (MediaSourceService.cpp L189-L192).
        h.peerSendCaptureResponse(status = -7, sessionId = -1)

        val response = h.mic.startCapture()
        assertFalse(response.ok)
        assertEquals(-7, response.status)
        assertFalse(h.mic.capturing)
    }

    // --- audio --------------------------------------------------------------

    @Test
    fun `PCM is decoded as little-endian signed 16-bit`() = harness { h ->
        h.peerSendCaptureResponse(status = 0, sessionId = 3)
        h.mic.startCapture()

        // Chosen so that the big-endian reading of the same bytes is a different
        // number for every sample.
        val pcm = byteArrayOf(
            0x01, 0x00, // 1
            0x00, 0x80.toByte(), // -32768, the most negative sample
            0xFF.toByte(), 0x7F, // 32767, the most positive
            0x00, 0xFF.toByte(), // -256
        )
        h.peerSendData(timestampMicros = 1_234_567L, pcm = pcm)

        val event = h.mic.receiveEvent()
        assertTrue(event is MicrophoneChannel.Event.AudioReceived, "got $event")
        val chunk = (event as MicrophoneChannel.Event.AudioReceived).chunk
        assertEquals(1_234_567L, chunk.timestampMicros)

        val littleEndian = shortArrayOf(1, -32768, 32767, -256)
        assertArrayEquals(littleEndian, chunk.samples)

        // The point of the fixture: a big-endian reader would produce audible,
        // plausible-looking, completely different audio.
        val bigEndian = shortArrayOf(256, 128, -129, 255)
        assertNotEquals(
            bigEndian.toList(),
            chunk.samples.toList(),
            "samples must not be read big-endian; openauto captures LittleEndian/SignedInt " +
                "(QtAudioInput.cpp L41-L42) and aa-proxy-rs decodes i16::from_le_bytes",
        )
    }

    @Test
    fun `every data message is acknowledged with the head unit's session id`() = harness { h ->
        h.peerSendCaptureResponse(status = 0, sessionId = 42)
        h.mic.startCapture()
        h.peer.receive() // the open request

        h.peerSendData(timestampMicros = 0L, pcm = ByteArray(640))
        h.mic.receiveEvent()

        val ack = h.peer.receive()
        assertEquals(AvMessageId.ACK, ack.messageId)
        val parsed = Ack.parseFrom(ack.payload)
        assertEquals(42, parsed.sessionId)
        assertEquals(1, parsed.ack)
        assertEquals(1L, h.mic.acksSent)
        assertEquals(320L, h.mic.framesReceived, "640 bytes of 16-bit mono is 320 frames")
    }

    @Test
    fun `an odd-length PCM payload is refused rather than resynchronised`() {
        // Dropping the stray byte would shift every subsequent sample by one
        // byte, which sounds like noise and reads like a decoder bug.
        val error = assertThrows(MicrophoneChannelException::class.java) {
            harness { h ->
                h.peerSendData(timestampMicros = 0L, pcm = byteArrayOf(0x01, 0x00, 0x02))
                h.mic.receiveEvent()
            }
        }
        assertTrue(error.message!!.contains("whole number of 16-bit samples"), error.message)
    }

    @Test
    fun `a data payload too short to hold a timestamp is refused`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            harness { h ->
                h.peerSend(AvMessageId.DATA, ByteArray(MediaFrame.TIMESTAMP_SIZE - 1))
                h.mic.receiveEvent()
            }
        }
        assertTrue(error.message!!.contains("timestamp header"), error.message)
    }

    // --- the flow -----------------------------------------------------------

    @Test
    fun `the pcm flow yields every chunk and completes when the head unit answers a close`() =
        harness { h ->
            h.peerSendCaptureResponse(status = 0, sessionId = 9)
            h.mic.startCapture()

            h.peerSendData(timestampMicros = 0L, pcm = byteArrayOf(0x01, 0x00))
            h.peerSendData(timestampMicros = 20_000L, pcm = byteArrayOf(0x02, 0x00))
            h.peerSendCaptureResponse(status = 0, sessionId = 9)

            // Send-only, so the collector below is the one that reads the reply.
            h.mic.stopCapture()

            val chunks = withTimeout(TIMEOUT_MILLIS) { h.mic.pcm().toList() }
            assertEquals(listOf(0L, 20_000L), chunks.map { it.timestampMicros })
            assertArrayEquals(shortArrayOf(1), chunks[0].samples)
            assertArrayEquals(shortArrayOf(2), chunks[1].samples)
            assertFalse(h.mic.capturing, "the close reply must clear the capture state")
        }

    // --- unknown and misaddressed messages ----------------------------------

    @Test
    fun `an unimplemented media message id is surfaced rather than failing the channel`() =
        harness { h ->
            // 0x800B AUDIO_UNDERFLOW_NOTIFICATION: a real id with no meaning
            // here. aasdk logs and re-arms (MediaSourceService.cpp L92-L95).
            h.peerSend(0x800B, byteArrayOf(0x08, 0x01))

            val event = h.mic.receiveEvent()
            assertTrue(event is MicrophoneChannel.Event.Unhandled, "got $event")
            assertEquals(0x800B, (event as MicrophoneChannel.Event.Unhandled).messageId)
        }

    @Test
    fun `a message addressed to another channel is refused`() {
        val error = assertThrows(MicrophoneChannelException::class.java) {
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
                h.mic.receiveEvent()
            }
        }
        assertTrue(error.message!!.contains("router"), error.message)
    }

    // --- format -------------------------------------------------------------

    @Test
    fun `the default format is the mono 16 kHz every reference expects`() {
        val format = MicrophoneFormat.CAR_MICROPHONE
        assertEquals(16_000, format.sampleRateHz)
        assertEquals(16, format.bitsPerSample)
        assertEquals(1, format.channels)
        assertEquals(2, format.bytesPerFrame)
        // 320 frames is the 20 ms the emulator chunks at.
        assertEquals(20_000L, format.durationMicros(320L))
    }

    // --- harness ------------------------------------------------------------

    private fun harness(block: suspend (Harness) -> Unit) = runBlocking {
        Harness().use { block(it) }
    }

    /**
     * A [MicrophoneChannel] wired to a peer that speaks raw messages.
     *
     * The peer is a plain [FramedConnection] rather than `EmulatedMicrophone`,
     * for the reason given in the class KDoc: these tests need to send sequences
     * and ids a correct head unit would not.
     */
    private class Harness : AutoCloseable {
        private val phoneToPeer = Channel<ByteArray>(Channel.UNLIMITED)
        private val peerToPhone = Channel<ByteArray>(Channel.UNLIMITED)

        private val phoneConnection = FramedConnection(ChannelTransport(peerToPhone, phoneToPeer))
            .apply { cryptor = PassThroughCryptor }
        val peer = FramedConnection(ChannelTransport(phoneToPeer, peerToPhone))
            .apply { cryptor = PassThroughCryptor }

        val mic = MicrophoneChannel(phoneConnection)

        suspend fun peerSend(messageId: Int, payload: ByteArray) {
            peer.send(
                AapMessage(
                    channelId = ChannelId.MEDIA_SOURCE_MICROPHONE.id,
                    control = false,
                    encrypted = true,
                    messageId = messageId,
                    payload = payload,
                )
            )
        }

        suspend fun peerSendConfig(status: Config.Status, maxUnacked: Int, indices: List<Int>) {
            peerSend(AvMessageId.CONFIG, config(status, maxUnacked, indices))
        }

        suspend fun peerSendCaptureResponse(status: Int, sessionId: Int) {
            peerSend(
                AvMessageId.MICROPHONE_RESPONSE,
                MicrophoneResponse.newBuilder()
                    .setStatus(status)
                    .setSessionId(sessionId)
                    .build()
                    .toByteArray(),
            )
        }

        /** Builds a `DATA` message the way a head unit does — timestamp header included. */
        suspend fun peerSendData(timestampMicros: Long, pcm: ByteArray) {
            peer.send(
                MediaFrame.data(
                    channelId = ChannelId.MEDIA_SOURCE_MICROPHONE.id,
                    media = pcm,
                    timestampMicros = timestampMicros,
                )
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
        const val TIMEOUT_MILLIS = 30_000L

        fun config(status: Config.Status, maxUnacked: Int, indices: List<Int>): ByteArray =
            Config.newBuilder()
                .setStatus(status)
                .setMaxUnacked(maxUnacked)
                .addAllConfigurationIndices(indices)
                .build()
                .toByteArray()

        fun ByteArray.hex(): String = joinToString(" ") { "%02x".format(it) }
    }
}

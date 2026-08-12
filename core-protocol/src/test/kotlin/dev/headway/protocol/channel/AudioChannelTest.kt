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

import aap_protobuf.service.ServiceOuterClass
import aap_protobuf.service.control.message.AudioFocusRequestTypeOuterClass.AudioFocusRequestType
import aap_protobuf.service.control.message.AudioFocusStateTypeOuterClass.AudioFocusStateType
import aap_protobuf.service.media.shared.message.AudioConfigurationOuterClass.AudioConfiguration
import aap_protobuf.service.media.shared.message.ConfigOuterClass.Config
import aap_protobuf.service.media.shared.message.MediaCodecTypeOuterClass.MediaCodecType
import aap_protobuf.service.media.sink.MediaSinkServiceOuterClass.MediaSinkService
import aap_protobuf.service.media.sink.message.AudioStreamTypeOuterClass.AudioStreamType
import aap_protobuf.service.media.sink.message.AudioUnderflowNotificationOuterClass.AudioUnderflowNotification
import aap_protobuf.service.media.source.message.AckOuterClass.Ack
import dev.headway.protocol.control.ControlMessageType
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.EOFException

/**
 * Unit-level tests for the phone side of an audio sink channel and the
 * control-channel focus exchange.
 *
 * These pin what Headway puts on the wire — the `08 01` PCM setup, the
 * timestamp header on a PCM buffer, the two-byte focus request — and the
 * behaviour a shared-code round trip cannot catch: that the sample format comes
 * from the head unit's advertisement rather than a constant, that the
 * presentation clock is derived from the negotiated rate, and that the A2DP
 * routing decision really does keep the media channel silent.
 */
class AudioChannelTest {

    // --- format -------------------------------------------------------------

    @Test
    fun `a format's frame size and duration follow from the advertised fields`() {
        // openauto's guidance output: mono, 16-bit, 16 kHz
        // (ServiceFactory.cpp L140-L144).
        assertEquals(2, GUIDANCE_FORMAT.bytesPerFrame)
        assertEquals(32_000, GUIDANCE_FORMAT.bytesPerSecond)
        assertEquals(10_000L, GUIDANCE_FORMAT.durationMicros(320))
        assertEquals(320, GUIDANCE_FORMAT.byteCountFor(10_000L))

        // openauto's media output: stereo, 16-bit, 48 kHz (ServiceFactory.cpp
        // L128-L131). One 20 ms buffer is 3840 B.
        assertEquals(4, MEDIA_FORMAT.bytesPerFrame)
        assertEquals(192_000, MEDIA_FORMAT.bytesPerSecond)
        assertEquals(3_840, MEDIA_FORMAT.byteCountFor(20_000L))
    }

    @Test
    fun `a format is read from the advertised AudioConfiguration, not assumed`() {
        // 44.1 kHz is nothing any reference constructs, which is the point: the
        // phone must carry whatever the car advertises.
        val odd = PcmFormat.from(
            AudioConfiguration.newBuilder()
                .setSamplingRate(44_100)
                .setNumberOfBits(16)
                .setNumberOfChannels(2)
                .build()
        )
        assertEquals(PcmFormat(44_100, 16, 2), odd)
        assertEquals(176_400, odd.bytesPerSecond)
    }

    @Test
    fun `a sample size that is not a whole number of bytes is refused`() {
        // Nothing frames 12-bit PCM, and silently rounding it would corrupt
        // every buffer's channel alignment.
        assertThrows(IllegalArgumentException::class.java) { PcmFormat(48_000, 12, 2) }
    }

    // --- setup / start ------------------------------------------------------

    @Test
    fun `the setup request asks for PCM in two bytes`() = harness { h ->
        h.audio.sendSetup()

        val sent = h.peer.receive()
        assertEquals(AvMessageId.SETUP, sent.messageId)
        // Setup { type = MEDIA_CODEC_AUDIO_PCM (1) }: field 1, varint 1.
        assertEquals("08 01", sent.payload.hex())
        assertEquals(ChannelId.MEDIA_SINK_GUIDANCE_AUDIO.id, sent.channelId)
        assertTrue(sent.encrypted, "every audio message travels inside the TLS session")
        assertFalse(sent.control, "audio messages are MessageType::SPECIFIC, not CONTROL")
    }

    @Test
    fun `the config response yields openauto's window and offered index`() = harness { h ->
        h.peerSendConfig(Config.Status.STATUS_READY, maxUnacked = 1, indices = listOf(0))

        val response = h.audio.awaitConfig()
        assertTrue(response.ready)
        assertEquals(1, response.maxUnacked)
        assertEquals(listOf(0), response.configurationIndices)
    }

    @Test
    fun `start carries session and index and adopts the format at that index`() = harness(
        formats = listOf(GUIDANCE_FORMAT, MEDIA_FORMAT),
    ) { h ->
        h.peerSendConfig(Config.Status.STATUS_READY, maxUnacked = 1, indices = listOf(0, 1))
        h.audio.awaitConfig()

        h.audio.sendStart(sessionId = 0, configurationIndex = 1)

        val sent = h.peer.receive()
        assertEquals(AvMessageId.START, sent.messageId)
        // Start { session_id = 0, configuration_index = 1 }.
        assertEquals("08 00 10 01", sent.payload.hex())
        assertEquals(MEDIA_FORMAT, h.audio.format, "the format must come from the advertised list")
        assertEquals(1, h.audio.configurationIndex)
        assertEquals(0, h.audio.sessionId)
    }

    @Test
    fun `a configuration index the head unit never offered is refused`() {
        val error = assertThrows(AudioChannelException::class.java) {
            harness { h ->
                h.peerSendConfig(Config.Status.STATUS_READY, maxUnacked = 1, indices = listOf(0))
                h.audio.awaitConfig()
                h.audio.sendStart(sessionId = 1, configurationIndex = 3)
            }
        }
        assertTrue(error.message!!.contains("not offered"), error.message)
    }

    @Test
    fun `an index with no matching AudioConfiguration is refused`() {
        // A head unit offering index 1 while advertising one config is
        // inconsistent; starting anyway would leave the channel with no sample
        // rate and a presentation clock stuck at zero.
        val error = assertThrows(AudioChannelException::class.java) {
            harness { h ->
                h.peerSendConfig(Config.Status.STATUS_READY, maxUnacked = 1, indices = listOf(0, 1))
                h.audio.awaitConfig()
                h.audio.sendStart(sessionId = 1, configurationIndex = 1)
            }
        }
        assertTrue(error.message!!.contains("no matching AudioConfiguration"), error.message)
    }

    @Test
    fun `stop carries the empty Stop message`() = harness { h ->
        h.start()
        h.audio.sendStop()

        val sent = h.peer.receive()
        assertEquals(AvMessageId.STOP, sent.messageId)
        assertEquals(0, sent.payload.size, "Stop has no fields")
        assertTrue(h.audio.stopped)
    }

    // --- media --------------------------------------------------------------

    @Test
    fun `a buffer is a big endian microsecond timestamp followed by the samples`() = harness { h ->
        h.start()
        val pcm = byteArrayOf(0x11, 0x22, 0x33, 0x44)

        h.audio.sendPcm(pcm, timestampMicros = 0x0102030405060708L)

        val sent = h.peer.receive()
        assertEquals(AvMessageId.DATA, sent.messageId)
        assertEquals("01 02 03 04 05 06 07 08 11 22 33 44", sent.payload.hex())
    }

    @Test
    fun `the presentation clock advances by each buffer's own duration`() = harness { h ->
        h.start()
        // 320 B of 16 kHz mono 16-bit is 160 frames, i.e. 10 ms.
        val buffer = ByteArray(320)

        assertEquals(0L, h.audio.nextTimestampMicros)
        h.audio.sendPcm(buffer)
        assertEquals(10_000L, h.audio.nextTimestampMicros)
        h.audio.sendPcm(buffer)
        assertEquals(20_000L, h.audio.nextTimestampMicros)

        assertEquals(0L, MediaFrame.parseData(h.peer.receive().payload).timestampMicros)
        assertEquals(10_000L, MediaFrame.parseData(h.peer.receive().payload).timestampMicros)
    }

    @Test
    fun `an explicit timestamp does not disturb the clock`() = harness { h ->
        h.start()
        val buffer = ByteArray(320)

        h.audio.sendPcm(buffer, timestampMicros = 5_000_000L)
        // The clock is the byte count, not the last timestamp used: the next
        // buffer follows the first one's audio, wherever it was placed.
        assertEquals(10_000L, h.audio.nextTimestampMicros)
    }

    @Test
    fun `a buffer that is not a whole number of frames is refused`() = harness(
        formats = listOf(MEDIA_FORMAT),
    ) { h ->
        h.start()
        // 6 B is one and a half stereo frames; sending it would swap the
        // channels for everything that follows.
        val error = assertThrows(AudioChannelException::class.java) {
            runBlocking { h.audio.sendPcm(ByteArray(6)) }
        }
        assertTrue(error.message!!.contains("whole number"), error.message)
    }

    @Test
    fun `PCM before start is refused because the format is not negotiated`() = harness { h ->
        val error = assertThrows(AudioChannelException::class.java) {
            runBlocking { h.audio.sendPcm(ByteArray(320)) }
        }
        assertTrue(error.message!!.contains("before Start"), error.message)
    }

    @Test
    fun `streamPcm splits the buffer on the requested duration`() = harness { h ->
        // No window advertised, so nothing gates the sends.
        h.peerSend(
            AvMessageId.CONFIG,
            Config.newBuilder()
                .setStatus(Config.Status.STATUS_READY)
                .addConfigurationIndices(0)
                .build()
                .toByteArray(),
        )
        h.audio.awaitConfig()
        h.audio.sendStart(sessionId = 7, configurationIndex = 0)
        h.peer.receive()

        // 25 ms of 16 kHz mono at a 10 ms buffer: 10 + 10 + 5.
        val sent = h.audio.streamPcm(ByteArray(800), bufferMicros = 10_000L)

        assertEquals(3, sent)
        val sizes = List(3) { MediaFrame.parseData(h.peer.receive().payload).media.size }
        assertEquals(listOf(320, 320, 160), sizes)
    }

    @Test
    fun `a full window blocks the next buffer until an acknowledgement arrives`() = runBlocking {
        Harness().use { h ->
            h.start(maxUnacked = 1)

            h.audio.sendPcm(ByteArray(320))
            assertEquals(1, h.audio.unacknowledgedMessages)

            val blocked = async { h.audio.sendPcm(ByteArray(320)) }
            repeat(20) { yield() }
            assertFalse(blocked.isCompleted, "the second buffer must wait for the ack")

            h.peerSendAck(sessionId = 0, ack = 1)
            withTimeout(5_000) { blocked.await() }
            assertEquals(1, h.audio.unacknowledgedMessages)
            assertEquals(1L, h.audio.acksReceived)
        }
    }

    @Test
    fun `an underflow notification is surfaced rather than silently dropped`() = harness { h ->
        // Nothing implements this id -- aasdk's audio channel does not name it
        // (AudioMediaSinkService.cpp L98-L121) -- but it is in the schema and it
        // is the only signal that would explain choppy playback.
        h.peerSend(
            AudioChannel.AUDIO_UNDERFLOW_NOTIFICATION,
            AudioUnderflowNotification.newBuilder().setSessionId(9).build().toByteArray(),
        )

        val event = h.audio.receiveEvent()
        assertTrue(event is AudioChannel.Event.Underflow, "got $event")
        assertEquals(9, (event as AudioChannel.Event.Underflow).sessionId)
    }

    @Test
    fun `a message addressed to another channel is refused`() {
        val error = assertThrows(AudioChannelException::class.java) {
            harness { h ->
                h.peer.send(
                    AapMessage(
                        channelId = ChannelId.MEDIA_SINK_VIDEO.id,
                        control = false,
                        encrypted = true,
                        messageId = AvMessageId.ACK,
                        payload = byteArrayOf(0x08, 0x00),
                    )
                )
                h.audio.receiveEvent()
            }
        }
        assertTrue(error.message!!.contains("router"), error.message)
    }

    // --- routing ------------------------------------------------------------

    @Test
    fun `media audio routed over A2DP puts nothing on the wire`() = harness(
        channelId = ChannelId.MEDIA_SINK_MEDIA_AUDIO.id,
        stream = AudioStreamType.AUDIO_STREAM_MEDIA,
        formats = listOf(MEDIA_FORMAT),
        route = MediaAudioRoute.BLUETOOTH_A2DP,
    ) { h ->
        assertFalse(h.audio.transmits)

        // The lifecycle is refused outright: a channel Headway has decided not
        // to feed must not be set up, or the car waits for audio forever.
        val error = assertThrows(AudioChannelException::class.java) {
            runBlocking { h.audio.sendSetup() }
        }
        assertTrue(error.message!!.contains("BLUETOOTH_A2DP"), error.message)

        // A buffer offered anyway is dropped, not thrown on: the media pipeline
        // keeps producing while the car plays the same audio over Bluetooth.
        assertFalse(h.audio.sendPcm(ByteArray(4_000)))
        assertEquals(0, h.audio.streamPcm(ByteArray(8_000)))
        assertEquals(12_000L, h.audio.bytesSuppressed)
        assertEquals(0L, h.audio.bytesSent)
        assertEquals(0L, h.audio.mediaMessagesSent)
        // Counted at the transport, below framing: not "the peer read nothing
        // within a timeout" but "no byte was ever written".
        assertEquals(0L, h.phoneBytesWritten, "nothing at all may reach the wire")
    }

    @Test
    fun `the settings toggle moves the same media channel onto AAP`() = harness(
        channelId = ChannelId.MEDIA_SINK_MEDIA_AUDIO.id,
        stream = AudioStreamType.AUDIO_STREAM_MEDIA,
        formats = listOf(MEDIA_FORMAT),
        route = MediaAudioRoute.AAP_MEDIA_CHANNEL,
    ) { h ->
        assertTrue(h.audio.transmits)
        h.start()

        assertTrue(h.audio.sendPcm(ByteArray(4_000)))
        assertEquals(4_000L, h.audio.bytesSent)
        assertEquals(0L, h.audio.bytesSuppressed)
        assertEquals(AvMessageId.DATA, h.peer.receive().messageId)
    }

    @Test
    fun `guidance audio is never suppressed by the media route`() = harness(
        route = MediaAudioRoute.BLUETOOTH_A2DP,
    ) { h ->
        // A2DP carries one stream and the music is on it; Headway's own spoken
        // output has nowhere else to go.
        assertTrue(h.audio.transmits)
        h.start()
        assertTrue(h.audio.sendPcm(ByteArray(320)))
    }

    // --- building from the advertisement ------------------------------------

    @Test
    fun `fromService takes channel id, stream type and formats off the wire`() = harness { h ->
        val service = ServiceOuterClass.Service.newBuilder()
            .setId(5)
            .setMediaSinkService(
                MediaSinkService.newBuilder()
                    .setAvailableType(MediaCodecType.MEDIA_CODEC_AUDIO_PCM)
                    .setAudioType(AudioStreamType.AUDIO_STREAM_GUIDANCE)
                    .addAudioConfigs(
                        AudioConfiguration.newBuilder()
                            .setSamplingRate(16_000)
                            .setNumberOfBits(16)
                            .setNumberOfChannels(1)
                    )
            )
            .build()

        val channel = AudioChannel.fromService(h.phoneConnection, service)

        assertEquals(5, channel.channelId)
        assertEquals(AudioStreamType.AUDIO_STREAM_GUIDANCE, channel.stream)
        assertEquals(listOf(GUIDANCE_FORMAT), channel.advertisedFormats)
    }

    @Test
    fun `a sink advertising no AudioConfiguration is refused`() = harness { h ->
        val service = ServiceOuterClass.Service.newBuilder()
            .setId(6)
            .setMediaSinkService(
                MediaSinkService.newBuilder()
                    .setAvailableType(MediaCodecType.MEDIA_CODEC_AUDIO_PCM)
                    .setAudioType(AudioStreamType.AUDIO_STREAM_SYSTEM_AUDIO)
            )
            .build()

        val error = assertThrows(AudioChannelException::class.java) {
            AudioChannel.fromService(h.phoneConnection, service)
        }
        assertTrue(error.message!!.contains("no AudioConfiguration"), error.message)
    }

    // --- focus --------------------------------------------------------------

    @Test
    fun `a focus request is two bytes on the control channel`() = harness { h ->
        h.focus.requestTransient(mayDuck = true)

        val sent = h.peer.receive()
        assertEquals(ChannelId.CONTROL.id, sent.channelId)
        assertEquals(ControlMessageType.AUDIO_FOCUS_REQUEST.id, sent.messageId)
        // AudioFocusRequest { audio_focus_type = GAIN_TRANSIENT_MAY_DUCK (3) }.
        assertEquals("08 03", sent.payload.hex())
        assertTrue(sent.encrypted)
        assertFalse(sent.control, "aasdk builds focus messages as SPECIFIC, not CONTROL")
    }

    @Test
    fun `release is request type four`() = harness { h ->
        h.focus.release()
        assertEquals("08 04", h.peer.receive().payload.hex())
        assertEquals(listOf(AudioFocusRequestType.AUDIO_FOCUS_RELEASE), h.focus.requests)
    }

    @Test
    fun `a notification updates the focus state and its history`() = harness { h ->
        h.peerSendFocus(AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_TRANSIENT)
        h.peerSendFocus(AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS)

        assertEquals(
            AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_TRANSIENT,
            h.focus.awaitNotification().state,
        )
        assertEquals(AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS, h.focus.awaitNotification().state)
        assertEquals(
            listOf(
                AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_TRANSIENT,
                AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS,
            ),
            h.focus.notifications.map { it.state },
        )
        assertFalse(h.focus.state!!.holdsFocus)
    }

    @Test
    fun `the unsolicited flag is only written when it is set`() {
        // openauto builds its notification with focus_state alone
        // (AndroidAutoEntity.cpp L245-L246); emitting an explicit false would
        // put two bytes on the wire no reference emits.
        assertEquals(
            "08 01",
            AudioFocus.notificationMessage(AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN).payload.hex(),
        )
        assertEquals(
            "08 01 10 01",
            AudioFocus.notificationMessage(
                AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN,
                unsolicited = true,
            ).payload.hex(),
        )
    }

    @Test
    fun `an unsolicited loss is readable as one`() = harness { h ->
        h.peerSendFocus(AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS, unsolicited = true)

        val state = h.focus.awaitNotification()
        assertTrue(state.unsolicited)
        assertTrue(state.mustStop)
    }

    @Test
    fun `the gain states say which stream they cover`() {
        val gain = AudioFocusState(AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN)
        assertTrue(gain.holdsFocus)
        assertFalse(gain.transient)
        assertTrue(gain.allows(AudioStreamType.AUDIO_STREAM_MEDIA))
        assertTrue(gain.allows(AudioStreamType.AUDIO_STREAM_GUIDANCE))

        val transient = AudioFocusState(AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_TRANSIENT)
        assertTrue(transient.holdsFocus)
        assertTrue(transient.transient)

        val mediaOnly = AudioFocusState(AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_MEDIA_ONLY)
        assertTrue(mediaOnly.allows(AudioStreamType.AUDIO_STREAM_MEDIA))
        assertFalse(mediaOnly.allows(AudioStreamType.AUDIO_STREAM_GUIDANCE))

        val guidanceOnly =
            AudioFocusState(AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_TRANSIENT_GUIDANCE_ONLY)
        assertTrue(guidanceOnly.allows(AudioStreamType.AUDIO_STREAM_GUIDANCE))
        assertFalse(guidanceOnly.allows(AudioStreamType.AUDIO_STREAM_MEDIA))
        assertFalse(
            guidanceOnly.allows(AudioStreamType.AUDIO_STREAM_SYSTEM_AUDIO),
            "the conservative reading: a guidance-only grant names guidance and nothing else",
        )
    }

    @Test
    fun `the loss states separate ducking from stopping`() {
        val duck = AudioFocusState(AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS_TRANSIENT_CAN_DUCK)
        assertTrue(duck.mustDuck)
        assertFalse(duck.mustPause)
        assertTrue(
            duck.allows(AudioStreamType.AUDIO_STREAM_GUIDANCE),
            "ducking means keep sending, quieter -- not stop",
        )

        val pause = AudioFocusState(AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS_TRANSIENT)
        assertTrue(pause.mustPause)
        assertFalse(pause.allows(AudioStreamType.AUDIO_STREAM_GUIDANCE))

        val loss = AudioFocusState(AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS)
        assertTrue(loss.mustStop)
        assertFalse(loss.holdsFocus)

        // A head unit that sends INVALID is not granting anything.
        assertTrue(AudioFocusState(AudioFocusStateType.AUDIO_FOCUS_STATE_INVALID).mustStop)
    }

    @Test
    fun `openauto's answer collapses every gain flavour into GAIN`() {
        // AndroidAutoEntity.cpp L236-L240. A phone that assumed the state
        // mirrored its request would misread this unit completely.
        for (type in listOf(
            AudioFocusRequestType.AUDIO_FOCUS_GAIN,
            AudioFocusRequestType.AUDIO_FOCUS_GAIN_TRANSIENT,
            AudioFocusRequestType.AUDIO_FOCUS_GAIN_TRANSIENT_MAY_DUCK,
        )) {
            assertEquals(
                AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN,
                AudioFocus.openautoResponseTo(type),
            )
        }
        assertEquals(
            AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS,
            AudioFocus.openautoResponseTo(AudioFocusRequestType.AUDIO_FOCUS_RELEASE),
        )
    }

    @Test
    fun `a message for another channel is handed back rather than swallowed`() = harness { h ->
        // The control channel is where a session's shared traffic lives, so the
        // thing polling for focus is the most likely to see someone else's
        // message. Dropping it would lose an audio ack.
        h.peerSend(AvMessageId.ACK, Ack.newBuilder().setSessionId(0).setAck(1).build().toByteArray())

        val event = h.focus.receiveEvent()
        assertTrue(event is AudioFocus.Event.Foreign, "got $event")
        assertEquals(
            ChannelId.MEDIA_SINK_GUIDANCE_AUDIO.id,
            (event as AudioFocus.Event.Foreign).message.channelId,
        )
    }

    @Test
    fun `an unimplemented control message id is surfaced, not fatal`() = harness { h ->
        h.peerSendControl(ControlMessageType.PING_REQUEST.id, byteArrayOf(0x08, 0x2A))

        val event = h.focus.receiveEvent()
        assertTrue(event is AudioFocus.Event.Unhandled, "got $event")
        assertEquals(
            ControlMessageType.PING_REQUEST.id,
            (event as AudioFocus.Event.Unhandled).messageId,
        )
    }

    // --- harness ------------------------------------------------------------

    private fun harness(
        channelId: Int = ChannelId.MEDIA_SINK_GUIDANCE_AUDIO.id,
        stream: AudioStreamType = AudioStreamType.AUDIO_STREAM_GUIDANCE,
        formats: List<PcmFormat> = listOf(GUIDANCE_FORMAT),
        route: MediaAudioRoute = MediaAudioRoute.BLUETOOTH_A2DP,
        block: suspend (Harness) -> Unit,
    ) = runBlocking {
        Harness(channelId, stream, formats, route).use { block(it) }
    }

    /**
     * An [AudioChannel] and an [AudioFocus] over one connection, wired to a peer
     * that speaks raw messages.
     *
     * The peer is a plain [FramedConnection] rather than the emulated sink:
     * these tests are about what one side puts on the wire, so the other side
     * has to be able to send anything, including sequences no head unit would.
     */
    private class Harness(
        channelId: Int = ChannelId.MEDIA_SINK_GUIDANCE_AUDIO.id,
        private val stream: AudioStreamType = AudioStreamType.AUDIO_STREAM_GUIDANCE,
        formats: List<PcmFormat> = listOf(GUIDANCE_FORMAT),
        route: MediaAudioRoute = MediaAudioRoute.BLUETOOTH_A2DP,
    ) : AutoCloseable {
        private val phoneToPeer = Channel<ByteArray>(Channel.UNLIMITED)
        private val peerToPhone = Channel<ByteArray>(Channel.UNLIMITED)

        private val phoneTransport = ChannelTransport(peerToPhone, phoneToPeer)

        val phoneConnection = FramedConnection(phoneTransport).apply { cryptor = PassThroughCryptor }
        val peer = FramedConnection(ChannelTransport(phoneToPeer, peerToPhone))
            .apply { cryptor = PassThroughCryptor }

        /** Bytes the phone has put on the wire, headers included. */
        val phoneBytesWritten: Long get() = phoneTransport.bytesWritten

        private val audioChannelId = channelId

        val audio = AudioChannel(
            connection = phoneConnection,
            channelId = channelId,
            stream = stream,
            advertisedFormats = formats,
            route = route,
        )
        val focus = AudioFocus(phoneConnection)

        /**
         * Setup through Start, consuming what the phone sent so the peer's queue
         * is clean.
         *
         * The default window of 0 is [AudioChannel]'s "no window" reading, which
         * keeps tests that are not about flow control from having to answer
         * every buffer with an ack.
         */
        suspend fun start(sessionId: Int = 0, maxUnacked: Int = 0, index: Int = 0) {
            audio.sendSetup()
            peer.receive()
            peerSendConfig(Config.Status.STATUS_READY, maxUnacked, listOf(index))
            audio.awaitConfig()
            audio.sendStart(sessionId = sessionId, configurationIndex = index)
            peer.receive()
        }

        suspend fun peerSend(messageId: Int, payload: ByteArray) {
            peer.send(
                AapMessage(
                    channelId = audioChannelId,
                    control = false,
                    encrypted = true,
                    messageId = messageId,
                    payload = payload,
                )
            )
        }

        suspend fun peerSendControl(messageId: Int, payload: ByteArray) {
            peer.send(
                AapMessage(
                    channelId = ChannelId.CONTROL.id,
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

        suspend fun peerSendFocus(state: AudioFocusStateType, unsolicited: Boolean = false) {
            peer.send(AudioFocus.notificationMessage(state, unsolicited))
        }

        override fun close() {
            phoneToPeer.close()
            peerToPhone.close()
        }
    }

    /** See [dev.headway.protocol.channel.VideoChannelTest]'s note: `core-protocol` cannot
     * depend on `core-transport`'s loopback, the dependency runs the other way. */
    private class ChannelTransport(
        private val incoming: Channel<ByteArray>,
        private val outgoing: Channel<ByteArray>,
    ) : Transport {
        private var pending = ByteArray(0)

        /** Total bytes handed to [write], headers included. */
        @Volatile
        var bytesWritten: Long = 0
            private set

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
            bytesWritten += length
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
        /** openauto's guidance/system output: mono 16-bit 16 kHz (`ServiceFactory.cpp` L140-L144). */
        val GUIDANCE_FORMAT = PcmFormat(16_000, 16, 1)

        /** openauto's media output: stereo 16-bit 48 kHz (`ServiceFactory.cpp` L128-L131). */
        val MEDIA_FORMAT = PcmFormat(48_000, 16, 2)

        fun ByteArray.hex(): String = joinToString(" ") { "%02x".format(it) }
    }
}

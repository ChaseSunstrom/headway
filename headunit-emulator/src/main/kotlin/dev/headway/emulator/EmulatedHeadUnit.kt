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

package dev.headway.emulator

import aap_protobuf.service.ServiceOuterClass
import aap_protobuf.service.control.message.ChannelOpenRequestOuterClass.ChannelOpenRequest
import aap_protobuf.service.control.message.ChannelOpenResponseOuterClass.ChannelOpenResponse
import aap_protobuf.service.control.message.ServiceDiscoveryResponseOuterClass.ServiceDiscoveryResponse
import aap_protobuf.service.inputsource.InputSourceServiceOuterClass.InputSourceService
import aap_protobuf.service.media.shared.message.AudioConfigurationOuterClass.AudioConfiguration
import aap_protobuf.service.media.shared.message.MediaCodecTypeOuterClass.MediaCodecType
import aap_protobuf.service.media.sink.MediaSinkServiceOuterClass.MediaSinkService
import aap_protobuf.service.media.sink.message.AudioStreamTypeOuterClass.AudioStreamType
import aap_protobuf.service.media.sink.message.VideoCodecResolutionTypeOuterClass.VideoCodecResolutionType
import aap_protobuf.service.media.sink.message.VideoConfigurationOuterClass.VideoConfiguration
import aap_protobuf.service.media.sink.message.VideoFrameRateTypeOuterClass.VideoFrameRateType
import aap_protobuf.shared.MessageStatusOuterClass.MessageStatus
import dev.headway.protocol.control.ControlMessageType
import dev.headway.protocol.control.VersionHandshake
import dev.headway.protocol.framing.AapMessage
import dev.headway.protocol.framing.ChannelId
import dev.headway.protocol.io.FramedConnection
import dev.headway.protocol.session.TlsHandshakeDriver

/**
 * What the emulated head unit claims to be.
 *
 * Defaults describe a Chevrolet Infotainment 3-class unit — the 2021 Malibu
 * target — but nothing here is Malibu-specific in the protocol sense. Per
 * CLAUDE.md the head unit is treated as a generic wireless AAP unit and quirks
 * are configuration, so every field is a parameter.
 */
data class HeadUnitConfig(
    val displayName: String = "Headway Emulator",
    val version: VersionHandshake.Version =
        VersionHandshake.Version(VersionHandshake.MAJOR, VersionHandshake.MINOR),
    /**
     * 800x480, the resolution enum value a Malibu-class unit advertises.
     * `VideoCodecResolutionType.VIDEO_800x480` — the enum is the authority on
     * the pixel dimensions, which is why the resolution is carried as an enum
     * rather than a width/height pair.
     */
    val videoResolution: VideoCodecResolutionType = VideoCodecResolutionType.VIDEO_800x480,
    val frameRate: VideoFrameRateType = VideoFrameRateType.VIDEO_FPS_30,
    val density: Int = 140,
    val touchWidth: Int = 800,
    val touchHeight: Int = 480,
    /** Speech/system audio is mono 16 kHz; media audio is stereo 48 kHz. */
    val mediaAudioSampleRate: Int = 48_000,
    val speechAudioSampleRate: Int = 16_000,
    val microphoneSampleRate: Int = 16_000,
) {
    /** Channels this unit advertises, in advertisement order. */
    val advertisedChannels: List<ChannelId> = listOf(
        ChannelId.MEDIA_SINK_VIDEO,
        ChannelId.MEDIA_SINK_MEDIA_AUDIO,
        ChannelId.MEDIA_SINK_SYSTEM_AUDIO,
        ChannelId.MEDIA_SINK_GUIDANCE_AUDIO,
        ChannelId.INPUT_SOURCE,
        ChannelId.MEDIA_SOURCE_MICROPHONE,
    )
}

/**
 * A head unit that speaks AAP — the Phase 0 harness that every later phase tests
 * against.
 *
 * Mirrors [dev.headway.protocol.session.AapSession] from the other side: it
 * initiates the version exchange, acts as the TLS **client**, sends
 * `AuthComplete`, advertises its services in response to the phone's discovery
 * request, and accepts channel opens.
 *
 * Per ADR 0002 this shares `core-protocol` with the phone. That makes it a weak
 * oracle — a wrong-but-symmetric constant round-trips cleanly here and still
 * fails in a car — so the byte fixtures in `core-protocol`'s tests, not a green
 * run of this, are what pin the wire format.
 */
class EmulatedHeadUnit(
    private val connection: FramedConnection,
    private val tls: TlsHandshakeDriver,
    val config: HeadUnitConfig = HeadUnitConfig(),
    private val onStep: (String) -> Unit = {},
) {
    var phoneVersion: VersionHandshake.Version? = null
        private set

    var phoneDeviceName: String? = null
        private set

    private val opened = mutableListOf<Int>()
    val openedChannelIds: List<Int> get() = opened.toList()

    /**
     * Runs the head-unit side of bring-up until [channelOpens] channels have been
     * opened, then returns.
     *
     * @param channelOpens how many `ChannelOpenRequest`s to service. The phone
     *   decides how many channels it wants, so the caller states the expectation
     *   rather than the emulator guessing when to stop reading.
     */
    suspend fun run(channelOpens: Int = config.advertisedChannels.size) {
        sendVersionRequest()
        runTlsHandshake()
        sendAuthComplete()
        answerServiceDiscovery()
        repeat(channelOpens) { answerChannelOpen() }
        onStep("head unit ready; ${opened.size} channel(s) open")
    }

    // --- version ------------------------------------------------------------

    private suspend fun sendVersionRequest() {
        // The head unit speaks first.
        connection.send(VersionHandshake.request(config.version.major, config.version.minor))
        val reply = expect(ControlMessageType.VERSION_RESPONSE)
        val response = VersionHandshake.parseResponse(reply.payload)
        phoneVersion = response.version
        if (!response.compatible) {
            throw IllegalStateException("phone refused version ${config.version}: status ${response.status}")
        }
        onStep("phone announced AAP ${response.version}")
    }

    // --- TLS ----------------------------------------------------------------

    private suspend fun runTlsHandshake() {
        // The head unit is the TLS client, so it produces the ClientHello.
        var outgoing = tls.beginHandshake()
        var flights = 0
        while (true) {
            if (outgoing.isNotEmpty()) {
                connection.send(encapsulatedSsl(outgoing))
            }
            if (tls.handshakeComplete) break
            if (++flights > MAX_TLS_FLIGHTS) {
                throw IllegalStateException("TLS handshake did not converge")
            }
            val incoming = expect(ControlMessageType.ENCAPSULATED_SSL)
            outgoing = tls.continueHandshake(incoming.payload)
        }
        connection.cryptor = tls
        onStep("TLS established")
    }

    private fun encapsulatedSsl(payload: ByteArray) = AapMessage(
        channelId = ChannelId.CONTROL.id,
        control = false,
        encrypted = false,
        messageId = ControlMessageType.ENCAPSULATED_SSL.id,
        payload = payload,
    )

    // --- auth ---------------------------------------------------------------

    private suspend fun sendAuthComplete() {
        // AuthResponse { required int32 status = 1 } with STATUS_SUCCESS -- the
        // 08 00 that AACS shows on the wire.
        connection.send(
            AapMessage(
                channelId = ChannelId.CONTROL.id,
                control = false,
                encrypted = false,
                messageId = ControlMessageType.AUTH_COMPLETE.id,
                payload = byteArrayOf(0x08, 0x00),
            )
        )
        onStep("sent AuthComplete")
    }

    // --- service discovery --------------------------------------------------

    private suspend fun answerServiceDiscovery() {
        val request = expect(ControlMessageType.SERVICE_DISCOVERY_REQUEST)
        val parsed =
            aap_protobuf.service.control.message.ServiceDiscoveryRequestOuterClass
                .ServiceDiscoveryRequest.parseFrom(request.payload)
        phoneDeviceName = if (parsed.hasDeviceName()) parsed.deviceName else null
        onStep("discovery request from '${phoneDeviceName ?: "unnamed"}'")

        connection.send(
            AapMessage(
                channelId = ChannelId.CONTROL.id,
                control = false,
                encrypted = true,
                messageId = ControlMessageType.SERVICE_DISCOVERY_RESPONSE.id,
                payload = buildServiceDiscoveryResponse().toByteArray(),
            )
        )
    }

    /**
     * Builds the advertisement.
     *
     * The head unit is a collection of sinks and sources: a video sink (its
     * screen), audio sinks (its speakers), an input source (its touch panel) and
     * a media source (its microphone). Each becomes one `Service` whose `id` is
     * the channel number the phone will address.
     */
    fun buildServiceDiscoveryResponse(): ServiceDiscoveryResponse {
        val builder = ServiceDiscoveryResponse.newBuilder()
            .setDisplayName(config.displayName)

        for (channel in config.advertisedChannels) {
            val service = ServiceOuterClass.Service.newBuilder().setId(channel.id)
            when (channel) {
                ChannelId.MEDIA_SINK_VIDEO -> service.setMediaSinkService(
                    MediaSinkService.newBuilder()
                        .setAvailableType(MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP)
                        .addVideoConfigs(
                            VideoConfiguration.newBuilder()
                                .setCodecResolution(config.videoResolution)
                                .setFrameRate(config.frameRate)
                                .setDensity(config.density)
                                .setWidthMargin(0)
                                .setHeightMargin(0)
                        )
                )

                ChannelId.MEDIA_SINK_MEDIA_AUDIO -> service.setMediaSinkService(
                    audioSink(AudioStreamType.AUDIO_STREAM_MEDIA, config.mediaAudioSampleRate, channels = 2)
                )

                ChannelId.MEDIA_SINK_SYSTEM_AUDIO -> service.setMediaSinkService(
                    audioSink(AudioStreamType.AUDIO_STREAM_SYSTEM_AUDIO, config.speechAudioSampleRate, channels = 1)
                )

                ChannelId.MEDIA_SINK_GUIDANCE_AUDIO -> service.setMediaSinkService(
                    audioSink(AudioStreamType.AUDIO_STREAM_GUIDANCE, config.speechAudioSampleRate, channels = 1)
                )

                ChannelId.INPUT_SOURCE -> service.setInputSourceService(
                    InputSourceService.newBuilder()
                        .addTouchscreen(
                            InputSourceService.TouchScreen.newBuilder()
                                .setWidth(config.touchWidth)
                                .setHeight(config.touchHeight)
                        )
                        .addAllKeycodesSupported(SUPPORTED_KEYCODES)
                )

                ChannelId.MEDIA_SOURCE_MICROPHONE -> service.setMediaSourceService(
                    aap_protobuf.service.media.source.MediaSourceServiceOuterClass.MediaSourceService
                        .newBuilder()
                        .setAvailableType(MediaCodecType.MEDIA_CODEC_AUDIO_PCM)
                        .setAudioConfig(
                            AudioConfiguration.newBuilder()
                                .setSamplingRate(config.microphoneSampleRate)
                                .setNumberOfBits(16)
                                .setNumberOfChannels(1)
                        )
                )

                else -> Unit
            }
            builder.addChannels(service)
        }
        return builder.build()
    }

    private fun audioSink(type: AudioStreamType, sampleRate: Int, channels: Int) =
        MediaSinkService.newBuilder()
            .setAvailableType(MediaCodecType.MEDIA_CODEC_AUDIO_PCM)
            .setAudioType(type)
            .addAudioConfigs(
                AudioConfiguration.newBuilder()
                    .setSamplingRate(sampleRate)
                    .setNumberOfBits(16)
                    .setNumberOfChannels(channels)
            )

    // --- channel open -------------------------------------------------------

    private suspend fun answerChannelOpen() {
        val message = connection.receive()
        check(message.messageId == ControlMessageType.CHANNEL_OPEN_REQUEST.id) {
            "expected CHANNEL_OPEN_REQUEST, got ${ControlMessageType.describe(message.messageId)}"
        }
        val request = ChannelOpenRequest.parseFrom(message.payload)

        // Refuse a channel we never advertised, the way a real unit would --
        // otherwise a phone bug looks like success here and fails in a car.
        val known = config.advertisedChannels.any { it.id == request.serviceId }
        val status = if (known) MessageStatus.STATUS_SUCCESS else MessageStatus.STATUS_INVALID_SERVICE
        if (known) opened += request.serviceId

        connection.send(
            AapMessage(
                channelId = message.channelId,
                control = false,
                encrypted = true,
                messageId = ControlMessageType.CHANNEL_OPEN_RESPONSE.id,
                payload = ChannelOpenResponse.newBuilder().setStatus(status).build().toByteArray(),
            )
        )
        onStep("channel ${ChannelId.describe(request.serviceId)} -> $status")
    }

    // --- helpers ------------------------------------------------------------

    private suspend fun expect(type: ControlMessageType): AapMessage {
        val message = connection.receive()
        check(message.messageId == type.id) {
            "expected ${type.name}, got ${ControlMessageType.describe(message.messageId)}"
        }
        return message
    }

    /**
     * Sends one control-channel keepalive and asserts the phone answers it.
     *
     * A real head unit pings for the life of the session and drops it when the
     * pongs stop — openauto's `AndroidAutoEntity` runs exactly this loop. The
     * emulator did not, so Headway's complete absence of a `PING_RESPONSE`
     * handler was invisible to every acceptance test: a session that came up in
     * CI would have died seconds into a real drive, and nothing here would have
     * noticed.
     *
     * @return the round-trip the phone reported, in the units the ping carried.
     */
    suspend fun ping(timestamp: Long = 1): Long {
        connection.send(
            AapMessage(
                channelId = ChannelId.CONTROL.id,
                control = false,
                encrypted = true,
                messageId = ControlMessageType.PING_REQUEST.id,
                payload = aap_protobuf.service.control.message.PingRequestOuterClass.PingRequest
                    .newBuilder().setTimestamp(timestamp).build().toByteArray(),
            )
        )
        val reply = expect(ControlMessageType.PING_RESPONSE)
        val pong = aap_protobuf.service.control.message.PingResponseOuterClass.PingResponse
            .parseFrom(reply.payload)
        onStep("phone answered the keepalive with timestamp ${pong.timestamp}")
        return pong.timestamp
    }

    companion object {
        const val MAX_TLS_FLIGHTS = 10

        /**
         * Steering-wheel and media keys a typical unit reports. Values are
         * `KeyCode` enum numbers from
         * `aap_protobuf/service/media/sink/message/KeyCode.proto`.
         */
        val SUPPORTED_KEYCODES: List<Int> = listOf(
            84,  // SEARCH / voice
            85,  // MEDIA_PLAY_PAUSE
            87,  // MEDIA_NEXT
            88,  // MEDIA_PREVIOUS
            126, // MEDIA_PLAY
            127, // MEDIA_PAUSE
        )
    }
}

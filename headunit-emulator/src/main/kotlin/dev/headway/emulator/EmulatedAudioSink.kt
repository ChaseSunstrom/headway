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
import aap_protobuf.service.control.message.AudioFocusRequestTypeOuterClass.AudioFocusRequestType
import aap_protobuf.service.control.message.AudioFocusStateTypeOuterClass.AudioFocusStateType
import aap_protobuf.service.media.shared.message.ConfigOuterClass.Config
import aap_protobuf.service.media.shared.message.MediaCodecTypeOuterClass.MediaCodecType
import aap_protobuf.service.media.shared.message.SetupOuterClass.Setup
import aap_protobuf.service.media.shared.message.StartOuterClass.Start
import aap_protobuf.service.media.sink.message.AudioStreamTypeOuterClass.AudioStreamType
import aap_protobuf.service.media.source.message.AckOuterClass.Ack
import dev.headway.protocol.channel.AudioChannel
import dev.headway.protocol.channel.AudioFocus
import dev.headway.protocol.channel.AvMessageId
import dev.headway.protocol.channel.MediaFrame
import dev.headway.protocol.channel.PcmFormat
import dev.headway.protocol.control.ControlMessageType
import dev.headway.protocol.framing.AapMessage
import dev.headway.protocol.framing.ChannelId
import dev.headway.protocol.io.FramedConnection

/**
 * How the emulated unit answers an audio focus request.
 *
 * The head unit is the arbiter here — it decides whether the phone gets the
 * speakers — so this is the emulator's most consequential policy knob.
 */
enum class AudioFocusPolicy {
    /**
     * What openauto does: `RELEASE` is answered with `LOSS`, every other request
     * with `GAIN`, regardless of which flavour of gain was asked for
     * (`openauto/src/autoapp/Service/AndroidAutoEntity.cpp` L236-L246).
     *
     * The default, because it is the only head-unit policy any reference
     * implements. A phone that only works against a unit which distinguishes
     * transient grants is a phone that will misbehave in an openauto car.
     */
    OPENAUTO,

    /**
     * Answers a transient request with a transient grant:
     * `GAIN_TRANSIENT` and `GAIN_TRANSIENT_MAY_DUCK` become
     * `AUDIO_FOCUS_STATE_GAIN_TRANSIENT`, `GAIN` becomes
     * `AUDIO_FOCUS_STATE_GAIN`, `RELEASE` becomes `AUDIO_FOCUS_STATE_LOSS`.
     *
     * **Not observed anywhere.** No reference implements it and no capture in
     * this repository shows a real unit doing it. It exists so a test can drive
     * the phone's transient-grant handling at all — under [OPENAUTO] that code
     * path is unreachable, and untestable code is where bugs live. Treat a test
     * that only passes under this policy as evidence about Headway, not about
     * cars.
     */
    TRANSIENT_AWARE,
    ;

    fun responseTo(request: AudioFocusRequestType): AudioFocusStateType = when (this) {
        OPENAUTO -> AudioFocus.openautoResponseTo(request)
        TRANSIENT_AWARE -> when (request) {
            AudioFocusRequestType.AUDIO_FOCUS_GAIN ->
                AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN

            AudioFocusRequestType.AUDIO_FOCUS_GAIN_TRANSIENT,
            AudioFocusRequestType.AUDIO_FOCUS_GAIN_TRANSIENT_MAY_DUCK,
            -> AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_TRANSIENT

            AudioFocusRequestType.AUDIO_FOCUS_RELEASE ->
                AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS

            else -> AudioFocusStateType.AUDIO_FOCUS_STATE_INVALID
        }
    }
}

/** How the emulated sink answers setup, and what it claims about its speakers. */
data class AudioSinkConfig(
    /** `STATUS_READY` (2) — openauto's audio sink never sends `STATUS_WAIT`
     * (`openauto/src/autoapp/Service/MediaSink/AudioMediaSinkService.cpp`
     * L171-L175), unlike its video sink. */
    val status: Config.Status = Config.Status.STATUS_READY,
    /** `Config.max_unacked`; openauto advertises 1 (`AudioMediaSinkService.cpp` L174). */
    val maxUnacked: Int = 1,
    /** Offered `configuration_indices`; openauto offers `[0]` (`AudioMediaSinkService.cpp` L175). */
    val configurationIndices: List<Int> = listOf(0),
    /** `Ack.ack`; openauto sends 1 for every buffer (`AudioMediaSinkService.cpp` L213). */
    val ackValue: Int = 1,
    val focusPolicy: AudioFocusPolicy = AudioFocusPolicy.OPENAUTO,
)

/**
 * The **head-unit** side of one audio sink channel, plus the control-channel
 * focus exchange that governs it.
 *
 * Mirrors `aasdk/src/Channel/MediaSink/Audio/AudioMediaSinkService.cpp` and
 * `openauto/src/autoapp/Service/MediaSink/AudioMediaSinkService.cpp`: answers
 * `Setup` with `Config`, adopts the session from `Start`, acknowledges every
 * media message, forgets the session on `Stop`. Where openauto hands the PCM to
 * an output device, this keeps the bytes, so a test can assert that the audio
 * the phone generated is the audio the car would have played — not that some
 * bytes arrived.
 *
 * ## Why focus is handled here
 *
 * `AUDIO_FOCUS_REQUEST` (control message 18) belongs to the head unit's control
 * handler, not to any audio sink — openauto answers it in `AndroidAutoEntity`,
 * a level above its audio services
 * (`openauto/src/autoapp/Service/AndroidAutoEntity.cpp` L216-L253). It lives
 * here because [EmulatedHeadUnit] hands its [FramedConnection] over once
 * bring-up finishes and there is no router above that connection yet, so
 * whichever object is reading is the only object that can answer. **Move this
 * to the control handler when the router lands**; the wire behaviour is
 * unaffected either way, since a control-channel message looks the same whoever
 * writes it.
 *
 * Per ADR 0002 this shares `core-protocol` with the phone and is therefore a
 * weak oracle for the wire format. It is not a weak oracle for message *order*,
 * for PCM integrity, or for the timestamps, which are what a focus/ducking bug
 * actually corrupts.
 */
class EmulatedAudioSink(
    private val connection: FramedConnection,
    /** The channel this sink serves — the id it advertised, not a constant. */
    val channelId: Int,
    /** Which of the car's sinks this is, as advertised. */
    val stream: AudioStreamType,
    /** The formats this unit advertised, in `audio_configs` order. */
    val advertisedFormats: List<PcmFormat>,
    val config: AudioSinkConfig = AudioSinkConfig(),
    private val onStep: (String) -> Unit = {},
) {

    /** One media message as it arrived. */
    data class ReceivedBuffer(
        /** Arrival order on this channel, starting at 0. */
        val index: Int,
        /** Presentation timestamp in microseconds, from the `DATA` header. */
        val timestampMicros: Long,
        /** The PCM payload, with the timestamp header already stripped. */
        val bytes: ByteArray,
    )

    /** A focus message this unit saw, in the direction it travelled. */
    sealed interface FocusEvent {
        /** `AudioFocusRequest` from the phone, control message id 18. */
        data class Requested(val type: AudioFocusRequestType) : FocusEvent

        /** `AudioFocusNotification` this unit sent, control message id 19. */
        data class Notified(val state: AudioFocusStateType, val unsolicited: Boolean) : FocusEvent
    }

    private val lock = Any()
    private val buffers = ArrayList<ReceivedBuffer>()
    private val focus = ArrayList<FocusEvent>()
    private val unhandled = ArrayList<Int>()

    /** The codec the phone asked for in `Setup`, once it has. */
    var requestedCodec: MediaCodecType? = null
        private set

    /**
     * The session id from `Start`, echoed in every `Ack`.
     *
     * openauto initialises it to -1 and — unlike its video sink — resets it to
     * -1 again on `Stop` (`AudioMediaSinkService.cpp` L31, L189, L194-L200), so
     * an ack sent outside a session carries -1. Reproduced rather than tidied
     * up: a phone that keys acks by session id has to cope with that.
     */
    var sessionId: Int = NO_SESSION
        private set

    /** The configuration index the phone selected in `Start`. */
    var configurationIndex: Int? = null
        private set

    /** The format that index selected, i.e. what these PCM bytes actually are. */
    var selectedFormat: PcmFormat? = null
        private set

    /** True once the phone has sent `Stop`. */
    var stopped: Boolean = false
        private set

    /** Acknowledgements sent. */
    var acksSent: Long = 0L
        private set

    /** Every media message received on this channel, in arrival order. */
    val receivedBuffers: List<ReceivedBuffer> get() = synchronized(lock) { buffers.toList() }

    val bufferCount: Int get() = synchronized(lock) { buffers.size }

    /** Focus messages seen — requests received and notifications sent — in wire order. */
    val focusEvents: List<FocusEvent> get() = synchronized(lock) { focus.toList() }

    /** Message ids this sink did not implement, in arrival order. */
    val unhandledMessageIds: List<Int> get() = synchronized(lock) { unhandled.toList() }

    /**
     * Every PCM byte received, concatenated in arrival order — what this unit's
     * DAC would have been handed.
     */
    fun pcmBytes(): ByteArray {
        val all = receivedBuffers
        val out = ByteArray(all.sumOf { it.bytes.size })
        var offset = 0
        for (buffer in all) {
            buffer.bytes.copyInto(out, offset)
            offset += buffer.bytes.size
        }
        return out
    }

    /** How long the received audio plays for, per the negotiated format. */
    fun receivedDurationMicros(): Long =
        selectedFormat?.durationMicros(pcmBytes().size.toLong()) ?: 0L

    /**
     * Services the channel.
     *
     * @param mediaMessages return once this many `DATA` messages have been
     *   received and acknowledged. Null means run until the phone sends `Stop`.
     *   A caller that cannot guarantee a `Stop` must state a count, or this
     *   blocks forever.
     */
    suspend fun run(mediaMessages: Int? = null) {
        if (mediaMessages != null && bufferCount >= mediaMessages) return
        while (true) {
            handle(connection.receive())
            if (mediaMessages != null) {
                if (bufferCount >= mediaMessages) return
            } else if (stopped) {
                return
            }
        }
    }

    /** Reads and services exactly [count] messages. Lets a test step the unit. */
    suspend fun runMessages(count: Int) {
        repeat(count) { handle(connection.receive()) }
    }

    /** Decodes and services one message. */
    suspend fun handle(message: AapMessage) {
        if (message.channelId == ChannelId.CONTROL.id) {
            handleControl(message)
            return
        }
        if (message.channelId != channelId) {
            throw IllegalStateException(
                "message for ${ChannelId.describe(message.channelId)} arrived on the " +
                    "${stream.name} sink's connection"
            )
        }

        when (message.messageId) {
            AvMessageId.SETUP -> onSetup(message.payload)
            AvMessageId.START -> onStart(message.payload)
            AvMessageId.STOP -> onStop()
            AvMessageId.DATA -> onData(message.payload)

            // PCM has nothing to configure, so this id should never appear on an
            // audio channel -- but aasdk routes it to onMediaIndication anyway
            // (AudioMediaSinkService.cpp L111-L113) and openauto acks it with
            // timestamp 0 (openauto AudioMediaSinkService.cpp L222-L226). If a
            // phone sends one, the ack it gets back must not be missing.
            AvMessageId.CODEC_CONFIG -> onCodecConfig(message.payload)

            else -> {
                synchronized(lock) { unhandled += message.messageId }
                onStep("unhandled ${AvMessageId.describe(message.messageId)}")
            }
        }
    }

    // --- setup --------------------------------------------------------------

    private suspend fun onSetup(payload: ByteArray) {
        val setup = Setup.parseFrom(payload)
        requestedCodec = setup.type
        onStep("setup request on ${stream.name}: ${setup.type.name}")

        val response = Config.newBuilder()
            .setStatus(config.status)
            .setMaxUnacked(config.maxUnacked)
            .addAllConfigurationIndices(config.configurationIndices)
            .build()
        connection.send(specific(AvMessageId.CONFIG, response.toByteArray()))
        onStep("config sent: ${config.status.name}, window ${config.maxUnacked}")

        // No focus notification is chained on here. openauto's *video* sink does
        // that (VideoMediaSinkService.cpp L120-L125) because video focus is a
        // media-channel message; audio focus is not, and nothing on this channel
        // implies anything about it.
    }

    private fun onStart(payload: ByteArray) {
        val start = Start.parseFrom(payload)
        if (start.configurationIndex !in config.configurationIndices) {
            throw IllegalStateException(
                "phone selected audio configuration index ${start.configurationIndex}, which " +
                    "this unit did not offer (${config.configurationIndices})"
            )
        }
        sessionId = start.sessionId
        configurationIndex = start.configurationIndex
        selectedFormat = advertisedFormats.getOrNull(start.configurationIndex)
        onStep("start: session ${start.sessionId}, format ${selectedFormat ?: "unknown"}")
    }

    private fun onStop() {
        stopped = true
        sessionId = NO_SESSION
        onStep("stop on ${stream.name}")
    }

    // --- media --------------------------------------------------------------

    private suspend fun onData(payload: ByteArray) {
        val data = MediaFrame.parseData(payload)
        record(data.timestampMicros, data.media)
        sendAck()
    }

    private suspend fun onCodecConfig(payload: ByteArray) {
        record(timestampMicros = 0L, pcm = payload)
        sendAck()
    }

    private fun record(timestampMicros: Long, pcm: ByteArray) {
        synchronized(lock) {
            buffers += ReceivedBuffer(
                index = buffers.size,
                timestampMicros = timestampMicros,
                bytes = pcm,
            )
        }
    }

    private suspend fun sendAck() {
        val ack = Ack.newBuilder()
            .setSessionId(sessionId)
            .setAck(config.ackValue)
            .build()
        connection.send(specific(AvMessageId.ACK, ack.toByteArray()))
        acksSent++
    }

    // --- audio focus --------------------------------------------------------

    private suspend fun handleControl(message: AapMessage) {
        when (message.messageId) {
            ControlMessageType.AUDIO_FOCUS_REQUEST.id -> {
                val type = AudioFocus.parseRequest(message.payload)
                synchronized(lock) { focus += FocusEvent.Requested(type) }
                onStep("audio focus request: ${type.name}")
                sendFocusNotification(config.focusPolicy.responseTo(type), unsolicited = false)
            }

            else -> {
                synchronized(lock) { unhandled += message.messageId }
                onStep("unhandled control ${ControlMessageType.describe(message.messageId)}")
            }
        }
    }

    /**
     * Sends a focus state the phone did not ask for — the driver pressing the
     * radio's source button, or the car needing the speakers for a reversing
     * chime.
     *
     * openauto's own comment describes exactly this case without implementing
     * it: "When HU starts playing music, we should send a STATE LOSS to stop MD
     * music and guidance" (`AndroidAutoEntity.cpp` L228). Since no reference
     * sends one, the `unsolicited` flag's real-world value is unknown; this sets
     * it, which is the reading its name supports.
     */
    suspend fun sendUnsolicitedFocus(state: AudioFocusStateType) {
        sendFocusNotification(state, unsolicited = true)
    }

    private suspend fun sendFocusNotification(state: AudioFocusStateType, unsolicited: Boolean) {
        connection.send(AudioFocus.notificationMessage(state, unsolicited))
        synchronized(lock) { focus += FocusEvent.Notified(state, unsolicited) }
        onStep("audio focus notification: ${state.name}${if (unsolicited) " (unsolicited)" else ""}")
    }

    // --- helpers ------------------------------------------------------------

    /** Encrypted, `MessageType::SPECIFIC` — see [dev.headway.protocol.channel.AudioChannel]. */
    private fun specific(messageId: Int, payload: ByteArray) = AapMessage(
        channelId = channelId,
        control = false,
        encrypted = true,
        messageId = messageId,
        payload = payload,
    )

    companion object {
        /** openauto's out-of-session id (`AudioMediaSinkService.cpp` L31, L198). */
        const val NO_SESSION: Int = -1

        /**
         * Builds a sink from the `Service` the head unit advertised, so the
         * emulator's two sides cannot disagree about what was offered.
         */
        fun fromService(
            connection: FramedConnection,
            service: ServiceOuterClass.Service,
            config: AudioSinkConfig = AudioSinkConfig(),
            onStep: (String) -> Unit = {},
        ): EmulatedAudioSink {
            require(service.hasMediaSinkService() && service.mediaSinkService.hasAudioType()) {
                "service ${service.id} is not an audio sink"
            }
            val sink = service.mediaSinkService
            require(sink.availableType == MediaCodecType.MEDIA_CODEC_AUDIO_PCM) {
                "audio sink ${service.id} advertises ${sink.availableType.name}, not PCM"
            }
            return EmulatedAudioSink(
                connection = connection,
                channelId = service.id,
                stream = sink.audioType,
                advertisedFormats = sink.audioConfigsList.map(PcmFormat::from),
                config = config,
                onStep = onStep,
            )
        }
    }
}

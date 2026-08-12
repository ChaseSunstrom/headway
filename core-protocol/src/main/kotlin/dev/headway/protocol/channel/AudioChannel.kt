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
import aap_protobuf.service.media.shared.message.AudioConfigurationOuterClass.AudioConfiguration
import aap_protobuf.service.media.shared.message.ConfigOuterClass.Config
import aap_protobuf.service.media.shared.message.MediaCodecTypeOuterClass.MediaCodecType
import aap_protobuf.service.media.shared.message.SetupOuterClass.Setup
import aap_protobuf.service.media.shared.message.StartOuterClass.Start
import aap_protobuf.service.media.shared.message.StopOuterClass.Stop
import aap_protobuf.service.media.sink.message.AudioStreamTypeOuterClass.AudioStreamType
import aap_protobuf.service.media.sink.message.AudioUnderflowNotificationOuterClass.AudioUnderflowNotification
import aap_protobuf.service.media.source.message.AckOuterClass.Ack
import dev.headway.protocol.framing.AapMessage
import dev.headway.protocol.framing.ChannelId
import dev.headway.protocol.io.FramedConnection

/** Raised when the head unit, or the caller, violates the audio channel's expected sequence. */
class AudioChannelException(message: String) : RuntimeException(message)

/**
 * One entry of a head unit's advertised `audio_configs`.
 *
 * `AudioConfiguration { required uint32 sampling_rate = 1; required uint32
 * number_of_bits = 2; required uint32 number_of_channels = 3 }`
 * (`aasdk/protobuf/aap_protobuf/service/media/shared/message/AudioConfiguration.proto`
 * L5-L9). The head unit fills it from its own output device
 * (`openauto/src/autoapp/Service/MediaSink/AudioMediaSinkService.cpp`
 * L114-L117), which is why nothing here may be hardcoded on the phone: the
 * numbers describe the car's DAC, not the protocol.
 *
 * The values openauto's factory actually constructs are stereo 48 kHz 16-bit for
 * media and mono 16 kHz 16-bit for guidance and system audio
 * (`openauto/src/autoapp/Service/ServiceFactory.cpp` L128-L131, L140-L144,
 * L169-L174). Treat those as *typical*, not as constants — a unit advertising
 * 44.1 kHz media is perfectly legal and Headway would resample to whatever
 * arrives here.
 *
 * ## Sample layout
 *
 * Signed 16-bit little-endian, channels interleaved. openauto's Qt output
 * declares exactly that — `setSampleSize(16)`,
 * `setByteOrder(QAudioFormat::LittleEndian)`,
 * `setSampleType(QAudioFormat::SignedInt)`
 * (`openauto/src/autoapp/Projection/QtAudioOutput.cpp` L36-L43) — its RtAudio
 * output opens the stream as `RTAUDIO_SINT16`
 * (`openauto/src/autoapp/Projection/RtAudioOutput.cpp` L67), and aa-proxy-rs
 * calls the format "AA media PCM" = `s16le` stereo throughout
 * (`aa-proxy-rs/src/bt_sco.rs` L28-L31, L872-L879). [bitsPerSample] is carried
 * anyway because it is a wire field and a unit could in principle advertise
 * something else; [AudioChannel] refuses a format it cannot frame rather than
 * guessing.
 */
data class PcmFormat(
    val sampleRateHz: Int,
    val bitsPerSample: Int,
    val channelCount: Int,
) {
    init {
        require(sampleRateHz > 0) { "sample rate must be positive: $sampleRateHz" }
        require(bitsPerSample > 0 && bitsPerSample % 8 == 0) {
            "sample size must be a whole number of bytes: $bitsPerSample bits"
        }
        require(channelCount > 0) { "channel count must be positive: $channelCount" }
    }

    /** Bytes in one sample of one channel. */
    val bytesPerSample: Int get() = bitsPerSample / 8

    /** Bytes in one frame — one sample for every channel. 4 for 16-bit stereo. */
    val bytesPerFrame: Int get() = bytesPerSample * channelCount

    /** Bytes for one second of audio. */
    val bytesPerSecond: Int get() = bytesPerFrame * sampleRateHz

    /**
     * How long [byteCount] bytes of this format play for, in microseconds.
     *
     * Integer division truncates, so a buffer whose duration is not a whole
     * microsecond loses a fraction. At 48 kHz one microsecond is 0.048 frames;
     * the error is under a frame per buffer and does not accumulate across
     * buffers, because [AudioChannel] advances its clock from the running byte
     * count rather than by summing per-buffer durations.
     */
    fun durationMicros(byteCount: Long): Long = byteCount * 1_000_000L / bytesPerSecond.toLong()

    /** [durationMicros] for a single buffer's length. */
    fun durationMicros(byteCount: Int): Long = durationMicros(byteCount.toLong())

    /** Bytes needed to carry [micros] microseconds, rounded down to a whole frame. */
    fun byteCountFor(micros: Long): Int {
        val frames = micros * sampleRateHz / 1_000_000L
        return (frames * bytesPerFrame).toInt()
    }

    override fun toString(): String =
        "$sampleRateHz Hz ${bitsPerSample}-bit " +
            when (channelCount) {
                1 -> "mono"
                2 -> "stereo"
                else -> "$channelCount ch"
            }

    companion object {
        fun from(config: AudioConfiguration): PcmFormat = PcmFormat(
            sampleRateHz = config.samplingRate,
            bitsPerSample = config.numberOfBits,
            channelCount = config.numberOfChannels,
        )
    }
}

/**
 * Where third-party media audio goes.
 *
 * CLAUDE.md settles this for Headway:
 *
 * > Third-party media audio: do **not** attempt playback capture as the primary
 * > path (`AudioPlaybackCapture` is opt-out-able and adds latency). Default
 * > strategy: instruct/steer media audio over the car's normal Bluetooth A2DP
 * > link, which coexists with the AAP session. Implement AAP media-audio channel
 * > support behind a settings toggle for apps that allow capture.
 *
 * So [BLUETOOTH_A2DP] is the default and it means the AAP media channel carries
 * nothing: the music is already reaching the car's speakers over a separate
 * radio link, and sending it again over AAP would play it twice. This only ever
 * governs `AUDIO_STREAM_MEDIA`. Guidance and system audio are Headway's own
 * output — its voice replies and prompts — and always go over AAP, because A2DP
 * carries one stream and it is busy.
 */
enum class MediaAudioRoute {
    /** Media plays over the car's Bluetooth A2DP link; the AAP media channel stays silent. */
    BLUETOOTH_A2DP,

    /** Media is captured and streamed over the AAP media channel (the settings toggle). */
    AAP_MEDIA_CHANNEL,
}

/**
 * The **phone** side of one audio sink channel: Headway sends PCM, the head unit
 * plays it.
 *
 * One instance drives one channel. AAP splits audio output across three sinks
 * that differ only in what the car does with them —
 * `MEDIA_SINK_MEDIA_AUDIO` (4), `MEDIA_SINK_GUIDANCE_AUDIO` (5) and
 * `MEDIA_SINK_SYSTEM_AUDIO` (6) in [ChannelId], each advertised with an
 * `AudioStreamType` (`AUDIO_STREAM_MEDIA`, `AUDIO_STREAM_GUIDANCE`,
 * `AUDIO_STREAM_SYSTEM_AUDIO`;
 * `aasdk/protobuf/aap_protobuf/service/media/sink/message/AudioStreamType.proto`
 * L5-L11, mapped to channels by
 * `openauto/src/autoapp/Service/MediaSink/AudioMediaSinkService.cpp` L84-L110).
 * The car ducks, mixes and routes each differently, which is the whole reason
 * there are three.
 *
 * ## Sequence
 *
 * Identical to video's, minus the codec configuration — PCM has nothing to
 * configure:
 *
 * ```text
 *  phone (Headway)                          head unit
 *  ---------------                          ---------
 *  Setup { AUDIO_PCM }         0x8000 -->
 *                              <-- 0x8003   Config { status, max_unacked, indices }
 *  Start { session, index }     0x8001 -->
 *  Data (pts + PCM)            0x0000 -->
 *                              <-- 0x8004   Ack { session, 1 }
 *  ...
 *  Stop {}                     0x8002 -->
 * ```
 *
 * aasdk's audio sink handles exactly `CHANNEL_OPEN_REQUEST`, `SETUP`, `START`,
 * `STOP`, `CODEC_CONFIG` and `DATA`
 * (`aasdk/src/Channel/MediaSink/Audio/AudioMediaSinkService.cpp` L98-L121) — no
 * focus ids, because audio focus is a control-channel affair; see [AudioFocus].
 * openauto answers `Setup` with `STATUS_READY`, `max_unacked = 1` and a single
 * offered index 0, and acknowledges every `DATA`
 * (`openauto/src/autoapp/Service/MediaSink/AudioMediaSinkService.cpp` L167-L183,
 * L204-L220) — the same flow control as video, so with openauto's window of 1
 * the channel is strictly lock-step.
 *
 * ## The format comes off the wire
 *
 * [advertisedFormats] is the head unit's `audio_configs` list, and
 * [sendStart] selects one of them by index — the same index the head unit
 * offered in `Config.configuration_indices`. Only after that does [format]
 * exist, and everything that needs to know the sample rate (the presentation
 * clock, the frame-alignment check) reads it from there. There is no default
 * sample rate anywhere in this class on purpose.
 *
 * ## Ownership of the connection
 *
 * As with [VideoChannel], this reads [connection] directly and refuses messages
 * addressed elsewhere; a session running several channels needs a router above
 * [FramedConnection] that does not exist yet.
 */
class AudioChannel(
    private val connection: FramedConnection,
    /**
     * The channel number the head unit assigned to this audio service.
     *
     * Not a protocol constant — the authority is the `Service.id` in the
     * `ServiceDiscoveryResponse`; see [ChannelId] and [VideoChannel]. Prefer
     * [fromService] over passing this by hand.
     */
    val channelId: Int,
    /** Which of the car's three sinks this is, as the head unit advertised it. */
    val stream: AudioStreamType,
    /** The head unit's `audio_configs`, in advertisement order — the index space of `Start`. */
    val advertisedFormats: List<PcmFormat>,
    /**
     * Where media audio goes. Ignored unless [stream] is `AUDIO_STREAM_MEDIA`;
     * see [MediaAudioRoute].
     */
    val route: MediaAudioRoute = MediaAudioRoute.BLUETOOTH_A2DP,
    /** Narrates each step; wired to the debug log export in the app. */
    private val onStep: (String) -> Unit = {},
) {

    /** What the head unit answered [sendSetup] with. See [VideoChannel.SetupResponse]. */
    data class SetupResponse(
        val status: Config.Status,
        /** `Config.max_unacked`; openauto advertises 1. Null when the head unit omitted it. */
        val maxUnacked: Int?,
        /** Indices into [advertisedFormats] the head unit will accept. openauto offers `[0]`. */
        val configurationIndices: List<Int>,
    ) {
        val ready: Boolean get() = status == Config.Status.STATUS_READY
    }

    /** Something the head unit sent on this channel. */
    sealed interface Event {
        /** The setup response, message id `0x8003`. */
        data class ConfigReceived(val response: SetupResponse) : Event

        /** A media acknowledgement, message id `0x8004`. */
        data class Acknowledged(val sessionId: Int, val ack: Int?) : Event

        /**
         * The head unit ran out of audio to play, message id `0x800B`.
         *
         * `MEDIA_MESSAGE_AUDIO_UNDERFLOW_NOTIFICATION` and its one-field
         * `AudioUnderflowNotification { required int32 session_id = 1 }` are in
         * the schema
         * (`aasdk/protobuf/aap_protobuf/service/media/sink/MediaMessageId.proto`
         * L20; `.../message/AudioUnderflowNotification.proto` L5-L7) but **no
         * reference implements either side** — aasdk's audio channel does not
         * name the id in its dispatch (`AudioMediaSinkService.cpp` L98-L121), so
         * a real unit sending one would land in its "not handled" log. Headway
         * surfaces it because it is the one signal that would explain choppy
         * playback, and because ignoring a message the schema defines is a
         * choice worth making visibly.
         */
        data class Underflow(val sessionId: Int) : Event

        /** A message id this channel does not implement. */
        data class Unhandled(val messageId: Int, val payload: ByteArray) : Event
    }

    /** The head unit's setup response, once [awaitConfig] has read it. */
    var setupResponse: SetupResponse? = null
        private set

    /** The session id passed to [sendStart]; the head unit echoes it in every [Ack]. */
    var sessionId: Int? = null
        private set

    /** The index into [advertisedFormats] passed to [sendStart]. */
    var configurationIndex: Int? = null
        private set

    /** The format selected by [sendStart]. Null until then. */
    var format: PcmFormat? = null
        private set

    /**
     * Presentation timestamp the next buffer will carry, in microseconds.
     *
     * Advanced by each buffer's own duration, computed from the byte count and
     * the negotiated [format]: aa-proxy-rs does exactly this when it injects PCM
     * into a media channel — an 8-byte big-endian `pts_us` in front of the
     * samples, then `pts_us += frames * 1_000_000 / sample_rate`
     * (`aa-proxy-rs/src/bt_sco_media_bridge.rs` L442-L457). Nothing states what
     * the epoch is; starting at 0 for each `Start` is what that code does too.
     *
     * The clock is derived from [bytesSent] rather than accumulated per buffer,
     * so truncation in [PcmFormat.durationMicros] cannot drift over a long
     * stream.
     */
    val nextTimestampMicros: Long
        get() = format?.durationMicros(bytesSent) ?: 0L

    /** PCM bytes handed to [sendPcm] and transmitted. */
    var bytesSent: Long = 0L
        private set

    /** PCM bytes handed to [sendPcm] and dropped because of [route]. */
    var bytesSuppressed: Long = 0L
        private set

    /** `DATA` messages transmitted. */
    var mediaMessagesSent: Long = 0L
        private set

    /** Acknowledgements read off the channel. */
    var acksReceived: Long = 0L
        private set

    /** Media messages sent but not yet acknowledged. Bounded by [SetupResponse.maxUnacked]. */
    var unacknowledgedMessages: Int = 0
        private set

    /** True once [sendStop] has been sent. */
    var stopped: Boolean = false
        private set

    /**
     * False when [route] says this stream's audio belongs on another link.
     *
     * True for guidance and system audio always; true for media audio only when
     * the settings toggle has moved it onto the AAP media channel.
     */
    val transmits: Boolean
        get() = stream != AudioStreamType.AUDIO_STREAM_MEDIA ||
            route == MediaAudioRoute.AAP_MEDIA_CHANNEL

    // --- setup --------------------------------------------------------------

    /**
     * Sends the setup request, message id `0x8000`.
     *
     * `Setup { required MediaCodecType type = 1 }` with `MEDIA_CODEC_AUDIO_PCM`
     * (1;
     * `aasdk/protobuf/aap_protobuf/service/media/shared/message/MediaCodecType.proto`
     * L5-L7) serialises to the two bytes `08 01`. PCM is what openauto
     * advertises for every audio sink
     * (`openauto/src/autoapp/Service/MediaSink/AudioMediaSinkService.cpp`
     * L81-L83) and what aa-proxy-rs requires before it will use a sink at all
     * (`aa-proxy-rs/src/mitm.rs` L1246-L1248). The schema also lists AAC, which
     * no reference implements on this channel.
     *
     * @throws AudioChannelException when [transmits] is false. Setting up a
     *   channel Headway has already decided not to feed would leave the car
     *   waiting for audio that never comes; refusing here turns a routing
     *   mistake into a stack trace instead of silence.
     */
    suspend fun sendSetup(codec: MediaCodecType = MediaCodecType.MEDIA_CODEC_AUDIO_PCM) {
        requireTransmitting("set up")
        connection.send(
            specific(AvMessageId.SETUP, Setup.newBuilder().setType(codec).build().toByteArray())
        )
        onStep("setup requested (${codec.name}) on ${stream.name}")
    }

    /** Reads until the head unit's `Config` arrives and returns it. */
    suspend fun awaitConfig(): SetupResponse {
        while (true) {
            when (val event = receiveEvent()) {
                is Event.ConfigReceived -> return event.response
                else -> Unit
            }
        }
    }

    /**
     * Sends the start indication, message id `0x8001`, and adopts the
     * [advertisedFormats] entry at [configurationIndex] as this stream's
     * [format].
     *
     * `Start { required int32 session_id = 1; required uint32
     * configuration_index = 2 }`. The session id comes back in every `Ack`
     * (`openauto/src/autoapp/Service/MediaSink/AudioMediaSinkService.cpp`
     * L186-L192, L211-L213), which is what makes an ack attributable to a
     * stream rather than to the channel.
     */
    suspend fun sendStart(sessionId: Int, configurationIndex: Int) {
        requireTransmitting("start")
        val offered = setupResponse?.configurationIndices
        if (offered != null && offered.isNotEmpty() && configurationIndex !in offered) {
            throw AudioChannelException(
                "configuration index $configurationIndex was not offered by the head unit " +
                    "(it offered $offered)"
            )
        }
        val selected = advertisedFormats.getOrNull(configurationIndex)
            ?: throw AudioChannelException(
                "configuration index $configurationIndex has no matching AudioConfiguration; " +
                    "the head unit advertised ${advertisedFormats.size}"
            )

        connection.send(
            specific(
                AvMessageId.START,
                Start.newBuilder()
                    .setSessionId(sessionId)
                    .setConfigurationIndex(configurationIndex)
                    .build()
                    .toByteArray(),
            )
        )
        this.sessionId = sessionId
        this.configurationIndex = configurationIndex
        this.format = selected
        this.bytesSent = 0L
        onStep("started session $sessionId on configuration $configurationIndex ($selected)")
    }

    /** Sends the stop indication, message id `0x8002`, whose payload is the empty `Stop`. */
    suspend fun sendStop() {
        requireTransmitting("stop")
        connection.send(specific(AvMessageId.STOP, Stop.newBuilder().build().toByteArray()))
        stopped = true
        onStep("stopped session ${sessionId ?: "-"}")
    }

    // --- media --------------------------------------------------------------

    /**
     * Sends one buffer of PCM, message id `0x0000`: an 8-byte big-endian
     * microsecond presentation timestamp followed by the samples. See
     * [MediaFrame] for the layout and its citations.
     *
     * Suspends first if the acknowledgement window is full, exactly as
     * [VideoChannel.sendFrame] does.
     *
     * @param pcm samples in the negotiated [format] — signed little-endian,
     *   channels interleaved. Must be a whole number of frames: half a stereo
     *   frame would swap left and right for the rest of the stream. No reference
     *   states that requirement, but every reference produces buffers that
     *   satisfy it, and the failure it prevents is silent.
     * @param timestampMicros defaults to [nextTimestampMicros], the running
     *   stream clock. Passing one explicitly places that one buffer on an
     *   external timeline and leaves the clock alone — it is derived from the
     *   bytes sent since `Start`, not from the last timestamp used.
     * @return true if the buffer went out, false if it was dropped because
     *   [transmits] is false. A dropped buffer is normal and expected — with the
     *   default [MediaAudioRoute.BLUETOOTH_A2DP] the media pipeline keeps
     *   producing while the car plays the same audio over Bluetooth — which is
     *   why this returns a flag instead of throwing the way the lifecycle
     *   methods do.
     */
    suspend fun sendPcm(pcm: ByteArray, timestampMicros: Long = nextTimestampMicros): Boolean {
        if (!transmits) {
            bytesSuppressed += pcm.size
            onStep("suppressed ${pcm.size} B of ${stream.name}: routed over ${route.name}")
            return false
        }

        val active = format ?: throw AudioChannelException(
            "PCM was offered before Start; the sample format is not negotiated yet"
        )
        if (pcm.size % active.bytesPerFrame != 0) {
            throw AudioChannelException(
                "buffer of ${pcm.size} B is not a whole number of ${active.bytesPerFrame} B " +
                    "frames for $active"
            )
        }

        awaitCredit()
        connection.send(MediaFrame.data(channelId, pcm, timestampMicros))
        bytesSent += pcm.size
        mediaMessagesSent++
        unacknowledgedMessages++
        return true
    }

    /**
     * Splits [pcm] into buffers of at most [bufferMicros] and sends them in
     * order, returning how many went out.
     *
     * Buffer sizing is a head-unit-comfort question, not a protocol one. The
     * only numbers anyone states: openauto opens its output device with 1024
     * frames at 16 kHz and 2048 otherwise, "according to observation of audio
     * packets" (`openauto/src/autoapp/Projection/RtAudioOutput.cpp` L62) — 64 ms
     * and ~43 ms respectively — and aa-proxy-rs targets "about 20ms" per chunk
     * (`aa-proxy-rs/src/bt_sco.rs` L28-L31). The 20 ms default here follows
     * aa-proxy-rs because it is the only figure written down as a target rather
     * than inferred from a capture, and because a shorter buffer costs latency
     * nothing and gives the head unit's own buffering more to work with.
     *
     * The final buffer is whatever is left; it is not padded, because padding
     * with silence would append audio the caller did not ask to play.
     */
    suspend fun streamPcm(pcm: ByteArray, bufferMicros: Long = DEFAULT_BUFFER_MICROS): Int {
        if (!transmits) {
            bytesSuppressed += pcm.size
            onStep("suppressed ${pcm.size} B of ${stream.name}: routed over ${route.name}")
            return 0
        }
        val active = format ?: throw AudioChannelException(
            "PCM was offered before Start; the sample format is not negotiated yet"
        )
        val chunk = active.byteCountFor(bufferMicros).coerceAtLeast(active.bytesPerFrame)

        var offset = 0
        var sent = 0
        while (offset < pcm.size) {
            val end = minOf(offset + chunk, pcm.size)
            if (sendPcm(pcm.copyOfRange(offset, end))) sent++
            offset = end
        }
        return sent
    }

    /** Reads until an acknowledgement arrives and returns it. */
    suspend fun awaitAck(): Event.Acknowledged {
        while (true) {
            when (val event = receiveEvent()) {
                is Event.Acknowledged -> return event
                else -> Unit
            }
        }
    }

    /** Consumes acknowledgements until nothing is outstanding. */
    suspend fun awaitAllAcks() {
        while (unacknowledgedMessages > 0) {
            awaitAck()
        }
    }

    // --- receive ------------------------------------------------------------

    /** Reads exactly one message and decodes it, updating this channel's state. */
    suspend fun receiveEvent(): Event {
        val message = connection.receive()
        if (message.channelId != channelId) {
            throw AudioChannelException(
                "message for ${ChannelId.describe(message.channelId)} arrived on the " +
                    "${stream.name} channel's connection; a session with more than one open " +
                    "channel needs a router above FramedConnection"
            )
        }

        return when (message.messageId) {
            AvMessageId.CONFIG -> {
                val config = Config.parseFrom(message.payload)
                val response = SetupResponse(
                    status = config.status,
                    maxUnacked = if (config.hasMaxUnacked()) config.maxUnacked else null,
                    configurationIndices = config.configurationIndicesList.toList(),
                )
                setupResponse = response
                onStep(
                    "config: ${response.status.name}, window ${response.maxUnacked ?: "unstated"}, " +
                        "indices ${response.configurationIndices}"
                )
                Event.ConfigReceived(response)
            }

            AvMessageId.ACK -> {
                val ack = Ack.parseFrom(message.payload)
                acksReceived++
                if (unacknowledgedMessages > 0) unacknowledgedMessages--
                Event.Acknowledged(
                    sessionId = ack.sessionId,
                    ack = if (ack.hasAck()) ack.ack else null,
                )
            }

            AUDIO_UNDERFLOW_NOTIFICATION -> {
                val underflow = AudioUnderflowNotification.parseFrom(message.payload)
                onStep("underflow on session ${underflow.sessionId}")
                Event.Underflow(underflow.sessionId)
            }

            else -> {
                onStep("unhandled ${AvMessageId.describe(message.messageId)}")
                Event.Unhandled(message.messageId, message.payload)
            }
        }
    }

    // --- helpers ------------------------------------------------------------

    /** See [VideoChannel]'s note on why an absent or zero window means "no window". */
    private suspend fun awaitCredit() {
        val window = setupResponse?.maxUnacked ?: return
        if (window <= 0) return
        while (unacknowledgedMessages >= window) {
            awaitAck()
        }
    }

    private fun requireTransmitting(action: String) {
        if (!transmits) {
            throw AudioChannelException(
                "refusing to $action the ${stream.name} channel: media audio is routed over " +
                    "${route.name}, so this channel carries nothing"
            )
        }
    }

    /** Audio messages are encrypted and `MessageType::SPECIFIC`, as video's are. */
    private fun specific(messageId: Int, payload: ByteArray) = AapMessage(
        channelId = channelId,
        control = false,
        encrypted = true,
        messageId = messageId,
        payload = payload,
    )

    companion object {
        /**
         * `MEDIA_MESSAGE_AUDIO_UNDERFLOW_NOTIFICATION` = 32779
         * (`aasdk/protobuf/aap_protobuf/service/media/sink/MediaMessageId.proto`
         * L20). Absent from [AvMessageId] because it is the one id in that
         * enumeration that only an audio channel can carry.
         */
        const val AUDIO_UNDERFLOW_NOTIFICATION: Int = 0x800B

        /** Default buffer duration for [streamPcm]; see that method for the citation. */
        const val DEFAULT_BUFFER_MICROS: Long = 20_000L

        /**
         * Builds a channel from what the head unit advertised.
         *
         * This is the intended constructor: it takes the channel id, the stream
         * type and the sample formats from the same `Service` message, so none
         * of the three can be a phone-side assumption. A service that is not a
         * PCM audio sink is refused rather than half-configured.
         *
         * @throws AudioChannelException if [service] is not an audio sink, does
         *   not offer PCM, or advertises no `audio_configs`.
         */
        fun fromService(
            connection: FramedConnection,
            service: ServiceOuterClass.Service,
            route: MediaAudioRoute = MediaAudioRoute.BLUETOOTH_A2DP,
            onStep: (String) -> Unit = {},
        ): AudioChannel {
            if (!service.hasMediaSinkService()) {
                throw AudioChannelException("service ${service.id} is not a media sink")
            }
            val sink = service.mediaSinkService
            if (sink.availableType != MediaCodecType.MEDIA_CODEC_AUDIO_PCM) {
                throw AudioChannelException(
                    "service ${service.id} advertises ${sink.availableType.name}, not PCM audio"
                )
            }
            if (!sink.hasAudioType()) {
                throw AudioChannelException(
                    "audio sink ${service.id} advertised no AudioStreamType, so there is no way " +
                        "to know whether it is media, guidance or system audio"
                )
            }
            if (sink.audioConfigsCount == 0) {
                throw AudioChannelException(
                    "audio sink ${service.id} advertised no AudioConfiguration; the sample " +
                        "format is only knowable from the head unit"
                )
            }

            return AudioChannel(
                connection = connection,
                channelId = service.id,
                stream = sink.audioType,
                advertisedFormats = sink.audioConfigsList.map(PcmFormat::from),
                route = route,
                onStep = onStep,
            )
        }
    }
}

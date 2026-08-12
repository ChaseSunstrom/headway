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

import aap_protobuf.service.media.shared.message.AudioConfigurationOuterClass.AudioConfiguration
import aap_protobuf.service.media.shared.message.ConfigOuterClass.Config
import aap_protobuf.service.media.shared.message.MediaCodecTypeOuterClass.MediaCodecType
import aap_protobuf.service.media.shared.message.SetupOuterClass.Setup
import aap_protobuf.service.media.source.message.AckOuterClass.Ack
import aap_protobuf.service.media.source.message.MicrophoneRequestOuterClass.MicrophoneRequest
import aap_protobuf.service.media.source.message.MicrophoneResponseOuterClass.MicrophoneResponse
import dev.headway.protocol.framing.AapMessage
import dev.headway.protocol.framing.ChannelId
import dev.headway.protocol.io.FramedConnection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** Raised when the head unit violates the microphone channel's expected sequence. */
class MicrophoneChannelException(message: String) : RuntimeException(message)

/**
 * The linear-PCM format of a media-source stream.
 *
 * The head unit states its own format in the `MediaSourceService.audio_config`
 * of its `ServiceDiscoveryResponse` entry
 * (`aasdk/protobuf/aap_protobuf/service/media/source/MediaSourceService.proto`
 * L10-L12), so nothing here is a constant the phone gets to choose — use
 * [fromAdvertisement] and treat [CAR_MICROPHONE] as a default, not a promise.
 *
 * Deliberately separate from [PcmFormat], which the audio *output* channels use.
 * The two carry the same three fields but measure duration differently: output
 * counts bytes, because it paces a stream it is writing, while capture counts
 * frames, because it is decoding buffers it received. Collapsing them into one
 * type would mean one of the two call sites converting units at every use, which
 * is exactly where a sample-rate bug hides.
 *
 * @property sampleRateHz `AudioConfiguration.sampling_rate`.
 * @property bitsPerSample `AudioConfiguration.number_of_bits`.
 * @property channels `AudioConfiguration.number_of_channels`.
 */
data class MicrophoneFormat(
    val sampleRateHz: Int,
    val bitsPerSample: Int,
    val channels: Int,
) {
    init {
        require(sampleRateHz > 0) { "sample rate must be positive: $sampleRateHz" }
        require(bitsPerSample > 0) { "bits per sample must be positive: $bitsPerSample" }
        require(channels > 0) { "channel count must be positive: $channels" }
    }

    /** Bytes occupied by one sample of every channel. */
    val bytesPerFrame: Int get() = channels * (bitsPerSample / 8)

    /** How long [frames] frames last, in microseconds — the unit AAP timestamps use. */
    fun durationMicros(frames: Long): Long = frames * MICROS_PER_SECOND / sampleRateHz

    override fun toString(): String = "$sampleRateHz Hz, $bitsPerSample bit, ${channels}ch"

    companion object {
        const val MICROS_PER_SECOND: Long = 1_000_000L

        /**
         * What every reference expects a car microphone to be: 16 kHz, 16-bit,
         * mono.
         *
         * openauto constructs its microphone input as
         * `QtAudioInput(1, 16, 16000)` — channels, sample size, sample rate
         * (`openauto/src/autoapp/Service/ServiceFactory.cpp` L194) — and
         * aa-proxy-rs scores an advertised source as a perfect match at exactly
         * `16_000 && 1 channel && 16 bits`, with 8 kHz mono as its next-best
         * (`aa-proxy-rs/src/mitm.rs` L1130-L1139).
         */
        val CAR_MICROPHONE: MicrophoneFormat = MicrophoneFormat(16_000, 16, 1)

        /** Reads the format out of an advertised `MediaSourceService.audio_config`. */
        fun fromAdvertisement(config: AudioConfiguration): MicrophoneFormat = MicrophoneFormat(
            sampleRateHz = config.samplingRate,
            bitsPerSample = config.numberOfBits,
            channels = config.numberOfChannels,
        )
    }
}

/**
 * Decoding of the PCM payload carried by a media-source `DATA` message.
 *
 * ## Sample format: little-endian signed 16-bit
 *
 * This is the one place on the microphone channel where the byte order is
 * *opposite* to the rest of AAP. Every AAP header field — frame sizes, the
 * 2-byte message id, the 8-byte presentation timestamp — is big-endian
 * (`aasdk/src/Messenger/Timestamp.cpp` L29-L38). The audio *samples* inside the
 * payload are not: they are the raw capture buffer of the head unit's audio
 * device, and every reference reads and writes them little-endian.
 *
 * - openauto configures its microphone capture format as
 *   `setByteOrder(QAudioFormat::LittleEndian)` and
 *   `setSampleType(QAudioFormat::SignedInt)` with `setCodec("audio/pcm")`, then
 *   hands the device buffer to the channel unmodified
 *   (`openauto/src/autoapp/Projection/QtAudioInput.cpp` L37-L42, L150-L173).
 * - aa-proxy-rs, which reads this traffic off a real phone-to-head-unit link,
 *   skips the 8-byte timestamp (`aa-proxy-rs/src/mitm.rs` L2591-L2596) and
 *   decodes what follows with `i16::from_le_bytes`, refusing anything whose
 *   advertised `number_of_bits` is not 16
 *   (`aa-proxy-rs/src/bt_sco.rs` L689-L730).
 *
 * Getting this backwards does not fail loudly: byte-swapped s16 is still
 * audible, just as loud broadband noise. That is why it is asserted on directly
 * in `MicrophoneChannelTest` rather than left to a round-trip test, which would
 * pass with both ends wrong.
 *
 * Only 16-bit is decoded. No reference implements any other width on this
 * channel — aa-proxy-rs drops non-16-bit audio outright — so a head unit
 * advertising, say, 24-bit is a case Headway has never seen and must not guess
 * at.
 */
object PcmDecoder {

    /**
     * Decodes [bytes] as little-endian signed 16-bit samples, channels
     * interleaved.
     *
     * @throws MicrophoneChannelException if the length is not a whole number of
     *   samples. A half sample means the payload is not what it claims to be —
     *   silently dropping the odd byte would resynchronise the stream one byte
     *   out and turn every subsequent sample into noise.
     */
    fun decodeS16Le(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): ShortArray {
        if (length % BYTES_PER_S16 != 0) {
            throw MicrophoneChannelException(
                "PCM payload is $length bytes, not a whole number of 16-bit samples"
            )
        }
        val samples = ShortArray(length / BYTES_PER_S16)
        for (i in samples.indices) {
            val low = bytes[offset + i * BYTES_PER_S16].toInt() and 0xFF
            val high = bytes[offset + i * BYTES_PER_S16 + 1].toInt()
            samples[i] = ((high shl 8) or low).toShort()
        }
        return samples
    }

    /** Encodes [samples] as little-endian signed 16-bit. The inverse of [decodeS16Le]. */
    fun encodeS16Le(samples: ShortArray, offset: Int = 0, length: Int = samples.size - offset): ByteArray {
        val out = ByteArray(length * BYTES_PER_S16)
        for (i in 0 until length) {
            val sample = samples[offset + i].toInt()
            out[i * BYTES_PER_S16] = sample.toByte()
            out[i * BYTES_PER_S16 + 1] = (sample shr 8).toByte()
        }
        return out
    }

    const val BYTES_PER_S16: Int = 2
}

/** One `DATA` message's worth of microphone audio, decoded. */
data class PcmChunk(
    /**
     * The message's presentation timestamp in microseconds, big-endian on the
     * wire — see [MediaFrame] for the layout and its citations.
     */
    val timestampMicros: Long,
    /**
     * The samples, channels interleaved. Note this is an array: two chunks with
     * identical audio are not `==` to each other, so tests must compare
     * contents.
     */
    val samples: ShortArray,
) {
    /** Sample frames in this chunk, i.e. samples divided by the channel count. */
    fun frameCount(format: MicrophoneFormat): Int = samples.size / format.channels
}

/**
 * The **phone** side of the car-microphone channel: audio flows from the head
 * unit to Headway.
 *
 * ## The direction is what makes this channel unusual
 *
 * Every other media channel Headway implements is a *sink* on the head unit —
 * the phone sends, the car plays or displays. This one is a *source*: the car's
 * microphone captures, and the phone consumes. `DATA` messages therefore arrive
 * rather than depart, and it is the phone that acknowledges them.
 *
 * The polarity is inverted relative to the references, as with the video
 * channel: `aasdk/src/Channel/MediaSource/MediaSourceService.cpp` and
 * `openauto/src/autoapp/Service/MediaSource/MediaSourceService.cpp` implement
 * the *head-unit* half. aa-proxy-rs sees both halves — it drives a real head
 * unit's microphone on behalf of a Bluetooth handsfree bridge
 * (`aa-proxy-rs/src/bt_sco_media_bridge.rs` L796-L836) — and is the only
 * reference for what a phone actually puts on this channel.
 *
 * ## Sequence
 *
 * ```text
 *  phone (Headway)                              head unit (car)
 *  ---------------                              ---------------
 *  Setup { AUDIO_PCM }             0x8000 -->
 *                                 <-- 0x8003    Config { READY, max_unacked, [0] }
 *  MicrophoneRequest { open=1 }    0x8005 -->
 *                                 <-- 0x8006    MicrophoneResponse { status, session_id }
 *                                 <-- 0x0000    Data { pts_us, s16le PCM }
 *  Ack { session_id, 1 }           0x8004 -->
 *                                 <-- 0x0000    Data { ... }
 *  ...
 *  MicrophoneRequest { open=0 }    0x8005 -->
 *                                 <-- 0x8006    MicrophoneResponse { status, session_id }
 * ```
 *
 * **There is no `Start` and no `Stop` on this channel.** aasdk's media-source
 * dispatcher has cases for exactly four inbound ids — `CHANNEL_OPEN_REQUEST`,
 * `SETUP` (0x8000), `MICROPHONE_REQUEST` (0x8005) and `ACK` (0x8004) — and
 * logs anything else as unhandled
 * (`aasdk/src/Channel/MediaSource/MediaSourceService.cpp` L79-L96). The
 * microphone request *is* the start indication here; `Start.configuration_index`
 * has no counterpart because there is only ever one PCM configuration to select.
 *
 * ## Where the references disagree: the ids the head unit replies on
 *
 * aasdk sends both of its replies on the wrong message id, and does so
 * consistently:
 *
 * - the setup response (a `Config`) goes out as `MEDIA_MESSAGE_SETUP` 0x8000
 *   (`MediaSourceService.cpp` L59-L71, specifically L67), where every media
 *   *sink* channel in the same library uses `MEDIA_MESSAGE_CONFIG` 0x8003
 *   (`aasdk/src/Channel/MediaSink/Video/VideoMediaSinkService.cpp` L70);
 * - the `MicrophoneResponse` goes out as `MEDIA_MESSAGE_MICROPHONE_REQUEST`
 *   0x8005 rather than `..._RESPONSE` 0x8006
 *   (`MediaSourceService.cpp` L99-L110, specifically L105-L106).
 *
 * Both look like copy-paste errors: 0x8000 and 0x8005 are the ids the *phone*
 * sends on, so an aasdk head unit echoes the request id back. aa-proxy-rs, which
 * decodes traffic from real phones and real head units, matches the response on
 * `MEDIA_MESSAGE_MICROPHONE_RESPONSE` and the config on `MEDIA_MESSAGE_CONFIG`
 * (`aa-proxy-rs/src/mitm.rs` L2572-L2617). So the observed wire behaviour and
 * aasdk's code disagree.
 *
 * CLAUDE.md says to follow aasdk where references disagree. That rule cannot be
 * applied literally here: "follow aasdk" would mean *expecting* the reply on
 * 0x8000/0x8005, and a head unit that behaves as aa-proxy-rs observed would
 * then hang this channel forever. Headway therefore **accepts either id** and
 * says so loudly. That is safe in a way the symmetric choice would not be: this
 * channel never receives 0x8000 or 0x8005, because those are the two ids the
 * phone itself sends, so treating them as the reply cannot shadow a real
 * message. [Event.Unhandled] still surfaces anything else.
 *
 * ## Ownership of the connection
 *
 * As with [VideoChannel], this class reads [connection] directly and refuses
 * messages addressed elsewhere; a session running several channels at once needs
 * a router above [FramedConnection] that does not exist yet. Consequently
 * exactly one coroutine may be reading at a time — collect [pcm] *or* call
 * [receiveEvent], not both concurrently. [stopCapture] is deliberately
 * send-only so that it can be called from another coroutine while [pcm] is being
 * collected; the collector sees the head unit's reply and completes.
 */
class MicrophoneChannel(
    private val connection: FramedConnection,
    /**
     * The channel number the head unit assigned to its microphone service.
     *
     * **Not a protocol constant**, for the reason spelled out in
     * [dev.headway.protocol.framing.ChannelId]: the authoritative value is the
     * `Service.id` of the advertised entry whose `media_source_service` carries
     * a PCM `audio_config`. aasdk's own static table puts the microphone at 9
     * (`aasdk/src/Channel/MediaSource/Audio/MicrophoneAudioChannel.cpp`
     * L129-L131 passes `ChannelId::MEDIA_SOURCE_MICROPHONE`), which is the
     * default here and what Headway advertises, but a real unit picks its own.
     */
    val channelId: Int = ChannelId.MEDIA_SOURCE_MICROPHONE.id,
    /**
     * The PCM format the head unit advertised. Used to decode samples and to
     * report chunk durations; it is not sent anywhere.
     */
    val format: MicrophoneFormat = MicrophoneFormat.CAR_MICROPHONE,
    /** Narrates each step; wired to the debug log export in the app. */
    private val onStep: (String) -> Unit = {},
) {

    /** What the head unit answered [open]'s `Setup` with. */
    data class SetupResponse(
        val status: Config.Status,
        /**
         * How many `DATA` messages the head unit will send before it expects an
         * `Ack`, or null if it left the optional field unset. openauto
         * advertises 1
         * (`openauto/src/autoapp/Service/MediaSource/MediaSourceService.cpp`
         * L144-L148).
         *
         * Headway acknowledges every message regardless, so this is reported
         * rather than acted on — see [Event.AudioReceived].
         */
        val maxUnacked: Int?,
        /** Configuration indices offered. openauto sends `[0]` (same lines). */
        val configurationIndices: List<Int>,
    ) {
        val ready: Boolean get() = status == Config.Status.STATUS_READY
    }

    /** The head unit's answer to a microphone open or close request. */
    data class CaptureResponse(
        /**
         * `MicrophoneResponse.status`, a `required int32` carrying a
         * `aap_protobuf.shared.MessageStatus` value. openauto sends
         * `STATUS_SUCCESS` (0) when its audio input starts and on every close,
         * and `STATUS_INTERNAL_ERROR` (-7) when the input fails to start
         * (`openauto/.../MediaSourceService.cpp` L189-L192, L207-L210,
         * L228-L230).
         */
        val status: Int,
        /**
         * `MicrophoneResponse.session_id`, echoed back in every [Ack].
         *
         * Null when the head unit omits the optional field. **openauto always
         * sends -1**: it initialises `session_` to -1 in its constructor
         * (`openauto/.../MediaSourceService.cpp` L28) and there is no assignment
         * to it anywhere in the class — the only three uses are the
         * `set_session_id(session_)` calls in the three response paths (L190,
         * L208, L229). A phone must therefore not treat a negative session id as
         * an error.
         */
        val sessionId: Int?,
        /** True when this answered a request to open, false a request to close. */
        val opening: Boolean,
    ) {
        /** `MessageStatus.STATUS_SUCCESS` is 0; every failure code is negative. */
        val ok: Boolean get() = status == STATUS_SUCCESS
    }

    /** Something the head unit sent on this channel. */
    sealed interface Event {
        /** The setup response, `Config`. */
        data class ConfigReceived(val response: SetupResponse) : Event

        /** A `MicrophoneResponse`, to either an open or a close request. */
        data class CaptureStateChanged(val response: CaptureResponse) : Event

        /**
         * A `DATA` message, already decoded and already acknowledged — the [Ack]
         * is sent by [receiveEvent] before this is returned, so that a slow
         * consumer of [pcm] cannot stall the head unit's capture.
         */
        data class AudioReceived(val chunk: PcmChunk) : Event

        /**
         * A message id this channel does not implement.
         *
         * Surfaced rather than thrown for the reason aasdk logs and re-arms
         * instead of failing the channel
         * (`aasdk/src/Channel/MediaSource/MediaSourceService.cpp` L92-L95).
         */
        data class Unhandled(val messageId: Int, val payload: ByteArray) : Event
    }

    /** The head unit's setup response, once [open] has read it. */
    var setupResponse: SetupResponse? = null
        private set

    /** The session id from the last `MicrophoneResponse`; echoed in every `Ack`. */
    var sessionId: Int? = null
        private set

    /** True between a successful [startCapture] and the reply to [stopCapture]. */
    var capturing: Boolean = false
        private set

    /**
     * The `open` flag of the last `MicrophoneRequest` sent, or null if none has
     * been. `MicrophoneResponse` carries no field saying which request it
     * answers — it is `{ status, session_id }` and nothing else
     * (`aasdk/protobuf/aap_protobuf/service/media/source/message/MicrophoneResponse.proto`) —
     * so the only way to tell an open reply from a close reply is to remember
     * what was asked.
     */
    private var lastRequestedOpen: Boolean? = null

    /** `DATA` messages received. */
    var chunksReceived: Long = 0L
        private set

    /** Sample frames received, across every chunk. */
    var framesReceived: Long = 0L
        private set

    /** Acknowledgements sent — one per `DATA`. */
    var acksSent: Long = 0L
        private set

    // --- setup --------------------------------------------------------------

    /**
     * Sends `Setup` and waits for the head unit's `Config`.
     *
     * `Setup { required MediaCodecType type = 1 }` with `MEDIA_CODEC_AUDIO_PCM`
     * (1) serialises to the two bytes `08 01`. The codec is the one the head
     * unit advertised in `MediaSourceService.available_type`, whose schema
     * default is `MEDIA_CODEC_AUDIO_PCM`
     * (`aasdk/protobuf/aap_protobuf/service/media/source/MediaSourceService.proto`
     * L10) and which openauto sets explicitly
     * (`openauto/.../MediaSourceService.cpp` L74-L75).
     *
     * A `STATUS_WAIT` response is returned rather than thrown on, exactly as in
     * [VideoChannel]: it is a state, not a protocol violation, and no reference
     * says what follows it.
     */
    suspend fun open(codec: MediaCodecType = MediaCodecType.MEDIA_CODEC_AUDIO_PCM): SetupResponse {
        connection.send(
            specific(AvMessageId.SETUP, Setup.newBuilder().setType(codec).build().toByteArray())
        )
        onStep("microphone setup requested (${codec.name})")
        while (true) {
            val event = receiveEvent()
            if (event is Event.ConfigReceived) return event.response
        }
    }

    // --- capture ------------------------------------------------------------

    /**
     * Asks the head unit to open its microphone and waits for the answer.
     *
     * The defaults reproduce, byte for byte, what a real Android Auto phone
     * sends. aa-proxy-rs builds the open request by hand from a decompiled
     * Gearhead as `08 01 10 00 18 00 20 02` — field 1 true, fields 2 and 3
     * false, field 4 = 2 — and the close request as just `08 00`
     * (`aa-proxy-rs/src/bt_sco_media_bridge.rs` L825-L836). Emitting the two
     * false fields explicitly rather than letting proto2 elide them is
     * deliberate: it is what the wire capture shows, and a head unit that
     * distinguishes "absent" from "false" would notice.
     *
     * @param ancEnabled `MicrophoneRequest.anc_enabled` — acoustic noise
     *   cancellation. openauto only logs it (`openauto/.../MediaSourceService.cpp`
     *   L178-L180); no reference implements it.
     * @param ecEnabled `MicrophoneRequest.ec_enabled` — echo cancellation, also
     *   only logged.
     * @param maxUnacked `MicrophoneRequest.max_unacked`. **The name and the
     *   observed value disagree.** aasdk's schema calls field 4 `max_unacked`
     *   (`.../source/message/MicrophoneRequest.proto` L9); aa-proxy-rs's comment
     *   on the same field, derived from decompiled Gearhead, calls it
     *   "mode/source" and sends 2 (`bt_sco_media_bridge.rs` L825-L836). aasdk's
     *   name is used here per CLAUDE.md, and Gearhead's value is the default
     *   because it is the only value ever observed on a wire.
     */
    suspend fun startCapture(
        ancEnabled: Boolean = false,
        ecEnabled: Boolean = false,
        maxUnacked: Int = GEARHEAD_MAX_UNACKED,
    ): CaptureResponse {
        connection.send(
            specific(
                AvMessageId.MICROPHONE_REQUEST,
                MicrophoneRequest.newBuilder()
                    .setOpen(true)
                    .setAncEnabled(ancEnabled)
                    .setEcEnabled(ecEnabled)
                    .setMaxUnacked(maxUnacked)
                    .build()
                    .toByteArray(),
            )
        )
        lastRequestedOpen = true
        onStep("microphone open requested")
        while (true) {
            val event = receiveEvent()
            if (event is Event.CaptureStateChanged) return event.response
        }
    }

    /**
     * Asks the head unit to close its microphone. **Send-only.**
     *
     * It does not wait for the reply, so that it can be called while another
     * coroutine is collecting [pcm] — that collector is the channel's reader and
     * will see the reply and complete. A caller that is not collecting should
     * follow this with [awaitCaptureResponse].
     *
     * The close request carries field 1 alone (`08 00`); Gearhead omits the
     * other three (`aa-proxy-rs/src/bt_sco_media_bridge.rs` L825-L836).
     */
    suspend fun stopCapture() {
        connection.send(
            specific(
                AvMessageId.MICROPHONE_REQUEST,
                MicrophoneRequest.newBuilder().setOpen(false).build().toByteArray(),
            )
        )
        lastRequestedOpen = false
        onStep("microphone close requested")
    }

    /** Reads until a `MicrophoneResponse` arrives, discarding audio that arrives first. */
    suspend fun awaitCaptureResponse(): CaptureResponse {
        while (true) {
            val event = receiveEvent()
            if (event is Event.CaptureStateChanged) return event.response
        }
    }

    // --- audio --------------------------------------------------------------

    /**
     * The microphone audio, chunk by chunk, with each chunk's presentation
     * timestamp.
     *
     * Cold: collecting starts reading the channel, and only one collector may
     * run at a time (see the class KDoc on connection ownership). The flow
     * completes when the head unit answers a [stopCapture] — a close reply while
     * collecting is the channel's end-of-stream. It does *not* complete on an
     * open reply, which can legitimately arrive first if [startCapture] was
     * skipped.
     *
     * Backpressure is deliberately not propagated to the head unit: each `DATA`
     * is acknowledged inside [receiveEvent] before it is emitted, so a slow
     * collector delays Headway, not the car's capture. The alternative — acking
     * after the collector returns — would let a stalled consumer throttle the
     * microphone and lose audio at the source, where it cannot be recovered.
     */
    fun pcm(): Flow<PcmChunk> = flow {
        while (true) {
            when (val event = receiveEvent()) {
                is Event.AudioReceived -> emit(event.chunk)
                is Event.CaptureStateChanged -> if (!event.response.opening) return@flow
                else -> Unit
            }
        }
    }

    /** [pcm] without the timestamps, for a consumer that only wants to hear it. */
    fun samples(): Flow<ShortArray> = pcm().map { it.samples }

    // --- receive ------------------------------------------------------------

    /**
     * Reads exactly one message, decodes it, updates channel state, and — for a
     * `DATA` message — acknowledges it.
     */
    suspend fun receiveEvent(): Event {
        val message = connection.receive()
        if (message.channelId != channelId) {
            throw MicrophoneChannelException(
                "message for ${ChannelId.describe(message.channelId)} arrived on the microphone " +
                    "channel's connection; a session with more than one open channel needs a " +
                    "router above FramedConnection"
            )
        }

        return when (message.messageId) {
            AvMessageId.DATA -> onData(message.payload)

            // 0x8003 is what a phone observes on the wire; 0x8000 is what aasdk
            // emits. See the class KDoc — this channel never legitimately
            // receives a SETUP, so accepting it here shadows nothing.
            AvMessageId.CONFIG -> onConfig(message.payload, aasdkId = false)
            AvMessageId.SETUP -> onConfig(message.payload, aasdkId = true)

            // Likewise 0x8006 vs aasdk's 0x8005.
            AvMessageId.MICROPHONE_RESPONSE -> onCaptureResponse(message.payload, aasdkId = false)
            AvMessageId.MICROPHONE_REQUEST -> onCaptureResponse(message.payload, aasdkId = true)

            else -> {
                onStep("unhandled ${AvMessageId.describe(message.messageId)}")
                Event.Unhandled(message.messageId, message.payload)
            }
        }
    }

    private fun onConfig(payload: ByteArray, aasdkId: Boolean): Event {
        val config = Config.parseFrom(payload)
        val response = SetupResponse(
            status = config.status,
            maxUnacked = if (config.hasMaxUnacked()) config.maxUnacked else null,
            configurationIndices = config.configurationIndicesList.toList(),
        )
        setupResponse = response
        onStep(
            "microphone config: ${response.status.name}, window ${response.maxUnacked ?: "unstated"}" +
                if (aasdkId) " (on 0x8000, the aasdk id)" else ""
        )
        return Event.ConfigReceived(response)
    }

    private fun onCaptureResponse(payload: ByteArray, aasdkId: Boolean): Event {
        val response = MicrophoneResponse.parseFrom(payload)
        val sessionId = if (response.hasSessionId()) response.sessionId else null
        this.sessionId = sessionId

        // Falls back to "the opposite of the current state" only for an
        // unsolicited reply, which no reference sends but none forbids either.
        val opening = lastRequestedOpen ?: !capturing
        capturing = opening && response.status == STATUS_SUCCESS
        onStep(
            "microphone ${if (opening) "opened" else "closed"}: status ${response.status}, " +
                "session ${sessionId ?: "unstated"}" + if (aasdkId) " (on 0x8005, the aasdk id)" else ""
        )
        return Event.CaptureStateChanged(CaptureResponse(response.status, sessionId, opening))
    }

    private suspend fun onData(payload: ByteArray): Event {
        val data = MediaFrame.parseData(payload)
        if (format.bitsPerSample != PcmDecoder.BYTES_PER_S16 * 8) {
            throw MicrophoneChannelException(
                "head unit advertised ${format.bitsPerSample}-bit microphone audio; only 16-bit " +
                    "linear PCM is decoded, and no reference implements anything else"
            )
        }
        val chunk = PcmChunk(data.timestampMicros, PcmDecoder.decodeS16Le(data.media))
        chunksReceived++
        framesReceived += chunk.frameCount(format)
        sendAck()
        return Event.AudioReceived(chunk)
    }

    /**
     * Acknowledges one `DATA`.
     *
     * `Ack { required int32 session_id = 1; optional uint32 ack = 2 }` with the
     * session id the head unit gave us. openauto ignores acks on this channel
     * entirely — its handler only re-arms the receive
     * (`openauto/.../MediaSourceService.cpp` L161-L165) — but aasdk parses them
     * and hands them to the event handler
     * (`aasdk/src/Channel/MediaSource/MediaSourceService.cpp` L150-L159), and
     * `Config.max_unacked` only means anything if the phone sends them. Acking
     * every message satisfies any window; not acking would stall a head unit
     * that honours its own.
     *
     * The value 1 is what openauto sends on the sink side for each frame
     * (`openauto/src/autoapp/Service/MediaSink/VideoMediaSinkService.cpp`
     * L177-L186); nothing in any reference reads it.
     */
    private suspend fun sendAck() {
        val ack = Ack.newBuilder()
            .setSessionId(sessionId ?: NO_SESSION)
            .setAck(ACK_VALUE)
            .build()
        connection.send(specific(AvMessageId.ACK, ack.toByteArray()))
        acksSent++
    }

    // --- helpers ------------------------------------------------------------

    /** Encrypted, `MessageType::SPECIFIC` — see [VideoChannel] for the citations. */
    private fun specific(messageId: Int, payload: ByteArray) = AapMessage(
        channelId = channelId,
        control = false,
        encrypted = true,
        messageId = messageId,
        payload = payload,
    )

    companion object {
        /**
         * `MessageStatus.STATUS_SUCCESS`. Source:
         * `aasdk/protobuf/aap_protobuf/shared/MessageStatus.proto` L6 — note
         * that this enum's success value is 0 and every error is negative, so
         * `status != 0` is the failure test, not `status < 0`.
         */
        const val STATUS_SUCCESS: Int = 0

        /**
         * The session id used in an `Ack` before the head unit has given one.
         * openauto uses the same sentinel for the value it never assigns
         * (`openauto/.../MediaSourceService.cpp` L28).
         */
        const val NO_SESSION: Int = -1

        /** `Ack.ack`; openauto sends 1 per media message and nothing reads it. */
        const val ACK_VALUE: Int = 1

        /**
         * `MicrophoneRequest.max_unacked` as decompiled Gearhead sends it
         * (`aa-proxy-rs/src/bt_sco_media_bridge.rs` L825-L836, the trailing
         * `20 02`).
         */
        const val GEARHEAD_MAX_UNACKED: Int = 2
    }
}

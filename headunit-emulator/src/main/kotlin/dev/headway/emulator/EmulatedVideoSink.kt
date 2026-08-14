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

import aap_protobuf.service.media.shared.message.ConfigOuterClass.Config
import aap_protobuf.service.media.shared.message.MediaCodecTypeOuterClass.MediaCodecType
import aap_protobuf.service.media.shared.message.SetupOuterClass.Setup
import aap_protobuf.service.media.shared.message.StartOuterClass.Start
import aap_protobuf.service.media.source.message.AckOuterClass.Ack
import aap_protobuf.service.media.video.message.VideoFocusModeOuterClass.VideoFocusMode
import aap_protobuf.service.media.video.message.VideoFocusNotificationOuterClass.VideoFocusNotification
import aap_protobuf.service.media.video.message.VideoFocusRequestNotificationOuterClass.VideoFocusRequestNotification
import dev.headway.protocol.channel.AvMessageId
import dev.headway.protocol.channel.MediaFrame
import dev.headway.protocol.framing.AapMessage
import dev.headway.protocol.framing.ChannelId
import dev.headway.protocol.io.FramedConnection

/**
 * One H.264 NAL unit located inside an Annex-B byte stream.
 *
 * @property type `nal_unit_type`, the low five bits of the header byte. 5 is an
 *   IDR slice, 1 a non-IDR slice, 6 SEI, 7 SPS, 8 PPS, 9 an access unit
 *   delimiter. The 5-bit mask and the IDR/non-IDR meanings are the ones
 *   aa-proxy-rs uses to find keyframes (`aa-proxy-rs/src/media_tap.rs`
 *   L884-L905).
 * @property refIdc `nal_ref_idc`, bits 5-6 of the same byte. Recorded because a
 *   zero here on a slice means the picture is not used as a reference, which is
 *   the sort of thing worth seeing in a dump.
 * @property startCodeLength 3 for `00 00 01`, 4 for `00 00 00 01`. Both appear
 *   in real streams and a parser that handles only one silently loses NAL units.
 * @property offset index of the header byte within the buffer.
 * @property length bytes from [offset] up to the next start code, or the end of
 *   the buffer.
 */
data class NalUnit(
    val type: Int,
    val refIdc: Int,
    val startCodeLength: Int,
    val offset: Int,
    val length: Int,
)

/**
 * Annex-B byte-stream parsing.
 *
 * The video channel carries H.264 as an Annex-B byte stream — NAL units
 * separated by `00 00 01` or `00 00 00 01` start codes. AACS pins its encoder
 * caps to `stream-format=byte-stream`
 * (`AACS/AAServer/src/VideoChannelHandler.cpp` L75-L78) and its head-unit-side
 * appsrc declares the identical caps; aa-proxy-rs walks the same start codes to
 * classify frames (`aa-proxy-rs/src/media_tap.rs` L884-L905). Nothing in the AAP
 * layer describes the payload, so this is the only structure the head unit has
 * to work with.
 *
 * Note what is *not* a start code: `00 00 03`, the emulation prevention byte an
 * encoder inserts precisely so that payload data cannot look like a start code.
 * A scanner that treats any `00 00` as a boundary splits NAL units in the middle
 * and produces garbage types.
 */
object AnnexB {

    /**
     * Splits [data] into its NAL units.
     *
     * Bytes before the first start code are ignored, matching what a decoder
     * does with a stream it joins mid-flight. An empty NAL — two adjacent start
     * codes — is dropped rather than reported with a fabricated type.
     */
    fun parse(data: ByteArray): List<NalUnit> {
        val startCodes = ArrayList<IntArray>() // (offset of the start code, its length)
        var i = 0
        while (i + 2 < data.size) {
            if (data[i] == ZERO && data[i + 1] == ZERO) {
                if (data[i + 2] == ONE) {
                    startCodes += intArrayOf(i, 3)
                    i += 3
                    continue
                }
                // A run of more than three zeroes is legal (leading_zero_8bits),
                // so this must not consume the zeroes it did not match.
                if (i + 3 < data.size && data[i + 2] == ZERO && data[i + 3] == ONE) {
                    startCodes += intArrayOf(i, 4)
                    i += 4
                    continue
                }
            }
            i++
        }

        val units = ArrayList<NalUnit>(startCodes.size)
        for ((index, startCode) in startCodes.withIndex()) {
            val offset = startCode[0] + startCode[1]
            val end = if (index + 1 < startCodes.size) startCodes[index + 1][0] else data.size
            if (offset >= end) continue
            val header = data[offset].toInt() and 0xFF
            units += NalUnit(
                type = header and 0x1F,
                refIdc = (header shr 5) and 0x03,
                startCodeLength = startCode[1],
                offset = offset,
                length = end - offset,
            )
        }
        return units
    }

    /** The `nal_unit_type` of every NAL unit in [data], in stream order. */
    fun types(data: ByteArray): List<Int> = parse(data).map { it.type }

    private const val ZERO: Byte = 0
    private const val ONE: Byte = 1
}

/** How the emulated sink answers setup, and what it claims about its decoder. */
data class VideoSinkConfig(
    /**
     * `STATUS_READY` (2) means the video output initialised. openauto sends
     * `STATUS_WAIT` (1) when its output fails to init
     * (`openauto/src/autoapp/Service/MediaSink/VideoMediaSinkService.cpp`
     * L108-L112); a test can select that to exercise the phone's handling.
     */
    val status: Config.Status = Config.Status.STATUS_READY,
    /**
     * Flow-control window, `Config.max_unacked`. openauto advertises 1
     * (`openauto/.../VideoMediaSinkService.cpp` L117), i.e. it acknowledges each
     * media message before the next is expected.
     */
    val maxUnacked: Int = 1,
    /**
     * Which advertised `video_configs` entries this unit will accept. openauto
     * and aa-proxy-rs both send `[0]` (`openauto/.../VideoMediaSinkService.cpp`
     * L118; `aa-proxy-rs/src/display.rs` L308-L316).
     */
    val configurationIndices: List<Int> = listOf(0),
    /**
     * Whether to chain a `VideoFocusNotification` onto the setup response, as
     * openauto does (`openauto/.../VideoMediaSinkService.cpp` L120-L125).
     */
    val notifyFocusAfterSetup: Boolean = true,
    /**
     * The focus openauto reports, both unprompted after setup and in answer to
     * every focus request (`openauto/.../VideoMediaSinkService.cpp` L233-L245).
     */
    val focus: VideoFocusMode = VideoFocusMode.VIDEO_FOCUS_PROJECTED,
    /** `Ack.ack`. openauto sends 1 for every frame (`openauto/.../VideoMediaSinkService.cpp` L181). */
    val ackValue: Int = 1,
)

/**
 * The **head-unit** side of the video channel: it answers setup, accepts start,
 * acknowledges every media message, and records what arrived.
 *
 * Mirrors `aasdk/src/Channel/MediaSink/Video/VideoMediaSinkService.cpp` and
 * `openauto/src/autoapp/Service/MediaSink/VideoMediaSinkService.cpp`. Where those
 * hand the payload to a decoder, this parses the Annex-B framing and keeps the
 * bytes, so a test can assert that an SPS/PPS/IDR sequence really arrived intact
 * and in order rather than that some bytes arrived.
 *
 * Per ADR 0002 this shares `core-protocol` with the phone and is therefore a
 * weak oracle for the wire format — but not for *this* question. Frame ordering,
 * timestamp monotonicity and NAL integrity are properties of the channel, and a
 * symmetric bug in the framing layer does not manufacture them.
 *
 * ## Codec configuration is acknowledged too
 *
 * openauto's `onMediaIndication` — the `CODEC_CONFIG` path — delegates to
 * `onMediaWithTimestampIndication(0, buffer)`
 * (`openauto/.../VideoMediaSinkService.cpp` L190-L192), which writes the buffer
 * and then acks (L171-L187). So the SPS/PPS blob consumes one slot of the
 * acknowledgement window exactly like a frame does, and is recorded here with
 * timestamp 0 for the same reason openauto passes 0.
 */
class EmulatedVideoSink(
    private val connection: FramedConnection,
    /**
     * The channel this sink serves. Not a protocol constant — see
     * [dev.headway.protocol.channel.VideoChannel]; it is whatever id the unit
     * advertised for its video service.
     */
    val channelId: Int = ChannelId.MEDIA_SINK_VIDEO.id,
    val config: VideoSinkConfig = VideoSinkConfig(),
    private val onStep: (String) -> Unit = {},
) {

    /** Whether a recorded media message carried a timestamp. */
    enum class MediaKind { CODEC_CONFIG, DATA }

    /** One media message as it arrived. */
    data class ReceivedFrame(
        /** Arrival order across both kinds, starting at 0. */
        val index: Int,
        val kind: MediaKind,
        /**
         * Presentation timestamp in microseconds. Always 0 for
         * [MediaKind.CODEC_CONFIG], which carries no timestamp header.
         */
        val timestampMicros: Long,
        /** The media payload, with the timestamp header already stripped. */
        val bytes: ByteArray,
        /** NAL units found in [bytes] by [AnnexB.parse]. */
        val nalUnits: List<NalUnit>,
    ) {
        /** `nal_unit_type` of each NAL unit, in stream order. */
        val nalTypes: List<Int> get() = nalUnits.map { it.type }

        /** True when this access unit contains an IDR slice (type 5) — a keyframe. */
        val containsIdr: Boolean get() = nalUnits.any { it.type == NAL_IDR }
    }

    private val lock = Any()
    private val received = ArrayList<ReceivedFrame>()
    private val focusRequests = ArrayList<VideoFocusRequestNotification>()
    private val unhandled = ArrayList<Int>()

    /** The codec the phone asked for in its `Setup`, once it has. */
    var requestedCodec: MediaCodecType? = null
        private set

    /**
     * The session id from `Start`, echoed in every `Ack`.
     *
     * openauto initialises this to -1 and overwrites it on `Start`
     * (`openauto/.../VideoMediaSinkService.cpp` L151-L159); acks sent before a
     * `Start` therefore carry -1, which is behaviour worth reproducing rather
     * than papering over.
     */
    var sessionId: Int = NO_SESSION
        private set

    /** The configuration index the phone selected in `Start`. */
    var configurationIndex: Int? = null
        private set

    /** True once the phone has sent `Stop`. */
    var stopped: Boolean = false
        private set

    /** Acknowledgements sent. */
    var acksSent: Long = 0L
        private set

    /**
     * Whether this unit is actually putting the phone's picture on its screen.
     *
     * Modelled rather than assumed, because the difference is the whole of a
     * real failure. A 2021 Chevrolet Infotainment 3 unit answered `Config`,
     * accepted `Start`, and acknowledged 1434 frames across fifteen seconds
     * while its screen stayed on "Connecting Android Auto phone" — every frame
     * decoded and discarded, because nothing had asked it for the display. The
     * emulator could not reproduce that, because it *volunteers* focus the way
     * openauto does ([VideoSinkConfig.notifyFocusAfterSetup]) and so was
     * projecting before the question could arise.
     *
     * With `notifyFocusAfterSetup = false` this stays false until the phone
     * asks, which is a head unit that behaves like the car.
     */
    var projecting: Boolean = false
        private set

    /**
     * Frames received while [projecting] was false — decoded and thrown away.
     *
     * Nonzero means the phone is streaming to a screen that is not showing it,
     * which on the wire is indistinguishable from everything working.
     */
    var framesDiscarded: Long = 0L
        private set

    /** Every media message received, in arrival order. */
    val frames: List<ReceivedFrame> get() = synchronized(lock) { received.toList() }

    /** Only the timestamped frames — codec configuration excluded. */
    val dataFrames: List<ReceivedFrame> get() = frames.filter { it.kind == MediaKind.DATA }

    val frameCount: Int get() = synchronized(lock) { received.size }

    /** Focus requests the phone sent, in arrival order. */
    val videoFocusRequests: List<VideoFocusRequestNotification>
        get() = synchronized(lock) { focusRequests.toList() }

    /** Message ids this sink did not implement, in arrival order. */
    val unhandledMessageIds: List<Int> get() = synchronized(lock) { unhandled.toList() }

    /**
     * Every media byte received, concatenated in arrival order.
     *
     * This is what a decoder would have been fed, and comparing it against what
     * the phone encoded is the strongest statement the emulator can make: not
     * "frames arrived" but "the byte stream is the one that was sent".
     */
    fun mediaBytes(): ByteArray {
        val all = frames
        val out = ByteArray(all.sumOf { it.bytes.size })
        var offset = 0
        for (frame in all) {
            frame.bytes.copyInto(out, offset)
            offset += frame.bytes.size
        }
        return out
    }

    /** `nal_unit_type` of every NAL unit received, across all frames, in order. */
    fun nalTypes(): List<Int> = frames.flatMap { it.nalTypes }

    /**
     * Services the channel.
     *
     * @param mediaMessages return once this many media messages — codec
     *   configuration plus frames — have been received and acknowledged. Null
     *   means run until the phone sends `Stop`, which blocks forever if it never
     *   does; a caller that cannot guarantee a `Stop` should state a count.
     */
    suspend fun run(mediaMessages: Int? = null) {
        if (mediaMessages != null && frameCount >= mediaMessages) return
        while (true) {
            handle(connection.receive())
            if (mediaMessages != null) {
                if (frameCount >= mediaMessages) return
            } else if (stopped) {
                return
            }
        }
    }

    /** Decodes and services one message. Exposed so a test can drive step by step. */
    suspend fun handle(message: AapMessage) {
        if (message.channelId != channelId) {
            throw IllegalStateException(
                "message for ${ChannelId.describe(message.channelId)} arrived on the video sink's " +
                    "connection"
            )
        }

        when (message.messageId) {
            AvMessageId.SETUP -> onSetup(message.payload)
            AvMessageId.START -> onStart(message.payload)
            AvMessageId.STOP -> onStop()
            AvMessageId.CODEC_CONFIG -> onCodecConfig(message.payload)
            AvMessageId.DATA -> onData(message.payload)
            AvMessageId.VIDEO_FOCUS_REQUEST -> onFocusRequest(message.payload)
            else -> {
                // aasdk logs an unhandled id and re-arms its receive rather than
                // failing the channel (VideoMediaSinkService.cpp L128-L132).
                synchronized(lock) { unhandled += message.messageId }
                onStep("unhandled ${AvMessageId.describe(message.messageId)}")
            }
        }
    }

    // --- setup --------------------------------------------------------------

    /**
     * Answers `Setup` with `Config`, then — as openauto does — an unsolicited
     * `VideoFocusNotification`.
     *
     * The ordering matters to at least one reference: AACS's phone side waits
     * for the focus notification, not the config, before sending `Start`
     * (`AACS/AAServer/src/VideoChannelHandler.cpp` L181-L183). Sending the
     * config alone would deadlock such a phone.
     */
    private suspend fun onSetup(payload: ByteArray) {
        val setup = Setup.parseFrom(payload)
        requestedCodec = setup.type
        onStep("setup request: ${setup.type.name}")

        val response = Config.newBuilder()
            .setStatus(config.status)
            .setMaxUnacked(config.maxUnacked)
            .addAllConfigurationIndices(config.configurationIndices)
            .build()
        connection.send(specific(AvMessageId.CONFIG, response.toByteArray()))
        onStep("config sent: ${config.status.name}, window ${config.maxUnacked}")

        if (config.notifyFocusAfterSetup) {
            sendFocusNotification(unsolicited = false)
        }
    }

    private suspend fun onStart(payload: ByteArray) {
        val start = Start.parseFrom(payload)
        if (start.configurationIndex !in config.configurationIndices) {
            // A real unit was never observed refusing this, but accepting an
            // index we never offered would let a phone bug pass here and fail in
            // a car, which is the failure mode this emulator exists to prevent.
            throw IllegalStateException(
                "phone selected video configuration index ${start.configurationIndex}, " +
                    "which this unit did not offer (${config.configurationIndices})"
            )
        }
        sessionId = start.sessionId
        configurationIndex = start.configurationIndex
        onStep("start: session ${start.sessionId}, configuration ${start.configurationIndex}")
    }

    private fun onStop() {
        // openauto parses the empty Stop and just re-arms its receive
        // (VideoMediaSinkService.cpp L162-L169).
        stopped = true
        onStep("stop: session $sessionId")
    }

    // --- media --------------------------------------------------------------

    /** `CODEC_CONFIG` has no timestamp header; openauto records it as timestamp 0. */
    private suspend fun onCodecConfig(payload: ByteArray) {
        record(MediaKind.CODEC_CONFIG, timestampMicros = 0L, media = payload)
        sendAck()
    }

    private suspend fun onData(payload: ByteArray) {
        val data = MediaFrame.parseData(payload)
        record(MediaKind.DATA, data.timestampMicros, data.media)
        sendAck()
    }

    private fun record(kind: MediaKind, timestampMicros: Long, media: ByteArray) {
        // Counted before the frame is stored, and acknowledged either way: a
        // head unit that is not projecting still decodes and acks. That is
        // precisely why the failure is invisible on the wire.
        if (!projecting) framesDiscarded++
        synchronized(lock) {
            received += ReceivedFrame(
                index = received.size,
                kind = kind,
                timestampMicros = timestampMicros,
                bytes = media,
                nalUnits = AnnexB.parse(media),
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

    // --- video focus --------------------------------------------------------

    private suspend fun onFocusRequest(payload: ByteArray) {
        val request = VideoFocusRequestNotification.parseFrom(payload)
        synchronized(lock) { focusRequests += request }
        onStep("focus request: ${request.mode.name} (${request.reason.name})")

        // openauto answers every request with its configured focus regardless of
        // what was asked for, and separately acts on VIDEO_FOCUS_NATIVE by
        // relinquishing the display (VideoMediaSinkService.cpp L216-L231). This
        // emulator has no display to relinquish, so it only answers.
        sendFocusNotification(unsolicited = false)
    }

    private suspend fun sendFocusNotification(unsolicited: Boolean) {
        val notification = VideoFocusNotification.newBuilder()
            .setFocus(config.focus)
            .setUnsolicited(unsolicited)
            .build()
        connection.send(
            specific(AvMessageId.VIDEO_FOCUS_NOTIFICATION, notification.toByteArray())
        )
        // Announcing PROJECTED is the moment the picture reaches the screen; see
        // [projecting]. NATIVE is the unit taking its own display back.
        projecting = config.focus == VideoFocusMode.VIDEO_FOCUS_PROJECTED
        onStep("focus notification: ${config.focus.name}; projecting = $projecting")
    }

    // --- helpers ------------------------------------------------------------

    /** Encrypted, `MessageType::SPECIFIC` — see [dev.headway.protocol.channel.VideoChannel]. */
    private fun specific(messageId: Int, payload: ByteArray) = AapMessage(
        channelId = channelId,
        control = false,
        encrypted = true,
        messageId = messageId,
        payload = payload,
    )

    companion object {
        /** openauto's pre-`Start` session id (`VideoMediaSinkService.cpp` L151-L159). */
        const val NO_SESSION: Int = -1

        /** `nal_unit_type` 5, an IDR slice (`aa-proxy-rs/src/media_tap.rs` L884-L905). */
        const val NAL_IDR: Int = 5
    }
}

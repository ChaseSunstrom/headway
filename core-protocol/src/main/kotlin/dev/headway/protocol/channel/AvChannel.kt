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

import dev.headway.protocol.framing.AapMessage

/**
 * Message ids shared by every audio/video channel.
 *
 * Transcribed from
 * `aap_protobuf/service/media/sink/MediaMessageId.proto`. The two data ids are
 * small numbers and the control ids all sit above `0x8000`, which is a useful
 * sanity check when reading a frame dump.
 */
object AvMessageId {
    /** Media payload with an 8-byte timestamp header. */
    const val DATA: Int = 0x0000

    /** Codec configuration (H.264 SPS/PPS). **No timestamp header** — see [MediaFrame]. */
    const val CODEC_CONFIG: Int = 0x0001

    const val SETUP: Int = 0x8000
    const val START: Int = 0x8001
    const val STOP: Int = 0x8002
    const val CONFIG: Int = 0x8003
    const val ACK: Int = 0x8004
    const val MICROPHONE_REQUEST: Int = 0x8005
    const val MICROPHONE_RESPONSE: Int = 0x8006
    const val VIDEO_FOCUS_REQUEST: Int = 0x8007
    const val VIDEO_FOCUS_NOTIFICATION: Int = 0x8008

    fun describe(id: Int): String = when (id) {
        DATA -> "DATA"
        CODEC_CONFIG -> "CODEC_CONFIG"
        SETUP -> "SETUP"
        START -> "START"
        STOP -> "STOP"
        CONFIG -> "CONFIG"
        ACK -> "ACK"
        MICROPHONE_REQUEST -> "MICROPHONE_REQUEST"
        MICROPHONE_RESPONSE -> "MICROPHONE_RESPONSE"
        VIDEO_FOCUS_REQUEST -> "VIDEO_FOCUS_REQUEST"
        VIDEO_FOCUS_NOTIFICATION -> "VIDEO_FOCUS_NOTIFICATION"
        else -> "UNKNOWN_AV(0x%04x)".format(id)
    }
}

/**
 * A media payload with its presentation timestamp.
 *
 * ## Wire layout of a `DATA` message
 *
 * ```text
 * [2 bytes  message id     big-endian]   <- handled by AapMessage
 * [8 bytes  presentation timestamp  big-endian unsigned, MICROSECONDS]
 * [n bytes  media payload — H.264 Annex-B for video, PCM for audio]
 * ```
 *
 * Sources: aasdk gates on `payload.size >= sizeof(Timestamp::ValueType)` and
 * offsets the media buffer by that size
 * (`aasdk/src/Channel/MediaSink/Video/VideoMediaSinkService.cpp` L181-L192);
 * the width and `uint64_t` type come from
 * `aasdk/include/aasdk/Messenger/Timestamp.hpp`; big-endian from
 * `aasdk/src/Messenger/Timestamp.cpp` L29-L38 (`boost::endian::native_to_big`);
 * microseconds is stated explicitly in `aa-proxy-rs/src/media_tap.rs` L1041-L1046
 * and corroborated by AACS dividing GStreamer's nanosecond PTS by 1000
 * (`AACS/AAServer/src/VideoChannelHandler.cpp`).
 *
 * **`CODEC_CONFIG` carries no timestamp header.** aasdk routes it straight to
 * `onMediaIndication` without the offset (`VideoMediaSinkService.cpp`
 * L121-L123), and openauto re-emits it with timestamp 0. Applying the 8-byte
 * offset to it would silently eat the first eight bytes of the SPS — which is
 * exactly the sort of failure that shows up as "the car screen stays black".
 */
object MediaFrame {

    /** Width of the presentation-timestamp header on a `DATA` message. */
    const val TIMESTAMP_SIZE: Int = 8

    /**
     * Builds a `DATA` message: timestamp header followed by [media].
     *
     * @param timestampMicros presentation timestamp in **microseconds**.
     */
    fun data(
        channelId: Int,
        media: ByteArray,
        timestampMicros: Long,
        offset: Int = 0,
        length: Int = media.size - offset,
    ): AapMessage {
        require(timestampMicros >= 0) { "timestamp must be non-negative: $timestampMicros" }
        val payload = ByteArray(TIMESTAMP_SIZE + length)
        writeTimestamp(payload, timestampMicros)
        media.copyInto(payload, TIMESTAMP_SIZE, offset, offset + length)
        return AapMessage(
            channelId = channelId,
            control = false,
            encrypted = true,
            messageId = AvMessageId.DATA,
            payload = payload,
        )
    }

    /**
     * Builds a `CODEC_CONFIG` message — raw SPS/PPS with no timestamp header.
     */
    fun codecConfig(channelId: Int, config: ByteArray): AapMessage = AapMessage(
        channelId = channelId,
        control = false,
        encrypted = true,
        messageId = AvMessageId.CODEC_CONFIG,
        payload = config,
    )

    /** The timestamp and media halves of a received `DATA` payload. */
    data class Data(val timestampMicros: Long, val media: ByteArray)

    /**
     * Splits a received `DATA` payload.
     *
     * @throws IllegalArgumentException if the payload is too short to contain a
     *   timestamp, which means the peer is not framing media the way every
     *   reference does.
     */
    fun parseData(payload: ByteArray): Data {
        require(payload.size >= TIMESTAMP_SIZE) {
            "media DATA payload is ${payload.size} bytes, too short for a " +
                "$TIMESTAMP_SIZE-byte timestamp header"
        }
        return Data(
            timestampMicros = readTimestamp(payload),
            media = payload.copyOfRange(TIMESTAMP_SIZE, payload.size),
        )
    }

    private fun writeTimestamp(out: ByteArray, value: Long) {
        for (i in 0 until TIMESTAMP_SIZE) {
            out[i] = (value ushr (8 * (TIMESTAMP_SIZE - 1 - i))).toByte()
        }
    }

    private fun readTimestamp(bytes: ByteArray): Long {
        var value = 0L
        for (i in 0 until TIMESTAMP_SIZE) {
            value = (value shl 8) or (bytes[i].toLong() and 0xFF)
        }
        return value
    }
}

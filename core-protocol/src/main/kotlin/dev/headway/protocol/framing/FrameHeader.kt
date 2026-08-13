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

package dev.headway.protocol.framing

/**
 * Position of a frame within its logical message.
 *
 * Occupies bits 0-1 of the flags byte.
 * Source: `aasdk/include/aasdk/Messenger/FrameType.hpp` L27-L32.
 *
 * Note that [BULK] is `FIRST or LAST` — a complete message in one frame — so
 * these must be compared by masked equality, never by bit test. Testing
 * `flags and FIRST != 0` would match [BULK] too, and would wrongly add the
 * extended size field to every single-frame message. Every reference does the
 * masked comparison (`aa-proxy-rs/src/mitm.rs` L3925, L3932;
 * `WirelessAndroidAutoDongle/.../proxyHandler.cpp` L51;
 * `AACS/AAServer/src/AaCommunicator.cpp` L421).
 */
enum class FrameType(val bits: Int) {
    MIDDLE(0),
    FIRST(1),
    LAST(2),
    BULK(3),
    ;

    /** True when this frame completes a message — i.e. [LAST] or [BULK]. */
    val completesMessage: Boolean get() = this == LAST || this == BULK

    /** True when this frame starts a message — i.e. [FIRST] or [BULK]. */
    val startsMessage: Boolean get() = this == FIRST || this == BULK

    companion object {
        /** Bits 0-1 of the flags byte. */
        const val MASK: Int = 0x03

        fun fromFlags(flags: Int): FrameType = when (flags and MASK) {
            0 -> MIDDLE
            1 -> FIRST
            2 -> LAST
            else -> BULK
        }
    }
}

/**
 * The AAP frame header: the 4- or 8-byte prefix that precedes every frame
 * payload.
 *
 * ## Wire layout (all multi-byte fields big-endian)
 *
 * ```
 * offset 0 : uint8   channel id
 * offset 1 : uint8   flags  — bits 0-1 frame type, bit 2 control, bit 3 encrypted
 * offset 2 : uint16  frame payload size (bytes on the wire for THIS frame)
 * offset 4 : uint32  total message size — PRESENT ONLY on exactly-FIRST frames
 * ```
 *
 * Sources: `aasdk/src/Messenger/FrameHeader.cpp` L25-L30 and L54-L62 (channel
 * and flags); `aasdk/src/Messenger/FrameSize.cpp` L35-L46 (frame size), L48-L62
 * (total size), L73-L75 (2 vs 6 bytes); corroborated independently by
 * `aa-proxy-rs/src/mitm.rs` L1066-L1085 and
 * `WirelessAndroidAutoDongle/.../proxyHandler.cpp` L40-L54.
 *
 * aasdk splits this across two types — its `FrameHeader` is only the first two
 * bytes and its `FrameSize` is the following 2 or 6 — because it reads them in
 * separate socket operations. Headway models the whole prefix as one value; the
 * split is an artefact of aasdk's async read structure, not of the wire format.
 *
 * ## The two size fields are in different units when encrypted
 *
 * This is the subtlest thing in the frame format and a likely source of
 * breakage. On an encrypted FIRST frame:
 *
 * - [payloadLength] is the **ciphertext** length — the TLS record actually on
 *   the wire (`aasdk/src/Messenger/MessageOutStream.cpp` L111-L118 takes it from
 *   the encrypt call's output; `aa-proxy-rs/src/mitm.rs` L1070 likewise).
 * - [totalMessageLength] is the **plaintext** length of the whole reassembled
 *   message (`MessageOutStream.cpp` L103-L127 passes
 *   `message_->getPayload().size()`; `aa-proxy-rs/src/packet_fragment.rs`
 *   L25-L28 documents it as "not an encrypted/ciphertext length").
 *
 * aasdk bridges the two by assuming a fixed 29-byte TLS record overhead
 * (`aasdk/src/Messenger/Cryptor.cpp` L149-L151). That constant is cipher-suite
 * dependent, so Headway does not replicate it: the TLS layer reports the
 * plaintext length it actually produced.
 */
data class FrameHeader(
    /** Raw channel id from byte 0. Kept as [Int] so unknown channels survive. */
    val channelId: Int,
    val frameType: FrameType,
    /**
     * The `CONTROL` flag, bit 2 (0x04).
     *
     * Source: `aasdk/include/aasdk/Messenger/MessageType.hpp` L26-L29, which
     * defines `SPECIFIC = 0, CONTROL = 1 << 2`.
     *
     * **Reference disagreement, now settled by a real capture.** AACS
     * (`AACS/include/enums.h` L16-L19) names the same bit the other way round —
     * `Control = 0, Specific = 1 << 2` — and is internally self-consistent with
     * that naming. `aa-proxy-rs` (`src/mitm.rs` L143) agrees with aasdk.
     *
     * The names disagree; the *behaviour* does not, and that is what matters.
     * Both stacks set this bit on a channel-open message travelling on a
     * service channel and clear it on channel 0 — AACS's phone role sends
     * `Bulk | Encrypted | Specific` on `channelId`
     * (`AACS/AAServer/src/ChannelHandler.cpp` L34-L46), and aa-proxy-rs derives
     * the same rule from observed traffic:
     * `let control_flag = if channel == 0 { 0 } else { CONTROL_FLAG };`
     * (`src/bt_real_hu_passthrough.rs` L131).
     *
     * An earlier version of this note asked for confirmation "against a real
     * capture". It arrived: sending a channel open *without* this bit made a
     * 2021 Chevrolet Infotainment 3 unit close the session about 30 ms later,
     * deterministically. See `AapSession.openChannel` and
     * `docs/protocol-notes.md`.
     */
    val control: Boolean,
    /** The `ENCRYPTED` flag, bit 3 (0x08). Source: `EncryptionType.hpp` L24-L27. */
    val encrypted: Boolean,
    /** Bytes of payload carried by this frame, as they appear on the wire. */
    val payloadLength: Int,
    /**
     * Total plaintext length of the fully reassembled message. Present only on
     * exactly-FIRST frames; null otherwise.
     *
     * Held as a [Long] because the wire field is an **unsigned** 32-bit integer:
     * a value above 2^31 is well-formed on the wire but negative in a signed
     * [Int]. Decoding must never throw on a well-formed frame — a corrupt or
     * hostile peer advertising a 4 GiB message is a protocol error to be
     * reported cleanly by [MessageAssembler], not a decoder crash that takes out
     * the read loop.
     *
     * Treated as a sanity check and preallocation hint, never as the reassembly
     * terminator — see [MessageAssembler].
     */
    val totalMessageLength: Long?,
) {
    init {
        require(channelId in 0..0xFF) { "channelId out of range: $channelId" }
        require(payloadLength in 0..0xFFFF) {
            "payloadLength must fit in the 16-bit size field: $payloadLength"
        }
        require((frameType == FrameType.FIRST) == (totalMessageLength != null)) {
            "totalMessageLength is present on exactly-FIRST frames and only those " +
                "(frameType=$frameType, totalMessageLength=$totalMessageLength)"
        }
        if (totalMessageLength != null) {
            require(totalMessageLength in 0..MAX_TOTAL_MESSAGE_LENGTH) {
                "totalMessageLength must fit in the unsigned 32-bit size field: $totalMessageLength"
            }
        }
    }

    /** Encoded size of this header: 8 bytes on exactly-FIRST frames, else 4. */
    val encodedSize: Int get() = if (totalMessageLength != null) EXTENDED_SIZE else BASE_SIZE

    /** The flags byte as it appears at offset 1. */
    val flags: Int
        get() = frameType.bits or
            (if (control) FLAG_CONTROL else 0) or
            (if (encrypted) FLAG_ENCRYPTED else 0)

    /** Serialises this header to its 4- or 8-byte wire form. */
    fun encode(): ByteArray {
        val out = ByteArray(encodedSize)
        out[0] = channelId.toByte()
        out[1] = flags.toByte()
        out[2] = (payloadLength ushr 8).toByte()
        out[3] = payloadLength.toByte()
        if (totalMessageLength != null) {
            out[4] = (totalMessageLength ushr 24).toByte()
            out[5] = (totalMessageLength ushr 16).toByte()
            out[6] = (totalMessageLength ushr 8).toByte()
            out[7] = totalMessageLength.toByte()
        }
        return out
    }

    override fun toString(): String = buildString {
        append("Frame(")
        append(ChannelId.describe(channelId))
        append(' ').append(frameType)
        if (control) append(" CONTROL")
        if (encrypted) append(" ENC")
        append(" len=").append(payloadLength)
        totalMessageLength?.let { append(" total=").append(it) }
        append(')')
    }

    companion object {
        /** Channel id + flags + 16-bit size. */
        const val BASE_SIZE: Int = 4

        /** [BASE_SIZE] plus the 32-bit total size carried by exactly-FIRST frames. */
        const val EXTENDED_SIZE: Int = 8

        /** Bit 2 of the flags byte. Source: `MessageType.hpp` L26-L29. */
        const val FLAG_CONTROL: Int = 0x04

        /** Bit 3 of the flags byte. Source: `EncryptionType.hpp` L24-L27. */
        const val FLAG_ENCRYPTED: Int = 0x08

        /** Largest value the unsigned 32-bit total-size field can carry. */
        const val MAX_TOTAL_MESSAGE_LENGTH: Long = 0xFFFF_FFFFL

        /**
         * Whether a frame with these flags carries the extended 4-byte total
         * size field.
         *
         * Masked equality against FIRST, deliberately — see [FrameType].
         */
        fun hasExtendedSize(flags: Int): Boolean =
            (flags and FrameType.MASK) == FrameType.FIRST.bits

        /**
         * Decodes a header from [bytes] at [offset].
         *
         * Callers that read from a stream should first read [BASE_SIZE] bytes,
         * consult [hasExtendedSize] on byte 1, and read the remaining 4 only
         * when it returns true.
         */
        fun decode(bytes: ByteArray, offset: Int = 0): FrameHeader {
            require(bytes.size - offset >= BASE_SIZE) {
                "need at least $BASE_SIZE bytes to decode a frame header, " +
                    "have ${bytes.size - offset}"
            }
            val channelId = bytes[offset].toInt() and 0xFF
            val flags = bytes[offset + 1].toInt() and 0xFF
            val payloadLength =
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)

            val totalMessageLength = if (hasExtendedSize(flags)) {
                require(bytes.size - offset >= EXTENDED_SIZE) {
                    "FIRST frame needs $EXTENDED_SIZE header bytes, have ${bytes.size - offset}"
                }
                ((bytes[offset + 4].toLong() and 0xFF) shl 24) or
                    ((bytes[offset + 5].toLong() and 0xFF) shl 16) or
                    ((bytes[offset + 6].toLong() and 0xFF) shl 8) or
                    (bytes[offset + 7].toLong() and 0xFF)
            } else {
                null
            }

            return FrameHeader(
                channelId = channelId,
                frameType = FrameType.fromFlags(flags),
                control = (flags and FLAG_CONTROL) != 0,
                encrypted = (flags and FLAG_ENCRYPTED) != 0,
                payloadLength = payloadLength,
                totalMessageLength = totalMessageLength,
            )
        }
    }
}

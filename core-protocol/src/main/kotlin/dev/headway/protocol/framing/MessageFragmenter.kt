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
 * One fragment of a message, still in plaintext.
 *
 * Fragmentation happens *before* encryption, and the two size fields in the
 * resulting frame header end up in different units as a result — see
 * [FrameHeader]. Keeping fragments in plaintext here, and letting the writer
 * encrypt and fill in the on-wire length, is what keeps that asymmetry in one
 * place instead of smeared across the framing code.
 */
class MessageFragment(
    val channelId: Int,
    val control: Boolean,
    val encrypted: Boolean,
    val frameType: FrameType,
    /** This fragment's share of the message's wire payload, unencrypted. */
    val plaintext: ByteArray,
    /**
     * Total plaintext length of the whole message. Non-null exactly when
     * [frameType] is [FrameType.FIRST], matching the wire rule.
     */
    val totalPlaintextLength: Int?,
) {
    init {
        require((frameType == FrameType.FIRST) == (totalPlaintextLength != null)) {
            "totalPlaintextLength is carried by exactly-FIRST fragments and only those"
        }
    }

    /**
     * Builds the header for this fragment given the length it occupies on the
     * wire — which is [plaintext].size for a plain frame, or the ciphertext
     * length once a TLS record has been produced.
     */
    fun headerFor(wireLength: Int): FrameHeader = FrameHeader(
        channelId = channelId,
        frameType = frameType,
        control = control,
        encrypted = encrypted,
        payloadLength = wireLength,
        totalMessageLength = totalPlaintextLength?.toLong(),
    )
}

/**
 * Splits messages into frames.
 *
 * Mirrors `aasdk/src/Messenger/MessageOutStream.cpp` L44-L74:
 *
 * - a payload shorter than the fragment size goes out as a single [FrameType.BULK]
 *   frame (L44-L50),
 * - otherwise the first chunk is [FrameType.FIRST], every chunk with bytes still
 *   to follow is [FrameType.MIDDLE], and the final chunk is [FrameType.LAST]
 *   (L66-L74).
 *
 * ## Fragment size
 *
 * Every reference picks a different one, which is itself the useful finding:
 *
 * | Implementation | Max plaintext per frame | Source |
 * |---|---|---|
 * | aasdk | 16384 (0x4000) | `MessageOutStream.hpp` L62 |
 * | aa-proxy-rs | 16120 first / 16124 rest | `packet_fragment.rs` L3-L16 |
 * | AACS server | 2000 | `AaCommunicator.cpp` L371-L372 |
 * | AACS client | 10000 | `AAClient/AaCommunicator.cpp` L281 |
 *
 * They interoperate, so the size is a sender's choice and **a receiver must not
 * assume any particular value** — only the 16-bit size field bounds a frame.
 * Headway sends aasdk's 16384 by default because CLAUDE.md directs us to
 * implement aasdk-documented behaviour where references differ, and makes it
 * configurable because AACS's comment ("it should work up to about 16k, but we
 * might get some weird hardware issues") suggests real units that misbehave near
 * the limit. That knob is per-head-unit quirk configuration.
 */
class MessageFragmenter(
    private val maxFragmentSize: Int = DEFAULT_MAX_FRAGMENT_SIZE,
) {
    init {
        require(maxFragmentSize in 1..MAX_FRAME_PAYLOAD) {
            "maxFragmentSize must be in 1..$MAX_FRAME_PAYLOAD, was $maxFragmentSize"
        }
    }

    /**
     * Splits [message] into one or more fragments, in send order.
     *
     * Fragments regardless of whether the message is encrypted. AACS splits only
     * encrypted messages (`AACS/AAServer/src/AaCommunicator.cpp` L377), leaving
     * a plaintext message over 65535 bytes to silently corrupt its own length
     * field; aasdk fragments unconditionally (`MessageOutStream.cpp` L44) and we
     * follow aasdk.
     */
    fun fragment(message: AapMessage): List<MessageFragment> {
        val wire = message.wirePayload()

        if (wire.size <= maxFragmentSize) {
            return listOf(
                MessageFragment(
                    channelId = message.channelId,
                    control = message.control,
                    encrypted = message.encrypted,
                    frameType = FrameType.BULK,
                    plaintext = wire,
                    totalPlaintextLength = null,
                )
            )
        }

        val fragments = ArrayList<MessageFragment>((wire.size + maxFragmentSize - 1) / maxFragmentSize)
        var offset = 0
        while (offset < wire.size) {
            val size = minOf(maxFragmentSize, wire.size - offset)
            val isFirst = offset == 0
            val isLast = offset + size == wire.size
            val frameType = when {
                isFirst -> FrameType.FIRST
                isLast -> FrameType.LAST
                else -> FrameType.MIDDLE
            }
            fragments += MessageFragment(
                channelId = message.channelId,
                control = message.control,
                encrypted = message.encrypted,
                frameType = frameType,
                plaintext = wire.copyOfRange(offset, offset + size),
                // Only the FIRST frame advertises the total, and it is the
                // plaintext total for the whole message.
                totalPlaintextLength = if (isFirst) wire.size else null,
            )
            offset += size
        }
        return fragments
    }

    companion object {
        /** aasdk's `cMaxFramePayloadSize`. Source: `MessageOutStream.hpp` L62. */
        const val DEFAULT_MAX_FRAGMENT_SIZE: Int = 0x4000

        /** The hard ceiling imposed by the 16-bit frame size field. */
        const val MAX_FRAME_PAYLOAD: Int = 0xFFFF
    }
}

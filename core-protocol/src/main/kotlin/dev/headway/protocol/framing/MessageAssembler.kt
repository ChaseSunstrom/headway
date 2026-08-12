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

import java.io.ByteArrayOutputStream

/** Raised when the peer's framing is invalid and the session cannot continue. */
class FramingException(message: String) : RuntimeException(message)

/**
 * Reassembles fragmented messages, one partial message per channel.
 *
 * AAP interleaves channels on a single connection, so a video FIRST frame can be
 * followed by a control BULK frame and then the video LAST frame. Partial
 * messages are therefore keyed by channel — matching aasdk's
 * `std::map<ChannelId, Message::Pointer> messageBuffer_`
 * (`aasdk/src/Messenger/MessageInStream.cpp` L67).
 *
 * ## Termination: on the LAST bit, not on the byte count
 *
 * The two references disagree here and the disagreement matters:
 *
 * - aasdk terminates purely on the frame type — `thisFrameType_ == BULK ||
 *   thisFrameType_ == LAST` (`MessageInStream.cpp` L149-L161) — and never reads
 *   the 4-byte total size during reassembly, despite parsing it
 *   (`FrameSize.cpp` L42-L45 vs `MessageInStream.cpp` L128-L130).
 * - AACS's client instead loops `while (fullContent.size() < totalLength)`
 *   (`AACS/AAClient/src/AaCommunicator.cpp` L464) and never inspects the LAST
 *   bit, so it cannot terminate a message whose total was never advertised.
 *
 * Headway terminates on LAST/BULK like aasdk, and additionally uses the
 * advertised total as a **sanity check and preallocation hint**. Checking it
 * catches a desynchronised stream at the frame that broke rather than several
 * messages later, which during protocol bring-up is the difference between a
 * one-line fix and an afternoon.
 */
class MessageAssembler(
    /**
     * Upper bound on a single reassembled message. A corrupt or hostile FIRST
     * frame can advertise a 4 GiB total; without a cap the phone would try to
     * buffer it. 16 MiB is far above any real AAP message (the largest are video
     * keyframes, tens of KiB).
     */
    private val maxMessageSize: Int = DEFAULT_MAX_MESSAGE_SIZE,
) {
    private class Partial(
        val control: Boolean,
        val encrypted: Boolean,
        val advertisedTotal: Int,
    ) {
        val buffer = ByteArrayOutputStream()
    }

    private val partials = HashMap<Int, Partial>()

    /**
     * Feeds one frame's **plaintext** payload to the assembler.
     *
     * Callers must have already decrypted an encrypted frame, since the frame's
     * on-wire length is the ciphertext length while reassembly works in
     * plaintext (see [FrameHeader]).
     *
     * @return the completed message, or null if more frames are needed.
     */
    fun onFrame(header: FrameHeader, plaintext: ByteArray): AapMessage? {
        val channel = header.channelId

        return when (header.frameType) {
            FrameType.BULK -> {
                // A complete message in one frame. Any partial on this channel is
                // stale — aasdk likewise overwrites the buffer entry on
                // FIRST/BULK (MessageInStream.cpp L75-L98).
                partials.remove(channel)
                AapMessage.fromWirePayload(channel, header.control, header.encrypted, plaintext)
            }

            FrameType.FIRST -> {
                val total = header.totalMessageLength
                    ?: throw FramingException("FIRST frame on channel $channel has no total size")
                // The wire field is an unsigned 32-bit value, so this bound is
                // what stops a corrupt or hostile FIRST frame from making the
                // phone try to buffer 4 GiB.
                if (total > maxMessageSize) {
                    throw FramingException(
                        "message on ${ChannelId.describe(channel)} advertises $total bytes, " +
                            "over the $maxMessageSize limit"
                    )
                }
                if (plaintext.size > total) {
                    throw FramingException(
                        "FIRST frame on ${ChannelId.describe(channel)} carries ${plaintext.size} " +
                            "bytes but advertises a $total byte total"
                    )
                }
                val partial = Partial(header.control, header.encrypted, total.toInt())
                partial.buffer.write(plaintext)
                partials[channel] = partial
                null
            }

            FrameType.MIDDLE, FrameType.LAST -> {
                val partial = partials[channel]
                    ?: throw FramingException(
                        // aasdk calls this MESSENGER_INTERTWINED_CHANNELS
                        // (MessageInStream.cpp L67-L98).
                        "${header.frameType} frame on ${ChannelId.describe(channel)} with no " +
                            "message in progress — the stream is desynchronised"
                    )
                if (partial.buffer.size() + plaintext.size > partial.advertisedTotal) {
                    partials.remove(channel)
                    throw FramingException(
                        "message on ${ChannelId.describe(channel)} overruns its advertised " +
                            "${partial.advertisedTotal} byte total"
                    )
                }
                partial.buffer.write(plaintext)

                if (header.frameType == FrameType.LAST) {
                    partials.remove(channel)
                    val assembled = partial.buffer.toByteArray()
                    if (assembled.size != partial.advertisedTotal) {
                        throw FramingException(
                            "message on ${ChannelId.describe(channel)} completed at " +
                                "${assembled.size} bytes but advertised ${partial.advertisedTotal}"
                        )
                    }
                    AapMessage.fromWirePayload(
                        channel,
                        partial.control,
                        partial.encrypted,
                        assembled,
                    )
                } else {
                    null
                }
            }
        }
    }

    /** Number of channels with a partially received message. Diagnostics only. */
    fun pendingChannelCount(): Int = partials.size

    /**
     * Discards all partial state. Called on reconnect: a new session must not
     * inherit half a message from the previous one.
     */
    fun reset() = partials.clear()

    companion object {
        /** 16 MiB. */
        const val DEFAULT_MAX_MESSAGE_SIZE: Int = 16 * 1024 * 1024
    }
}

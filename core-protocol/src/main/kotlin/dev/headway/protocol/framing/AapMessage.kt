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
 * A complete logical AAP message, independent of how it was fragmented.
 *
 * On the wire a message's payload is the 2-byte big-endian message id followed
 * by the serialised protobuf. aasdk builds this by inserting the id into the
 * payload before the protobuf
 * (`aasdk/src/Channel/Control/ControlServiceChannel.cpp` L77-L88;
 * `aasdk/src/Messenger/MessageId.cpp` L30-L42) and strips the first two bytes
 * on receive (`ControlServiceChannel.cpp` L188-L197).
 *
 * Headway keeps [messageId] and [payload] as separate fields rather than
 * pre-concatenating them, so that channel code never has to remember to skip two
 * bytes. [wirePayload] performs the join, and it is the only place that layout
 * is expressed.
 *
 * Note that the id is per-channel: message id 1 on the control channel and
 * message id 1 on the video channel are unrelated messages.
 */
class AapMessage(
    val channelId: Int,
    /** The `CONTROL` flag. See [FrameHeader.control] for the aasdk/AACS naming split. */
    val control: Boolean,
    /** Whether this message travels inside the TLS session. */
    val encrypted: Boolean,
    /** Big-endian 16-bit message id. Source: `aasdk/include/aasdk/Messenger/MessageId.hpp` L26-L36. */
    val messageId: Int,
    /** The serialised protobuf, *excluding* the message id. */
    val payload: ByteArray,
) {
    init {
        require(channelId in 0..0xFF) { "channelId out of range: $channelId" }
        require(messageId in 0..0xFFFF) { "messageId must fit in 16 bits: $messageId" }
    }

    /** The message id followed by [payload] — the byte sequence that gets fragmented. */
    fun wirePayload(): ByteArray {
        val out = ByteArray(MESSAGE_ID_SIZE + payload.size)
        out[0] = (messageId ushr 8).toByte()
        out[1] = messageId.toByte()
        payload.copyInto(out, MESSAGE_ID_SIZE)
        return out
    }

    override fun toString(): String =
        "AapMessage(${ChannelId.describe(channelId)} id=0x${messageId.toString(16)} " +
            "${payload.size}B${if (control) " CONTROL" else ""}${if (encrypted) " ENC" else ""})"

    companion object {
        /** Source: `aasdk/include/aasdk/Messenger/MessageId.hpp` L26-L36. */
        const val MESSAGE_ID_SIZE: Int = 2

        /**
         * Rebuilds a message from a fully reassembled wire payload, splitting
         * the leading message id back off.
         */
        fun fromWirePayload(
            channelId: Int,
            control: Boolean,
            encrypted: Boolean,
            wirePayload: ByteArray,
        ): AapMessage {
            require(wirePayload.size >= MESSAGE_ID_SIZE) {
                "message on channel ${ChannelId.describe(channelId)} is " +
                    "${wirePayload.size} bytes, too short to contain a message id"
            }
            val messageId =
                ((wirePayload[0].toInt() and 0xFF) shl 8) or (wirePayload[1].toInt() and 0xFF)
            return AapMessage(
                channelId = channelId,
                control = control,
                encrypted = encrypted,
                messageId = messageId,
                payload = wirePayload.copyOfRange(MESSAGE_ID_SIZE, wirePayload.size),
            )
        }
    }
}

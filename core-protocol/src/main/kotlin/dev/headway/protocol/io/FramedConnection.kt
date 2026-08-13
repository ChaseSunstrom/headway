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

package dev.headway.protocol.io

import dev.headway.protocol.framing.AapMessage
import dev.headway.protocol.framing.ChannelId
import dev.headway.protocol.framing.FrameHeader
import dev.headway.protocol.framing.FramingException
import dev.headway.protocol.framing.MessageAssembler
import dev.headway.protocol.framing.MessageFragmenter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Message-level view of a [Transport]: sends and receives whole [AapMessage]s,
 * hiding framing, fragmentation and record encryption.
 *
 * This is the seam the whole session sits on, and it is deliberately symmetric —
 * the phone and the head-unit emulator both use it, differing only in which
 * messages they send. See ADR 0002 for why that symmetry is convenient but is
 * *not* by itself evidence of correctness.
 */
class FramedConnection(
    private val transport: Transport,
    private val fragmenter: MessageFragmenter = MessageFragmenter(),
    private val assembler: MessageAssembler = MessageAssembler(),
    /**
     * Optional frame logger, wired by the app's debug build to the log export.
     *
     * Carries the payload as well as the header, and that is the point. An
     * earlier version passed only the header, and nothing wired it at all
     * despite the comment claiming otherwise — so the exported log had hex for
     * every Bluetooth frame and not one byte of AAP. A real head unit closed
     * sessions over a single wrong flag bit for weeks, and the bit had to be
     * found by reading four reference implementations instead of by reading the
     * log the user had already sent.
     *
     * [payload] is what actually went on the wire in that direction: ciphertext
     * on send when encrypted, decrypted plaintext on receive. Callers are
     * expected to truncate — video frames run to 16 KB.
     */
    private val onFrame: (
        direction: Direction,
        header: FrameHeader,
        payload: ByteArray,
    ) -> Unit = { _, _, _ -> },
) : MessageChannel {
    enum class Direction { SENT, RECEIVED }

    /**
     * Set once the TLS handshake completes. Until then, only plaintext frames
     * can be sent — which is exactly what the version exchange and the
     * encapsulated handshake messages are.
     */
    @Volatile
    override var cryptor: Cryptor? = null

    // AAP multiplexes channels over one connection, so several coroutines will
    // send concurrently (video frames while audio plays while control pings).
    // Interleaving two messages' frames would corrupt both.
    private val sendLock = Mutex()
    private val receiveLock = Mutex()

    /**
     * Fragments, encrypts if required, and writes [message].
     *
     * @throws IllegalStateException if the message is marked encrypted but no
     *   TLS session has been established — a bug worth failing loudly on, since
     *   the alternative is silently transmitting plaintext the peer will reject.
     */
    override suspend fun send(message: AapMessage) {
        sendLock.withLock {
            for (fragment in fragmenter.fragment(message)) {
                val wire = if (fragment.encrypted) {
                    val c = cryptor ?: throw IllegalStateException(
                        "message on ${ChannelId.describe(message.channelId)} is marked encrypted " +
                            "but the TLS session is not established"
                    )
                    c.encrypt(fragment.plaintext)
                } else {
                    fragment.plaintext
                }

                // The header can only be built once the on-wire length is known,
                // because for an encrypted frame that is the ciphertext length
                // while the total-size field stays in plaintext units.
                val header = fragment.headerFor(wire.size)
                // Logged as plaintext, not as the ciphertext actually written:
                // a hex dump of a TLS record tells a reader nothing, and the
                // whole point of this hook is that a failure be readable.
                onFrame(Direction.SENT, header, fragment.plaintext)

                // One write per frame: header and payload must not be separated
                // by another coroutine's frame.
                val out = ByteArray(header.encodedSize + wire.size)
                header.encode().copyInto(out)
                wire.copyInto(out, header.encodedSize)
                transport.write(out)
            }
        }
    }

    /**
     * Reads frames until a complete message is assembled, and returns it.
     *
     * @throws java.io.EOFException if the peer closes.
     * @throws FramingException if the peer's framing is invalid.
     */
    override suspend fun receive(): AapMessage {
        receiveLock.withLock {
            while (true) {
                val header = readHeader()

                val wire = transport.readFully(header.payloadLength)
                val plaintext = if (header.encrypted) {
                    val c = cryptor ?: throw FramingException(
                        "peer sent an encrypted frame on " +
                            "${ChannelId.describe(header.channelId)} before the TLS session was up"
                    )
                    c.decrypt(wire)
                } else {
                    wire
                }
                // After decryption rather than before, for the same reason the
                // send side logs plaintext.
                onFrame(Direction.RECEIVED, header, plaintext)

                assembler.onFrame(header, plaintext)?.let { return it }
            }
        }
    }

    /**
     * Reads a frame header, which is 4 bytes or 8 depending on the frame type.
     *
     * The extra 4 bytes cannot be read speculatively — a BULK frame's payload
     * would be consumed as a size field — so the base header is read first and
     * the flags byte decides whether more follows.
     */
    private suspend fun readHeader(): FrameHeader {
        val base = transport.readFully(FrameHeader.BASE_SIZE)
        val flags = base[1].toInt() and 0xFF
        if (!FrameHeader.hasExtendedSize(flags)) {
            return FrameHeader.decode(base)
        }
        val rest = transport.readFully(FrameHeader.EXTENDED_SIZE - FrameHeader.BASE_SIZE)
        val full = ByteArray(FrameHeader.EXTENDED_SIZE)
        base.copyInto(full)
        rest.copyInto(full, FrameHeader.BASE_SIZE)
        return FrameHeader.decode(full)
    }

    /**
     * Drops any partially received message. Called when a session ends so a
     * reconnect cannot inherit half a message from the previous one.
     */
    fun reset() {
        assembler.reset()
        cryptor = null
    }

    fun close() = transport.close()
}

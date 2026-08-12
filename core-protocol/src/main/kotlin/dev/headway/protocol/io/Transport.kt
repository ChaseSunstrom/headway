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

/**
 * A bidirectional, ordered, reliable byte stream.
 *
 * Everything above this interface is transport-agnostic, which is what lets the
 * same AAP implementation run over:
 *
 *  - a TCP socket to the head unit (the real session),
 *  - a Bluetooth RFCOMM socket (the wireless credentials handshake),
 *  - an in-process pipe pair (CI, where there is no radio at all).
 *
 * The fake transport is not a testing afterthought: no Bluetooth radio, Wi-Fi
 * AP, or vehicle exists in the build environment (see BLOCKERS.md B-001), so the
 * in-process implementation is how every channel state machine is exercised.
 *
 * Implementations must be safe for one concurrent reader and one concurrent
 * writer. They are *not* required to tolerate multiple concurrent readers or
 * multiple concurrent writers; [dev.headway.protocol.io.FramedConnection] and
 * the session layer above it serialise access.
 */
interface Transport : AutoCloseable {

    /**
     * Reads exactly [length] bytes, suspending until they are all available.
     *
     * AAP is a framed protocol whose headers state their payload length exactly,
     * so partial reads are never useful to a caller — every call site would
     * otherwise have to reimplement the same loop. Returning short reads was
     * tried and removed for that reason.
     *
     * @throws java.io.EOFException if the peer closes before [length] bytes arrive.
     */
    suspend fun readFully(length: Int): ByteArray

    /**
     * Writes [length] bytes from [bytes] starting at [offset], suspending until
     * they have all been handed to the underlying stream.
     */
    suspend fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset)

    /**
     * Closes the transport. Any suspended [readFully] or [write] must fail
     * promptly rather than hang — reconnection is a first-class feature and a
     * stuck read would stall it.
     *
     * Must be idempotent.
     */
    override fun close()
}

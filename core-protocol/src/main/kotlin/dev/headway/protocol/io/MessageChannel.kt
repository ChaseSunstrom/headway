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

/**
 * Somewhere to send AAP messages and somewhere to receive them from.
 *
 * [FramedConnection] is the implementation that talks to a socket. The reason
 * this interface exists is [ChannelDemultiplexer]: a live session has video,
 * audio, input and control channels all reading concurrently, and they cannot
 * each call `receive()` on one connection — whichever coroutine wins the race
 * gets the other's message. Channels therefore take a [MessageChannel], which is
 * either the whole connection (single-channel use, and every existing test) or a
 * per-channel view onto a demultiplexer.
 */
interface MessageChannel {

    /**
     * The link's TLS session, once established.
     *
     * Encryption is a property of the connection rather than of any one channel,
     * so a per-channel view forwards to the link it belongs to. The session
     * layer sets this the moment the handshake completes; before then only
     * plaintext frames are legal.
     */
    var cryptor: Cryptor?

    /** Fragments, encrypts if required, and writes [message]. */
    suspend fun send(message: AapMessage)

    /**
     * Suspends until a message arrives.
     *
     * @throws java.io.EOFException if the peer closes.
     */
    suspend fun receive(): AapMessage
}

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
 * Encrypts and decrypts frame payloads once the TLS session is established.
 *
 * AAP does not run TLS on the socket. The handshake is carried inside
 * control-channel `ENCAPSULATED_SSL` messages, and afterwards each frame's
 * payload is an independent TLS record. That is why this is a byte-array
 * transform rather than a stream wrapper: there is no continuous TLS byte stream
 * to wrap, only discrete records sitting inside AAP frames.
 *
 * Implementations are stateful (a TLS session has sequence numbers) and are
 * therefore not safe for concurrent use; [dev.headway.protocol.io.FramedConnection]
 * serialises access.
 */
interface Cryptor {

    /**
     * Wraps [plaintext] into one or more TLS records.
     *
     * The returned length is what goes in the frame header's payload-size field,
     * while the *plaintext* length is what goes in the total-size field of a
     * FIRST frame — see [dev.headway.protocol.framing.FrameHeader].
     */
    fun encrypt(plaintext: ByteArray): ByteArray

    /** Unwraps the TLS record(s) in [ciphertext] back to plaintext. */
    fun decrypt(ciphertext: ByteArray): ByteArray
}

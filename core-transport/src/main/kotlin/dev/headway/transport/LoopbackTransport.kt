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

package dev.headway.transport

import dev.headway.protocol.io.Transport

/**
 * The "fake transport" required by CLAUDE.md Phase 0 — a connected pair of
 * [Transport]s in one process, with no radio, no socket and no loopback network
 * interface involved.
 *
 * CLAUDE.md asks the emulator to offer
 *
 * > a loopback "fake transport" mode that skips real BT/Wi-Fi and exposes the
 * > same RFCOMM+TCP byte streams over local sockets
 *
 * and this is that mode. It is used both for the RFCOMM credentials exchange and
 * for the TCP session, since at this layer they are the same thing: an ordered
 * reliable byte stream.
 *
 * Both ends wrap [StreamTransport], so CI exercises the same read/write code
 * that runs against a real socket rather than a parallel implementation that
 * could drift from it.
 */
object LoopbackTransport {

    /**
     * A connected pair. Bytes written to [phone] are readable from [headUnit]
     * and vice versa.
     */
    data class Pair(
        val phone: Transport,
        val headUnit: Transport,
    ) : AutoCloseable {
        override fun close() {
            runCatching { phone.close() }
            runCatching { headUnit.close() }
        }
    }

    /**
     * Creates a connected [Pair].
     *
     * @param capacity per-direction buffer size; writers block when it fills, so
     *   backpressure behaves like a socket rather than an unbounded queue.
     */
    fun pair(capacity: Int = InMemoryPipe.DEFAULT_CAPACITY): Pair {
        val phoneToHeadUnit = InMemoryPipe(capacity)
        val headUnitToPhone = InMemoryPipe(capacity)

        val phone = StreamTransport(
            input = headUnitToPhone.source,
            output = phoneToHeadUnit.sink,
        )
        val headUnit = StreamTransport(
            input = phoneToHeadUnit.source,
            output = headUnitToPhone.sink,
        )
        return Pair(phone = phone, headUnit = headUnit)
    }
}

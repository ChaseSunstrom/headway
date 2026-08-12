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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A [Transport] over an ordinary [InputStream]/[OutputStream] pair.
 *
 * This single implementation covers every transport Headway needs, because all
 * of them expose stream pairs:
 *
 *  - `java.net.Socket` — the TCP session to the head unit,
 *  - Android's `BluetoothSocket` — the RFCOMM credentials handshake,
 *  - `java.io.PipedInputStream` — the in-process fake used by CI.
 *
 * Preferring this over an NIO/selector implementation is a deliberate
 * application of "prefer boring technology": AAP has exactly one connection per
 * session, so the scalability argument for NIO does not apply, and blocking
 * reads dispatched to [Dispatchers.IO] are markedly easier to reason about
 * during a protocol bring-up.
 *
 * @param closeStreams whether [close] should also close the wrapped streams.
 *   False when the caller owns them (e.g. an Android `BluetoothSocket` that must
 *   be closed through the socket object itself).
 */
class StreamTransport(
    private val input: InputStream,
    private val output: OutputStream,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val closeStreams: Boolean = true,
    private val onClose: () -> Unit = {},
) : Transport {

    private val closed = AtomicBoolean(false)

    // One reader and one writer may run concurrently, but two writers must not
    // interleave halves of a frame onto the wire. The session layer is expected
    // to serialise, and these guard against it regressing.
    private val readLock = Mutex()
    private val writeLock = Mutex()

    override suspend fun readFully(length: Int): ByteArray {
        require(length >= 0) { "length must be non-negative, was $length" }
        if (length == 0) return ByteArray(0)

        return readLock.withLock {
            withContext(dispatcher) {
                val buffer = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = try {
                        input.read(buffer, read, length - read)
                    } catch (e: Exception) {
                        // A close() racing an in-flight read surfaces as a
                        // SocketException / IOException. Reconnection treats a
                        // closed transport and a failed read identically, so
                        // normalise to EOF rather than leaking the variation.
                        if (closed.get()) throw EOFException("transport closed during read")
                        throw e
                    }
                    if (n < 0) {
                        throw EOFException(
                            "peer closed after $read of $length bytes"
                        )
                    }
                    read += n
                }
                buffer
            }
        }
    }

    override suspend fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) {
            "bad range: offset=$offset length=$length size=${bytes.size}"
        }
        if (length == 0) return

        writeLock.withLock {
            withContext(dispatcher) {
                try {
                    output.write(bytes, offset, length)
                    output.flush()
                } catch (e: Exception) {
                    if (closed.get()) throw EOFException("transport closed during write")
                    throw e
                }
            }
        }
    }

    override fun close() {
        // Idempotent: reconnection logic closes defensively and must not throw.
        if (!closed.compareAndSet(false, true)) return

        // Close the streams without taking the locks. Taking them would deadlock
        // against a reader blocked in input.read() — which is exactly the caller
        // we are trying to unblock.
        if (closeStreams) {
            runCatching { input.close() }
            runCatching { output.close() }
        }
        runCatching { onClose() }
    }
}

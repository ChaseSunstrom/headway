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

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A thread-safe, blocking, in-memory byte pipe.
 *
 * This exists instead of [java.io.PipedInputStream] because the JDK's piped
 * streams track the *identity* of the reading and writing threads and throw
 * "Read end dead" / "Write end dead" once those threads exit. Coroutines
 * dispatched to `Dispatchers.IO` hop between pool threads freely, so the JDK
 * pipes fail spuriously under exactly the usage Headway has. This implementation
 * tracks stream liveness by explicit [close] instead of by thread liveness.
 *
 * Backed by a growable circular buffer bounded at [capacity]; writers block once
 * it is full, which preserves the backpressure a real socket provides. Without
 * that bound a fast producer in a test would buffer without limit and the test
 * would stop resembling the network.
 */
class InMemoryPipe(private val capacity: Int = DEFAULT_CAPACITY) {

    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val notFull = lock.newCondition()

    private val buffer = ByteArray(capacity)
    private var head = 0
    private var count = 0

    private var writerClosed = false
    private var readerClosed = false

    val source: InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            lock.withLock {
                while (count == 0) {
                    if (writerClosed) return -1
                    if (readerClosed) throw IOException("pipe reader closed")
                    notEmpty.await()
                }
                val n = minOf(len, count)
                for (i in 0 until n) {
                    b[off + i] = buffer[(head + i) % capacity]
                }
                head = (head + n) % capacity
                count -= n
                notFull.signalAll()
                return n
            }
        }

        override fun available(): Int = lock.withLock { count }

        override fun close() {
            lock.withLock {
                readerClosed = true
                // Wake a blocked writer so it observes the closed reader rather
                // than waiting for space that will never be consumed.
                notFull.signalAll()
                notEmpty.signalAll()
            }
        }
    }

    val sink: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            var written = 0
            while (written < len) {
                lock.withLock {
                    if (writerClosed) throw IOException("pipe writer closed")
                    while (count == capacity) {
                        if (readerClosed) throw IOException("pipe reader closed")
                        if (writerClosed) throw IOException("pipe writer closed")
                        notFull.await()
                    }
                    if (readerClosed) throw IOException("pipe reader closed")
                    val n = minOf(len - written, capacity - count)
                    val tail = (head + count) % capacity
                    for (i in 0 until n) {
                        buffer[(tail + i) % capacity] = b[off + written + i]
                    }
                    count += n
                    written += n
                    notEmpty.signalAll()
                }
            }
        }

        override fun close() {
            lock.withLock {
                writerClosed = true
                // Wake the reader so it sees EOF instead of blocking forever.
                notEmpty.signalAll()
                notFull.signalAll()
            }
        }
    }

    companion object {
        /**
         * 256 KiB. Large enough that a single H.264 keyframe does not deadlock a
         * single-threaded test that writes before it reads, small enough to keep
         * backpressure meaningful.
         */
        const val DEFAULT_CAPACITY: Int = 256 * 1024
    }
}

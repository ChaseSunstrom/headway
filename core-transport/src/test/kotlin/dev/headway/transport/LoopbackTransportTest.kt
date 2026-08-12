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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.EOFException
import kotlin.random.Random

/**
 * The fake transport carries every CI acceptance test, so its own correctness is
 * load-bearing: a bug here would show up as an unexplainable protocol failure
 * several layers up.
 */
class LoopbackTransportTest {

    @Test
    fun `bytes written by one end are read by the other`() = runBlocking {
        LoopbackTransport.pair().use { pair ->
            val payload = byteArrayOf(0x00, 0x01, 0x7F, -1, -128)

            val received = async(Dispatchers.IO) { pair.headUnit.readFully(payload.size) }
            pair.phone.write(payload)

            assertArrayEquals(payload, withTimeout(5_000) { received.await() })
        }
    }

    @Test
    fun `both directions are independent`() = runBlocking {
        LoopbackTransport.pair().use { pair ->
            val toCar = "phone->car".toByteArray()
            val toPhone = "car->phone".toByteArray()

            val carRead = async(Dispatchers.IO) { pair.headUnit.readFully(toCar.size) }
            val phoneRead = async(Dispatchers.IO) { pair.phone.readFully(toPhone.size) }

            pair.phone.write(toCar)
            pair.headUnit.write(toPhone)

            withTimeout(5_000) {
                assertArrayEquals(toCar, carRead.await())
                assertArrayEquals(toPhone, phoneRead.await())
            }
        }
    }

    @Test
    fun `readFully reassembles a payload split across many writes`() = runBlocking {
        // AAP frame headers state an exact length, so readFully must not return
        // short. Writing byte-at-a-time is the harshest version of that.
        LoopbackTransport.pair().use { pair ->
            val payload = Random(7).nextBytes(4096)

            val received = async(Dispatchers.IO) { pair.headUnit.readFully(payload.size) }
            withContext(Dispatchers.IO) {
                for (b in payload) pair.phone.write(byteArrayOf(b))
            }

            assertArrayEquals(payload, withTimeout(15_000) { received.await() })
        }
    }

    @Test
    fun `a payload larger than the buffer streams through without deadlocking`() = runBlocking {
        // Backpressure check: the writer must block and resume as the reader
        // drains, rather than the pair deadlocking. A video keyframe is exactly
        // this shape.
        val capacity = 8 * 1024
        LoopbackTransport.pair(capacity = capacity).use { pair ->
            val payload = Random(11).nextBytes(capacity * 6)

            val received = async(Dispatchers.IO) { pair.headUnit.readFully(payload.size) }
            launch(Dispatchers.IO) { pair.phone.write(payload) }

            assertArrayEquals(payload, withTimeout(30_000) { received.await() })
        }
    }

    @Test
    fun `closing the writing end surfaces EOF to a blocked reader`() = runBlocking {
        // Reconnection depends on this: a head unit that goes away must fail the
        // pending read promptly instead of hanging the session.
        val pair = LoopbackTransport.pair()
        val read = async(Dispatchers.IO) {
            assertThrows(EOFException::class.java) {
                runBlocking { pair.headUnit.readFully(16) }
            }
        }
        withContext(Dispatchers.IO) { pair.phone.close() }
        withTimeout(5_000) { read.await() }
        pair.close()
    }

    @Test
    fun `partial payload then close reports how much arrived`() = runBlocking {
        val pair = LoopbackTransport.pair()
        val read = async(Dispatchers.IO) {
            val e = assertThrows(EOFException::class.java) {
                runBlocking { pair.headUnit.readFully(64) }
            }
            // The message should say what was actually received; a bare "EOF"
            // during protocol bring-up is needlessly hard to diagnose.
            assertEquals(true, e.message!!.contains("10 of 64"), "was: ${e.message}")
        }
        pair.phone.write(ByteArray(10))
        withContext(Dispatchers.IO) { pair.phone.close() }
        withTimeout(5_000) { read.await() }
        pair.close()
    }

    @Test
    fun `close is idempotent`() = runBlocking {
        val pair = LoopbackTransport.pair()
        pair.phone.close()
        pair.phone.close()
        pair.close()
        pair.close()
    }

    @Test
    fun `zero length read and write are no-ops`() = runBlocking {
        LoopbackTransport.pair().use { pair ->
            pair.phone.write(ByteArray(0))
            assertArrayEquals(ByteArray(0), pair.headUnit.readFully(0))
        }
    }
}

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

package dev.headway.app.audio

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The queue that stops the car's pace deciding the capture's.
 *
 * `AudioChannel.sendPcm` waits for the head unit's credit. That wait used to be
 * inside the loop that read `AudioRecord`, whose buffer is a ring — audio that
 * arrives with no room is overwritten, silently, with no error and no short
 * read. One drive captured 34 seconds less music than it played.
 */
class MediaQueueTest {

    private fun buffer(tag: Int) = ByteArray(4) { tag.toByte() }

    @Test
    fun `offering never blocks and reports nothing lost while there is room`() {
        val queue = MediaQueue(depth = 3)
        assertEquals(0, queue.offer(buffer(1)))
        assertEquals(0, queue.offer(buffer(2)))
        assertEquals(0, queue.offer(buffer(3)))
        assertEquals(0L, queue.dropped)
        assertEquals(3, queue.waiting)
        assertEquals(3, queue.deepest)
    }

    @Test
    fun `a full queue drops the oldest and says how many`() {
        val queue = MediaQueue(depth = 2)
        queue.offer(buffer(1))
        queue.offer(buffer(2))
        assertEquals(1, queue.offer(buffer(3)), "one had to go to make room")
        assertEquals(1L, queue.dropped)

        // The oldest went, so what is left is the newest two, in order.
        val kept = runBlocking { listOf(queue.take(), queue.take()) }
        assertEquals(listOf(2.toByte(), 3.toByte()), kept.map { it!![0] })
    }

    @Test
    fun `take waits for a buffer rather than spinning`() = runBlocking {
        val queue = MediaQueue(depth = 4)
        val taken = async { queue.take() }
        Thread.sleep(50)
        assertTrue(taken.isActive, "take must suspend while the queue is empty")
        queue.offer(buffer(7))
        val got = withTimeout(5_000) { taken.await() }
        assertEquals(7.toByte(), got!![0])
    }

    @Test
    fun `closing drains what is left and then ends`() = runBlocking {
        val queue = MediaQueue(depth = 4)
        queue.offer(buffer(1))
        queue.offer(buffer(2))
        queue.close()
        // Everything already captured still goes to the car; only then does the
        // sender finish. Discarding it would be a click at the end of every
        // grant, and there is no reason for one.
        assertEquals(1.toByte(), queue.take()!![0])
        assertEquals(2.toByte(), queue.take()!![0])
        assertNull(queue.take())
    }

    @Test
    fun `a slow consumer costs the oldest audio and nothing else`() = runBlocking {
        // The failure this whole class exists for, in miniature: the sender is
        // far behind, and the reader keeps its pace regardless.
        val queue = MediaQueue(depth = 5)
        repeat(50) { queue.offer(buffer(it)) }
        assertEquals(45L, queue.dropped)
        assertEquals(5, queue.waiting)
        assertEquals(5, queue.deepest, "the bound must actually bound")
        assertEquals(45.toByte(), queue.take()!![0], "what is kept is the newest")
    }
}

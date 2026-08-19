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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.EOFException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The control channel must not queue behind media.
 *
 * A head unit pings on a timer and tears the session down when the answers stop.
 * Headway's send path is one fair `Mutex` per connection, and a live session
 * puts ~80 media messages a second into it — 30 fps of video plus ~50 audio
 * buffers. On a congested car radio a ping answer that joins the back of that
 * queue is seconds late, and the car resets the connection rather than sending
 * `BYEBYE_REQUEST`. The 2026-08-19 drive log against the target vehicle has
 * three such resets in thirty minutes and no byebye with any of them.
 *
 * So this holds one message on the wire, piles media up behind it, and asserts
 * that a control message sent *last* still goes out *first* of everything that
 * was waiting.
 */
class FramedConnectionPriorityTest {

    /** A transport whose first write parks until the test lets it go. */
    private class GatedTransport : Transport {
        val written = CopyOnWriteArrayList<Int>()
        val firstWriteStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        private var seenFirst = false

        override suspend fun readFully(length: Int): ByteArray = throw EOFException("not used")

        override suspend fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (!seenFirst) {
                seenFirst = true
                firstWriteStarted.complete(Unit)
                release.await()
            }
            // The channel id is the first byte of an AAP frame header, which is
            // all this test needs to identify who got the wire.
            written += bytes[offset].toInt()
        }

        override fun close() = Unit
    }

    private fun message(channel: ChannelId) = AapMessage(
        channelId = channel.id,
        control = false,
        encrypted = false,
        messageId = 1,
        payload = ByteArray(8),
    )

    @Test
    fun `a control message overtakes media already waiting for the wire`() = runBlocking {
        val transport = GatedTransport()
        val connection = FramedConnection(transport)

        // Holds the wire. Everything below queues behind this one message.
        val holder = launch(Dispatchers.IO) { connection.send(message(ChannelId.MEDIA_SINK_VIDEO)) }
        withTimeout(5_000) { transport.firstWriteStarted.await() }

        // The backlog a real session builds: video and audio, in that order.
        val backlog = List(8) { index ->
            launch(Dispatchers.IO) {
                connection.send(
                    message(if (index % 2 == 0) ChannelId.MEDIA_SINK_VIDEO else ChannelId.MEDIA_SINK_MEDIA_AUDIO)
                )
            }
        }
        // The backlog has to actually be waiting on the lock before control
        // asks, or the test would pass by racing rather than by priority.
        Thread.sleep(100)

        val ping = launch(Dispatchers.IO) { connection.send(message(ChannelId.CONTROL)) }
        Thread.sleep(50)

        transport.release.complete(Unit)
        withTimeout(10_000) {
            holder.join()
            ping.join()
            backlog.forEach { it.join() }
        }

        assertEquals(10, transport.written.size, "every message should have been written")
        assertEquals(
            ChannelId.MEDIA_SINK_VIDEO.id,
            transport.written[0],
            "the message that already held the wire keeps it",
        )
        assertEquals(
            ChannelId.CONTROL.id,
            transport.written[1],
            "control must come next, ahead of the eight media messages queued before it",
        )
    }

    @Test
    fun `media still goes out in order`() = runBlocking {
        val transport = GatedTransport()
        val connection = FramedConnection(transport)

        val holder = launch(Dispatchers.IO) { connection.send(message(ChannelId.MEDIA_SINK_VIDEO)) }
        withTimeout(5_000) { transport.firstWriteStarted.await() }
        transport.release.complete(Unit)
        withTimeout(5_000) { holder.join() }

        // Sequential sends: the lane must not reorder or lose media.
        repeat(4) { connection.send(message(ChannelId.MEDIA_SINK_MEDIA_AUDIO)) }
        assertEquals(5, transport.written.size)
        assertTrue(
            transport.written.drop(1).all { it == ChannelId.MEDIA_SINK_MEDIA_AUDIO.id },
            "media must still arrive on its own channel: ${transport.written}",
        )
    }
}

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import java.io.EOFException
import java.util.concurrent.ConcurrentHashMap

/**
 * Fans one [FramedConnection] out into per-channel [MessageChannel] views.
 *
 * AAP multiplexes every service over a single connection, so a live session has
 * the video channel, three audio channels, the input channel, the microphone and
 * the control channel all wanting to read at once. If each called `receive()` on
 * the shared connection, whichever coroutine won the race would consume a
 * message meant for another — and the symptom would be an occasional, timing
 * dependent "unexpected message" failure that is close to undebuggable in a car.
 *
 * So exactly one coroutine reads the wire ([pump]) and hands each message to the
 * queue for its channel id. Sends do not need this: [FramedConnection] already
 * serialises writers.
 *
 * ## Backpressure and the deliberate choice not to apply it
 *
 * Per-channel queues are bounded. When one fills, the **oldest** message on that
 * channel is dropped rather than the pump suspending, because suspending the
 * pump would stall *every* channel to wait for the one that is behind — a slow
 * consumer on, say, the sensor channel would freeze video. Dropping is visible
 * ([droppedMessages]) rather than silent.
 *
 * This is the right trade for media, which is what fills a queue in practice: a
 * late video frame is worthless. It would be the wrong trade for control, so the
 * control channel gets a much larger queue and in practice never fills.
 */
class ChannelDemultiplexer(
    private val connection: FramedConnection,
    private val mediaQueueDepth: Int = DEFAULT_MEDIA_QUEUE_DEPTH,
    private val controlQueueDepth: Int = DEFAULT_CONTROL_QUEUE_DEPTH,
    /** Reports a message for a channel nobody subscribed to. */
    private val onUnroutable: (AapMessage) -> Unit = {},
) {
    private val queues = ConcurrentHashMap<Int, Channel<AapMessage>>()

    /** Messages dropped because a channel's queue was full. Diagnostics. */
    @Volatile
    var droppedMessages: Long = 0L
        private set

    private val droppedPerChannel = ConcurrentHashMap<Int, Long>()

    /**
     * [droppedMessages] broken down by channel id.
     *
     * The total on its own is not actionable. A drive log reporting "38 were
     * evicted from a full channel queue" says nothing about whether that was
     * video the driver will never miss or control traffic the head unit was
     * waiting on, and those two readings call for opposite responses. Naming
     * the channel is the difference between a number and a diagnosis.
     */
    val droppedByChannel: Map<Int, Long> get() = droppedPerChannel.toMap()

    /** Messages that arrived for a channel with no subscriber. Diagnostics. */
    @Volatile
    var unroutableMessages: Long = 0L
        private set

    /**
     * A view onto [channelId]. Calling this registers the queue, so subscribe
     * *before* starting [pump] or the first messages are unroutable.
     */
    fun channel(channelId: Int): MessageChannel {
        queueFor(channelId)
        return View(channelId)
    }

    private fun queueFor(channelId: Int): Channel<AapMessage> = queues.computeIfAbsent(channelId) {
        val depth = if (it == ChannelId.CONTROL.id) controlQueueDepth else mediaQueueDepth
        // Plain bounded channel: the pump only ever uses trySend, so it never
        // suspends, and dropping is done explicitly below so it can be counted.
        Channel(capacity = depth)
    }

    /**
     * Reads the wire until the peer closes or the caller is cancelled, routing
     * every message to its channel.
     *
     * Run this in its own coroutine for the life of the session. It returns
     * normally on EOF so the supervisor can treat a closed link as an ordinary
     * session end rather than an error.
     */
    suspend fun pump() {
        try {
            while (true) {
                val message = connection.receive()
                val queue = queues[message.channelId]
                if (queue == null) {
                    unroutableMessages++
                    onUnroutable(message)
                    continue
                }
                if (!queue.trySend(message).isSuccess) {
                    // The queue is full. Discard its oldest entry and take the
                    // new one: suspending here would stall every other channel
                    // waiting on the slowest consumer, so a stuck video sink
                    // would freeze control traffic and the session with it.
                    queue.tryReceive()
                    droppedMessages++
                    droppedPerChannel.merge(message.channelId, 1L, Long::plus)
                    queue.trySend(message)
                }
            }
        } catch (e: EOFException) {
            closeAll(e)
        } catch (e: CancellationException) {
            closeAll(e)
            throw e
        } catch (e: Exception) {
            closeAll(e)
            throw e
        }
    }

    /** Fails every subscriber so no channel is left suspended on a dead link. */
    fun closeAll(cause: Throwable? = null) {
        for (queue in queues.values) queue.close(cause)
    }

    private inner class View(private val channelId: Int) : MessageChannel {
        // The TLS session belongs to the link, not to any one channel.
        override var cryptor: Cryptor?
            get() = connection.cryptor
            set(value) { connection.cryptor = value }

        override suspend fun send(message: AapMessage) = connection.send(message)

        override suspend fun receive(): AapMessage {
            val result = queueFor(channelId).receiveCatching()
            result.getOrNull()?.let { return it }
            val cause = result.exceptionOrNull()
            throw (cause as? EOFException)
                ?: EOFException(
                    "channel ${ChannelId.describe(channelId)} closed: " +
                        // "link ended" was a lie in the one case it mattered.
                        // A genuine hang-up arrives here as the peer's own
                        // EOFException ("peer closed after N of M bytes") and
                        // takes the branch above; reaching this fallback with no
                        // cause means *Headway* closed the channels, and the old
                        // wording made its own teardown read as the head unit
                        // dropping the link. A drive was spent on that.
                        (cause?.message ?: "closed by Headway, not by the head unit")
                )
        }
    }

    companion object {
        /**
         * Roughly half a second of 30 fps video. Deep enough to absorb a
         * scheduling hiccup, shallow enough that a genuinely stuck consumer
         * discards stale frames instead of accumulating latency.
         */
        const val DEFAULT_MEDIA_QUEUE_DEPTH: Int = 16

        /** Control traffic is small and must not be dropped in practice. */
        const val DEFAULT_CONTROL_QUEUE_DEPTH: Int = 256
    }
}

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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel

/**
 * Captured audio waiting for the car, so that reading never waits on sending.
 *
 * ## The drive this exists because of
 *
 * `AudioChannel.sendPcm` calls `awaitCredit()`, which suspends until the head
 * unit acknowledges enough of what it has already been sent. That is correct —
 * the car sets the window and a phone that ignores it floods a device with a
 * small buffer. It was being called from the same loop that read the capture.
 *
 * So when the car was slow to acknowledge, Headway stopped reading. And
 * `AudioRecord` does not wait: its buffer is a ring, and audio that arrives
 * with no room is overwritten and gone. No error, no short read, nothing in
 * any log. The 2026-08-19 12:15 drive captured 231,990 KiB across a capture
 * that was open for 1271.6 s — 6,431 KiB less than 48 kHz stereo produces in
 * that time, which is **34 seconds of music** that the phone recorded over
 * before it could be sent, in a session that never once dropped, stalled or
 * reported an error.
 *
 * A queue is the whole fix. The reader reads and enqueues; the sender waits on
 * the car. Nothing the car does can stop the capture being drained on time.
 *
 * ## Why it still drops, and why that is better
 *
 * If the car stays behind for longer than the queue is deep, something has to
 * go. Dropping here is strictly better than overrunning in the kernel: it is
 * bounded, it is counted, and it keeps the car near the present instead of
 * playing further and further behind the phone. The oldest goes first, exactly
 * as `VideoPump` drops the oldest frame, and for the same reason — audio the
 * car should already have played is worth less than audio it is about to.
 */
class MediaQueue(depth: Int = DEFAULT_DEPTH) {

    private val depth = depth.coerceAtLeast(1)
    private val lock = Any()
    private val queue = ArrayDeque<ByteArray>(this.depth)

    /** Conflated: one pending wake-up is as good as ten. */
    private val wakeup = Channel<Unit>(Channel.CONFLATED)

    /** Buffers thrown away because the car was too far behind. Diagnostics. */
    var dropped: Long = 0L
        private set

    /** The most that was ever waiting at once. Diagnostics. */
    var deepest: Int = 0
        private set

    /** How many buffers are waiting right now. */
    val waiting: Int get() = synchronized(lock) { queue.size }

    /**
     * Adds [buffer], discarding the oldest if there is no room.
     *
     * Never suspends and never fails: it is called from the capture thread,
     * which must return to `AudioRecord.read` promptly whatever else is true.
     *
     * @return how many buffers were discarded to make room, so the caller can
     *   say so rather than lose it silently.
     */
    fun offer(buffer: ByteArray): Int {
        var discarded = 0
        synchronized(lock) {
            while (queue.size >= depth) {
                queue.removeFirst()
                dropped++
                discarded++
            }
            queue.addLast(buffer)
            if (queue.size > deepest) deepest = queue.size
        }
        wakeup.trySend(Unit)
        return discarded
    }

    /** Takes the oldest buffer, suspending until there is one or [close]. */
    suspend fun take(): ByteArray? {
        while (true) {
            synchronized(lock) { queue.removeFirstOrNull() }?.let { return it }
            val more = try {
                wakeup.receive()
                true
            } catch (cancelled: CancellationException) {
                // Rethrown rather than folded in with "the queue is closed".
                // A `runCatching` here would swallow the session ending and
                // send one more buffer into a cancelled coroutine, where the
                // failure would be reported as the link dying.
                throw cancelled
            } catch (closed: Exception) {
                false
            }
            if (!more) return synchronized(lock) { queue.removeFirstOrNull() }
        }
    }

    /** Wakes a waiting [take] so it can return null and finish. */
    fun close() {
        wakeup.close()
    }

    companion object {
        /**
         * Buffers held before the oldest is dropped.
         *
         * Fifty 40 ms buffers is two seconds. It is chosen against the failure
         * rather than for a round number: the head unit's acknowledgements are
         * what the sender waits on, and two seconds is far longer than any
         * acknowledgement round trip observed on the target vehicle while still
         * being a bounded amount of audio the car can fall behind before Headway
         * gives up on the oldest of it.
         */
        const val DEFAULT_DEPTH: Int = 50
    }
}

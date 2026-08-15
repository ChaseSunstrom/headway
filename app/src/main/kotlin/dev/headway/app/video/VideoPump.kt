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

package dev.headway.app.video

import dev.headway.protocol.channel.VideoChannel
import dev.headway.video.ScreenEncoder
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * Carries encoded frames from [ScreenEncoder] to [VideoChannel] across the gap
 * between a synchronous callback and a suspending, flow-controlled send.
 *
 * ## Why anything is needed here at all
 *
 * The two halves cannot be joined directly, for three separate reasons:
 *
 * 1. **The buffer is borrowed.** `ScreenEncoder.Sink.onFrame` hands out the
 *    encoder's own scratch buffer, valid only until the call returns. Anything
 *    that outlives the call must copy.
 * 2. **One side suspends and the other cannot.** `onFrame` is a plain callback
 *    on the drain thread; `VideoChannel.sendFrame` is `suspend` and *blocks on
 *    the head unit's acknowledgement window*, which openauto sets to 1 — that
 *    is, strictly lock-step. Calling one from the other is not possible, and
 *    blocking the drain thread would stall `MediaCodec` itself.
 * 3. **They run at different speeds and must be allowed to.** The encoder
 *    produces at the negotiated frame rate no matter what the car is doing. A
 *    car that stops acknowledging must not be able to freeze screen capture.
 *
 * So: a small bounded queue, and the newest frames win.
 *
 * ## Why dropping is right, and dropping the *oldest* is right
 *
 * Video is the one thing in this session with no value in being late. A frame
 * the car draws half a second after the finger moved is worse than no frame,
 * and the next keyframe repairs the picture anyway. So the queue is bounded and
 * overflow discards the *oldest* frame, never blocks the producer.
 *
 * ## Why a deque and not a `Channel` with `DROP_OLDEST`
 *
 * `Channel(onBufferOverflow = DROP_OLDEST)` does exactly the right thing and
 * will not say how often it did it -- the discard happens inside the channel
 * with no hook. A silent drop is indistinguishable from a bug, and a session
 * that drops steadily is telling you the bitrate or the link is wrong. So the
 * queue is an explicit `ArrayDeque` under a lock, which makes [droppedFrames]
 * exact rather than inferred, and a conflated `Channel<Unit>` carries only the
 * wake-up.
 */
class VideoPump(
    private val channel: VideoChannel,
    private val onStep: (String) -> Unit = {},
    queueDepth: Int = DEFAULT_QUEUE_DEPTH,
) : ScreenEncoder.Sink {

    /** One encoded access unit, copied out of the encoder's scratch buffer. */
    private class Frame(val bytes: ByteArray, val timestampMicros: Long, val keyFrame: Boolean)

    private val depth = queueDepth.coerceAtLeast(1)
    private val lock = Any()
    private val queue = ArrayDeque<Frame>(depth)

    /** Conflated: one pending wake-up is as good as ten. */
    private val wakeup = Channel<Unit>(Channel.CONFLATED)

    /**
     * SPS/PPS, held rather than queued.
     *
     * It must reach the car exactly once and *before* any frame, and it is not a
     * frame — `MediaFrame.codecConfig` writes it with no timestamp header, and
     * dropping it would leave the car with a decoder it cannot start. Keeping it
     * out of the droppable queue is the whole point.
     */
    @Volatile
    private var codecConfig: ByteArray? = null

    @Volatile
    private var codecConfigSent: Boolean = false

    /** Frames the car never saw because the queue overflowed. Diagnostics. */
    @Volatile
    var droppedFrames: Long = 0L
        private set

    /** Frames handed to the channel. Diagnostics. */
    @Volatile
    var sentFrames: Long = 0L
        private set

    // --- ScreenEncoder.Sink, called on the drain thread ----------------------

    override fun onCodecConfig(codecConfig: ByteArray) {
        // Copied for the same reason frames are: the contract does not promise
        // this array outlives the call either.
        this.codecConfig = codecConfig.copyOf()
        onStep("encoder produced ${codecConfig.size} bytes of codec config (SPS/PPS)")
    }

    override fun onFrame(data: ByteArray, length: Int, presentationTimeUs: Long, keyFrame: Boolean) {
        // Copied before anything else: past this method the encoder reuses it.
        val frame = Frame(data.copyOf(length), presentationTimeUs, keyFrame)
        synchronized(lock) {
            while (queue.size >= depth) {
                queue.removeFirst()
                droppedFrames++
            }
            queue.addLast(frame)
        }
        // Never suspends, never fails: conflated.
        wakeup.trySend(Unit)
    }

    /** Takes the next frame, or null when the queue is empty. */
    private fun poll(): Frame? = synchronized(lock) { queue.removeFirstOrNull() }

    // --- the sending side, driven by the session -----------------------------

    /**
     * Sends queued frames until the coroutine is cancelled or the channel dies.
     *
     * Run this in its own coroutine for the life of the video stream. It is the
     * only place `VideoChannel.sendFrame` is called, which matters: that method
     * reads the connection while waiting for credit, so two callers would race
     * for acknowledgements.
     */
    suspend fun pump() {
        while (currentCoroutineContext().isActive) {
            val frame = poll()
            if (frame == null) {
                // Nothing queued: wait to be woken rather than spinning.
                wakeup.receive()
                continue
            }
            sendCodecConfigOnce()
            // Suspends here whenever the head unit has not acknowledged enough
            // frames. That is the point: back-pressure lands on this coroutine,
            // and the encoder keeps running behind the bounded queue.
            channel.sendFrame(frame.bytes, frame.timestampMicros)
            sentFrames++
        }
    }

    /**
     * Sends SPS/PPS ahead of the first frame.
     *
     * Deferred to here rather than done at `onCodecConfig` time because that
     * runs on the drain thread, which cannot suspend, and because the channel is
     * not necessarily started yet when the encoder produces it.
     */
    private suspend fun sendCodecConfigOnce() {
        if (codecConfigSent) return
        val config = codecConfig ?: return
        channel.sendCodecConfig(config)
        codecConfigSent = true
        onStep("sent ${config.size} bytes of codec config to the head unit")
    }

    fun close() {
        wakeup.close()
        synchronized(lock) { queue.clear() }
    }

    private companion object {
        /**
         * Frames buffered before the oldest is dropped.
         *
         * Four is about 130 ms at 30 fps: enough to ride out a stalled
         * acknowledgement or a scheduling hiccup, short enough that the car
         * never shows a picture the driver would notice as lagging. It is also
         * the window Gearhead negotiates for video on this class of head unit
         * ("MAX_UNACK: 4" in a real Android Auto capture), so the queue and the
         * link are sized alike.
         */
        const val DEFAULT_QUEUE_DEPTH = 4
    }
}

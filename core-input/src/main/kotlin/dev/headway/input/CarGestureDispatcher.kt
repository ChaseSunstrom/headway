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

package dev.headway.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.os.Handler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/** What became of one [CarGesture] handed to the platform. */
sealed interface GestureDispatchOutcome {

    /** The platform replayed the whole gesture. */
    data object Completed : GestureDispatchOutcome

    /** The platform tore the gesture down early — usually because something else injected. */
    data object Cancelled : GestureDispatchOutcome

    /** `dispatchGesture` refused it outright and no callback will ever arrive. */
    data class Rejected(val reason: String) : GestureDispatchOutcome

    /**
     * Neither callback arrived in time. Observed cause is the service being
     * unbound mid-gesture; treated as a failure so the chain is not left waiting
     * forever on a callback that is never coming.
     */
    data object TimedOut : GestureDispatchOutcome

    /** Not sent: an earlier slice of the same gesture failed, so this one is an orphan. */
    data object SkippedBrokenChain : GestureDispatchOutcome

    /** True only when the target app actually saw the whole gesture. */
    val delivered: Boolean get() = this is Completed
}

/**
 * Feeds [CarGesture]s to an `AccessibilityService`, one at a time.
 *
 * ## Why anything is needed beyond calling dispatchGesture
 *
 * `dispatchGesture` is asynchronous and the platform injects for **one** gesture
 * per service at a time. A second call made while the first is still being
 * replayed does not queue behind it — the in-flight injection is torn down. For
 * a stream of car touches that is not a rare race: [GestureBuilder] emits a
 * slice every [GestureConfig.segmentBudgetMillis] during a drag, and each slice
 * takes its own duration to replay, so back-to-back dispatches are the normal
 * case rather than the exception. Every gesture therefore waits for its
 * predecessor's `GestureResultCallback` before it is submitted.
 *
 * Ordering matters twice over: a continued stroke is only valid after the stroke
 * it continues, so a reordered pair breaks the chain and drops the finger.
 *
 * ## When a slice fails
 *
 * A failed or cancelled slice leaves the platform with no stroke to continue, so
 * every later slice of that gesture is an orphan. Those are dropped
 * ([GestureDispatchOutcome.SkippedBrokenChain]) rather than dispatched, and the
 * caller should also call [GestureBuilder.cancel] so the builder stops producing
 * them. The next physical touch starts a new chain and recovers on its own.
 *
 * ## Requirements on the service
 *
 * The service's `accessibility-service` metadata must declare
 * `android:canPerformGestures="true"`, and dispatch is only legal once
 * `onServiceConnected` has run. Neither is checked here; both surface as
 * [GestureDispatchOutcome.Rejected].
 *
 * ## Threading
 *
 * [submit] is safe from any thread. [dispatchNow] and the internal worker share
 * one mutex, so a caller may mix the two.
 */
class CarGestureDispatcher(
    private val service: AccessibilityService,
    scope: CoroutineScope,
    /**
     * Looper the platform delivers `GestureResultCallback` on. Null means the
     * main thread, which is where an `AccessibilityService`'s own callbacks
     * already run.
     */
    private val callbackHandler: Handler? = null,
    /**
     * Added to a gesture's own duration to get the callback deadline. Covers the
     * binder round trip and the platform's own scheduling, both of which are
     * small next to a gesture; a value near zero would report healthy gestures
     * as timed out.
     */
    private val callbackSlackMillis: Long = 2_000L,
    private val onOutcome: (CarGesture, GestureDispatchOutcome) -> Unit = { _, _ -> },
) : AutoCloseable {

    private val queue = Channel<CarGesture>(Channel.UNLIMITED)
    private val lock = Mutex()
    private val depth = AtomicInteger(0)

    /**
     * The chain whose continuations are now orphans, or null.
     *
     * Only touched under [lock].
     */
    private var brokenChain: Long? = null

    private val worker: Job = scope.launch {
        for (gesture in queue) {
            depth.decrementAndGet()
            val outcome = dispatchNow(gesture)
            onOutcome(gesture, outcome)
        }
    }

    /** Gestures accepted but not yet handed to the platform. */
    val pending: Int get() = depth.get()

    /**
     * Queues a gesture.
     *
     * The queue is unbounded and lossless on purpose: dropping a slice under
     * pressure would break the very chain the drop was meant to protect, turning
     * a laggy scroll into a stuck finger. Watch [pending] instead — a queue that
     * grows means gestures are being produced faster than the platform will
     * replay them, and the fix is a larger [GestureConfig.segmentBudgetMillis],
     * not a shorter queue.
     *
     * @return false once [close] has been called.
     */
    fun submit(gesture: CarGesture): Boolean {
        depth.incrementAndGet()
        val sent = queue.trySend(gesture).isSuccess
        if (!sent) depth.decrementAndGet()
        return sent
    }

    /**
     * Dispatches one gesture and suspends until the platform is finished with it.
     *
     * Cancelling the calling coroutine stops the wait but **not** the injection:
     * the platform exposes no way to recall a dispatched gesture.
     */
    suspend fun dispatchNow(gesture: CarGesture): GestureDispatchOutcome = lock.withLock {
        if (brokenChain == gesture.chainId) return@withLock GestureDispatchOutcome.SkippedBrokenChain
        brokenChain = null

        val outcome = withTimeoutOrNull(gesture.durationMillis + callbackSlackMillis) {
            send(gesture.description)
        } ?: GestureDispatchOutcome.TimedOut

        // A failure only orphans something if more of this gesture was coming.
        if (!outcome.delivered && gesture.continues) brokenChain = gesture.chainId
        outcome
    }

    private suspend fun send(description: GestureDescription): GestureDispatchOutcome =
        suspendCancellableCoroutine { continuation ->
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    if (continuation.isActive) continuation.resume(GestureDispatchOutcome.Completed)
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    if (continuation.isActive) continuation.resume(GestureDispatchOutcome.Cancelled)
                }
            }

            val accepted = try {
                service.dispatchGesture(description, callback, callbackHandler)
            } catch (e: RuntimeException) {
                // IllegalStateException for a continuation the platform no longer
                // knows about, IllegalArgumentException for a malformed stroke.
                // Neither is worth killing the input pipeline over.
                if (continuation.isActive) {
                    continuation.resume(
                        GestureDispatchOutcome.Rejected("${e.javaClass.simpleName}: ${e.message}")
                    )
                }
                return@suspendCancellableCoroutine
            }

            // A false return means no callback will ever arrive; without this the
            // wait would burn the whole timeout for nothing.
            if (!accepted && continuation.isActive) {
                continuation.resume(
                    GestureDispatchOutcome.Rejected("dispatchGesture returned false")
                )
            }
        }

    /** Stops accepting gestures. Anything already queued is still dispatched. */
    override fun close() {
        queue.close()
    }

    /** [close], then wait for the queue to drain. */
    suspend fun closeAndJoin() {
        close()
        worker.join()
    }
}

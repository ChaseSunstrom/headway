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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** What the supervisor is doing, for the notification and the debug log. */
sealed interface LinkState {
    data object Idle : LinkState
    data class Connecting(val attempt: Int) : LinkState
    data object Connected : LinkState
    data class WaitingToRetry(val attempt: Int, val delayMillis: Long, val cause: String) : LinkState
    data class GaveUp(val cause: String) : LinkState
}

/**
 * Keeps the car link up.
 *
 * CLAUDE.md makes this a headline requirement rather than an afterthought:
 *
 * > Reconnection is a first-class feature: the app must survive walking away
 * > from the car and returning, BT flapping, and the head unit rebooting,
 * > reconnecting automatically within 15 seconds of the car being available,
 * > without user interaction.
 *
 * The supervisor is deliberately pure logic over two suspending lambdas, with no
 * Android and no socket types, so the retry behaviour — the part that is
 * genuinely hard to get right and impossible to eyeball — is unit-testable with
 * induced failures.
 *
 * ## Why the backoff is capped low
 *
 * Exponential backoff normally grows until it is measured in minutes. That is
 * wrong here. The user is sitting in a car expecting the screen to come back,
 * and the failure being retried is almost always "the car is not in range yet"
 * — which resolves the instant they get in. So the delay is capped at
 * [maxDelayMillis], defaulting to 8 s, which keeps the worst-case time from the
 * car becoming available to a completed connection inside the 15 s the spec
 * asks for: at most one full backoff wait plus one connect attempt.
 *
 * Retries are unbounded by default for the same reason. Giving up after N
 * attempts would mean a phone that sat in a driveway overnight needs the user to
 * tap something in the morning, which is exactly the "without user interaction"
 * the spec rules out.
 */
class SessionSupervisor(
    /**
     * Establishes and then *runs* one session, returning when it ends normally.
     * Throwing signals a failure that should be retried.
     *
     * The `onUp` argument must be invoked once the link is actually established
     * — not before, not after. It is what drives [LinkState.Connected]. A
     * session that only signalled by returning would report "Connected" at the
     * instant it *ended*, so the UI would read "Connecting" for an entire drive
     * and flash green exactly when the car went away.
     */
    private val runSession: suspend (onUp: () -> Unit) -> Unit,
    /** Called on every state change; wired to the notification and the log. */
    private val onState: (LinkState) -> Unit = {},
    private val initialDelayMillis: Long = 500,
    private val maxDelayMillis: Long = 8_000,
    /**
     * A per-failure override for the retry delay, in milliseconds.
     *
     * Returning null keeps the ordinary backoff. It exists because not every
     * failure wants the same haste: most of them are "the car is not in range
     * yet" and should retry within a second, but a failure that involved a
     * system UI the user has to interact with needs that UI to settle first, and
     * hammering it makes the situation worse rather than better. Capping the
     * whole backoff at that slower rate instead would punish the common case,
     * which is the one the 15-second reconnect requirement is about.
     *
     * The override is used verbatim rather than combined with the backoff, so
     * the caller can be explicit about what it wants.
     */
    private val delayOverrideFor: (Throwable) -> Long? = { null },
    /** Null means retry forever, which is the default and the intended behaviour. */
    private val maxAttempts: Int? = null,
    /** Injected so tests can run the state machine without real waiting. */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {

    /** Sessions that ran and ended without throwing. Diagnostics. */
    var completedSessions: Int = 0
        private set

    /** Connection attempts made across the supervisor's life. Diagnostics. */
    var attempts: Int = 0
        private set

    /**
     * Runs sessions until cancelled, reconnecting after each failure.
     *
     * Returns only if [maxAttempts] is exhausted; otherwise it runs until the
     * calling coroutine is cancelled, which is what the foreground service does.
     */
    suspend fun run() {
        var consecutiveFailures = 0

        while (true) {
            coroutineContext.ensureActive()
            attempts++
            report(LinkState.Connecting(attempts))

            val failure = try {
                // Connected is signalled from inside the session, when the link
                // is up, not here on return -- return means it has ended.
                runSession { report(LinkState.Connected) }
                completedSessions++
                // A session that ended cleanly is not a failure, so the next
                // attempt starts from the short delay rather than inheriting the
                // backoff of some earlier problem.
                consecutiveFailures = 0
                null
            } catch (e: CancellationException) {
                // Not every CancellationException means the user stopped.
                //
                // `withTimeout` reports a timeout by throwing
                // TimeoutCancellationException, which is a CancellationException
                // -- so a head unit that merely took too long arrived here
                // looking exactly like a shutdown. Rethrowing it ended the
                // supervisor permanently, with no retry and, because the state
                // callback is only invoked on the failure path, no log line
                // either. The link simply went quiet and stayed quiet.
                //
                // ensureActive() is the distinction: it throws when *this*
                // coroutine has been cancelled, which is the real shutdown, and
                // returns when the cancellation came from inside the attempt.
                coroutineContext.ensureActive()
                consecutiveFailures++
                e
            } catch (e: Throwable) {
                // Throwable, not Exception. An OutOfMemoryError decoding a
                // frame, or a NoClassDefFoundError from a platform API missing
                // on some build, is a bad session -- not a reason for the car
                // link to stop reconnecting for the rest of the drive. Genuine
                // cancellation is still distinguished above.
                consecutiveFailures++
                e
            }

            // The attempt limit bounds attempts, not just failures. Checking it
            // only on the failure path means a supervisor whose sessions keep
            // ending cleanly never returns, which is right in production (where
            // the limit is null) but makes the limit meaningless when one is set.
            val limit = maxAttempts
            if (limit != null && attempts >= limit) {
                if (failure != null) report(LinkState.GaveUp(describe(failure)))
                return
            }

            val delayMillis = failure?.let(delayOverrideFor)?.coerceAtLeast(0)
                ?: backoffFor(consecutiveFailures)
            if (failure != null) {
                report(LinkState.WaitingToRetry(attempts, delayMillis, describe(failure)))
            }
            sleep(delayMillis)
        }
    }

    /**
     * Delay before attempt number [consecutiveFailures] + 1.
     *
     * Doubling from [initialDelayMillis], capped at [maxDelayMillis]. Zero
     * failures means a session just ended cleanly, so reconnect promptly.
     */
    internal fun backoffFor(consecutiveFailures: Int): Long {
        if (consecutiveFailures <= 0) return initialDelayMillis
        // Shift rather than pow, and clamp the shift so a long-running failure
        // cannot overflow into a negative delay.
        val doublings = minOf(consecutiveFailures - 1, 20)
        val scaled = initialDelayMillis shl doublings
        return if (scaled <= 0 || scaled > maxDelayMillis) maxDelayMillis else scaled
    }

    /**
     * Publishes a state, and never lets the listener stop the link.
     *
     * `onState` is wired to a notification and a log. Both are diagnostics, and
     * a diagnostic that throws -- a notification posted without permission, a
     * formatting bug -- must not escape into the retry loop and end it. Three
     * of these calls sit outside the session's own try block, so before this
     * they could do exactly that.
     */
    private fun report(state: LinkState) {
        runCatching { onState(state) }
    }

    private fun describe(e: Throwable): String =
        e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "unknown failure"
}

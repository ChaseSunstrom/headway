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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.EOFException

/**
 * Reconnection is a first-class requirement, and its failure mode is a car that
 * silently never comes back. These tests induce the failures directly rather
 * than waiting for real timeouts: [SessionSupervisor] takes its sleep function
 * as a parameter, so the whole state machine runs instantly and the delays it
 * chose are recorded and asserted.
 */
class SessionSupervisorTest {

    private class Harness {
        val slept = mutableListOf<Long>()
        val states = mutableListOf<LinkState>()

        /**
         * Records the delay instead of taking it, but still suspends.
         *
         * The yield is not decoration. Without it the retry loop contains no
         * suspension point at all, so on `runBlocking`'s single thread a
         * supervisor with no attempt limit spins forever and starves the test
         * that is trying to stop it — including the timeout meant to bound it.
         */
        suspend fun sleep(millis: Long) {
            slept += millis
            yield()
        }
    }

    @Test
    fun `a failing session is retried with growing delay capped at the maximum`() = runBlocking {
        val h = Harness()
        val supervisor = SessionSupervisor(
            runSession = { _ -> throw EOFException("head unit went away") },
            onState = { h.states += it },
            initialDelayMillis = 500,
            maxDelayMillis = 8_000,
            maxAttempts = 8,
            sleep = h::sleep,
        )
        supervisor.run()

        // 500, 1000, 2000, 4000, 8000, then capped.
        assertEquals(listOf(500L, 1000L, 2000L, 4000L, 8000L, 8000L, 8000L), h.slept)
        // The eighth attempt hits the limit and returns without scheduling a ninth.
        assertEquals(8, supervisor.attempts)
        assertTrue(h.states.last() is LinkState.GaveUp)
    }

    @Test
    fun `the worst case reconnect fits inside the fifteen second budget`() = runBlocking {
        // CLAUDE.md: "reconnecting automatically within 15 seconds of the car
        // being available". Worst case is one full capped wait that began just
        // before the car came back, plus the connect attempt after it.
        //
        // Driven through run() rather than asserted on backoffFor() alone. The
        // old version of this test called backoffFor(50) directly and checked it
        // equalled the cap, which is a restatement of the implementation: it
        // would have passed unchanged while run() ignored the cap entirely.
        val h = Harness()
        var attempt = 0
        val supervisor = SessionSupervisor(
            runSession = { _ ->
                attempt++
                // Fail for a long time -- the car was gone all night -- then
                // succeed, which is the car coming back.
                if (attempt < 30) throw IllegalStateException("car not in range")
            },
            onState = { h.states += it },
            maxAttempts = 31,
            sleep = h::sleep,
        )
        supervisor.run()

        val waitBeforeSuccess = h.slept[28]
        assertTrue(
            waitBeforeSuccess <= 8_000,
            "after a long absence the wait was $waitBeforeSuccess ms; the cap is what keeps " +
                "the car-becomes-available-to-connected time inside 15 s",
        )
        assertTrue(h.slept.all { it <= 8_000 }, "no wait may exceed the cap: ${h.slept}")
    }

    @Test
    fun `a per-failure delay override is honoured, and only for that failure`() = runBlocking {
        // The join-failure path needs a much longer wait than the rest: coming
        // straight back after releasing a Wi-Fi network request lands on
        // Android's stale approval dialog instead of raising a new one. Every
        // other failure must keep the sub-second backoff the 15 s budget needs.
        class JoinFailure : RuntimeException("join")

        val h = Harness()
        var attempt = 0
        val supervisor = SessionSupervisor(
            runSession = { _ ->
                attempt++
                if (attempt == 1) throw JoinFailure() else throw IllegalStateException("other")
            },
            onState = { h.states += it },
            delayOverrideFor = { if (it is JoinFailure) 30_000L else null },
            maxAttempts = 3,
            sleep = h::sleep,
        )
        supervisor.run()

        assertEquals(30_000L, h.slept[0], "the join failure must get its own long wait")
        assertEquals(1_000L, h.slept[1], "an unrelated failure must not inherit it")
    }

    @Test
    fun `a terminal failure stops the loop instead of making things worse`() = runBlocking {
        // Retrying is the right instinct for almost everything here, and exactly
        // wrong for one case: a head unit whose DHCP table is full gives out no
        // more addresses, and every retry consumes another one. The loop has to
        // stop and let the message do the work.
        class DhcpExhausted : RuntimeException("no address")

        val h = Harness()
        var attempt = 0
        val supervisor = SessionSupervisor(
            runSession = { _ ->
                attempt++
                throw DhcpExhausted()
            },
            onState = { h.states += it },
            terminalFailure = { it is DhcpExhausted },
            sleep = h::sleep,
        )
        // No maxAttempts: without the terminal check this would never return.
        withTimeout(10_000) { supervisor.run() }

        assertEquals(1, attempt, "a terminal failure must not be retried")
        assertTrue(h.states.last() is LinkState.GaveUp, "got ${h.states.last()}")
        assertTrue(h.slept.isEmpty(), "there is nothing to wait for: ${h.slept}")
    }

    @Test
    fun `a listener that throws cannot stop the link`() = runBlocking {
        // onState drives a notification and a log, both diagnostics. Three of
        // the calls sit outside the session's own try block, so before they were
        // guarded a notification posted without permission could end the retry
        // loop for the rest of the drive.
        val h = Harness()
        var attempt = 0
        val supervisor = SessionSupervisor(
            runSession = { _ -> attempt++ },
            onState = { state ->
                h.states += state
                throw IllegalStateException("the notification blew up")
            },
            maxAttempts = 3,
            sleep = h::sleep,
        )
        supervisor.run()

        assertEquals(3, attempt, "the loop must keep running despite a throwing listener")
    }

    @Test
    fun `an Error in a session is retried rather than ending reconnection`() = runBlocking {
        // An OutOfMemoryError decoding a frame is a bad session, not a reason to
        // stop reconnecting. Only genuine cancellation ends the supervisor.
        val h = Harness()
        var attempt = 0
        val supervisor = SessionSupervisor(
            runSession = { _ ->
                attempt++
                if (attempt == 1) throw StackOverflowError("deep")
            },
            onState = { h.states += it },
            maxAttempts = 2,
            sleep = h::sleep,
        )
        supervisor.run()

        assertEquals(2, attempt, "the attempt after an Error must still happen")
    }

    @Test
    fun `a clean session end reconnects promptly instead of inheriting old backoff`() = runBlocking {
        // Walking away and returning ends the session cleanly. Coming back
        // should not be penalised by whatever went wrong an hour earlier.
        val h = Harness()
        var call = 0
        val supervisor = SessionSupervisor(
            runSession = { onUp ->
                call++
                // Fail three times to build up backoff, then succeed, then a
                // clean end again.
                if (call in 1..3) throw EOFException("out of range")
                onUp()
            },
            onState = { h.states += it },
            initialDelayMillis = 500,
            maxDelayMillis = 8_000,
            maxAttempts = 6,
            sleep = h::sleep,
        )
        supervisor.run()

        assertEquals(listOf(500L, 1000L, 2000L, 500L, 500L), h.slept)
        assertEquals(3, supervisor.completedSessions)
    }

    @Test
    fun `cancelling the supervisor ends it rather than being retried`() = runBlocking {
        // A user stopping the service must not have the session resurrected.
        // Note this cancels the supervisor's own coroutine, which is what
        // stopping the service actually does -- a CancellationException merely
        // *thrown* by the session is a different thing entirely, covered below.
        val h = Harness()
        val running = CompletableDeferred<Unit>()
        val job = launch {
            SessionSupervisor(
                runSession = { _ ->
                    running.complete(Unit)
                    awaitCancellation()
                },
                onState = { h.states += it },
                sleep = h::sleep,
            ).run()
        }

        running.await()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertTrue(h.slept.isEmpty(), "cancellation must not schedule a retry")
    }

    @Test
    fun `a session that times out is retried instead of killing the supervisor`() = runBlocking {
        // The bug this exists for, and it was invisible by construction.
        //
        // withTimeout reports a timeout by throwing TimeoutCancellationException,
        // which is a CancellationException -- so a head unit that merely took too
        // long was indistinguishable from the user pressing stop. The supervisor
        // rethrew it, reconnection ended permanently, and because the state
        // callback only fires on the failure path, nothing was logged. The link
        // went quiet mid-handshake and stayed quiet.
        val h = Harness()
        var calls = 0
        val supervisor = SessionSupervisor(
            runSession = { _ ->
                calls++
                // A real timeout, produced the way the link produces one.
                if (calls < 3) withTimeout(1) { delay(10_000) }
            },
            onState = { h.states += it },
            sleep = h::sleep,
            maxAttempts = 3,
        )

        supervisor.run()

        assertEquals(3, calls, "a timed-out attempt must be retried")
        assertTrue(
            h.states.any { it is LinkState.WaitingToRetry },
            "a timeout must be reported, not swallowed: ${h.states}",
        )
    }

    @Test
    fun `retries are unbounded by default`() = runBlocking {
        // A phone parked overnight must reconnect in the morning without the
        // user touching anything, so there is no attempt limit by default.
        val h = Harness()
        var call = 0
        val supervisor = SessionSupervisor(
            runSession = { _ ->
                call++
                throw EOFException("still out of range")
            },
            onState = { h.states += it },
            sleep = h::sleep,
        )

        // Stopped by cancelling it, which is the only thing that stops it now --
        // a thrown CancellationException is a failed attempt, not a shutdown.
        val job = launch { supervisor.run() }
        withTimeout(10_000) { while (call < 50) yield() }
        job.cancelAndJoin()

        assertTrue(supervisor.attempts >= 50, "the supervisor must not give up on its own")
        assertTrue(
            h.states.none { it is LinkState.GaveUp },
            "an unbounded supervisor must never report giving up",
        )
    }

    @Test
    fun `state transitions are reported for the notification and the log`() = runBlocking {
        val h = Harness()
        var call = 0
        SessionSupervisor(
            runSession = { onUp -> if (++call == 1) throw EOFException("bt flapped") else onUp() },
            onState = { h.states += it },
            maxAttempts = 2,
            sleep = h::sleep,
        ).run()

        assertEquals(LinkState.Connecting(1), h.states[0])
        val waiting = h.states[1]
        assertTrue(waiting is LinkState.WaitingToRetry, "got $waiting")
        assertEquals("bt flapped", (waiting as LinkState.WaitingToRetry).cause)
        assertEquals(LinkState.Connecting(2), h.states[2])
        assertEquals(LinkState.Connected, h.states[3])
    }

    @Test
    fun `a failure with no message still produces a legible cause`() = runBlocking {
        val h = Harness()
        SessionSupervisor(
            runSession = { _ -> throw EOFException() },
            onState = { h.states += it },
            maxAttempts = 1,
            sleep = h::sleep,
        ).run()
        val gaveUp = h.states.last()
        assertTrue(gaveUp is LinkState.GaveUp)
        assertEquals("EOFException", (gaveUp as LinkState.GaveUp).cause)
    }
}

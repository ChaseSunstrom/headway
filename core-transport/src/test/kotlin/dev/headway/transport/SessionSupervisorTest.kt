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
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
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
        suspend fun sleep(millis: Long) { slept += millis }
    }

    @Test
    fun `a failing session is retried with growing delay capped at the maximum`() = runBlocking {
        val h = Harness()
        val supervisor = SessionSupervisor(
            runSession = { throw EOFException("head unit went away") },
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
    fun `the worst case reconnect fits inside the fifteen second budget`() {
        // CLAUDE.md: "reconnecting automatically within 15 seconds of the car
        // being available". Worst case is one full capped wait that began just
        // before the car came back, plus the connect attempt after it.
        val supervisor = SessionSupervisor(runSession = {}, maxDelayMillis = 8_000)
        val worstWait = supervisor.backoffFor(consecutiveFailures = 50)
        assertEquals(8_000L, worstWait, "backoff must stay capped no matter how long the car was gone")
        assertTrue(
            worstWait < 15_000,
            "a $worstWait ms wait leaves no room to connect inside the 15 s budget",
        )
    }

    @Test
    fun `a clean session end reconnects promptly instead of inheriting old backoff`() = runBlocking {
        // Walking away and returning ends the session cleanly. Coming back
        // should not be penalised by whatever went wrong an hour earlier.
        val h = Harness()
        var call = 0
        val supervisor = SessionSupervisor(
            runSession = {
                call++
                // Fail three times to build up backoff, then succeed, then a
                // clean end again.
                if (call in 1..3) throw EOFException("out of range")
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
    fun `cancellation ends the supervisor rather than being retried`() {
        // A user stopping the service must not have the session resurrected.
        val h = Harness()
        val supervisor = SessionSupervisor(
            runSession = { throw CancellationException("service stopped") },
            onState = { h.states += it },
            sleep = h::sleep,
        )
        assertThrows(CancellationException::class.java) { runBlocking { supervisor.run() } }
        assertEquals(1, supervisor.attempts)
        assertTrue(h.slept.isEmpty(), "cancellation must not schedule a retry")
    }

    @Test
    fun `retries are unbounded by default`() = runBlocking {
        // A phone parked overnight must reconnect in the morning without the
        // user touching anything, so there is no attempt limit by default.
        val h = Harness()
        var call = 0
        val supervisor = SessionSupervisor(
            runSession = {
                call++
                if (call < 50) throw EOFException("still out of range")
                throw CancellationException("stop the test")
            },
            onState = { h.states += it },
            sleep = h::sleep,
        )
        assertThrows(CancellationException::class.java) { runBlocking { supervisor.run() } }
        assertEquals(50, supervisor.attempts, "the supervisor must not give up on its own")
    }

    @Test
    fun `state transitions are reported for the notification and the log`() = runBlocking {
        val h = Harness()
        var call = 0
        SessionSupervisor(
            runSession = { if (++call == 1) throw EOFException("bt flapped") },
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
            runSession = { throw EOFException() },
            onState = { h.states += it },
            maxAttempts = 1,
            sleep = h::sleep,
        ).run()
        val gaveUp = h.states.last()
        assertTrue(gaveUp is LinkState.GaveUp)
        assertEquals("EOFException", (gaveUp as LinkState.GaveUp).cause)
    }
}

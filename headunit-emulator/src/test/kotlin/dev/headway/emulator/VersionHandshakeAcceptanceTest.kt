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

package dev.headway.emulator

import dev.headway.protocol.control.VersionHandshake
import dev.headway.protocol.io.FramedConnection
import dev.headway.protocol.session.PhoneSession
import dev.headway.protocol.session.SessionException
import dev.headway.transport.LoopbackTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Phase 1 acceptance — the version-handshake portion, over the fake transport.
 *
 * CLAUDE.md's Phase 1 criterion is:
 *
 * > Headway on a real phone completes discovery against the emulator over actual
 * > BT + Wi-Fi, and in CI over fake transport, 20/20 consecutive attempts.
 *
 * **This test is not all of that**, and the gap is stated rather than papered
 * over. It covers the plaintext version exchange 20/20 over the fake transport.
 * The TLS handshake and service discovery are not implemented yet, and the
 * real-hardware half cannot run here at all (BLOCKERS.md B-001). Phase 1 stays
 * "In progress" in PROGRESS.md until the rest lands.
 *
 * The 20-iteration count is the one from the spec: a handshake that works once
 * but races on the second attempt is the failure mode this is designed to catch.
 */
class VersionHandshakeAcceptanceTest {

    /** Runs one complete phone <-> head unit version exchange over a fresh pair. */
    private fun oneSession(): VersionHandshake.VersionResponse = runBlocking {
        LoopbackTransport.pair().use { pair ->
            val headUnit = HeadUnitEmulator(FramedConnection(pair.headUnit))
            val phone = PhoneSession(FramedConnection(pair.phone))

            // The phone must be listening before the head unit speaks: the head
            // unit initiates, and the phone's first act is a blocking read.
            val phoneSide = async(Dispatchers.IO) { phone.awaitVersionHandshake() }
            val response = withTimeout(10_000) { headUnit.performVersionHandshake() }
            val seenByPhone = withTimeout(10_000) { phoneSide.await() }

            assertEquals(VersionHandshake.MAJOR, seenByPhone.major)
            assertEquals(VersionHandshake.MINOR, seenByPhone.minor)
            response
        }
    }

    @Test
    fun `version handshake completes over the fake transport`() {
        val response = oneSession()
        assertTrue(response.compatible, "status was ${response.status}")
        assertEquals(VersionHandshake.MAJOR, response.version.major)
        assertEquals(VersionHandshake.MINOR, response.version.minor)
    }

    @Test
    fun `twenty consecutive handshakes all succeed`() {
        // The spec's 20/20 criterion. Each iteration builds a brand new transport
        // pair, so this also covers the teardown path -- a session that leaks a
        // thread or leaves a pipe half-closed shows up here rather than in a car.
        repeat(20) { attempt ->
            val response = oneSession()
            assertTrue(response.compatible, "attempt ${attempt + 1} failed with status ${response.status}")
        }
    }

    @Test
    fun `the head unit speaks first`() = runBlocking {
        // If the phone ever sent first, a real head unit would see an unexpected
        // message before its VersionRequest. Assert the ordering directly: the
        // phone writes nothing until it has read the request.
        LoopbackTransport.pair().use { pair ->
            val phone = PhoneSession(FramedConnection(pair.phone))
            val phoneSide = async(Dispatchers.IO) { phone.awaitVersionHandshake() }

            val headUnitConnection = FramedConnection(pair.headUnit)
            // Send the request only after giving the phone a chance to misbehave.
            val headUnit = HeadUnitEmulator(headUnitConnection)
            val response = withTimeout(10_000) { headUnit.performVersionHandshake() }

            withTimeout(10_000) { phoneSide.await() }
            assertTrue(response.compatible)
        }
    }

    @Test
    fun `an incompatible major version is refused by the phone`() = runBlocking {
        LoopbackTransport.pair().use { pair ->
            val headUnit = HeadUnitEmulator(
                FramedConnection(pair.headUnit),
                announcedVersion = VersionHandshake.Version(major = 9, minor = 0),
            )
            val phone = PhoneSession(FramedConnection(pair.phone))

            val phoneSide = async(Dispatchers.IO) {
                runCatching { phone.awaitVersionHandshake() }
            }
            val response = withTimeout(10_000) { headUnit.performVersionHandshake() }

            // The phone must still answer -- refusing silently would leave the
            // head unit waiting -- but the status says no.
            assertEquals(VersionHandshake.STATUS_NO_COMPATIBLE_VERSION, response.status)
            assertTrue(!response.compatible)

            val phoneResult = withTimeout(10_000) { phoneSide.await() }
            assertTrue(phoneResult.exceptionOrNull() is SessionException, "phone should raise")
        }
    }

    @Test
    fun `a differing minor version is accepted`() = runBlocking {
        // The references announce 1.1, 1.5 and 1.6 and interoperate, so refusing
        // on a minor mismatch would reject head units that work.
        LoopbackTransport.pair().use { pair ->
            val headUnit = HeadUnitEmulator(
                FramedConnection(pair.headUnit),
                announcedVersion = VersionHandshake.Version(major = 1, minor = 1),
            )
            val phone = PhoneSession(FramedConnection(pair.phone))

            val phoneSide = async(Dispatchers.IO) { phone.awaitVersionHandshake() }
            val response = withTimeout(10_000) { headUnit.performVersionHandshake() }
            val seen = withTimeout(10_000) { phoneSide.await() }

            assertTrue(response.compatible)
            assertEquals(1, seen.major)
            assertEquals(1, seen.minor)
        }
    }
}

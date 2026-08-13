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

package dev.headway.transport.tls

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Drives a full AAP TLS handshake between the two roles, exchanging flights the
 * way `ENCAPSULATED_SSL` control messages do.
 */
class TlsSessionTest {

    /**
     * Runs the handshake to completion, returning both sessions and the number
     * of flights exchanged.
     */
    private fun handshake(): Triple<TlsSession, TlsSession, Int> {
        // The head unit is the TLS *client*, the phone is the TLS *server*.
        val headUnit = TlsSession(AapTls.headUnitEngine())
        val phone = TlsSession(AapTls.phoneEngine())

        var flights = 0
        var fromHeadUnit = headUnit.beginHandshake()
        assertTrue(fromHeadUnit.isNotEmpty(), "the head unit must open with a ClientHello")

        while (!headUnit.handshakeComplete || !phone.handshakeComplete) {
            check(++flights < 20) { "handshake did not converge" }

            val fromPhone = phone.continueHandshake(fromHeadUnit)
            if (phone.handshakeComplete && headUnit.handshakeComplete) {
                if (fromPhone.isNotEmpty()) headUnit.continueHandshake(fromPhone)
                break
            }
            fromHeadUnit = headUnit.continueHandshake(fromPhone)
        }
        return Triple(headUnit, phone, flights)
    }

    @Test
    fun `handshake completes between head unit client and phone server`() {
        val (headUnit, phone, flights) = handshake()
        assertTrue(headUnit.handshakeComplete, "head unit side incomplete")
        assertTrue(phone.handshakeComplete, "phone side incomplete")
        assertTrue(flights in 1..10, "unexpected flight count: $flights")
    }

    @Test
    fun `negotiates a TLS 1_2 ECDHE suite as the references observe`() {
        val (headUnit, phone, _) = handshake()
        assertEquals(headUnit.cipherSuite, phone.cipherSuite)
        // aa-proxy-rs logs TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 against a real
        // unit (src/ssl_rustls.rs L334-L336). We assert the family rather than
        // the exact suite, since the JDK's preference order may pick AES-256.
        assertTrue(
            headUnit.cipherSuite.contains("ECDHE_RSA"),
            "expected an ECDHE_RSA suite, negotiated ${headUnit.cipherSuite}",
        )
    }

    @Test
    fun `application data round trips in both directions after the handshake`() {
        val (headUnit, phone, _) = handshake()

        val toPhone = "video focus request".toByteArray()
        assertArrayEquals(toPhone, phone.decrypt(headUnit.encrypt(toPhone)))

        val toHeadUnit = "service discovery response".toByteArray()
        assertArrayEquals(toHeadUnit, headUnit.decrypt(phone.encrypt(toHeadUnit)))
    }

    @Test
    fun `a payload larger than one TLS record round trips`() {
        // A video keyframe exceeds the 16 KiB TLS record limit, so encrypt must
        // emit several records and decrypt must consume all of them.
        val (headUnit, phone, _) = handshake()
        val payload = Random(42).nextBytes(100_000)

        val ciphertext = headUnit.encrypt(payload)
        assertTrue(ciphertext.size > payload.size, "ciphertext should carry record overhead")
        assertArrayEquals(payload, phone.decrypt(ciphertext))
    }

    @Test
    fun `encrypting before the handshake completes is refused`() {
        val phone = TlsSession(AapTls.phoneEngine())
        val e = assertThrowsIllegalState { phone.encrypt(ByteArray(4)) }
        assertTrue(e.message!!.contains("before the TLS handshake"), e.message)
    }

    @Test
    fun `the vendored key material loads and the pairs match`() {
        val phone = AapTls.phoneKeyMaterial()
        val headUnit = AapTls.headUnitKeyMaterial()

        assertEquals("RSA", phone.privateKey.algorithm)
        assertEquals("RSA", headUnit.privateKey.algorithm)
        assertTrue(phone.certificate.subjectX500Principal.name.contains("CarService"))
        assertTrue(headUnit.certificate.issuerX500Principal.name.contains("Google Automotive Link"))
    }

    /**
     * Documents the expiry that BLOCKERS.md B-003 tracks. If this test ever
     * starts failing because the certificate became valid again, something is
     * very wrong; it exists so the expiry is a stated fact in the test suite
     * rather than a surprise in a car park.
     */
    @Test
    fun `the vendored phone certificate is known to be expired`() {
        val phone = AapTls.phoneKeyMaterial()
        val expiry = phone.certificate.notAfter
        assertTrue(
            expiry.before(java.util.Date()),
            "the phone certificate was expected to be expired (see BLOCKERS.md B-003); notAfter=$expiry",
        )
        // The handshake still completes because AAP disables peer verification
        // (aasdk SSL_VERIFY_NONE). A real head unit may not be so relaxed.
        val (_, phoneSession, _) = handshake()
        assertTrue(phoneSession.handshakeComplete)
    }

    /**
     * The premise of certificate rotation, asserted rather than assumed.
     *
     * `AapTls.bundledPhoneCredentials` exists because two of the three vendored
     * certificates have *not* expired and are signed by the *same* CA as the one
     * that has. If either half of that stopped being true — a re-vendoring that
     * dropped the unexpired pairs, or one of them quietly passing its own
     * notAfter as the years go by — the rotation would be busywork that costs a
     * user two failed sessions per connect, and nothing else in the codebase
     * would notice.
     */
    @Test
    fun `rotation has unexpired candidates from the same CA`() {
        val candidates = AapTls.bundledPhoneCredentials()
        assertTrue(candidates.size >= 2, "rotation needs something to rotate to")
        assertEquals(
            candidates.map { it.id }.distinct().size,
            candidates.size,
            "candidate ids are used in the quirk file and must be unique",
        )

        val issuers = candidates.map { it.material.certificate.issuerX500Principal.name }.distinct()
        assertEquals(
            1, issuers.size,
            "every candidate must be signed by the same CA, or presenting one instead of " +
                "another changes the chain as well as the dates: $issuers",
        )
        assertTrue(issuers.single().contains("Google Automotive Link"), issuers.single())

        val unexpired = candidates.filter { it.currentlyValid }
        assertTrue(
            unexpired.isNotEmpty(),
            "no unexpired candidate is left, so rotation cannot help any more and " +
                "BLOCKERS.md B-003 needs revisiting: " +
                candidates.joinToString { "${it.id} expires ${it.material.certificate.notAfter}" },
        )

        // Each key must belong to its certificate. A mismatched pair fails at
        // the head unit with nothing useful on the wire, and the rotation would
        // read as "the car refused that certificate too".
        candidates.forEach { candidate ->
            val public = candidate.material.certificate.publicKey
                as java.security.interfaces.RSAPublicKey
            val private = candidate.material.privateKey
                as java.security.interfaces.RSAPrivateKey
            assertEquals(
                public.modulus, private.modulus,
                "the ${candidate.id} key does not belong to the ${candidate.id} certificate",
            )
        }
    }

    /** The phone-role certificate stays first: it is what a lenient unit expects. */
    @Test
    fun `rotation starts with the phone-role certificate`() {
        val first = AapTls.bundledPhoneCredentials().first()
        assertEquals("phone", first.id)
        assertTrue(first.material.certificate.subjectX500Principal.name.contains("CarService"))
    }

    private fun assertThrowsIllegalState(block: () -> Unit): IllegalStateException =
        try {
            block()
            throw AssertionError("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            e
        }
}

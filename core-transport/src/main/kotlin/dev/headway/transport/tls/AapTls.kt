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

import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** An X.509 certificate and its private key. */
class KeyMaterial(val certificate: X509Certificate, val privateKey: PrivateKey)

/**
 * TLS setup for AAP.
 *
 * ## Role polarity
 *
 * **The phone is the TLS server and the head unit is the TLS client.** This is
 * the opposite of what the transport direction suggests (the phone opens the TCP
 * connection *to* the head unit) and is the single easiest thing to get
 * backwards. Sources: `AACS/AAServer/src/AaCommunicator.cpp` L292-L300 uses
 * `SSL_set_accept_state` on the phone side; `aasdk/src/Transport/SSLWrapper.cpp`
 * L137-L140 uses `SSL_set_connect_state` on the head-unit side; `aa-proxy-rs`
 * states the mapping explicitly in `src/ssl_rustls.rs` L430-L438.
 *
 * ## Peer verification is disabled, deliberately
 *
 * aasdk calls `SSL_set_verify(ssl, SSL_VERIFY_NONE, nullptr)`
 * (`SSLWrapper.cpp` L137-L140) and the other references behave the same way.
 * That is a property of the protocol as deployed, not a shortcut taken here: the
 * peer presents a certificate chained to a Google Automotive Link CA we do not
 * possess, so validating it is not possible. Headway therefore presents its
 * certificate and does not validate the head unit's.
 *
 * Concretely this means **a hostile device on the car's Wi-Fi could impersonate
 * the head unit.** The exposure is bounded by the fact that the car's AP has no
 * internet and an attacker must already be associated to it, but it is real and
 * it is not something Headway can fix without the CA.
 */
object AapTls {

    /**
     * TLS 1.2.
     *
     * `aa-proxy-rs` pins exactly this (`src/ssl_rustls.rs` L447-L484) and logs
     * `TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256` as the negotiated suite
     * (L334-L336). AACS builds its context with `SSLv23_*_method`
     * (`AAClient/src/AaCommunicator.cpp` L196-L236), which negotiates down to
     * the same place against a real unit.
     */
    const val PROTOCOL: String = "TLSv1.2"

    /** SNI the references present. Source: `aa-proxy-rs/src/ssl_rustls.rs` L477-L479. */
    const val SERVER_NAME: String = "android.auto"

    private const val RESOURCE_ROOT = "/dev/headway/transport/tls"

    private const val PKCS1_LABEL = "RSA PRIVATE KEY"

    /** `1.2.840.113549.1.1.1` — rsaEncryption, DER-encoded. */
    private val RSA_ENCRYPTION_OID = byteArrayOf(
        0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x01,
    )

    /** An ASN.1 NULL: the parameters rsaEncryption takes. */
    private val DER_NULL = byteArrayOf(0x05, 0x00)

    /**
     * The phone-side certificate, as extracted by the AACS project.
     *
     * **This certificate expired on 2022-08-24.** See BLOCKERS.md B-003 — a real
     * head unit that validates it will reject the session, and no amount of
     * client-side work fixes that. [phoneKeyMaterial] therefore accepts
     * replacement material so a user can supply their own.
     *
     * Source: `AACS/AAServer/ssl/android_auto.crt` and `.key`. The key was
     * converted from PKCS#1 to PKCS#8 when vendored so that the JDK can load it
     * without a third-party PEM parser; the key itself is unchanged.
     */
    fun phoneKeyMaterial(): KeyMaterial = loadResourceKeyMaterial("phone")

    /**
     * The phone's key material, preferring a pair the user supplied.
     *
     * ## Why this has to be replaceable
     *
     * The bundled phone certificate expired on 2022-08-24 and cannot be
     * reissued without Google's CA key (BLOCKERS.md B-003). A real 2021
     * Chevrolet Infotainment 3 unit checks it: the session reaches
     * `AUTH_COMPLETE`, the unit answers `STATUS_AUTHENTICATION_FAILURE`, and the
     * car screen says the phone and vehicle calendars are set to different dates
     * and times — which is its rendering of a certificate validity failure.
     * Nothing in the protocol layer can fix that, and no reference ships a valid
     * replacement: AACS carries the identical expired certificate, and
     * aa-proxy-rs deliberately loads its pair from a path the operator provides
     * (`src/ssl_rustls.rs` L440-L441) rather than bundling one at all.
     *
     * So Headway does the same thing. If both files exist and parse, they are
     * used; anything else falls back to the bundled pair, because a car that
     * cannot connect is a better outcome than an app that will not start.
     *
     * @param certPem PEM certificate, or null to use the bundled one.
     * @param keyPem PKCS#8 PEM private key, or null.
     */
    fun phoneKeyMaterial(certPem: String?, keyPem: String?): KeyMaterial {
        if (certPem.isNullOrBlank() || keyPem.isNullOrBlank()) return phoneKeyMaterial()
        return runCatching {
            KeyMaterial(parseCertificate(certPem), parsePkcs8PrivateKey(keyPem))
        }.getOrElse { phoneKeyMaterial() }
    }

    /**
     * Whether [material] is usable at [at], and what to say if not.
     *
     * Returns null when the certificate is inside its validity window. The
     * check is done by Headway rather than left to the head unit because the
     * head unit's rejection arrives as a bare status code after a full TCP and
     * TLS bring-up, and a log that says the certificate expired four years ago
     * before any of that happens saves the reader the whole chain.
     */
    fun validityProblem(material: KeyMaterial, at: java.util.Date = java.util.Date()): String? {
        val cert = material.certificate
        return when {
            at.before(cert.notBefore) ->
                "the phone certificate is not valid until ${cert.notBefore} and this device " +
                    "thinks it is $at"
            at.after(cert.notAfter) ->
                "the phone certificate expired on ${cert.notAfter} and this device thinks it " +
                    "is $at"
            else -> null
        }
    }

    /**
     * The head-unit certificate, used by the emulator to play the car's role.
     * Valid until 2045. Source: `aasdk/cert/headunit.crt` and `.key`.
     */
    fun headUnitKeyMaterial(): KeyMaterial = loadResourceKeyMaterial("headunit")

    /**
     * A certificate Headway can present to the head unit, and why it might work.
     *
     * @param id stable identifier, used in the quirk file and the log.
     * @param label one line for a person reading the log or the settings screen.
     * @param rationale why this candidate is worth an attempt.
     */
    class PhoneCredential(
        val id: String,
        val label: String,
        val rationale: String,
        val material: KeyMaterial,
    ) {
        /** True when this device's clock puts the certificate inside its window. */
        val currentlyValid: Boolean get() = validityProblem(material) == null

        override fun toString(): String =
            "$label (${material.certificate.subjectX500Principal.name}, " +
                "expires ${material.certificate.notAfter})"
    }

    /**
     * Every bundled pair Headway can try, in the order worth trying them.
     *
     * ## Why there is more than one
     *
     * The phone-role certificate expired on 2022-08-24 and cannot be reissued
     * (BLOCKERS.md B-003). But it is not the only material signed by the *same*
     * "Google Automotive Link" CA that the references carry, and the others have
     * not expired:
     *
     * | id | subject | expires |
     * |----|---------|---------|
     * | `phone` | `O=CarService, OU=53` | 2022-08-24 |
     * | `internal` | `O=Android-Auto-Internal, OU=01` | 2048-08-01 |
     * | `headunit` | `O=JVC Kenwood, OU=01` | 2045-04-29 |
     *
     * The last two were issued for the *head unit* role, which is why no phone
     * implementation has ever presented one. Whether a car accepts one from the
     * phone side depends entirely on what it checks. If it verifies the chain up
     * to the Google Automotive Link CA and the validity dates — which is what a
     * garden-variety TLS peer verification does, and what the target car's
     * `STATUS_AUTHENTICATION_FAILURE`-rather-than-`STATUS_CERTIFICATE_ERROR`
     * points at — then an unexpired sibling certificate satisfies it and the
     * role in the subject never comes up. If it additionally pins the subject or
     * checks a role attribute, it will not.
     *
     * That is not knowable from the references, and it costs one reconnect to
     * find out, so Headway tries them rather than assuming. Ordering puts the
     * correct-role certificate first, because it is what every reference sends
     * and what a lenient unit expects; the unexpired ones follow.
     *
     * Sources: `AACS/AAServer/ssl/android_auto.crt`,
     * `AACS/AAClient/ssl/headunit.crt`, and `aasdk/src/Messenger/Cryptor.cpp`
     * L275 (`Cryptor::cCertificate`). The fetched bytes are stored verbatim; the
     * PKCS#1 keys among them are wrapped in a PKCS#8 envelope at load time by
     * [wrapPkcs1AsPkcs8], because the JDK's `KeyFactory` takes only PKCS#8 and a
     * PEM library for one envelope is a dependency the licence audit would carry
     * forever. No key material is altered, and each was checked to match its
     * certificate on the RSA modulus.
     */
    fun bundledPhoneCredentials(): List<PhoneCredential> = listOf(
        PhoneCredential(
            id = "phone",
            label = "the phone-role certificate",
            rationale = "the role every reference implementation sends, but it expired " +
                "on 2022-08-24 — this is the one that works if the car's clock has been " +
                "set back, or if the car does not check dates",
            material = phoneKeyMaterial(),
        ),
        PhoneCredential(
            id = "internal",
            label = "the Android-Auto-Internal certificate",
            rationale = "signed by the same Google Automotive Link CA and valid until " +
                "2048 — this works if the car checks the chain and the dates but not " +
                "which role the certificate was issued for",
            material = loadResourceKeyMaterial("internal"),
        ),
        PhoneCredential(
            id = "headunit",
            label = "the JVC Kenwood certificate",
            rationale = "the same idea as Android-Auto-Internal with a different subject, " +
                "valid until 2045, in case the car objects to that one by name",
            material = headUnitKeyMaterial(),
        ),
    )

    /** Builds an [SSLEngine] in server mode — the phone's role. */
    fun phoneEngine(material: KeyMaterial = phoneKeyMaterial()): SSLEngine =
        engine(material, clientMode = false)

    /** Builds an [SSLEngine] in client mode — the head unit's role. */
    fun headUnitEngine(material: KeyMaterial = headUnitKeyMaterial()): SSLEngine =
        engine(material, clientMode = true)

    private fun engine(material: KeyMaterial, clientMode: Boolean): SSLEngine {
        val context = SSLContext.getInstance(PROTOCOL).apply {
            init(keyManagers(material), arrayOf<TrustManager>(AcceptAllPeers), SecureRandom())
        }
        return context.createSSLEngine().apply {
            useClientMode = clientMode
            if (!clientMode) {
                // Ask for the head unit's certificate, but do not require it.
                //
                // This is `SSL_VERIFY_PEER` with an always-accept callback,
                // which is what both phone-role references do — they request
                // the peer certificate and then accept whatever comes back.
                // Headway previously asked for nothing at all, which is a
                // materially different ClientHello/CertificateRequest exchange
                // from the one a real head unit has been built against, and it
                // also meant the unit's certificate never reached us even as a
                // diagnostic.
                //
                // `want`, not `need`: the certificate cannot be validated (see
                // the class note on the expired bundled CA), so a unit that
                // declines to send one must still get a session. `need` would
                // turn "did not present a certificate" into a handshake failure
                // for no gain.
                wantClientAuth = true
                needClientAuth = false
            }
            enabledProtocols = arrayOf(PROTOCOL)
        }
    }

    /**
     * A key manager that always presents Headway's certificate.
     *
     * The platform's own `KeyManagerFactory` is deliberately not used. Its
     * `chooseServerAlias` filters candidate aliases, and one of the things it
     * filters on is validity: a certificate that expired in 2022 can be skipped
     * on a phone whose clock says 2026, leaving the handshake with no alias and
     * failing it as "no cipher suites in common" — a message that says nothing
     * about certificates at all.
     *
     * Headway has exactly one certificate and no choice to make, so there is
     * nothing for a selection policy to do except go wrong. Whether the
     * certificate is acceptable is the *head unit's* judgement, made against the
     * head unit's clock, and this makes sure it always gets the chance to make
     * it. That matters for the one workaround available to a user with no
     * replacement certificate: rolling the car's clock back into the validity
     * window only helps if the phone still sends the thing.
     */
    private class FixedKeyManager(private val material: KeyMaterial) :
        javax.net.ssl.X509ExtendedKeyManager() {

        override fun getClientAliases(keyType: String?, issuers: Array<out java.security.Principal>?) =
            arrayOf(ALIAS)

        override fun chooseClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out java.security.Principal>?,
            socket: java.net.Socket?,
        ) = ALIAS

        override fun getServerAliases(keyType: String?, issuers: Array<out java.security.Principal>?) =
            arrayOf(ALIAS)

        override fun chooseServerAlias(
            keyType: String?,
            issuers: Array<out java.security.Principal>?,
            socket: java.net.Socket?,
        ) = ALIAS

        override fun chooseEngineServerAlias(
            keyType: String?,
            issuers: Array<out java.security.Principal>?,
            engine: SSLEngine?,
        ) = ALIAS

        override fun chooseEngineClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out java.security.Principal>?,
            engine: SSLEngine?,
        ) = ALIAS

        override fun getCertificateChain(alias: String?) = arrayOf(material.certificate)

        override fun getPrivateKey(alias: String?) = material.privateKey

        private companion object {
            const val ALIAS = "aap"
        }
    }

    private fun keyManagers(material: KeyMaterial): Array<javax.net.ssl.KeyManager> =
        arrayOf(FixedKeyManager(material))

    private fun loadResourceKeyMaterial(name: String): KeyMaterial {
        val certPem = readResource("$RESOURCE_ROOT/$name.crt")
        val keyPem = readResource("$RESOURCE_ROOT/$name.key")
        return KeyMaterial(parseCertificate(certPem), parsePkcs8PrivateKey(keyPem))
    }

    private fun readResource(path: String): String =
        AapTls::class.java.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
            ?: error("missing TLS resource $path")

    /** Parses a PEM certificate. */
    fun parseCertificate(pem: String): X509Certificate {
        val der = decodePem(pem, "CERTIFICATE")
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    /**
     * Parses a PKCS#8 PEM private key (`-----BEGIN PRIVATE KEY-----`).
     *
     * PKCS#1 (`BEGIN RSA PRIVATE KEY`) is deliberately unsupported: the JDK
     * cannot load it without a third-party parser, and converting the one
     * vendored key at packaging time is simpler than carrying an ASN.1 parser we
     * would have to keep correct.
     */
    fun parsePkcs8PrivateKey(pem: String): PrivateKey {
        // PKCS#1 is accepted rather than refused. It used to tell the user to
        // run `openssl pkcs8 -topk8`, which is a reasonable thing to ask of a
        // developer and an unreasonable thing to ask of a driver who has been
        // handed a key file — and the references emit PKCS#1: aasdk's
        // `Cryptor.cpp` embeds a `BEGIN RSA PRIVATE KEY` literal, which is the
        // pair for the one certificate a real Chevrolet accepts.
        val der = if (pem.contains(PKCS1_LABEL)) {
            wrapPkcs1AsPkcs8(decodePem(pem, PKCS1_LABEL))
        } else {
            decodePem(pem, "PRIVATE KEY")
        }
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
    }

    /**
     * Wraps a PKCS#1 `RSAPrivateKey` in a PKCS#8 `PrivateKeyInfo`.
     *
     * The JDK's `KeyFactory` only takes PKCS#8, and the difference between the
     * two is an envelope, not an encoding — the inner key bytes are identical.
     * PKCS#8 (RFC 5208 §5) is:
     *
     * ```text
     * PrivateKeyInfo ::= SEQUENCE {
     *   version              INTEGER (0),
     *   privateKeyAlgorithm  SEQUENCE { OID 1.2.840.113549.1.1.1, NULL },
     *   privateKey           OCTET STRING  -- the PKCS#1 DER, verbatim
     * }
     * ```
     *
     * Hand-written rather than pulled from Bouncy Castle: this is thirty lines
     * of DER against a megabyte of dependency, and a cryptography library added
     * for one envelope is a dependency the licence audit has to carry forever.
     */
    private fun wrapPkcs1AsPkcs8(pkcs1: ByteArray): ByteArray {
        val algorithm = derSequence(derOid(RSA_ENCRYPTION_OID) + DER_NULL)
        val version = byteArrayOf(0x02, 0x01, 0x00)
        return derSequence(version + algorithm + derOctetString(pkcs1))
    }

    private fun derSequence(content: ByteArray): ByteArray =
        byteArrayOf(0x30) + derLength(content.size) + content

    private fun derOctetString(content: ByteArray): ByteArray =
        byteArrayOf(0x04) + derLength(content.size) + content

    private fun derOid(encoded: ByteArray): ByteArray =
        byteArrayOf(0x06) + derLength(encoded.size) + encoded

    /**
     * DER definite-length encoding.
     *
     * Short form below 128, otherwise a leading byte carrying how many length
     * bytes follow, big-endian. An RSA key is a couple of kilobytes, so two
     * length bytes is the realistic maximum, but the loop is general because
     * getting this subtly wrong produces a key that parses on one size of input
     * and not another.
     */
    private fun derLength(length: Int): ByteArray {
        if (length < 0x80) return byteArrayOf(length.toByte())
        var remaining = length
        val bytes = ArrayDeque<Byte>()
        while (remaining > 0) {
            bytes.addFirst((remaining and 0xFF).toByte())
            remaining = remaining ushr 8
        }
        return byteArrayOf((0x80 or bytes.size).toByte()) + bytes.toByteArray()
    }

    private fun decodePem(pem: String, label: String): ByteArray {
        val begin = "-----BEGIN $label-----"
        val end = "-----END $label-----"
        val start = pem.indexOf(begin)
        require(start >= 0) { "PEM does not contain $begin" }
        val stop = pem.indexOf(end, start)
        require(stop >= 0) { "PEM does not contain $end" }
        val body = pem.substring(start + begin.length, stop).replace(Regex("\\s"), "")
        return Base64.getDecoder().decode(body)
    }

    /**
     * Accepts any peer certificate — the equivalent of aasdk's
     * `SSL_VERIFY_NONE`. See the class documentation for why this is the
     * protocol's behaviour rather than a shortcut, and what it costs.
     */
    private object AcceptAllPeers : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}

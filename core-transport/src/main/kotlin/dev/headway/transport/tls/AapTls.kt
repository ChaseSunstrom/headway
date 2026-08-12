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
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
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
     * The head-unit certificate, used by the emulator to play the car's role.
     * Valid until 2045. Source: `aasdk/cert/headunit.crt` and `.key`.
     */
    fun headUnitKeyMaterial(): KeyMaterial = loadResourceKeyMaterial("headunit")

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
            // The peer's certificate cannot be validated (see the class note), so
            // requiring it would only produce a failure we cannot act on.
            if (!clientMode) {
                needClientAuth = false
                wantClientAuth = false
            }
            enabledProtocols = arrayOf(PROTOCOL)
        }
    }

    private fun keyManagers(material: KeyMaterial): Array<javax.net.ssl.KeyManager> {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry(
                "aap",
                material.privateKey,
                KEYSTORE_PASSWORD,
                arrayOf(material.certificate),
            )
        }
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore, KEYSTORE_PASSWORD)
        return factory.keyManagers
    }

    private val KEYSTORE_PASSWORD = CharArray(0)

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
        require(!pem.contains("BEGIN RSA PRIVATE KEY")) {
            "PKCS#1 key given; convert it first: openssl pkcs8 -topk8 -nocrypt -in key.pem -out key.pk8"
        }
        val der = decodePem(pem, "PRIVATE KEY")
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
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

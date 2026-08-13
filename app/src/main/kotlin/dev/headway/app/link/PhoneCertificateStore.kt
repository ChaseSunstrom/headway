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

package dev.headway.app.link

import android.content.Context
import dev.headway.transport.tls.AapTls
import dev.headway.transport.tls.KeyMaterial
import java.io.File

/**
 * The phone certificate Headway presents to the head unit, and the user's
 * replacement for it.
 *
 * ## Why a replacement is needed at all
 *
 * The certificate every open-source Android Auto implementation carries expired
 * on **2022-08-24**. A real 2021 Chevrolet Infotainment 3 unit checks it: the
 * session completes its version handshake and TLS, the unit answers
 * `AUTH_COMPLETE` with `STATUS_AUTHENTICATION_FAILURE`, and the car screen says
 *
 * > The phone and vehicle calendars are set to different dates and times.
 *
 * which is that head unit's way of reporting a certificate validity failure.
 * BLOCKERS.md B-003 has the detail.
 *
 * ## Why Headway cannot fix it on its own
 *
 * The certificate is signed by Google's Automotive Link CA. Issuing a fresh one
 * needs that CA's private key, which no open-source project has, and re-dating
 * the existing one would invalidate its signature. Generating a self-signed
 * replacement only helps if the head unit checks validity dates *without*
 * checking the chain, and the evidence points the other way: this unit returned
 * `STATUS_AUTHENTICATION_FAILURE` rather than `STATUS_CERTIFICATE_ERROR`, which
 * reads as "the chain was fine, the dates were not".
 *
 * So the honest position is that this is a one-time manual step, and Headway's
 * job is to make it *once* rather than every drive. aa-proxy-rs reached the same
 * conclusion and does not bundle a certificate at all — it loads the pair from a
 * path the operator provides (`src/ssl_rustls.rs` L440-L441).
 *
 * ## What the user has to do
 *
 * Put a PEM certificate and its PKCS#8 private key in this store, once. After
 * that every session uses them and nothing else is required.
 */
class PhoneCertificateStore(private val directory: File) {

    private val certFile: File get() = File(directory, CERT_NAME)
    private val keyFile: File get() = File(directory, KEY_NAME)

    /** True when the user has supplied both halves. */
    val hasUserMaterial: Boolean get() = certFile.isFile && keyFile.isFile

    /** Where the files belong, for the UI to display. */
    val certPath: String get() = certFile.absolutePath
    val keyPath: String get() = keyFile.absolutePath

    /**
     * The material to present, preferring the user's.
     *
     * Falls back to the bundled pair on any problem — a malformed import must
     * leave the app exactly as capable as it was before, not unable to start.
     */
    fun keyMaterial(): KeyMaterial = AapTls.phoneKeyMaterial(
        certPem = readOrNull(certFile),
        keyPem = readOrNull(keyFile),
    )

    /**
     * Stores a pair after checking it parses and the two halves match.
     *
     * The match check is the point: a certificate and a key from different
     * sources produce a TLS handshake that fails at the head unit with nothing
     * useful in the log, and the import screen is the last place that mismatch
     * is cheap to catch.
     *
     * @return null on success, or why the pair was rejected.
     */
    fun store(certPem: String, keyPem: String): String? {
        val parsed = runCatching {
            KeyMaterial(AapTls.parseCertificate(certPem), AapTls.parsePkcs8PrivateKey(keyPem))
        }.getOrElse {
            return "could not read that pair: ${it.message}. The certificate must be PEM " +
                "(-----BEGIN CERTIFICATE-----) and the key PKCS#8 " +
                "(-----BEGIN PRIVATE KEY-----); convert an RSA key with " +
                "'openssl pkcs8 -topk8 -nocrypt'"
        }
        if (!keyMatchesCertificate(parsed)) {
            return "that private key does not belong to that certificate"
        }
        return runCatching {
            directory.mkdirs()
            certFile.writeText(certPem)
            keyFile.writeText(keyPem)
            null
        }.getOrElse { "could not save the pair: ${it.message}" }
    }

    /** Forgets the user's pair, returning to the bundled one. */
    fun clear() {
        runCatching { certFile.delete() }
        runCatching { keyFile.delete() }
    }

    /** One line for the settings screen and the session log. */
    fun describe(): String {
        val material = keyMaterial()
        val source = if (hasUserMaterial) "your imported certificate" else "the bundled certificate"
        val problem = AapTls.validityProblem(material)
        return "$source, ${material.certificate.subjectX500Principal.name}, valid until " +
            "${material.certificate.notAfter}" + if (problem == null) "" else " — $problem"
    }

    private fun readOrNull(file: File): String? =
        runCatching { if (file.isFile) file.readText() else null }.getOrNull()

    companion object {
        private const val CERT_NAME = "phone_cert.pem"
        private const val KEY_NAME = "phone_key.pem"

        /** The store backed by the app's private storage. */
        fun inAppStorage(context: Context): PhoneCertificateStore =
            PhoneCertificateStore(context.filesDir)

        /**
         * Whether the private key is the one the certificate was issued for.
         *
         * Compared on the RSA modulus, which is the shared half of the pair.
         * Non-RSA material is accepted without the check rather than rejected:
         * the references are all RSA, so anything else is a case this code has
         * never seen and should not presume to fail.
         */
        private fun keyMatchesCertificate(material: KeyMaterial): Boolean {
            val publicKey = material.certificate.publicKey
            if (publicKey !is java.security.interfaces.RSAPublicKey) return true
            val privateKey = material.privateKey as? java.security.interfaces.RSAPrivateKey
                ?: return true
            return publicKey.modulus == privateKey.modulus
        }
    }
}

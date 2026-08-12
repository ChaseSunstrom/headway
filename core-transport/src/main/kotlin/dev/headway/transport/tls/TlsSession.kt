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

import dev.headway.protocol.io.Cryptor
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLEngineResult.HandshakeStatus

/** Raised when the TLS layer fails in a way the session cannot recover from. */
class TlsException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * An AAP TLS session: a hand-pumped [SSLEngine] exposed as a [Cryptor].
 *
 * AAP does not run TLS on the socket. The handshake travels inside
 * control-channel `ENCAPSULATED_SSL` messages, and afterwards each frame payload
 * is an independent TLS record. So there is no socket to hand to an `SSLSocket`
 * — the engine must be driven by hand, feeding it bytes that arrived as AAP
 * messages and taking back bytes to send as AAP messages.
 *
 * [SSLEngine] exists for exactly this and is a supported public API, which is
 * one of the reasons the protocol core stays on the JVM (ADR 0001) — the C++
 * equivalent is an OpenSSL BIO pair, no simpler and needing cross-compilation.
 *
 * ## Usage
 *
 * The head unit (TLS client) starts:
 *
 * ```kotlin
 * val session = TlsSession(AapTls.headUnitEngine())
 * var outgoing = session.beginHandshake()          // ClientHello
 * while (!session.handshakeComplete) {
 *     send(outgoing)
 *     outgoing = session.continueHandshake(receive())
 * }
 * ```
 *
 * The phone (TLS server) waits, then answers each flight:
 *
 * ```kotlin
 * val session = TlsSession(AapTls.phoneEngine())
 * while (!session.handshakeComplete) {
 *     val outgoing = session.continueHandshake(receive())
 *     if (outgoing.isNotEmpty()) send(outgoing)
 * }
 * ```
 *
 * Not safe for concurrent use; `FramedConnection` serialises access.
 */
class TlsSession(private val engine: SSLEngine) : Cryptor {

    /** Bytes received from the peer that did not yet form a complete TLS record. */
    private var pending: ByteArray = ByteArray(0)

    /**
     * Whether [SSLEngine.beginHandshake] has been called.
     *
     * This matters because `getHandshakeStatus()` reports `NOT_HANDSHAKING` both
     * *before* a handshake starts and *after* one finishes. Without this flag the
     * server side reads `NOT_HANDSHAKING` on its very first flight and concludes
     * the handshake is already done — which is how the two states get conflated.
     */
    private var started: Boolean = false

    var handshakeComplete: Boolean = false
        private set

    /** The negotiated cipher suite, once the handshake is done. Diagnostics only. */
    val cipherSuite: String get() = engine.session.cipherSuite

    /**
     * Starts the handshake and returns the first flight to send.
     *
     * Only the TLS client (the head unit) produces output here; calling it on
     * the server side returns empty, which is correct — the server speaks only
     * once it has heard a ClientHello.
     */
    fun beginHandshake(): ByteArray {
        ensureStarted()
        return pump(ByteArray(0))
    }

    /**
     * Feeds a received handshake flight to the engine and returns the reply to
     * send, which may be empty when the engine needs more input first.
     */
    fun continueHandshake(incoming: ByteArray): ByteArray {
        // The server never calls beginHandshake explicitly -- it just receives a
        // ClientHello -- so start the engine here if nobody has yet.
        ensureStarted()
        return pump(incoming)
    }

    private fun ensureStarted() {
        if (!started) {
            started = true
            engine.beginHandshake()
        }
    }

    /**
     * Drives the engine until it needs more input from the peer or the handshake
     * finishes, accumulating everything it wants to send.
     */
    private fun pump(incoming: ByteArray): ByteArray {
        val outgoing = ByteArrayOutputStream()
        var input = ByteBuffer.wrap(if (pending.isEmpty()) incoming else pending + incoming)
        pending = ByteArray(0)

        try {
            loop@ while (true) {
                when (engine.handshakeStatus) {
                    HandshakeStatus.NEED_UNWRAP -> {
                        if (!input.hasRemaining()) break@loop
                        val app = ByteBuffer.allocate(engine.session.applicationBufferSize)
                        val result = engine.unwrap(input, app)
                        when (result.status) {
                            // A TLS record split across two AAP messages: keep the
                            // remainder and wait for the next one.
                            SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                                stashRemaining(input)
                                break@loop
                            }
                            SSLEngineResult.Status.OK -> Unit
                            SSLEngineResult.Status.CLOSED ->
                                throw TlsException("peer closed the TLS session during the handshake")
                            SSLEngineResult.Status.BUFFER_OVERFLOW ->
                                throw TlsException("TLS application buffer overflow while unwrapping")
                            null -> throw TlsException("null unwrap status")
                        }
                    }

                    HandshakeStatus.NEED_WRAP -> {
                        val net = ByteBuffer.allocate(engine.session.packetBufferSize)
                        val result = engine.wrap(EMPTY, net)
                        if (result.status != SSLEngineResult.Status.OK) {
                            throw TlsException("TLS wrap failed during handshake: ${result.status}")
                        }
                        net.flip()
                        val bytes = ByteArray(net.remaining())
                        net.get(bytes)
                        outgoing.write(bytes)
                    }

                    // Key agreement and signature work the engine wants off the
                    // I/O path. We are already off it, so run them inline.
                    HandshakeStatus.NEED_TASK -> {
                        while (true) {
                            val task = engine.delegatedTask ?: break
                            task.run()
                        }
                    }

                    HandshakeStatus.FINISHED, HandshakeStatus.NOT_HANDSHAKING -> {
                        handshakeComplete = true
                        stashRemaining(input)
                        break@loop
                    }

                    else -> break@loop
                }
            }
        } catch (e: TlsException) {
            throw e
        } catch (e: Exception) {
            throw TlsException("TLS handshake failed: ${e.message}", e)
        }

        return outgoing.toByteArray()
    }

    /** Keeps bytes the engine has not consumed for the next call. */
    private fun stashRemaining(input: ByteBuffer) {
        if (input.hasRemaining()) {
            val rest = ByteArray(input.remaining())
            input.get(rest)
            pending += rest
        }
    }

    override fun encrypt(plaintext: ByteArray): ByteArray {
        check(handshakeComplete) { "cannot encrypt before the TLS handshake completes" }
        val out = ByteArrayOutputStream()
        val src = ByteBuffer.wrap(plaintext)
        // A payload larger than one TLS record produces several records; AAP
        // carries them all in the one frame, so concatenate.
        while (src.hasRemaining()) {
            val net = ByteBuffer.allocate(engine.session.packetBufferSize)
            val result = engine.wrap(src, net)
            if (result.status != SSLEngineResult.Status.OK) {
                throw TlsException("TLS wrap failed: ${result.status}")
            }
            net.flip()
            val bytes = ByteArray(net.remaining())
            net.get(bytes)
            out.write(bytes)
        }
        return out.toByteArray()
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        check(handshakeComplete) { "cannot decrypt before the TLS handshake completes" }
        val out = ByteArrayOutputStream()
        val src = ByteBuffer.wrap(if (pending.isEmpty()) ciphertext else pending + ciphertext)
        pending = ByteArray(0)

        while (src.hasRemaining()) {
            val app = ByteBuffer.allocate(engine.session.applicationBufferSize)
            val result = engine.unwrap(src, app)
            when (result.status) {
                SSLEngineResult.Status.OK -> {
                    app.flip()
                    val bytes = ByteArray(app.remaining())
                    app.get(bytes)
                    out.write(bytes)
                }
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                    stashRemaining(src)
                    break
                }
                SSLEngineResult.Status.CLOSED -> throw TlsException("peer closed the TLS session")
                SSLEngineResult.Status.BUFFER_OVERFLOW ->
                    throw TlsException("TLS application buffer overflow while decrypting")
                null -> throw TlsException("null unwrap status")
            }
        }
        return out.toByteArray()
    }

    private companion object {
        val EMPTY: ByteBuffer = ByteBuffer.allocate(0)
    }
}

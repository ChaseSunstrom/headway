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

import dev.headway.protocol.channel.AvMessageId
import dev.headway.protocol.channel.MediaFrame
import dev.headway.protocol.control.ControlMessageType
import dev.headway.protocol.framing.ChannelId
import dev.headway.protocol.io.FramedConnection
import dev.headway.protocol.session.AapSession
import dev.headway.protocol.session.PhoneIdentity
import dev.headway.transport.LoopbackTransport
import dev.headway.transport.TcpTransport
import dev.headway.transport.tls.AapTls
import dev.headway.transport.tls.TlsSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.EOFException
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * A runnable head unit.
 *
 * Two modes, and the difference matters:
 *
 * - `--self-test` runs both halves in one process over an in-memory pipe. It
 *   proves the protocol stack works. It does **not** prove anything about a
 *   phone, because there is no phone involved — see ADR 0002 on why a harness
 *   that shares code with the thing it tests is a weak oracle.
 * - `--listen` binds a TCP port and waits for a real Headway install to connect
 *   over the network. That is the strongest verification available without a
 *   car: a real phone, real sockets, real TLS, real video off a real encoder.
 *
 * Neither is evidence about a Chevrolet. Only a Chevrolet is.
 */
object Cli {

    private const val USAGE = """
Headway head unit emulator

  --self-test              Run both sides locally and report. No phone needed.
  --listen                 Wait for a real phone to connect, then report what it sends.
  --connect <host>         Act as the PHONE against a --listen emulator elsewhere.
  --port <n>               Port to listen on (default ${TcpTransport.DEFAULT_PORT}).
  --seconds <n>            How long to keep a --listen session running (default 60).
  --help

Typical use:

  # 1. Prove the stack works, on this machine alone:
  ./gradlew :headunit-emulator:run --args="--self-test"

  # 2. Prove it works against your phone:
  ./gradlew :headunit-emulator:run --args="--listen"
  #    then point Headway at the address printed below, over the same Wi-Fi.
"""

    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.toList()
        if (options.isEmpty() || options.contains("--help")) {
            println(USAGE.trimIndent())
            return
        }
        val port = valueOf(options, "--port")?.toIntOrNull() ?: TcpTransport.DEFAULT_PORT
        val seconds = valueOf(options, "--seconds")?.toIntOrNull() ?: 60

        val ok = when {
            options.contains("--self-test") -> selfTest()
            options.contains("--listen") -> listen(port, seconds)
            options.contains("--connect") ->
                connect(valueOf(options, "--connect") ?: "127.0.0.1", port)
            else -> {
                println(USAGE.trimIndent())
                return
            }
        }
        if (!ok) kotlin.system.exitProcess(1)
    }

    private fun valueOf(args: List<String>, name: String): String? {
        val i = args.indexOf(name)
        return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
    }

    // --- self test ----------------------------------------------------------

    /** Runs a phone and a head unit against each other in this process. */
    private fun selfTest(): Boolean = runBlocking {
        header("Self test - both sides in one process")
        println("Proves: framing, TLS, authentication, service discovery, channel open.")
        println("Does not prove: anything about your phone, or about a car.\n")

        LoopbackTransport.pair().use { pair ->
            val headUnit = EmulatedHeadUnit(
                connection = FramedConnection(pair.headUnit),
                tls = TlsSession(AapTls.headUnitEngine()),
                onStep = { step("car ", it) },
            )
            val phone = AapSession(
                connection = FramedConnection(pair.phone),
                tls = TlsSession(AapTls.phoneEngine()),
                identity = PhoneIdentity(deviceName = "self-test"),
                onStep = { step("phone", it) },
            )

            val result = runCatching {
                val phoneSide = async(Dispatchers.IO) { phone.connect() }
                val carSide = async(Dispatchers.IO) { headUnit.run() }
                val profile = withTimeout(60_000) { phoneSide.await() }
                withTimeout(60_000) { carSide.await() }
                profile
            }

            result.onFailure {
                println("\nFAILED: ${it.message}")
                return@runBlocking false
            }
            val profile = result.getOrThrow()

            println()
            header("Result")
            println("  head unit      ${profile.displayName}")
            println("  AAP version    ${profile.version}")
            println("  channels open  ${profile.channelIds.size}")
            for (id in profile.channelIds) println("                 ${ChannelId.describe(id)}")
            println("\nPASS - the protocol stack completed a full session.")
            true
        }
    }

    /**
     * Drives the phone side against a `--listen` emulator over real TCP.
     *
     * Two uses: it verifies the listening path end to end without needing a
     * phone, and it lets you check that a phone *would* be able to reach this
     * machine across your network before blaming the app.
     */
    private fun connect(host: String, port: Int): Boolean = runBlocking {
        header("Connecting to $host:$port as a phone would")
        val transport = runCatching { TcpTransport.connect(host, port) }.getOrElse {
            println("FAILED to connect: ${it.message}")
            return@runBlocking false
        }
        val session = AapSession(
            connection = FramedConnection(transport),
            tls = TlsSession(AapTls.phoneEngine()),
            identity = PhoneIdentity(deviceName = "headway-cli"),
            onStep = { step("phone", it) },
        )
        val result = runCatching { withTimeout(60_000) { session.connect() } }
        transport.close()

        result.onFailure {
            println("\nFAILED: ${it.message}")
            return@runBlocking false
        }
        val profile = result.getOrThrow()
        println("\nPASS - completed a session with '${profile.displayName}' over TCP,")
        println("with ${profile.channelIds.size} channels open.")
        true
    }

    // --- listen for a real phone -------------------------------------------

    private fun listen(port: Int, seconds: Int): Boolean = runBlocking {
        header("Waiting for a phone")
        for (address in localAddresses()) {
            println("  point Headway at   $address:$port")
        }
        println("\nThe phone must be on the same network as this machine.")
        println("Ctrl-C to stop.\n")

        TcpTransport.listen(port = port).use { server ->
            val transport = runCatching { server.accept() }.getOrElse {
                println("FAILED to accept a connection: ${it.message}")
                return@runBlocking false
            }
            println("Phone connected.\n")

            val connection = FramedConnection(transport)
            val headUnit = EmulatedHeadUnit(
                connection = connection,
                tls = TlsSession(AapTls.headUnitEngine()),
                onStep = { step("car ", it) },
            )

            val brought = runCatching { withTimeout(60_000) { headUnit.run() } }
            brought.onFailure {
                println("\nFAILED during bring-up: ${it.message}")
                println("If this is a TLS failure, the likely cause is the expired phone")
                println("certificate — see BLOCKERS.md B-003.")
                return@runBlocking false
            }

            println("\nSession up. Reporting traffic for ${seconds}s.\n")
            val tally = Tally()
            val deadline = System.nanoTime() + seconds * 1_000_000_000L
            while (System.nanoTime() < deadline) {
                val message = try {
                    withTimeout(2_000) { connection.receive() }
                } catch (e: EOFException) {
                    println("\nPhone disconnected.")
                    break
                } catch (e: Exception) {
                    continue
                }
                tally.record(message.channelId, message.messageId, message.payload)
            }

            println()
            header("What the phone sent")
            tally.report()
            val ok = tally.videoFrames > 0
            println(
                if (ok) "\nPASS — a real phone completed the handshake and streamed video."
                else "\nThe session came up but no video arrived. Check that the phone " +
                    "granted screen capture."
            )
            ok
        }
    }

    /** Running totals for the traffic report. */
    private class Tally {
        var videoFrames = 0L
        private var videoBytes = 0L
        private var firstTimestamp = -1L
        private var lastTimestamp = -1L
        private var codecConfigs = 0L
        private val other = LinkedHashMap<String, Long>()

        fun record(channelId: Int, messageId: Int, payload: ByteArray) {
            when {
                messageId == AvMessageId.DATA && channelId == ChannelId.MEDIA_SINK_VIDEO.id -> {
                    videoFrames++
                    videoBytes += payload.size
                    val data = runCatching { MediaFrame.parseData(payload) }.getOrNull() ?: return
                    if (firstTimestamp < 0) firstTimestamp = data.timestampMicros
                    lastTimestamp = data.timestampMicros
                }
                messageId == AvMessageId.CODEC_CONFIG -> codecConfigs++
                else -> {
                    val label = if (channelId == ChannelId.CONTROL.id) {
                        "control ${ControlMessageType.describe(messageId)}"
                    } else {
                        "${ChannelId.describe(channelId)} ${AvMessageId.describe(messageId)}"
                    }
                    other[label] = (other[label] ?: 0) + 1
                }
            }
        }

        fun report() {
            println("  codec configs  $codecConfigs")
            println("  video frames   $videoFrames  (${videoBytes / 1024} KiB)")
            if (videoFrames > 1 && lastTimestamp > firstTimestamp) {
                val span = (lastTimestamp - firstTimestamp) / 1_000_000.0
                println("  stream time    %.1fs  (%.1f fps by presentation timestamp)"
                    .format(span, (videoFrames - 1) / span))
            }
            for ((label, count) in other) println("  %-30s %d".format(label, count))
        }
    }

    // --- output -------------------------------------------------------------

    private fun header(title: String) {
        println(title)
        println("-".repeat(title.length))
    }

    private fun step(who: String, message: String) = println("  [$who] $message")

    /** Addresses a phone on the same network could actually reach. */
    private fun localAddresses(): List<String> =
        runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress }
                .toList()
        }.getOrDefault(emptyList()).ifEmpty { listOf("<this machine's IP>") }
}

fun main(args: Array<String>) = Cli.main(args)

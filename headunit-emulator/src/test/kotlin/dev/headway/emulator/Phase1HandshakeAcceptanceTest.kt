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

import dev.headway.protocol.control.ControlMessageType
import dev.headway.protocol.control.VersionHandshake
import dev.headway.protocol.framing.AapMessage
import dev.headway.protocol.framing.ChannelId
import dev.headway.protocol.io.FramedConnection
import dev.headway.protocol.session.AapSession
import dev.headway.protocol.session.HeadUnitProfile
import dev.headway.protocol.session.PhoneIdentity
import dev.headway.transport.LoopbackTransport
import dev.headway.transport.TcpTransport
import dev.headway.transport.tls.AapTls
import dev.headway.transport.tls.TlsSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **Phase 1 acceptance.**
 *
 * CLAUDE.md:
 *
 * > **Phase 1 — Handshake.** BT RFCOMM exchange (real + fake transport), Wi-Fi
 * > join with bound network, TCP, AAP version + TLS handshake, service
 * > discovery. *Accepted when:* Headway on a real phone completes discovery
 * > against the emulator over actual BT + Wi-Fi, and in CI over fake transport,
 * > 20/20 consecutive attempts.
 *
 * This covers the CI half in full: version handshake, real TLS, authentication,
 * service discovery and channel open, 20/20 consecutive, over both the in-process
 * fake transport and a **real TCP socket**. The real-phone half needs hardware
 * that does not exist in CI (BLOCKERS.md B-001).
 */
class Phase1HandshakeAcceptanceTest {

    /** Runs a complete bring-up over the given transport pair. */
    private fun bringUp(
        pair: LoopbackTransport.Pair,
        config: HeadUnitConfig = HeadUnitConfig(),
    ): Pair<HeadUnitProfile, EmulatedHeadUnit> = runBlocking {
        val headUnit = EmulatedHeadUnit(
            connection = FramedConnection(pair.headUnit),
            tls = TlsSession(AapTls.headUnitEngine()),
            config = config,
        )
        val session = AapSession(
            connection = FramedConnection(pair.phone),
            tls = TlsSession(AapTls.phoneEngine()),
            identity = PhoneIdentity(deviceName = "Headway Test"),
        )

        // Both sides must run concurrently: each blocks reading the other.
        val phoneSide = async(Dispatchers.IO) { session.connect() }
        val headUnitSide = async(Dispatchers.IO) { headUnit.run() }

        val profile = withTimeout(60_000) { phoneSide.await() }
        withTimeout(60_000) { headUnitSide.await() }
        profile to headUnit
    }

    @Test
    fun `a head unit that skips TLS still gets a session`() = runBlocking {
        // Observed on a real 2021 Chevrolet Infotainment 3 unit: it answers the
        // version response with AUTH_COMPLETE and never sends a single
        // ENCAPSULATED_SSL flight. The old code demanded ENCAPSULATED_SSL next
        // and failed with "expected ENCAPSULATED_SSL, got AUTH_COMPLETE",
        // refusing a session the head unit had just declared good.
        //
        // AACS's phone side dispatches on message type rather than order
        // (AaCommunicator.cpp handleMessageContent), which is why it would have
        // survived this and a sequential reader did not.
        LoopbackTransport.pair().use { pair ->
            val car = FramedConnection(pair.headUnit)
            val session = AapSession(
                connection = FramedConnection(pair.phone),
                tls = TlsSession(AapTls.phoneEngine()),
                identity = PhoneIdentity(deviceName = "Headway Test"),
            )
            // runCatching because this test stops at service discovery: the
            // phone is left waiting for a response that never comes, and the
            // EOF from tearing the pair down is teardown noise, not a result.
            val phoneSide = async(Dispatchers.IO) { runCatching { session.connect() } }

            // Version, exactly as the car sends it.
            car.send(VersionHandshake.request(1, 5))
            val versionReply = withTimeout(10_000) { car.receive() }
            assertEquals(ControlMessageType.VERSION_RESPONSE.id, versionReply.messageId)
            // [u16 major][u16 minor][u16 status]
            val answeredMinor = ((versionReply.payload[2].toInt() and 0xFF) shl 8) or
                (versionReply.payload[3].toInt() and 0xFF)
            assertEquals(
                5, answeredMinor,
                "answering 1.6 to a 1.5 head unit claims a version it never offered",
            )

            // Straight to AUTH_COMPLETE: no TLS at all. AuthResponse.status is
            // field 1, varint 0 = success.
            car.send(
                AapMessage(
                    channelId = ChannelId.CONTROL.id,
                    control = false,
                    encrypted = false,
                    messageId = ControlMessageType.AUTH_COMPLETE.id,
                    payload = byteArrayOf(0x08, 0x00),
                )
            )

            // The phone must now proceed, in plaintext, to service discovery.
            val discovery = withTimeout(10_000) { car.receive() }
            assertEquals(
                ControlMessageType.SERVICE_DISCOVERY_REQUEST.id, discovery.messageId,
                "an authenticated session must continue even with no TLS",
            )
            assertTrue(
                !discovery.encrypted,
                "no TLS happened, so nothing may claim to be encrypted",
            )

            phoneSide.cancel()
            Unit
        }
    }

    @Test
    fun `full handshake through to open channels over the fake transport`() {
        LoopbackTransport.pair().use { pair ->
            val (profile, headUnit) = bringUp(pair)

            assertEquals("Headway Emulator", profile.displayName)
            assertEquals("Headway Test", headUnit.phoneDeviceName)

            // The head unit advertises its sinks and sources; the phone opened them.
            assertEquals(
                listOf(
                    ChannelId.MEDIA_SINK_VIDEO.id,
                    ChannelId.MEDIA_SINK_MEDIA_AUDIO.id,
                    ChannelId.MEDIA_SINK_SYSTEM_AUDIO.id,
                    ChannelId.MEDIA_SINK_GUIDANCE_AUDIO.id,
                    ChannelId.INPUT_SOURCE.id,
                    ChannelId.MEDIA_SOURCE_MICROPHONE.id,
                ),
                profile.channelIds,
            )
            assertEquals(profile.channelIds, headUnit.openedChannelIds)
        }
    }

    @Test
    fun `the advertised video configuration is readable by the phone`() {
        LoopbackTransport.pair().use { pair ->
            val (profile, _) = bringUp(pair)
            val video = profile.serviceFor(ChannelId.MEDIA_SINK_VIDEO.id)
            assertNotNull(video, "video service must be advertised")
            val config = video!!.mediaSinkService.getVideoConfigs(0)

            // 800x480 at 30 fps -- the Malibu-class unit the emulator models.
            assertEquals("VIDEO_800x480", config.codecResolution.name)
            assertEquals("VIDEO_FPS_30", config.frameRate.name)
            assertEquals(140, config.density)
        }
    }

    @Test
    fun `the advertised touchscreen and microphone are readable by the phone`() {
        LoopbackTransport.pair().use { pair ->
            val (profile, _) = bringUp(pair)

            val input = profile.serviceFor(ChannelId.INPUT_SOURCE.id)
            assertNotNull(input, "input service must be advertised")
            val touch = input!!.inputSourceService.getTouchscreen(0)
            assertEquals(800, touch.width)
            assertEquals(480, touch.height)
            assertTrue(input.inputSourceService.keycodesSupportedList.contains(84), "voice key")

            val mic = profile.serviceFor(ChannelId.MEDIA_SOURCE_MICROPHONE.id)
            assertNotNull(mic, "microphone service must be advertised")
            val audio = mic!!.mediaSourceService.audioConfig
            assertEquals(16_000, audio.samplingRate)
            assertEquals(16, audio.numberOfBits)
            assertEquals(1, audio.numberOfChannels)
        }
    }

    /**
     * The spec's 20/20 criterion, now over the whole bring-up rather than just
     * the version exchange. Each iteration builds fresh transports and a fresh
     * TLS session, so a leak or a state bug in teardown surfaces here.
     */
    @Test
    fun `twenty consecutive full handshakes all succeed`() {
        repeat(20) { attempt ->
            LoopbackTransport.pair().use { pair ->
                val (profile, headUnit) = bringUp(pair)
                assertEquals(6, profile.channelIds.size, "attempt ${attempt + 1}")
                assertEquals(6, headUnit.openedChannelIds.size, "attempt ${attempt + 1}")
            }
        }
    }

    /**
     * The same bring-up over a **real TCP socket** on the loopback interface —
     * genuine kernel sockets, not an in-process pipe.
     *
     * This is what the phone will actually do once it has joined the car's Wi-Fi:
     * connect to the head unit's TCP endpoint (port 5288 on a real unit) and run
     * the session over it. Exercising it here catches anything the in-process
     * fake papers over, such as partial reads at socket boundaries.
     */
    @Test
    fun `full handshake over a real TCP socket`() = runBlocking {
        TcpTransport.listen(port = 0).use { server ->
            val accepted = async(Dispatchers.IO) { server.accept() }
            val phoneTransport = TcpTransport.connect("127.0.0.1", server.port)
            val headUnitTransport = withTimeout(15_000) { accepted.await() }

            try {
                val headUnit = EmulatedHeadUnit(
                    connection = FramedConnection(headUnitTransport),
                    tls = TlsSession(AapTls.headUnitEngine()),
                )
                val session = AapSession(
                    connection = FramedConnection(phoneTransport),
                    tls = TlsSession(AapTls.phoneEngine()),
                )

                val phoneSide = async(Dispatchers.IO) { session.connect() }
                val headUnitSide = async(Dispatchers.IO) { headUnit.run() }

                val profile = withTimeout(60_000) { phoneSide.await() }
                withTimeout(60_000) { headUnitSide.await() }

                assertEquals(6, profile.channelIds.size)
                assertEquals(6, headUnit.openedChannelIds.size)
            } finally {
                phoneTransport.close()
                headUnitTransport.close()
            }
        }
    }

    @Test
    fun `the phone can decline channels it does not want`() {
        LoopbackTransport.pair().use { pair ->
            runBlocking {
                val headUnit = EmulatedHeadUnit(
                    connection = FramedConnection(pair.headUnit),
                    tls = TlsSession(AapTls.headUnitEngine()),
                )
                val session = AapSession(
                    connection = FramedConnection(pair.phone),
                    tls = TlsSession(AapTls.phoneEngine()),
                )

                // Open video and input only -- a user who has audio over A2DP
                // has no reason to open the audio sinks.
                val wanted = listOf(ChannelId.MEDIA_SINK_VIDEO.id, ChannelId.INPUT_SOURCE.id)
                val phoneSide = async(Dispatchers.IO) { session.connect { wanted } }
                val headUnitSide = async(Dispatchers.IO) { headUnit.run(channelOpens = wanted.size) }

                withTimeout(60_000) { phoneSide.await() }
                withTimeout(60_000) { headUnitSide.await() }

                assertEquals(wanted, session.openedChannelIds)
                assertEquals(wanted, headUnit.openedChannelIds)
            }
        }
    }
}

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

import aap_protobuf.service.control.message.PingRequestOuterClass.PingRequest
import aap_protobuf.service.control.message.PingResponseOuterClass.PingResponse
import aap_protobuf.service.media.shared.message.MediaCodecTypeOuterClass.MediaCodecType
import dev.headway.protocol.channel.InputChannel
import dev.headway.protocol.channel.VideoChannel
import dev.headway.protocol.control.ControlKeepalive
import dev.headway.protocol.control.ControlMessageType
import dev.headway.protocol.framing.AapMessage
import dev.headway.protocol.framing.ChannelId
import dev.headway.protocol.io.ChannelDemultiplexer
import dev.headway.protocol.io.FramedConnection
import dev.headway.protocol.session.AapSession
import dev.headway.protocol.session.HeadUnitProfile
import dev.headway.protocol.session.PhoneIdentity
import dev.headway.transport.LoopbackTransport
import dev.headway.transport.tls.AapTls
import dev.headway.transport.tls.TlsSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [EmulatedSession], the head unit's *running session* loop.
 *
 * ## Why this file exists at all
 *
 * Every other acceptance test composes one emulated sink by hand against a bare
 * connection and drives it. That is sound for what those tests assert, and it is
 * structurally unable to catch the class of bug that has actually shipped from
 * this project twice: a session that comes up correctly and then dies, because
 * something that has to keep happening for the whole session does not.
 *
 * Both real-car failures were of that shape. The missing `PING_RESPONSE` handler
 * was invisible because no emulator ever pinged after bring-up. The missing
 * CONTROL flag was invisible because the emulator accepted a frame a real
 * Chevrolet closed the link over. In each case CI was green and the car hung up.
 *
 * [EmulatedSession] is the loop that closes that gap — and it is therefore also
 * the thing that must not be wrong, because from here on it is the oracle. An
 * untested harness produces false confidence more efficiently than no harness at
 * all. So these tests are about the harness, and they check the properties a
 * per-channel test cannot express:
 *
 * 1. **The session keeps living.** Keepalives flow both ways for its whole
 *    length, and a phone that stops answering is dropped the way openauto drops
 *    it — not sooner, which would fail a phone a real car accepts, and not
 *    later, which would pass one it hangs up on.
 * 2. **Channels coexist.** Video streams while input is bound and keepalives are
 *    answered, all over one multiplexed connection, with one reader per channel.
 * 3. **Flow control converges.** Every media message is acknowledged and nothing
 *    is left outstanding, so neither side can deadlock waiting for the other.
 *
 * The phone side of the third test is built the way `HeadwayService.runChannels`
 * builds it — a [ChannelDemultiplexer] with every view subscribed before
 * `pump()` starts, one control reader answering keepalives — because that
 * arrangement is the part of the Android wiring that is not Android-specific,
 * and it is the part that can be got wrong silently.
 *
 * Per ADR 0002 this shares `core-protocol` with the phone, so it remains a weak
 * oracle for the wire format. It is not a weak oracle for liveness: a
 * symmetrically-wrong constant still has to keep a session alive to pass.
 *
 * ## Why the reader coroutines are never joined
 *
 * [session] runs the loops in a scope it cancels but does not await, which is
 * unusual enough to be worth stating. `StreamTransport.readFully` dispatches a
 * *blocking* stream read to `Dispatchers.IO`, so a coroutine parked inside one
 * does not observe cancellation until bytes arrive or the stream closes. A test
 * whose last phone-side act is to fall silent therefore leaves the head unit's
 * reader parked forever, and a structured-concurrency scope that waits for its
 * children would hang rather than fail.
 *
 * Closing the transport is what actually releases those threads, and
 * `LoopbackTransport.Pair.use` does it as the test returns. This is also why
 * [EmulatedSession.stop] is documented as leaving the connection alone: the
 * owner of the transport closes it, and that close is the real wake-up.
 */
class EmulatedSessionTest {

    @Test
    fun `the phone answers keepalives for the whole session, not just bring-up`() = session {
        val loop = sessionLoop(EmulatedSessionConfig(pingPeriodMillis = PING_PERIOD_MILLIS))
        background.launch { answerKeepalivesOnly(phoneConnection) }
        loop.start(background)

        // Several ping periods, so a phone that answers once by accident cannot
        // pass.
        waitUntil { loop.pongsReceived >= 3 }

        assertTrue(
            loop.pongsReceived >= 3,
            "the phone answered ${loop.pongsReceived} keepalive(s); a head unit pings for the " +
                "life of the session and needs an answer to each",
        )
        assertFalse(
            loop.missedKeepalives,
            "the session was dropped for missed keepalives: ${loop.describe()}",
        )
    }

    @Test
    fun `a phone that stops answering is dropped, on openauto's schedule`() = session {
        val loop = sessionLoop(
            EmulatedSessionConfig(
                pingPeriodMillis = PING_PERIOD_MILLIS,
                // Leaves the transport up, so the assertions run against a
                // session that has decided the phone is gone rather than
                // against a closed socket.
                dropSessionOnMissedKeepalives = false,
            )
        )
        // No phone-side reader at all: nothing reads, so nothing answers.
        loop.start(background)

        waitUntil { loop.missedKeepalives }

        assertTrue(
            loop.missedKeepalives,
            "a head unit ends a session whose keepalives go unanswered; this one did not: " +
                loop.describe(),
        )
        assertEquals(
            0L,
            loop.pongsReceived,
            "nothing was reading the phone side, so no answer could have arrived",
        )
        // openauto forgives one outstanding ping and dies on the second, so the
        // drop cannot happen on the first period.
        assertTrue(
            loop.pingsScheduled >= 2,
            "dropped after ${loop.pingsScheduled} ping(s); openauto tolerates one unanswered " +
                "keepalive before hanging up (Pinger.cpp L73)",
        )
    }

    @Test
    fun `video streams while input is bound and keepalives are answered`() = session {
        val loop = sessionLoop(EmulatedSessionConfig(pingPeriodMillis = PING_PERIOD_MILLIS))

        // Built the way runChannels builds it: every view subscribed before the
        // pump starts, which is the demultiplexer's contract and the one
        // ordering mistake that loses the first message on every channel.
        val demux = ChannelDemultiplexer(phoneConnection)
        val control = demux.channel(ChannelId.CONTROL.id)
        // By codec, not by channel id: the advertisement is the authority on
        // which channel is which, the same reason CarVideoStream.videoServiceOf
        // matches this way.
        val videoService = profile.services.first {
            it.hasMediaSinkService() &&
                it.mediaSinkService.availableType == MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP
        }
        val inputService = profile.services.first { it.hasInputSourceService() }
        val video = VideoChannel(demux.channel(videoService.id), videoService.id)
        val input = InputChannel(demux.channel(inputService.id), inputService.id)

        background.launch { demux.pump() }
        background.launch {
            while (true) {
                val message = control.receive()
                if (ControlKeepalive.isPing(message)) {
                    ControlKeepalive.answer(control, message, phoneConnection.cryptor != null)
                }
            }
        }
        loop.start(background)

        // Input first: binding is a request/response, so it proves the input
        // channel round-trips before video saturates the link.
        input.requestKeyBinding(EmulatedHeadUnit.SUPPORTED_KEYCODES)

        video.sendSetup()
        val config = video.awaitConfig()
        assertTrue(config.ready, "the emulated head unit refused video: ${config.status}")
        video.sendStart(
            sessionId = SESSION_ID,
            configurationIndex = config.configurationIndices.first(),
        )
        video.sendCodecConfig(CODEC_CONFIG)
        repeat(FRAMES) { i -> video.sendFrame(accessUnit(i), i * FRAME_INTERVAL_MICROS) }
        video.awaitAllAcks()

        val sink = requireNotNull(loop.video) { "the emulated head unit advertised no video sink" }
        assertEquals(
            FRAMES + 1,
            sink.frameCount,
            "every frame plus the codec config must reach the sink; " + loop.describe(),
        )
        assertEquals(
            0,
            video.unacknowledgedMessages,
            "flow control did not converge: the phone is still waiting on an ack",
        )
        waitUntil { loop.boundKeycodes != null }
        assertEquals(
            EmulatedHeadUnit.SUPPORTED_KEYCODES,
            loop.boundKeycodes,
            "the input channel did not round-trip while video was streaming",
        )
        // Waited for rather than asserted outright: ten frames over loopback
        // finish in a few milliseconds, so without this the assertion runs
        // before the head unit's first ping has even left. What is being checked
        // is that keepalives still work *after* a stream has run, not that one
        // happened to fall inside it.
        waitUntil { loop.pongsReceived >= 1 }
        assertTrue(
            loop.pongsReceived >= 1,
            "keepalives stopped being answered once video started: ${loop.describe()}",
        )
        assertEquals(
            0L,
            loop.unroutableMessages,
            "the session loop could not route something the phone sent: ${loop.describe()}",
        )
    }

    @Test
    fun `a keepalive the phone originates is answered by the head unit`() = session {
        // Both directions matter. Headway pings a car that has gone quiet, and a
        // head unit that never answered would make the phone give up on a live
        // session -- the same bug as the original, with the roles swapped.
        val loop = sessionLoop(
            // No head-unit pings, so the only keepalive traffic is the phone's
            // and the assertion cannot be satisfied by an unrelated echo.
            EmulatedSessionConfig(pingPeriodMillis = NO_PINGS_MILLIS)
        )
        loop.start(background)

        // Built here rather than through ControlKeepalive, which only answers
        // pings: Headway is the phone, and a phone originating one is a
        // reconnection-watchdog concern that has no helper yet.
        phoneConnection.send(
            AapMessage(
                channelId = ChannelId.CONTROL.id,
                control = false,
                encrypted = phoneConnection.cryptor != null,
                messageId = ControlMessageType.PING_REQUEST.id,
                payload = PingRequest.newBuilder().setTimestamp(TIMESTAMP).build().toByteArray(),
            )
        )
        val answer = withTimeout(TIMEOUT_MILLIS) { phoneConnection.receive() }

        assertEquals(
            ControlMessageType.PING_RESPONSE.id,
            answer.messageId,
            "the head unit answered a ping with 0x%04x".format(answer.messageId),
        )
        assertEquals(
            TIMESTAMP,
            PingResponse.parseFrom(answer.payload).timestamp,
            "the timestamp must be echoed back unchanged",
        )
        // The counter is incremented after the answer is sent, so receiving the
        // answer does not prove it has been incremented yet.
        waitUntil { loop.phonePingsAnswered == 1L }
        assertEquals(1L, loop.phonePingsAnswered)
    }

    // --- harness -------------------------------------------------------------

    /**
     * A brought-up session: real version handshake, real TLS, real service
     * discovery over the fake transport, with both connections and a scope for
     * the loops.
     */
    private class Fixture(
        val phoneConnection: FramedConnection,
        val headUnitConnection: FramedConnection,
        val headUnit: EmulatedHeadUnit,
        val profile: HeadUnitProfile,
        val background: CoroutineScope,
    ) {
        /** The loop under test, serving exactly what the head unit advertised. */
        fun sessionLoop(config: EmulatedSessionConfig): EmulatedSession =
            EmulatedSession.of(
                headUnit = headUnit,
                connection = headUnitConnection,
                config = config,
            )
    }

    /**
     * Brings a session up and runs [body] against it.
     *
     * Going through the whole bring-up rather than starting from a bare
     * connection is what makes every channel id come from the advertisement
     * instead of a constant — the one thing about these channels that every
     * reference agrees is not fixed by the protocol.
     */
    private fun session(body: suspend Fixture.() -> Unit) {
        LoopbackTransport.pair().use { pair ->
            val phoneConnection = FramedConnection(pair.phone)
            val headUnitConnection = FramedConnection(pair.headUnit)
            val headUnit = EmulatedHeadUnit(
                connection = headUnitConnection,
                tls = TlsSession(AapTls.headUnitEngine()),
                config = HeadUnitConfig(),
            )
            val session = AapSession(
                connection = phoneConnection,
                tls = TlsSession(AapTls.phoneEngine()),
                identity = PhoneIdentity(deviceName = "Headway Test"),
            )
            // Cancelled, never joined; see the class KDoc.
            val background = CoroutineScope(Dispatchers.IO + SupervisorJob())
            try {
                runBlocking {
                    // Both sides must run concurrently: each blocks reading the
                    // other.
                    val phoneSide = background.async { session.connect() }
                    val headUnitSide = background.async { headUnit.run() }
                    val profile = withTimeout(TIMEOUT_MILLIS) { phoneSide.await() }
                    withTimeout(TIMEOUT_MILLIS) { headUnitSide.await() }
                    Fixture(
                        phoneConnection = phoneConnection,
                        headUnitConnection = headUnitConnection,
                        headUnit = headUnit,
                        profile = profile,
                        background = background,
                    ).body()
                }
            } finally {
                background.cancel()
            }
        }
    }

    /**
     * The weakest phone that should survive: reads everything, answers
     * keepalives, discards the rest.
     *
     * Deliberately *not* a demultiplexer. The keepalive tests assert that a
     * phone which merely keeps reading stays alive; building the full router
     * here would test the router instead.
     */
    private suspend fun answerKeepalivesOnly(connection: FramedConnection) {
        runCatching {
            while (true) {
                val message = connection.receive()
                if (ControlKeepalive.isPing(message)) {
                    ControlKeepalive.answer(connection, message, connection.cryptor != null)
                }
            }
        }
    }

    /**
     * Polls [condition] until it holds or the budget runs out.
     *
     * Polling rather than a fixed `delay` because these tests turn on *how many*
     * keepalive periods have passed, and a sleep long enough to be reliable on a
     * loaded CI machine would make every one of them slow. The assertion after
     * the wait is what fails; this only decides when to give up.
     */
    private suspend fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TIMEOUT_MILLIS * 1_000_000
        while (!condition() && System.nanoTime() < deadline) delay(POLL_MILLIS)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 60_000L
        const val POLL_MILLIS = 10L

        /**
         * Keepalive period for the tests.
         *
         * openauto's is 5 s, which is the emulator's default and the right
         * default; a test that waited three of those would take fifteen seconds
         * to assert something that is not about wall-clock time at all. The
         * ratio the tests depend on — one unanswered ping forgiven, the second
         * fatal — is a property of the loop, not of the period.
         */
        const val PING_PERIOD_MILLIS = 50L

        /** Long enough that no head-unit ping fires during a test. */
        const val NO_PINGS_MILLIS = 600_000L

        const val SESSION_ID = 1
        const val FRAMES = 10
        const val FRAME_INTERVAL_MICROS = 33_333L
        const val TIMESTAMP = 4242L

        /** A minimal Annex-B SPS/PPS pair; the sink parses NAL units out of it. */
        val CODEC_CONFIG = byteArrayOf(
            0, 0, 0, 1, 0x67, 0x42, 0x00, 0x0A,
            0, 0, 0, 1, 0x68, 0xCE.toByte(), 0x38, 0x80.toByte(),
        )

        /** An IDR access unit, distinguishable per index so ordering is checkable. */
        fun accessUnit(index: Int): ByteArray =
            byteArrayOf(0, 0, 0, 1, 0x65, index.toByte(), 0x11, 0x22)
    }
}

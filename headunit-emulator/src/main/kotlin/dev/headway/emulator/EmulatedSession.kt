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

import aap_protobuf.service.ServiceOuterClass
import aap_protobuf.service.control.message.ChannelOpenRequestOuterClass.ChannelOpenRequest
import aap_protobuf.service.control.message.ChannelOpenResponseOuterClass.ChannelOpenResponse
import aap_protobuf.service.control.message.PingRequestOuterClass.PingRequest
import aap_protobuf.service.control.message.PingResponseOuterClass.PingResponse
import aap_protobuf.service.media.shared.message.MediaCodecTypeOuterClass.MediaCodecType
import aap_protobuf.shared.MessageStatusOuterClass.MessageStatus
import dev.headway.protocol.channel.AvMessageId
import dev.headway.protocol.channel.InputChannel
import dev.headway.protocol.channel.InputChannelMessage
import dev.headway.protocol.channel.InputMessageId
import dev.headway.protocol.control.ControlKeepalive
import dev.headway.protocol.control.ControlMessageType
import dev.headway.protocol.framing.AapMessage
import dev.headway.protocol.framing.ChannelId
import dev.headway.protocol.io.FramedConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.EOFException

/**
 * Timings and policies the emulated unit applies for the life of a session.
 *
 * Nothing here is a protocol constant — a head unit is free to keepalive at any
 * rate it likes — so every value is a knob, with openauto's number as the
 * default per CLAUDE.md's rule that unknown head-unit behaviour is implemented
 * the aasdk-documented way, made configurable, and logged loudly.
 */
data class EmulatedSessionConfig(
    /**
     * Milliseconds between keepalives.
     *
     * openauto builds its pinger with `Pinger(ioService_, 5000)`
     * (`openauto/openauto/Service/AndroidAutoEntityFactory.cpp` L69).
     */
    val pingPeriodMillis: Long = 5_000,
    /**
     * Whether to tear the link down when the phone stops answering.
     *
     * True reproduces a head unit. False keeps the session up so a test can
     * observe a phone that misses keepalives without the transport disappearing
     * underneath its assertions — [missedKeepalives] is set either way.
     */
    val dropSessionOnMissedKeepalives: Boolean = true,
    val video: VideoSinkConfig = VideoSinkConfig(),
    val audio: AudioSinkConfig = AudioSinkConfig(),
)

/**
 * A head unit that keeps running after bring-up: it demultiplexes the phone's
 * traffic to the emulated sinks, answers the phone's keepalives, and sends its
 * own on a timer.
 *
 * ## The gap this fills
 *
 * [EmulatedHeadUnit.run] completes bring-up — version, TLS, authentication,
 * discovery, channel opens — and returns. Until now nothing came after it, so
 * the standalone TCP emulator accepted a connection, finished the handshake and
 * then sat mute: no acknowledgements, so the phone's first video frame never
 * cleared the flow-control window, and no keepalives, so nothing would have
 * noticed. That is the same shape as the failure a real 2021 Chevrolet
 * Infotainment 3 unit produced, and the emulator could not reproduce it because
 * the emulator had no running session at all. Each acceptance test instead
 * composed a sink by hand against a bare connection, which works precisely
 * because those tests drive exactly one channel.
 *
 * This class is that missing loop. [EmulatedHeadUnit.run] is untouched — the
 * acceptance tests depend on its behaviour — and this runs after it, over the
 * same [FramedConnection].
 *
 * ## Why [dev.headway.protocol.io.ChannelDemultiplexer] is not reused
 *
 * It is the right idea and the wrong shape for this side, for two reasons that
 * are properties of the emulated sinks rather than of the demultiplexer:
 *
 * 1. **The sinks take a `FramedConnection`, not a `MessageChannel`.**
 *    `ChannelDemultiplexer.channel(id)` hands out a `MessageChannel` view, and
 *    [EmulatedVideoSink], [EmulatedAudioSink], [EmulatedInputSource] and
 *    [EmulatedMicrophone] all declare the concrete type. They cannot be handed a
 *    view without changing their constructors, and those constructors are what
 *    every acceptance test is written against.
 * 2. **Nothing here wants a queue.** The demultiplexer exists so several
 *    coroutines can each `receive()` on their own channel; the two sinks that
 *    matter here expose a synchronous `handle(message)` instead, so a queue per
 *    channel would add a hop with no consumer on the far end — and its
 *    drop-oldest overflow policy, which is right for a phone discarding stale
 *    video, would silently drop the head unit's own inbound acknowledgements.
 *
 * So this reads the wire itself, in one coroutine, and calls `handle` directly.
 * The rule the demultiplexer exists to enforce still holds and is the reason
 * this class exists: **exactly one coroutine may call
 * [FramedConnection.receive]**, or two channels steal each other's messages.
 *
 * ## What runs, and what does not
 *
 * - **Video and audio sinks** are driven fully: setup, start, every media
 *   message acknowledged, video focus, and the control-channel audio focus
 *   exchange (which [EmulatedAudioSink] owns — see its KDoc for why).
 * - **The input source** is driven for the phone-originated half of the channel:
 *   this answers `KeyBindingRequest`, then [input] is free for a script to
 *   produce touches and keys. [EmulatedInputSource.answerKeyBinding] is *not*
 *   used, because it reads the connection itself and would race this loop.
 * - **The microphone is not driven.** [EmulatedMicrophone.run] owns a read loop
 *   over its own `FramedConnection` and has no `handle` entry point, so it
 *   cannot be fed by a router without either editing it or bridging bytes
 *   through a second transport. Traffic on the microphone channel is therefore
 *   counted and narrated, not answered; a phone that opens a voice session
 *   against this loop waits for a `Config` that will not come. Phase 5's test
 *   composes [EmulatedMicrophone] directly, which is sound because that test
 *   drives one channel. Closing this properly means giving the microphone a
 *   `handle`, which is a change to a file the acceptance suite pins.
 *
 * ## Keepalives, and why they are not optional
 *
 * openauto pings every 5 s for the life of the session and quits when the
 * answers stop (`openauto/openauto/Service/Pinger.cpp`,
 * `AndroidAutoEntityFactory.cpp` L69, `AndroidAutoEntity.cpp` L267-L297). The
 * emulator did not, so Headway's missing `PING_RESPONSE` handler was invisible
 * to every acceptance test — a session that came up green in CI would have died
 * seconds into a real drive. That bug is fixed; this loop is what stops it
 * coming back, so the behaviour is reproduced including its exact tolerance.
 *
 * ## What a green run here does and does not prove
 *
 * Per ADR 0002 this shares `core-protocol` with the phone, so it is a **weak
 * oracle for the wire format**: a wrong-but-symmetric constant round-trips
 * cleanly here and still fails in a car. The byte fixtures in `core-protocol`'s
 * tests are what pin the bytes.
 *
 * It is *not* a weak oracle for the properties this class is about, because a
 * symmetric framing bug still has to deliver them:
 *
 * - that the phone answers keepalives for the whole session, not just during
 *   bring-up, and does so within the window a head unit allows;
 * - that the phone's channels coexist — video streaming while audio focus is
 *   negotiated and input reports flow — over one multiplexed connection;
 * - that flow control converges: every media message acknowledged, nothing left
 *   outstanding, no deadlock between a sink that will not read and a phone that
 *   will not send.
 *
 * Those are exactly the failures that only appear in a *running* session and
 * that a per-channel harness cannot see, which is the weak-oracle gap ADR 0002
 * warns about narrowed by one notch. It stays open on everything that needs a
 * vehicle: whether a Chevrolet ducks its radio, honours a focus grant, or agrees
 * with any of this at all (BLOCKERS.md B-001).
 */
class EmulatedSession(
    private val connection: FramedConnection,
    /** The video sink, or null when this unit advertised no video service. */
    val video: EmulatedVideoSink? = null,
    /** One sink per advertised audio stream, in advertisement order. */
    val audioSinks: List<EmulatedAudioSink> = emptyList(),
    /**
     * The touch panel and hard keys, for a script to drive. Null when this unit
     * advertised no input service.
     */
    val input: EmulatedInputSource? = null,
    /**
     * The advertised id of the input channel.
     *
     * Passed separately because [EmulatedInputSource] keeps its channel id
     * private, and routing needs it. Kept in step by [of], which takes both from
     * the same advertised `Service`.
     */
    private val inputChannelId: Int? = null,
    /**
     * Keycodes this unit will bind, i.e. what it advertised in
     * `InputSourceService.keycodes_supported`.
     */
    private val inputKeycodes: List<Int> = EmulatedHeadUnit.SUPPORTED_KEYCODES,
    /** The advertised microphone channel, so its traffic is named rather than "unknown". */
    private val microphoneChannelId: Int? = null,
    private val config: EmulatedSessionConfig = EmulatedSessionConfig(),
    private val onStep: (String) -> Unit = {},
) {

    private val jobs: MutableList<Job> = mutableListOf()

    /** Channel ids this loop will service. Anything else is counted as unroutable. */
    private val servicedChannelIds: Set<Int> = buildSet {
        video?.let { add(it.channelId) }
        audioSinks.forEach { add(it.channelId) }
        inputChannelId?.let { add(it) }
        microphoneChannelId?.let { add(it) }
    }

    /** Messages read off the wire and routed. Diagnostics. */
    @Volatile
    var messagesRouted: Long = 0L
        private set

    /** Messages for a channel this session does not service. Diagnostics. */
    @Volatile
    var unroutableMessages: Long = 0L
        private set

    /**
     * Keepalives sent — counted the way openauto counts them, at *schedule*
     * time. See [keepalive]; the difference matters for [missedKeepalives].
     */
    @Volatile
    var pingsScheduled: Long = 0L
        private set

    /** Answers the phone sent. */
    @Volatile
    var pongsReceived: Long = 0L
        private set

    /** Keepalives the phone originated and this unit answered. */
    @Volatile
    var phonePingsAnswered: Long = 0L
        private set

    /**
     * Round trip of the most recent keepalive, in microseconds, or -1 before the
     * first answer.
     *
     * Measured against this machine's clock at both ends, so it is a real
     * round trip over a real socket in `--listen` mode and a measure of the JVM
     * scheduler over a loopback pipe. Worth reading in the first case only.
     */
    @Volatile
    var lastRoundTripMicros: Long = -1L
        private set

    /** The timestamp on this loop's most recent ping; see [onPong]. */
    @Volatile
    private var lastPingSentMicros: Long = -1L

    /** True once the phone missed enough keepalives that a head unit would hang up. */
    @Volatile
    var missedKeepalives: Boolean = false
        private set

    /** Why the session stopped, or null while it is running. */
    @Volatile
    var endedReason: String? = null
        private set

    /** Keycodes the phone asked to have bound, once it has asked. */
    var boundKeycodes: List<Int>? = null
        private set

    /** True once the phone has asked for the microphone this loop cannot answer. */
    @Volatile
    var microphoneTrafficSeen: Boolean = false
        private set

    /**
     * Whether frames may claim encryption.
     *
     * Read from the link rather than remembered, because a real 2021 Chevrolet
     * Infotainment 3 unit authenticates with no TLS handshake at all and every
     * frame after that is plaintext — [FramedConnection.send] rejects a frame
     * marked encrypted when there is no session, rather than putting a lie on
     * the wire.
     */
    private val secured: Boolean get() = connection.cryptor != null

    /**
     * Starts the reader and the keepalive timer, and returns.
     *
     * Call after [EmulatedHeadUnit.run] has returned: bring-up owns the
     * connection until then, and two readers would steal each other's messages.
     * Both coroutines run until [stop], until the phone disconnects, or until
     * the keepalives go unanswered.
     */
    suspend fun start(scope: CoroutineScope) {
        check(jobs.isEmpty()) { "this session is already running" }
        jobs += scope.launch { readLoop() }
        jobs += scope.launch { keepalive() }
        onStep(
            "session loop running; servicing " +
                servicedChannelIds.joinToString { ChannelId.describe(it) }
        )
    }

    /** What happened on this session, for the log. */
    fun describe(): String {
        val parts = mutableListOf(
            "$messagesRouted message(s) routed",
            // Worded rather than divided, because pingsScheduled counts at arm
            // time (openauto's quirk, reproduced deliberately in [keepalive]),
            // so a perfectly healthy session is always one behind. "4/5
            // answered" reads as a fault in an exported log; this does not.
            "$pongsReceived keepalive(s) answered of $pingsScheduled scheduled",
        )
        video?.let { parts += "video ${it.frameCount} message(s), ${it.acksSent} ack(s)" }
        for (sink in audioSinks) {
            parts += "${sink.stream.name} ${sink.bufferCount} buffer(s)"
        }
        input?.let { parts += "input ${it.reportsSent} report(s) sent" }
        if (unroutableMessages > 0) parts += "$unroutableMessages unroutable"
        if (missedKeepalives) parts += "phone stopped answering keepalives"
        endedReason?.let { parts += "ended: $it" }
        return parts.joinToString(", ")
    }

    /**
     * Stops both coroutines and leaves the connection open.
     *
     * The transport belongs to whoever opened it — the CLI closes its socket,
     * a test closes its `LoopbackTransport` pair — and closing it here would
     * turn an orderly stop into an I/O error on the other side, which is not
     * what a head unit ending a session does.
     */
    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    // --- the reader ----------------------------------------------------------

    private suspend fun readLoop() {
        try {
            while (true) {
                route(connection.receive())
            }
        } catch (e: EOFException) {
            // The phone hung up. An ordinary end of session, not a failure:
            // walking away from the car is a supported thing to do.
            endedReason = "the phone disconnected"
            onStep("phone disconnected after $messagesRouted message(s)")
            // The timer has nothing left to ping, and leaving it running gets
            // one of two wrong answers: it writes to a closed transport and
            // throws out of its own coroutine, or it decides a phone that said
            // goodbye stopped answering and overwrites endedReason with a
            // keepalive fault -- in a log someone reads to diagnose a car.
            // Self-cancelling from here is safe; this coroutine is returning
            // anyway, which is what onMissedKeepalives already relies on.
            stop()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            endedReason = "${e::class.simpleName}: ${e.message}"
            onStep("session failed: ${e.message}")
            throw e
        }
    }

    private suspend fun route(message: AapMessage) {
        messagesRouted++

        if (message.channelId == ChannelId.CONTROL.id) {
            routeControl(message)
            return
        }

        // A channel open is the one control message that travels on a *service*
        // channel, and the CONTROL flag is what identifies it there. Bring-up
        // normally consumes all of these, so one arriving here means the phone
        // opened a channel late -- answered rather than ignored, because an
        // unanswered open leaves the phone waiting forever.
        if (message.messageId == ControlMessageType.CHANNEL_OPEN_REQUEST.id) {
            answerChannelOpen(message)
            return
        }

        val audio = audioSinks.firstOrNull { it.channelId == message.channelId }
        when {
            video != null && message.channelId == video.channelId -> video.handle(message)
            audio != null -> audio.handle(message)
            inputChannelId != null && message.channelId == inputChannelId -> routeInput(message)
            microphoneChannelId != null && message.channelId == microphoneChannelId ->
                narrateMicrophone(message)

            else -> {
                unroutableMessages++
                onStep(
                    "no sink for ${AvMessageId.describe(message.messageId)} on " +
                        ChannelId.describe(message.channelId)
                )
            }
        }
    }

    // --- control channel ------------------------------------------------------

    private suspend fun routeControl(message: AapMessage) {
        when (message.messageId) {
            ControlMessageType.PING_RESPONSE.id -> onPong(message)

            // A phone may keepalive too, and openauto's head unit answers by
            // echoing the timestamp (`AndroidAutoEntity.cpp` L233-L245).
            // ControlKeepalive is written from the phone's side and named for
            // it, but a PING_RESPONSE is the same message whoever sends it, and
            // its encryption rule -- encrypted once TLS is up, plain before --
            // is the rule this side needs as well.
            ControlMessageType.PING_REQUEST.id -> {
                ControlKeepalive.answer(connection, message, secured)
                phonePingsAnswered++
            }

            // Audio focus belongs to the head unit's control handler, and
            // EmulatedAudioSink is where that logic lives -- see its KDoc, which
            // asks for exactly this move once a router exists. Routed to the
            // first sink only: the arbitration is per-unit, not per-stream, and
            // answering once per sink would send the phone three notifications
            // for one request.
            ControlMessageType.AUDIO_FOCUS_REQUEST.id -> {
                val arbiter = audioSinks.firstOrNull()
                if (arbiter == null) {
                    onStep("audio focus request arrived but this unit advertised no audio sink")
                } else {
                    arbiter.handle(message)
                }
            }

            ControlMessageType.BYEBYE_REQUEST.id -> {
                // No reference implements either half of this exchange -- there
                // is no ByeBye handling anywhere in aasdk or openauto -- so no
                // response is invented here. The phone said it is leaving; the
                // link closing is what will actually end the session.
                endedReason = "the phone sent BYEBYE_REQUEST"
                onStep("phone sent BYEBYE_REQUEST; no reference implements a reply, so none is sent")
            }

            else -> onStep(
                "unhandled control ${ControlMessageType.describe(message.messageId)}"
            )
        }
    }

    private fun onPong(message: AapMessage) {
        pongsReceived++
        val sent = runCatching { PingResponse.parseFrom(message.payload).timestamp }.getOrNull()
        // Only a pong answering *this* loop's own ping gives a round trip.
        // Bring-up sends pings stamped with a fixed sentinel and does not wait
        // for them (`EmulatedHeadUnit.pingUnanswered`), so a late one can land
        // here; measuring against it would report a round trip of decades.
        if (sent != null && sent == lastPingSentMicros) {
            lastRoundTripMicros = EmulatedInputSource.epochMicros() - sent
        }
    }

    /**
     * Answers a channel open that arrives after bring-up.
     *
     * The rules are [EmulatedHeadUnit]'s, and deliberately identical: refuse a
     * service that was never advertised, and reject a request that reached a
     * service channel without the CONTROL flag. That second check is not
     * pedantry — a real 2021 Chevrolet Infotainment 3 unit closed the session
     * ~30 ms after receiving such a frame, eleven times out of eleven, while
     * this emulator accepted it happily for the whole of the project's life. The
     * rule is aa-proxy-rs's, from observed traffic
     * (`aa-proxy-rs/src/bt_real_hu_passthrough.rs` L131):
     * `let control_flag = if channel == 0 { 0 } else { CONTROL_FLAG };`
     */
    private suspend fun answerChannelOpen(message: AapMessage) {
        if (!message.control) {
            throw IllegalStateException(
                "channel open for service ${message.channelId} arrived without the CONTROL " +
                    "flag; a real head unit closes the session rather than answering this"
            )
        }
        val request = ChannelOpenRequest.parseFrom(message.payload)
        val known = request.serviceId in servicedChannelIds
        val status =
            if (known) MessageStatus.STATUS_SUCCESS else MessageStatus.STATUS_INVALID_SERVICE

        connection.send(
            AapMessage(
                channelId = message.channelId,
                control = message.channelId != ChannelId.CONTROL.id,
                // From the link rather than a constant true: an unencrypted
                // session is a real configuration, not a mistake. See [secured].
                encrypted = secured,
                messageId = ControlMessageType.CHANNEL_OPEN_RESPONSE.id,
                payload = ChannelOpenResponse.newBuilder().setStatus(status).build().toByteArray(),
            )
        )
        onStep("late channel open ${ChannelId.describe(request.serviceId)} -> $status")
    }

    // --- input channel --------------------------------------------------------

    /**
     * Services the phone-originated half of the input channel.
     *
     * `KeyBindingRequest` is the only message the phone sends here; everything
     * else on this channel flows head unit → phone and is produced by [input].
     *
     * The binding policy is openauto's: walk the requested list and reject the
     * *whole* request on the first keycode this unit never advertised, with
     * `STATUS_KEYCODE_NOT_BOUND`
     * (`openauto/src/autoapp/Service/InputSource/InputSourceService.cpp`
     * L102-L132). There is no partial bind, and a phone that assumed there was
     * would silently lose keys against a real unit.
     *
     * This duplicates the few lines of [EmulatedInputSource.answerKeyBinding]
     * rather than calling it, because that method reads the connection itself
     * and a second reader would steal messages from this loop. The encoding is
     * shared — both go through `InputChannel` in `core-protocol` — so only the
     * policy is stated twice.
     */
    private suspend fun routeInput(message: AapMessage) {
        val channelId = inputChannelId ?: return
        when (val decoded = InputChannel.decode(message)) {
            is InputChannelMessage.KeyBinding -> {
                val unsupported = decoded.keycodes.firstOrNull { it !in inputKeycodes }
                val status = if (unsupported == null) {
                    InputChannelMessage.KeyBindingResult.STATUS_SUCCESS
                } else {
                    InputChannelMessage.KeyBindingResult.STATUS_KEYCODE_NOT_BOUND
                }
                connection.send(InputChannel.keyBindingResponse(channelId, status))
                boundKeycodes = if (unsupported == null) decoded.keycodes else emptyList()
                onStep(
                    if (unsupported == null) "bound ${decoded.keycodes.size} keycode(s)"
                    else "refused key binding: keycode $unsupported was never advertised"
                )
            }

            else -> onStep(
                "unexpected ${InputMessageId.describe(message.messageId)} from the phone on the " +
                    "input channel ($decoded)"
            )
        }
    }

    // --- microphone -----------------------------------------------------------

    /**
     * Records that the phone wants the car's microphone, which this loop cannot
     * give it. See the class KDoc for why [EmulatedMicrophone] cannot be routed
     * to without changing a file the acceptance suite pins.
     */
    private fun narrateMicrophone(message: AapMessage) {
        if (!microphoneTrafficSeen) {
            microphoneTrafficSeen = true
            onStep(
                "the phone opened a voice session (${AvMessageId.describe(message.messageId)}); " +
                    "this session loop does not drive the microphone, so no audio will arrive " +
                    "-- Phase 5's test drives EmulatedMicrophone directly"
            )
        }
    }

    // --- keepalives -----------------------------------------------------------

    /**
     * Pings on a timer and gives up when the answers stop.
     *
     * Reproduces openauto's `Pinger` faithfully, quirk included: the count is
     * incremented when the timer is *armed*, not when the ping goes out
     * (`Pinger::ping`, `Pinger.cpp` L36-L54), and the session is dropped when
     * `pingsCount_ - pongsCount_ > 1` at expiry (`onTimerExceeded`, L63-L81).
     * The net effect is that the first ping leaves at t = 5 s, one outstanding
     * ping is tolerated, and a phone that has not answered by the *second*
     * expiry is treated as gone — that is, the session dies about ten seconds
     * after the phone goes quiet.
     *
     * Reproducing the off-by-one rather than tidying it up is the point. A phone
     * that answers on the second ping passes here and passes in an openauto car;
     * a stricter emulator would fail a phone that a real unit accepts, and a
     * laxer one would pass a phone a real unit hangs up on.
     */
    private suspend fun keepalive() {
        while (currentCoroutineContext().isActive) {
            pingsScheduled++
            delay(config.pingPeriodMillis)

            if (pingsScheduled - pongsReceived > MAX_OUTSTANDING_PINGS) {
                onMissedKeepalives()
                return
            }
            sendPing()
        }
    }

    private suspend fun sendPing() {
        val timestamp = EmulatedInputSource.epochMicros()
        lastPingSentMicros = timestamp
        connection.send(
            AapMessage(
                channelId = ChannelId.CONTROL.id,
                control = false,
                encrypted = secured,
                messageId = ControlMessageType.PING_REQUEST.id,
                payload = PingRequest.newBuilder()
                    .setTimestamp(timestamp)
                    .build()
                    .toByteArray(),
            )
        )
    }

    private fun onMissedKeepalives() {
        missedKeepalives = true
        endedReason = "the phone stopped answering keepalives"
        onStep(
            "phone answered $pongsReceived of $pingsScheduled keepalive(s); a head unit ends the " +
                "session here"
        )
        if (config.dropSessionOnMissedKeepalives) {
            // A real unit drops the link, and everything downstream of that --
            // the phone noticing, reconnecting, and doing so within fifteen
            // seconds -- is behaviour Headway has to get right.
            runCatching { connection.close() }
            stop()
        }
    }

    companion object {
        /**
         * How many keepalives may be outstanding before the session is dropped.
         *
         * openauto's `pingsCount_ - pongsCount_ > 1` (`Pinger.cpp` L73), i.e. one
         * unanswered ping is forgiven and the second is fatal.
         */
        const val MAX_OUTSTANDING_PINGS: Long = 1L

        /**
         * Builds a session that serves exactly what [headUnit] advertised.
         *
         * The sinks come from the head unit's own `ServiceDiscoveryResponse`
         * rather than from [ChannelId] constants, for the reason
         * [EmulatedAudioSink.fromService] gives and
         * `dev.headway.protocol.session.AapSession` documents at length: channel
         * numbers are assigned by the head unit at runtime and the advertisement
         * is the only authority on them. Deriving both halves from one call is
         * what makes it impossible for this loop to route by an id the phone was
         * never told about.
         *
         * @param connection the same connection [EmulatedHeadUnit] brought up.
         *   Passed separately because [EmulatedHeadUnit] keeps its own private.
         */
        fun of(
            headUnit: EmulatedHeadUnit,
            connection: FramedConnection,
            config: EmulatedSessionConfig = EmulatedSessionConfig(),
            onStep: (String) -> Unit = {},
        ): EmulatedSession {
            val services = headUnit.buildServiceDiscoveryResponse().channelsList

            val videoService = services.firstOrNull {
                it.hasMediaSinkService() &&
                    it.mediaSinkService.availableType == MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP
            }
            val audioServices = services.filter {
                it.hasMediaSinkService() && it.mediaSinkService.hasAudioType()
            }
            val inputService = services.firstOrNull { it.hasInputSourceService() }
            val microphoneService = services.firstOrNull { it.hasMediaSourceService() }

            return EmulatedSession(
                connection = connection,
                video = videoService?.let {
                    EmulatedVideoSink(
                        connection = connection,
                        channelId = it.id,
                        config = config.video,
                        onStep = onStep,
                    )
                },
                audioSinks = audioServices.map {
                    EmulatedAudioSink.fromService(
                        connection = connection,
                        service = it,
                        config = config.audio,
                        onStep = onStep,
                    )
                },
                input = inputService?.let { inputSource(connection, it, onStep) },
                inputChannelId = inputService?.id,
                inputKeycodes = inputService?.inputSourceService?.keycodesSupportedList
                    ?: EmulatedHeadUnit.SUPPORTED_KEYCODES,
                microphoneChannelId = microphoneService?.id,
                config = config,
                onStep = onStep,
            )
        }

        /**
         * Builds the touch panel from the advertised `InputSourceService`.
         *
         * Coordinates on this channel are in the projected video's pixel space,
         * not the panel's — a real unit rescales before sending
         * (`openauto/src/autoapp/Projection/InputDevice.cpp` L391-L392) — and the
         * advertised `TouchScreen` geometry is what a phone builds its transform
         * from, so it is also what the emulator must generate within. A unit that
         * advertised no touchscreen falls back to
         * [EmulatedInputSource]'s own defaults rather than to zero, which would
         * make every scripted coordinate fail its bounds check.
         */
        private fun inputSource(
            connection: FramedConnection,
            service: ServiceOuterClass.Service,
            onStep: (String) -> Unit,
        ): EmulatedInputSource {
            val panel = service.inputSourceService.touchscreenList.firstOrNull()
            return if (panel == null) {
                EmulatedInputSource(
                    connection = connection,
                    channelId = service.id,
                    supportedKeycodes = service.inputSourceService.keycodesSupportedList,
                    onStep = onStep,
                )
            } else {
                EmulatedInputSource(
                    connection = connection,
                    channelId = service.id,
                    displayWidth = panel.width,
                    displayHeight = panel.height,
                    supportedKeycodes = service.inputSourceService.keycodesSupportedList,
                    onStep = onStep,
                )
            }
        }
    }
}

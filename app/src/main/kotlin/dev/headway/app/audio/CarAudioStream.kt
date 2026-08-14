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

package dev.headway.app.audio

import aap_protobuf.service.ServiceOuterClass
import aap_protobuf.service.media.shared.message.MediaCodecTypeOuterClass.MediaCodecType
import aap_protobuf.service.media.sink.message.AudioStreamTypeOuterClass.AudioStreamType
import android.content.Context
import dev.headway.audio.CarAudioSource
import dev.headway.audio.CarAudioSourceException
import dev.headway.audio.PcmBuffer
import dev.headway.audio.PhoneAudioFocus
import dev.headway.protocol.channel.AudioChannel
import dev.headway.protocol.channel.AudioFocus
import dev.headway.protocol.channel.AudioFocusState
import dev.headway.protocol.channel.MediaAudioRoute
import dev.headway.protocol.channel.PcmFormat
import dev.headway.protocol.control.ControlMessageType
import dev.headway.protocol.framing.AapMessage
import dev.headway.protocol.framing.ChannelId
import dev.headway.protocol.io.MessageChannel
import dev.headway.protocol.session.HeadUnitProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Everything between "the car opened its audio sink channels" and "the car plays
 * what Headway says".
 *
 * ## What this is for, and what it deliberately is not for
 *
 * Headway's *own* audio — spoken replies from the Phase 5 voice pipeline, and any
 * UI prompt — is the only thing that travels on the AAP audio channels. Music
 * from third-party apps does not, and this class does not carry it. CLAUDE.md
 * settles that:
 *
 * > Default strategy: instruct/steer media audio over the car's normal Bluetooth
 * > A2DP link, which coexists with the AAP session.
 *
 * so [AudioChannel] refuses to be set up at all when its route is
 * [MediaAudioRoute.BLUETOOTH_A2DP] (`AudioChannel.requireTransmitting`), and the
 * consequence here is stronger still: the media sink is **never subscribed to**.
 * Registering a demultiplexer view for a channel nobody reads would turn "a
 * message arrived for a channel with no handler", which the service logs, into a
 * message quietly queued and eventually dropped, which nobody sees. Leaving the
 * media sink unsubscribed keeps that failure visible.
 *
 * The telephony sink is left alone for the same kind of reason: a phone call
 * already reaches the car over Bluetooth HFP, and duplicating it over AAP would
 * put the caller's voice into the cabin twice.
 *
 * ## Bring-up order
 *
 * Per channel, identical to video's minus the codec configuration — PCM has
 * nothing to configure:
 *
 * 1. `sendSetup` — offer PCM.
 * 2. `awaitConfig` — the head unit answers with a status, an acknowledgement
 *    window and the configuration *indices* it will accept.
 * 3. `sendStart` — with a session id and the chosen index, which is what fixes
 *    [AudioChannel.format]. **Nothing may be rendered before this point**: the
 *    sample rate is the car's, and synthesising at a guessed rate produces a
 *    prompt that plays at the wrong speed and reports no error (see
 *    [CarAudioSource]).
 *
 * ## Why bring-up runs in the background
 *
 * [start] launches the bring-up and returns immediately, where
 * `CarVideoStream.start` performs its own inline. That difference is deliberate.
 * A real 2021 Chevrolet Infotainment 3 unit closes the session about fifteen
 * seconds after the last channel opens if no video has arrived, and audio setup
 * is the kind of exchange a head unit is entitled never to answer. Blocking the
 * session's start-up sequence on a `Config` that may never come would spend the
 * video deadline on a prompt channel nobody is listening to yet. Every wait in
 * here is bounded for the same reason.
 *
 * ## Focus, and who reads the control channel
 *
 * Audio focus is not part of any audio channel: it is two control-channel
 * messages, `MESSAGE_AUDIO_FOCUS_REQUEST` (18) out and
 * `MESSAGE_AUDIO_FOCUS_NOTIFICATION` (19) back
 * (`aasdk/protobuf/aap_protobuf/service/control/ControlMessageType.proto`
 * L23-L24; see [AudioFocus]). This class owns an [AudioFocus] over the control
 * channel and **only ever sends on it**. It must not read: the session's
 * keepalive loop already reads the control channel, and
 * [dev.headway.protocol.io.ChannelDemultiplexer] gives every view of a channel id
 * the same queue, so a second reader would race for messages — half the pings
 * would land here and go unanswered, and a head unit concludes a phone that does
 * not answer a keepalive has gone away.
 *
 * So notifications are *pushed* in through [onControlMessage], which is the
 * arrangement [PhoneAudioFocus.onCarFocus] documents ("whichever component owns
 * the control channel's demultiplexer view feeds notifications in here"). The
 * cost is that [AudioFocus.state] stays null — it is only updated by the reads
 * this class must not do — so the car's last word is tracked here instead, in
 * [lastCarFocus].
 */
class CarAudioStream(
    /** The sinks Headway will drive: guidance and system audio, in that order. */
    private val sinks: List<AudioChannel>,
    /** Advertised audio sinks left to another link; reported by [describe]. */
    private val untouched: List<ServiceOuterClass.Service>,
    /** Kept for [describe] and because [PhoneAudioFocus] sends through it. */
    private val focus: AudioFocus,
    private val phoneFocus: PhoneAudioFocus,
    private val source: CarAudioSource,
    private val onStep: (String) -> Unit = {},
) {

    private val jobs: MutableList<Job> = mutableListOf()

    /** One prompt at a time; see [withFocus]. */
    private val speaking = Mutex()

    /**
     * Completes when [bringUp] has finished, however it finished. [speak] waits
     * on it so a voice reply arriving during bring-up queues rather than being
     * dropped for a channel that was two hundred milliseconds from ready.
     */
    private val broughtUp = CompletableDeferred<Unit>()

    /**
     * Focus states pushed in by [onControlMessage].
     *
     * Conflated: only the car's latest word matters, and a prompt that waits for
     * a grant is not interested in the state before it. Stale entries are drained
     * before each request, so a grant left over from the previous prompt cannot
     * satisfy the next one.
     */
    private val grants = Channel<AudioFocusState>(Channel.CONFLATED)

    /** Swapped wholesale by [bringUp] rather than mutated, so [speak] sees it consistently. */
    @Volatile
    private var opened: List<AudioChannel> = emptyList()

    @Volatile
    private var refusals: List<String> = emptyList()

    /** The car's latest focus notification, since [AudioFocus.state] cannot be. */
    @Volatile
    var lastCarFocus: AudioFocusState? = null
        private set

    /** Prompts rendered onto a channel. Diagnostics. */
    @Volatile
    var promptsPlayed: Long = 0L
        private set

    /** PCM buffers handed to the channels. Diagnostics. */
    @Volatile
    var buffersSent: Long = 0L
        private set

    /**
     * `AUDIO_UNDERFLOW_NOTIFICATION` messages seen — the car ran out of audio to
     * play, which is what choppy speech looks like on the wire.
     *
     * Incomplete on purpose, and worth knowing about: this counts only the ones
     * [drainAcks] sees. An underflow arriving while [AudioChannel.sendPcm] is
     * waiting for acknowledgement credit is consumed inside `awaitCredit`, which
     * has no hook. Reporting a partial count and saying so beats reporting a
     * number that looks complete.
     */
    @Volatile
    var underflows: Long = 0L
        private set

    /** True once a guidance channel is started and a prompt can actually be spoken. */
    val canSpeak: Boolean
        get() = channelFor(AudioStreamType.AUDIO_STREAM_GUIDANCE) != null

    // --- lifecycle ------------------------------------------------------------

    /**
     * Starts the focus relay and the channel bring-up, both in [scope], and
     * returns at once. See the class KDoc for why this does not wait.
     *
     * @return false when the head unit advertised nothing Headway can drive,
     *   which is a real answer rather than an error: the session stays up, video
     *   and input are unaffected, and the log says what was missing.
     */
    suspend fun start(scope: CoroutineScope, sessionId: Int = DEFAULT_SESSION_ID): Boolean {
        if (sinks.isEmpty()) {
            onStep("the head unit advertised no guidance or system audio sink; Headway is mute")
            broughtUp.complete(Unit)
            return false
        }

        // PhoneAudioFocus queues AudioManager's callbacks and states plainly that
        // "nothing here launches anything" -- the session owns the coroutine that
        // drains them, and without it a phone call would never be relayed to the
        // car.
        jobs += scope.launch { phoneFocus.relayPhoneFocusChanges() }
        jobs += scope.launch { bringUp(sessionId) }
        return true
    }

    /**
     * Stops relaying focus and releases the phone-side resources.
     *
     * No `Stop` is sent on the audio channels, for the same reason
     * `CarVideoStream.stop` does not: this runs when the session is already being
     * torn down, sending is suspending, and the link is usually gone by now. The
     * head unit sees the connection close, which is the same information.
     */
    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        // Abandons Android's focus. The car's was already released at the end of
        // every prompt by withFocus, so nothing is left holding the radio down.
        runCatching { phoneFocus.close() }
        runCatching { source.close() }
    }

    /** What opened, what did not, and what the car has said about focus. */
    fun describe(): String {
        val parts = mutableListOf<String>()
        parts += if (opened.isEmpty()) {
            "no channel open"
        } else {
            opened.joinToString { "${ChannelId.describe(it.channelId)} at ${it.format}" }
        }
        parts += "$promptsPlayed prompt(s), $buffersSent buffer(s), $underflows underflow(s) seen"
        if (refusals.isNotEmpty()) parts += "refused: ${refusals.joinToString("; ")}"
        if (untouched.isNotEmpty()) {
            parts += "left to Bluetooth: " +
                untouched.joinToString { it.mediaSinkService.audioType.name }
        }
        parts += "focus: ${focus.requests.size} request(s) sent, car last said " +
            (lastCarFocus?.state?.name ?: "nothing")
        return "audio: " + parts.joinToString(" | ")
    }

    // --- bring-up -------------------------------------------------------------

    private suspend fun bringUp(sessionId: Int) {
        val started = mutableListOf<AudioChannel>()
        val refused = mutableListOf<String>()
        try {
            for (channel in sinks) {
                if (open(channel, sessionId, refused)) started += channel
            }
        } finally {
            // Published on every path, including cancellation: a speak() waiting
            // on broughtUp must not be left suspended by a session that went
            // away, and whatever did open before it went away is still the truth.
            opened = started.toList()
            refusals = refused.toList()
            broughtUp.complete(Unit)
        }
        onStep(describe())
    }

    /**
     * Sets up and starts one sink, or records why it could not be.
     *
     * A head unit refusing a channel — or never answering — is an ordinary
     * outcome that belongs in the log, not an exception that takes down a session
     * whose video is working. Only cancellation propagates.
     */
    private suspend fun open(
        channel: AudioChannel,
        sessionId: Int,
        refused: MutableList<String>,
    ): Boolean {
        val name = ChannelId.describe(channel.channelId)
        try {
            channel.sendSetup()
            val config = withTimeoutOrNull(SETUP_TIMEOUT_MILLIS) { channel.awaitConfig() }
            if (config == null) {
                refused += "$name did not answer Setup within $SETUP_TIMEOUT_MILLIS ms"
                onStep("$name never answered Setup; Headway will not use it this session")
                return false
            }
            if (!config.ready) {
                refused += "$name answered ${config.status}"
                onStep("the head unit refused $name: ${config.status}")
                return false
            }

            val index = chosenIndex(channel, config.configurationIndices, name)
            if (index == null) {
                refused += "$name offered no usable configuration"
                return false
            }

            channel.sendStart(sessionId = sessionId, configurationIndex = index)
            onStep(
                "$name ready: ${channel.format}, window ${config.maxUnacked ?: 1}, " +
                    "session $sessionId"
            )
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            // Includes EOFException when the link dies mid bring-up. Swallowed
            // here on purpose: the demultiplexer pump notices the same close and
            // the supervisor reconnects, and audio must not be the component that
            // decides a session is over.
            refused += "$name failed: ${failed.message ?: failed::class.java.simpleName}"
            onStep("could not bring up $name: ${failed.message}")
            return false
        }
    }

    /**
     * Picks the configuration index to start with.
     *
     * openauto offers exactly one index, zero
     * (`openauto/src/autoapp/Service/MediaSink/AudioMediaSinkService.cpp`
     * L167-L183), and taking the first offered is what
     * `CarVideoStream.start` does with the video equivalent.
     *
     * The one departure: a head unit that answers `STATUS_READY` while naming no
     * index at all is tolerated when it advertised a single `AudioConfiguration`,
     * because then there is nothing to guess — one format was offered and it is
     * the only thing `Start` could mean. With two or more advertised formats and
     * no index, this refuses rather than picking, since choosing wrong yields
     * audio at the wrong sample rate and no error anywhere.
     */
    private fun chosenIndex(
        channel: AudioChannel,
        offered: List<Int>,
        name: String,
    ): Int? {
        offered.firstOrNull()?.let { return it }
        if (channel.advertisedFormats.size == 1) {
            onStep(
                "$name accepted setup but named no configuration index; using its only " +
                    "advertised format (${channel.advertisedFormats.first()})"
            )
            return 0
        }
        onStep(
            "$name accepted setup but named no configuration index and advertised " +
                "${channel.advertisedFormats.size} formats; refusing to guess a sample rate"
        )
        return null
    }

    // --- speaking -------------------------------------------------------------

    /**
     * Says [text] in the car, through the guidance channel.
     *
     * This is the call the Phase 5 voice pipeline makes for a spoken reply.
     * Guidance rather than system audio because that is the stream a head unit
     * treats as navigation-and-assistant speech — it is the one openauto
     * configures at 16 kHz mono for exactly this
     * (`openauto/src/autoapp/Service/ServiceFactory.cpp` L140-L144) — and because
     * `AUDIO_FOCUS_STATE_GAIN_TRANSIENT_GUIDANCE_ONLY` exists precisely so a car
     * can grant this stream and withhold the others.
     *
     * @return false when there is no guidance channel, the phone's audio focus
     *   was refused, or no text-to-speech engine is installed — all of which are
     *   conditions a caller should report rather than crash on.
     */
    suspend fun speak(text: String): Boolean {
        val channel = awaitChannel(AudioStreamType.AUDIO_STREAM_GUIDANCE) ?: run {
            onStep("cannot speak: no guidance audio channel is open")
            return false
        }
        return withFocus(channel) { source.speak(text, it) }
    }

    /**
     * Plays an already-rendered buffer — a UI sound, a captured prompt — on the
     * system audio channel, resampled into whatever format the car negotiated.
     *
     * Separate from [speak] because system audio is what a car mixes over its own
     * source rather than ducking it for, which is the right treatment for a blip
     * and the wrong one for a sentence.
     */
    suspend fun play(
        buffer: PcmBuffer,
        stream: AudioStreamType = AudioStreamType.AUDIO_STREAM_SYSTEM_AUDIO,
    ): Boolean {
        val channel = awaitChannel(stream) ?: run {
            onStep("cannot play: no ${stream.name} channel is open")
            return false
        }
        return withFocus(channel) {
            val target = it.format ?: throw CarAudioSourceException(
                "${ChannelId.describe(it.channelId)} has no negotiated format"
            )
            it.streamPcm(source.render(buffer, target))
        }
    }

    /**
     * Takes focus on both sides, runs [render], drains the acknowledgements, and
     * gives focus back — in that order, which is the whole of the Phase 4
     * criterion.
     *
     * Skipping the release is the failure it is really about: the prompt ends,
     * the car's radio stays ducked, and nothing on the wire says why. So the
     * release is in a `finally`, reached even when synthesis fails.
     *
     * Serialised on [speaking] because two prompts sharing one channel would
     * interleave their buffers into gibberish, and because [CarAudioSource]
     * serialises synthesis anyway.
     */
    private suspend fun withFocus(
        channel: AudioChannel,
        render: suspend (AudioChannel) -> Int,
    ): Boolean = speaking.withLock {
        // A grant left over from the previous prompt must not satisfy this one.
        while (grants.tryReceive().isSuccess) Unit

        // Phone first, then car: if AudioManager refuses, the car is never asked,
        // so the radio is never ducked for a prompt that will not play.
        // PhoneAudioFocus.acquire sends the AAP request as part of this.
        if (!phoneFocus.requestForPrompt(mayDuck = true)) {
            onStep("the phone refused audio focus; the prompt is dropped rather than queued")
            return@withLock false
        }

        try {
            awaitGrant(channel.stream)
            // Bounded, and it has to be. With openauto's `max_unacked = 1` the
            // stream is strictly lock-step, so a head unit that wedges its audio
            // stack without closing TCP stops acknowledging and both
            // `AudioChannel.sendPcm`'s credit wait and the drain below wait
            // forever -- while holding [speaking] and the car's ducked radio.
            // That would make every later prompt in the session hang too, and
            // the release in the finally below would never run.
            val sent = withTimeoutOrNull(PROMPT_TIMEOUT_MILLIS) {
                val buffers = render(channel)
                drainAcks(channel)
                buffers
            }
            if (sent == null) {
                onStep(
                    "the head unit stopped acknowledging within $PROMPT_TIMEOUT_MILLIS ms; " +
                        "abandoning the prompt and giving focus back"
                )
                false
            } else {
                buffersSent += sent
                promptsPlayed++
                true
            }
        } catch (unavailable: CarAudioSourceException) {
            // No engine, blank text, an unsupported sample format: the prompt is
            // lost, the session is not.
            onStep("could not render the prompt: ${unavailable.message}")
            false
        } finally {
            phoneFocus.release()
        }
        // AudioChannelException is deliberately not caught. It means this class
        // drove the channel out of sequence -- a bug here, not a car being
        // awkward -- and swallowing it would hide it behind silence.
    }

    /**
     * Waits briefly for the head unit to grant focus for [stream].
     *
     * Not waiting at all would be wrong: the car ducks its own source when it
     * grants, and a prompt that starts first has its opening word played over the
     * radio. Waiting indefinitely would be worse — nothing in the protocol
     * obliges a head unit to answer, and a silent voice assistant is a harder
     * failure to diagnose than an overlapping one — so after
     * [FOCUS_GRANT_TIMEOUT_MILLIS] the prompt plays anyway and says so.
     *
     * @return whether a grant covering [stream] actually arrived.
     */
    private suspend fun awaitGrant(stream: AudioStreamType): Boolean {
        val granted = withTimeoutOrNull(FOCUS_GRANT_TIMEOUT_MILLIS) {
            var state = grants.receive()
            // A GAIN_MEDIA_ONLY grant does not cover a guidance prompt; keep
            // waiting rather than treating any notification as a yes.
            while (!state.allows(stream)) state = grants.receive()
            state
        }
        if (granted == null) {
            onStep(
                "no focus grant for ${stream.name} within $FOCUS_GRANT_TIMEOUT_MILLIS ms; " +
                    "playing anyway -- the car may not duck its own source"
            )
            return false
        }
        return true
    }

    /**
     * Reads until nothing is outstanding, counting underflows on the way.
     *
     * [AudioChannel.awaitAllAcks] would do the waiting, but it discards every
     * event that is not an acknowledgement, and the underflow notification is the
     * one signal that would explain choppy playback in a car.
     *
     * Unbounded by itself; [withFocus] is where the bound lives, because the
     * budget that matters covers synthesis and streaming as well.
     */
    private suspend fun drainAcks(channel: AudioChannel) {
        while (channel.unacknowledgedMessages > 0) {
            when (channel.receiveEvent()) {
                is AudioChannel.Event.Underflow -> underflows++
                else -> Unit
            }
        }
    }

    // --- control channel ------------------------------------------------------

    /**
     * Hands a control-channel message to the focus bridge.
     *
     * Called by whoever reads the control channel — this class must not, see the
     * class KDoc. Everything that is not a focus notification is left alone, so
     * the caller can go on treating the message as its own.
     *
     * @return true when this was an audio focus notification and has been applied.
     */
    fun onControlMessage(message: AapMessage): Boolean {
        if (message.channelId != ChannelId.CONTROL.id) return false
        if (message.messageId != ControlMessageType.AUDIO_FOCUS_NOTIFICATION.id) return false

        val state = AudioFocus.parseNotification(message.payload)
        lastCarFocus = state
        val volunteered = if (state.unsolicited) " (unsolicited)" else ""
        onStep("car audio focus: ${state.state.name}$volunteered")
        // Lets the car revoke Headway's phone-side focus too, so other apps are
        // not held quiet for audio the car will not play.
        phoneFocus.onCarFocus(state)
        grants.trySend(state)
        return true
    }

    // --- helpers --------------------------------------------------------------

    private fun channelFor(stream: AudioStreamType): AudioChannel? =
        opened.firstOrNull { it.stream == stream }

    /** [channelFor], but tolerant of being called while bring-up is still running. */
    private suspend fun awaitChannel(stream: AudioStreamType): AudioChannel? {
        channelFor(stream)?.let { return it }
        withTimeoutOrNull(READY_TIMEOUT_MILLIS) { broughtUp.await() }
        return channelFor(stream)
    }

    companion object {
        /**
         * Any non-zero value works; the head unit echoes it back in every
         * acknowledgement so the two sides can tell streams apart. The same id on
         * both channels is fine — it scopes to a channel, not to a session.
         */
        const val DEFAULT_SESSION_ID: Int = 1

        /**
         * How long to wait for a `Config`.
         *
         * Well inside the ~15 s the observed Chevrolet allows before it closes a
         * session with no video, so even both channels timing out cannot cost the
         * video deadline. Nothing in the references states a figure; this is a
         * budget, not a protocol constant.
         */
        const val SETUP_TIMEOUT_MILLIS: Long = 3_000L

        /** How long [speak] waits for bring-up before concluding there is no channel. */
        const val READY_TIMEOUT_MILLIS: Long = 10_000L

        /**
         * How long a whole prompt — synthesis, streaming and acknowledgement —
         * may take before it is abandoned.
         *
         * A budget, not a protocol constant. openauto answers `Setup` with
         * `max_unacked = 1` (`openauto/src/autoapp/Service/MediaSink/AudioMediaSinkService.cpp`
         * L167-L183), so the stream is lock-step and a single lost
         * acknowledgement stalls it. Fifteen seconds is far longer than any
         * prompt in this grammar and short enough that a wedged head unit gives
         * the radio back.
         */
        const val PROMPT_TIMEOUT_MILLIS: Long = 15_000L

        /**
         * How long to wait for a focus grant before speaking regardless. Short:
         * this is dead air in front of every spoken reply, and Phase 5's budget
         * is 2 s from end of speech to action.
         */
        const val FOCUS_GRANT_TIMEOUT_MILLIS: Long = 750L

        /**
         * The streams Headway drives, in bring-up order.
         *
         * Media is absent because it belongs on A2DP, telephony because it
         * belongs on HFP; see the class KDoc.
         */
        val DRIVEN_STREAMS: List<AudioStreamType> = listOf(
            AudioStreamType.AUDIO_STREAM_GUIDANCE,
            AudioStreamType.AUDIO_STREAM_SYSTEM_AUDIO,
        )

        /**
         * Finds the head unit's audio sinks by what they contain, not by channel
         * id.
         *
         * Same argument as `CarVideoStream.videoServiceOf`: the ids in
         * [ChannelId] are Headway's own convention, while the authority is the
         * `Service.id` the head unit put in its `ServiceDiscoveryResponse`.
         * Matching on the constant would work against the emulator, which
         * advertises six channels, and mis-address a car that advertises thirteen
         * and numbers them differently. What identifies an audio sink is that it
         * is a media sink offering PCM with an `AudioStreamType` and at least one
         * `AudioConfiguration` — the same four conditions
         * [AudioChannel.fromService] checks before it will build a channel.
         */
        fun audioSinksOf(profile: HeadUnitProfile): List<ServiceOuterClass.Service> =
            profile.services.filter {
                it.hasMediaSinkService() &&
                    it.mediaSinkService.availableType == MediaCodecType.MEDIA_CODEC_AUDIO_PCM &&
                    it.mediaSinkService.hasAudioType() &&
                    it.mediaSinkService.audioConfigsCount > 0
            }

        /**
         * Builds the stream for a profile, or null when the car offers no audio
         * sink at all.
         *
         * The channels are built with [AudioChannel]'s constructor rather than
         * its [AudioChannel.fromService] factory, which takes a whole
         * `FramedConnection`; a live session multiplexes thirteen channels over
         * one connection, so each channel gets a demultiplexer view instead. The
         * stream type and the sample formats are still taken from the same
         * `Service` message the factory reads them from, so none of the three is
         * a phone-side assumption.
         *
         * @param connectionFor per-channel view lookup. Called for the control
         *   channel too, for sending focus requests only — see the class KDoc.
         * @param context used for `AudioManager` and the text-to-speech engine.
         *   Only the application context is retained ([CarAudioSource] and
         *   [PhoneAudioFocus] both take one deliberately).
         */
        fun of(
            profile: HeadUnitProfile,
            connectionFor: (Int) -> MessageChannel,
            context: Context,
            onStep: (String) -> Unit = {},
        ): CarAudioStream? {
            val advertised = audioSinksOf(profile)
            if (advertised.isEmpty()) return null

            val driven = DRIVEN_STREAMS.mapNotNull { stream ->
                advertised.firstOrNull { it.mediaSinkService.audioType == stream }
            }
            // Compared by id rather than by message equality: two services can
            // carry byte-identical sink descriptions and still be different
            // channels.
            val untouched = advertised.filterNot { candidate ->
                driven.any { it.id == candidate.id }
            }

            val focus = AudioFocus(connectionFor(ChannelId.CONTROL.id), onStep = onStep)
            val channels = driven.map { service ->
                AudioChannel(
                    connection = connectionFor(service.id),
                    channelId = service.id,
                    stream = service.mediaSinkService.audioType,
                    advertisedFormats = service.mediaSinkService.audioConfigsList
                        .map(PcmFormat::from),
                    // Headway's own speech, never third-party media, so this is
                    // only ever the default; it is passed explicitly so the
                    // routing decision is visible at the call site.
                    route = MediaAudioRoute.BLUETOOTH_A2DP,
                    onStep = onStep,
                )
            }

            return CarAudioStream(
                sinks = channels,
                untouched = untouched,
                focus = focus,
                phoneFocus = PhoneAudioFocus(context, focus, onStep),
                source = CarAudioSource(context, onStep = onStep),
                onStep = onStep,
            )
        }
    }
}

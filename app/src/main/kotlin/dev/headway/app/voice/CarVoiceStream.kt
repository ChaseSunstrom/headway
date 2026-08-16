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

package dev.headway.app.voice

import aap_protobuf.service.ServiceOuterClass
import aap_protobuf.service.media.shared.message.MediaCodecTypeOuterClass.MediaCodecType
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dev.headway.app.dash.CarShell
import dev.headway.app.dash.tiles.MapsTile
import dev.headway.app.phone.CarPhone
import dev.headway.protocol.channel.MicrophoneChannel
import dev.headway.protocol.channel.MicrophoneChannelException
import dev.headway.protocol.channel.MicrophoneFormat
import dev.headway.protocol.channel.PcmChunk
import dev.headway.protocol.channel.PcmDecoder
import dev.headway.protocol.io.MessageChannel
import dev.headway.protocol.session.HeadUnitProfile
import dev.headway.voice.CommandEngine
import dev.headway.voice.InstalledApp
import dev.headway.voice.SpeechRecognizer
import dev.headway.voice.VoiceCommand
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Everything between "the driver pressed Voice" and "the phone did what they
 * asked".
 *
 * ## The chain, and where each link already lives
 *
 * ```text
 *   Data on the media-source channel
 *     -> MicrophoneChannel.pcm()      decode: s16le PCM, already acknowledged
 *     -> Endpointer.accept            when did the driver stop talking?
 *     -> SpeechRecognizer.accept      on-device STT (Vosk), 16 kHz mono
 *     -> CommandEngine.parse          transcript -> VoiceCommand
 *     -> onCommand                    launch the app / go home / hand it on
 * ```
 *
 * Every link but the endpointer was written and tested before this file existed;
 * this class is the wiring plus the one decision none of them can make, which is
 * *when the utterance is over*. That decision is argued at [Endpointer] because
 * getting it wrong is the difference between a command that works and a car
 * microphone that stays open for the rest of the drive.
 *
 * ## Push to talk, not always on — deliberately
 *
 * A voice session begins when the driver asks for one: the Voice button on
 * The car screen's microphone button ([CarShell.onVoiceRequested], installed by
 * [start]) or a steering-wheel voice key routed into [requestListening]. There
 * is **no wake word and no always-listening mode, and adding one is a non-goal**.
 *
 * The reasoning is privacy, and it is structural rather than a policy note.
 * CLAUDE.md requires that "recognition results and audio never leave the device;
 * do not write raw audio to disk except behind an explicit debug flag" — this
 * class satisfies that by never persisting a sample and never opening a socket,
 * and the samples it does hold live only for the length of one utterance. An
 * always-on mode would invert that: the car's cabin microphone would be open for
 * every conversation held in the vehicle, including passengers who never
 * consented to Headway existing, and the only thing standing between that audio
 * and a disk would be a policy. A button press is a consent signal that costs
 * the driver one touch and cannot be misread.
 *
 * It is also, incidentally, the only design that works: `MicrophoneRequest` opens
 * the head unit's microphone, and every reference's head unit shows a "recording"
 * state on the car screen while it is open.
 *
 * ## Reading: one collector at a time
 *
 * [MicrophoneChannel]'s KDoc is explicit that exactly one coroutine may read the
 * channel at a time — collect `pcm()` *or* call `receiveEvent()`, never both. In
 * a live session the [dev.headway.protocol.io.ChannelDemultiplexer] owns the
 * socket and this class owns the microphone channel's *view* of it, so the
 * constraint reduces to: only one [listen] may be in flight. [listening] enforces
 * that, and a second button press while a session is running is answered by
 * [stopListening] rather than by opening a second one.
 *
 * The reply to a close request must always be drained before the next [listen],
 * which is why [closeCapture] waits for it even when the caller has walked away.
 * `MicrophoneResponse` carries no field saying which request it answers — the
 * channel infers that from the last request *it* sent — so a close reply left in
 * the queue would be read as the answer to the *next* open request, and the
 * channel would believe it was capturing when it was not.
 */
class CarVoiceStream(
    private val channel: MicrophoneChannel,
    private val engine: CommandEngine,
    /**
     * Opens the speech recogniser, or returns null when this phone has none.
     *
     * A function rather than a [SpeechRecognizer] because loading a Vosk model
     * is ~40 MB of mmap and the better part of a second: [start] kicks it off in
     * the background so it is warm by the time a button is pressed, and a phone
     * with no model must degrade to "the audio path works, nothing is
     * transcribed" rather than to a crash. See [voskRecognizer] for why the
     * default implementation loads reflectively.
     */
    private val openRecognizer: () -> SpeechRecognizer?,
    /** Carries out a resolved command. [defaultExecutor] is what `of` supplies. */
    private val onCommand: (VoiceCommand) -> Unit,
    /** Narrates each step; wired to the debug log export in the app. */
    private val onStep: (String) -> Unit = {},
    /**
     * The application context, for the phone-microphone fallback only.
     *
     * Nullable so the unit tests can build a stream with no Android around it,
     * which is most of them: a null one simply means the fallback is
     * unavailable, and the car path never asks.
     */
    private val appContext: Context? = null,
) {

    private val listening = AtomicBoolean(false)
    private val closeRequested = AtomicBoolean(false)

    private var scope: CoroutineScope? = null
    private var recognizerLoad: Deferred<SpeechRecognizer?>? = null
    private var trigger: Job? = null

    /**
     * The loaded recogniser, held separately from [recognizerLoad] so that [stop]
     * can close it without asking a [Deferred] for a result it may not have.
     * `Deferred.getCompleted()` would be the direct way and is opt-in
     * experimental API; a volatile field the loader writes costs nothing and
     * commits this file to no unstable surface.
     */
    @Volatile
    private var recognizer: SpeechRecognizer? = null

    /** Non-null once a recogniser has actually been constructed. Diagnostics. */
    @Volatile
    private var recognizerDescription: String? = null

    /** Listening sessions started. Diagnostics. */
    @Volatile
    var utterances: Long = 0L
        private set

    /** Commands resolved to something actionable and handed to [onCommand]. */
    @Volatile
    var commandsRun: Long = 0L
        private set

    /**
     * Negotiates the microphone channel and arms the Voice button.
     *
     * Does **not** open the microphone: that happens per [listen]. What this
     * settles is whether the head unit will give us audio at all, which it
     * answers with a `Config` in reply to our `Setup`.
     *
     * @return false when the head unit refused the stream, which is a real
     *   answer rather than an error: the session stays up and the log says why.
     */
    suspend fun start(scope: CoroutineScope): Boolean {
        // A head unit that never answers Setup must not hold up the rest of the
        // bring-up. The video stream is on a deadline measured in seconds
        // (see CarVideoStream), and a car with a mute microphone is still a car
        // worth mirroring to.
        val setup = withTimeoutOrNull(SETUP_TIMEOUT_MILLIS) {
            channel.open(MediaCodecType.MEDIA_CODEC_AUDIO_PCM)
        }
        if (setup == null) {
            onStep("voice: the head unit did not answer the microphone setup within $SETUP_TIMEOUT_MILLIS ms")
            return startOnPhoneMicrophone(scope)
        }
        if (!setup.ready) {
            onStep("voice: the head unit refused the microphone: ${setup.status}")
            return startOnPhoneMicrophone(scope)
        }

        this.scope = scope
        // Loading starts now so the model is resident before the first press.
        // async with the failure caught *inside* the block on purpose: an async
        // that throws would fail this scope, and the scope is the session.
        recognizerLoad = scope.async(Dispatchers.IO) {
            // Caught here, not in voskRecognizer: that is only the *default*
            // openRecognizer, and this is a public constructor parameter. An
            // injected one that throws would fail this scope -- and the scope is
            // the session, which runChannels builds with coroutineScope rather
            // than supervisorScope, so video, audio and input would die with it.
            val opened = runCatching { openRecognizer() }
                .onFailure { onStep("voice: the speech model could not be opened: $it") }
                .getOrNull()
            recognizer = opened
            if (opened != null) {
                recognizerDescription = "speech model loaded at ${opened.sampleRateHz} Hz"
            }
            opened
        }

        CarShell.onVoiceRequested = { requestListening() }
        onStep("voice ready: ${channel.format}, window ${setup.maxUnacked ?: "unstated"}")
        return true
    }

    /**
     * The fallback when the car offers no microphone: listen with the phone's.
     *
     * Everything after the audio is the same — the endpointer, the recogniser,
     * the command engine — so this arms the identical button against a different
     * source. It is deliberately second: the cabin microphone is echo-cancelled,
     * aimed at the driver and above the road noise, and a phone in a cup holder
     * is none of those.
     *
     * Returns false, and leaves the button saying so, when the driver has not
     * granted `RECORD_AUDIO`. Nothing here asks for it: a permission prompt
     * raised from a background service mid-drive is worse than a button that
     * explains itself.
     */
    private fun startOnPhoneMicrophone(scope: CoroutineScope): Boolean {
        val context = appContext
        if (context == null || !PhoneMicSource.available(context)) {
            onStep(
                "voice: the car offered no microphone and Headway has no microphone permission, " +
                    "so the Voice button has nothing to listen with. Grant Microphone in " +
                    "Android's app settings to use the phone's instead",
            )
            return false
        }
        this.scope = scope
        usingPhoneMicrophone = true
        recognizerLoad = scope.async(Dispatchers.IO) {
            val opened = runCatching { openRecognizer() }
                .onFailure { onStep("voice: the speech model could not be opened: $it") }
                .getOrNull()
            recognizer = opened
            if (opened != null) {
                recognizerDescription = "speech model loaded at ${opened.sampleRateHz} Hz"
            }
            opened
        }
        CarShell.onVoiceRequested = { requestListening() }
        onStep("voice ready on the phone's microphone: ${PhoneMicSource.FORMAT}")
        return true
    }

    /** True while the Voice button listens with the phone rather than the car. */
    @Volatile
    private var usingPhoneMicrophone: Boolean = false

    /**
     * Starts a listening session without waiting for it — what the Voice button
     * calls, and what a steering-wheel voice key should be routed to.
     *
     * Anything thrown inside is logged rather than propagated. This runs in the
     * session's scope, so an escaping exception would take the whole car session
     * down: a mis-heard command is not worth losing video over.
     */
    fun requestListening() {
        // Never while a call is live. `startCapture` asks the head unit to hand
        // its cabin microphone to Headway, and during a call that microphone is
        // the *call's* -- routed there natively over Bluetooth HFP by the
        // phone's own telephony stack, which is how a driver talks to whoever
        // rang. Taking it for a voice command would cut the caller off, and
        // nothing else in this app would report why.
        //
        // Headway cannot supply the call's audio itself at any grant a user can
        // give; see BLOCKERS.md B-027. So the correct behaviour is to leave the
        // microphone alone and say so.
        CarPhone.call?.let { live ->
            onStep("voice: not listening while a call is up; the car's mic belongs to the call")
            CarShell.active()?.showVoiceMessage(
                if (live.ringing) "Answer or decline first" else "The car's mic is on your call",
            )
            return
        }
        val scope = scope
        if (scope == null) {
            // Says so *on the car screen*, not only in the log. The hook the rail
            // calls is installed by `HeadwayService` before this stream starts --
            // it has to be, because the shell is built during video bring-up and
            // the stream is not -- so a head unit that refuses the microphone
            // channel leaves a button that is wired, callable, and silent. Press
            // it, nothing happens, nothing anywhere says why: which is exactly
            // what "the mic button doesn't work" looks like from the seat.
            onStep("voice: a listening session was requested before the channel was ready")
            CarShell.active()?.showVoiceMessage("The car has not offered its microphone")
            return
        }
        // A second press ends the utterance rather than being ignored. Without
        // this the Voice button is the only wired trigger and a driver who has
        // finished speaking has no way to say so -- they wait out the trailing
        // silence, or the ten-second cap if the cabin is loud enough that the
        // endpointer never hears silence at all.
        if (listening.get()) {
            trigger = scope.launch { runCatching { stopListening() } }
            return
        }
        trigger = scope.launch {
            try {
                listen()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onStep("voice: the listening session failed: $e")
            }
        }
    }

    /**
     * Opens the car microphone, listens until the driver stops speaking, and
     * resolves what they said.
     *
     * @return the command that was recognised, or null when nothing was — a head
     *   unit that refused to open its microphone, a phone with no speech model,
     *   silence, or speech that matched no rule. Every one of those is logged
     *   with which it was.
     */
    suspend fun listen(): VoiceCommand? {
        if (!listening.compareAndSet(false, true)) {
            onStep("voice: already listening; ignoring the request")
            return null
        }
        utterances++
        closeRequested.set(false)
        CarShell.active()?.setListening(true)
        try {
            // The phone's microphone needs no channel opened and no channel
            // closed: there is nothing on the wire and nothing for a head unit
            // to refuse. Everything after the audio is shared.
            if (usingPhoneMicrophone) return listenOnPhone()
            val opened = channel.startCapture()
            if (!opened.ok) {
                onStep("voice: the head unit would not open its microphone: status ${opened.status}")
                CarShell.active()?.showVoiceMessage("The car would not open its microphone")
                return null
            }

            // Awaited here rather than at start() so that a slow model load
            // costs the first utterance a little latency instead of costing
            // every session a stalled bring-up.
            val stt = recognizerLoad?.await()
            stt?.reset()

            val endpointer = Endpointer(channel.format)
            val transcript = try {
                collectUtterance(stt, endpointer)
            } finally {
                closeCapture()
            }

            if (!endpointer.speechSeen) {
                onStep("voice: no speech in ${endpointer.elapsedMicros / 1_000} ms of car audio")
                CarShell.active()?.showVoiceMessage("Heard nothing")
                return null
            }
            if (stt == null) {
                // Honest degradation: the audio path demonstrably worked, and
                // saying so is what tells a user their microphone is fine and
                // their model is missing.
                onStep(
                    "voice: heard ${endpointer.elapsedMicros / 1_000} ms of speech but this phone has " +
                        "no on-device speech model, so nothing was transcribed"
                )
                CarShell.active()?.showVoiceMessage("No speech model on this phone")
                return null
            }

            onStep("voice heard: \"$transcript\"")
            CarShell.active()?.showVoiceMessage("\u201c$transcript\u201d")
            val command = engine.parse(transcript)
            if (command is VoiceCommand.Unrecognised) {
                onStep("voice: \"$transcript\" matched no command")
                return command
            }
            commandsRun++
            onCommand(command)
            return command
        } catch (e: MicrophoneChannelException) {
            // The channel refuses audio it cannot decode rather than guessing at
            // it. That kills voice for this session, not the session.
            onStep("voice: the microphone channel gave up: ${e.message}")
            CarShell.active()?.showVoiceMessage("The car's microphone stopped")
            return null
        } finally {
            listening.set(false)
            CarShell.active()?.setListening(false)
        }
    }

    /**
     * Ends the current listening session early — a push-to-talk release, or a
     * second press of the Voice button.
     *
     * Send-only by way of [MicrophoneChannel.stopCapture], which is documented as
     * safe to call from a coroutine other than the one collecting: the head unit
     * stops sending `Data` and answers, the collector sees the close reply as
     * end-of-stream, and [listen] transcribes what it has.
     */
    suspend fun stopListening() {
        if (!listening.get()) return
        if (closeRequested.compareAndSet(false, true)) {
            channel.stopCapture()
        }
    }

    /** Model state and command count, for the log. */
    fun describe(): String {
        val model = recognizerDescription ?: "no on-device speech model"
        val source = if (usingPhoneMicrophone) "the phone's microphone" else "the car's microphone"
        return "voice: $model, listening on $source, $utterances utterance(s), " +
            "$commandsRun command(s) run, ${channel.chunksReceived} chunk(s) / " +
            "${channel.framesReceived} frame(s) of car audio"
    }

    /**
     * Disarms the Voice button and releases the model.
     *
     * The microphone is not closed here: if a session is in flight its own
     * `finally` closes it, and issuing a second close would leave a reply in the
     * queue for the next session to misread (see the class KDoc).
     */
    fun stop() {
        CarShell.onVoiceRequested = null
        trigger?.cancel()
        trigger = null
        recognizerLoad?.cancel()
        recognizerLoad = null
        scope = null
        runCatching { recognizer?.close() }
        recognizer = null
        recognizerDescription = null
    }

    // --- the audio path ------------------------------------------------------

    /**
     * One utterance from the phone's microphone.
     *
     * The same shape as the car path with the channel removed: no `startCapture`
     * to be refused, no close to be drained, and a source that ends when the
     * endpointer says the driver stopped talking. Called with [listening] already
     * true and the banner already up, from [listen], which owns both.
     */
    private suspend fun listenOnPhone(): VoiceCommand? {
        val context = appContext ?: return null
        val stt = recognizerLoad?.await()
        stt?.reset()
        val endpointer = Endpointer(PhoneMicSource.FORMAT)
        val transcript = try {
            withTimeoutOrNull(HARD_LIMIT_MILLIS) {
                PhoneMicSource.pcm(onStep).firstOrNull { chunk ->
                    if (stt != null) {
                        val bytes = PcmDecoder.encodeS16Le(chunk.samples)
                        stt.accept(bytes, bytes.size)
                    }
                    endpointer.accept(chunk)
                }
            }
            stt?.finish().orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Every way the microphone can refuse to open arrives here as an
            // IllegalStateException from the flow. Reported on the car screen,
            // because a Voice button that goes quiet is the complaint this whole
            // path exists to answer.
            onStep("voice: the phone's microphone would not open: $e")
            CarShell.active()?.showVoiceMessage("The phone's microphone would not open")
            return null
        }
        if (!endpointer.speechSeen) {
            onStep("voice: no speech in ${endpointer.elapsedMicros / 1_000} ms of phone audio")
            CarShell.active()?.showVoiceMessage("Heard nothing")
            return null
        }
        if (stt == null) {
            onStep(
                "voice: heard ${endpointer.elapsedMicros / 1_000} ms of speech on the phone's " +
                    "microphone but this phone has no on-device speech model"
            )
            CarShell.active()?.showVoiceMessage("No speech model on this phone")
            return null
        }
        onStep("voice heard (phone microphone): \"$transcript\"")
        CarShell.active()?.showVoiceMessage("\u201c$transcript\u201d")
        val command = engine.parse(transcript)
        if (command is VoiceCommand.Unrecognised) {
            onStep("voice: \"$transcript\" matched no command")
            return command
        }
        commandsRun++
        onCommand(command)
        return command
    }

    /**
     * Feeds the recogniser until the endpointer says the driver has finished.
     *
     * `firstOrNull` is the collection shape rather than a bare `collect` because
     * it stops the flow at the chunk that ends the utterance, which is what
     * releases the channel for the close request. A hard timeout sits outside it
     * so that a head unit which streams silence forever cannot pin the coroutine.
     */
    private suspend fun collectUtterance(
        recognizer: SpeechRecognizer?,
        endpointer: Endpointer,
    ): String {
        withTimeoutOrNull(HARD_LIMIT_MILLIS) {
            channel.pcm().firstOrNull { chunk ->
                // Re-encoding costs one 640-byte array per 20 ms and buys the
                // recogniser the byte layout its API takes. PcmDecoder documents
                // encodeS16Le as the exact inverse of the decode the channel just
                // did, so nothing is reinterpreted on the way through.
                if (recognizer != null) {
                    val bytes = PcmDecoder.encodeS16Le(chunk.samples)
                    recognizer.accept(bytes, bytes.size)
                }
                endpointer.accept(chunk)
            }
        }
        return recognizer?.finish().orEmpty()
    }

    /**
     * Closes the microphone and drains the head unit's reply.
     *
     * [NonCancellable] because this is the one thing that must happen even when
     * the session is being torn down: a head unit left capturing shows a
     * recording indicator on the car screen with nothing listening to it.
     */
    private suspend fun closeCapture() = withContext(NonCancellable) {
        if (closeRequested.compareAndSet(false, true)) {
            runCatching { channel.stopCapture() }
        }
        // On the push-to-talk path [stopListening] already sent the close, and
        // `pcm()` treats the reply as end-of-stream and *consumes* it. Waiting
        // for a second reply that will never come costs the full timeout and
        // then logs a failure for a head unit that did exactly the right thing
        // -- with [listening] still true, so the next button press is swallowed
        // too. The channel clears `capturing` when it parses that reply, which
        // is the signal that there is nothing left to drain.
        if (!channel.capturing) return@withContext
        val closed = runCatching {
            withTimeoutOrNull(CLOSE_REPLY_TIMEOUT_MILLIS) { channel.awaitCaptureResponse() }
        }.getOrNull()
        if (closed == null) {
            onStep("voice: the head unit did not confirm the microphone close")
        }
    }

    /**
     * Decides when an utterance has ended, from the audio alone.
     *
     * ## Why this does not ask the recogniser
     *
     * Vosk will happily say when it thinks an utterance ended — that is what
     * `accept()` returning a segment means. Two things make it the wrong oracle
     * here. It splits on *internal* silence, so "open… calculator" ends twice
     * (which is exactly why [dev.headway.voice.VoskSpeechRecognizer] accumulates
     * segments at all); and on a phone with no model there is no recogniser to
     * ask, yet the microphone still has to be closed. Endpointing therefore runs
     * on the samples, independently, and works identically with the recogniser
     * absent.
     *
     * ## Why the threshold is relative
     *
     * A cabin at 110 km/h with the fan on has a noise floor tens of decibels
     * above a parked one, so a fixed threshold is either deaf in one car or
     * permanently triggered in the other. The first [CALIBRATION_MICROS] of every
     * session — the moment just after the button press, when the driver has not
     * started speaking yet — measures the floor, and speech is then anything
     * [SPEECH_OVER_NOISE] times louder. [ABSOLUTE_FLOOR_RMS] keeps a silent
     * parked cabin from setting a floor so low that the microphone's own dither
     * reads as speech.
     *
     * The thresholds are honest guesses: there is no car available to tune them
     * against (BLOCKERS.md B-001), and the numbers below are the conventional
     * ones for 16-bit speech VAD rather than measurements. They are all
     * constants in one place for exactly that reason.
     */
    private class Endpointer(private val format: MicrophoneFormat) {

        /** True once anything louder than the measured floor has arrived. */
        var speechSeen: Boolean = false
            private set

        /** Audio consumed so far, for the log. */
        var elapsedMicros: Long = 0L
            private set

        private var noiseSum: Double = 0.0
        private var noiseMicros: Long = 0L
        private var noiseRms: Double = 0.0
        private var silenceMicros: Long = 0L

        /** @return true when the utterance is over and the flow should stop. */
        fun accept(chunk: PcmChunk): Boolean {
            val micros = format.durationMicros(chunk.frameCount(format).toLong())
            elapsedMicros += micros
            val rms = rms(chunk.samples)

            if (noiseMicros < CarVoiceStream.CALIBRATION_MICROS) {
                // Weighted by duration rather than by chunk, so the estimate does
                // not depend on how the head unit chose to cut the stream up.
                noiseSum += rms * micros
                noiseMicros += micros
                noiseRms = if (noiseMicros > 0) noiseSum / noiseMicros else 0.0
                return elapsedMicros >= CarVoiceStream.HARD_LIMIT_MICROS
            }

            val threshold = maxOf(
                noiseRms * CarVoiceStream.SPEECH_OVER_NOISE,
                CarVoiceStream.ABSOLUTE_FLOOR_RMS,
            )
            if (rms > threshold) {
                speechSeen = true
                silenceMicros = 0L
            } else if (speechSeen) {
                silenceMicros += micros
            }

            return when {
                speechSeen && silenceMicros >= CarVoiceStream.TRAILING_SILENCE_MICROS -> true
                !speechSeen && elapsedMicros >= CarVoiceStream.LEAD_IN_MICROS -> true
                elapsedMicros >= CarVoiceStream.HARD_LIMIT_MICROS -> true
                else -> false
            }
        }

        private fun rms(samples: ShortArray): Double {
            if (samples.isEmpty()) return 0.0
            var sum = 0.0
            for (sample in samples) {
                val value = sample.toDouble()
                sum += value * value
            }
            return sqrt(sum / samples.size)
        }
    }

    companion object {

        /** How long to wait for the head unit's answer to the microphone `Setup`. */
        const val SETUP_TIMEOUT_MILLIS: Long = 5_000

        /** How long to wait for the reply to a close request before giving up on it. */
        const val CLOSE_REPLY_TIMEOUT_MILLIS: Long = 2_000

        /**
         * The longest a single utterance may run. A command in this grammar is
         * three words; ten seconds is generous for one and short enough that a
         * head unit streaming silence releases the microphone promptly.
         */
        const val HARD_LIMIT_MILLIS: Long = 10_000
        const val HARD_LIMIT_MICROS: Long = HARD_LIMIT_MILLIS * 1_000

        /** Noise-floor measurement window, taken from the top of each session. */
        const val CALIBRATION_MICROS: Long = 200_000

        /** Silence after speech that ends the utterance. */
        const val TRAILING_SILENCE_MICROS: Long = 800_000

        /** Silence *before* any speech that abandons the session. */
        const val LEAD_IN_MICROS: Long = 4_000_000

        /** How much louder than the measured floor a chunk must be to be speech. */
        const val SPEECH_OVER_NOISE: Double = 3.0

        /** Floor below which a "floor" is not believed. About -46 dBFS. */
        const val ABSOLUTE_FLOOR_RMS: Double = 160.0

        /** Where a Vosk model is expected on the phone, if one has been installed. */
        const val MODEL_DIRECTORY: String = "vosk-model"

        /**
         * Loaded by name so the app module does not link against Vosk.
         *
         * `:core-voice` declares Vosk `compileOnly` and `:app` adds no
         * `vosk-android` runtime, which PROGRESS.md records: on a phone the
         * `org.vosk` classes are simply absent. A direct reference here would be
         * worse than a runtime failure — it would make
         * [dev.headway.voice.VoskSpeechRecognizer] reachable code, and R8 fails
         * a release build outright when a reachable class's dependencies are
         * missing. So the app builds and ships either way, and the recogniser
         * appears the day the dependency and the model do.
         */
        const val VOSK_RECOGNIZER_CLASS: String = "dev.headway.voice.VoskSpeechRecognizer"

        /**
         * Finds the head unit's microphone service by content, not by channel id.
         *
         * The advertisement is the authority — `ChannelId.MEDIA_SOURCE_MICROPHONE`
         * is Headway's own convention and a real unit numbers its channels how it
         * likes — and what identifies the car microphone is that it is a media
         * *source* offering linear PCM. Note the codec comparison holds for a
         * unit that omits the field: `MediaSourceService.available_type` has a
         * proto2 default of `MEDIA_CODEC_AUDIO_PCM`
         * (`aasdk/protobuf/aap_protobuf/service/media/source/MediaSourceService.proto`
         * L10), and no reference puts anything else on this channel.
         */
        fun microphoneServiceOf(profile: HeadUnitProfile): ServiceOuterClass.Service? =
            profile.services
                .filter {
                    it.hasMediaSourceService() &&
                        it.mediaSourceService.availableType == MediaCodecType.MEDIA_CODEC_AUDIO_PCM
                }
                // Scored, not "the first one". A head unit may advertise several
                // media sources -- typically a 16 kHz cabin microphone and an
                // 8 kHz one for calls -- in no guaranteed order, which is why
                // aa-proxy-rs ranks them rather than taking the head of the list
                // (`aa-proxy-rs/src/mitm.rs` L1109-L1159,
                // `choose_bt_sco_microphone_source`). Taking the first would
                // pick the call microphone on a unit that lists it first and
                // then warn about a sample rate the car never made us use.
                .minByOrNull { microphoneScore(advertisedFormat(it)) }

        /** The format a source advertises, or the car-microphone default. */
        private fun advertisedFormat(service: ServiceOuterClass.Service): MicrophoneFormat {
            val source = service.mediaSourceService
            return if (source.hasAudioConfig()) {
                MicrophoneFormat.fromAdvertisement(source.audioConfig)
            } else {
                MicrophoneFormat.CAR_MICROPHONE
            }
        }

        /**
         * Lower is better; aa-proxy-rs's ranking, with its numbers.
         *
         * 16 kHz mono 16-bit is what the speech model is trained on and what
         * every reference calls the cabin microphone. 8 kHz mono 16-bit is the
         * call microphone: usable, and much worse. Anything else is a format
         * nothing here decodes well and is taken only if it is all there is.
         */
        private fun microphoneScore(format: MicrophoneFormat): Int = when {
            format == MicrophoneFormat.CAR_MICROPHONE -> 0
            format.sampleRateHz == 8_000 && format.channels == 1 && format.bitsPerSample == 16 -> 10
            else -> 50
        }

        /** Where [voskRecognizer] looks for an unpacked model. */
        fun defaultModelDirectory(context: Context): File = File(context.filesDir, MODEL_DIRECTORY)

        /**
         * Opens a Vosk recogniser at [modelDirectory], or returns null with a
         * reason logged.
         *
         * Missing-model detection follows `core-voice`'s own convention, which is
         * a directory test rather than an exception type: its Phase 5 acceptance
         * test skips on `File(configured).isDirectory` being false. Everything
         * else — the absent `org.vosk` classes, a native library that will not
         * load, a corrupt model — surfaces as a [Throwable] rather than an
         * [Exception] in at least one case (`NoClassDefFoundError`), which is why
         * the catch is that wide. None of them is worth losing the session over:
         * a car with no voice is still a car with a screen.
         */
        fun voskRecognizer(
            modelDirectory: File,
            sampleRateHz: Int,
            onStep: (String) -> Unit,
        ): SpeechRecognizer? {
            if (!modelDirectory.isDirectory) {
                onStep(
                    "voice: no speech model at ${modelDirectory.absolutePath}; the car microphone " +
                        "still works but nothing will be transcribed"
                )
                return null
            }
            val breadcrumb = File(modelDirectory.parentFile, LOADING_MARKER)
            if (breadcrumb.exists()) {
                onStep(
                    "voice: the last attempt to start the speech model killed the app, so it is " +
                        "being skipped. This is the speech engine needing to build machine code " +
                        "while it runs, which GrapheneOS blocks with \"Restrict dynamic code " +
                        "loading\" -- allowing it for Headway in the OS app settings, under " +
                        "Exploit protection, is what turns voice back on. Everything else works " +
                        "without it. Delete the model and re-download it to try again"
                )
                return null
            }
            // Written before the call and removed after it, because the failure
            // this guards against cannot be caught. Loading Vosk goes through
            // JNA, which builds its native call trampolines at runtime and needs
            // memory that is first writable and then executable. GrapheneOS
            // refuses that -- a real drive log has
            // TSEC_FLAG_DENY_EXECUTE_APP_DATA_FILE on this process's cache
            // directory -- and libffi does not check: it writes through the null
            // it got back and the whole process dies on SIGSEGV at address 0x8,
            // taking the car session with it. No catch block runs, so the only
            // evidence that survives is a file.
            runCatching { breadcrumb.parentFile?.mkdirs(); breadcrumb.writeText(VOSK_RECOGNIZER_CLASS) }
            return try {
                val recognizer = Class.forName(VOSK_RECOGNIZER_CLASS)
                    .getConstructor(String::class.java, Int::class.javaPrimitiveType)
                    .newInstance(modelDirectory.absolutePath, sampleRateHz) as SpeechRecognizer
                onStep("voice: loaded the speech model at ${modelDirectory.absolutePath}")
                recognizer
            } catch (t: Throwable) {
                onStep("voice: this build cannot run the speech model (${describeCause(t)})")
                null
            } finally {
                // Reached on success and on a thrown failure alike. The only
                // path that leaves it behind is the one that never returns.
                runCatching { breadcrumb.delete() }
            }
        }

        /**
         * Clears the "loading the speech model killed us" marker.
         *
         * Exposed so a driver who has allowed dynamic code loading, or who
         * re-downloads the model, gets voice back without reinstalling.
         */
        fun forgetSpeechModelCrash(context: Context) {
            runCatching { File(defaultModelDirectory(context).parentFile, LOADING_MARKER).delete() }
        }

        /** True when the last attempt to start the speech model did not return. */
        fun speechModelCrashed(context: Context): Boolean =
            runCatching {
                File(defaultModelDirectory(context).parentFile, LOADING_MARKER).exists()
            }.getOrDefault(false)

        /**
         * Name of the breadcrumb file. A sibling of the model directory rather
         * than a child, so deleting and re-extracting the model does not carry
         * a stale marker back in with it.
         */
        private const val LOADING_MARKER = "speech-model-loading"

        /**
         * Names the failure a reflective construction actually hit.
         *
         * `Constructor.newInstance` wraps whatever the constructor threw in an
         * `InvocationTargetException` whose own message is null, so the log line
         * from a real drive read, in full, "InvocationTargetException: null" --
         * which says nothing at all about why voice was unavailable. Walking the
         * cause chain turns that into the `UnsatisfiedLinkError` naming the
         * library that would not load, which is a fixable report.
         */
        private fun describeCause(thrown: Throwable): String {
            val chain = generateSequence(thrown) { it.cause?.takeIf { cause -> cause !== it } }
            return chain
                .take(MAX_CAUSE_DEPTH)
                .joinToString(" <- ") { link ->
                    val message = link.message?.takeIf { it.isNotBlank() }
                    if (message == null) link.javaClass.simpleName
                    else "${link.javaClass.simpleName}: $message"
                }
        }

        /** Deep enough for a reflective wrapper over a loader error, and bounded. */
        private const val MAX_CAUSE_DEPTH = 5

        /**
         * Every app with a launcher entry, as the command engine's vocabulary.
         *
         * Headway itself is excluded: "go home" is the way back to the car
         * launcher, and leaving Headway in the list lets a mis-heard app name
         * fuzzy-match onto it.
         */
        fun installedApps(context: Context): List<InstalledApp> {
            val packageManager = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            return packageManager
                .queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
                .asSequence()
                .filter { it.activityInfo.packageName != context.packageName }
                .map { InstalledApp(it.activityInfo.packageName, it.loadLabel(packageManager).toString()) }
                .distinctBy { it.packageName }
                .toList()
        }

        /**
         * Carries out the commands this class can carry out on its own.
         *
         * Launching an app and returning to the launcher are both a
         * `startActivity`, and both are things the car launcher already does for
         * a touch — the flags are the same ones
         * the car screen uses, for the same reasons. Media, volume and
         * search are deliberately *not* handled here: transport controls belong
         * to the session's media handling and typing belongs to the accessibility
         * service, and a caller that owns those can pass its own executor to
         * [of]. They are logged rather than silently dropped so that a driver's
         * "pause" showing up in the log with nothing after it is diagnosable.
         *
         * One honest caveat: Android's background-activity-start rules mean a
         * `startActivity` from a service can be refused when Headway has no
         * visible activity. Since ADR 0010 there deliberately is none — a
         * session that comes up on its own must not take the phone's screen —
         * so a launch onto the app display may be silently ignored by the
         * platform. The call reports success either way and there is no
         * unprivileged API that reports otherwise; `B-018` tracks it.
         */
        fun defaultExecutor(context: Context, onStep: (String) -> Unit): (VoiceCommand) -> Unit =
            { command ->
                when (command) {
                    is VoiceCommand.LaunchApp -> {
                        val target = command.packageName
                        val shell = CarShell.active()
                        val intent = target?.let { context.packageManager.getLaunchIntentForPackage(it) }
                        if (intent == null) {
                            onStep("voice: nothing installed matches \"${command.query}\"")
                        } else if (shell != null) {
                            // Into the app pane, exactly as a tap on the grid
                            // would. The shell also brings a layout with an app
                            // pane forward if the one on screen has none.
                            shell.openApp(target)
                            onStep("voice: opened $target in the app pane")
                        } else {
                            intent.addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                            )
                            val started = runCatching { context.startActivity(intent) }
                            onStep(
                                if (started.isSuccess) "voice: launched $target"
                                else "voice: could not launch $target (${started.exceptionOrNull()})"
                            )
                        }
                    }

                    VoiceCommand.GoHome -> {
                        val shell = CarShell.active()
                        if (shell == null) {
                            onStep("voice: there is no car screen to go home to")
                        } else {
                            shell.goHome()
                            onStep("voice: back to the home layout")
                        }
                    }

                    is VoiceCommand.Navigate -> {
                        // MapsTile owns the geo: hand-off, the driver's chosen
                        // map app, and giving the car screen to it, so the
                        // executor asks rather than reimplementing all three.
                        MapsTile.navigateTo(context, command.destination, onStep)
                    }

                    is VoiceCommand.Media -> onStep("voice: ${command.action} needs a media handler")
                    is VoiceCommand.Volume ->
                        onStep("voice: volume ${command.direction} x${command.steps} needs a volume handler")
                    is VoiceCommand.Search -> onStep("voice: search \"${command.text}\" needs a text handler")
                    is VoiceCommand.Unrecognised -> Unit
                }
            }

        /**
         * Builds the stream for a profile, or null when the car offers no
         * microphone Headway can decode.
         *
         * @param context any application-lifetime context; used for the installed
         *   app list, the model directory and `startActivity`.
         * @param onCommand overrides [defaultExecutor] for a caller that owns
         *   media transport controls or the accessibility service.
         */
        fun of(
            profile: HeadUnitProfile,
            connectionFor: (Int) -> MessageChannel,
            context: Context,
            modelDirectory: File = defaultModelDirectory(context),
            onCommand: ((VoiceCommand) -> Unit)? = null,
            onStep: (String) -> Unit = {},
        ): CarVoiceStream? {
            val service = microphoneServiceOf(profile) ?: return null
            val source = service.mediaSourceService

            // The head unit declares the format; CAR_MICROPHONE is a default, not
            // a promise (MicrophoneFormat's KDoc). A unit that states nothing gets
            // what every reference expects a car microphone to be.
            val format = if (source.hasAudioConfig()) {
                MicrophoneFormat.fromAdvertisement(source.audioConfig)
            } else {
                onStep("voice: the head unit advertised no microphone format; assuming a car microphone")
                MicrophoneFormat.CAR_MICROPHONE
            }

            if (format.bitsPerSample != BITS_PER_SAMPLE) {
                // Refused here rather than at the first Data message, which is
                // where MicrophoneChannel would otherwise throw. Nothing decodes
                // anything but 16-bit linear PCM and no reference implements
                // anything else, so this is a car we have never seen, not a bug.
                onStep(
                    "voice: the head unit advertised ${format.bitsPerSample}-bit microphone audio, " +
                        "which nothing here decodes; voice is disabled for this session"
                )
                return null
            }
            if (format.sampleRateHz != MicrophoneFormat.CAR_MICROPHONE.sampleRateHz) {
                // Loud on purpose: the bundled model is trained at 16 kHz and
                // this project ships no resampler, so recognition will be poor
                // rather than absent. Reporting it is what makes a log from a
                // real drive enough to diagnose that.
                onStep(
                    "voice: the head unit's microphone runs at ${format.sampleRateHz} Hz, not " +
                        "16000 Hz; the speech model is trained at 16 kHz and there is no resampler"
                )
            }

            val channel = MicrophoneChannel(connectionFor(service.id), service.id, format, onStep)
            val apps = installedApps(context)
            onStep("voice: ${apps.size} launchable app(s) in the command vocabulary")

            return CarVoiceStream(
                channel = channel,
                engine = CommandEngine(apps),
                openRecognizer = { voskRecognizer(modelDirectory, format.sampleRateHz, onStep) },
                onCommand = onCommand ?: defaultExecutor(context, onStep),
                onStep = onStep,
                appContext = context.applicationContext,
            )
        }

        /** The only sample width anything in this project decodes. */
        private const val BITS_PER_SAMPLE: Int = 16
    }
}

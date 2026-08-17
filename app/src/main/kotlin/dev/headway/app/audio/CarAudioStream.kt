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
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.os.SystemClock
import dev.headway.audio.CarAudioSource
import dev.headway.audio.CarAudioSourceException
import dev.headway.audio.PcmBuffer
import dev.headway.audio.PhoneAudioFocus
import dev.headway.protocol.channel.AudioChannel
import dev.headway.protocol.channel.AudioChannelException
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Everything between "the car opened its audio sink channels" and "the car plays
 * what Headway says".
 *
 * ## What travels here
 *
 * Headway's own audio — spoken replies from the voice pipeline and UI prompts —
 * and, by default, **third-party music**.
 *
 * That last part reverses CLAUDE.md, which says *"instruct/steer media audio
 * over the car's normal Bluetooth A2DP link, which coexists with the AAP
 * session"*. The coexistence premise is false on the target vehicle, and a
 * capture of real Android Auto against that same head unit says so plainly:
 * Gearhead logs `disabling A2dp route while in projection`, retries with
 * `A2DP playing while in projection. Trying disabling`, ends at
 * `ANDROID_AUTO_BLUETOOTH_A2DP_DISCONNECTED`, and streams a named third-party
 * player over the AAP media channel instead. The driver's report matches: with
 * Headway projecting, the car is silent.
 *
 * So media is driven here, tapped from the phone with [PhoneAudioCapture] — the
 * unprivileged spelling of the `REMOTE_SUBMIX` recording the same capture shows
 * Gearhead doing. The `mediaAudioOverAap` quirk still turns it off for a unit
 * that genuinely keeps A2DP alive. See ADR 0005.
 *
 * The telephony sink is still left alone, and that is not symmetry: the same
 * capture shows Gearhead *keeping* `STATE_HFP_CONNECTED` throughout. A phone
 * call already reaches the car over Bluetooth HFP, and duplicating it over AAP
 * would put the caller's voice in the cabin twice — and voice audio is not
 * capturable at any privilege level anyway.
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
    /**
     * The screen-capture grant, reused to tap playback.
     *
     * The *initial* one. A session very often starts without a grant -- the
     * driver has not answered the consent sheet yet, or Android 15 tore the last
     * one down at the lock screen -- and this used to be the end of the story:
     * [pumpMedia] returned on a null and nothing restarted it, so music never
     * reached the car for the rest of the drive however many times the driver
     * re-shared. A driver reported it as "audio won't play through my car unless
     * I am screen sharing". See [adoptProjection].
     */
    private val projection: MediaProjection? = null,
    /** Used only to ask whether anything is playing; see [pumpMedia]. */
    private val audioManager: AudioManager? = null,
    /**
     * Application context, used only to check RECORD_AUDIO before capturing.
     *
     * Optional so the tests that build this directly need not supply one; a
     * null is read as "cannot check", which errs towards trying rather than
     * towards refusing.
     */
    private val appContext: Context? = null,
    /** Channel id to advertised name, so the log stops guessing. */
    private val names: Map<Int, String> = emptyMap(),
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

    /**
     * Whether the media pump currently holds focus, as a field rather than a local.
     *
     * It has to be shared, because it is not only the pump that can lose it.
     * `PhoneAudioFocus` keeps one focus slot with no ownership or refcount, so a
     * spoken prompt's `withFocus` takes that slot and its `finally` releases it
     * -- sending an AAP RELEASE that cancels the *media* GAIN as well. With the
     * flag as a local in the pump loop it stayed true across that, the
     * `if (!holdingFocus)` guard never re-requested, and every buffer after the
     * first voice reply was streamed to a car that had switched back to its own
     * radio: music silently gone for the rest of the drive after one prompt.
     */
    @Volatile
    private var mediaFocusHeld: Boolean = false

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

    /** Longest single wait for the car to take a buffer, in ms. */
    private var longestSendMillis: Long = 0L

    /** Longest gap between finishing one buffer and starting the next, in ms. */
    private var idleGapMillis: Long = 0L

    /** When the last buffer finished being handed to the link. */
    private var lastSendEndedAt: Long = 0L

    /** Sends that took longer than [STALL_REPORT_MILLIS]. */
    private var stalls: Long = 0L

    /** Captured audio forwarded to the car. Diagnostics. */
    @Volatile
    var mediaBytesSent: Long = 0L
        private set

    /** Held so [stop] can close the tap even if the pump is cancelled mid-read. */
    @Volatile
    private var mediaCapture: PhoneAudioCapture? = null

    /**
     * The grant media capture is using, which can arrive and vanish mid-session.
     *
     * A flow rather than a field so [pumpMedia] can wait on it instead of
     * polling or giving up. `AudioPlaybackCaptureConfiguration` is built from a
     * `MediaProjection` and there is no unprivileged substitute, so no grant
     * genuinely means no music over AAP -- but "not yet" and "never" are
     * different answers and only one of them was being given.
     */
    private val liveProjection = MutableStateFlow(projection)

    /**
     * Points media capture at a new grant, or clears it when one is lost.
     *
     * Called by the service on both edges: `adoptProjectionGrant` when the
     * driver shares, `AppPaneHost.onGrantLost` when the platform takes it away.
     */
    fun adoptProjection(projection: MediaProjection?) {
        val had = liveProjection.value != null
        liveProjection.value = projection
        if (projection != null && !had) {
            onStep("media: a screen-sharing grant arrived; music can now reach the car")
        } else if (projection == null && had) {
            onStep("media: the screen-sharing grant went away, so music stops reaching the car")
        }
    }

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
        live.set(this)
        jobs += scope.launch { phoneFocus.relayPhoneFocusChanges() }
        jobs += scope.launch {
            bringUp(sessionId)
            pumpMedia()
        }
        return true
    }

    // --- media ----------------------------------------------------------------

    /**
     * Sends the phone's music to the car for as long as there is music.
     *
     * ## Why this is not simply "capture, forward, forever"
     *
     * Holding `AUDIO_FOCUS_GAIN` on the control channel is what makes the head
     * unit unmute the Android Auto source — verified against a real capture,
     * where the car answers `LOSS` before the request and `STATE_GAIN` after,
     * and Gearhead only then enables the stream. It is also what silences the
     * car's own radio. So focus has to be taken when there is something to play
     * and handed back when there is not, or Headway mutes the driver's radio for
     * the whole drive and plays digital silence over it.
     *
     * `AudioManager.isMusicActive` is the unprivileged answer to "is there
     * something to play". It is polled rather than observed because no callback
     * for it exists below the notification-listener grant, and the poll is
     * cheap.
     *
     * ## Why the read loop needs no clock
     *
     * [PhoneAudioCapture.read] blocks until the tap has a buffer, and the tap
     * fills at real time. So the loop below runs at exactly the rate the music
     * plays, and back-pressure from the car's acknowledgement window
     * (`MAX_UNACK: 16` on this head unit, not openauto's 1) simply slows the
     * reads. No pacing code, and no queue to drift.
     */
    /**
     * Whether the first [length] bytes of [buffer] are silence.
     *
     * 16-bit little-endian, which is the only format the media sink negotiates
     * (`MEDIA(5) ready: 48000 Hz 16-bit stereo` on the target vehicle) and what
     * `PhoneAudioCapture` is configured to produce. Reads every other byte --
     * the high half of each sample -- because the low half cannot carry a
     * meaningful amplitude on its own, and this runs on the thread pumping
     * music.
     */
    // A member only so the constant travels with it; the work is the top-level
    // function below, which is pure and has a unit test.
    private fun isSilence(buffer: ByteArray, length: Int): Boolean =
        isPcm16Silence(buffer, length, SILENCE_LEVEL)

    private suspend fun pumpMedia() {
        val channel = channelFor(AudioStreamType.AUDIO_STREAM_MEDIA) ?: return
        val format = channel.format ?: run {
            onStep("media: the channel started without a negotiated format; not sending music")
            return
        }
        // Outer loop: one pass per grant. A capture ends when the driver's grant
        // does -- Android 15 and later stop projection at every lock screen --
        // and the first version of this treated that as the end of media for
        // the session.
        var failures = 0
        while (currentCoroutineContext().isActive) {
            val projection = awaitProjection() ?: return
            val started = pumpMediaWith(channel, projection, format)
            // Somebody else has already moved on -- a new grant arrived, or the
            // platform's callback reported this one lost. Nothing to decide.
            if (liveProjection.value !== projection) {
                failures = 0
                continue
            }
            // This is the correction to a fix that was worse than the bug. The
            // grant is *still live*: the capture ended for its own reasons --
            // `AudioRecord` refusing to initialise, an audioserver restart
            // returning ERROR_DEAD_OBJECT, `streamPcm` throwing on a link
            // hiccup. Clearing it here, as the first version did unconditionally,
            // parked the pump forever on a grant nobody would ever re-issue and
            // told the driver to share a screen they were already sharing.
            failures = if (started) 0 else failures + 1
            if (failures >= MEDIA_CAPTURE_ATTEMPTS) {
                onStep(
                    "media: playback capture would not start $failures times running; giving up " +
                        "on this screen-sharing grant. Sharing again will start it over"
                )
                // compareAndSet, so a grant that arrived while this was deciding
                // is never clobbered.
                liveProjection.compareAndSet(projection, null)
                failures = 0
                continue
            }
            // Always a pause before going round, so a capture that fails
            // instantly and forever cannot spin the CPU for the drive.
            sleepBetweenCaptures()
        }
    }

    /**
     * Whether RECORD_AUDIO is granted, which playback capture also needs.
     *
     * Null-safe about the context: the stream is constructible in tests without
     * one, and a missing context should not be reported as a missing permission.
     */
    private fun hasRecordPermission(): Boolean {
        val context = appContext ?: return true
        return runCatching {
            context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrDefault(true)
    }

    /** A pause between capture attempts, overridable by tests. */
    private suspend fun sleepBetweenCaptures() {
        delay(MEDIA_RETRY_MILLIS)
    }

    /**
     * Waits for a screen-sharing grant, saying once why nothing is playing.
     *
     * The message is the point. Without a grant Headway cannot tap the phone's
     * audio at all -- `AudioPlaybackCaptureConfiguration` takes a
     * `MediaProjection` and there is no unprivileged alternative -- and the car
     * has already switched its source to Android Auto, so Bluetooth A2DP is not
     * being listened to either. Silence with no explanation is what the driver
     * actually experienced.
     */
    private suspend fun awaitProjection(): MediaProjection? {
        liveProjection.value?.let { return it }
        onStep(
            "media: no screen-sharing grant, so there is nothing to tap. Music reaches the car " +
                "over the AAP media channel, which is captured from the phone's own playback, " +
                "and Android only allows that capture through a screen-sharing grant -- so " +
                "share your screen from Headway's notification to hear music in the car"
        )
        return runCatching { liveProjection.first { it != null } }.getOrNull()
    }

    /** @return whether the capture actually started; see [pumpMedia]'s retry. */
    private suspend fun pumpMediaWith(
        channel: AudioChannel,
        projection: MediaProjection,
        format: PcmFormat,
    ): Boolean {
        // Checked before the grant is blamed. `AudioPlaybackCapture` records
        // through `AudioRecord`, so it needs RECORD_AUDIO as well as the
        // projection -- and without it `AudioRecord` simply refuses, which the
        // retry above would have reported as the screen-sharing grant being at
        // fault and told the driver to share their screen again. That remedy
        // cannot work, and following it three times is how the driver would
        // find that out.
        if (!hasRecordPermission()) {
            onStep(
                "media: the microphone permission is not granted, and playback capture records " +
                    "through AudioRecord -- so music cannot be sent. Grant Headway the " +
                    "microphone permission on the phone; sharing the screen again will not help"
            )
            return false
        }
        onStep(PhoneAudioCapture.describeCapturePolicy())
        val capture = PhoneAudioCapture(projection, format, onStep)
        if (!capture.start()) return false
        mediaCapture = capture

        val buffer = ByteArray(capture.bufferBytes)
        var idleSince = 0L
        try {
            while (currentCoroutineContext().isActive) {
                val read = capture.read(buffer)
                if (read <= 0) {
                    if (read < 0) break
                    continue
                }

                // The samples first, and `isMusicActive` only when they are
                // silent. Two things come out of that order.
                //
                // Correctness: `AudioManager.isMusicActive()` describes the
                // phone's music stream, not this capture, and it goes false
                // across a gapless track change, a buffering stall and an app
                // switching decoders. Three seconds of that used to release
                // focus and stop the stream, and the car needed a moment to
                // switch its source back when it returned -- which is the
                // "music cuts out for a few seconds every few minutes" a driver
                // reported. Audio that is arriving is proof that audio is
                // playing, and no framework flag can contradict it.
                //
                // Cost: it was a binder call for every buffer, twenty-five
                // times a second, on the thread pumping music. Now it is
                // consulted only when the buffer is silent, which is when there
                // is nothing else to do anyway.
                val quiet = isSilence(buffer, read)
                val playing = !quiet || PhoneAudioCapture.isMusicPlaying(audioManager)
                if (playing) {
                    idleSince = 0L
                    // Re-reads the shared flag every buffer, so focus taken away
                    // by a spoken prompt is noticed and taken back rather than
                    // assumed. See [mediaFocusHeld].
                    if (!mediaFocusHeld) {
                        // Same lock, same reason: taking focus mid-prompt would
                        // overwrite the prompt's own request in the single slot.
                        speaking.withLock {
                            if (!mediaFocusHeld) mediaFocusHeld = requestMediaFocus()
                        }
                    }
                } else if (mediaFocusHeld) {
                    // Not released on the first silent buffer: a track change is
                    // a second of silence, and giving the radio back and taking
                    // it again across every gap would make the car click.
                    val now = SystemClock.elapsedRealtime()
                    if (idleSince == 0L) idleSince = now
                    if (now - idleSince >= MEDIA_IDLE_RELEASE_MILLIS) {
                        // Under the same lock a prompt takes. `PhoneAudioFocus`
                        // keeps one slot, so releasing it here while a spoken
                        // reply is mid-render would send the car a RELEASE for
                        // the *prompt's* focus and un-duck the radio underneath
                        // it. `speaking` is the existing serialiser for exactly
                        // this resource; the pump had simply never taken it.
                        speaking.withLock {
                            releaseMediaFocus()
                            mediaFocusHeld = false
                        }
                    }
                }

                if (!mediaFocusHeld) continue
                val sent = channel.streamPcm(
                    if (read == buffer.size) buffer else buffer.copyOf(read)
                )
                buffersSent += sent
                mediaBytesSent += read
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            // The link dying mid-song is ordinary. Audio must never be the
            // component that decides the session is over.
            onStep("media stream ended: ${failed.message ?: failed::class.java.simpleName}")
        } finally {
            if (mediaFocusHeld) {
                runCatching { releaseMediaFocus() }
                mediaFocusHeld = false
            }
            runCatching { capture.close() }
            onStep(capture.describe())
        }
        return true
    }

    /** Takes focus for continuous playback, on both the phone and the car. */
    private suspend fun requestMediaFocus(): Boolean {
        val granted = runCatching { phoneFocus.requestForMedia() }.getOrDefault(false)
        onStep(
            if (granted) "media: focus taken; the car should switch to Android Auto audio"
            else "media: the phone refused audio focus; sending anyway"
        )
        // Sent regardless. The phone's own focus stack is not the car's, and the
        // car has been observed to grant AAP focus that Android had declined.
        return true
    }

    private suspend fun releaseMediaFocus() {
        runCatching { phoneFocus.release() }
        onStep("media: focus released after ${MEDIA_IDLE_RELEASE_MILLIS / 1000} s of silence")
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
        // Closed here as well as in the pump's finally: a cancelled coroutine
        // parked inside AudioRecord.read does not run its finally until the read
        // returns, and a live tap holds a microphone-class resource.
        runCatching { mediaCapture?.close() }
        mediaCapture = null
        // Compare-and-set, for the same reason `CarVideoStream` does it: a new
        // session may already have published itself before this one's teardown
        // arrives, and unpublishing it would leave a live stream unreachable.
        live.compareAndSet(this, null)
    }

    /**
     * A channel's advertised name, falling back to Headway's constant table.
     *
     * See [nameOf]: the table is Headway's own numbering and a real car assigns
     * its own, so using it as a name misreports every channel on this vehicle.
     */
    private fun nameFor(channelId: Int): String =
        names[channelId] ?: ChannelId.describe(channelId)

    /** What opened, what did not, and what the car has said about focus. */
    fun describe(): String {
        val parts = mutableListOf<String>()
        parts += if (opened.isEmpty()) {
            "no channel open"
        } else {
            // Named from the advertisement, not from ChannelId: this car assigns
            // its guidance sink the id Headway's own table calls
            // MEDIA_SINK_VIDEO, and the log said exactly that for a whole drive.
            opened.joinToString {
                // The negotiated format either way. A deferred sink has no
                // `format` until its first use -- `sendStart` is what sets it --
                // and printing null for a channel that is set up and waiting
                // would read as a failure rather than as the resting state.
                val shape = it.format
                    ?: planned[it.channelId]?.let { index -> it.advertisedFormats.getOrNull(index) }
                val state = if (it.sessionId == null) " (idle)" else ""
                "${nameFor(it.channelId)} at $shape$state"
            }
        }
        parts += "$promptsPlayed prompt(s), $buffersSent buffer(s), $underflows underflow(s) seen"
        if (mediaBytesSent > 0 || channelFor(AudioStreamType.AUDIO_STREAM_MEDIA) != null) {
            parts += "music ${mediaBytesSent / 1024} KiB sent"
            // Always stated once music has flowed. These are the numbers that
            // answer "why did it cut out", and a summary that only mentions
            // them when they are bad cannot show that they were fine.
            if (mediaBytesSent > 0) {
                parts += "worst wait for the car ${longestSendMillis} ms, " +
                    "worst gap in capture ${idleGapMillis} ms, $stalls stall(s)"
            }
        }
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

    /**
     * Whether this sink is started as soon as it is set up.
     *
     * ## The bug this exists to not have
     *
     * `Start` is not "I might use this channel", it is "audio is coming". A
     * drive log has Headway sending it on the *guidance* sink at connect and
     * then sending `0 prompt(s)` for the rest of the session -- so the head
     * unit spent the whole drive believing a voice stream was live. The driver
     * reported that the car's volume knob would only adjust voice volume, and
     * that turning it up changed nothing they could hear, which is exactly what
     * an empty-but-active guidance stream looks like from the cabin: guidance
     * outranks media in a car's audio policy, so the knob binds to it, and there
     * is nothing on it to make louder.
     *
     * Media is the exception and stays eager. It is the stream that carries
     * music the moment a capture grant exists, its `Start` is what negotiates
     * the format the capture is resampled into, and it is working -- so it
     * keeps the behaviour it has.
     *
     * Deferred channels are still set up, so [canSpeak] and the negotiated
     * format are known from bring-up. Only the announcement waits.
     */
    private fun startsEagerly(stream: AudioStreamType): Boolean =
        stream == AudioStreamType.AUDIO_STREAM_MEDIA

    /**
     * Sends `Start` for a deferred sink the first time something is put on it.
     *
     * Idempotent by [AudioChannel.sessionId], which `sendStart` sets. Started
     * channels are left started rather than stopped after each prompt: a car
     * that has been told about a stream carrying real audio is in a true state,
     * and Start/Stop per prompt would be chatter on a link already carrying
     * video.
     */
    private suspend fun ensureStarted(channel: AudioChannel) {
        if (channel.sessionId != null) return
        val index = planned[channel.channelId] ?: return
        channel.sendStart(sessionId = startedSessionId, configurationIndex = index)
        onStep("${nameFor(channel.channelId)} started on first use: ${channel.format}")
    }

    /** Configuration index chosen at Setup, by channel id, for [ensureStarted]. */
    private val planned = mutableMapOf<Int, Int>()

    /** The session id [bringUp] used, so a deferred Start matches the eager ones. */
    private var startedSessionId: Int = DEFAULT_SESSION_ID

    private suspend fun bringUp(sessionId: Int) {
        startedSessionId = sessionId
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
        val name = nameFor(channel.channelId)
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

            planned[channel.channelId] = index
            // Only the media sink is started here. See `startLazily` for why the
            // others wait, and `ensureStarted` for what wakes them.
            if (startsEagerly(channel.stream)) {
                channel.sendStart(sessionId = sessionId, configurationIndex = index)
                onStep(
                    "$name ready: ${channel.format}, window ${config.maxUnacked ?: 1}, " +
                        "session $sessionId"
                )
            } else {
                onStep(
                    "$name negotiated ${channel.advertisedFormats.getOrNull(index)}, window " +
                        "${config.maxUnacked ?: 1}; not started, because nothing is being sent " +
                        "on it yet"
                )
            }
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
                "${nameFor(it.channelId)} has no negotiated format"
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

        // The announcement this channel did not make at bring-up. Before focus,
        // because the car sizes its ducking from the streams it believes are
        // live, and because `render` reads the format `Start` selects.
        //
        // A link that died between bring-up and this prompt costs the prompt,
        // not the session -- the demultiplexer notices the same close and the
        // supervisor reconnects. `AudioChannelException` is let through for the
        // reason given at the end of this function: it means a sequencing bug
        // here, and silence would hide it.
        val announced = runCatching { ensureStarted(channel) }.exceptionOrNull()
        if (announced != null) {
            if (announced is AudioChannelException || announced is CancellationException) {
                throw announced
            }
            onStep(
                "could not start ${nameFor(channel.channelId)} for this prompt: " +
                    "${announced.message}"
            )
            return@withLock false
        }

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
            // The release above is not only this prompt's. `PhoneAudioFocus`
            // holds one slot, so it also cancelled any GAIN the media pump was
            // holding -- on the phone and, via the control channel, on the car.
            // Saying so lets the pump take it back on its next buffer instead of
            // streaming into a head unit that has switched back to its radio.
            if (mediaFocusHeld) {
                mediaFocusHeld = false
                onStep("media: the prompt's focus release also dropped music focus; retaking it")
            }
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
        // And the media pump's own flag, which is the other half of the fix that
        // made it shared. A prompt taking focus away was handled; the *car*
        // taking it away was not -- and that is the more ordinary event, since
        // a head unit revokes focus whenever its own source wants the speakers.
        // Left set, the pump goes on streaming PCM into a car playing its radio
        // and never asks for focus back, because its "do I hold it" test is this
        // flag.
        if (state.mustStop && mediaFocusHeld) {
            mediaFocusHeld = false
            onStep("media: the car took focus back, so music focus will be re-requested")
        }
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
         * The live stream, so a screen-sharing grant can find it mid-session.
         *
         * Null between sessions. Media capture needs a `MediaProjection` and the
         * grant usually arrives *after* the session is up -- a link that comes
         * up on its own has no grant to travel with it -- so the service needs
         * somewhere to deliver one. See [adoptProjection].
         */
        private val live = java.util.concurrent.atomic.AtomicReference<CarAudioStream?>(null)

        /** The live stream, or null between sessions. */
        val current: CarAudioStream? get() = live.get()

        /**
         * How long to wait before trying a failed playback capture again.
         *
         * Long enough that a capture failing instantly cannot spin, short
         * enough that a transient refusal -- an audioserver restart is the
         * realistic one -- costs a few seconds of music rather than the drive.
         */
        const val MEDIA_RETRY_MILLIS: Long = 3_000

        /**
         * Consecutive failed capture starts before the grant is set aside.
         *
         * Not one: `AudioRecord` refusing once is ordinary. Not unbounded
         * either, because a grant that can never be captured from should stop
         * being retried and let the driver re-share.
         */
        const val MEDIA_CAPTURE_ATTEMPTS: Int = 3

        /**
         * A send this slow is audible, so it is worth a line of its own.
         *
         * 250 ms is roughly six buffers at the 40 ms this capture produces, and
         * well past anything the acknowledgement window absorbs normally.
         */
        private const val STALL_REPORT_MILLIS: Long = 250L

        /** Stalls reported individually before the summary count takes over. */
        private const val MAX_REPORTED_STALLS: Long = 12L
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
         * Silence before Headway gives the car's audio back.
         *
         * Holding focus mutes the car's own radio, so it cannot be held
         * indefinitely — but releasing it on the first silent buffer would
         * hand the radio back and take it again across every track change, and
         * a head unit switching source twice a song is worse than a short
         * pause. Three seconds is longer than any gap between tracks and short
         * enough that a driver who stops the music gets their radio back before
         * they wonder why it is quiet.
         */
        /**
         * How long the capture must be silent before the car gets its radio back.
         *
         * Ten seconds, not three. Releasing focus is audible at both ends --
         * the car switches source away and back -- so the window has to be
         * longer than any gap that is not really a stop. Three seconds is
         * inside the range of a podcast's pause, a long track gap and an app
         * reloading its decoder, and a driver reported the resulting dropouts.
         * Nothing is lost by waiting: silence is streamed to the car during the
         * window, which is what it was already doing between tracks.
         */
        const val MEDIA_IDLE_RELEASE_MILLIS: Long = 10_000L

        /**
         * Amplitude at or below which a 16-bit sample counts as silence.
         *
         * Not zero. A capture of "nothing playing" is not digitally silent --
         * resampling and mixing leave a least-significant-bit of noise -- and a
         * zero test would hold the car's audio focus for the whole drive.
         */
        private const val SILENCE_LEVEL: Int = 64

        /**
         * The streams Headway always drives, in bring-up order.
         *
         * Telephony is absent because it belongs on Bluetooth HFP, and that is
         * not a guess: a capture of real Android Auto against a 2021 Chevrolet
         * Infotainment 3 unit shows Gearhead holding `STATE_HFP_CONNECTED`
         * throughout while it explicitly tears down A2DP.
         *
         * Media is conditional rather than absent — see [streamsFor].
         */
        val DRIVEN_STREAMS: List<AudioStreamType> = listOf(
            AudioStreamType.AUDIO_STREAM_GUIDANCE,
            AudioStreamType.AUDIO_STREAM_SYSTEM_AUDIO,
        )

        /**
         * The streams to drive given a routing choice.
         *
         * Media comes first when it is driven at all. It is the stream with a
         * continuous producer behind it, so a head unit that only honours the
         * first `Start` it sees gets the one that matters, and a driver whose
         * session dies during bring-up loses a prompt rather than their music.
         */
        fun streamsFor(mediaOverAap: Boolean): List<AudioStreamType> =
            if (mediaOverAap) listOf(AudioStreamType.AUDIO_STREAM_MEDIA) + DRIVEN_STREAMS
            else DRIVEN_STREAMS

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
            mediaOverAap: Boolean = true,
            projection: MediaProjection? = null,
            onStep: (String) -> Unit = {},
        ): CarAudioStream? {
            val advertised = audioSinksOf(profile)

            // Advertised sinks Headway could not even consider, which used to
            // appear in no list at all. A head unit that offers a system-audio
            // sink with an empty audio_configs is indistinguishable in the log
            // from one that offers no system-audio sink -- and the two have
            // different fixes.
            val rejected = profile.services.filter {
                it.hasMediaSinkService() &&
                    it.mediaSinkService.availableType == MediaCodecType.MEDIA_CODEC_AUDIO_PCM &&
                    advertised.none { usable -> usable.id == it.id }
            }
            if (rejected.isNotEmpty()) {
                onStep(
                    "audio: ignoring " + rejected.joinToString { service ->
                        val sink = service.mediaSinkService
                        "channel ${service.id} (" +
                            (if (!sink.hasAudioType()) "no stream type" else sink.audioType.name) +
                            ", ${sink.audioConfigsCount} format(s))"
                    }
                )
            }

            if (advertised.isEmpty()) return null

            // Not `mediaOverAap && projection != null`. The channel has to be
            // opened before a grant exists, because the grant usually arrives
            // *after* the session -- a link that comes up on its own has none
            // travelling with it, and the driver answers the consent
            // notification later.
            //
            // Making the channel conditional on the grant is what made the fix
            // for that inert: with no media channel, `pumpMedia` returns at its
            // `channelFor(...) ?: return` and never reaches the code that waits
            // for a grant, so a driver who shared afterwards still got silence
            // for the whole drive. An open channel with nothing on it costs the
            // car nothing; it is fed only when there is something to feed it.
            val wanted = streamsFor(mediaOverAap)
            if (mediaOverAap && projection == null) {
                onStep(
                    "audio: media is routed over AAP and there is no screen capture grant yet, " +
                        "so the media channel opens empty and starts carrying music the moment " +
                        "screen sharing is allowed"
                )
            }
            val driven = wanted.mapNotNull { stream ->
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
                    // The one gate that decides whether music can be sent at
                    // all: AudioChannel.transmits is false for MEDIA on the
                    // A2DP route, and requireTransmitting throws out of
                    // sendSetup. Passed explicitly so the decision is visible
                    // where it is made.
                    route = if (service.mediaSinkService.audioType ==
                        AudioStreamType.AUDIO_STREAM_MEDIA
                    ) {
                        MediaAudioRoute.AAP_MEDIA_CHANNEL
                    } else {
                        MediaAudioRoute.BLUETOOTH_A2DP
                    },
                    onStep = onStep,
                )
            }

            return CarAudioStream(
                sinks = channels,
                untouched = untouched,
                focus = focus,
                phoneFocus = PhoneAudioFocus(context, focus, onStep),
                source = CarAudioSource(context, onStep = onStep),
                projection = projection,
                audioManager = context.getSystemService(AudioManager::class.java),
                appContext = context.applicationContext,
                names = profile.services.associate { it.id to nameOf(it) },
                onStep = onStep,
            )
        }

        /**
         * A channel's name taken from what the head unit advertised.
         *
         * [ChannelId.describe] is Headway's own numbering table, and a real car
         * assigns its own ids — which is how a guidance sink on this Chevrolet
         * came to be logged as `MEDIA_SINK_VIDEO`. The advertisement is the
         * authority.
         */
        private fun nameOf(service: ServiceOuterClass.Service): String {
            val sink = service.mediaSinkService
            if (!service.hasMediaSinkService() || !sink.hasAudioType()) {
                return "${ChannelId.describe(service.id)}?id=${service.id}"
            }
            return sink.audioType.name.removePrefix("AUDIO_STREAM_") + "(${service.id})"
        }
    }
}

/**
 * Whether the first [length] bytes of [buffer] are 16-bit PCM silence.
 *
 * Top-level and pure so it can be tested without an audio device: everything
 * else on the media path needs a live capture, a car and a link.
 *
 * Little-endian, which is the only format the media sink negotiates -- the
 * target vehicle reports `MEDIA(5) ready: 48000 Hz 16-bit stereo` -- and what
 * `PhoneAudioCapture` is configured to produce. A trailing odd byte cannot form
 * a sample and is ignored rather than guessed at.
 *
 * @param level amplitude at or below which a sample counts as silent. Not zero:
 *   a capture of "nothing playing" carries a least-significant-bit of noise
 *   from resampling and mixing, and an exact-zero test would hold the car's
 *   audio focus for a whole drive.
 */
internal fun isPcm16Silence(buffer: ByteArray, length: Int, level: Int): Boolean {
    val usable = minOf(length, buffer.size)
    var index = 0
    while (index + 1 < usable) {
        val sample = (buffer[index].toInt() and 0xff) or (buffer[index + 1].toInt() shl 8)
        if (sample > level || sample < -level) return false
        index += 2
    }
    return true
}

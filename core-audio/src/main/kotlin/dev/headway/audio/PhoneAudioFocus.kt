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

package dev.headway.audio

import aap_protobuf.service.control.message.AudioFocusRequestTypeOuterClass.AudioFocusRequestType
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import dev.headway.protocol.channel.AudioFocus
import dev.headway.protocol.channel.AudioFocusState
import kotlinx.coroutines.channels.Channel

/** Raised when the phone's audio focus cannot be bridged to the car's. */
class PhoneAudioFocusException(message: String) : RuntimeException(message)

/**
 * Keeps Android's audio focus and AAP's audio focus telling the same story.
 *
 * Headway sits between two arbiters that have never heard of each other. On the
 * phone, `AudioManager` decides which app may make noise. In the car, the head
 * unit decides whether the phone may — two control messages,
 * `MESSAGE_AUDIO_FOCUS_REQUEST` (18) and `MESSAGE_AUDIO_FOCUS_NOTIFICATION`
 * (19), transcribed in [AudioFocus] from
 * `aasdk/protobuf/aap_protobuf/service/control/ControlMessageType.proto`
 * L23-L24. Neither one observes the other, so without a bridge the car ducks its
 * radio for a prompt that a phone call has already silenced, or keeps ducking
 * long after Headway lost the right to speak.
 *
 * ## Why Headway asks Android for focus at all
 *
 * [CarAudioSource] never opens an `AudioTrack` — Headway's speech goes to the
 * car over AAP, not to the phone's mixer — so on the face of it Headway is not
 * competing for the phone's output and has no business requesting focus. It
 * requests anyway, because of the routing CLAUDE.md settles: third-party media
 * audio reaches the car over **Bluetooth A2DP**, not over the AAP media channel
 * (see [dev.headway.protocol.channel.MediaAudioRoute]). The music app and
 * Headway therefore share the car's speakers by two different paths, and the
 * only lever Headway has over the music app is Android's focus arbiter. Taking
 * transient focus is what makes the music duck while a spoken reply plays.
 *
 * ## The two directions
 *
 * ```text
 *  another phone app takes focus
 *      AudioManager -> onAudioFocusChange(LOSS) -> AudioFocusRequest{RELEASE} 18 -> car
 *
 *  the driver presses the car's radio button
 *      car -> 19 AudioFocusNotification{LOSS} -> abandonAudioFocusRequest()
 * ```
 *
 * Acquisition goes phone first, then car: if `AudioManager` refuses, the car is
 * never asked, so the radio is never ducked for a prompt that will not play.
 * Release goes in the mirror order, car first — the last thing acquired is the
 * first thing given back.
 *
 * ## What is inference
 *
 * The mapping from an Android focus change to an AAP request type, and from an
 * AAP state back to an Android action, is **policy, not protocol**. No reference
 * implements this bridge: aasdk and openauto are head units, and AACS's phone
 * side does not run on Android. The particular choices below are argued in place
 * and should be the first thing revisited when a real head unit misbehaves.
 *
 * ## Threading
 *
 * `AudioManager` delivers focus changes on a `Handler` thread, and relaying one
 * means sending an AAP message, which suspends. Changes are therefore queued and
 * drained by [relayPhoneFocusChanges], which the session is expected to launch
 * as its own coroutine. Nothing here launches anything.
 */
class PhoneAudioFocus(
    context: Context,
    /** The control-channel half of the exchange; see [AudioFocus]. */
    private val carFocus: AudioFocus,
    /** Narrates each step; wired to the debug log export in the app. */
    private val onStep: (String) -> Unit = {},
) : AutoCloseable {

    /** What `AudioManager` last said about Headway's standing on the phone. */
    enum class PhoneFocus {
        /** Nothing requested, or the request was abandoned. */
        NONE,

        /** Granted and held. */
        HELD,

        /** Held, but another app asked to be heard over Headway. */
        DUCKED,

        /** Lost with an expectation of getting it back — a call, a navigation prompt. */
        LOST_TRANSIENT,

        /** Lost outright; Headway must ask again before it may speak. */
        LOST,
    }

    private val audioManager: AudioManager =
        context.applicationContext.getSystemService(AudioManager::class.java)
            ?: throw PhoneAudioFocusException("this device has no AudioManager")

    /**
     * Unbounded rather than conflated: the order of LOSS_TRANSIENT then GAIN is
     * the whole meaning of the pair, and dropping the first would leave the car
     * holding a release Headway never re-requested.
     */
    private val changes = Channel<Int>(Channel.UNLIMITED)

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        changes.trySend(change)
    }

    private var held: AudioFocusRequest? = null

    /**
     * The AAP request type behind the currently held focus, remembered so that
     * regaining phone focus can re-ask the car for the same thing rather than
     * silently downgrading a media grant to a prompt.
     */
    private var lastCarRequest: AudioFocusRequestType? = null

    @Volatile
    var phoneFocus: PhoneFocus = PhoneFocus.NONE
        private set

    /** The head unit's latest word, as [AudioFocus] parsed it. */
    val carFocusState: AudioFocusState? get() = carFocus.state

    // --- acquiring ------------------------------------------------------------

    /**
     * Takes focus for the length of one spoken reply or navigation prompt.
     *
     * `GAIN_TRANSIENT_MAY_DUCK` on both sides by default: the music app lowers
     * its volume rather than stopping, and openauto answers any non-`RELEASE`
     * request with a plain `GAIN` anyway
     * (`openauto/src/autoapp/Service/AndroidAutoEntity.cpp` L236-L240), so the
     * distinction costs nothing where it is not honoured.
     *
     * @return false if `AudioManager` refused, in which case the car is not
     *   asked at all.
     */
    suspend fun requestForPrompt(mayDuck: Boolean = true): Boolean = acquire(
        androidGain = if (mayDuck) {
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        } else {
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        },
        carType = if (mayDuck) {
            AudioFocusRequestType.AUDIO_FOCUS_GAIN_TRANSIENT_MAY_DUCK
        } else {
            AudioFocusRequestType.AUDIO_FOCUS_GAIN_TRANSIENT
        },
        // USAGE_ASSISTANT with CONTENT_TYPE_SPEECH is what a spoken reply is;
        // it is also the usage other apps' ducking rules are tuned for.
        usage = AudioAttributes.USAGE_ASSISTANT,
        contentType = AudioAttributes.CONTENT_TYPE_SPEECH,
    )

    /**
     * Takes focus for media Headway itself is streaming over the AAP media
     * channel — the settings toggle in
     * [dev.headway.protocol.channel.MediaAudioRoute], not the default A2DP path.
     */
    suspend fun requestForMedia(): Boolean = acquire(
        androidGain = AudioManager.AUDIOFOCUS_GAIN,
        carType = AudioFocusRequestType.AUDIO_FOCUS_GAIN,
        usage = AudioAttributes.USAGE_MEDIA,
        contentType = AudioAttributes.CONTENT_TYPE_MUSIC,
    )

    private suspend fun acquire(
        androidGain: Int,
        carType: AudioFocusRequestType,
        usage: Int,
        contentType: Int,
    ): Boolean {
        val request = AudioFocusRequest.Builder(androidGain)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(contentType)
                    .build()
            )
            .setOnAudioFocusChangeListener(listener)
            // Headway has nothing playing locally to pause, so a duck request
            // needs no cooperation from it; see onPhoneChange.
            .setWillPauseWhenDucked(false)
            .build()

        // Not setAcceptsDelayedFocusGain: a delayed grant would arrive after the
        // prompt it was for had already been rendered and dropped. The result is
        // therefore only ever GRANTED or FAILED.
        val result = audioManager.requestAudioFocus(request)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            onStep("phone audio focus refused (result $result); the car was not asked")
            return false
        }

        held = request
        lastCarRequest = carType
        phoneFocus = PhoneFocus.HELD
        carFocus.request(carType)
        onStep("phone focus held; asked the car for ${carType.name}")
        return true
    }

    // --- releasing ------------------------------------------------------------

    /**
     * Gives focus back on both sides.
     *
     * The car is released first so the head unit can un-duck its own source
     * before the phone's apps are let loose again — the mirror of [acquire]'s
     * order. Safe to call when nothing is held.
     */
    suspend fun release() {
        if (lastCarRequest != null) {
            carFocus.release()
            lastCarRequest = null
        }
        abandonPhoneFocus()
        onStep("audio focus released on both sides")
    }

    private fun abandonPhoneFocus() {
        held?.let { audioManager.abandonAudioFocusRequest(it) }
        held = null
        phoneFocus = PhoneFocus.NONE
    }

    // --- phone to car ---------------------------------------------------------

    /**
     * Drains queued `AudioManager` focus changes and relays each to the car.
     * Suspends forever; the session runs it as its own coroutine and cancels it
     * with the session.
     */
    suspend fun relayPhoneFocusChanges() {
        for (change in changes) {
            onPhoneChange(change)
        }
    }

    /**
     * Applies one `AudioManager` focus change. Public so a test — or a session
     * that prefers to own its own dispatch — can drive it directly.
     */
    suspend fun onPhoneChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                phoneFocus = PhoneFocus.HELD
                // Re-asking is not redundant: the release sent on the way down
                // is answered by the head unit with LOSS, and openauto only
                // returns to GAIN when the phone asks again
                // (openauto/src/autoapp/Service/AndroidAutoEntity.cpp L236-L240).
                lastCarRequest?.let {
                    carFocus.request(it)
                    onStep("phone focus regained; re-asked the car for ${it.name}")
                }
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                // The system has already taken it; abandoning as well clears the
                // listener registration so a stale one cannot fire later.
                abandonPhoneFocus()
                phoneFocus = PhoneFocus.LOST
                lastCarRequest = null
                carFocus.release()
                onStep("phone focus lost; released the car's")
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                phoneFocus = PhoneFocus.LOST_TRANSIENT
                // lastCarRequest is kept: this is the case the AUDIOFOCUS_GAIN
                // branch re-requests from.
                carFocus.release()
                onStep("phone focus lost transiently; released the car's")
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Nothing is relayed. Ducking is an instruction to lower a local
                // output level, and Headway has no local output to lower — its
                // samples go straight onto an AAP channel the phone's mixer
                // never sees. Telling the car to release here would silence
                // Headway completely for what Android meant as "play quieter",
                // which is strictly worse than ignoring it.
                phoneFocus = PhoneFocus.DUCKED
                onStep("phone focus ducked; nothing to relay")
            }

            else -> onStep("unhandled phone audio focus change: $change")
        }
    }

    // --- car to phone ---------------------------------------------------------

    /**
     * Applies a focus state the head unit reported.
     *
     * Push rather than pull: [AudioFocus] reads its connection directly, and the
     * control channel carries far more than focus, so whichever component owns
     * the control channel's demultiplexer view feeds notifications in here.
     *
     * The car revoking focus means Headway's audio has nowhere to go, so the
     * phone-side request is abandoned — continuing to hold other apps quiet for
     * a prompt the car will not play is the one clearly wrong outcome. This is
     * policy; nothing on the wire prescribes it.
     */
    fun onCarFocus(state: AudioFocusState) {
        when {
            state.mustStop -> {
                abandonPhoneFocus()
                lastCarRequest = null
                onStep("car revoked focus (${state.state.name}); abandoned the phone's")
            }

            state.mustPause -> {
                abandonPhoneFocus()
                // The request type is kept so a later resume asks for the same
                // thing; the car said it intends to hand focus back.
                onStep("car paused focus (${state.state.name}); abandoned the phone's")
            }

            else -> onStep("car focus: ${state.state.name}")
        }
    }

    /** Abandons any held focus and stops queueing changes. Does not touch the car. */
    override fun close() {
        abandonPhoneFocus()
        lastCarRequest = null
        changes.close()
    }
}

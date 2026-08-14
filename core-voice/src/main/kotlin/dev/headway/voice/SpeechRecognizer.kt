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

package dev.headway.voice

/**
 * Turns 16 kHz mono 16-bit PCM into text, entirely on the device.
 *
 * The car microphone streams exactly that format over the AAP AV-input channel,
 * so no resampling sits between the wire and the recogniser.
 *
 * CLAUDE.md requires the engine to be pluggable — Vosk is the default, with a
 * whisper.cpp backend as a build flavour — which is why this interface exists
 * rather than the pipeline calling Vosk directly.
 *
 * Implementations are stateful and single-session: feed one utterance, take the
 * result, then [reset] before the next.
 */
interface SpeechRecognizer : AutoCloseable {

    /** Sample rate the recogniser expects. The car mic supplies 16 kHz. */
    val sampleRateHz: Int

    /**
     * Feeds a chunk of PCM.
     *
     * @return a stable partial transcript if the recogniser considers the
     *   utterance complete at this point, otherwise null. Callers may ignore
     *   this and just use [finish].
     */
    fun accept(pcm: ByteArray, length: Int = pcm.size): String?

    /** Ends the utterance and returns the final transcript, lowercased and trimmed. */
    fun finish(): String

    /** Discards utterance state so the next [accept] starts fresh. */
    fun reset()
}

/**
 * What the user asked for.
 *
 * Deliberately a closed set rather than free-form intent: CLAUDE.md calls for a
 * deterministic, grammar-based engine with no LLM dependency, so the vocabulary
 * is fixed and every branch is testable.
 */
sealed interface VoiceCommand {

    /** Launch an app. [query] is the spoken name, already fuzzy-matched to [packageName] when resolved. */
    data class LaunchApp(val query: String, val packageName: String?) : VoiceCommand

    data class Media(val action: MediaAction) : VoiceCommand

    data class Volume(val direction: VolumeDirection, val steps: Int = 1) : VoiceCommand

    /** Return to Headway's own launcher surface on the car screen. */
    data object GoHome : VoiceCommand

    /** Type [text] into whatever has focus. */
    data class Search(val text: String) : VoiceCommand

    /**
     * Start navigation to [destination] in the driver's map app.
     *
     * Distinct from [Search] because it goes somewhere else entirely: a search
     * types into the foreground app, whereas this hands a `geo:` link to a map
     * app and gives it the car screen. Distinct from [LaunchApp] because the
     * destination is the point — "navigate to the airport" is not a request to
     * open a map and then find the airport by hand, which is the one thing a
     * driver must not be doing.
     */
    data class Navigate(val destination: String) : VoiceCommand

    /** Recognised speech that matched no rule. [transcript] is kept for the debug log. */
    data class Unrecognised(val transcript: String) : VoiceCommand
}

enum class MediaAction { PLAY, PAUSE, PLAY_PAUSE, NEXT, PREVIOUS, STOP }

enum class VolumeDirection { UP, DOWN, MUTE }

/** An installed app the command engine can launch. */
data class InstalledApp(val packageName: String, val label: String)

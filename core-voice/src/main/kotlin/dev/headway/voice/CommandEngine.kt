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

import kotlin.math.min

/**
 * Maps a transcript to a [VoiceCommand] using a fixed grammar.
 *
 * Deterministic and rule-based per CLAUDE.md — no model, no network, no
 * ranking heuristics that change between runs.
 *
 * ## Why the matching is fuzzy
 *
 * Small speech models mis-hear short function words constantly. Measured on this
 * project's own fixtures: the same phrase synthesised by espeak-ng transcribes
 * as *"and calculator"* while a neural TTS of the same words gives
 * *"open calculator"*. The word that carries the meaning — the app name —
 * survived in both. A grammar that required a literal leading "open" would have
 * rejected a request the user made perfectly clearly.
 *
 * So the engine matches on the *content* words and treats the leading verb as a
 * hint rather than a requirement, and app names are matched by edit distance
 * rather than equality.
 */
class CommandEngine(
    /** Installed apps to match launch requests against. Supplied by Android. */
    private val installedApps: List<InstalledApp> = emptyList(),
    /**
     * Maximum normalised edit distance for an app-name match, 0.0 (exact) to
     * 1.0 (anything). 0.34 accepts roughly one wrong character in three, which
     * covers "calculater"/"calc you later" style mis-hearings without matching
     * unrelated apps.
     */
    private val appMatchThreshold: Double = 0.34,
) {

    fun parse(rawTranscript: String): VoiceCommand {
        val transcript = normalise(rawTranscript)
        if (transcript.isEmpty()) return VoiceCommand.Unrecognised(rawTranscript)
        val words = transcript.split(' ').filter { it.isNotEmpty() }

        // Order matters: the most specific rules first, so "go home" is not
        // swallowed by a launch rule matching an app called "Home".
        matchGoHome(words)?.let { return it }
        matchVolume(words)?.let { return it }
        matchMedia(words)?.let { return it }
        // Before search, because "find the nearest petrol station" is a
        // navigation request and "find" is also a search verb. Navigation is
        // the more specific reading and the one that is dangerous to get wrong:
        // typing a destination into whatever happens to be on screen is a
        // driver looking at a phone.
        matchNavigate(words)?.let { return it }
        matchSearch(words)?.let { return it }
        matchLaunch(words)?.let { return it }

        return VoiceCommand.Unrecognised(rawTranscript)
    }

    // --- rules --------------------------------------------------------------

    private fun matchGoHome(words: List<String>): VoiceCommand? {
        val joined = words.joinToString(" ")
        return if (joined in GO_HOME_PHRASES) VoiceCommand.GoHome else null
    }

    private fun matchVolume(words: List<String>): VoiceCommand? {
        if (words.firstOrNull() !in VOLUME_NOUNS && words.getOrNull(1) !in VOLUME_NOUNS) {
            if (words.firstOrNull() !in MUTE_WORDS) return null
        }
        if (words.firstOrNull() in MUTE_WORDS) {
            return VoiceCommand.Volume(VolumeDirection.MUTE)
        }
        val direction = when {
            words.any { it in UP_WORDS } -> VolumeDirection.UP
            words.any { it in DOWN_WORDS } -> VolumeDirection.DOWN
            words.any { it in MUTE_WORDS } -> VolumeDirection.MUTE
            else -> return null
        }
        // "volume up two" -> two steps. Spoken digits only; the model emits
        // number words, not digits.
        val steps = words.firstNotNullOfOrNull { NUMBER_WORDS[it] } ?: 1
        return VoiceCommand.Volume(direction, steps)
    }

    private fun matchMedia(words: List<String>): VoiceCommand? {
        val joined = words.joinToString(" ")
        for ((phrase, action) in MEDIA_PHRASES) {
            if (joined == phrase || joined.startsWith("$phrase ") || joined.endsWith(" $phrase")) {
                return VoiceCommand.Media(action)
            }
        }
        return null
    }

    /**
     * "navigate to X", "drive to X", "take me to X", "directions to X".
     *
     * The verb *is* required here, unlike a launch. A bare place name is far
     * more likely to be an app the driver wants opened than a destination they
     * want a route to, and starting navigation by accident hands the car screen
     * to a map app in the middle of something else.
     *
     * "find the nearest X" is included because it is how people actually ask,
     * and because `geo:0,0?q=` is a search query rather than a coordinate — the
     * map app does the finding.
     */
    private fun matchNavigate(words: List<String>): VoiceCommand? {
        val joined = words.joinToString(" ")
        for (verb in NAVIGATE_VERBS) {
            if (!joined.startsWith("$verb ")) continue
            // Whole words, not string prefixes. Stripping "to " off "to"
            // leaves "to", so "navigate to" with nothing after it used to
            // produce a route to a place called "to" -- and the map app would
            // dutifully search for one.
            val rest = joined.removePrefix("$verb ").trim().split(' ')
                .filter { it.isNotEmpty() }
                .dropWhile { it in FILLER_PREFIXES }
            if (rest.isNotEmpty()) return VoiceCommand.Navigate(rest.joinToString(" "))
        }
        return null
    }

    private fun matchSearch(words: List<String>): VoiceCommand? {
        for (verb in SEARCH_VERBS) {
            val parts = verb.split(' ')
            if (words.size > parts.size && words.take(parts.size) == parts) {
                val text = words.drop(parts.size).joinToString(" ")
                if (text.isNotEmpty()) return VoiceCommand.Search(text)
            }
        }
        return null
    }

    /**
     * Launch an app.
     *
     * The leading verb ("open", "launch", "start") is stripped when present but
     * is not required — see the class note on mis-heard function words. A bare
     * app name is a launch request, which is also how people actually speak.
     */
    private fun matchLaunch(words: List<String>): VoiceCommand? {
        val stripped = if (words.size > 1 && words.first() in LAUNCH_VERBS) words.drop(1) else words
        if (stripped.isEmpty()) return null
        val query = stripped.joinToString(" ")

        val match = bestAppMatch(query) ?: return if (words.first() in LAUNCH_VERBS) {
            // The user clearly asked to launch something we could not resolve.
            // Reporting that is more useful than reporting "unrecognised".
            VoiceCommand.LaunchApp(query, packageName = null)
        } else {
            null
        }
        return VoiceCommand.LaunchApp(query, match.packageName)
    }

    /** Closest installed app within [appMatchThreshold], or null. */
    fun bestAppMatch(query: String): InstalledApp? {
        if (installedApps.isEmpty()) return null
        var best: InstalledApp? = null
        var bestScore = Double.MAX_VALUE

        for (app in installedApps) {
            val label = normalise(app.label)
            if (label.isEmpty()) continue

            // An exact or containment hit wins outright: "maps" should pick
            // "Maps" over a same-distance unrelated label.
            val score = when {
                label == query -> 0.0
                label.startsWith(query) || query.startsWith(label) -> 0.05
                label.contains(query) || query.contains(label) -> 0.15
                else -> editDistance(label, query).toDouble() / maxOf(label.length, query.length)
            }
            if (score < bestScore) {
                bestScore = score
                best = app
            }
        }
        return if (bestScore <= appMatchThreshold) best else null
    }

    // --- helpers ------------------------------------------------------------

    /** Lowercase, strip punctuation, collapse whitespace. */
    private fun normalise(text: String): String =
        text.lowercase()
            .map { if (it.isLetterOrDigit() || it == ' ') it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    /** Levenshtein distance, two-row variant. */
    internal fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)

        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = min(min(current[j - 1] + 1, previous[j] + 1), substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    private companion object {
        val LAUNCH_VERBS = setOf("open", "launch", "start", "run", "show", "go", "and")

        // "and" is in LAUNCH_VERBS deliberately: it is the single most common
        // mis-hearing of "open" observed in this project's own fixtures.

        val GO_HOME_PHRASES = setOf(
            "go home", "home", "headway", "go to home", "home screen", "launcher",
        )

        val MEDIA_PHRASES: List<Pair<String, MediaAction>> = listOf(
            "play pause" to MediaAction.PLAY_PAUSE,
            "pause" to MediaAction.PAUSE,
            "resume" to MediaAction.PLAY,
            "play" to MediaAction.PLAY,
            "stop" to MediaAction.STOP,
            "next track" to MediaAction.NEXT,
            "next song" to MediaAction.NEXT,
            "next" to MediaAction.NEXT,
            "skip" to MediaAction.NEXT,
            "previous track" to MediaAction.PREVIOUS,
            "previous song" to MediaAction.PREVIOUS,
            "previous" to MediaAction.PREVIOUS,
            "back" to MediaAction.PREVIOUS,
        )

        val VOLUME_NOUNS = setOf("volume", "sound")
        val UP_WORDS = setOf("up", "louder", "increase", "raise")
        val DOWN_WORDS = setOf("down", "quieter", "lower", "decrease", "reduce")
        val MUTE_WORDS = setOf("mute", "silence", "unmute")

        val SEARCH_VERBS = listOf("search for", "search", "look for", "find", "type")

        /**
         * Longest first, so "navigate to" is not matched as "navigate" leaving
         * "to the airport" for the filler stripper to guess at.
         */
        val NAVIGATE_VERBS = listOf(
            "navigate to", "directions to", "take me to", "drive to", "route to",
            "navigate", "directions", "find the nearest", "find nearest",
        )

        /** Words left over after the verb that are not part of a place name. */
        val FILLER_PREFIXES = setOf("to", "the", "a", "an")

        val NUMBER_WORDS = mapOf(
            "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
            "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        )
    }
}

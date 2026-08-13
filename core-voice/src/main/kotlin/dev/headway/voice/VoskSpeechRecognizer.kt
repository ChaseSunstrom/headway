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

import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer

/**
 * [SpeechRecognizer] backed by Vosk, running fully on the device.
 *
 * Vosk exposes the same `org.vosk` Java classes on desktop
 * (`com.alphacephei:vosk`) and on Android (`com.alphacephei:vosk-android`) —
 * only the bundled native library differs. That is what lets this class live in
 * a plain JVM module and still be the code that ships on the phone, so the
 * recogniser exercised by CI is the recogniser in the car rather than a
 * stand-in.
 *
 * ## Nothing leaves the device
 *
 * Vosk performs no I/O beyond reading its model directory. CLAUDE.md requires
 * recognition results and audio never to leave the device; that is satisfied
 * structurally here rather than by policy, since there is no network code in
 * the path at all.
 *
 * @param modelPath directory of an unpacked Vosk model.
 */
class VoskSpeechRecognizer(
    modelPath: String,
    override val sampleRateHz: Int = CAR_MICROPHONE_SAMPLE_RATE,
) : SpeechRecognizer {

    private val model = Model(modelPath)
    private var recognizer = Recognizer(model, sampleRateHz.toFloat())

    /**
     * Segments Vosk has already finalised during this utterance.
     *
     * Vosk splits on internal silence, and `getResult()` both returns and
     * *consumes* the segment it split off. `getFinalResult()` then returns only
     * what came after the last split. So a command spoken with any pause in it
     * -- "open... calculator" -- lost everything before the pause, and the
     * command engine was handed "calculator" and asked to make sense of it.
     * Whatever [accept] hands back is kept here so [finish] can return the whole
     * thing.
     */
    private val segments = StringBuilder()

    override fun accept(pcm: ByteArray, length: Int): String? {
        // True means Vosk decided the utterance ended (it saw enough silence).
        val complete = recognizer.acceptWaveForm(pcm, length)
        if (!complete) return null
        val segment = extractText(recognizer.result)
        if (segment.isNotEmpty()) {
            if (segments.isNotEmpty()) segments.append(' ')
            segments.append(segment)
        }
        return segment
    }

    override fun finish(): String {
        val tail = extractText(recognizer.finalResult)
        val whole = buildString {
            append(segments)
            if (tail.isNotEmpty()) {
                if (isNotEmpty()) append(' ')
                append(tail)
            }
        }
        return whole
    }

    override fun reset() {
        segments.setLength(0)
        recognizer.close()
        recognizer = Recognizer(model, sampleRateHz.toFloat())
    }

    override fun close() {
        runCatching { recognizer.close() }
        runCatching { model.close() }
    }

    /**
     * Pulls the `text` field out of Vosk's JSON result.
     *
     * Hand-parsed rather than pulling in a JSON library for one field of one
     * flat object. Vosk emits `{"text" : "..."}` with no nesting and no escapes
     * beyond the standard ones, so a scan for the value is sufficient and keeps
     * the module's dependency list at exactly one.
     */
    private fun extractText(json: String): String {
        val key = "\"text\""
        val keyIndex = json.indexOf(key)
        if (keyIndex < 0) return ""
        val colon = json.indexOf(':', keyIndex + key.length)
        if (colon < 0) return ""
        val open = json.indexOf('"', colon + 1)
        if (open < 0) return ""

        val out = StringBuilder()
        var i = open + 1
        while (i < json.length) {
            val c = json[i]
            when {
                c == '\\' && i + 1 < json.length -> {
                    out.append(json[i + 1]); i += 2
                }
                c == '"' -> return out.toString().trim().lowercase()
                else -> {
                    out.append(c); i++
                }
            }
        }
        return out.toString().trim().lowercase()
    }

    companion object {
        /**
         * The car microphone streams 16 kHz mono 16-bit PCM over the AAP
         * AV-input channel, and Vosk's small English model is trained at the
         * same rate, so no resampling sits between the wire and the recogniser.
         */
        const val CAR_MICROPHONE_SAMPLE_RATE: Int = 16_000

        /** Quietens Vosk's native logging; it writes to stderr otherwise. */
        fun silenceNativeLogging() = LibVosk.setLogLevel(LogLevel.WARNINGS)
    }
}

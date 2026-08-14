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

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import dev.headway.protocol.channel.MicrophoneFormat
import dev.headway.protocol.channel.PcmChunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/**
 * The phone's own microphone, shaped like the car's.
 *
 * ## Why this exists
 *
 * The Voice button is wired to the head unit's AV-input channel, and a head unit
 * that refuses that channel leaves a button that is present, pressable and
 * useless. The first drive reported exactly that. Headway cannot make the car
 * offer its microphone, but it can listen with the one in the driver's pocket,
 * and every part of the pipeline after the audio — the endpointer, Vosk, the
 * command engine — does not care where 16 kHz mono PCM came from.
 *
 * It is a fallback and stays one. The cabin microphone is echo-cancelled, aimed
 * at the driver and above the road noise; a phone face-down in a cup holder is
 * none of those. So this runs only when the car has offered nothing, and the car
 * screen says which one is listening.
 *
 * ## What it costs the user
 *
 * The `RECORD_AUDIO` permission, which is why it is opt-in: nothing here asks
 * for it, [available] simply reports whether it has been granted, and the phone's
 * settings screen is where the driver turns it on. Audio is read, fed to the
 * recogniser in memory and dropped. Nothing is written to disk and nothing
 * leaves the device — the same promise the car microphone path makes, and for
 * the same reason it is worth making.
 *
 * ## Format
 *
 * 16 kHz mono `ENCODING_PCM_16BIT`, which is what the Vosk model wants and what
 * `MicrophoneChannel` negotiates with a head unit, so the two sources are
 * interchangeable rather than merely similar. `VOICE_RECOGNITION` as the source:
 * it is the one AOSP defines as "tuned for speech recognition", it skips the
 * AGC and noise suppression that `MIC` may apply, and on a phone with no such
 * tuning it falls back to `MIC` in the framework rather than failing here.
 */
object PhoneMicSource {

    /** What this produces, and what every downstream stage is built for. */
    val FORMAT: MicrophoneFormat = MicrophoneFormat(
        sampleRateHz = 16_000,
        bitsPerSample = 16,
        channels = 1,
    )

    /** 20 ms, which is one AAP microphone message's worth. */
    private const val FRAMES_PER_CHUNK = 320

    /** Whether the driver has granted the microphone. */
    fun available(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Opens the microphone and emits until the collector stops.
     *
     * Cold: nothing is recorded until something collects, and the `AudioRecord`
     * is released in a `finally` so a cancelled utterance does not leave the
     * phone's recording indicator up.
     *
     * @throws IllegalStateException when the microphone will not open, which
     *   `CarVoiceStream` reports on the car screen rather than propagating.
     */
    fun pcm(onStep: (String) -> Unit = {}): Flow<PcmChunk> = flow {
        val minimum = AudioRecord.getMinBufferSize(
            FORMAT.sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimum > 0) { "this phone reports no usable microphone buffer size ($minimum)" }
        // Four chunks of headroom over the floor. Less and a scheduling hiccup
        // drops audio mid-word; more only delays the first sample.
        val bufferBytes = maxOf(minimum, FRAMES_PER_CHUNK * 2 * 4)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            FORMAT.sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) {
            "the phone microphone would not initialise"
        }
        try {
            record.startRecording()
            onStep("voice: listening on the phone's microphone at ${FORMAT.sampleRateHz} Hz")
            val buffer = ShortArray(FRAMES_PER_CHUNK)
            var micros = 0L
            while (coroutineContext.isActive) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    // ERROR_INVALID_OPERATION means the record was stopped under
                    // us, which is a normal end rather than a fault.
                    if (read != AudioRecord.ERROR_INVALID_OPERATION && read != 0) {
                        onStep("voice: the phone microphone returned $read; stopping")
                    }
                    return@flow
                }
                emit(PcmChunk(micros, buffer.copyOf(read)))
                micros += read * 1_000_000L / FORMAT.sampleRateHz
            }
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
        }
    }.flowOn(Dispatchers.IO)
}

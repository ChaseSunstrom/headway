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

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dev.headway.protocol.channel.AudioChannel
import dev.headway.protocol.channel.PcmFormat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/** Raised when audio cannot be produced in the format the head unit asked for. */
class CarAudioSourceException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * A block of signed 16-bit little-endian interleaved PCM together with the
 * format it is actually in.
 *
 * The pairing is the point. Every bug this file exists to prevent is a buffer
 * travelling without its sample rate and being reinterpreted at the other end.
 */
class PcmBuffer(val format: PcmFormat, val samples: ByteArray) {
    init {
        if (samples.size % format.bytesPerFrame != 0) {
            throw CarAudioSourceException(
                "${samples.size} B is not a whole number of ${format.bytesPerFrame} B frames " +
                    "for $format"
            )
        }
    }

    val frameCount: Int get() = samples.size / format.bytesPerFrame

    val durationMicros: Long get() = format.durationMicros(samples.size)
}

/**
 * Produces the PCM Headway puts on the AAP guidance and system audio channels:
 * spoken replies from the platform's text-to-speech engine, or a caller-supplied
 * buffer, converted into whichever [PcmFormat] the head unit advertised.
 *
 * ## Nothing is played locally
 *
 * There is no `AudioTrack` anywhere in this class, and that is deliberate rather
 * than incidental. The samples are destined for the car's speakers over
 * [AudioChannel]; opening a local track as well would put the same prompt out of
 * the phone's own output at the same time. `TextToSpeech.synthesizeToFile` is
 * the only public API that yields PCM without routing it to an output device —
 * `speak()` always plays — so synthesis goes through a file in the app's cache
 * that is read once and deleted immediately. CLAUDE.md's rule about not writing
 * raw audio to disk is aimed at captured microphone audio; this is the platform
 * leaving no alternative for the phone's *own* speech, and the file does not
 * outlive the call that made it.
 *
 * ## Why the conversion is not optional
 *
 * The sample format is the head unit's, taken off the wire: `AudioConfiguration`
 * entries in the `ServiceDiscoveryResponse`, selected by index in `Start` (see
 * [PcmFormat] and [AudioChannel]). openauto's own units advertise stereo 48 kHz
 * 16-bit for media and **mono 16 kHz 16-bit for guidance and system audio**
 * (`openauto/src/autoapp/Service/ServiceFactory.cpp` L128-L131, L140-L144,
 * L169-L174), while Android TTS engines synthesise at whatever rate they please
 * — 22.05 kHz and 24 kHz are both common. Handing 24 kHz samples to a channel
 * negotiated at 16 kHz does not fail: the car plays them 1.5x fast, and the
 * driver hears a chipmunk. Nothing on the wire reports it.
 *
 * ## The resampler
 *
 * Real linear interpolation, per channel, over the whole buffer — not sample
 * dropping or repetition. Two properties it has on purpose:
 *
 * - **Endpoints are exact.** Output frame 0 is input frame 0 and the final
 *   output frame is the final input frame, because positions are mapped
 *   `i * (sourceFrames - 1) / (targetFrames - 1)` rather than by accumulating a
 *   phase increment. A prompt therefore neither gains a fractional sample of
 *   silence at the front nor loses its last consonant.
 * - **Duration is preserved to within half a frame**, since the output frame
 *   count is the input count scaled by the rate ratio and rounded.
 *
 * Two things it deliberately does not do, both of which matter if this is ever
 * reused for something other than whole prompts:
 *
 * - **No anti-alias filter.** Down-converting (24 kHz TTS to a 16 kHz guidance
 *   channel is the real case) folds anything above the target Nyquist back into
 *   the passband instead of removing it. A correct implementation low-passes
 *   first; an FIR pre-filter is the fix and is recorded as a known gap.
 * - **No streaming state.** Each call resamples an independent buffer. Feeding
 *   consecutive chunks of one stream through it would repeat the boundary
 *   sample and lose the fractional phase between chunks. Headway renders one
 *   complete utterance at a time, so this never arises here.
 *
 * ## Threading
 *
 * [convert] and the functions it is built from are pure and safe from any
 * thread. [speak] and [synthesize] serialise on a mutex, because a
 * [TextToSpeech] instance carries exactly one `UtteranceProgressListener` and
 * two concurrent syntheses would race for it.
 */
class CarAudioSource(
    context: Context,
    /** How long to wait for the TTS engine to bind and report its init status. */
    private val initTimeoutMillis: Long = DEFAULT_INIT_TIMEOUT_MILLIS,
    /** How long to wait for one utterance to be written out. */
    private val synthesisTimeoutMillis: Long = DEFAULT_SYNTHESIS_TIMEOUT_MILLIS,
    /** Narrates each step; wired to the debug log export in the app. */
    private val onStep: (String) -> Unit = {},
) : AutoCloseable {

    // The application context: a CarAudioSource outlives any Activity, and the
    // TTS service binding it holds would keep one alive for the whole session.
    private val context: Context = context.applicationContext

    private val engineLock = Mutex()
    private val synthesisLock = Mutex()
    private val utterances = AtomicLong()

    @Volatile
    private var engine: TextToSpeech? = null

    /** Utterances synthesised since construction; the app's debug overlay reads it. */
    @Volatile
    var utterancesSynthesised: Long = 0L
        private set

    /**
     * True when this image has a usable text-to-speech engine.
     *
     * Binds one to find out and keeps it, so this costs nothing when it is
     * called before the first [speak]. A phone with no engine installed — and a
     * headless AOSP image is exactly that — is a supported configuration:
     * Headway's spoken replies are simply unavailable and the caller should say
     * so rather than crash mid-session.
     */
    suspend fun engineAvailable(): Boolean = try {
        engine()
        true
    } catch (unavailable: CarAudioSourceException) {
        onStep("no text-to-speech engine: ${unavailable.message}")
        false
    }

    /**
     * Synthesises [text] and returns it in [target].
     *
     * @param target the format the head unit selected — pass [AudioChannel.format],
     *   never a constant.
     */
    suspend fun speak(text: String, target: PcmFormat): ByteArray =
        convert(synthesize(text), target)

    /**
     * Synthesises [text] and streams it onto [channel] in the channel's own
     * negotiated format, returning the number of media messages sent.
     *
     * @throws CarAudioSourceException if `Start` has not been exchanged, since
     *   until then there is no negotiated format to render into and any guess
     *   would be the chipmunk bug this class exists to prevent.
     */
    suspend fun speak(
        text: String,
        channel: AudioChannel,
        bufferMicros: Long = AudioChannel.DEFAULT_BUFFER_MICROS,
    ): Int {
        val target = channel.format ?: throw CarAudioSourceException(
            "the ${channel.stream.name} channel has not been started, so the head unit's " +
                "sample format is not known yet"
        )
        return channel.streamPcm(speak(text, target), bufferMicros)
    }

    /** Renders an existing buffer into [target], resampling and remapping as needed. */
    fun render(source: PcmBuffer, target: PcmFormat): ByteArray = convert(source, target)

    /**
     * Runs the TTS engine over [text] and returns the raw synthesis, in whatever
     * format the engine chose.
     *
     * The engine's rate is read from the RIFF header of the file it just wrote,
     * not from [android.speech.tts.Voice] — a voice may report no sample rate at
     * all, and the header is the only account of the bytes that is guaranteed to
     * describe those bytes.
     */
    suspend fun synthesize(text: String): PcmBuffer {
        if (text.isBlank()) {
            throw CarAudioSourceException("refusing to synthesise blank text")
        }
        val limit = TextToSpeech.getMaxSpeechInputLength()
        if (text.length > limit) {
            throw CarAudioSourceException(
                "text is ${text.length} characters; the engine accepts at most $limit"
            )
        }

        val tts = engine()
        return synthesisLock.withLock {
            val utteranceId = "headway-${utterances.incrementAndGet()}"
            val file = File(scratchDirectory(), "$utteranceId.wav")
            val finished = CompletableDeferred<Int>()

            tts.setOnUtteranceProgressListener(listenerFor(utteranceId, finished))
            val queued = tts.synthesizeToFile(text, Bundle(), file, utteranceId)
            if (queued != TextToSpeech.SUCCESS) {
                file.delete()
                throw CarAudioSourceException("the engine refused to synthesise (code $queued)")
            }

            val status = withTimeoutOrNull(synthesisTimeoutMillis) { finished.await() }
            try {
                when {
                    status == null -> throw CarAudioSourceException(
                        "synthesis did not finish within $synthesisTimeoutMillis ms"
                    )

                    status != TextToSpeech.SUCCESS -> throw CarAudioSourceException(
                        "synthesis failed with error code $status"
                    )
                }
                val bytes = file.readBytes()
                if (bytes.isEmpty()) {
                    throw CarAudioSourceException("the engine reported success but wrote no audio")
                }
                val rendered = readWav(bytes)
                utterancesSynthesised++
                onStep(
                    "synthesised ${rendered.durationMicros / 1000} ms at ${rendered.format}"
                )
                rendered
            } finally {
                // Deleted on every path, including the timeout: a partially
                // written utterance is still recorded speech sitting in cache.
                file.delete()
            }
        }
    }

    /**
     * Releases the TTS engine's service binding.
     *
     * Not synchronised against an in-flight [synthesize]: shutting down mid
     * utterance is a caller cancelling, and the synthesis then fails its
     * timeout, which is the correct outcome. Calling this twice is harmless.
     */
    override fun close() {
        val existing = engine ?: return
        engine = null
        existing.setOnUtteranceProgressListener(null)
        existing.shutdown()
        onStep("text-to-speech engine released")
    }

    // --- engine ---------------------------------------------------------------

    private suspend fun engine(): TextToSpeech {
        engine?.let { return it }
        return engineLock.withLock {
            engine ?: bind().also { engine = it }
        }
    }

    private suspend fun bind(): TextToSpeech {
        val initialised = CompletableDeferred<Int>()
        // Constructed on the main looper: TextToSpeech binds a service and
        // delivers onInit through a Handler, and a worker or instrumentation
        // thread need not have a Looper for one to post to.
        val created = CompletableDeferred<TextToSpeech>()
        Handler(Looper.getMainLooper()).post {
            created.complete(TextToSpeech(context) { status -> initialised.complete(status) })
        }

        val instance = withTimeoutOrNull(initTimeoutMillis) { created.await() }
            ?: throw CarAudioSourceException(
                "the main looper did not run within $initTimeoutMillis ms"
            )
        val status = withTimeoutOrNull(initTimeoutMillis) { initialised.await() }
        if (status != TextToSpeech.SUCCESS) {
            instance.shutdown()
            throw CarAudioSourceException(
                if (status == null) {
                    "no text-to-speech engine answered within $initTimeoutMillis ms"
                } else {
                    "text-to-speech initialisation failed with status $status"
                }
            )
        }
        onStep("text-to-speech engine ready")
        return instance
    }

    private fun scratchDirectory(): File =
        File(context.cacheDir, "headway-tts").apply { mkdirs() }

    /**
     * `UtteranceProgressListener` has three abstract methods and one of them is
     * the long-deprecated single-argument `onError`; it still has to be
     * implemented or the object will not compile.
     */
    @Suppress("OverridingDeprecatedMember", "DEPRECATION")
    private fun listenerFor(
        utteranceId: String,
        finished: CompletableDeferred<Int>,
    ): UtteranceProgressListener = object : UtteranceProgressListener() {
        override fun onStart(id: String?) = Unit

        override fun onDone(id: String?) {
            if (id == utteranceId) finished.complete(TextToSpeech.SUCCESS)
        }

        override fun onError(id: String?) {
            if (id == utteranceId) finished.complete(TextToSpeech.ERROR)
        }

        override fun onError(id: String?, errorCode: Int) {
            if (id == utteranceId) finished.complete(errorCode)
        }
    }

    companion object {
        /** Bytes per sample. Everything here is signed 16-bit; see [requireSixteenBit]. */
        private const val BYTES_PER_SAMPLE = 2

        const val DEFAULT_INIT_TIMEOUT_MILLIS: Long = 5_000L
        const val DEFAULT_SYNTHESIS_TIMEOUT_MILLIS: Long = 10_000L

        /**
         * Converts [source] into [target]: channel count first or last depending
         * on which direction is cheaper, then rate.
         *
         * Down-mixing happens before resampling and up-mixing after it, so the
         * resampler — whose cost is linear in the channel count — always runs on
         * the smaller of the two. Both orders are algebraically identical here
         * because linear interpolation and an average are both linear; only the
         * intermediate rounding differs, by at most one least-significant bit.
         */
        fun convert(source: PcmBuffer, target: PcmFormat): ByteArray {
            requireSixteenBit(source.format, "source")
            requireSixteenBit(target, "head unit")

            var samples = source.samples
            var channels = source.format.channelCount

            if (target.channelCount < channels) {
                samples = remapChannels(samples, channels, target.channelCount)
                channels = target.channelCount
            }
            if (source.format.sampleRateHz != target.sampleRateHz) {
                samples = resample(
                    samples,
                    channels,
                    source.format.sampleRateHz,
                    target.sampleRateHz,
                )
            }
            if (channels != target.channelCount) {
                samples = remapChannels(samples, channels, target.channelCount)
            }
            return if (samples === source.samples) samples.copyOf() else samples
        }

        /**
         * Linear-interpolation resampler over a whole buffer. See the class KDoc
         * for what it does and does not guarantee.
         *
         * @param pcm signed 16-bit little-endian, [channelCount] channels
         *   interleaved.
         */
        fun resample(
            pcm: ByteArray,
            channelCount: Int,
            fromRateHz: Int,
            toRateHz: Int,
        ): ByteArray {
            require(channelCount > 0) { "channel count must be positive: $channelCount" }
            require(fromRateHz > 0 && toRateHz > 0) {
                "sample rates must be positive: $fromRateHz -> $toRateHz"
            }
            val frameBytes = BYTES_PER_SAMPLE * channelCount
            if (pcm.size % frameBytes != 0) {
                throw CarAudioSourceException(
                    "${pcm.size} B is not a whole number of $frameBytes B frames"
                )
            }
            if (fromRateHz == toRateHz) return pcm.copyOf()

            val sourceFrames = pcm.size / frameBytes
            if (sourceFrames == 0) return ByteArray(0)

            // Rounded rather than truncated so that a buffer's duration survives
            // the conversion to within half a frame in either direction.
            val targetFrames = ((sourceFrames.toLong() * toRateHz + fromRateHz / 2) / fromRateHz)
                .toInt()
                .coerceAtLeast(1)
            val out = ByteArray(targetFrames * frameBytes)

            if (targetFrames == 1) {
                pcm.copyInto(out, 0, 0, frameBytes)
                return out
            }

            val lastSourceFrame = (sourceFrames - 1).toDouble()
            val step = lastSourceFrame / (targetFrames - 1).toDouble()
            for (frame in 0 until targetFrames) {
                // The final position is pinned instead of computed so that
                // accumulated double rounding cannot leave the tail a fraction
                // of a sample short of the last input frame.
                val position = if (frame == targetFrames - 1) lastSourceFrame else frame * step
                val lower = position.toInt().coerceIn(0, sourceFrames - 1)
                val upper = (lower + 1).coerceAtMost(sourceFrames - 1)
                val fraction = position - lower

                for (channel in 0 until channelCount) {
                    val a = sampleAt(pcm, lower * channelCount + channel)
                    val b = sampleAt(pcm, upper * channelCount + channel)
                    val interpolated = a + (b - a) * fraction
                    putSample(out, frame * channelCount + channel, interpolated.roundToInt())
                }
            }
            return out
        }

        /**
         * Maps [fromChannels] interleaved channels onto [toChannels].
         *
         * Mono to many duplicates the single channel into each — the car mixes
         * and pans, and sending silence in the right channel would put a
         * navigation prompt entirely in the driver's left ear. Many to mono
         * averages, which cannot clip. Anything else (stereo to 4, say) is
         * refused rather than guessed at: there is no correct answer without
         * knowing what the extra channels mean, and no reference head unit
         * advertises more than two.
         */
        fun remapChannels(pcm: ByteArray, fromChannels: Int, toChannels: Int): ByteArray {
            require(fromChannels > 0 && toChannels > 0) {
                "channel counts must be positive: $fromChannels -> $toChannels"
            }
            if (fromChannels == toChannels) return pcm.copyOf()

            val frameBytes = BYTES_PER_SAMPLE * fromChannels
            if (pcm.size % frameBytes != 0) {
                throw CarAudioSourceException(
                    "${pcm.size} B is not a whole number of $frameBytes B frames"
                )
            }
            val frames = pcm.size / frameBytes
            val out = ByteArray(frames * BYTES_PER_SAMPLE * toChannels)

            when {
                fromChannels == 1 -> for (frame in 0 until frames) {
                    val value = sampleAt(pcm, frame)
                    for (channel in 0 until toChannels) {
                        putSample(out, frame * toChannels + channel, value)
                    }
                }

                toChannels == 1 -> for (frame in 0 until frames) {
                    var sum = 0
                    for (channel in 0 until fromChannels) {
                        sum += sampleAt(pcm, frame * fromChannels + channel)
                    }
                    putSample(out, frame, (sum.toDouble() / fromChannels).roundToInt())
                }

                else -> throw CarAudioSourceException(
                    "no channel map from $fromChannels to $toChannels channels; only mono to " +
                        "many and many to mono are defined"
                )
            }
            return out
        }

        /**
         * Reads a RIFF/WAVE file of 16-bit PCM — the shape
         * `TextToSpeech.synthesizeToFile` produces.
         *
         * The chunk list is walked rather than the canonical 44-byte header
         * assumed, because engines interleave `LIST` and `fact` chunks and the
         * data chunk moves. The declared `data` size is clamped to what the file
         * actually holds: an engine that streams into the file and never seeks
         * back leaves that field stale, and trusting it would either truncate
         * the prompt or read past the end.
         */
        fun readWav(bytes: ByteArray): PcmBuffer {
            if (bytes.size < 12 || ascii(bytes, 0) != "RIFF" || ascii(bytes, 8) != "WAVE") {
                throw CarAudioSourceException("the engine did not write a RIFF/WAVE file")
            }

            var sampleRateHz = -1
            var channelCount = -1
            var bitsPerSample = -1
            var data: ByteArray? = null

            var offset = 12
            while (offset + 8 <= bytes.size) {
                val id = ascii(bytes, offset)
                val declared = le32(bytes, offset + 4)
                val body = offset + 8
                val available = bytes.size - body
                if (declared < 0) {
                    throw CarAudioSourceException("WAV chunk '$id' declares $declared bytes")
                }
                val size = minOf(declared, available)
                when (id) {
                    "fmt " -> {
                        if (size < 16) {
                            throw CarAudioSourceException("WAV fmt chunk is only $size bytes")
                        }
                        val encoding = le16(bytes, body)
                        if (encoding != WAVE_FORMAT_PCM) {
                            throw CarAudioSourceException(
                                "only uncompressed PCM is supported; the engine wrote format " +
                                    encoding
                            )
                        }
                        channelCount = le16(bytes, body + 2)
                        sampleRateHz = le32(bytes, body + 4)
                        bitsPerSample = le16(bytes, body + 14)
                    }

                    "data" -> data = bytes.copyOfRange(body, body + size)
                }
                // Chunks are word aligned; an odd size carries a pad byte.
                offset = body + size + (size and 1)
            }

            if (sampleRateHz <= 0 || channelCount <= 0) {
                throw CarAudioSourceException("the WAV file has no fmt chunk")
            }
            if (bitsPerSample != 16) {
                throw CarAudioSourceException(
                    "only 16-bit PCM is supported; the engine wrote $bitsPerSample-bit"
                )
            }
            val payload = data ?: throw CarAudioSourceException("the WAV file has no data chunk")
            val format = PcmFormat(sampleRateHz, bitsPerSample, channelCount)
            // A stale or padded data chunk can end mid-frame; the trailing
            // partial frame is dropped rather than shifting every later sample
            // by a byte, which would swap the channels for the rest of the
            // prompt.
            val whole = payload.size - payload.size % format.bytesPerFrame
            return PcmBuffer(format, payload.copyOf(whole))
        }

        private const val WAVE_FORMAT_PCM = 1

        private fun requireSixteenBit(format: PcmFormat, role: String) {
            if (format.bitsPerSample != 16) {
                throw CarAudioSourceException(
                    "the $role format is ${format.bitsPerSample}-bit; Headway only renders " +
                        "signed 16-bit PCM, which is what every reference head unit advertises"
                )
            }
        }

        /** One signed little-endian 16-bit sample, by sample index rather than byte offset. */
        private fun sampleAt(pcm: ByteArray, index: Int): Int {
            val at = index * BYTES_PER_SAMPLE
            // The high byte keeps its sign; the low byte must not.
            return (pcm[at].toInt() and 0xFF) or (pcm[at + 1].toInt() shl 8)
        }

        private fun putSample(pcm: ByteArray, index: Int, value: Int) {
            val at = index * BYTES_PER_SAMPLE
            pcm[at] = value.toByte()
            pcm[at + 1] = (value shr 8).toByte()
        }

        private fun ascii(bytes: ByteArray, offset: Int): String =
            String(bytes, offset, 4, Charsets.US_ASCII)

        private fun le16(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

        private fun le32(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }
}

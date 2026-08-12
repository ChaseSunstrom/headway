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
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.headway.protocol.channel.AudioFocus
import dev.headway.protocol.channel.PcmFormat
import dev.headway.protocol.control.ControlMessageType
import dev.headway.protocol.framing.AapMessage
import dev.headway.protocol.framing.ChannelId
import dev.headway.protocol.io.Cryptor
import dev.headway.protocol.io.MessageChannel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.EOFException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Exercises [CarAudioSource] and [PhoneAudioFocus] on a real device.
 *
 * Three things are checked, in rising order of what the image has to provide:
 * the sample-rate conversion (pure arithmetic, always runs), the `AudioManager`
 * focus round trip (needs a working audio service, which a headless AOSP image
 * has), and a genuine text-to-speech synthesis (needs an installed engine, which
 * a headless AOSP image usually does *not* have — that test assumes rather than
 * fails, and names the reason it skipped).
 *
 * The resampler assertions are the ones that matter most. A conversion that
 * quietly changes a buffer's duration is the bug that makes a car play speech at
 * chipmunk pitch, and nothing about it is visible on the wire — the head unit
 * acknowledges the wrong-rate audio exactly as it would the right-rate audio.
 */
@RunWith(AndroidJUnit4::class)
class CarAudioSourceTest {

    /** What openauto advertises for guidance and system audio: mono 16 kHz 16-bit. */
    private val guidance = PcmFormat(16_000, 16, 1)

    /** What openauto advertises for the media sink: stereo 48 kHz 16-bit. */
    private val media = PcmFormat(48_000, 16, 2)

    // --- resampling -----------------------------------------------------------

    @Test
    fun resamplingUpAndBackPreservesDurationAndEndpoints() {
        val source = tone(frames = 1_600, rateHz = 16_000, hz = 200.0)

        val up = CarAudioSource.resample(source, channelCount = 1, fromRateHz = 16_000, toRateHz = 48_000)
        assertEquals("16 kHz to 48 kHz must produce three frames for one", 4_800 * 2, up.size)
        assertEquals(
            "the prompt must last exactly as long after conversion",
            PcmFormat(16_000, 16, 1).durationMicros(source.size),
            PcmFormat(48_000, 16, 1).durationMicros(up.size),
        )
        assertEquals("first sample", sampleAt(source, 0), sampleAt(up, 0))
        assertEquals("last sample", sampleAt(source, 1_599), sampleAt(up, 4_799))

        val down = CarAudioSource.resample(up, channelCount = 1, fromRateHz = 48_000, toRateHz = 16_000)
        assertEquals("the round trip must land back on the original length", source.size, down.size)
        assertEquals("first sample", sampleAt(source, 0), sampleAt(down, 0))
        assertEquals("last sample", sampleAt(source, 1_599), sampleAt(down, 1_599))

        // Linear interpolation is not lossless, so this bounds the damage rather
        // than asserting equality: at 200 Hz the error of a one-third-sample
        // interpolation is a fraction of a percent of full scale, and anything
        // near 1% would mean the interpolation is not happening at all.
        var worst = 0
        for (frame in 0 until 1_600) {
            worst = maxOf(worst, abs(sampleAt(source, frame) - sampleAt(down, frame)))
        }
        assertTrue("round-trip error of $worst counts is too large to be interpolation", worst <= 256)
    }

    @Test
    fun resamplingANonIntegerRatioKeepsTheDuration() {
        // The case Headway actually hits: a 24 kHz TTS engine feeding a head unit
        // that advertised 16 kHz for guidance audio.
        val engineOutput = PcmBuffer(PcmFormat(24_000, 16, 1), tone(frames = 2_400, rateHz = 24_000, hz = 300.0))
        val rendered = CarAudioSource.convert(engineOutput, guidance)

        assertEquals("100 ms at 24 kHz is 1600 frames at 16 kHz", 1_600 * 2, rendered.size)
        assertEquals(engineOutput.durationMicros, guidance.durationMicros(rendered.size))
        assertEquals(sampleAt(engineOutput.samples, 0), sampleAt(rendered, 0))
        assertEquals(sampleAt(engineOutput.samples, 2_399), sampleAt(rendered, 1_599))
    }

    @Test
    fun anUnchangedRateIsCopiedNotAliased() {
        val original = tone(frames = 64, rateHz = 16_000, hz = 400.0)
        val copy = CarAudioSource.resample(original, 1, 16_000, 16_000)
        assertEquals(original.size, copy.size)
        // A caller mutating what it got back must not reach into the source
        // buffer, which for a prompt rendered once and streamed twice would
        // corrupt the second send.
        copy[0] = (copy[0] + 1).toByte()
        assertNotEquals(original[0], copy[0])
    }

    // --- channels -------------------------------------------------------------

    @Test
    fun monoToStereoDuplicatesChannels() {
        val mono = pcmOf(0, 1_000, -1_000, 32_767, -32_768)
        val stereo = CarAudioSource.remapChannels(mono, fromChannels = 1, toChannels = 2)

        assertEquals(mono.size * 2, stereo.size)
        for (frame in 0 until 5) {
            val expected = sampleAt(mono, frame)
            assertEquals("left of frame $frame", expected, sampleAt(stereo, frame * 2))
            assertEquals("right of frame $frame", expected, sampleAt(stereo, frame * 2 + 1))
        }
    }

    @Test
    fun stereoToMonoAveragesChannels() {
        val stereo = pcmOf(1_000, 2_000, -30_000, 30_000, 32_767, 32_767)
        val mono = CarAudioSource.remapChannels(stereo, fromChannels = 2, toChannels = 1)

        assertEquals(stereo.size / 2, mono.size)
        assertEquals(1_500, sampleAt(mono, 0))
        assertEquals(0, sampleAt(mono, 1))
        // Averaging is what keeps a full-scale pair from clipping.
        assertEquals(32_767, sampleAt(mono, 2))
    }

    @Test
    fun aMonoPromptIsRenderedIntoTheStereoMediaFormat() {
        val prompt = PcmBuffer(guidance, tone(frames = 1_600, rateHz = 16_000, hz = 250.0))
        val rendered = CarAudioSource.convert(prompt, media)

        assertEquals("100 ms of 48 kHz stereo", 4_800 * 4, rendered.size)
        assertEquals(prompt.durationMicros, media.durationMicros(rendered.size))
        for (frame in intArrayOf(0, 1, 2_399, 4_799)) {
            assertEquals(
                "channels must stay identical for an up-mixed mono prompt",
                sampleAt(rendered, frame * 2),
                sampleAt(rendered, frame * 2 + 1),
            )
        }
    }

    @Test
    fun anUndefinedChannelMapIsRefusedRatherThanGuessed() {
        val quad = ByteArray(8 * 4)
        assertThrows(CarAudioSourceException::class.java) {
            CarAudioSource.remapChannels(quad, fromChannels = 2, toChannels = 4)
        }
    }

    @Test
    fun aFormatHeadwayCannotFrameIsRefused() {
        val prompt = PcmBuffer(guidance, tone(frames = 32, rateHz = 16_000, hz = 400.0))
        assertThrows(CarAudioSourceException::class.java) {
            CarAudioSource.convert(prompt, PcmFormat(16_000, 24, 1))
        }
    }

    // --- WAV ------------------------------------------------------------------

    @Test
    fun aStaleDataChunkSizeIsClampedToTheFileLength() {
        val samples = pcmOf(1, -1, 2, -2, 3, -3)
        // An engine that streams into the file and never seeks back to fix the
        // header leaves the size field at its initial guess.
        val stale = wav(rateHz = 22_050, channels = 1, samples = samples, declaredDataSize = 1 shl 20)
        val parsed = CarAudioSource.readWav(stale)

        assertEquals(22_050, parsed.format.sampleRateHz)
        assertEquals(1, parsed.format.channelCount)
        assertEquals(samples.size, parsed.samples.size)
        assertEquals(3, sampleAt(parsed.samples, 4))
    }

    @Test
    fun aTrailingPartialFrameIsDroppedRatherThanShiftingTheChannels() {
        val stereo = pcmOf(100, 200, 300, 400)
        val truncated = wav(rateHz = 16_000, channels = 2, samples = stereo + byteArrayOf(7))
        val parsed = CarAudioSource.readWav(truncated)

        assertEquals(0, parsed.samples.size % parsed.format.bytesPerFrame)
        assertEquals(2, parsed.frameCount)
    }

    // --- audio focus ----------------------------------------------------------

    @Test
    fun androidFocusRequestAndAbandonRoundTripToTheCar() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue(
            "this image has no AudioManager",
            context.getSystemService(AudioManager::class.java) != null,
        )

        val wire = RecordingChannel()
        val car = AudioFocus(wire)
        val bridge = PhoneAudioFocus(context, car)
        try {
            val granted = runBlocking { bridge.requestForPrompt() }
            assumeTrue("AudioManager refused audio focus on this image", granted)

            assertEquals(PhoneAudioFocus.PhoneFocus.HELD, bridge.phoneFocus)
            assertEquals(
                listOf(AudioFocusRequestType.AUDIO_FOCUS_GAIN_TRANSIENT_MAY_DUCK),
                car.requests,
            )

            val sent = wire.sent.single()
            assertEquals(ChannelId.CONTROL.id, sent.channelId)
            assertEquals(ControlMessageType.AUDIO_FOCUS_REQUEST.id, sent.messageId)
            assertTrue("post-auth control traffic is encrypted", sent.encrypted)
            assertTrue(
                "audio focus travels as SPECIFIC, without the frame's control flag",
                !sent.control,
            )

            runBlocking { bridge.release() }
            assertEquals(PhoneAudioFocus.PhoneFocus.NONE, bridge.phoneFocus)
            assertEquals(AudioFocusRequestType.AUDIO_FOCUS_RELEASE, car.requests.last())
            assertEquals(2, wire.sent.size)
        } finally {
            bridge.close()
        }
    }

    @Test
    fun losingPhoneFocusReleasesTheCarsAndRegainingReAsksForIt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val wire = RecordingChannel()
        val car = AudioFocus(wire)
        val bridge = PhoneAudioFocus(context, car)
        try {
            assumeTrue(
                "AudioManager refused audio focus on this image",
                runBlocking { bridge.requestForPrompt() },
            )

            // A phone call arrives: transient loss, then the call ends.
            runBlocking { bridge.onPhoneChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) }
            assertEquals(PhoneAudioFocus.PhoneFocus.LOST_TRANSIENT, bridge.phoneFocus)
            assertEquals(AudioFocusRequestType.AUDIO_FOCUS_RELEASE, car.requests.last())

            runBlocking { bridge.onPhoneChange(AudioManager.AUDIOFOCUS_GAIN) }
            assertEquals(PhoneAudioFocus.PhoneFocus.HELD, bridge.phoneFocus)
            assertEquals(
                "regaining must re-ask for what was held, not silently downgrade it",
                AudioFocusRequestType.AUDIO_FOCUS_GAIN_TRANSIENT_MAY_DUCK,
                car.requests.last(),
            )

            // A duck is not relayed: Headway has no local output to lower.
            val beforeDuck = car.requests.size
            runBlocking { bridge.onPhoneChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) }
            assertEquals(PhoneAudioFocus.PhoneFocus.DUCKED, bridge.phoneFocus)
            assertEquals(beforeDuck, car.requests.size)
        } finally {
            bridge.close()
        }
    }

    // --- text to speech -------------------------------------------------------

    @Test
    fun aSpokenPromptIsRenderedIntoTheAdvertisedGuidanceFormat() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = CarAudioSource(context)
        try {
            assumeTrue(
                "no text-to-speech engine is installed on this image",
                runBlocking { source.engineAvailable() },
            )

            val pcm = runBlocking { source.speak("Turn right in two hundred metres", guidance) }

            assertTrue("the engine produced no audio", pcm.isNotEmpty())
            assertEquals(
                "a partial frame would swap the channels for the rest of the prompt",
                0,
                pcm.size % guidance.bytesPerFrame,
            )
            val micros = guidance.durationMicros(pcm.size)
            assertTrue("a six-word prompt lasting $micros us is not speech", micros > 400_000)
            assertTrue("a six-word prompt lasting $micros us has run away", micros < 20_000_000)
            assertTrue(
                "the rendered prompt is silence",
                (0 until pcm.size / 2).any { abs(sampleAt(pcm, it)) > 64 },
            )
            assertEquals(1L, source.utterancesSynthesised)
        } finally {
            source.close()
        }
    }

    // --- helpers --------------------------------------------------------------

    /** Records what the focus bridge puts on the wire; nothing ever comes back. */
    private class RecordingChannel : MessageChannel {
        val sent = mutableListOf<AapMessage>()
        override var cryptor: Cryptor? = null

        override suspend fun send(message: AapMessage) {
            sent += message
        }

        override suspend fun receive(): AapMessage =
            throw EOFException("this test never reads from the head unit")
    }

    /**
     * A sine, because the round-trip assertion needs a signal whose
     * interpolation error is bounded and small; a square wave or noise would
     * make the tolerance meaningless.
     */
    private fun tone(frames: Int, rateHz: Int, hz: Double, amplitude: Int = 20_000): ByteArray {
        val out = ByteArray(frames * 2)
        for (frame in 0 until frames) {
            val value = (amplitude * sin(2.0 * PI * hz * frame / rateHz)).roundToInt()
            out[frame * 2] = value.toByte()
            out[frame * 2 + 1] = (value shr 8).toByte()
        }
        return out
    }

    private fun pcmOf(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (index in samples.indices) {
            out[index * 2] = samples[index].toByte()
            out[index * 2 + 1] = (samples[index] shr 8).toByte()
        }
        return out
    }

    private fun sampleAt(pcm: ByteArray, index: Int): Int =
        (pcm[index * 2].toInt() and 0xFF) or (pcm[index * 2 + 1].toInt() shl 8)

    /** A canonical 44-byte-header RIFF/WAVE file of 16-bit PCM. */
    private fun wav(
        rateHz: Int,
        channels: Int,
        samples: ByteArray,
        declaredDataSize: Int = samples.size,
    ): ByteArray {
        val out = ByteArray(44 + samples.size)
        ascii(out, 0, "RIFF")
        le32(out, 4, 36 + samples.size)
        ascii(out, 8, "WAVE")
        ascii(out, 12, "fmt ")
        le32(out, 16, 16)
        le16(out, 20, 1)
        le16(out, 22, channels)
        le32(out, 24, rateHz)
        le32(out, 28, rateHz * channels * 2)
        le16(out, 32, channels * 2)
        le16(out, 34, 16)
        ascii(out, 36, "data")
        le32(out, 40, declaredDataSize)
        samples.copyInto(out, 44)
        return out
    }

    private fun ascii(out: ByteArray, offset: Int, text: String) =
        text.toByteArray(Charsets.US_ASCII).copyInto(out, offset)

    private fun le16(out: ByteArray, offset: Int, value: Int) {
        out[offset] = value.toByte()
        out[offset + 1] = (value shr 8).toByte()
    }

    private fun le32(out: ByteArray, offset: Int, value: Int) {
        out[offset] = value.toByte()
        out[offset + 1] = (value shr 8).toByte()
        out[offset + 2] = (value shr 16).toByte()
        out[offset + 3] = (value shr 24).toByte()
    }
}

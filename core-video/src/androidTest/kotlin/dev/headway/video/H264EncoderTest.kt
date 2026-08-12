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

package dev.headway.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies that this device really produces conformant H.264 for the settings
 * [VideoResolution] derives, by running MediaCodec end to end.
 *
 * ## Why this feeds the encoder buffers rather than a surface
 *
 * [ScreenEncoder] configures MediaCodec in **surface** mode, which is correct: it
 * is fed by a `VirtualDisplay` that the `MediaProjection` owns, and that is the
 * only path that avoids copying every frame through the CPU.
 *
 * That path cannot be driven from a test. `MediaCodec.createInputSurface()`
 * returns a surface that only accepts frames rendered through EGL — `lockCanvas`
 * on it does not produce a frame, it simply yields nothing and the encoder
 * emits no output. And `MediaProjection` needs a user consent dialog that an
 * instrumentation test cannot grant.
 *
 * So the encoder settings, the codec's willingness to accept them, and the
 * shape of the bitstream it emits are verified here in byte-buffer mode with
 * synthetic YUV, which exercises the same codec with the same configuration.
 * What remains unverified on-device is the surface plumbing itself, which is
 * Tier C — see `docs/completion-plan.md`.
 */
@RunWith(AndroidJUnit4::class)
class H264EncoderTest {

    private data class Encoded(
        val codecConfig: ByteArray?,
        val frames: List<Pair<Long, Int>>,
        val keyFrames: Int,
    )

    @Test
    fun theDeviceEncodesTheMalibuConfigurationToRealH264() {
        // The Malibu-class advertisement: 800x480 at 30 fps.
        val resolution = VideoResolution.forSize(800, 480)
        assumeTrue("800x480 is not a resolution the protocol names", resolution != null)
        val bitRate = EncoderConfiguration.bitRateFor(resolution!!.width, resolution.height, 30)
        val result = encode(resolution.width, resolution.height, 30, bitRate, frames = 12)

        val csd = result.codecConfig
        assertTrue("the encoder produced no codec configuration", csd != null)

        // csd-0 must carry the parameter sets the head unit needs before it can
        // decode anything. Without them the car screen stays black.
        val nalTypes = annexBNalTypes(csd!!)
        assertTrue("csd-0 must contain an SPS (type 7), got $nalTypes", nalTypes.contains(7))
        assertTrue("csd-0 must contain a PPS (type 8), got $nalTypes", nalTypes.contains(8))

        assertTrue("expected encoded frames, got none", result.frames.isNotEmpty())
        assertTrue("expected at least one keyframe", result.keyFrames >= 1)

        // Presentation timestamps must rise; the video channel sends them
        // verbatim as the 8-byte microsecond header.
        val timestamps = result.frames.map { it.first }
        assertEquals("timestamps must be strictly increasing", timestamps.sorted(), timestamps)
        assertEquals(timestamps.distinct().size, timestamps.size)
    }

    /**
     * Which advertised resolutions this device can actually encode.
     *
     * Not every device encodes every resolution the protocol can name: this
     * emulator's software encoder refuses 2560x1440, and low-end phones refuse
     * it too. That is a real constraint rather than a defect, and it has a
     * consequence for negotiation -- Headway must pick a configuration the
     * *phone* can encode from those the head unit advertised, instead of
     * assuming any advertised index will work. A head unit offering only
     * resolutions this device cannot encode has to fail the session with a
     * clear message rather than silently producing no video.
     *
     * What is asserted here is the floor every AVC encoder must meet: 720p and
     * below, which covers every head unit resolution seen in the references,
     * including the 800x480 Malibu-class panel. Anything above that is reported
     * rather than required.
     */
    @Test
    fun theDeviceEncodesEveryResolutionARealHeadUnitAdvertises() {
        val unsupported = mutableListOf<String>()
        for (resolution in VideoResolution.entries) {
            val supported = findEncoder(resolution.width, resolution.height) != null
            val label = "${resolution.width}x${resolution.height}"
            if (!supported) {
                unsupported += label
                // Above 720p is optional for an AVC encoder, and no head unit in
                // any reference advertises more.
                assertTrue(
                    "this device cannot encode $label, which is at or below 720p " +
                        "and therefore mandatory",
                    resolution.pixels > 1280 * 720,
                )
            }
        }
        if (unsupported.isNotEmpty()) {
            println("device cannot encode: ${unsupported.joinToString()} (above 720p, not required)")
        }
        // The Malibu-class panel must work.
        assertTrue(
            "the device cannot encode 800x480, the target head unit's resolution",
            findEncoder(800, 480) != null,
        )
    }

    // --- encoding -----------------------------------------------------------

    private fun encode(width: Int, height: Int, fps: Int, bitRate: Int, frames: Int): Encoded {
        val format = baseFormat(width, height, fps, bitRate).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
        }
        val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)
        assumeTrue("no H.264 encoder on this image", name != null)

        val codec = MediaCodec.createByCodecName(name!!)
        var codecConfig: ByteArray? = null
        val out = mutableListOf<Pair<Long, Int>>()
        var keyFrames = 0

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val info = MediaCodec.BufferInfo()
            val frameIntervalUs = 1_000_000L / fps
            var submitted = 0
            var sawEos = false
            val deadline = System.nanoTime() + TIMEOUT_NANOS

            while (!sawEos && System.nanoTime() < deadline) {
                if (submitted <= frames) {
                    val index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        val eos = submitted == frames
                        if (!eos) fillYuv420(buffer, width, height, submitted)
                        codec.queueInputBuffer(
                            index,
                            0,
                            if (eos) 0 else width * height * 3 / 2,
                            submitted * frameIntervalUs,
                            if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0,
                        )
                        submitted++
                    }
                }

                val index = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                if (index >= 0) {
                    val buffer = codec.getOutputBuffer(index)!!
                    val bytes = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.get(bytes)

                    when {
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 -> codecConfig = bytes
                        info.size > 0 -> {
                            out += info.presentationTimeUs to info.size
                            if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) keyFrames++
                        }
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawEos = true
                    codec.releaseOutputBuffer(index, false)
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
        return Encoded(codecConfig, out, keyFrames)
    }

    private fun baseFormat(width: Int, height: Int, fps: Int, bitRate: Int): MediaFormat =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

    private fun findEncoder(width: Int, height: Int): String? =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(
            baseFormat(width, height, 30, 2_000_000).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                )
            }
        )

    /** A moving pattern, so consecutive frames differ and the encoder has work to do. */
    private fun fillYuv420(buffer: java.nio.ByteBuffer, width: Int, height: Int, index: Int) {
        buffer.clear()
        val luma = ByteArray(width * height)
        val band = (index * 8) % height
        for (y in 0 until height) {
            val value = if (y in band until (band + 8)) 235.toByte() else 16.toByte()
            java.util.Arrays.fill(luma, y * width, (y + 1) * width, value)
        }
        buffer.put(luma)
        // Neutral chroma: a grey image is enough to exercise the encoder.
        val chroma = ByteArray(width * height / 2) { 128.toByte() }
        buffer.put(chroma)
    }

    /** NAL unit types in an Annex-B buffer, ignoring emulation-prevention bytes. */
    private fun annexBNalTypes(data: ByteArray): List<Int> {
        val types = mutableListOf<Int>()
        var i = 0
        while (i + 3 < data.size) {
            val three = data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1
            val four = data[i].toInt() == 0 && data[i + 1].toInt() == 0 &&
                data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1
            when {
                four -> { types += data[i + 4].toInt() and 0x1F; i += 5 }
                three -> { types += data[i + 3].toInt() and 0x1F; i += 4 }
                else -> i++
            }
        }
        return types
    }

    private companion object {
        const val DEQUEUE_TIMEOUT_US = 10_000L

        /**
         * 90 seconds. Generous because a software AVC encoder on an emulator
         * without KVM is orders of magnitude slower than hardware; on a phone
         * this completes in well under a second.
         */
        val TIMEOUT_NANOS = 90_000_000_000L
    }
}

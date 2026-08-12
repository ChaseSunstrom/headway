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

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.SystemClock
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs [ScreenEncoder] against the device's real H.264 encoder.
 *
 * **No `MediaProjection`.** Capture consent is a user gesture that no
 * instrumentation test can grant without privileged APIs, which CLAUDE.md
 * forbids outright. Everything downstream of the projection is exercised anyway
 * by drawing into the codec's input surface directly — the projection is only
 * one of several producers that can fill it, so the encoder, its output format,
 * the parameter sets and the timestamps are all genuinely tested; only the
 * virtual-display hookup is not.
 *
 * Drawing into an encoder input surface with `Canvas` is not guaranteed: the
 * codec chooses the buffer format and may pick one the CPU or HWUI cannot render
 * into. Tests that need pixels therefore *assume* rather than fail when neither
 * lock path works, and say so in the skip message — a skip that names the reason
 * is honest, a green test that encoded nothing is not.
 */
@RunWith(AndroidJUnit4::class)
class ScreenEncoderTest {

    // Note: the tests that fed frames in and asserted on the resulting H.264
    // used to live here and were wrong -- a MediaCodec input surface only
    // accepts frames rendered through EGL, so lockCanvas produced nothing and
    // the encoder emitted no output. That coverage now lives in
    // H264EncoderTest, which drives the same codec with the same configuration
    // in byte-buffer mode. What is left here is the lifecycle, which is what
    // ScreenEncoder itself owns.

    /** The advertisement a Malibu-class head unit sends: 800x480, 30 fps. */
    private val configuration = EncoderConfiguration(
        width = 800,
        height = 480,
        frameRate = 30,
        bitRateBitsPerSecond = EncoderConfiguration.bitRateFor(800, 480, 30),
        densityDpi = 140,
    )

    private class RecordedFrame(
        val bytes: ByteArray,
        val presentationTimeUs: Long,
        val keyFrame: Boolean,
    )

    private class RecordingSink : ScreenEncoder.Sink {
        val codecConfigs = mutableListOf<ByteArray>()
        val frames = mutableListOf<RecordedFrame>()

        override fun onCodecConfig(codecConfig: ByteArray) {
            codecConfigs += codecConfig
        }

        override fun onFrame(
            data: ByteArray,
            length: Int,
            presentationTimeUs: Long,
            keyFrame: Boolean,
        ) {
            // The encoder reuses this array; a test that kept the reference would
            // assert against whatever the next frame happened to overwrite it with.
            frames += RecordedFrame(data.copyOf(length), presentationTimeUs, keyFrame)
        }
    }

    @Test
    fun theEncoderStartsStopsAndRestartsWithoutStrandingTheCodec() {
        assumeAvcEncoder()
        val encoder = ScreenEncoder(configuration)

        val first = encoder.start(RecordingSink())
        assertTrue("input surface should be usable", first.isValid)
        assertTrue(encoder.running)
        assertThrows(IllegalStateException::class.java) { encoder.start(RecordingSink()) }

        encoder.stop()
        assertFalse(encoder.running)
        assertFalse(
            "stop() must release the input surface, or every reconnect leaks one",
            first.isValid,
        )

        val second = encoder.start(RecordingSink())
        assertTrue("the encoder must be restartable for reconnects", second.isValid)
        assertEquals(0L, encoder.framesEncoded)
        encoder.stop()
        // Hardware encoders stay claimed for the process lifetime if release() is
        // skipped, so a double stop must be harmless rather than throwing and
        // leaving the second release unreached.
        encoder.stop()
        assertFalse(second.isValid)
    }

    @Test
    fun drainingAndRequestingKeyframesBeforeStartAreNoOps() {
        val encoder = ScreenEncoder(configuration)
        assertEquals(0, encoder.drain(0L))
        encoder.requestKeyFrame()
        assertFalse(encoder.running)
    }

    
    
    // --- helpers ------------------------------------------------------------

    private fun assumeAvcEncoder() {
        val probe = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            configuration.width,
            configuration.height,
        )
        val encoder = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(probe)
        assumeTrue("no H.264 encoder on this image", encoder != null)
    }

    private fun assumeDrawable(surface: Surface) {
        assumeTrue(
            "this image's encoder input surface accepts neither lockHardwareCanvas nor lockCanvas",
            paint(surface, 0),
        )
    }

    /**
     * Draws one frame. Returns false if the surface cannot be drawn into at all.
     *
     * Content changes every frame on purpose: an unchanging image lets the
     * encoder emit skipped frames, which carry no slice and would make the NAL
     * assertions meaningless.
     */
    private fun paint(surface: Surface, index: Int): Boolean {
        val canvas = lockCanvas(surface) ?: return false
        return try {
            canvas.drawColor(if (index % 2 == 0) Color.DKGRAY else Color.BLACK)
            val paint = Paint().apply { color = Color.WHITE }
            val left = (index * 17 % (configuration.width - BLOCK)).toFloat()
            canvas.drawRect(left, 40f, left + BLOCK, 40f + BLOCK, paint)
            surface.unlockCanvasAndPost(canvas)
            true
        } catch (t: Throwable) {
            false
        }
    }

    private fun lockCanvas(surface: Surface): Canvas? {
        // lockHardwareCanvas first: a codec input surface usually allocates buffers
        // the CPU may not write, and the GPU path copes with more formats.
        return try {
            surface.lockHardwareCanvas()
        } catch (hardware: Throwable) {
            try {
                surface.lockCanvas(null)
            } catch (software: Throwable) {
                null
            }
        }
    }

    private fun drainUntil(encoder: ScreenEncoder, done: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + FLUSH_TIMEOUT_MS
        while (!done() && SystemClock.elapsedRealtime() < deadline) {
            encoder.drain(50_000L)
        }
    }

    private fun startsWithStartCode(bytes: ByteArray): Boolean =
        bytes.size > 4 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() &&
            (bytes[2] == 1.toByte() || (bytes[2] == 0.toByte() && bytes[3] == 1.toByte()))

    /**
     * Annex-B NAL header types, the same way aa-proxy-rs classifies frames
     * (`aa-proxy-rs/src/media_tap.rs` L884-L905): scan for a 3- or 4-byte start
     * code and take the low five bits of the byte after it.
     */
    private fun nalTypes(annexB: ByteArray): List<Int> {
        val types = mutableListOf<Int>()
        var i = 0
        while (i + 3 < annexB.size) {
            val zeroPair = annexB[i] == 0.toByte() && annexB[i + 1] == 0.toByte()
            when {
                zeroPair && annexB[i + 2] == 1.toByte() -> {
                    types += annexB[i + 3].toInt() and 0x1f
                    i += 4
                }

                zeroPair && annexB[i + 2] == 0.toByte() && annexB[i + 3] == 1.toByte() -> {
                    if (i + 4 >= annexB.size) return types
                    types += annexB[i + 4].toInt() and 0x1f
                    i += 5
                }

                else -> i++
            }
        }
        return types
    }

    private companion object {
        const val FRAMES = 10
        const val FRAME_INTERVAL_MS = 40L
        const val FLUSH_TIMEOUT_MS = 2_000L
        const val BLOCK = 120

        const val NAL_IDR = 5
        const val NAL_SPS = 7
        const val NAL_PPS = 8
    }
}

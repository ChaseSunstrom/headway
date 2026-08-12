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

import aap_protobuf.service.media.sink.message.VideoCodecResolutionTypeOuterClass.VideoCodecResolutionType
import aap_protobuf.service.media.sink.message.VideoConfigurationOuterClass.VideoConfiguration
import aap_protobuf.service.media.sink.message.VideoFrameRateTypeOuterClass.VideoFrameRateType
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The negotiation half of the video path, on a real device.
 *
 * Nothing here needs Android — that is the point of keeping [VideoResolution]
 * free of framework imports — but it runs on the device anyway because the
 * protobuf runtime that supplies the enums is the one thing between here and the
 * wire that behaves differently on ART, and because a wrong resolution is not a
 * visible bug: the phone encodes happily and the car shows a black or garbled
 * screen with no error anywhere.
 */
@RunWith(AndroidJUnit4::class)
class VideoResolutionTest {

    @Test
    fun everyResolutionTheProtocolCanAdvertiseIsMapped() {
        val unmapped = VideoCodecResolutionType.values().filter { VideoResolution.of(it) == null }
        assertTrue(
            "no pixel dimensions for $unmapped — the encoder cannot size its surface for them",
            unmapped.isEmpty(),
        )
    }

    @Test
    fun tabulatedDimensionsAgreeWithTheProtobufEnumNames() {
        // The enum name is the primary source (aa-proxy-rs/src/sdr_ui.rs
        // L1158-L1167 parses it at runtime); this is the check that the hand-written
        // table did not drift from it.
        for (resolution in VideoResolution.values()) {
            val fromName = VideoResolution.dimensionsFromEnumName(resolution.codecResolution.name)
            assertNotNull("cannot parse ${resolution.codecResolution.name}", fromName)
            assertEquals(resolution.codecResolution.name, fromName!!.first, resolution.width)
            assertEquals(resolution.codecResolution.name, fromName.second, resolution.height)
        }
    }

    @Test
    fun theFourPortraitEntriesAreTheTransposeOfTheLandscapeOnes() {
        // VIDEO_720x1280 .. VIDEO_2160x3840 are the same panels rotated, so a
        // transposed pair that does not exist means a value was transcribed
        // backwards — the failure mode that produces a 90-degree-wrong car screen.
        for (resolution in VideoResolution.values()) {
            if (resolution == VideoResolution.VIDEO_800x480) continue
            assertNotNull(
                "no transpose of $resolution",
                VideoResolution.forSize(resolution.height, resolution.width),
            )
        }
    }

    @Test
    fun dimensionsMapBackToTheAdvertisedEnum() {
        assertEquals(VideoResolution.VIDEO_800x480, VideoResolution.forSize(800, 480))
        assertEquals(VideoResolution.VIDEO_1080x1920, VideoResolution.forSize(1080, 1920))
        assertNull("1024x600 is not a resolution the protocol can express", VideoResolution.forSize(1024, 600))
    }

    @Test
    fun theFrameRateEnumIsNumberedBackwards() {
        // VIDEO_FPS_60 = 1, VIDEO_FPS_30 = 2 (VideoFrameRateType.proto L5-L8).
        // AACS numbers them the other way; reading it that way would encode 60 fps
        // for a car that asked for 30 and blow the bit rate budget.
        assertEquals(60, VideoResolution.frameRateOf(VideoFrameRateType.VIDEO_FPS_60))
        assertEquals(30, VideoResolution.frameRateOf(VideoFrameRateType.VIDEO_FPS_30))
        assertEquals(1, VideoFrameRateType.VIDEO_FPS_60.number)
        assertEquals(2, VideoFrameRateType.VIDEO_FPS_30.number)
    }

    @Test
    fun derivesTheEncoderConfigurationFromAMalibuClassAdvertisement() {
        // The advertisement HeadUnitConfig sends: 800x480, 30 fps, 140 dpi.
        val advertised = VideoConfiguration.newBuilder()
            .setCodecResolution(VideoCodecResolutionType.VIDEO_800x480)
            .setFrameRate(VideoFrameRateType.VIDEO_FPS_30)
            .setDensity(140)
            .build()

        val encoder = EncoderConfiguration.of(advertised)

        assertEquals(800, encoder.width)
        assertEquals(480, encoder.height)
        assertEquals(30, encoder.frameRate)
        assertEquals(140, encoder.densityDpi)
        assertEquals(1, encoder.keyFrameIntervalSeconds)
        assertEquals(800 * 480 * 30 * 15 / 100, encoder.bitRateBitsPerSecond)
        // Two frame intervals at 30 fps.
        assertEquals(66_666L, encoder.repeatFrameAfterMicros)
    }

    @Test
    fun sixtyFpsAdvertisementsDoubleThePixelRateAndTheBitRate() {
        val advertised = VideoConfiguration.newBuilder()
            .setCodecResolution(VideoCodecResolutionType.VIDEO_1280x720)
            .setFrameRate(VideoFrameRateType.VIDEO_FPS_60)
            .setDensity(160)
            .build()

        val encoder = EncoderConfiguration.of(advertised)

        assertEquals(60, encoder.frameRate)
        assertEquals(1280 * 720 * 60 * 15 / 100, encoder.bitRateBitsPerSecond)
        assertEquals(33_333L, encoder.repeatFrameAfterMicros)
    }

    @Test
    fun anAbsentDensityFallsBackAndAnAbsentFrameRateMeansThirty() {
        val advertised = VideoConfiguration.newBuilder()
            .setCodecResolution(VideoCodecResolutionType.VIDEO_800x480)
            .build()

        val encoder = EncoderConfiguration.of(advertised)

        assertEquals(EncoderConfiguration.DEFAULT_DENSITY_DPI, encoder.densityDpi)
        assertEquals(30, encoder.frameRate)
    }

    @Test
    fun realDensityIsUsedOnlyWhenDensityIsMissing() {
        val both = VideoConfiguration.newBuilder()
            .setCodecResolution(VideoCodecResolutionType.VIDEO_800x480)
            .setDensity(140)
            .setRealDensity(213)
            .build()
        assertEquals(140, EncoderConfiguration.of(both).densityDpi)

        val realOnly = VideoConfiguration.newBuilder()
            .setCodecResolution(VideoCodecResolutionType.VIDEO_800x480)
            .setRealDensity(213)
            .build()
        assertEquals(213, EncoderConfiguration.of(realOnly).densityDpi)
    }

    @Test
    fun marginsShrinkTheVisibleAreaButNotTheEncodedFrame() {
        // width_margin/height_margin are TOTALS across opposing edges
        // (aa-proxy-rs/src/sdr_ui.rs L675-L687). Encoding at the reduced size
        // instead would produce a stream the car's decoder rejects outright.
        val advertised = VideoConfiguration.newBuilder()
            .setCodecResolution(VideoCodecResolutionType.VIDEO_800x480)
            .setFrameRate(VideoFrameRateType.VIDEO_FPS_30)
            .setWidthMargin(40)
            .setHeightMargin(20)
            .build()

        val encoder = EncoderConfiguration.of(advertised)

        assertEquals(800, encoder.width)
        assertEquals(480, encoder.height)
        assertEquals(760, encoder.visibleWidth)
        assertEquals(460, encoder.visibleHeight)
    }

    @Test
    fun theBitRateIsClampedAtBothEnds() {
        // 4K at 60 asks for ~100 Mbit/s, which the car's Wi-Fi link cannot carry.
        val fourKSixty = EncoderConfiguration.bitRateFor(3840, 2160, 60)
        assertEquals(EncoderConfiguration.MAX_BIT_RATE, fourKSixty)

        // A hypothetical tiny panel must still get enough bits to keep text legible.
        assertEquals(EncoderConfiguration.MIN_BIT_RATE, EncoderConfiguration.bitRateFor(320, 240, 30))

        // And the ordinary case is neither clamp.
        val eightHundredBy480 = EncoderConfiguration.bitRateFor(800, 480, 30)
        assertTrue(
            "800x480@30 should not hit a clamp: $eightHundredBy480",
            eightHundredBy480 > EncoderConfiguration.MIN_BIT_RATE &&
                eightHundredBy480 < EncoderConfiguration.MAX_BIT_RATE,
        )
    }

    @Test
    fun anAdvertisementWithoutAResolutionIsRejectedRatherThanGuessed() {
        val noResolution = VideoConfiguration.newBuilder()
            .setFrameRate(VideoFrameRateType.VIDEO_FPS_30)
            .build()

        assertThrows(IllegalArgumentException::class.java) {
            EncoderConfiguration.of(noResolution)
        }
    }

    @Test
    fun nonsensicalGeometryIsRejectedAtConstruction() {
        assertThrows(IllegalArgumentException::class.java) {
            EncoderConfiguration(
                width = 800,
                height = 480,
                frameRate = 30,
                bitRateBitsPerSecond = 1_000_000,
                densityDpi = 160,
                widthMargin = 800,
            )
        }
    }
}

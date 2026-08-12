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

/**
 * The pixel dimensions behind each `VideoCodecResolutionType` the head unit may
 * advertise.
 *
 * The protocol never sends a width and a height: `VideoConfiguration` carries
 * only the enum (`codec_resolution`, field 1 —
 * `aasdk/protobuf/aap_protobuf/service/media/sink/message/VideoConfiguration.proto`
 * L11), so the enum *is* the resolution and this table is the only place the
 * encoder learns how large a surface to allocate. The enum names encode the
 * dimensions literally — `VIDEO_<width>x<height>` — which aa-proxy-rs relies on
 * by parsing the name at runtime rather than tabulating it
 * (`aa-proxy-rs/src/sdr_ui.rs` L1158-L1167). Headway tabulates instead, and
 * [dimensionsFromEnumName] exists so the table can be checked against the names
 * rather than being trusted.
 *
 * Values: `aasdk/protobuf/aap_protobuf/service/media/sink/message/VideoCodecResolutionType.proto`
 * L5-L15 (1 = 800x480 through 9 = 2160x3840); openauto's landscape entries are
 * corroborated by its own `QRect` mapping
 * (`openauto/src/autoapp/Service/ServiceFactory.cpp` L99-L112).
 *
 * Entry names deliberately mirror the protobuf enum names so a mismatch between
 * this file and a regenerated proto is visible on sight.
 */
enum class VideoResolution(
    val codecResolution: VideoCodecResolutionType,
    val width: Int,
    val height: Int,
) {
    VIDEO_800x480(VideoCodecResolutionType.VIDEO_800x480, 800, 480),
    VIDEO_1280x720(VideoCodecResolutionType.VIDEO_1280x720, 1280, 720),
    VIDEO_1920x1080(VideoCodecResolutionType.VIDEO_1920x1080, 1920, 1080),
    VIDEO_2560x1440(VideoCodecResolutionType.VIDEO_2560x1440, 2560, 1440),
    VIDEO_3840x2160(VideoCodecResolutionType.VIDEO_3840x2160, 3840, 2160),
    VIDEO_720x1280(VideoCodecResolutionType.VIDEO_720x1280, 720, 1280),
    VIDEO_1080x1920(VideoCodecResolutionType.VIDEO_1080x1920, 1080, 1920),
    VIDEO_1440x2560(VideoCodecResolutionType.VIDEO_1440x2560, 1440, 2560),
    VIDEO_2160x3840(VideoCodecResolutionType.VIDEO_2160x3840, 2160, 3840),
    ;

    /** Head units in this orientation are rare but the enum has four of them. */
    val portrait: Boolean get() = height > width

    val pixels: Int get() = width * height

    companion object {
        private const val ENUM_NAME_PREFIX = "VIDEO_"

        /** The advertised enum, or null if the head unit sent a value this build predates. */
        fun of(codecResolution: VideoCodecResolutionType): VideoResolution? =
            values().firstOrNull { it.codecResolution == codecResolution }

        /** The reverse mapping: what a phone would advertise for a given panel size. */
        fun forSize(width: Int, height: Int): VideoResolution? =
            values().firstOrNull { it.width == width && it.height == height }

        /**
         * Derives the dimensions from the protobuf enum name, the way aa-proxy-rs
         * does (`aa-proxy-rs/src/sdr_ui.rs` L1158-L1167).
         *
         * Not used to look resolutions up — a typo in a vendored proto would then
         * silently become the encoder's opinion of the car's screen. It is here so
         * the table above can be cross-checked against the names it claims to
         * follow.
         */
        fun dimensionsFromEnumName(name: String): Pair<Int, Int>? {
            if (!name.startsWith(ENUM_NAME_PREFIX)) return null
            val parts = name.removePrefix(ENUM_NAME_PREFIX).split('x', 'X')
            if (parts.size != 2) return null
            val width = parts[0].toIntOrNull() ?: return null
            val height = parts[1].toIntOrNull() ?: return null
            return width to height
        }

        /**
         * Frames per second for a `VideoFrameRateType`.
         *
         * **The ordering is inverted from the obvious one**: `VIDEO_FPS_60 = 1`,
         * `VIDEO_FPS_30 = 2`
         * (`aasdk/protobuf/aap_protobuf/service/media/sink/message/VideoFrameRateType.proto`
         * L5-L8). AACS's legacy enum numbers them the other way round
         * (`AACS/proto/VideoFps.proto` L7-L15); per CLAUDE.md aasdk wins, and
         * getting this backwards would encode 60 fps for a car that asked for 30.
         */
        fun frameRateOf(frameRate: VideoFrameRateType): Int = when (frameRate) {
            VideoFrameRateType.VIDEO_FPS_60 -> 60
            VideoFrameRateType.VIDEO_FPS_30 -> 30
        }
    }
}

/**
 * Everything `MediaCodec` and `VirtualDisplay` need, derived from one advertised
 * `VideoConfiguration`.
 *
 * Kept free of Android imports so the negotiation logic — the part that can be
 * wrong in a way that bricks the car screen — is testable without a device, and
 * so [ScreenEncoder] has nothing left to decide beyond calling the framework.
 *
 * @param widthMargin total horizontal margin, i.e. left plus right, not per edge
 *   (`aa-proxy-rs/src/sdr_ui.rs` L675-L687 sums the opposing `UiConfig.margins`
 *   insets into this scalar; `aa-proxy-rs/src/display.rs` L180-L183 splits it
 *   back evenly). The encoded frame is still the full [width] by [height] — the
 *   margins describe which part of it the car actually shows, so they belong to
 *   layout and to the touch transform, not to the codec.
 */
data class EncoderConfiguration(
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val bitRateBitsPerSecond: Int,
    val densityDpi: Int,
    val keyFrameIntervalSeconds: Int = DEFAULT_KEY_FRAME_INTERVAL_SECONDS,
    val widthMargin: Int = 0,
    val heightMargin: Int = 0,
) {
    init {
        require(width > 0 && height > 0) { "resolution must be positive: ${width}x$height" }
        require(frameRate > 0) { "frame rate must be positive: $frameRate" }
        require(bitRateBitsPerSecond > 0) { "bit rate must be positive: $bitRateBitsPerSecond" }
        require(densityDpi > 0) { "density must be positive: $densityDpi" }
        require(widthMargin in 0 until width) { "width margin $widthMargin does not fit in $width" }
        require(heightMargin in 0 until height) { "height margin $heightMargin does not fit in $height" }
    }

    /** Width of the region the car displays, once its margins are taken off. */
    val visibleWidth: Int get() = width - widthMargin

    /** Height of the region the car displays, once its margins are taken off. */
    val visibleHeight: Int get() = height - heightMargin

    /**
     * How long the encoder may go without emitting a frame before it repeats the
     * last one, in microseconds. See [ScreenEncoder] for why a static screen must
     * still produce output.
     */
    val repeatFrameAfterMicros: Long get() = REPEAT_FRAME_INTERVALS * 1_000_000L / frameRate

    companion object {
        /**
         * One second between IDRs, the closest round figure to the only keyframe
         * cadence any reference states: AACS configures `x264enc` with
         * `key-int-max = 25`, about 0.83 s at 30 fps
         * (`AACS/AAServer/src/VideoChannelHandler.cpp` L73-L74). No reference
         * states a protocol-mandated interval, and the cost of guessing short is
         * bandwidth while the cost of guessing long is a black car screen for that
         * long after any packet loss, so this errs short.
         */
        const val DEFAULT_KEY_FRAME_INTERVAL_SECONDS: Int = 1

        /**
         * Density when the head unit leaves `VideoConfiguration.density` (field 5)
         * unset. 160 is aa-proxy-rs's default
         * (`aa-proxy-rs/src/inject_displays.rs` L16-L30) and is also Android's
         * `DisplayMetrics.DENSITY_DEFAULT`, so the phone renders at 1x rather than
         * at a scale factor invented here. openauto defaults to 140 instead
         * (`openauto/src/autoapp/Configuration/Configuration.cpp` L127-L132) — the
         * disagreement is why this only applies when the field is absent.
         */
        const val DEFAULT_DENSITY_DPI: Int = 160

        /**
         * Bits per pixel per frame used to size the bit rate.
         *
         * **Inferred, not extracted.** No reference states a video bit rate: AACS
         * leaves `x264enc` at its default and openauto only decodes. 0.15 bpp puts
         * an 800x480 30 fps car at ~1.7 Mbit/s, which is comfortable for UI-style
         * content over the car's 5 GHz link while leaving headroom under the
         * 250 ms end-to-end budget in CLAUDE.md.
         */
        const val BITS_PER_PIXEL_PER_FRAME: Double = 0.15

        /** Below this, text on the car screen smears during scrolls. Inferred. */
        const val MIN_BIT_RATE: Int = 1_000_000

        /**
         * Ceiling on the computed rate. 1080p60 and 4K would otherwise ask for
         * 19-100 Mbit/s, which the car's Wi-Fi link cannot sustain and which would
         * buy latency, not quality, on a screen showing mostly flat UI. Inferred.
         */
        const val MAX_BIT_RATE: Int = 12_000_000

        /** Repeat the previous frame after this many frame intervals of silence. */
        private const val REPEAT_FRAME_INTERVALS = 2L

        /**
         * Turns one advertised `VideoConfiguration` into encoder settings.
         *
         * The caller picks *which* configuration: the head unit advertises a
         * repeated list and the index the phone selects in `Start` is an index into
         * that list (`docs/protocol-notes.md` §5.1 step 0). Passing the wrong
         * element here encodes at a size the car will not decode.
         *
         * @throws IllegalArgumentException if `codec_resolution` is absent or names
         *   a resolution this build does not know. Both are fatal rather than
         *   defaultable: there is no safe fallback size, and encoding at a guessed
         *   resolution produces a car screen that is silently wrong.
         */
        fun of(configuration: VideoConfiguration): EncoderConfiguration {
            require(configuration.hasCodecResolution()) {
                "head unit advertised a video configuration with no codec_resolution"
            }
            val resolution = VideoResolution.of(configuration.codecResolution)
            requireNotNull(resolution) {
                "unsupported codec_resolution ${configuration.codecResolution}"
            }
            val frameRate = VideoResolution.frameRateOf(
                if (configuration.hasFrameRate()) {
                    configuration.frameRate
                } else {
                    // openauto's default and the only rate a Malibu-class unit is
                    // known to ask for.
                    VideoFrameRateType.VIDEO_FPS_30
                }
            )
            val density = when {
                configuration.hasDensity() && configuration.density > 0 -> configuration.density
                // real_density (field 9) is aa-proxy-rs's addition and is only
                // populated by units that also populate density, so it is a
                // second chance rather than a preference.
                configuration.hasRealDensity() && configuration.realDensity > 0 ->
                    configuration.realDensity
                else -> DEFAULT_DENSITY_DPI
            }
            return EncoderConfiguration(
                width = resolution.width,
                height = resolution.height,
                frameRate = frameRate,
                bitRateBitsPerSecond = bitRateFor(resolution.width, resolution.height, frameRate),
                densityDpi = density,
                widthMargin = configuration.widthMargin.coerceIn(0, resolution.width - 1),
                heightMargin = configuration.heightMargin.coerceIn(0, resolution.height - 1),
            )
        }

        /** [BITS_PER_PIXEL_PER_FRAME] times the pixel rate, clamped to the band above. */
        fun bitRateFor(width: Int, height: Int, frameRate: Int): Int {
            val raw = width.toDouble() * height.toDouble() * frameRate.toDouble() *
                BITS_PER_PIXEL_PER_FRAME
            return raw.toLong().coerceIn(MIN_BIT_RATE.toLong(), MAX_BIT_RATE.toLong()).toInt()
        }
    }
}

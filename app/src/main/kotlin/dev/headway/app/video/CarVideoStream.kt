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

package dev.headway.app.video

import aap_protobuf.service.ServiceOuterClass
import aap_protobuf.service.media.shared.message.MediaCodecTypeOuterClass.MediaCodecType
import android.media.projection.MediaProjection
import dev.headway.protocol.channel.VideoChannel
import dev.headway.protocol.io.MessageChannel
import dev.headway.protocol.session.HeadUnitProfile
import dev.headway.video.EncoderConfiguration
import dev.headway.video.ScreenEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Everything between "the car opened a video channel" and "the car is showing
 * the phone screen".
 *
 * ## Why this is its own class
 *
 * The bring-up has a fixed order that is easy to get subtly wrong, and every
 * step depends on the previous one's answer:
 *
 * 1. `sendSetup` — offer H.264.
 * 2. `awaitConfig` — the head unit replies with the configuration *indices* it
 *    will accept and its acknowledgement window. **The encoder cannot be built
 *    before this**, because the chosen index selects which advertised
 *    `VideoConfiguration` (resolution, frame rate, density, margins) applies.
 * 3. `sendStart` — with a session id and the chosen index.
 * 4. Start the encoder, which produces SPS/PPS and then frames.
 * 5. `sendCodecConfig` then `sendFrame`, in that order, forever.
 *
 * Getting 2 and 4 the wrong way round means encoding at a guessed resolution,
 * which produces a car screen that is silently wrong rather than an error.
 *
 * ## The deadline this exists to meet
 *
 * A real 2021 Chevrolet Infotainment 3 unit closes the session about fifteen
 * seconds after the last channel opens if no video has arrived — measured at
 * 15 s, 16 s and 19 s across three sessions. So this path does no work it can
 * avoid, asks for nothing interactive, and starts the encoder the moment the
 * head unit says which configuration it wants.
 */
class CarVideoStream(
    private val channel: VideoChannel,
    private val service: ServiceOuterClass.Service,
    private val projection: MediaProjection,
    private val onStep: (String) -> Unit = {},
) {

    private var encoder: ScreenEncoder? = null
    private var pump: VideoPump? = null
    private var jobs: MutableList<Job> = mutableListOf()

    /**
     * Runs the whole bring-up, then streams until [scope] is cancelled.
     *
     * @return false when the head unit refused the stream, which is a real
     *   answer rather than an error: the session stays up and the log says why.
     */
    suspend fun start(scope: CoroutineScope, sessionId: Int = DEFAULT_SESSION_ID): Boolean {
        channel.sendSetup(MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP)
        val response = channel.awaitConfig()
        if (!response.ready) {
            onStep("the head unit refused the video stream: ${response.status}")
            return false
        }

        val index = response.configurationIndices.firstOrNull()
        if (index == null) {
            onStep("the head unit accepted video but offered no configuration to use")
            return false
        }
        val configurations = service.mediaSinkService.videoConfigsList
        if (index !in configurations.indices) {
            onStep(
                "the head unit chose video configuration $index but only advertised " +
                    "${configurations.size}; refusing to guess a resolution"
            )
            return false
        }

        val encoderConfiguration = EncoderConfiguration.of(configurations[index])
        onStep(
            "video: ${encoderConfiguration.width}x${encoderConfiguration.height} at " +
                "${encoderConfiguration.frameRate} fps, ${encoderConfiguration.densityDpi} dpi, " +
                "window ${response.maxUnacked ?: 1}"
        )

        channel.sendStart(sessionId = sessionId, configurationIndex = index)

        val screenEncoder = ScreenEncoder(encoderConfiguration, onStep = onStep)
        val videoPump = VideoPump(channel, onStep)
        encoder = screenEncoder
        pump = videoPump

        // The order matters: the encoder must have a sink before it produces
        // anything, and startCapture begins producing immediately.
        screenEncoder.startCapture(projection, videoPump)

        jobs += scope.launch { screenEncoder.encodeUntilStopped() }
        jobs += scope.launch { videoPump.pump() }
        onStep("video stream started")
        return true
    }

    /** Frames sent and dropped, for the log. */
    fun describe(): String {
        val p = pump ?: return "video not started"
        return "video: ${p.sentFrames} frame(s) sent, ${p.droppedFrames} dropped"
    }

    /**
     * Stops encoding but leaves the projection alone.
     *
     * Consent is a user gesture and belongs to whoever obtained it —
     * `ScreenEncoder.stop()` is documented as deliberately not touching it, and
     * a reconnect must not put a dialog in front of a driver.
     */
    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        runCatching { encoder?.stop() }
        runCatching { pump?.close() }
    }

    companion object {
        /**
         * Any non-zero value works; the head unit echoes it back on every
         * acknowledgement so the two sides can tell streams apart.
         */
        const val DEFAULT_SESSION_ID: Int = 1

        /**
         * Finds the head unit's video service by codec, not by channel id.
         *
         * Channel ids are assigned by the head unit and Headway's `ChannelId`
         * table is its own convention, so matching on it would work against the
         * emulator and fail against a car that numbers things differently. The
         * advertisement is the authority, and the thing that identifies a video
         * sink is that it is a media sink offering H.264.
         */
        fun videoServiceOf(profile: HeadUnitProfile): ServiceOuterClass.Service? =
            profile.services.firstOrNull {
                it.hasMediaSinkService() &&
                    it.mediaSinkService.availableType == MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP
            }

        /** Builds the stream for a profile, or null when the car offers no video. */
        fun of(
            profile: HeadUnitProfile,
            connectionFor: (Int) -> MessageChannel,
            projection: MediaProjection,
            onStep: (String) -> Unit = {},
        ): CarVideoStream? {
            val service = videoServiceOf(profile) ?: return null
            val channel = VideoChannel(connectionFor(service.id), service.id, onStep)
            return CarVideoStream(channel, service, projection, onStep)
        }
    }
}

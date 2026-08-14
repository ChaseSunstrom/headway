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

import aap_protobuf.service.media.sink.MediaSinkServiceOuterClass.MediaSinkService
import aap_protobuf.service.ServiceOuterClass
import aap_protobuf.service.media.shared.message.ConfigOuterClass.Config
import aap_protobuf.service.media.shared.message.MediaCodecTypeOuterClass.MediaCodecType
import aap_protobuf.service.media.sink.message.VideoCodecResolutionTypeOuterClass.VideoCodecResolutionType
import aap_protobuf.service.media.sink.message.VideoConfigurationOuterClass.VideoConfiguration
import aap_protobuf.service.media.sink.message.VideoFrameRateTypeOuterClass.VideoFrameRateType
import dev.headway.protocol.channel.AvMessageId
import dev.headway.protocol.channel.VideoChannel
import dev.headway.protocol.io.Cryptor
import dev.headway.protocol.framing.AapMessage
import dev.headway.protocol.framing.ChannelId
import dev.headway.protocol.io.MessageChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `Start` is a promise, and it must not be made before it can be kept.
 *
 * ## The drive this comes from
 *
 * A 2021 Chevrolet Infotainment 3 unit closed the TCP connection **464 ms**
 * after Headway sent `Start`, twice in one drive, having received no video
 * frame. The order was: negotiate the configuration, send `Start`, and only then
 * build the picture source — which for the car-display route goes through
 * `Dialog.show`, which must run on the main thread, which `CarSurface` waits up
 * to three seconds for. The head unit was told video was beginning and then
 * heard nothing while the session thread sat on a `CountDownLatch`.
 *
 * So the rule this test exists to keep is narrow and absolute: **nothing may
 * send `Start` until a source of pixels exists.** Getting that wrong again costs
 * a drive to find out, which is the most expensive unit of debugging this
 * project has.
 *
 * ## What is and is not covered
 *
 * The no-source path is checked end to end, because it is the one a JVM test can
 * drive: `CarSurface` is a `Presentation`, so a factory cannot return a real one
 * off-device. The ordering itself is checked by having the factory record what
 * had been sent at the moment it ran — which pins the invariant regardless of
 * what the factory then returns, and is the assertion that would have failed
 * before the fix.
 */
class CarVideoStreamOrderTest {

    /**
     * The factory runs before `Start` goes out.
     *
     * The whole regression in one assertion.
     */
    @Test
    fun `the picture source is built before Start is sent`() = runBlocking {
        val wire = RecordingChannel()
        var sentWhenFactoryRan: List<Int>? = null

        val stream = CarVideoStream(
            channel = VideoChannel(wire),
            service = videoService(),
            projection = null,
            surfaceFactory = { _, _ ->
                sentWhenFactoryRan = wire.sent.map { it.messageId }
                // Null, so the stream falls through to the mirroring branch and
                // then to "nothing to send". The recording above has already
                // captured what matters.
                null
            },
        )

        wire.answerConfigOnce()
        val started = stream.start(this)

        assertNotNull(sentWhenFactoryRan, "the surface factory was never consulted")
        assertTrue(
            sentWhenFactoryRan!!.contains(AvMessageId.SETUP),
            "Setup should already have gone out by then; sent=$sentWhenFactoryRan",
        )
        assertFalse(
            sentWhenFactoryRan!!.contains(AvMessageId.START),
            "Start was sent before the picture source existed — this is the 464 ms " +
                "disconnect. sent=$sentWhenFactoryRan",
        )
        assertFalse(started, "no source, so the stream cannot have started")
    }

    /**
     * With no source at all, `Start` is never sent.
     *
     * The head unit is told nothing rather than told a lie. Before the fix it
     * was told to expect video from a phone that had neither a car display nor a
     * capture grant, and the only way it could find out was by waiting.
     */
    @Test
    fun `no source means no Start on the wire`() = runBlocking {
        val wire = RecordingChannel()
        val stream = CarVideoStream(
            channel = VideoChannel(wire),
            service = videoService(),
            projection = null,
            surfaceFactory = null,
        )

        wire.answerConfigOnce()
        assertFalse(stream.start(this))

        val ids = wire.sent.map { it.messageId }
        assertEquals(listOf(AvMessageId.SETUP), ids, "only Setup should have been sent; sent=$ids")
    }

    /**
     * The focus request is suppressible, and suppressing it changes only that.
     *
     * `videoFocusRequest = false` is the one knob that lets a driver tell the
     * two candidate causes of that disconnect apart without a rebuild, so it has
     * to actually reach the wire decision.
     */
    @Test
    fun `the focus request is not sent when the quirk turns it off`() = runBlocking {
        val wire = RecordingChannel()
        val stream = CarVideoStream(
            channel = VideoChannel(wire),
            service = videoService(),
            projection = null,
            surfaceFactory = null,
            requestFocus = false,
        )

        wire.answerConfigOnce()
        stream.start(this)

        assertFalse(
            wire.sent.any { it.messageId == AvMessageId.VIDEO_FOCUS_REQUEST },
            "no focus request should reach the wire",
        )
    }

    // --- fixtures ---------------------------------------------------------

    /** A head unit offering one 800x480 H.264 configuration, as the Malibu does. */
    private fun videoService(): ServiceOuterClass.Service =
        ServiceOuterClass.Service.newBuilder()
            .setId(ChannelId.MEDIA_SINK_VIDEO.id)
            .setMediaSinkService(
                MediaSinkService.newBuilder()
                    .setAvailableType(MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP)
                    .addVideoConfigs(
                        VideoConfiguration.newBuilder()
                            // The resolution is an enum, not a width and a
                            // height: the head unit picks from a fixed table.
                            .setCodecResolution(VideoCodecResolutionType.VIDEO_800x480)
                            .setFrameRate(VideoFrameRateType.VIDEO_FPS_30)
                            .setDensity(DENSITY)
                            .build()
                    )
                    .build()
            )
            .build()

    /**
     * Records what the phone sends and plays back one scripted reply.
     *
     * A fake [MessageChannel] rather than a framed loopback pair: the question
     * here is the *order of messages the phone emits*, and framing, fragmenting
     * and encryption are all covered by `VideoChannelTest` in core-protocol
     * against real bytes. Putting them in the way here would only add ways for
     * this test to fail for reasons that are not its subject.
     */
    private class RecordingChannel : MessageChannel {
        override var cryptor: Cryptor? = null

        val sent = mutableListOf<AapMessage>()
        private val inbound = Channel<AapMessage>(Channel.UNLIMITED)

        override suspend fun send(message: AapMessage) {
            sent += message
        }

        override suspend fun receive(): AapMessage = inbound.receive()

        /** Queues the `Config` the head unit answers `Setup` with. */
        fun answerConfigOnce() {
            val config = Config.newBuilder()
                .setStatus(Config.Status.STATUS_READY)
                .setMaxUnacked(MAX_UNACKED)
                .addConfigurationIndices(0)
                .build()
            inbound.trySend(
                AapMessage(
                    channelId = ChannelId.MEDIA_SINK_VIDEO.id,
                    control = false,
                    encrypted = true,
                    messageId = AvMessageId.CONFIG,
                    payload = config.toByteArray(),
                )
            )
        }
    }

    private companion object {
        const val DENSITY = 160
        const val MAX_UNACKED = 4
    }
}

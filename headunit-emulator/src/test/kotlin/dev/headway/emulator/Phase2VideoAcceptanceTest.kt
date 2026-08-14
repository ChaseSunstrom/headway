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

package dev.headway.emulator

import aap_protobuf.service.media.shared.message.MediaCodecTypeOuterClass.MediaCodecType
import aap_protobuf.service.media.video.message.VideoFocusModeOuterClass.VideoFocusMode
import aap_protobuf.service.media.video.message.VideoFocusReasonOuterClass.VideoFocusReason
import dev.headway.protocol.channel.VideoChannel
import dev.headway.protocol.framing.ChannelId
import dev.headway.protocol.io.FramedConnection
import dev.headway.protocol.session.AapSession
import dev.headway.protocol.session.PhoneIdentity
import dev.headway.transport.LoopbackTransport
import dev.headway.transport.tls.AapTls
import dev.headway.transport.tls.TlsSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * **Phase 2 acceptance — video.**
 *
 * CLAUDE.md:
 *
 * > **Phase 2 — Video.** *Accepted when:* the emulator displays the live phone
 * > screen at >= 25 fps for 10 minutes without a stall > 1 s.
 *
 * ## What this test does and does not prove
 *
 * Ten real minutes cannot run in CI, and the fps that criterion is really about
 * — how fast a Pixel's `MediaCodec` can encode its own screen — is not
 * measurable from a JVM test at all. It is Tier C/D evidence (CLAUDE.md's
 * confidence tiers): it needs the phone, a car or the emulator on real hardware,
 * and a stopwatch. BLOCKERS.md B-001 covers the missing hardware.
 *
 * What *is* measurable here is the channel: whether it carries a 30 fps stream's
 * worth of frames in order, with their presentation timestamps intact, with no
 * gap in the presentation timeline exceeding one second, and with every H.264
 * byte arriving unmodified. Those are properties of the transport and the
 * channel state machine, and they are the ones that would silently break.
 *
 * So the stream is driven in **simulated time**. A 10-minute stream at 30 fps is
 * 18 000 frames ([FULL_FRAME_COUNT]); by default [DEFAULT_FRAME_COUNT] frames
 * are transmitted, each carrying its true position on that 18 000-frame timeline
 * (frame *i* takes grid slot `i * 17999 / (n - 1)`). The frames therefore span
 * the whole ten minutes of stream time, the cadence asserted is the real 30 fps
 * grid, and `-Dheadway.video.frames=18000` transmits every frame of the full
 * stream. The full run is not expensive — it was measured at under four seconds
 * — so raising the default is a reasonable call for the integrator to make; the
 * subsample exists to keep the unit-test suite's total time honest, not because
 * the whole stream is unaffordable.
 *
 * Wall-clock timing is deliberately not asserted. Both ends run in one process
 * over an in-memory pipe, so wall-clock throughput here measures this machine's
 * scheduler, not a phone's encoder.
 */
class Phase2VideoAcceptanceTest {

    // --- the acceptance criterion -------------------------------------------

    @Test
    fun `the channel carries a ten minute thirty fps stream in order with no stall over one second`() {
        val frames = FRAME_COUNT
        val session = videoSession(mediaMessages = frames + 1) { video -> streamFrames(video, frames) }
        val data = session.sink.dataFrames

        assertEquals(frames, data.size, "every frame must arrive")

        // Order and integrity: frame i must be the i-th thing the sink saw, with
        // the timestamp and the bytes the phone gave it. Index 0 is the codec
        // configuration, so data frame i is arrival index i + 1.
        for ((i, frame) in data.withIndex()) {
            assertEquals(i + 1, frame.index, "frame $i arrived out of order")
            assertEquals(
                presentationTimestamp(i, frames),
                frame.timestampMicros,
                "frame $i carries the wrong presentation timestamp",
            )
            assertArrayEquals(accessUnit(i), frame.bytes, "frame $i payload was altered")
        }

        // Cadence, measured in stream time.
        val gaps = data.zipWithNext { earlier, later -> later.timestampMicros - earlier.timestampMicros }
        assertTrue(gaps.all { it > 0 }, "presentation timestamps must strictly increase")
        val worstGap = gaps.max()
        assertTrue(
            worstGap <= STALL_LIMIT_MICROS,
            "worst gap in the presentation timeline was $worstGap us, over the ${STALL_LIMIT_MICROS} us stall limit",
        )
        assertTrue(
            data.all { it.timestampMicros % FRAME_INTERVAL_MICROS == 0L },
            "every timestamp must land on the 30 fps grid it was generated from",
        )

        // The timeline covered is the full ten minutes, at a grid rate above the
        // 25 fps the criterion asks for.
        val span = data.last().timestampMicros - data.first().timestampMicros
        assertTrue(
            span >= STREAM_DURATION_MICROS - FRAME_INTERVAL_MICROS * SUBSAMPLE_SLACK,
            "the stream spans ${span / 1_000_000} s of presentation time, short of ten minutes",
        )
        assertTrue(
            1_000_000.0 / FRAME_INTERVAL_MICROS >= MIN_FPS,
            "the grid the frames were generated on is below $MIN_FPS fps",
        )

        // Nothing was left outstanding, and the head unit acknowledged the codec
        // configuration as well as every frame — openauto acks both.
        assertEquals(0, session.channel.unacknowledgedMessages)
        assertEquals((frames + 1).toLong(), session.sink.acksSent)
        assertEquals((frames + 1).toLong(), session.channel.acksReceived)
        assertTrue(session.sink.stopped, "the head unit must see the phone's Stop")
    }

    // --- H.264 integrity ----------------------------------------------------

    /**
     * The bytes on this channel are an H.264 Annex-B byte stream and the head
     * unit has to be able to find NAL units in them. This builds a small one —
     * SPS and PPS as the codec configuration, then access units of an AUD plus a
     * slice, alternating IDR and non-IDR — and checks the sink recovers exactly
     * that structure.
     *
     * The synthetic NAL payloads are not decodable video: they carry valid
     * headers and valid Annex-B framing, and nothing more. What is under test is
     * the channel's fidelity, not a decoder.
     *
     * Each payload also contains the byte sequence `00 00 03 01` — the escaped
     * form of a start code that a real encoder emits. A scanner that treated any
     * `00 00` as a boundary would split the slice in two and report a bogus NAL
     * type, so its absence from the parse is the point.
     */
    @Test
    fun `a synthetic Annex-B stream survives the channel bit-identically`() {
        val frames = 3
        val session = videoSession(mediaMessages = frames + 1) { video -> streamFrames(video, frames) }
        val sink = session.sink

        val codecConfig = sink.frames.first()
        assertEquals(EmulatedVideoSink.MediaKind.CODEC_CONFIG, codecConfig.kind)
        assertEquals(0L, codecConfig.timestampMicros, "codec config carries no timestamp header")
        assertArrayEquals(CODEC_CONFIG, codecConfig.bytes)
        assertEquals(listOf(NAL_SPS, NAL_PPS), codecConfig.nalTypes)

        // Frame 0 is a keyframe (index % 25 == 0), 1 and 2 are not.
        assertEquals(listOf(NAL_AUD, NAL_IDR), sink.dataFrames[0].nalTypes)
        assertEquals(listOf(NAL_AUD, NAL_NON_IDR), sink.dataFrames[1].nalTypes)
        assertEquals(listOf(NAL_AUD, NAL_NON_IDR), sink.dataFrames[2].nalTypes)
        assertTrue(sink.dataFrames[0].containsIdr, "the first access unit must be a keyframe")

        // The slice NAL must be one unit, not two: the emulation-prevention
        // sequence inside it is not a start code.
        val slice = sink.dataFrames[0].nalUnits.last()
        assertEquals(4, slice.startCodeLength)
        assertEquals(3, slice.refIdc, "an IDR slice is a reference picture")
        assertEquals(accessUnit(0).size - slice.offset, slice.length)

        // Both start-code lengths appear, which is why the parser handles both.
        assertEquals(3, sink.dataFrames[0].nalUnits.first().startCodeLength)

        // Strongest statement available: the byte stream the head unit would
        // have fed its decoder is the one the phone encoded.
        val expected = CODEC_CONFIG + accessUnit(0) + accessUnit(1) + accessUnit(2)
        assertArrayEquals(expected, sink.mediaBytes())
        assertEquals(
            listOf(NAL_SPS, NAL_PPS, NAL_AUD, NAL_IDR, NAL_AUD, NAL_NON_IDR, NAL_AUD, NAL_NON_IDR),
            sink.nalTypes(),
        )
    }

    // --- the setup exchange -------------------------------------------------

    @Test
    fun `setup, config, focus and start follow the sequence the references implement`() {
        val session = videoSession(mediaMessages = 3) { video -> streamFrames(video, frames = 2) }

        assertEquals(MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP, session.sink.requestedCodec)

        val response = session.channel.setupResponse
        assertNotNull(response, "the head unit must answer Setup with a Config")
        assertTrue(response!!.ready)
        // openauto's window of 1 and single offered index 0.
        assertEquals(1, response.maxUnacked)
        assertEquals(listOf(0), response.configurationIndices)

        // openauto chains an unsolicited focus notification onto the config, and
        // AACS's phone side waits for exactly that before sending Start.
        assertEquals(VideoFocusMode.VIDEO_FOCUS_PROJECTED, session.channel.videoFocus)

        assertEquals(SESSION_ID, session.sink.sessionId)
        assertEquals(0, session.sink.configurationIndex)
        assertTrue(session.sink.unhandledMessageIds.isEmpty(), "the sink saw an unexpected message id")
    }

    @Test
    fun `the phone can ask for video focus mid stream and the head unit answers`() {
        val session = videoSession(mediaMessages = 11) { video ->
            openStream(video)
            repeat(5) { i -> video.sendFrame(accessUnit(i), presentationTimestamp(i, 10)) }
            video.requestVideoFocus(
                VideoFocusMode.VIDEO_FOCUS_PROJECTED,
                VideoFocusReason.PHONE_SCREEN_OFF,
            )
            for (i in 5 until 10) {
                video.sendFrame(accessUnit(i), presentationTimestamp(i, 10))
            }
            video.awaitAllAcks()
            video.sendStop()
        }

        // Two: the one openStream sends during bring-up, and the one this test
        // sends mid-stream. Re-asking is legal and is what a phone does when it
        // has been backgrounded and wants the screen again.
        val requests = session.sink.videoFocusRequests
        assertEquals(2, requests.size)
        assertEquals(VideoFocusMode.VIDEO_FOCUS_PROJECTED, requests[1].mode)
        assertEquals(VideoFocusReason.PHONE_SCREEN_OFF, requests[1].reason)
        // The reply is interleaved with the acks; the frames must not have been
        // disturbed by it.
        assertEquals(10, session.sink.dataFrames.size)
        assertEquals(VideoFocusMode.VIDEO_FOCUS_PROJECTED, session.channel.videoFocus)
    }

    @Test
    fun `a head unit that never volunteers focus is asked for it, and projects`() {
        // The real failure, reproduced. openauto chains a `VideoFocusNotification`
        // onto its Config response, so against the default emulator the car
        // screen lights up whether or not the phone ever asks -- and the missing
        // request was invisible for the whole life of this project.
        //
        // A 2021 Chevrolet Infotainment 3 unit volunteers nothing. It answered
        // Config, accepted Start and acknowledged 1434 frames over fifteen
        // seconds while its screen stayed on "Connecting Android Auto phone".
        // Nothing on the wire looked wrong, because nothing on the wire was
        // wrong: the phone had simply never asked for the display.
        val session = videoSession(
            mediaMessages = 6,
            sinkConfig = VideoSinkConfig(notifyFocusAfterSetup = false),
        ) { video -> streamFrames(video, frames = 5) }

        assertTrue(
            session.sink.videoFocusRequests.isNotEmpty(),
            "the phone streamed to a head unit that had not granted video focus and never asked " +
                "for it; every frame would be decoded and discarded",
        )
        assertTrue(
            session.sink.projecting,
            "the head unit never started projecting, so nothing the phone sent was displayed",
        )
        assertEquals(
            VideoFocusMode.VIDEO_FOCUS_PROJECTED,
            session.channel.videoFocus,
            "the phone did not record the focus the head unit granted",
        )
        assertEquals(
            0L,
            session.sink.framesDiscarded,
            "${session.sink.framesDiscarded} frame(s) arrived before the head unit was " +
                "projecting; focus must be requested before the first frame, not after",
        )
    }

    // --- the phone's stream -------------------------------------------------

    /**
     * Setup through Start — everything before the first media message.
     *
     * Deliberately the same order as [dev.headway.app.video.CarVideoStream],
     * including the focus request, which is not optional and is the one step
     * whose absence a wire trace does not reveal: a head unit that never grants
     * video focus still answers Config, accepts Start, and acknowledges every
     * frame while showing none of them. Keep the two in step; `a head unit that
     * never volunteers focus is asked for it` is what fails if they drift.
     */
    private suspend fun openStream(video: VideoChannel) {
        video.sendSetup()
        val config = video.awaitConfig()
        check(config.ready) { "head unit answered setup with ${config.status}" }
        video.requestVideoFocus(
            mode = VideoFocusMode.VIDEO_FOCUS_PROJECTED,
            reason = VideoFocusReason.PHONE_SCREEN_OFF,
        )
        video.sendStart(
            sessionId = SESSION_ID,
            configurationIndex = config.configurationIndices.first(),
        )
        video.sendCodecConfig(CODEC_CONFIG)
    }

    private suspend fun streamFrames(video: VideoChannel, frames: Int) {
        openStream(video)
        for (i in 0 until frames) {
            video.sendFrame(accessUnit(i), presentationTimestamp(i, frames))
        }
        video.awaitAllAcks()
        video.sendStop()
    }

    // --- harness ------------------------------------------------------------

    private class VideoRun(val sink: EmulatedVideoSink, val channel: VideoChannel)

    /**
     * Brings a session up the way Phase 1 does — real version handshake, real
     * TLS, real service discovery over the fake transport — then runs [phone]
     * against [EmulatedVideoSink] on the video channel the head unit advertised.
     *
     * Going through the whole bring-up rather than starting from a bare
     * connection is what makes the channel id come from the advertisement
     * instead of a constant, which is the one thing about this channel that
     * every reference agrees is *not* fixed by the protocol.
     */
    private fun videoSession(
        mediaMessages: Int,
        timeoutMillis: Long = 300_000,
        sinkConfig: VideoSinkConfig = VideoSinkConfig(),
        phone: suspend (VideoChannel) -> Unit,
    ): VideoRun = runBlocking {
        LoopbackTransport.pair().use { pair ->
            val phoneConnection = FramedConnection(pair.phone)
            val headUnitConnection = FramedConnection(pair.headUnit)

            val headUnit = EmulatedHeadUnit(
                connection = headUnitConnection,
                tls = TlsSession(AapTls.headUnitEngine()),
            )
            val session = AapSession(
                connection = phoneConnection,
                tls = TlsSession(AapTls.phoneEngine()),
                identity = PhoneIdentity(deviceName = "Headway Test"),
            )

            val wanted = listOf(ChannelId.MEDIA_SINK_VIDEO.id)
            val phoneBringUp = async(Dispatchers.IO) { session.connect { wanted } }
            val headUnitBringUp = async(Dispatchers.IO) { headUnit.run(channelOpens = wanted.size) }
            val profile = withTimeout(60_000) { phoneBringUp.await() }
            withTimeout(60_000) { headUnitBringUp.await() }

            val videoService = profile.services.first {
                it.hasMediaSinkService() &&
                    it.mediaSinkService.availableType == MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP
            }
            val video = VideoChannel(phoneConnection, channelId = videoService.id)
            val sink = EmulatedVideoSink(
                headUnitConnection,
                channelId = videoService.id,
                config = sinkConfig,
            )

            val sinkSide = async(Dispatchers.IO) {
                sink.run(mediaMessages = mediaMessages)
                // Every stream here ends with Stop, so one more message is
                // exactly what is left to service.
                sink.handle(headUnitConnection.receive())
            }
            val phoneSide = async(Dispatchers.IO) { phone(video) }

            withTimeout(timeoutMillis) { phoneSide.await() }
            withTimeout(timeoutMillis) { sinkSide.await() }
            VideoRun(sink, video)
        }
    }

    // --- synthetic H.264 ----------------------------------------------------

    private companion object {

        /** 30 fps, `VideoFrameRateType.VIDEO_FPS_30` — the rate the emulator advertises. */
        const val FRAMES_PER_SECOND = 30

        /** One frame's share of a second, in microseconds: 33 333 us at 30 fps. */
        const val FRAME_INTERVAL_MICROS = 1_000_000L / FRAMES_PER_SECOND

        /** The acceptance criterion's ten minutes. */
        const val STREAM_DURATION_MICROS = 600L * 1_000_000L

        /** Frames in ten minutes of 30 fps video. */
        const val FULL_FRAME_COUNT = 600 * FRAMES_PER_SECOND

        /** The criterion's fps floor. */
        const val MIN_FPS = 25.0

        /** The criterion's stall limit, in stream time. */
        const val STALL_LIMIT_MICROS = 1_000_000L

        /**
         * How many frames to transmit. Enough that ordering and window bugs have
         * room to show up, small enough to stay a unit test.
         * `-Dheadway.video.frames=18000` transmits the full ten minutes.
         */
        const val DEFAULT_FRAME_COUNT = 2_000

        val FRAME_COUNT: Int =
            System.getProperty("headway.video.frames")?.toIntOrNull() ?: DEFAULT_FRAME_COUNT

        /**
         * Integer division when spreading n frames over the 18 000-slot grid
         * leaves the last frame up to one subsample step short of the end.
         */
        const val SUBSAMPLE_SLACK = 10

        const val SESSION_ID = 0

        /**
         * AACS's encoder uses `key-int-max=25`
         * (`AACS/AAServer/src/VideoChannelHandler.cpp` L73-L74). That is one
         * implementation's encoder setting, not a protocol requirement — no
         * reference states a keyframe cadence — but it is the only concrete
         * number available, so the synthetic stream uses it.
         */
        const val KEYFRAME_INTERVAL = 25

        const val NAL_NON_IDR = 1
        const val NAL_IDR = 5
        const val NAL_SPS = 7
        const val NAL_PPS = 8
        const val NAL_AUD = 9

        /** Bytes of synthetic slice data per access unit. Small keeps the test quick. */
        const val SLICE_PAYLOAD_SIZE = 192

        /**
         * The codec configuration blob: an SPS (`nal_ref_idc` 3, type 7) and a
         * PPS (type 8), each behind a 4-byte start code — the shape a
         * `MediaCodec` `csd-0` buffer has.
         */
        val CODEC_CONFIG: ByteArray = byteArrayOf(
            0, 0, 0, 1, 0x67, 0x42, 0x00.toByte(), 0x1E, 0x8D.toByte(), 0x68, 0x05, 0x00, 0x5B,
            0, 0, 0, 1, 0x68, 0xCE.toByte(), 0x06, 0xE2.toByte(),
        )

        /**
         * The presentation timestamp of transmitted frame [index] of [count],
         * placed on the 30 fps grid of the full ten-minute stream.
         */
        fun presentationTimestamp(index: Int, count: Int): Long {
            val slot = if (count <= 1) 0L else index.toLong() * (FULL_FRAME_COUNT - 1) / (count - 1)
            return slot * FRAME_INTERVAL_MICROS
        }

        /**
         * A deterministic synthetic access unit: an access unit delimiter behind
         * a 3-byte start code, then a slice behind a 4-byte start code.
         *
         * The slice payload starts with the frame index (so a reordered frame is
         * visible in a failure message), then `00 00 03 01`, then filler with no
         * zero bytes — a random zero run could otherwise synthesise a start code
         * and make this test's own fixture the bug.
         */
        fun accessUnit(index: Int): ByteArray {
            val keyframe = index % KEYFRAME_INTERVAL == 0
            val out = ArrayList<Byte>(SLICE_PAYLOAD_SIZE + 32)

            // AUD, 3-byte start code. primary_pic_type in the payload byte.
            out += listOf<Byte>(0, 0, 1, NAL_AUD.toByte(), 0xF0.toByte())

            // Slice, 4-byte start code. nal_ref_idc 3 on an IDR (0x65), 2 on a
            // non-IDR reference slice (0x41).
            out += listOf<Byte>(0, 0, 0, 1)
            out += if (keyframe) 0x65.toByte() else 0x41.toByte()

            // The index is written one nibble per byte, each lifted into
            // 0x40..0x4F. Writing it as a plain big-endian int would emit
            // 00 00 00 01 for frame 1 -- a genuine 4-byte start code, which the
            // parser would correctly split on, making this fixture manufacture
            // the very bug the test is meant to rule out.
            for (shift in intArrayOf(12, 8, 4, 0)) {
                out += (0x40 or ((index ushr shift) and 0x0F)).toByte()
            }

            // Emulation prevention: the escaped form of 00 00 01.
            out += listOf<Byte>(0, 0, 3, 1)

            val filler = Random(index).nextBytes(SLICE_PAYLOAD_SIZE)
            for (b in filler) {
                out += (0x20 + (b.toInt() and 0x3F)).toByte()
            }
            return out.toByteArray()
        }
    }
}

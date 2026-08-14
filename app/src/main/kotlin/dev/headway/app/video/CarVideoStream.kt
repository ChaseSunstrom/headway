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
import aap_protobuf.service.media.video.message.VideoFocusModeOuterClass.VideoFocusMode
import aap_protobuf.service.media.video.message.VideoFocusReasonOuterClass.VideoFocusReason
import android.media.projection.MediaProjection
import dev.headway.app.dash.CarSurface
import dev.headway.protocol.channel.VideoChannel
import dev.headway.protocol.io.MessageChannel
import dev.headway.protocol.session.HeadUnitProfile
import dev.headway.video.EncoderConfiguration
import dev.headway.video.ScreenEncoder
import java.util.concurrent.atomic.AtomicReference
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
    /**
     * The screen-capture grant, for the mirroring path only.
     *
     * Null in dashboard mode, which needs no projection to draw: it renders onto
     * a display Headway creates. Audio capture still wants a projection, but
     * that is the audio stream's business, not this one's.
     */
    private val projection: MediaProjection?,
    /**
     * Builds the car-native surface, or returns null to fall back to mirroring.
     *
     * A factory rather than a `CarSurface` because the surface cannot exist
     * until the head unit has said which configuration it wants — the display
     * has to be created at the car's resolution, and that number arrives in the
     * `Config` reply partway through [start].
     */
    private val surfaceFactory: ((EncoderConfiguration, ScreenEncoder.Sink) -> CarSurface?)? = null,
    /**
     * Whether to ask the head unit to put the projection on its display.
     *
     * The `videoFocusRequest` quirk. Defaults on; see `HeadUnitQuirks` for the
     * two competing explanations of a 464 ms disconnect that this exists to let
     * one drive distinguish.
     */
    private val requestFocus: Boolean = true,
    private val onStep: (String) -> Unit = {},
) {

    /** The car-native surface, once built. Null while mirroring. */
    var surface: CarSurface? = null
        private set

    /**
     * The mirroring encoder, created once and reused for the whole session.
     *
     * Not rebuilt on every switch back to mirroring, and this is not an
     * optimisation. Since Android 14 `MediaProjection.createVirtualDisplay`
     * throws `SecurityException` on a projection that has already produced a
     * display, so a second `ScreenEncoder` handed the same projection would take
     * `startCapture`'s create branch — its own `virtualDisplay` field being
     * null — and fail. The same instance takes the *reuse* branch instead,
     * re-pointing the display it already owns at the new codec's surface, which
     * is exactly what `ScreenEncoder.heldProjection` was written for.
     *
     * Released rather than merely stopped in [stop], because that is the point
     * at which the projection really is going away.
     */
    private var encoder: ScreenEncoder? = null
    private var pump: VideoPump? = null

    /** The pump's job, which outlives every source change. */
    private var pumpJob: Job? = null

    /** The current source's job, cancelled and replaced by [show]. */
    private var sourceJob: Job? = null

    /** Held so [show] can start a new source after the initial bring-up. */
    private var streamScope: CoroutineScope? = null

    /**
     * The geometry the head unit actually chose, once it has.
     *
     * Published because it is the only place the car's real size and density are
     * known: they come from the advertised configuration the unit selected in
     * its `Config` reply, not from anything the phone decides. The launcher
     * sizes its touch targets from it, and so does the floating voice button —
     * a 48 dp target on a 1344-pixel-wide phone is a smear on an 800-pixel car
     * panel, and a driver aiming at it is not looking down.
     */
    @Volatile
    var negotiated: EncoderConfiguration? = null
        private set

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
        negotiated = encoderConfiguration
        onStep(
            "video: ${encoderConfiguration.width}x${encoderConfiguration.height} at " +
                "${encoderConfiguration.frameRate} fps, ${encoderConfiguration.densityDpi} dpi, " +
                "window ${response.maxUnacked ?: 1}"
        )

        val videoPump = VideoPump(channel, onStep)
        pump = videoPump
        streamScope = scope

        // Build the picture source BEFORE telling the head unit to start.
        //
        // Start is the last thing before pixels rather than the first thing
        // after a promise: by the time it is sent, either a surface exists and
        // is drawing, or the encoder is already capturing, or we have returned
        // false without claiming a stream at all. The pump job is launched after
        // Start so nothing is transmitted before the head unit has been told the
        // session id it belongs to.
        //
        // A correction, because the first version of this comment stated the
        // wrong cause as fact and a reader would have inherited it. On the
        // 2026-08-14 drive the session died 21 ms after Start, and the reason
        // was **not** that Start had been promised too early — it was that
        // `CarSurface.create` threw immediately, from a `Presentation`
        // constructed off the main thread, and the exception tore down the whole
        // AAP session. Nor did the head unit hang up: "channel CONTROL closed"
        // was Headway's own teardown message. That is fixed in `CarSurface` and
        // in `HeadwayService.startSubsystem`.
        //
        // This ordering stays because it is right on its own terms — a head unit
        // told to expect video should get video — and because it is what makes
        // the "no source, so nothing was claimed" path honest.
        val source = openSource(encoderConfiguration, videoPump)
        if (source == null) {
            onStep(
                "no car display and no screen capture grant, so there is nothing to send. " +
                    "The car will stay on its connecting screen"
            )
            return false
        }

        channel.sendStart(sessionId = sessionId, configurationIndex = index)

        pumpJob = scope.launch { videoPump.pump() }
        // Both sources need draining, and only one of them used to get it.
        // MediaCodec holds every encoded frame in an output buffer until it is
        // dequeued; the dequeue loop is `encodeUntilStopped`. Without it the
        // dashboard path produced a display, a window and a running codec, and
        // sent nothing — `video: 0 frame(s) sent` — which a head unit shows as
        // "Connecting Android Auto phone", forever.
        sourceJob = when (source) {
            SourceKind.DASHBOARD -> surface?.let { car -> scope.launch { car.encodeUntilStopped() } }
            SourceKind.FALLBACK_CAPTURE -> encoder?.let { it2 -> scope.launch { it2.encodeUntilStopped() } }
        }
        publishSwitch()
        onStep(
            if (source == SourceKind.DASHBOARD) {
                "video stream started from the car display"
            } else {
                "video stream started from a raw capture of the phone screen"
            }
        )

        // Say so, loudly, if the picture never starts.
        //
        // This is the shape of failure that has cost the most drives: every
        // step succeeds, the log fills with cheerful lines, the head unit stays
        // on "Connecting Android Auto phone" and nothing anywhere says why. It
        // happened for real because the dashboard encoder was never drained, so
        // a running codec produced zero frames — and the only trace was a
        // periodic "0 frame(s) sent" among lines that all looked fine.
        //
        // A head unit with no video cannot distinguish a phone that is thinking
        // from one that is broken, and neither could the log. Now it can.
        scope.launch {
            kotlinx.coroutines.delay(FIRST_FRAME_WARNING_MILLIS)
            if (videoPump.sentFrames == 0L) {
                onStep(
                    "NO VIDEO: the encoder is running but not one frame has reached the car " +
                        "after ${FIRST_FRAME_WARNING_MILLIS / 1000}s. The head unit will sit on " +
                        "its connecting screen until this is fixed. Source is " +
                        "${if (source == SourceKind.DASHBOARD) "the car display" else "a raw screen capture"}"
                )
            }
        }

        // Asking for the screen, once there is a screen to ask about.
        //
        // openauto volunteers a `VideoFocusNotification` by chaining it onto the
        // Config response
        // (`openauto/src/autoapp/Service/MediaSink/VideoMediaSinkService.cpp`
        // L120-L125), so against the emulator the projection appears without
        // this and its absence was invisible. The Chevrolet unit volunteers
        // nothing: on build 84 it answered Config, accepted Start, and
        // acknowledged 1434 frames over fifteen seconds while its screen stayed
        // on "Connecting Android Auto phone" — every frame decoded and thrown
        // away, because nothing had asked it to put the projection on the
        // display.
        //
        // After Start rather than before it, and behind a quirk, because the
        // request is also the prime suspect for that same unit hanging up: the
        // 464 ms disconnect appeared in the first drive that carried this
        // message, and build 84 without it streamed for fifteen seconds. Which
        // of the two it was — this or the Start-before-pixels stall above — one
        // drive now distinguishes, because the stall is gone and this can be
        // turned off from the quirk file without a rebuild.
        if (requestFocus) {
            channel.requestVideoFocus(
                mode = VideoFocusMode.VIDEO_FOCUS_PROJECTED,
                reason = VideoFocusReason.PHONE_SCREEN_OFF,
            )
        } else {
            onStep("video focus request suppressed by the videoFocusRequest quirk")
        }
        return true
    }

    /**
     * Where the encoder's pixels come from.
     *
     * [DASHBOARD] is the only one anybody chooses. [FALLBACK_CAPTURE] happens
     * when the platform refuses Headway a display at all: rather than send the
     * car nothing, the session captures the phone's screen the way build 84 did
     * — no panes, no rail, no app pane, and a line in the log saying so. It is a
     * degraded mode, not a feature, and there is no way to switch into it.
     */
    private enum class SourceKind { DASHBOARD, FALLBACK_CAPTURE }

    /**
     * Gets pixels flowing into [videoPump], or returns null if nothing can.
     *
     * The car-native surface first: it draws the dashboard at the head unit's
     * own resolution, so nothing is scaled and the phone's screen is not in the
     * picture. Mirroring is the fallback, not the plan — see `CarSurface` for
     * why that inversion happened.
     */
    private fun openSource(
        encoderConfiguration: EncoderConfiguration,
        videoPump: VideoPump,
    ): SourceKind? {
        // Both attempts are wrapped, and neither used to be. `CarSurface.create`
        // threw from a `Presentation` constructed off the main thread and said
        // nothing on the way out; `ScreenEncoder.startCapture` throws
        // `ScreenEncoderException` for four separate reasons and narrates none
        // of them. In both cases the exception left this function, left
        // `start`, and ended the AAP session — so the loudest symptom of "no
        // video" was "no car". Falling back, or returning null, is always
        // better than that.
        val native = runCatching { surfaceFactory?.invoke(encoderConfiguration, videoPump) }
            .getOrElse {
                onStep("the car display could not be built, so falling back to mirroring: $it")
                null
            }
        if (native != null) {
            surface = native
            return SourceKind.DASHBOARD
        }

        val token = projection ?: return null
        val screenEncoder = ScreenEncoder(encoderConfiguration, onStep = onStep)
        encoder = screenEncoder
        // The order matters: the encoder must have a sink before it produces
        // anything, and startCapture begins producing immediately.
        val capturing = runCatching { screenEncoder.startCapture(token, videoPump) }
        if (capturing.isFailure) {
            onStep("screen capture would not start: ${capturing.exceptionOrNull()}")
            encoder = null
            runCatching { screenEncoder.stop() }
            return null
        }
        onStep(
            "car surface: no car display, so the car will be sent a raw capture of the phone " +
                "screen. There are no panes and no app pane in this mode"
        )
        return SourceKind.FALLBACK_CAPTURE
    }

    /**
     * Publishes this stream, so anything that needs the negotiated geometry can
     * find it.
     *
     * A static handle rather than an injected dependency because the callers are
     * views inside a `Presentation`, which is not constructed by anything that
     * has the session's object graph. It is the same shape, and for the same
     * reason, as `HeadwayService.linkState`.
     */
    private fun publishSwitch() {
        switcher.set(this)
    }

    /** Frames sent and dropped, and whether the car ever took the screen. */
    fun describe(): String {
        val p = pump ?: return "video not started"
        // The focus state is the line that separates "the car ignored us" from
        // "the car drew it": a session can acknowledge every frame and still
        // never leave its connecting screen, which is exactly what a real
        // Chevrolet did before Headway asked for focus.
        //
        // Worded carefully, because the old phrasing — "never reported by the
        // head unit" — was read as evidence the car had ignored a focus request
        // when in fact nothing had read the video channel yet. The field is only
        // ever populated by a read, and the first read happens when the pump
        // waits for credit on the first frame. With zero frames sent it cannot
        // be anything but null, so saying the head unit failed to answer is an
        // accusation the log has no basis for.
        val focus = channel.videoFocus?.name
            ?: if (p.sentFrames == 0L) "not yet read (no frame has been sent)" else "no answer yet"
        return "video: ${p.sentFrames} frame(s) sent, ${p.droppedFrames} dropped, focus $focus"
    }

    /**
     * Stops encoding but leaves the projection alone.
     *
     * Consent is a user gesture and belongs to whoever obtained it —
     * `ScreenEncoder.stop()` is documented as deliberately not touching it, and
     * a reconnect must not put a dialog in front of a driver.
     */
    fun stop() {
        switcher.compareAndSet(this, null)
        pumpJob?.cancel()
        pumpJob = null
        sourceJob?.cancel()
        sourceJob = null
        streamScope = null
        runCatching { surface?.stop() }
        surface = null
        // release(), not stop(): the session is over, so the virtual display the
        // fallback encoder may be holding has to go with it.
        runCatching { encoder?.release() }
        encoder = null
        runCatching { pump?.close() }
    }

    companion object {

        /**
         * The live stream, for anything that wants to change what the car shows.
         *
         * Null between sessions. Cleared by [stop] with a compare-and-set so a
         * new session that has already published cannot be unpublished by the
         * old one's teardown arriving late.
         */
        private val switcher = AtomicReference<CarVideoStream?>(null)

        /** The geometry the head unit chose, or null when no session is up. */
        val currentGeometry: EncoderConfiguration? get() = switcher.get()?.negotiated

        /**
         * Any non-zero value works; the head unit echoes it back on every
         * acknowledgement so the two sides can tell streams apart.
         */
        const val DEFAULT_SESSION_ID: Int = 1

        /**
         * How long to wait before declaring that no video is arriving.
         *
         * Long enough that a slow first keyframe on a cold codec is not
         * reported as a fault, short enough to be in the log well before a
         * driver gives up on the connecting screen.
         */
        const val FIRST_FRAME_WARNING_MILLIS: Long = 5_000

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
            projection: MediaProjection?,
            surfaceFactory: ((EncoderConfiguration, ScreenEncoder.Sink) -> CarSurface?)? = null,
            requestFocus: Boolean = true,
            onStep: (String) -> Unit = {},
        ): CarVideoStream? {
            val service = videoServiceOf(profile) ?: return null
            val channel = VideoChannel(connectionFor(service.id), service.id, onStep)
            return CarVideoStream(channel, service, projection, surfaceFactory, requestFocus, onStep)
        }
    }
}

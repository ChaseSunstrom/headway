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
import dev.headway.protocol.channel.VideoChannel
import dev.headway.protocol.io.MessageChannel
import dev.headway.app.dash.CarSurface
import dev.headway.protocol.session.HeadUnitProfile
import dev.headway.video.EncoderConfiguration
import dev.headway.video.ScreenEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * What the car screen is showing.
 *
 * These are the two things Headway can put on a head unit, and the platform
 * decides that there are exactly two. [DASHBOARD] is content Headway draws
 * itself onto a display it created at the car's own resolution — beautiful,
 * correctly sized, and unable to contain another app's window, because
 * `ActivityTaskSupervisor.isCallerAllowedToLaunchOnDisplay` refuses that
 * (ADR 0004). [MIRROR] is display 0 as it stands, which is the only way another
 * app's own pixels reach the car, and which is the wrong shape because a phone
 * is portrait and a dashboard is not.
 *
 * Android Auto resolves this by having apps write a second UI against a template
 * library, and Headway is now a host for that library too — see ADR 0007 and
 * `dev.headway.app.carapp`, which draws a car app's templates as a dashboard
 * pane rather than as pixels. [MIRROR] is what remains for the apps that
 * publish no templates at all, and even those need not be squeezed: with a
 * simulated secondary display configured (ADR 0008) they are launched onto a
 * car-sized display and captured from there, so what mirroring means in
 * practice is decided by `CarAppDisplay` rather than by this enum.
 */
enum class CarSurfaceMode {
    /** Headway's own dashboard, drawn at the head unit's resolution. */
    DASHBOARD,

    /** The phone's display 0, whatever is on it. */
    MIRROR;

    fun describe(): String = when (this) {
        DASHBOARD -> "the Headway dashboard"
        MIRROR -> "the phone screen"
    }
}

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

    /** What the car is showing now. */
    @Volatile
    var mode: CarSurfaceMode = CarSurfaceMode.DASHBOARD
        private set

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

        // Asking for the screen. openauto volunteers a `VideoFocusNotification`
        // by chaining it onto the Config response
        // (`openauto/src/autoapp/Service/MediaSink/VideoMediaSinkService.cpp`
        // L120-L125), so against the emulator the projection appears without
        // this and its absence was invisible. A real 2021 Chevrolet
        // Infotainment 3 unit volunteers nothing: it answered Config, accepted
        // Start, and acknowledged 1434 frames over fifteen seconds while its
        // screen stayed on "Connecting Android Auto phone". Every one of those
        // frames was decoded and thrown away, because nothing had asked the unit
        // to put the projection on the display.
        //
        // Sent before Start, which is where the documented sequence puts the
        // notification, and not waited for: nothing in the protocol obliges a
        // head unit to answer, and blocking here would spend the ~15 s deadline
        // on a reply that may never come. The answer, when it arrives, is
        // recorded by the channel as it skips past it looking for
        // acknowledgements.
        channel.requestVideoFocus(
            mode = VideoFocusMode.VIDEO_FOCUS_PROJECTED,
            reason = VideoFocusReason.PHONE_SCREEN_OFF,
        )

        channel.sendStart(sessionId = sessionId, configurationIndex = index)

        val videoPump = VideoPump(channel, onStep)
        pump = videoPump

        // The car-native surface first. It draws the dashboard at the head
        // unit's own resolution, so nothing is scaled and the phone's screen is
        // not in the picture; mirroring is the fallback, not the plan. See
        // CarSurface for why that inversion happened.
        streamScope = scope

        val native = surfaceFactory?.invoke(encoderConfiguration, videoPump)
        if (native != null) {
            surface = native
            mode = CarSurfaceMode.DASHBOARD
            pumpJob = scope.launch { videoPump.pump() }
            publishSwitch()
            onStep("video stream started from the car display")
            return true
        }

        val token = projection
        if (token == null) {
            onStep(
                "no car display and no screen capture grant, so there is nothing to send. " +
                    "The car will stay on its connecting screen"
            )
            return false
        }

        val screenEncoder = ScreenEncoder(encoderConfiguration, onStep = onStep)
        encoder = screenEncoder

        // The order matters: the encoder must have a sink before it produces
        // anything, and startCapture begins producing immediately.
        screenEncoder.startCapture(token, videoPump)

        mode = CarSurfaceMode.MIRROR
        sourceJob = scope.launch { screenEncoder.encodeUntilStopped() }
        pumpJob = scope.launch { videoPump.pump() }
        publishSwitch()
        onStep("video stream started by mirroring the phone screen")
        return true
    }

    /**
     * Changes what the car is showing, without touching the AAP stream.
     *
     * The head unit is not told anything. It negotiated one resolution, one
     * frame rate and one session id, and all three are unchanged — only the
     * source of the pixels moves. What the car does see is a fresh SPS/PPS and
     * a keyframe, which is ordinary mid-stream H.264 and is exactly what
     * [VideoPump.resetForNewStream] exists to arrange; without that reset the
     * pump would consider the codec config already sent and the decoder would
     * be handed frames it has no parameter sets for.
     *
     * Callable from any thread. [CarSurface.create] posts its own window work to
     * the main looper and waits for it, and everything else here is either a
     * coroutine launch or a `MediaCodec` call that does not care.
     *
     * @return false when the mode is unreachable — mirroring with no projection
     *   grant, or a dashboard the platform refused — in which case the car keeps
     *   showing what it was showing and the log says why.
     */
    fun show(wanted: CarSurfaceMode): Boolean {
        if (wanted == mode) return true
        val configuration = negotiated
        val videoPump = pump
        val scope = streamScope
        if (configuration == null || videoPump == null || scope == null) {
            onStep("car surface: cannot switch to ${wanted.describe()} before video has started")
            return false
        }

        when (wanted) {
            CarSurfaceMode.MIRROR -> {
                val token = projection
                if (token == null) {
                    onStep(
                        "car surface: showing an app needs the screen-capture grant, and this " +
                            "session started without one. Press Connect again and accept it"
                    )
                    return false
                }
                // The dashboard goes first. Its encoder holds the only surface
                // on the virtual display, and a second encoder feeding the same
                // pump would interleave two streams of frames into one decoder.
                runCatching { surface?.stop() }
                surface = null
                sourceJob?.cancel()
                sourceJob = null
                videoPump.resetForNewStream()

                val screenEncoder = encoder ?: ScreenEncoder(configuration, onStep = onStep)
                val started = runCatching { screenEncoder.startCapture(token, videoPump) }
                if (started.isFailure) {
                    onStep("car surface: screen capture would not start (${started.exceptionOrNull()})")
                    // Nothing is producing frames now, so go back rather than
                    // leave the car on a frozen last frame for the drive.
                    return recoverDashboard(configuration, videoPump)
                }
                encoder = screenEncoder
                sourceJob = scope.launch { screenEncoder.encodeUntilStopped() }
            }

            CarSurfaceMode.DASHBOARD -> {
                sourceJob?.cancel()
                sourceJob = null
                // stop(), not release(): the encoder keeps its virtual display
                // so the next switch back to mirroring can reuse it. See the
                // field's KDoc for why that is load-bearing rather than tidy.
                runCatching { encoder?.stop() }
                videoPump.resetForNewStream()
                val native = surfaceFactory?.invoke(configuration, videoPump)
                if (native == null) {
                    onStep("car surface: the dashboard is unavailable, staying on the phone screen")
                    return false
                }
                surface = native
            }
        }
        mode = wanted
        onStep("car surface: now showing ${wanted.describe()}")
        return true
    }

    /** Puts the dashboard back after a failed switch to mirroring. */
    private fun recoverDashboard(
        configuration: EncoderConfiguration,
        videoPump: VideoPump,
    ): Boolean {
        videoPump.resetForNewStream()
        surface = surfaceFactory?.invoke(configuration, videoPump)
        mode = CarSurfaceMode.DASHBOARD
        return false
    }

    /**
     * Makes [show] reachable from a tile or a floating button.
     *
     * A static handle rather than an injected dependency because the callers are
     * views: a `LauncherTile` inside a `Presentation` and an overlay window on
     * display 0, neither of which is constructed by anything that has the
     * session's object graph. It is the same shape, and for the same reason, as
     * `HeadwayService.linkState`.
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
        val focus = channel.videoFocus?.name ?: "never reported by the head unit"
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
        // encoder has been holding across mode switches has to go with it.
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

        /** What the car is showing, or null when no session is up. */
        val currentMode: CarSurfaceMode? get() = switcher.get()?.mode

        /**
         * Asks the car to show something else.
         *
         * @return false when there is no session, or the mode is unreachable.
         */
        fun showOnCar(mode: CarSurfaceMode): Boolean = switcher.get()?.show(mode) ?: false

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
            projection: MediaProjection?,
            surfaceFactory: ((EncoderConfiguration, ScreenEncoder.Sink) -> CarSurface?)? = null,
            onStep: (String) -> Unit = {},
        ): CarVideoStream? {
            val service = videoServiceOf(profile) ?: return null
            val channel = VideoChannel(connectionFor(service.id), service.id, onStep)
            return CarVideoStream(channel, service, projection, surfaceFactory, onStep)
        }
    }
}

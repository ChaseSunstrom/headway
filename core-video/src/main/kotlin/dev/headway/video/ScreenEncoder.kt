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

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Raised when the encoder is misused or the platform refuses to encode. */
class ScreenEncoderException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * Screen capture to H.264: `MediaProjection` -> `VirtualDisplay` -> `MediaCodec`
 * surface encoder -> Annex-B access units for
 * [dev.headway.protocol.channel.VideoChannel].
 *
 * The capture path never touches pixels in Java. The projection writes into the
 * codec's input surface and the encoder reads from it, so the only bytes that
 * cross into the VM are the compressed ones — which is the difference between
 * making 30 fps on a phone and not.
 *
 * ## Two sources, one pipeline
 *
 * [startCapture] mirrors the phone's own screen and needs a `MediaProjection` to
 * do it. [startOwnContent] encodes a `DisplayManager` virtual display that
 * somebody else created and still owns — the dashboard's, per ADR 0004, which
 * is the only source that keeps rendering with the phone locked. Everything
 * downstream of the input surface is identical; the two differ only in who
 * produces frames and who owns the display.
 *
 * ## Emitting frames
 *
 * Output is pushed to a [Sink] rather than returned, and the [ByteArray] handed
 * to [Sink.onFrame] is the encoder's own scratch buffer, valid only until the
 * call returns. ADR 0001 records JVM allocation pressure on the video path as a
 * real risk at 30 fps; allocating a fresh array per access unit is the exact
 * shape of that risk. The buffer only ever grows, so a session reaches steady
 * state and stops allocating.
 *
 * ## Keyframes
 *
 * `KEY_I_FRAME_INTERVAL` is set from
 * [EncoderConfiguration.keyFrameIntervalSeconds] because the car's decoder
 * cannot start on anything but an IDR: aa-proxy-rs's consumers detect keyframes
 * structurally (Annex-B NAL type 5) and hold playback until one arrives
 * (`aa-proxy-rs/src/media_tap.rs` L884-L905, `aa-proxy-rs/src/mitm.rs`
 * L2644-L2669). Without a bounded interval, a car that joins mid-GOP shows a
 * black screen until the next natural IDR, which for a default encoder can be
 * many seconds.
 *
 * [requestKeyFrame] forces one immediately. That is what a reconnect needs: the
 * session restarts, the head unit's decoder is empty, and waiting up to a full
 * interval would put a black screen in front of the driver every time the link
 * flaps. Reconnection is a first-class feature per CLAUDE.md, so this is not an
 * edge case.
 *
 * ## Static screens
 *
 * `KEY_REPEAT_PREVIOUS_FRAME_AFTER` makes the encoder re-emit the last frame
 * when nothing on screen changes. A surface encoder is driven by the producer,
 * so an idle screen produces *no* buffers at all — and the video channel's
 * acknowledgement window then sits idle with no way to tell "nothing changed"
 * from "the phone died". Whether a real head unit times out on silence is
 * unverified; repeating is cheap (a skipped-frame P is a handful of bytes) and
 * the failure it guards against is severe.
 *
 * ## Lifecycle
 *
 * [start] and [stop] may be called repeatedly on one instance. Every [stop]
 * releases the codec, its input surface and the virtual display, so a
 * reconnect loop that stops and starts for hours leaks nothing. [stop] does
 * **not** stop the [MediaProjection] — user consent is expensive and belongs to
 * whoever obtained it.
 *
 * ## Threading
 *
 * [start], [drain] and [stop] serialise on one lock. Drive [drain] from a single
 * thread (or use [encodeUntilStopped]); [stop] and [requestKeyFrame] may be
 * called from any thread, including a `MediaProjection` callback.
 */
class ScreenEncoder(
    val configuration: EncoderConfiguration,
    /** Narrates each step; wired to the debug log export in the app. */
    private val onStep: (String) -> Unit = {},
) {

    /** Receives everything the encoder produces, on the thread that called [drain]. */
    interface Sink {
        /**
         * The csd-0 blob for
         * [dev.headway.protocol.channel.VideoChannel.sendCodecConfig]: SPS and PPS
         * as Annex-B NAL units. Delivered once per [start], before any frame.
         */
        fun onCodecConfig(codecConfig: ByteArray)

        /**
         * One access unit.
         *
         * @param data the encoder's scratch buffer — **copy it if you keep it**.
         * @param length valid bytes in [data], from index 0.
         * @param presentationTimeUs microseconds since the first frame of this
         *   session, the unit and origin
         *   [dev.headway.protocol.channel.MediaFrame] writes on the wire.
         * @param keyFrame true when this access unit is an IDR.
         */
        fun onFrame(data: ByteArray, length: Int, presentationTimeUs: Long, keyFrame: Boolean)
    }

    private val lock = Any()

    /** Reused across every drain — MediaCodec fills it rather than returning one. */
    private val bufferInfo = MediaCodec.BufferInfo()

    /** Reused: the contents never vary, and a reconnect storm calls [requestKeyFrame] repeatedly. */
    private val syncFrameRequest = Bundle().apply {
        putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
    }

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null

    /**
     * The projection and its one virtual display, kept across encoder restarts.
     *
     * Since Android 14 `createVirtualDisplay` throws `SecurityException` on a
     * projection that has already produced one, so a restart cannot simply make
     * another. Encoder restarts are routine -- every reconnect is one -- so
     * releasing the display on stop would have lost video for the rest of the
     * session, with re-prompting the driver as the only way back.
     *
     * The display therefore outlives the codec: [stopLocked] detaches its
     * surface and leaves it in place, and [start] resizes it and points it at
     * the new encoder's surface. One consent, one display, any number of
     * encoders.
     */
    @Volatile
    private var heldProjection: MediaProjection? = null

    /**
     * A display this encoder only borrowed — see [startOwnContent].
     *
     * Kept apart from [virtualDisplay] because the two differ in exactly one
     * respect that matters: [release] destroys the display it created and must
     * never destroy this one, which belongs to its creator and outlives any
     * number of encoders.
     */
    private var borrowedDisplay: VirtualDisplay? = null
    private var sink: Sink? = null
    private var scratch = ByteArray(INITIAL_SCRATCH_BYTES)
    private var baseTimestampUs = UNSET_TIMESTAMP

    /** The SPS/PPS blob for the current session, once the encoder has produced it. */
    @Volatile
    var codecConfig: ByteArray? = null
        private set

    /** Access units emitted since the last [start]. Codec configuration is not counted. */
    @Volatile
    var framesEncoded: Long = 0L
        private set

    /** Presentation timestamp of the most recent access unit, or -1 before the first. */
    @Volatile
    var lastPresentationTimeUs: Long = UNSET_TIMESTAMP
        private set

    /** True between [start] and [stop]. */
    val running: Boolean get() = synchronized(lock) { codec != null }

    /**
     * Configures and starts the codec, returning the surface it encodes from.
     *
     * Exposed separately from [startCapture] because the surface has exactly one
     * requirement — something must post buffers to it. `MediaProjection` is one
     * such producer; a test drawing with `Canvas` is another, which is the only
     * way this path can be exercised at all without user consent.
     */
    fun start(sink: Sink): Surface = synchronized(lock) {
        check(codec == null) { "encoder is already running" }
        val created = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        } catch (e: Exception) {
            throw ScreenEncoderException("no H.264 encoder on this device", e)
        }
        try {
            configureWithProfileFallback(created)
            val surface = created.createInputSurface()
            created.start()
            codec = created
            inputSurface = surface
            this.sink = sink
            codecConfig = null
            framesEncoded = 0L
            lastPresentationTimeUs = UNSET_TIMESTAMP
            baseTimestampUs = UNSET_TIMESTAMP
            onStep(
                "encoder started ${configuration.width}x${configuration.height} " +
                    "@${configuration.frameRate}fps ${configuration.bitRateBitsPerSecond / 1000}kbps"
            )
            surface
        } catch (e: Exception) {
            // A codec that was created but never handed over would otherwise be
            // stranded: nothing else holds a reference to release it.
            codec = null
            inputSurface = null
            this.sink = null
            runCatching { created.release() }
            throw ScreenEncoderException("could not start the H.264 encoder", e)
        }
    }

    /**
     * [start], then mirror the screen into the encoder through a virtual display.
     *
     * @param displayName shows up in `dumpsys display`; keep it recognisable.
     */
    fun startCapture(
        projection: MediaProjection,
        sink: Sink,
        displayName: String = DISPLAY_NAME,
        callbackHandler: Handler = Handler(Looper.getMainLooper()),
    ): VirtualDisplay {
        val surface = start(sink)
        return synchronized(lock) {
            try {
                // Android 14 rejects createVirtualDisplay unless a callback is
                // registered first, and we need one regardless: the user can revoke
                // the projection from the status bar at any moment and the encoder
                // must not keep draining a dead display.
                val callback = object : MediaProjection.Callback() {
                    override fun onStop() {
                        onStep("media projection stopped by the system or the user")
                        // release(), not stop(): the consent is gone, so the
                        // display cannot be reused and holding it would leak a
                        // display for the life of the process. The next capture
                        // needs a fresh projection anyway.
                        release()
                    }
                }
                projection.registerCallback(callback, callbackHandler)
                projectionCallback = callback
                this.projection = projection

                // Reuse the display this projection already owns, if it has one.
                // Android will not give a second one out, and a restart that
                // asked for another would fail outright.
                val existing = virtualDisplay?.takeIf { heldProjection === projection }
                val display = if (existing != null) {
                    existing.resize(
                        configuration.width,
                        configuration.height,
                        configuration.densityDpi,
                    )
                    existing.surface = surface
                    onStep("re-pointed the existing virtual display '$displayName' at a new encoder")
                    existing
                } else {
                    val created = projection.createVirtualDisplay(
                        displayName,
                        configuration.width,
                        configuration.height,
                        configuration.densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                        surface,
                        null,
                        null,
                    ) ?: throw ScreenEncoderException("the system refused the virtual display")
                    onStep("mirroring into virtual display '$displayName'")
                    created
                }
                heldProjection = projection
                virtualDisplay = display
                display
            } catch (e: Exception) {
                stopLocked()
                throw if (e is ScreenEncoderException) {
                    e
                } else {
                    ScreenEncoderException("could not create the capture display", e)
                }
            }
        }
    }

    /**
     * [start], then encode a virtual display that somebody else created and
     * still owns.
     *
     * The display must be one `DisplayManager` created with
     * `VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY`. `dev.headway.app.dash.CarDisplay`
     * is what creates it, and its documentation carries the AOSP reasoning for
     * why the dashboard cannot live on the projection's display at all on this
     * platform.
     *
     * Two things are deliberately absent compared with [startCapture], and both
     * absences are the point. There is no `MediaProjection.Callback`, because
     * there is no consent that can be revoked from the status bar and nothing to
     * tear down when it is. And there is no one-display-per-projection problem to
     * work around, so this path does not have to reuse a display across encoder
     * restarts to stay alive — it simply reattaches to the one it is handed.
     *
     * @return the same display, resized to the negotiated geometry and pointed
     *   at the codec's input surface. [stop] detaches that surface and leaves the
     *   display in place; nothing here ever releases it.
     *
     *   Detaching is not free, though it is recoverable: `VirtualDisplayAdapter`
     *   derives display state from whether a surface is attached, so a detached
     *   display goes to `STATE_OFF`, `DisplayContent.shouldSleep` becomes true,
     *   and whatever is on it is paused until a surface comes back. The tasks
     *   survive; the activity stops running. A reconnect is a pause and a
     *   resume.
     */
    fun startOwnContent(ownContentDisplay: VirtualDisplay, sink: Sink): VirtualDisplay {
        val surface = start(sink)
        return synchronized(lock) {
            try {
                // Resized rather than assumed to match: the head unit picks the
                // geometry, and a display created for the previous connection has
                // no reason to still be the size this one negotiated.
                ownContentDisplay.resize(
                    configuration.width,
                    configuration.height,
                    configuration.densityDpi,
                )
                ownContentDisplay.surface = surface
                borrowedDisplay = ownContentDisplay
                onStep(
                    "encoding own-content display ${ownContentDisplay.display.displayId} at " +
                        "${configuration.width}x${configuration.height}"
                )
                ownContentDisplay
            } catch (e: Exception) {
                stopLocked()
                throw ScreenEncoderException(
                    "could not attach the encoder to the own-content display", e,
                )
            }
        }
    }

    /**
     * Asks the encoder for an IDR as soon as it can.
     *
     * No-op when not running: a reconnect may race the teardown that caused it,
     * and throwing there would turn a recoverable flap into a crash.
     */
    fun requestKeyFrame() {
        synchronized(lock) {
            val active = codec ?: return
            runCatching { active.setParameters(syncFrameRequest) }
                .onSuccess { onStep("keyframe requested") }
                .onFailure { onStep("keyframe request refused: ${it.message}") }
        }
    }

    /**
     * Drains every output buffer the codec currently has, pushing each to the
     * [Sink], and returns how many access units were emitted.
     *
     * @param timeoutUs how long to wait for the *first* buffer; subsequent
     *   buffers are only taken if they are already there, so a burst is delivered
     *   in one call without ever blocking for a second frame that has not been
     *   captured yet.
     */
    fun drain(timeoutUs: Long = DRAIN_TIMEOUT_US): Int = synchronized(lock) {
        val codec = this.codec
        val sink = this.sink
        if (codec == null || sink == null) return@synchronized 0
        var emitted = 0
        var wait = timeoutUs
        var draining = true
        while (draining) {
            val index = try {
                codec.dequeueOutputBuffer(bufferInfo, wait)
            } catch (e: IllegalStateException) {
                throw ScreenEncoderException("encoder failed while draining", e)
            }
            wait = 0L
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> draining = false

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                    captureCodecConfigFromFormat(codec.outputFormat, sink)

                // INFO_OUTPUT_BUFFERS_CHANGED and anything else negative: nothing
                // to read, and the buffer-array API it refers to is not used here.
                index < 0 -> Unit

                else -> {
                    if (emit(codec, index, sink)) emitted++
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }
        emitted
    }

    /**
     * Drains in a loop until the coroutine is cancelled or [stop] is called.
     *
     * Runs on [Dispatchers.Default] because `dequeueOutputBuffer` blocks for up
     * to [DRAIN_TIMEOUT_US]; on a main-thread dispatcher that is a dropped frame
     * in the phone's own UI for every frame sent to the car.
     */
    suspend fun encodeUntilStopped() {
        withContext(Dispatchers.Default) {
            while (isActive && running) {
                drain()
            }
        }
    }

    /**
     * Releases the codec, its input surface and the virtual display. Idempotent,
     * and safe to call from a projection callback or another thread.
     */
    fun stop() = synchronized(lock) { stopLocked() }

    /**
     * Releases the virtual display as well as the codec.
     *
     * For when the projection itself is going away -- the user revoked consent,
     * or the service is shutting down -- as opposed to an encoder restart, which
     * must keep the display. [stop] is the restart-safe one and is what the
     * session path calls.
     *
     * A display borrowed through [startOwnContent] is untouched here too. It was
     * never this encoder's to destroy, and the dashboard's display in particular
     * carries the activities launched onto it, which releasing would take down
     * with it.
     */
    fun release() = synchronized(lock) {
        stopLocked()
        virtualDisplay?.let { runCatching { it.release() } }
        virtualDisplay = null
        heldProjection = null
    }

    // --- internals ----------------------------------------------------------

    private fun stopLocked() {
        // The virtual display is released, but the projection is deliberately
        // not stopped -- consent is the caller's asset and a reconnect that had
        // to re-prompt the driver would be worse than useless.
        //
        // The display is detached rather than released, for the same reason:
        // Android 14 refuses a second createVirtualDisplay on one projection, so
        // releasing this one would make the next start fail with a
        // SecurityException. Dropping the surface stops it consuming frames from
        // a codec that is about to be released.
        virtualDisplay?.let { runCatching { it.surface = null } }
        // The same detach for a borrowed display, and the whole of the cleanup
        // owed to one: the codec's input surface is released a few lines below,
        // and a display still pointing at it would be consuming from a dead
        // buffer queue. The display itself stays -- still holding whatever
        // Headway launched onto it -- ready for the next startOwnContent.
        borrowedDisplay?.let { runCatching { it.surface = null } }
        borrowedDisplay = null
        projectionCallback?.let { callback ->
            runCatching { projection?.unregisterCallback(callback) }
        }
        projectionCallback = null
        projection = null
        codec?.let { active ->
            // stop() throws on a codec in an error state; release() still has to
            // happen or the hardware encoder stays claimed for the process's life.
            runCatching { active.stop() }
            runCatching { active.release() }
            onStep("encoder stopped after $framesEncoded frames")
        }
        codec = null
        inputSurface?.let { runCatching { it.release() } }
        inputSurface = null
        sink = null
    }

    /** Returns true when an access unit (rather than codec configuration) was emitted. */
    private fun emit(codec: MediaCodec, index: Int, sink: Sink): Boolean {
        if (bufferInfo.size <= 0) return false
        val buffer = codec.getOutputBuffer(index) ?: return false
        ensureScratch(bufferInfo.size)
        buffer.position(bufferInfo.offset)
        buffer.limit(bufferInfo.offset + bufferInfo.size)
        buffer.get(scratch, 0, bufferInfo.size)

        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            // Once per session, so a copy here costs nothing and the blob has to
            // outlive the scratch buffer anyway.
            val config = scratch.copyOf(bufferInfo.size)
            if (codecConfig == null) {
                codecConfig = config
                sink.onCodecConfig(config)
                onStep("codec config ready (${config.size} B)")
            }
            return false
        }

        if (baseTimestampUs == UNSET_TIMESTAMP) baseTimestampUs = bufferInfo.presentationTimeUs
        // Surface input timestamps come from the producer's clock, i.e. boot time.
        // Nothing states what origin the head unit expects, but MediaFrame.data
        // rejects negatives and a decoder handed a timestamp days into a timeline
        // has no reason to render promptly, so each session starts at zero.
        val presentationTimeUs = (bufferInfo.presentationTimeUs - baseTimestampUs)
            .coerceAtLeast(0L)
        val keyFrame = bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        framesEncoded++
        lastPresentationTimeUs = presentationTimeUs
        sink.onFrame(scratch, bufferInfo.size, presentationTimeUs, keyFrame)
        return true
    }

    /**
     * Some encoders never emit a `BUFFER_FLAG_CODEC_CONFIG` buffer and publish
     * the parameter sets only through the output format, where SPS and PPS are
     * split across csd-0 and csd-1. The head unit wants one blob, so they are
     * concatenated in that order — which is the order they appear in a
     * `BUFFER_FLAG_CODEC_CONFIG` buffer.
     */
    private fun captureCodecConfigFromFormat(format: MediaFormat, sink: Sink) {
        if (codecConfig != null) return
        val sps = format.bytesOrNull("csd-0") ?: return
        val config = sps + (format.bytesOrNull("csd-1") ?: ByteArray(0))
        codecConfig = config
        sink.onCodecConfig(config)
        onStep("codec config from output format (${config.size} B)")
    }

    private fun MediaFormat.bytesOrNull(key: String): ByteArray? {
        if (!containsKey(key)) return null
        val buffer = getByteBuffer(key)?.duplicate() ?: return null
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    private fun ensureScratch(size: Int) {
        if (scratch.size >= size) return
        // Grow geometrically: an IDR after a full-screen change can be an order of
        // magnitude larger than the P frames around it, and resizing once per
        // keyframe would reintroduce exactly the churn this buffer exists to avoid.
        scratch = ByteArray(maxOf(size, scratch.size * 2))
    }

    /**
     * Configures the encoder, dropping optional keys until it accepts them.
     *
     * Two keys here are preferences rather than requirements, and an encoder is
     * allowed to reject either:
     *
     * - **profile/level.** Baseline is what the channel negotiated, but some
     *   encoders refuse an explicit pair instead of clamping it. Their default
     *   is baseline anyway.
     * - **`KEY_PREPEND_HEADER_TO_SYNC_FRAMES`.** Self-contained IDRs are worth
     *   having (see [mediaFormat]) but a head unit that receives the codec
     *   config once still decodes a stream without them.
     *
     * The ladder exists because dropping only the first was not enough: adding
     * the prepend key broke `theEncoderStartsStopsAndRestartsWithoutStrandingTheCodec`
     * on the CI emulator's software AVC encoder, and there was no rung that
     * could give it up. An optimisation must never be the reason the car gets
     * no picture, so every combination is tried and the one that worked is
     * logged — on a real head unit that line is the difference between "this
     * encoder is fussy" and an afternoon of guessing.
     */
    private fun configureWithProfileFallback(codec: MediaCodec) {
        val attempts = listOf(
            Attempt(withProfile = true, prependHeaders = true),
            Attempt(withProfile = false, prependHeaders = true),
            Attempt(withProfile = true, prependHeaders = false),
            Attempt(withProfile = false, prependHeaders = false),
        )
        var lastFailure: Exception? = null
        for ((index, attempt) in attempts.withIndex()) {
            try {
                if (index > 0) codec.reset()
                codec.configure(
                    mediaFormat(attempt.withProfile, attempt.prependHeaders),
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE,
                )
                if (index > 0) {
                    onStep(
                        "encoder accepted configuration with profile=" +
                            "${attempt.withProfile}, prependHeaders=" +
                            "${attempt.prependHeaders} after ${index} rejection(s); " +
                            "last was ${lastFailure?.message}"
                    )
                }
                return
            } catch (e: Exception) {
                lastFailure = e
            }
        }
        throw ScreenEncoderException(
            "the encoder rejected every configuration tried", lastFailure,
        )
    }

    private data class Attempt(val withProfile: Boolean, val prependHeaders: Boolean)

    private fun mediaFormat(withProfile: Boolean, prependHeaders: Boolean = true): MediaFormat =
        MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            configuration.width,
            configuration.height,
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, configuration.bitRateBitsPerSecond)
            setInteger(MediaFormat.KEY_FRAME_RATE, configuration.frameRate)
            // Say what the pixels mean, rather than letting the encoder guess.
            //
            // Left unset, the codec logs `expected specified color aspects
            // (0:0:0:0)` -- it has been handed a surface and no statement of
            // what its colours are -- and then writes whatever it decides into
            // the VUI. The head unit believes that, so a guess that differs
            // from the surface's actual encoding shows up in the car as washed
            // out or over-saturated video, with nothing in the log connecting
            // the two.
            //
            // BT.601 limited range, because that is what an SDR H.264 baseline
            // stream at this size is and what a decoder assumes when a stream
            // says nothing. Naming it explicitly means the assumption and the
            // truth are the same statement rather than two guesses that happen
            // to agree.
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT601_NTSC)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, configuration.keyFrameIntervalSeconds)
            // Put SPS/PPS in front of every IDR, not only in the one-off
            // codec-config buffer at the start of the stream.
            //
            // The head unit's decoder needs parameter sets before it can decode
            // anything, and AAP delivers them once. That is fine for a decoder
            // listening from the first byte and useless for one that is not:
            // a head unit that resumes video focus, or reopens the channel
            // mid-session, gets IDRs it cannot decode and shows a black screen
            // until something restarts the encoder. Self-contained IDRs cost a
            // few tens of bytes each and remove the whole class of problem.
            //
            // Optional, and given up by configureWithProfileFallback on an
            // encoder that will not take it.
            if (prependHeaders) setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
            setLong(
                MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER,
                configuration.repeatFrameAfterMicros,
            )
            // Variable rate: a car UI is static most of the time, and spending the
            // full budget on unchanged pixels only crowds the Wi-Fi link the
            // session itself runs over.
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
            )
            // 0 = realtime. Mirroring competes with whatever the user is actually
            // running, and a late frame is worse than a slightly cheaper one.
            setInteger(MediaFormat.KEY_PRIORITY, PRIORITY_REALTIME)
            setInteger(MediaFormat.KEY_OPERATING_RATE, configuration.frameRate)
            if (withProfile) {
                // The channel negotiates MEDIA_CODEC_VIDEO_H264_BP = 3, Baseline
                // Profile (aasdk .../MediaCodecType.proto L5-L14), and AACS pins
                // its encoder to profile=baseline, stream-format=byte-stream
                // (AACS/AAServer/src/VideoChannelHandler.cpp L75-L78). Baseline
                // also has no B-frames, so output order is display order and the
                // presentation timestamps stay monotonic.
                setInteger(
                    MediaFormat.KEY_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
                )
                setInteger(MediaFormat.KEY_LEVEL, avcLevel())
            }
        }

    /**
     * Lowest H.264 level that covers the negotiated size and rate.
     *
     * **Inferred.** No reference states a level (`docs/protocol-notes.md` §5.1,
     * "NO reference states an H.264 LEVEL"), and Android ignores `KEY_PROFILE`
     * unless `KEY_LEVEL` accompanies it. Declaring more than the stream needs
     * risks a car decoder refusing a stream it could actually have played.
     */
    private fun avcLevel(): Int {
        val pixels = configuration.width.toLong() * configuration.height
        val fast = configuration.frameRate > 30
        return when {
            pixels <= 1280L * 720 && !fast -> MediaCodecInfo.CodecProfileLevel.AVCLevel31
            pixels <= 1280L * 720 -> MediaCodecInfo.CodecProfileLevel.AVCLevel32
            pixels <= 1920L * 1080 && !fast -> MediaCodecInfo.CodecProfileLevel.AVCLevel4
            pixels <= 1920L * 1080 -> MediaCodecInfo.CodecProfileLevel.AVCLevel42
            pixels <= 2560L * 1440 -> MediaCodecInfo.CodecProfileLevel.AVCLevel51
            else -> MediaCodecInfo.CodecProfileLevel.AVCLevel52
        }
    }

    companion object {
        /** Name the mirrored display appears under in `dumpsys display`. */
        const val DISPLAY_NAME: String = "Headway"

        /**
         * How long [drain] waits for a frame. Long enough that an idle screen does
         * not spin a core, short enough that [stop] is never blocked behind it for
         * a perceptible time.
         */
        const val DRAIN_TIMEOUT_US: Long = 10_000L

        /** `MediaFormat.KEY_PRIORITY`: 0 is realtime, 1 is best-effort. */
        private const val PRIORITY_REALTIME = 0

        /** Comfortably above a 800x480 IDR; grown on demand for anything larger. */
        private const val INITIAL_SCRATCH_BYTES = 256 * 1024

        private const val UNSET_TIMESTAMP = -1L
    }
}

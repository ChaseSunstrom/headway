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

package dev.headway.input

import android.accessibilityservice.GestureDescription
import android.view.Display
import android.content.Context
import android.graphics.Path
import android.view.ViewConfiguration
import dev.headway.protocol.channel.PhoneTouch
import dev.headway.protocol.channel.TouchAction
import dev.headway.protocol.channel.TouchSurface

/** How a completed gesture is best described. Reported for logging and for the debug overlay. */
enum class CarGestureKind {
    /** One finger, no meaningful travel, released before the long-press timeout. */
    TAP,

    /** One finger, no meaningful travel, held past the long-press timeout. */
    LONG_PRESS,

    /** One finger that travelled beyond the touch slop. Includes flings. */
    DRAG,

    /** More than one finger took part. */
    MULTI_TOUCH,
}

/**
 * One dispatchable unit of injected motion.
 *
 * A single physical gesture on the car's screen can become **several** of these:
 * see [GestureBuilder] for why a long drag has to be cut into continued slices.
 * [chainId] is constant across those slices, and [continues] is true on every
 * slice except the last, so a consumer can tell "more of the same finger is
 * coming" from "the finger has lifted".
 */
class CarGesture internal constructor(
    val description: GestureDescription,
    val kind: CarGestureKind,
    /** Identifies the physical gesture. Slices of one drag share it. */
    val chainId: Long,
    /** 0 for the first slice of a gesture, incrementing for each continuation. */
    val sliceIndex: Int,
    /** True when at least one stroke was submitted with `willContinue`. */
    val continues: Boolean,
    /** Wall time this slice spans, in milliseconds, as the head unit timed it. */
    val durationMillis: Long,
    /** Fingers represented by strokes in this slice. */
    val pointerCount: Int,
    /** Path vertices across all strokes. 1 means a zero-length (tap) path. */
    val pointCount: Int,
) {
    override fun toString(): String =
        "CarGesture($kind chain=$chainId slice=$sliceIndex strokes=${description.strokeCount} " +
            "points=$pointCount ${durationMillis}ms${if (continues) " continues" else ""})"
}

/**
 * Thresholds [GestureBuilder] works to.
 *
 * The defaults are the platform's own view thresholds where one exists, so an
 * injected gesture is classified by the target app exactly as a finger on the
 * phone's own glass would be. Use [forDevice] to pick up the device's real touch
 * slop, which is density-dependent and therefore not a constant.
 */
data class GestureConfig(
    /**
     * Travel below which a gesture is still a press rather than a drag.
     * `ViewConfiguration.get(context).scaledTouchSlop` on the real device; the
     * literal here is the AOSP base value of 8 dp at xhdpi, used only when no
     * [Context] is available.
     */
    val touchSlopPx: Float = 24f,

    /** `ViewConfiguration.getLongPressTimeout()`, the timer the target app runs. */
    val longPressMillis: Long = ViewConfiguration.getLongPressTimeout().toLong(),

    /**
     * Added to the injected hold time of a long press. The target's timer starts
     * when it receives the injected DOWN, which is strictly after we asked for
     * it, so injecting exactly [longPressMillis] races the timer and lands as a
     * tap about half the time.
     */
    val longPressMarginMillis: Long = 50L,

    /**
     * Floor on the injected duration of a stationary press.
     * `ViewConfiguration.getTapTimeout()`. Needed because head units send the
     * DOWN and the UP of a tap with the **same** timestamp
     * (`aa-proxy-rs/src/mitm.rs` L3554-L3603 sends both packets with one
     * `SystemTime::now()` reading), so the measured duration is routinely 0 and
     * `StrokeDescription` rejects a zero duration outright.
     */
    val minTapMillis: Long = ViewConfiguration.getTapTimeout().toLong(),

    /**
     * How much of a drag is accumulated before it is flushed as a continuing
     * gesture. Trades injection lag against stroke count: nothing at all reaches
     * the target app until a slice is emitted, so a driver scrolling a list sees
     * no movement for this long. Every slice costs one `dispatchGesture` round
     * trip, and the finger is held still at the join while that happens.
     */
    val segmentBudgetMillis: Long = 250L,

    /** Vertices per stroke before a flush is forced, to bound the IPC payload. */
    val maxSamplesPerStroke: Int = 128,

    /** Consecutive samples closer together than this add nothing to the path. */
    val minSampleSpacingPx: Float = 1f,

    /** `GestureDescription.getMaxGestureDuration()`: the platform's hard ceiling. */
    val maxStrokeMillis: Long = GestureDescription.getMaxGestureDuration(),

    /** `GestureDescription.getMaxStrokeCount()`: the platform's simultaneous-finger ceiling. */
    val maxStrokeCount: Int = GestureDescription.getMaxStrokeCount(),

    /**
     * Which display the injected gesture lands on.
     *
     * `Display.DEFAULT_DISPLAY` is the phone's own screen and is what every
     * gesture went to before Headway could render an app on a display of its
     * own. When the car is showing an app on a simulated secondary display
     * (see `OverlayDisplay`), the touch has to be aimed *there* — a gesture
     * dispatched to display 0 while the driver is looking at display 1 lands on
     * whatever happens to be on the phone, which is both useless and alarming.
     *
     * `GestureDescription.Builder.setDisplayId` is public API since API 30 and
     * needs no permission beyond the accessibility grant already held.
     */
    val displayId: Int = Display.DEFAULT_DISPLAY,
) {
    init {
        require(touchSlopPx >= 0f) { "touch slop must not be negative: $touchSlopPx" }
        require(segmentBudgetMillis > 0) { "segment budget must be positive" }
        require(maxSamplesPerStroke >= 2) { "a stroke needs room for at least two samples" }
        require(maxStrokeCount >= 1) { "at least one stroke must be allowed" }
    }

    companion object {
        /** Reads the device's real touch slop; everything else keeps its default. */
        fun forDevice(context: Context): GestureConfig =
            GestureConfig(touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop.toFloat())
    }
}

/**
 * Turns the car's touch event stream into `GestureDescription`s an
 * `AccessibilityService` can inject.
 *
 * ## What the platform will and will not let us do
 *
 * `dispatchGesture` is not a motion-event pipe. There is no unprivileged way to
 * push a DOWN now, a MOVE in 16 ms and an UP when the driver's finger actually
 * lifts: a gesture is handed over as a **complete** path with a duration, and
 * the platform then replays it. Three consequences drive this whole class.
 *
 * 1. **A gesture cannot be emitted while it is still happening.** The stroke is
 *    only known once the finger lifts. Waiting for the lift would mean a scroll
 *    shows nothing until the driver stops, so a drag is instead cut into slices
 *    at [GestureConfig.segmentBudgetMillis] and each slice after the first is
 *    built with `StrokeDescription.continueStroke`. The platform keeps the
 *    finger down between a stroke marked `willContinue` and its continuation, so
 *    the target app sees one uninterrupted gesture rather than a burst of
 *    separate taps. The price is that the slices must be dispatched **in order
 *    and promptly** — that is [CarGestureDispatcher]'s job — and that injection
 *    trails the driver's finger by up to one slice.
 * 2. **Velocity within a stroke is uniform.** The platform walks the path by arc
 *    length, linearly in time, so per-sample speed inside one stroke is lost.
 *    Timing fidelity is therefore per slice, not per sample: each slice carries
 *    the real elapsed time of the samples it contains, which is what keeps the
 *    fast tail of a fling fast. A fling shorter than one slice budget is still
 *    averaged — inferred limitation, not measured against a real velocity
 *    tracker.
 * 3. **A stroke has hard limits.** `getMaxGestureDuration()` caps one stroke and
 *    `getMaxStrokeCount()` caps simultaneous fingers; a zero duration, an empty
 *    path or a negative coordinate is rejected by the `StrokeDescription`
 *    constructor.
 *
 * ## What must not happen
 *
 * A fling must never be emitted as a tap. That is the failure this class exists
 * to prevent: collapsing the move stream to its endpoints, or to just the DOWN
 * point, produces something that *works* for buttons and silently breaks every
 * scrollable surface in the car. Every sample that survives the spacing filter
 * becomes a vertex of the path.
 *
 * ## Timestamps
 *
 * [PhoneTouch.timestampMicros] comes from the **head unit's** clock, which has no
 * defined relationship to the phone's. Only differences are ever used, so the
 * offset cancels; differences are clamped at zero so a unit that sends a
 * non-monotonic timestamp cannot produce a negative duration. A unit that sends
 * milliseconds where the references send microseconds (see the `send_input_key`
 * discrepancy noted on `CarInputEvent.timestampMicros`) would yield durations
 * 1000x too short, which the tap and stroke minimums clamp into something
 * dispatchable but not into something faithful.
 *
 * ## Threading
 *
 * Not thread safe. Feed it from the single coroutine that reads the input
 * channel.
 */
class GestureBuilder(
    val config: GestureConfig = GestureConfig(),
) {
    private val tracks = LinkedHashMap<Int, Track>()

    // A caller may configure a lower ceiling than the platform's, never a higher
    // one: addStroke throws past the real limit, which would lose the whole slice.
    private val strokeCeiling = minOf(config.maxStrokeCount, GestureDescription.getMaxStrokeCount())

    private var chainId = 0L
    private var sliceIndex = 0
    private var lastEventMicros = 0L
    private var active = false

    /** True between the DOWN that starts a gesture and the UP that ends it. */
    val inProgress: Boolean get() = active

    /**
     * Consumes one mapped touch event.
     *
     * @return a gesture to dispatch, or null when this event only added to the
     *   one being accumulated.
     */
    fun accept(touch: PhoneTouch): CarGesture? {
        // A touchpad reports relative motion against its own geometry
        // (InputSourceService.proto L19-L29 flags it ui_navigation), so its
        // coordinates are not positions on the mirrored screen and feeding them
        // to an absolute gesture would inject a jump to an arbitrary point.
        if (touch.surface != TouchSurface.TOUCHSCREEN) return null

        lastEventMicros = touch.timestampMicros

        return when (touch.action) {
            TouchAction.DOWN -> {
                // A DOWN while a gesture is open means we lost its UP — the head
                // unit dropped a report, or the lift landed in a letterbox bar
                // and TouchTransform discarded it. Close the old gesture rather
                // than leaving a finger down forever.
                val orphan = if (active) forceFinish() else null
                begin(touch)
                orphan
            }

            TouchAction.POINTER_DOWN -> {
                if (!active) return null
                touch.changedPointer?.let { startTrack(it.id, touch.timestampMicros, it.x, it.y) }
                sample(touch)
                flushIfBudgetSpent()
            }

            TouchAction.MOVED -> {
                if (!active) return null
                sample(touch)
                flushIfBudgetSpent()
            }

            TouchAction.POINTER_UP -> {
                if (!active) return null
                sample(touch)
                touch.changedPointer?.let { tracks[it.id]?.lift(touch.timestampMicros) }
                flushIfBudgetSpent()
            }

            TouchAction.UP -> {
                if (!active) return null
                sample(touch)
                for (track in tracks.values) track.lift(touch.timestampMicros)
                emit(final = true)
            }
        }
    }

    /**
     * Ends the gesture in progress, lifting every finger at the last event time.
     *
     * The session must call this when it stops consuming input — on video focus
     * loss, on disconnect — or the last stroke stays marked `willContinue` and
     * the platform holds a finger down on whatever app is in front.
     */
    fun flush(): CarGesture? = if (active) forceFinish() else null

    /**
     * Drops the gesture in progress without emitting anything.
     *
     * For use when a dispatch failed: the continuation chain is dead in the
     * platform, so the remaining slices would be orphans.
     */
    fun cancel() {
        tracks.clear()
        active = false
    }

    // --- accumulation -------------------------------------------------------

    private fun begin(touch: PhoneTouch) {
        tracks.clear()
        chainId++
        sliceIndex = 0
        active = true
        for (pointer in touch.pointers) {
            startTrack(pointer.id, touch.timestampMicros, pointer.x, pointer.y)
        }
    }

    private fun startTrack(id: Int, atMicros: Long, x: Double, y: Double) {
        // Beyond the platform's stroke ceiling there is nowhere to put the
        // finger, and admitting the track would make addStroke throw mid-slice.
        if (tracks.size >= strokeCeiling) return
        if (tracks.containsKey(id)) return
        tracks[id] = Track(Sample(atMicros, x.toFloatCoordinate(), y.toFloatCoordinate()))
    }

    private fun sample(touch: PhoneTouch) {
        for (pointer in touch.pointers) {
            // An id we never saw go down: either it started before we attached or
            // its DOWN was discarded. Inventing an origin for it would inject a
            // stroke from a point the driver never touched.
            val track = tracks[pointer.id] ?: continue
            track.add(
                Sample(
                    touch.timestampMicros,
                    pointer.x.toFloatCoordinate(),
                    pointer.y.toFloatCoordinate(),
                ),
                config.minSampleSpacingPx,
            )
        }
    }

    private fun flushIfBudgetSpent(): CarGesture? = if (budgetSpent()) emit(final = false) else null

    private fun budgetSpent(): Boolean {
        val live = tracks.values.filter { !it.finished && it.samples.isNotEmpty() }
        if (live.isEmpty()) return false

        // A finger resting on a button has nothing to show mid-gesture, and
        // slicing it would hand the platform a chain of zero-length paths, each
        // of which it re-reads as a tap location. Hold it whole; only the
        // platform's own ceiling can force a stationary press apart.
        val moved = live.any { it.movedBeyond(config.touchSlopPx) }
        val sliceStart = live.minOf { it.samples.first().timeMicros }
        val elapsed = millisBetween(sliceStart, lastEventMicros)
        if (!moved) return elapsed >= config.maxStrokeMillis - STROKE_CEILING_MARGIN_MILLIS

        return elapsed >= config.segmentBudgetMillis ||
            live.any { it.samples.size >= config.maxSamplesPerStroke }
    }

    private fun forceFinish(): CarGesture? {
        for (track in tracks.values) track.lift(lastEventMicros)
        return emit(final = true)
    }

    // --- emission -----------------------------------------------------------

    private fun emit(final: Boolean): CarGesture? {
        val live = tracks.values.filter { !it.finished && it.samples.isNotEmpty() }
        if (live.isEmpty()) {
            if (final) cancel()
            return null
        }

        val sliceStart = live.minOf { it.samples.first().timeMicros }
        val builder = GestureDescription.Builder().setDisplayId(config.displayId)
        val emitted = ArrayList<Pair<Track, GestureDescription.StrokeDescription>>(live.size)
        var points = 0
        var sliceMillis = 0L

        for (track in live) {
            if (emitted.size >= strokeCeiling) break

            val first = track.samples.first()
            // A finger that lifted earlier in this slice ends at its own lift,
            // not at the slice boundary; one still down is carried to the newest
            // event time so that a finger held still while another drags does
            // not get replayed faster than it happened.
            val end = track.liftMicros ?: lastEventMicros
            val startMillis = millisBetween(sliceStart, first.timeMicros)
            // No room left under the ceiling for a stroke that starts this late.
            // Only reachable when a finger joins a stationary press that has been
            // held for most of getMaxGestureDuration().
            if (startMillis >= config.maxStrokeMillis) continue
            var durationMillis = millisBetween(first.timeMicros, end)

            val willContinue = !track.lifted
            if (!willContinue && !track.movedBeyond(config.touchSlopPx)) {
                durationMillis = stationaryDuration(track, end, durationMillis)
            }
            durationMillis = durationMillis.coerceIn(
                MIN_STROKE_MILLIS,
                (config.maxStrokeMillis - startMillis).coerceAtLeast(MIN_STROKE_MILLIS),
            )

            val path = track.buildPath()
            val previous = track.stroke
            val stroke = if (previous != null) {
                previous.continueStroke(path, startMillis, durationMillis, willContinue)
            } else {
                GestureDescription.StrokeDescription(path, startMillis, durationMillis, willContinue)
            }
            builder.addStroke(stroke)
            emitted += track to stroke
            points += track.samples.size
            sliceMillis = maxOf(sliceMillis, startMillis + durationMillis)
        }

        if (emitted.isEmpty()) {
            if (final) cancel()
            return null
        }

        val description = builder.build()
        var continues = false
        for ((track, stroke) in emitted) {
            if (stroke.willContinue()) continues = true
            track.advance(stroke)
        }

        val gesture = CarGesture(
            description = description,
            kind = classify(),
            chainId = chainId,
            sliceIndex = sliceIndex,
            continues = continues,
            durationMillis = sliceMillis,
            pointerCount = emitted.size,
            pointCount = points,
        )
        sliceIndex++
        if (final) cancel()
        return gesture
    }

    private fun stationaryDuration(track: Track, endMicros: Long, measured: Long): Long {
        // Classification uses the whole press, not just this slice, so a hold
        // that got split by the platform ceiling is still a long press.
        val held = millisBetween(track.origin.timeMicros, endMicros)
        return if (held >= config.longPressMillis) {
            measured + config.longPressMarginMillis
        } else {
            measured.coerceAtLeast(config.minTapMillis)
        }
    }

    private fun classify(): CarGestureKind {
        if (tracks.size > 1) return CarGestureKind.MULTI_TOUCH
        val track = tracks.values.firstOrNull() ?: return CarGestureKind.TAP
        if (track.movedBeyond(config.touchSlopPx)) return CarGestureKind.DRAG
        val held = millisBetween(track.origin.timeMicros, track.liftMicros ?: lastEventMicros)
        return if (held >= config.longPressMillis) CarGestureKind.LONG_PRESS else CarGestureKind.TAP
    }

    private companion object {
        /** `StrokeDescription` rejects a duration of zero. */
        const val MIN_STROKE_MILLIS = 1L

        /**
         * Kept clear of `getMaxGestureDuration()` so the flush happens before the
         * ceiling rather than on it, leaving room for the long-press margin.
         */
        const val STROKE_CEILING_MARGIN_MILLIS = 1_000L

        fun millisBetween(fromMicros: Long, toMicros: Long): Long =
            ((toMicros - fromMicros) / 1_000L).coerceAtLeast(0L)

        /** `StrokeDescription` rejects a path whose bounds are negative or not finite. */
        fun Double.toFloatCoordinate(): Float {
            val value = toFloat()
            return if (value.isFinite()) value.coerceAtLeast(0f) else 0f
        }
    }
}

private class Sample(val timeMicros: Long, val x: Float, val y: Float)

/**
 * One finger's accumulated state.
 *
 * [samples] holds only the current slice. After a flush it is reset to the point
 * the slice ended on, so the continuation's path starts exactly where the
 * previous stroke stopped and no jump is injected at the join.
 */
private class Track(val origin: Sample) {
    val samples = ArrayList<Sample>(32)
    var stroke: GestureDescription.StrokeDescription? = null
    var lifted = false
    var liftMicros: Long? = null
    var finished = false
    private var drift = 0f

    init {
        samples += origin
    }

    fun add(sample: Sample, minSpacingPx: Float) {
        if (lifted) return
        drift = maxOf(drift, distance(origin, sample))
        val last = samples.last()
        // Under the spacing floor the vertex adds nothing to the shape, and the
        // timing it carries is preserved anyway: slice durations come from the
        // event clock, not from the surviving samples.
        if (distance(last, sample) < minSpacingPx) return
        samples += sample
    }

    fun lift(atMicros: Long) {
        if (lifted) return
        lifted = true
        liftMicros = atMicros
    }

    fun movedBeyond(slopPx: Float): Boolean = drift > slopPx

    fun buildPath(): Path {
        val path = Path()
        val first = samples.first()
        path.moveTo(first.x, first.y)
        for (index in 1 until samples.size) {
            val sample = samples[index]
            path.lineTo(sample.x, sample.y)
        }
        return path
    }

    /** Records the stroke just emitted and re-anchors the next slice on its end point. */
    fun advance(emitted: GestureDescription.StrokeDescription) {
        stroke = emitted
        val last = samples.last()
        samples.clear()
        if (lifted) {
            finished = true
        } else {
            samples += last
        }
    }

    private fun distance(a: Sample, b: Sample): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}

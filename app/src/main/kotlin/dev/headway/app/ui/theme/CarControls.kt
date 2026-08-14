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

package dev.headway.app.ui.theme

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * The transport controls, drawn rather than written.
 *
 * ## Why these are not text
 *
 * The obvious implementation is a `Button` reading "Previous", "Play", "Next",
 * and that is what the dashboard shipped with. Three problems, in increasing
 * order of how much they matter in a car:
 *
 * 1. **Words are slow.** A driver glancing at an 800x480 panel at arm's length
 *    reads a triangle instantly and a five-letter word deliberately.
 * 2. **Words are wide.** Three text buttons at a size worth hitting consume most
 *    of the pane's width, which on a two-pane layout is about 400 pixels.
 * 3. **Glyphs are not guaranteed.** The alternative to words is `⏮ ⏯ ⏭`, and
 *    those live in a Unicode block whose coverage depends on the device's font
 *    stack. A missing glyph on a car screen is a tofu box where the pause
 *    button should be.
 *
 * Drawing them removes all three. A `Path` of two triangles is a handful of
 * floats, it scales to whatever the pane gives it, and it renders identically on
 * every device because nothing outside this file decides what it looks like.
 *
 * ## Sizing
 *
 * The button is a circle inscribed in whatever square it is laid out at, and
 * every feature inside it is a fraction of that. Callers pass a size derived
 * from [CarMetrics.touchTargetPx]; nothing here is in `dp`, for the reason
 * [Headway]'s KDoc gives.
 */
class TransportButton(
    context: Context,
    /** Which control this is. Changing it redraws; see [PLAY] and [PAUSE]. */
    kind: Int,
    private val onPress: () -> Unit,
) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyph = Path()
    private val bounds = RectF()

    /** Whether this is the emphasised control. Play/pause is; skip is not. */
    var emphasised: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var kind: Int = kind
        set(value) {
            if (field == value) return
            field = value
            contentDescription = describe(value)
            invalidate()
        }

    init {
        isClickable = true
        isFocusable = true
        contentDescription = describe(kind)
        setOnClickListener { onPress() }
    }

    /**
     * Dips on touch-down and springs back on release.
     *
     * Not `Headway.pressable`, which animates *after* the click and is right for
     * a tile: a transport control has to acknowledge the finger while it is
     * still down, because the effect — a track changing — is not visible on this
     * pane at all when the driver has skipped past the end of a playlist.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                animate().scaleX(PRESS_SCALE).scaleY(PRESS_SCALE)
                    .setDuration(Headway.QUICK_MILLIS / 2).start()

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                animate().scaleX(1f).scaleY(1f)
                    .setDuration(Headway.QUICK_MILLIS / 2).start()
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        if (size <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val radius = size / 2f

        paint.style = Paint.Style.FILL
        paint.color = if (emphasised) Headway.ACCENT else Headway.SURFACE_RAISED
        canvas.drawCircle(cx, cy, radius, paint)

        if (!emphasised) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (radius / STROKE_DIVISOR).coerceAtLeast(1f)
            paint.color = Headway.OUTLINE
            canvas.drawCircle(cx, cy, radius - paint.strokeWidth / 2f, paint)
            paint.style = Paint.Style.FILL
        }

        // The mark sits on the accent when emphasised, so it has to be the
        // ground colour there or it disappears into its own button.
        paint.color = if (emphasised) Headway.GROUND else Headway.TEXT
        val reach = radius * GLYPH_FRACTION
        when (kind) {
            PLAY -> {
                glyph.reset()
                // Nudged right by an eighth: a triangle centred on its bounding
                // box looks left-heavy, because its visual mass is at the flat
                // edge rather than at the point.
                val shift = reach * PLAY_NUDGE
                glyph.moveTo(cx - reach * 0.62f + shift, cy - reach)
                glyph.lineTo(cx + reach + shift, cy)
                glyph.lineTo(cx - reach * 0.62f + shift, cy + reach)
                glyph.close()
                canvas.drawPath(glyph, paint)
            }

            PAUSE -> {
                val barWidth = reach * PAUSE_BAR
                val gap = reach * PAUSE_GAP
                bounds.set(cx - gap - barWidth, cy - reach, cx - gap, cy + reach)
                canvas.drawRoundRect(bounds, barWidth / 3f, barWidth / 3f, paint)
                bounds.set(cx + gap, cy - reach, cx + gap + barWidth, cy + reach)
                canvas.drawRoundRect(bounds, barWidth / 3f, barWidth / 3f, paint)
            }

            PREVIOUS, NEXT -> {
                // Two triangles and a bar, mirrored for previous. Drawn as one
                // path so the antialiased seam between the triangles does not
                // show as a hairline.
                val direction = if (kind == NEXT) 1f else -1f
                val half = reach * SKIP_HEIGHT
                val step = reach * SKIP_STEP
                glyph.reset()
                for (index in 0..1) {
                    val front = cx + direction * (reach - index * step * 2f)
                    val back = front - direction * step * 2f
                    glyph.moveTo(back, cy - half)
                    glyph.lineTo(front, cy)
                    glyph.lineTo(back, cy + half)
                    glyph.close()
                }
                canvas.drawPath(glyph, paint)
                val barWidth = reach * SKIP_BAR
                val edge = cx + direction * reach
                bounds.set(
                    minOf(edge, edge - direction * barWidth),
                    cy - half,
                    maxOf(edge, edge - direction * barWidth),
                    cy + half,
                )
                canvas.drawRect(bounds, paint)
            }
        }
    }

    private fun describe(kind: Int): String = when (kind) {
        PLAY -> "Play"
        PAUSE -> "Pause"
        PREVIOUS -> "Previous track"
        NEXT -> "Next track"
        else -> "Transport control"
    }

    companion object {
        const val PLAY = 0
        const val PAUSE = 1
        const val PREVIOUS = 2
        const val NEXT = 3

        private const val GLYPH_FRACTION = 0.42f
        private const val PLAY_NUDGE = 0.12f
        private const val PAUSE_BAR = 0.34f
        private const val PAUSE_GAP = 0.16f
        private const val SKIP_HEIGHT = 0.95f
        private const val SKIP_STEP = 0.5f
        private const val SKIP_BAR = 0.22f
        private const val STROKE_DIVISOR = 14f
        private const val PRESS_SCALE = 0.9f
    }
}

/**
 * How far through the track we are, as a rule across the pane.
 *
 * A number would be more precise and much less useful: the question a driver
 * has is "nearly over or just started", and a bar answers it without being
 * read. There is no scrubbing — a seek gesture on a moving car's touchscreen is
 * a way to lose your place in a podcast, and `MediaController.seekTo` is one
 * unhandled `PlaybackState.ACTION_SEEK_TO` away from doing nothing anyway.
 *
 * [set] takes the position rather than animating from a clock. `PlaybackState`
 * publishes a position and the timestamp it was measured at, and the tile
 * already re-renders on every state change; extrapolating between those would
 * mean a second timer per pane for a bar three pixels tall.
 */
class ProgressRule(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = RectF()

    /** 0..1, or negative when the track has no known duration. */
    private var fraction: Float = -1f

    fun set(positionMillis: Long, durationMillis: Long) {
        val next = if (durationMillis <= 0L) {
            -1f
        } else {
            (positionMillis.toFloat() / durationMillis).coerceIn(0f, 1f)
        }
        if (next == fraction) return
        fraction = next
        visibility = if (next < 0f) GONE else VISIBLE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || fraction < 0f) return
        val radius = h / 2f

        paint.color = Headway.OUTLINE
        bar.set(0f, 0f, w, h)
        canvas.drawRoundRect(bar, radius, radius, paint)

        paint.color = Headway.ACCENT
        bar.set(0f, 0f, (w * fraction).coerceAtLeast(h), h)
        canvas.drawRoundRect(bar, radius, radius, paint)
    }
}

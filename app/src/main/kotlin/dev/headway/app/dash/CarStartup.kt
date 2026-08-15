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

package dev.headway.app.dash

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import dev.headway.app.ui.theme.CarMetrics
import dev.headway.app.ui.theme.Headway
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * What the car shows in the first second and a half of a session.
 *
 * ## Why an animation is worth the code
 *
 * The head unit displays whatever the first decoded frame contains, and it
 * arrives within a frame or two of the encoder attaching. Before this, that
 * frame was a static mark on a black field, held while the tiles inflated and
 * their permissions resolved — indistinguishable, from the driver's seat, from a
 * head unit that has frozen part-way through connecting. Every previous drive
 * that went wrong looked exactly like that, which is precisely why a *still*
 * image is the wrong thing to open with: it cannot say whether anything is
 * happening.
 *
 * ## What it draws
 *
 * The launcher icon, becoming itself. Three bars of decreasing width — a road
 * narrowing towards a vanishing point — arrive from the left, staggered, fastest
 * first, and *overshoot slightly* before settling onto a horizon line that opens
 * from the centre. The wordmark fades up beneath them with its letters drawing
 * in from wide to their resting spacing. A highlight then crosses the settled
 * bars, which is what keeps the last third of the run from reading as a frozen
 * frame. Finally the whole composition lifts, swells a few percent and
 * dissolves, so the dashboard arrives *through* it rather than after it.
 *
 * The overshoot and the swell are both deliberately small. This plays on a
 * dashboard, at arm's length, once per drive: the job is to look alive and
 * deliberate, not to be noticed as an animation.
 *
 * No frame of it is decorative in the sense of being arbitrary: it is the
 * product's one visual idea (see [Headway]) drawn over time instead of at rest,
 * in whatever palette the driver chose, at the car's own size.
 *
 * ## Why it is a `View` and a single animator
 *
 * One `ValueAnimator` from 0 to 1, **linear**, with each element deriving its own
 * phase from the same number and easing itself. Several animators would need
 * coordinating and would drift against each other on a device that drops frames;
 * one cannot. The linearity is load-bearing rather than a detail: the phase
 * fractions below are moments in time, and easing the master animator curved the
 * timeline itself — every element was eased twice and the stagger was squeezed
 * into the first third of the run. It is also the
 * cheapest thing that can be encoded at 30 fps while the rest of the session is
 * still being brought up — three rounded rectangles, a line and one text run.
 */
class CarStartupView(
    context: Context,
    private val metrics: CarMetrics,
    /** Called once, on the main thread, when the animation has finished. */
    private val onFinished: () -> Unit,
) : View(context) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.30f
        isFakeBoldText = true
    }
    private val rect = RectF()

    private var progress = 0f
    private var animator: ValueAnimator? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    private fun start() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = TOTAL_MILLIS
            // Linear, and this matters. Every element derives its own phase from
            // `progress` with hard fractions -- a bar starts at 0.09, the
            // wordmark at 0.44 -- and those fractions are only the intended
            // moments if `progress` advances with wall-clock time. Easing here
            // curved the timeline itself, so each element was eased twice and
            // the stagger it was meant to have was squeezed into the first third
            // of the run. Elements ease themselves; the clock does not.
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            // No AnimatorListener: `doOnEnd` would also fire on cancel, and a
            // cancel happens when the window goes away mid-animation — at which
            // point revealing a dashboard that is being torn down is the last
            // thing anybody wants. The end is signalled from the draw pass that
            // reaches 1, which by definition only happens on screen.
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0f || height <= 0f) return

        // The hand-off: the composition eases up and swells very slightly as it
        // dissolves, so the dashboard arrives *through* it rather than after it.
        // A pure fade reads as the picture being switched off; a small scale
        // reads as it opening out into what comes next.
        val exit = ease(phase(EXIT_FROM, 1f))
        val alpha = 1f - exit
        val lift = -exit * metrics.unit * 0.55f
        val swell = 1f + exit * 0.06f

        canvas.drawColor(Headway.GROUND)
        canvas.save()
        canvas.translate(0f, lift)
        canvas.scale(swell, swell, width / 2f, height * 0.44f)

        val centreX = width / 2f
        val centreY = height * 0.44f
        val unit = min(metrics.unit.toFloat(), height / 6f)
        val barHeight = unit * 0.42f
        val gap = barHeight * 0.75f
        val longest = min(width * 0.42f, unit * 5.5f)

        // The horizon: a rule that opens from the centre, under the bars.
        val horizon = ease(phase(HORIZON_FROM, HORIZON_TO))
        val horizonY = centreY + gap * 2.4f
        if (horizon > 0f) {
            linePaint.color = withAlpha(Headway.OUTLINE, alpha * 0.9f)
            linePaint.strokeWidth = max(1f, unit * 0.05f)
            val half = longest * 0.62f * horizon
            canvas.drawLine(centreX - half, horizonY, centreX + half, horizonY, linePaint)
        }

        // Three bars, decreasing in width, arriving from the left in sequence.
        // The sweep is a highlight that crosses them once they have settled --
        // the one moment of motion after everything has arrived, which is what
        // keeps the last third from reading as a frozen frame.
        val sweep = phase(SWEEP_FROM, SWEEP_TO)
        for (index in 0 until BARS) {
            val from = index * STAGGER
            val bar = phase(from, from + BAR_TRAVEL)
            if (bar <= 0f) continue
            val eased = settle(bar)
            val barWidth = longest * (1f - index * 0.26f)
            val restX = centreX - longest / 2f
            val startX = restX - longest * 1.4f
            val left = startX + (restX - startX) * eased
            val top = centreY - gap * 1.2f + index * (barHeight + gap * 0.5f)
            // The leading bar carries the accent; the ones behind it recede,
            // which is what makes three parallel rules read as distance rather
            // than as a list.
            val color = when (index) {
                0 -> Headway.ACCENT
                1 -> Headway.ACCENT_DIM
                else -> Headway.OUTLINE
            }
            val arrival = ease(bar)
            barPaint.color = withAlpha(color, alpha * arrival)
            rect.set(left, top, left + barWidth * arrival, top + barHeight)
            canvas.drawRoundRect(rect, barHeight / 2f, barHeight / 2f, barPaint)

            // A short bright segment travelling along the settled bar. Drawn
            // only while the sweep is running and only over the part of the bar
            // that exists, so it can never trail off the end.
            if (sweep > 0f && sweep < 1f && arrival > 0.99f) {
                val head = rect.left + rect.width() * (sweep * 1.25f - index * 0.08f)
                val tail = head - barWidth * 0.22f
                if (head > rect.left && tail < rect.right) {
                    // Brightest in the middle of its own travel, so it appears
                    // and leaves rather than switching on and off.
                    val strength = 1f - abs(sweep - 0.5f) * 2f
                    barPaint.color = withAlpha(Headway.TEXT, alpha * strength * 0.5f)
                    rect.set(
                        max(tail, rect.left),
                        top,
                        min(head, rect.right),
                        top + barHeight,
                    )
                    canvas.drawRoundRect(rect, barHeight / 2f, barHeight / 2f, barPaint)
                }
            }
        }

        val word = ease(phase(WORD_FROM, WORD_TO))
        if (word > 0f) {
            textPaint.color = withAlpha(Headway.TEXT, alpha * word)
            textPaint.textSize = unit * 0.34f
            // The letters draw apart and close to their resting spacing as they
            // fade up. It is the cheapest way to make type look like it is being
            // *set* rather than switched on, and it costs one float per frame.
            textPaint.letterSpacing = WORD_SPACING_WIDE +
                (WORD_SPACING_REST - WORD_SPACING_WIDE) * word
            canvas.drawText(
                WORDMARK,
                centreX,
                horizonY + unit * 0.62f + (1f - word) * unit * 0.22f,
                textPaint,
            )
        }
        canvas.restore()

        if (progress >= 1f) {
            // Idempotent: onDraw can be called again before the view is removed.
            if (!finished) {
                finished = true
                post { onFinished() }
            }
        }
    }

    private var finished = false

    /** Where [progress] sits within a sub-interval, clamped to 0..1. */
    private fun phase(from: Float, to: Float): Float {
        if (to <= from) return if (progress >= to) 1f else 0f
        return ((progress - from) / (to - from)).coerceIn(0f, 1f)
    }

    /** A soft-landing curve, so a bar decelerates into place instead of stopping. */
    private fun ease(value: Float): Float {
        val inverted = 1f - value
        return 1f - inverted * inverted * inverted
    }

    /**
     * [ease] with a small overshoot, so a bar arrives with weight.
     *
     * A rule that decelerates to exactly its resting place looks correct and
     * dead. Passing it by a few percent and coming back is what makes the
     * arrival read as a physical object stopping rather than an interpolation
     * ending. The overshoot is deliberately small: this plays on a dashboard, at
     * arm's length, once per drive.
     */
    private fun settle(value: Float): Float {
        val eased = ease(value)
        val back = sin(value.coerceIn(0f, 1f) * Math.PI.toFloat())
        return eased + back * OVERSHOOT * (1f - value)
    }

    private fun withAlpha(color: Int, fraction: Float): Int = Color.argb(
        (Color.alpha(color) * fraction.coerceIn(0f, 1f)).toInt(),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )

    private companion object {
        /**
         * Long enough to read as deliberate, short enough that nobody waits.
         *
         * The session's own bring-up runs alongside it, so this is not time the
         * driver is made to spend: the dashboard is built and its tiles are
         * started underneath, and the animation is simply the top layer until it
         * fades. It does not end early when the dashboard finishes first —
         * cutting a 1.5 s animation at 300 ms reads as a glitch, not as speed.
         */
        const val TOTAL_MILLIS = 1_500L

        const val BARS = 3
        const val STAGGER = 0.09f
        const val BAR_TRAVEL = 0.42f
        const val HORIZON_FROM = 0.22f
        const val HORIZON_TO = 0.62f
        const val WORD_FROM = 0.44f
        const val WORD_TO = 0.76f

        /** The highlight that crosses the settled bars, after they have all landed. */
        const val SWEEP_FROM = 0.58f
        const val SWEEP_TO = 0.88f

        const val EXIT_FROM = 0.86f

        /** How far a bar passes its resting place before coming back. */
        const val OVERSHOOT = 0.035f

        /** Letter spacing as the wordmark appears, and where it settles. */
        const val WORD_SPACING_WIDE = 0.55f
        const val WORD_SPACING_REST = 0.30f

        const val WORDMARK = "HEADWAY"
    }
}

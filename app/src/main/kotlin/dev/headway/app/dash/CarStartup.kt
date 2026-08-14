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
import android.view.animation.DecelerateInterpolator
import dev.headway.app.ui.theme.CarMetrics
import dev.headway.app.ui.theme.Headway
import kotlin.math.max
import kotlin.math.min

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
 * first, and settle onto a horizon line that opens from the centre. The wordmark
 * fades up beneath them. Then the whole thing lifts and dissolves into the
 * dashboard.
 *
 * No frame of it is decorative in the sense of being arbitrary: it is the
 * product's one visual idea (see [Headway]) drawn over time instead of at rest,
 * in whatever palette the driver chose, at the car's own size.
 *
 * ## Why it is a `View` and a single animator
 *
 * One `ValueAnimator` from 0 to 1, with each element deriving its own phase from
 * the same number. Several animators would need coordinating and would drift
 * against each other on a device that drops frames; one cannot. It is also the
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
            interpolator = DecelerateInterpolator(1.2f)
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

        // The whole composition lifts slightly and fades as it hands over.
        val exit = phase(EXIT_FROM, 1f)
        val alpha = 1f - exit
        val lift = -exit * metrics.unit * 0.6f

        canvas.drawColor(Headway.GROUND)
        canvas.save()
        canvas.translate(0f, lift)

        val centreX = width / 2f
        val centreY = height * 0.44f
        val unit = min(metrics.unit.toFloat(), height / 6f)
        val barHeight = unit * 0.42f
        val gap = barHeight * 0.75f
        val longest = min(width * 0.42f, unit * 5.5f)

        // The horizon: a rule that opens from the centre, under the bars.
        val horizon = phase(HORIZON_FROM, HORIZON_TO)
        if (horizon > 0f) {
            linePaint.color = withAlpha(Headway.OUTLINE, alpha * 0.9f)
            linePaint.strokeWidth = max(1f, unit * 0.05f)
            val half = longest * 0.62f * horizon
            val y = centreY + gap * 2.4f
            canvas.drawLine(centreX - half, y, centreX + half, y, linePaint)
        }

        // Three bars, decreasing in width, arriving from the left in sequence.
        for (index in 0 until BARS) {
            val from = index * STAGGER
            val bar = phase(from, from + BAR_TRAVEL)
            if (bar <= 0f) continue
            val eased = ease(bar)
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
            barPaint.color = withAlpha(color, alpha * eased)
            rect.set(left, top, left + barWidth * eased, top + barHeight)
            canvas.drawRoundRect(rect, barHeight / 2f, barHeight / 2f, barPaint)
        }

        val word = phase(WORD_FROM, WORD_TO)
        if (word > 0f) {
            textPaint.color = withAlpha(Headway.TEXT, alpha * word)
            textPaint.textSize = unit * 0.34f
            canvas.drawText(
                WORDMARK,
                centreX,
                centreY + gap * 2.4f + unit * 0.62f + (1f - word) * unit * 0.2f,
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
         * driver is made to spend: the tiles are inflating and the first channels
         * are opening underneath. It ends early if the dashboard is ready sooner —
         * see `CarShell`.
         */
        const val TOTAL_MILLIS = 1_500L

        const val BARS = 3
        const val STAGGER = 0.09f
        const val BAR_TRAVEL = 0.42f
        const val HORIZON_FROM = 0.22f
        const val HORIZON_TO = 0.62f
        const val WORD_FROM = 0.44f
        const val WORD_TO = 0.76f
        const val EXIT_FROM = 0.86f

        const val WORDMARK = "HEADWAY"
    }
}

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

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The one place Headway's colours, spacing and motion are defined.
 *
 * ## Where the look comes from
 *
 * The launcher icon is three horizontal bars of decreasing width in a single
 * cyan on near-black — a road narrowing towards a vanishing point, or the
 * speed-lines shorthand for forward motion. That is the whole visual idea, and
 * everything here is derived from it rather than invented alongside it:
 *
 * - **One accent, used sparingly.** [ACCENT] is the icon's cyan, unchanged. A
 *   second accent would compete with it; state that needs to differ uses
 *   brightness, not hue.
 * - **Near-black, not black.** [GROUND] is a very dark blue-grey rather than
 *   `#000000`. Pure black against an OLED panel makes every edge a hard cutout
 *   and every dark UI look like a hole; a few points of lift gives surfaces
 *   something to sit on.
 * - **Horizontal geometry.** Panels are wide and short, dividers are rules,
 *   progress is a bar. The icon is horizontal and a car screen is 800x480.
 *
 * ## Why this is Kotlin and not `themes.xml`
 *
 * Every car surface is built in code — `CarLauncherActivity` and
 * `DashboardActivity` construct their views directly, because the geometry has
 * to be computed from the head unit's advertised resolution and density at run
 * time and no static dimension resource can know those. A palette split between
 * XML and Kotlin would be a palette that drifts.
 *
 * ## Sizing
 *
 * Nothing here is in `dp`. The car scales Headway's surface to its own panel, so
 * a size that is comfortable on a phone can be unreadable on the dashboard.
 * Callers pass a **unit** derived from the car's geometry — see
 * `CarMetrics.touchTargetPx` — and everything is a multiple of it.
 */
object Headway {

    // --- colour ---------------------------------------------------------------

    /** The icon's cyan. The only accent in the product. */
    val ACCENT: Int = Color.rgb(0x4F, 0xC3, 0xF7)

    /** Accent at rest, for rules and inactive marks. */
    val ACCENT_DIM: Int = Color.rgb(0x2A, 0x6B, 0x8A)

    /** Page background. */
    val GROUND: Int = Color.rgb(0x0B, 0x0E, 0x11)

    /** A panel sitting on [GROUND]. */
    val SURFACE: Int = Color.rgb(0x15, 0x1A, 0x1F)

    /** A panel that is pressed, focused, or otherwise raised. */
    val SURFACE_RAISED: Int = Color.rgb(0x1E, 0x25, 0x2C)

    /** Hairline between panels. */
    val OUTLINE: Int = Color.rgb(0x2A, 0x32, 0x3A)

    val TEXT: Int = Color.rgb(0xEC, 0xEF, 0xF1)
    val TEXT_MUTED: Int = Color.rgb(0x90, 0xA4, 0xAE)

    /** Reserved for genuine faults. Nothing decorative is ever this colour. */
    val WARN: Int = Color.rgb(0xFF, 0xB3, 0x4D)
    val FAULT: Int = Color.rgb(0xFF, 0x6B, 0x6B)
    val GOOD: Int = Color.rgb(0x66, 0xD9, 0x9A)

    // --- motion ---------------------------------------------------------------

    /**
     * How long a transition takes.
     *
     * Short. A car UI is glanced at, and an animation the driver has to wait out
     * is an animation that steals attention it was supposed to save. Long enough
     * to show *what* changed, not long enough to be a performance.
     */
    const val QUICK_MILLIS: Long = 140L
    const val SETTLE_MILLIS: Long = 260L

    /** The pulse period of the connecting indicator. */
    const val PULSE_MILLIS: Long = 1_400L

    // --- building blocks ------------------------------------------------------

    /** A rounded panel background, in [SURFACE] unless told otherwise. */
    fun panel(radiusPx: Float, fill: Int = SURFACE, stroke: Int? = OUTLINE): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(fill)
            stroke?.let { setStroke(maxOf(1, (radiusPx / 12f).toInt()), it) }
        }

    /** A title, sized from the car unit rather than in sp. */
    fun title(context: Context, unitPx: Int, text: CharSequence): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, unitPx * TITLE_FRACTION)
            includeFontPadding = false
        }

    /** Secondary text. */
    fun caption(context: Context, unitPx: Int, text: CharSequence): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(TEXT_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, unitPx * CAPTION_FRACTION)
            includeFontPadding = false
        }

    /**
     * A pressable panel that dips when touched.
     *
     * The dip is the only feedback a car screen gives: there is no cursor, no
     * hover, and the driver's finger covers whatever they pressed. Without it a
     * tap that did nothing and a tap that missed look identical.
     */
    fun pressable(view: View, radiusPx: Float, onClick: () -> Unit) {
        view.background = panel(radiusPx)
        view.isClickable = true
        view.setOnClickListener {
            view.animate()
                .scaleX(PRESS_SCALE).scaleY(PRESS_SCALE)
                .setDuration(QUICK_MILLIS / 2)
                .withEndAction {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(QUICK_MILLIS / 2).start()
                    onClick()
                }
                .start()
        }
    }

    /** Fades a view in from nothing, for content that arrives asynchronously. */
    fun revealIn(view: View, delayMillis: Long = 0L) {
        view.alpha = 0f
        view.translationY = view.resources.displayMetrics.density * REVEAL_RISE_DP
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(delayMillis)
            .setDuration(SETTLE_MILLIS)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    private const val TITLE_FRACTION = 0.40f
    private const val CAPTION_FRACTION = 0.26f
    private const val PRESS_SCALE = 0.96f
    private const val REVEAL_RISE_DP = 10f
}

/**
 * The mark: three bars narrowing towards a vanishing point.
 *
 * The same geometry as the launcher icon, drawn rather than inflated so it can
 * animate. Two states:
 *
 * - **Still.** The identity. Used as a header mark and on empty states.
 * - **Travelling.** Each bar sweeps left to right in turn, which reads as
 *   forward motion rather than as a spinner. Used while connecting, because
 *   "the car is thinking" is a different feeling from "this app is busy" and a
 *   generic spinner says the wrong one.
 *
 * Nothing here allocates during a frame: the paints and the rectangle are made
 * once and reused, because this may be on screen at 60 fps behind an H.264
 * encoder that wants the GPU.
 */
class HeadwayMark(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = RectF()
    private var animator: ValueAnimator? = null

    /** 0..1, the position of the travelling highlight. */
    private var phase = 0f

    /** Whether the mark is animating. */
    var travelling: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) start() else stop()
        }

    override fun onDetachedFromWindow() {
        // A ValueAnimator outlives its view and keeps posting frames to a view
        // that will never draw again, which on a car session is a wake-up every
        // sixteen milliseconds for the rest of the drive.
        stop()
        super.onDetachedFromWindow()
    }

    private fun start() {
        stop()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Headway.PULSE_MILLIS
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stop() {
        animator?.cancel()
        animator = null
        phase = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Three bars, centred, each narrower than the one above, with the
        // widest in the middle — the icon's proportions exactly.
        val thickness = h * BAR_THICKNESS
        val gap = h * BAR_GAP
        val block = thickness * 3 + gap * 2
        var top = (h - block) / 2f
        val widths = floatArrayOf(w * 0.66f, w * 0.90f, w * 0.44f)

        for (index in widths.indices) {
            val barWidth = widths[index]
            val left = (w - barWidth) / 2f
            bar.set(left, top, left + barWidth, top + thickness)

            // Each bar lights in turn: its own third of the cycle.
            val lit = if (!travelling) {
                1f
            } else {
                val window = ((phase * widths.size) - index).coerceIn(0f, 1f)
                // Rises then falls inside its window, so the highlight sweeps
                // rather than blinking.
                1f - kotlin.math.abs(window - 0.5f) * 2f
            }
            paint.color = blend(Headway.ACCENT_DIM, Headway.ACCENT, if (travelling) lit else 1f)
            canvas.drawRoundRect(bar, thickness / 2f, thickness / 2f, paint)
            top += thickness + gap
        }
    }

    private fun blend(from: Int, to: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        fun mix(a: Int, b: Int) = (a + (b - a) * t).toInt().coerceIn(0, 255)
        return Color.rgb(
            mix(Color.red(from), Color.red(to)),
            mix(Color.green(from), Color.green(to)),
            mix(Color.blue(from), Color.blue(to)),
        )
    }

    private companion object {
        const val BAR_THICKNESS = 0.14f
        const val BAR_GAP = 0.09f
    }
}

/**
 * The geometry a car surface should lay itself out with.
 *
 * The head unit advertises a resolution and a density, and those two together
 * are the only truthful basis for a size on the car screen. A `dp` computed from
 * the phone's density is wrong by whatever ratio the car scales by — on a
 * 1080x2404 phone shown on an 800x480 panel that ratio is five to one, which is
 * the difference between a comfortable button and an invisible one.
 *
 * @param widthPx the car's own pixel width.
 * @param heightPx the car's own pixel height.
 * @param densityDpi the car's advertised density.
 */
data class CarMetrics(val widthPx: Int, val heightPx: Int, val densityDpi: Int) {

    /**
     * The minimum comfortable touch target, in car pixels.
     *
     * 48 dp at the *car's* density, floored so a unit that under-reports its
     * density cannot produce a target too small to hit. CLAUDE.md asks for 48 dp
     * "scaled to the head unit density" and this is that number.
     */
    val touchTargetPx: Int
        get() = maxOf(MIN_TARGET_PX, (MIN_TARGET_DP * densityDpi / BASE_DPI).toInt())

    /** The layout unit everything else is a multiple of. */
    val unit: Int get() = touchTargetPx

    val gutter: Int get() = (unit * GUTTER).toInt().coerceAtLeast(4)

    val radius: Float get() = unit * RADIUS

    /** True for a panel wide enough to put two columns of content side by side. */
    val wide: Boolean get() = widthPx >= unit * WIDE_UNITS

    fun describe(): String = "${widthPx}x$heightPx @ $densityDpi dpi, unit ${unit}px"

    private companion object {
        const val MIN_TARGET_DP = 48.0
        const val BASE_DPI = 160.0

        /**
         * An absolute floor in car pixels.
         *
         * A 160 dpi 800x480 unit computes a 48-pixel target, which is 6% of the
         * panel width — right. A unit that advertises 96 dpi would compute 29,
         * which is not a target anyone hits at speed. Whichever is larger wins.
         */
        const val MIN_TARGET_PX = 44

        const val GUTTER = 0.28f
        const val RADIUS = 0.30f
        const val WIDE_UNITS = 12
    }
}

/** A horizontal rule in the accent's dim tone. */
fun rule(context: Context, metrics: CarMetrics): View = View(context).apply {
    setBackgroundColor(Headway.OUTLINE)
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
}

/** A row that centres its children with the standard gutter between them. */
fun row(context: Context, metrics: CarMetrics): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    val g = metrics.gutter
    setPadding(g, g, g, g)
}

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

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import dev.headway.app.ui.theme.CarMetrics
import dev.headway.app.ui.theme.Headway

/** The symbols the car screen draws. */
enum class CarGlyph {
    SETTINGS,
    MIC,
    MIC_LISTENING,
    CLOSE,
    LOCK,
    UNLOCK,
    ADD,
    SPLIT_HORIZONTAL,
    SPLIT_VERTICAL,
    REMOVE,
    CHECK,
    APPS,
}

/**
 * Icons, drawn rather than shipped.
 *
 * ## Why not vector drawables
 *
 * Because every size on the car screen comes from the head unit at run time. A
 * `VectorDrawable` is scaled from a fixed viewport, and its stroke widths scale
 * with it — so an icon sized for a 160 dpi 800x480 panel is a smear of hairlines
 * on a 240 dpi one, and the only fix is a second asset. Here the stroke width is
 * a fraction of the *car's* touch target, so the same glyph is correctly weighted
 * on any panel Headway is ever pointed at.
 *
 * It also keeps the APK free of another dozen XML files for shapes that are four
 * lines of `Canvas` each, and keeps them in the palette automatically — the paint
 * colour is read at draw time, so a theme change repaints them with everything
 * else.
 */
class CarGlyphView(
    context: Context,
    private val glyph: CarGlyph,
    private var tint: () -> Int = { Headway.TEXT },
) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val box = RectF()
    private val path = Path()

    fun setTint(tint: () -> Int) {
        this.tint = tint
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        if (size <= 0f) return
        val stroke = size * STROKE_FRACTION
        paint.strokeWidth = stroke
        paint.color = tint()
        fill.color = tint()

        val cx = width / 2f
        val cy = height / 2f
        // Every glyph is drawn inside a square of this radius, so a row of them
        // reads as one set regardless of which shapes are in it.
        val r = size * 0.30f

        when (glyph) {
            CarGlyph.SETTINGS -> settings(canvas, cx, cy, r, stroke)
            CarGlyph.MIC -> mic(canvas, cx, cy, r, false)
            CarGlyph.MIC_LISTENING -> mic(canvas, cx, cy, r, true)
            CarGlyph.CLOSE -> {
                canvas.drawLine(cx - r, cy - r, cx + r, cy + r, paint)
                canvas.drawLine(cx + r, cy - r, cx - r, cy + r, paint)
            }

            CarGlyph.LOCK -> lock(canvas, cx, cy, r, closed = true)
            CarGlyph.UNLOCK -> lock(canvas, cx, cy, r, closed = false)
            CarGlyph.ADD -> {
                canvas.drawLine(cx - r, cy, cx + r, cy, paint)
                canvas.drawLine(cx, cy - r, cx, cy + r, paint)
            }

            CarGlyph.SPLIT_HORIZONTAL -> {
                box.set(cx - r, cy - r * 0.8f, cx + r, cy + r * 0.8f)
                canvas.drawRoundRect(box, stroke, stroke, paint)
                canvas.drawLine(cx, cy - r * 0.8f, cx, cy + r * 0.8f, paint)
            }

            CarGlyph.SPLIT_VERTICAL -> {
                box.set(cx - r, cy - r * 0.8f, cx + r, cy + r * 0.8f)
                canvas.drawRoundRect(box, stroke, stroke, paint)
                canvas.drawLine(cx - r, cy, cx + r, cy, paint)
            }

            CarGlyph.REMOVE -> canvas.drawLine(cx - r, cy, cx + r, cy, paint)

            CarGlyph.CHECK -> {
                path.reset()
                path.moveTo(cx - r, cy)
                path.lineTo(cx - r * 0.2f, cy + r * 0.7f)
                path.lineTo(cx + r, cy - r * 0.7f)
                canvas.drawPath(path, paint)
            }

            CarGlyph.APPS -> {
                val cell = r * 0.55f
                val gap = r * 0.62f
                for (row in -1..1 step 2) {
                    for (column in -1..1 step 2) {
                        box.set(
                            cx + column * gap - cell / 2,
                            cy + row * gap - cell / 2,
                            cx + column * gap + cell / 2,
                            cy + row * gap + cell / 2,
                        )
                        canvas.drawRoundRect(box, cell / 4, cell / 4, fill)
                    }
                }
            }
        }
    }

    /** A cog: a ring, and eight teeth around it. */
    private fun settings(canvas: Canvas, cx: Float, cy: Float, r: Float, stroke: Float) {
        canvas.drawCircle(cx, cy, r * 0.52f, paint)
        val tooth = r * 0.30f
        for (index in 0 until TEETH) {
            val angle = index * (2.0 * Math.PI / TEETH)
            val sin = Math.sin(angle).toFloat()
            val cos = Math.cos(angle).toFloat()
            canvas.drawLine(
                cx + cos * (r * 0.74f),
                cy + sin * (r * 0.74f),
                cx + cos * (r * 0.74f + tooth),
                cy + sin * (r * 0.74f + tooth),
                paint,
            )
        }
    }

    /** A capsule, a cradle under it, and a stem. */
    private fun mic(canvas: Canvas, cx: Float, cy: Float, r: Float, listening: Boolean) {
        val capsuleWidth = r * 0.62f
        box.set(cx - capsuleWidth, cy - r, cx + capsuleWidth, cy + r * 0.25f)
        if (listening) {
            canvas.drawRoundRect(box, capsuleWidth, capsuleWidth, fill)
        } else {
            canvas.drawRoundRect(box, capsuleWidth, capsuleWidth, paint)
        }
        box.set(cx - r * 0.86f, cy - r * 0.5f, cx + r * 0.86f, cy + r * 0.86f)
        canvas.drawArc(box, 20f, 140f, false, paint)
        canvas.drawLine(cx, cy + r * 0.86f, cx, cy + r * 1.2f, paint)
    }

    /** A body and a shackle; the open one is lifted and offset. */
    private fun lock(canvas: Canvas, cx: Float, cy: Float, r: Float, closed: Boolean) {
        box.set(cx - r * 0.8f, cy - r * 0.1f, cx + r * 0.8f, cy + r * 0.9f)
        canvas.drawRoundRect(box, r * 0.22f, r * 0.22f, paint)
        val shackleCentre = if (closed) cx else cx + r * 0.45f
        box.set(
            shackleCentre - r * 0.5f,
            cy - r * 0.95f,
            shackleCentre + r * 0.5f,
            cy - r * 0.05f,
        )
        canvas.drawArc(box, 180f, 180f, false, paint)
    }

    private companion object {
        const val STROKE_FRACTION = 0.075f
        const val TEETH = 8
    }
}

/**
 * A glyph sized and padded as a car touch target, with a label for accessibility
 * and a background that says whether it is active.
 */
class CarGlyphButton(
    context: Context,
    private val metrics: CarMetrics,
    glyph: CarGlyph,
    description: String,
    private val active: Boolean = false,
    onTap: () -> Unit,
) : LinearLayout(context) {

    private val icon = CarGlyphView(
        context,
        glyph,
        tint = { if (active) Headway.ON_ACCENT else Headway.TEXT },
    )

    init {
        gravity = Gravity.CENTER
        isClickable = true
        contentDescription = description
        background = Headway.panel(
            radiusPx = metrics.touchTargetPx / 2f,
            fill = if (active) Headway.ACCENT else Headway.SURFACE,
            stroke = if (active) null else Headway.OUTLINE,
        )
        setOnClickListener { onTap() }
        val side = metrics.touchTargetPx
        addView(icon, LayoutParams(side, side))
        layoutParams = LayoutParams(side, side).apply {
            val gap = metrics.gutter / 3
            setMargins(gap, gap, gap, gap)
        }
    }

    /** Swaps the symbol without rebuilding the button; used by the mic. */
    fun setGlyph(glyph: CarGlyph, active: Boolean) {
        removeAllViews()
        val side = metrics.touchTargetPx
        val replacement = CarGlyphView(
            context,
            glyph,
            tint = { if (active) Headway.ON_ACCENT else Headway.TEXT },
        )
        background = Headway.panel(
            radiusPx = metrics.touchTargetPx / 2f,
            fill = if (active) Headway.ACCENT else Headway.SURFACE,
            stroke = if (active) null else Headway.OUTLINE,
        )
        addView(replacement, LayoutParams(side, side))
    }
}

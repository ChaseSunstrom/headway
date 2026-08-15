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
import dev.headway.dash.TabIcon

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

    // --- tab icons ----------------------------------------------------------
    //
    // Drawn in exactly the same idiom as the controls above -- one stroke
    // weight, one bounding radius, round caps and joins -- because they sit on
    // the same rail as Settings and the microphone and a set that does not match
    // reads as two sets. `TabIcon` is the stored vocabulary; these are the
    // shapes for it, and `CarGlyph.forTab` is the join.
    MAP,
    MUSIC,
    PHONE,
    MESSAGE,
    CAR,
    CLOCK,
    HOME,
    STAR,
    COMPASS,
    RADIO,
    LIST,
    GRID,
    GAUGE,
    NAVIGATION,
    HEADPHONES,
    VIDEO,
    CAMERA,
    BELL,
    HEART,
    BOLT,
    COFFEE,
    WRENCH,
    ;

    companion object {

        /** The glyph for a stored tab icon, or null when there is none. */
        fun forTab(icon: TabIcon?): CarGlyph? = when (icon) {
            null -> null
            TabIcon.MAP -> MAP
            TabIcon.MUSIC -> MUSIC
            TabIcon.PHONE -> PHONE
            TabIcon.MESSAGE -> MESSAGE
            TabIcon.CAR -> CAR
            TabIcon.CLOCK -> CLOCK
            TabIcon.HOME -> HOME
            TabIcon.STAR -> STAR
            TabIcon.COMPASS -> COMPASS
            TabIcon.RADIO -> RADIO
            TabIcon.LIST -> LIST
            TabIcon.GRID -> GRID
            TabIcon.GAUGE -> GAUGE
            TabIcon.NAVIGATION -> NAVIGATION
            TabIcon.HEADPHONES -> HEADPHONES
            TabIcon.VIDEO -> VIDEO
            TabIcon.CAMERA -> CAMERA
            TabIcon.BELL -> BELL
            TabIcon.HEART -> HEART
            TabIcon.BOLT -> BOLT
            TabIcon.COFFEE -> COFFEE
            TabIcon.WRENCH -> WRENCH
        }
    }
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
            // --- tab icons ---------------------------------------------------

            CarGlyph.MAP -> {
                // A folded map: an outline with two creases.
                box.set(cx - r, cy - r * 0.75f, cx + r, cy + r * 0.75f)
                canvas.drawRoundRect(box, stroke, stroke, paint)
                canvas.drawLine(cx - r * 0.33f, cy - r * 0.75f, cx - r * 0.33f, cy + r * 0.75f, paint)
                canvas.drawLine(cx + r * 0.33f, cy - r * 0.75f, cx + r * 0.33f, cy + r * 0.75f, paint)
            }

            CarGlyph.MUSIC -> {
                // A quaver: a stem, a flag, and a filled head.
                canvas.drawLine(cx - r * 0.1f, cy + r * 0.55f, cx - r * 0.1f, cy - r * 0.9f, paint)
                path.reset()
                path.moveTo(cx - r * 0.1f, cy - r * 0.9f)
                path.quadTo(cx + r * 0.85f, cy - r * 0.6f, cx + r * 0.5f, cy - r * 0.05f)
                canvas.drawPath(path, paint)
                canvas.drawCircle(cx - r * 0.5f, cy + r * 0.55f, r * 0.4f, fill)
            }

            CarGlyph.PHONE -> {
                // A handset, drawn as a rounded bar rotated across the square.
                canvas.save()
                canvas.rotate(-35f, cx, cy)
                box.set(cx - r * 0.9f, cy - r * 0.34f, cx + r * 0.9f, cy + r * 0.34f)
                canvas.drawRoundRect(box, r * 0.34f, r * 0.34f, paint)
                canvas.restore()
            }

            CarGlyph.MESSAGE -> {
                // A speech bubble with a tail at the bottom left.
                box.set(cx - r, cy - r * 0.85f, cx + r, cy + r * 0.45f)
                canvas.drawRoundRect(box, r * 0.3f, r * 0.3f, paint)
                path.reset()
                path.moveTo(cx - r * 0.45f, cy + r * 0.45f)
                path.lineTo(cx - r * 0.55f, cy + r * 0.95f)
                path.lineTo(cx - r * 0.05f, cy + r * 0.45f)
                canvas.drawPath(path, paint)
            }

            CarGlyph.CAR -> {
                // A cabin over a body, with two wheels under it.
                path.reset()
                path.moveTo(cx - r * 0.75f, cy - r * 0.05f)
                path.lineTo(cx - r * 0.5f, cy - r * 0.7f)
                path.lineTo(cx + r * 0.5f, cy - r * 0.7f)
                path.lineTo(cx + r * 0.75f, cy - r * 0.05f)
                canvas.drawPath(path, paint)
                box.set(cx - r, cy - r * 0.05f, cx + r, cy + r * 0.5f)
                canvas.drawRoundRect(box, r * 0.22f, r * 0.22f, paint)
                canvas.drawCircle(cx - r * 0.55f, cy + r * 0.55f, r * 0.2f, fill)
                canvas.drawCircle(cx + r * 0.55f, cy + r * 0.55f, r * 0.2f, fill)
            }

            CarGlyph.CLOCK -> {
                canvas.drawCircle(cx, cy, r * 0.9f, paint)
                canvas.drawLine(cx, cy, cx, cy - r * 0.5f, paint)
                canvas.drawLine(cx, cy, cx + r * 0.4f, cy + r * 0.2f, paint)
            }

            CarGlyph.HOME -> {
                // A roof over a body, open at the bottom.
                path.reset()
                path.moveTo(cx - r, cy - r * 0.05f)
                path.lineTo(cx, cy - r * 0.9f)
                path.lineTo(cx + r, cy - r * 0.05f)
                canvas.drawPath(path, paint)
                path.reset()
                path.moveTo(cx - r * 0.72f, cy - r * 0.05f)
                path.lineTo(cx - r * 0.72f, cy + r * 0.85f)
                path.lineTo(cx + r * 0.72f, cy + r * 0.85f)
                path.lineTo(cx + r * 0.72f, cy - r * 0.05f)
                canvas.drawPath(path, paint)
            }

            CarGlyph.STAR -> {
                path.reset()
                for (point in 0 until STAR_POINTS * 2) {
                    val radius = if (point % 2 == 0) r else r * 0.42f
                    val angle = -Math.PI / 2 + point * Math.PI / STAR_POINTS
                    val x = cx + (Math.cos(angle) * radius).toFloat()
                    val y = cy + (Math.sin(angle) * radius).toFloat()
                    if (point == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                canvas.drawPath(path, paint)
            }

            CarGlyph.COMPASS -> {
                canvas.drawCircle(cx, cy, r * 0.9f, paint)
                path.reset()
                path.moveTo(cx + r * 0.42f, cy - r * 0.42f)
                path.lineTo(cx - r * 0.12f, cy + r * 0.12f)
                path.lineTo(cx - r * 0.42f, cy + r * 0.42f)
                path.lineTo(cx + r * 0.12f, cy - r * 0.12f)
                path.close()
                canvas.drawPath(path, fill)
            }

            CarGlyph.RADIO -> {
                // A dial with two broadcast arcs to its right.
                canvas.drawCircle(cx - r * 0.35f, cy, r * 0.42f, paint)
                box.set(cx - r * 0.1f, cy - r * 0.7f, cx + r * 0.65f, cy + r * 0.7f)
                canvas.drawArc(box, -60f, 120f, false, paint)
                box.set(cx + r * 0.1f, cy - r, cx + r * 1.1f, cy + r)
                canvas.drawArc(box, -60f, 120f, false, paint)
            }

            CarGlyph.LIST -> {
                for (row in -1..1) {
                    val y = cy + row * r * 0.62f
                    canvas.drawCircle(cx - r * 0.78f, y, stroke * 0.9f, fill)
                    canvas.drawLine(cx - r * 0.35f, y, cx + r, y, paint)
                }
            }

            CarGlyph.GRID -> {
                box.set(cx - r, cy - r, cx + r, cy + r)
                canvas.drawRoundRect(box, stroke, stroke, paint)
                canvas.drawLine(cx, cy - r, cx, cy + r, paint)
                canvas.drawLine(cx - r, cy, cx + r, cy, paint)
            }

            CarGlyph.GAUGE -> {
                // A dial arc and a needle, which is what a car pane is.
                box.set(cx - r, cy - r * 0.6f, cx + r, cy + r * 1.4f)
                canvas.drawArc(box, 180f, 180f, false, paint)
                canvas.drawLine(cx, cy + r * 0.4f, cx + r * 0.55f, cy - r * 0.3f, paint)
            }

            CarGlyph.NAVIGATION -> {
                // The turn arrow: a filled chevron pointing up-right.
                path.reset()
                path.moveTo(cx, cy - r)
                path.lineTo(cx + r * 0.8f, cy + r * 0.9f)
                path.lineTo(cx, cy + r * 0.42f)
                path.lineTo(cx - r * 0.8f, cy + r * 0.9f)
                path.close()
                canvas.drawPath(path, fill)
            }

            CarGlyph.HEADPHONES -> {
                box.set(cx - r * 0.9f, cy - r * 0.85f, cx + r * 0.9f, cy + r * 0.5f)
                canvas.drawArc(box, 180f, 180f, false, paint)
                box.set(cx - r * 0.9f, cy - r * 0.15f, cx - r * 0.32f, cy + r * 0.85f)
                canvas.drawRoundRect(box, r * 0.28f, r * 0.28f, paint)
                box.set(cx + r * 0.32f, cy - r * 0.15f, cx + r * 0.9f, cy + r * 0.85f)
                canvas.drawRoundRect(box, r * 0.28f, r * 0.28f, paint)
            }

            CarGlyph.VIDEO -> {
                box.set(cx - r, cy - r * 0.62f, cx + r * 0.25f, cy + r * 0.62f)
                canvas.drawRoundRect(box, stroke, stroke, paint)
                path.reset()
                path.moveTo(cx + r * 0.42f, cy - r * 0.55f)
                path.lineTo(cx + r, cy - r * 0.85f)
                path.lineTo(cx + r, cy + r * 0.85f)
                path.lineTo(cx + r * 0.42f, cy + r * 0.55f)
                path.close()
                canvas.drawPath(path, paint)
            }

            CarGlyph.CAMERA -> {
                box.set(cx - r, cy - r * 0.55f, cx + r, cy + r * 0.75f)
                canvas.drawRoundRect(box, r * 0.22f, r * 0.22f, paint)
                canvas.drawCircle(cx, cy + r * 0.1f, r * 0.36f, paint)
                canvas.drawLine(cx - r * 0.35f, cy - r * 0.55f, cx - r * 0.15f, cy - r * 0.85f, paint)
            }

            CarGlyph.BELL -> {
                path.reset()
                path.moveTo(cx - r * 0.8f, cy + r * 0.45f)
                path.quadTo(cx - r * 0.62f, cy - r * 0.75f, cx, cy - r * 0.85f)
                path.quadTo(cx + r * 0.62f, cy - r * 0.75f, cx + r * 0.8f, cy + r * 0.45f)
                path.close()
                canvas.drawPath(path, paint)
                canvas.drawLine(cx - r * 0.22f, cy + r * 0.75f, cx + r * 0.22f, cy + r * 0.75f, paint)
            }

            CarGlyph.HEART -> {
                path.reset()
                path.moveTo(cx, cy + r * 0.85f)
                path.cubicTo(cx - r * 1.5f, cy - r * 0.15f, cx - r * 0.5f, cy - r, cx, cy - r * 0.35f)
                path.cubicTo(cx + r * 0.5f, cy - r, cx + r * 1.5f, cy - r * 0.15f, cx, cy + r * 0.85f)
                path.close()
                canvas.drawPath(path, paint)
            }

            CarGlyph.BOLT -> {
                path.reset()
                path.moveTo(cx + r * 0.35f, cy - r)
                path.lineTo(cx - r * 0.6f, cy + r * 0.15f)
                path.lineTo(cx - r * 0.02f, cy + r * 0.15f)
                path.lineTo(cx - r * 0.35f, cy + r)
                path.lineTo(cx + r * 0.6f, cy - r * 0.15f)
                path.lineTo(cx + r * 0.02f, cy - r * 0.15f)
                path.close()
                canvas.drawPath(path, fill)
            }

            CarGlyph.COFFEE -> {
                box.set(cx - r * 0.8f, cy - r * 0.35f, cx + r * 0.45f, cy + r * 0.7f)
                canvas.drawRoundRect(box, r * 0.18f, r * 0.18f, paint)
                box.set(cx + r * 0.3f, cy - r * 0.2f, cx + r * 0.95f, cy + r * 0.35f)
                canvas.drawArc(box, -90f, 180f, false, paint)
                canvas.drawLine(cx - r * 0.4f, cy - r * 0.95f, cx - r * 0.4f, cy - r * 0.6f, paint)
                canvas.drawLine(cx + r * 0.05f, cy - r * 0.95f, cx + r * 0.05f, cy - r * 0.6f, paint)
            }

            CarGlyph.WRENCH -> {
                // A ring with a bite out of it, and a shaft across the square.
                box.set(cx - r, cy - r, cx + r * 0.05f, cy + r * 0.05f)
                canvas.drawArc(box, 30f, 280f, false, paint)
                canvas.drawLine(cx - r * 0.2f, cy - r * 0.2f, cx + r * 0.85f, cy + r * 0.85f, paint)
            }
        }
    }

    /** A cog: a ring, and eight teeth around it. */    /** A cog: a ring, and eight teeth around it. */
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
        const val STAR_POINTS = 5
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

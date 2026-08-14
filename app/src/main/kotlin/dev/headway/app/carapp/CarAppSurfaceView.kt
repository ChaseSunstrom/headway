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

package dev.headway.app.carapp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import dev.headway.app.ui.theme.Headway

/**
 * The patch of screen a car app draws its own map into.
 *
 * ## Why a real `Surface` and not a bitmap
 *
 * Because this is the one place the template protocol stops being models and
 * starts being pixels, on purpose. A map cannot be a template — it is a
 * continuously re-rendered raster with the app's own tiles, styling and
 * animation — so the library gives the app a `Surface` and lets it draw. The
 * host decides where the surface is, how big, and at what density; the app fills
 * it. That is still not mirroring: the app renders *for this size*, at this dpi,
 * with no letterboxing and no scaling, because it was told the geometry.
 *
 * ## Gestures
 *
 * `ISurfaceCallback` takes scroll, fling, scale and click, which is exactly the
 * set `GestureDetector` and `ScaleGestureDetector` produce. Forwarding them
 * rather than synthesising touches matters: the app has no `MotionEvent` stream
 * and no view hierarchy here, so a pan is a pan only because the host says the
 * word.
 *
 * The coordinates are surface-local, which is what the app expects — it knows
 * the surface's size because Headway told it, and it has no idea where on the
 * car screen the surface sits.
 *
 * ## Density
 *
 * `dpi` comes from the display the view is attached to, which on the car
 * dashboard is `CarDisplay` — created at the resolution and density the head
 * unit negotiated. So the app sizes its own labels and markers for the car
 * rather than for the phone, and that is the difference between a readable map
 * and a screenshot of one.
 */
@SuppressLint("ViewConstructor")
class CarAppSurfaceView(
    context: Context,
    private val session: CarAppSession,
) : SurfaceView(context) {

    private var handle: SurfaceHandle? = null

    private val gestures = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {

            override fun onDown(event: MotionEvent): Boolean = true

            override fun onSingleTapUp(event: MotionEvent): Boolean {
                session.click(event.x, event.y)
                return true
            }

            override fun onScroll(
                down: MotionEvent?,
                event: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                session.scroll(distanceX, distanceY)
                return true
            }

            override fun onFling(
                down: MotionEvent?,
                event: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                session.fling(velocityX, velocityY)
                return true
            }
        },
    )

    private val pinch = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                session.scale(detector.focusX, detector.focusY, detector.scaleFactor)
                return true
            }
        },
    )

    init {
        setBackgroundColor(Headway.GROUND)
        holder.addCallback(object : SurfaceHolder.Callback {

            override fun surfaceCreated(holder: SurfaceHolder) = Unit

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int,
            ) {
                // surfaceChanged and not surfaceCreated: the size is not known
                // until here, and the app is told a width and a height it will
                // render to exactly. Handing it a surface with the wrong
                // geometry produces a map drawn for a different screen.
                val created = SurfaceHandle(
                    surface = holder.surface,
                    width = width,
                    height = height,
                    dpi = resources.displayMetrics.densityDpi,
                )
                this@CarAppSurfaceView.handle = created
                val bounds = Rect(0, 0, width, height)
                session.surfaceReady(created, bounds, bounds)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                handle = null
                session.surfaceGone()
            }
        })
    }

    /** Tells the app the surface is going before the view is thrown away. */
    fun detach() {
        if (handle != null) {
            handle = null
            session.surfaceGone()
        }
        (parent as? android.view.ViewGroup)?.removeView(this)
    }

    /**
     * Touch handling, with the pinch detector fed first.
     *
     * Both detectors see every event on purpose: a two-finger gesture that the
     * scale detector claims would otherwise also register as a scroll, and the
     * map would zoom and pan at once. Feeding the scale detector first and
     * suppressing the scroll while it is in progress is what keeps them apart.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        pinch.onTouchEvent(event)
        if (!pinch.isInProgress) gestures.onTouchEvent(event)
        return true
    }
}

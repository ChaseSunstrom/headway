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
import android.graphics.SurfaceTexture
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView

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
 *
 * ## And when the app ignores the density
 *
 * Some do, and there is no way to make them stop. A driver reported that
 * turning HERE WeGo's size down moved its road labels and Headway's chrome and
 * left the location triangle exactly as big — which is the signature of a
 * marker sized from a pixel constant, or from the *phone's*
 * `Resources.getSystem()` metrics, rather than from anything the host sends.
 * `SurfaceContainer.getDpi()` is advisory and the `Configuration` at
 * `onAppCreate` is advisory; the one number an app cannot ignore is the pixel
 * size of the buffer it is drawing into, because that is the memory.
 *
 * So [pixelScale] enlarges the *buffer* and reports a proportionally larger
 * dpi. Content that honours dpi is drawn N times bigger into a buffer N times
 * bigger and lands on screen at the size it always was; content sized in fixed
 * pixels is drawn at its usual size into a bigger buffer and lands on screen N
 * times *smaller*. One knob, and it moves only the content that ignored the
 * others. `TextureView` does the downscale in the layer transform, so it costs
 * no copy — the price is memory (N² of it) and filtering.
 */
@SuppressLint("ViewConstructor")
class CarAppSurfaceView(
    context: Context,
    private val session: CarAppSession,
    /**
     * How much larger than the pane the app's buffer is, 1.0 for the same size.
     *
     * See the class KDoc. Above 1 it shrinks whatever the app draws in fixed
     * pixels; it is deliberately not a general quality knob, and every gesture
     * coordinate is multiplied by it because the app's coordinate space is the
     * buffer's, not the view's.
     */
    private val pixelScale: Float = 1f,
) : TextureView(context) {

    /** [pixelScale] clamped to what a texture and a driver's patience allow. */
    private val scale: Float get() = pixelScale.coerceIn(MIN_PIXEL_SCALE, MAX_PIXEL_SCALE)

    private var handle: SurfaceHandle? = null

    /**
     * The one `Surface` wrapped around this view's texture, for its whole life.
     *
     * Held rather than rebuilt per size change so that a resize can hand the app
     * a new *handle* without invalidating the surface the old one named. It is
     * released exactly once, after the app has been told the surface is gone.
     */
    private var surface: Surface? = null

    private val gestures = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {

            override fun onDown(event: MotionEvent): Boolean = true

            override fun onSingleTapUp(event: MotionEvent): Boolean {
                // Into buffer coordinates. The app was told the surface is
                // `scale` times the view, so a tap reported in view pixels
                // would land at 1/scale of where the driver touched.
                session.click(event.x * scale, event.y * scale)
                return true
            }

            override fun onScroll(
                down: MotionEvent?,
                event: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                session.scroll(distanceX * scale, distanceY * scale)
                return true
            }

            override fun onFling(
                down: MotionEvent?,
                event: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                session.fling(velocityX * scale, velocityY * scale)
                return true
            }
        },
    )

    private val pinch = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                // The focus is a point and scales; `scaleFactor` is a ratio
                // and does not.
                session.scale(detector.focusX * scale, detector.focusY * scale, detector.scaleFactor)
                return true
            }
        },
    )

    init {
        // No background. This is the whole of "the map never appeared".
        //
        // As a `SurfaceView` this used to call `setBackgroundColor(GROUND)`, and
        // a below-parent `SurfaceView` is not drawn by the window at all -- it
        // is a separate layer SurfaceFlinger composites *behind* the window,
        // visible only through a transparent hole the view punches in the
        // window's canvas. Giving it a `View` background clears
        // `PFLAG_SKIP_DRAW`, which moves it off `dispatchDraw` (punch, draw
        // nothing) and onto `draw()` -- which punches the hole and then calls
        // `super.draw`, whose first act is painting that opaque background
        // straight back over it. The app's map composited underneath the whole
        // time; the pane painted near-black over exactly its rectangle, and the
        // template chrome drew on top. "A few buttons for routing, no map" is
        // that, precisely.
        //
        // It is a `TextureView` now rather than a background-free `SurfaceView`,
        // because ADR 0010 has already settled this for the identical display
        // path: a `SurfaceView` on Headway's *virtual* car display, whose output
        // is a codec input surface, came back as "a black rectangle with no
        // error anywhere" on a real drive, and `AppPaneTile` moved to a
        // `TextureView` for it. That fix postdates this file and was never
        // brought across. A `TextureView` is drawn by the window like any other
        // view, so there is no hole to punch and no background to punch it back.
        isOpaque = false
        surfaceTextureListener = object : SurfaceTextureListener {

            override fun onSurfaceTextureAvailable(
                texture: SurfaceTexture,
                width: Int,
                height: Int,
            ) {
                offer(texture, width, height)
            }

            override fun onSurfaceTextureSizeChanged(
                texture: SurfaceTexture,
                width: Int,
                height: Int,
            ) {
                // The same `Surface`, a new handle. The app is told the geometry
                // it must render to exactly; handing it the wrong one produces a
                // map drawn for a different screen.
                offer(texture, width, height)
            }

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                handle = null
                session.surfaceGone()
                // After `surfaceGone`, never before: the app is drawing into
                // this until it has been told to stop, and releasing first is a
                // use-after-release in somebody else's process.
                surface?.release()
                surface = null
                return true
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
        }
    }

    /**
     * Hands the app a surface of [width] x [height], creating one if needed.
     *
     * @see surface for why it is not rebuilt on every size change.
     */
    private fun offer(texture: SurfaceTexture, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val factor = scale
        val bufferWidth = (width * factor).toInt().coerceAtLeast(1)
        val bufferHeight = (height * factor).toInt().coerceAtLeast(1)
        // Before the Surface is built and before the app is told, because
        // `SurfaceTexture.setDefaultBufferSize` documents that a GL producer
        // must create its EGLSurface *after* the size is set. TextureView
        // re-applies the view's own size in `onSizeChanged` before dispatching
        // the callback that lands here, so this has to run on every resize
        // rather than once.
        if (factor != 1f) {
            runCatching { texture.setDefaultBufferSize(bufferWidth, bufferHeight) }
        }
        val live = surface ?: Surface(texture).also { surface = it }
        val created = SurfaceHandle(
            surface = live,
            width = bufferWidth,
            height = bufferHeight,
            // Scaled with the buffer, so content that *does* honour dpi comes
            // out the same size on screen as it did at 1.0 and only fixed-pixel
            // content moves.
            dpi = (resources.displayMetrics.densityDpi * factor).toInt().coerceAtLeast(1),
        )
        handle = created
        val bounds = Rect(0, 0, bufferWidth, bufferHeight)
        session.surfaceReady(created, bounds, bounds)
    }

    /** Tells the app the surface is going before the view is thrown away. */
    fun detach() {
        if (handle != null) {
            handle = null
            session.surfaceGone()
        }
        surface?.release()
        surface = null
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

    companion object {

        /** No supersampling: the buffer is the pane. */
        const val MIN_PIXEL_SCALE: Float = 1f

        /**
         * The ceiling, chosen by memory rather than by taste.
         *
         * The buffer costs the square of this, and it is a second full-screen
         * RGBA allocation in a process that already holds an encoder, a
         * projection and a media browser. 3.0 on an 800x480 pane is a
         * 2400x1440 buffer — 13 MB — which is as far as this should go without
         * evidence that anything needs more.
         */
        const val MAX_PIXEL_SCALE: Float = 3f
    }
}

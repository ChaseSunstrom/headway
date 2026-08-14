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
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import dev.headway.app.ui.theme.CarMetrics
import dev.headway.protocol.channel.CarInputEvent
import dev.headway.protocol.channel.TouchAction
import dev.headway.protocol.channel.TouchSurface
import dev.headway.video.EncoderConfiguration
import dev.headway.video.ScreenEncoder

/**
 * The car screen as its own display, rendered at the head unit's own resolution.
 *
 * ## Why this replaces mirroring
 *
 * Mirroring was the wrong shape and a real drive showed exactly how wrong. The
 * phone is 1080x2404 and the car panel is 800x480, so the projected image
 * occupied **216 of 800 columns** — a narrow portrait strip in the middle of a
 * landscape dashboard, with 73% of the car's screen inert black bar. Every touch
 * had to be scaled by 0.1997 and letterbox-corrected, every phone notification
 * appeared on the dashboard, and the driver's own screen was on display in their
 * car.
 *
 * Android Auto does not mirror, and this is why. The head unit is a *display*,
 * not a viewport onto the phone: content is composed for its geometry and sent
 * to it. Headway now does the same. A [dev.headway.app.dash.CarDisplay] is
 * created at exactly the resolution and density the head unit advertised, the
 * dashboard is drawn onto it, and the encoder takes that display. There is no
 * scaling, no letterbox, no bar, and the phone's own screen is never in the
 * picture.
 *
 * ## What this buys, beyond looking right
 *
 * - **Touch becomes identity and stops going through the accessibility
 *   service.** The car sends a point in its own coordinate space, the display is
 *   in that same space, and the target is Headway's own window — so the event is
 *   dispatched straight into the view tree. No `TouchTransform`, no
 *   `GestureBuilder`, no `dispatchGesture`, no accessibility grant required for
 *   the dashboard to work at all. That whole chain existed only because the
 *   target was somebody else's window.
 * - **The phone screen is free.** The driver can lock the phone, or use it,
 *   without either affecting the car.
 * - **Screen-off is reachable.** An own-content display's state follows whether
 *   a surface is attached, not whether the phone is awake (ADR 0004 Finding 4).
 * - **No projection consent for video.** `MediaProjection` is still needed to
 *   capture *audio*, but not to draw.
 *
 * ## What it costs
 *
 * Third-party apps cannot appear here — not their windows, anyway. That was
 * already true of any display Headway owns (ADR 0004), and mirroring was the
 * escape hatch. Mirroring is still available as a mode; it is no longer the
 * default, because a 216-pixel strip is not a car interface.
 *
 * ## Why a `Presentation` and not an activity
 *
 * `ActivityTaskSupervisor.isCallerAllowedToLaunchOnDisplay` refuses the *first*
 * activity onto an untrusted virtual display: the `ACTIVITY_EMBEDDING` gate is
 * waived only when the caller already has an activity there, which on a display
 * created a moment ago is never. A `Presentation` is a `Dialog` — a window added
 * through `WindowManager` on a display this process owns — and never enters that
 * path. ADR 0004 Finding 4 has the full chain.
 */
class CarSurface private constructor(
    private val display: CarDisplay,
    private val presentation: DashboardPresentation,
    private val encoder: ScreenEncoder,
    val metrics: CarMetrics,
    private val onStep: (String) -> Unit,
) {

    private val main = Handler(Looper.getMainLooper())

    /**
     * The pointer's current position, for synthesising a coherent gesture.
     *
     * A `MotionEvent` stream has to be well formed — a DOWN, then MOVEs, then an
     * UP, all sharing one `downTime` — or the view tree's own gesture detectors
     * reject it. The car sends exactly that shape, so the only state needed is
     * when the current gesture began.
     */
    private var downTime: Long = 0L

    /** Touches delivered into the dashboard. Diagnostics. */
    @Volatile
    var touchesDelivered: Long = 0L
        private set

    /** Touches that arrived before the surface was ready. Diagnostics. */
    @Volatile
    var touchesDropped: Long = 0L
        private set

    /**
     * Hands one car touch to the dashboard, unchanged.
     *
     * No transform: the display is the car's own size, so the car's coordinates
     * are already the view tree's coordinates. That is the whole point of this
     * class, and it is why the letterbox arithmetic that used to sit here is
     * gone rather than simplified.
     */
    fun deliver(touch: CarInputEvent.Touch) {
        if (touch.surface != TouchSurface.TOUCHSCREEN) return
        // The pointer whose state changed, falling back to the first one down.
        // A touchpad or a report with an out-of-range action_index would
        // otherwise dereference nothing.
        val point = touch.changedPointer ?: touch.pointers.firstOrNull() ?: return
        val action = when (touch.action) {
            TouchAction.DOWN, TouchAction.POINTER_DOWN -> MotionEvent.ACTION_DOWN
            TouchAction.MOVED -> MotionEvent.ACTION_MOVE
            TouchAction.UP, TouchAction.POINTER_UP -> MotionEvent.ACTION_UP
            // A cancel arrives as an UP the view tree can undo cleanly; there is
            // no car action that maps to ACTION_CANCEL's meaning of "the system
            // took this gesture away".
            else -> MotionEvent.ACTION_CANCEL
        }
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN || downTime == 0L) downTime = now

        val event = MotionEvent.obtain(
            downTime,
            now,
            action,
            point.x.toFloat(),
            point.y.toFloat(),
            0,
        )
        if (action == MotionEvent.ACTION_UP) downTime = 0L

        // Windows are main-thread-only, and this arrives on the session's
        // reader coroutine. recycle() happens on the far side so the event is
        // alive for the whole dispatch.
        main.post {
            val handled = runCatching { presentation.dispatchTouchEvent(event) }.getOrDefault(false)
            event.recycle()
            if (handled) touchesDelivered++ else touchesDropped++
        }
    }

    /** Whether the surface is on screen and taking touches. */
    val isShowing: Boolean get() = presentation.isShowing

    fun describe(): String =
        "car surface: ${metrics.describe()}, $touchesDelivered touch(es) delivered, " +
            "$touchesDropped unhandled"

    /**
     * Tears the surface down, in the order that avoids a black frame.
     *
     * Encoder first so nothing is drawing into a surface about to vanish, then
     * the window, then the display. The reverse order leaves the codec pointed
     * at a released surface, which on some devices is a crash rather than a
     * no-op.
     */
    fun stop() {
        runCatching { encoder.stop() }
        main.post { runCatching { presentation.dismiss() } }
        runCatching { display.release() }
    }

    companion object {

        /**
         * Builds the whole car-side pipeline for a negotiated video configuration.
         *
         * Order matters and is not obvious:
         *
         * 1. Create the display **with no surface**. `VirtualDisplayAdapter`
         *    derives display state from whether a surface is attached, so a
         *    display made without one exists, has an id, and is simply off.
         * 2. Show the `Presentation` on it. This is what makes the display have
         *    content; doing it before the encoder means the first encoded frame
         *    already has the dashboard in it rather than being a black frame the
         *    car shows while the window inflates.
         * 3. Attach the encoder's input surface, which turns the display on and
         *    starts frames flowing.
         *
         * @return null when the platform refuses the display, which is a real
         *   answer and not an error: the caller falls back to mirroring and the
         *   log says why.
         */
        fun create(
            context: Context,
            configuration: EncoderConfiguration,
            sink: ScreenEncoder.Sink,
            onStep: (String) -> Unit = {},
        ): CarSurface? {
            val metrics = CarMetrics(
                widthPx = configuration.width,
                heightPx = configuration.height,
                densityDpi = configuration.densityDpi,
            )

            val displayManager = context.getSystemService(DisplayManager::class.java)
            if (displayManager == null) {
                onStep("car surface: no DisplayManager, falling back to mirroring")
                return null
            }

            val display = runCatching {
                CarDisplay.create(
                    displayManager = displayManager,
                    name = DISPLAY_NAME,
                    width = metrics.widthPx,
                    height = metrics.heightPx,
                    densityDpi = metrics.densityDpi,
                )
            }.getOrElse {
                onStep("car surface: the system refused a car display ($it)")
                return null
            }

            val virtual = display.virtualDisplay
            if (virtual == null) {
                onStep("car surface: the car display went away before it could be used")
                runCatching { display.release() }
                return null
            }

            val presentation = DashboardPresentation(context, virtual.display, metrics, onStep)
            val shown = showOnMainThread(presentation, onStep)
            if (!shown) {
                runCatching { display.release() }
                return null
            }

            val encoder = ScreenEncoder(configuration, onStep = onStep)
            val started = runCatching { encoder.startOwnContent(virtual, sink) }
            if (started.isFailure) {
                onStep("car surface: the encoder would not take the car display (${started.exceptionOrNull()})")
                runCatching { presentation.dismiss() }
                runCatching { display.release() }
                return null
            }

            onStep("car surface ready: ${metrics.describe()}, no mirroring, touches are 1:1")
            return CarSurface(display, presentation, encoder, metrics, onStep)
        }

        /**
         * Shows the window and waits for it, because the caller needs to know.
         *
         * `Dialog.show` must run on the main thread and the session is not on it.
         * Blocking briefly here is the honest option: the alternative is
         * returning a `CarSurface` whose window may or may not exist, and every
         * later call having to cope with both.
         */
        private fun showOnMainThread(
            presentation: DashboardPresentation,
            onStep: (String) -> Unit,
        ): Boolean {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                return runCatching { presentation.show(); true }.getOrElse {
                    onStep("car surface: the dashboard window was refused ($it)")
                    false
                }
            }
            val done = java.util.concurrent.CountDownLatch(1)
            var ok = false
            Handler(Looper.getMainLooper()).post {
                ok = runCatching { presentation.show(); true }.getOrElse {
                    onStep("car surface: the dashboard window was refused ($it)")
                    false
                }
                done.countDown()
            }
            // Bounded: a main thread this busy has worse problems, and hanging
            // the session's bring-up on it would spend the head unit's video
            // deadline.
            if (!done.await(SHOW_TIMEOUT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                onStep("car surface: the dashboard window did not appear within $SHOW_TIMEOUT_MILLIS ms")
                return false
            }
            return ok
        }

        /** Shown in `dumpsys display`, so make it recognisable. */
        const val DISPLAY_NAME: String = "Headway car screen"

        private const val SHOW_TIMEOUT_MILLIS: Long = 3_000
    }
}

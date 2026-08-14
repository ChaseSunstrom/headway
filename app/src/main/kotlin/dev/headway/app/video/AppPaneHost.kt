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

package dev.headway.app.video

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import android.view.WindowInsets
import android.view.WindowManager
import dev.headway.app.log.SessionLog
import dev.headway.dash.PaneRect
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG = "HeadwayAppPane"

/**
 * The one live app picture, and which pane it is currently in.
 *
 * ## What this replaces
 *
 * Tapping an app used to call `CarVideoStream.showOnCar(MIRROR)`, which stopped
 * the car surface, released the car display, and gave the whole head unit to a
 * raw capture of the phone. The dashboard did not survive the tap — which is
 * exactly the complaint ADR 0010 exists to answer.
 *
 * Now the projection renders into **a `Surface` belonging to a pane** of the
 * dashboard that is still there. The car display, the `Presentation` and the
 * pane tree live for the whole drive; only the contents of one rectangle change.
 *
 * ## Why exactly one
 *
 * `MediaProjection.createVirtualDisplay` is documented to throw
 * `SecurityException` *"If the target SDK is U and up, and if this instance has
 * already taken a recording through #createVirtualDisplay, but stop() wasn't
 * invoked to end the recording"*. One grant, one virtual display, no
 * negotiation. And a second grant is not obtainable either — the same page says
 * `getMediaProjection` may be redeemed once per consent.
 *
 * That would be a hard limit of one app pane, except that
 * `VirtualDisplay.setSurface` (API 20) exists: *"Sets the surface that backs the
 * virtual display."* So the display is created once and **moved** between panes,
 * which costs a buffer swap and asks the driver nothing. A layout may hold as
 * many app panes as it likes; one of them has the picture, and [bind] is how it
 * changes hands.
 *
 * ## Why an object
 *
 * Same reason as [CarAppDisplay], which this sits beside: the pane tile, the
 * touch router, the launcher and the session assembly all need the same answer
 * and none of them can be handed it without threading a parameter through a
 * dozen signatures. State is guarded by one lock and every field that is read
 * without it is `@Volatile`.
 */
object AppPaneHost {

    /** Told when the picture appears, moves, or goes away. */
    fun interface Listener {
        fun onAppPaneChanged(reason: String)
    }

    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val main = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var display: VirtualDisplay? = null

    /** Whichever pane's surface the display is currently pointed at. */
    private var boundTo: Any? = null
    private var boundWidth = 0
    private var boundHeight = 0

    private var densityDpi = 160
    private var onStep: (String) -> Unit = {}

    /**
     * The size of the display being captured, in its own pixels.
     *
     * Either the simulated secondary display the driver configured, or the
     * phone's own screen when there is none. `PaneFit` needs it to shape the
     * pane's picture, and the touch router needs it to map back.
     */
    @Volatile
    var sourceWidth: Int = 0
        private set

    @Volatile
    var sourceHeight: Int = 0
        private set

    /**
     * True once the platform has told us the captured region's real size.
     *
     * Until then [sourceWidth]/[sourceHeight] are an *inference* -- the display
     * Headway believes is being recorded -- and inference is exactly what goes
     * wrong when the driver shares a single app instead of a whole display. A
     * measured size overrides it and is never overwritten by a guess.
     */
    @Volatile
    var sourceMeasured: Boolean = false
        private set

    /**
     * Where the captured region's top-left corner sits on the phone's screen.
     *
     * ## Why a capture is not always at (0, 0)
     *
     * App screen sharing. The frames hold one app's window and nothing else --
     * Android excludes "the status bar, navigation bar, notifications, and other
     * system UI elements" -- so capture pixel (0, 0) is the *window's* corner,
     * one status bar down from the top of the screen.
     *
     * Touches go the other way. A `GestureDescription` path is absolute on the
     * display, so mapping a car touch into window space and dispatching it as
     * though it were screen space puts every finger high by that same status
     * bar. On a Pixel that is about 48 dp: enough to hit the row above the one
     * the driver aimed at, every time, with nothing in any log to say why. It is
     * most of what "I cannot do anything on it" is.
     *
     * ## Where the number comes from
     *
     * The system-bar insets of display 0, and the measured capture size. If the
     * capture is shorter than the display by at least the status bar, the window
     * starts below the status bar and this is that height; if it is the full
     * height -- a whole-display capture, or an edge-to-edge app -- it is zero.
     * Horizontally the window is centred, which for every phone layout means
     * zero, and the halved difference covers the ones where it does not.
     *
     * Insets rather than the accessibility service on purpose. The service
     * declares `canRetrieveWindowContent="false"`, which is a promise on the
     * grant screen that Headway injects and never observes, and knowing where a
     * window is would mean breaking it. This costs nothing and reads nothing.
     */
    @Volatile
    var sourceOriginX: Int = 0
        private set

    @Volatile
    var sourceOriginY: Int = 0
        private set

    /** True when there is a grant to render with at all. */
    val available: Boolean get() = synchronized(lock) { projection != null }

    /**
     * False while the shared app is completely covered on the phone.
     *
     * Read by the pane, which says so rather than showing a stale frame.
     */
    @Volatile
    var contentVisible: Boolean = true
        private set

    /** True when some pane currently holds the picture. */
    val live: Boolean get() = synchronized(lock) { display != null && boundTo != null }

    /** Whatever pane is live, for the touch router to compare against. */
    val holder: Any? get() = synchronized(lock) { boundTo }

    /**
     * Where the live pane's picture is on the car screen, in car pixels.
     *
     * Published by the shell whenever the view tree settles, and read by the
     * touch router, which are in different parts of the session with no handle
     * on each other. It sits here rather than in the shell because this is
     * already the object both of them know about, and because it belongs with
     * [sourceWidth]/[sourceHeight] — the three of them together are the entire
     * mapping between a finger on the dashboard and a tap in the app.
     *
     * Empty means no pane holds a picture, which the router reads as "every
     * touch is Headway's".
     */
    @Volatile
    var pictureRect: PaneRect = PaneRect(0, 0, 0, 0)

    /**
     * Takes charge of [projection] for the session.
     *
     * The projection is *not* stopped by [detach] — it belongs to the service,
     * which may be holding it across reconnects deliberately (see
     * `HeadwaySettings.KEY_HOLD_PROJECTION`). What this owns is the virtual
     * display, and that it does release.
     *
     * @param projection null when the driver has granted no capture, in which
     *   case every app pane says so instead of showing a black rectangle.
     */
    fun attach(
        context: Context,
        projection: MediaProjection?,
        densityDpi: Int,
        onStep: (String) -> Unit,
    ) {
        val source = sourceGeometry(context)
        synchronized(lock) {
            // The display is kept when the grant is the same object, and that is
            // load-bearing rather than an optimisation. The service holds one
            // `MediaProjection` across reconnects deliberately, and a projection
            // allows exactly one `createVirtualDisplay` for its whole life --
            // the second call throws `SecurityException` on target SDK 34 and
            // up. Releasing the display between sessions therefore spent the
            // grant's one recording, and every session after the first would
            // have had no app pane at all, silently.
            val sameGrant = projection != null && projection === this.projection
            if (!sameGrant) {
                releaseDisplayLocked("a different screen-capture grant")
                unregisterLocked()
            }
            this.projection = projection
            this.densityDpi = densityDpi.coerceAtLeast(1)
            this.onStep = onStep
            if (!sourceMeasured || !sameGrant) {
                sourceWidth = source.first
                sourceHeight = source.second
                if (!sameGrant) sourceMeasured = false
            }
            if (projection != null && !sameGrant) {
                // Required before createVirtualDisplay on API 34+, and needed
                // regardless: the driver can revoke the grant from the status
                // bar at any moment and every app pane has to stop claiming it
                // is showing something.
                val callback = object : MediaProjection.Callback() {

                    /**
                     * The captured region's real size, straight from the platform.
                     *
                     * This is the whole answer to "the panel shows the wrong
                     * shape". When the driver shares one app rather than a
                     * display, the recorded region is that app's window -- not
                     * the phone's screen -- and nothing Headway can read tells
                     * it so. Android 14 added this callback for exactly that,
                     * and without it every app pane fits its picture to a
                     * rectangle the capture does not have: a phone-shaped strip
                     * for an app that is not phone-shaped.
                     */
                    override fun onCapturedContentResize(width: Int, height: Int) {
                        if (width <= 0 || height <= 0) return
                        val origin = captureOrigin(context, width, height)
                        synchronized(lock) {
                            if (sourceWidth == width && sourceHeight == height) return
                            sourceWidth = width
                            sourceHeight = height
                            sourceMeasured = true
                            sourceOriginX = origin.first
                            sourceOriginY = origin.second
                        }
                        onStep(
                            "app pane: the shared content is ${width}x$height at " +
                                "${origin.first},${origin.second} on the phone",
                        )
                        announce("the captured content resized")
                    }

                    /**
                     * Whether the shared app is on screen at all.
                     *
                     * In single-app sharing the captured app can be completely
                     * covered, at which point the platform stops producing
                     * frames and the pane would hold its last one forever --
                     * indistinguishable, on a dashboard, from a frozen car
                     * screen.
                     */
                    override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                        contentVisible = isVisible
                        onStep(
                            if (isVisible) {
                                "app pane: the shared app is visible again"
                            } else {
                                "app pane: the shared app is covered on the phone, so it has " +
                                    "stopped sending frames"
                            },
                        )
                        announce("captured visibility changed")
                    }

                    override fun onStop() {
                        onStep("screen sharing was stopped, so app panes have nothing to show")
                        synchronized(lock) {
                            releaseDisplayLocked("the grant was revoked")
                            this@AppPaneHost.projection = null
                        }
                        announce("screen sharing stopped")
                    }
                }
                runCatching { projection.registerCallback(callback, main) }
                projectionCallback = callback
            }
        }
        onStep(
            if (projection == null) {
                "app panes: no screen-sharing grant, so an app pane will offer to ask for one"
            } else {
                "app panes: ready, capturing a ${source.first}x${source.second} source"
            },
        )
        announce("attached")
    }

    /**
     * Points the picture at [owner]'s [surface], creating the display the first
     * time.
     *
     * @param width the surface's real width in car pixels — the pane has already
     *   fitted it to the source's proportions, so this rectangle has the
     *   source's aspect and the capture fills it with no letterbox of its own.
     * @return false when there is nothing to show, which the pane renders as an
     *   explanation rather than as black.
     */
    fun bind(owner: Any, surface: Surface, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        if (!surface.isValid) return false
        synchronized(lock) {
            val token = projection ?: return false
            val existing = display
            if (existing == null) {
                val created = runCatching {
                    token.createVirtualDisplay(
                        DISPLAY_NAME,
                        width,
                        height,
                        densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                        surface,
                        null,
                        null,
                    )
                }.getOrElse { error ->
                    // The two documented reasons are a stopped projection and a
                    // second recording on one grant. Both are states the driver
                    // can reach, and neither may take the session down: the
                    // dashboard is still drawing everything else.
                    //
                    // The grant is dropped along with its callback, because both
                    // reasons are permanent for this projection: retrying would
                    // throw again on every surface the panes offer, once per
                    // layout pass.
                    SessionLog.shared.warn(TAG, "no app-pane display: $error")
                    onStep("app pane: the system refused a capture display ($error)")
                    unregisterLocked()
                    projection = null
                    return false
                }
                if (created == null) {
                    onStep("app pane: the system returned no capture display")
                    return false
                }
                display = created
                boundTo = owner
                boundWidth = width
                boundHeight = height
                onStep("app pane: showing the app in a ${width}x$height pane")
            } else {
                val moved = boundTo !== owner
                if (boundWidth != width || boundHeight != height) {
                    runCatching { existing.resize(width, height, densityDpi) }
                    boundWidth = width
                    boundHeight = height
                }
                runCatching { existing.surface = surface }
                boundTo = owner
                if (moved) onStep("app pane: moved the app to another pane")
            }
        }
        announce("bound")
        return true
    }

    /**
     * Lets go of [owner]'s surface, if it is the one holding the picture.
     *
     * The display is kept and merely detached — `setSurface(null)` is documented
     * as *"a similar effect to turning off the screen"* — because releasing it
     * would burn the one recording this grant is allowed and the next pane would
     * have nothing to bind to.
     */
    fun unbind(owner: Any) {
        var changed = false
        synchronized(lock) {
            if (boundTo !== owner) return
            boundTo = null
            boundWidth = 0
            boundHeight = 0
            runCatching { display?.surface = null }
            changed = true
        }
        if (changed) announce("unbound")
    }

    /**
     * Ends the session's use of the panes, keeping the display for the next one.
     *
     * Deliberately not a teardown. See [attach]: one grant allows one virtual
     * display for its lifetime, and the service holds the grant across
     * reconnects, so releasing here would leave every later session unable to
     * show an app. What ends is the *binding* -- no pane holds the picture, and
     * the geometry is forgotten so a pane cannot fit against a stale source.
     */
    fun detach() {
        synchronized(lock) {
            boundTo = null
            boundWidth = 0
            boundHeight = 0
            runCatching { display?.surface = null }
            sourceWidth = 0
            sourceHeight = 0
            pictureRect = PaneRect(0, 0, 0, 0)
            onStep = {}
        }
        announce("detached")
    }

    /**
     * The real teardown, for when the process is done with the grant.
     *
     * Called from the service's `onDestroy`, which is also where the projection
     * itself stops mattering. Everything here is idempotent.
     */
    fun release() {
        synchronized(lock) {
            releaseDisplayLocked("the service is going away")
            unregisterLocked()
            projection = null
            sourceWidth = 0
            sourceHeight = 0
            sourceMeasured = false
            sourceOriginX = 0
            sourceOriginY = 0
            contentVisible = true
            pictureRect = PaneRect(0, 0, 0, 0)
            onStep = {}
        }
        announce("released")
    }

    fun observe(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun unobserve(listener: Listener) {
        listeners.remove(listener)
    }

    /** One line for the session log. */
    fun describe(): String = synchronized(lock) {
        when {
            projection == null -> "app pane: no capture grant"
            display == null -> "app pane: granted, nothing bound"
            boundTo == null -> "app pane: detached from every pane"
            else -> "app pane: live at ${boundWidth}x$boundHeight from ${sourceWidth}x$sourceHeight"
        }
    }

    private fun announce(reason: String) {
        listeners.forEach { runCatching { it.onAppPaneChanged(reason) } }
    }

    private fun releaseDisplayLocked(why: String) {
        val going = display ?: return
        display = null
        boundTo = null
        boundWidth = 0
        boundHeight = 0
        runCatching { going.surface = null }
        runCatching { going.release() }
        SessionLog.shared.info(TAG, "released the app-pane display: $why")
    }

    private fun unregisterLocked() {
        val callback = projectionCallback ?: return
        projectionCallback = null
        runCatching { projection?.unregisterCallback(callback) }
    }

    /**
     * What the projection is capturing, as far as Headway can tell.
     *
     * It cannot be measured: `MediaProjection` exposes no getter for the display
     * the driver picked in the consent dialog. So this is the same inference
     * `CarAppDisplay` documents — the simulated display if one is configured and
     * the feature is on, otherwise display 0 — and a driver who configures one
     * and then picks "Entire screen" in the dialog gets a pane containing their
     * phone screen, correctly fitted, with touches aimed at a display they are
     * not looking at. The setup screen names the row to pick; there is no API
     * that would let this class check.
     */
    private fun sourceGeometry(context: Context): Pair<Int, Int> {
        CarAppDisplay.active?.let { return it.width to it.height }
        val manager = context.getSystemService(DisplayManager::class.java)
        val display = manager?.getDisplay(Display.DEFAULT_DISPLAY)
        // The display *as it is oriented*, not its physical panel. `Display.Mode`
        // reports the unrotated dimensions, so a phone held in landscape would
        // give 1080x2404 for a capture that is really 2404x1080 -- and every
        // touch mapped through it would land somewhere else entirely.
        val metrics = display?.let {
            runCatching { context.createDisplayContext(it).resources.displayMetrics }.getOrNull()
        } ?: runCatching { context.resources.displayMetrics }.getOrNull()
        val width = metrics?.widthPixels ?: 0
        val height = metrics?.heightPixels ?: 0
        if (width > 0 && height > 0) return width to height
        val mode = display?.mode
        return (mode?.physicalWidth?.takeIf { it > 0 } ?: 1080) to
            (mode?.physicalHeight?.takeIf { it > 0 } ?: 1920)
    }

    /**
     * Where a capture of [width]x[height] starts on the phone's screen.
     *
     * See [sourceOriginX] for why this is needed at all. Deliberately forgiving:
     * every failure path returns (0, 0), which is exactly right for a
     * whole-display capture and no worse than the behaviour this replaced for
     * anything else.
     */
    private fun captureOrigin(context: Context, width: Int, height: Int): Pair<Int, Int> {
        // A simulated display is captured whole, so its capture starts at its
        // own origin and there are no system bars in the way.
        if (CarAppDisplay.active != null) return 0 to 0
        val display = sourceGeometry(context)
        val displayWidth = display.first
        val displayHeight = display.second
        if (displayWidth <= 0 || displayHeight <= 0) return 0 to 0
        val insets = runCatching {
            val windows = context.getSystemService(WindowManager::class.java)
            windows?.currentWindowMetrics?.windowInsets
                ?.getInsets(WindowInsets.Type.systemBars())
        }.getOrNull()
        val statusBar = insets?.top ?: 0
        val left = ((displayWidth - width) / 2).coerceAtLeast(0)
        // Only when the capture is genuinely short of the display by at least a
        // status bar. An edge-to-edge app fills the height and starts at the top,
        // and giving that one an offset would break what currently works.
        val top = if (statusBar > 0 && displayHeight - height >= statusBar) statusBar else 0
        return left to top
    }

    private const val DISPLAY_NAME = "headway-app-pane"
}

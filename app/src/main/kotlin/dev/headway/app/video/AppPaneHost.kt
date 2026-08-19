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

    /**
     * The exact `Surface` the display is pointed at, kept for identity.
     *
     * A pane hands back the same instance every time -- see `AppPaneTile.attach`
     * -- so identity is what tells a repeat bind from a real one. Without it
     * `bind` had no way to know it was being asked for something it had already
     * done, and it announced regardless. See the `changed` check in [bind].
     */
    private var boundSurface: Surface? = null

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
     * True once the capture is known to be **one app** rather than the whole
     * display.
     *
     * Measured, not assumed: the platform reports the captured region's real
     * size through `onCapturedContentResize`, and a region smaller than display 0
     * is a single-app capture. Headway cannot ask the consent dialog what the
     * driver picked, and asking the driver a second time on the car screen is
     * worse than measuring.
     *
     * This is what makes the blackout safe to apply by itself. Covering the
     * phone with a black window is invisible to an app capture -- Android
     * excludes system UI from one -- and is captured in full by a *display*
     * capture, where it would send the car a perfectly black rectangle while
     * every diagnostic reported frames arriving. So the cover goes up only when
     * this is true. See `HeadwayService.coverPhoneScreen`.
     */
    @Volatile
    var sharingSingleApp: Boolean = false
        private set

    /**
     * Whether the capture is of the *simulated* display rather than display 0.
     *
     * The other case in which the phone screen can safely be covered, and the
     * one that was missing. `sharingSingleApp` answers "is this smaller than
     * every display it could be", so a whole capture of a 720x480 simulated
     * display measures as *not* single-app — correctly — and the cover was then
     * refused. But the thing being recorded there is the simulated display, and
     * the blackout is a window on display 0, which is not in that recording at
     * all. So the phone can be black and the car still gets a picture.
     *
     * That is what makes the Developer-options preview window hideable, which
     * the README has claimed and this had quietly stopped delivering.
     */
    @Volatile
    var sharingOtherDisplay: Boolean = false
        private set

    /**
     * Called on the first measurement, with whether it is a single-app capture.
     *
     * Exists because the answer arrives *after* the session is up: the
     * measurement comes with the first frames, and the service that wants to act
     * on it has long since finished starting. Set by `HeadwayService`, cleared
     * when the session ends.
     */
    @Volatile
    var onSharingKnown: ((Boolean) -> Unit)? = null

    /**
     * Called when the platform takes the capture grant away.
     *
     * The usual cause is not the driver: **Android 15 QPR1 and later stop a
     * MediaProjection unconditionally when the device locks**, and there is no
     * app-side exemption for it. Whoever owns the grant has to hear about that
     * and offer it again, or the app pane is dead until the app is restarted.
     */
    @Volatile
    var onGrantLost: (() -> Unit)? = null

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

    /**
     * The app in front on the phone, as the accessibility service last saw it.
     *
     * Written by `HeadwayAccessibilityService.onAccessibilityEvent` and read by
     * [showsWantedApp]. Null until the service has seen a window change, or
     * when the driver has not granted accessibility -- and null is treated as
     * "cannot tell", which shows the picture rather than hiding it. Guessing
     * the other way would blank a working pane for anyone without the grant.
     */
    @Volatile
    var foregroundPackage: String? = null
        set(value) {
            if (field == value) return
            field = value
            // Panes redraw from this, so a change has to reach them. Cheap: a
            // window change is a human-scale event, not a frame-rate one.
            announce("foreground app is ${value ?: "unknown"}")
        }

    /**
     * Whether the phone's assistant is the window in front.
     *
     * Kept apart from [foregroundPackage] on purpose. An assistant session is
     * an overlay *over* whatever the driver was doing, not a replacement for
     * it, and treating it as one is what made talking to the assistant look
     * like Headway crashing: the pane decided the driver had left their app,
     * covered itself, and then never uncovered -- because dismissing an
     * overlay does not change the state of the window underneath, so no second
     * event ever arrived to say the app was back.
     */
    @Volatile
    var assistantInFront: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            announce(if (value) "the assistant is in front" else "the assistant has gone")
        }

    /**
     * The app the driver last opened into a pane, if any.
     *
     * Set by `CarShell.openApp` rather than taken from the pane's own
     * configuration: a pane can be a general "show me an app" pane with no
     * preferred package, and what matters is what the driver actually asked
     * for. Cleared when the pane goes away.
     */
    @Volatile
    var openedPackage: String? = null

    /**
     * Whether the capture is still showing the app the pane was opened for.
     *
     * Only meaningful for a whole-screen share. A single-app share has
     * `onCapturedContentVisibilityChanged` to answer this properly, and the
     * platform tells the truth there; a whole-screen capture is always
     * "visible" by definition, because the screen is always showing something.
     * That something stopped being the driver's app the moment they closed it,
     * and the car went on showing their launcher, their messages, whatever came
     * next. This is what stops that.
     *
     * @param wanted the package the pane was opened for, or null when the pane
     *   is showing the phone generally rather than one app -- in which case
     *   there is nothing to compare and the picture stands.
     */
    fun showsWantedApp(wanted: String?): Boolean {
        if (wanted == null) return true
        if (sharingSingleApp) return true
        val front = foregroundPackage ?: return true
        return front == wanted
    }

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
     * which holds it across reconnects so that a car that drops and comes back
     * does not cost the driver a second consent dialog. What this owns is the
     * virtual display, and that it does release.
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
                        // A capture the size of a display, to within a pixel of
                        // rounding, is that display. Anything smaller than every
                        // display it could be is one app's window. See
                        // [capturesSingleApp] for why "every" rather than "the".
                        val single = capturesSingleApp(context, width, height)
                        val other = capturesOtherDisplay(context, width, height)
                        val changed = synchronized(lock) {
                            // `sourceMeasured` is part of the test, not just the
                            // size. `detach` clears it, and a re-attach on the
                            // same grant does not re-register this callback --
                            // so without it, a session whose capture is the same
                            // size as the last one would keep the "not measured
                            // yet" answer and never derive a verdict at all.
                            if (sourceMeasured && sourceWidth == width && sourceHeight == height) {
                                false
                            } else {
                                sourceWidth = width
                                sourceHeight = height
                                sourceMeasured = true
                                sourceOriginX = origin.first
                                sourceOriginY = origin.second
                                sharingSingleApp = single
                                sharingOtherDisplay = other
                                true
                            }
                        }
                        if (!changed) return
                        runCatching { onSharingKnown?.invoke(single) }
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
                        // The service holds its own reference to the same grant,
                        // and clearing only this one left that copy stale for the
                        // life of the process: the "share your screen" notification
                        // was never re-offered, and every later session handed the
                        // dead token back. A lock screen therefore killed app panes
                        // permanently rather than until the next unlock.
                        runCatching { onGrantLost?.invoke() }
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
        // Set only where something really moved. See the `changed` check below
        // the block: this used to announce on every call, and the announcement
        // comes back round as another call.
        var changed = false
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
                boundSurface = surface
                changed = true
                onStep("app pane: showing the app in a ${width}x$height pane")
            } else {
                val moved = boundTo !== owner
                if (boundWidth != width || boundHeight != height) {
                    runCatching { existing.resize(width, height, densityDpi) }
                    boundWidth = width
                    boundHeight = height
                    changed = true
                }
                if (boundSurface !== surface) {
                    runCatching { existing.surface = surface }
                    boundSurface = surface
                    changed = true
                }
                boundTo = owner
                if (moved) {
                    changed = true
                    onStep("app pane: moved the app to another pane")
                }
            }
        }
        // Only when something moved, and this is not a nicety.
        //
        // `announce` runs its listeners synchronously, and the only listener is
        // the shell, which answers by posting a refresh of every app pane
        // (`CarShell.appPaneChanged`). `AppPaneTile.applyState` ends that
        // refresh by re-driving the bind -- deliberately, so a pane that lost
        // the picture and got it back is not left black -- which called this
        // again, which announced again, which posted again.
        //
        // Nothing in that ring ever decremented. One extra runnable on the main
        // looper per pass, forever, on the same thread that measures, lays out
        // and draws the dashboard being encoded to the head unit. It never
        // blocked input outright, so it did not look like a hang; it looked
        // like a car screen that had gone treacly, which is how it was
        // reported. And when the pane is covered -- the app closed on the
        // phone, exactly the state the cover exists for -- each pass also ran
        // `updateDormantText`, which is two PackageManager IPCs.
        //
        // A bind that changes nothing has nothing to tell anyone.
        if (changed) announce("bound")
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
            boundSurface = null
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
            boundSurface = null
            runCatching { display?.surface = null }
            sourceWidth = 0
            sourceHeight = 0
            // The verdict goes with the geometry it was derived from. Left
            // standing, a `true` latched from a session that shared one app
            // makes `HeadwayService.coverPhoneScreen` -- which is gated on
            // exactly this flag and runs eagerly at bring-up, before any
            // measurement -- black out the phone at the start of a *later*
            // session that is sharing the whole display. The car's picture then
            // is the cover. Zeroing the size while leaving "measured" true was
            // its own contradiction: a measurement of 0x0.
            sourceMeasured = false
            sharingSingleApp = false
            sharingOtherDisplay = false
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
            sharingSingleApp = false
            sharingOtherDisplay = false
            contentVisible = true
            openedPackage = null
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
        boundSurface = null
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
        return phoneGeometry(context)
    }

    /**
     * Whether a capture of [width]x[height] is one app's window rather than a display.
     *
     * The question this answers decides whether the phone screen may be covered,
     * and getting it wrong in one direction sends the car a black rectangle for
     * the rest of the drive. So it is asked of *every* display the capture could
     * be, and answers "single app" only when the capture is smaller than all of
     * them.
     *
     * Comparing against one display is not enough once a simulated display
     * exists. That display can be larger than the phone's own screen -- a
     * 1920x720 car geometry against a 1080x2400 panel is wider than it -- so a
     * driver who configured one and then shared their whole *phone* screen
     * produced a capture narrower than the reference and was called single-app,
     * which raised the cover over the very picture the car was being sent.
     *
     * Erring the other way is cheap: a single app mistaken for a display gets no
     * cover and a pane fitted to the display's rectangle, which is what every
     * build before app sharing did.
     */
    /**
     * Whether this capture is the simulated display, whole.
     *
     * Deliberately narrow: it matches the simulated display's own geometry and
     * nothing else, so a phone that happens to be the same size as the car
     * display is still excluded by the second test. See [sharingOtherDisplay]
     * for what it is used for and why the answer differs from
     * [capturesSingleApp].
     */
    private fun capturesOtherDisplay(context: Context, width: Int, height: Int): Boolean {
        val simulated = CarAppDisplay.active ?: return false
        val matches = kotlin.math.abs(simulated.width - width) <= 1 &&
            kotlin.math.abs(simulated.height - height) <= 1
        if (!matches) return false
        val (phoneWidth, phoneHeight) = phoneGeometry(context)
        return kotlin.math.abs(phoneWidth - width) > 1 || kotlin.math.abs(phoneHeight - height) > 1
    }

    private fun capturesSingleApp(context: Context, width: Int, height: Int): Boolean {
        val candidates = buildList {
            CarAppDisplay.active?.let { add(it.width to it.height) }
            add(phoneGeometry(context))
        }
        return candidates.all { (displayWidth, displayHeight) ->
            displayWidth - width > 1 || displayHeight - height > 1
        }
    }

    /** Display 0, as it is oriented right now. */
    private fun phoneGeometry(context: Context): Pair<Int, Int> {
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
        // A simulated display is captured whole and has no system bars, so its
        // capture starts at its own origin. Decided by the measurement rather
        // than by the display merely existing: a driver who has one configured
        // and shares a single app on the *phone* is capturing display 0, bars
        // and all, and short-circuiting on `active != null` gave that capture an
        // origin of (0, 0) and a picture offset by the height of a status bar.
        CarAppDisplay.active?.let { simulated ->
            if (simulated.width - width <= 1 && simulated.height - height <= 1) return 0 to 0
        }
        val display = phoneGeometry(context)
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

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
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import dev.headway.video.EncoderConfiguration
import dev.headway.video.ScreenEncoder

/** Raised when the platform will not give Headway a display to put the car screen on. */
class CarDisplayException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * The display the dashboard lives on: a private virtual display Headway owns,
 * created straight from `DisplayManager` and deliberately not through
 * `MediaProjection`.
 *
 * ## Why this exists next to the projection path rather than replacing it
 *
 * Headway fills the car screen two ways and they need two different displays.
 *
 * Mirroring — the escape hatch of ADR 0004, showing whatever the phone itself is
 * showing — can only be done by capturing display 0, and capturing display 0
 * means `MediaProjection` and the consent dialog that comes with it. Nothing
 * here changes that; [ScreenEncoder.startCapture] still owns that path and still
 * has to, because there is no other way to get an arbitrary third-party app onto
 * the car screen at all.
 *
 * The dashboard is the other way, and it cannot share the projection's display
 * for two independent reasons.
 *
 * The first is the lock screen. ADR 0004 Finding 3: when the screen turns off,
 * `DisplayPolicy.screenTurnedOff` takes a sleep token for display 0,
 * `DisplayContent.shouldSleep()` becomes true, the activities on it are paused,
 * and the mirrored picture goes black. No foreground service type, wake lock or
 * projection flag touches that, because it is a property of the display being
 * asleep rather than of the capture. Finding 4 is the counterpart:
 * `VirtualDisplayAdapter` derives a virtual display's state from whether its
 * `Surface` is non-null, and sleep tokens are per-display, so the token taken
 * for display 0 leaves this one awake and an activity of Headway's own on it
 * stays resumed with the phone locked and dark. "Survives screen lock" is a
 * Definition of Done item, and this display is how it is met.
 *
 * The second is that on Android 17 a projection-backed display cannot host tasks
 * at all. `ActivityTaskSupervisor` asks `Display.canHostTasks()` before placing
 * anything, and `LogicalDisplay.validateCanHostTasksLocked` answers false
 * whenever `shouldOnlyMirror()` is true — which `VirtualDisplayAdapter` defines
 * as "created through a `MediaProjection`". A `DisplayManager` display carrying
 * [DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY] takes the early true
 * branch and is allowed. So putting Headway's *own* dashboard activity on the
 * mirroring display, which earlier releases permitted, is now refused outright.
 * This class is not a tidier arrangement of the same capability; on this
 * platform it is the only arrangement left.
 *
 * ## What may appear on it
 *
 * A `Presentation`, and nothing else — **not an activity, not even Headway's
 * own.** This is the opposite of what an earlier draft of this file said, and
 * the difference matters enough to spell out.
 *
 * `ActivityTaskSupervisor.isCallerAllowedToLaunchOnDisplay` runs three checks in
 * order, and the third is the one everyone quotes:
 *
 * ```java
 * if (!display.isTrusted()) {
 *     if ((aInfo.flags & FLAG_ALLOW_EMBEDDED) == 0) return false;                 // 1
 *     if (checkPermission(ACTIVITY_EMBEDDING, ...) == PERMISSION_DENIED
 *             && !uidPresentOnDisplay) return false;                              // 2
 * }
 * if (display.getOwnerUid() == callingUid) return true;                           // 3
 * ```
 *
 * This display is untrusted — `VirtualDisplayAdapter` sets `FLAG_TRUSTED` only
 * for `VIRTUAL_DISPLAY_FLAG_TRUSTED`, which is `@SystemApi` behind
 * `ADD_TRUSTED_DISPLAY` (`signature|role`) and out of bounds under CLAUDE.md
 * constraint 2. So checks 1 and 2 both apply. Headway's dashboard clears 1 by
 * declaring `allowEmbedded` on itself, and then **fails 2**: `ACTIVITY_EMBEDDING`
 * is `signature|privileged`, and `uidPresentOnDisplay` is false because
 * `DisplayContent.isUidPresent` matches `ActivityRecord`s and a display that has
 * just been created has none. Check 3 is never reached.
 *
 * The owner-uid branch is therefore not an alternative to the untrusted gate —
 * it sits after it, and only ever helps a *second* activity once one is already
 * resident. There is no way to get the first one there. An activity cannot
 * bootstrap this display.
 *
 * A `Presentation` can, because it is a `Dialog` — a window added through
 * `WindowManager` on a display its own process owns, which never enters the
 * activity-launch path at all. That is the documented purpose of the
 * `Presentation` API and it is the only route this class can serve.
 *
 * Other apps reach the car screen as data drawn into a [DashTile], never as
 * windows, and that part is unchanged.
 *
 * ## Consent, or the absence of it
 *
 * This display costs the driver nothing. There is no projection, so no dialog on
 * every reconnect, nothing for the system to revoke from the status bar, and
 * none of the Android 14 one-display-per-projection bookkeeping that
 * [ScreenEncoder] carries in order to survive an encoder restart. A reconnect
 * can rebuild the entire video path without asking the driver anything, which is
 * the behaviour CLAUDE.md requires of reconnection.
 *
 * ## Lifecycle
 *
 * The display is created with the instance and torn down by [release], which is
 * idempotent. [virtualDisplay] reads null once released, so a caller that raced
 * the teardown gets a null rather than a call into a dead token; [displayId] and
 * [display] are captured eagerly and keep reading afterwards, because a log line
 * naming the display that just went away is worth more than a null.
 */
class CarDisplay private constructor(
    created: VirtualDisplay,
    /** Narrates each step; wired to the debug log export in the app. */
    private val onStep: (String) -> Unit,
) {

    private val lock = Any()

    /** Null once released, which is what makes [release] safe to call twice. */
    private var held: VirtualDisplay? = created

    /**
     * The display object, for `Context.createDisplayContext` and for anything
     * that wants to read the geometry the system actually gave us rather than
     * the geometry that was asked for.
     *
     * Captured eagerly and never cleared. After [release] it is invalid —
     * `Display.isValid()` says so — but it still carries a name and an id, and
     * diagnosing a session from its log is easier when those survive.
     */
    val display: Display = created.display

    /**
     * What to hand `ActivityOptions.setLaunchDisplayId` when launching one of
     * Headway's own activities onto the car screen.
     */
    val displayId: Int = display.displayId

    /**
     * The raw display, for [ScreenEncoder.startOwnContent], or null once
     * released.
     *
     * Exposed rather than wrapped because the encoder must resize the display to
     * whatever geometry the head unit negotiated and point it at its own codec
     * input surface. Mirroring those two calls onto this class would give the
     * display two owners of its mutable state and no way to tell which of them
     * wrote it last; one owner and a plain accessor is the honest shape.
     */
    val virtualDisplay: VirtualDisplay? get() = synchronized(lock) { held }

    /** False once the display has been released, by us or by the system. */
    val alive: Boolean get() = synchronized(lock) { held != null }

    /**
     * Tears the display down. Idempotent, and safe from a
     * `VirtualDisplay.Callback`.
     *
     * Anything still drawing on the display stops having somewhere to draw, so
     * the encoder should be stopped first; nothing here enforces that, because
     * the system may call this path itself when it stops the display and there
     * is no ordering to enforce by then.
     */
    fun release() {
        val going = synchronized(lock) {
            val current = held ?: return
            held = null
            current
        }
        // runCatching rather than a bare call: the system may already have
        // destroyed the display underneath us, in which case releasing the token
        // throws and there is nothing left to do about it.
        runCatching { going.release() }
        onStep("car display $displayId released")
    }

    /** One line for the session log. */
    fun describe(): String {
        val current = virtualDisplay ?: return "car display $displayId: released"
        val surface = if (current.surface != null) "surface attached" else "no surface"
        return "car display $displayId '${display.name}': ${stateName(display.state)}, $surface"
    }

    private fun stateName(state: Int): String = when (state) {
        Display.STATE_ON -> "on"
        Display.STATE_OFF -> "off"
        Display.STATE_DOZE -> "dozing"
        Display.STATE_DOZE_SUSPEND -> "dozing, suspended"
        Display.STATE_ON_SUSPEND -> "on, suspended"
        Display.STATE_VR -> "vr"
        Display.STATE_UNKNOWN -> "unknown"
        else -> "state $state"
    }

    companion object {
        /** Name the dashboard's display appears under in `dumpsys display`. */
        const val DISPLAY_NAME: String = "Headway dashboard"

        /**
         * Exactly [DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY], and the
         * absences matter as much as the presence.
         *
         * `OWN_CONTENT_ONLY` is what the platform reads when it decides whether
         * the display may host tasks, so it is set explicitly rather than left to
         * be implied by the absence of `PUBLIC` and `AUTO_MIRROR`. The implication
         * is documented, but the dashboard's ability to exist should not rest on
         * a documented side effect when the flag that the check actually reads can
         * simply be named.
         *
         * `PUBLIC` is not set, so the display stays visible only to the uid that
         * owns it. Nothing about the dashboard wants an audience: a public display
         * is one other applications can enumerate and route media to, and — absent
         * `OWN_CONTENT_ONLY`, which is the documented interaction between the two
         * flags — one that auto-mirrors display 0, which is the mirroring path's
         * job rather than this one's.
         *
         * `PRESENTATION` is not set. Its documented effect is to register the
         * display in `DisplayManager.DISPLAY_CATEGORY_PRESENTATION` so that
         * applications may automatically project onto it; because this display is
         * private, the only process that could ever enumerate that category and
         * find it is Headway, which is already holding the object. It would buy
         * nothing and would invite this app's own `MediaRouter` to offer the car
         * screen as a route.
         *
         * `SECURE` is not set: it exists so a display may present DRM-protected
         * surfaces, which the dashboard never does, and it is not a capability to
         * ask for speculatively.
         */
        const val FLAGS: Int = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY

        /**
         * Creates the dashboard's display at the geometry the head unit
         * negotiated.
         *
         * @param surface where the display's content goes. Normally null, which
         *   is not a degenerate case: `VirtualDisplayAdapter` derives the
         *   display's state from whether a surface is attached, so a display made
         *   without one exists, has an id, and is simply off until
         *   [ScreenEncoder.startOwnContent] attaches the codec's input surface.
         *   That ordering is what lets the encoder be torn down and rebuilt —
         *   every reconnect does exactly that — while the display, its
         *   [displayId] and its tasks all stay put.
         *
         *   Note "tasks", not "everything keeps running": the same
         *   surface-implies-state rule that makes a detached display survive
         *   also puts it in `STATE_OFF`, which makes `DisplayContent.shouldSleep`
         *   true and *pauses* whatever is on it until a surface returns. A
         *   reconnect is therefore a pause and a resume, not an uninterrupted
         *   session, and anything reading the dashboard's lifecycle should
         *   expect that cycle rather than be surprised by it.
         *
         *   A caller holding a surface already, such as a test drawing with
         *   `Canvas`, may pass one.
         * @param callbackHandler where `VirtualDisplay.Callback` is delivered.
         * @throws CarDisplayException when the system refuses the display, or
         *   stops it before it can be handed back.
         */
        fun create(
            displayManager: DisplayManager,
            width: Int,
            height: Int,
            densityDpi: Int,
            surface: Surface? = null,
            name: String = DISPLAY_NAME,
            callbackHandler: Handler = Handler(Looper.getMainLooper()),
            onStep: (String) -> Unit = {},
        ): CarDisplay {
            require(width > 0 && height > 0) { "display size must be positive: ${width}x$height" }
            require(densityDpi > 0) { "display density must be positive: $densityDpi" }

            // The callback has to be handed to createVirtualDisplay before the
            // CarDisplay that would answer it can exist, so it forwards through a
            // holder that is filled the instant construction succeeds.
            val lifecycle = DisplayLifecycle(onStep)
            val created = try {
                displayManager.createVirtualDisplay(
                    name,
                    width,
                    height,
                    densityDpi,
                    surface,
                    FLAGS,
                    lifecycle,
                    callbackHandler,
                )
            } catch (e: Exception) {
                throw CarDisplayException("the system refused the car display", e)
            } ?: throw CarDisplayException("the system returned no car display")

            val carDisplay = CarDisplay(created, onStep)
            lifecycle.owner = carDisplay
            // Closes the window between createVirtualDisplay returning and the
            // holder being filled. Vanishingly unlikely, but a display stopped in
            // that window would otherwise be reported alive forever and its token
            // leaked for the life of the process.
            if (lifecycle.stoppedBeforeHandover) carDisplay.release()
            // Checked on the outcome rather than on the flag, because the flag
            // only covers one of the two orderings. If `onStopped` reads a
            // non-null owner just after the line above, it releases the display
            // itself and never sets the flag — and this method would have
            // returned a CarDisplay whose token was already gone, contradicting
            // its own @throws and leaving the caller with a null
            // `virtualDisplay` and no explanation. Asking whether it is alive
            // catches both orderings and needs no extra state.
            if (!carDisplay.alive) {
                throw CarDisplayException("the system stopped the car display before it was usable")
            }
            onStep(
                "car display ${carDisplay.displayId} '$name' created, " +
                    "${width}x$height at $densityDpi dpi, own content only"
            )
            return carDisplay
        }

        /**
         * As [create], resolving `DisplayManager` from a context.
         *
         * @throws CarDisplayException when the context has no display service,
         *   which on a phone means something is very wrong rather than that the
         *   feature is unavailable.
         */
        fun create(
            context: Context,
            width: Int,
            height: Int,
            densityDpi: Int,
            surface: Surface? = null,
            name: String = DISPLAY_NAME,
            callbackHandler: Handler = Handler(Looper.getMainLooper()),
            onStep: (String) -> Unit = {},
        ): CarDisplay {
            val displayManager = context.getSystemService(DisplayManager::class.java)
                ?: throw CarDisplayException("this device has no display manager")
            return create(
                displayManager, width, height, densityDpi, surface, name, callbackHandler, onStep,
            )
        }

        /**
         * As [create], taking the geometry from what the video channel
         * negotiated.
         *
         * Worth its own overload because the display and the encoder must agree
         * exactly: the head unit chose one advertised `VideoConfiguration` and
         * anything else here scales the dashboard against the car's expectation,
         * which shows up as a slightly soft, slightly wrong screen rather than as
         * an error. Passing the configuration removes the chance to transcribe it
         * wrong. It is the full [EncoderConfiguration.width] and
         * [EncoderConfiguration.height] that are wanted here, not the visible
         * size: the car's margins say which part of the frame the panel shows,
         * not how large the frame is.
         */
        fun create(
            displayManager: DisplayManager,
            configuration: EncoderConfiguration,
            surface: Surface? = null,
            name: String = DISPLAY_NAME,
            callbackHandler: Handler = Handler(Looper.getMainLooper()),
            onStep: (String) -> Unit = {},
        ): CarDisplay = create(
            displayManager = displayManager,
            width = configuration.width,
            height = configuration.height,
            densityDpi = configuration.densityDpi,
            surface = surface,
            name = name,
            callbackHandler = callbackHandler,
            onStep = onStep,
        )
    }
}

/**
 * Forwards `VirtualDisplay.Callback` to the [CarDisplay] that owns the display.
 *
 * Separate from [CarDisplay] only because the callback must be constructed
 * before the display, and the display before the [CarDisplay].
 *
 * `onStopped` is the one that matters. The system can stop a virtual display of
 * its own accord — the callback exists because it can — and a stopped display
 * never resumes, yet its token still has to be released by the application.
 * Without this the car screen would go black, the display would leak for the
 * life of the process, and the session log would say nothing at all about why.
 * CLAUDE.md asks that a single drive's log be enough to diagnose a fault, and a
 * display dying in silence is exactly the fault that would defeat that.
 */
private class DisplayLifecycle(private val onStep: (String) -> Unit) : VirtualDisplay.Callback() {

    @Volatile
    var owner: CarDisplay? = null

    @Volatile
    var stoppedBeforeHandover: Boolean = false

    override fun onPaused() {
        onStep("car display paused: nothing is consuming its frames")
    }

    override fun onResumed() {
        onStep("car display resumed")
    }

    override fun onStopped() {
        val current = owner
        if (current == null) {
            onStep("car display stopped by the system; it will not resume")
            stoppedBeforeHandover = true
            return
        }
        // A deliberate release fires this callback too, and used to log it as a
        // system kill. `VirtualDisplayAdapter.destroyLocked(binderAlive)`
        // dispatches `displayStopped` whenever the binder is still alive, which
        // is every app-initiated release; only `binderDied` passes false. So the
        // most alarming line about this display was printed on its most ordinary
        // event, which is the opposite of what a log is for.
        //
        // `release()` nulls the holder before releasing the token, so a false
        // `alive` here means Headway asked for this.
        if (!current.alive) {
            onStep("car display stopped, following Headway's own release")
            return
        }
        onStep("car display stopped by the system; it will not resume")
        // Stopped is not released: the display is dead but its token is not, and
        // this is the only place that knows.
        current.release()
    }
}

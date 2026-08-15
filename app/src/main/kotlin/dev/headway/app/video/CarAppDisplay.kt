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
import android.view.Display
import dev.headway.app.log.SessionLog
import dev.headway.app.ui.HeadwaySettings

private const val TAG = "HeadwayAppDisplay"

/**
 * Where a third-party app goes when the driver taps it, and where its touches
 * come back to.
 *
 * ## Why this is one shared answer rather than a parameter
 *
 * Three unrelated places need it and none of them can be handed it: the launcher
 * tile decides where to start an activity, `CarInputStream` decides which
 * display to aim a gesture at and what geometry to scale into, and the setup
 * screen reports what is going to happen. Threading a display id from the
 * session through all three would touch a dozen signatures to carry one integer
 * that is constant for the whole drive.
 *
 * ## Why the answer is a setting and not a probe
 *
 * Because `MediaProjection` will not say which display it is recording. The
 * driver chooses that in the system consent dialog — Android 17 lists each
 * connected display as its own row — and there is no public getter afterwards.
 * So Headway cannot detect the choice; it can only be told, which is what the
 * `Render apps on the car display` switch on the setup screen is for. With it on
 * and a simulated display present, everything below aims there; with it off, or
 * with no such display, everything aims at the phone exactly as before.
 *
 * A driver who turns it on and then picks "Entire screen" in the consent dialog
 * gets the old mirrored view with touches going to a display they are not
 * looking at. That is a real failure mode and the only defence is the setup
 * screen saying, in the sentence next to the switch, which row to pick.
 *
 * ## Lifetime
 *
 * Resolved once per session, because the display cannot appear or vanish
 * mid-drive without the driver going into Settings — and if they do, the next
 * session picks it up. [clear] on session end, so a stale id from a display the
 * platform has since destroyed never aims a gesture into nothing.
 */
object CarAppDisplay {

    @Volatile
    private var target: OverlayDisplayInfo? = null

    /** The simulated display in use, or null when apps go to the phone's screen. */
    val active: OverlayDisplayInfo? get() = target

    /** What `setLaunchDisplayId` and `GestureDescription.Builder` should be told. */
    val displayId: Int get() = target?.displayId ?: Display.DEFAULT_DISPLAY

    /** True when a third-party app will render at the car's size rather than be mirrored. */
    val native: Boolean get() = target != null

    /**
     * Decides for this session, and says what it decided.
     *
     * Called once during bring-up. Returns the display or null; the log line is
     * emitted either way, because "apps will be mirrored" is exactly as much of
     * an answer as the other one and a driver debugging a squashed car screen
     * needs to see which they got.
     */
    fun resolve(context: Context, onStep: (String) -> Unit): OverlayDisplayInfo? {
        if (!enabled(context)) {
            target = null
            onStep("car app display: off; apps will be mirrored from the phone screen")
            return null
        }
        val found = OverlayDisplay.find(context)
        target = found
        if (found == null) {
            onStep(
                "car app display: on, but this phone has no simulated display. Turn on " +
                    "Developer options, Simulate secondary displays, and pick an entry " +
                    "without \"(secure)\". Apps will be mirrored until then",
            )
        } else {
            SessionLog.shared.info(TAG, "rendering apps on $found")
            onStep(
                "car app display: apps will render natively on $found and be captured " +
                    "from there, not from the phone screen",
            )
            // Said every time, and loudly, because it is the difference between
            // a feature and a demo. A Developer-options display is
            // `Display.TYPE_OVERLAY`, and the accessibility service that injects
            // the car's touches can never reach one -- the platform excludes
            // that type from the display list it builds injectors for, by type
            // and not by permission. See `OverlayDisplayInfo.takesTouch`.
            if (!found.takesTouch) {
                SessionLog.shared.warn(
                    TAG,
                    "$found is a simulated display; Android does not let an accessibility " +
                        "service inject touch onto one, so this pane is view-only",
                )
                onStep(
                    "car app display: this is a simulated display, so the car can SEE the app " +
                        "but cannot touch it -- Android refuses injected gestures on this kind " +
                        "of display, whatever permissions Headway holds. Turn the setting off " +
                        "to mirror the phone screen instead, where touch works",
                )
            }
        }
        return found
    }

    fun clear() {
        target = null
    }

    /** The driver's switch. Off by default: it needs a Developer options toggle first. */
    fun enabled(context: Context): Boolean =
        HeadwaySettings.of(context).getBoolean(HeadwaySettings.KEY_NATIVE_APP_DISPLAY, false)
}

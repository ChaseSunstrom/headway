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

import android.app.Activity
import android.content.Intent
import android.util.Log

/**
 * Two real third-party apps, side by side, on the car screen.
 *
 * ## Why this exists when the rest of the package draws its own panes
 *
 * Everything else here renders content Headway obtained as *data* — media
 * sessions, notifications, hosted widgets — because ADR 0004 establishes that no
 * unprivileged app can place another app's activity on a display it owns, nor
 * set another app's window bounds. A system split screen is the one arrangement
 * that escapes both findings, for the simple reason that the system owns it:
 * Headway is not positioning anything, it is asking for a task to be started
 * into a layout the window manager already maintains. This file is therefore the
 * only path by which two arbitrary apps can both be live on the mirrored screen
 * at once, at full framerate, with touch working in both.
 *
 * ## What is and is not possible, verified against `android17-release`
 *
 * **Headway cannot create a split.** The accessibility route is gone.
 * `GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN` (=7) had its handler removed from
 * `SystemActionPerformer` between `android11-release` and `android13-release`,
 * and `android17-release` contains no occurrence of `SPLIT_SCREEN` in that file
 * at all. On Android 13 and later `performGlobalAction(7)` returns `false` with
 * no log line and no exception, which is the worst failure mode there is: code
 * built on it does nothing and reports success in every way a caller can check.
 *
 * **Headway can fill a split that already exists.**
 * [Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT] (0x00001000) is neither removed nor
 * deprecated. `ActivityStarter` L3104-3120 (`android17-release`) honours it when
 * the intent also carries [Intent.FLAG_ACTIVITY_NEW_TASK] *and* the start has a
 * non-null `mSourceRecord` — that is, when the caller is an **Activity**.
 * `android17-release` adds a third condition: the source task must not have set
 * `isLaunchAdjacentDisabled()`.
 *
 * That `mSourceRecord` requirement is why every function in this object takes an
 * [Activity] and not a `Context`. A `Service` has no activity record, so the
 * identical call from `HeadwayService` would lose the adjacency and open the app
 * fullscreen — silently, again, because an ignored intent flag is not an error.
 * Demanding an `Activity` in the signature makes the compiler enforce at the
 * call site what the window manager would otherwise enforce wrongly at runtime.
 *
 * ## The resulting shape of the feature
 *
 * The driver creates a split once, parked, following [HOW_TO_SPLIT]. From then
 * on Headway fills either half on demand. There is no way to shorten that first
 * step, and pretending otherwise would ship a button that does nothing.
 */
object SplitFill {

    /**
     * What to tell the driver, because only they can create the split.
     *
     * Written for a Pixel running gesture navigation, which is the target
     * configuration in CLAUDE.md, and kept literal about the gestures: this text
     * goes in front of somebody sitting in a car, and a step that is nearly
     * right is worse than no instructions, because they will keep trying it.
     *
     * Long-press rather than tap on the icon: a single tap opens the same menu
     * on current Pixel builds, but long-press has worked on every release that
     * had the menu at all, so it is the instruction that does not need a version
     * check attached to it.
     */
    const val HOW_TO_SPLIT: String =
        "Android only lets you set up a split screen yourself — no app can do it for you. " +
            "On a Pixel with gesture navigation:\n" +
            "1. Swipe up from the bottom of the screen and hold, to open Recents.\n" +
            "2. Long-press the app's icon at the top of its card.\n" +
            "3. Choose Split screen.\n" +
            "4. Pick the second app from Recents.\n" +
            "Do this while parked. Once the split exists, Headway can put apps into either half."

    /**
     * Starts [packageName]'s launcher activity in the other half of the split.
     *
     * @return true when the system accepted the start, which is the most this
     *   can honestly report. `Activity.startActivity` returns void and says
     *   nothing about placement, so true means the app was launched — not that a
     *   split exists, and not that the app landed in the half the driver was
     *   looking at. The nearest available check is the imprecise
     *   [isLikelyInSplit], and this function deliberately does not consult it:
     *   see there for why guessing wrong must not block the launch.
     */
    fun launchAdjacent(activity: Activity, packageName: String): Boolean =
        launch(activity, packageName, adjacent = true)

    /**
     * Opens [first] normally, then opens [second] beside it.
     *
     * What this does not do is create the split. If the system is not already in
     * split screen both starts still succeed, `FLAG_ACTIVITY_LAUNCH_ADJACENT` is
     * ignored rather than rejected, and the driver ends up looking at [second]
     * fullscreen where they asked for two apps. Nothing in the return value can
     * distinguish that outcome, so a caller for whom it matters should show
     * [HOW_TO_SPLIT] first.
     *
     * The two starts go out back to back with no delay between them, which is
     * safe because `startActivity` is a synchronous binder call into the
     * activity task manager: the second start is evaluated against the state the
     * first produced rather than racing it.
     *
     * Which app ends up in which half is the window manager's decision and this
     * makes no attempt to influence it. Choosing deliberately would need
     * `WindowContainerTransaction`, which ADR 0004 records as absent from both
     * `current.txt` and `system-current.txt` — there is no protection level at
     * which it becomes available.
     *
     * @return true when both starts were accepted. [second] is attempted even
     *   when [first] fails, since a driver who asked for two apps is better
     *   served by one of them than by neither.
     */
    fun launchPair(activity: Activity, first: String, second: String): Boolean {
        val startedFirst = launch(activity, first, adjacent = false)
        val startedSecond = launch(activity, second, adjacent = true)
        return startedFirst && startedSecond
    }

    /**
     * A best-effort guess at whether the system is in split screen right now.
     *
     * No public API answers this question. `Activity.isInMultiWindowMode` is the
     * closest thing and it is also true in freeform and picture-in-picture, so
     * on its own it would claim a split on a desktop-windowed phone. The second
     * test narrows it geometrically: a split divides the screen along one axis
     * and spans it along the other, so exactly one of the two dimensions is
     * reduced, whereas a freeform or picture-in-picture window is smaller in
     * both. Requiring the reduction on exactly one axis separates the cases,
     * comparing `WindowManager.getCurrentWindowMetrics()` against
     * `getMaximumWindowMetrics()`.
     *
     * How far to trust it. This is a hint for the UI and never a gate.
     * `getMaximumWindowMetrics` is specified in terms of the largest bounds this
     * window may expect rather than the physical display's, and nothing promises
     * those coincide in every windowing mode; where the platform reports the
     * pane itself as the maximum, this returns false and the driver is shown
     * [HOW_TO_SPLIT] they did not need. That is the harmless direction to be
     * wrong in, and it is why [launchAdjacent] ignores this function entirely —
     * the window manager's own answer, arrived at during the start, is the only
     * authoritative one, and a wrong guess here must not be what stops an app
     * from opening.
     *
     * Call it from `onResume` or later. `isInMultiWindowMode` reads state the
     * framework sets as the activity is created and reconfigured, so asking
     * during `onCreate` can see the value from before the current configuration
     * was applied.
     */
    fun isLikelyInSplit(activity: Activity): Boolean = runCatching {
        if (!activity.isInMultiWindowMode) return@runCatching false

        val windowManager = activity.windowManager
        val current = windowManager.currentWindowMetrics.bounds
        val maximum = windowManager.maximumWindowMetrics.bounds
        if (maximum.width() <= 0 || maximum.height() <= 0) return@runCatching false

        val narrowed = current.width().toFloat() < maximum.width() * AXIS_FULL_FRACTION
        val shortened = current.height().toFloat() < maximum.height() * AXIS_FULL_FRACTION
        // Exactly one axis, hence the inequality rather than an or: both axes
        // reduced is a freeform or picture-in-picture window, neither is
        // fullscreen, and only one is the divided screen we are looking for.
        narrowed != shortened
    }.getOrElse {
        // A hint about the window layout is never worth taking the car launcher
        // down for, and this runs on whatever surface the driver is touching.
        Log.w(TAG, "could not read window metrics; assuming no split", it)
        false
    }

    /**
     * The one place a launch intent is built, so both halves of [launchPair] get
     * the same treatment and the flag reasoning lives once.
     */
    private fun launch(activity: Activity, packageName: String, adjacent: Boolean): Boolean {
        // A start issued from an activity the framework has already torn down
        // has no source record to be adjacent to, which is precisely the
        // condition this whole file depends on.
        if (activity.isFinishing || activity.isDestroyed) {
            Log.w(TAG, "not launching $packageName: the source activity is gone")
            return false
        }

        val intent = activity.packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            // Normal for a package with no launcher entry point, and for one the
            // user has since uninstalled or disabled. Not an error.
            Log.w(TAG, "$packageName has no launchable activity")
            return false
        }

        // NEW_TASK is set explicitly even though getLaunchIntentForPackage
        // already returns an intent carrying it: that is an implementation
        // detail of the resolver rather than a documented guarantee, and
        // ActivityStarter drops the adjacency outright if the flag is missing,
        // so the app would open fullscreen with nothing to say why.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (adjacent) {
            intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        }
        // FLAG_ACTIVITY_MULTIPLE_TASK is deliberately not set. It would force a
        // second instance of an app that is already running, so a driver putting
        // their already-open maps app into the other pane would get a fresh copy
        // with the route gone. Reusing the existing task is what they meant.

        return runCatching { activity.startActivity(intent) }
            .onFailure {
                // ActivityNotFoundException when the package went away between
                // resolving and starting, SecurityException when its launcher
                // activity turns out not to be exported to us.
                Log.w(TAG, "could not launch $packageName (adjacent=$adjacent)", it)
            }
            .isSuccess
    }

    /**
     * How much of an axis a pane must span to count as spanning all of it.
     *
     * A split pane occupies the full extent of the axis it is not divided along,
     * so in principle this could be an equality test; the margin absorbs the
     * divider, insets and rounding. On the other axis a Pixel allows the split
     * to sit between roughly a third and two thirds, well clear of this
     * threshold in every position.
     */
    private const val AXIS_FULL_FRACTION = 0.9f

    private const val TAG = "HeadwaySplit"
}

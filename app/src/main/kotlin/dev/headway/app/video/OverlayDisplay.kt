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
 */

package dev.headway.app.video

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.view.Display
import dev.headway.app.log.SessionLog

private const val TAG = "HeadwayOverlay"

/**
 * A secondary display the *system* created, and what it measures.
 *
 * @param displayId what `ActivityOptions.setLaunchDisplayId` and
 *   `GestureDescription.Builder.setDisplayId` take.
 */
data class OverlayDisplayInfo(
    val displayId: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    /**
     * Whether a car touch can ever reach an app on this display.
     *
     * **False for every Developer-options simulated display, permanently.**
     * `AccessibilityManagerService.AccessibilityDisplayListener.isValidDisplay`
     * opens with
     *
     * ```java
     * if (display == null || display.getType() == Display.TYPE_OVERLAY) {
     *     return false;
     * }
     * ```
     *
     * so an overlay display never enters `getValidDisplayList()`, which is what
     * `AccessibilityInputFilter` builds the per-display `MotionEventInjector`
     * map from. `dispatchGesture` uses the display id purely as a key into that
     * map and answers `onPerformGestureResult(sequence, false)` when the lookup
     * misses. Headway builds its gestures correctly --
     * `GestureDescription.Builder().setDisplayId(...)` with the simulated
     * display's id -- and the platform discards them.
     *
     * It is a hardcoded type exclusion rather than a permission, so no grant,
     * no flag and no amount of `canRetrieveWindowContent` changes it. A driver
     * reported it as "I cant tap inside of it"; this is why, and it is not
     * fixable from inside an app.
     *
     * ## Why this is not read from the display
     *
     * `Display.getType()` and `Display.TYPE_OVERLAY` are both `@hide`, so an
     * unprivileged app cannot ask a display what kind it is without reaching
     * for a non-SDK API — which is out of bounds here. What it *can* do is
     * observe that this list exists for one feature, "Simulate secondary
     * displays", and that every display the feature produces is an overlay
     * display. So the answer is derived from `system`: a display the system
     * itself created and offered here is one accessibility will not inject
     * onto. A genuine external panel would be a false positive, and it would
     * cost a warning that is wrong rather than a feature that is broken.
     */
    val takesTouch: Boolean = true,
) {
    override fun toString(): String = "$name (#$displayId, ${width}x$height @ ${densityDpi}dpi)" +
        if (takesTouch) "" else " [view only -- accessibility cannot inject touch here]"
}

/**
 * The one display a driver can create that another app's activity may live on.
 *
 * ## Why this exists, and why it is the answer to "no screen mirroring"
 *
 * ADR 0004 established that Headway cannot host a third-party activity on a
 * display it creates, and that has not changed. The reason is precise:
 * `ActivityTaskSupervisor.isCallerAllowedToLaunchOnDisplay` puts the
 * `android:allowEmbedded` requirement behind `if (!display.isTrusted())`, and a
 * display an app creates is never trusted — `ADD_TRUSTED_DISPLAY` is
 * `signature|role` and every role that carries it is `systemOnly="true"`.
 *
 * What that phrasing leaves open is a display the driver creates through
 * Settings. **Developer options → Simulate secondary displays** builds an
 * overlay `LogicalDisplay`, and `OverlayDisplayAdapter` marks it trusted in as
 * many words: *"The display is trusted since it is created by system."* Because
 * it is trusted and not private, the embedding gate is skipped entirely and any
 * app may launch any activity onto it. No permission is involved at all — it is
 * a Settings toggle, which is exactly the class of thing this project is allowed
 * to ask a user for.
 *
 * The result is not mirroring in any sense. Organic Maps, launched onto a
 * 720×480 display, *lays itself out for 720×480*: its own fonts at that density,
 * its own layout for that aspect, its own composition. Headway captures that
 * display's frames — never display 0 — and the car sees the app's real rendering
 * at 1:1 pixels, pillarboxed to the negotiated 800×480 with a 40-pixel bar each
 * side. Against the 216 usable columns of 800 that display-0 mirroring gives on
 * this phone, that is 720 of 800.
 *
 * ## The two costs, which are real and are not hidden
 *
 * 1. **The geometry comes from a fixed list.** `Settings.Global`'s
 *    `OVERLAY_DISPLAY_DEVICES` needs `WRITE_SECURE_SETTINGS`, which is out of
 *    reach, so Headway cannot widen SettingsLib's twelve-entry menu and cannot
 *    ask for 800×480. 720×480 at 142 dpi is the closest and is what the setup
 *    screen names.
 * 2. **The phone's screen has to stay on.** `OverlayDisplayWindow` forwards
 *    display 0's power state to the overlay device, so a dark phone is a dark
 *    overlay display and every app on it stops drawing. Headway holds the screen
 *    on and turns the brightness down; that is the whole of the mitigation and
 *    the README says so.
 *
 * A third thing the driver will notice: the overlay display is *also* drawn on
 * the phone, half size and slightly transparent, as a draggable window. That is
 * the platform's own preview and it cannot be turned off. The captured stream is
 * clean full-resolution content regardless.
 *
 * ## Detection is structural, not by name
 *
 * `Display.TYPE_OVERLAY` is `@hide` and the display's name is a localised
 * resource, so neither is usable. What is public and sufficient: it is not
 * display 0, it is not private, it is not secure, and it advertises itself as a
 * presentation display. Headway's own displays are private (`CarDisplay` uses
 * `OWN_CONTENT_ONLY` without `PUBLIC`), so they cannot be mistaken for it — and
 * the capture display, which *is* public, is excluded by name.
 */
object OverlayDisplay {

    /**
     * The best simulated display on this phone, or null when there is none.
     *
     * "Best" is the widest, because the car panel is wider than it is tall and a
     * wider source pillarboxes less. Density is not weighed: the driver picked
     * an entry from a menu and Headway uses what they picked.
     */
    fun find(context: Context): OverlayDisplayInfo? = all(context).maxByOrNull { it.width }

    /** Every candidate, for the diagnostics screen and for [describe]. */
    fun all(context: Context): List<OverlayDisplayInfo> {
        val manager = context.getSystemService(DisplayManager::class.java) ?: return emptyList()
        val displays = runCatching { manager.displays }.getOrNull().orEmpty()
        return displays.mapNotNull { display ->
            if (display.displayId == Display.DEFAULT_DISPLAY) return@mapNotNull null
            val flags = display.flags
            // Private rules out everything Headway itself creates. Secure rules
            // out the "(secure)" entries in the Settings menu, which carry
            // FLAG_SECURE and which MediaProjection refuses to record — picking
            // one produces a black car screen and no error, so it must never be
            // offered.
            if (flags and Display.FLAG_PRIVATE != 0) return@mapNotNull null
            if (flags and Display.FLAG_SECURE != 0) return@mapNotNull null
            if (flags and Display.FLAG_PRESENTATION == 0) return@mapNotNull null
            val name = display.name.orEmpty()
            // The capture display is public and presentation-flagged too. It is
            // ours, it is named by us, and launching an app onto it would put
            // the app inside the very sink that is recording it.
            if (name.contains(HEADWAY_DISPLAY_MARKER, ignoreCase = true)) return@mapNotNull null
            val mode = display.mode
            val metrics = runCatching {
                context.createDisplayContext(display).resources.displayMetrics
            }.getOrNull()
            OverlayDisplayInfo(
                displayId = display.displayId,
                name = name.ifBlank { "Display ${display.displayId}" },
                width = mode?.physicalWidth?.takeIf { it > 0 } ?: metrics?.widthPixels ?: 0,
                height = mode?.physicalHeight?.takeIf { it > 0 } ?: metrics?.heightPixels ?: 0,
                densityDpi = metrics?.densityDpi ?: 0,
                // Kept, but marked. See [OverlayDisplayInfo.takesTouch] --
                // including why this cannot ask the display directly.
                takesTouch = false,
            )
        }.filter { it.width > 0 && it.height > 0 }
    }

    /**
     * Starts an app on the simulated display.
     *
     * `setLaunchDisplayId` is public API and needs no permission; what it needs
     * is for the display to accept the launch, which is the whole point of using
     * a trusted one. `MULTIPLE_TASK` alongside `NEW_TASK` so an app already open
     * on the phone gets a second task on this display rather than the platform
     * bringing the existing one forward on display 0 — which would look, from
     * the car, exactly like the launch being ignored.
     */
    fun launch(
        context: Context,
        packageName: String,
        displayId: Int,
        onStep: (String) -> Unit,
    ): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            onStep("car app display: $packageName has nothing to launch")
            return false
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (displayId != Display.DEFAULT_DISPLAY) {
            // On a secondary display, an app already open on the phone would
            // otherwise be brought forward *there* instead, which from the car
            // looks exactly like the launch being ignored.
            intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        } else {
            // On the phone's own display it is the opposite: MULTIPLE_TASK makes
            // a second copy of the app every time the driver taps it, so a drive
            // ends with a stack of Maps instances and none of them where they
            // left off. Returning to what they last saw is the whole point.
            intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        // The application context on purpose. A caller inside the car screen
        // holds a *display context* for the car display, and since Android 10 an
        // activity started from one inherits that display -- which
        // ActivityTaskSupervisor then refuses, because no third-party app
        // declares android:allowEmbedded. The explicit launch display id should
        // win, and starting from a context with no display of its own means it
        // does not have to.
        val launcher = context.applicationContext
        val started = runCatching { launcher.startActivity(intent, options.toBundle()) }
        started.exceptionOrNull()?.let { error ->
            SessionLog.shared.warn(TAG, "could not launch $packageName on #$displayId: $error")
            onStep("car app display: $packageName would not open on the car display ($error)")
            return false
        }
        onStep("car app display: opened $packageName on display #$displayId")
        return true
    }

    /** Developer options, where the display is created. Resolves on every build. */
    fun settingsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** One line for the setup screen and for the session log. */
    fun describe(context: Context): String {
        val found = all(context)
        if (found.isEmpty()) return "No simulated display. Apps will be mirrored instead."
        val best = found.maxByOrNull { it.width }!!
        return if (found.size == 1) best.toString() else "$best (+${found.size - 1} more)"
    }

    /**
     * Every display, for the diagnostics screen.
     *
     * Includes the ones [all] rejects and says why it rejected them, because the
     * likely failure is the driver picking a `(secure)` entry from the Settings
     * menu — which looks identical in the list, produces a display that is there
     * and unusable, and gives no clue on its own.
     */
    fun diagnose(context: Context): String {
        val manager = context.getSystemService(DisplayManager::class.java)
            ?: return "DisplayManager is unavailable."
        val displays = runCatching { manager.displays }.getOrNull().orEmpty()
        return buildString {
            append(displays.size)
            appendLine(" display(s):")
            displays.forEach { display ->
                val flags = display.flags
                val mode = display.mode
                val density = runCatching {
                    context.createDisplayContext(display).resources.displayMetrics.densityDpi
                }.getOrNull()
                append("  #").append(display.displayId).append(' ')
                append('"').append(display.name).append("\" ")
                append(mode?.physicalWidth).append('x').append(mode?.physicalHeight)
                append(" @ ").append(density).append("dpi")
                val notes = buildList {
                    if (display.displayId == Display.DEFAULT_DISPLAY) add("the phone's own")
                    if (flags and Display.FLAG_PRIVATE != 0) add("private")
                    if (flags and Display.FLAG_SECURE != 0) add("SECURE — cannot be recorded")
                    if (flags and Display.FLAG_PRESENTATION != 0) add("presentation")
                }
                if (notes.isNotEmpty()) append(" [").append(notes.joinToString(", ")).append(']')
                appendLine()
            }
            val usable = all(context)
            if (usable.isEmpty()) {
                appendLine(
                    "None can host another app. Turn on Developer options → " +
                        "Simulate secondary displays and pick an entry WITHOUT \"(secure)\".",
                )
            } else {
                appendLine("Usable for native app rendering: " + usable.joinToString(", "))
            }
        }
    }

    /**
     * The substring that marks a display as Headway's own.
     *
     * Both `ScreenEncoder`'s capture display and `CarDisplay` are named with it,
     * so [all] can exclude them without knowing their ids.
     */
    const val HEADWAY_DISPLAY_MARKER = "headway"
}

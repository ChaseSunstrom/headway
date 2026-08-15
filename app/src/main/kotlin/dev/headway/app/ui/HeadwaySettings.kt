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

package dev.headway.app.ui

import android.content.Context
import android.content.SharedPreferences
import dev.headway.dash.AllowedApps
import dev.headway.dash.RailStyle

/**
 * Persisted user choices, in one place so the two activities cannot disagree.
 *
 * `SharedPreferences` rather than DataStore: four scalars, no migration story
 * needed, and one fewer dependency in an app that has to be auditable for
 * F-Droid.
 */
object HeadwaySettings {

    const val PREFS_NAME: String = "headway"

    /** Set once the user has seen the driving-safety notice. */
    const val KEY_SAFETY_NOTICE_ACCEPTED: String = "safety_notice_accepted"

    /**
     * The optional restriction CLAUDE.md describes: "provide an optional
     * 'parked-only for video apps' toggle (off by default, user's choice — this
     * is a user-freedom project, not a nanny)".
     */
    const val KEY_PARKED_ONLY_VIDEO: String = "parked_only_video"

    /** Package names shown on the car launcher grid. */
    const val KEY_PINNED_APPS: String = "pinned_apps"

    /**
     * Which surface the car shows when a session comes up: the multi-pane
     * dashboard, or the plain app grid.
     *
     * Defaults to the dashboard, because it is a superset — one of its panes is
     * the app grid, and the mirror pane is one tap from the plain phone screen.
     * A driver who dislikes it turns this off and gets exactly what build 79
     * gave them.
     */
    const val KEY_CAR_SURFACE_DASHBOARD: String = "car_surface_dashboard"

    /**
     * The package the Maps pane opens, and whose notification it trusts.
     *
     * Unset by default, in which case the first app that handles `geo:` is used
     * — see `MapsTile.mapApps`. Stored rather than resolved fresh each time
     * because "whichever the platform lists first" is stable enough to look
     * deliberate and not stable enough to *be* deliberate, and a driver who
     * picked Organic Maps should not get HERE WeGo after an update reorders the
     * resolver.
     */
    const val KEY_MAP_APP: String = "map_app"

    /**
     * Whether a third-party app should render on a simulated secondary display
     * rather than be mirrored from the phone's screen.
     *
     * Off by default because it needs a Developer options toggle first, and a
     * switch that silently does nothing until the driver has been somewhere
     * else in Settings is worse than one they have to find. `CarAppDisplay` has
     * the derivation and the two costs.
     */
    const val KEY_NATIVE_APP_DISPLAY: String = "native_app_display"

    /**
     * Cover the phone screen, and the simulated display's preview, while driving.
     *
     * Off by default. It is the only way to hide the preview window Developer
     * options puts on the phone — Android has no setting for it and the window
     * cannot be closed, because it *is* the simulated display's output surface.
     * Covering needs a `TYPE_ACCESSIBILITY_OVERLAY`, which only the
     * accessibility service can add, so this does nothing until that is granted.
     *
     * Default off because it covers the status bar too, and a black screen the
     * driver did not ask for looks exactly like a phone that has crashed.
     *
     * Turn it on **with single-app sharing**, where it is the closest thing to a
     * phone that is off: the screen has to stay lit for the shared app to keep
     * drawing, and this makes it black to look at without touching what is
     * captured, because app screen sharing excludes system UI. With
     * whole-display sharing it would black out the car screen too.
     *
     * **On by default**, which is only safe because the cover gates itself:
     * `HeadwayService.coverPhoneScreen` puts it up once `AppPaneHost` has
     * *measured* the capture as a single app, and never for a whole-display one.
     * This KDoc said "a choice and not a default" while both readers passed
     * `true`.
     */
    const val KEY_BLANK_PHONE_SCREEN: String = "blank_phone_screen"

    /**
     * The car screen's palette, as `ThemeChoice.encode()`.
     *
     * Unset means the dark cyan Headway has always been. See ADR 0010 for why
     * this is one string rather than a night-mode flag.
     */
    const val KEY_THEME: String = "car_theme"

    /**
     * The rail: the driver's pinned layouts and apps, in order. See `Rail`.
     *
     * Distinct from [KEY_PINNED_APPS], which is the unordered set behind the
     * all-apps grid. They answer different questions — "which apps do I want a
     * shortcut to" and "which six things do I reach for without looking" — and
     * folding them together would make the rail reorder itself whenever the
     * grid was edited.
     */
    const val KEY_RAIL: String = "car_rail"

    /**
     * The rail's edge, size and clock, as `RailStyle` JSON.
     *
     * Separate from [KEY_RAIL], which is the driver's pinned *items*. The two
     * change for different reasons -- pinning an app is a daily act, moving the
     * rail is a once-ever one -- and keeping them apart means an unreadable
     * style cannot cost somebody their pins.
     */
    const val KEY_RAIL_STYLE: String = "car_rail_style"

    /**
     * Bring a session up on its own when the car's Bluetooth appears.
     *
     * On by default. The whole point of the feature is that the driver gets in
     * and drives; a switch that has to be armed every time is the thing it
     * exists to remove. It costs nothing when no car is in range, because it is
     * driven by a broadcast rather than a poll.
     */
    const val KEY_AUTO_CONNECT: String = "auto_connect"

    /**
     * The Bluetooth address of the car, once a session has reached its
     * handshake.
     *
     * Remembered rather than asked for, and remembered from the *handshake*
     * rather than from the driver's pick, so the address recorded is one that
     * actually spoke Android Auto. `CarPresenceReceiver` compares against it:
     * without it, every pair of headphones that connects would bring an AAP
     * session up.
     */
    const val KEY_CAR_ADDRESS: String = "car_bluetooth_address"

    /**
     * Whether an app pane crops to fill rather than fitting inside.
     *
     * Off by default: cropping hides content, and hiding a row of a list is a
     * worse surprise than a bar down each side. On for a map or a video, where
     * the middle is the point and the edges are chrome.
     */
    const val KEY_APP_PANE_FILL: String = "app_pane_fill"

    fun of(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun parkedOnlyVideo(context: Context): Boolean =
        of(context).getBoolean(KEY_PARKED_ONLY_VIDEO, false)

    fun dashboardOnCarScreen(context: Context): Boolean =
        of(context).getBoolean(KEY_CAR_SURFACE_DASHBOARD, true)

    fun autoConnect(context: Context): Boolean =
        of(context).getBoolean(KEY_AUTO_CONNECT, true)

    /** Set once the one-time move off the simulated display has been applied. */
    const val KEY_APP_SOURCE_MIGRATED: String = "app_source_migrated"

    /**
     * Moves an existing install off the simulated display, once.
     *
     * The simulated display was the only way an app could reach the car with a
     * car-shaped picture, so it was worth its two costs: a Developer options
     * toggle, and a phone screen that has to stay on and be turned on and off
     * around every drive. It is not the only way any more — an app pane renders
     * the phone's own screen just as well, and crops to fill — and those costs
     * are paid every drive by a driver who may not remember choosing them.
     *
     * So: one move, recorded so it never happens twice, and the setting is still
     * there for anyone who wants it back. Silently changing a choice the driver
     * made is only defensible because the choice now has a cheaper answer and
     * the log says exactly what changed and how to undo it.
     */
    fun migrateAppSource(context: Context, onNote: (String) -> Unit = {}) {
        val prefs = runCatching { of(context) }.getOrNull() ?: return
        if (prefs.getBoolean(KEY_APP_SOURCE_MIGRATED, false)) return
        val wasOn = prefs.getBoolean(KEY_NATIVE_APP_DISPLAY, false)
        runCatching {
            prefs.edit()
                .putBoolean(KEY_APP_SOURCE_MIGRATED, true)
                .apply { if (wasOn) putBoolean(KEY_NATIVE_APP_DISPLAY, false) }
                .apply()
        }
        if (wasOn) {
            onNote(
                "apps now run on the phone's own screen instead of a simulated display, so " +
                    "nothing has to be turned on in Developer options before a drive. The car " +
                    "screen's settings can put it back",
            )
        }
    }

    /**
     * Packages the driver has allowed onto the car screen. See `AllowedApps`.
     *
     * A `Set<String>`, like the pinned grid, because it is a membership question
     * with no order. Empty by default, and empty means the car offers nothing.
     */
    const val KEY_ALLOWED_APPS: String = "allowed_apps"

    /** The rail's placement, or the default when nothing has been chosen. */
    fun railStyle(context: Context): RailStyle =
        RailStyle.fromJson(of(context).getString(KEY_RAIL_STYLE, null))

    fun setRailStyle(context: Context, style: RailStyle) {
        of(context).edit().putString(KEY_RAIL_STYLE, style.toJson().toString()).apply()
    }

    fun allowedApps(context: Context): Set<String> =
        runCatching { of(context).getStringSet(KEY_ALLOWED_APPS, null) }.getOrNull()
            ?: AllowedApps.NONE

    fun setAllowedApps(context: Context, allowed: Set<String>) {
        runCatching {
            of(context).edit().putStringSet(KEY_ALLOWED_APPS, allowed.toSet()).apply()
        }
    }

    /** Whether [packageName] may be put on the car screen at all. */
    fun allowsApp(context: Context, packageName: String?): Boolean =
        AllowedApps.allows(allowedApps(context), packageName)

    fun appPaneFill(context: Context): Boolean =
        of(context).getBoolean(KEY_APP_PANE_FILL, false)

    fun carAddress(context: Context): String? =
        runCatching { of(context).getString(KEY_CAR_ADDRESS, null) }.getOrNull()
            ?.takeIf { it.isNotBlank() }

    /** Called once a session has reached the car's Bluetooth handshake. */
    fun rememberCar(context: Context, address: String) {
        if (address.isBlank()) return
        if (carAddress(context) == address) return
        runCatching { of(context).edit().putString(KEY_CAR_ADDRESS, address).apply() }
    }
}

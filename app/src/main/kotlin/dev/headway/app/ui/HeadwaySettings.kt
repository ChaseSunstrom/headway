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
     * Bring a session up on its own when the car's Bluetooth appears.
     *
     * On by default. The whole point of the feature is that the driver gets in
     * and drives; a switch that has to be armed every time is the thing it
     * exists to remove. It costs nothing when no car is in range, because it is
     * driven by a broadcast rather than a poll.
     */
    const val KEY_AUTO_CONNECT: String = "auto_connect"

    /**
     * Hold the screen-capture grant across drives instead of asking again.
     *
     * On by default, and only meaningful once the driver has granted it once.
     * Android 14 requires consent per projection *session*, not per connection,
     * so a grant that is never stopped survives the car going away and coming
     * back — which is the difference between one tap a day and one tap a drive.
     */
    const val KEY_HOLD_PROJECTION: String = "hold_projection"

    fun of(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun parkedOnlyVideo(context: Context): Boolean =
        of(context).getBoolean(KEY_PARKED_ONLY_VIDEO, false)

    fun dashboardOnCarScreen(context: Context): Boolean =
        of(context).getBoolean(KEY_CAR_SURFACE_DASHBOARD, true)

    fun autoConnect(context: Context): Boolean =
        of(context).getBoolean(KEY_AUTO_CONNECT, true)

    fun holdProjection(context: Context): Boolean =
        of(context).getBoolean(KEY_HOLD_PROJECTION, true)
}

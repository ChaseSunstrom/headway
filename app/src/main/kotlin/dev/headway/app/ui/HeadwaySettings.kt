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
import dev.headway.app.carapp.CarAppSurfaceView
import dev.headway.dash.AllowedApps
import dev.headway.dash.CarUiScale
import dev.headway.dash.CarUnits
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
     * The music app the Music and podcasts panel opens straight into.
     *
     * Unset means the panel opens on its list of apps, which is the right
     * default for somebody who has several and the wrong one for somebody who
     * always uses the same player. The same reasoning as [KEY_MAP_APP], which
     * this deliberately mirrors -- a driver who asked for one asked for the
     * other in the same breath.
     *
     * A package name rather than a browse-service component, because the
     * component can change with an update while the package does not, and
     * `MediaApps.installed` resolves the current one anyway.
     */
    const val KEY_MEDIA_APP: String = "media_app"

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
     * **On by default**, and safe to be because the cover gates itself:
     * `HeadwayService.coverPhoneScreen` raises it only once `AppPaneHost` has
     * *measured* the capture as a single app, never for a whole-display one —
     * where the black would be the entire picture the car receives.
     *
     * With single-app sharing it is the closest thing to a phone that is off.
     * The screen has to stay lit for the shared app to keep drawing, so the
     * cover makes it black to look at without touching what is captured: app
     * screen sharing excludes system UI, and an accessibility overlay is not
     * part of the shared app's window either. It also hides the preview window
     * that Developer options puts on the phone for a simulated display, which
     * Android offers no setting for and which cannot be closed, because it *is*
     * that display's output surface.
     *
     * Two things it needs, and one it no longer does. It needs the accessibility
     * service, because only that can add a `TYPE_ACCESSIBILITY_OVERLAY`, and it
     * needs the screen to stay awake, which the same window does with
     * `FLAG_KEEP_SCREEN_ON`. It is *not* dismissed by tapping it any more: a
     * touchable full-screen overlay sits above everything and swallowed every
     * gesture injected from the car. The way back is the notification action or
     * the car screen's settings sheet.
     *
     * This KDoc previously said "off by default" in two places and "on by
     * default" in a third, while both readers passed `true`.
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

    /**
     * Per-app panel scale, as `CarUiScale` JSON. See `CarUiScale`.
     *
     * One map rather than a key per package, so a driver who has sized four
     * apps has one preference rather than four, and clearing it is one delete.
     */
    const val KEY_APP_UI_SCALE: String = "app_ui_scale"

    /**
     * How large every panel's own content draws, as a `CarUiScale` factor.
     *
     * One number for the whole car screen, distinct from [KEY_APP_UI_SCALE]
     * which is per hosted app. A driver asked for both: the per-app one because
     * a map and a messenger disagree about what 100% means, and this one because
     * "all UI in ALL panels" should be movable without visiting each app.
     */
    const val KEY_PANEL_SCALE: String = "panel_scale"

    fun panelScale(context: Context): Float =
        runCatching { of(context).getFloat(KEY_PANEL_SCALE, CarUiScale.DEFAULT) }
            .getOrDefault(CarUiScale.DEFAULT)
            .coerceIn(CarUiScale.MIN, CarUiScale.MAX)

    fun setPanelScale(context: Context, scale: Float) {
        runCatching { of(context).edit().putFloat(KEY_PANEL_SCALE, scale).apply() }
    }

    /**
     * The tab the car screen opens on when a session comes up.
     *
     * ## Why this is not simply "where you left off"
     *
     * It used to be, and it was wrong for the thing a car is for. The active
     * tab is persisted, so ending one drive on the music tab meant the next
     * drive started there — and a driver who wants the map has to find it
     * first, every time, from the seat, usually while reversing off a drive.
     * A driver reported exactly that: "the map view doesnt pull up
     * automatically, it only goes to the home view".
     *
     * ## What the default means
     *
     * Unset is [START_LAYOUT_DRIVING] — the first tab that can show a map,
     * which in the shipped set is *Drive* and in an edited one is whatever the
     * driver built with a maps or car-app pane in it. Named, so a tab renamed
     * or re-split still resolves; falls back to the remembered tab when no
     * layout has a map in it at all, because forcing a mapless tab on somebody
     * who deleted every map pane would be worse than remembering.
     *
     * [START_LAYOUT_LAST] restores the old behaviour, and any other value is a
     * layout name.
     */
    const val KEY_START_LAYOUT: String = "start_layout"

    /** [KEY_START_LAYOUT]: the first tab that can show a map. The default. */
    const val START_LAYOUT_DRIVING: String = "driving"

    /** [KEY_START_LAYOUT]: whichever tab the last drive ended on. */
    const val START_LAYOUT_LAST: String = "last"

    /**
     * The prefix a stored layout *name* carries.
     *
     * A tab can be called anything, including "driving", so the two modes and a
     * name cannot share one flat value space without one of them shadowing the
     * other. Prefixing the name is the whole of the fix, and it keeps the value
     * printable -- these end up in a SharedPreferences XML file, where a
     * reserved control character would be a nonconforming document rather than
     * a clever sentinel.
     */
    const val START_LAYOUT_NAMED: String = "name:"

    fun startLayout(context: Context): String =
        runCatching { of(context).getString(KEY_START_LAYOUT, null) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: START_LAYOUT_DRIVING

    /** The layout [value] names, or null when it names a mode rather than a tab. */
    fun startLayoutName(value: String): String? =
        value.removePrefix(START_LAYOUT_NAMED).takeIf { it != value && it.isNotBlank() }

    fun setStartLayout(context: Context, value: String) {
        runCatching { of(context).edit().putString(KEY_START_LAYOUT, value).apply() }
    }

    /** Every app's chosen panel scale. */
    fun appUiScales(context: Context): Map<String, Float> =
        CarUiScale.readAll(
            runCatching { of(context).getString(KEY_APP_UI_SCALE, null) }.getOrNull(),
        )

    /** The panel scale for [packageName], or 1.0. */
    fun appUiScale(context: Context, packageName: String?): Float =
        CarUiScale.forApp(appUiScales(context), packageName)

    fun setAppUiScale(context: Context, packageName: String, scale: Float) {
        if (packageName.isBlank()) return
        runCatching {
            val next = appUiScales(context).toMutableMap()
            if (scale == CarUiScale.DEFAULT) next.remove(packageName) else next[packageName] = scale
            of(context).edit().putString(KEY_APP_UI_SCALE, CarUiScale.writeAll(next)).apply()
        }
    }

    /**
     * Per-app map supersampling, as `CarUiScale` JSON keyed the same way.
     *
     * A second number because it corrects a different fault. [KEY_APP_UI_SCALE]
     * moves the density, which moves everything drawn *from* the density —
     * Headway's chrome, an app's road labels, its text. Anything an app sizes
     * in fixed pixels ignores it completely, and a driver found one: HERE
     * WeGo's location triangle stayed exactly as large while everything around
     * it shrank. `CarAppSurfaceView` has the derivation; the short version is
     * that enlarging the *buffer* is the only lever an app cannot ignore,
     * because the buffer is the memory it draws into.
     *
     * Stored in the same `CarUiScale` map format, and read with the same
     * helpers, because it is the same shape of thing: a package keyed to a
     * factor, defaults omitted.
     */
    const val KEY_APP_MAP_DETAIL: String = "app_map_detail"

    /** Every app's chosen map supersample factor. */
    fun appMapDetails(context: Context): Map<String, Float> =
        CarUiScale.readAll(
            runCatching { of(context).getString(KEY_APP_MAP_DETAIL, null) }.getOrNull(),
        )

    /** The map supersample factor for [packageName], or 1.0. */
    fun appMapDetail(context: Context, packageName: String?): Float =
        packageName?.let { appMapDetails(context)[it] }
            ?.coerceIn(CarAppSurfaceView.MIN_PIXEL_SCALE, CarAppSurfaceView.MAX_PIXEL_SCALE)
            ?: CarUiScale.DEFAULT

    fun setAppMapDetail(context: Context, packageName: String, scale: Float) {
        if (packageName.isBlank()) return
        runCatching {
            val next = appMapDetails(context).toMutableMap()
            if (scale == CarUiScale.DEFAULT) next.remove(packageName) else next[packageName] = scale
            of(context).edit().putString(KEY_APP_MAP_DETAIL, CarUiScale.writeAll(next)).apply()
        }
    }

    /** The chosen music app's package, or null to open on the app list. */
    fun mediaApp(context: Context): String? =
        runCatching { of(context).getString(KEY_MEDIA_APP, null) }.getOrNull()
            ?.takeIf { it.isNotBlank() }

    fun setMediaApp(context: Context, packageName: String?) {
        runCatching {
            of(context).edit().apply {
                if (packageName.isNullOrBlank()) remove(KEY_MEDIA_APP)
                else putString(KEY_MEDIA_APP, packageName)
            }.apply()
        }
    }

    fun appPaneFill(context: Context): Boolean =
        of(context).getBoolean(KEY_APP_PANE_FILL, false)

    /**
     * Whether the phone is turned sideways for the drive, so apps lay out wide.
     *
     * Off by default and gated behind a permission the driver grants in
     * Settings, because it changes the phone itself rather than anything
     * Headway draws — `PhoneRotation` has the derivation, the costs, and the
     * reason it is the only unprivileged way to make a mirrored app render in
     * landscape at all.
     */
    const val KEY_LANDSCAPE_APPS: String = "landscape_apps"

    fun landscapeApps(context: Context): Boolean =
        of(context).getBoolean(KEY_LANDSCAPE_APPS, false)

    fun setLandscapeApps(context: Context, on: Boolean) {
        runCatching { of(context).edit().putBoolean(KEY_LANDSCAPE_APPS, on).apply() }
    }

    /**
     * Which units the car pane shows, as a `CarUnits` name.
     *
     * Unset means [CarUnits.AUTOMATIC] — follow the phone's region. Stored
     * rather than always derived because the region is a guess about a person,
     * and a driver reading a number at speed should not have to work out which
     * unit it is in.
     */
    const val KEY_CAR_UNITS: String = "car_units"

    fun carUnits(context: Context): CarUnits =
        CarUnits.of(runCatching { of(context).getString(KEY_CAR_UNITS, null) }.getOrNull())

    fun setCarUnits(context: Context, units: CarUnits) {
        runCatching { of(context).edit().putString(KEY_CAR_UNITS, units.name).apply() }
    }

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

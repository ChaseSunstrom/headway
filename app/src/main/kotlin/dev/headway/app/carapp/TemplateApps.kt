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

package dev.headway.app.carapp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.car.app.CarAppService
import dev.headway.app.ui.HeadwaySettings

/**
 * An app that offers a car interface, and what kind of one it says it is.
 *
 * @param service the `CarAppService` to bind. The component and not just the
 *   package: an app may declare more than one, and the intent has to name which.
 * @param categories the `androidx.car.app.category.*` values from the service's
 *   intent filter. These are the app's own claim about what it is for —
 *   NAVIGATION, POI, MESSAGING and so on — and they are the only structured
 *   description of a car app that exists before it is bound.
 */
data class TemplateApp(
    val packageName: String,
    val service: ComponentName,
    val label: String,
    val categories: Set<String>,
) {
    fun icon(context: Context): Drawable? = runCatching {
        context.packageManager.getApplicationIcon(packageName)
    }.getOrNull()

    /**
     * Whether the driver has allowed this app onto the car screen.
     *
     * Carried on the app rather than applied as a filter, so a picker can show
     * every car app and mark the ones that still need a grant. The gate is the
     * same one `AllowedApps` enforces everywhere else; only its *visibility*
     * changed.
     */
    fun allowed(context: Context): Boolean =
        packageName in HeadwaySettings.allowedApps(context)

    /** "Navigation", "Points of interest", … for a picker row. */
    fun describeCategories(): String = categories
        .mapNotNull { CATEGORY_NAMES[it] }
        .sorted()
        .joinToString(" · ")
        .ifBlank { "Car app" }

    private companion object {
        // PARKING and CHARGING are deprecated in the library and still declared
        // by installed apps, which is the only thing that matters when the job
        // is reading somebody else's manifest.
        @Suppress("DEPRECATION")
        val CATEGORY_NAMES: Map<String, String> = mapOf(
            CarAppService.CATEGORY_NAVIGATION_APP to "Navigation",
            CarAppService.CATEGORY_PARKING_APP to "Parking",
            CarAppService.CATEGORY_CHARGING_APP to "Charging",
            CarAppService.CATEGORY_POI_APP to "Points of interest",
            CarAppService.CATEGORY_IOT_APP to "Home control",
            CarAppService.CATEGORY_SETTINGS_APP to "Settings",
            CarAppService.CATEGORY_MESSAGING_APP to "Messaging",
            CarAppService.CATEGORY_CALLING_APP to "Calling",
            CarAppService.CATEGORY_WEATHER_APP to "Weather",
        )
    }
}

/**
 * Finding the apps that have a car interface to give.
 *
 * ## What this is discovering
 *
 * Not "apps", and not "apps Headway can mirror". Apps that implement the *other*
 * half of the Android for Cars App Library — a `CarAppService` that hands a host
 * a tree of templates and lets the host draw them. That is the same contract
 * Android Auto uses, and it is the one route by which a third-party app's
 * interface reaches the car screen as car UI rather than as a scaled photograph
 * of a phone.
 *
 * ## Why an intent query and not a list
 *
 * `androidx.car.app.CarAppService` is the library's own `SERVICE_INTERFACE`
 * constant and every car app declares it, because without it no host can find
 * them either. Querying it is exact, complete, and needs no allowlist to keep up
 * to date — the same argument `MapsTile` makes for `geo:`.
 *
 * ## The one thing discovery cannot tell you
 *
 * Whether the app will actually *answer*. Every car app validates its caller in
 * its own process before replying, and an app can refuse. [CarAppSession] is
 * where that is found out; this list is "apps worth trying", and the pane says
 * so when one turns out not to be.
 */
object TemplateApps {

    /**
     * The car apps that say they are navigators, best first.
     *
     * This is what makes a Maps pane an actual map. A navigation car app is
     * handed a `Surface` through `SurfaceContainer` and draws its own map into
     * it -- see `CarAppSession` -- so the pane holds the app's rendering rather
     * than a picture of it, at the car's size, with no capture involved and
     * nothing on the phone's screen. That is how Android Auto does maps too.
     *
     * Order is the order [installed] returns, which is by label, so a phone with
     * one navigator gets that one and a phone with several gets a stable choice
     * the driver can override in the pane's own picker.
     *
     * Empty is the ordinary case on a phone whose map app has no car interface,
     * and `MapsTile` -- the turn card and a way into the app -- is what that
     * phone gets instead.
     */
    fun navigators(context: Context): List<TemplateApp> =
        installed(context).filter { CarAppService.CATEGORY_NAVIGATION_APP in it.categories }

    /**
     * Navigators the driver has already allowed, best first.
     *
     * The Maps pane opens one of these *without being asked*, so unlike the
     * picker it must not offer an app the driver has never approved. The picker
     * shows everything and asks; this list is what may run unprompted.
     */
    fun allowedNavigators(context: Context): List<TemplateApp> =
        navigators(context).filter { it.allowed(context) }

    /**
     * Every car app on the phone, by label.
     *
     * `MATCH_ALL` is deliberately not passed: a disabled component should not be
     * offered, and the default match already excludes it.
     */
    /**
     * Every car app on the phone, allowed or not.
     *
     * ## Why the allow-list is not applied here
     *
     * It was, and it made the feature look broken. Filtering inside *discovery*
     * meant an app the driver had not ticked was not "blocked" but **invisible**:
     * the picker showed a shorter list with nothing to say why, a driver with
     * five car apps installed saw one, and there was no route from the car screen
     * to the setting that would have revealed the rest. It also silently broke
     * the two self-test diagnostics that BLOCKERS B-012 names as the way to close
     * it, since those enumerate car apps through this function.
     *
     * A filter you cannot see is not a permission gate, it is a bug. So discovery
     * reports what exists, and [TemplateApp.allowed] carries the decision — the
     * picker shows every app and asks for a grant on the one the driver taps.
     */
    fun installed(context: Context): List<TemplateApp> {
        val packages = context.packageManager
        val query = Intent(CarAppService.SERVICE_INTERFACE)
        val resolved = runCatching {
            packages.queryIntentServices(
                query,
                PackageManager.ResolveInfoFlags.of(PackageManager.GET_RESOLVED_FILTER.toLong()),
            )
        }.getOrNull().orEmpty()

        return resolved
            .asSequence()
            .mapNotNull { info ->
                val service = info.serviceInfo ?: return@mapNotNull null
                if (service.packageName == context.packageName) return@mapNotNull null
                TemplateApp(
                    packageName = service.packageName,
                    service = ComponentName(service.packageName, service.name),
                    label = runCatching { info.loadLabel(packages).toString() }
                        .getOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?: service.packageName,
                    // The filter is only populated when GET_RESOLVED_FILTER was
                    // asked for, and it is null on some OEM builds regardless;
                    // an app with no categories is still perfectly bindable.
                    categories = info.filter?.categoriesIterator()
                        ?.asSequence()
                        ?.toSet()
                        .orEmpty(),
                )
            }
            .distinctBy { it.service }
            .sortedBy { it.label.lowercase() }
            .toList()
    }
}

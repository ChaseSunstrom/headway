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

package dev.headway.app.nav

import android.app.Notification
import android.service.notification.StatusBarNotification
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One step of a route, as much of it as the navigating app will say.
 *
 * @param instruction the road or manoeuvre in words — "Turn right onto High St".
 * @param distance how far to it, as the app phrased it. A string and not a
 *   number on purpose: the only source that carries it is a display string
 *   already localised and already unit-converted, and re-parsing "0.4 mi" into
 *   metres to re-render it as "0.4 mi" would add two chances to be wrong.
 * @param eta the arrival summary, when there is one.
 * @param source the package the instruction came from, for the label.
 * @param structured true when this came from a car app's `Trip` rather than
 *   from a notification. It is not cosmetic: a structured step carries a real
 *   maneuver code and a real distance, and it must not be overwritten by a
 *   scrape of the same app's notification a moment later.
 */
data class NavigationStep(
    val instruction: String,
    val distance: String?,
    val eta: String?,
    val source: String,
    val structured: Boolean = false,
)

/**
 * Turn-by-turn, from whichever app is navigating.
 *
 * ## Why this is a notification reader
 *
 * Because for every map app except one, it is the only thing there is. Google
 * Maps, HERE WeGo and Organic Maps publish no API for a third party to observe
 * a route; what they publish is an ongoing notification, and its extras are
 * standard:
 *
 * - `EXTRA_TITLE` — the distance to the next manoeuvre
 * - `EXTRA_TEXT` — the manoeuvre itself
 * - `EXTRA_SUB_TEXT` — a separated summary, typically duration, remaining
 *   distance and arrival time
 *
 * That is a real, structured-enough model and it needs no permission Headway
 * does not already hold: the notification listener is already running for
 * messages.
 *
 * ## What this deliberately does not do
 *
 * It does not recover the manoeuvre *icon*. The turn arrow is not in the extras
 * at all — the only known way to get it is to rasterise the notification's large
 * icon to a small bitmap and Hamming-match it against a hand-transcribed table
 * of every arrow the app ships, which is roughly a thousand lines of magic
 * numbers that silently degrade whenever the app reskins. Gadgetbridge does
 * exactly that and documents it breaking. Headway shows the words instead, which
 * are the part that does not rot.
 *
 * For OsmAnd there is something far better and [OsmAndNavigation] uses it: a
 * published AIDL interface returning a numeric turn type and a distance in
 * metres. Where both are available the AIDL wins.
 *
 * ## Matching
 *
 * A notification qualifies when it is ongoing, comes from a package the driver
 * has nominated as their map app or from one of the known navigators, and
 * carries both a title and a text. `FLAG_ONGOING_EVENT` is what separates a
 * live route from a "your commute is 20 minutes" suggestion, and getting that
 * wrong would put a marketing card on the dashboard.
 */
object NavigationFeed {

    /** Packages known to publish a navigation notification in this shape. */
    val KNOWN_NAVIGATORS: Set<String> = setOf(
        "com.google.android.apps.maps",
        "com.waze",
        "com.here.app.maps",
        "net.osmand",
        "net.osmand.plus",
        "app.organicmaps",
        "app.organicmaps.web",
        "de.storchp.opentracks",
        "com.mapbox.navigation",
        "org.navitproject.navit",
        "com.sygic.aura",
        "com.tomtom.gplay.navapp",
    )

    fun interface Listener {
        fun onStep(step: NavigationStep?)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile
    private var current: NavigationStep? = null

    /** The step being shown, or null when nothing is navigating. */
    val step: NavigationStep? get() = current

    fun observe(listener: Listener) {
        listeners.addIfAbsent(listener)
        listener.onStep(current)
    }

    /** Removal is by identity, so callers must pass the same instance. */
    fun unobserve(listener: Listener) {
        listeners.remove(listener)
    }

    /**
     * Offers a notification to the feed.
     *
     * @return true when it was a navigation update, so the caller knows not to
     *   also treat it as a message.
     */
    fun offer(sbn: StatusBarNotification, extraPackages: Set<String>): Boolean {
        // A car app connected through the template host publishes a real Trip,
        // which beats anything that can be recovered from a notification. While
        // one is doing so, the scrape stays out of the way rather than
        // overwriting a structured maneuver with a parsed display string.
        if (current?.structured == true && current?.source != sbn.packageName) return false
        val parsed = parse(sbn, extraPackages) ?: return false
        publish(parsed)
        return true
    }

    /**
     * Offers a step that did not come from a notification.
     *
     * The entry point for `CarAppTrips`, which decodes the `Trip` a car app
     * publishes over the template host. Null clears.
     */
    fun offer(step: NavigationStep?) {
        publish(step)
    }

    /** Called when a notification goes away, so a finished route clears. */
    fun withdraw(sbn: StatusBarNotification) {
        if (current?.source != sbn.packageName) return
        publish(null)
    }

    /** Clears everything; for a listener disconnect. */
    fun clear() {
        if (current == null) return
        publish(null)
    }

    private fun publish(step: NavigationStep?) {
        current = step
        listeners.forEach { runCatching { it.onStep(step) } }
    }

    /**
     * Reads a step out of a notification, or null when it is not one.
     *
     * Exposed rather than private so it can be tested without a live listener:
     * this is string handling over other people's formatting, which is exactly
     * the kind of code that needs fixtures.
     */
    fun parse(sbn: StatusBarNotification, extraPackages: Set<String> = emptySet()): NavigationStep? {
        val notification = sbn.notification ?: return null
        if ((notification.flags and Notification.FLAG_ONGOING_EVENT) == 0) return null
        if (sbn.packageName !in KNOWN_NAVIGATORS && sbn.packageName !in extraPackages) return null

        val extras = notification.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
        val sub = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
            // Google Maps separates its summary with a non-breaking space
            // either side of the bullet, which renders as a wide gap and
            // breaks any split on a plain space.
            ?.replace(' ', ' ')
            ?.trim()

        // Both halves are required. A navigation notification that has lost one
        // of them is mid-update, and half a step on a dashboard is worse than
        // none: "Turn right" with no distance invites the wrong turn.
        if (title.isNullOrEmpty() || text.isNullOrEmpty()) return null

        return NavigationStep(
            instruction = text,
            distance = title,
            eta = sub?.takeIf { it.isNotEmpty() },
            source = sbn.packageName,
        )
    }
}

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

package dev.headway.dash

/**
 * Which apps the driver has allowed onto the car screen.
 *
 * ## Why an allow list rather than "every app you have"
 *
 * Because putting an app on the car screen is not like opening it on the phone.
 * It is shared over a screen-capture grant, drawn on a display in a vehicle that
 * other people can see, and touched by a coordinate stream arriving from
 * hardware Headway does not control. "Everything installed, one tap away" makes
 * that decision silently, once, for a banking app and a map alike.
 *
 * So nothing *runs* on the car until it is allowed — one deliberate choice per
 * app. An empty list is the correct starting state.
 *
 * ## Where the choice is made
 *
 * On the phone, parked, with a keyboard and a full screen to read on; and from
 * the seat, where the car-app pane lists what is installed and asks for the
 * grant on the one the driver taps. Both write the same set through the same
 * functions here.
 *
 * What this list does **not** do is hide apps from a picker. Filtering inside
 * discovery made an unticked app invisible rather than blocked, with nothing on
 * the car screen to say why or how to change it — a filter nobody can see is a
 * bug, not a gate. Pickers show everything; [allows] decides what may open.
 *
 * ## Why the list is stored and not derived
 *
 * A derived rule — "apps with a launcher icon", "apps that declare car support" —
 * changes under the driver when they install something. This changes only when
 * they change it.
 */
object AllowedApps {

    /** The empty list, which is what a fresh install has. */
    val NONE: Set<String> = emptySet()

    /**
     * Whether [packageName] may be shown on the car screen.
     *
     * Blank package names are never allowed: they arrive from stored layouts
     * whose argument was lost, and "allow the app with no name" is not a
     * question worth having an answer to.
     */
    fun allows(allowed: Set<String>, packageName: String?): Boolean =
        !packageName.isNullOrBlank() && packageName in allowed

    fun allow(allowed: Set<String>, packageName: String): Set<String> =
        if (packageName.isBlank()) allowed else allowed + packageName

    fun deny(allowed: Set<String>, packageName: String): Set<String> = allowed - packageName

    /** Flips one entry, which is what a row in a list of switches does. */
    fun toggle(allowed: Set<String>, packageName: String): Set<String> =
        if (allows(allowed, packageName)) deny(allowed, packageName) else allow(allowed, packageName)

    /**
     * The subset of [candidates] the driver has allowed, in the order given.
     *
     * Order is preserved because the caller sorted it for a human — by label,
     * usually — and re-sorting a filtered list by package name would put
     * "com.a" above "Zebra".
     */
    fun filter(allowed: Set<String>, candidates: List<String>): List<String> =
        candidates.filter { it in allowed }

    /**
     * Drops entries for apps that are no longer installed.
     *
     * An allow list is a set of *decisions*, and a decision about an app that
     * has gone is not one worth keeping — it would silently re-apply if the
     * driver ever reinstalled it, which is not what "allow" meant when they
     * tapped it.
     */
    fun prune(allowed: Set<String>, installed: Set<String>): Set<String> =
        allowed.filterTo(mutableSetOf()) { it in installed }
}

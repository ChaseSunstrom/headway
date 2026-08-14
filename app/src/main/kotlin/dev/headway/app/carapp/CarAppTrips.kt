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

import androidx.car.app.model.CarText
import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.Trip
import dev.headway.app.nav.NavigationFeed
import dev.headway.app.nav.NavigationStep
import java.util.concurrent.TimeUnit

/**
 * Turn-by-turn from a car app, which is the good version of the same thing.
 *
 * ## Why this exists next to `NavigationFeed`
 *
 * [NavigationFeed] scrapes a navigation notification, because for most map apps
 * that is all there is. A car app is different: it publishes a `Trip` through
 * `INavigationHost.updateTrip`, and a `Trip` is a real model — a numeric
 * maneuver type, a distance with a *unit*, a remaining time in seconds, an
 * arrival timestamp with a zone. Nothing is parsed out of a display string and
 * nothing is guessed from a bitmap.
 *
 * So when a car app is connected, this is the source, and the notification
 * scrape is the fallback for everything else. Both end up in the same
 * [NavigationFeed] so the Maps pane and the AAP navigation-status service have
 * one place to read from and do not have to know which they got.
 *
 * ## What is deliberately dropped
 *
 * The lane guidance and the app's own maneuver icon. Lanes need a renderer of
 * their own and the icon is a `CarIcon` that has to be loaded, tinted and
 * scaled; neither is worth holding up the instruction itself, which is the part
 * that keeps a driver in the right lane in practice. The numeric maneuver type
 * *is* kept — [describeManeuver] turns it into words, and it is what the AAP
 * navigation-status service will send when that channel is wired.
 */
object CarAppTrips {

    /**
     * Takes a decoded `updateTrip` payload.
     *
     * `Any?` rather than `Trip` because it arrives as a `Bundleable` whose
     * contents are whatever the app put there; a host that assumed the type
     * would throw on a malformed one, inside a binder call, and take the app's
     * process down with it rather than its own.
     */
    fun offer(packageName: String, payload: Any?) {
        val trip = payload as? Trip ?: return
        if (trip.isLoading) return

        val step = trip.steps.firstOrNull()
        val estimate = trip.stepTravelEstimates.firstOrNull()
        val destination = trip.destinationTravelEstimates.firstOrNull()

        val cue = step?.cue?.plain()
        val road = step?.road?.plain() ?: trip.currentRoad?.plain()
        val maneuver = step?.maneuver?.type?.let { describeManeuver(it) }

        // The instruction, in the order a driver reads it: what to do, then
        // where. "Turn right onto High Street" beats either half alone, and an
        // app that supplies only the cue still gets a usable line.
        val instruction = listOfNotNull(
            cue ?: maneuver,
            road?.takeIf { it != cue },
        ).joinToString(" · ").ifBlank { return }

        NavigationFeed.offer(
            NavigationStep(
                instruction = instruction,
                distance = estimate?.remainingDistance?.let { describeDistance(it) },
                eta = destination?.let { describeEta(it.remainingTimeSeconds) },
                source = packageName,
                structured = true,
            ),
        )
    }

    /** Called when the app stops navigating, so the pane clears. */
    fun withdraw(packageName: String) {
        if (NavigationFeed.step?.source != packageName) return
        NavigationFeed.offer(null)
    }

    /**
     * A distance with its unit, in the unit the app chose.
     *
     * Not converted. The app already decided between miles and kilometres based
     * on the user's own locale and its own settings, and a host that re-derived
     * that would get it wrong for exactly the drivers who set it deliberately.
     *
     * `UNIT_*_P1` means "one decimal place", which is the only formatting
     * instruction the model carries and the only one applied here.
     */
    fun describeDistance(distance: Distance): String {
        val value = distance.displayDistance
        val precise = distance.displayUnit == Distance.UNIT_KILOMETERS_P1 ||
            distance.displayUnit == Distance.UNIT_MILES_P1
        val number = if (precise) String.format("%.1f", value) else value.toInt().toString()
        return "$number ${unitName(distance.displayUnit)}"
    }

    private fun unitName(unit: Int): String = when (unit) {
        Distance.UNIT_METERS -> "m"
        Distance.UNIT_KILOMETERS, Distance.UNIT_KILOMETERS_P1 -> "km"
        Distance.UNIT_MILES, Distance.UNIT_MILES_P1 -> "mi"
        Distance.UNIT_FEET -> "ft"
        Distance.UNIT_YARDS -> "yd"
        else -> ""
    }

    /** "18 min" or "1 h 04 min", from the remaining seconds. */
    fun describeEta(remainingSeconds: Long): String? {
        if (remainingSeconds < 0) return null
        val hours = TimeUnit.SECONDS.toHours(remainingSeconds)
        val minutes = TimeUnit.SECONDS.toMinutes(remainingSeconds) % 60
        return if (hours > 0) "$hours h ${"%02d".format(minutes)} min" else "$minutes min"
    }

    /**
     * The maneuver, in words.
     *
     * Words rather than an arrow for the reason [NavigationFeed] gives at
     * length: a glyph that is missing from the head unit's font draws as a box,
     * and a hand-drawn arrow set is fifty vector assets to maintain for
     * information the cue string usually already carries. Where the app sends a
     * cue this is only the fallback; where it does not, this is the instruction.
     *
     * Every constant is `androidx.car.app.navigation.model.Maneuver`'s own.
     */
    fun describeManeuver(type: Int): String = when (type) {
        Maneuver.TYPE_DEPART -> "Start"
        Maneuver.TYPE_NAME_CHANGE -> "Continue"
        Maneuver.TYPE_KEEP_LEFT -> "Keep left"
        Maneuver.TYPE_KEEP_RIGHT -> "Keep right"
        Maneuver.TYPE_TURN_SLIGHT_LEFT -> "Slight left"
        Maneuver.TYPE_TURN_SLIGHT_RIGHT -> "Slight right"
        Maneuver.TYPE_TURN_NORMAL_LEFT -> "Turn left"
        Maneuver.TYPE_TURN_NORMAL_RIGHT -> "Turn right"
        Maneuver.TYPE_TURN_SHARP_LEFT -> "Sharp left"
        Maneuver.TYPE_TURN_SHARP_RIGHT -> "Sharp right"
        Maneuver.TYPE_U_TURN_LEFT, Maneuver.TYPE_U_TURN_RIGHT -> "Make a U-turn"
        Maneuver.TYPE_ON_RAMP_SLIGHT_LEFT,
        Maneuver.TYPE_ON_RAMP_NORMAL_LEFT,
        Maneuver.TYPE_ON_RAMP_SHARP_LEFT,
        Maneuver.TYPE_ON_RAMP_U_TURN_LEFT,
        -> "Take the ramp left"
        Maneuver.TYPE_ON_RAMP_SLIGHT_RIGHT,
        Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT,
        Maneuver.TYPE_ON_RAMP_SHARP_RIGHT,
        Maneuver.TYPE_ON_RAMP_U_TURN_RIGHT,
        -> "Take the ramp right"
        Maneuver.TYPE_OFF_RAMP_SLIGHT_LEFT, Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT -> "Exit left"
        Maneuver.TYPE_OFF_RAMP_SLIGHT_RIGHT, Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT -> "Exit right"
        Maneuver.TYPE_FORK_LEFT -> "Fork left"
        Maneuver.TYPE_FORK_RIGHT -> "Fork right"
        Maneuver.TYPE_MERGE_LEFT -> "Merge left"
        Maneuver.TYPE_MERGE_RIGHT -> "Merge right"
        Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED -> "Merge"
        Maneuver.TYPE_ROUNDABOUT_ENTER_CW,
        Maneuver.TYPE_ROUNDABOUT_ENTER_CCW,
        Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW,
        Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE,
        Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW,
        Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE,
        -> "At the roundabout"
        Maneuver.TYPE_ROUNDABOUT_EXIT_CW, Maneuver.TYPE_ROUNDABOUT_EXIT_CCW ->
            "Leave the roundabout"
        Maneuver.TYPE_STRAIGHT -> "Straight on"
        Maneuver.TYPE_FERRY_BOAT, Maneuver.TYPE_FERRY_BOAT_LEFT, Maneuver.TYPE_FERRY_BOAT_RIGHT ->
            "Take the ferry"
        Maneuver.TYPE_FERRY_TRAIN,
        Maneuver.TYPE_FERRY_TRAIN_LEFT,
        Maneuver.TYPE_FERRY_TRAIN_RIGHT,
        -> "Take the car train"
        Maneuver.TYPE_DESTINATION -> "Arrive"
        Maneuver.TYPE_DESTINATION_STRAIGHT -> "Arrive ahead"
        Maneuver.TYPE_DESTINATION_LEFT -> "Arrive on the left"
        Maneuver.TYPE_DESTINATION_RIGHT -> "Arrive on the right"
        else -> "Continue"
    }
}

/**
 * A `CarText` as plain text, or null when there is none worth showing.
 *
 * `toCharSequence` and not `toString`: the latter is a debug rendering that
 * includes the variant list. The spans are dropped on purpose — they are
 * `DistanceSpan` and `DurationSpan`, whose values Headway already has in
 * structured form and renders itself.
 */
internal fun CarText?.plain(): String? =
    this?.takeIf { !it.isEmpty }?.toCharSequence()?.toString()?.trim()?.takeIf { it.isNotEmpty() }

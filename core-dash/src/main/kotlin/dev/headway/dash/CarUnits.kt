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
 * Which units the car pane shows its readings in.
 *
 * ## Why this exists rather than "just use the locale"
 *
 * It did just use the locale, and only for *speed*. Everything else was
 * hard-coded metric, so a driver in the United States got miles per hour above
 * a panel of kilometres, degrees Celsius and kilopascals. The protocol carries
 * SI throughout — `SensorBatch` is metres per second, hundredths of a kilopascal
 * and thousandths of a degree Celsius — so the conversion has to happen at the
 * point of display, and it has to happen to all of it or none of it.
 *
 * [AUTOMATIC] keeps the old behaviour as the default, because guessing right
 * for most people beats asking everyone. The other two exist because the guess
 * is a guess: the same country can hold somebody who thinks in Celsius, and a
 * driver who has to reason about the number on a dashboard at speed should not
 * have to reason about the unit too.
 */
enum class CarUnits {

    /** Follow the phone's region. See [imperialFor]. */
    AUTOMATIC,

    /** km/h, km, °C, kPa. */
    METRIC,

    /** mph, miles, °F, psi. */
    IMPERIAL,
    ;

    fun describe(): String = when (this) {
        AUTOMATIC -> "Automatic"
        METRIC -> "Metric"
        IMPERIAL -> "Imperial"
    }

    /** A one-line summary of what this choice actually shows. */
    fun detail(): String = when (this) {
        AUTOMATIC -> "Follow the phone's region"
        METRIC -> "km/h, km, °C, kPa"
        IMPERIAL -> "mph, miles, °F, psi"
    }

    companion object {

        val DEFAULT: CarUnits = AUTOMATIC

        /** Reads a stored name, falling back to [DEFAULT] for anything unknown. */
        fun of(name: String?): CarUnits =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: DEFAULT

        /**
         * ISO 3166-1 alpha-2 codes for the places that sign roads in mph.
         *
         * The United Kingdom and the United States, plus Liberia and Myanmar and
         * the Caribbean and Pacific territories that follow the US convention.
         * Deliberately short and deliberately explicit — anything not on it gets
         * metric, which is the right answer for the overwhelming majority.
         *
         * Note the United Kingdom's presence is about *road signs*: it posts
         * speeds and distances in miles while using Celsius for weather. That
         * inconsistency is real and this does not model it, because a car pane
         * showing miles beside Celsius is exactly the mixture the driver
         * complained about. [IMPERIAL] is one choice, applied to everything.
         */
        val MPH_COUNTRIES: Set<String> = setOf(
            "US", "GB", "LR", "MM",
            "AS", "BS", "BZ", "DM", "FM", "GD", "GU", "KN", "KY", "LC", "MH", "MP",
            "MS", "PR", "PW", "SH", "VC", "VG", "VI", "WS",
        )

        /** Whether [country] — an ISO 3166-1 alpha-2 code — reads in imperial. */
        fun imperialFor(country: String?): Boolean =
            !country.isNullOrBlank() && country.uppercase() in MPH_COUNTRIES
    }
}

/**
 * The conversions, in one place, from the SI the protocol carries.
 *
 * Free functions rather than methods on [CarUnits] because the caller always
 * knows by then whether it is showing imperial, and threading the enum into
 * every format string made the call sites harder to read than the arithmetic.
 */
object CarUnitConversion {

    const val METERS_PER_SECOND_TO_KMH: Double = 3.6
    const val METERS_PER_SECOND_TO_MPH: Double = 2.236936
    const val KM_TO_MILES: Double = 0.6213712
    const val KPA_TO_PSI: Double = 0.1450377

    fun speed(metersPerSecond: Double, imperial: Boolean): Double =
        metersPerSecond * if (imperial) METERS_PER_SECOND_TO_MPH else METERS_PER_SECOND_TO_KMH

    fun speedUnit(imperial: Boolean): String = if (imperial) "mph" else "km/h"

    fun distance(km: Double, imperial: Boolean): Double =
        if (imperial) km * KM_TO_MILES else km

    fun distanceUnit(imperial: Boolean): String = if (imperial) "mi" else "km"

    fun temperature(celsius: Double, imperial: Boolean): Double =
        if (imperial) celsius * 9.0 / 5.0 + 32.0 else celsius

    fun temperatureUnit(imperial: Boolean): String = if (imperial) "°F" else "°C"

    fun pressure(kpa: Double, imperial: Boolean): Double =
        if (imperial) kpa * KPA_TO_PSI else kpa

    fun pressureUnit(imperial: Boolean): String = if (imperial) "psi" else "kPa"
}

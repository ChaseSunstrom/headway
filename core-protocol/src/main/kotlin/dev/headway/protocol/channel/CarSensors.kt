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

package dev.headway.protocol.channel

/**
 * What the car has told us about itself, in units a person reads.
 *
 * ## Where the numbers come from
 *
 * `SensorBatch` (`aap_protobuf/service/sensorsource/message/SensorBatch.proto`)
 * carries 22 repeated fields, one per sensor type. The protocol stores most
 * quantities as scaled integers so it never has to send a float: a field named
 * `_e3` is the value times 1000, `_e2` is times 100. Every conversion in this
 * file exists because of that, and getting one wrong is the kind of bug that
 * shows a driver 100 km/h when they are doing 0.1 — so each is tested.
 *
 * ## What the car may not send
 *
 * All of it. The head unit advertises a sensor service listing the types it
 * supports, and a unit is free to advertise a type and then never send a value.
 * Every field here is therefore nullable and "not reported" is a normal state
 * the dashboard must render, not an error.
 *
 * ## What the protocol does not have
 *
 * **Oil pressure, oil level and coolant temperature are not in the AAP sensor
 * set.** `SensorType.proto` defines 22 types and none of them is any of those.
 * They are readable over OBD-II, which is a different bus that Android Auto does
 * not bridge, so no amount of work on this channel produces them. Recorded here
 * because it is the first thing anyone will ask.
 */
data class CarSensors(
    /** Road speed in metres per second, or null when the car has not said. */
    val speedMetersPerSecond: Double? = null,
    /** True when cruise control is engaged. */
    val cruiseEngaged: Boolean? = null,
    /** Engine speed in revolutions per minute. */
    val rpm: Double? = null,
    /** Fuel remaining as a percentage, 0..100. */
    val fuelPercent: Int? = null,
    /** Estimated range on the fuel remaining, in metres. */
    val rangeMeters: Int? = null,
    /** True when the car is warning about fuel level. */
    val lowFuel: Boolean? = null,
    /** Tyre pressures in kilopascals, in the car's own wheel order. */
    val tyrePressuresKpa: List<Double> = emptyList(),
    /** Outside temperature in degrees Celsius. */
    val outsideTemperatureCelsius: Double? = null,
    /** Total distance travelled, in kilometres. */
    val odometerKm: Double? = null,
    /** True when the parking brake is engaged. */
    val parkingBrake: Boolean? = null,
    /** True when the car says it is dark enough for night colours. */
    val nightMode: Boolean? = null,
    /** The raw driving-status bitmask, or null. See [restricted]. */
    val drivingStatus: Int? = null,
) {

    /** True when the car has told us anything at all. */
    val any: Boolean
        get() = speedMetersPerSecond != null || rpm != null || fuelPercent != null ||
            rangeMeters != null || tyrePressuresKpa.isNotEmpty() ||
            outsideTemperatureCelsius != null || odometerKm != null ||
            parkingBrake != null || nightMode != null || drivingStatus != null ||
            cruiseEngaged != null || lowFuel != null

    /** Speed in kilometres per hour. */
    val speedKph: Double? get() = speedMetersPerSecond?.times(SECONDS_PER_HOUR / 1000.0)

    /** Speed in miles per hour. */
    val speedMph: Double? get() = speedMetersPerSecond?.times(SECONDS_PER_HOUR / METERS_PER_MILE)

    /**
     * True when the car says the driver is restricted, i.e. moving.
     *
     * `DrivingStatusData.status` is a bitmask; anything other than
     * [DRIVING_STATUS_UNRESTRICTED] means at least one restriction applies. The
     * individual bits are not enumerated in the schema, so this deliberately
     * tests for "not zero" rather than inventing names for bits nothing
     * documents.
     */
    val restricted: Boolean? get() = drivingStatus?.let { it != DRIVING_STATUS_UNRESTRICTED }

    /** Merges [update] over this, keeping values the update did not carry. */
    fun mergedWith(update: CarSensors): CarSensors = CarSensors(
        speedMetersPerSecond = update.speedMetersPerSecond ?: speedMetersPerSecond,
        cruiseEngaged = update.cruiseEngaged ?: cruiseEngaged,
        rpm = update.rpm ?: rpm,
        fuelPercent = update.fuelPercent ?: fuelPercent,
        rangeMeters = update.rangeMeters ?: rangeMeters,
        lowFuel = update.lowFuel ?: lowFuel,
        tyrePressuresKpa = update.tyrePressuresKpa.ifEmpty { tyrePressuresKpa },
        outsideTemperatureCelsius =
            update.outsideTemperatureCelsius ?: outsideTemperatureCelsius,
        odometerKm = update.odometerKm ?: odometerKm,
        parkingBrake = update.parkingBrake ?: parkingBrake,
        nightMode = update.nightMode ?: nightMode,
        drivingStatus = update.drivingStatus ?: drivingStatus,
    )

    /** One line for the session log. */
    fun describe(): String {
        if (!any) return "sensors: nothing reported"
        val parts = mutableListOf<String>()
        speedKph?.let { parts += "speed %.1f km/h".format(it) }
        rpm?.let { parts += "%.0f rpm".format(it) }
        fuelPercent?.let { parts += "fuel $it%" }
        rangeMeters?.let { parts += "range ${it / 1000} km" }
        if (tyrePressuresKpa.isNotEmpty()) {
            parts += "tyres " + tyrePressuresKpa.joinToString("/") { "%.0f".format(it) } + " kPa"
        }
        outsideTemperatureCelsius?.let { parts += "outside %.1f C".format(it) }
        parkingBrake?.let { parts += if (it) "parking brake on" else "parking brake off" }
        nightMode?.let { parts += if (it) "night" else "day" }
        return "sensors: " + parts.joinToString(", ")
    }

    companion object {
        /** Nothing known yet. */
        val UNKNOWN: CarSensors = CarSensors()

        /**
         * `DrivingStatusData.status` when no restriction applies.
         *
         * Zero, and the schema states no name for it, so the constant is here
         * rather than invented in the protobuf.
         */
        const val DRIVING_STATUS_UNRESTRICTED: Int = 0

        private const val SECONDS_PER_HOUR = 3600.0
        private const val METERS_PER_MILE = 1609.344

        /**
         * `SpeedData.speed_e3` is metres per second times 1000.
         *
         * Required in the schema, so a batch carrying speed always carries this.
         */
        fun speedFromE3(speedE3: Int): Double = speedE3 / 1000.0

        /** `RpmData.rpm` is revolutions per minute times 1000. */
        fun rpmFromE3(rpmE3: Int): Double = rpmE3 / 1000.0

        /** `TirePressureData.tire_pressures_e2` is kilopascals times 100. */
        fun pressureFromE2(pressureE2: Int): Double = pressureE2 / 100.0

        /** `EnvironmentData.temperature_e3` is degrees Celsius times 1000. */
        fun temperatureFromE3(temperatureE3: Int): Double = temperatureE3 / 1000.0

        /**
         * `OdometerData.kms_e1` is **kilometres** times 10, not metres.
         *
         * The field name says so and it is worth restating: reading it as metres
         * puts a car with 200 000 km on the clock at 20 km, which looks like a
         * plausible trip meter rather than an obvious unit error.
         */
        fun odometerKmFromE1(kmsE1: Int): Double = kmsE1 / 10.0
    }
}

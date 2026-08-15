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
    /**
     * `FuelData.fuel_level`, verbatim. **The unit is not stated anywhere.**
     *
     * Named for the schema field rather than for a quantity, because there is
     * nothing to cite for the quantity. The field is a bare
     * `optional int32 fuel_level = 1` with no comment
     * (`.../sensorsource/message/FuelData.proto` L6, identical in
     * `aasdk/docs/protos.proto` L394-L398 and `aa-proxy-rs/src/protos/protos.proto`
     * L419-L423), and unlike every other quantity on this channel it carries no
     * `_eN` suffix to say what it is scaled by.
     *
     * The one reading in any reference treats it as a **percentage**:
     * aa-proxy-rs takes `msg.fuel_data[0].fuel_level()` from a real head unit's
     * batch and passes it straight into `battery_level_percentage`
     * (`aa-proxy-rs/src/mitm.rs` L2408-L2432), a field its own API validates as
     * 0.0-100.0 (`src/web.rs` L640-L646). That is an inference drawn for
     * electric vehicles, not documentation, so this field is reported as the
     * number the car sent and no unit is asserted on it. A single drive's log
     * settles it; see BLOCKERS.md B-022.
     */
    val fuelLevel: Int? = null,
    /**
     * `FuelData.range`, verbatim. **The unit is not stated anywhere, and no
     * reference reads this field at all.**
     *
     * Same shape as [fuelLevel] and less evidence: `optional int32 range = 2`
     * with no comment and no `_eN` suffix, and a grep of aasdk, openauto,
     * aa-proxy-rs and AACS finds no producer and no consumer. Metres, kilometres
     * and tenths of a kilometre are all plausible and none is citable, so the
     * raw number is what is carried and what the dashboard shows.
     */
    val range: Int? = null,
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
        get() = speedMetersPerSecond != null || rpm != null || fuelLevel != null ||
            range != null || tyrePressuresKpa.isNotEmpty() ||
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
        fuelLevel = update.fuelLevel ?: fuelLevel,
        range = update.range ?: range,
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
        // No unit on either: see the fields' own KDoc. Printing "45%" or
        // "380 km" here would put a guess into every log from every drive, which
        // is the one place the guess would be hardest to notice and hardest to
        // undo.
        fuelLevel?.let { parts += "fuel level $it" }
        range?.let { parts += "range $it" }
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
         * Kilometres from `OdometerData.kms_e1`, at [scale] tenths per kilometre.
         *
         * ## Why this is not simply `/ 10.0`
         *
         * The field name says `_e1`, and every other scaled field in the sensor
         * set follows that convention exactly — `speed_e3`, `rpm_e3`,
         * `temperature_e3`, `pressure_e2` are all value times ten to the N. So
         * kilometres times ten is what the reverse-engineered schema says, and
         * it is the default here.
         *
         * A real 2021 Chevrolet Infotainment 3 unit disagrees. Its driver
         * reported the pane reading **exactly one hundred times** their real
         * odometer, which puts that unit's raw value at kilometres times a
         * thousand — metres — whatever the field is called. One hundred is not a
         * number that arrives by accident, and it is the difference between
         * `_e1` and `_e3`.
         *
         * CLAUDE.md's rule for exactly this situation is to implement the
         * documented behaviour, make it configurable, and log loudly. So the
         * schema's reading stays the default, the divisor is a quirk, and
         * `SensorChannel` logs the raw value beside the converted one so a
         * single drive's log settles it for any other car.
         *
         * @param scale tenths of a kilometre per raw unit: 1 for the schema's
         *   `_e1`, 1000 for a unit that sends metres.
         */
        fun odometerKmFromE1(kmsE1: Int, scale: Int = ODOMETER_SCALE_E1): Double =
            kmsE1 / (10.0 * scale.coerceAtLeast(1))

        /** The schema's reading: `kms_e1` really is kilometres times ten. */
        const val ODOMETER_SCALE_E1: Int = 1

        /** A unit whose `kms_e1` carries metres, which is a hundred times finer. */
        const val ODOMETER_SCALE_METERS: Int = 100
    }
}

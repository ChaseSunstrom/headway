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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The scaled-integer conversions, which are where a sensor bug hides.
 *
 * Every quantity in `SensorBatch` is an integer scaled by a power of ten, and a
 * conversion off by one factor shows a driver 0.1 km/h or 1000 km/h without
 * anything looking wrong. The scale factors are read off the field names in
 * `aap_protobuf/service/sensorsource/message/`, and the expected values here are
 * worked by hand from those names.
 */
class CarSensorsTest {

    @Test
    fun `speed_e3 is metres per second times a thousand`() {
        // 27.778 m/s is 100 km/h, the number a driver is most likely to check.
        assertEquals(27.778, CarSensors.speedFromE3(27_778), 0.001)
        val sensors = CarSensors(speedMetersPerSecond = CarSensors.speedFromE3(27_778))
        assertEquals(100.0, sensors.speedKph!!, 0.01)
        assertEquals(62.14, sensors.speedMph!!, 0.01)
    }

    @Test
    fun `a stationary car reads zero, not unknown`() {
        val stopped = CarSensors(speedMetersPerSecond = CarSensors.speedFromE3(0))
        assertEquals(0.0, stopped.speedKph!!, 0.0001)
        assertTrue(stopped.any, "a reported zero is data, not the absence of it")
    }

    @Test
    fun `rpm_e3 is revolutions per minute times a thousand`() {
        assertEquals(2500.0, CarSensors.rpmFromE3(2_500_000), 0.001)
    }

    @Test
    fun `tire_pressures_e2 is kilopascals times a hundred`() {
        // 220 kPa is about 32 psi, a normal cold tyre.
        assertEquals(220.0, CarSensors.pressureFromE2(22_000), 0.001)
    }

    @Test
    fun `temperature_e3 is celsius times a thousand, including below freezing`() {
        assertEquals(21.5, CarSensors.temperatureFromE3(21_500), 0.001)
        assertEquals(-7.25, CarSensors.temperatureFromE3(-7_250), 0.001)
    }

    @Test
    fun `kms_e1 is kilometres times ten, not metres`() {
        // The trap: read as metres this is 20 km, which looks like a trip meter
        // rather than an obviously wrong odometer.
        assertEquals(200_000.0, CarSensors.odometerKmFromE1(2_000_000), 0.001)
        // The same raw value on a unit that sends metres. A hundred times
        // finer, which is exactly the error a driver reported from a real car.
        assertEquals(
            2_000.0,
            CarSensors.odometerKmFromE1(2_000_000, CarSensors.ODOMETER_SCALE_METERS),
            0.001,
        )
        // A nonsense scale must not divide by zero or flip the sign.
        assertEquals(200_000.0, CarSensors.odometerKmFromE1(2_000_000, 0), 0.001)
        assertEquals(200_000.0, CarSensors.odometerKmFromE1(2_000_000, -5), 0.001)
    }

    @Test
    fun `nothing reported is not an error state`() {
        assertFalse(CarSensors.UNKNOWN.any)
        assertNull(CarSensors.UNKNOWN.speedKph)
        assertNull(CarSensors.UNKNOWN.restricted)
        assertEquals("sensors: nothing reported", CarSensors.UNKNOWN.describe())
    }

    @Test
    fun `an update keeps values it does not carry`() {
        // A batch carries only the sensors that changed, so a speed-only update
        // must not erase the fuel level the car sent a minute ago.
        val known = CarSensors(fuelLevel = 60, tyrePressuresKpa = listOf(220.0, 221.0))
        val merged = known.mergedWith(CarSensors(speedMetersPerSecond = 10.0))
        assertEquals(60, merged.fuelLevel)
        assertEquals(listOf(220.0, 221.0), merged.tyrePressuresKpa)
        assertEquals(10.0, merged.speedMetersPerSecond!!, 0.0001)
    }

    @Test
    fun `an update overwrites what it does carry`() {
        val known = CarSensors(fuelLevel = 60)
        assertEquals(55, known.mergedWith(CarSensors(fuelLevel = 55)).fuelLevel)
    }

    @Test
    fun `an empty tyre list does not erase a known one`() {
        // Distinct from the nullable fields: the list's "absent" is empty, and
        // empty must mean "not in this batch" rather than "the car has no tyres".
        val known = CarSensors(tyrePressuresKpa = listOf(220.0))
        assertEquals(listOf(220.0), known.mergedWith(CarSensors()).tyrePressuresKpa)
    }

    @Test
    fun `driving status zero is the only unrestricted value`() {
        assertFalse(CarSensors(drivingStatus = 0).restricted!!)
        assertTrue(CarSensors(drivingStatus = 1).restricted!!)
        assertTrue(CarSensors(drivingStatus = 8).restricted!!)
    }

    @Test
    fun `describe names every quantity it has`() {
        val full = CarSensors(
            speedMetersPerSecond = 27.778,
            rpm = 2100.0,
            fuelLevel = 45,
            range = 380,
            tyrePressuresKpa = listOf(221.0, 220.0, 219.0, 223.0),
            outsideTemperatureCelsius = 18.5,
            parkingBrake = false,
            nightMode = true,
        )
        val line = full.describe()
        listOf("100.0 km/h", "2100 rpm", "fuel level 45", "range 380", "221/220/219/223 kPa",
            "18.5 C", "parking brake off", "night").forEach {
            assertTrue(line.contains(it), "describe() lost \"$it\": $line")
        }
    }

    /**
     * The two fields whose unit nothing in any reference states.
     *
     * `FuelData.fuel_level` and `FuelData.range` are bare `int32`s with no
     * comment and no `_eN` suffix, and the only reference that reads either
     * infers a percentage for electric vehicles rather than documenting one
     * (see the fields' KDoc, and BLOCKERS.md B-022). This pins the consequence:
     * the numbers travel and are shown exactly as the car sent them, and no
     * `%`, `km` or `mi` is attached to a guess. Change it when a real drive's
     * log says what the unit is, not before.
     */
    @Test
    fun `fuel level and range carry no invented unit`() {
        val line = CarSensors(fuelLevel = 45, range = 380).describe()
        assertTrue(line.contains("fuel level 45"), line)
        assertTrue(line.contains("range 380"), line)
        assertFalse(line.contains("45%"), "no reference states fuel_level is a percentage: $line")
        assertFalse(line.contains("380 km"), "no reference states range is in kilometres: $line")
    }
}

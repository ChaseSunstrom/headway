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

import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CarUnitsTest {

    private fun near(expected: Double, actual: Double, tolerance: Double = 0.01, what: String) {
        assertTrue(abs(expected - actual) < tolerance, "$what: expected $expected, got $actual")
    }

    @Test
    fun `an unknown or missing stored name falls back rather than throwing`() {
        assertEquals(CarUnits.DEFAULT, CarUnits.of(null))
        assertEquals(CarUnits.DEFAULT, CarUnits.of(""))
        assertEquals(CarUnits.DEFAULT, CarUnits.of("furlongs"))
    }

    @Test
    fun `a stored name is read whatever its case`() {
        assertEquals(CarUnits.IMPERIAL, CarUnits.of("IMPERIAL"))
        assertEquals(CarUnits.IMPERIAL, CarUnits.of("imperial"))
        assertEquals(CarUnits.METRIC, CarUnits.of("Metric"))
    }

    @Test
    fun `the automatic guess follows road signs, not the language`() {
        assertTrue(CarUnits.imperialFor("US"))
        assertTrue(CarUnits.imperialFor("gb"))
        // Canada speaks English and posts km/h; guessing from the language would
        // put it on miles, which is the mistake this list exists to avoid.
        assertFalse(CarUnits.imperialFor("CA"))
        assertFalse(CarUnits.imperialFor("DE"))
        assertFalse(CarUnits.imperialFor(null))
        assertFalse(CarUnits.imperialFor(""))
    }

    @Test
    fun `speed converts from metres per second`() {
        near(100.0, CarUnitConversion.speed(27.7778, imperial = false), what = "100 km/h")
        near(62.14, CarUnitConversion.speed(27.7778, imperial = true), what = "100 km/h in mph")
        assertEquals("km/h", CarUnitConversion.speedUnit(imperial = false))
        assertEquals("mph", CarUnitConversion.speedUnit(imperial = true))
    }

    @Test
    fun `distance converts from kilometres`() {
        near(100.0, CarUnitConversion.distance(100.0, imperial = false), what = "metric passthrough")
        near(62.14, CarUnitConversion.distance(100.0, imperial = true), what = "100 km in miles")
        assertEquals("mi", CarUnitConversion.distanceUnit(imperial = true))
    }

    @Test
    fun `temperature converts from celsius, including below freezing`() {
        near(32.0, CarUnitConversion.temperature(0.0, imperial = true), what = "freezing")
        near(212.0, CarUnitConversion.temperature(100.0, imperial = true), what = "boiling")
        near(-40.0, CarUnitConversion.temperature(-40.0, imperial = true), what = "the crossover")
        near(21.0, CarUnitConversion.temperature(21.0, imperial = false), what = "metric passthrough")
    }

    @Test
    fun `tyre pressure converts from kilopascals`() {
        // 220 kPa is an ordinary cold placard pressure, and 32 psi is how it is
        // written on the door of a car sold in the United States.
        near(31.9, CarUnitConversion.pressure(220.0, imperial = true), what = "220 kPa in psi")
        near(220.0, CarUnitConversion.pressure(220.0, imperial = false), what = "metric passthrough")
        assertEquals("psi", CarUnitConversion.pressureUnit(imperial = true))
        assertEquals("kPa", CarUnitConversion.pressureUnit(imperial = false))
    }

    @Test
    fun `every choice describes itself and its units`() {
        CarUnits.entries.forEach { choice ->
            assertTrue(choice.describe().isNotBlank(), "${choice.name} has no name")
            assertTrue(choice.detail().isNotBlank(), "${choice.name} has no detail")
        }
    }
}

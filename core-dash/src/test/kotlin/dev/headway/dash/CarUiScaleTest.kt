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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CarUiScaleTest {

    @Test
    fun `an absurd scale is clamped rather than obeyed`() {
        assertEquals(CarUiScale.MAX, CarUiScale("a", 40f).effective)
        assertEquals(CarUiScale.MIN, CarUiScale("a", 0.01f).effective)
        assertEquals(CarUiScale.MIN, CarUiScale("a", -3f).effective)
    }

    @Test
    fun `density never falls to zero`() {
        // Configuration.densityDpi of zero means "undefined" and makes every dp
        // resolve to nothing, which is a blank pane rather than a small one.
        assertTrue(CarUiScale("a", CarUiScale.MIN).densityFor(1) >= CarUiScale.MIN_DENSITY_DPI)
        assertTrue(CarUiScale("a", CarUiScale.MIN).densityFor(0) >= CarUiScale.MIN_DENSITY_DPI)
    }

    @Test
    fun `density follows the scale on a car-class panel`() {
        // 160 dpi is what the target head unit reports.
        assertEquals(160, CarUiScale("a", 1.0f).densityFor(160))
        assertEquals(200, CarUiScale("a", 1.25f).densityFor(160))
        assertEquals(128, CarUiScale("a", 0.8f).densityFor(160))
    }

    @Test
    fun `every offered size survives the clamp and is distinct`() {
        val drawn = CarUiScale.CHOICES.map { CarUiScale("a", it).densityFor(160) }
        assertEquals(CarUiScale.CHOICES.size, drawn.toSet().size, "two sizes draw alike: $drawn")
        CarUiScale.CHOICES.forEach { choice ->
            assertEquals(choice, CarUiScale("a", choice).effective, "size $choice")
        }
    }

    @Test
    fun `the map round-trips and drops defaults`() {
        val written = CarUiScale.writeAll(mapOf("a.b" to 1.25f, "c.d" to CarUiScale.DEFAULT))
        val back = CarUiScale.readAll(written)
        assertEquals(1.25f, back["a.b"])
        assertTrue(!back.containsKey("c.d"), "a default should leave no trace")
    }

    @Test
    fun `unreadable storage costs a size and not a car screen`() {
        assertEquals(emptyMap<String, Float>(), CarUiScale.readAll(null))
        assertEquals(emptyMap<String, Float>(), CarUiScale.readAll(""))
        assertEquals(emptyMap<String, Float>(), CarUiScale.readAll("{not json"))
    }

    @Test
    fun `an app with no entry is left at its default`() {
        val scales = mapOf("a.b" to 1.5f)
        assertEquals(1.5f, CarUiScale.forApp(scales, "a.b"))
        assertEquals(CarUiScale.DEFAULT, CarUiScale.forApp(scales, "other"))
        assertEquals(CarUiScale.DEFAULT, CarUiScale.forApp(scales, null))
    }
}

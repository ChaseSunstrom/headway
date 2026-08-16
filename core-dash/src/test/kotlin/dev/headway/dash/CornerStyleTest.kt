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

class CornerStyleTest {

    @Test
    fun `an unknown or missing name reads as the default`() {
        assertEquals(CornerStyle.DEFAULT, CornerStyle.of(null))
        assertEquals(CornerStyle.DEFAULT, CornerStyle.of(""))
        assertEquals(CornerStyle.DEFAULT, CornerStyle.of("BEVELLED"))
    }

    @Test
    fun `a stored name is read whatever its case`() {
        assertEquals(CornerStyle.SQUARE, CornerStyle.of("SQUARE"))
        assertEquals(CornerStyle.SQUARE, CornerStyle.of("square"))
        assertEquals(CornerStyle.PILL, CornerStyle.of("Pill"))
    }

    @Test
    fun `square is exactly square`() {
        // Not nearly zero. A driver who asks for square corners is asking for
        // square corners, and a half-pixel radius is visible on a panel this
        // size against a flat background.
        assertEquals(0f, CornerStyle.SQUARE.scale)
    }

    @Test
    fun `the styles are ordered from least to most rounded`() {
        CornerStyle.ALL.zipWithNext { a, b ->
            assertTrue(a.scale < b.scale, "${a.name} should be less round than ${b.name}")
        }
    }

    @Test
    fun `every style round-trips through its name`() {
        CornerStyle.ALL.forEach { style ->
            assertEquals(style, CornerStyle.of(style.name), "${style.name} did not round-trip")
        }
    }
}

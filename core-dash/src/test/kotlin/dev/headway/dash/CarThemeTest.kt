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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Every palette the driver can choose, checked for the thing that actually
 * matters on a dashboard: whether the text can be read.
 */
class CarThemeTest {

    private fun hex(color: Int) = "#%08X".format(color)

    @Test
    @DisplayName("body text clears 4.5:1 against its own background in every theme")
    fun textIsReadable() {
        CarTheme.all().forEach { choice ->
            val palette = CarTheme.palette(choice)
            listOf(
                "text on ground" to Contrast.ratio(palette.text, palette.ground),
                "text on surface" to Contrast.ratio(palette.text, palette.surface),
                "text on raised" to Contrast.ratio(palette.text, palette.surfaceRaised),
            ).forEach { (what, ratio) ->
                assertTrue(ratio >= 4.5, "$what is $ratio:1 in ${choice.encode()}")
            }
        }
    }

    @Test
    @DisplayName("muted text clears 3:1, which is the floor for a glance")
    fun mutedTextIsReadable() {
        CarTheme.all().forEach { choice ->
            val palette = CarTheme.palette(choice)
            val onSurface = Contrast.ratio(palette.textMuted, palette.surface)
            assertTrue(onSurface >= 3.0, "muted text is $onSurface:1 in ${choice.encode()}")
        }
    }

    @Test
    @DisplayName("a label on a filled accent pill is readable in every theme")
    fun accentPillsAreReadable() {
        CarTheme.all().forEach { choice ->
            val palette = CarTheme.palette(choice)
            val ratio = Contrast.ratio(palette.onAccent, palette.accent)
            assertTrue(ratio >= 4.5, "accent label is $ratio:1 in ${choice.encode()}")
        }
    }

    @Test
    @DisplayName("the accent is visible against the ground it sits on")
    fun accentStandsOut() {
        CarTheme.all().forEach { choice ->
            val palette = CarTheme.palette(choice)
            val ratio = Contrast.ratio(palette.accent, palette.ground)
            assertTrue(ratio >= 3.0, "accent is $ratio:1 on ground in ${choice.encode()}")
        }
    }

    @Test
    @DisplayName("surfaces are distinguishable from the ground they sit on")
    fun surfacesAreVisible() {
        CarTheme.all().forEach { choice ->
            val palette = CarTheme.palette(choice)
            assertFalse(
                Contrast.indistinguishable(palette.surface, palette.ground),
                "surface ${hex(palette.surface)} vanishes into ground in ${choice.encode()}",
            )
            assertFalse(
                Contrast.indistinguishable(palette.surfaceRaised, palette.surface),
                "raised surface vanishes into surface in ${choice.encode()}",
            )
        }
    }

    @Test
    @DisplayName("choosing no accent really does leave no hue")
    fun noAccentIsGreyscale() {
        listOf(ThemeBase.DARK, ThemeBase.MIDNIGHT, ThemeBase.LIGHT).forEach { base ->
            val accent = CarTheme.palette(ThemeChoice(base, ThemeAccent.NONE)).accent
            val red = (accent shr 16) and 0xFF
            val green = (accent shr 8) and 0xFF
            val blue = accent and 0xFF
            val spread = maxOf(red, green, blue) - minOf(red, green, blue)
            assertTrue(spread <= 24, "accent ${hex(accent)} in $base is not neutral (spread $spread)")
        }
    }

    @Test
    @DisplayName("light is light and the dark themes are dark")
    fun basesAreWhatTheySay() {
        assertTrue(CarTheme.palette(ThemeChoice(ThemeBase.LIGHT)).isLight)
        assertFalse(CarTheme.palette(ThemeChoice(ThemeBase.DARK)).isLight)
        assertFalse(CarTheme.palette(ThemeChoice(ThemeBase.MIDNIGHT)).isLight)
        assertEquals(0xFF000000.toInt(), CarTheme.palette(ThemeChoice(ThemeBase.MIDNIGHT)).ground)
    }

    @Test
    @DisplayName("a theme choice survives being written down and read back")
    fun choiceRoundTrips() {
        CarTheme.all().forEach { choice ->
            assertEquals(choice, ThemeChoice.decode(choice.encode()))
        }
    }

    @Test
    @DisplayName("an unreadable stored theme is the default, never a crash")
    fun decodingNeverThrows() {
        listOf(null, "", "  ", "GARBAGE", "DARK:", ":", "PINK:NEON", "DARK:NEON")
            .forEach { stored ->
                val decoded = ThemeChoice.decode(stored)
                // Everything unreadable falls back; a readable base with an
                // unreadable accent keeps the base.
                assertTrue(decoded.base in ThemeBase.entries, "for input $stored")
                assertTrue(decoded.accent in ThemeAccent.entries, "for input $stored")
            }
        assertEquals(ThemeChoice.DEFAULT, ThemeChoice.decode("PINK:NEON"))
        assertEquals(ThemeBase.DARK, ThemeChoice.decode("DARK:NEON").base)
        assertEquals(ThemeAccent.HEADWAY, ThemeChoice.decode("DARK:NEON").accent)
    }

    @Test
    @DisplayName("the default is the dark cyan Headway has always been")
    fun defaultIsUnchanged() {
        val palette = CarTheme.palette(ThemeChoice.DEFAULT)
        assertEquals(0xFF4FC3F7.toInt(), palette.accent)
        assertEquals(0xFF0B0E11.toInt(), palette.ground)
        assertEquals(0xFF151A1F.toInt(), palette.surface)
    }
}

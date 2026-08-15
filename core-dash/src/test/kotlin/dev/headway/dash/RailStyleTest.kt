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

class RailStyleTest {

    @Test
    fun `a style round-trips through JSON`() {
        val style = RailStyle(RailEdge.LEFT, 1.25f, showClock = true, showDate = true)
        val back = RailStyle.fromJson(style.toJson().toString())
        assertEquals(style.edge, back.edge)
        assertTrue(abs(style.scale - back.scale) < 0.001f, "scale drifted: ${back.scale}")
        assertEquals(style.showClock, back.showClock)
        assertEquals(style.showDate, back.showDate)
    }

    @Test
    fun `every offered size survives the round trip`() {
        // A settings screen that offers a size the store cannot read back would
        // silently reset the rail on the next drive.
        RailStyle.SCALE_CHOICES.forEach { scale ->
            val back = RailStyle.fromJson(RailStyle(scale = scale).toJson().toString())
            assertTrue(
                abs(scale - back.effectiveScale) < 0.001f,
                "$scale came back as ${back.effectiveScale}",
            )
        }
    }

    @Test
    fun `nothing unreadable can stop the rail being built`() {
        // Each of these is something a corrupted or hand-edited preference can
        // really contain. The car screen has to come up regardless.
        listOf(null, "", "   ", "not json", "[]", "{", """{"edge":"SIDEWAYS"}""").forEach { text ->
            val style = RailStyle.fromJson(text)
            assertTrue(
                style.effectiveScale in RailStyle.MIN_SCALE..RailStyle.MAX_SCALE,
                "unreadable input $text produced ${style.effectiveScale}",
            )
        }
        // Unreadable input must give back the defaults themselves, not merely
        // something in range.
        assertEquals(RailStyle.DEFAULT, RailStyle.fromJson("nonsense"))
        assertEquals(RailStyle.DEFAULT, RailStyle.fromJson(null))
        // A recognisable object with one bad field keeps its readable fields and
        // defaults only the bad one.
        val partial = RailStyle.fromJson("""{"edge":"SIDEWAYS","clock":false}""")
        assertEquals(RailEdge.TOP, partial.edge)
        assertFalse(partial.showClock, "a readable field beside a bad one was discarded")
    }

    @Test
    fun `an absurd scale is clamped rather than obeyed`() {
        assertEquals(RailStyle.MAX_SCALE, RailStyle(scale = 40f).effectiveScale)
        assertEquals(RailStyle.MIN_SCALE, RailStyle(scale = 0.01f).effectiveScale)
        assertEquals(RailStyle.MIN_SCALE, RailStyle(scale = -3f).effectiveScale)
    }

    @Test
    fun `every offered size survives the clamp`() {
        // The picker offers these, so none of them may be silently changed into
        // something else on the way to the rail.
        RailStyle.SCALE_CHOICES.forEach { choice ->
            assertEquals(choice, RailStyle(scale = choice).effectiveScale, "size $choice")
        }
    }

    @Test
    fun `no two offered sizes draw the same rail`() {
        // The bug this exists for: sizes are applied by scaling the car's
        // metrics, and `CarMetrics.touchTargetPx` has an absolute 44-pixel floor
        // under it. Two choices that both land under that floor produce an
        // identical rail -- the chip moves, the screen does not. Modelled here
        // rather than in the app module because `CarMetrics` cannot be reached
        // from a JVM test, and the arithmetic is the whole of it.
        fun targetPx(densityDpi: Int, scale: Float): Int {
            val base = maxOf(44, (48.0 * densityDpi / 160.0).toInt())
            return (base * scale).toInt().coerceAtLeast(44)
        }
        // 160 is what the target head unit reports; 120 and 213 stand in for a
        // unit that under- and over-reports.
        listOf(120, 160, 213).forEach { dpi ->
            val drawn = RailStyle.SCALE_CHOICES.map { targetPx(dpi, it) }
            assertEquals(
                drawn.size,
                drawn.toSet().size,
                "at $dpi dpi two sizes draw the same rail: ${RailStyle.SCALE_CHOICES.zip(drawn)}",
            )
        }
    }

    @Test
    fun `only the side edges are vertical`() {
        assertTrue(RailEdge.LEFT.vertical)
        assertTrue(RailEdge.RIGHT.vertical)
        assertFalse(RailEdge.TOP.vertical)
        assertFalse(RailEdge.BOTTOM.vertical)
    }

    @Test
    fun `a date with no time is not drawn`() {
        // Otherwise the rail shows a bare date, which reads as a label rather
        // than a clock the driver turned off.
        assertFalse(RailStyle(showClock = false, showDate = true).clockShowsDate)
        assertTrue(RailStyle(showClock = true, showDate = true).clockShowsDate)
    }

    @Test
    fun `an edge name is read whatever its case`() {
        assertEquals(RailEdge.BOTTOM, RailEdge.of("bottom"))
        assertEquals(RailEdge.RIGHT, RailEdge.of("Right"))
        assertEquals(RailEdge.TOP, RailEdge.of(null))
        assertEquals(RailEdge.TOP, RailEdge.of(""))
    }
}

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

class OverlaySpotTest {

    @Test
    fun `a spot survives a round trip`() {
        val spot = OverlaySpot(x = 0.1f, y = 0.2f, width = 0.5f, height = 0.4f)
        assertEquals(spot, OverlaySpot.decode(OverlaySpot.encode(spot)))
    }

    @Test
    fun `nothing readable gives the default rather than throwing`() {
        assertEquals(OverlaySpot.DEFAULT, OverlaySpot.decode(null))
        assertEquals(OverlaySpot.DEFAULT, OverlaySpot.decode(""))
        assertEquals(OverlaySpot.DEFAULT, OverlaySpot.decode("not json"))
        assertEquals(OverlaySpot.DEFAULT, OverlaySpot.decode("{}"))
    }

    @Test
    fun `a card cannot be pushed off the panel`() {
        // The failure this prevents is one a driver cannot undo from the seat:
        // a card dragged past the edge is a sliver they can no longer get hold
        // of to drag back.
        val offRight = OverlaySpot(x = 2f, y = 2f, width = 0.4f, height = 0.4f)
        assertEquals(0.6f, offRight.effectiveX, 0.001f)
        assertEquals(0.6f, offRight.effectiveY, 0.001f)

        val offLeft = OverlaySpot(x = -3f, y = -3f)
        assertEquals(0f, offLeft.effectiveX, 0.001f)
        assertEquals(0f, offLeft.effectiveY, 0.001f)
    }

    @Test
    fun `a card too small to hold anything is grown`() {
        val tiny = OverlaySpot(width = 0.001f, height = 0f)
        assertEquals(OverlaySpot.MIN_SIZE, tiny.effectiveWidth, 0.001f)
        assertEquals(OverlaySpot.MIN_SIZE, tiny.effectiveHeight, 0.001f)
    }

    @Test
    fun `pixels come from the panel, so a different head unit still fits`() {
        val spot = OverlaySpot(x = 0.5f, y = 0.25f, width = 0.5f, height = 0.5f)
        assertEquals(400, spot.leftPx(800))
        assertEquals(120, spot.topPx(480))
        assertEquals(400, spot.widthPx(800))
        assertEquals(240, spot.heightPx(480))
        // The same fractions on a bigger panel, which is the whole point.
        assertEquals(960, spot.leftPx(1920))
        assertEquals(960, spot.widthPx(1920))
    }

    @Test
    fun `every offered corner lands the card fully on the panel`() {
        OverlaySpot.SIZES.forEach { (_, width, height) ->
            OverlaySpot.corners(width, height).forEach { (label, x, y) ->
                val spot = OverlaySpot(x = x, y = y, width = width, height = height)
                assertTrue(spot.effectiveX >= 0f, "$label went off the left")
                assertTrue(spot.effectiveY >= 0f, "$label went off the top")
                assertTrue(
                    spot.effectiveX + spot.effectiveWidth <= 1.001f,
                    "$label went off the right",
                )
                assertTrue(
                    spot.effectiveY + spot.effectiveHeight <= 1.001f,
                    "$label went off the bottom",
                )
            }
        }
    }
}

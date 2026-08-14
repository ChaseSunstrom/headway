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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.abs

/** Where an app's picture lands in a pane, and where a touch on it goes back to. */
class PaneFitTest {

    @Test
    @DisplayName("a source wider than the pane is fitted to the pane's width and centred vertically")
    fun wideSource() {
        // The simulated display (720x480, 3:2) in a 600x480 pane.
        val rect = PaneFit.fit(720, 480, 600, 480)
        assertEquals(600, rect.width)
        assertEquals(400, rect.height)
        assertEquals(0, rect.left)
        assertEquals(40, rect.top)
    }

    @Test
    @DisplayName("a portrait phone in a landscape pane becomes a centred strip, not a stretch")
    fun tallSource() {
        // A Pixel (1080x2404) in a 800x432 pane: the shape that made mirroring
        // unusable, now confined to a pane instead of the whole car screen.
        val rect = PaneFit.fit(1080, 2404, 800, 432)
        assertEquals(432, rect.height)
        assertEquals(194, rect.width)
        assertEquals(0, rect.top)
        assertEquals((800 - 194) / 2, rect.left)
        // Aspect preserved to within a pixel.
        assertTrue(abs(rect.width / rect.height.toDouble() - 1080 / 2404.0) < 0.005)
    }

    @Test
    @DisplayName("a source with the pane's exact aspect fills it edge to edge")
    fun exactFit() {
        val rect = PaneFit.fit(720, 480, 360, 240)
        assertEquals(PaneRect(0, 0, 360, 240), rect)
    }

    @Test
    @DisplayName("an unmeasured pane fits nothing rather than dividing by zero")
    fun degenerate() {
        assertEquals(0, PaneFit.fit(720, 480, 0, 0).width)
        assertEquals(0, PaneFit.fit(0, 0, 400, 300).width)
        assertEquals(0, PaneFit.fit(720, 480, 400, -3).height)
    }

    @Test
    @DisplayName("a touch in the middle of the picture maps to the middle of the source")
    fun touchMapsThrough() {
        val picture = PaneFit.fit(720, 480, 600, 480)
        val point = PaneFit.toSource(300, 240, picture, 720, 480)
        assertNotNull(point)
        assertEquals(360, point!!.x)
        assertEquals(240, point.y)
    }

    @Test
    @DisplayName("the corners of the picture map to the corners of the source, inclusive")
    fun cornersMap() {
        val picture = PaneFit.fit(720, 480, 600, 480)
        val topLeft = PaneFit.toSource(picture.left, picture.top, picture, 720, 480)!!
        assertEquals(0, topLeft.x)
        assertEquals(0, topLeft.y)

        val bottomRight =
            PaneFit.toSource(picture.right - 1, picture.bottom - 1, picture, 720, 480)!!
        assertEquals(719, bottomRight.x)
        assertEquals(479, bottomRight.y)
    }

    @Test
    @DisplayName("a touch on the letterbox is not the app's, and is refused")
    fun letterboxTouchesAreRefused() {
        val picture = PaneFit.fit(720, 480, 600, 480)
        // Above the picture: the pane's own background.
        assertNull(PaneFit.toSource(300, 10, picture, 720, 480))
        // Below it.
        assertNull(PaneFit.toSource(300, 470, picture, 720, 480))
        // Just inside the top edge is the app's.
        assertNotNull(PaneFit.toSource(300, picture.top, picture, 720, 480))
    }

    @Test
    @DisplayName("no mapped point is ever outside the source display")
    fun mappingStaysInBounds() {
        val picture = PaneFit.fit(1080, 2404, 800, 432)
        for (x in picture.left until picture.right) {
            for (y in picture.top until picture.bottom step 7) {
                val point = PaneFit.toSource(x, y, picture, 1080, 2404)!!
                assertTrue(point.x in 0..1079, "x=${point.x} for pane ($x,$y)")
                assertTrue(point.y in 0..2403, "y=${point.y} for pane ($x,$y)")
            }
        }
    }

    @Test
    @DisplayName("cover fills the pane, overflowing on the axis fit would have left blank")
    fun cover() {
        // The phone-screen case: 1080x2404 into an 800x432 pane.
        val rect = PaneFit.cover(1080, 2404, 800, 432)
        assertEquals(800, rect.width, "the pane's width must be filled")
        assertTrue(rect.height > 432, "the overflow is what gets clipped")
        assertEquals(0, rect.left)
        assertTrue(rect.top < 0, "the overflow is centred, so it hangs off both ends")
        // Aspect preserved.
        assertTrue(abs(rect.width / rect.height.toDouble() - 1080 / 2404.0) < 0.01)
    }

    @Test
    @DisplayName("cover and fit agree when the source already has the pane's shape")
    fun coverEqualsFitWhenAspectsMatch() {
        assertEquals(PaneFit.fit(720, 480, 360, 240), PaneFit.cover(720, 480, 360, 240))
    }

    @Test
    @DisplayName("cover never leaves a gap on either axis")
    fun coverLeavesNoGap() {
        listOf(720 to 480, 1080 to 2404, 480 to 480, 2400 to 1080).forEach { (w, h) ->
            listOf(800 to 432, 300 to 400, 640 to 200).forEach { (pw, ph) ->
                val rect = PaneFit.cover(w, h, pw, ph)
                assertTrue(rect.width >= pw, "width $rect for ${w}x$h in ${pw}x$ph")
                assertTrue(rect.height >= ph, "height $rect for ${w}x$h in ${pw}x$ph")
            }
        }
    }

    @Test
    @DisplayName("place picks whichever the driver asked for")
    fun place() {
        assertEquals(
            PaneFit.fit(1080, 2404, 800, 432),
            PaneFit.place(1080, 2404, 800, 432, fill = false),
        )
        assertEquals(
            PaneFit.cover(1080, 2404, 800, 432),
            PaneFit.place(1080, 2404, 800, 432, fill = true),
        )
    }

    @Test
    @DisplayName("a touch still maps through a cropped picture, including off-pane rows")
    fun touchThroughCover() {
        val rect = PaneFit.cover(1080, 2404, 800, 432)
        // The centre of the pane is the centre of the source, cropped or not.
        val middle = PaneFit.toSource(400, 216, rect, 1080, 2404)!!
        assertTrue(abs(middle.x - 540) <= 2, "x was ${middle.x}")
        assertTrue(abs(middle.y - 1202) <= 6, "y was ${middle.y}")
        // A point outside the picture's rectangle is still refused, which for a
        // cover can only happen on the axis that was not filled.
        assertNull(PaneFit.toSource(-5, 216, rect, 1080, 2404))
    }

    @Test
    @DisplayName("an unmeasured pane covers nothing rather than dividing by zero")
    fun coverDegenerate() {
        assertEquals(0, PaneFit.cover(720, 480, 0, 0).width)
        assertEquals(0, PaneFit.cover(0, 0, 400, 300).width)
    }

    @Test
    @DisplayName("a pane that wobbled by a pixel does not reallocate the display")
    fun resizeHasSlack() {
        val target = PaneFit.fit(720, 480, 600, 480)
        assertFalse(PaneFit.needsResize(target.width, target.height, target))
        assertFalse(PaneFit.needsResize(target.width - 1, target.height + 1, target))
        assertTrue(PaneFit.needsResize(target.width - 40, target.height, target))
        // A target with no area is not something to resize towards.
        assertFalse(PaneFit.needsResize(10, 10, PaneRect(0, 0, 0, 0)))
    }
}

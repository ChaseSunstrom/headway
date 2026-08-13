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

import kotlin.math.abs
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Property-style checks on [TouchTransform] across the resolution pairs Headway
 * will actually meet.
 *
 * The interesting cases are the mismatched aspect ratios: a 1.67:1 car panel and
 * a 0.45:1 phone leave nearly two-thirds of the car screen blank, so a transform
 * that ignores the bars is wrong by hundreds of pixels at the edges — and still
 * looks roughly right in the middle, which is how such a bug survives a
 * demo.
 */
class TouchTransformTest {

    /** Car video resolutions: the Malibu-class 800x480 plus common wide panels. */
    private val carSizes = listOf(
        800 to 480,
        1280 to 720,
        1920 to 720,
        1920 to 1080,
        1024 to 600,
    )

    /** Phone screens: tall modern portrait, plus a landscape one to flip the bar axis. */
    private val phoneSizes = listOf(
        1080 to 2400,
        1440 to 3120,
        1080 to 1920,
        2400 to 1080,
        1440 to 1440,
    )

    private fun transforms(): List<TouchTransform> = carSizes.flatMap { (cw, ch) ->
        phoneSizes.map { (pw, ph) -> TouchTransform(cw, ch, pw, ph) }
    }

    private fun assertClose(expected: Double, actual: Double, tolerance: Double, what: String) {
        assertTrue(
            abs(expected - actual) <= tolerance,
            "$what: expected $expected, got $actual (tolerance $tolerance)",
        )
    }

    // --- geometry ------------------------------------------------------------

    /**
     * `scale = min(...)` means the axis that runs out first fits exactly, so only
     * one axis can have bars. Both non-zero would mean the content is smaller
     * than it needs to be on both axes — wasted screen and a wrong scale.
     */
    @Test
    fun `at most one axis is barred`() {
        for (t in transforms()) {
            assertFalse(
                t.pillarboxed && t.letterboxed,
                "$t has bars on both axes",
            )
        }
    }

    @Test
    fun `the content rectangle fits inside the car screen and preserves aspect ratio`() {
        for (t in transforms()) {
            val rect = t.contentRect
            assertTrue(rect.left >= -1e-9 && rect.top >= -1e-9, "$t content starts off-screen")
            assertTrue(rect.right <= t.carWidth + 1e-9, "$t content overflows to the right")
            assertTrue(rect.bottom <= t.carHeight + 1e-9, "$t content overflows at the bottom")

            val phoneAspect = t.phoneWidth.toDouble() / t.phoneHeight
            val contentAspect = rect.width / rect.height
            assertClose(phoneAspect, contentAspect, 1e-9, "$t aspect ratio")
        }
    }

    @Test
    fun `a matching aspect ratio produces no bars at all`() {
        val t = TouchTransform(carWidth = 1920, carHeight = 1080, phoneWidth = 1280, phoneHeight = 720)
        assertFalse(t.pillarboxed)
        assertFalse(t.letterboxed)
        assertEquals(1920.0, t.contentRect.width, 1e-9)
        assertEquals(1080.0, t.contentRect.height, 1e-9)
    }

    @Test
    fun `zero or negative dimensions are refused`() {
        assertThrows(IllegalArgumentException::class.java) { TouchTransform(0, 480, 1080, 2400) }
        assertThrows(IllegalArgumentException::class.java) { TouchTransform(800, 480, 1080, -1) }
    }

    // --- round trip ----------------------------------------------------------

    /**
     * The two directions are algebraic inverses, so a car point inside the
     * content rectangle must survive the trip. One pixel of slack covers the
     * clamp at the tolerance band; in practice the error is ~1e-13.
     */
    @Test
    fun `car to phone to car round-trips within one pixel`() {
        val random = Random(0x7011C4)
        for (t in transforms()) {
            repeat(200) {
                val carX = t.contentRect.left + random.nextDouble() * t.contentRect.width
                val carY = t.contentRect.top + random.nextDouble() * t.contentRect.height

                val phone = t.toPhone(carX, carY)
                assertNotNull(phone, "$t: ($carX, $carY) is inside the content rect")
                val back = t.toCar(phone!!.x, phone.y)

                assertClose(carX, back.x, 1.0, "$t round trip x")
                assertClose(carY, back.y, 1.0, "$t round trip y")
            }
        }
    }

    @Test
    fun `phone to car to phone round-trips within one pixel`() {
        val random = Random(0x9E3779B)
        for (t in transforms()) {
            repeat(200) {
                val phoneX = random.nextDouble() * t.phoneWidth
                val phoneY = random.nextDouble() * t.phoneHeight

                val car = t.toCar(phoneX, phoneY)
                val back = t.toPhone(car.x, car.y)
                assertNotNull(back, "$t: every phone pixel is inside the content rect")

                assertClose(phoneX, back!!.x, 1.0, "$t round trip x")
                assertClose(phoneY, back.y, 1.0, "$t round trip y")
            }
        }
    }

    /**
     * The wire carries `uint32` coordinates, so the input is always integral.
     * This is the round trip that actually happens in the session.
     */
    @Test
    fun `integer car coordinates round-trip within one pixel`() {
        val random = Random(0xC0FFEE)
        for (t in transforms()) {
            repeat(200) {
                val carX = (t.contentRect.left + random.nextDouble() * t.contentRect.width).toInt()
                val carY = (t.contentRect.top + random.nextDouble() * t.contentRect.height).toInt()

                // Truncation can land the sample one pixel outside a fractional
                // content edge; that pixel is bar, and has nothing to round-trip.
                val phone = t.toPhone(carX, carY) ?: return@repeat
                val back = t.toCar(phone.x, phone.y)
                assertClose(carX.toDouble(), back.x, 1.0, "$t round trip x")
                assertClose(carY.toDouble(), back.y, 1.0, "$t round trip y")
            }
        }
    }

    // --- corners and centre --------------------------------------------------

    @Test
    fun `the corners of the content rectangle are the corners of the phone screen`() {
        for (t in transforms()) {
            val rect = t.contentRect
            val w = t.phoneWidth.toDouble()
            val h = t.phoneHeight.toDouble()

            val corners = listOf(
                Triple(rect.left, rect.top, 0.0 to 0.0),
                Triple(rect.right, rect.top, w to 0.0),
                Triple(rect.left, rect.bottom, 0.0 to h),
                Triple(rect.right, rect.bottom, w to h),
            )
            for ((carX, carY, expected) in corners) {
                val phone = t.toPhone(carX, carY)
                assertNotNull(phone, "$t: content corner ($carX, $carY) must map")
                assertClose(expected.first, phone!!.x, 0.5, "$t corner x")
                assertClose(expected.second, phone.y, 0.5, "$t corner y")
            }
        }
    }

    @Test
    fun `the centre of the car screen is the centre of the phone screen`() {
        for (t in transforms()) {
            val phone = t.toPhone(t.carWidth / 2.0, t.carHeight / 2.0)
            assertNotNull(phone, "$t: the centre is never in a bar")
            assertClose(t.phoneWidth / 2.0, phone!!.x, 0.5, "$t centre x")
            assertClose(t.phoneHeight / 2.0, phone.y, 0.5, "$t centre y")
        }
    }

    // --- the bars ------------------------------------------------------------

    /**
     * The point of the whole class. A clamping transform would return the nearest
     * edge here, so a palm on the blank strip would press whatever the phone is
     * drawing at x=0 — usually a back button or a list item.
     */
    @Test
    fun `points inside a bar map to nothing`() {
        for (t in transforms()) {
            val rect = t.contentRect
            if (t.pillarboxed) {
                // A whole pixel inside the bar, so the half-pixel edge tolerance
                // cannot account for it.
                assertNull(t.toPhone(rect.left - 1.5, t.carHeight / 2.0), "$t left bar")
                assertNull(t.toPhone(rect.right + 1.5, t.carHeight / 2.0), "$t right bar")
                assertNull(t.toPhone(0.0, 0.0), "$t top-left corner is bar")
            }
            if (t.letterboxed) {
                assertNull(t.toPhone(t.carWidth / 2.0, rect.top - 1.5), "$t top bar")
                assertNull(t.toPhone(t.carWidth / 2.0, rect.bottom + 1.5), "$t bottom bar")
            }
        }
    }

    /**
     * Concrete numbers, so a regression is legible without recomputing the
     * algebra: 1080x2400 mirrored onto 800x480 scales by 480/2400 = 0.2, giving a
     * 216-wide strip of image with 292 blank pixels on each side.
     */
    @Test
    fun `a portrait phone on an 800x480 panel is pillarboxed as computed by hand`() {
        val t = TouchTransform(carWidth = 800, carHeight = 480, phoneWidth = 1080, phoneHeight = 2400)

        assertEquals(0.2, t.scale, 1e-12)
        assertEquals(216.0, t.contentRect.width, 1e-9)
        assertEquals(480.0, t.contentRect.height, 1e-9)
        assertEquals(292.0, t.contentRect.left, 1e-9)
        assertEquals(0.0, t.contentRect.top, 1e-9)
        assertTrue(t.pillarboxed)
        assertFalse(t.letterboxed)

        // 292 is the first column of image; 291 is the last column of bar.
        assertNull(t.toPhone(291, 240))
        assertNotNull(t.toPhone(292, 240), "the first image column must be live")
        assertNotNull(t.toPhone(507, 240), "the last image column must be live")
        assertNull(t.toPhone(509, 240))

        // Centre of the image, mid-height.
        val centre = t.toPhone(400, 240)
        assertNotNull(centre, "centre")
        assertClose(540.0, centre!!.x, 0.5, "centre x")
        assertClose(1200.0, centre.y, 0.5, "centre y")
    }

    @Test
    fun `a landscape phone on a tall-ish panel is letterboxed as computed by hand`() {
        // 2400x1080 (2.22:1) inside 1024x600 (1.71:1): width binds, scale =
        // 1024/2400, content height = 1080 * that = 460.8, bars of 69.6.
        val t = TouchTransform(carWidth = 1024, carHeight = 600, phoneWidth = 2400, phoneHeight = 1080)

        assertClose(1024.0 / 2400.0, t.scale, 1e-12, "scale")
        assertClose(1024.0, t.contentRect.width, 1e-9, "content width")
        assertClose(460.8, t.contentRect.height, 1e-9, "content height")
        assertClose(69.6, t.contentRect.top, 1e-9, "top bar")
        assertTrue(t.letterboxed)
        assertFalse(t.pillarboxed)

        assertNull(t.toPhone(512, 60), "inside the top bar")
        assertNotNull(t.toPhone(512, 300), "inside the image")
    }

    // --- whole events --------------------------------------------------------

    @Test
    fun `a touch entirely inside the image maps every pointer and keeps ids`() {
        val t = TouchTransform(800, 480, 1080, 2400)
        val touch = CarInputEvent.Touch(
            timestampMicros = 99,
            action = TouchAction.MOVED,
            actionIndex = 0,
            pointers = listOf(TouchPointer(id = 7, x = 400, y = 240), TouchPointer(id = 9, x = 350, y = 100)),
            surface = TouchSurface.TOUCHSCREEN,
        )

        val mapped = t.map(touch)
        assertNotNull(mapped, "both pointers are on the image")
        assertEquals(listOf(7, 9), mapped!!.pointers.map { it.id })
        assertEquals(TouchAction.MOVED, mapped.action)
        assertEquals(99L, mapped.timestampMicros)
        assertEquals(0, mapped.actionIndex)
        assertClose(540.0, mapped.pointers[0].x, 0.5, "pointer 7 x")
    }

    @Test
    fun `a touch whose changed pointer is in a bar is dropped whole`() {
        val t = TouchTransform(800, 480, 1080, 2400)
        val touch = CarInputEvent.Touch(
            timestampMicros = 1,
            action = TouchAction.DOWN,
            actionIndex = 0,
            pointers = listOf(TouchPointer(id = 0, x = 20, y = 240)),
            surface = TouchSurface.TOUCHSCREEN,
        )
        assertNull(t.map(touch), "a DOWN in the bar must not start a gesture")
    }

    @Test
    fun `a lift whose changed pointer is in a bar is delivered clamped, not dropped`() {
        // The finger began on the image, dragged into the pillarbox bar, and
        // lifted there. Dropping this UP -- as a DOWN in the same place is
        // dropped -- would leave the injected gesture held on the phone forever.
        val t = TouchTransform(800, 480, 1080, 2400)
        val up = CarInputEvent.Touch(
            timestampMicros = 9,
            action = TouchAction.UP,
            actionIndex = 0,
            pointers = listOf(TouchPointer(id = 0, x = 20, y = 240)), // deep in the left bar
            surface = TouchSurface.TOUCHSCREEN,
        )

        val mapped = t.map(up)
        assertNotNull(mapped, "an UP must be delivered even from a bar so the gesture can end")
        assertEquals(TouchAction.UP, mapped!!.action)
        // Clamped to the image's left edge, not placed hundreds of pixels into it.
        assertEquals(0.0, mapped.pointers.single().x, 1e-9)
    }

    /**
     * A second finger resting on the bar must not shift `actionIndex`: the event
     * is still about the finger it was about, which is now at a different list
     * position.
     */
    @Test
    fun `a bystander pointer in a bar is dropped and the action index is rebased`() {
        val t = TouchTransform(800, 480, 1080, 2400)
        val touch = CarInputEvent.Touch(
            timestampMicros = 5,
            action = TouchAction.POINTER_UP,
            actionIndex = 1,
            pointers = listOf(
                TouchPointer(id = 0, x = 20, y = 240),   // on the left bar
                TouchPointer(id = 1, x = 400, y = 240),  // on the image
            ),
            surface = TouchSurface.TOUCHSCREEN,
        )

        val mapped = t.map(touch)
        assertNotNull(mapped, "the changed pointer is on the image")
        assertEquals(listOf(1), mapped!!.pointers.map { it.id })
        assertEquals(0, mapped.actionIndex, "index rebased onto the surviving list")
        assertEquals(1, mapped.changedPointer!!.id)
    }

    @Test
    fun `an action index the head unit sent out of range is refused`() {
        val t = TouchTransform(800, 480, 1080, 2400)
        val touch = CarInputEvent.Touch(
            timestampMicros = 1,
            action = TouchAction.POINTER_DOWN,
            actionIndex = 3,
            pointers = listOf(TouchPointer(id = 0, x = 400, y = 240)),
            surface = TouchSurface.TOUCHSCREEN,
        )
        assertNull(t.map(touch))
    }
}

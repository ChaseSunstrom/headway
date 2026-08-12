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

package dev.headway.input

import android.accessibilityservice.GestureDescription
import android.graphics.PathMeasure
import android.view.ViewConfiguration
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.headway.protocol.channel.PhonePointer
import dev.headway.protocol.channel.PhoneTouch
import dev.headway.protocol.channel.TouchAction
import dev.headway.protocol.channel.TouchSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.hypot

/**
 * Runs [GestureBuilder] on a real device and reads the resulting
 * `GestureDescription` back through its public accessors.
 *
 * This has to be an instrumentation test: `GestureDescription`,
 * `StrokeDescription` and `Path` are all platform classes with native backing,
 * and `StrokeDescription`'s constructor validation — zero duration, empty path,
 * negative bounds, more than one contour — is the part most likely to reject
 * what the builder produces. A JVM test with a mocked framework would assert the
 * builder's intent instead of the platform's verdict on it.
 *
 * Nothing here enables an `AccessibilityService`. Building and inspecting a
 * gesture needs no permission at all; only dispatching one does.
 */
@RunWith(AndroidJUnit4::class)
class GestureBuilderTest {

    @Test
    fun tapIsASingleZeroLengthStroke() {
        val builder = GestureBuilder()
        assertNull("a DOWN alone cannot be dispatched", builder.accept(down(0, at(100.0, 200.0))))

        val gesture = builder.accept(up(40, at(100.0, 200.0)))
        assertNotNull("the UP should complete the gesture", gesture)
        gesture!!

        assertEquals(CarGestureKind.TAP, gesture.kind)
        assertEquals(1, gesture.description.strokeCount)
        assertFalse("a tap is complete on its own", gesture.continues)

        val stroke = gesture.description.getStroke(0)
        assertEquals(0L, stroke.startTime)
        assertFalse(stroke.willContinue())
        // The platform reads a zero-length path as a tap location, which is what
        // a press with no travel is.
        assertEquals(0f, PathMeasure(stroke.path, false).length, 0.01f)
    }

    @Test
    fun tapShorterThanTheTapTimeoutIsStretchedRatherThanRejected() {
        // Head units send the DOWN and the UP of a tap with one timestamp, so the
        // measured duration is 0 and StrokeDescription would throw on it.
        val builder = GestureBuilder()
        builder.accept(down(1_000, at(50.0, 60.0)))
        val gesture = builder.accept(up(1_000, at(50.0, 60.0)))

        assertNotNull(gesture)
        val stroke = gesture!!.description.getStroke(0)
        assertTrue(
            "a same-timestamp tap became ${stroke.duration} ms",
            stroke.duration >= ViewConfiguration.getTapTimeout().toLong(),
        )
    }

    @Test
    fun longPressIsHeldPastTheLongPressTimeout() {
        val builder = GestureBuilder()
        val config = builder.config
        builder.accept(down(0, at(300.0, 400.0)))
        val gesture = builder.accept(up(700, at(300.0, 400.0)))

        assertNotNull(gesture)
        gesture!!
        assertEquals(CarGestureKind.LONG_PRESS, gesture.kind)
        assertEquals(1, gesture.description.strokeCount)

        val stroke = gesture.description.getStroke(0)
        assertTrue(
            "held for ${stroke.duration} ms, target's timer is ${config.longPressMillis} ms",
            stroke.duration > config.longPressMillis,
        )
        assertEquals(0f, PathMeasure(stroke.path, false).length, 0.01f)
    }

    @Test
    fun dragKeepsEveryVertexOfTheMoveStream() {
        val builder = GestureBuilder()
        builder.accept(down(0, at(100.0, 100.0)))
        builder.accept(moved(25, at(200.0, 100.0)))
        builder.accept(moved(50, at(300.0, 100.0)))
        builder.accept(moved(75, at(300.0, 200.0)))
        builder.accept(moved(100, at(300.0, 300.0)))
        val gesture = builder.accept(up(100, at(300.0, 300.0)))

        assertNotNull(gesture)
        gesture!!
        assertEquals(CarGestureKind.DRAG, gesture.kind)
        assertEquals(1, gesture.description.strokeCount)
        assertEquals("every distinct sample should be a vertex", 5, gesture.pointCount)

        val stroke = gesture.description.getStroke(0)
        val length = PathMeasure(stroke.path, false).length
        val asTheCrowFlies = hypot(300.0 - 100.0, 300.0 - 100.0).toFloat()

        // The corner is the point: a two-point path from the first sample to the
        // last would measure the straight line, so a path measurably longer than
        // that can only have come from the intermediate samples.
        assertTrue(
            "path measured $length px against a $asTheCrowFlies px straight line",
            length > asTheCrowFlies * 1.3f,
        )
        assertEquals(400f, length, 1f)

        val vertices = stroke.path.approximate(0.5f).size / 3
        assertTrue("path approximated to $vertices points", vertices >= 3)

        assertTrue("duration was ${stroke.duration} ms for a 100 ms drag", stroke.duration in 90L..140L)
    }

    @Test
    fun flingIsNotFlattenedIntoATap() {
        // The regression this whole class exists to prevent. A fling collapsed to
        // its endpoints, or to its DOWN point, still "works" on buttons and
        // silently breaks every scrollable surface in the car.
        val builder = GestureBuilder()
        builder.accept(down(0, at(540.0, 1600.0)))
        var y = 1600.0
        var t = 0L
        var step = 20.0
        repeat(6) {
            t += 16
            y -= step
            step *= 1.6
            builder.accept(moved(t, at(540.0, y)))
        }
        val gesture = builder.accept(up(t, at(540.0, y)))

        assertNotNull(gesture)
        gesture!!
        assertEquals(CarGestureKind.DRAG, gesture.kind)

        val stroke = gesture.description.getStroke(0)
        val length = PathMeasure(stroke.path, false).length
        assertTrue("a fling must not become a zero-length path", length > 0f)
        assertEquals("the whole travel must survive", (1600.0 - y).toFloat(), length, 1f)
        assertTrue("only ${gesture.pointCount} vertices survived", gesture.pointCount > 2)
    }

    @Test
    fun dragPastTheSegmentBudgetIsContinuedRatherThanRestarted() {
        val builder = GestureBuilder(GestureConfig(segmentBudgetMillis = 100L))
        val gestures = ArrayList<CarGesture>()

        gestures.addNotNull(builder.accept(down(0, at(100.0, 100.0))))
        var t = 0L
        var x = 100.0
        while (t < 500) {
            t += 50
            x += 40.0
            gestures.addNotNull(builder.accept(moved(t, at(x, 100.0))))
        }
        gestures.addNotNull(builder.accept(up(t, at(x, 100.0))))

        assertTrue("a 500 ms drag on a 100 ms budget produced ${gestures.size} slices", gestures.size >= 2)

        val chains = gestures.map { it.chainId }.toSet()
        assertEquals("all slices belong to one physical gesture", 1, chains.size)
        assertEquals(
            "slice indices should run 0..n",
            (gestures.indices).toList(),
            gestures.map { it.sliceIndex },
        )

        for (gesture in gestures.dropLast(1)) {
            assertEquals(1, gesture.description.strokeCount)
            assertTrue(
                "slice ${gesture.sliceIndex} must keep the finger down",
                gesture.description.getStroke(0).willContinue(),
            )
            assertTrue(gesture.continues)
        }
        val last = gestures.last()
        assertFalse("the final slice lifts the finger", last.description.getStroke(0).willContinue())
        assertFalse(last.continues)

        // Timing comes from the event stream, so the slices must add up to the
        // real gesture. A budget-driven split that invented its own durations
        // would replay the drag at the wrong speed.
        val total = gestures.sumOf { it.description.getStroke(0).duration }
        assertTrue("slices summed to $total ms for a $t ms drag", total in (t - 60)..(t + 60))
    }

    @Test
    fun stationaryHoldIsNotSlicedIntoContinuations() {
        // A held finger has nothing to show mid-gesture, and slicing it would hand
        // the platform a chain of zero-length paths.
        val builder = GestureBuilder(GestureConfig(segmentBudgetMillis = 50L))
        val gestures = ArrayList<CarGesture>()

        gestures.addNotNull(builder.accept(down(0, at(640.0, 360.0))))
        for (t in 50L..700L step 50L) {
            gestures.addNotNull(builder.accept(moved(t, at(640.0, 360.0))))
        }
        gestures.addNotNull(builder.accept(up(700, at(640.0, 360.0))))

        assertEquals("a hold is one gesture, not one per budget window", 1, gestures.size)
        assertEquals(CarGestureKind.LONG_PRESS, gestures[0].kind)
        assertFalse(gestures[0].continues)
    }

    @Test
    fun multiTouchGetsOneStrokePerFinger() {
        val builder = GestureBuilder()
        builder.accept(down(0, at(200.0, 200.0)))
        builder.accept(
            PhoneTouch(
                timestampMicros = 50_000,
                action = TouchAction.POINTER_DOWN,
                actionIndex = 1,
                pointers = listOf(PhonePointer(0, 200.0, 200.0), PhonePointer(1, 800.0, 800.0)),
                surface = TouchSurface.TOUCHSCREEN,
            )
        )
        builder.accept(
            PhoneTouch(
                timestampMicros = 100_000,
                action = TouchAction.MOVED,
                actionIndex = 0,
                pointers = listOf(PhonePointer(0, 260.0, 200.0), PhonePointer(1, 740.0, 800.0)),
                surface = TouchSurface.TOUCHSCREEN,
            )
        )
        val gesture = builder.accept(
            PhoneTouch(
                timestampMicros = 150_000,
                action = TouchAction.UP,
                actionIndex = 0,
                pointers = listOf(PhonePointer(0, 300.0, 200.0), PhonePointer(1, 700.0, 800.0)),
                surface = TouchSurface.TOUCHSCREEN,
            )
        )

        assertNotNull(gesture)
        gesture!!
        assertEquals(CarGestureKind.MULTI_TOUCH, gesture.kind)
        assertEquals(2, gesture.description.strokeCount)
        assertEquals(2, gesture.pointerCount)

        // The second finger landed 50 ms into the gesture and its stroke has to
        // say so, or a pinch replays as two fingers arriving together.
        val startTimes = (0 until gesture.description.strokeCount)
            .map { gesture.description.getStroke(it).startTime }
            .toSet()
        assertEquals(setOf(0L, 50L), startTimes)

        for (index in 0 until gesture.description.strokeCount) {
            val stroke = gesture.description.getStroke(index)
            assertFalse(stroke.willContinue())
            assertTrue("stroke $index has no travel", PathMeasure(stroke.path, false).length > 0f)
        }
    }

    @Test
    fun neverExceedsThePlatformStrokeCeiling() {
        val builder = GestureBuilder()
        val pointers = ArrayList<PhonePointer>()
        pointers += PhonePointer(0, 10.0, 10.0)
        builder.accept(down(0, pointers.toList()))

        for (id in 1 until GestureDescription.getMaxStrokeCount() + 4) {
            pointers += PhonePointer(id, 10.0 + id * 20.0, 10.0)
            builder.accept(
                PhoneTouch(
                    timestampMicros = id * 5_000L,
                    action = TouchAction.POINTER_DOWN,
                    actionIndex = id,
                    pointers = pointers.toList(),
                    surface = TouchSurface.TOUCHSCREEN,
                )
            )
        }
        val gesture = builder.accept(
            PhoneTouch(
                timestampMicros = 200_000,
                action = TouchAction.UP,
                actionIndex = 0,
                pointers = pointers.toList(),
                surface = TouchSurface.TOUCHSCREEN,
            )
        )

        assertNotNull(gesture)
        gesture!!
        assertEquals(GestureDescription.getMaxStrokeCount(), gesture.description.strokeCount)
        for (index in 0 until gesture.description.strokeCount) {
            val stroke = gesture.description.getStroke(index)
            assertTrue(
                "stroke $index ends at ${stroke.startTime + stroke.duration} ms",
                stroke.startTime + stroke.duration <= GestureDescription.getMaxGestureDuration(),
            )
        }
    }

    @Test
    fun flushLiftsAFingerLeftDownByAnInterruptedDrag() {
        val builder = GestureBuilder(GestureConfig(segmentBudgetMillis = 50L))
        builder.accept(down(0, at(100.0, 100.0)))
        val mid = builder.accept(moved(60, at(400.0, 100.0)))

        assertNotNull("the budget should have produced a continuing slice", mid)
        assertTrue(mid!!.continues)
        assertTrue(builder.inProgress)

        val closing = builder.flush()
        assertNotNull("flush must terminate the chain", closing)
        assertFalse(closing!!.continues)
        assertFalse(closing.description.getStroke(0).willContinue())
        assertEquals(mid.chainId, closing.chainId)
        assertFalse(builder.inProgress)
    }

    @Test
    fun eventsWithoutADownAreIgnored() {
        // Happens whenever the DOWN landed in a letterbox bar: TouchTransform drops
        // it, and inventing an origin for the MOVE would drag from a point the
        // driver never touched.
        val builder = GestureBuilder()
        assertNull(builder.accept(moved(10, at(100.0, 100.0))))
        assertNull(builder.accept(up(20, at(120.0, 100.0))))
        assertFalse(builder.inProgress)
    }

    @Test
    fun touchpadEventsAreIgnored() {
        val builder = GestureBuilder()
        val touch = PhoneTouch(
            timestampMicros = 0,
            action = TouchAction.DOWN,
            actionIndex = 0,
            pointers = listOf(PhonePointer(0, 10.0, 10.0)),
            surface = TouchSurface.TOUCHPAD,
        )
        assertNull(builder.accept(touch))
        assertFalse(builder.inProgress)
    }

    // --- helpers ------------------------------------------------------------

    private fun at(x: Double, y: Double): List<PhonePointer> = listOf(PhonePointer(0, x, y))

    private fun down(atMillis: Long, pointers: List<PhonePointer>) =
        PhoneTouch(atMillis * 1_000L, TouchAction.DOWN, 0, pointers, TouchSurface.TOUCHSCREEN)

    private fun moved(atMillis: Long, pointers: List<PhonePointer>) =
        PhoneTouch(atMillis * 1_000L, TouchAction.MOVED, 0, pointers, TouchSurface.TOUCHSCREEN)

    private fun up(atMillis: Long, pointers: List<PhonePointer>) =
        PhoneTouch(atMillis * 1_000L, TouchAction.UP, 0, pointers, TouchSurface.TOUCHSCREEN)

    private fun MutableList<CarGesture>.addNotNull(gesture: CarGesture?) {
        if (gesture != null) add(gesture)
    }
}

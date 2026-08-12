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

package dev.headway.emulator

import dev.headway.protocol.channel.CarInputEvent
import dev.headway.protocol.channel.InputChannel
import dev.headway.protocol.channel.InputChannelMessage
import dev.headway.protocol.channel.InputKeyCodes
import dev.headway.protocol.channel.PhoneTouch
import dev.headway.protocol.channel.TouchAction
import dev.headway.protocol.channel.TouchTransform
import dev.headway.protocol.framing.ChannelId
import dev.headway.protocol.io.FramedConnection
import dev.headway.protocol.session.AapSession
import dev.headway.protocol.session.PhoneIdentity
import dev.headway.transport.LoopbackTransport
import dev.headway.transport.tls.AapTls
import dev.headway.transport.tls.TlsSession
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **Phase 3 acceptance — the protocol half.**
 *
 * CLAUDE.md:
 *
 * > **Phase 3 — Input.** Touch transform + accessibility gesture injection, key
 * > events. *Accepted when:* scripted emulator touches operate a real
 * > third-party app (e.g., open an app from the launcher grid, scroll a list,
 * > type via drag on keyboard) hands-free.
 *
 * This covers everything up to the Android boundary: the emulator scripts real
 * gestures onto a real (TLS-encrypted, framed) input channel, and the phone side
 * decodes them and maps them through [TouchTransform] onto phone coordinates.
 * That is **Tier A** — executed, asserted, on real bytes.
 *
 * The last hop, handing the resulting [PhoneTouch] to
 * `AccessibilityService.dispatchGesture`, is **Tier C**: it compiles against the
 * real Android SDK and cannot be executed here, because there is no device and
 * no accelerated emulator in the build environment (`BLOCKERS.md` B-001, and the
 * tier table in `docs/completion-plan.md`). "Scripted touches operate a real
 * third-party app" therefore remains unproven until someone runs it on hardware.
 * Nothing in this file should be read as evidence for it.
 *
 * The car here is the emulator's default 800x480 panel and the phone is
 * 1080x2400, so the mirrored image occupies 216 of the 800 columns and the
 * remaining 584 are pillarbox bar. Two thirds of the car screen is bar, which is
 * what makes the transform worth testing rather than assuming.
 */
class Phase3InputAcceptanceTest {

    private val carWidth = 800
    private val carHeight = 480
    private val phoneWidth = 1080
    private val phoneHeight = 2400

    /**
     * The phone-side and head-unit-side halves of a live input channel, after a
     * full bring-up.
     */
    private class Harness(
        val input: InputChannel,
        val source: EmulatedInputSource,
        val transform: TouchTransform,
    )

    /**
     * Runs a real handshake over the loopback transport, opens the input channel
     * only, and hands [body] both ends of it.
     *
     * The channel really is encrypted: `FramedConnection.send` refuses an
     * encrypted message before TLS is up, so this cannot accidentally degrade to
     * plaintext.
     */
    private fun <T> withInputChannel(body: suspend CoroutineScope.(Harness) -> T): T = runBlocking {
        LoopbackTransport.pair().use { pair ->
            val phoneConnection = FramedConnection(pair.phone)
            val headUnitConnection = FramedConnection(pair.headUnit)

            val headUnit = EmulatedHeadUnit(
                connection = headUnitConnection,
                tls = TlsSession(AapTls.headUnitEngine()),
                config = HeadUnitConfig(touchWidth = carWidth, touchHeight = carHeight),
            )
            val session = AapSession(
                connection = phoneConnection,
                tls = TlsSession(AapTls.phoneEngine()),
                identity = PhoneIdentity(deviceName = "Headway Test"),
            )

            val wanted = listOf(ChannelId.INPUT_SOURCE.id)
            val phoneSide = async(Dispatchers.IO) { session.connect { wanted } }
            val headUnitSide = async(Dispatchers.IO) { headUnit.run(channelOpens = wanted.size) }
            withTimeout(TIMEOUT_MILLIS) { phoneSide.await() }
            withTimeout(TIMEOUT_MILLIS) { headUnitSide.await() }

            body(
                Harness(
                    input = InputChannel(phoneConnection),
                    source = EmulatedInputSource(
                        connection = headUnitConnection,
                        displayWidth = carWidth,
                        displayHeight = carHeight,
                    ),
                    // The emulator advertises the same geometry for its video sink
                    // and its touchscreen, so either reads the same here; the video
                    // resolution is the one that is correct in general.
                    transform = TouchTransform(carWidth, carHeight, phoneWidth, phoneHeight),
                )
            )
        }
    }

    /** Reads exactly [reports] input reports and flattens them into events. */
    private suspend fun InputChannel.collect(reports: Int): List<CarInputEvent> {
        val events = mutableListOf<CarInputEvent>()
        repeat(reports) {
            val message = receiveMessage()
            events += assertInstanceOf(InputChannelMessage.Report::class.java, message).events
        }
        return events
    }

    private fun List<CarInputEvent>.touches(): List<CarInputEvent.Touch> =
        map { assertInstanceOf(CarInputEvent.Touch::class.java, it) }

    // --- key binding ---------------------------------------------------------

    @Test
    fun `the phone binds the keycodes the head unit advertised`() = withInputChannel { h ->
        val answering = async(Dispatchers.IO) { h.source.answerKeyBinding() }

        h.input.requestKeyBinding(EmulatedHeadUnit.SUPPORTED_KEYCODES)
        val response = withTimeout(TIMEOUT_MILLIS) { h.input.receiveMessage() }
        val requested = withTimeout(TIMEOUT_MILLIS) { answering.await() }

        assertEquals(EmulatedHeadUnit.SUPPORTED_KEYCODES, requested)
        assertTrue(
            assertInstanceOf(InputChannelMessage.KeyBindingResult::class.java, response).bound,
            "every requested keycode was advertised",
        )
    }

    /**
     * There is no partial bind: openauto rejects the whole request on the first
     * keycode it never advertised. A phone that asks for a superset gets nothing,
     * not a subset.
     */
    @Test
    fun `asking for an unadvertised keycode fails the whole binding`() = withInputChannel { h ->
        val answering = async(Dispatchers.IO) { h.source.answerKeyBinding() }

        h.input.requestKeyBinding(listOf(InputKeyCodes.SEARCH, InputKeyCodes.NAVIGATION))
        val response = withTimeout(TIMEOUT_MILLIS) { h.input.receiveMessage() }
        withTimeout(TIMEOUT_MILLIS) { answering.await() }

        val result = assertInstanceOf(InputChannelMessage.KeyBindingResult::class.java, response)
        assertFalse(result.bound)
        assertEquals(InputChannelMessage.KeyBindingResult.STATUS_KEYCODE_NOT_BOUND, result.status)
    }

    // --- taps ----------------------------------------------------------------

    @Test
    fun `a tap arrives as a down and an up at the transformed coordinates`() = withInputChannel { h ->
        // Dead centre of the mirrored image.
        val sending = async(Dispatchers.IO) { h.source.tap(x = 400, y = 240) }
        val touches = withTimeout(TIMEOUT_MILLIS) { h.input.collect(2) }.touches()
        withTimeout(TIMEOUT_MILLIS) { sending.await() }

        assertEquals(listOf(TouchAction.DOWN, TouchAction.UP), touches.map { it.action })

        val mapped = touches.map { h.transform.map(it) }
        assertTrue(mapped.all { it != null }, "the centre is never in a bar")
        for (touch in mapped) {
            assertEquals(1, touch!!.pointers.size)
            assertClose(phoneWidth / 2.0, touch.pointers[0].x, "tap x")
            assertClose(phoneHeight / 2.0, touch.pointers[0].y, "tap y")
        }

        // A tap is a tap because there is nothing between the down and the up.
        assertTrue(touches.none { it.action == TouchAction.MOVED })
        assertEquals(
            EmulatedInputSource.TAP_DWELL_MICROS,
            touches[1].timestampMicros - touches[0].timestampMicros,
        )
    }

    @Test
    fun `a long press is distinguishable from a tap only by its dwell`() = withInputChannel { h ->
        val sending = async(Dispatchers.IO) { h.source.longPress(x = 400, y = 100) }
        val touches = withTimeout(TIMEOUT_MILLIS) { h.input.collect(2) }.touches()
        withTimeout(TIMEOUT_MILLIS) { sending.await() }

        assertEquals(listOf(TouchAction.DOWN, TouchAction.UP), touches.map { it.action })
        val dwell = touches[1].timestampMicros - touches[0].timestampMicros
        assertEquals(EmulatedInputSource.LONG_PRESS_MICROS, dwell)
        assertTrue(
            dwell > EmulatedInputSource.TAP_DWELL_MICROS,
            "the dwell is the only signal the phone gets",
        )
    }

    // --- drags ---------------------------------------------------------------

    /**
     * The failure this guards against: a receiver that treats each report as an
     * independent event, or that drops MOVED, turns a scroll into a click on
     * whatever the finger started over. The drag must survive as a stream.
     */
    @Test
    fun `a drag arrives as a stream of moves and is not collapsed into taps`() = withInputChannel { h ->
        val steps = 12
        val expected = steps + 2
        val sending = async(Dispatchers.IO) {
            h.source.drag(fromX = 400, fromY = 400, toX = 400, toY = 80, steps = steps)
        }
        val touches = withTimeout(TIMEOUT_MILLIS) { h.input.collect(expected) }.touches()
        assertEquals(expected, withTimeout(TIMEOUT_MILLIS) { sending.await() })

        assertEquals(TouchAction.DOWN, touches.first().action)
        assertEquals(TouchAction.UP, touches.last().action)
        assertEquals(steps, touches.count { it.action == TouchAction.MOVED })
        assertEquals(1, touches.count { it.action == TouchAction.DOWN }, "exactly one gesture started")
        assertEquals(1, touches.count { it.action == TouchAction.UP }, "exactly one gesture ended")

        // Timestamps must be strictly increasing, or a receiver that sorts by
        // time reorders the path.
        val stamps = touches.map { it.timestampMicros }
        assertEquals(stamps.sorted(), stamps)
        assertEquals(stamps.distinct().size, stamps.size)
        assertEquals(
            EmulatedInputSource.SAMPLE_INTERVAL_MICROS * (steps + 1),
            stamps.last() - stamps.first(),
            "the gesture keeps its scripted duration regardless of how fast CI runs",
        )

        // The path is monotonic upwards in car space and stays so after mapping.
        val mapped = touches.map { h.transform.map(it) }
        assertTrue(mapped.all { it != null }, "the whole path is on the mirrored image")
        val ys = mapped.map { it!!.pointers.single().y }
        assertTrue(ys.zipWithNext().all { (a, b) -> b <= a }, "y decreases monotonically: $ys")

        // A 320 px drag on an 800x480 panel is 1600 px on a 1080x2400 phone,
        // because the mirrored image is scaled by 0.2.
        assertClose(1600.0, ys.first() - ys.last(), "mapped drag distance", tolerance = 2.0)
    }

    /**
     * A fling has the same wire shape as a drag; what differs is the spacing of
     * the samples. A receiver computing velocity from the last two samples must
     * see the deceleration.
     */
    @Test
    fun `a fling decelerates towards its end point`() = withInputChannel { h ->
        val steps = 12
        val sending = async(Dispatchers.IO) {
            h.source.fling(fromX = 100, fromY = 400, toX = 700, toY = 400, steps = steps)
        }
        val touches = withTimeout(TIMEOUT_MILLIS) { h.input.collect(steps + 2) }.touches()
        withTimeout(TIMEOUT_MILLIS) { sending.await() }

        val moves = touches.filter { it.action == TouchAction.MOVED }
        val gaps = moves.map { it.pointers.single().x }.zipWithNext { a, b -> b - a }
        assertTrue(gaps.first() > gaps.last(), "samples bunch up at the end: $gaps")
        assertTrue(gaps.all { it >= 0 }, "a fling never reverses: $gaps")
    }

    /**
     * Two fingers. The action sequence, the `action_index` on the pointer-specific
     * actions, and the stability of `pointer_id` across the whole gesture are the
     * three things a receiver needs to track a multi-touch, and all three are
     * easy to lose in a decoder that flattens `pointer_data` to a single point.
     */
    @Test
    fun `a two-finger drag keeps both pointers and their ids`() = withInputChannel { h ->
        val steps = 6
        val expected = steps + 4
        val sending = async(Dispatchers.IO) {
            h.source.twoFingerDrag(
                first = 350 to 400,
                firstTo = 350 to 120,
                second = 450 to 400,
                secondTo = 450 to 120,
                steps = steps,
            )
        }
        val touches = withTimeout(TIMEOUT_MILLIS) { h.input.collect(expected) }.touches()
        assertEquals(expected, withTimeout(TIMEOUT_MILLIS) { sending.await() })

        assertEquals(TouchAction.DOWN, touches[0].action)
        assertEquals(TouchAction.POINTER_DOWN, touches[1].action)
        assertEquals(TouchAction.POINTER_UP, touches[expected - 2].action)
        assertEquals(TouchAction.UP, touches[expected - 1].action)
        assertEquals(steps, touches.count { it.action == TouchAction.MOVED })

        // action_index names the finger that changed, not the first one.
        assertEquals(1, touches[1].actionIndex)
        assertEquals(EmulatedInputSource.SECONDARY, touches[1].changedPointer!!.id)
        assertEquals(1, touches[expected - 2].actionIndex)

        // Ids are stable, so the two paths can be told apart across reports.
        for (touch in touches.filter { it.action == TouchAction.MOVED }) {
            assertEquals(
                listOf(EmulatedInputSource.PRIMARY, EmulatedInputSource.SECONDARY),
                touch.pointers.map { it.id },
            )
        }

        val mapped = touches.map { h.transform.map(it)!! }
        val moved = mapped.filter { it.action == TouchAction.MOVED }
        assertTrue(
            moved.all { it.pointers[0].x < it.pointers[1].x },
            "the left finger stays left of the right one through the transform",
        )
    }

    // --- the letterbox bars --------------------------------------------------

    /**
     * The bezel-adjacent black strip must be inert. A transform that clamped
     * would map this touch to x=0 on the phone, which on most launchers is a back
     * gesture or the first item of a list — so a passenger resting a hand on the
     * blank part of the screen would operate the phone.
     */
    @Test
    fun `a touch on the pillarbox bar is dropped, not clamped onto the image edge`() =
        withInputChannel { h ->
            assertTrue(h.transform.pillarboxed, "a portrait phone on this panel must pillarbox")

            val sending = async(Dispatchers.IO) { h.source.tap(x = 40, y = 240) }
            val touches = withTimeout(TIMEOUT_MILLIS) { h.input.collect(2) }.touches()
            withTimeout(TIMEOUT_MILLIS) { sending.await() }

            // The events arrive intact -- the head unit really did send them.
            assertEquals(listOf(TouchAction.DOWN, TouchAction.UP), touches.map { it.action })
            assertEquals(40, touches[0].pointers.single().x)

            // ... and the transform refuses to place them.
            for (touch in touches) {
                assertNull(h.transform.map(touch), "x=40 is 250 px inside the left bar")
            }
        }

    @Test
    fun `the first column of the mirrored image is live`() = withInputChannel { h ->
        val left = h.transform.contentRect.left.toInt() + 1
        val sending = async(Dispatchers.IO) { h.source.tap(x = left, y = 240) }
        val touches = withTimeout(TIMEOUT_MILLIS) { h.input.collect(2) }.touches()
        withTimeout(TIMEOUT_MILLIS) { sending.await() }

        val mapped = h.transform.map(touches[0])
        assertNotNull(mapped, "one pixel inside the image must map")
        // One car pixel is five phone pixels at this scale, so the first live
        // column lands within a handful of pixels of the phone's own edge.
        val x = mapped!!.pointers.single().x
        assertTrue(x in 0.0..10.0, "first live column mapped to x=$x, expected near 0")
    }

    // --- keys and the knob ---------------------------------------------------

    @Test
    fun `a steering-wheel key arrives as a down and an up`() = withInputChannel { h ->
        val sending = async(Dispatchers.IO) { h.source.keyPress(InputKeyCodes.SEARCH) }
        val events = withTimeout(TIMEOUT_MILLIS) { h.input.collect(2) }
        withTimeout(TIMEOUT_MILLIS) { sending.await() }

        val keys = events.map { assertInstanceOf(CarInputEvent.Key::class.java, it) }
        assertEquals(listOf(true, false), keys.map { it.down })
        assertTrue(keys.all { it.keycode == InputKeyCodes.SEARCH })
        assertTrue(
            keys[1].timestampMicros > keys[0].timestampMicros,
            "openauto stamps press and release separately; aa-proxy-rs reuses one stamp",
        )
    }

    /**
     * The knob is the standing trap in this channel: it never appears as a
     * `KeyEvent`, so a phone that only reads `key_event` sees a dead knob and no
     * error to explain it.
     */
    @Test
    fun `the rotary knob arrives as relative events, one per detent`() = withInputChannel { h ->
        val sending = async(Dispatchers.IO) { h.source.rotary(detents = -3) }
        val events = withTimeout(TIMEOUT_MILLIS) { h.input.collect(3) }
        assertEquals(3, withTimeout(TIMEOUT_MILLIS) { sending.await() })

        val relatives = events.map { assertInstanceOf(CarInputEvent.Relative::class.java, it) }
        assertEquals(3, relatives.size)
        assertTrue(relatives.all { it.isRotary })
        assertTrue(relatives.all { it.keycode == 65536 })
        assertEquals(listOf(-1, -1, -1), relatives.map { it.delta })
        assertTrue(events.none { it is CarInputEvent.Key }, "the knob is not a key event")
    }

    // --- the whole thing -----------------------------------------------------

    /**
     * One scripted session that walks a launcher grid the way the acceptance
     * criterion describes: press the voice key, scroll a list, tap an item.
     * Everything below Android is exercised end to end; what happens after the
     * final [PhoneTouch] is Tier C.
     */
    @Test
    fun `a scripted session of mixed gestures decodes in order`() = withInputChannel { h ->
        val binding = async(Dispatchers.IO) { h.source.answerKeyBinding() }
        h.input.requestKeyBinding(EmulatedHeadUnit.SUPPORTED_KEYCODES)
        assertTrue(
            assertInstanceOf(
                InputChannelMessage.KeyBindingResult::class.java,
                withTimeout(TIMEOUT_MILLIS) { h.input.receiveMessage() },
            ).bound
        )
        withTimeout(TIMEOUT_MILLIS) { binding.await() }

        val script = async(Dispatchers.IO) {
            var reports = 0
            reports += h.source.keyPress(InputKeyCodes.SEARCH)
            reports += h.source.drag(fromX = 400, fromY = 400, toX = 400, toY = 120, steps = 8)
            reports += h.source.tap(x = 400, y = 200)
            reports
        }
        val expected = 2 + 10 + 2
        val events = withTimeout(TIMEOUT_MILLIS) { h.input.collect(expected) }
        assertEquals(expected, withTimeout(TIMEOUT_MILLIS) { script.await() })

        assertEquals(2, events.count { it is CarInputEvent.Key })
        val touches = events.filterIsInstance<CarInputEvent.Touch>()
        assertEquals(12, touches.size)
        assertEquals(
            listOf(TouchAction.DOWN, TouchAction.UP, TouchAction.DOWN, TouchAction.UP),
            touches.filter { it.action != TouchAction.MOVED }.map { it.action },
            "two gestures, each properly opened and closed",
        )

        // Every event is strictly ordered in time across the whole script.
        val stamps = events.map { it.timestampMicros }
        assertEquals(stamps.sorted(), stamps)
        assertEquals(stamps.distinct().size, stamps.size)

        // And every touch lands somewhere real on the phone.
        val mapped: List<PhoneTouch> = touches.map { h.transform.map(it)!! }
        assertTrue(
            mapped.all { touch ->
                touch.pointers.all { it.x in 0.0..phoneWidth.toDouble() && it.y in 0.0..phoneHeight.toDouble() }
            }
        )
    }

    private fun assertClose(expected: Double, actual: Double, what: String, tolerance: Double = 1.0) {
        assertTrue(
            abs(expected - actual) <= tolerance,
            "$what: expected $expected, got $actual (tolerance $tolerance)",
        )
    }

    private companion object {
        const val TIMEOUT_MILLIS = 60_000L
    }
}

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

import aap_protobuf.service.inputsource.message.InputReportOuterClass.InputReport
import dev.headway.protocol.channel.InputChannel
import dev.headway.protocol.channel.InputChannelMessage
import dev.headway.protocol.channel.InputKeyCodes
import dev.headway.protocol.channel.InputReports
import dev.headway.protocol.channel.TouchAction
import dev.headway.protocol.channel.TouchPointer
import dev.headway.protocol.framing.ChannelId
import dev.headway.protocol.io.FramedConnection
import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The **head-unit** side of the input channel: a touch panel and a set of hard
 * keys that can be driven from a script.
 *
 * Phase 3's acceptance criterion is that scripted emulator touches operate a real
 * app. This is the scripting half — it produces the wire traffic a driver's
 * finger would, so the phone side can be exercised without a car. What it
 * deliberately does *not* do is pretend to be a real panel: there is no jitter
 * model, no palm rejection and no pressure, because none of those reach the wire.
 *
 * ## What a gesture looks like on the wire
 *
 * Every gesture is a stream of `InputReport`s, one per sample, each with its own
 * timestamp:
 *
 * ```text
 *   tap        DOWN . . . . . . . . . . . . UP                      2 reports
 *   long press DOWN ....(hold)............. UP                      2 reports
 *   drag       DOWN  MOVED MOVED ... MOVED  UP                  steps+2 reports
 *   two finger DOWN  POINTER_DOWN  MOVED... POINTER_UP  UP      steps+4 reports
 * ```
 *
 * A tap and a drag differ *only* in whether MOVED reports appear between the
 * DOWN and the UP, which is why the acceptance test checks that a drag is not
 * collapsed into a tap: a receiver that coalesces or drops MOVED reports turns
 * every scroll into a click on whatever was under the finger.
 *
 * ## Timestamps
 *
 * `InputReport.timestamp` is microseconds since the epoch — openauto casts
 * `high_resolution_clock::now()` to `microseconds`
 * (`openauto/src/autoapp/Service/InputSource/InputSourceService.cpp` L145-L151,
 * L177-L183) and aa-proxy-rs uses `SystemTime::now()...as_micros()`
 * (`aa-proxy-rs/src/mitm.rs` L3553-L3557). One aa-proxy-rs helper,
 * `send_input_key`, uses milliseconds instead; its own comment marks that as a
 * duplicate to be merged away (`mitm.rs` L3646-L3655), so we follow the
 * microsecond form.
 *
 * The timestamps here are **synthesised, not measured**: the base comes from the
 * clock but each subsequent sample advances by the scripted interval rather than
 * by real elapsed time. A CI run emits a 300 ms drag in microseconds of wall
 * clock, and a receiver that infers velocity from timestamps must see the
 * intended velocity, not the machine's.
 *
 * ## Coordinates
 *
 * In the **projected video's** pixel space, not the physical panel's: a real head
 * unit rescales before sending (`openauto/src/autoapp/Projection/InputDevice.cpp`
 * L391-L392). So [displayWidth]/[displayHeight] are the resolution the unit
 * advertised for its video sink.
 *
 * Per ADR 0002 this shares `core-protocol` with the phone, so a round trip
 * through it proves symmetry rather than correctness; the byte fixtures in
 * `InputChannelTest` are what pin the wire format.
 */
class EmulatedInputSource(
    private val connection: FramedConnection,
    private val channelId: Int = ChannelId.INPUT_SOURCE.id,
    /** Video-space width the head unit advertised. Coordinates are checked against it. */
    val displayWidth: Int = 800,
    val displayHeight: Int = 480,
    /** Keycodes this unit will admit to being able to generate. */
    private val supportedKeycodes: List<Int> = EmulatedHeadUnit.SUPPORTED_KEYCODES,
    /** Wall clock, in microseconds since the epoch. Overridden in tests for determinism. */
    private val clock: () -> Long = { epochMicros() },
    private val onStep: (String) -> Unit = {},
) {
    /** Timestamp of the last report sent, so the stream is strictly increasing. */
    private var lastMicros: Long = Long.MIN_VALUE

    /** Total reports emitted. Handy for a test that wants to read exactly as many. */
    var reportsSent: Int = 0
        private set

    // --- key binding ---------------------------------------------------------

    /**
     * Answers the phone's `KeyBindingRequest`.
     *
     * openauto walks the requested list and rejects the whole request on the
     * first keycode it did not advertise, with `STATUS_KEYCODE_NOT_BOUND`
     * (`openauto/src/autoapp/Service/InputSource/InputSourceService.cpp`
     * L102-L132). There is no partial bind. We reproduce that, because a phone
     * that assumes partial success would silently lose keys against a real unit.
     *
     * @return the keycodes the phone asked for.
     */
    suspend fun answerKeyBinding(): List<Int> {
        val message = connection.receive()
        val binding = InputChannel.decode(message)
        check(binding is InputChannelMessage.KeyBinding) {
            "expected KEY_BINDING_REQUEST, got $binding"
        }

        val unsupported = binding.keycodes.firstOrNull { it !in supportedKeycodes }
        val status = if (unsupported == null) {
            InputChannelMessage.KeyBindingResult.STATUS_SUCCESS
        } else {
            InputChannelMessage.KeyBindingResult.STATUS_KEYCODE_NOT_BOUND
        }
        connection.send(InputChannel.keyBindingResponse(channelId, status))
        onStep(
            if (unsupported == null) "bound ${binding.keycodes.size} keycode(s)"
            else "refused key binding: keycode $unsupported was never advertised"
        )
        return binding.keycodes
    }

    // --- touch gestures ------------------------------------------------------

    /**
     * A tap: DOWN then UP at the same point, [dwellMicros] apart.
     *
     * @return the number of reports sent.
     */
    suspend fun tap(x: Int, y: Int, dwellMicros: Long = TAP_DWELL_MICROS): Int {
        requireOnPanel(x, y)
        val down = beginGesture()
        send(InputReports.touch(down, TouchAction.DOWN, listOf(TouchPointer(PRIMARY, x, y))))
        send(
            InputReports.touch(
                advance(dwellMicros),
                TouchAction.UP,
                listOf(TouchPointer(PRIMARY, x, y)),
            )
        )
        onStep("tap ($x, $y)")
        return 2
    }

    /**
     * A long press: DOWN, a hold, then UP.
     *
     * No MOVED reports are emitted during the hold. A real panel only reports on
     * change — openauto emits touch events from Qt touch *updates*
     * (`openauto/src/autoapp/Projection/InputDevice.cpp` L257-L377) — so a
     * motionless finger is silent, and the receiver has only the DOWN→UP interval
     * to judge a long press by. That makes this the case a receiver is most
     * likely to get wrong, so the emulator does not soften it.
     */
    suspend fun longPress(x: Int, y: Int, holdMicros: Long = LONG_PRESS_MICROS): Int {
        requireOnPanel(x, y)
        val down = beginGesture()
        send(InputReports.touch(down, TouchAction.DOWN, listOf(TouchPointer(PRIMARY, x, y))))
        send(
            InputReports.touch(
                advance(holdMicros),
                TouchAction.UP,
                listOf(TouchPointer(PRIMARY, x, y)),
            )
        )
        onStep("long press ($x, $y) for ${holdMicros / 1000} ms")
        return 2
    }

    /**
     * A drag at constant speed: DOWN, [steps] MOVED reports along the path, UP at
     * the end point.
     *
     * @param steps how many intermediate samples. The default is about what a
     *   60 Hz panel produces for a short flick.
     * @return the number of reports sent, `steps + 2`.
     */
    suspend fun drag(
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        steps: Int = DEFAULT_DRAG_STEPS,
        sampleMicros: Long = SAMPLE_INTERVAL_MICROS,
    ): Int = stroke(fromX, fromY, toX, toY, steps, sampleMicros, ::linear, "drag")

    /**
     * A fling: the same DOWN/MOVED/UP shape, but the samples are spaced along a
     * decelerating curve so the finger covers most of the distance early.
     *
     * The wire form is identical to [drag] — a fling is not a distinct protocol
     * event. It exists here because a receiver that infers velocity from sample
     * spacing behaves differently under the two, and only one of them is a
     * uniform ramp.
     */
    suspend fun fling(
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        steps: Int = DEFAULT_DRAG_STEPS,
        sampleMicros: Long = SAMPLE_INTERVAL_MICROS,
    ): Int = stroke(fromX, fromY, toX, toY, steps, sampleMicros, ::easeOut, "fling")

    private suspend fun stroke(
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        steps: Int,
        sampleMicros: Long,
        easing: (Double) -> Double,
        label: String,
    ): Int {
        requireOnPanel(fromX, fromY)
        requireOnPanel(toX, toY)
        require(steps >= 1) { "a $label needs at least one intermediate sample" }

        send(
            InputReports.touch(
                beginGesture(),
                TouchAction.DOWN,
                listOf(TouchPointer(PRIMARY, fromX, fromY)),
            )
        )
        for (step in 1..steps) {
            val progress = easing(step.toDouble() / (steps + 1))
            send(
                InputReports.touch(
                    advance(sampleMicros),
                    TouchAction.MOVED,
                    listOf(TouchPointer(PRIMARY, lerp(fromX, toX, progress), lerp(fromY, toY, progress))),
                )
            )
        }
        send(
            InputReports.touch(
                advance(sampleMicros),
                TouchAction.UP,
                listOf(TouchPointer(PRIMARY, toX, toY)),
            )
        )
        onStep("$label ($fromX, $fromY) -> ($toX, $toY) in ${steps + 2} reports")
        return steps + 2
    }

    /**
     * Two fingers moving together — a pinch, a two-finger scroll, or any gesture
     * the phone distinguishes by pointer count.
     *
     * The action sequence is the one openauto produces from Qt touch events
     * (`openauto/src/autoapp/Projection/InputDevice.cpp` L257-L377): the first
     * finger is DOWN, the second is POINTER_DOWN with its own `action_index`,
     * movement is MOVED with `action_index` 0, the second finger leaving is
     * POINTER_UP with its index, and the last finger leaving is UP. Every pointer
     * still down appears in `pointer_data` on every report.
     *
     * @return the number of reports sent, `steps + 4`.
     */
    suspend fun twoFingerDrag(
        first: Pair<Int, Int>,
        firstTo: Pair<Int, Int>,
        second: Pair<Int, Int>,
        secondTo: Pair<Int, Int>,
        steps: Int = DEFAULT_DRAG_STEPS,
        sampleMicros: Long = SAMPLE_INTERVAL_MICROS,
    ): Int {
        for (point in listOf(first, firstTo, second, secondTo)) requireOnPanel(point.first, point.second)
        require(steps >= 1) { "a two-finger drag needs at least one intermediate sample" }

        fun pointers(progress: Double) = listOf(
            TouchPointer(PRIMARY, lerp(first.first, firstTo.first, progress), lerp(first.second, firstTo.second, progress)),
            TouchPointer(SECONDARY, lerp(second.first, secondTo.first, progress), lerp(second.second, secondTo.second, progress)),
        )

        // The first finger is alone in pointer_data until the second arrives.
        send(InputReports.touch(beginGesture(), TouchAction.DOWN, listOf(pointers(0.0)[0])))
        send(
            InputReports.touch(
                advance(sampleMicros),
                TouchAction.POINTER_DOWN,
                pointers(0.0),
                actionIndex = 1,
            )
        )
        for (step in 1..steps) {
            val progress = step.toDouble() / (steps + 1)
            send(InputReports.touch(advance(sampleMicros), TouchAction.MOVED, pointers(progress)))
        }
        // Both pointers are still listed on POINTER_UP so the released finger's
        // final position is known; only after that does it drop out.
        send(
            InputReports.touch(
                advance(sampleMicros),
                TouchAction.POINTER_UP,
                pointers(1.0),
                actionIndex = 1,
            )
        )
        send(
            InputReports.touch(
                advance(sampleMicros),
                TouchAction.UP,
                listOf(pointers(1.0)[0]),
            )
        )
        onStep("two-finger drag in ${steps + 4} reports")
        return steps + 4
    }

    // --- keys ----------------------------------------------------------------

    /**
     * Presses and releases a hard key: two reports, DOWN then UP.
     *
     * openauto stamps each with a fresh timestamp
     * (`InputSourceService.cpp` L145-L151); aa-proxy-rs sends both with the same
     * one (`mitm.rs` L3554-L3603). We follow openauto — it is the aasdk-family
     * producer, and a receiver that measures press duration needs the two to
     * differ.
     *
     * @param longPress sets `KeyEvent.Key.longpress`. Both reference producers
     *   hard-code false, so a true here is untested against real hardware.
     */
    suspend fun keyPress(
        keycode: Int,
        holdMicros: Long = KEY_HOLD_MICROS,
        longPress: Boolean = false,
    ): Int {
        send(InputReports.key(beginGesture(), keycode, down = true, longPress = longPress))
        send(InputReports.key(advance(holdMicros), keycode, down = false, longPress = longPress))
        onStep("key $keycode")
        return 2
    }

    /**
     * Turns the rotary controller by [detents], negative for counter-clockwise.
     *
     * One report per detent with delta ±1, which is what openauto emits
     * (`InputSourceService.cpp` L153-L156). aa-proxy-rs documents |delta| as
     * scaling linearly (`mitm.rs` L3608-L3619), so a real unit may batch several
     * detents into one report; a receiver must handle both, and this emulator
     * exercises the openauto shape.
     *
     * @return the number of reports sent, `abs(detents)`.
     */
    suspend fun rotary(detents: Int): Int {
        require(detents != 0) { "a rotary event of zero detents is not a thing" }
        val step = if (detents < 0) -1 else 1
        for (index in 0 until abs(detents)) {
            val at = if (index == 0) beginGesture() else advance(ROTARY_DETENT_MICROS)
            send(InputReports.relative(at, InputKeyCodes.ROTARY_CONTROLLER, step))
        }
        onStep("rotary $detents detent(s)")
        return abs(detents)
    }

    // --- internals -----------------------------------------------------------

    private suspend fun send(report: InputReport) {
        connection.send(InputChannel.inputReport(channelId, report))
        reportsSent++
    }

    /**
     * Starts a gesture at the current wall clock, never at or before the previous
     * report. A test clock that returns a constant would otherwise emit a whole
     * gesture at one timestamp, which no real unit does and which hides ordering
     * bugs in the receiver.
     */
    private fun beginGesture(): Long {
        val now = clock()
        lastMicros = if (lastMicros == Long.MIN_VALUE) now else maxOf(now, lastMicros + 1)
        return lastMicros
    }

    private fun advance(byMicros: Long): Long {
        lastMicros += byMicros
        return lastMicros
    }

    private fun requireOnPanel(x: Int, y: Int) {
        require(x in 0..displayWidth && y in 0..displayHeight) {
            "($x, $y) is off a ${displayWidth}x$displayHeight display"
        }
    }

    private fun lerp(from: Int, to: Int, progress: Double): Int =
        (from + (to - from) * progress).roundToInt()

    private fun linear(progress: Double): Double = progress

    /** Quadratic ease-out: fast at the start, settling at the end. */
    private fun easeOut(progress: Double): Double = 1.0 - (1.0 - progress) * (1.0 - progress)

    companion object {
        /** `pointer_id` of the first finger. Ids are per-gesture and small. */
        const val PRIMARY: Int = 0

        /** `pointer_id` of the second finger. */
        const val SECONDARY: Int = 1

        /** 60 ms — a comfortable tap, well under any long-press threshold. */
        const val TAP_DWELL_MICROS: Long = 60_000

        /** 700 ms — past Android's 500 ms long-press timeout with margin. */
        const val LONG_PRESS_MICROS: Long = 700_000

        /** ~16.7 ms: one sample per frame at 60 Hz, the low end of panel report rates. */
        const val SAMPLE_INTERVAL_MICROS: Long = 16_667

        /** 80 ms — a deliberate button press rather than a brush. */
        const val KEY_HOLD_MICROS: Long = 80_000

        /** 40 ms between detents: a brisk but human turn of the knob. */
        const val ROTARY_DETENT_MICROS: Long = 40_000

        /** Enough samples that a receiver cannot mistake the stroke for a tap. */
        const val DEFAULT_DRAG_STEPS: Int = 12

        /** Microseconds since the epoch, the unit every producer uses. */
        fun epochMicros(): Long {
            val now = Instant.now()
            return now.epochSecond * 1_000_000L + (now.nano / 1_000L).toLong()
        }
    }
}

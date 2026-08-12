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

import aap_protobuf.service.inputsource.message.FeedbackEventOuterClass.FeedbackEvent
import dev.headway.protocol.framing.AapMessage
import dev.headway.protocol.framing.ChannelId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Byte-level fixtures for the input channel.
 *
 * Per ADR 0002 the emulator round-trip is a weak oracle — it shares this code,
 * so a symmetric mistake passes. The expected byte strings below were derived by
 * hand from the field numbers and wire types in
 * `aap_protobuf/service/inputsource/message`, and the decoder is checked
 * against *those bytes*, not against our own encoder.
 *
 * Worked example, the touch fixture:
 *
 * ```text
 *   08 01                       field 1 (timestamp)      varint 1
 *   1a 0c                       field 3 (touch_event)    length 12
 *     0a 06                       field 1 (pointer_data) length 6
 *       08 64                       field 1 (x)          varint 100
 *       10 32                       field 2 (y)          varint 50
 *       18 00                       field 3 (pointer_id) varint 0
 *     10 00                       field 2 (action_index) varint 0
 *     18 00                       field 3 (action)       varint 0 = ACTION_DOWN
 * ```
 */
class InputChannelTest {

    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "").replace("\n", "")
        require(clean.length % 2 == 0) { "odd-length hex: $s" }
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it) }

    private val input = ChannelId.INPUT_SOURCE.id

    private fun report(payloadHex: String) = AapMessage(
        channelId = input,
        control = false,
        encrypted = true,
        messageId = InputMessageId.INPUT_REPORT,
        payload = hex(payloadHex),
    )

    // --- message ids ---------------------------------------------------------

    @Test
    fun `the four input message ids match InputMessageId proto`() {
        assertEquals(32769, InputMessageId.INPUT_REPORT)
        assertEquals(32770, InputMessageId.KEY_BINDING_REQUEST)
        assertEquals(32771, InputMessageId.KEY_BINDING_RESPONSE)
        assertEquals(32772, InputMessageId.INPUT_FEEDBACK)
    }

    @Test
    fun `an input report carries its message id big-endian ahead of the protobuf`() {
        val message = InputChannel.inputReport(
            input,
            InputReports.key(timestampMicros = 1, keycode = InputKeyCodes.SEARCH, down = true),
        )
        // 32769 = 0x8001. Getting the byte order wrong here reads as message
        // 0x0180, which is not an input message at all.
        assertEquals("80 01", message.wirePayload().copyOfRange(0, 2).toHex())
        assertFalse(message.control, "input messages are SPECIFIC, not CONTROL")
        assertTrue(message.encrypted, "everything after AuthComplete is inside the TLS session")
    }

    // --- touch ---------------------------------------------------------------

    @Test
    fun `a single-pointer down decodes from the documented bytes`() {
        val decoded = InputChannel.decode(report("08 01 1a 0c 0a 06 08 64 10 32 18 00 10 00 18 00"))

        val events = assertInstanceOf(InputChannelMessage.Report::class.java, decoded).events
        assertEquals(1, events.size)
        val touch = assertInstanceOf(CarInputEvent.Touch::class.java, events[0])

        assertEquals(1L, touch.timestampMicros)
        assertEquals(TouchAction.DOWN, touch.action)
        assertEquals(0, touch.actionIndex)
        assertEquals(TouchSurface.TOUCHSCREEN, touch.surface)
        assertEquals(listOf(TouchPointer(id = 0, x = 100, y = 50)), touch.pointers)
    }

    @Test
    fun `our encoder produces those same bytes`() {
        // Pins field ordering as well as field numbers: protobuf serialises in
        // ascending field number, so a mis-numbered field moves in the output.
        val encoded = InputReports.touch(
            timestampMicros = 1,
            action = TouchAction.DOWN,
            pointers = listOf(TouchPointer(id = 0, x = 100, y = 50)),
        ).toByteArray()

        assertEquals("08 01 1a 0c 0a 06 08 64 10 32 18 00 10 00 18 00", encoded.toHex())
    }

    @Test
    fun `a two-pointer move keeps pointer ids and order`() {
        val decoded = InputChannel.decode(
            report(
                "08 07 1a 14 0a 06 08 0a 10 14 18 00 0a 06 08 1e 10 28 18 01 10 00 18 02"
            )
        )

        val touch = assertInstanceOf(
            CarInputEvent.Touch::class.java,
            assertInstanceOf(InputChannelMessage.Report::class.java, decoded).events.single(),
        )
        assertEquals(7L, touch.timestampMicros)
        assertEquals(TouchAction.MOVED, touch.action)
        assertEquals(
            listOf(TouchPointer(id = 0, x = 10, y = 20), TouchPointer(id = 1, x = 30, y = 40)),
            touch.pointers,
        )
        assertEquals(TouchPointer(id = 0, x = 10, y = 20), touch.changedPointer)
    }

    /**
     * The action numbers skip 3 and 4 because they are Android `MotionEvent`
     * constants. A decoder that treated the enum as dense would map
     * `ACTION_POINTER_DOWN` onto `ACTION_MOVED` and turn every second finger into
     * a drag.
     */
    @Test
    fun `pointer action values are the sparse MotionEvent numbers`() {
        assertEquals(0, TouchAction.DOWN.value)
        assertEquals(1, TouchAction.UP.value)
        assertEquals(2, TouchAction.MOVED.value)
        assertEquals(5, TouchAction.POINTER_DOWN.value)
        assertEquals(6, TouchAction.POINTER_UP.value)
        assertNull(TouchAction.fromValue(3), "3 and 4 are not PointerAction values")
        assertNull(TouchAction.fromValue(4))
    }

    @Test
    fun `action index survives a second finger going down`() {
        val encoded = InputReports.touch(
            timestampMicros = 42,
            action = TouchAction.POINTER_DOWN,
            pointers = listOf(TouchPointer(0, 10, 10), TouchPointer(1, 700, 400)),
            actionIndex = 1,
        )
        val touch = assertInstanceOf(
            CarInputEvent.Touch::class.java,
            InputChannel.decodeReport(encoded.toByteArray()).single(),
        )
        assertEquals(TouchAction.POINTER_DOWN, touch.action)
        assertEquals(1, touch.actionIndex)
        assertEquals(TouchPointer(1, 700, 400), touch.changedPointer)
    }

    /**
     * Field 7 carries the identical `TouchEvent` body as field 3. Conflating them
     * would feed touchpad deltas to the absolute-coordinate transform.
     */
    @Test
    fun `a touchpad event is distinguished from a touchscreen event`() {
        val encoded = InputReports.touch(
            timestampMicros = 3,
            action = TouchAction.MOVED,
            pointers = listOf(TouchPointer(0, 5, 5)),
            surface = TouchSurface.TOUCHPAD,
        )
        val touch = assertInstanceOf(
            CarInputEvent.Touch::class.java,
            InputChannel.decodeReport(encoded.toByteArray()).single(),
        )
        assertEquals(TouchSurface.TOUCHPAD, touch.surface)
    }

    // --- keys ----------------------------------------------------------------

    @Test
    fun `a key down decodes from the documented bytes`() {
        val decoded = InputChannel.decode(report("08 82 02 22 0a 0a 08 08 54 10 01 18 00 20 00"))

        val key = assertInstanceOf(
            CarInputEvent.Key::class.java,
            assertInstanceOf(InputChannelMessage.Report::class.java, decoded).events.single(),
        )
        assertEquals(258L, key.timestampMicros)
        assertEquals(InputKeyCodes.SEARCH, key.keycode)
        assertTrue(key.down)
        assertEquals(0, key.metaState)
        assertFalse(key.longPress)
    }

    // --- rotary --------------------------------------------------------------

    /**
     * The knob is the trap in this channel: it is a `RelativeEvent`, never a
     * `KeyEvent`, so a phone that only reads `key_event` sees a dead knob and no
     * error. Note the ten-byte `delta` — a negative `int32` is sign-extended to
     * 64 bits by protobuf's varint encoding.
     */
    @Test
    fun `a counter-clockwise detent decodes as a relative event, not a key`() {
        val decoded = InputChannel.decode(
            report("08 01 32 11 0a 0f 08 80 80 04 10 ff ff ff ff ff ff ff ff ff 01")
        )

        val events = assertInstanceOf(InputChannelMessage.Report::class.java, decoded).events
        val relative = assertInstanceOf(CarInputEvent.Relative::class.java, events.single())
        assertEquals(InputKeyCodes.ROTARY_CONTROLLER, relative.keycode)
        assertEquals(65536, relative.keycode, "KEYCODE_ROTARY_CONTROLLER")
        assertEquals(-1, relative.delta)
        assertTrue(relative.isRotary)
        assertTrue(events.none { it is CarInputEvent.Key }, "the knob must not decode as a key")
    }

    // --- key binding ---------------------------------------------------------

    @Test
    fun `a key binding request encodes its keycodes packed`() {
        val message = InputChannel.keyBindingRequest(
            input,
            listOf(InputKeyCodes.SEARCH, InputKeyCodes.ROTARY_CONTROLLER),
        )
        assertEquals(InputMessageId.KEY_BINDING_REQUEST, message.messageId)
        // 0a 04 = field 1, length 4; then 54 (84) and 80 80 04 (65536) with no
        // per-element tags, which is what `[packed = true]` means.
        assertEquals("0a 04 54 80 80 04", message.payload.toHex())
    }

    @Test
    fun `a key binding rejection decodes to STATUS_KEYCODE_NOT_BOUND`() {
        val decoded = InputChannel.decode(
            AapMessage(
                channelId = input,
                control = false,
                encrypted = true,
                messageId = InputMessageId.KEY_BINDING_RESPONSE,
                // -18 as a sign-extended int32 varint.
                payload = hex("08 ee ff ff ff ff ff ff ff ff 01"),
            )
        )
        val result = assertInstanceOf(InputChannelMessage.KeyBindingResult::class.java, decoded)
        assertEquals(InputChannelMessage.KeyBindingResult.STATUS_KEYCODE_NOT_BOUND, result.status)
        assertEquals(-18, result.status)
        assertFalse(result.bound)
    }

    @Test
    fun `a successful key binding decodes as bound`() {
        val decoded = InputChannel.decode(
            InputChannel.keyBindingResponse(input, InputChannelMessage.KeyBindingResult.STATUS_SUCCESS)
        )
        assertTrue(assertInstanceOf(InputChannelMessage.KeyBindingResult::class.java, decoded).bound)
    }

    // --- feedback ------------------------------------------------------------

    @Test
    fun `input feedback round-trips through its enum`() {
        val decoded = InputChannel.decode(InputChannel.feedback(input, FeedbackEvent.FEEDBACK_DRAG_START))
        assertEquals(
            FeedbackEvent.FEEDBACK_DRAG_START,
            assertInstanceOf(InputChannelMessage.Feedback::class.java, decoded).event,
        )
    }

    // --- robustness ----------------------------------------------------------

    @Test
    fun `an unrecognised message id is reported rather than thrown`() {
        val decoded = InputChannel.decode(
            AapMessage(
                channelId = input,
                control = false,
                encrypted = true,
                messageId = 0x9999,
                payload = byteArrayOf(1, 2, 3),
            )
        )
        val unhandled = assertInstanceOf(InputChannelMessage.Unhandled::class.java, decoded)
        assertEquals(0x9999, unhandled.messageId)
        assertEquals(3, unhandled.payloadSize)
    }

    @Test
    fun `a truncated report fails with a legible error`() {
        // Field 3 claims 12 bytes of touch event and supplies none.
        val e = assertThrows(InputChannelException::class.java) {
            InputChannel.decode(report("08 01 1a 0c"))
        }
        assertTrue(e.message!!.contains("InputReport"), e.message)
    }
}

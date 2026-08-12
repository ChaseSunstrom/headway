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

import aap_protobuf.service.inputsource.message.AbsoluteEventOuterClass.AbsoluteEvent
import aap_protobuf.service.inputsource.message.FeedbackEventOuterClass.FeedbackEvent
import aap_protobuf.service.inputsource.message.InputFeedbackOuterClass.InputFeedback
import aap_protobuf.service.inputsource.message.InputReportOuterClass.InputReport
import aap_protobuf.service.inputsource.message.KeyEventOuterClass.KeyEvent
import aap_protobuf.service.inputsource.message.PointerActionOuterClass.PointerAction
import aap_protobuf.service.inputsource.message.RelativeEventOuterClass.RelativeEvent
import aap_protobuf.service.inputsource.message.TouchEventOuterClass.TouchEvent
import aap_protobuf.service.media.sink.message.KeyBindingRequestOuterClass.KeyBindingRequest
import aap_protobuf.service.media.sink.message.KeyBindingResponseOuterClass.KeyBindingResponse
import dev.headway.protocol.framing.AapMessage
import dev.headway.protocol.framing.ChannelId
import dev.headway.protocol.io.FramedConnection
import dev.headway.protocol.io.MessageChannel

/**
 * Message ids on the input channel.
 *
 * Source: `aasdk/protobuf/aap_protobuf/service/inputsource/InputMessageId.proto`
 * L5-L11. AACS names the same four values `Event`, `HandshakeRequest`,
 * `HandshakeResponse` (`AACS/include/enums.h` L49-L54); the numbers agree, so
 * the naming split is cosmetic.
 *
 * Note the direction asymmetry: [KEY_BINDING_REQUEST] is the only one the phone
 * originates. Everything else on this channel flows head unit → phone.
 */
object InputMessageId {
    /** HU → phone. Payload is an `InputReport`: one touch, key, rotary or absolute event. */
    const val INPUT_REPORT: Int = 32769

    /** Phone → HU. Payload is `KeyBindingRequest { repeated int32 keycodes }`. */
    const val KEY_BINDING_REQUEST: Int = 32770

    /** HU → phone. Payload is `KeyBindingResponse { required int32 status }`. */
    const val KEY_BINDING_RESPONSE: Int = 32771

    /**
     * `InputFeedback { optional FeedbackEvent event }`. Present in the protos
     * and decoded by aa-proxy-rs's pretty-printer
     * (`aa-proxy-rs/src/mitm_prettyprint.rs` L949-L959), but no reference sends
     * or handles it, so **the direction is unverified**. Headway decodes it if it
     * arrives and can emit it, and does neither by default.
     */
    const val INPUT_FEEDBACK: Int = 32772

    fun describe(id: Int): String = when (id) {
        INPUT_REPORT -> "INPUT_REPORT"
        KEY_BINDING_REQUEST -> "KEY_BINDING_REQUEST"
        KEY_BINDING_RESPONSE -> "KEY_BINDING_RESPONSE"
        INPUT_FEEDBACK -> "INPUT_FEEDBACK"
        else -> "UNKNOWN_INPUT(0x%04x)".format(id)
    }
}

/**
 * Key codes Headway names.
 *
 * Values are `KeyCode` enum numbers from
 * `aasdk/protobuf/aap_protobuf/service/media/sink/message/KeyCode.proto`. Values
 * 0-263 mirror Android's `KeyEvent` constants exactly; the 65536+ range is
 * Android-Auto-specific and has no Android equivalent.
 *
 * This is deliberately a short list — the ones the references actually
 * advertise — rather than a transcription of all 270-odd values. A head unit may
 * send any number in `keycodes_supported`, and [CarInputEvent.Key] carries the
 * raw `Int`, so an unnamed key is forwarded rather than dropped.
 */
object InputKeyCodes {
    /** `KeyCode.proto` L9-L12. */
    const val HOME: Int = 3

    /** `KeyCode.proto` L9-L12. */
    const val BACK: Int = 4

    /** `KeyCode.proto` L9-L12. */
    const val CALL: Int = 5

    /** `KeyCode.proto` L9-L12. */
    const val ENDCALL: Int = 6

    /** `KeyCode.proto` L25-L29. */
    const val DPAD_UP: Int = 19

    /** `KeyCode.proto` L25-L29. */
    const val DPAD_DOWN: Int = 20

    /** `KeyCode.proto` L25-L29. */
    const val DPAD_LEFT: Int = 21

    /** `KeyCode.proto` L25-L29. */
    const val DPAD_RIGHT: Int = 22

    /** `KeyCode.proto` L25-L29. */
    const val DPAD_CENTER: Int = 23

    /**
     * The steering-wheel voice button. openauto maps its `VoiceCommandButton`
     * here (`openauto/src/autoapp/Configuration/Configuration.cpp` L728-L747),
     * and AACS's `ButtonCode::MICROPHONE_1` is the same number
     * (`AACS/proto/ButtonsEvent.proto` L23-L25). `KeyCode.proto` L88-L91.
     */
    const val SEARCH: Int = 84

    /** `KeyCode.proto` L91-L96. */
    const val MEDIA_PLAY_PAUSE: Int = 85

    /** `KeyCode.proto` L91-L96. */
    const val MEDIA_NEXT: Int = 87

    /** `KeyCode.proto` L91-L96. */
    const val MEDIA_PREVIOUS: Int = 88

    /** `KeyCode.proto` L132-L133. */
    const val MEDIA_PLAY: Int = 126

    /** `KeyCode.proto` L132-L133. */
    const val MEDIA_PAUSE: Int = 127

    /**
     * The rotary controller. **Never arrives as a key event.** It is carried in
     * a `RelativeEvent.Rel { keycode, delta }`, so a phone that only reads
     * `key_event` sees nothing at all from the knob. `KeyCode.proto` L274-L284;
     * routing per `openauto/src/autoapp/Service/InputSource/InputSourceService.cpp`
     * L153-L156.
     */
    const val ROTARY_CONTROLLER: Int = 65536

    /** `KeyCode.proto` L274-L284. */
    const val MEDIA: Int = 65537

    /** `KeyCode.proto` L274-L284. */
    const val NAVIGATION: Int = 65538

    /** `KeyCode.proto` L274-L284. */
    const val TEL: Int = 65540
}

/**
 * What a pointer did, from `PointerAction`.
 *
 * Source: `aasdk/protobuf/aap_protobuf/service/inputsource/message/PointerAction.proto`
 * L5-L12. AACS spells the same numbers `Press`/`Release`/`Drag`/`Down`/`Up`
 * (`AACS/proto/TouchAction.proto` L7-L14); we use aasdk's names.
 *
 * The gap at 3 and 4 is inherited from Android's `MotionEvent` action constants,
 * which is where the numbering comes from.
 */
enum class TouchAction(val value: Int) {
    /** First finger down. */
    DOWN(0),

    /** Last finger up. */
    UP(1),

    /** Drag. `action_index` is 0 — the moved pointers are all of them. */
    MOVED(2),

    /** An additional finger went down; `action_index` says which. */
    POINTER_DOWN(5),

    /** One of several fingers lifted; `action_index` says which. */
    POINTER_UP(6),
    ;

    companion object {
        private val byValue = entries.associateBy(TouchAction::value)

        fun fromValue(value: Int): TouchAction? = byValue[value]
    }
}

/**
 * Which physical surface produced a touch.
 *
 * `InputReport` carries touchscreen events in field 3 and touchpad events in
 * field 7, with the identical `TouchEvent` body
 * (`InputReport.proto` L14, L18). The distinction matters upstream: a touchpad
 * is usually relative and `ui_navigation`-flagged
 * (`InputSourceService.proto` L19-L29), so it must not be fed to the same
 * absolute-coordinate transform as the screen.
 */
enum class TouchSurface { TOUCHSCREEN, TOUCHPAD }

/**
 * One finger, in the head unit's coordinate space.
 *
 * `pointer_id` is a small sequential id the head unit assigns per finger and
 * keeps stable for the life of the gesture
 * (`openauto/src/autoapp/Projection/InputDevice.cpp` L379-L398). It is the only
 * way to correlate a finger across reports, since the order of `pointer_data` is
 * not guaranteed.
 */
data class TouchPointer(val id: Int, val x: Int, val y: Int)

/**
 * An input event the head unit sent us, still in **car coordinates**.
 *
 * One `InputReport` can in principle carry several sub-messages, and a
 * `KeyEvent` carries a repeated `keys` list, so decoding a report yields a
 * *list* of these. Every reference producer sends exactly one event per report,
 * but nothing in the schema requires that.
 *
 * Coordinates are in the projected video's pixel space, not the physical panel's:
 * the head unit already rescales from panel geometry to display geometry before
 * sending (`openauto/src/autoapp/Projection/InputDevice.cpp` L391-L392). That is
 * why [TouchTransform] takes the *advertised video resolution* as its car size.
 */
sealed interface CarInputEvent {
    /**
     * `required uint64 timestamp = 1`. No unit is stated in any `.proto`;
     * every producer uses **microseconds since the epoch**
     * (`openauto/src/autoapp/Service/InputSource/InputSourceService.cpp`
     * L145-L151; `aa-proxy-rs/src/mitm.rs` L3553-L3557). One aa-proxy-rs helper,
     * `send_input_key`, uses milliseconds instead — its own comment calls that a
     * bug to be merged away (`mitm.rs` L3646-L3655).
     */
    val timestampMicros: Long

    /** A touchscreen or touchpad event. */
    data class Touch(
        override val timestampMicros: Long,
        val action: TouchAction,
        /**
         * Index into [pointers] of the pointer whose state changed. 0 for
         * DOWN/UP/MOVED; the changed pointer for POINTER_DOWN/POINTER_UP
         * (`InputDevice.cpp` L257-L377).
         */
        val actionIndex: Int,
        /**
         * Every pointer currently down. Points in the Released state are omitted
         * unless the action is UP or POINTER_UP, in which case the released one
         * is still present so its final position is known (`InputDevice.cpp`
         * L347-L366).
         */
        val pointers: List<TouchPointer>,
        val surface: TouchSurface,
    ) : CarInputEvent {
        /** The pointer [actionIndex] refers to, or null if the head unit's index is out of range. */
        val changedPointer: TouchPointer? get() = pointers.getOrNull(actionIndex)
    }

    /** A button press or release. Press and release arrive as separate reports. */
    data class Key(
        override val timestampMicros: Long,
        val keycode: Int,
        val down: Boolean,
        /**
         * `required uint32 metastate = 3`. Both producers hard-code 0
         * (`InputSourceService.cpp` L157-L163; `mitm.rs` L3561-L3565), so a
         * non-zero value from a real unit is unexplored territory.
         */
        val metaState: Int,
        /**
         * `optional bool longpress = 4`. Both producers hard-code false and let
         * the phone infer long-press from the down/up interval, so treat a true
         * here as a bonus rather than as the mechanism.
         */
        val longPress: Boolean,
    ) : CarInputEvent

    /**
     * A `RelativeEvent.Rel` — a signed step count for one keycode.
     *
     * In practice this is only ever the rotary controller: [isRotary] is the
     * test. openauto emits one event of delta ±1 per detent, on key *release*
     * only, to avoid double-counting the press
     * (`openauto/src/autoapp/Projection/InputDevice.cpp` L191-L194). aa-proxy-rs
     * documents |delta| = 1 as a single UI step scaling linearly, positive =
     * clockwise (`aa-proxy-rs/src/mitm.rs` L3608-L3619), so a unit may legally
     * batch several detents into one event.
     */
    data class Relative(
        override val timestampMicros: Long,
        val keycode: Int,
        val delta: Int,
    ) : CarInputEvent {
        val isRotary: Boolean get() = keycode == InputKeyCodes.ROTARY_CONTROLLER
    }

    /**
     * An `AbsoluteEvent.Abs` — an absolute axis value for one keycode. No
     * reference in `references/` produces one; decoded so that a unit that does
     * is visible in the logs rather than silently ignored.
     */
    data class Absolute(
        override val timestampMicros: Long,
        val keycode: Int,
        val value: Int,
    ) : CarInputEvent
}

/** A decoded message from the input channel. */
sealed interface InputChannelMessage {
    /** `INPUT_REPORT`. [events] is empty if the report carried only a deprecated or unknown field. */
    data class Report(val events: List<CarInputEvent>) : InputChannelMessage

    /**
     * `KEY_BINDING_REQUEST`. The phone normally sends this, so receiving one
     * means we are acting as the head unit (the emulator does).
     */
    data class KeyBinding(val keycodes: List<Int>) : InputChannelMessage

    /** `KEY_BINDING_RESPONSE`. [status] is a `MessageStatus` value. */
    data class KeyBindingResult(val status: Int) : InputChannelMessage {
        /** `MessageStatus::STATUS_SUCCESS` = 0 (`aasdk/protobuf/aap_protobuf/shared/MessageStatus.proto` L5-L8). */
        val bound: Boolean get() = status == STATUS_SUCCESS

        companion object {
            const val STATUS_SUCCESS: Int = 0

            /**
             * Returned when the phone asked for a keycode the head unit never
             * advertised. openauto sets this and breaks out of the loop on the
             * first offender (`InputSourceService.cpp` L102-L132).
             * `MessageStatus.proto` L24-L26.
             */
            const val STATUS_KEYCODE_NOT_BOUND: Int = -18
        }
    }

    /** `INPUT_FEEDBACK`. [event] is null if the head unit sent a value we do not know. */
    data class Feedback(val event: FeedbackEvent?) : InputChannelMessage

    /** Anything else that arrived on this channel. Kept rather than thrown so a log shows it. */
    data class Unhandled(val messageId: Int, val payloadSize: Int) : InputChannelMessage
}

/** The peer sent something on the input channel that does not parse. */
class InputChannelException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * The **phone** side of the input channel: it receives what the driver does to
 * the car's screen and buttons.
 *
 * ## Sequence
 *
 * ```text
 *  head unit                                 phone (Headway)
 *  ---------                                 ---------------
 *  ServiceDiscoveryResponse    ------->       (InputSourceService: touchscreen
 *   [advertises keycodes_supported]            geometry + keycodes it can send)
 *                              <-------      ChannelOpenRequest
 *  ChannelOpenResponse         ------->
 *                              <-------      KeyBindingRequest  [32770]
 *  KeyBindingResponse [32771]  ------->
 *  InputReport [32769]         ------->      ... for the rest of the session
 * ```
 *
 * Steps are from §6.1 of `docs/protocol-notes.md`. Two things are easy to get
 * backwards:
 *
 * 1. **The head unit advertises the keycodes**, not the phone. The phone picks a
 *    subset and asks for it. Asking for one that was not advertised gets the
 *    whole request rejected with `STATUS_KEYCODE_NOT_BOUND`, not a partial bind
 *    — openauto breaks on the first offender.
 * 2. **The rotary knob is not a key event.** See [CarInputEvent.Relative].
 *
 * ## Framing
 *
 * Every message on this channel is `ENCRYPTED` and `SPECIFIC` (i.e. *not* the
 * CONTROL flag), including the key-binding exchange —
 * `aasdk/src/Channel/InputSource/InputSourceService.cpp` L43-L55 and L57-L68.
 * Only `ChannelOpenRequest`/`Response`, which also travel on this channel id,
 * use CONTROL.
 *
 * ## Reading
 *
 * [handle] is the real entry point: a session demultiplexes frames by channel id
 * and hands this the ones that belong to it. [receiveMessage] reads the
 * connection directly and is only correct when nothing else is reading it — a
 * single-channel driver or a test. `FramedConnection.receive` returns whatever
 * arrives next on *any* channel, so two competing readers would steal each
 * other's messages.
 */
class InputChannel(
    private val connection: MessageChannel,
    /**
     * The channel number the head unit advertised for its `InputSourceService`.
     * Defaults to aasdk's static convention; the authority is the `id` field of
     * the advertised `Service` (see [ChannelId]).
     */
    val channelId: Int = ChannelId.INPUT_SOURCE.id,
    private val onStep: (String) -> Unit = {},
) {
    /**
     * Asks the head unit to deliver [keycodes].
     *
     * Send only codes the unit advertised in `keycodes_supported`; one it did not
     * advertise fails the entire request.
     */
    suspend fun requestKeyBinding(keycodes: List<Int>) {
        connection.send(keyBindingRequest(channelId, keycodes))
        onStep("requested ${keycodes.size} keycode binding(s)")
    }

    /** Emits an `InputFeedback`. Direction is unverified — see [InputMessageId.INPUT_FEEDBACK]. */
    suspend fun sendFeedback(event: FeedbackEvent) {
        connection.send(feedback(channelId, event))
    }

    /**
     * Decodes one message addressed to this channel.
     *
     * @throws InputChannelException if it is addressed elsewhere or does not parse.
     */
    fun handle(message: AapMessage): InputChannelMessage {
        if (message.channelId != channelId) {
            throw InputChannelException(
                "message for ${ChannelId.describe(message.channelId)} handed to the input " +
                    "channel (${ChannelId.describe(channelId)})"
            )
        }
        return decode(message)
    }

    /** Reads the connection until a message for this channel arrives, and decodes it. */
    suspend fun receiveMessage(): InputChannelMessage {
        while (true) {
            val message = connection.receive()
            if (message.channelId == channelId) return decode(message)
            onStep("ignored a message on ${ChannelId.describe(message.channelId)}")
        }
    }

    companion object {
        /**
         * Decodes an input-channel message by its id.
         *
         * The dispatch mirrors aasdk's head-unit switch
         * (`aasdk/src/Channel/InputSource/InputSourceService.cpp` L83-L102) and
         * aa-proxy-rs's exhaustive match (`src/mitm_prettyprint.rs` L949-L960),
         * with the directions inverted because we are the phone.
         */
        fun decode(message: AapMessage): InputChannelMessage = when (message.messageId) {
            InputMessageId.INPUT_REPORT -> InputChannelMessage.Report(decodeReport(message.payload))

            InputMessageId.KEY_BINDING_REQUEST -> InputChannelMessage.KeyBinding(
                parse("KeyBindingRequest") { KeyBindingRequest.parseFrom(message.payload) }.keycodesList
            )

            InputMessageId.KEY_BINDING_RESPONSE -> InputChannelMessage.KeyBindingResult(
                parse("KeyBindingResponse") { KeyBindingResponse.parseFrom(message.payload) }.status
            )

            InputMessageId.INPUT_FEEDBACK -> {
                val body = parse("InputFeedback") { InputFeedback.parseFrom(message.payload) }
                // A value outside the enum lands in unknown fields, so hasEvent()
                // is false rather than the getter returning a bogus constant.
                InputChannelMessage.Feedback(if (body.hasEvent()) body.event else null)
            }

            else -> InputChannelMessage.Unhandled(message.messageId, message.payload.size)
        }

        /**
         * Flattens an `InputReport` into zero or more [CarInputEvent]s.
         *
         * A report carries one timestamp and, per the schema, any combination of
         * the five event sub-messages; every reference sends exactly one. The
         * deprecated `disp_channel_id` (field 2) is ignored.
         */
        fun decodeReport(payload: ByteArray): List<CarInputEvent> {
            val report = parse("InputReport") { InputReport.parseFrom(payload) }
            val at = report.timestamp
            val events = mutableListOf<CarInputEvent>()

            if (report.hasTouchEvent()) {
                events += decodeTouch(at, report.touchEvent, TouchSurface.TOUCHSCREEN)
            }
            if (report.hasTouchpadEvent()) {
                events += decodeTouch(at, report.touchpadEvent, TouchSurface.TOUCHPAD)
            }
            for (key in report.keyEvent.keysList) {
                events += CarInputEvent.Key(
                    timestampMicros = at,
                    keycode = key.keycode,
                    down = key.down,
                    metaState = key.metastate,
                    longPress = key.hasLongpress() && key.longpress,
                )
            }
            for (rel in report.relativeEvent.dataList) {
                events += CarInputEvent.Relative(at, rel.keycode, rel.delta)
            }
            for (abs in report.absoluteEvent.dataList) {
                events += CarInputEvent.Absolute(at, abs.keycode, abs.value)
            }
            return events
        }

        private fun decodeTouch(
            at: Long,
            event: TouchEvent,
            surface: TouchSurface,
        ): CarInputEvent.Touch {
            // `action` is optional. Absent means the proto2 default, which is the
            // first enumerator, ACTION_DOWN. No reference producer omits it, so
            // this branch is defensive rather than observed.
            val action = if (event.hasAction()) {
                TouchAction.fromValue(event.action.number)
                    ?: throw InputChannelException("unknown PointerAction ${event.action.number}")
            } else {
                TouchAction.DOWN
            }
            return CarInputEvent.Touch(
                timestampMicros = at,
                action = action,
                actionIndex = if (event.hasActionIndex()) event.actionIndex else 0,
                pointers = event.pointerDataList.map { TouchPointer(it.pointerId, it.x, it.y) },
                surface = surface,
            )
        }

        // --- encoders ---------------------------------------------------------
        //
        // The phone only originates KeyBindingRequest and (perhaps) InputFeedback.
        // The report encoders exist for the head-unit emulator, which shares this
        // module per ADR 0002 — so a round trip through them proves symmetry, not
        // correctness. The byte fixtures in InputChannelTest are the oracle.

        fun keyBindingRequest(channelId: Int, keycodes: List<Int>): AapMessage = AapMessage(
            channelId = channelId,
            control = false,
            encrypted = true,
            messageId = InputMessageId.KEY_BINDING_REQUEST,
            payload = KeyBindingRequest.newBuilder().addAllKeycodes(keycodes).build().toByteArray(),
        )

        fun keyBindingResponse(channelId: Int, status: Int): AapMessage = AapMessage(
            channelId = channelId,
            control = false,
            encrypted = true,
            messageId = InputMessageId.KEY_BINDING_RESPONSE,
            payload = KeyBindingResponse.newBuilder().setStatus(status).build().toByteArray(),
        )

        fun feedback(channelId: Int, event: FeedbackEvent): AapMessage = AapMessage(
            channelId = channelId,
            control = false,
            encrypted = true,
            messageId = InputMessageId.INPUT_FEEDBACK,
            payload = InputFeedback.newBuilder().setEvent(event).build().toByteArray(),
        )

        fun inputReport(channelId: Int, report: InputReport): AapMessage = AapMessage(
            channelId = channelId,
            control = false,
            encrypted = true,
            messageId = InputMessageId.INPUT_REPORT,
            payload = report.toByteArray(),
        )

        private inline fun <T> parse(what: String, body: () -> T): T = try {
            body()
        } catch (e: Exception) {
            throw InputChannelException("malformed $what on the input channel", e)
        }
    }
}

/**
 * Builders for the `InputReport`s a head unit sends.
 *
 * These live in `core-protocol` rather than in the emulator so that the
 * decoder's counterpart is next to it and the two cannot drift apart in
 * field numbering. Only the emulator and tests use them.
 */
object InputReports {

    /** A touchscreen (or touchpad) report. */
    fun touch(
        timestampMicros: Long,
        action: TouchAction,
        pointers: List<TouchPointer>,
        actionIndex: Int = 0,
        surface: TouchSurface = TouchSurface.TOUCHSCREEN,
    ): InputReport {
        require(pointers.isNotEmpty()) { "a touch report needs at least one pointer" }
        require(actionIndex in pointers.indices) {
            "actionIndex $actionIndex is outside pointer_data of size ${pointers.size}"
        }
        val touch = TouchEvent.newBuilder()
            .setAction(PointerAction.forNumber(action.value))
            .setActionIndex(actionIndex)
        for (pointer in pointers) {
            touch.addPointerData(
                TouchEvent.Pointer.newBuilder()
                    .setPointerId(pointer.id)
                    .setX(pointer.x)
                    .setY(pointer.y)
            )
        }
        val report = InputReport.newBuilder().setTimestamp(timestampMicros)
        when (surface) {
            TouchSurface.TOUCHSCREEN -> report.setTouchEvent(touch)
            TouchSurface.TOUCHPAD -> report.setTouchpadEvent(touch)
        }
        return report.build()
    }

    /**
     * A button report. `metastate` is required by the schema and every producer
     * sends 0.
     */
    fun key(
        timestampMicros: Long,
        keycode: Int,
        down: Boolean,
        longPress: Boolean = false,
        metaState: Int = 0,
    ): InputReport = InputReport.newBuilder()
        .setTimestamp(timestampMicros)
        .setKeyEvent(
            KeyEvent.newBuilder().addKeys(
                KeyEvent.Key.newBuilder()
                    .setKeycode(keycode)
                    .setDown(down)
                    .setMetastate(metaState)
                    .setLongpress(longPress)
            )
        )
        .build()

    /** A rotary detent, or any other relative axis. */
    fun relative(
        timestampMicros: Long,
        keycode: Int,
        delta: Int,
    ): InputReport = InputReport.newBuilder()
        .setTimestamp(timestampMicros)
        .setRelativeEvent(
            RelativeEvent.newBuilder().addData(
                RelativeEvent.Rel.newBuilder().setKeycode(keycode).setDelta(delta)
            )
        )
        .build()

    /** An absolute axis value. */
    fun absolute(
        timestampMicros: Long,
        keycode: Int,
        value: Int,
    ): InputReport = InputReport.newBuilder()
        .setTimestamp(timestampMicros)
        .setAbsoluteEvent(
            AbsoluteEvent.newBuilder().addData(
                AbsoluteEvent.Abs.newBuilder().setKeycode(keycode).setValue(value)
            )
        )
        .build()
}

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

import aap_protobuf.service.control.message.AudioFocusNotificationOuterClass.AudioFocusNotification
import aap_protobuf.service.control.message.AudioFocusRequestOuterClass.AudioFocusRequest
import aap_protobuf.service.control.message.AudioFocusRequestTypeOuterClass.AudioFocusRequestType
import aap_protobuf.service.control.message.AudioFocusStateTypeOuterClass.AudioFocusStateType
import aap_protobuf.service.media.sink.message.AudioStreamTypeOuterClass.AudioStreamType
import dev.headway.protocol.control.ControlMessageType
import dev.headway.protocol.framing.AapMessage
import dev.headway.protocol.framing.ChannelId
import dev.headway.protocol.io.FramedConnection
import dev.headway.protocol.io.MessageChannel

/** Raised when the audio-focus exchange is driven in a way the protocol cannot express. */
class AudioFocusException(message: String) : RuntimeException(message)

/**
 * What the head unit last said about the phone's audio focus, and what Headway
 * is therefore allowed to put on its audio channels.
 *
 * The state values are transcribed from
 * `aasdk/protobuf/aap_protobuf/service/control/message/AudioFocusStateType.proto`
 * L5-L15. The *meaning* attached to each below is read off the Android audio
 * focus vocabulary the names are borrowed from; no reference documents them, and
 * the only prose anywhere is openauto's comment block
 * (`openauto/src/autoapp/Service/AndroidAutoEntity.cpp` L222-L235):
 *
 * > When the MD starts playing music for example, it sends a gain request. The
 * > HU replies: STATE_GAIN - no restrictions / STATE_GAIN_MEDIA_ONLY when using
 * > a guidance channel / STATE_LOSS when vehicle is playing high priority sound
 * > after stopping native media (ie USB, RADIO). When HU starts playing music,
 * > we should send a STATE LOSS to stop MD music and guidance.
 *
 * So the direction is: the head unit arbitrates, the phone obeys. A `LOSS`
 * means the car took the speakers back and Headway must stop sending; a
 * `LOSS_TRANSIENT_CAN_DUCK` means keep sending but quieter. **Everything in
 * [allows], [mustDuck] and [mustPause] is inference from the enum names, not
 * observed behaviour** — the fields exist so the policy sits in one readable
 * place rather than being re-derived at each call site, and so the inference can
 * be corrected in one place once a real head unit has been watched.
 */
data class AudioFocusState(
    val state: AudioFocusStateType,
    /**
     * `AudioFocusNotification.unsolicited` (field 2,
     * `AudioFocusNotification.proto` L7-L10). Set when the head unit changed the
     * focus on its own — the driver pressed the radio's source button — rather
     * than answering a request. No reference *sets* it: openauto builds its
     * notification with `focus_state` alone
     * (`openauto/src/autoapp/Service/AndroidAutoEntity.cpp` L245-L246), so a
     * head unit that leaves it unset while spontaneously revoking focus is
     * entirely possible. Do not use it to decide whether to obey a state.
     */
    val unsolicited: Boolean = false,
) {
    /** True for the four `GAIN_*` states: the phone may drive at least one stream. */
    val holdsFocus: Boolean
        get() = when (state) {
            AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN,
            AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_TRANSIENT,
            AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_MEDIA_ONLY,
            AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_TRANSIENT_GUIDANCE_ONLY,
            -> true

            else -> false
        }

    /**
     * True when the grant is explicitly short-lived. Headway should release it
     * as soon as the prompt that asked for it has finished playing rather than
     * sit on it — a transient grant the phone never releases leaves the car's
     * own source ducked indefinitely.
     */
    val transient: Boolean
        get() = state == AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_TRANSIENT ||
            state == AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_TRANSIENT_GUIDANCE_ONLY

    /** `LOSS_TRANSIENT_CAN_DUCK`: keep streaming, at reduced gain. */
    val mustDuck: Boolean
        get() = state == AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS_TRANSIENT_CAN_DUCK

    /** `LOSS_TRANSIENT`: stop streaming, expect focus back without asking again. */
    val mustPause: Boolean
        get() = state == AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS_TRANSIENT

    /** `LOSS`: stop streaming; the phone must request focus again before resuming. */
    val mustStop: Boolean
        get() = state == AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS ||
            state == AudioFocusStateType.AUDIO_FOCUS_STATE_INVALID

    /**
     * Whether [stream] may carry audio in this state.
     *
     * The two `*_ONLY` states are the reason this is a function rather than a
     * boolean: `GAIN_MEDIA_ONLY` and `GAIN_TRANSIENT_GUIDANCE_ONLY` grant one
     * stream and withhold the others, so "do I have focus" is not answerable
     * without saying which channel is asking.
     *
     * System audio is treated as *not* covered by either restricted grant. That
     * is the conservative reading: the restricted states name exactly one stream
     * each, and sending a UI blip into a grant that named guidance is more
     * likely to be refused than to be tolerated. Inference, not observation.
     */
    fun allows(stream: AudioStreamType): Boolean = when (state) {
        AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN,
        AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_TRANSIENT,
        AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS_TRANSIENT_CAN_DUCK,
        -> true

        AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_MEDIA_ONLY ->
            stream == AudioStreamType.AUDIO_STREAM_MEDIA

        AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_TRANSIENT_GUIDANCE_ONLY ->
            stream == AudioStreamType.AUDIO_STREAM_GUIDANCE

        else -> false
    }
}

/**
 * The **phone** side of the audio-focus exchange on the control channel.
 *
 * ## Where this lives on the wire
 *
 * Focus is not part of any audio channel. It is two control-channel messages —
 * `MESSAGE_AUDIO_FOCUS_REQUEST` (18) from the phone and
 * `MESSAGE_AUDIO_FOCUS_NOTIFICATION` (19) back
 * (`aasdk/protobuf/aap_protobuf/service/control/ControlMessageType.proto`
 * L23-L24, transcribed into [ControlMessageType]). AACS's independent enum
 * agrees on both numbers (`AACS/include/enums.h` L35-L36) and aa-proxy-rs's
 * message-name table agrees again (`aa-proxy-rs/src/mitm.rs` L791-L792).
 *
 * ```text
 *  phone (Headway)                          head unit
 *  ---------------                          ---------
 *  AudioFocusRequest { GAIN_TRANSIENT_MAY_DUCK }  18 -->
 *                              <-- 19   AudioFocusNotification { GAIN_TRANSIENT }
 *  ... audio flows on channel 4/5/6 ...
 *  AudioFocusRequest { RELEASE }                  18 -->
 *                              <-- 19   AudioFocusNotification { LOSS }
 * ```
 *
 * ## Which message the request actually is
 *
 * aasdk parses id 18 as `AudioFocusRequest { required AudioFocusRequestType
 * audio_focus_type = 1 }`
 * (`aasdk/src/Channel/Control/ControlServiceChannel.cpp` L213-L215, L264-L273);
 * aa-proxy-rs's pretty-printer parses the same id as
 * `AudioFocusRequestNotification { required AudioFocusRequestType request = 1 }`
 * (`aa-proxy-rs/src/mitm_prettyprint.rs` L843). Both messages are a single
 * required enum in field 1, so the encoding is byte-identical and the
 * disagreement is only about which schema name is right. Headway follows aasdk
 * and uses `AudioFocusRequest`; a reader diffing against aa-proxy-rs should
 * expect the other name for the same two bytes.
 *
 * ## Message flags
 *
 * Encrypted, and `MessageType::SPECIFIC` — i.e. *without* the frame's control
 * flag, despite travelling on the control channel. That is how aasdk builds the
 * notification (`ControlServiceChannel.cpp` L89-L103: `ENCRYPTED`, `SPECIFIC`),
 * and the request is built the same way by every send on that channel that is
 * not a channel-open response. The control *channel* and the control *flag* are
 * unrelated things; see [dev.headway.protocol.framing.FrameHeader].
 *
 * ## Ownership of the connection
 *
 * Like [VideoChannel] and [AudioChannel] this reads [connection] directly, and
 * a session that runs focus alongside media needs a demultiplexer above
 * [FramedConnection]. Unlike those two, a message for another channel is
 * *surfaced* here as [Event.Foreign] rather than refused: the control channel is
 * where a session's shared traffic lives, so a caller polling for focus is the
 * most likely thing to see an audio ack that belongs elsewhere, and it needs to
 * be able to hand it on.
 */
class AudioFocus(
    private val connection: MessageChannel,
    /** Always 0. The control channel is the one channel id AAP fixes. */
    val channelId: Int = ChannelId.CONTROL.id,
    /** Narrates each step; wired to the debug log export in the app. */
    private val onStep: (String) -> Unit = {},
) {

    /** Something that arrived while waiting on focus. */
    sealed interface Event {
        /** A focus state from the head unit, control message id 19. */
        data class Notified(val state: AudioFocusState) : Event

        /**
         * A focus *request*, control message id 18.
         *
         * Headway is the phone, so it sends these rather than receiving them.
         * Surfaced rather than dropped because seeing one means the peer thinks
         * it is the phone — a role mix-up worth being able to diagnose.
         */
        data class Requested(val type: AudioFocusRequestType) : Event

        /** A control-channel message this class does not implement (ping, byebye, …). */
        data class Unhandled(val messageId: Int, val payload: ByteArray) : Event

        /** A message for another channel, to be handed to whatever owns it. */
        data class Foreign(val message: AapMessage) : Event
    }

    /** Latest state the head unit reported, or null if it has reported none. */
    var state: AudioFocusState? = null
        private set

    /** Every state the head unit has reported, in arrival order. */
    private val received = mutableListOf<AudioFocusState>()
    val notifications: List<AudioFocusState> get() = received.toList()

    /** Every request sent, in send order. */
    private val sent = mutableListOf<AudioFocusRequestType>()
    val requests: List<AudioFocusRequestType> get() = sent.toList()

    /** True when the last notification was a grant covering [stream]. */
    fun holds(stream: AudioStreamType): Boolean = state?.allows(stream) ?: false

    // --- requests -----------------------------------------------------------

    /**
     * Sends `AudioFocusRequest { audio_focus_type = [type] }`, control message
     * id 18.
     *
     * The four request types are
     * `aasdk/protobuf/aap_protobuf/service/control/message/AudioFocusRequestType.proto`
     * L5-L11: `GAIN` (1), `GAIN_TRANSIENT` (2), `GAIN_TRANSIENT_MAY_DUCK` (3),
     * `RELEASE` (4). openauto treats exactly one of them specially — `RELEASE`
     * is answered with `LOSS`, everything else with `GAIN`
     * (`openauto/src/autoapp/Service/AndroidAutoEntity.cpp` L236-L246) — so a
     * head unit collapsing the three gain flavours into one grant is normal and
     * the phone must not assume the state it gets back mirrors the type it asked
     * for.
     */
    suspend fun request(type: AudioFocusRequestType) {
        connection.send(requestMessage(type, channelId))
        sent += type
        onStep("audio focus requested: ${type.name}")
    }

    /**
     * Focus for the length of one prompt.
     *
     * @param mayDuck when true, asks with `GAIN_TRANSIENT_MAY_DUCK`, which in
     *   the Android vocabulary these names come from means the current holder
     *   may lower its volume instead of stopping. That is the right request for
     *   a spoken navigation or voice-assistant prompt over the car's radio.
     */
    suspend fun requestTransient(mayDuck: Boolean = true) = request(
        if (mayDuck) {
            AudioFocusRequestType.AUDIO_FOCUS_GAIN_TRANSIENT_MAY_DUCK
        } else {
            AudioFocusRequestType.AUDIO_FOCUS_GAIN_TRANSIENT
        }
    )

    /**
     * Gives focus back, so the car's own source can resume.
     *
     * Skipping this after a transient grant is the failure the Phase 4 criterion
     * is really about: the prompt finishes, the radio stays ducked, and nothing
     * on the wire says why.
     */
    suspend fun release() = request(AudioFocusRequestType.AUDIO_FOCUS_RELEASE)

    // --- receive ------------------------------------------------------------

    /**
     * Reads until the head unit reports a focus state, and returns it.
     *
     * Other control-channel traffic is skipped, not failed on. A
     * [Event.Foreign] message would be *lost* here, so this refuses to swallow
     * one: it throws, naming the router that does not exist yet, which is the
     * same bargain [VideoChannel] strikes.
     */
    suspend fun awaitNotification(): AudioFocusState {
        while (true) {
            when (val event = receiveEvent()) {
                is Event.Notified -> return event.state
                is Event.Foreign -> throw AudioFocusException(
                    "message for ${ChannelId.describe(event.message.channelId)} arrived while " +
                        "waiting for an audio focus notification; a session with more than one " +
                        "open channel needs a router above FramedConnection"
                )

                else -> Unit
            }
        }
    }

    /** Reads exactly one message and decodes it, updating [state] as a side effect. */
    suspend fun receiveEvent(): Event {
        val message = connection.receive()
        if (message.channelId != channelId) return Event.Foreign(message)

        return when (message.messageId) {
            ControlMessageType.AUDIO_FOCUS_NOTIFICATION.id -> {
                val parsed = parseNotification(message.payload)
                received += parsed
                state = parsed
                onStep("audio focus: ${parsed.state.name}${if (parsed.unsolicited) " (unsolicited)" else ""}")
                Event.Notified(parsed)
            }

            ControlMessageType.AUDIO_FOCUS_REQUEST.id ->
                Event.Requested(parseRequest(message.payload))

            else -> Event.Unhandled(message.messageId, message.payload)
        }
    }

    companion object {
        /**
         * Builds the phone's request. Exposed so the head-unit emulator and a
         * future control-channel router encode it the same way this does.
         */
        fun requestMessage(
            type: AudioFocusRequestType,
            channelId: Int = ChannelId.CONTROL.id,
        ): AapMessage = specific(
            channelId,
            ControlMessageType.AUDIO_FOCUS_REQUEST.id,
            AudioFocusRequest.newBuilder().setAudioFocusType(type).build().toByteArray(),
        )

        /**
         * Builds the head unit's notification.
         *
         * `unsolicited` is left unset when false rather than written as
         * `false`, matching openauto, which sets `focus_state` and nothing else
         * (`openauto/src/autoapp/Service/AndroidAutoEntity.cpp` L245-L246).
         * Writing the default explicitly would put two extra bytes on the wire
         * that no reference emits.
         */
        fun notificationMessage(
            state: AudioFocusStateType,
            unsolicited: Boolean = false,
            channelId: Int = ChannelId.CONTROL.id,
        ): AapMessage {
            val notification = AudioFocusNotification.newBuilder().setFocusState(state)
            if (unsolicited) notification.setUnsolicited(true)
            return specific(
                channelId,
                ControlMessageType.AUDIO_FOCUS_NOTIFICATION.id,
                notification.build().toByteArray(),
            )
        }

        fun parseRequest(payload: ByteArray): AudioFocusRequestType =
            AudioFocusRequest.parseFrom(payload).audioFocusType

        fun parseNotification(payload: ByteArray): AudioFocusState {
            val notification = AudioFocusNotification.parseFrom(payload)
            return AudioFocusState(
                state = notification.focusState,
                unsolicited = notification.hasUnsolicited() && notification.unsolicited,
            )
        }

        /**
         * The head unit's answer to [type] as openauto computes it: `RELEASE`
         * becomes `LOSS`, everything else becomes `GAIN`
         * (`openauto/src/autoapp/Service/AndroidAutoEntity.cpp` L236-L240).
         *
         * Kept here, next to the phone's side of the exchange, because it is the
         * only concrete head-unit policy any reference implements and it is what
         * the emulator has to imitate to be worth testing against. A real unit
         * is free to answer `GAIN_MEDIA_ONLY` or refuse outright.
         */
        fun openautoResponseTo(type: AudioFocusRequestType): AudioFocusStateType =
            if (type == AudioFocusRequestType.AUDIO_FOCUS_RELEASE) {
                AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS
            } else {
                AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN
            }

        private fun specific(channelId: Int, messageId: Int, payload: ByteArray) = AapMessage(
            channelId = channelId,
            control = false,
            encrypted = true,
            messageId = messageId,
            payload = payload,
        )
    }
}

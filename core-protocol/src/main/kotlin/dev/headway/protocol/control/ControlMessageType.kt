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

package dev.headway.protocol.control

/**
 * Message ids on the control channel (channel 0).
 *
 * Transcribed verbatim from
 * `aasdk/protobuf/aap_protobuf/service/control/ControlMessageType.proto`.
 *
 * Note the gap at 10 — it is absent from the enum in the reference, not omitted
 * here.
 */
enum class ControlMessageType(val id: Int) {
    VERSION_REQUEST(1),
    VERSION_RESPONSE(2),
    ENCAPSULATED_SSL(3),
    AUTH_COMPLETE(4),
    SERVICE_DISCOVERY_REQUEST(5),
    SERVICE_DISCOVERY_RESPONSE(6),
    CHANNEL_OPEN_REQUEST(7),
    CHANNEL_OPEN_RESPONSE(8),
    CHANNEL_CLOSE_NOTIFICATION(9),
    PING_REQUEST(11),
    PING_RESPONSE(12),
    NAV_FOCUS_REQUEST(13),
    NAV_FOCUS_NOTIFICATION(14),
    BYEBYE_REQUEST(15),
    BYEBYE_RESPONSE(16),
    VOICE_SESSION_NOTIFICATION(17),
    AUDIO_FOCUS_REQUEST(18),
    AUDIO_FOCUS_NOTIFICATION(19),
    CAR_CONNECTED_DEVICES_REQUEST(20),
    CAR_CONNECTED_DEVICES_RESPONSE(21),
    USER_SWITCH_REQUEST(22),
    BATTERY_STATUS_NOTIFICATION(23),
    CALL_AVAILABILITY_STATUS(24),
    USER_SWITCH_RESPONSE(25),
    SERVICE_DISCOVERY_UPDATE(26),
    UNEXPECTED_MESSAGE(255),
    FRAMING_ERROR(65535),
    ;

    companion object {
        private val byId = entries.associateBy(ControlMessageType::id)

        fun fromId(id: Int): ControlMessageType? = byId[id]

        /** Legible label for logs; unknown ids must stay diagnosable. */
        fun describe(id: Int): String = byId[id]?.name ?: "UNKNOWN_CONTROL(0x%04x)".format(id)
    }
}

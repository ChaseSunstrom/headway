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

import dev.headway.protocol.framing.AapMessage
import dev.headway.protocol.framing.ChannelId

/**
 * The plaintext version exchange that opens every AAP session, before TLS.
 *
 * These two messages are **not** protobuf — they are raw big-endian 16-bit
 * fields, which is why they are hand-coded here while every other control
 * message is generated from a `.proto`.
 *
 * Wire format (`aasdk/src/Channel/Control/ControlServiceChannel.cpp` L37-L51 for
 * the request, L238-L251 for parsing the response):
 *
 * ```text
 * VersionRequest   payload: [id=0x0001][major u16][minor u16]
 * VersionResponse  payload: [id=0x0002][major u16][minor u16][status u16]
 * ```
 *
 * ## Frame flags
 *
 * Both go out as BULK, PLAIN, and **`MessageType::SPECIFIC`** — i.e. flags
 * `0x03`, with bit 2 *clear* despite travelling on the control channel
 * (`ControlServiceChannel.cpp` L37-L51 constructs them with
 * `EncryptionType::PLAIN, MessageType::SPECIFIC`).
 *
 * This is worth pausing on: the bit named `CONTROL` in aasdk is not "this is on
 * the control channel". Channel 0 messages carry it clear. Whatever the bit
 * distinguishes, it is not the control channel, and any implementation that sets
 * it because the message is a control message will produce frames a head unit
 * rejects.
 */
object VersionHandshake {

    /**
     * The protocol version Headway announces.
     *
     * aasdk declares 1.6 (`aasdk/include/aasdk/Version.hpp` — `AASDK_MAJOR = 1`,
     * `AASDK_MINOR = 6`); AACS sends 1.1
     * (`AACS/AAClient/src/AaCommunicator.cpp` L56) and its phone side answers
     * 1.5 while accepting any major == 1
     * (`AACS/AAServer/src/AaCommunicator.cpp` L75-L92).
     *
     * We follow aasdk, per CLAUDE.md's direction to implement aasdk-documented
     * behaviour where references differ. The spread across references suggests
     * head units are lenient about the minor, but that is an inference and it is
     * not relied upon.
     */
    const val MAJOR: Int = 1
    const val MINOR: Int = 6

    /** Versions are compatible. Source: `AACS/AAClient/src/AaCommunicator.cpp` L248-L254. */
    const val STATUS_MATCH: Int = 0

    /**
     * No compatible version; the session must be abandoned.
     *
     * Carried as `0xFFFF` — the references treat it as a signed -1 in a 16-bit
     * field (`openauto/src/autoapp/Service/AndroidAutoEntity.cpp` L109-L120
     * quits on `STATUS_NO_COMPATIBLE_VERSION`).
     */
    const val STATUS_NO_COMPATIBLE_VERSION: Int = 0xFFFF

    data class Version(val major: Int, val minor: Int) {
        override fun toString(): String = "$major.$minor"
    }

    data class VersionResponse(val version: Version, val status: Int) {
        val compatible: Boolean get() = status == STATUS_MATCH
    }

    /** Builds the head unit's opening `VersionRequest`. */
    fun request(major: Int = MAJOR, minor: Int = MINOR): AapMessage = AapMessage(
        channelId = ChannelId.CONTROL.id,
        // Bit 2 clear -- see the note above; these are MessageType::SPECIFIC.
        control = false,
        encrypted = false,
        messageId = ControlMessageType.VERSION_REQUEST.id,
        payload = u16(major) + u16(minor),
    )

    /** Builds the phone's `VersionResponse`. */
    fun response(
        major: Int = MAJOR,
        minor: Int = MINOR,
        status: Int = STATUS_MATCH,
    ): AapMessage = AapMessage(
        channelId = ChannelId.CONTROL.id,
        control = false,
        encrypted = false,
        messageId = ControlMessageType.VERSION_RESPONSE.id,
        payload = u16(major) + u16(minor) + u16(status),
    )

    /** Parses a `VersionRequest` payload (message id already stripped). */
    fun parseRequest(payload: ByteArray): Version {
        require(payload.size >= 4) {
            "VersionRequest payload is ${payload.size} bytes, expected at least 4"
        }
        return Version(readU16(payload, 0), readU16(payload, 2))
    }

    /**
     * Parses a `VersionResponse` payload (message id already stripped).
     *
     * AACS's head-unit side requires exactly 8 payload bytes *including* the
     * message id (`AAClient/src/AaCommunicator.cpp` L248-L254), i.e. 6 here.
     */
    fun parseResponse(payload: ByteArray): VersionResponse {
        require(payload.size >= 6) {
            "VersionResponse payload is ${payload.size} bytes, expected at least 6"
        }
        return VersionResponse(
            version = Version(readU16(payload, 0), readU16(payload, 2)),
            status = readU16(payload, 4),
        )
    }

    private fun u16(value: Int): ByteArray {
        require(value in 0..0xFFFF) { "value does not fit in 16 bits: $value" }
        return byteArrayOf((value ushr 8).toByte(), value.toByte())
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
}

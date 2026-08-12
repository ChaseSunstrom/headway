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

import dev.headway.protocol.framing.ChannelId
import dev.headway.protocol.framing.FrameType
import dev.headway.protocol.framing.MessageFragmenter
import dev.headway.protocol.framing.toHex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Byte fixtures for the version exchange — the first bytes ever sent on an AAP
 * session, and therefore the first thing to compare against a real capture.
 */
class VersionHandshakeTest {

    /** Renders a message exactly as it goes on the wire, header included. */
    private fun wireBytes(message: dev.headway.protocol.framing.AapMessage): String {
        val fragment = MessageFragmenter().fragment(message).single()
        val header = fragment.headerFor(fragment.plaintext.size)
        return (header.encode() + fragment.plaintext).toHex()
    }

    @Test
    fun `the version request goes out as the documented bytes`() {
        // channel 0x00 | flags 0x03 | length 0x0006 | id 0x0001 | major 1 | minor 6
        assertEquals("00 03 00 06 00 01 00 01 00 06", wireBytes(VersionHandshake.request()))
    }

    @Test
    fun `the version response goes out as the documented bytes`() {
        // channel 0x00 | flags 0x03 | length 0x0008 | id 0x0002 | major | minor | status
        assertEquals("00 03 00 08 00 02 00 01 00 06 00 00", wireBytes(VersionHandshake.response()))
    }

    /**
     * The bit aasdk names `CONTROL` is *clear* on control-channel handshake
     * messages: `ControlServiceChannel.cpp` L37-L51 builds them with
     * `MessageType::SPECIFIC`. Setting it because "this is a control message"
     * produces frames a real head unit rejects, so pin it.
     */
    @Test
    fun `handshake frames are BULK PLAIN and not flagged control`() {
        for (message in listOf(VersionHandshake.request(), VersionHandshake.response())) {
            val fragment = MessageFragmenter().fragment(message).single()
            val header = fragment.headerFor(fragment.plaintext.size)

            assertEquals(ChannelId.CONTROL.id, header.channelId)
            assertEquals(FrameType.BULK, header.frameType)
            assertFalse(header.encrypted, "the version exchange precedes TLS")
            assertFalse(header.control, "aasdk builds these with MessageType::SPECIFIC")
            assertEquals(0x03, header.flags)
        }
    }

    @Test
    fun `message ids match the aasdk enum`() {
        // aasdk/protobuf/aap_protobuf/service/control/ControlMessageType.proto
        assertEquals(1, ControlMessageType.VERSION_REQUEST.id)
        assertEquals(2, ControlMessageType.VERSION_RESPONSE.id)
        assertEquals(3, ControlMessageType.ENCAPSULATED_SSL.id)
        assertEquals(4, ControlMessageType.AUTH_COMPLETE.id)
        assertEquals(5, ControlMessageType.SERVICE_DISCOVERY_REQUEST.id)
        assertEquals(6, ControlMessageType.SERVICE_DISCOVERY_RESPONSE.id)
        assertEquals(11, ControlMessageType.PING_REQUEST.id)
        assertEquals(255, ControlMessageType.UNEXPECTED_MESSAGE.id)
        assertEquals(65535, ControlMessageType.FRAMING_ERROR.id)
        // 10 is absent from the reference enum, not omitted here.
        assertTrue(ControlMessageType.fromId(10) == null)
    }

    @Test
    fun `announced version follows aasdk`() {
        // aasdk/include/aasdk/Version.hpp: AASDK_MAJOR = 1, AASDK_MINOR = 6
        assertEquals(1, VersionHandshake.MAJOR)
        assertEquals(6, VersionHandshake.MINOR)
    }

    @Test
    fun `request and response parse back`() {
        val request = VersionHandshake.parseRequest(VersionHandshake.request(1, 6).payload)
        assertEquals(1, request.major)
        assertEquals(6, request.minor)

        val ok = VersionHandshake.parseResponse(VersionHandshake.response(1, 6, 0).payload)
        assertTrue(ok.compatible)

        val bad = VersionHandshake.parseResponse(
            VersionHandshake.response(1, 6, VersionHandshake.STATUS_NO_COMPATIBLE_VERSION).payload
        )
        assertFalse(bad.compatible)
        assertEquals(0xFFFF, bad.status)
    }
}

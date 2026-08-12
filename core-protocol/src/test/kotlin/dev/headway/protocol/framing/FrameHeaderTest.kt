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

package dev.headway.protocol.framing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Hex helpers keep the fixtures below readable as wire bytes. */
internal fun hex(s: String): ByteArray {
    val clean = s.replace(" ", "").replace("\n", "")
    require(clean.length % 2 == 0) { "odd-length hex: $s" }
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

internal fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it) }

/**
 * Byte-level fixtures for the frame header.
 *
 * Per ADR 0002 these fixtures — not the emulator round-trips — are the real
 * correctness oracle. The emulator shares this code, so a symmetric bug would
 * round-trip cleanly and still be wrong on a real head unit. These expected byte
 * strings are derived by hand from the layout documented in
 * `docs/protocol-notes.md`, which cites aasdk, aa-proxy-rs,
 * WirelessAndroidAutoDongle and AACS.
 */
class FrameHeaderTest {

    @Test
    fun `plain control BULK frame encodes to the documented bytes`() {
        // channel 0 (CONTROL), flags = BULK(0x03) | CONTROL(0x04) = 0x07,
        // payload length 4.
        val header = FrameHeader(
            channelId = ChannelId.CONTROL.id,
            frameType = FrameType.BULK,
            control = true,
            encrypted = false,
            payloadLength = 4,
            totalMessageLength = null,
        )
        assertEquals("00 07 00 04", header.encode().toHex())
        assertEquals(FrameHeader.BASE_SIZE, header.encodedSize)
    }

    @Test
    fun `encrypted video FIRST frame carries the 8 byte extended header`() {
        // channel 3 (MEDIA_SINK_VIDEO), flags = FIRST(0x01) | ENCRYPTED(0x08) = 0x09.
        // SPECIFIC, so bit 2 stays clear. payload 0x4000, total 0x00005000.
        val header = FrameHeader(
            channelId = ChannelId.MEDIA_SINK_VIDEO.id,
            frameType = FrameType.FIRST,
            control = false,
            encrypted = true,
            payloadLength = 0x4000,
            totalMessageLength = 0x5000L,
        )
        assertEquals("03 09 40 00 00 00 50 00", header.encode().toHex())
        assertEquals(FrameHeader.EXTENDED_SIZE, header.encodedSize)
    }

    @Test
    fun `encrypted LAST frame has no extended header`() {
        val header = FrameHeader(
            channelId = ChannelId.MEDIA_SINK_VIDEO.id,
            frameType = FrameType.LAST,
            control = false,
            encrypted = true,
            payloadLength = 0x1000,
            totalMessageLength = null,
        )
        assertEquals("03 0a 10 00", header.encode().toHex())
    }

    @Test
    fun `decode is the inverse of encode for every flag combination`() {
        for (type in FrameType.entries) {
            for (control in listOf(false, true)) {
                for (encrypted in listOf(false, true)) {
                    val header = FrameHeader(
                        channelId = 7,
                        frameType = type,
                        control = control,
                        encrypted = encrypted,
                        payloadLength = 0x1234,
                        totalMessageLength = if (type == FrameType.FIRST) 0x99999L else null,
                    )
                    assertEquals(header, FrameHeader.decode(header.encode()), "roundtrip $header")
                }
            }
        }
    }

    /**
     * The single most dangerous bug in this layer: BULK is FIRST|LAST, so a bit
     * test against FIRST matches BULK too and would add four phantom bytes to
     * every single-frame message. Every reference uses masked equality —
     * `aa-proxy-rs/src/mitm.rs` L3925, `proxyHandler.cpp` L51,
     * `AACS/AAServer/src/AaCommunicator.cpp` L421.
     */
    @Test
    fun `BULK does not carry the extended size field even though it contains the FIRST bit`() {
        assertTrue(FrameType.BULK.bits and FrameType.FIRST.bits != 0, "precondition: BULK contains FIRST")

        assertTrue(FrameHeader.hasExtendedSize(FrameType.FIRST.bits))
        assertFalse(FrameHeader.hasExtendedSize(FrameType.BULK.bits))
        assertFalse(FrameHeader.hasExtendedSize(FrameType.LAST.bits))
        assertFalse(FrameHeader.hasExtendedSize(FrameType.MIDDLE.bits))

        // And with the other flag bits set, which must not disturb the masking.
        val bulkControlEncrypted =
            FrameType.BULK.bits or FrameHeader.FLAG_CONTROL or FrameHeader.FLAG_ENCRYPTED
        assertEquals(0x0f, bulkControlEncrypted)
        assertFalse(FrameHeader.hasExtendedSize(bulkControlEncrypted))

        val firstControlEncrypted =
            FrameType.FIRST.bits or FrameHeader.FLAG_CONTROL or FrameHeader.FLAG_ENCRYPTED
        assertEquals(0x0d, firstControlEncrypted)
        assertTrue(FrameHeader.hasExtendedSize(firstControlEncrypted))
    }

    @Test
    fun `decodes a BULK frame without consuming payload as a size field`() {
        // 00 0f 00 02 aa bb  — control+encrypted BULK, 2 payload bytes.
        // A bit-test implementation would read "aa bb" as part of a total size.
        val header = FrameHeader.decode(hex("00 0f 00 02 aa bb"))
        assertEquals(FrameType.BULK, header.frameType)
        assertEquals(2, header.payloadLength)
        assertNull(header.totalMessageLength)
        assertEquals(FrameHeader.BASE_SIZE, header.encodedSize)
        assertTrue(header.control)
        assertTrue(header.encrypted)
    }

    @Test
    fun `frame type is read from the low two bits only`() {
        assertEquals(FrameType.MIDDLE, FrameType.fromFlags(0x00))
        assertEquals(FrameType.FIRST, FrameType.fromFlags(0x01))
        assertEquals(FrameType.LAST, FrameType.fromFlags(0x02))
        assertEquals(FrameType.BULK, FrameType.fromFlags(0x03))
        // High bits are flags, not frame type.
        assertEquals(FrameType.LAST, FrameType.fromFlags(0x0a))
        assertEquals(FrameType.MIDDLE, FrameType.fromFlags(0x0c))
    }

    @Test
    fun `sizes at the 16 bit boundary encode correctly`() {
        val header = FrameHeader(
            channelId = 0xFF,
            frameType = FrameType.LAST,
            control = false,
            encrypted = false,
            payloadLength = 0xFFFF,
            totalMessageLength = null,
        )
        assertEquals("ff 02 ff ff", header.encode().toHex())
        assertEquals(0xFFFF, FrameHeader.decode(header.encode()).payloadLength)
    }

    @Test
    fun `total size uses the full unsigned 32 bit range without sign extension`() {
        // The field is an unsigned 32-bit integer. 0x80000001 exceeds Int.MAX_VALUE,
        // so a signed model would decode it negative -- and a decoder that threw
        // on it would kill the read loop over a frame that is well-formed on the
        // wire. Rejecting an implausibly large message is MessageAssembler's job,
        // and it reports a protocol error rather than crashing.
        val bytes = hex("03 01 00 00 80 00 00 01")
        val header = FrameHeader.decode(bytes)
        assertEquals(0x80000001L, header.totalMessageLength)
        assertEquals(bytes.toHex(), header.encode().toHex())
    }

    @Test
    fun `total size accepts the largest representable unsigned value`() {
        val header = FrameHeader.decode(hex("03 01 00 00 ff ff ff ff"))
        assertEquals(FrameHeader.MAX_TOTAL_MESSAGE_LENGTH, header.totalMessageLength)
        assertEquals("03 01 00 00 ff ff ff ff", header.encode().toHex())
    }

    @Test
    fun `rejects a header shorter than four bytes`() {
        assertThrows(IllegalArgumentException::class.java) { FrameHeader.decode(hex("00 07 00")) }
    }

    @Test
    fun `rejects a FIRST header truncated before its total size`() {
        assertThrows(IllegalArgumentException::class.java) { FrameHeader.decode(hex("03 01 40 00 00")) }
    }

    @Test
    fun `rejects a total size on a non FIRST frame`() {
        assertThrows(IllegalArgumentException::class.java) {
            FrameHeader(
                channelId = 0,
                frameType = FrameType.BULK,
                control = true,
                encrypted = false,
                payloadLength = 1,
                totalMessageLength = 10,
            )
        }
    }

    @Test
    fun `rejects a FIRST frame without a total size`() {
        assertThrows(IllegalArgumentException::class.java) {
            FrameHeader(
                channelId = 0,
                frameType = FrameType.FIRST,
                control = true,
                encrypted = false,
                payloadLength = 1,
                totalMessageLength = null,
            )
        }
    }

    @Test
    fun `rejects a payload length that cannot fit the 16 bit size field`() {
        assertThrows(IllegalArgumentException::class.java) {
            FrameHeader(
                channelId = 0,
                frameType = FrameType.BULK,
                control = true,
                encrypted = false,
                payloadLength = 0x10000,
                totalMessageLength = null,
            )
        }
    }

    @Test
    fun `decodes at an offset within a larger buffer`() {
        val header = FrameHeader.decode(hex("de ad 00 07 00 04"), offset = 2)
        assertEquals(0, header.channelId)
        assertEquals(FrameType.BULK, header.frameType)
        assertEquals(4, header.payloadLength)
    }

    @Test
    fun `channel ids match the aasdk enum`() {
        // aasdk/include/aasdk/Messenger/ChannelId.hpp L30-L51
        assertEquals(0, ChannelId.CONTROL.id)
        assertEquals(1, ChannelId.SENSOR.id)
        assertEquals(3, ChannelId.MEDIA_SINK_VIDEO.id)
        assertEquals(4, ChannelId.MEDIA_SINK_MEDIA_AUDIO.id)
        assertEquals(5, ChannelId.MEDIA_SINK_GUIDANCE_AUDIO.id)
        assertEquals(6, ChannelId.MEDIA_SINK_SYSTEM_AUDIO.id)
        assertEquals(8, ChannelId.INPUT_SOURCE.id)
        assertEquals(9, ChannelId.MEDIA_SOURCE_MICROPHONE.id)
        assertEquals(18, ChannelId.WIFI_PROJECTION.id)
        assertEquals(255, ChannelId.NONE.id)
    }

    @Test
    fun `unknown channel ids stay legible rather than being dropped`() {
        assertEquals("CONTROL", ChannelId.describe(0))
        assertEquals("UNKNOWN(99)", ChannelId.describe(99))
        assertNull(ChannelId.fromId(99))
        // And decoding one must not throw — an unrecognised channel is logged
        // and skipped, never fatal.
        assertEquals(99, FrameHeader.decode(hex("63 03 00 00")).channelId)
    }
}

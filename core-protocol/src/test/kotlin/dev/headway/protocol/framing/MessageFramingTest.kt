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

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class MessageFramingTest {

    private fun message(
        channel: Int = ChannelId.CONTROL.id,
        messageId: Int = 0x0001,
        payload: ByteArray = ByteArray(0),
        control: Boolean = true,
        encrypted: Boolean = false,
    ) = AapMessage(channel, control, encrypted, messageId, payload)

    // --- message id placement ------------------------------------------------

    @Test
    fun `the message id is a big endian 16 bit prefix on the payload`() {
        // aasdk/src/Messenger/MessageId.cpp L30-L42
        val msg = message(messageId = 0x1234, payload = hex("de ad be ef"))
        assertEquals("12 34 de ad be ef", msg.wirePayload().toHex())
    }

    @Test
    fun `receiving strips the message id back off`() {
        val parsed = AapMessage.fromWirePayload(
            channelId = ChannelId.CONTROL.id,
            control = true,
            encrypted = false,
            wirePayload = hex("00 07 01 02 03"),
        )
        assertEquals(0x0007, parsed.messageId)
        assertEquals("01 02 03", parsed.payload.toHex())
    }

    @Test
    fun `a message with no protobuf body is still valid`() {
        // Several control messages are bare ids with an empty body.
        val parsed = AapMessage.fromWirePayload(0, true, false, hex("00 0b"))
        assertEquals(0x000b, parsed.messageId)
        assertEquals(0, parsed.payload.size)
    }

    @Test
    fun `a payload too short to hold a message id is rejected`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            AapMessage.fromWirePayload(0, true, false, hex("00"))
        }
        assertTrue(e.message!!.contains("too short"), e.message)
    }

    // --- fragmentation -------------------------------------------------------

    @Test
    fun `a short message goes out as a single BULK frame`() {
        // aasdk/src/Messenger/MessageOutStream.cpp L44-L50
        val fragments = MessageFragmenter().fragment(message(payload = ByteArray(10)))
        assertEquals(1, fragments.size)
        assertEquals(FrameType.BULK, fragments[0].frameType)
        assertNull(fragments[0].totalPlaintextLength)
    }

    @Test
    fun `a long message is split FIRST MIDDLE LAST with the total on FIRST only`() {
        val payload = Random(3).nextBytes(40_000)
        val msg = message(payload = payload)
        val fragments = MessageFragmenter(maxFragmentSize = 16_384).fragment(msg)

        // 40_000 + 2 bytes of message id = 40_002 -> 16384 + 16384 + 7234
        assertEquals(3, fragments.size)
        assertEquals(listOf(FrameType.FIRST, FrameType.MIDDLE, FrameType.LAST), fragments.map { it.frameType })
        assertEquals(listOf(16_384, 16_384, 7_234), fragments.map { it.plaintext.size })

        assertEquals(40_002, fragments[0].totalPlaintextLength)
        assertNull(fragments[1].totalPlaintextLength)
        assertNull(fragments[2].totalPlaintextLength)
    }

    @Test
    fun `a message exactly at the fragment size still fits one BULK frame`() {
        // Boundary: wire payload == maxFragmentSize must not split.
        val payload = ByteArray(16_384 - AapMessage.MESSAGE_ID_SIZE)
        val fragments = MessageFragmenter(maxFragmentSize = 16_384).fragment(message(payload = payload))
        assertEquals(1, fragments.size)
        assertEquals(FrameType.BULK, fragments[0].frameType)
    }

    @Test
    fun `one byte over the fragment size splits into two frames`() {
        val payload = ByteArray(16_384 - AapMessage.MESSAGE_ID_SIZE + 1)
        val fragments = MessageFragmenter(maxFragmentSize = 16_384).fragment(message(payload = payload))
        assertEquals(2, fragments.size)
        assertEquals(listOf(FrameType.FIRST, FrameType.LAST), fragments.map { it.frameType })
        assertEquals(1, fragments[1].plaintext.size)
    }

    @Test
    fun `plaintext messages fragment too`() {
        // AACS fragments only encrypted messages (AAServer/AaCommunicator.cpp
        // L377), so a >64 KiB plaintext message silently corrupts its own length
        // field there. aasdk fragments unconditionally; we follow aasdk.
        val fragments = MessageFragmenter().fragment(
            message(payload = ByteArray(100_000), encrypted = false)
        )
        assertTrue(fragments.size > 1, "plaintext message must still be fragmented")
        assertTrue(fragments.all { !it.encrypted })
    }

    @Test
    fun `fragments preserve the total plaintext length across the whole message`() {
        val fragments = MessageFragmenter(maxFragmentSize = 1000).fragment(
            message(payload = ByteArray(5_000))
        )
        val total = fragments.sumOf { it.plaintext.size }
        assertEquals(5_002, total)
        assertEquals(total, fragments.first().totalPlaintextLength)
    }

    @Test
    fun `default fragment size matches aasdk`() {
        // aasdk/include/aasdk/Messenger/MessageOutStream.hpp L62
        assertEquals(0x4000, MessageFragmenter.DEFAULT_MAX_FRAGMENT_SIZE)
    }

    // --- reassembly ----------------------------------------------------------

    /** Feeds fragments through the assembler as a conformant peer would. */
    private fun roundTrip(msg: AapMessage, fragmentSize: Int): AapMessage {
        val assembler = MessageAssembler()
        var out: AapMessage? = null
        for (f in MessageFragmenter(maxFragmentSize = fragmentSize).fragment(msg)) {
            val result = assembler.onFrame(f.headerFor(f.plaintext.size), f.plaintext)
            if (result != null) {
                assertNull(out, "assembler produced more than one message")
                out = result
            }
        }
        assertNotNull(out, "assembler produced no message")
        return out!!
    }

    @Test
    fun `fragment then reassemble round trips a large payload`() {
        val payload = Random(5).nextBytes(100_000)
        val original = message(channel = ChannelId.MEDIA_SINK_VIDEO.id, messageId = 0x0006, payload = payload, control = false)
        val restored = roundTrip(original, fragmentSize = 4_096)

        assertEquals(original.channelId, restored.channelId)
        assertEquals(original.messageId, restored.messageId)
        assertEquals(original.control, restored.control)
        assertArrayEquals(payload, restored.payload)
    }

    @Test
    fun `round trips across a range of payload and fragment sizes`() {
        for (fragmentSize in listOf(3, 16, 1000, 16_384)) {
            for (payloadSize in listOf(0, 1, 2, 3, 100, 4095, 4096, 4097)) {
                val payload = Random(payloadSize).nextBytes(payloadSize)
                val restored = roundTrip(message(payload = payload), fragmentSize)
                assertArrayEquals(payload, restored.payload, "size=$payloadSize frag=$fragmentSize")
            }
        }
    }

    @Test
    fun `interleaved channels reassemble independently`() {
        // AAP multiplexes channels on one connection, so a video FIRST can be
        // followed by a control BULK and then the video LAST.
        val assembler = MessageAssembler()
        val video = message(channel = ChannelId.MEDIA_SINK_VIDEO.id, messageId = 6, payload = Random(1).nextBytes(3_000), control = false)
        val videoFragments = MessageFragmenter(maxFragmentSize = 1_024).fragment(video)
        assertTrue(videoFragments.size >= 3)

        // video FIRST
        assertNull(assembler.onFrame(videoFragments[0].headerFor(videoFragments[0].plaintext.size), videoFragments[0].plaintext))

        // an entire control message arrives in the middle of it
        val control = message(messageId = 0x0003, payload = hex("aa bb"))
        val controlFragment = MessageFragmenter().fragment(control).single()
        val gotControl = assembler.onFrame(
            controlFragment.headerFor(controlFragment.plaintext.size),
            controlFragment.plaintext,
        )
        assertNotNull(gotControl)
        assertEquals(0x0003, gotControl!!.messageId)
        assertEquals(1, assembler.pendingChannelCount(), "video message must still be pending")

        // remaining video fragments
        var restored: AapMessage? = null
        for (f in videoFragments.drop(1)) {
            restored = assembler.onFrame(f.headerFor(f.plaintext.size), f.plaintext) ?: restored
        }
        assertArrayEquals(video.payload, restored!!.payload)
        assertEquals(0, assembler.pendingChannelCount())
    }

    @Test
    fun `a MIDDLE frame with nothing in progress is rejected`() {
        // aasdk calls this MESSENGER_INTERTWINED_CHANNELS
        // (MessageInStream.cpp L67-L98).
        val assembler = MessageAssembler()
        val header = FrameHeader(3, FrameType.MIDDLE, false, false, 4, null)
        val e = assertThrows(FramingException::class.java) { assembler.onFrame(header, ByteArray(4)) }
        assertTrue(e.message!!.contains("desynchronised"), e.message)
    }

    @Test
    fun `a LAST frame with nothing in progress is rejected`() {
        val assembler = MessageAssembler()
        val header = FrameHeader(3, FrameType.LAST, false, false, 4, null)
        assertThrows(FramingException::class.java) { assembler.onFrame(header, ByteArray(4)) }
    }

    @Test
    fun `a BULK frame discards a stale partial on the same channel`() {
        val assembler = MessageAssembler()
        val first = FrameHeader(3, FrameType.FIRST, false, false, 4, 9_999L)
        assembler.onFrame(first, ByteArray(4))
        assertEquals(1, assembler.pendingChannelCount())

        val bulk = FrameHeader(3, FrameType.BULK, false, false, 4, null)
        val out = assembler.onFrame(bulk, hex("00 05 ff ff"))
        assertNotNull(out)
        assertEquals(0x0005, out!!.messageId)
        assertEquals(0, assembler.pendingChannelCount())
    }

    @Test
    fun `a message that overruns its advertised total is rejected`() {
        val assembler = MessageAssembler()
        assembler.onFrame(FrameHeader(3, FrameType.FIRST, false, false, 10, 12L), ByteArray(10))
        val e = assertThrows(FramingException::class.java) {
            assembler.onFrame(FrameHeader(3, FrameType.LAST, false, false, 10, null), ByteArray(10))
        }
        assertTrue(e.message!!.contains("overruns"), e.message)
    }

    @Test
    fun `a message that ends short of its advertised total is rejected`() {
        // aasdk would silently accept this -- it terminates on the LAST bit and
        // never reads the total. Checking it catches a desynchronised stream at
        // the frame that broke rather than several messages later.
        val assembler = MessageAssembler()
        assembler.onFrame(FrameHeader(3, FrameType.FIRST, false, false, 10, 100L), ByteArray(10))
        val e = assertThrows(FramingException::class.java) {
            assembler.onFrame(FrameHeader(3, FrameType.LAST, false, false, 5, null), ByteArray(5))
        }
        assertTrue(e.message!!.contains("completed at"), e.message)
    }

    @Test
    fun `an implausibly large advertised total is rejected rather than buffered`() {
        val assembler = MessageAssembler()
        val header = FrameHeader(3, FrameType.FIRST, false, false, 4, 0xFFFF_FFFFL)
        val e = assertThrows(FramingException::class.java) { assembler.onFrame(header, ByteArray(4)) }
        assertTrue(e.message!!.contains("over the"), e.message)
    }

    @Test
    fun `a FIRST frame carrying more than its advertised total is rejected`() {
        val assembler = MessageAssembler()
        val header = FrameHeader(3, FrameType.FIRST, false, false, 50, 10L)
        assertThrows(FramingException::class.java) { assembler.onFrame(header, ByteArray(50)) }
    }

    @Test
    fun `reset clears partial state so a reconnect starts clean`() {
        val assembler = MessageAssembler()
        assembler.onFrame(FrameHeader(3, FrameType.FIRST, false, false, 4, 100L), ByteArray(4))
        assertEquals(1, assembler.pendingChannelCount())

        assembler.reset()
        assertEquals(0, assembler.pendingChannelCount())

        // And the stale partial must not be resumable after the reset.
        assertThrows(FramingException::class.java) {
            assembler.onFrame(FrameHeader(3, FrameType.LAST, false, false, 4, null), ByteArray(4))
        }
    }
}

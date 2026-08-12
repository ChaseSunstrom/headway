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

package dev.headway.protocol.io

import dev.headway.protocol.framing.AapMessage
import dev.headway.protocol.framing.ChannelId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.EOFException

/**
 * A live session has video, three audio channels, input, the microphone and
 * control all reading at once. Without a demultiplexer they race on one
 * `receive()` and each steals messages meant for the others — a failure that
 * only appears under concurrency and presents in a car as an occasional
 * "unexpected message".
 *
 * These tests reproduce that situation directly.
 */
class ChannelDemultiplexerTest {

    /** A transport that replays a scripted byte stream and then blocks. */
    private class ScriptedTransport(script: List<AapMessage>) : Transport {
        private val bytes: ByteArray
        private var offset = 0
        private val exhausted = Channel<Unit>(Channel.RENDEZVOUS)

        init {
            val out = java.io.ByteArrayOutputStream()
            val fragmenter = dev.headway.protocol.framing.MessageFragmenter()
            for (message in script) {
                for (fragment in fragmenter.fragment(message)) {
                    val header = fragment.headerFor(fragment.plaintext.size)
                    out.write(header.encode())
                    out.write(fragment.plaintext)
                }
            }
            bytes = out.toByteArray()
        }

        override suspend fun readFully(length: Int): ByteArray {
            if (offset + length > bytes.size) {
                // Script exhausted: behave like a peer that closed.
                throw EOFException("script exhausted")
            }
            val out = bytes.copyOfRange(offset, offset + length)
            offset += length
            return out
        }

        override suspend fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
        override fun close() { exhausted.close() }
    }

    private fun message(channel: Int, id: Int, body: ByteArray = ByteArray(0)) =
        AapMessage(channel, control = false, encrypted = false, messageId = id, payload = body)

    @Test
    fun `messages reach the channel they are addressed to`() = runBlocking {
        val script = listOf(
            message(ChannelId.MEDIA_SINK_VIDEO.id, 0x8004),
            message(ChannelId.CONTROL.id, 0x000b),
            message(ChannelId.INPUT_SOURCE.id, 0x8001),
            message(ChannelId.MEDIA_SINK_VIDEO.id, 0x8004),
        )
        val demux = ChannelDemultiplexer(FramedConnection(ScriptedTransport(script)))
        val video = demux.channel(ChannelId.MEDIA_SINK_VIDEO.id)
        val control = demux.channel(ChannelId.CONTROL.id)
        val input = demux.channel(ChannelId.INPUT_SOURCE.id)

        launch(Dispatchers.IO) { runCatching { demux.pump() } }

        withTimeout(10_000) {
            assertEquals(0x8004, video.receive().messageId)
            assertEquals(0x000b, control.receive().messageId)
            assertEquals(0x8001, input.receive().messageId)
            assertEquals(0x8004, video.receive().messageId)
        }
    }

    /**
     * The bug the demultiplexer exists to prevent: concurrent readers on one
     * connection stealing each other's messages. Here each channel reads its own
     * queue, so a message can only ever be delivered to its addressee.
     */
    @Test
    fun `concurrent readers never steal each other's messages`() = runBlocking {
        val perChannel = 40
        val channels = listOf(
            ChannelId.MEDIA_SINK_VIDEO.id,
            ChannelId.MEDIA_SINK_MEDIA_AUDIO.id,
            ChannelId.INPUT_SOURCE.id,
            ChannelId.CONTROL.id,
        )
        // Interleave them the way a real session does.
        val script = (0 until perChannel).flatMap { i ->
            channels.map { message(it, id = i) }
        }

        val demux = ChannelDemultiplexer(
            FramedConnection(ScriptedTransport(script)),
            mediaQueueDepth = 256,
        )
        val views = channels.associateWith { demux.channel(it) }
        launch(Dispatchers.IO) { runCatching { demux.pump() } }

        val readers = views.map { (channelId, view) ->
            async(Dispatchers.IO) {
                // Every message this reader sees must be addressed to it, and the
                // ids must arrive in order.
                (0 until perChannel).map {
                    val m = view.receive()
                    assertEquals(channelId, m.channelId, "message delivered to the wrong channel")
                    m.messageId
                }
            }
        }
        withTimeout(30_000) {
            for (reader in readers) {
                assertEquals((0 until perChannel).toList(), reader.await())
            }
        }
        assertEquals(0L, demux.droppedMessages)
    }

    @Test
    fun `a slow consumer drops its own oldest messages rather than stalling the others`() = runBlocking {
        // The whole point of bounded queues plus explicit dropping: a stuck video
        // sink must not freeze control traffic and take the session with it.
        val script = (0 until 60).map { message(ChannelId.MEDIA_SINK_VIDEO.id, id = it) } +
            message(ChannelId.CONTROL.id, 0x000b)

        val demux = ChannelDemultiplexer(
            FramedConnection(ScriptedTransport(script)),
            mediaQueueDepth = 8,
        )
        val video = demux.channel(ChannelId.MEDIA_SINK_VIDEO.id)
        val control = demux.channel(ChannelId.CONTROL.id)

        // Never read video; let its queue overflow.
        launch(Dispatchers.IO) { runCatching { demux.pump() } }

        // Control still gets through.
        assertEquals(0x000b, withTimeout(10_000) { control.receive() }.messageId)
        assertTrue(demux.droppedMessages > 0, "expected video frames to be dropped")

        // And what video does have is the newest, not the oldest.
        val next = withTimeout(10_000) { video.receive() }
        assertTrue(next.messageId >= 52, "expected recent frames, got ${next.messageId}")
    }

    @Test
    fun `a message for an unsubscribed channel is reported rather than fatal`() = runBlocking {
        val seen = mutableListOf<Int>()
        val demux = ChannelDemultiplexer(
            FramedConnection(ScriptedTransport(listOf(message(99, 0x0001), message(ChannelId.CONTROL.id, 0x000b)))),
            onUnroutable = { seen += it.channelId },
        )
        val control = demux.channel(ChannelId.CONTROL.id)
        launch(Dispatchers.IO) { runCatching { demux.pump() } }

        assertEquals(0x000b, withTimeout(10_000) { control.receive() }.messageId)
        assertEquals(listOf(99), seen)
        assertEquals(1L, demux.unroutableMessages)
    }

    @Test
    fun `every subscriber fails promptly when the link ends`() = runBlocking {
        // Reconnection depends on this: a dead link must fail the waiting reads
        // rather than leaving channel coroutines suspended forever.
        val demux = ChannelDemultiplexer(FramedConnection(ScriptedTransport(emptyList())))
        val video = demux.channel(ChannelId.MEDIA_SINK_VIDEO.id)
        val control = demux.channel(ChannelId.CONTROL.id)

        launch(Dispatchers.IO) { runCatching { demux.pump() } }

        withTimeout(10_000) {
            assertThrows(EOFException::class.java) { runBlocking { video.receive() } }
            assertThrows(EOFException::class.java) { runBlocking { control.receive() } }
        }
    }

    @Test
    fun `the TLS session is shared by every channel view`() {
        // Encryption belongs to the link. A view that kept its own cryptor would
        // send plaintext on one channel and ciphertext on another.
        val connection = FramedConnection(ScriptedTransport(emptyList()))
        val demux = ChannelDemultiplexer(connection)
        val video = demux.channel(ChannelId.MEDIA_SINK_VIDEO.id)
        val control = demux.channel(ChannelId.CONTROL.id)

        val cryptor = object : Cryptor {
            override fun encrypt(plaintext: ByteArray) = plaintext
            override fun decrypt(ciphertext: ByteArray) = ciphertext
        }
        control.cryptor = cryptor

        assertEquals(cryptor, video.cryptor, "a channel view must see the link's TLS session")
        assertEquals(cryptor, connection.cryptor)
    }
}

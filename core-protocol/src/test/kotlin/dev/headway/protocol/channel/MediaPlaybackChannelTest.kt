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

import aap_protobuf.service.mediaplayback.message.MediaPlaybackStatusOuterClass.MediaPlaybackStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MediaPlaybackChannelTest {

    @Test
    fun `a status frame carries the ids the schema names`() {
        val message = MediaPlaybackChannel.status(
            channelId = 7,
            status = MediaPlaybackChannel.Status(
                state = MediaPlaybackChannel.Playback.PLAYING,
                source = "Symfonium",
                positionSeconds = 42,
            ),
        )
        assertEquals(7, message.channelId)
        assertEquals(MediaPlaybackMessageId.MEDIA_PLAYBACK_STATUS, message.messageId)
        // Every non-control service message on this link travels inside TLS.
        assertFalse(message.control, "a service message is not a control message")
        assertTrue(message.encrypted, "a service message travels inside the TLS session")

        val decoded = MediaPlaybackChannel.decodeStatus(message.payload)
        assertEquals(MediaPlaybackStatus.State.PLAYING, decoded.state)
        assertEquals("Symfonium", decoded.mediaSource)
        assertEquals(42, decoded.playbackSeconds)
    }

    @Test
    fun `an unknown position is left out rather than sent as a huge number`() {
        // `PlaybackState` reports PLAYBACK_POSITION_UNKNOWN for a session that
        // has not said where it is, and `playback_seconds` is a uint32 -- so a
        // negative reaching the wire would put a nonsense elapsed time on the
        // dashboard rather than none.
        val message = MediaPlaybackChannel.status(
            channelId = 7,
            status = MediaPlaybackChannel.Status(
                state = MediaPlaybackChannel.Playback.PAUSED,
                positionSeconds = -1,
            ),
        )
        val decoded = MediaPlaybackChannel.decodeStatus(message.payload)
        assertFalse(decoded.hasPlaybackSeconds(), "an unknown position must not be sent")
        assertEquals(MediaPlaybackStatus.State.PAUSED, decoded.state)
    }

    @Test
    fun `a blank field is omitted, not sent empty`() {
        val message = MediaPlaybackChannel.metadata(
            channelId = 7,
            metadata = MediaPlaybackChannel.Metadata(
                song = "Roundabout",
                artist = "   ",
                album = "Fragile",
                durationSeconds = 508,
            ),
        )
        assertEquals(MediaPlaybackMessageId.MEDIA_PLAYBACK_METADATA, message.messageId)
        val decoded = MediaPlaybackChannel.decodeMetadata(message.payload)
        assertEquals("Roundabout", decoded.song)
        assertFalse(decoded.hasArtist(), "a blank artist must be omitted")
        assertEquals("Fragile", decoded.album)
        assertEquals(508, decoded.durationSeconds)
    }

    @Test
    fun `every state maps to the schema's own enum`() {
        MediaPlaybackChannel.Playback.entries.forEach { state ->
            val decoded = MediaPlaybackChannel.decodeStatus(
                MediaPlaybackChannel.status(
                    channelId = 1,
                    status = MediaPlaybackChannel.Status(state = state),
                ).payload,
            )
            assertEquals(state.name, decoded.state.name, "${state.name} did not round-trip")
        }
    }
}

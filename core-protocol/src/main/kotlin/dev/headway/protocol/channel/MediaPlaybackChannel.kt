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

import aap_protobuf.service.mediaplayback.message.MediaPlaybackMetadataOuterClass.MediaPlaybackMetadata
import aap_protobuf.service.mediaplayback.message.MediaPlaybackStatusOuterClass.MediaPlaybackStatus
import dev.headway.protocol.framing.AapMessage

/**
 * Message ids on the media-playback-status channel.
 *
 * Source: `core-protocol/src/main/proto/aap_protobuf/service/mediaplayback/`
 * `MediaPlaybackStatusMessageId.proto` L5-L10, which is the schema vendored
 * from aasdk. aasdk's own head-unit-role handler receives
 * `MEDIA_PLAYBACK_STATUS` and `MEDIA_PLAYBACK_METADATA` and only ever replies
 * with a `ChannelOpenResponse`
 * (`aasdk/src/Channel/MediaPlaybackStatus/MediaPlaybackStatusService.cpp`
 * L61-L116), which is what fixes the direction: these two are phone → head
 * unit. [MEDIA_PLAYBACK_INPUT] travels the other way and is not sent here.
 */
object MediaPlaybackMessageId {

    /** Phone → HU. `MediaPlaybackStatus`: playing/paused/stopped, and where. */
    const val MEDIA_PLAYBACK_STATUS: Int = 32769

    /**
     * HU → phone. Instrument-cluster input.
     *
     * Decoded by no reference implementation this project has read, and never
     * sent by Headway. Named so a frame carrying it is recognisable in a log
     * rather than reported as an unknown id.
     */
    const val MEDIA_PLAYBACK_INPUT: Int = 32770

    /** Phone → HU. `MediaPlaybackMetadata`: song, artist, album, duration. */
    const val MEDIA_PLAYBACK_METADATA: Int = 32771
}

/**
 * The phone half of the media-playback-status channel: telling the car what is playing.
 *
 * ## Why this exists, and what it fixes
 *
 * A driver reported that the physical skip buttons on their steering wheel and
 * head unit answered **"Action unavailable"**, while Headway's own on-screen
 * transport worked. That string appears nowhere in this repository, nowhere in
 * `androidx.car.app`, and cannot be a reply to anything: the input channel has
 * no per-event acknowledgement — its four message ids are `INPUT_REPORT`,
 * `KEY_BINDING_REQUEST`, `KEY_BINDING_RESPONSE` and `INPUT_FEEDBACK`, and none
 * of them says "the phone did/did not act on that key". So the head unit is not
 * reporting a refusal; it is deciding, from its own state, not to send the key
 * at all.
 *
 * The state it decides from is this channel. The target vehicle advertises
 * `MEDIA_PLAYBACK_STATUS` — it is in the real 2021 Chevrolet Infotainment 3
 * service list captured in `docs/protocol-notes.md` — and `AapSession.connect`
 * opens every advertised service, so the channel was open and then permanently
 * silent. A head unit told nothing about any media has nothing to skip.
 *
 * ## Why the phone is the source
 *
 * `media_playback_service` arrives in the head unit's own
 * `ServiceDiscoveryResponse`, which by itself only says the unit supports it.
 * The direction comes from aasdk's head-unit implementation, which *receives*
 * both messages and sends neither. See [MediaPlaybackMessageId].
 *
 * ## Encoding
 *
 * `control = false, encrypted = true`, like every other non-control service
 * message — the same shape `InputChannel.keyBindingRequest` uses.
 */
object MediaPlaybackChannel {

    /** How Headway describes the state of playback to the car. */
    enum class Playback {
        STOPPED,
        PLAYING,
        PAUSED,
        ;

        /** The protobuf enum this maps to. */
        fun wire(): MediaPlaybackStatus.State = when (this) {
            STOPPED -> MediaPlaybackStatus.State.STOPPED
            PLAYING -> MediaPlaybackStatus.State.PLAYING
            PAUSED -> MediaPlaybackStatus.State.PAUSED
        }
    }

    /**
     * What is playing, for [status].
     *
     * @param source the app the audio is coming from, by display name. The
     *   field is `media_source` and a head unit shows it as the "station".
     * @param positionSeconds how far into the track. Negative is dropped
     *   rather than clamped: a `PlaybackState` with no position reports
     *   `PLAYBACK_POSITION_UNKNOWN`, and sending that as a large unsigned
     *   number would put a nonsense elapsed time on the dashboard.
     */
    data class Status(
        val state: Playback,
        val source: String? = null,
        val positionSeconds: Int = -1,
        val shuffle: Boolean = false,
        val repeat: Boolean = false,
        val repeatOne: Boolean = false,
    )

    /**
     * The track, for [metadata].
     *
     * `album_art` is deliberately absent from this type. The field exists in
     * the schema, but it is raw bytes inside a single AAP message, and a cover
     * is commonly a megabyte — which has to be fragmented across a link that is
     * also carrying video. Nothing in the reported fault needs it, and a head
     * unit that wants artwork has the album name to find it with.
     */
    data class Metadata(
        val song: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val playlist: String? = null,
        val durationSeconds: Int = -1,
    )

    /** True when [status] would carry anything the car can act on. */
    fun isPlaying(state: Playback): Boolean = state == Playback.PLAYING

    fun status(channelId: Int, status: Status): AapMessage {
        val message = MediaPlaybackStatus.newBuilder().apply {
            state = status.state.wire()
            status.source?.takeIf { it.isNotBlank() }?.let { mediaSource = it }
            if (status.positionSeconds >= 0) playbackSeconds = status.positionSeconds
            shuffle = status.shuffle
            repeat = status.repeat
            repeatOne = status.repeatOne
        }.build()
        return AapMessage(
            channelId = channelId,
            control = false,
            encrypted = true,
            messageId = MediaPlaybackMessageId.MEDIA_PLAYBACK_STATUS,
            payload = message.toByteArray(),
        )
    }

    fun metadata(channelId: Int, metadata: Metadata): AapMessage {
        val message = MediaPlaybackMetadata.newBuilder().apply {
            metadata.song?.takeIf { it.isNotBlank() }?.let { song = it }
            metadata.artist?.takeIf { it.isNotBlank() }?.let { artist = it }
            metadata.album?.takeIf { it.isNotBlank() }?.let { album = it }
            metadata.playlist?.takeIf { it.isNotBlank() }?.let { playlist = it }
            if (metadata.durationSeconds >= 0) durationSeconds = metadata.durationSeconds
        }.build()
        return AapMessage(
            channelId = channelId,
            control = false,
            encrypted = true,
            messageId = MediaPlaybackMessageId.MEDIA_PLAYBACK_METADATA,
            payload = message.toByteArray(),
        )
    }

    /** Decodes a status frame, for the head-unit emulator and for tests. */
    fun decodeStatus(payload: ByteArray): MediaPlaybackStatus =
        MediaPlaybackStatus.parseFrom(payload)

    /** Decodes a metadata frame, for the head-unit emulator and for tests. */
    fun decodeMetadata(payload: ByteArray): MediaPlaybackMetadata =
        MediaPlaybackMetadata.parseFrom(payload)
}

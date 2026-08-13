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

/**
 * Channel identifiers carried in byte 0 of every AAP frame header.
 *
 * ## Where these numbers come from, precisely
 *
 * **Only `CONTROL = 0` is cited.** This list used to name
 * `aasdk/include/aasdk/Messenger/ChannelId.hpp` L30-L51 as its source, and that
 * header does not contain it: aasdk declares a shorter, differently ordered
 * enum — `CONTROL, INPUT, SENSOR, VIDEO, MEDIA_AUDIO, SPEECH_AUDIO,
 * SYSTEM_AUDIO, AV_INPUT, BLUETOOTH, NAVIGATION, MEDIA_STATUS, NONE = 255` —
 * in which INPUT is 1 and SENSOR is 2, where this list has SENSOR at 1 and
 * INPUT_SOURCE at 8. Citing a file that says something else is worse than
 * citing nothing, because it survives review.
 *
 * The values below are **Headway's own assignment**, and nothing is broken by
 * that, for the reason in the next section: the numbers that actually travel on
 * the wire come from the head unit at runtime, not from here.
 *
 * ## These numbers are a convention, not a protocol mandate
 *
 * aasdk's own header carries this note (`ChannelId.hpp` L22-L27):
 *
 * > In AA, Channel Id's are dynamic. We use ChannelId here for a static
 * > implementation, which, while acceptable, may cause more channels to be open
 * > than needs to be.
 *
 * The authoritative binding between a channel number and the service it carries
 * is the `ServiceDiscoveryResponse`: each advertised service has a `required
 * int32 id` (`aasdk/protobuf/aap_protobuf/service/Service.proto` L23), and
 * openauto assigns it from the channel id
 * (`openauto/src/autoapp/Service/MediaSink/VideoMediaSinkService.cpp` L76).
 *
 * Headway therefore uses this list as *its own* assignment when advertising
 * services, and routes inbound frames by the ids it advertised rather than by
 * these constants. **Channel 0 is the one fixed point** — control is always
 * channel 0, which every reference special-cases (e.g. `aa-proxy-rs/src/mitm.rs`
 * L893).
 *
 * Frames are addressed by raw [Int] throughout the framing layer so that an
 * unrecognised channel is forwarded and logged rather than dropped; this enum is
 * a naming convenience over that.
 */
enum class ChannelId(val id: Int) {
    CONTROL(0),
    SENSOR(1),
    MEDIA_SINK(2),
    MEDIA_SINK_VIDEO(3),
    MEDIA_SINK_MEDIA_AUDIO(4),
    MEDIA_SINK_GUIDANCE_AUDIO(5),
    MEDIA_SINK_SYSTEM_AUDIO(6),
    MEDIA_SINK_TELEPHONY_AUDIO(7),
    INPUT_SOURCE(8),
    MEDIA_SOURCE_MICROPHONE(9),
    BLUETOOTH(10),
    RADIO(11),
    NAVIGATION_STATUS(12),
    MEDIA_PLAYBACK_STATUS(13),
    PHONE_STATUS(14),
    MEDIA_BROWSER(15),
    VENDOR_EXTENSION(16),
    GENERIC_NOTIFICATION(17),
    WIFI_PROJECTION(18),
    NONE(255),
    ;

    companion object {
        private val byId: Map<Int, ChannelId> = entries.associateBy(ChannelId::id)

        /** Returns the known channel for [id], or null if it is not one we name. */
        fun fromId(id: Int): ChannelId? = byId[id]

        /**
         * A human-readable label for [id], falling back to the raw number.
         * Used in logs, where an unknown channel must still be legible.
         */
        fun describe(id: Int): String = byId[id]?.name ?: "UNKNOWN($id)"
    }
}

/**
 * Service identifiers, as distinct from [ChannelId].
 *
 * Source: `aasdk/include/aasdk/Messenger/ServiceId.hpp` L24-L40.
 *
 * These diverge from [ChannelId] from index 3 onward because [ChannelId] splits
 * `MEDIA_SINK` into four audio/video sub-channels while this enum does not.
 * Frame header byte 0 carries a [ChannelId]; no framing code in aasdk uses
 * `ServiceId`, so it appears vestigial at this layer. It is recorded here so the
 * two numbering schemes are never conflated by a future reader.
 */
enum class ServiceId(val id: Int) {
    CONTROL(0),
    SENSOR(1),
    MEDIA_SINK(2),
    INPUT_SOURCE(3),
    MEDIA_SOURCE(4),
    BLUETOOTH(5),
    RADIO(6),
    NAVIGATION_STATUS(7),
    MEDIA_PLAYBACK_STATUS(8),
    PHONE_STATUS(9),
    MEDIA_BROWSER(10),
    VENDOR_EXTENSION(11),
    GENERIC_NOTIFICATION(12),
    WIFI_PROJECTION(13),
    NONE(255),
}

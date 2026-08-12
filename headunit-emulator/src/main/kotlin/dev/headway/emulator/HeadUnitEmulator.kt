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

package dev.headway.emulator

import dev.headway.protocol.control.ControlMessageType
import dev.headway.protocol.control.VersionHandshake
import dev.headway.protocol.framing.ChannelId
import dev.headway.protocol.io.FramedConnection

/**
 * The head-unit side of an AAP session — the Phase 0 test harness.
 *
 * Plays the role a Chevrolet Infotainment 3 unit plays, so that Headway's phone
 * side can be exercised with no vehicle, radio or device present
 * ([BLOCKERS.md](../../../../../../BLOCKERS.md) B-001).
 *
 * Per ADR 0002 this shares `core-protocol` with the phone, which makes it cheap
 * and always in sync but a **weak oracle**: a wrong-but-symmetric constant
 * round-trips here and still fails in a real car. The byte fixtures in
 * `core-protocol`'s tests are what actually pin the wire format.
 */
class HeadUnitEmulator(
    private val connection: FramedConnection,
    private val announcedVersion: VersionHandshake.Version =
        VersionHandshake.Version(VersionHandshake.MAJOR, VersionHandshake.MINOR),
) {
    /** The version the phone announced, once known. */
    var phoneVersion: VersionHandshake.Version? = null
        private set

    /**
     * Opens the session by sending `VersionRequest` and reading the phone's
     * response.
     *
     * The head unit speaks first — see [dev.headway.protocol.session.PhoneSession].
     *
     * @return the phone's response.
     * @throws IllegalStateException if the phone replies with the wrong message.
     */
    suspend fun performVersionHandshake(): VersionHandshake.VersionResponse {
        connection.send(
            VersionHandshake.request(announcedVersion.major, announcedVersion.minor)
        )

        val reply = connection.receive()
        check(reply.channelId == ChannelId.CONTROL.id) {
            "expected VERSION_RESPONSE on the control channel, got " +
                ChannelId.describe(reply.channelId)
        }
        check(reply.messageId == ControlMessageType.VERSION_RESPONSE.id) {
            "expected VERSION_RESPONSE, got ${ControlMessageType.describe(reply.messageId)}"
        }

        val response = VersionHandshake.parseResponse(reply.payload)
        phoneVersion = response.version
        return response
    }
}

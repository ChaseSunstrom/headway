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

package dev.headway.protocol.session

import dev.headway.protocol.control.ControlMessageType
import dev.headway.protocol.control.VersionHandshake
import dev.headway.protocol.framing.AapMessage
import dev.headway.protocol.framing.ChannelId
import dev.headway.protocol.io.MessageChannel

/** Raised when the peer violates the session's expected message sequence. */
open class SessionException(message: String) : RuntimeException(message)

/**
 * The head unit refused the phone's certificate.
 *
 * Separated from a plain [SessionException] because it is the one session
 * failure with an automatic remedy: Headway carries several certificates and a
 * caller that can tell this failure apart can present a different one on the
 * next attempt. String-matching the message would work today and break the
 * first time the wording improved.
 *
 * @param status the `MessageStatus` number the head unit sent.
 */
class AuthenticationRejectedException(val status: Int, message: String) :
    SessionException(message)

/**
 * The phone side of an AAP session — the role Headway plays, and the role almost
 * no prior art implements.
 *
 * Every reference except AACS implements the *head unit*, so the polarity here
 * is inverted relative to aasdk and openauto. Two consequences worth stating
 * explicitly because they are easy to get backwards:
 *
 * 1. **The head unit speaks first.** It sends `VersionRequest` as soon as the
 *    TCP connection is up; the phone answers. openauto's
 *    `AndroidAutoEntity::start()` sends the request and only then arms a receive
 *    (`openauto/src/autoapp/Service/AndroidAutoEntity.cpp` L46-L61), and
 *    aa-proxy-rs notes "waiting for initial version frame (HU is starting
 *    transmission)" (`src/mitm.rs` L4100-L4102).
 * 2. **The phone is the TLS server.** The head unit calls
 *    `SSL_set_connect_state` and the phone calls `SSL_set_accept_state`
 *    (`AACS/AAServer/src/AaCommunicator.cpp` L292-L300; corroborated by
 *    `aa-proxy-rs/src/ssl_rustls.rs` L430-L438). A phone that tries to be the
 *    TLS client will hang waiting for a ClientHello that never comes.
 */
class PhoneSession(
    private val connection: MessageChannel,
    private val announcedVersion: VersionHandshake.Version =
        VersionHandshake.Version(VersionHandshake.MAJOR, VersionHandshake.MINOR),
) {
    /** The version the head unit announced, once known. */
    var headUnitVersion: VersionHandshake.Version? = null
        private set

    /**
     * Waits for the head unit's `VersionRequest` and answers it.
     *
     * @return the version the head unit announced.
     * @throws SessionException if the first message is not a version request.
     */
    suspend fun awaitVersionHandshake(): VersionHandshake.Version {
        val message = connection.receive()
        expectControl(message, ControlMessageType.VERSION_REQUEST)

        val version = VersionHandshake.parseRequest(message.payload)
        headUnitVersion = version

        // Every reference accepts any major == 1 rather than requiring an exact
        // match (AACS/AAServer/src/AaCommunicator.cpp L75-L92), and the
        // references themselves announce three different minors -- 1.1, 1.5 and
        // 1.6 -- while interoperating. Refusing on a minor mismatch would reject
        // head units that work.
        val status = if (version.major == VersionHandshake.MAJOR) {
            VersionHandshake.STATUS_MATCH
        } else {
            VersionHandshake.STATUS_NO_COMPATIBLE_VERSION
        }

        connection.send(
            VersionHandshake.response(
                major = announcedVersion.major,
                minor = announcedVersion.minor,
                status = status,
            )
        )

        if (status != VersionHandshake.STATUS_MATCH) {
            throw SessionException(
                "head unit announced AAP $version; Headway implements major " +
                    "${VersionHandshake.MAJOR} only"
            )
        }
        return version
    }

    private fun expectControl(message: AapMessage, expected: ControlMessageType) {
        if (message.channelId != ChannelId.CONTROL.id) {
            throw SessionException(
                "expected ${expected.name} on the control channel, got a message on " +
                    ChannelId.describe(message.channelId)
            )
        }
        if (message.messageId != expected.id) {
            throw SessionException(
                "expected ${expected.name}, got ${ControlMessageType.describe(message.messageId)}"
            )
        }
    }
}

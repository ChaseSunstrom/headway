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

import aap_protobuf.service.control.message.PingRequestOuterClass.PingRequest
import aap_protobuf.service.control.message.PingResponseOuterClass.PingResponse
import dev.headway.protocol.framing.AapMessage
import dev.headway.protocol.framing.ChannelId
import dev.headway.protocol.io.MessageChannel

/**
 * The control channel's keepalive, from the phone's side.
 *
 * ## Why this is not optional
 *
 * A head unit pings on a timer for the whole session and tears it down when the
 * answers stop — openauto's `AndroidAutoEntity` runs exactly that loop. Headway
 * declared [ControlMessageType.PING_REQUEST] and
 * [ControlMessageType.PING_RESPONSE] and then never answered one: every ping
 * fell into the service's "unhandled message" branch. A session that came up
 * would have died a few seconds later with nothing in the log to explain it.
 *
 * ## Why it lives here rather than in the service
 *
 * Because the service is Android-only, and this needs to be provable. The
 * acceptance suite runs the real phone session against the real emulator on a
 * bare JVM, so putting the logic here is what lets CI hold a head unit's ping
 * against a phone that must answer it. The equivalent code inside
 * `HeadwayService` was unreachable from any test, which is precisely how a
 * declared-but-unhandled message id survived this long.
 */
object ControlKeepalive {

    /** True if [message] is a head unit's keepalive on the control channel. */
    fun isPing(message: AapMessage): Boolean =
        message.channelId == ChannelId.CONTROL.id &&
            message.messageId == ControlMessageType.PING_REQUEST.id

    /**
     * Answers a ping, echoing the timestamp it carried.
     *
     * Echoed rather than restamped: a ping measures a round trip, and only the
     * sender's clock can interpret the sender's timestamp. A body that fails to
     * parse still gets an answer — the head unit is waiting for one, and a
     * timestamp Headway cannot read is not a reason to let the session die.
     *
     * The reply mirrors the request's encryption. Everything after `AuthComplete`
     * travels inside TLS, so a ping that arrived encrypted must be answered the
     * same way, and one that did not must not be.
     */
    suspend fun answer(connection: MessageChannel, ping: AapMessage) {
        val timestamp = runCatching { PingRequest.parseFrom(ping.payload).timestamp }
            .getOrDefault(0L)
        connection.send(
            AapMessage(
                channelId = ChannelId.CONTROL.id,
                control = false,
                encrypted = ping.encrypted,
                messageId = ControlMessageType.PING_RESPONSE.id,
                payload = PingResponse.newBuilder().setTimestamp(timestamp).build().toByteArray(),
            )
        )
    }
}

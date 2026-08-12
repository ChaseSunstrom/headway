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

import aap_protobuf.service.ServiceOuterClass
import aap_protobuf.service.control.message.ChannelOpenRequestOuterClass.ChannelOpenRequest
import aap_protobuf.service.control.message.ChannelOpenResponseOuterClass.ChannelOpenResponse
import aap_protobuf.service.control.message.ServiceDiscoveryRequestOuterClass.ServiceDiscoveryRequest
import aap_protobuf.service.control.message.ServiceDiscoveryResponseOuterClass.ServiceDiscoveryResponse
import aap_protobuf.shared.MessageStatusOuterClass.MessageStatus
import dev.headway.protocol.control.ControlMessageType
import dev.headway.protocol.control.VersionHandshake
import dev.headway.protocol.framing.AapMessage
import dev.headway.protocol.framing.ChannelId
import dev.headway.protocol.io.Cryptor
import dev.headway.protocol.io.FramedConnection

/**
 * Drives a TLS handshake that is carried inside AAP control messages.
 *
 * Kept as an interface so `core-protocol` stays free of any particular TLS
 * implementation — the JVM `SSLEngine`-backed one lives in `core-transport`.
 */
interface TlsHandshakeDriver : Cryptor {
    val handshakeComplete: Boolean
    fun beginHandshake(): ByteArray
    fun continueHandshake(incoming: ByteArray): ByteArray
}

/** Identity Headway presents to the head unit during service discovery. */
data class PhoneIdentity(
    val deviceName: String = "Headway",
    val labelText: String = "Headway",
)

/** What the head unit told us about itself, and what it can do. */
data class HeadUnitProfile(
    val version: VersionHandshake.Version,
    val displayName: String?,
    val services: List<ServiceOuterClass.Service>,
) {
    /** Finds the advertised service whose id matches [channelId]. */
    fun serviceFor(channelId: Int): ServiceOuterClass.Service? = services.firstOrNull { it.id == channelId }

    /** Ids of every service the head unit advertised, in advertisement order. */
    val channelIds: List<Int> get() = services.map { it.id }
}

/**
 * The phone side of an AAP session, from the first byte to open channels.
 *
 * ## Sequence
 *
 * ```text
 *  head unit                                phone (Headway)
 *  ---------                                ---------------
 *  VersionRequest              ------->
 *                              <-------     VersionResponse
 *  EncapsulatedSSL (ClientHello) ----->
 *                              <-------     EncapsulatedSSL (ServerHello ...)
 *  EncapsulatedSSL (Finished)  ------->
 *                              <-------     EncapsulatedSSL (Finished)
 *  AuthComplete                ------->
 *                              <-------     ServiceDiscoveryRequest   [encrypted]
 *  ServiceDiscoveryResponse    ------->
 *                              <-------     ChannelOpenRequest        [per channel]
 *  ChannelOpenResponse         ------->
 * ```
 *
 * ## Two things that are the opposite of what you would guess
 *
 * 1. **The phone is the TLS server** even though it opened the TCP connection.
 *    See `AapTls` for the citations.
 * 2. **The head unit advertises the services, not the phone.** CLAUDE.md
 *    describes this as Headway advertising "the channels Headway supports", but
 *    the references are unambiguous the other way: the phone sends
 *    `ServiceDiscoveryRequest` carrying its *identity* (device name, label,
 *    icons — see `ServiceDiscoveryRequest.proto`), and the head unit replies
 *    with `ServiceDiscoveryResponse` listing *its* channels plus its own make,
 *    model and driver position (`ServiceDiscoveryResponse.proto`).
 *    `openauto/src/autoapp/Service/AndroidAutoEntity.cpp` L178-L212 builds the
 *    response from each of its services' `fillFeatures`, and
 *    `aasdk/src/Channel/Control/ControlServiceChannel.cpp` L253-L258 handles the
 *    *request* on the head-unit side. This makes physical sense: the head unit
 *    is the collection of sinks and sources (a screen, speakers, a touch panel);
 *    the phone consumes them. The phone then opens the ones it wants with
 *    `ChannelOpenRequest`.
 */
class AapSession(
    private val connection: FramedConnection,
    private val tls: TlsHandshakeDriver,
    private val identity: PhoneIdentity = PhoneIdentity(),
    private val announcedVersion: VersionHandshake.Version =
        VersionHandshake.Version(VersionHandshake.MAJOR, VersionHandshake.MINOR),
    /** Narrates each step; wired to the debug log export in the app. */
    private val onStep: (String) -> Unit = {},
) {
    var profile: HeadUnitProfile? = null
        private set

    /** Channels successfully opened, in the order the head unit accepted them. */
    private val openChannels = mutableListOf<Int>()
    val openedChannelIds: List<Int> get() = openChannels.toList()

    /**
     * Runs the whole bring-up and returns what the head unit offers.
     *
     * @param channelsToOpen decides which advertised channels to open. Defaults
     *   to every channel the head unit advertised.
     */
    suspend fun connect(
        channelsToOpen: (HeadUnitProfile) -> List<Int> = { it.channelIds },
    ): HeadUnitProfile {
        val version = versionHandshake()
        runTlsHandshake()
        awaitAuthComplete()
        val discovered = discoverServices(version)
        profile = discovered

        for (channelId in channelsToOpen(discovered)) {
            openChannel(channelId)
        }
        onStep("session ready; ${openChannels.size} channel(s) open")
        return discovered
    }

    // --- step 1: version ----------------------------------------------------

    private suspend fun versionHandshake(): VersionHandshake.Version {
        val message = expect(ControlMessageType.VERSION_REQUEST)
        val version = VersionHandshake.parseRequest(message.payload)
        onStep("head unit announced AAP $version")

        // Accept any major == 1: the references announce 1.1, 1.5 and 1.6 while
        // interoperating, so refusing on the minor would reject working units.
        val status = if (version.major == VersionHandshake.MAJOR) {
            VersionHandshake.STATUS_MATCH
        } else {
            VersionHandshake.STATUS_NO_COMPATIBLE_VERSION
        }
        connection.send(
            VersionHandshake.response(announcedVersion.major, announcedVersion.minor, status)
        )
        if (status != VersionHandshake.STATUS_MATCH) {
            throw SessionException(
                "head unit announced AAP $version; Headway implements major ${VersionHandshake.MAJOR}"
            )
        }
        return version
    }

    // --- step 2: TLS --------------------------------------------------------

    /**
     * Pumps the TLS handshake through `ENCAPSULATED_SSL` control messages.
     *
     * The phone is the TLS server, so it never speaks first here: every flight
     * it sends is a reply to one the head unit sent.
     */
    private suspend fun runTlsHandshake() {
        var flights = 0
        while (!tls.handshakeComplete) {
            if (++flights > MAX_TLS_FLIGHTS) {
                throw SessionException("TLS handshake did not converge after $flights flights")
            }
            val incoming = expect(ControlMessageType.ENCAPSULATED_SSL)
            val outgoing = tls.continueHandshake(incoming.payload)
            if (outgoing.isNotEmpty()) {
                connection.send(encapsulatedSsl(outgoing))
            }
        }
        // Only now may frames carry the encrypted flag.
        connection.cryptor = tls
        onStep("TLS established")
    }

    private fun encapsulatedSsl(payload: ByteArray) = AapMessage(
        channelId = ChannelId.CONTROL.id,
        control = false,
        encrypted = false,
        messageId = ControlMessageType.ENCAPSULATED_SSL.id,
        payload = payload,
    )

    // --- step 3: auth -------------------------------------------------------

    /**
     * Waits for the head unit's `AuthComplete`.
     *
     * Its payload is an `AuthResponse` with `required int32 status = 1`
     * (`aap_protobuf/service/control/message/AuthResponse.proto`), which AACS
     * shows on the wire as `00 04 08 00` — message id 4, then field 1 varint 0,
     * i.e. `STATUS_SUCCESS` (`AACS/AAClient/src/AaCommunicator.cpp` L143-L151).
     */
    private suspend fun awaitAuthComplete() {
        val message = expect(ControlMessageType.AUTH_COMPLETE)
        val status = parseAuthStatus(message.payload)
        if (status != MessageStatus.STATUS_SUCCESS.number) {
            throw SessionException("head unit rejected authentication: status $status")
        }
        onStep("authentication complete")
    }

    /**
     * Reads field 1 of an `AuthResponse` by hand.
     *
     * A four-byte message does not justify pulling the generated parser in, and
     * doing it here keeps the failure legible when a head unit sends something
     * unexpected.
     */
    private fun parseAuthStatus(payload: ByteArray): Int {
        if (payload.size >= 2 && payload[0].toInt() == 0x08) {
            var result = 0
            var shift = 0
            var index = 1
            while (index < payload.size) {
                val b = payload[index].toInt()
                result = result or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                index++
            }
        }
        throw SessionException(
            "malformed AuthComplete payload (${payload.size} bytes)"
        )
    }

    // --- step 4: service discovery -----------------------------------------

    private suspend fun discoverServices(version: VersionHandshake.Version): HeadUnitProfile {
        val request = ServiceDiscoveryRequest.newBuilder()
            .setDeviceName(identity.deviceName)
            .setLabelText(identity.labelText)
            .build()

        connection.send(
            AapMessage(
                channelId = ChannelId.CONTROL.id,
                control = false,
                // Everything after AuthComplete travels inside the TLS session.
                encrypted = true,
                messageId = ControlMessageType.SERVICE_DISCOVERY_REQUEST.id,
                payload = request.toByteArray(),
            )
        )

        val message = expect(ControlMessageType.SERVICE_DISCOVERY_RESPONSE)
        val response = ServiceDiscoveryResponse.parseFrom(message.payload)
        val profile = HeadUnitProfile(
            version = version,
            displayName = if (response.hasDisplayName()) response.displayName else null,
            services = response.channelsList,
        )
        onStep(
            "head unit '${profile.displayName ?: "unnamed"}' offers " +
                profile.channelIds.joinToString { ChannelId.describe(it) }
        )
        return profile
    }

    // --- step 5: channel open ----------------------------------------------

    /**
     * Opens one advertised channel.
     *
     * `ChannelOpenRequest` carries `required sint32 priority` and
     * `required int32 service_id`. openauto sends priority 0 for every channel,
     * so we do the same rather than inventing a scheme the head unit has no
     * reason to honour.
     */
    suspend fun openChannel(channelId: Int, priority: Int = 0) {
        val request = ChannelOpenRequest.newBuilder()
            .setPriority(priority)
            .setServiceId(channelId)
            .build()

        connection.send(
            AapMessage(
                channelId = channelId,
                control = false,
                encrypted = true,
                messageId = ControlMessageType.CHANNEL_OPEN_REQUEST.id,
                payload = request.toByteArray(),
            )
        )

        val message = connection.receive()
        if (message.messageId != ControlMessageType.CHANNEL_OPEN_RESPONSE.id) {
            throw SessionException(
                "expected CHANNEL_OPEN_RESPONSE for ${ChannelId.describe(channelId)}, got " +
                    ControlMessageType.describe(message.messageId)
            )
        }
        val response = ChannelOpenResponse.parseFrom(message.payload)
        if (response.status != MessageStatus.STATUS_SUCCESS) {
            throw SessionException(
                "head unit refused ${ChannelId.describe(channelId)}: ${response.status}"
            )
        }
        openChannels += channelId
        onStep("channel ${ChannelId.describe(channelId)} open")
    }

    // --- helpers ------------------------------------------------------------

    private suspend fun expect(type: ControlMessageType): AapMessage {
        val message = connection.receive()
        if (message.channelId != ChannelId.CONTROL.id) {
            throw SessionException(
                "expected ${type.name} on the control channel, got a message on " +
                    ChannelId.describe(message.channelId)
            )
        }
        if (message.messageId != type.id) {
            throw SessionException(
                "expected ${type.name}, got ${ControlMessageType.describe(message.messageId)}"
            )
        }
        return message
    }

    private companion object {
        /**
         * A TLS 1.2 handshake takes two round trips. Ten is generous headroom
         * that still fails fast if a head unit loops.
         */
        const val MAX_TLS_FLIGHTS = 10
    }
}

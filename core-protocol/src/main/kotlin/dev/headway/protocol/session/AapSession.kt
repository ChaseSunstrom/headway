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
import dev.headway.protocol.control.ControlKeepalive
import dev.headway.protocol.control.ControlMessageType
import dev.headway.protocol.control.VersionHandshake
import dev.headway.protocol.framing.AapMessage
import dev.headway.protocol.framing.ChannelId
import dev.headway.protocol.io.Cryptor
import dev.headway.protocol.io.FramedConnection
import dev.headway.protocol.io.MessageChannel

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
 * That is the usual shape, not a contract. The TLS and authentication steps are
 * driven by [secureAndAuthenticate], which dispatches on whatever the head unit
 * sends rather than requiring this order — a real 2021 Chevrolet Infotainment 3
 * unit jumps straight from the version exchange to `AuthComplete` with no TLS at
 * all, and the `[encrypted]` marks above are then false for the rest of the
 * session. See [secured].
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
    private val connection: MessageChannel,
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
     * Whether TLS was actually negotiated, and therefore whether frames after
     * authentication may set the encrypted flag.
     *
     * Normally true — the sequence above is what every reference shows. It is
     * false against a head unit that authenticates without a TLS handshake at
     * all, which a real 2021 Chevrolet Infotainment 3 unit does; setting the
     * flag then would claim an encryption that does not exist, and the framing
     * layer rejects it rather than putting a lie on the wire.
     */
    private val secured: Boolean get() = tls.handshakeComplete

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
        secureAndAuthenticate()
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
        // Never announce a minor above the head unit's.
        //
        // AACS -- the only reference implementing this side -- answers a flat
        // 1.5 whatever the head unit said (`AaCommunicator.cpp` L85-L93), and
        // the observed Chevrolet announces 1.5 while Headway's default is
        // aasdk's 1.6. Claiming a higher minor than the peer offered invites it
        // to expect messages this implementation has never been tested against,
        // for no gain: nothing here needs anything above 1.5.
        val minor = minOf(announcedVersion.minor, version.minor)
        if (minor != announcedVersion.minor) {
            onStep("answering AAP $version with 1.$minor rather than announcing higher")
        }
        connection.send(VersionHandshake.response(announcedVersion.major, minor, status))
        if (status != VersionHandshake.STATUS_MATCH) {
            throw SessionException(
                "head unit announced AAP $version; Headway implements major ${VersionHandshake.MAJOR}"
            )
        }
        return version
    }

    // --- step 2: TLS --------------------------------------------------------

    /**
     * Drives the TLS handshake and the authentication that follows it.
     *
     * Dispatches on whatever the head unit sends rather than demanding a fixed
     * order, which is how AACS's phone side is written
     * (`AACS/AAServer/src/AaCommunicator.cpp` `handleMessageContent`, L219-L248:
     * a type switch, not a sequence). The difference is not stylistic. A real
     * 2021 Chevrolet Infotainment 3 unit answers the version response with
     * `AUTH_COMPLETE` and no TLS flight at all, and a sequential reader that
     * insists on `ENCAPSULATED_SSL` next fails the session on a head unit that
     * is telling it the session is already good to go.
     *
     * The phone is the TLS server — AACS uses `SSL_set_accept_state` and
     * `SSLv23_server_method` (L293-L305), and Headway matches — so it never
     * speaks first: every flight it sends answers one the head unit sent.
     */
    /**
     * What an authentication rejection usually means, for the three codes a
     * phone can actually provoke.
     *
     * The generic failure gets the longest answer because it is the one a real
     * head unit returns for an expired certificate: a 2021 Chevrolet
     * Infotainment 3 unit answers -3 and puts "the phone and vehicle calendars
     * are set to different dates and times" on its screen, which sends everyone
     * who reads it to the clock settings instead of to the certificate.
     */
    private fun authFailureAdvice(status: Int): String = when (status) {
        MessageStatus.STATUS_AUTHENTICATION_FAILURE.number,
        MessageStatus.STATUS_AUTHENTICATION_FAILURE_CERT_EXPIRED.number,
        MessageStatus.STATUS_CERTIFICATE_ERROR.number ->
            ". This is almost always the phone certificate. The one every open-source " +
                "implementation ships expired on 2022-08-24, and a head unit that checks it " +
                "refuses the session here -- some of them describe it on screen as the phone " +
                "and vehicle clocks disagreeing, which is misleading. Import a valid " +
                "certificate from Headway's Diagnostics screen. See BLOCKERS.md B-003"
        MessageStatus.STATUS_AUTHENTICATION_FAILURE_CERT_NOT_YET_VALID.number ->
            ". The certificate is not valid yet, which usually means the head unit's clock " +
                "is set to a date before the certificate was issued"
        else -> ""
    }

    private suspend fun secureAndAuthenticate() {
        var flights = 0
        while (true) {
            val message = expectControl()
            when (message.messageId) {
                ControlMessageType.ENCAPSULATED_SSL.id -> {
                    if (++flights > MAX_TLS_FLIGHTS) {
                        throw SessionException(
                            "TLS handshake did not converge after $flights flights"
                        )
                    }
                    val outgoing = tls.continueHandshake(message.payload)
                    if (outgoing.isNotEmpty()) connection.send(encapsulatedSsl(outgoing))
                    if (tls.handshakeComplete) {
                        // Only now may frames carry the encrypted flag.
                        connection.cryptor = tls
                        onStep("TLS established")
                    }
                }

                ControlMessageType.AUTH_COMPLETE.id -> {
                    val status = parseAuthStatus(message.payload)
                    if (status != MessageStatus.STATUS_SUCCESS.number) {
                        // Whether TLS finished is the single most useful fact
                        // here and it was not being recorded. A unit that
                        // rejects during the handshake is objecting to the
                        // certificate itself; one that rejects after a completed
                        // handshake is objecting to something else, and the two
                        // send a reader to opposite ends of the stack.
                        val tlsState = if (tls.handshakeComplete) {
                            "the TLS handshake had completed"
                        } else {
                            "the TLS handshake had NOT completed, so the head unit stopped " +
                                "while looking at the certificate"
                        }
                        throw AuthenticationRejectedException(
                            status,
                            "head unit rejected authentication: " +
                                "${MessageStatus.forNumber(status)?.name ?: "status $status"} " +
                                "($tlsState)" + authFailureAdvice(status),
                        )
                    }
                    if (!tls.handshakeComplete) {
                        // Not a failure to route around quietly: it means every
                        // frame from here on is plaintext, which is worth saying
                        // out loud in a log someone will read later.
                        onStep(
                            "head unit authenticated the session without a TLS handshake; " +
                                "continuing unencrypted"
                        )
                    }
                    onStep("authentication complete")
                    return
                }

                else -> throw SessionException(
                    "expected ENCAPSULATED_SSL or AUTH_COMPLETE, got " +
                        ControlMessageType.describe(message.messageId)
                )
            }
        }
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
     * Reads one control-channel message, whatever type it is.
     *
     * `AuthComplete`'s payload is an `AuthResponse` with
     * `required int32 status = 1`
     * (`aap_protobuf/service/control/message/AuthResponse.proto`), which AACS
     * shows on the wire as `00 04 08 00` — message id 4, then field 1 varint 0,
     * i.e. `STATUS_SUCCESS` (`AACS/AAClient/src/AaCommunicator.cpp` L143-L151).
     */
    private suspend fun expectControl(): AapMessage {
        val message = connection.receive()
        if (message.channelId != ChannelId.CONTROL.id) {
            throw SessionException(
                "expected a control message, got one on " +
                    ChannelId.describe(message.channelId)
            )
        }
        onStep("rx control ${ControlMessageType.describe(message.messageId)}")
        return message
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
                encrypted = secured,
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
                encrypted = secured,
                messageId = ControlMessageType.CHANNEL_OPEN_REQUEST.id,
                payload = request.toByteArray(),
            )
        )

        // Read until the response for *this* channel arrives, skipping anything
        // interleaved rather than failing the whole bring-up on it. A head unit
        // is free to emit a PING_REQUEST or a focus notification between channel
        // opens -- openauto chains a VideoFocusNotification onto one -- and the
        // old code threw SessionException on the first such message and restarted
        // the entire session. Matching on channelId as well as message id also
        // stops another channel's response being mistaken for this one's.
        var skipped = 0
        while (true) {
            val message = connection.receive()
            val isOurResponse =
                message.messageId == ControlMessageType.CHANNEL_OPEN_RESPONSE.id &&
                    message.channelId == channelId
            if (!isOurResponse) {
                if (++skipped > MAX_INTERLEAVED_MESSAGES) {
                    throw SessionException(
                        "no CHANNEL_OPEN_RESPONSE for ${ChannelId.describe(channelId)} after " +
                            "$skipped interleaved messages"
                    )
                }
                // A ping is the one interleaved message that must be *answered*
                // rather than merely tolerated, and skipping it cost a real
                // session. A 2021 Chevrolet Infotainment 3 log shows discovery
                // completing, SENSOR opening, a PING_REQUEST arriving on CONTROL
                // and being ignored -- and the head unit dropping the link 9 ms
                // later. From its point of view a phone that does not answer a
                // keepalive during bring-up has gone away, and it is right.
                //
                // Keepalives were already answered in the *running* session; the
                // gap was this window, between discovery and the last channel
                // opening, which is exactly when a unit checks whether the phone
                // it just authenticated is really there.
                if (ControlKeepalive.isPing(message)) {
                    ControlKeepalive.answer(connection, message)
                    onStep(
                        "answered a keepalive while opening ${ChannelId.describe(channelId)}"
                    )
                    continue
                }
                onStep(
                    "ignoring ${ControlMessageType.describe(message.messageId)} on " +
                        "${ChannelId.describe(message.channelId)} while opening " +
                        ChannelId.describe(channelId)
                )
                continue
            }
            val response = ChannelOpenResponse.parseFrom(message.payload)
            if (response.status != MessageStatus.STATUS_SUCCESS) {
                throw SessionException(
                    "head unit refused ${ChannelId.describe(channelId)}: ${response.status}"
                )
            }
            openChannels += channelId
            onStep("channel ${ChannelId.describe(channelId)} open")
            return
        }
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

        /**
         * How many unrelated messages may arrive between a channel-open request
         * and its response before Headway concludes the response is not coming.
         * Bounded so a head unit that never answers fails the attempt instead of
         * reading forever.
         */
        const val MAX_INTERLEAVED_MESSAGES = 16
    }
}

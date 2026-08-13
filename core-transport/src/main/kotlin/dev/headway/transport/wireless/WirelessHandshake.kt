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

package dev.headway.transport.wireless

import aap_protobuf.aaw.MessageIdOuterClass.MessageId
import aap_protobuf.aaw.StatusOuterClass.Status
import aap_protobuf.aaw.WifiInfoResponseOuterClass.WifiInfoResponse
import aap_protobuf.service.wifiprojection.message.AccessPointTypeOuterClass.AccessPointType
import aap_protobuf.service.wifiprojection.message.WifiSecurityModeOuterClass.WifiSecurityMode
import aap_protobuf.aaw.WifiStartResponseOuterClass.WifiStartResponse
import aap_protobuf.aaw.WifiConnectionStatusOuterClass.WifiConnectionStatus
import aap_protobuf.aaw.WifiStartRequestOuterClass.WifiStartRequest
import headway.aaw.AawVersion.AawWifiVersionRequest
import headway.aaw.AawVersion.AawWifiVersionResponse
import headway.aaw.AawVersion.WifiProjectionProtocolInfo
import com.google.protobuf.MessageLite
import dev.headway.protocol.io.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Raised when the head unit's Bluetooth handshake fails or is malformed. */
class WirelessHandshakeException(message: String) : RuntimeException(message)

/**
 * One message on the Bluetooth RFCOMM link: a 16-bit id and a protobuf body.
 *
 * ## Wire format
 *
 * ```text
 * [2 bytes  protobuf payload size  big-endian]
 * [2 bytes  message id             big-endian]
 * [n bytes  protobuf]
 * ```
 *
 * Note the size field counts **only the protobuf**, not the 4-byte header.
 * Source: `WirelessAndroidAutoDongle/.../bluetoothProfiles.cpp` L100-L114 —
 * `messageSize = message->ByteSizeLong(); length = messageSize + 4;` then
 * `htons(messageSize)` at offset 0 and `htons(messageId)` at offset 2. The read
 * side at L126-L145 mirrors it.
 */
data class RfcommMessage(val messageId: Int, val payload: ByteArray) {

    fun encode(): ByteArray {
        val out = ByteArray(HEADER_SIZE + payload.size)
        out[0] = (payload.size ushr 8).toByte()
        out[1] = payload.size.toByte()
        out[2] = (messageId ushr 8).toByte()
        out[3] = messageId.toByte()
        payload.copyInto(out, HEADER_SIZE)
        return out
    }

    companion object {
        const val HEADER_SIZE: Int = 4

        fun of(messageId: MessageId, body: MessageLite): RfcommMessage =
            RfcommMessage(messageId.number, body.toByteArray())

        /** Reads one message, blocking until it is complete. */
        suspend fun read(transport: Transport): RfcommMessage {
            val header = transport.readFully(HEADER_SIZE)
            val size = ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
            val id = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
            return RfcommMessage(id, transport.readFully(size))
        }

        /**
         * Hex for the log.
         *
         * These messages are tens of bytes, and when a head unit sends something
         * the schemas do not describe, the bytes are the only evidence there is.
         * Truncated because a malformed length field can claim 64 KiB.
         */
        fun hex(bytes: ByteArray, limit: Int = 64): String {
            val shown = bytes.take(limit).joinToString(" ") { "%02x".format(it) }
            return if (bytes.size > limit) "$shown ... (${bytes.size} bytes)" else shown
        }
    }
}

/**
 * The car's access point and AAP endpoint, as handed over on Bluetooth.
 *
 * Note a naming discrepancy between references: aasdk's schema calls the
 * passphrase field `password` (`aap_protobuf/aaw/WifiInfoResponse.proto`) while
 * aa-proxy-rs calls it `key` (`aa-proxy-rs/src/protos/WifiInfoResponse.proto`).
 * Both are field number 2 and both are `string`, so the two are wire-compatible
 * and the disagreement is cosmetic -- but only the field *number* is load
 * bearing, which is worth remembering before trusting a field name from any one
 * reference. aa-proxy-rs also nests the enums inside the message where aasdk
 * declares them in `service.wifiprojection.message`; again the wire values match.
 */
data class CarNetworkCredentials(
    val ssid: String,
    val passphrase: String,
    val bssid: String,
    val securityMode: WifiSecurityMode,
    val accessPointType: AccessPointType,
    /**
     * Where to open the AAP session, when the head unit says.
     *
     * Null is normal, not an error. A real 2021 Chevrolet Infotainment 3 unit
     * answers `WifiInfoRequest` with full credentials and never sends
     * `WifiStartRequest` at all, so there is no endpoint to be had over
     * Bluetooth. The caller resolves one after joining the access point -- the
     * head unit is the only other host on its own network. See
     * `docs/protocol-notes.md` § "Evidence from a real head unit".
     */
    val endpoint: CarEndpoint? = null,
    /**
     * Wi-Fi frequencies in MHz the head unit said its access point can use.
     *
     * From `WifiVersionRequest`, which the target vehicle answers with
     * `[0, 5745]` — 5745 MHz being 5 GHz channel 149. Carried through to the
     * association because Android will use it to make the first scan a
     * channel-limited one instead of a full-band sweep, which is the difference
     * between finding the car in the first scan and finding it in the third.
     * Empty when the unit never ran a version exchange.
     */
    val advertisedFrequenciesMhz: List<Int> = emptyList(),
) {
    /** Redacts the passphrase; this gets logged and logs get shared for diagnosis. */
    override fun toString(): String =
        "CarNetworkCredentials(ssid=$ssid, bssid=$bssid, security=$securityMode, " +
            "type=$accessPointType, endpoint=${endpoint ?: "<not offered>"}, " +
            "passphrase=<redacted>)"
}

/** The head unit's AAP listener. */
data class CarEndpoint(val ipAddress: String, val port: Int) {
    override fun toString(): String = "$ipAddress:$port"
}

/**
 * The Bluetooth half of wireless Android Auto: the exchange that hands the phone
 * the car's Wi-Fi credentials and the TCP endpoint to connect to.
 *
 * This runs over an RFCOMM socket to the head unit's AA Wireless service, before
 * any Wi-Fi or TCP exists. It is deliberately transport-agnostic — Android
 * supplies a real `BluetoothSocket` wrapped in a [Transport], and CI supplies an
 * in-process pipe, so the exchange itself is exercised in tests without a radio
 * (BLOCKERS.md B-001).
 *
 * ## Sequence
 *
 * The head unit speaks first, as it does on the AAP session proper.
 *
 * ```text
 *  head unit                                   phone
 *  ---------                                   -----
 *  WifiStartRequest {ip, port}    ------->
 *                                 <-------     WifiInfoRequest {}
 *  WifiInfoResponse {ssid, key,
 *                    bssid, ...}  ------->
 *                                 <-------     (join Wi-Fi, then TCP to ip:port)
 * ```
 *
 * Some units also exchange `WifiVersionRequest`/`Response` first, and some send
 * `WifiConnectionStatus` afterwards; both are handled if present and not
 * required if absent, because the references disagree about the order and a
 * strict state machine would reject working head units.
 *
 * Sources: message ids from `aap_protobuf/aaw/MessageId.proto`; the message
 * bodies from the sibling `.proto` files in that directory; the ordering and
 * the roles from `aa-proxy-rs/src/bluetooth.rs` and
 * `WirelessAndroidAutoDongle/.../bluetoothProfiles.cpp`.
 */
class WirelessHandshake(
    private val transport: Transport,
    private val onStep: (String) -> Unit = {},
    /**
     * Whether to populate `selected_wifi_channel_type` (response field 5).
     *
     * **On**, which reverses an earlier decision, so the reasoning matters.
     *
     * It was off because the field's meaning is not documented anywhere: no
     * reference records an observed value, and "channel type" is not obviously
     * the MHz frequency the head unit advertised. aasdk's schema stops at field
     * 4 and does not have the field at all. Since the bug that immediately
     * preceded that decision was putting a guessed value into a field whose
     * meaning was unknown, leaving it empty was the cautious choice.
     *
     * What changed is the evidence about what real phones do. aa-proxy-rs
     * decodes this field *out of frames captured from genuine Android Auto
     * phones* (`WifiVersionResponseDebugInfo`, `src/bluetooth.rs` L1206-L1214,
     * read by `inspect_wifi_version_response` L1604-L1657), so a real phone
     * populates it and Headway was the odd one out. And the head unit's version
     * request is where it advertises its frequencies — the target vehicle sends
     * `[0, 5745]` — which makes "which of these do you accept" the only reading
     * of a reply field that fits. A unit waiting to be told before it commits a
     * channel and brings its access point up would look exactly like the
     * observed failure: credentials handed over, then an access point that
     * never appears.
     *
     * Still a flag, and the value sent is logged, because the reading is
     * inferred rather than documented. Set it false if a capture shows a unit
     * objecting.
     */
    private val announceSelectedWifiChannel: Boolean = true,
) {

    /**
     * Runs the exchange and returns the credentials.
     *
     * @param maxMessages guards against a head unit that chatters without ever
     *   sending the response; without it a malfunctioning unit hangs the
     *   connection attempt instead of failing it, and reconnection depends on
     *   failing promptly.
     */
    suspend fun perform(
        maxMessages: Int = 16,
        silenceProbeMillis: Long = DEFAULT_SILENCE_PROBE_MILLIS,
        deadlineMillis: Long = DEFAULT_DEADLINE_MILLIS,
    ): CarNetworkCredentials = coroutineScope {
        var endpoint: WifiStartRequest? = null
        var info: WifiInfoResponse? = null
        var projectionEndpoint: WifiProjectionProtocolInfo? = null
        var advertised: List<Int> = emptyList()
        val heardSomething = AtomicBoolean(false)
        val lastActivity = AtomicLong(System.nanoTime())

        // Two paths reach "ask for the credentials" -- answering the version
        // request and receiving WifiStartRequest -- and a head unit that sends
        // both gets asked twice. The observed Chevrolet tolerated it, but there
        // is no reason to send a message twice when the first is still in
        // flight. The idle prodder deliberately ignores this: re-asking is
        // exactly its job when the first request was dropped.
        val askedForCredentials = AtomicBoolean(false)
        val startedAt = System.nanoTime()
        val expired = AtomicBoolean(false)

        // What we are still short of, in the words a person reading a log needs.
        //
        // Written as a closure over the same locals the read loop assigns so it
        // cannot drift from them. Kotlin captures those by reference, so the
        // prodder coroutine sees the reader's latest values.
        fun outstanding(): String = if (info == null) "credentials" else "nothing"

        // Prod a head unit that stops talking, rather than blocking forever.
        //
        // Every reference has the head unit speak first, and aa-proxy-rs even
        // buffers "HU pre-bootstrap frames captured before phone arrived"
        // (src/bluetooth.rs, car-wifi-mitm). A real Chevrolet Infotainment 3
        // unit was observed accepting the RFCOMM connection on channel 3 and
        // then sending nothing at all for twenty seconds, so that expectation
        // does not hold universally.
        //
        // The trigger is idleness, not "has never spoken". Silence in the middle
        // of the exchange is the more likely stall of the two: aa-proxy-rs
        // records head units that omit WifiStartRequest entirely and simply wait
        // for the phone to ask (bluetooth.rs L2042-L2060), and a prodder that
        // disarmed on the first message would hang against exactly those units,
        // having just answered their version request.
        //
        // The prodding runs in its own coroutine because cancelling a blocked
        // read is not an option: Android's BluetoothSocket read is not
        // interruptible, so a timed-out read would still be sitting on the
        // socket and would swallow the reply we are waiting for.
        val prodder = launch {
            val tick = (silenceProbeMillis / 4).coerceAtLeast(20)
            var sent = 0
            while (true) {
                delay(tick)
                val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

                // The read is blocked in the Bluetooth stack and cannot be
                // interrupted, so the only way to end the attempt with a usable
                // message is to say it here and then close the socket out from
                // under the reader. Without this the caller's own timeout fires
                // and the log records a bare cancellation, which says nothing
                // about how far the exchange actually got.
                if (elapsedMillis >= deadlineMillis) {
                    expired.set(true)
                    onStep(
                        "giving up after ${elapsedMillis}ms still waiting for " +
                            "${outstanding()}; closing the RFCOMM link"
                    )
                    runCatching { transport.close() }
                    return@launch
                }

                if (sent >= MAX_SILENCE_PROBES) continue
                val idleMillis = (System.nanoTime() - lastActivity.get()) / 1_000_000
                if (idleMillis < silenceProbeMillis) continue
                // A unit that has never spoken may not have started its side of
                // the exchange; one that has is only missing the credentials.
                val probe =
                    if (heardSomething.get()) MessageId.WIFI_INFO_REQUEST
                    else SILENCE_PROBES[sent.coerceAtMost(SILENCE_PROBES.lastIndex)]
                onStep(
                    "head unit idle for ${idleMillis}ms and still owes " +
                        "${outstanding()}; probing with ${probe.name}"
                )
                send(probe, probeBody(probe))
                lastActivity.set(System.nanoTime())
                sent++
            }
        }

        try {
        repeat(maxMessages) {
            val message = RfcommMessage.read(transport)
            heardSomething.set(true)
            lastActivity.set(System.nanoTime())
            onStep(
                "rx id=${message.messageId} " +
                    "(${MessageId.forNumber(message.messageId)?.name ?: "unknown"}) " +
                    RfcommMessage.hex(message.payload)
            )
            when (MessageId.forNumber(message.messageId)) {
                MessageId.WIFI_START_REQUEST -> {
                    endpoint = WifiStartRequest.parseFrom(message.payload)
                    onStep("head unit offers ${endpoint!!.ipAddress}:${endpoint!!.port}")
                    // Ask for the credentials now that we know where to connect,
                    // unless answering the version request already did.
                    if (info == null && askedForCredentials.compareAndSet(false, true)) {
                        send(
                            MessageId.WIFI_INFO_REQUEST,
                            aap_protobuf.aaw.WifiInfoRequestOuterClass.WifiInfoRequest
                                .getDefaultInstance(),
                        )
                    }
                }

                MessageId.WIFI_INFO_RESPONSE -> {
                    val parsed = WifiInfoResponse.parseFrom(message.payload)
                    // The schema is deliberately all-optional so that an unusual
                    // head unit reaches this line instead of dying inside
                    // parseFrom with a message that names no field. The SSID is
                    // the one part there is no way to work around.
                    if (!parsed.hasSsid() || parsed.ssid.isEmpty()) {
                        throw WirelessHandshakeException(
                            "the head unit answered WifiInfoRequest without an SSID " +
                                "(${RfcommMessage.hex(message.payload)}). There is nothing " +
                                "to join; please report this log."
                        )
                    }
                    if (!parsed.hasSecurityMode()) {
                        // Not fatal: the security type is taken from whether a
                        // passphrase is present, not from this field. Logged
                        // because an unset value here means the unit sent a wire
                        // value outside the enum, which is worth knowing.
                        onStep(
                            "the head unit's security_mode is absent or outside the known " +
                                "values; treating the network as " +
                                if (parsed.password.isEmpty()) "open" else "WPA2-PSK"
                        )
                    }
                    info = parsed
                    // Byte length and hex, not just the name. Android matches
                    // the SSID with a byte-exact literal comparison against the
                    // scan result, so a trailing space, a non-ASCII lookalike or
                    // an unexpected encoding produces a network that never
                    // matches and a log line that looks completely correct.
                    val ssidBytes = parsed.ssid.toByteArray()
                    onStep(
                        "received credentials for SSID '${parsed.ssid}' " +
                            "(${ssidBytes.size} bytes: ${RfcommMessage.hex(ssidBytes, limit = 32)})"
                    )
                    if (parsed.ssid != parsed.ssid.trim() || ssidBytes.size > MAX_SSID_BYTES) {
                        onStep(
                            "warning: that SSID has surrounding whitespace or is longer than " +
                                "$MAX_SSID_BYTES bytes, either of which can stop Android " +
                                "matching it"
                        )
                    }
                }

                MessageId.WIFI_VERSION_REQUEST -> {
                    // Field 4 of the response is a STATUS, and this is where an
                    // earlier version of Headway silently killed every session:
                    // it sent 2 there. A real Chevrolet Infotainment 3 unit
                    // answered its version request, read a non-zero status, and
                    // stopped talking -- with no error, because from its point
                    // of view the phone had declined.
                    //
                    // aasdk's schema is what led us there: it names these fields
                    // unknown_value_a..d and declares the *request* as an empty
                    // message, so there was nothing to suggest field 4 meant
                    // anything. See headway/aaw_version.proto for the corrected
                    // schema and its provenance.
                    val request = AawWifiVersionRequest.parseFrom(message.payload)
                    val frequencies = advertisedFrequencies(request)
                    advertised = frequencies.filter { it > 0 }
                    onStep(
                        "head unit speaks AA Wireless " +
                            "${request.majorVersion}.${request.minorVersion}; " +
                            "offers $frequencies MHz"
                    )

                    // A head unit may put the projection endpoint here and never
                    // send WifiStartRequest at all -- but the competing schema
                    // revision puts HeadUnitInfo at the same field number, and
                    // its field 1 is a string too, so "Chevrolet" parses happily
                    // as an ip_address. Believe it only if it looks like one.
                    val carried = usableEndpoint(request)
                    if (carried != null) {
                        projectionEndpoint = carried
                        onStep(
                            "version request carries the endpoint " +
                                "${carried.ipAddress}:${carried.port}"
                        )
                    }

                    val reply = AawWifiVersionResponse.newBuilder()
                        // Mirror the version the head unit announced rather than
                        // asserting our own; the exchange is the unit telling us
                        // what it speaks, not a negotiation.
                        .setMajorVersion(request.majorVersion)
                        .setMinorVersion(request.minorVersion)
                        .setStatus(STATUS_SUCCESS)

                    // Optionally tell it which of its frequencies we accept. 0
                    // appears in real advertisements as a placeholder, so pick
                    // the first real one; a 5 GHz unit offers e.g. 5745 MHz.
                    // See the constructor parameter for why this is off.
                    if (announceSelectedWifiChannel) {
                        frequencies.firstOrNull { it > 0 }?.let {
                            onStep("announcing selected wifi channel type $it (unverified field)")
                            reply.setSelectedWifiChannelType(it)
                        }
                    }

                    send(MessageId.WIFI_VERSION_RESPONSE, reply.build())

                    // Ask for the credentials straight away rather than waiting
                    // to be offered an endpoint.
                    //
                    // openauto's head unit sends WifiVersionRequest and
                    // WifiStartRequest back to back without waiting for the
                    // version response at all, and answers WifiInfoRequest
                    // whenever it arrives, in any order
                    // (openauto/src/btservice/AndroidBluetoothServer.cpp
                    // L79-L86 and L160-L178). The observed Chevrolet sends only
                    // the version request, which leaves two readings: it is
                    // still bringing up its access point, or it is waiting to be
                    // asked. aa-proxy-rs documents units of the second kind and
                    // gives up waiting after 3s (bluetooth.rs L2042-L2060).
                    //
                    // Asking costs one message that every reference head unit
                    // knows how to answer, and it distinguishes the two readings
                    // in the log. The idle prodder still covers a unit that
                    // needs asking more than once.
                    if (info == null && askedForCredentials.compareAndSet(false, true)) {
                        send(
                            MessageId.WIFI_INFO_REQUEST,
                            aap_protobuf.aaw.WifiInfoRequestOuterClass.WifiInfoRequest
                                .getDefaultInstance(),
                        )
                        lastActivity.set(System.nanoTime())
                    }
                }

                MessageId.WIFI_VERSION_RESPONSE -> {
                    // Only reachable when the silence probe asked first. Worth
                    // decoding rather than dropping: if a head unit ever refuses
                    // *us*, this status is the only place it says so.
                    val response = AawWifiVersionResponse.parseFrom(message.payload)
                    onStep(
                        "head unit answered the version probe with " +
                            "${response.majorVersion}.${response.minorVersion}, " +
                            "status ${response.status}"
                    )
                    if (response.hasStatus() && response.status != STATUS_SUCCESS) {
                        throw WirelessHandshakeException(
                            "head unit rejected the version exchange with status " +
                                "${response.status}"
                        )
                    }
                }

                MessageId.WIFI_PING_REQUEST -> answerPing(message)

                MessageId.WIFI_PING_RESPONSE ->
                    onStep("head unit answered a keepalive ping")

                MessageId.WIFI_CONNECTION_STATUS -> {
                    val status = aap_protobuf.aaw.WifiConnectionStatusOuterClass
                        .WifiConnectionStatus.parseFrom(message.payload)
                    if (status.status != Status.STATUS_SUCCESS) {
                        throw WirelessHandshakeException(
                            "head unit reported ${status.status}" +
                                if (status.hasErrorMessage()) ": ${status.errorMessage}" else ""
                        )
                    }
                }

                else -> onStep("ignoring unknown RFCOMM message id ${message.messageId}")
            }

            // The credentials are what the exchange is for. An endpoint is a
            // bonus: a head unit may send WifiStartRequest, or carry the
            // endpoint in its version request, or -- as the observed Chevrolet
            // does -- never offer one at all, answering WifiInfoRequest and
            // going quiet. Waiting for one it will never send is how this
            // stalled at 20 s per attempt with the credentials already in hand.
            val i = info
            if (i != null) {
                // Acknowledge, the way a real phone does.
                //
                // aa-proxy-rs drives genuine Android Auto phones and records the
                // exact sequence in `send_params` (`src/bluetooth.rs`
                // L6207-L6245): after WifiInfoResponse it *blocks* reading
                // WifiStartResponse and then WifiConnectStatus from the phone.
                // A head unit doing the same is still waiting for both when
                // Headway closes Bluetooth and connects, which is why a real
                // Chevrolet refused TCP on a port it had never been told to
                // open. Status is field 3 here and field 1 in
                // WifiConnectionStatus -- they are not interchangeable.
                send(
                    MessageId.WIFI_START_RESPONSE,
                    WifiStartResponse.newBuilder().setStatus(Status.STATUS_SUCCESS).build(),
                )
                val host = endpoint?.ipAddress ?: projectionEndpoint?.ipAddress
                val tcpPort = endpoint?.port ?: projectionEndpoint?.port
                val resolved =
                    if (host != null && tcpPort != null) CarEndpoint(host, tcpPort) else null
                if (resolved == null) {
                    onStep(
                        "head unit gave credentials but no endpoint; it will be " +
                            "resolved from the access point after joining"
                    )
                }
                return@coroutineScope CarNetworkCredentials(
                    ssid = i.ssid,
                    passphrase = i.password,
                    bssid = i.bssid,
                    securityMode = i.securityMode,
                    accessPointType = i.accessPointType,
                    endpoint = resolved,
                    advertisedFrequenciesMhz = advertised,
                )
            }
        }
        throw WirelessHandshakeException(
            if (heardSomething.get()) {
                "head unit did not supply both an endpoint and credentials within " +
                    "$maxMessages messages"
            } else {
                // The distinction matters for diagnosis: a unit that accepted the
                // socket and then said nothing, even when prompted, is a
                // different problem from one that talked and gave us the wrong
                // thing.
                "head unit accepted the RFCOMM connection but sent nothing, including " +
                    "after $MAX_SILENCE_PROBES probe(s). Check that Android Auto is " +
                    "enabled for this phone in the car's Bluetooth device settings."
            }
        )
        } catch (e: WirelessHandshakeException) {
            throw e
        } catch (e: Throwable) {
            // A read that failed because the deadline closed the socket is not a
            // Bluetooth error, and reporting it as one sends the next reader
            // hunting the wrong problem.
            if (expired.get()) {
                throw WirelessHandshakeException(
                    deadlineMessage(outstanding(), deadlineMillis, heardSomething.get())
                )
            }
            throw e
        } finally {
            prodder.cancel()
        }
    }

    /** The give-up message, kept in one place so the log and the exception agree. */
    private fun deadlineMessage(
        outstanding: String,
        deadlineMillis: Long,
        heardAnything: Boolean,
    ): String = if (heardAnything) {
        "head unit never supplied $outstanding within ${deadlineMillis}ms. It did talk, " +
            "so Bluetooth and pairing are fine; it is the Wi-Fi credentials handshake that " +
            "stalled. Check that Android Auto is enabled for this phone in the car's " +
            "Bluetooth device settings, and that the car is not already projecting to " +
            "another phone."
    } else {
        // The distinct never-spoke case, which was previously unreachable once
        // the deadline fired first: telling this user 'it answered the version
        // exchange' sends them to the Wi-Fi handshake when the unit never said a
        // word.
        "head unit accepted the RFCOMM connection and then sent nothing at all for " +
            "${deadlineMillis}ms, including after being prompted. Check that Android Auto " +
            "is enabled for this phone in the car's Bluetooth device settings."
    }

    /**
     * Answers a keepalive ping, echoing the timestamp the peer sent.
     *
     * The body is `PingRequest`/`PingResponse` from the control service, which
     * already carry `timestamp` as field 1 — the layout aa-proxy-rs encodes by
     * hand for this message (`src/bluetooth.rs` L930-L936). Echoing rather than
     * re-stamping because a ping's purpose is round-trip measurement, and the
     * sender's clock is the only one that can interpret its own timestamp.
     */
    private suspend fun answerPing(message: RfcommMessage) {
        val timestamp = runCatching {
            aap_protobuf.service.control.message.PingRequestOuterClass.PingRequest
                .parseFrom(message.payload).timestamp
        }.getOrDefault(0L)
        send(
            MessageId.WIFI_PING_RESPONSE,
            aap_protobuf.service.control.message.PingResponseOuterClass.PingResponse
                .newBuilder()
                .setTimestamp(timestamp)
                .build(),
        )
    }

    /**
     * Keeps reading the RFCOMM link after the credentials have arrived.
     *
     * ## Why the link cannot simply be left alone
     *
     * [perform] returns the moment it has credentials, and until this existed
     * nothing read the socket again until it was closed. That leaves the whole
     * Wi-Fi join window — which is where a real attempt spends its time, and can
     * be a minute or more — with the head unit talking into a socket nobody is
     * listening to. A ping goes unanswered; a `WifiConnectionStatus` reporting a
     * problem is never seen; anything the unit says about its access point is
     * lost. aa-proxy-rs keeps a reader on this link for the entire bring-up and
     * answers pings from both directions, and it is the reference that actually
     * talks to real head units.
     *
     * Runs until cancelled or until the link fails. Failures are reported and
     * swallowed rather than thrown: by the time this is running, the session's
     * fate belongs to the Wi-Fi join and the TCP connect, and an RFCOMM link
     * that drops after handing over credentials must not by itself fail an
     * attempt that is otherwise succeeding.
     */
    suspend fun serviceLink() {
        try {
            while (true) {
                val message = RfcommMessage.read(transport)
                val id = MessageId.forNumber(message.messageId)
                onStep(
                    "rx id=${message.messageId} (${id?.name ?: "unknown"}) " +
                        RfcommMessage.hex(message.payload) + " [after handshake]"
                )
                when (id) {
                    MessageId.WIFI_PING_REQUEST -> answerPing(message)
                    MessageId.WIFI_CONNECTION_STATUS -> {
                        val status = WifiConnectionStatus.parseFrom(message.payload)
                        if (status.status != Status.STATUS_SUCCESS) {
                            onStep(
                                "the head unit reports a problem with the Wi-Fi link: " +
                                    "${status.status}" +
                                    if (status.hasErrorMessage()) ": ${status.errorMessage}" else ""
                            )
                        }
                    }
                    // Everything else is logged above and needs no answer. A
                    // second WifiStartRequest in particular is normal: units
                    // re-announce their endpoint once their access point is up.
                    else -> Unit
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onStep("the Bluetooth link stopped being readable after the handshake: ${e.message}")
        }
    }

    /**
     * Tells the head unit the phone is now on its access point.
     *
     * The second half of what a real phone owes the head unit after the
     * credentials exchange, and it has to be sent *after* the Wi-Fi association
     * rather than with the rest of the handshake — it is the announcement that
     * the association happened. aa-proxy-rs reads it as the final step of
     * bring-up (`src/bluetooth.rs` L6245), and a head unit waiting for it has no
     * reason to start accepting AAP connections.
     *
     * Failing to send it is not worth aborting the attempt over: the endpoint is
     * discoverable without it, so a head unit that does not care should still
     * get a session. Reported and swallowed.
     */
    suspend fun reportWifiConnected() {
        try {
            send(
                MessageId.WIFI_CONNECTION_STATUS,
                WifiConnectionStatus.newBuilder().setStatus(Status.STATUS_SUCCESS).build(),
            )
        } catch (e: Exception) {
            onStep("could not tell the head unit we joined its network: ${e.message}")
        }
    }

    /**
     * Tells the head unit the phone could not get onto its access point.
     *
     * The counterpart to [reportWifiConnected], and its absence was a real gap.
     * A phone that gives up simply dropped Bluetooth, leaving the head unit in
     * the middle of a bootstrap it believed was still in flight — and Headway
     * then reconnected half a second later into a projection state machine that
     * had never been told the previous attempt failed. `Status` has the
     * vocabulary for this precisely because real phones use it: aa-proxy-rs
     * decodes the frame from genuine phones and records both shapes, `[08, 00]`
     * for success and a large negative for "phone cannot connect to our Wi-Fi
     * AP" (`src/bluetooth.rs` L4738-L4750).
     *
     * Best-effort, like its counterpart: by the time this is sent the attempt
     * has already failed, and a Bluetooth link that is also gone must not turn
     * one failure into two.
     */
    suspend fun reportWifiFailed(status: Status) {
        try {
            onStep("telling the head unit the Wi-Fi join failed: $status")
            send(
                MessageId.WIFI_CONNECTION_STATUS,
                WifiConnectionStatus.newBuilder().setStatus(status).build(),
            )
        } catch (e: Exception) {
            onStep("could not tell the head unit the join failed: ${e.message}")
        }
    }

    /**
     * Every Wi-Fi frequency the head unit advertised, in MHz.
     *
     * Two schema revisions are in circulation: one repeats the frequencies at
     * field 3, the other adds a second varint at field 4. The target Chevrolet
     * uses both at once -- it sent `field 3 = 0, field 4 = 5745` -- so reading
     * only field 3 yields a placeholder and nothing usable.
     */
    private fun advertisedFrequencies(request: AawWifiVersionRequest): List<Int> =
        buildList {
            addAll(request.supportedWifiChannelsList)
            if (request.hasSupportedWifiChannelAlt()) add(request.supportedWifiChannelAlt)
        }

    /**
     * The projection endpoint from a version request, if there really is one.
     *
     * Mirrors `wifi_projection_info_looks_valid` in `aa-proxy-rs/src/bluetooth.rs`
     * L1412-L1421: the other schema revision puts a `HeadUnitInfo` at this field
     * number, and its first field is a string as well, so an unguarded read
     * turns a car's make into an IP address and sends the phone off to connect
     * to "Chevrolet".
     */
    private fun usableEndpoint(request: AawWifiVersionRequest): WifiProjectionProtocolInfo? {
        if (!request.hasProjection()) return null
        val candidate = request.projection
        if (candidate.port <= 0 || candidate.port > 65535) return null
        if (!looksLikeLiteralAddress(candidate.ipAddress)) return null
        return candidate
    }

    /**
     * Whether a string is an IP literal.
     *
     * Deliberately not `InetAddress.getByName`, which resolves anything that is
     * not a literal -- and this runs on a phone attached to a car with no
     * internet, where a DNS attempt is a stall, not an answer.
     */
    private fun looksLikeLiteralAddress(value: String): Boolean {
        if (value.isEmpty()) return false
        if (value.contains(':')) return true // IPv6; the head unit endpoints seen are v4
        val parts = value.split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) &&
                part.toInt() in 0..255
        }
    }

    /** Body for a probe message. Kept beside [SILENCE_PROBES] so they cannot drift. */
    private fun probeBody(id: MessageId): MessageLite = when (id) {
        MessageId.WIFI_VERSION_REQUEST ->
            aap_protobuf.aaw.WifiVersionRequestOuterClass.WifiVersionRequest.getDefaultInstance()
        else ->
            aap_protobuf.aaw.WifiInfoRequestOuterClass.WifiInfoRequest.getDefaultInstance()
    }

    private suspend fun send(id: MessageId, body: MessageLite) {
        val message = RfcommMessage.of(id, body)
        onStep("tx id=${id.number} (${id.name}) ${RfcommMessage.hex(message.payload)}")
        transport.write(message.encode())
    }

    companion object {
        /**
         * Field 4 of `AawWifiVersionResponse`, per `Status.proto` STATUS_SUCCESS.
         *
         * Anything else and a real head unit stops the handshake without saying
         * why, because a non-zero status is the phone declining.
         */
        const val STATUS_SUCCESS: Int = 0

        /** 802.11 caps an SSID at 32 octets; anything longer cannot be matched. */
        const val MAX_SSID_BYTES: Int = 32

        /**
         * How long to let the head unit speak before prompting it.
         *
         * Every reference has the head unit open the conversation immediately,
         * so a unit that has not spoken in five seconds is not being slow.
         */
        const val DEFAULT_SILENCE_PROBE_MILLIS: Long = 5_000

        /**
         * What to send a head unit that will not open the conversation, in order.
         *
         * A version exchange first because that is what the references show real
         * units leading with -- aa-proxy-rs notes that `WifiVersionRequest` can
         * itself carry the projection endpoint, and that strict units may then
         * omit `WifiStartRequest` entirely. Asking for the credentials is the
         * fallback, since it is the one message whose reply we need regardless.
         *
         * Only used before the head unit has said anything. Once it has, the
         * only thing worth asking for again is the credentials.
         */
        val SILENCE_PROBES: List<MessageId> =
            listOf(MessageId.WIFI_VERSION_REQUEST, MessageId.WIFI_INFO_REQUEST)

        /**
         * How many times to prod before giving up on the whole attempt.
         *
         * Reconnection depends on failing promptly, so this is a bound on how
         * long a wedged head unit can hold the attempt open, not a retry budget.
         */
        const val MAX_SILENCE_PROBES: Int = 3

        /**
         * How long the whole exchange gets before it is called off.
         *
         * Sits inside `BluetoothCarLink`'s own timeout on purpose, so the
         * failure the user sees is this class's description of what was missing
         * rather than a bare cancellation from the caller.
         */
        const val DEFAULT_DEADLINE_MILLIS: Long = 18_000

        /**
         * The AA Wireless RFCOMM service UUID.
         *
         * Confirmed identically in three independent references:
         * `aa-proxy-rs/src/bluetooth.rs` L381
         * (`0x4de17a0052cb11e6bdf40800200c9a66`),
         * `openauto/src/btservice/AndroidBluetoothService.cpp` L26, and
         * `WirelessAndroidAutoDongle/.../bluetoothHandler.cpp` L24.
         */
        const val AA_WIRELESS_SERVICE_UUID: String = "4de17a00-52cb-11e6-bdf4-0800200c9a66"

        /**
         * The Bluetooth profile the head unit connects on to wake Android Auto.
         * Source: `aa-proxy-rs/src/bluetooth.rs` (HSP AG).
         */
        const val HSP_AG_UUID: String = "00001112-0000-1000-8000-00805f9b34fb"

        /**
         * RFCOMM channel the references pin.
         *
         * `aa-proxy-rs` and `aawgd` both use 8, but openauto lets Qt allocate a
         * dynamic channel and publishes it in its SDP record. A phone must
         * therefore discover the channel via SDP rather than assume 8 — Android's
         * `createRfcommSocketToServiceRecord` does that lookup for us, which is
         * why Headway never hardcodes this. Kept for diagnostics.
         */
        const val COMMONLY_USED_RFCOMM_CHANNEL: Int = 8
    }
}

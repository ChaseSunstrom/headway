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
import aap_protobuf.aaw.WifiStartRequestOuterClass.WifiStartRequest
import aap_protobuf.aaw.WifiVersionResponseOuterClass.WifiVersionResponse
import com.google.protobuf.MessageLite
import dev.headway.protocol.io.Transport

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
    val ipAddress: String,
    val port: Int,
) {
    /** Redacts the passphrase; this gets logged and logs get shared for diagnosis. */
    override fun toString(): String =
        "CarNetworkCredentials(ssid=$ssid, bssid=$bssid, security=$securityMode, " +
            "type=$accessPointType, endpoint=$ipAddress:$port, passphrase=<redacted>)"
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
) {

    /**
     * Runs the exchange and returns the credentials.
     *
     * @param maxMessages guards against a head unit that chatters without ever
     *   sending the response; without it a malfunctioning unit hangs the
     *   connection attempt instead of failing it, and reconnection depends on
     *   failing promptly.
     */
    suspend fun perform(maxMessages: Int = 16): CarNetworkCredentials {
        var endpoint: WifiStartRequest? = null
        var info: WifiInfoResponse? = null

        repeat(maxMessages) {
            val message = RfcommMessage.read(transport)
            onStep(
                "rx id=${message.messageId} " +
                    "(${MessageId.forNumber(message.messageId)?.name ?: "unknown"}) " +
                    RfcommMessage.hex(message.payload)
            )
            when (MessageId.forNumber(message.messageId)) {
                MessageId.WIFI_START_REQUEST -> {
                    endpoint = WifiStartRequest.parseFrom(message.payload)
                    onStep("head unit offers ${endpoint!!.ipAddress}:${endpoint!!.port}")
                    // Ask for the credentials now that we know where to connect.
                    send(MessageId.WIFI_INFO_REQUEST, aap_protobuf.aaw.WifiInfoRequestOuterClass.WifiInfoRequest.getDefaultInstance())
                }

                MessageId.WIFI_INFO_RESPONSE -> {
                    info = WifiInfoResponse.parseFrom(message.payload)
                    onStep("received credentials for SSID '${info!!.ssid}'")
                }

                MessageId.WIFI_VERSION_REQUEST -> {
                    // Answer with the same shape the references send. The four
                    // fields are named unknown_value_* in aasdk's schema because
                    // nobody has established what they mean; echoing the
                    // observed values is the only defensible option.
                    send(
                        MessageId.WIFI_VERSION_RESPONSE,
                        WifiVersionResponse.newBuilder()
                            .setUnknownValueA(1)
                            .setUnknownValueB(1)
                            .setUnknownValueD(2)
                            .build(),
                    )
                }

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

            val e = endpoint
            val i = info
            if (e != null && i != null) {
                return CarNetworkCredentials(
                    ssid = i.ssid,
                    passphrase = i.password,
                    bssid = i.bssid,
                    securityMode = i.securityMode,
                    accessPointType = i.accessPointType,
                    ipAddress = e.ipAddress,
                    port = e.port,
                )
            }
        }
        throw WirelessHandshakeException(
            "head unit did not supply both an endpoint and credentials within $maxMessages messages"
        )
    }

    private suspend fun send(id: MessageId, body: MessageLite) {
        val message = RfcommMessage.of(id, body)
        onStep("tx id=${id.number} (${id.name}) ${RfcommMessage.hex(message.payload)}")
        transport.write(message.encode())
    }

    companion object {
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

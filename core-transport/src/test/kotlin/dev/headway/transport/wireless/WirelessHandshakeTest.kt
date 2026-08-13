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
import aap_protobuf.aaw.WifiConnectionStatusOuterClass.WifiConnectionStatus
import aap_protobuf.aaw.WifiInfoResponseOuterClass.WifiInfoResponse
import aap_protobuf.service.wifiprojection.message.AccessPointTypeOuterClass.AccessPointType
import aap_protobuf.service.wifiprojection.message.WifiSecurityModeOuterClass.WifiSecurityMode
import aap_protobuf.aaw.WifiStartResponseOuterClass.WifiStartResponse
import aap_protobuf.aaw.WifiStartRequestOuterClass.WifiStartRequest
import headway.aaw.AawVersion.AawWifiVersionRequest
import headway.aaw.AawVersion.AawWifiVersionResponse
import headway.aaw.AawVersion.WifiProjectionProtocolInfo
import dev.headway.protocol.io.Transport
import dev.headway.transport.LoopbackTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The Bluetooth credentials exchange, over the fake transport.
 *
 * There is no Bluetooth radio in CI (BLOCKERS.md B-001), but RFCOMM is just an
 * ordered byte stream, so the exchange itself is fully exercised here. What is
 * not covered is Android's socket setup and SDP lookup, which live in `:app`.
 */
class WirelessHandshakeTest {

    private fun credentials() = WifiInfoResponse.newBuilder()
        .setSsid("MyCar-AA")
        .setPassword("hunter2hunter2")
        .setBssid("aa:bb:cc:dd:ee:ff")
        .setSecurityMode(WifiSecurityMode.WPA2_PERSONAL)
        .setAccessPointType(AccessPointType.DYNAMIC)
        .build()

    private suspend fun send(transport: Transport, id: MessageId, body: com.google.protobuf.MessageLite) {
        transport.write(RfcommMessage.of(id, body).encode())
    }

    @Test
    fun `the exchange yields the car's credentials and endpoint`() = runBlocking {
        LoopbackTransport.pair().use { pair ->
            val phone = async(Dispatchers.IO) { WirelessHandshake(pair.phone).perform() }

            // Head unit speaks first with the endpoint.
            send(
                pair.headUnit, MessageId.WIFI_START_REQUEST,
                WifiStartRequest.newBuilder().setIpAddress("192.168.43.1").setPort(5288).build(),
            )
            // Phone asks for credentials; answer.
            val request = RfcommMessage.read(pair.headUnit)
            assertEquals(MessageId.WIFI_INFO_REQUEST.number, request.messageId)
            send(pair.headUnit, MessageId.WIFI_INFO_RESPONSE, credentials())

            val result = withTimeout(10_000) { phone.await() }
            assertEquals("MyCar-AA", result.ssid)
            assertEquals("hunter2hunter2", result.passphrase)
            assertEquals("aa:bb:cc:dd:ee:ff", result.bssid)
            assertEquals("192.168.43.1", result.endpoint?.ipAddress)
            assertEquals(5288, result.endpoint?.port)
            assertEquals(WifiSecurityMode.WPA2_PERSONAL, result.securityMode)
        }
    }

    @Test
    fun `the framing matches the documented layout`() {
        // [u16 BE payload size][u16 BE message id][protobuf]
        // Source: WirelessAndroidAutoDongle/.../bluetoothProfiles.cpp L100-L114.
        val body = WifiStartRequest.newBuilder().setIpAddress("1.2.3.4").setPort(5288).build()
        val encoded = RfcommMessage.of(MessageId.WIFI_START_REQUEST, body).encode()

        val size = ((encoded[0].toInt() and 0xFF) shl 8) or (encoded[1].toInt() and 0xFF)
        val id = ((encoded[2].toInt() and 0xFF) shl 8) or (encoded[3].toInt() and 0xFF)

        assertEquals(body.serializedSize, size, "size field counts the protobuf only, not the header")
        assertEquals(MessageId.WIFI_START_REQUEST.number, id)
        assertEquals(RfcommMessage.HEADER_SIZE + body.serializedSize, encoded.size)
    }

    @Test
    fun `a version request is answered rather than ignored`() = runBlocking {
        LoopbackTransport.pair().use { pair ->
            val phone = async(Dispatchers.IO) { WirelessHandshake(pair.phone).perform() }

            send(
                pair.headUnit, MessageId.WIFI_VERSION_REQUEST,
                aap_protobuf.aaw.WifiVersionRequestOuterClass.WifiVersionRequest.getDefaultInstance(),
            )
            val versionReply = RfcommMessage.read(pair.headUnit)
            assertEquals(MessageId.WIFI_VERSION_RESPONSE.number, versionReply.messageId)

            send(
                pair.headUnit, MessageId.WIFI_START_REQUEST,
                WifiStartRequest.newBuilder().setIpAddress("10.0.0.1").setPort(5288).build(),
            )
            RfcommMessage.read(pair.headUnit) // the info request
            send(pair.headUnit, MessageId.WIFI_INFO_RESPONSE, credentials())

            assertEquals("10.0.0.1", withTimeout(10_000) { phone.await() }.endpoint?.ipAddress)
        }
    }

    @Test
    fun `a failure status aborts rather than proceeding to a doomed Wi-Fi join`() = runBlocking {
        LoopbackTransport.pair().use { pair ->
            val phone = async(Dispatchers.IO) {
                runCatching { WirelessHandshake(pair.phone).perform() }
            }
            send(
                pair.headUnit, MessageId.WIFI_CONNECTION_STATUS,
                WifiConnectionStatus.newBuilder()
                    .setStatus(Status.STATUS_WIFI_INCORRECT_CREDENTIALS)
                    .setErrorMessage("bad key")
                    .build(),
            )
            val outcome = withTimeout(10_000) { phone.await() }
            val error = outcome.exceptionOrNull()
            assertTrue(error is WirelessHandshakeException, "got $outcome")
            assertTrue(error!!.message!!.contains("STATUS_WIFI_INCORRECT_CREDENTIALS"), error.message)
        }
    }

    @Test
    fun `an unknown message id is skipped rather than fatal`() = runBlocking {
        // Head units send messages the references never documented. Dropping
        // the connection over one would fail a car that otherwise works.
        LoopbackTransport.pair().use { pair ->
            val phone = async(Dispatchers.IO) { WirelessHandshake(pair.phone).perform() }

            pair.headUnit.write(RfcommMessage(messageId = 9999, payload = byteArrayOf(1, 2, 3)).encode())
            send(
                pair.headUnit, MessageId.WIFI_START_REQUEST,
                WifiStartRequest.newBuilder().setIpAddress("172.16.0.1").setPort(5288).build(),
            )
            RfcommMessage.read(pair.headUnit)
            send(pair.headUnit, MessageId.WIFI_INFO_RESPONSE, credentials())

            assertEquals("172.16.0.1", withTimeout(10_000) { phone.await() }.endpoint?.ipAddress)
        }
    }

    @Test
    fun `a chattering head unit fails the attempt instead of hanging it`() = runBlocking {
        LoopbackTransport.pair().use { pair ->
            val phone = async(Dispatchers.IO) {
                runCatching { WirelessHandshake(pair.phone).perform(maxMessages = 3) }
            }
            repeat(3) {
                pair.headUnit.write(RfcommMessage(messageId = 4242, payload = ByteArray(0)).encode())
            }
            val error = withTimeout(10_000) { phone.await() }.exceptionOrNull()
            assertTrue(error is WirelessHandshakeException, "expected a handshake failure, got $error")
        }
    }

    @Test
    fun `the passphrase is not written into logs`() {
        val creds = CarNetworkCredentials(
            ssid = "MyCar-AA",
            passphrase = "hunter2hunter2",
            bssid = "aa:bb:cc:dd:ee:ff",
            securityMode = WifiSecurityMode.WPA2_PERSONAL,
            accessPointType = AccessPointType.DYNAMIC,
            endpoint = CarEndpoint("192.168.43.1", 5288),
        )
        // Logs get exported and shared to diagnose real-car failures, so the
        // key must not ride along.
        val rendered = creds.toString()
        assertTrue(rendered.contains("MyCar-AA"))
        assertTrue(!rendered.contains("hunter2hunter2"), rendered)
        assertTrue(rendered.contains("redacted"), rendered)
    }

    @Test
    fun `a silent head unit is prompted rather than waited on forever`() = runBlocking {
        // The failure this exists for: a real Chevrolet Infotainment 3 unit
        // accepted the RFCOMM connection on channel 3 and then sent nothing at
        // all. Every reference has the head unit speak first, so the original
        // code blocked reading until the outer timeout and produced no evidence.
        LoopbackTransport.pair().use { pair ->
            val phone = async(Dispatchers.IO) {
                WirelessHandshake(pair.phone).perform(silenceProbeMillis = 200)
            }

            // Say nothing. The phone should prod us.
            val probe = withTimeout(10_000) { RfcommMessage.read(pair.headUnit) }
            assertEquals(MessageId.WIFI_VERSION_REQUEST.number, probe.messageId)

            // Now behave like a head unit that just needed asking.
            send(pair.headUnit, MessageId.WIFI_START_REQUEST,
                WifiStartRequest.newBuilder().setIpAddress("192.168.1.1").setPort(5288).build())
            RfcommMessage.read(pair.headUnit) // the info request
            send(pair.headUnit, MessageId.WIFI_INFO_RESPONSE, credentials())

            assertEquals("192.168.1.1", withTimeout(10_000) { phone.await() }.endpoint?.ipAddress)
        }
    }

    @Test
    fun `a head unit that speaks first is never probed`() = runBlocking {
        // Probing a unit that was about to talk anyway risks confusing it, so
        // the prod must not fire when the conversation is already underway.
        LoopbackTransport.pair().use { pair ->
            val phone = async(Dispatchers.IO) {
                WirelessHandshake(pair.phone).perform(silenceProbeMillis = 500)
            }
            send(pair.headUnit, MessageId.WIFI_START_REQUEST,
                WifiStartRequest.newBuilder().setIpAddress("10.1.1.1").setPort(5288).build())

            val first = withTimeout(10_000) { RfcommMessage.read(pair.headUnit) }
            assertEquals(
                MessageId.WIFI_INFO_REQUEST.number,
                first.messageId,
                "a head unit that spoke first must not be probed",
            )
            send(pair.headUnit, MessageId.WIFI_INFO_RESPONSE, credentials())
            assertEquals("10.1.1.1", withTimeout(10_000) { phone.await() }.endpoint?.ipAddress)
        }
    }

    @Test
    fun `a head unit that stays silent through every probe reports that clearly`() = runBlocking {
        LoopbackTransport.pair().use { pair ->
            val phone = async(Dispatchers.IO) {
                runCatching {
                    WirelessHandshake(pair.phone).perform(maxMessages = 1, silenceProbeMillis = 100)
                }
            }
            // Drain the probes; never answer.
            repeat(2) { runCatching { withTimeout(5_000) { RfcommMessage.read(pair.headUnit) } } }
            pair.headUnit.close()

            val error = withTimeout(15_000) { phone.await() }.exceptionOrNull()
            assertTrue(error != null, "expected a handshake failure, got $error")
        }
    }

    /**
     * The exact bytes a 2021 Chevrolet Infotainment 3 head unit sent as its
     * `WifiVersionRequest`, captured from the target vehicle over RFCOMM.
     *
     * ```text
     * 08 01   field 1 varint 1      major_version
     * 10 00   field 2 varint 0      minor_version
     * 18 00   field 3 varint 0      supported_wifi_channels[0]
     * 20 f1 2c field 4 varint 5745  supported_wifi_channel_alt (5 GHz channel 149)
     * ```
     */
    private val malibuVersionRequest = byteArrayOf(
        0x08, 0x01, 0x10, 0x00, 0x18, 0x00, 0x20, 0xf1.toByte(), 0x2c,
    )

    @Test
    fun `the car's real version request decodes under the corrected schema`() {
        // aasdk declares WifiVersionRequest as an empty message, so these bytes
        // decode to nothing under it and there is no way to notice.
        val request = AawWifiVersionRequest.parseFrom(malibuVersionRequest)

        assertEquals(1, request.majorVersion)
        assertEquals(0, request.minorVersion)
        assertEquals(listOf(0), request.supportedWifiChannelsList)
        assertTrue(request.hasSupportedWifiChannelAlt(), "field 4 must be readable")
        assertEquals(5745, request.supportedWifiChannelAlt, "5745 MHz is 5 GHz channel 149")

        // And nothing in it may be mistaken for a projection endpoint.
        assertTrue(!request.hasProjection())
    }

    /**
     * The exact `WifiInfoResponse` the same vehicle sent, captured over RFCOMM.
     *
     * ```text
     * 0a 0f "myChevrolet1189"    field 1 string  ssid
     * 12 0c "<12-char key>"      field 2 string  password
     * 1a 11 "ce:44:26:bf:18:ec"  field 3 string  bssid
     * 20 08                      field 4 varint  security_mode = 8
     * ```
     *
     * The passphrase bytes are replaced with a placeholder of the same length;
     * nothing here depends on its value and a real key does not belong in a
     * repository.
     */
    private val malibuInfoResponse: ByteArray = buildString {
        append("myChevrolet1189")
    }.let { ssid ->
        val key = "XXXXXXXXXXXX"
        val bssid = "ce:44:26:bf:18:ec"
        byteArrayOf(0x0a, ssid.length.toByte()) + ssid.toByteArray() +
            byteArrayOf(0x12, key.length.toByte()) + key.toByteArray() +
            byteArrayOf(0x1a, bssid.length.toByte()) + bssid.toByteArray() +
            byteArrayOf(0x20, 0x08)
    }

    @Test
    fun `wire value 8 is WPA2-PSK, which is what the car actually runs`() {
        // This is the regression the enum numbering cost us. Headway numbered
        // WifiSecurityMode sequentially, so the car's 8 decoded as
        // WPA2_ENTERPRISE and every log line and every reader was told the
        // Chevrolet ran an enterprise network. It runs WPA2-PSK. All three
        // references number this enum as a bitfield -- 4 = WPA, 8 = WPA2,
        // 16 = enterprise -- so 8 is WPA2_PERSONAL.
        val info = WifiInfoResponse.parseFrom(malibuInfoResponse)

        assertEquals("myChevrolet1189", info.ssid)
        assertEquals("ce:44:26:bf:18:ec", info.bssid)
        assertEquals(WifiSecurityMode.WPA2_PERSONAL, info.securityMode)
    }

    @Test
    fun `a mixed-mode access point does not fail the parse`() {
        // Wire value 12 is WPA_WPA2_PERSONAL, which is what a head unit running
        // a mixed WPA/WPA2 access point sends, and 24 is what openauto sends.
        // Neither existed in the sequential enum, and because security_mode was
        // `required`, an unknown value left it unset and made parseFrom throw
        // "Message missing required fields" -- naming no field, in a car, with
        // the credentials already on the wire.
        for (wire in listOf(12, 20, 24, 28)) {
            val bytes = malibuInfoResponse.copyOf().also { it[it.size - 1] = wire.toByte() }
            val info = WifiInfoResponse.parseFrom(bytes)
            assertEquals("myChevrolet1189", info.ssid, "security_mode=$wire broke the parse")
        }

        // And a value no reference declares must degrade rather than throw,
        // because the security type is decided by the presence of a passphrase.
        val unknown = malibuInfoResponse.copyOf().also { it[it.size - 1] = 0x63 }
        val info = WifiInfoResponse.parseFrom(unknown)
        assertEquals("myChevrolet1189", info.ssid)
        assertTrue(!info.hasSecurityMode(), "an unmodelled value must read as absent, not throw")
    }

    @Test
    fun `an open head unit network parses without a passphrase`() {
        // `required string password` would abort the parse for an access point
        // with no key at all -- and CarWifiNetwork already has a code path for
        // exactly that case, which was unreachable.
        val ssid = "OpenCar"
        val bytes = byteArrayOf(0x0a, ssid.length.toByte()) + ssid.toByteArray()

        val info = WifiInfoResponse.parseFrom(bytes)
        assertEquals(ssid, info.ssid)
        assertTrue(info.password.isEmpty())
    }

    @Test
    fun `the version response carries a success status`() = runBlocking {
        // The bug this exists for. Headway used to answer the car's version
        // request with status = 2 in field 4, because aasdk names that field
        // `unknown_value_d` and nothing suggested it meant anything. The head
        // unit read a non-success status, concluded the phone had declined, and
        // stopped talking -- silently, with no error anywhere.
        LoopbackTransport.pair().use { pair ->
            val phone = async(Dispatchers.IO) {
                runCatching { WirelessHandshake(pair.phone).perform(maxMessages = 1) }
            }
            pair.headUnit.write(
                RfcommMessage(MessageId.WIFI_VERSION_REQUEST.number, malibuVersionRequest).encode()
            )

            val reply = withTimeout(10_000) { RfcommMessage.read(pair.headUnit) }
            assertEquals(MessageId.WIFI_VERSION_RESPONSE.number, reply.messageId)

            val response = AawWifiVersionResponse.parseFrom(reply.payload)
            assertEquals(
                WirelessHandshake.STATUS_SUCCESS, response.status,
                "a non-zero status makes a real head unit give up without saying why",
            )
            assertEquals(1, response.majorVersion, "mirror the version the head unit announced")
            assertEquals(0, response.minorVersion)

            // Field 5 stays absent by default. Real phones do populate it, but
            // its meaning is inferred rather than documented, and a guessed
            // value in an unverified field is exactly what broke this handshake
            // one field earlier. The quirk file turns it on.
            assertTrue(
                !response.hasSelectedWifiChannelType(),
                "an inferred field must be opt-in, not the default",
            )

            pair.headUnit.close()
            phone.await()
            Unit
        }
    }

    @Test
    fun `the selected wifi channel can be announced when a head unit needs it`() = runBlocking {
        // The quirk file's announceWifiChannel. 0 is a placeholder in the
        // observed advertisement, so the value picked must be the real
        // frequency -- naming the placeholder would be worse than saying
        // nothing.
        LoopbackTransport.pair().use { pair ->
            val phone = async(Dispatchers.IO) {
                runCatching {
                    WirelessHandshake(pair.phone, announceSelectedWifiChannel = true)
                        .perform(maxMessages = 1)
                }
            }
            pair.headUnit.write(
                RfcommMessage(MessageId.WIFI_VERSION_REQUEST.number, malibuVersionRequest).encode()
            )

            val reply = withTimeout(10_000) { RfcommMessage.read(pair.headUnit) }
            val response = AawWifiVersionResponse.parseFrom(reply.payload)
            assertEquals(WirelessHandshake.STATUS_SUCCESS, response.status)
            assertEquals(5745, response.selectedWifiChannelType, "0 is a placeholder, 5745 is real")

            pair.headUnit.close()
            phone.await()
            Unit
        }
    }

    @Test
    fun `a projection endpoint in the version request is used when there is no start request`() =
        runBlocking {
            // Some head units never send WifiStartRequest at all.
            LoopbackTransport.pair().use { pair ->
                val phone = async(Dispatchers.IO) { WirelessHandshake(pair.phone).perform() }

                send(
                    pair.headUnit, MessageId.WIFI_VERSION_REQUEST,
                    AawWifiVersionRequest.newBuilder()
                        .setMajorVersion(1)
                        .setMinorVersion(1)
                        .setProjection(
                            WifiProjectionProtocolInfo.newBuilder()
                                .setIpAddress("192.168.43.1")
                                .setPort(5288)
                        )
                        .build(),
                )
                RfcommMessage.read(pair.headUnit) // the version response
                send(pair.headUnit, MessageId.WIFI_INFO_RESPONSE, credentials())

                val result = withTimeout(10_000) { phone.await() }
                assertEquals("192.168.43.1", result.endpoint?.ipAddress)
                assertEquals(5288, result.endpoint?.port)
            }
        }

    @Test
    fun `a head unit description at the projection field is not mistaken for an address`() =
        runBlocking {
            // The trap: the competing schema revision puts HeadUnitInfo at field
            // 5, and its field 1 is a string too. Unguarded, "Chevrolet" reads
            // as an ip_address and the phone tries to connect to it.
            LoopbackTransport.pair().use { pair ->
                val phone = async(Dispatchers.IO) { WirelessHandshake(pair.phone).perform() }

                send(
                    pair.headUnit, MessageId.WIFI_VERSION_REQUEST,
                    AawWifiVersionRequest.newBuilder()
                        .setMajorVersion(1)
                        .setProjection(
                            // What HeadUnitInfo{car_make: "Chevrolet"} looks like
                            // when read as a WifiProjectionProtocolInfo.
                            WifiProjectionProtocolInfo.newBuilder().setIpAddress("Chevrolet")
                        )
                        .build(),
                )
                RfcommMessage.read(pair.headUnit) // the version response
                RfcommMessage.read(pair.headUnit) // the info request
                send(pair.headUnit, MessageId.WIFI_INFO_RESPONSE, credentials())

                // No believable endpoint arrived, so there must be none -- the
                // alternative is dialling a car manufacturer by name.
                val result = withTimeout(10_000) { phone.await() }
                assertEquals(
                    null, result.endpoint,
                    "a head unit description is not an address",
                )
            }
        }

    @Test
    fun `the observed Chevrolet flow completes without an endpoint`() = runBlocking {
        // Captured from the target vehicle, in order:
        //   rx WIFI_VERSION_REQUEST   (the bytes below)
        //   tx WIFI_VERSION_RESPONSE  status 0
        //   tx WIFI_INFO_REQUEST      -- the car volunteers nothing, so ask
        //   rx WIFI_INFO_RESPONSE     SSID, passphrase, BSSID
        //   ...and no WifiStartRequest, ever.
        // Waiting for an endpoint here is what left the app looping with the
        // credentials already in hand.
        LoopbackTransport.pair().use { pair ->
            val phone = async(Dispatchers.IO) { WirelessHandshake(pair.phone).perform() }

            pair.headUnit.write(
                RfcommMessage(MessageId.WIFI_VERSION_REQUEST.number, malibuVersionRequest).encode()
            )
            assertEquals(
                MessageId.WIFI_VERSION_RESPONSE.number,
                withTimeout(10_000) { RfcommMessage.read(pair.headUnit) }.messageId,
            )
            assertEquals(
                MessageId.WIFI_INFO_REQUEST.number,
                withTimeout(10_000) { RfcommMessage.read(pair.headUnit) }.messageId,
                "the credentials must be asked for, not waited for",
            )
            send(pair.headUnit, MessageId.WIFI_INFO_RESPONSE, credentials())

            val result = withTimeout(10_000) { phone.await() }
            assertEquals("MyCar-AA", result.ssid)
            assertEquals("hunter2hunter2", result.passphrase)
            assertEquals(
                null, result.endpoint,
                "no endpoint was offered; the caller resolves one after joining",
            )
        }
    }

    @Test
    fun `a head unit that ignores the first request is asked again`() = runBlocking {
        // The stall the idle-based prodder exists for. A prodder that disarmed
        // on the first message would sit here forever, having just answered a
        // version request, with the car equally patiently waiting for us.
        LoopbackTransport.pair().use { pair ->
            val phone = async(Dispatchers.IO) {
                WirelessHandshake(pair.phone).perform(silenceProbeMillis = 200)
            }

            pair.headUnit.write(
                RfcommMessage(MessageId.WIFI_VERSION_REQUEST.number, malibuVersionRequest).encode()
            )
            assertEquals(
                MessageId.WIFI_VERSION_RESPONSE.number,
                withTimeout(10_000) { RfcommMessage.read(pair.headUnit) }.messageId,
            )
            // Drop the phone's first request on the floor and go quiet.
            assertEquals(
                MessageId.WIFI_INFO_REQUEST.number,
                withTimeout(10_000) { RfcommMessage.read(pair.headUnit) }.messageId,
            )

            val probe = withTimeout(10_000) { RfcommMessage.read(pair.headUnit) }
            assertEquals(
                MessageId.WIFI_INFO_REQUEST.number, probe.messageId,
                "an ignored request must be retried, not waited out",
            )
            send(pair.headUnit, MessageId.WIFI_INFO_RESPONSE, credentials())
            assertEquals("MyCar-AA", withTimeout(10_000) { phone.await() }.ssid)
        }
    }

    @Test
    fun `a late endpoint still wins when the head unit sends one first`() = runBlocking {
        // openauto's head unit sends WifiStartRequest before the phone has said
        // anything (AndroidBluetoothServer.cpp L79-L86), so the endpoint is
        // already known by the time credentials arrive and must be kept.
        LoopbackTransport.pair().use { pair ->
            val phone = async(Dispatchers.IO) { WirelessHandshake(pair.phone).perform() }

            send(
                pair.headUnit, MessageId.WIFI_START_REQUEST,
                WifiStartRequest.newBuilder().setIpAddress("192.168.43.1").setPort(5288).build(),
            )
            RfcommMessage.read(pair.headUnit) // the info request
            send(pair.headUnit, MessageId.WIFI_INFO_RESPONSE, credentials())

            val result = withTimeout(10_000) { phone.await() }
            assertEquals("192.168.43.1", result.endpoint?.ipAddress)
            assertEquals(5288, result.endpoint?.port)
        }
    }

    @Test
    fun `the phone acknowledges the credentials the way a real phone does`() = runBlocking {
        // aa-proxy-rs drives genuine Android Auto phones and blocks reading
        // WifiStartResponse then WifiConnectStatus after sending the credentials
        // (src/bluetooth.rs send_params, L6207-L6245). Sending neither left a
        // real Chevrolet waiting, and it refused the AAP connection because it
        // had never been told to expect one.
        LoopbackTransport.pair().use { pair ->
            val handshake = WirelessHandshake(pair.phone)
            val phone = async(Dispatchers.IO) { handshake.perform() }

            pair.headUnit.write(
                RfcommMessage(MessageId.WIFI_VERSION_REQUEST.number, malibuVersionRequest).encode()
            )
            RfcommMessage.read(pair.headUnit) // version response
            RfcommMessage.read(pair.headUnit) // info request
            send(pair.headUnit, MessageId.WIFI_INFO_RESPONSE, credentials())

            val ack = withTimeout(10_000) { RfcommMessage.read(pair.headUnit) }
            assertEquals(
                MessageId.WIFI_START_RESPONSE.number, ack.messageId,
                "the credentials must be acknowledged with WifiStartResponse",
            )
            val started = WifiStartResponse.parseFrom(ack.payload)
            assertEquals(
                Status.STATUS_SUCCESS, started.status,
                "status is field 3 here, and a non-success value refuses the session",
            )

            withTimeout(10_000) { phone.await() }

            // Then, only once the phone is actually on the network.
            handshake.reportWifiConnected()
            val joined = withTimeout(10_000) { RfcommMessage.read(pair.headUnit) }
            assertEquals(MessageId.WIFI_CONNECTION_STATUS.number, joined.messageId)
            assertEquals(
                Status.STATUS_SUCCESS,
                WifiConnectionStatus.parseFrom(joined.payload).status,
                "status is field 1 in this message, not field 3",
            )
        }
    }

    @Test
    fun `reporting the wifi join never fails the session`() = runBlocking {
        // The endpoint is discoverable without this message, so a head unit that
        // does not want it must still get a session rather than an exception
        // from a write to a link it already closed.
        LoopbackTransport.pair().use { pair ->
            val handshake = WirelessHandshake(pair.phone)
            pair.phone.close()
            handshake.reportWifiConnected()
        }
    }

    @Test
    fun `the captured Chevrolet exchange happens in exactly this order`() = runBlocking {
        // Transcribed from a real capture, and asserted as a sequence rather
        // than as individual messages: every bug in this handshake so far has
        // been an ordering or a duplicate, not a malformed body.
        LoopbackTransport.pair().use { pair ->
            val handshake = WirelessHandshake(pair.phone)
            val phone = async(Dispatchers.IO) { handshake.perform() }
            val seen = mutableListOf<Int>()

            pair.headUnit.write(
                RfcommMessage(MessageId.WIFI_VERSION_REQUEST.number, malibuVersionRequest).encode()
            )
            seen += withTimeout(10_000) { RfcommMessage.read(pair.headUnit) }.messageId // version resp
            seen += withTimeout(10_000) { RfcommMessage.read(pair.headUnit) }.messageId // info req

            // The car answers the info request with the endpoint first, then the
            // credentials -- which is where the phone used to ask a second time.
            send(
                pair.headUnit, MessageId.WIFI_START_REQUEST,
                WifiStartRequest.newBuilder().setIpAddress("192.168.5.1").setPort(7001).build(),
            )
            send(pair.headUnit, MessageId.WIFI_INFO_RESPONSE, credentials())
            seen += withTimeout(10_000) { RfcommMessage.read(pair.headUnit) }.messageId // start resp

            val result = withTimeout(10_000) { phone.await() }
            handshake.reportWifiConnected()
            seen += withTimeout(10_000) { RfcommMessage.read(pair.headUnit) }.messageId // connected

            assertEquals(
                listOf(
                    MessageId.WIFI_VERSION_RESPONSE.number,
                    MessageId.WIFI_INFO_REQUEST.number,
                    MessageId.WIFI_START_RESPONSE.number,
                    MessageId.WIFI_CONNECTION_STATUS.number,
                ),
                seen,
                "the phone must send exactly these, once each, in this order",
            )
            assertEquals("192.168.5.1", result.endpoint?.ipAddress)
            assertEquals(7001, result.endpoint?.port, "the car's port, not the reference default")
        }
    }

    @Test
    fun `the service UUID matches all three references`() {
        assertEquals(
            "4de17a00-52cb-11e6-bdf4-0800200c9a66",
            WirelessHandshake.AA_WIRELESS_SERVICE_UUID,
        )
    }
}

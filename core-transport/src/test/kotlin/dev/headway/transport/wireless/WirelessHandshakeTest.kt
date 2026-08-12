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
import aap_protobuf.aaw.WifiStartRequestOuterClass.WifiStartRequest
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
            assertEquals("192.168.43.1", result.ipAddress)
            assertEquals(5288, result.port)
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

            assertEquals("10.0.0.1", withTimeout(10_000) { phone.await() }.ipAddress)
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

            assertEquals("172.16.0.1", withTimeout(10_000) { phone.await() }.ipAddress)
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
            ipAddress = "192.168.43.1",
            port = 5288,
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

            assertEquals("192.168.1.1", withTimeout(10_000) { phone.await() }.ipAddress)
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
            assertEquals("10.1.1.1", withTimeout(10_000) { phone.await() }.ipAddress)
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

    @Test
    fun `the service UUID matches all three references`() {
        assertEquals(
            "4de17a00-52cb-11e6-bdf4-0800200c9a66",
            WirelessHandshake.AA_WIRELESS_SERVICE_UUID,
        )
    }
}

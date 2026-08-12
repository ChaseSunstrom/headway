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

package dev.headway.app.link

import aap_protobuf.service.wifiprojection.message.AccessPointTypeOuterClass.AccessPointType
import aap_protobuf.service.wifiprojection.message.WifiSecurityModeOuterClass.WifiSecurityMode
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.NetworkCapabilities
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.headway.transport.wireless.CarNetworkCredentials
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs [CarWifiNetwork] against the real platform Wi-Fi and connectivity APIs.
 *
 * ## What this can and cannot prove
 *
 * A headless AOSP emulator has no way to associate with a real access point, and
 * no car exists to run one (BLOCKERS.md B-001), so "the phone joins the car" is
 * not assertable anywhere. What *is* assertable, and is asserted here, is
 * everything up to the radio: that the credentials a head unit sends are turned
 * into the SSID, BSSID and passphrase the platform will match on, that the
 * request does not demand internet the car does not have, that malformed
 * credentials degrade instead of throwing, and that the request is released.
 *
 * Those are the parts that would silently be wrong. The association itself
 * either works or visibly does not.
 *
 * [WifiNetworkSpecifier] exposes no getters — it can be built, compared and
 * printed, but not read — which is why the SSID/BSSID/passphrase assertions run
 * against [CarWifiNetwork.Spec] and the specifier itself is checked by
 * construction and equality.
 */
@RunWith(AndroidJUnit4::class)
class CarWifiNetworkTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * What a real head unit sends, with the values the references actually put
     * on the wire: security mode wire value 8 and a dynamic AP
     * (`docs/protocol-notes.md` §3.1 STEP 8).
     */
    private fun credentials(
        ssid: String = "AAWirelessDongle",
        passphrase: String = "password",
        bssid: String = "02:11:22:33:44:55",
        securityMode: WifiSecurityMode = WifiSecurityMode.WPA2_ENTERPRISE,
    ) = CarNetworkCredentials(
        ssid = ssid,
        passphrase = passphrase,
        bssid = bssid,
        securityMode = securityMode,
        accessPointType = AccessPointType.DYNAMIC,
        ipAddress = "10.0.0.1",
        port = 5288,
    )

    @Test
    fun specCarriesTheCredentialsTheHeadUnitSent() {
        val spec = CarWifiNetwork.specFor(credentials())

        assertEquals("AAWirelessDongle", spec.ssid)
        assertEquals("02:11:22:33:44:55", spec.bssid)
        assertEquals("password", spec.passphrase)
        assertFalse("head unit APs are not hidden unless the quirk file says so", spec.hiddenSsid)
    }

    @Test
    fun passphraseNotTheSecurityEnumDecidesTheSecurityType() {
        // Wire value 8 is WPA2_PERSONAL in the enum aa-proxy-rs compiles against
        // and WPA2_ENTERPRISE in the one Headway parses with, while the actual
        // access point is WPA2-PSK. A spec built from the "enterprise" symbol
        // must still carry the pre-shared key, or every real car fails to join.
        val enterprise = CarWifiNetwork.specFor(
            credentials(securityMode = WifiSecurityMode.WPA2_ENTERPRISE)
        )
        val personal = CarWifiNetwork.specFor(
            credentials(securityMode = WifiSecurityMode.WPA2_PERSONAL)
        )

        assertEquals("password", enterprise.passphrase)
        assertEquals(enterprise, personal)
    }

    @Test
    fun anEmptyPassphraseMeansAnOpenNetwork() {
        val spec = CarWifiNetwork.specFor(credentials(passphrase = ""))
        assertNull(spec.passphrase)
        assertNotNull("an open network must still be requestable", CarWifiNetwork.buildSpecifier(spec))
    }

    @Test
    fun unusableBssidsDegradeToAnSsidOnlyMatch() {
        // aa-proxy-rs warns that head units send an empty bssid; the platform
        // rejects the zero and broadcast addresses outright. None of these may
        // fail the connection attempt.
        for (bad in listOf("", "   ", "not-a-mac", "00:00:00:00:00:00", "ff:ff:ff:ff:ff:ff")) {
            val spec = CarWifiNetwork.specFor(credentials(bssid = bad))
            assertNull("bssid '$bad' should have been dropped", spec.bssid)
            assertFalse(CarWifiNetwork.isUsableBssid(bad))
            assertNotNull(
                "a dropped bssid must still produce a usable specifier",
                CarWifiNetwork.buildSpecifier(spec),
            )
        }
    }

    @Test
    fun aUsableBssidParsesToTheSameMacThePlatformWillMatch() {
        assertTrue(CarWifiNetwork.isUsableBssid("02:11:22:33:44:55"))
        assertEquals(
            MacAddress.fromString("02:11:22:33:44:55"),
            CarWifiNetwork.parseBssid("  02:11:22:33:44:55  "),
        )
    }

    @Test
    fun specifiersFromEqualCredentialsAreEqual() {
        // WifiNetworkSpecifier implements equals over its SSID, BSSID and
        // credential fields, so this is the only inspection the platform allows:
        // it fails if any of them were built differently.
        val a = CarWifiNetwork.buildSpecifier(CarWifiNetwork.specFor(credentials()))
        val b = CarWifiNetwork.buildSpecifier(CarWifiNetwork.specFor(credentials()))
        assertEquals(a, b)

        val other = CarWifiNetwork.buildSpecifier(
            CarWifiNetwork.specFor(credentials(ssid = "SomeOtherCar"))
        )
        assertFalse("a different SSID must not compare equal", a == other)
    }

    @Test
    fun theRequestIsWifiOnlyAndDoesNotDemandInternet() {
        val spec = CarWifiNetwork.specFor(credentials())
        val request = CarWifiNetwork.buildRequest(spec)

        assertTrue(request.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
        assertFalse(
            "the car AP has no internet; demanding it means the request is never satisfied",
            request.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        )
        assertEquals(CarWifiNetwork.buildSpecifier(spec), request.networkSpecifier)
    }

    @Test
    fun connectivityManagerIsReachable() {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        assertNotNull("ConnectivityManager is required to request the car network", manager)
        // Not asserting anything about the active network: an emulator may have
        // one, a phone in a garage may have none, and neither says anything
        // about whether the car link will work.
        manager!!.activeNetwork
    }

    @Test
    fun closeIsIdempotentAndLeavesNoRequestRegistered() {
        val wifi = CarWifiNetwork(context)
        assertFalse(wifi.isRequestActive)

        wifi.close()
        wifi.close()

        assertFalse(wifi.isRequestActive)
        assertNull(wifi.network)
    }

    @Test
    fun joiningAfterCloseFails() {
        val wifi = CarWifiNetwork(context)
        wifi.close()
        try {
            runBlocking { wifi.join(credentials(), timeoutMillis = 1_000) }
            fail("join after close should have thrown")
        } catch (expected: IllegalStateException) {
            // A closed link must not silently re-register a network request.
        }
    }

    @Test
    fun aRequestForAnAbsentCarFailsAndReleasesTheRequest() {
        // Everything below this point touches the real Wi-Fi stack. On a
        // headless AOSP image there is no car access point to join, and the test
        // app holds neither CHANGE_NETWORK_STATE (which the app manifest does not
        // declare) nor the NEARBY_WIFI_DEVICES runtime grant, so the request
        // would be denied before it reached the radio. Skipped rather than
        // failed: the assertion below is about the callback lifecycle, and it is
        // only meaningful when the platform actually runs the request.
        assumeTrue(
            "no Wi-Fi hardware on this device",
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI),
        )
        assumeTrue(
            "CHANGE_NETWORK_STATE not held; ConnectivityManager.requestNetwork would be denied",
            granted(Manifest.permission.CHANGE_NETWORK_STATE),
        )
        assumeTrue(
            "NEARBY_WIFI_DEVICES not granted to the test app",
            granted(Manifest.permission.NEARBY_WIFI_DEVICES),
        )

        val wifi = CarWifiNetwork(context)
        try {
            runBlocking {
                withTimeout(30_000) {
                    try {
                        // No such network exists, so the platform answers
                        // onUnavailable when the timeout expires.
                        wifi.join(credentials(ssid = "HeadwayNoSuchCar"), timeoutMillis = 5_000)
                        fail("joined a network that does not exist")
                    } catch (expected: CarWifiException) {
                        // The failure path is the point of the test.
                    }
                }
            }
            assertFalse("the request must be released on failure", wifi.isRequestActive)
            assertNull(wifi.network)
        } finally {
            wifi.close()
        }
    }

    private fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}

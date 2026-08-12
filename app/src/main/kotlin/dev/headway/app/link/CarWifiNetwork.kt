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

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import dev.headway.transport.wireless.CarNetworkCredentials
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/** Raised when the phone cannot join, or stay on, the car's access point. */
class CarWifiException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * Joins the head unit's access point and hands back the [Network] object.
 *
 * ## Why the Network object is the whole point
 *
 * The car's AP has no internet. Android validates every network it joins and
 * routes app traffic to whichever network *does* have internet, so after a
 * successful association the phone's default route is still mobile data and
 * `Socket("10.0.0.1", 5288)` connects to nothing. The only supported
 * unprivileged fix is to create the session's sockets from
 * [Network.getSocketFactory], which binds each socket's file descriptor to this
 * network regardless of the default route. CLAUDE.md calls this out as the
 * critical Android detail of the transport, and it is the single most likely
 * thing to be silently wrong in a wireless-AA implementation, because
 * everything up to the TCP connect succeeds without it.
 *
 * [ConnectivityManager.bindProcessToNetwork] would also work and is what most
 * sample code reaches for. Headway does not use it: it is process-global, so it
 * would drag every unrelated socket in the app — and any library that opens one
 * — onto a dead-end network for as long as the session lasts. Per-socket
 * binding is scoped to exactly the sockets that want it.
 *
 * ## Lifecycle
 *
 * The request lives as long as the registered callback. Unregistering it is what
 * releases the phone from the car's AP, so [close] must run on every path,
 * including the failure paths — a leaked callback keeps the phone associated to
 * a network with no internet after Headway has given up on it.
 */
class CarWifiNetwork(
    context: Context,
    private val onStep: (String) -> Unit = {},
) : AutoCloseable {

    // The application context, not the passed one: this outlives whatever
    // started it, and holding an activity here would leak it for the session.
    private val appContext: Context = context.applicationContext

    private val connectivityManager: ConnectivityManager? =
        appContext.getSystemService(ConnectivityManager::class.java)

    private val closed = AtomicBoolean(false)

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var available = CompletableDeferred<Network>()
    private var lost = CompletableDeferred<Unit>()

    /** The joined network, or null before [join] succeeds or after [close]. */
    @Volatile
    var network: Network? = null
        private set

    /** True while a network request is registered. Drives the leak assertions. */
    val isRequestActive: Boolean get() = callback != null

    /**
     * Requests the car's AP and suspends until the platform reports it
     * available.
     *
     * @param timeoutMillis handed to the platform, which answers with
     *   `onUnavailable` rather than leaving the request pending forever. This is
     *   the only reliable way to bound the wait: an association that never
     *   completes produces no callback at all otherwise.
     *
     * @throws CarWifiException on timeout, on a rejected request, or when the
     *   caller lacks the permissions the request needs.
     */
    suspend fun join(
        credentials: CarNetworkCredentials,
        timeoutMillis: Int = 30_000,
        hiddenSsid: Boolean = false,
    ): Network {
        check(!closed.get()) { "CarWifiNetwork already closed" }
        check(callback == null) { "a network request is already active" }
        val manager = connectivityManager
            ?: throw CarWifiException("no ConnectivityManager on this device")

        val spec = specFor(credentials, hiddenSsid)
        val request = buildRequest(spec)

        available = CompletableDeferred()
        lost = CompletableDeferred()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(joined: Network) {
                network = joined
                onStep("joined ${spec.ssid}")
                available.complete(joined)
            }

            override fun onUnavailable() {
                // Reached on timeout, and immediately when the platform refuses
                // the request -- notably when Headway is not the foreground app
                // or foreground service, which WifiNetworkSpecifier requires.
                available.completeExceptionally(
                    CarWifiException("could not join ${spec.ssid} within ${timeoutMillis} ms")
                )
            }

            override fun onLost(gone: Network) {
                if (gone == network) {
                    network = null
                    onStep("lost ${spec.ssid}")
                    lost.complete(Unit)
                }
                // Losing the network before it was ever handed out still has to
                // wake the caller, or join() waits out the full timeout for a
                // network the platform has already given up on.
                available.completeExceptionally(
                    CarWifiException("${spec.ssid} went away during association")
                )
            }
        }

        callback = cb
        try {
            manager.requestNetwork(request, cb, timeoutMillis)
        } catch (e: SecurityException) {
            callback = null
            // Name the one that is actually missing. The old message listed both
            // candidates, and the two have completely different fixes: one is
            // granted in Settings, the other can only be declared in the
            // manifest and does not appear in Settings at all. Guessing between
            // them sent a real user to grant a permission they already had.
            throw CarWifiException("requestNetwork denied; ${missingJoinPermission()}", e)
        } catch (e: RuntimeException) {
            callback = null
            throw CarWifiException("requestNetwork rejected: ${e.message}", e)
        }

        onStep("requesting ${spec.ssid}${if (spec.bssid != null) " (${spec.bssid})" else ""}")
        return try {
            available.await()
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    /**
     * Completes when the joined network goes away.
     *
     * The session layer races this against its own work: without it, a phone
     * that drives out of range keeps a socket that will not fail until a write
     * times out, and reconnection waits on that instead of starting.
     */
    suspend fun awaitLost() {
        lost.await()
    }

    /**
     * Which permission `requestNetwork` was actually missing, in words that say
     * what to do about it.
     *
     * `CHANGE_NETWORK_STATE` is a normal permission: it is granted at install if
     * the manifest declares it and is ungrantable otherwise, so its absence is a
     * build defect rather than anything the user can act on.
     * `NEARBY_WIFI_DEVICES` is a runtime permission and is the user's to grant.
     */
    private fun missingJoinPermission(): String {
        val missing = JOIN_PERMISSIONS.filter {
            appContext.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        return when {
            missing.isEmpty() ->
                "no permission is missing, so the platform refused for another reason — " +
                    "most likely Headway was not in the foreground and had no running " +
                    "foreground service, which WifiNetworkSpecifier requires"
            android.Manifest.permission.CHANGE_NETWORK_STATE in missing ->
                "CHANGE_NETWORK_STATE is not held. It is a normal permission granted at " +
                    "install, not one that appears in Settings, so this is a packaging " +
                    "bug and not something to grant"
            else -> "${missing.joinToString()} must be granted in Settings"
        }
    }

    /**
     * The head unit's own address on the access point it is hosting.
     *
     * Needed because a real 2021 Chevrolet Infotainment 3 unit never sends
     * `WifiStartRequest`: it answers `WifiInfoRequest` with SSID, passphrase and
     * BSSID, and then says nothing more, so Bluetooth yields no IP or port. The
     * unit is the only other host on a network it is hosting, so its address is
     * discoverable rather than guessable.
     *
     * The DHCP server address is preferred over the default-route gateway. On an
     * access point hosted by the head unit they are the same machine, but a car
     * AP with no internet may publish no default route at all, in which case the
     * gateway is absent and the DHCP server is not.
     *
     * Returns null if the platform reports neither, which is the caller's cue to
     * fail with something a person can act on rather than connect to a guess.
     */
    fun headUnitAddress(): String? {
        val joined = network ?: return null
        val properties = connectivityManager?.getLinkProperties(joined) ?: return null

        properties.dhcpServerAddress?.hostAddress?.let {
            onStep("head unit is the DHCP server at $it")
            return it
        }
        val gateway = properties.routes
            .firstOrNull { it.isDefaultRoute && it.hasGateway() }
            ?.gateway?.hostAddress
        if (gateway != null) onStep("head unit is the default gateway at $gateway")
        else onStep("the car network published neither a DHCP server nor a gateway")
        return gateway
    }

    /**
     * Opens a TCP socket bound to the car's network.
     *
     * Name resolution goes through [Network.getByName] rather than the default
     * resolver for the same reason the socket is bound: the car's DNS (if any)
     * is only reachable on that network. Head units advertise a literal address,
     * so this normally resolves without a query at all.
     */
    suspend fun openSocket(host: String, port: Int, connectTimeoutMillis: Int = 10_000): Socket {
        val joined = network ?: throw CarWifiException("not joined to the car's network")
        return withContext(Dispatchers.IO) {
            val address = joined.getByName(host)
                ?: throw CarWifiException("could not resolve $host on the car's network")
            // createSocket() with no arguments returns an unconnected socket
            // already bound to this network, which is what lets us apply our own
            // connect timeout instead of the factory's.
            val socket = joined.socketFactory.createSocket()
            try {
                socket.connect(InetSocketAddress(address, port), connectTimeoutMillis)
            } catch (e: Throwable) {
                runCatching { socket.close() }
                throw e
            }
            socket
        }
    }

    /** Releases the request. Idempotent; safe from any thread and any path. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val cb = callback
        callback = null
        network = null
        if (cb != null) {
            // Throws IllegalArgumentException if the callback was never
            // registered, which can happen when requestNetwork itself failed.
            runCatching { connectivityManager?.unregisterNetworkCallback(cb) }
            onStep("released the car network request")
        }
        available.completeExceptionally(CarWifiException("car network released"))
        lost.complete(Unit)
    }

    /**
     * The parts of [CarNetworkCredentials] that matter to Wi-Fi association,
     * normalised.
     *
     * Extracted as plain data because [WifiNetworkSpecifier] exposes no getters:
     * it can be built and compared but not inspected, so this is the only level
     * at which "did we configure the right SSID and passphrase?" can be
     * asserted on a device (see `CarWifiNetworkTest`).
     */
    data class Spec(
        val ssid: String,
        /** Null when the head unit sent no usable BSSID; the match is then SSID-only. */
        val bssid: String?,
        /** Null for an open network. */
        val passphrase: String?,
        val hiddenSsid: Boolean,
    )

    companion object {

        /**
         * Everything `ConnectivityManager.requestNetwork` needs to join the car.
         *
         * Asserted against the merged manifest in `CarWifiNetworkTest`, because
         * a missing entry here is invisible until a real car is in front of you:
         * the build succeeds, the Bluetooth handshake succeeds, and only the
         * join throws.
         */
        val JOIN_PERMISSIONS: List<String> = listOf(
            android.Manifest.permission.CHANGE_NETWORK_STATE,
            android.Manifest.permission.NEARBY_WIFI_DEVICES,
        )

        /**
         * Derives the association parameters from what the head unit sent.
         *
         * ## Why `security_mode` is ignored
         *
         * It is not trustworthy. The wire value 8 is `WPA2_PERSONAL` in the
         * enum aa-proxy-rs and aawgd compile against
         * (`aa-proxy-rs/src/protos/WifiInfoResponse.proto` L9-L20) and
         * `WPA2_ENTERPRISE` in the enum aasdk's `aaw/WifiInfoResponse.proto`
         * imports (`aap_protobuf/service/wifiprojection/message/WifiSecurityMode.proto`
         * L7-L18) — the two numberings disagree, and openauto sends the symbol
         * `WPA2_ENTERPRISE` while running a WPA2-PSK access point, with an
         * in-source comment acknowledging the mismatch itself
         * (`openauto/src/btservice/AndroidBluetoothServer.cpp` L165-L176).
         * Headway parses with the aasdk enum, so a real head unit's WPA2-PSK
         * network decodes here as "enterprise". Both details are recorded in
         * `docs/protocol-notes.md` §3.2.
         *
         * The presence of a passphrase is unambiguous where the enum is not, so
         * that is what selects the security type: a PSK means WPA2-PSK, no PSK
         * means open. Every head unit AP in the references is WPA2-PSK/CCMP
         * (`WirelessAndroidAutoDongle/.../hostapd.conf.in` L1-L16). If a unit
         * ever ships WPA3-SAE this is the line to change — `setWpa3Passphrase`
         * is the WPA3 counterpart and no reference uses it.
         */
        fun specFor(credentials: CarNetworkCredentials, hiddenSsid: Boolean = false): Spec = Spec(
            ssid = credentials.ssid,
            bssid = credentials.bssid.takeIf { isUsableBssid(it) },
            passphrase = credentials.passphrase.takeIf { it.isNotEmpty() },
            hiddenSsid = hiddenSsid,
        )

        /**
         * True if [raw] is a MAC address the platform will accept as a match
         * target.
         *
         * A head unit that sends an empty or zeroed BSSID is not hypothetical —
         * aa-proxy-rs warns about exactly that case
         * (`aa-proxy-rs/src/bluetooth.rs` L7175-L7193). Passing it to
         * `setBssid` throws, so the BSSID is dropped and the match falls back to
         * the SSID alone rather than failing the whole connection.
         */
        fun isUsableBssid(raw: String): Boolean = parseBssid(raw) != null

        /** Null rather than throwing, for the same reason as [isUsableBssid]. */
        fun parseBssid(raw: String): MacAddress? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val parsed = runCatching { MacAddress.fromString(trimmed) }.getOrNull() ?: return null
            // The platform rejects these two in setBssid; treating them as
            // "no BSSID given" keeps that rejection out of the connect path.
            if (parsed == MacAddress.fromString("00:00:00:00:00:00")) return null
            if (parsed == MacAddress.fromString("ff:ff:ff:ff:ff:ff")) return null
            return parsed
        }

        fun buildSpecifier(spec: Spec): WifiNetworkSpecifier =
            WifiNetworkSpecifier.Builder()
                .setSsid(spec.ssid)
                .apply {
                    parseBssid(spec.bssid.orEmpty())?.let { setBssid(it) }
                    spec.passphrase?.let { setWpa2Passphrase(it) }
                    if (spec.hiddenSsid) setIsHiddenSsid(true)
                }
                .build()

        /**
         * The request for the car's AP.
         *
         * `NET_CAPABILITY_INTERNET` is removed deliberately, and not as a
         * formality: [NetworkRequest.Builder] adds it by default, and a request
         * that demands internet will never be satisfied by an access point that
         * has none — which is every head unit. The phone would associate,
         * validation would fail, and the request would time out.
         */
        fun buildRequest(spec: Spec): NetworkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(buildSpecifier(spec))
            .build()
    }
}

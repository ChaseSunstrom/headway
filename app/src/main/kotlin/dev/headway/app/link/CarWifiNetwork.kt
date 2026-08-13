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

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import dev.headway.transport.wireless.CarEndpoint
import dev.headway.transport.wireless.CarNetworkCredentials
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Raised when the phone cannot join, or stay on, the car's access point.
 *
 * @param platformReason the `STATUS_LOCAL_ONLY_CONNECTION_FAILURE_*` name when
 *   Android gave one. Carried separately from the message because the supervisor
 *   has to make a decision on it — some of these are worth retrying immediately
 *   and at least one is made strictly worse by retrying.
 */
class CarWifiException(
    message: String,
    cause: Throwable? = null,
    val platformReason: String? = null,
) : RuntimeException(message, cause)

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

    private val wifiManager: WifiManager? =
        appContext.getSystemService(WifiManager::class.java)

    private val closed = AtomicBoolean(false)

    /**
     * Guards the registration lifecycle: [callback], [wifiWatch],
     * [adoptedWatch] and [failureListener].
     *
     * These are written by the supervisor coroutine and read by [close], which
     * runs on whatever thread destroyed the service — a different one. Without
     * the lock, a `close()` landing between `callback = cb` and
     * `requestNetwork` sees no callback to unregister, and the request that is
     * registered a microsecond later is never released at all. The phone then
     * keeps trying to join, or stays joined to, an access point belonging to an
     * app that has been destroyed.
     */
    private val registrationLock = Any()

    private var callback: ConnectivityManager.NetworkCallback? = null

    /**
     * A plain listen on every Wi-Fi network, registered only while a join is in
     * flight.
     *
     * It requests nothing and joins nothing — `registerNetworkCallback` with no
     * specifier is a passive listen and needs only `ACCESS_NETWORK_STATE`. It
     * exists because the specifier request cannot tell these two apart, and they
     * have opposite fixes:
     *
     * - the phone never associated with anything (the car's AP was not on the
     *   air, or the approval sheet was never tapped), versus
     * - the phone *did* associate but the request was never satisfied, which
     *   would be a fault in how Headway shaped the request.
     *
     * A real capture spent 75 s in the first state and the second, and the log
     * could not say which. Now it can.
     */
    private var wifiWatch: ConnectivityManager.NetworkCallback? = null

    private var available = CompletableDeferred<Network>()
    private var lost = CompletableDeferred<Unit>()

    /** The joined network, or null before [join] succeeds or after [close]. */
    @Volatile
    var network: Network? = null
        private set

    /** Which SSID [network] belongs to, so a second car cannot inherit it. */
    @Volatile
    private var joinedSsid: String? = null

    /**
     * True when [network] came from [adoptExistingCarNetwork] rather than from a
     * request Headway made.
     *
     * It decides one thing, and getting it wrong would be rude: Headway must not
     * disconnect the phone from a network the *user* joined. There is no
     * callback to unregister for an adopted network, and dropping the reference
     * is the whole of the cleanup.
     */
    @Volatile
    private var adopted: Boolean = false

    /**
     * True when the current network was adopted rather than requested.
     *
     * The caller needs this because an adoption is a strong guess, not a proof:
     * it is confirmed only when something on the network answers as the head
     * unit. See `HeadwayService`'s handling of a failed AAP connect.
     */
    val isAdopted: Boolean get() = adopted && network != null

    /** Reports the loss of an adopted network, which has no request behind it. */
    private var adoptedWatch: ConnectivityManager.NetworkCallback? = null

    /**
     * The registered [WifiManager.LocalOnlyConnectionFailureListener].
     *
     * Held as `Any?` rather than the interface type: the interface is API 34 and
     * `minSdk` is 33, and a field whose declared type does not exist on the
     * running device is the kind of thing that fails at class-verification time
     * rather than at the call.
     */
    private var failureListener: Any? = null

    /** The platform's own reason for the last failed join, when it gave one. */
    @Volatile
    private var platformFailure: String? = null

    /**
     * How many Wi-Fi scans the platform has completed since the join started.
     *
     * The single most discriminating number in the whole join, and it was
     * missing. `WifiNetworkFactory` scans every 10 s while a request is pending
     * and reports nothing to the app either way, so "no scans at all" and "many
     * scans and no match" — a phone that cannot use the band versus a car that
     * is not on the air — were the same silence. `registerScanResultsCallback`
     * needs only `ACCESS_WIFI_STATE`; it delivers no scan *results* without
     * location permission, which is fine, because it is the count that matters.
     */
    @Volatile
    private var scansSeen: Int = 0

    private var scanCallback: WifiManager.ScanResultsCallback? = null

    /** True while a network request is registered. Drives the leak assertions. */
    val isRequestActive: Boolean get() = callback != null

    /**
     * Requests the car's AP and suspends until the platform reports it
     * available.
     *
     * Calling this again while already joined to the same SSID returns the
     * existing network rather than re-requesting it — see [DEFAULT_JOIN_TIMEOUT_MILLIS]
     * and `HeadwayService.carWifi` for why that matters more than it looks.
     *
     * @param timeoutMillis handed to the platform, which answers with
     *   `onUnavailable` rather than leaving the request pending forever. This is
     *   the only reliable way to bound the wait: an association that never
     *   completes produces no callback at all otherwise. It must stay longer
     *   than AOSP's own retry budget; [DEFAULT_JOIN_TIMEOUT_MILLIS] explains.
     *
     * @throws CarWifiException on timeout, on a rejected request, when Wi-Fi is
     *   switched off, or when the caller lacks the permissions the request needs.
     */
    suspend fun join(
        credentials: CarNetworkCredentials,
        timeoutMillis: Int = DEFAULT_JOIN_TIMEOUT_MILLIS,
        hiddenSsid: Boolean = false,
        pinBssid: Boolean = false,
    ): Network = coroutineScope {
        check(!closed.get()) { "CarWifiNetwork already closed" }
        val manager = connectivityManager
            ?: throw CarWifiException("no ConnectivityManager on this device")

        // Already on the car's network from an earlier attempt. Re-requesting
        // would tear the association down and put the approval sheet back in
        // front of the user for a network the phone is *currently joined to*.
        //
        // Guarded on the SSID as well as on there being a network: a phone
        // paired with two cars must never reuse the first car's association for
        // the second, which would send the AAP session to the wrong vehicle.
        network?.let { joined ->
            if (joinedSsid == credentials.ssid) {
                // A completed `lost` here is not stale bookkeeping, it is a
                // tripwire: the session watchdog awaits it, so handing back a
                // network alongside a deferred that some earlier disconnection
                // already completed tears the new session down the instant it
                // comes up. Reachable because onLost and a later onAvailable can
                // both fire on one still-registered callback.
                if (lost.isCompleted) lost = CompletableDeferred()
                onStep("already joined ${credentials.ssid}; reusing the existing network")
                return@coroutineScope joined
            }
            onStep("joined ${joinedSsid} but this car wants ${credentials.ssid}; re-requesting")
            releaseRequest()
        }

        // A request left over from an association that has since gone away.
        // Leaving it registered would fail the next requestNetwork, and it is
        // doing nothing useful: the network it was satisfied by is gone.
        if (callback != null) {
            onStep("clearing the previous car network request before re-requesting")
            releaseRequest()
        }

        // The platform's own answer to a request made with Wi-Fi off is to
        // reject it outright -- AOSP WifiNetworkFactory.needNetworkFor:
        // "Request with wifi network specifier when wifi is off. Rejecting" --
        // which arrives as a bare onUnavailable indistinguishable from a
        // timeout. Saying it here is the difference between a user who turns
        // Wi-Fi on and a user who is told to tap a sheet that will never appear.
        requireWifiEnabled()

        val spec = specFor(credentials, hiddenSsid, pinBssid)
        val maxChannels = runCatching {
            wifiManager?.maxNumberOfChannelsPerNetworkSpecifierRequest ?: 0
        }.getOrDefault(0)
        if (spec.preferredFrequenciesMhz.isNotEmpty()) {
            onStep(
                "the head unit advertised ${spec.preferredFrequenciesMhz} MHz; asking " +
                    "Android to scan there first (it accepts $maxChannels channel hints)"
            )
        }
        val request = buildRequest(spec, maxChannels)

        available = CompletableDeferred()
        lost = CompletableDeferred()

        // Stamped just before requestNetwork, and read by onUnavailable, which
        // cannot fire before then. Declared here only because the callback
        // closes over it.
        var requestedAt = System.nanoTime()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(joined: Network) {
                network = joined
                joinedSsid = spec.ssid
                onStep("joined ${spec.ssid}")
                available.complete(joined)
            }

            override fun onCapabilitiesChanged(
                changed: Network,
                capabilities: NetworkCapabilities,
            ) {
                // Only interesting before the join completes, where it is the
                // one signal that the platform got as far as building a network
                // for us at all.
                if (!available.isCompleted) {
                    onStep("car network capabilities settling: $capabilities")
                }
            }

            override fun onLinkPropertiesChanged(changed: Network, properties: LinkProperties) {
                if (!available.isCompleted) {
                    onStep("car network link properties: $properties")
                }
            }

            override fun onUnavailable() {
                // Elapsed, not the budget. There is no platform timeout on this
                // request any more, so onUnavailable means the platform refused
                // or abandoned it — and an arrival two seconds in tells a very
                // different story from one five minutes in. Reporting the
                // budget made a refusal read as a patient wait that ran out.
                val elapsed = ((System.nanoTime() - requestedAt) / 1_000_000).toInt()
                available.completeExceptionally(
                    CarWifiException(unavailableMessage(spec, elapsed))
                )
            }

            override fun onLost(gone: Network) {
                if (gone == network) {
                    network = null
                    joinedSsid = null
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

        synchronized(registrationLock) {
            // Re-checked under the lock: close() may have run since the check at
            // the top of join(), and registering after that is the leak this
            // lock exists to prevent.
            check(!closed.get()) { "CarWifiNetwork already closed" }
            callback = cb
            startWifiWatch(manager)
            startFailureListener(spec)
            startScanWatch()
        }
        requestedAt = System.nanoTime()
        try {
            // No platform timeout, deliberately. `requestNetwork`'s timeout
            // variant does not just stop waiting — expiring it releases the
            // request, and releasing it mid-association *disconnects* the
            // attempt the platform is making. AOSP's own budget (4 x 30 s) is
            // measured from the moment the human taps Connect, not from here, so
            // no fixed value set at request time can be guaranteed to sit
            // outside it: a user who takes twenty seconds to notice the sheet
            // moves the whole budget twenty seconds later.
            //
            // The give-up decision is driven instead by things that mean
            // something: the platform's own failure verdict via
            // LocalOnlyConnectionFailureListener, and a backstop deadline far
            // outside any legitimate attempt.
            manager.requestNetwork(request, cb)
        } catch (e: SecurityException) {
            releaseRequest()
            // Name the one that is actually missing. The old message listed both
            // candidates, and the two have completely different fixes: one is
            // granted in Settings, the other can only be declared in the
            // manifest and does not appear in Settings at all. Guessing between
            // them sent a real user to grant a permission they already had.
            throw CarWifiException("requestNetwork denied; ${missingJoinPermission()}", e)
        } catch (e: RuntimeException) {
            releaseRequest()
            throw CarWifiException("requestNetwork rejected: ${e.message}", e)
        }

        // close() can have run while requestNetwork was in flight. It found no
        // registered callback to unregister, because the registration had not
        // completed yet, so releasing it is this coroutine's job.
        if (closed.get()) {
            releaseRequest()
            throw CarWifiException("car network released while the request was being made")
        }

        onStep(
            "requesting ${spec.ssid}${if (spec.bssid != null) " (${spec.bssid})" else " (any BSSID)"}" +
                ". Android may show a prompt to approve joining it: tap the car on that " +
                "prompt and leave it on screen. Do not switch back to Headway to check — " +
                "covering Android's prompt with this app is what makes it unrecoverable"
        )

        // A join is the one step in the whole bring-up that can take minutes and
        // produce no log line at all: the platform's approval sheet, scan and
        // connection retries are entirely internal. A real capture spent 75
        // seconds in exactly that silence, and there was no way afterwards to
        // tell whether the phone had been trying, waiting for a tap, or idle.
        val heartbeat = launch {
            val startedAt = System.nanoTime()
            while (true) {
                delay(HEARTBEAT_MILLIS)
                val elapsed = (System.nanoTime() - startedAt) / 1_000_000
                onStep("still waiting to join ${spec.ssid} after ${elapsed}ms — ${radioState()}")
                if (elapsed >= timeoutMillis) {
                    // The backstop, not a timeout in the platform's sense. It
                    // exists so a request that produces no callback at all --
                    // which is a state the platform can genuinely reach -- does
                    // not hang the service forever, and it is set far outside
                    // any legitimate attempt so that expiring it never cuts a
                    // real association short.
                    available.completeExceptionally(
                        CarWifiException(unavailableMessage(spec, elapsed.toInt()))
                    )
                    return@launch
                }
            }
        }

        try {
            available.await()
        } catch (e: Throwable) {
            close()
            throw e
        } finally {
            heartbeat.cancel()
            stopWifiWatch()
            stopFailureListener()
            stopScanWatch()
        }
    }

    /**
     * Uses a Wi-Fi network the phone is *already* on, when that network is
     * demonstrably the car's.
     *
     * ## Why this exists
     *
     * `WifiNetworkSpecifier` is the only unprivileged way to *join* a network,
     * and it costs a system approval sheet that a human has to tap. That is
     * survivable once. It is not survivable as the thing standing between the
     * user and every single connection attempt, and a real capture showed
     * exactly that: the sheet appeared, the 75 s expired, and the cycle repeated
     * with no way for the user to get past it.
     *
     * But nothing requires Headway to be the one that joins. If the user
     * connects to the car's Wi-Fi themselves in Settings — which they can always
     * do, and which is the obvious thing to try when an app cannot — the phone
     * is on the car's network already and the whole approval dance is
     * unnecessary. All Headway needs is the [Network] object, and
     * `ACCESS_NETWORK_STATE` is enough to enumerate those.
     *
     * ## Why the match is on the gateway and not the SSID
     *
     * The SSID is not readable. `WifiManager.getConnectionInfo().getSSID()`
     * returns `<unknown ssid>` without location permission, which Headway will
     * not request. But the head unit has just told us its own IP over Bluetooth,
     * and a head unit hosting an access point is the DHCP server and gateway on
     * it. So "a connected Wi-Fi network whose DHCP server is the address the car
     * just named" identifies the car's network exactly, with no permission and
     * no guessing — and, importantly, with no probe traffic sent to a head unit
     * that may only tolerate one connection.
     *
     * Returns null when there is no such network, which is the normal case and
     * not an error: the caller then goes on to [join].
     */
    fun adoptExistingCarNetwork(
        credentials: CarNetworkCredentials,
        expected: CarEndpoint? = credentials.endpoint,
    ): Network? {
        if (closed.get() || network != null) return null
        val manager = connectivityManager ?: return null

        // getAllNetworks() is deprecated in favour of a registered callback, and
        // a callback is the wrong shape here: this is a one-shot question asked
        // before the join, not an ongoing subscription. It still works and needs
        // nothing beyond ACCESS_NETWORK_STATE.
        @Suppress("DEPRECATION")
        val candidates = runCatching { manager.allNetworks.toList() }.getOrDefault(emptyList())

        for (candidate in candidates) {
            val capabilities = manager.getNetworkCapabilities(candidate) ?: continue
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val properties = manager.getLinkProperties(candidate) ?: continue

            val why = when {
                // Strongest signal: the head unit named its own address and a
                // network the phone is on is served by exactly that host.
                expected != null && hostsGateway(properties, expected.ipAddress) ->
                    "its gateway is ${expected.ipAddress}, which is the address this head " +
                        "unit just gave over Bluetooth"

                // No endpoint was announced, which is normal for this vehicle.
                // Fall back to the shape of the network: a head unit's access
                // point serves DHCP and has no working internet, so it never
                // validates. That excludes home Wi-Fi and, usefully, excludes
                // the car's own OnStar hotspot, which does have internet.
                expected == null &&
                    !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                    routerOf(properties) != null ->
                    // Either a DHCP server or a plain default-route gateway.
                    // The gateway alternative is not a nicety: a user working
                    // around a head unit that will not hand out a lease does it
                    // by setting a static IP, and a static configuration has no
                    // DHCP server at all. Requiring one would have disqualified
                    // exactly the setup this path exists to support.
                    "its router is ${routerOf(properties)} and it has no working internet, " +
                        "which is what a head unit's access point looks like. The AAP " +
                        "connect will confirm it"

                else -> null
            } ?: continue

            // A network with no IPv4 address is the DHCP failure seen from the
            // other side: the phone is associated, the head unit never issued a
            // lease, and every socket on it will fail. Adopting it would turn a
            // nameable problem into a connect timeout.
            if (properties.linkAddresses.none { it.address is java.net.Inet4Address }) {
                onStep(
                    "the phone is on a Wi-Fi network whose router is ${routerOf(properties)} " +
                        "but it has no IPv4 address, so the head unit never gave it one. " +
                        "Nothing can be sent over it. Either set a static IP on that network " +
                        "in Android's Wi-Fi settings, or set Privacy to 'Use per-network " +
                        "randomized MAC' so the car stops seeing a new device every time"
                )
                continue
            }

            onStep("reusing a Wi-Fi network the phone is already on: $why")
            network = candidate
            joinedSsid = credentials.ssid
            adopted = true
            // Fresh, because the session watchdog races awaitLost() and a
            // deferred left completed by an earlier attempt would fire the
            // watchdog immediately on a network that is perfectly healthy.
            lost = CompletableDeferred()
            watchAdopted(manager, candidate)
            return candidate
        }
        return null
    }

    /**
     * Notices an adopted network going away.
     *
     * A requested network reports its own loss through the request's callback.
     * An adopted one has no request behind it, so without this the session
     * watchdog would never fire and a phone driven out of range would sit on a
     * dead socket until a write timed out.
     */
    private fun watchAdopted(manager: ConnectivityManager, adoptedNetwork: Network) {
        val watch = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(gone: Network) {
                if (gone != adoptedNetwork) return
                network = null
                joinedSsid = null
                onStep("the car's Wi-Fi network went away")
                lost.complete(Unit)
            }
        }
        val registered = runCatching {
            manager.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                watch,
            )
        }.isSuccess
        if (registered) adoptedWatch = watch
    }

    /**
     * The other end of this network: its DHCP server, or failing that the
     * default-route gateway.
     *
     * On an access point hosted by a head unit these are the same machine. The
     * gateway is the fallback rather than the first choice because a car
     * network may publish no default route at all, but it is the *only* answer
     * when the phone is statically configured.
     */
    private fun routerOf(properties: LinkProperties): String? =
        properties.dhcpServerAddress?.hostAddress
            ?: properties.routes
                .firstOrNull { it.isDefaultRoute && it.hasGateway() }
                ?.gateway?.hostAddress

    /** True if [address] is the DHCP server or the default gateway on [properties]. */
    private fun hostsGateway(properties: LinkProperties, address: String): Boolean {
        if (properties.dhcpServerAddress?.hostAddress == address) return true
        return properties.routes.any {
            it.isDefaultRoute && it.hasGateway() && it.gateway?.hostAddress == address
        }
    }

    /** @throws CarWifiException when the user's Wi-Fi radio is switched off. */
    private fun requireWifiEnabled() {
        val manager = wifiManager ?: return
        if (runCatching { manager.isWifiEnabled }.getOrDefault(true)) return
        throw CarWifiException(
            "Wi-Fi is switched off on this phone, so the car's network cannot be " +
                "joined. Android rejects the request outright rather than offering " +
                "to turn Wi-Fi on. Switch Wi-Fi on and press Connect again — it does " +
                "not need to be connected to anything, it just has to be on."
        )
    }

    /**
     * What the radios are doing right now, for the heartbeat and the give-up
     * message.
     *
     * Process importance is in here because `WifiNetworkFactory` silently drops
     * a specifier request from an app that is neither foreground nor running a
     * foreground service, and from the app's side that is indistinguishable from
     * every other failure. `getMyMemoryState` is a static call with no
     * permission attached, so this costs nothing.
     */
    private fun radioState(): String {
        val wifiOn = runCatching { wifiManager?.isWifiEnabled }.getOrNull()
        val state = ActivityManager.RunningAppProcessInfo()
        runCatching { ActivityManager.getMyMemoryState(state) }
        // 5 GHz support is in here because the target head unit's access point
        // is on 5745 MHz (channel 149, U-NII-3). A phone that cannot use that
        // band -- or has fallen back to the worldwide regulatory domain, where
        // U-NII-3 is unavailable -- will scan forever and find nothing, which
        // is indistinguishable in every other signal from a car that is not
        // broadcasting.
        val fiveGhz = runCatching { wifiManager?.is5GHzBandSupported }.getOrNull()
        // Screen and keyguard, because both are causal here and neither was
        // recorded. A join started or continued with the display off behaves
        // differently -- scans are throttled and the approval prompt cannot be
        // tapped -- and in the capture that prompted all this, 1.7 s of
        // otherwise unexplained activity lifecycle was very likely the screen.
        val power = appContext.getSystemService(android.os.PowerManager::class.java)
        val keyguard = appContext.getSystemService(android.app.KeyguardManager::class.java)
        val interactive = runCatching { power?.isInteractive }.getOrNull()
        val locked = runCatching { keyguard?.isKeyguardLocked }.getOrNull()
        return "wifi enabled=$wifiOn, 5GHz supported=$fiveGhz, " +
            "platform scans completed=$scansSeen, " +
            "screen on=$interactive, locked=$locked, " +
            "process importance=${importanceName(state.importance)}, " +
            "associated=${associatedWifi ?: "nothing"}"
    }

    /**
     * The Wi-Fi network the phone is associated with, as seen by the passive
     * listen, or null.
     *
     * Deliberately not `WifiManager.getConnectionInfo().getSSID()`: on Android 10+
     * that returns `<unknown ssid>` to an app without location permission, which
     * Headway will not ask for.
     */
    @Volatile
    private var associatedWifi: String? = null

    private fun startWifiWatch(manager: ConnectivityManager) {
        if (wifiWatch != null) return
        val watch = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(joined: Network) {
                associatedWifi = joined.toString()
                onStep("the phone is now associated with a Wi-Fi network ($joined)")
            }

            override fun onLost(gone: Network) {
                if (associatedWifi == gone.toString()) associatedWifi = null
                onStep("a Wi-Fi network went away ($gone)")
            }
        }
        // A listen, not a request: it never causes the platform to join
        // anything, and needs no permission beyond ACCESS_NETWORK_STATE.
        val listened = runCatching {
            manager.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                watch,
            )
        }.isSuccess
        if (listened) wifiWatch = watch
    }

    private fun stopWifiWatch() {
        val watch = wifiWatch ?: return
        wifiWatch = null
        runCatching { connectivityManager?.unregisterNetworkCallback(watch) }
    }

    /**
     * Registers for the platform's own verdict on why a local-only join failed.
     *
     * This is the single most valuable thing in the class for diagnosis, and it
     * was missing. `NetworkCallback.onUnavailable()` carries no reason at all,
     * so every failure message Headway has ever written about a join has been a
     * guess between "the sheet was not tapped" and "the car was not there".
     * `WifiManager.addLocalOnlyConnectionFailureListener` answers it directly
     * — ASSOCIATION, AUTHENTICATION, IP_PROVISIONING, NOT_FOUND or NO_RESPONSE
     * — and needs only `ACCESS_WIFI_STATE`, which the manifest already
     * declares.
     *
     * **Silence from it is not evidence.** `WifiNetworkFactory` emits these
     * from two places only: a rejected user selection, and a connection that
     * failed after its retries were exhausted. A request whose SSID was never
     * matched in any scan, or whose prompt was simply never answered, produces
     * no code at all — and that is the shape of the failure this was added to
     * diagnose. The scan-results counter and the passive association listen are
     * what cover that case; this covers the cases where the platform got as far
     * as trying.
     *
     * API 34; `minSdk` is 33, so a Pixel on Android 13 simply gets the old
     * guesswork rather than a crash.
     */
    private fun startFailureListener(spec: Spec) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return
        }
        val manager = wifiManager ?: return
        val listener = WifiManager.LocalOnlyConnectionFailureListener { _, reason ->
            val name = localOnlyFailureName(reason)
            platformFailure = name
            onStep("the platform reports the join failed: $name")
            // The authoritative early-out. Without it the caller waits out the
            // backstop deadline for an attempt the platform has already
            // abandoned.
            available.completeExceptionally(
                CarWifiException(
                    "Android could not join ${spec.ssid}: $name. " + adviceFor(reason),
                    platformReason = name,
                )
            )
        }
        val registered = runCatching {
            manager.addLocalOnlyConnectionFailureListener(
                { it.run() },
                listener,
            )
        }.isSuccess
        if (registered) failureListener = listener
    }

    private fun startScanWatch() {
        if (scanCallback != null) return
        val manager = wifiManager ?: return
        scansSeen = 0
        val callback = object : WifiManager.ScanResultsCallback() {
            override fun onScanResultsAvailable() {
                scansSeen++
            }
        }
        val registered = runCatching {
            manager.registerScanResultsCallback({ it.run() }, callback)
        }.isSuccess
        if (registered) scanCallback = callback
    }

    private fun stopScanWatch() {
        val callback = scanCallback ?: return
        scanCallback = null
        runCatching { wifiManager?.unregisterScanResultsCallback(callback) }
    }

    private fun stopFailureListener() {
        val listener = failureListener ?: return
        failureListener = null
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return
        }
        runCatching {
            wifiManager?.removeLocalOnlyConnectionFailureListener(
                listener as WifiManager.LocalOnlyConnectionFailureListener
            )
        }
    }

    /**
     * What to say when the join is given up on, using what was actually
     * observed rather than listing every possibility.
     *
     * Three sources, in decreasing order of authority: the platform's own
     * failure code, whether the phone associated with any Wi-Fi network at all,
     * and the radio state. Nothing here asserts a cause it cannot support —
     * "associated but not handed over" in particular has several possible
     * explanations on the platform side, so it reports the observation and asks
     * for the log rather than blaming the car or the app.
     */
    private fun unavailableMessage(spec: Spec, elapsedMillis: Int): String {
        val associated = associatedWifi
        val wifiOn = runCatching { wifiManager?.isWifiEnabled }.getOrDefault(true)
        return buildString {
            append("gave up joining ${spec.ssid} after $elapsedMillis ms. ")
            platformFailure?.let {
                append("Android's own reason was $it. ")
            }
            when {
                wifiOn == false ->
                    append("Wi-Fi was switched off during the attempt; switch it back on.")
                associated != null ->
                    append(
                        "The phone did associate with a Wi-Fi network ($associated) but the " +
                            "request was never satisfied. Please report this log with the " +
                            "capabilities and link-property lines above it."
                    )
                scansSeen == 0 ->
                    append(
                        "The platform completed no Wi-Fi scans at all in that time, which is " +
                            "not the car's doing. Either the request was never activated, or " +
                            "scanning is blocked — check that Wi-Fi is on and that battery " +
                            "saver is not restricting it."
                    )
                else ->
                    append(
                        "The platform scanned $scansSeen time(s) and the phone never " +
                            "associated with any Wi-Fi network, so either Android's approval " +
                            "prompt was not tapped, or the car's access point was not on the " +
                            "air. If a prompt appeared naming the car, tap Connect on it and " +
                            "leave it on screen — switching back to Headway covers it and it " +
                            "cannot be recovered. If it said 'searching' with nothing to tap, " +
                            "open the Android Auto / projection settings on the car screen so " +
                            "the head unit brings its Wi-Fi up, then press Connect again."
                    )
            }
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
            val address = runCatching { joined.getByName(host) }.getOrNull()
                ?: InetAddress.getByName(host)

            // Bound to the car's network first: the car AP has no internet, so it
            // is never the default route while mobile data is up, and an unbound
            // socket would go out over cellular and reach nothing.
            //
            // But binding can fail outright. A real capture shows
            // "Binding socket to network 147 failed: EPERM" -- the kernel refuses
            // a netId the process is not permitted to use, which is what a
            // network that has been torn down since it was looked up becomes. An
            // app that treats that as fatal gives up while the car is sitting
            // there reachable over a perfectly good default route, which is
            // exactly the case when the *user* joined the car's Wi-Fi by hand.
            //
            // So fall back to an ordinary socket and say so. If the car network
            // is not the default route this will fail too, a second later, with
            // a message that names both attempts.
            val socket = try {
                joined.socketFactory.createSocket()
            } catch (e: Exception) {
                onStep(
                    "could not bind a socket to the car network ($joined): ${e.message}. " +
                        "Falling back to an unbound socket, which works when the phone's " +
                        "default route is already the car"
                )
                Socket()
            }
            try {
                socket.connect(InetSocketAddress(address, port), connectTimeoutMillis)
            } catch (e: Throwable) {
                runCatching { socket.close() }
                throw e
            }
            socket
        }
    }

    /**
     * Unregisters the network request without retiring this object.
     *
     * Split out of [close] because the two are wanted at different times. The
     * request has to go when the association is gone — a stale callback blocks
     * the next `requestNetwork` — but the object itself is reused across
     * reconnect attempts so that a car network which is *still up* is never torn
     * down just because Bluetooth or TCP had a bad attempt.
     */
    private fun releaseRequest() {
        val released = synchronized(registrationLock) {
            stopWifiWatch()
            stopFailureListener()
            stopScanWatch()
            adoptedWatch?.let { watch ->
                adoptedWatch = null
                // A listen, so unregistering it disconnects nothing — the user's
                // own Wi-Fi connection is left exactly as it was.
                runCatching { connectivityManager?.unregisterNetworkCallback(watch) }
            }
            val cb = callback
            callback = null
            network = null
            joinedSsid = null
            adopted = false
            if (cb != null) {
                // Throws IllegalArgumentException if the callback was never
                // registered, which can happen when requestNetwork itself failed.
                runCatching { connectivityManager?.unregisterNetworkCallback(cb) }
            }
            cb != null
        }
        if (released) onStep("released the car network request")
    }

    /** Releases the request. Idempotent; safe from any thread and any path. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        releaseRequest()
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
        /**
         * Frequencies in MHz to scan first, from the head unit's own
         * advertisement. Empty means "scan every band", which is the default
         * behaviour and simply slower.
         */
        val preferredFrequenciesMhz: List<Int> = emptyList(),
    )

    companion object {

        /**
         * The backstop deadline for a join. Not a timeout in the platform's
         * sense, and deliberately far longer than one.
         *
         * The join used to pass 75 s to `requestNetwork`'s timeout variant. Two
         * things were wrong with that, and the second is the serious one.
         *
         * First, it is inside AOSP's own budget: after the user taps Connect,
         * `WifiNetworkFactory` allows `NETWORK_CONNECTION_TIMEOUT_MS` (30 s) per
         * attempt and `USER_SELECTED_NETWORK_CONNECT_RETRY_MAX` (3) retries —
         * up to 120 s — and that clock starts at the *tap*, not at the request
         * (`packages/modules/Wifi/.../WifiNetworkFactory.java` L110-L116,
         * L1213, L1315-L1325). A user who takes twenty seconds to notice the
         * prompt moves the whole budget twenty seconds later, so no fixed value
         * chosen here can be guaranteed to sit outside it.
         *
         * Second, expiring that timeout does not merely stop waiting: it
         * releases the request, which tears down the association the platform
         * is in the middle of making and broadcasts `onAbort` to Settings'
         * approval dialog. So the old timeout was capable of cancelling a join
         * that was about to succeed.
         *
         * Headway therefore requests with no platform timeout and lets the
         * verdict come from `LocalOnlyConnectionFailureListener` where that
         * listener can speak at all. This value is the backstop for everything
         * else — and, importantly, most of the interesting failures are
         * "everything else": the listener covers a rejected prompt and an
         * exhausted connect, but says nothing at all about a request whose SSID
         * was never matched or never selected, which is the shape of the
         * observed failure.
         *
         * 150 s, not longer. It has to clear AOSP's 120 s post-tap budget, but
         * every second past that is a second in which a car that has become
         * available cannot be reconnected to — and CLAUDE.md asks for 15. A
         * user who takes a very long time to notice the prompt can still lose
         * an attempt; they get a fresh one 30 s later
         * (`HeadwayService.JOIN_RETRY_DELAY_MILLIS`) rather than being stuck
         * behind a five-minute wait.
         */
        const val DEFAULT_JOIN_TIMEOUT_MILLIS: Int = 150_000

        /** How often the join says what it is doing. */
        private const val HEARTBEAT_MILLIS: Long = 5_000

        /**
         * A `STATUS_LOCAL_ONLY_CONNECTION_FAILURE_*` code as its name.
         *
         * Written out rather than reflected so the log reads the same on a
         * device whose platform predates a code Headway knows about.
         */
        internal fun localOnlyFailureName(reason: Int): String = when (reason) {
            WifiManager.STATUS_LOCAL_ONLY_CONNECTION_FAILURE_UNKNOWN -> "UNKNOWN"
            WifiManager.STATUS_LOCAL_ONLY_CONNECTION_FAILURE_ASSOCIATION -> "ASSOCIATION"
            WifiManager.STATUS_LOCAL_ONLY_CONNECTION_FAILURE_AUTHENTICATION -> "AUTHENTICATION"
            WifiManager.STATUS_LOCAL_ONLY_CONNECTION_FAILURE_IP_PROVISIONING -> "IP_PROVISIONING"
            WifiManager.STATUS_LOCAL_ONLY_CONNECTION_FAILURE_NOT_FOUND -> "NOT_FOUND"
            WifiManager.STATUS_LOCAL_ONLY_CONNECTION_FAILURE_NO_RESPONSE -> "NO_RESPONSE"
            // Deliberately printed rather than mapped to "unknown": a code this
            // build has no name for is a code from a newer platform, and the
            // number is what makes it lookup-able.
            else -> "reason=$reason"
        }

        /** What the user can actually do about each platform failure code. */
        internal fun adviceFor(reason: Int): String = when (reason) {
            WifiManager.STATUS_LOCAL_ONLY_CONNECTION_FAILURE_NOT_FOUND ->
                "The car's access point was not on the air. On the car screen, open the " +
                    "Android Auto or projection settings so the head unit brings its Wi-Fi " +
                    "up, then press Connect again."
            WifiManager.STATUS_LOCAL_ONLY_CONNECTION_FAILURE_AUTHENTICATION ->
                "The passphrase the head unit sent was rejected by its own access point. " +
                    "Un-pair and re-pair the phone in the car's Bluetooth settings, which " +
                    "makes it issue a fresh key."
            WifiManager.STATUS_LOCAL_ONLY_CONNECTION_FAILURE_IP_PROVISIONING ->
                // The one failure that retrying makes worse, which is why the
                // supervisor treats it as terminal.
                //
                // Association and authentication both succeeded -- the car
                // accepted the passphrase and let the phone onto the radio --
                // and then no DHCP lease arrived. On GrapheneOS the usual cause
                // is its default per-connection MAC randomization meeting a head
                // unit whose DHCP table is small and does not evict: every
                // attempt looks like a brand new device, the table fills, and
                // the unit stops issuing leases to anyone. GrapheneOS documents
                // exactly this failure and its fix
                // (https://grapheneos.org/usage, Wi-Fi privacy).
                //
                // Android gives an app no way to influence the MAC of a
                // WifiNetworkSpecifier connection -- the builder has no setting
                // for it -- so Headway cannot fix this from inside the join.
                "The phone got onto the car's Wi-Fi and the car never gave it an " +
                    "address. Check the Bluetooth profile line above first: a head unit " +
                    "that does not consider a phone connected often will not finish " +
                    "bringing projection up, and that looks exactly like this. If those " +
                    "are connected, the other cause is the head unit's address table " +
                    "being full - turn the vehicle's Wi-Fi off and on in the car's " +
                    "settings, then join the car's network by hand once in Android's " +
                    "Wi-Fi settings and set Privacy to 'Use per-network randomized MAC' " +
                    "so it stops refilling. See the README for how to tell the two apart."
            WifiManager.STATUS_LOCAL_ONLY_CONNECTION_FAILURE_ASSOCIATION,
            WifiManager.STATUS_LOCAL_ONLY_CONNECTION_FAILURE_NO_RESPONSE ->
                "The access point was seen but would not complete the association. If it " +
                    "keeps happening, capture this log — it is a head unit quirk worth " +
                    "recording."
            else -> "Please report this log."
        }

        /** `RunningAppProcessInfo.importance` as the name from the constant. */
        internal fun importanceName(importance: Int): String = when (importance) {
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "FOREGROUND"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE ->
                "FOREGROUND_SERVICE"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "VISIBLE"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "PERCEPTIBLE"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "SERVICE"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "CACHED"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "GONE"
            // Anything above FOREGROUND_SERVICE is refused by
            // WifiNetworkFactory.acceptRequest, so the number matters even when
            // there is no name for it.
            else -> "importance=$importance"
        }

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
         * Not because it is ambiguous — that was Headway's own bug, now fixed.
         * `WifiSecurityMode.proto` was numbered sequentially while every
         * reference numbers it as a bitfield (4 = WPA, 8 = WPA2, 16 =
         * enterprise), so the target vehicle's wire value 8 decoded as
         * `WPA2_ENTERPRISE` when it means `WPA2_PERSONAL`. The enum now matches
         * `aa-proxy-rs/src/protos/WifiInfoResponse.proto` L9-L20, and the real
         * captured bytes are pinned in `WirelessHandshakeTest`.
         *
         * It is still ignored, for a different and better reason: openauto
         * genuinely does send the *symbol* `WPA2_ENTERPRISE` while running a
         * WPA2-PSK access point, and says so in its own source
         * (`openauto/src/btservice/AndroidBluetoothServer.cpp` L165-L176). So a
         * correctly decoded value can still be the wrong value.
         *
         * The presence of a passphrase is unambiguous where the enum is not, so
         * that is what selects the security type: a PSK means WPA2-PSK, no PSK
         * means open. Every head unit AP in the references is WPA2-PSK/CCMP
         * (`WirelessAndroidAutoDongle/.../hostapd.conf.in` L1-L16). If a unit
         * ever ships WPA3-SAE this is the line to change — `setWpa3Passphrase`
         * is the WPA3 counterpart and no reference uses it.
         */
        fun specFor(
            credentials: CarNetworkCredentials,
            hiddenSsid: Boolean = false,
            /**
             * Whether to require the exact BSSID the head unit named.
             *
             * Off, and this is the important default in this class. The target
             * vehicle announces `ce:44:26:bf:18:ec` over Bluetooth, while the
             * platform's own scan logged **two** BSSIDs for that one SSID —
             * `ce:22:26:bf:18:ec` and `ce:44:26:bf:18:ec` — which is what a
             * dual-radio access point looks like. Pinning the wrong one does not
             * fail loudly: `requestNetwork` simply never matches, and the join
             * times out after 30 s looking exactly like a car that is not
             * broadcasting.
             *
             * The SSID is what actually identifies the network, and a head unit
             * SSID is already specific to one car. Matching on it alone works on
             * whichever radio the unit brings up. The BSSID stays available for a
             * unit that genuinely needs pinning — somewhere with two cars of the
             * same model in range, say — but that is a rare problem and this was
             * a common one.
             */
            pinBssid: Boolean = false,
        ): Spec = Spec(
            ssid = credentials.ssid,
            bssid = credentials.bssid.takeIf { pinBssid && isUsableBssid(it) },
            passphrase = credentials.passphrase.takeIf { it.isNotEmpty() },
            hiddenSsid = hiddenSsid,
            preferredFrequenciesMhz = credentials.advertisedFrequenciesMhz
                .filter { it in MIN_WIFI_FREQUENCY_MHZ..MAX_WIFI_FREQUENCY_MHZ }
                .distinct(),
        )

        /**
         * Bounds on a plausible Wi-Fi frequency in MHz, so a misparsed field
         * cannot be handed to the platform.
         *
         * The low end covers 2.4 GHz channel 1 (2412) with room to spare and the
         * high end covers 6 GHz. The target vehicle advertises 5745, and it also
         * advertises 0 as a placeholder in the same message — which is exactly
         * the kind of value that must not reach `setPreferredChannelsFrequenciesMhz`.
         */
        const val MIN_WIFI_FREQUENCY_MHZ: Int = 2400
        const val MAX_WIFI_FREQUENCY_MHZ: Int = 7125

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

        /**
         * @param maxPreferredChannels what
         *   `WifiManager.getMaxNumberOfChannelsPerNetworkSpecifierRequest()`
         *   reports. Passing more than the platform accepts makes `build()`
         *   throw, and the limit is device-specific, so it is read rather than
         *   assumed. Zero or negative disables the hint entirely.
         */
        fun buildSpecifier(spec: Spec, maxPreferredChannels: Int = 0): WifiNetworkSpecifier =
            WifiNetworkSpecifier.Builder()
                .setSsid(spec.ssid)
                .apply {
                    parseBssid(spec.bssid.orEmpty())?.let { setBssid(it) }
                    spec.passphrase?.let { setWpa2Passphrase(it) }
                    if (spec.hiddenSsid) setIsHiddenSsid(true)
                    // Tells the platform where to look first. AOSP turns this
                    // into a channel-limited first scan before falling back to a
                    // full-band sweep, which is the difference between matching
                    // the car on the first scan and on the third — and each
                    // scan cycle is 10 s of the user staring at a prompt with
                    // nothing on it. Never combined with setBand, which build()
                    // rejects.
                    if (maxPreferredChannels > 0 && spec.preferredFrequenciesMhz.isNotEmpty()) {
                        setPreferredChannelsFrequenciesMhz(
                            spec.preferredFrequenciesMhz.take(maxPreferredChannels).toIntArray()
                        )
                    }
                }
                .build()

        /**
         * The request for the car's AP.
         *
         * `NET_CAPABILITY_INTERNET` is removed to document intent rather than
         * because the builder adds it: `NetworkCapabilities.DEFAULT_CAPABILITIES`
         * is `NOT_RESTRICTED | TRUSTED | NOT_VPN` (plus `NOT_BANDWIDTH_CONSTRAINED`
         * on V+) and does not include it (`NetworkCapabilities.java` L798-L809),
         * so the call is a no-op today. Keeping it is still right: a request
         * that *did* carry `INTERNET` alongside a Wi-Fi specifier is rejected
         * outright by `WifiNetworkFactory.acceptRequest`
         * (`WifiNetworkFactory.java` L727-L733) — an immediate `onUnavailable`,
         * not the validation timeout an earlier version of this comment
         * described.
         *
         * Nothing else should be removed. The local-only agent keeps `TRUSTED`
         * and `NOT_RESTRICTED` (`ClientModeImpl.getCapabilities` L5225-L5231),
         * `NOT_VCN_MANAGED` is deduced onto the request automatically
         * (`NetworkRequest.Builder.deduceNotVcnManagedCapability` L622-L628),
         * and `NET_CAPABILITY_LOCAL_NETWORK` means "this device advertises
         * addresses rather than obtaining them" — the car DHCPs the phone, so it
         * does not apply.
         */
        fun buildRequest(spec: Spec, maxPreferredChannels: Int = 0): NetworkRequest =
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(buildSpecifier(spec, maxPreferredChannels))
                .build()
    }
}

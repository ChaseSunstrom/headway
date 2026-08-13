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

package dev.headway.app.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.headway.app.BuildConfig
import dev.headway.app.R
import dev.headway.app.link.BluetoothCarLink
import dev.headway.app.log.SessionLog
import dev.headway.app.link.CarWifiNetwork
import dev.headway.app.quirks.HeadUnitIdentity
import dev.headway.app.quirks.HeadUnitQuirks
import dev.headway.app.quirks.QuirkStore
import dev.headway.protocol.framing.MessageFragmenter
import dev.headway.protocol.io.FramedConnection
import dev.headway.protocol.session.AapSession
import dev.headway.protocol.session.HeadUnitProfile
import dev.headway.protocol.session.PhoneIdentity
import dev.headway.transport.LinkState
import dev.headway.transport.SessionSupervisor
import dev.headway.transport.TcpTransport
import dev.headway.transport.wireless.CarEndpoint
import dev.headway.transport.tls.AapTls
import dev.headway.transport.tls.TlsSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Holds the car link for as long as the user wants to be connected.
 *
 * Everything with a lifetime longer than an activity lives here: the Bluetooth
 * handshake, the bound Wi-Fi network, the TCP socket, the AAP session, and the
 * retry loop that rebuilds all four when the car goes away.
 *
 * ## Why this has to be a foreground service, and how it survives the lock screen
 *
 * Three separate platform behaviours make a background service unworkable, and
 * a foreground one sufficient:
 *
 * 1. **The notification is not optional.** A service started with
 *    `startForegroundService` that has not posted its notification within about
 *    five seconds is killed with `ForegroundServiceDidNotStartInTimeException`.
 *    [startForegroundNow] is therefore the first statement of
 *    [onStartCommand], before any device or permission is even looked at — a
 *    connection failure must produce a notification saying so, not a dead
 *    process.
 * 2. **`WifiNetworkSpecifier` requests are only honoured for a foreground app
 *    or a foreground service.** AOSP's `WifiNetworkFactory.acceptRequest` gates
 *    them on `isRequestFromForegroundAppOrService`, which passes at importance
 *    `<= IMPORTANCE_FOREGROUND_SERVICE` (125) — exactly what a running
 *    `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` service supplies. Without the
 *    foreground service, an activity that goes away drops the process to
 *    `IMPORTANCE_VISIBLE` (200) or worse and the request is refused outright.
 *
 *    Note the check is made **once**, when the request arrives
 *    (`WifiNetworkFactory.java` L813-L818). There is no UID-importance listener
 *    and no re-check, so the platform does not tear an established car network
 *    down when Headway is backgrounded — an earlier version of this comment
 *    claimed it did. Past the moment of the request, the foreground service
 *    earns its keep through point 3 rather than point 2.
 * 3. **Process state.** A foreground service keeps the process out of the cached
 *    and frozen states and exempt from Doze's network restrictions, so the
 *    socket keeps being read while the screen is off.
 *
 * The manifest declares `mediaProjection|connectedDevice`, but only
 * `connectedDevice` is passed to `startForeground` here. Claiming the
 * `mediaProjection` type without holding a live projection token throws
 * `SecurityException` on Android 14+; the video path re-enters the foreground
 * with both types once the user has granted a projection.
 *
 * ## What it does not hold
 *
 * No `WifiLock` and no `WakeLock`. `WifiLock.acquire` is enforced against
 * `WAKE_LOCK`, which the manifest does not declare, so acquiring one would throw
 * rather than help. If a real car shows the 5 GHz link powering down with the
 * screen off, adding that permission and a `WIFI_MODE_FULL_LOW_LATENCY` lock is
 * the first thing to try.
 */
open class HeadwayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var linkJob: Job? = null

    /** Remembered across a `START_REDELIVER_INTENT` restart. */
    @Volatile
    private var carAddress: String? = null

    /**
     * The car's Wi-Fi network, held across reconnect attempts rather than per
     * attempt.
     *
     * This is deliberate and it is the difference between a car that connects
     * once and a car that never connects. `WifiNetworkSpecifier` requests are
     * approved by the user through a system sheet, and AOSP banks that approval
     * per app *and access point* — `mUserApprovedAccessPointMap` in
     * `WifiNetworkFactory` — so the second request for the same SSID connects
     * with no sheet at all. But the approval is only stored once a connection
     * *succeeds*, and the request is torn down the moment its callback is
     * unregistered.
     *
     * Scoping the network to one attempt therefore meant every retry cycle threw
     * away an association that was working, put the sheet back in front of the
     * user, and never banked anything. Holding it here means the sheet is tapped
     * once, ever, and a failure further up the stack — Bluetooth, TCP, TLS —
     * retries against a phone that is still on the car's access point.
     */
    @Volatile
    private var carWifi: CarWifiNetwork? = null

    private val notificationManager: NotificationManager?
        get() = getSystemService(NotificationManager::class.java)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Before anything that can fail or throw. See the class KDoc.
        startForegroundNow()

        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        intent?.getStringExtra(EXTRA_CAR_ADDRESS)?.let { carAddress = it }

        // Re-delivered intents and a second tap on "connect" must not start a
        // second supervisor; two of them would fight over the same radio.
        if (linkJob?.isActive != true) {
            linkJob = scope.launch { supervisor().run() }
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        scope.cancel()
        linkJob = null
        // The one place the car network is released. Everywhere else keeps it,
        // because everywhere else is a retry that wants it.
        releaseCarWifi()
        publish(LinkState.Idle)
        super.onDestroy()
    }

    private fun releaseCarWifi() {
        val wifi = carWifi
        carWifi = null
        runCatching { wifi?.close() }
    }

    private fun supervisor() = SessionSupervisor(
        runSession = { onUp -> runSession(onUp) },
        onState = { state ->
            publish(state)
            updateNotification(describe(state))
            Log.i(TAG, "link state: $state")
        },
    )

    /**
     * The AAP port this head unit last announced, if it ever has.
     *
     * Keyed by the car's Bluetooth address, not stored globally: a port learned
     * from one car must never be tried against a different one. The old single
     * key meant pairing a second car whose head unit listens on 5288 would keep
     * dialling the first car's 7001 and fail every cycle, with nothing to
     * unlearn it.
     *
     * Persisted rather than held in memory because the useful case is the run
     * *after* a restart: a head unit that names its port only sometimes should
     * not send the phone back to guessing every time the service starts.
     */
    private fun rememberedPort(address: String): Int? =
        getSharedPreferences(PREFS, MODE_PRIVATE).getInt(portKey(address), 0).takeIf { it > 0 }

    private fun rememberPort(address: String, port: Int) {
        if (port <= 0 || port == rememberedPort(address)) return
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt(portKey(address), port)
            .apply()
        step("remembering that the head unit at $address listens on port $port")
    }

    private fun portKey(address: String): String = "$KEY_LEARNED_PORT.$address"

    /**
     * Connects to the head unit, allowing for a listener that is not up yet.
     *
     * A refusal straight after association is not the same as a wrong address.
     * `ECONNREFUSED` means the head unit answered — it is on the network and
     * reachable — and only that nothing is bound to the port yet, which is
     * exactly what it looks like while the unit is still starting its AAP
     * server. Giving up on the first one throws away a session that was seconds
     * from working, and hands the supervisor a full backoff cycle instead.
     *
     * Bounded rather than open-ended: a head unit that is never going to listen
     * has to fail the attempt so reconnection can start over.
     */
    private suspend fun connectWithRetry(
        wifi: CarWifiNetwork,
        endpoint: CarEndpoint,
        totalMillis: Long = 20_000,
        retryDelayMillis: Long = 1_000,
    ): java.net.Socket {
        val deadline = System.nanoTime() + totalMillis * 1_000_000
        var attempt = 0
        while (true) {
            attempt++
            try {
                return wifi.openSocket(endpoint.ipAddress, endpoint.port)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // The user pressed Disconnect, or the service is going away.
                // CancellationException is an Exception, so the catch below
                // would rewrap it as "the head unit never accepted an AAP
                // connection" -- blaming the car for the user's own action.
                throw e
            } catch (e: Exception) {
                if (System.nanoTime() >= deadline) {
                    throw IllegalStateException(
                        "the head unit never accepted an AAP connection on $endpoint " +
                            "after $attempt attempts over ${totalMillis}ms. It is reachable, " +
                            "so the port is wrong or it is refusing the session: ${e.message}",
                        e,
                    )
                }
                step("$endpoint not accepting yet (attempt $attempt): ${e.message}")
                kotlinx.coroutines.delay(retryDelayMillis)
            }
        }
    }

    /**
     * One attempt at the whole chain, from radio to open channels.
     *
     * Returning normally means the session ended cleanly; throwing hands the
     * failure to [SessionSupervisor], which backs off and calls this again. Every
     * resource is scoped to this function so that both outcomes release the
     * Bluetooth socket, the network request and the TCP socket — a leaked
     * network request is worse than a failed connection, because the phone stays
     * associated to an access point with no internet.
     *
     * The Bluetooth permission is not checked here. It is granted in the UI
     * before the service is ever started, and if it is somehow missing the
     * platform throws `SecurityException`, which [BluetoothCarLink] converts into
     * a link failure with a readable message — a state the supervisor and the
     * notification already handle. A second check here would only duplicate it.
     */
    @SuppressLint("MissingPermission")
    private suspend fun runSession(onUp: () -> Unit = {}) {
        // Which build produced this log. Every real-car diagnosis so far has had
        // to start by guessing that from which log messages are present, and
        // guessing wrong sends the reader after a bug that was already fixed --
        // especially while a signing problem is keeping people on older builds.
        step("Headway build ${BuildConfig.VERSION_CODE} (${BuildConfig.VERSION_NAME})")

        val adapter = BluetoothCarLink.adapterOf(this)
            ?: throw IllegalStateException("this device has no Bluetooth adapter")
        if (!adapter.isEnabled) throw IllegalStateException("Bluetooth is off")

        val car = resolveCar(adapter)
        Log.i(TAG, "connecting to ${car.address}")

        BluetoothCarLink(car, adapter, onStep = ::step).use { link ->
            val credentials = link.fetchCredentials()
            // From here to the end of the attempt, something has to keep
            // reading Bluetooth. The head unit does not go quiet once it has
            // handed over the credentials -- it pings, and it re-announces --
            // and a peer whose keepalives are never answered has every reason
            // to decide the phone gave up. This used to be dead air for the
            // entire Wi-Fi join, which is the longest part of the bring-up.
            val rfcommService = scope.launch { link.serviceLink() }
            try {
            // Resolved before the join, not after, because two of these knobs
            // decide how the access point is matched. Identity is unknown until
            // service discovery, so this yields the catch-all profile plus any
            // user entry that matches every unit -- which is exactly where a
            // "my head unit hides its SSID" override has to live, since the
            // user cannot get far enough to learn the unit's identity.
            val quirks = QuirkStore.inAppStorage(this).quirksFor(HeadUnitIdentity())
            if (quirks != HeadUnitQuirks.DEFAULT) {
                step("applying head unit quirks: ${quirks.describe()}")
            }
            // CarNetworkCredentials.toString already redacts the passphrase, but
            // register it with the scrubber too, so it cannot leak through some
            // other log line before the export is shared for diagnosis.
            SessionLog.shared.protect(credentials.passphrase)
            Log.i(TAG, "head unit offered $credentials")

            // Every capture that reached TCP had the car announce its endpoint;
            // the attempts that timed out joining had not received one. A unit
            // that names where to connect is a unit that is ready to project,
            // which is also when its access point is on the air -- so the
            // absence is worth flagging before spending the join timeout.
            if (credentials.endpoint == null) {
                step(
                    "the car sent credentials but no endpoint this time, which has " +
                        "so far correlated with its access point not being up yet; " +
                        "if the join stalls, open Android Auto on the car screen"
                )
            }

            run {
                // Reused across attempts, not scoped to this one. See carWifi.
                val wifi = carWifi ?: CarWifiNetwork(this, onStep = ::step).also { carWifi = it }
                val network = try {
                    // If the user has already put the phone on the car's Wi-Fi
                    // themselves, use that and skip the approval sheet entirely.
                    // This is the escape hatch for a phone where the sheet keeps
                    // going unanswered, and it costs one cheap lookup.
                    wifi.adoptExistingCarNetwork(credentials)
                        ?: wifi.join(credentials, hiddenSsid = quirks.hiddenSsid, pinBssid = quirks.pinBssid)
                } catch (e: Throwable) {
                    // join() closes itself on failure, and a closed
                    // CarWifiNetwork cannot be joined again, so it must not be
                    // the one the next attempt picks up.
                    releaseCarWifi()
                    throw e
                }
                Log.i(TAG, "bound to car network $network")

                // Say so immediately, not after working out where to connect.
                // This is the announcement that the association happened, and a
                // head unit waiting for it has no reason to be accepting AAP
                // connections yet -- which is why a reachable one refused
                // outright. Sending it first also gives a unit that answers with
                // WifiStartRequest the longest possible head start.
                link.reportWifiConnected()

                // The head unit may never say where to connect. The observed
                // Chevrolet sends WifiStartRequest on some attempts and not
                // others, which made the whole link look flaky: the runs where
                // it spoke used its port, the runs where it stayed quiet used
                // the canonical 5288 and got ECONNREFUSED. The port it gave was
                // 7001, so the default was simply wrong for this car.
                //
                // A port learned from this head unit beats a constant from the
                // references, so remember it and prefer it next time.
                val endpoint = credentials.endpoint ?: run {
                    val host = wifi.headUnitAddress() ?: throw IllegalStateException(
                        "the head unit offered no endpoint over Bluetooth and the car " +
                            "network reports no DHCP server or gateway to fall back on"
                    )
                    val learned = rememberedPort(car.address)
                    val port = learned ?: TcpTransport.DEFAULT_PORT
                    step(
                        "head unit named no port; using " +
                            if (learned != null) "$port, remembered from an earlier session"
                            else "the default $port"
                    )
                    CarEndpoint(host, port)
                }
                credentials.endpoint?.let { rememberPort(car.address, it.port) }
                Log.i(TAG, "opening the AAP session to $endpoint")

                // Sockets come from the network, never from `new Socket()`:
                // the car AP has no internet and is not the default route.
                //
                // An adopted network is identified only by its gateway matching
                // the address the head unit named, which is strong evidence but
                // not proof — a home router on the same private address would
                // also match. The AAP connect is the proof. If it fails on an
                // adopted network, stop trusting the adoption and let the next
                // attempt request the car's access point properly, rather than
                // re-adopting the same wrong network forever.
                val socket = try {
                    connectWithRetry(wifi, endpoint)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (wifi.isAdopted) {
                        step(
                            "nothing answered on the Wi-Fi network Headway adopted, so it " +
                                "was probably not the car; asking Android to join the car's " +
                                "access point on the next attempt"
                        )
                        releaseCarWifi()
                    }
                    throw e
                }

                // `quirks` was resolved before the join, because two of its
                // knobs decide how the access point is matched. Identity-specific
                // touch corrections are applied later, once discovery has run.
                TcpTransport.wrap(socket).use { transport ->
                    val connection = FramedConnection(
                        transport,
                        fragmenter = MessageFragmenter(quirks.maxFragmentSize),
                    )
                    val session = AapSession(
                        connection = connection,
                        tls = TlsSession(AapTls.phoneEngine()),
                        identity = PhoneIdentity(),
                        announcedVersion = quirks.announcedVersion,
                        onStep = ::step,
                    )
                    val profile = session.connect()

                    // The link is genuinely up now: version, TLS-or-auth,
                    // service discovery and channel open all completed. Signal it
                    // so LinkState.Connected reaches the UI while the session is
                    // live rather than at the moment it ends. Then a richer
                    // notification than describe(Connected) would give.
                    onUp()
                    updateNotification(
                        "Connected to ${profile.displayName ?: car.name ?: car.address}"
                    )
                    step("session up: ${profile.displayName ?: "head unit"}, " +
                        "${profile.channelIds.size} channel(s) advertised")

                    coroutineScope {
                        // A phone that drives out of range keeps a socket that
                        // will not fail until a write times out. Watching the
                        // network makes the loss immediate, and throwing from a
                        // child cancels the scope and reaches the supervisor.
                        val watchdog = launch {
                            wifi.awaitLost()
                            throw IllegalStateException("car network lost")
                        }
                        try {
                            runChannels(connection, profile)
                        } finally {
                            watchdog.cancel()
                        }
                    }
                }
            }
            } finally {
                rfcommService.cancel()
            }
        }
    }

    /**
     * Runs for as long as the session is up.
     *
     * The default drains the connection and discards what it reads. That is not
     * a placeholder for "do nothing": something must be reading the socket, or a
     * head unit that disappears is never noticed and the supervisor never
     * reconnects. Video, input and audio wiring replaces this by overriding it
     * and dispatching each message to its channel.
     */
    protected open suspend fun runChannels(connection: FramedConnection, profile: HeadUnitProfile) {
        while (true) {
            val message = connection.receive()
            Log.v(TAG, "unhandled message on channel ${message.channelId}")
        }
    }

    // Same reasoning as runSession: BLUETOOTH_CONNECT is granted in the UI
    // before the service starts, and a SecurityException from here unwinds into
    // the supervisor's failure path with a readable message. Annotated on this
    // function too because lint checks the call site, so the annotation on the
    // caller does not reach the calls inside it.
    @SuppressLint("MissingPermission")
    private fun resolveCar(adapter: BluetoothAdapter): BluetoothDevice {
        // Logged on every attempt, not just failures. A head unit that stops
        // advertising the service between attempts is a real and confusing
        // failure mode, and it is only visible if the record is captured each
        // time rather than once at startup.
        runCatching { BluetoothCarLink.describeBondedDevices(adapter) }
            .onSuccess { Log.i(TAG, it) }

        val address = carAddress
        if (address != null) {
            return runCatching { adapter.getRemoteDevice(address) }.getOrElse {
                throw IllegalArgumentException("'$address' is not a Bluetooth address", it)
            }
        }
        return BluetoothCarLink.bondedCars(adapter).firstOrNull()
            ?: throw IllegalStateException(
                "no paired device advertises the Android Auto wireless service; " +
                    "pair the car in Settings, then pick it in Headway"
            )
    }

    // --- notification -------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Car link",
            // LOW: the notification exists because the platform requires one for
            // a foreground service, not because the user wants to be interrupted
            // every time the car comes back into range.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows whether Headway is connected to the car."
            setShowBadge(false)
        }
        notificationManager?.createNotificationChannel(channel)
    }

    private fun startForegroundNow() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(getString(R.string.app_name), "Starting"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    private fun buildNotification(title: String, text: String): Notification {
        // Resolved through the package manager rather than by naming the
        // activity class, so the service does not depend on the UI layer.
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launch?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        // Posting without POST_NOTIFICATIONS is a no-op on the platform side but
        // the compat layer can throw; a failed status update must never take the
        // session down with it.
        runCatching {
            notificationManager?.notify(
                NOTIFICATION_ID,
                buildNotification(getString(R.string.app_name), text),
            )
        }
    }

    /** Notification text. Terse: this is read at a glance, in a car. */
    private fun describe(state: LinkState): String = when (state) {
        is LinkState.Idle -> "Idle"
        is LinkState.Connecting -> "Connecting to the car"
        is LinkState.Connected -> "Connected"
        is LinkState.WaitingToRetry ->
            "Reconnecting in ${state.delayMillis / 1000}s — ${state.cause}"
        is LinkState.GaveUp -> "Disconnected — ${state.cause}"
    }

    private fun publish(state: LinkState) {
        mutableLinkState.value = state
    }

    private fun step(message: String) {
        // Into the exportable log, not only logcat. MainActivity tells the user
        // "if the car refuses to connect, export the log and send it" -- and
        // until this went through SessionLog the export held UI and
        // accessibility lines but not one line of the handshake, join or session
        // bring-up, which is the entire reason the export exists.
        SessionLog.shared.info(TAG, message)
    }

    companion object {
        private const val TAG = "HeadwayService"

        const val ACTION_STOP: String = "dev.headway.app.action.STOP"

        /** Bluetooth address of the car; omitted means "find it among the paired devices". */
        /** Where the learned head-unit port lives. */
        private const val PREFS: String = "headway_link"
        private const val KEY_LEARNED_PORT: String = "learned_aap_port"

        const val EXTRA_CAR_ADDRESS: String = "dev.headway.app.extra.CAR_ADDRESS"

        const val CHANNEL_ID: String = "headway.link"
        const val NOTIFICATION_ID: Int = 1

        private val mutableLinkState = MutableStateFlow<LinkState>(LinkState.Idle)

        /**
         * The link state, for the UI.
         *
         * Static because the state outlives any particular binding to the
         * service and the UI needs it before it has one; the service is a
         * singleton by construction, so there is exactly one writer.
         */
        val linkState: StateFlow<LinkState> = mutableLinkState.asStateFlow()

        /** Starts the service, optionally naming which paired device is the car. */
        fun start(context: android.content.Context, carAddress: String? = null) {
            val intent = Intent(context, HeadwayService::class.java).apply {
                if (carAddress != null) putExtra(EXTRA_CAR_ADDRESS, carAddress)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: android.content.Context) {
            context.startService(
                Intent(context, HeadwayService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}

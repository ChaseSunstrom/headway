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

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import androidx.annotation.RequiresPermission
import dev.headway.protocol.io.Transport
import dev.headway.transport.StreamTransport
import dev.headway.transport.wireless.CarNetworkCredentials
import dev.headway.transport.wireless.WirelessHandshake
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Raised when the Bluetooth half of the link cannot be established. */
class BluetoothCarLinkException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * The Android side of the wireless handshake: an RFCOMM socket to the head
 * unit's AA Wireless service, with [WirelessHandshake] run over it.
 *
 * This class exists only to turn a `BluetoothSocket` into a
 * [dev.headway.protocol.io.Transport]; every protocol decision lives in
 * [WirelessHandshake], which is exercised in CI over an in-process pipe because
 * the build environment has no radio (BLOCKERS.md B-001). What is *not*
 * exercised anywhere is this file — it is the untestable seam, so it is kept as
 * thin as the platform allows.
 *
 * ## Roles
 *
 * The phone is the RFCOMM client, the head unit is the RFCOMM server, and the
 * head unit speaks first once the socket is up
 * (`docs/protocol-notes.md` §3.1 STEP 0-STEP 8, from `aa-proxy-rs/src/bluetooth.rs`
 * and `WirelessAndroidAutoDongle/.../bluetoothProfiles.cpp`). That is why
 * nothing is written here before [WirelessHandshake] reads.
 *
 * ## Why the channel is never hardcoded
 *
 * `createRfcommSocketToServiceRecord` performs an SDP lookup for the service
 * UUID and uses whatever channel the record advertises. aa-proxy-rs and aawgd
 * both publish channel 8, but openauto lets Qt allocate one dynamically
 * (`WirelessHandshake.COMMONLY_USED_RFCOMM_CHANNEL`), so a phone that assumed 8
 * would connect to two of the three references and fail on the third — and,
 * more to the point, on any head unit that behaves like the third.
 */
class BluetoothCarLink(
    private val device: BluetoothDevice,
    /** Used only to stop an in-flight scan; null is tolerated. */
    private val adapter: BluetoothAdapter? = null,
    /**
     * Secure RFCOMM requires the devices to be bonded, which they are: CLAUDE.md
     * has the user pair with the car through the OS before Headway runs. The
     * insecure variant is exposed for the head-unit quirk file, since a unit
     * that publishes its SDP record without requiring authentication will refuse
     * the secure socket rather than downgrade.
     */
    private val secure: Boolean = true,
    private val onStep: (String) -> Unit = {},
) : AutoCloseable {

    private val closed = AtomicBoolean(false)

    @Volatile
    private var socket: BluetoothSocket? = null

    @Volatile
    private var transport: Transport? = null

    /** The device this link talks to; surfaced for the notification and logs. */
    val deviceAddress: String get() = device.address

    /**
     * Connects and runs the credentials exchange.
     *
     * The socket is deliberately left open afterwards. The references disagree
     * about whether the head unit expects the RFCOMM link to persist for the
     * session — aawgd tears its side down after the exchange, aa-proxy-rs keeps
     * it — and holding it open is the behaviour that survives both, since a
     * peer that does not care will not notice. [close] ends it.
     *
     * @param handshakeTimeoutMillis bounds the exchange itself. A head unit that
     *   connects and then says nothing must fail the attempt rather than hang
     *   it: the supervisor's reconnect budget is 15 s from the car becoming
     *   available.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun fetchCredentials(
        connectTimeoutMillis: Long = 20_000,
        handshakeTimeoutMillis: Long = 20_000,
    ): CarNetworkCredentials {
        check(!closed.get()) { "link already closed" }

        cancelDiscovery()

        // Try secure, then insecure. aa-proxy-rs and aawgd both register this
        // service with authentication *not* required, and a unit that does the
        // same can refuse the secure socket outright rather than downgrade to an
        // unauthenticated one. On the wire that refusal is indistinguishable
        // from "the head unit is not listening", so guessing wrong costs a
        // diagnosis rather than a retry -- cheap enough to just try both.
        val modes = if (secure) listOf(true, false) else listOf(false)
        var lastFailure: Throwable? = null

        for (useSecure in modes) {
            val label = if (useSecure) "secure" else "insecure"
            val opened = try {
                if (useSecure) {
                    device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                } else {
                    device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID)
                }
            } catch (e: IOException) {
                throw BluetoothCarLinkException("no AA Wireless SDP record on ${device.address}", e)
            } catch (e: SecurityException) {
                throw BluetoothCarLinkException("BLUETOOTH_CONNECT not granted", e)
            }
            socket = opened

            try {
                onStep("connecting RFCOMM to ${device.address} ($label)")
                withTimeout(connectTimeoutMillis) { connectBlocking(opened) }
            } catch (e: Throwable) {
                lastFailure = e
                onStep("$label RFCOMM failed: ${e.message}")
                // Close this socket directly rather than via close(): close()
                // retires the whole link, and the next mode still needs it.
                runCatching { opened.close() }
                socket = null
                continue
            }

            // closeStreams = false: a BluetoothSocket must be closed through the
            // socket object, and closing its streams first has been observed to
            // leave the socket half-open on some Android builds.
            val wrapped = StreamTransport(
                input = opened.inputStream,
                output = opened.outputStream,
                closeStreams = false,
                onClose = { runCatching { opened.close() } },
            )
            transport = wrapped

            try {
                onStep("RFCOMM up ($label); waiting for the head unit to speak")
                return withTimeout(handshakeTimeoutMillis) {
                    WirelessHandshake(wrapped, onStep).perform()
                }
            } catch (e: Throwable) {
                // Any failure past connect leaves a half-open socket that would
                // block the next attempt's SDP lookup on some stacks.
                close()
                throw e
            }
        }

        throw BluetoothCarLinkException(
            "RFCOMM connect to ${device.address} failed in every mode " +
                "(${modes.joinToString { if (it) "secure" else "insecure" }})",
            lastFailure as? Exception,
        )
    }

    /**
     * Runs the blocking connect, and makes coroutine cancellation actually stop
     * it.
     *
     * `BluetoothSocket.connect()` blocks in the stack for the full page-timeout
     * (roughly 12 s on a device that is not there) and ignores thread
     * interruption, so a cancelled coroutine would otherwise keep a thread and,
     * worse, keep the socket alive past the point the supervisor gave up on it.
     * Closing the socket is the documented way to abort a pending connect.
     */
    private suspend fun connectBlocking(target: BluetoothSocket) = withContext(Dispatchers.IO) {
        val abort = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause != null) runCatching { target.close() }
        }
        try {
            target.connect()
        } catch (e: IOException) {
            throw BluetoothCarLinkException(
                "RFCOMM connect to ${device.address} failed: ${e.message}", e
            )
        } catch (e: SecurityException) {
            throw BluetoothCarLinkException("BLUETOOTH_CONNECT not granted", e)
        } finally {
            abort?.dispose()
        }
    }

    /**
     * A scan running during a connect is not a minor inefficiency: the radio is
     * time-sliced between inquiry and the connect attempt, which in practice
     * turns a sub-second connect into a multi-second one or fails it outright.
     * Every Android Bluetooth guide says to cancel discovery first, and this is
     * the one piece of advice that reliably changes behaviour.
     */
    @SuppressLint("MissingPermission")
    private fun cancelDiscovery() {
        val a = adapter ?: return
        // BLUETOOTH_SCAN is what isDiscovering/cancelDiscovery need, and Headway
        // may be running without it (it is only requested for device discovery
        // in the UI). Not being allowed to cancel a scan is not a reason to fail
        // the connection attempt.
        runCatching {
            if (a.isDiscovering) {
                a.cancelDiscovery()
                onStep("cancelled Bluetooth discovery before connecting")
            }
        }
    }

    /** Idempotent; safe to call from the reconnect path and from teardown. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Closing the transport closes the socket through onClose. Close the
        // socket too, in case the transport was never created.
        runCatching { transport?.close() }
        runCatching { socket?.close() }
        transport = null
        socket = null
    }

    companion object {
        /**
         * The RFCOMM service to look up in the head unit's SDP records.
         *
         * Value and citations: [WirelessHandshake.AA_WIRELESS_SERVICE_UUID],
         * confirmed identically in `aa-proxy-rs/src/bluetooth.rs` L381,
         * `openauto/src/btservice/AndroidBluetoothService.cpp` L26 and
         * `WirelessAndroidAutoDongle/.../bluetoothHandler.cpp` L24.
         */
        val SERVICE_UUID: UUID = UUID.fromString(WirelessHandshake.AA_WIRELESS_SERVICE_UUID)

        /** Null on a device with no Bluetooth hardware, which CI emulators are. */
        fun adapterOf(context: Context): BluetoothAdapter? =
            context.getSystemService(BluetoothManager::class.java)?.adapter

        /**
         * Bonded devices whose cached SDP records advertise the AA Wireless
         * service.
         *
         * The cache is populated when the device is paired; it can be stale or
         * empty, and refreshing it needs `fetchUuidsWithSdp` plus a broadcast
         * receiver. Headway does not do that here — an empty result means the
         * UI asks the user which paired device is the car, which is a better
         * outcome than RFCOMM-connecting to a pair of headphones on a guess.
         */
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        fun bondedCars(adapter: BluetoothAdapter): List<BluetoothDevice> =
            bondedDevices(adapter).filter { device ->
                device.uuids?.any { it.uuid == SERVICE_UUID } == true
            }

        /** Every paired device, for the "which one is the car?" picker. */
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        fun bondedDevices(adapter: BluetoothAdapter): List<BluetoothDevice> =
            runCatching { adapter.bondedDevices?.toList().orEmpty() }.getOrDefault(emptyList())

        /**
         * Every paired device with the services it advertises, for the log.
         *
         * This exists because the two failures that look identical from the app
         * -- "connected to the wrong device" and "the right device is not
         * accepting" -- are trivially told apart by looking at what each paired
         * device actually offers. Without it, diagnosing a car that will not
         * connect means guessing.
         *
         * A head unit may present more than one Bluetooth address, with the
         * audio profiles on one and the projection service on another, so the
         * device carrying A2DP is not necessarily the one to dial.
         */
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        fun describeBondedDevices(adapter: BluetoothAdapter): String {
            val devices = bondedDevices(adapter)
            if (devices.isEmpty()) return "no paired Bluetooth devices"

            return buildString {
                appendLine("paired devices (${devices.size}):")
                for (device in devices) {
                    val name = runCatching { device.name }.getOrNull() ?: "<unnamed>"
                    val uuids = runCatching { device.uuids?.toList().orEmpty() }
                        .getOrDefault(emptyList())
                    val isCar = uuids.any { it.uuid == SERVICE_UUID }
                    appendLine("  ${device.address}  $name${if (isCar) "   <-- advertises AA Wireless" else ""}")
                    if (uuids.isEmpty()) {
                        // An empty list is itself the finding: the SDP cache is
                        // populated at pairing and can be empty or stale, and a
                        // stale cache is why a car that works with Android Auto
                        // can look like it offers nothing.
                        appendLine("      no cached SDP records (cache may be stale; re-pair to refresh)")
                    } else {
                        for (uuid in uuids) appendLine("      $uuid")
                    }
                }
            }.trimEnd()
        }
    }
}

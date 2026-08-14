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

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.headway.app.R
import dev.headway.app.log.SessionLog
import dev.headway.app.service.HeadwayService
import dev.headway.app.ui.HeadwaySettings
import dev.headway.app.ui.MainActivity

private const val TAG = "HeadwayPresence"

/**
 * Brings the link up when the car appears, without anybody touching the phone.
 *
 * ## What was missing
 *
 * The service already reconnects on its own — `SessionSupervisor` retries for as
 * long as it is running — so a driver who had pressed Connect once and left the
 * service alive did get an automatic reconnection. What they did not get was a
 * session after a reboot, after swiping the app away, or after the service was
 * killed for memory. In every one of those cases the car sat on its connecting
 * screen and the driver had to unlock the phone, find Headway and press a
 * button, which is precisely the ritual this project exists to delete.
 *
 * ## Why a Bluetooth broadcast
 *
 * Because it is the only signal that arrives *before* anything else can. The
 * car's access point is not up until the Bluetooth handshake has handed over
 * its credentials, so there is nothing on Wi-Fi to notice; and
 * `CompanionDeviceManager.startObservingDevicePresence` is aimed at BLE
 * companions rather than at a paired car's classic ACL link.
 * `BluetoothDevice.ACTION_ACL_CONNECTED` is on the platform's short list of
 * implicit broadcasts a manifest receiver may still register for, which is what
 * makes this work with the app not running at all.
 *
 * ## Starting a foreground service from the background
 *
 * Android 12 forbids it in general. Headway has an exemption and it is not a
 * loophole: an app that holds an active `CompanionDeviceManager` association —
 * which Headway already creates, for the Wi-Fi approval bypass `CarCompanion`
 * documents — and declares
 * `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND` may start one.
 * Both halves are true here, and the permission is `normal`: it is granted at
 * install and asks the driver nothing.
 *
 * When the platform refuses anyway — no association yet, a build that reads the
 * rule differently — the refusal is caught and turned into a notification the
 * driver can tap. That is a worse experience and an honest one; it is never a
 * silent failure, because a silent failure here looks exactly like the car being
 * broken.
 */
class CarPresenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> onBoot(context)

            BluetoothDevice.ACTION_ACL_CONNECTED -> onDeviceConnected(context, intent)
        }
    }

    /**
     * After a reboot or an update, nothing is running and no car is connected
     * yet — so this arms rather than connects.
     *
     * Nothing is started: an ACL connection will follow when the driver gets
     * into the car, and starting a foreground service at boot to sit waiting
     * would be a permanent notification for a phone that is indoors.
     */
    private fun onBoot(context: Context) {
        if (!HeadwaySettings.autoConnect(context)) return
        SessionLog.shared.info(TAG, "armed after boot; waiting for the car's Bluetooth")
    }

    private fun onDeviceConnected(context: Context, intent: Intent) {
        if (!HeadwaySettings.autoConnect(context)) return
        val device = deviceOf(intent) ?: return
        val expected = HeadwaySettings.carAddress(context)
        if (expected != null) {
            if (!expected.equals(device.address, ignoreCase = true)) return
        } else if (!advertisesAndroidAuto(context, device)) {
            // Nothing remembered yet, so the device has to prove it is a car:
            // it must advertise the Android Auto wireless RFCOMM service. That
            // is the same test `BluetoothCarLink.bondedCars` uses to pick which
            // paired device to talk to, so a pair of headphones cannot bring up
            // an AAP session — and a driver who has never connected before still
            // gets the first one for free, which "remember it at the handshake"
            // alone did not give them.
            return
        }

        SessionLog.shared.info(TAG, "the car's Bluetooth connected; starting the session")
        val started = runCatching { HeadwayService.start(context, carAddress = device.address) }
        started.exceptionOrNull()?.let { failure ->
            SessionLog.shared.warn(TAG, "could not start on my own: $failure")
            notifyManualStart(context, failure)
        }
    }

    /**
     * Whether [device] offers the service Headway speaks.
     *
     * Reading `uuids` needs `BLUETOOTH_CONNECT`, which Headway holds and the
     * driver granted; without it this answers false and the receiver waits for a
     * remembered address instead, which is the safe direction to fail in.
     */
    private fun advertisesAndroidAuto(context: Context, device: BluetoothDevice): Boolean {
        if (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val adapter = BluetoothCarLink.adapterOf(context) ?: return false
        val cars = runCatching { BluetoothCarLink.bondedCars(adapter) }.getOrDefault(emptyList())
        val match = cars.any { it.address.equals(device.address, ignoreCase = true) }
        if (match) {
            SessionLog.shared.info(
                TAG,
                "${device.address} advertises the Android Auto wireless service; treating it " +
                    "as the car",
            )
        }
        return match
    }

    @Suppress("DEPRECATION")
    private fun deviceOf(intent: Intent): BluetoothDevice? = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }.getOrNull()

    /**
     * The fallback: say the car is here and offer one tap.
     *
     * Deliberately high priority and deliberately rare — it fires only when the
     * platform has refused the automatic start, which is the one case where the
     * driver has to do something and would otherwise never find out.
     */
    private fun notifyManualStart(context: Context, failure: Throwable) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val tap = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, HeadwayService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Your car is here")
            .setContentText("Tap to connect — Android would not let Headway start on its own")
            .setSubText(failure.javaClass.simpleName)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private companion object {
        /**
         * Distinct from the service's own id, so the two never replace each
         * other: this one says "press me" and the service's says "connected",
         * and a driver seeing the first replaced by nothing would assume it had
         * worked.
         */
        const val NOTIFICATION_ID = 2
    }
}

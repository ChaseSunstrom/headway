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
import dev.headway.transport.LinkState

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

            BluetoothDevice.ACTION_ACL_DISCONNECTED -> onDeviceDisconnected(context, intent)
        }
    }

    /**
     * The car's Bluetooth went away, so stop trying to reach it.
     *
     * ## Why this has to exist
     *
     * Without it nothing ever told Headway the car was gone. The supervisor
     * retries with backoff and no attempt limit -- which is right while the car
     * is *there*, because a head unit rebooting or a Wi-Fi flap must not need
     * the driver -- so a phone carried out of range went on waking up, joining a
     * network that was not there and timing out, indefinitely, with a foreground
     * notification the whole time. A driver reported exactly that: it "keeps
     * trying to reconnect even when not in range, and doesn't turn off".
     *
     * ACL disconnect is the one signal that says the car is *not* there, as
     * distinct from a session that failed while it was. Walking away, switching
     * the ignition off and toggling Bluetooth all produce it.
     *
     * Stopping is cheap to undo: `ACTION_ACL_CONNECTED` starts a session again
     * with no user action, which is the same path that started this one. So the
     * cost of stopping too eagerly is one automatic restart, and the cost of not
     * stopping is a night of retries.
     *
     * ## Except while a session is actually up
     *
     * Bluetooth carries the *handshake*, not the session. Once the phone is on
     * the car's Wi-Fi the AAP link runs over TCP and needs no ACL at all, and
     * the two radios share an antenna on most phones -- so the BT link to a car
     * that is sitting right there drops and re-establishes on its own,
     * repeatedly, during a perfectly healthy drive.
     *
     * The first version of this did not check, and killed the session every
     * time that happened. A driver reported it as the connection being "kind of
     * flakey and cuts out all the time", which is exactly what it looks like
     * from the passenger seat: video stops, the car falls back to its own
     * screen, Headway starts again a few seconds later.
     *
     * So a live session is its own proof that the car is there, and outranks
     * the broadcast. This only stops the *searching*.
     */
    private fun onDeviceDisconnected(context: Context, intent: Intent) {
        val device = deviceOf(intent) ?: return
        val expected = HeadwaySettings.carAddress(context)
        // Only the car. Every other Bluetooth device on the phone -- headphones,
        // a watch, a tracker -- produces this broadcast too, and stopping the
        // session because a pair of earbuds went idle would be its own bug.
        if (expected == null || !expected.equals(device.address, ignoreCase = true)) return
        // Recorded whether or not it is acted on here, so a session that breaks
        // *later* knows the car was already gone and can stop instead of
        // retrying for minutes. See [carBluetoothGone].
        carBluetoothGone = true
        if (HeadwayService.linkState.value is LinkState.Connected) {
            SessionLog.shared.info(
                TAG,
                "the car's Bluetooth disconnected, but the AAP session is up and runs over " +
                    "Wi-Fi -- leaving it alone. Noted, in case the session breaks next",
            )
            return
        }
        SessionLog.shared.info(TAG, "the car's Bluetooth disconnected; stopping the search")
        runCatching { HeadwayService.stop(context) }
            .onFailure { SessionLog.shared.warn(TAG, "could not stop the session: $it") }
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

        carBluetoothGone = false
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

    companion object {
        /**
         * Distinct from the service's own id, so the two never replace each
         * other: this one says "press me" and the service's says "connected",
         * and a driver seeing the first replaced by nothing would assume it had
         * worked.
         */
        private const val NOTIFICATION_ID = 2

        /**
         * Whether the car's Bluetooth has gone away since it last appeared.
         *
         * This exists because ignoring an ACL disconnect during a live session
         * -- which is right, since the session runs over Wi-Fi and the two
         * radios share an antenna -- would otherwise throw away the only signal
         * that says the car is *gone* rather than merely unreachable.
         * `SessionSupervisor` says as much: its attempt limit is "the backstop
         * for when that signal never comes", and without this the signal would
         * never come for the one case it was written for -- a driver walking
         * away from a car that was working a second ago.
         *
         * So the disconnect is remembered rather than acted on, and the service
         * consults it when a session actually breaks: a break with the car's
         * Bluetooth already gone is a walk-away, and worth stopping for now
         * rather than after several minutes of backoff.
         *
         * Cleared when the car's Bluetooth comes back, and when a session
         * establishes -- a car that just completed a handshake is present
         * whatever an earlier broadcast said.
         */
        @Volatile
        var carBluetoothGone: Boolean = false
    }
}

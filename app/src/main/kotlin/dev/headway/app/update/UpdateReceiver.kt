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

package dev.headway.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast

/**
 * Receives the outcome of an install session and does the one thing that cannot
 * be done from the session itself: show the system's confirmation dialog.
 *
 * `PackageInstaller` does not prompt on its own. It reports
 * `STATUS_PENDING_USER_ACTION` and hands back an intent, and the install simply
 * never happens if nobody launches it — a silent no-op that looks identical to a
 * refusal.
 */
class UpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return

        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = confirmationIntent(intent)
                if (confirm == null) {
                    Log.e(TAG, "installer asked for confirmation but supplied no intent")
                    return
                }
                // The receiver has no task of its own to show a dialog in.
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
                    .onFailure { Log.e(TAG, "could not show the install prompt", it) }
            }

            PackageInstaller.STATUS_SUCCESS ->
                Log.i(TAG, "update installed")

            else -> {
                // The message is the useful half. "App not installed" with no
                // reason is exactly the failure mode this feature exists to end.
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.e(TAG, "update failed: status=$status ${message.orEmpty()}")
                Toast.makeText(
                    context,
                    "Update failed: ${message ?: describe(status)}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun confirmationIntent(intent: Intent): Intent? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    /** Plain-language forms of the statuses a sideloaded update actually hits. */
    private fun describe(status: Int): String = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> "cancelled"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "blocked by the system"
        PackageInstaller.STATUS_FAILURE_CONFLICT ->
            "it conflicts with the installed copy — usually a different signing key, " +
                "which needs an uninstall first"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "incompatible with this device"
        PackageInstaller.STATUS_FAILURE_INVALID -> "the downloaded APK is not valid"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "not enough storage"
        else -> "unknown error $status"
    }

    companion object {
        private const val TAG = "HeadwayUpdate"

        const val ACTION_INSTALL_STATUS: String = "dev.headway.app.INSTALL_STATUS"
    }
}

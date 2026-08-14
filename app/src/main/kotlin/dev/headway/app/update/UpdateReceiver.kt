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
import dev.headway.app.log.SessionLog

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

            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "update installed")
                SessionLog.shared.info(TAG, "update installed")
                clearLastFailure(context)
            }

            else -> {
                // The message is the useful half. "App not installed" with no
                // reason is exactly the failure mode this feature exists to end.
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                val other = intent.getStringExtra(PackageInstaller.EXTRA_OTHER_PACKAGE_NAME)
                val legacy = intent.getIntExtra(EXTRA_LEGACY_STATUS, Int.MIN_VALUE)
                val explanation = explain(status, message, other)

                Log.e(TAG, "update failed: status=$status legacy=$legacy other=$other ${message.orEmpty()}")
                // Into the session log as well as logcat, because the user this
                // is written for has no adb -- a Toast that has already faded is
                // indistinguishable from no diagnosis at all, and the export is
                // the only channel that survives.
                SessionLog.shared.warn(
                    TAG,
                    "update failed: status=$status" +
                        (if (legacy != Int.MIN_VALUE) " legacyStatus=$legacy" else "") +
                        (other?.let { " conflictingPackage=$it" } ?: "") +
                        " message=${message ?: "(none)"}",
                )
                // Kept so the Updates card can still show it after the Toast has
                // gone; a receiver has no window of its own to put a dialog in.
                storeLastFailure(context, explanation)
                Toast.makeText(context, "Update failed: $explanation", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Turns the installer's verdict into something a driver can act on.
     *
     * [PackageInstaller.EXTRA_OTHER_PACKAGE_NAME] is the load-bearing part and
     * the reason this is not just a status table: on a conflict the platform
     * names the package that already owns whatever Headway tried to claim, and
     * that name is the entire difference between "it won't install" and "Android
     * Auto owns the car-connection authority, remove it or use the compat APK".
     * It is public API and set only for [PackageInstaller.STATUS_FAILURE_CONFLICT].
     */
    private fun explain(status: Int, message: String?, other: String?): String {
        // The message is the platform's own INSTALL_FAILED_* text and is more
        // specific than any status code, so it leads when it says something
        // recognisable.
        val fromMessage = when {
            message == null -> null
            message.contains("DUPLICATE_PERMISSION", ignoreCase = true) ->
                "another app already defines a permission Headway declares" +
                    named(other) +
                    ". Install the \"compat\" APK from the same release — it drops the " +
                    "car-app host and installs anywhere."
            message.contains("CONFLICTING_PROVIDER", ignoreCase = true) ->
                "another app already owns the androidx.car.app.connection authority" +
                    named(other) +
                    ". Install the \"compat\" APK from the same release, or remove that app."
            message.contains("VERSION_DOWNGRADE", ignoreCase = true) ->
                "this build is older than the one installed"
            message.contains("UPDATE_INCOMPATIBLE", ignoreCase = true) ||
                message.contains("INCONSISTENT_CERTIFICATES", ignoreCase = true) ->
                "it is signed with a different key from the installed copy, which " +
                    "needs an uninstall first"
            message.contains("INSUFFICIENT_STORAGE", ignoreCase = true) ->
                "there is not enough free space"
            else -> null
        }
        return fromMessage ?: "${describe(status)}${message?.let { " ($it)" }.orEmpty()}"
    }

    private fun named(other: String?): String = other?.let { " ($it)" }.orEmpty()

    private fun storeLastFailure(context: Context, explanation: String) {
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_INSTALL_FAILURE, explanation)
                .apply()
        }
    }

    private fun clearLastFailure(context: Context) {
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_LAST_INSTALL_FAILURE)
                .apply()
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
        // Deliberately not "a different signing key" any more. That was true
        // when the only conflict Headway could hit was its own key changing;
        // since the car-app host it can also be a permission or a provider
        // authority another app owns, and naming the wrong one sends the driver
        // to uninstall an app that was never the problem. explain() reads the
        // platform's own message first for exactly this reason.
        PackageInstaller.STATUS_FAILURE_CONFLICT ->
            "it conflicts with something already installed — a different signing " +
                "key on the installed copy, or a permission or provider another " +
                "app owns"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "incompatible with this device"
        PackageInstaller.STATUS_FAILURE_INVALID -> "the downloaded APK is not valid"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "not enough storage"
        PackageInstaller.STATUS_FAILURE_TIMEOUT -> "the installer timed out"
        else -> "unknown error $status"
    }

    companion object {
        private const val TAG = "HeadwayUpdate"

        const val ACTION_INSTALL_STATUS: String = "dev.headway.app.INSTALL_STATUS"

        /**
         * The raw `INSTALL_FAILED_*` integer, which is the only precise answer.
         *
         * `PackageInstaller.EXTRA_LEGACY_STATUS` is `@SystemApi` and so absent
         * from `android.jar`, but the *key* is an ordinary string and reading an
         * extra by name off an Intent the platform handed us is
         * `Intent.getIntExtra`, a public method — no reflection, no hidden
         * member, nothing the non-SDK restrictions apply to. Best effort by
         * construction: absent, it reads as `Int.MIN_VALUE` and the message text
         * carries the diagnosis on its own.
         *
         * Worth having because the coarse `EXTRA_STATUS` deliberately throws
         * information away. `PackageManager.installStatusToPublicStatus` folds
         * eight distinct failures into `STATUS_FAILURE_CONFLICT` alone —
         * `DUPLICATE_PERMISSION`, `CONFLICTING_PROVIDER` and
         * `UPDATE_INCOMPATIBLE` among them — which are the three this project
         * most needs to tell apart, and which the stock installer renders as one
         * identical sentence.
         */
        private const val EXTRA_LEGACY_STATUS = "android.content.pm.extra.LEGACY_STATUS"

        /** Shared with `HeadwaySettings`; duplicated to avoid a UI dependency. */
        private const val PREFS_NAME = "headway"

        /** Read by the Updates card so a faded Toast is not the only record. */
        const val KEY_LAST_INSTALL_FAILURE: String = "last_install_failure"

        /** The stored explanation of the last failed install, if any. */
        fun lastFailure(context: Context): String? =
            runCatching {
                context.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_LAST_INSTALL_FAILURE, null)
            }.getOrNull()
    }
}

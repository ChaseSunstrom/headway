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

package dev.headway.app.video

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.Surface
import dev.headway.app.log.SessionLog

private const val TAG = "HeadwayRotation"

/**
 * Turns the phone's own display sideways, so a mirrored app lays out in landscape.
 *
 * ## The question this answers
 *
 * "Is there a way to have apps display in landscape view, if the panel is wider
 * than it is tall?" A driver asked it after watching a portrait phone screen
 * arrive in a wide pane as a narrow strip down the middle. On an 800x480 panel
 * the arithmetic is unkind: a 1080x2404 source scaled to fit is 216 columns of
 * 800, twenty-seven percent of the pane, and the app has laid itself out for a
 * tall screen the whole time.
 *
 * ## Why the display and not the picture
 *
 * Because an app's layout is decided by the display its activity is running on,
 * and nothing else. Scaling the picture larger does not make a maps app show a
 * wide map; it makes a tall map bigger. The only displays an unprivileged app
 * can influence are the Developer-options overlay display, whose geometry comes
 * from a fixed menu Headway cannot widen, and display 0 — whose *rotation* is
 * writable through a permission the driver can grant in Settings.
 *
 * Every other route was checked and is closed:
 *
 *  - a display Headway creates cannot host a third-party activity, because
 *    `ActivityTaskSupervisor.isCallerAllowedToLaunchOnDisplay` demands the
 *    *target* app declare `android:allowEmbedded` on any display that is not
 *    `FLAG_TRUSTED`, and `ADD_TRUSTED_DISPLAY` is `signature|role`;
 *  - the same holds for the display `MediaProjection` hands back, which is a
 *    recording sink by construction;
 *  - per-display `setIgnoreOrientationRequest` is `signature|recents`;
 *  - activity embedding needs the embedded app to opt in.
 *
 * ## What it costs, honestly
 *
 * An app that declares `android:screenOrientation="portrait"` *does* express a
 * preference, and the platform's own documentation for `USER_ROTATION` says the
 * value applies only "when no on-screen Activity expresses a preference". So
 * this buys landscape for the orientation-flexible apps — most maps, browsers
 * and video apps — and buys nothing for a portrait-locked one. Headway's own
 * phone screen, the lock screen and the shade rotate too, which matters little
 * on a phone that is face-down or covered for the drive.
 *
 * The driver's own two values are saved before anything is written and put back
 * on the way out, including after a crash, because a phone left stuck in
 * landscape by an app that is no longer running is a bug the driver cannot see
 * the cause of.
 */
object PhoneRotation {

    /** Whether the driver has granted the permission this needs. */
    fun granted(context: Context): Boolean =
        runCatching { Settings.System.canWrite(context) }.getOrDefault(false)

    /**
     * The Settings screen where the permission is granted.
     *
     * `WRITE_SETTINGS` is `signature|preinstalled|appop|pre23|role` — the
     * `appop` term is what makes it user-grantable, and this intent is the flow
     * the platform's own manifest comment points at.
     */
    fun accessIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Puts display 0 into landscape, remembering what it was.
     *
     * Returns false and writes nothing when the permission is missing, when the
     * phone is already held that way, or when a rotation is already in force —
     * a second apply must never overwrite the saved values with Headway's own.
     */
    fun applyLandscape(context: Context, onStep: (String) -> Unit = {}): Boolean {
        if (!granted(context)) {
            onStep("landscape apps: not granted permission to change the phone's rotation")
            return false
        }
        if (saved != null) return true
        val resolver = context.contentResolver ?: return false
        val previous = runCatching {
            Saved(
                auto = Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 1),
                rotation = Settings.System.getInt(resolver, Settings.System.USER_ROTATION, 0),
            )
        }.getOrNull() ?: return false
        val written = runCatching {
            // Auto-rotate off first. With it on, `USER_ROTATION` is ignored
            // entirely -- the sensor decides -- so writing the rotation without
            // this is a write that appears to do nothing.
            Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 0)
            Settings.System.putInt(resolver, Settings.System.USER_ROTATION, Surface.ROTATION_90)
            true
        }.getOrElse {
            SessionLog.shared.warn(TAG, "could not rotate the phone: $it")
            false
        }
        if (!written) return false
        saved = previous
        onStep("landscape apps: the phone is rotated for the drive")
        return true
    }

    /** Puts back whatever the driver had. Safe to call when nothing was changed. */
    fun restore(context: Context, onStep: (String) -> Unit = {}) {
        val previous = saved ?: return
        saved = null
        val resolver = context.contentResolver ?: return
        runCatching {
            Settings.System.putInt(resolver, Settings.System.USER_ROTATION, previous.rotation)
            Settings.System.putInt(
                resolver,
                Settings.System.ACCELEROMETER_ROTATION,
                previous.auto,
            )
            onStep("landscape apps: the phone's rotation is back to how you had it")
        }.onFailure { SessionLog.shared.warn(TAG, "could not restore the phone's rotation: $it") }
    }

    /** True while Headway is holding the phone sideways. */
    val holding: Boolean get() = saved != null

    private data class Saved(val auto: Int, val rotation: Int)

    /**
     * The driver's values, held for the life of the process.
     *
     * Not persisted: a value written to storage and restored on the next launch
     * would fight the driver's own rotation setting for as long as it took them
     * to notice. A process death mid-drive leaves the phone sideways, which is
     * one swipe of the quick settings tile to undo and is visible; the opposite
     * failure is not.
     */
    @Volatile
    private var saved: Saved? = null
}

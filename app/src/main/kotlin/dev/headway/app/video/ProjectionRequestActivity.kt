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

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import dev.headway.app.log.SessionLog
import dev.headway.app.service.HeadwayService

private const val TAG = "HeadwayProjection"

/**
 * The one screen whose entire job is to ask for screen sharing and go away.
 *
 * ## Why this exists separately from the setup screen
 *
 * Because the grant and the session no longer arrive together. A link that comes
 * up on its own — which is now the normal case — has no activity behind it, and
 * screen capture cannot be granted without one: `createScreenCaptureIntent`
 * returns an `Intent` that has to be *started for a result*, and only an
 * `Activity` can do that. Before this, a driver whose car connected by itself
 * had a dashboard with app panes that said "screen sharing is off" and no way to
 * turn it on short of disconnecting and pressing Connect again.
 *
 * Now the pane itself is the way in: tapping it starts this, the driver taps
 * once on the phone, and the grant is handed straight to the running service —
 * which attaches it to the panes without restarting the session. See
 * `HeadwayService.adoptProjectionGrant`.
 *
 * ## Why it forces the default display on Android 14 and up
 *
 * `MediaProjectionConfig.createConfigForDefaultDisplay()` makes the consent
 * dialog offer exactly one thing: the whole of display 0. Left to the ordinary
 * intent, the dialog also offers "a single app", and a driver who picks that
 * gets a capture of *one* app that never changes, while Headway goes on
 * launching things and mapping touches as though the whole screen were being
 * recorded. That failure is invisible — frames arrive, they are simply of the
 * wrong thing — and it is precisely the sort of mismatch that reads as "the app
 * pane is broken".
 *
 * The simulated-display path (ADR 0008) is the exception and says so: there the
 * driver must be able to pick that display, so the config is not applied.
 *
 * ## No UI
 *
 * It is transparent, it shows nothing, and it finishes as soon as the dialog
 * does. The driver sees the system's own consent sheet over whatever they were
 * looking at, and nothing of Headway's.
 */
class ProjectionRequestActivity : AppCompatActivity() {

    private val consent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            SessionLog.shared.info(TAG, "screen sharing granted; handing it to the session")
            HeadwayService.start(
                this,
                projectionResultCode = result.resultCode,
                projectionData = result.data,
            )
        } else {
            SessionLog.shared.info(TAG, "screen sharing was declined")
        }
        finish()
        // No animation: this window never had anything in it, and a fade would
        // be a black rectangle appearing over whatever the driver was doing.
        overridePendingTransition(0, 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            SessionLog.shared.warn(TAG, "this device offers no screen capture at all")
            finish()
            return
        }
        val intent = runCatching { captureIntent(manager) }.getOrNull()
        if (intent == null) {
            SessionLog.shared.warn(TAG, "could not build a screen-capture request")
            finish()
            return
        }
        runCatching { consent.launch(intent) }.onFailure {
            SessionLog.shared.warn(TAG, "could not ask for screen sharing: $it")
            finish()
        }
    }

    private fun captureIntent(manager: MediaProjectionManager): Intent =
        captureIntentFor(this, manager)

    companion object {

        /**
         * The consent intent Headway should ask with, from anywhere.
         *
         * Shared with the setup screen so the two cannot disagree: a session
         * started from the phone and one started from the car must capture the
         * same thing, or the pane's geometry and the touch mapping are built for
         * a display that is not the one being recorded.
         */
        fun captureIntentFor(context: Context, manager: MediaProjectionManager): Intent {
            // The user's choice, always -- because the choice Headway wants them
            // to make is "a single app".
            //
            // This previously forced `createConfigForDefaultDisplay()`, to stop
            // a driver picking a source that did not match what Headway assumed.
            // That was backwards, and a drive proved it: forcing the default
            // display *removes the single-app option from the dialog*, so the
            // only thing the driver could share was the whole screen, which is
            // exactly the "it shows the entire display, not the one app"
            // complaint.
            //
            // Android's own page is unambiguous about what the other option
            // gives: with app screen sharing "the status bar, navigation bar,
            // notifications, and other system UI elements are excluded from the
            // shared display. Only the content of the selected app is shared",
            // and "apps that use the MediaProjection APIs are capable of app
            // screen sharing automatically". So the mismatch this once guarded
            // against is handled where it belongs -- by `AppPaneHost` listening
            // to `onCapturedContentResize`, which reports the captured region's
            // real size instead of Headway guessing at it.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                return manager.createScreenCaptureIntent()
            }
            return manager.createScreenCaptureIntent(
                MediaProjectionConfig.createConfigForUserChoice(),
            )
        }

        /**
         * Opens the consent sheet from anywhere, including the car screen.
         *
         * `NEW_TASK` because the caller is usually a `Presentation` or a
         * notification rather than an activity, and `NO_ANIMATION` so nothing of
         * Headway's is ever drawn — the driver should see the system sheet and
         * nothing else.
         */
        fun ask(context: Context) {
            val intent = Intent(context, ProjectionRequestActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION,
                )
            runCatching { context.startActivity(intent) }
                .onFailure { SessionLog.shared.warn(TAG, "could not open the consent screen: $it") }
        }
    }
}

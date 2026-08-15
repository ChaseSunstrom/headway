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

package dev.headway.app.dash.tiles

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import dev.headway.app.log.SessionLog

private const val TAG = "HeadwayWidgets"

/**
 * The three steps a widget needs before a pane can show it, none of which a
 * `Presentation` is allowed to do.
 *
 * Adding a widget is allocate -> bind -> configure. The middle step may raise
 * the system's "allow Headway to add widgets?" dialog and the last one starts
 * the *provider's* own configure activity; both are `startActivityForResult`
 * calls, and only an `Activity` can make one. The car screen is a
 * `Presentation` on a display Headway owns, so it has no activity behind it and
 * cannot ask for either.
 *
 * So the car screen picks the widget and this finishes the job on the phone. It
 * is transparent and shows nothing of its own — the driver sees the system's
 * dialog, or the widget's configuration screen, and nothing else. The result
 * comes back through [WidgetSetup], in-process, because the caller is a view on
 * another display rather than something that can receive an activity result.
 *
 * ## What the driver has to do
 *
 * Usually nothing at all: once Headway has been approved as a widget host, once,
 * `bindAppWidgetIdIfAllowed` succeeds silently forever after and most widgets
 * have no configure activity. The first widget of the first drive costs one tap
 * on the phone. That is the cheapest this can be made — `BIND_APPWIDGET` is
 * `signature|privileged`, so there is no version of this that skips the dialog.
 *
 * ## Why the id is released on every path out
 *
 * An allocated id that is never bound is invisible: it belongs to Headway's
 * host, counts against the provider, and appears in no layout. [WidgetTile.forget]
 * on cancel keeps that from accumulating one id per abandoned attempt, and
 * `releaseOrphans` at start-up mops up the ones a crash stranded.
 */
class WidgetSetupActivity : AppCompatActivity() {

    private var widgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID
    private var provider: ComponentName? = null

    private val binding = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
        ::onBound,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wanted = intent?.getStringExtra(EXTRA_PROVIDER)
            ?.let { ComponentName.unflattenFromString(it) }
        if (wanted == null) {
            SessionLog.shared.warn(TAG, "asked to add a widget with no provider")
            deliver(AppWidgetManager.INVALID_APPWIDGET_ID)
            return
        }
        provider = wanted
        widgetId = WidgetTile.allocate(this) { SessionLog.shared.info(TAG, it) }
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            SessionLog.shared.warn(TAG, "the widget host would not allocate an id")
            deliver(AppWidgetManager.INVALID_APPWIDGET_ID)
            return
        }
        if (WidgetTile.bind(this, widgetId, wanted)) {
            configureOrFinish()
            return
        }
        // Not an error: it is what "the driver has not approved Headway as a
        // widget host yet" looks like, and the dialog is the answer to it.
        SessionLog.shared.info(TAG, "asking the driver to allow Headway to add widgets")
        val ask = runCatching { WidgetTile.bindPermissionIntent(widgetId, wanted) }.getOrNull()
        if (ask == null || runCatching { binding.launch(ask) }.isFailure) {
            SessionLog.shared.warn(TAG, "this phone offers no way to approve a widget host")
            cancel()
        }
    }

    private fun onBound(result: ActivityResult) {
        if (result.resultCode != Activity.RESULT_OK) {
            SessionLog.shared.info(TAG, "the driver declined to let Headway add widgets")
            cancel()
            return
        }
        // Approval is host-wide rather than per widget, so this is the last time
        // the dialog appears -- but the bind itself still has to be made, because
        // the dialog grants the right and does not exercise it.
        val target = provider
        if (target == null || !WidgetTile.bind(this, widgetId, target)) {
            SessionLog.shared.warn(TAG, "the widget could not be bound even after approval")
            cancel()
            return
        }
        configureOrFinish()
    }

    private fun configureOrFinish() {
        if (!WidgetTile.needsConfiguring(this, widgetId)) {
            deliver(widgetId)
            return
        }
        // Deliberately not fatal when it fails. A provider whose configure
        // activity will not start still renders its unconfigured default, and a
        // pane showing that beats no pane at all.
        val started = runCatching {
            SharedWidgetHost.host(this).startAppWidgetConfigureActivityForResult(
                this, widgetId, 0, REQUEST_CONFIGURE, null,
            )
            true
        }.getOrDefault(false)
        if (!started) {
            SessionLog.shared.info(TAG, "the widget's setup screen would not open; using its default")
            deliver(widgetId)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CONFIGURE) return
        // Cancelling configuration keeps the widget: several providers cancel
        // and configure themselves anyway, and the ones that do not still draw
        // something. Only an explicitly failed *bind* is worth throwing away.
        onConfigured(ActivityResult(resultCode, data))
    }

    private fun onConfigured(result: ActivityResult) {
        if (result.resultCode != Activity.RESULT_OK) {
            SessionLog.shared.info(TAG, "the widget was added without being configured")
        }
        deliver(widgetId)
    }

    private fun cancel() {
        WidgetTile.forget(this, widgetId) { SessionLog.shared.info(TAG, it) }
        deliver(AppWidgetManager.INVALID_APPWIDGET_ID)
    }

    private fun deliver(id: Int) {
        WidgetSetup.deliver(id, provider)
        finish()
        // Nothing was ever drawn here, so a transition would be a black
        // rectangle sliding over whatever the driver was actually looking at.
        overridePendingTransition(0, 0)
    }

    companion object {

        private const val EXTRA_PROVIDER = "dev.headway.app.widget.PROVIDER"

        /**
         * The request code for the provider's configure activity.
         *
         * Not routed through `registerForActivityResult` because
         * `startAppWidgetConfigureActivityForResult` takes a request code and
         * calls `startActivityForResult` itself — the launcher API cannot wrap
         * it, so the result arrives at [onActivityResult] the old way.
         */
        private const val REQUEST_CONFIGURE = 0x4857

        /** The intent that adds [provider] as a widget, from anywhere. */
        fun intentFor(context: Context, provider: ComponentName): Intent =
            Intent(context, WidgetSetupActivity::class.java)
                .putExtra(EXTRA_PROVIDER, provider.flattenToString())
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION,
                )
    }
}

/**
 * Carries a widget from the phone back to the car screen that asked for one.
 *
 * In-process and deliberately so. The two ends are a `Presentation` on the car
 * display and an `Activity` on the phone's, both inside Headway, and there is no
 * activity-result path between them: the car screen is not an activity, and a
 * `Presentation` cannot become one.
 *
 * Exactly one request can be outstanding. A second overwrites the first, whose
 * callback is then dropped — correct, because the only way to make a second
 * request is to open the picker again, and the earlier pane is no longer waiting
 * for an answer.
 *
 * A request that never returns — the process died while the driver was in a
 * provider's setup screen — leaks nothing but a callback the garbage collector
 * takes with the shell. The widget id it allocated is cleaned up by
 * `WidgetTile.releaseOrphans` at the next start.
 */
object WidgetSetup {

    private val lock = Any()
    private var pending: ((Int, ComponentName?) -> Unit)? = null

    /**
     * Starts the phone-side flow and calls [onDone] with the result.
     *
     * [onDone] runs on whichever thread the activity finished on, which is the
     * main thread in every path here — the car screen may touch its views
     * directly.
     *
     * @param onDone receives [AppWidgetManager.INVALID_APPWIDGET_ID] when the
     *   driver backed out or the phone refused, and a bound id otherwise.
     */
    fun request(
        context: Context,
        provider: ComponentName,
        onDone: (Int, ComponentName?) -> Unit,
    ) {
        synchronized(lock) { pending = onDone }
        val started = runCatching {
            context.startActivity(WidgetSetupActivity.intentFor(context, provider))
            true
        }.getOrDefault(false)
        if (!started) {
            SessionLog.shared.warn(TAG, "could not open the widget setup screen")
            deliver(AppWidgetManager.INVALID_APPWIDGET_ID, provider)
        }
    }

    /** Hands the result to whoever asked, and clears the request. */
    fun deliver(widgetId: Int, provider: ComponentName?) {
        val waiting = synchronized(lock) { pending.also { pending = null } }
        waiting?.invoke(widgetId, provider)
    }
}

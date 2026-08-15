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
 * One tap on the phone per widget, unless they tick **Always allow** in the
 * system's dialog — which starts *unticked* for a first-time host, because
 * AOSP's `AllowBindAppWidgetActivity` initialises that checkbox from
 * `hasBindAppWidgetPermission`. Ticking it persists the grant and later adds are
 * silent; not ticking it means the dialog appears every time, which is correct
 * behaviour rather than a fault. Most widgets have no configure activity, so
 * that dialog is usually the whole interaction.
 *
 * That is the cheapest this can be made: `BIND_APPWIDGET` is
 * `signature|privileged`, so no version of this skips the dialog.
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
        // A repair reuses the id the layout already stores; only a fresh add
        // allocates. Without this, repairing a pane whose binding was lost would
        // strand the old id and rewrite the layout for no reason.
        val existing = intent?.getIntExtra(EXTRA_EXISTING_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        widgetId = if (existing != AppWidgetManager.INVALID_APPWIDGET_ID) {
            SessionLog.shared.info(TAG, "repairing the binding for widget $existing")
            existing
        } else {
            WidgetTile.allocate(this) { SessionLog.shared.info(TAG, it) }
        }
        // Claimed before anything can run that sweeps orphans. Between allocate
        // and the save the id exists on the host and appears in no layout, which
        // is indistinguishable from an orphan -- and `releaseOrphanedWidgets`
        // runs on every car-screen bring-up, which reconnection makes frequent.
        WidgetSetup.claim(widgetId)
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
        // `RESULT_OK` means "Settings has already bound it", NOT "you may now
        // bind". AOSP's `AllowBindAppWidgetActivity.onClick` performs the bind
        // itself and only sets `RESULT_OK` if that bind succeeded.
        //
        // This used to bind a second time from Headway's own process, and that
        // second call cannot succeed: unless the driver also ticked the
        // "always allow" checkbox -- which starts unticked for a first-time host
        // -- Headway is not in the bind allowlist, so
        // `bindAppWidgetIdIfAllowed` returns false. The code then treated that
        // false as "could not be bound", cancelled, and *deleted the widget the
        // dialog had just bound for it*. Every single first add failed, and the
        // driver's evidence for it was a widget that never appeared.
        //
        // So verify rather than repeat. The dialog also returns the id it bound,
        // which is authoritative over the one allocated here.
        result.data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            ?.takeIf { it != AppWidgetManager.INVALID_APPWIDGET_ID }
            ?.let { widgetId = it }
        if (!WidgetTile.isBound(this, widgetId)) {
            SessionLog.shared.warn(TAG, "the approval dialog returned OK but nothing is bound")
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

        /** An id to repair rather than allocate. See [intentFor]. */
        private const val EXTRA_EXISTING_ID = "dev.headway.app.widget.EXISTING_ID"

        /**
         * The request code for the provider's configure activity.
         *
         * Not routed through `registerForActivityResult` because
         * `startAppWidgetConfigureActivityForResult` takes a request code and
         * calls `startActivityForResult` itself — the launcher API cannot wrap
         * it, so the result arrives at [onActivityResult] the old way.
         */
        private const val REQUEST_CONFIGURE = 0x4857

        /**
         * The intent that adds [provider] as a widget, from anywhere.
         *
         * @param existingId an id to re-bind instead of allocating a new one —
         *   how a *repair* runs, keeping the id the layout already stores.
         *
         * A repair has to come through here rather than firing the system dialog
         * directly. That dialog identifies its caller with
         * `Activity.getCallingPackage()`, which is null unless it was started
         * **for a result**; with a null package its own `getApplicationInfo`
         * lookup throws and its catch block calls `finish()`. So a plain
         * `startActivity` of `ACTION_APPWIDGET_BIND` shows the driver nothing at
         * all — the pane just says it is waiting for a permission nobody was
         * ever asked for.
         */
        fun intentFor(
            context: Context,
            provider: ComponentName,
            existingId: Int = AppWidgetManager.INVALID_APPWIDGET_ID,
        ): Intent =
            Intent(context, WidgetSetupActivity::class.java)
                .putExtra(EXTRA_PROVIDER, provider.flattenToString())
                .putExtra(EXTRA_EXISTING_ID, existingId)
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
     * The id an add is part way through, or [AppWidgetManager.INVALID_APPWIDGET_ID].
     *
     * `WidgetTile.releaseOrphans` documents that its keep-set must be every id in
     * every saved layout **plus any id a picker is part way through allocating**.
     * This is that second half. Without it, a car screen rebuilt while the driver
     * is still in the system dialog or the provider's setup screen deletes the id
     * they are configuring, and the add fails with nothing to explain it.
     */
    @Volatile
    var pendingId: Int = AppWidgetManager.INVALID_APPWIDGET_ID
        private set

    /** Marks [id] as in flight, so an orphan sweep leaves it alone. */
    fun claim(id: Int) {
        pendingId = id
    }

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
        pendingId = AppWidgetManager.INVALID_APPWIDGET_ID
        val waiting = synchronized(lock) { pending.also { pending = null } }
        waiting?.invoke(widgetId, provider)
    }
}

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
import android.app.ActivityOptions
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.util.SizeF
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.headway.app.dash.DashNode
import dev.headway.app.dash.DashTile
import dev.headway.app.ui.HeadwaySettings
import kotlin.math.max

/**
 * A pane showing another app's own live `RemoteViews`, hosted with
 * `AppWidgetHost`.
 *
 * ## Why this is the one tile that shows another app's real pixels
 *
 * `DashTile` explains the general rule: ADR 0004 found that no unprivileged app
 * may place another app's activity on a display it owns, so every other tile is
 * Headway drawing someone else's *data*. App widgets are the standing exception
 * the platform itself provides. A widget is a `RemoteViews` — a serialised view
 * tree plus the fixed set of setter calls it is allowed to carry — which the
 * provider builds in its own process and the host inflates in its own. Nothing
 * about it needs a window, a display, or a task, so nothing about it runs into
 * the launch and bounds restrictions that shut the other approaches down. It is
 * the same mechanism the notification shade uses to draw a notification whose
 * layout belongs to an app it does not own.
 *
 * The cost is that only apps that ship a widget can appear here, and a widget
 * shows what its author decided a widget should show. That is a real limit and
 * there is no unprivileged way past it.
 *
 * ## Binding, and the permission Headway does not have
 *
 * A host normally needs `android.permission.BIND_APPWIDGET` to bind an id to a
 * provider without asking. It is `signature|privileged`, so CLAUDE.md constraint
 * 2 rules it out and it is deliberately absent from the manifest. The
 * unprivileged path is [AppWidgetManager.bindAppWidgetIdIfAllowed], which
 * succeeds if the user has already approved this app as a widget host and
 * returns false otherwise; the recovery from false is to start
 * [AppWidgetManager.ACTION_APPWIDGET_BIND], which is the system's own "allow
 * Headway to add widgets?" dialog. Once the user says yes the grant is
 * remembered for the whole app, so this is a once-ever interruption rather than
 * a per-widget one.
 *
 * The dialog's result is not observed here, because a tile is not an `Activity`
 * and cannot call `startActivityForResult`. Instead the binding is retried at
 * the next [start], and the prompt is fired at most once per tile instance so a
 * driver who dismisses it is not shown it again every time the pane is
 * re-attached. A caller that *does* have an `Activity` — the widget picker —
 * should set [onBindPermissionNeeded] and drive the dialog properly.
 *
 * ## What the leaf argument holds
 *
 * `DashNode.Leaf.argument` carries the widget id and the provider, joined by
 * [ARGUMENT_SEPARATOR]: `"1234#com.example/.ClockWidget"`. Both halves are
 * needed and neither alone will do.
 *
 * The id has to be there because it is the identity of *this instance* of the
 * widget. `AppWidgetService` keys everything it remembers — the binding, the
 * options bundle, and whatever the widget's own configure activity stored — on
 * the id, and a driver may reasonably pin two clocks showing two cities. Storing
 * only the provider would mean allocating a fresh id on every start, which loses
 * that state and leaks an id per session.
 *
 * The provider has to be there because a binding can be lost while the id
 * survives: the user declines the host permission, or the provider's app is
 * updated or reinstalled. With the provider recorded the pane repairs itself on
 * the next start; without it, [AppWidgetManager.getAppWidgetInfo] returns null
 * and there is nothing left to identify what the pane was supposed to show.
 *
 * ## Widget ids leak if nobody deletes them
 *
 * An allocated id belongs to Headway's host until [AppWidgetHost.deleteAppWidgetId]
 * is called, and it survives the process, app updates and reboots. Deleting the
 * layout that referenced it does not delete it, so the dashboard must call
 * [release] when it removes this tile for good — *not* on [stop], which happens
 * every time the pane is hidden. [releaseOrphans] is the backstop for
 * the ids that get away regardless: a crash between allocating and saving, or a
 * layout deleted by an older build.
 */
class WidgetTile(
    /**
     * The allocated app widget id, or [AppWidgetManager.INVALID_APPWIDGET_ID]
     * when the saved argument could not be read.
     */
    val widgetId: Int,
    /** The provider to rebind to if the binding is missing; null when unknown. */
    val provider: ComponentName?,
    /** Narrates each step; wired to the debug log export in the app. */
    private val onStep: (String) -> Unit = {},
) : DashTile {

    override val kind: String get() = DashTile.Kind.WIDGET

    private var container: FrameLayout? = null
    private var hostView: AppWidgetHostView? = null
    private var host: AppWidgetHost? = null
    private var listening = false
    private var prompted = false
    private var state = "not started"

    /**
     * Where to send the system's bind-permission dialog, when a caller can do
     * better than firing it blind.
     *
     * The default starts it on the phone's display and forgets about it. An
     * `Activity` that sets this can use `startActivityForResult` and refresh the
     * pane the moment the user answers, which is what the widget picker should
     * do; a pane already on the car screen has no such option.
     */
    @Volatile
    var onBindPermissionNeeded: ((Intent) -> Unit)? = null

    /**
     * An empty frame; the widget goes in at [start].
     *
     * Nothing is inflated here because a widget may only be created while the
     * host is listening, and [start] is what makes that true. The frame also
     * carries the size reporting: a widget is told its size in dp so it can pick
     * between its layouts, and the size is only known once the dashboard has laid
     * the pane out.
     */
    override fun createView(context: Context): View {
        val frame = FrameLayout(context)
        frame.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            reportSize(right - left, bottom - top)
        }
        container = frame
        showMessage(LOADING)
        return frame
    }

    /**
     * Starts the host, binds if needed, and attaches the widget.
     *
     * Idempotent in the sense that matters — the host is retained once and the
     * pane ends in the same state however many times this is called — but not
     * inert: calling it again is the documented way to re-attempt a binding the
     * user has since approved.
     */
    override fun start() {
        val frame = container ?: return
        val context = frame.context
        if (!listening) {
            host = SharedWidgetHost.retain(context, onStep)
            listening = true
        }
        attach(context)
    }

    /**
     * Detaches the widget and drops the host reference.
     *
     * The widget view is thrown away rather than merely hidden. `RemoteViews`
     * updates are pushed to every hosted view the host knows about, so a view
     * that is off screen is still costing the provider's process work on every
     * update; and rebuilding it at the next [start] is one `createView` call.
     * The id and the binding are untouched, which is the whole point of the
     * split between this and [release].
     */
    override fun stop() {
        detach()
        if (listening) {
            listening = false
            host = null
            SharedWidgetHost.release(onStep)
        }
    }

    /**
     * Gives the widget id back to the system. Idempotent, and not part of
     * [DashTile].
     *
     * Call this exactly when the driver removes the pane from a layout, or when
     * a layout holding it is deleted. Anything less and the id stays allocated
     * for the life of the installation; anything more — calling it from [stop] —
     * and the driver loses the widget's configuration every time the pane is
     * hidden.
     *
     * The caller supplies the context rather than the tile reusing its view's,
     * because the case this exists for is a tile being deleted from a layout that
     * was never on screen, where there is no view and therefore no context to
     * reuse. That is precisely the case that would otherwise leak.
     */
    fun release(context: Context) {
        stop()
        forget(context, widgetId, onStep)
    }

    override fun describe(): String {
        val name = provider?.flattenToShortString() ?: "unknown provider"
        return "widget $widgetId ($name): $state"
    }

    // --- internals ------------------------------------------------------------

    private fun attach(context: Context) {
        val frame = container ?: return
        val host = this.host ?: return
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            state = "no widget id in the saved layout"
            showMessage("This widget was not saved properly. Pick it again.")
            return
        }
        // Null on a device with no PackageManager.FEATURE_APP_WIDGETS, where the
        // service is simply not registered. A phone always has it; a pane that
        // says so is better than a crash on whatever else Headway is installed on.
        val manager: AppWidgetManager? = AppWidgetManager.getInstance(context)
        if (manager == null) {
            state = "this device does not host app widgets"
            showMessage("This device does not support widgets.")
            return
        }

        // getAppWidgetInfo returns null both for an id that was never bound and
        // for one whose provider has since been uninstalled; rebind tells the two
        // apart by trying, which is the only way to.
        val info: AppWidgetProviderInfo =
            manager.getAppWidgetInfo(widgetId) ?: rebind(context, manager) ?: run {
                state = if (provider == null) {
                    "not bound and no provider recorded"
                } else {
                    "not bound"
                }
                showMessage("Waiting for permission to show this widget.")
                return
            }

        // The three-argument createView, taking the context explicitly. That
        // context is the one the RemoteViews inflate against, so on the dashboard
        // it must be the car display's and not the application's — a widget
        // inflated against the wrong context comes out at the phone's density.
        // Android 16 adds a two-argument overload that reuses the host's own
        // context and deprecates this one; against compileSdk 35 only this
        // signature exists, and it is the right one regardless for the same
        // reason.
        val view = runCatching { host.createView(context, widgetId, info) }.getOrElse { error ->
            // createView reaches across a binder to the provider's process. A
            // provider that is mid-update, or that throws while building its
            // RemoteViews, must cost one pane rather than the dashboard.
            state = "the provider would not render: $error"
            onStep("widget $widgetId failed to render: $error")
            showMessage("This widget could not be shown.")
            return
        }
        frame.removeAllViews()
        frame.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        hostView = view
        state = "showing ${info.loadLabel(context.packageManager)}"
        // The frame is usually already laid out by the time a pane is restarted,
        // so no layout change is coming to trigger the size report.
        reportSize(frame.width, frame.height)
    }

    /**
     * Attempts the unprivileged bind, and asks the user when it is refused.
     *
     * @return the provider info once bound, or null when it is not.
     */
    private fun rebind(context: Context, manager: AppWidgetManager): AppWidgetProviderInfo? {
        val provider = this.provider ?: return null
        val bound = runCatching { manager.bindAppWidgetIdIfAllowed(widgetId, provider) }
            .getOrDefault(false)
        if (bound) {
            onStep("widget $widgetId bound to ${provider.flattenToShortString()}")
            return manager.getAppWidgetInfo(widgetId)
        }
        if (!prompted) {
            prompted = true
            val intent = bindPermissionIntent(widgetId, provider)
            val hook = onBindPermissionNeeded
            if (hook != null) {
                hook(intent)
            } else {
                startOnPhoneDisplay(context, intent, onStep)
            }
            onStep("asked the user to allow Headway to host widgets")
        }
        return null
    }

    private fun detach() {
        val frame = container ?: return
        if (hostView == null) return
        frame.removeAllViews()
        hostView = null
        state = "detached"
        showMessage(LOADING)
    }

    /**
     * Tells the widget how much room it has, in dp.
     *
     * Widgets choose between layouts on their advertised size breakpoints, so a
     * widget never told its size renders at whatever the provider guessed and
     * usually looks wrong in a pane that is not a home-screen cell. The
     * conversion uses the view's own `DisplayMetrics`, which on the car display
     * is the density the head unit negotiated rather than the phone's — that is
     * the whole reason the dashboard's display is created at the car's density.
     *
     * A single [SizeF] rather than a landscape/portrait pair: a dashboard pane
     * does not rotate, so offering two sizes would be describing a choice the
     * widget will never get to make.
     */
    private fun reportSize(widthPx: Int, heightPx: Int) {
        val view = hostView ?: return
        if (widthPx <= 0 || heightPx <= 0) return
        val density = view.resources.displayMetrics.density
        if (density <= 0f) return
        val size = SizeF(widthPx / density, heightPx / density)
        runCatching { view.updateAppWidgetSize(Bundle(), listOf(size)) }
    }

    private fun showMessage(text: String) {
        val frame = container ?: return
        frame.removeAllViews()
        hostView = null
        frame.addView(
            messageView(frame.context, text),
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT),
        )
    }

    companion object {

        /**
         * Shown between the pane being built and the widget arriving.
         *
         * Not blank, because the gap is long enough to notice: the first
         * `RemoteViews` for a cold provider costs that app's process being
         * started.
         */
        private const val LOADING = "Loading widget"

        /**
         * Separates the widget id from the flattened provider in the leaf
         * argument.
         *
         * `#` because it cannot occur in either half: a decimal id is digits, and
         * `ComponentName.flattenToString` emits a package name and a class name,
         * both of which are Java identifiers joined by `/`. So splitting on the
         * first occurrence is unambiguous without escaping.
         */
        const val ARGUMENT_SEPARATOR: String = "#"

        /**
         * The leaf argument for a bound widget. See the class KDoc for why both
         * halves are stored.
         */
        fun argumentFor(widgetId: Int, provider: ComponentName): String =
            "$widgetId$ARGUMENT_SEPARATOR${provider.flattenToString()}"

        /** The leaf a picker should add to the layout once it has bound a widget. */
        fun leafFor(widgetId: Int, provider: ComponentName): DashNode.Leaf =
            DashNode.Leaf(DashTile.Kind.WIDGET, argumentFor(widgetId, provider))

        /**
         * The id in a saved argument, or [AppWidgetManager.INVALID_APPWIDGET_ID].
         *
         * Also accepts a bare id with no provider, which is what a layout written
         * before the provider was recorded contains. Such a pane still works as
         * long as its binding survives; it simply cannot repair itself.
         */
        fun widgetIdOf(argument: String?): Int {
            val text = argument?.substringBefore(ARGUMENT_SEPARATOR)?.trim()
            return text?.toIntOrNull() ?: AppWidgetManager.INVALID_APPWIDGET_ID
        }

        /** The provider in a saved argument, or null when there is not one. */
        fun providerOf(argument: String?): ComponentName? {
            if (argument == null || ARGUMENT_SEPARATOR !in argument) return null
            return ComponentName.unflattenFromString(
                argument.substringAfter(ARGUMENT_SEPARATOR),
            )
        }

        /**
         * Builds the tile for a saved leaf.
         *
         * Always returns a tile, even for an argument that makes no sense. A pane
         * that says why it is empty is worth more to a driver than a pane that
         * silently is not there, and `DashLayoutStore` has already decided that an
         * unreadable detail should cost as little as possible.
         */
        fun of(leaf: DashNode.Leaf, onStep: (String) -> Unit = {}): WidgetTile =
            WidgetTile(widgetIdOf(leaf.argument), providerOf(leaf.argument), onStep)

        /**
         * Every widget a picker could offer, by label.
         *
         * Providers that ask to be hidden are dropped —
         * [AppWidgetProviderInfo.WIDGET_FEATURE_HIDE_FROM_PICKER] exists so an app
         * can ship a widget that only it may add — and so are keyguard-only ones,
         * which are built for the lock screen's geometry and look wrong anywhere
         * else. Nothing filters on `WIDGET_CATEGORY_HOME_SCREEN`: the dashboard is
         * not a home screen, and most providers do not declare a category at all.
         */
        fun installedProviders(context: Context): List<AppWidgetProviderInfo> {
            val manager: AppWidgetManager? = AppWidgetManager.getInstance(context)
            val providers = manager?.installedProviders ?: return emptyList()
            val packageManager = context.packageManager
            return providers
                .asSequence()
                .filter {
                    it.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_HIDE_FROM_PICKER == 0
                }
                .filter { it.widgetCategory != AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD }
                .sortedBy { it.loadLabel(packageManager).lowercase() }
                .toList()
        }

        /**
         * Reserves an id for a new widget.
         *
         * The caller now owns it. If the flow that follows — bind, configure,
         * save — does not finish, the caller must call [forget], or the id stays
         * allocated forever. [releaseOrphans] exists because that promise will
         * occasionally be broken by a crash rather than by carelessness.
         */
        fun allocate(context: Context, onStep: (String) -> Unit = {}): Int =
            SharedWidgetHost.host(context).allocateAppWidgetId()
                .also { onStep("allocated widget id $it") }

        /**
         * Binds without asking, which only works once the user has approved
         * Headway as a widget host.
         *
         * @return false when approval is needed; start [bindPermissionIntent] and
         *   try again after the user answers.
         */
        fun bind(context: Context, widgetId: Int, provider: ComponentName): Boolean {
            val manager: AppWidgetManager = AppWidgetManager.getInstance(context) ?: return false
            return runCatching { manager.bindAppWidgetIdIfAllowed(widgetId, provider) }
                .getOrDefault(false)
        }

        /**
         * The system dialog that grants Headway the right to bind widgets.
         *
         * Start it for a result if you can: it returns `RESULT_OK` when the user
         * agrees, and the grant then applies to every widget, not only this one.
         */
        fun bindPermissionIntent(widgetId: Int, provider: ComponentName): Intent =
            Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider)

        /** Whether this widget wants to be configured before it is first shown. */
        fun needsConfiguring(context: Context, widgetId: Int): Boolean {
            val manager: AppWidgetManager = AppWidgetManager.getInstance(context) ?: return false
            return manager.getAppWidgetInfo(widgetId)?.configure != null
        }

        /**
         * Runs the widget's own configure activity.
         *
         * Requires an `Activity`, which is why a tile cannot do this itself and
         * the picker must: [AppWidgetHost.startAppWidgetConfigureActivityForResult]
         * is the only route that carries the host's temporary permission to launch
         * a configure activity that the provider has not exported. Sending a plain
         * [AppWidgetManager.ACTION_APPWIDGET_CONFIGURE] intent instead works for
         * some widgets and is refused for others, so it is not offered here.
         *
         * A widget that is never configured still renders; it renders whatever its
         * provider considers an unconfigured default, which for some widgets is
         * blank.
         *
         * @return false when the widget has no configure activity, or the system
         *   refused to start it.
         */
        fun configure(activity: Activity, widgetId: Int, requestCode: Int): Boolean {
            if (!needsConfiguring(activity, widgetId)) return false
            return runCatching {
                SharedWidgetHost.host(activity).startAppWidgetConfigureActivityForResult(
                    activity, widgetId, 0, requestCode, null,
                )
            }.isSuccess
        }

        /** Releases one id. Safe to call for an id that was never bound. */
        fun forget(context: Context, widgetId: Int, onStep: (String) -> Unit = {}) {
            if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
            runCatching { SharedWidgetHost.host(context).deleteAppWidgetId(widgetId) }
            onStep("released widget id $widgetId")
        }

        /**
         * Deletes every id this host owns that is not in [keep].
         *
         * **[keep] must be every id referenced by every saved layout**, gathered
         * with [widgetIdOf] over `DashLayoutStore.list()`, plus any id a picker is
         * part way through allocating. Passing a partial set silently unbinds
         * widgets that are still in use, and the driver's only symptom is panes
         * that come back empty on the next drive.
         *
         * Worth running once at start-up all the same. Ids outlive the layouts
         * that referenced them, and the ones stranded by a crash between
         * [allocate] and the save are invisible everywhere else.
         *
         * @return how many were released.
         */
        fun releaseOrphans(context: Context, keep: Set<Int>, onStep: (String) -> Unit = {}): Int {
            val host = SharedWidgetHost.host(context)
            val orphans = runCatching { host.appWidgetIds }.getOrNull()
                ?.filterNot { it in keep }
                ?: return 0
            orphans.forEach { runCatching { host.deleteAppWidgetId(it) } }
            if (orphans.isNotEmpty()) {
                onStep("released ${orphans.size} orphaned widget id(s): ${orphans.joinToString()}")
            }
            return orphans.size
        }
    }
}

/**
 * The one `AppWidgetHost` for the process, and a count of who wants it
 * listening.
 *
 * ## Why there cannot be one per tile
 *
 * `AppWidgetService` identifies a host by the pair (package, host id), not by the
 * object. Two `AppWidgetHost` instances sharing a host id are the same host as
 * far as the service is concerned, and `startListening` *replaces* the registered
 * callback rather than adding to it — so with a host per tile, only whichever
 * tile called `startListening` last would ever receive a `RemoteViews` update.
 * The others would show whatever their widget looked like at the moment they were
 * created and then freeze, which is a bug that looks exactly like a slow widget.
 *
 * Giving each tile a *different* host id would avoid the collision and create a
 * worse one: ids are allocated per host, so `AppWidgetHost.getAppWidgetIds` could
 * no longer enumerate everything Headway owns and `WidgetTile.releaseOrphans`
 * would have nothing to work from.
 *
 * ## Why the count
 *
 * `stopListening` is host-wide, so the first pane to hide would silence every
 * other pane. Counting retains means the host listens while any tile is showing
 * and stops when the last one goes, which is what `DashTile` asks of a tile that
 * observes something expensive.
 */
internal object SharedWidgetHost {

    /**
     * Headway's host id, and it must never change.
     *
     * The service keys allocated widget ids on it, so a new value would orphan
     * every widget the driver has ever added — they would keep existing, keep
     * counting against the provider, and be invisible to
     * `WidgetTile.releaseOrphans`, which only sees the current host's ids. `0x4857`
     * is "HW" and has no meaning beyond being a constant nobody will be tempted to
     * tidy.
     */
    const val HOST_ID: Int = 0x4857

    private val lock = Any()
    private var instance: AppWidgetHost? = null
    private var retained: Int = 0

    /** The host, created on first use against the application context. */
    fun host(context: Context): AppWidgetHost = synchronized(lock) {
        instance ?: AppWidgetHost(context.applicationContext, HOST_ID).also { instance = it }
    }

    /** The host, listening. Balance with [release]. */
    fun retain(context: Context, onStep: (String) -> Unit = {}): AppWidgetHost =
        synchronized(lock) {
            // Reentrant: host() takes the same monitor, and this is the one place
            // the two need to be one atomic step.
            val host = host(context)
            if (retained == 0) {
                // Reaches the service over binder and can fail if the service is
                // restarting. A dashboard that loses widget updates is worth
                // strictly more than one that does not come up at all.
                runCatching { host.startListening() }
                    .onFailure { onStep("the widget host could not start listening: $it") }
            }
            retained++
            host
        }

    /** Drops one retain; stops listening when the last one goes. */
    fun release(onStep: (String) -> Unit = {}) {
        synchronized(lock) {
            if (retained == 0) return
            retained--
            if (retained == 0) {
                runCatching { instance?.stopListening() }
                    .onFailure { onStep("the widget host could not stop listening: $it") }
            }
        }
    }
}

/**
 * Headway's pinned-app grid, as a dashboard pane.
 *
 * ## Where a tap actually lands
 *
 * Nowhere on this display. The dashboard lives on `CarDisplay`, a private virtual
 * display Headway owns, and ADR 0004 Finding 1 is that
 * `ActivityTaskSupervisor.isCallerAllowedToLaunchOnDisplay` refuses a third-party
 * activity there unless that app declares `android:allowEmbedded="true"`. So this
 * grid launches onto the phone's own display, explicitly — see
 * [startOnPhoneDisplay] for why "explicitly" is load-bearing — and the app then
 * appears in the mirror pane, which is the hole showing display 0.
 *
 * That is the intended behaviour and not a compromise made here: it is exactly
 * what `DashLayoutStore.DEFAULT` describes, a mirror hole that starts as the car
 * launcher and becomes whatever the driver opened.
 *
 * ## Why the pins are read rather than owned
 *
 * The same `SharedPreferences` key as `CarLauncherActivity`,
 * `HeadwaySettings.KEY_PINNED_APPS`, with the same meaning: absent means the user
 * has never chosen, which shows everything. Sharing the key rather than the code
 * is deliberate — the two surfaces have genuinely different sizing problems, and
 * the alternative was making a whole activity reusable from a tile — but it does
 * mean two grids that must keep looking alike by hand. If a third surface ever
 * needs one, the tile drawing here should be lifted out of both and this note
 * deleted.
 *
 * The pane also listens for changes to that key, so pinning an app on the phone
 * updates the car screen without a reconnect.
 *
 * ## Sizing, which is simpler here than in the launcher activity
 *
 * `CarLauncherActivity` computes phone pixels backwards from the car's geometry
 * because it draws on display 0 and is then scaled into the car panel. A tile
 * draws on the car's display directly, at the density the head unit negotiated,
 * so 48 dp here is 48 dp there and no back-computation is needed. The multiplier
 * over the 48 dp floor is kept the same as the activity's so the two look like
 * the same product.
 */
class LauncherTile(
    /** Narrates each step; wired to the debug log export in the app. */
    private val onStep: (String) -> Unit = {},
) : DashTile {

    override val kind: String get() = DashTile.Kind.LAUNCHER

    private var grid: GridLayout? = null
    private var preferences: SharedPreferences? = null
    private var shown = 0

    /**
     * Held in a field on purpose.
     *
     * `SharedPreferences` keeps only a weak reference to its listeners, so one
     * built inline and passed straight to `register` is collected at the next GC
     * and the pane silently stops noticing pins. This is the standard trap and
     * the field is the standard fix.
     */
    private val pinsChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // A null key means the whole file was cleared, which API 30 introduced
        // and which is exactly the case where the pins have changed the most.
        //
        // Touching views from here is safe: SharedPreferencesImpl delivers this on
        // the main thread — inline when the write came from it, posted to the main
        // handler otherwise — and the dashboard's views are the main thread's even
        // though they are drawn on the car's display rather than the phone's.
        if (key == null || key == HeadwaySettings.KEY_PINNED_APPS) populate()
    }

    override fun createView(context: Context): View {
        val cells = GridLayout(context).apply {
            columnCount = 1
            alignmentMode = GridLayout.ALIGN_BOUNDS
        }
        val view = ScrollView(context).apply {
            isFillViewport = true
            setBackgroundColor(TILE_BACKGROUND)
            addView(cells, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        // The column count depends on the pane's width, which the dashboard
        // decides and the driver can change by dragging a divider. Recomputed on
        // layout, and only written when it differs, because assigning it calls
        // requestLayout and an unconditional write during layout is a loop.
        cells.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val wanted = columnsFor(context, right - left)
            if (wanted != cells.columnCount) {
                cells.post { cells.columnCount = wanted }
            }
        }
        grid = cells
        return view
    }

    override fun start() {
        val context = grid?.context ?: return
        val prefs = preferences ?: HeadwaySettings.of(context).also { preferences = it }
        // Registering twice would deliver twice; unregister first so start is
        // honestly idempotent rather than idempotent-if-you-remember.
        prefs.unregisterOnSharedPreferenceChangeListener(pinsChanged)
        prefs.registerOnSharedPreferenceChangeListener(pinsChanged)
        populate()
    }

    /**
     * Stops watching the pins, and leaves the grid standing.
     *
     * The views are kept because they cost nothing to hold — a handful of icon
     * drawables the `PackageManager` has cached anyway — and rebuilding them
     * means re-querying every launchable package, which is the expensive half.
     * Nothing here observes anything after the listener goes, which is what
     * `DashTile` actually asks for.
     */
    override fun stop() {
        preferences?.unregisterOnSharedPreferenceChangeListener(pinsChanged)
    }

    override fun describe(): String = "launcher: $shown app(s) pinned"

    // --- content --------------------------------------------------------------

    private fun populate() {
        val cells = grid ?: return
        val context = cells.context
        val apps = pinnedApps(context)
        shown = apps.size
        cells.removeAllViews()
        cells.columnCount = columnsFor(context, cells.width)
        if (apps.isEmpty()) {
            // No dialog from here: a tile has no `Activity` to show one on, and
            // the picker that would fill this list lives on the phone.
            cells.addView(
                messageView(context, "No apps pinned yet. Choose some in Headway on the phone."),
            )
            return
        }
        apps.forEach { cells.addView(buildTile(context, it)) }
    }

    /**
     * One app: icon over label, the whole cell tappable.
     *
     * The label stays even though the icon is recognisable, for the reason
     * `CarLauncherActivity` gives — two similar icons at car viewing distance
     * through glare are a wrong-app launch at speed.
     */
    private fun buildTile(context: Context, entry: AppEntry): View {
        val size = tileSizePx(context)
        val gutter = gutterPx(context)
        val cell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(gutter / 2, gutter / 2, gutter / 2, gutter / 2)
            isClickable = true
            isFocusable = true
            minimumWidth = size
            minimumHeight = size
            contentDescription = entry.label
            setOnClickListener { launch(context, entry) }
        }
        val iconSize = (size * ICON_FRACTION).toInt()
        cell.addView(
            ImageView(context).apply {
                setImageDrawable(entry.icon(context.packageManager))
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            },
        )
        cell.addView(
            TextView(context).apply {
                text = entry.label
                setTextColor(TILE_TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, LABEL_SP)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        cell.layoutParams = GridLayout.LayoutParams().apply {
            width = size
            height = WRAP_CONTENT
            setMargins(gutter / 2, gutter / 2, gutter / 2, gutter / 2)
        }
        return cell
    }

    private fun launch(context: Context, entry: AppEntry) {
        val intent = context.packageManager.getLaunchIntentForPackage(entry.packageName)
        if (intent == null) {
            onStep("${entry.packageName} can no longer be launched; refreshing the grid")
            populate()
            return
        }
        // The same pair the launcher activity uses: NEW_TASK because the caller is
        // not an activity, and RESET_TASK_IF_NEEDED so returning to a running app
        // lands on what the driver last saw rather than a fresh copy.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (startOnPhoneDisplay(context, intent, onStep)) {
            onStep("launched ${entry.packageName} from the dashboard grid")
        }
    }

    /** Null pins means "the driver has never chosen", which shows everything. */
    private fun pinnedApps(context: Context): List<AppEntry> {
        val all = launchableApps(context)
        val pinned = HeadwaySettings.of(context)
            .getStringSet(HeadwaySettings.KEY_PINNED_APPS, null)
            ?: return all
        return all.filter { it.packageName in pinned }
    }

    /**
     * Everything with a launcher entry, minus Headway itself.
     *
     * Headway is excluded for the reason the launcher activity gives: opening it
     * from its own surface does nothing visible, and it costs a slot.
     */
    private fun launchableApps(context: Context): List<AppEntry> {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val packageManager = context.packageManager
        val resolved: List<ResolveInfo> = runCatching {
            packageManager.queryIntentActivities(query, PackageManager.ResolveInfoFlags.of(0L))
        }.getOrNull() ?: emptyList()
        return resolved
            .asSequence()
            .filter { it.activityInfo.packageName != context.packageName }
            .map {
                AppEntry(it.activityInfo.packageName, it.loadLabel(packageManager).toString(), it)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    private class AppEntry(
        val packageName: String,
        val label: String,
        private val resolveInfo: ResolveInfo,
    ) {
        fun icon(packageManager: PackageManager) = resolveInfo.loadIcon(packageManager)
    }

    // --- sizing ---------------------------------------------------------------

    private fun tileSizePx(context: Context): Int =
        (context.dp(MIN_TOUCH_TARGET_DP) * TILE_MULTIPLIER).toInt()

    private fun gutterPx(context: Context): Int =
        (context.dp(MIN_TOUCH_TARGET_DP) * GUTTER_FRACTION).toInt().coerceAtLeast(4)

    private fun columnsFor(context: Context, widthPx: Int): Int {
        if (widthPx <= 0) return 1
        return max(1, widthPx / (tileSizePx(context) + gutterPx(context)))
    }

    private companion object {
        /** The platform minimum, and the floor everything else is scaled from. */
        const val MIN_TOUCH_TARGET_DP = 48f

        /** Matches `CarLauncherActivity` so the two grids look like one product. */
        const val TILE_MULTIPLIER = 2.4
        const val ICON_FRACTION = 0.45
        const val GUTTER_FRACTION = 0.25
        const val LABEL_SP = 14f
    }
}

/**
 * The pane that shows the phone's real screen, by drawing absolutely nothing.
 *
 * ## Why an empty tile is the right implementation
 *
 * Everything else on the dashboard is Headway drawing another app's data, because
 * ADR 0004 established that another app's *window* cannot be put on a display
 * Headway owns. Mirroring is the escape hatch from that finding, and it works at
 * a different layer: `MediaProjection` captures display 0 as a whole and the
 * capture is composited into the car frame. The mirror is therefore already on
 * the car screen before this tile exists. Painting anything into this pane would
 * not add the phone screen to the dashboard — it would cover it up.
 *
 * So the contract is inverted from every other tile: this one's job is to occupy
 * a rectangle in the layout and leave those pixels alone. Its view has no
 * background, no children and no click handling, so touches fall through to
 * whatever is behind the dashboard. For that to actually read as a hole the
 * dashboard's own background must be transparent behind this pane; that belongs
 * to whatever composites the dashboard over the capture, and this tile cannot
 * enforce it. [bounds] is how that compositor is told where the hole is.
 *
 * ## Why there can be only one
 *
 * A mirror shows display 0, and there is exactly one display 0. Two mirror panes
 * would be two views of the same picture at two sizes — no more information, half
 * the dashboard spent on a duplicate, and a driver reasonably expecting the
 * second one to show something else. `DashTile.Kind.MIRROR` says the dashboard
 * enforces the limit; nothing about a second instance of this class is unsafe, it
 * is simply never what was wanted.
 *
 * ## Edit mode
 *
 * With nothing drawn, a driver rearranging panes has no way to see where this one
 * is or to grab its dividers — the pane is invisible by construction. [editMode]
 * turns on a faint outline and a label for exactly as long as the dashboard is
 * being edited, and off again the moment it is not.
 */
class MirrorTile(editMode: Boolean = false) : DashTile {

    override val kind: String get() = DashTile.Kind.MIRROR

    private var view: FrameLayout? = null
    private var label: TextView? = null

    /**
     * Whether to outline and name the pane.
     *
     * A plain property rather than a constructor argument alone, because the
     * dashboard toggles editing while the tile is on screen.
     */
    var editMode: Boolean = editMode
        set(value) {
            if (field == value) return
            field = value
            applyEditMode()
        }

    /**
     * An empty frame.
     *
     * Not clickable and not focusable, so the pane is transparent to input as
     * well as to light: a tap here is meant for whatever app the phone is
     * showing, and `TouchTransform` plus the accessibility service deliver it
     * there. Consuming it in a decorative view would make the mirrored app
     * unusable on the largest pane of the default layout.
     */
    override fun createView(context: Context): View {
        val frame = FrameLayout(context)
        frame.isClickable = false
        frame.isFocusable = false
        val caption = TextView(context).apply {
            text = "Phone screen"
            setTextColor(EDIT_LABEL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, EDIT_LABEL_SP)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        frame.addView(
            caption,
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER),
        )
        view = frame
        label = caption
        applyEditMode()
        return frame
    }

    /** Nothing to observe: the mirror is the video path's, not this tile's. */
    override fun start() = Unit

    /** Nothing to release, for the same reason. */
    override fun stop() = Unit

    override fun describe(): String {
        val rect = Rect()
        val where = if (bounds(rect)) {
            "${rect.width()}x${rect.height()} at ${rect.left},${rect.top}"
        } else {
            "not on screen"
        }
        val editing = if (editMode) ", edit mode" else ""
        return "mirror hole: $where$editing"
    }

    /**
     * Where the hole is, in the dashboard window's root coordinates.
     *
     * `View.getGlobalVisibleRect` reports the part of the view no parent has
     * clipped away, relative to the root of its window. The dashboard fills the
     * car display, so those coordinates are the car frame's coordinates and can be
     * handed straight to whatever composites the mirror capture.
     *
     * @return false when the pane is not laid out or is entirely clipped, in which
     *   case [into] is untouched and there is no hole to fill.
     */
    fun bounds(into: Rect): Boolean = view?.getGlobalVisibleRect(into) ?: false

    private fun applyEditMode() {
        val frame = view ?: return
        label?.visibility = if (editMode) View.VISIBLE else View.GONE
        if (!editMode) {
            // Null and not a transparent drawable: a background, even a fully
            // transparent one, is one more thing the pane draws every frame over
            // pixels it is supposed to be leaving alone.
            frame.background = null
            return
        }
        frame.background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(frame.context.dp(EDIT_STROKE_DP), EDIT_OUTLINE)
            setCornerRadius(frame.context.dp(EDIT_CORNER_DP).toFloat())
        }
    }

    private companion object {
        const val EDIT_STROKE_DP = 2f
        const val EDIT_CORNER_DP = 8f
        const val EDIT_LABEL_SP = 16f
        val EDIT_OUTLINE: Int = 0x66FFFFFF
        val EDIT_LABEL: Int = 0x99FFFFFF.toInt()
    }
}

// --- shared helpers ----------------------------------------------------------

/** Pure black behind near-white text, the same contrast choice the launcher makes. */
private val TILE_BACKGROUND: Int = 0xFF000000.toInt()
private val TILE_TEXT: Int = 0xFFFFFFFF.toInt()
private val TILE_DIM: Int = 0xFFB0BEC5.toInt()
private const val MESSAGE_SP = 16f
private const val MESSAGE_PADDING_DP = 16f

private fun Context.dp(value: Float): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

/**
 * The pane's fallback content: one sentence saying why it is empty.
 *
 * Every tile here has states it cannot draw its way out of — a widget awaiting
 * permission, a grid with nothing pinned — and CLAUDE.md asks that a drive be
 * diagnosable from what the driver saw. A blank pane says nothing; a sentence
 * says which of half a dozen causes it was.
 */
private fun messageView(context: Context, message: String): TextView {
    val padding = context.dp(MESSAGE_PADDING_DP)
    return TextView(context).apply {
        text = message
        setTextColor(TILE_DIM)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, MESSAGE_SP)
        gravity = Gravity.CENTER
        setPadding(padding, padding, padding, padding)
    }
}

/**
 * Starts [intent] on the phone's own display, never on the dashboard's.
 *
 * The explicit display id is the whole point. A tile's context is a display
 * context for `CarDisplay`, and since Android 10 an activity started from a
 * display-associated context inherits that display — which
 * `ActivityTaskSupervisor.isCallerAllowedToLaunchOnDisplay` then refuses for any
 * app that has not declared `android:allowEmbedded="true"` (ADR 0004, Finding 1).
 * The failure is a `SecurityException` at launch, or on some paths a silently
 * dropped start, and it would apply to every app the driver taps. Naming
 * [Display.DEFAULT_DISPLAY] sends it where it can actually run, where the mirror
 * pane is already showing display 0 to the car.
 *
 * @return false when the system refused the start, which is logged rather than
 *   thrown: one app that will not open must not take the dashboard with it.
 */
private fun startOnPhoneDisplay(
    context: Context,
    intent: Intent,
    onStep: (String) -> Unit,
): Boolean {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val options = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)
    val started = runCatching { context.startActivity(intent, options.toBundle()) }
    started.exceptionOrNull()?.let { error ->
        val what = intent.component?.flattenToShortString() ?: intent.action ?: "an activity"
        onStep("could not start $what: $error")
    }
    return started.isSuccess
}

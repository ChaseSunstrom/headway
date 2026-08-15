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

import android.content.ComponentName
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.car.app.model.Template
import dev.headway.app.carapp.CarAppSession
import dev.headway.app.carapp.CarHostCapability
import dev.headway.app.media.MediaApps
import dev.headway.app.carapp.HostState
import dev.headway.app.carapp.TemplateApp
import dev.headway.app.carapp.TemplateApps
import dev.headway.app.carapp.TemplateRenderer
import dev.headway.app.dash.CarShell
import dev.headway.app.dash.DashTile
import dev.headway.app.ui.HeadwaySettings
import dev.headway.app.ui.theme.Headway
import dev.headway.dash.AllowedApps
import dev.headway.dash.PaneKind

/**
 * A third-party app's own interface, drawn by Headway.
 *
 * This is the pane the whole "run apps like Android Auto does" question was
 * about, and it is the answer to it. The app hands over templates through
 * `androidx.car.app`; [TemplateRenderer] draws them with Headway's widgets at
 * the car's own size; taps go back as clicks. No mirroring, no scaling, no
 * screen capture, and the phone's own display is free to be off.
 *
 * ## Two states
 *
 * - **Nothing chosen** — the list of apps on this phone that offer a car
 *   interface, by icon and name and what they say they are for.
 * - **Inside an app** — its current screen, with the app's own Back in the
 *   header when it offers one.
 *
 * The chosen app is remembered in the pane's own saved argument, so a driver who
 * puts Organic Maps on their Maps tab gets Organic Maps next drive rather than a
 * picker.
 *
 * ## Why the map sits behind rather than beside
 *
 * A navigation template is guidance *over* a map, and a `LinearLayout` cannot
 * overlap its children. The surface is therefore pulled out of the renderer's
 * column and put underneath it in a `FrameLayout` — the app draws the full
 * pane, Headway's guidance card floats on top, and the geometry the app was
 * told matches what it can actually see.
 */
class CarAppTile(
    context: Context,
    private val argument: String? = null,
    private val onStep: (String) -> Unit = {},
    /**
     * Records the driver's pick so the next rebuild opens it again.
     *
     * Without this the choice lasts exactly as long as the view: `chosen` is a
     * plain field, `render()` discards the tile whenever anything about the
     * layout settles, and the pane comes back as a picker. A driver who put a
     * car app in a pane had to re-pick it after every divider drag, tab switch
     * and reconnect, and the layout they saved never contained the app they
     * thought they had saved.
     *
     * The default is a no-op, for the Maps pane -- `CarShell.mapsTileFor`
     * resolves a navigator itself and passes it in, so there is nothing there
     * for a picker to record.
     */
    private val onChosen: (ComponentName) -> Unit = {},
) : DashTile {

    private val appContext: Context = context.applicationContext

    override val kind: String = PaneKind.CAR_APP

    /** Everything: the map surface at the back, the template in front. */
    private var stack: FrameLayout? = null
    private var picker: LinearLayout? = null

    /** The scroller holding [picker]; hidden with it. See [open]. */
    private var pickerScroller: ScrollView? = null

    /** How many media apps to name before counting the rest. */
    private val MEDIA_NAMES_SHOWN: Int get() = 3

    private var running = false
    private var apps: List<TemplateApp> = emptyList()

    private var session: CarAppSession? = null
    private var renderer: TemplateRenderer? = null

    /**
     * The app the driver chose from the picker, if any.
     *
     * Held across [stop]/[start] because those are not "the driver left" — they
     * are the pane being hidden and shown again, which happens on every
     * Activity interruption when the dashboard is the mirrored fallback host:
     * the power button, Home, an incoming call, or a template app launching an
     * activity of its own. Without this the tile forgot the open app and came
     * back to a picker; with the visibility bug below it came back to nothing
     * at all. Cleared only by [leave].
     */
    private var chosen: TemplateApp? = null

    private val listener = CarAppSession.Listener { state, template -> paint(state, template) }

    override fun createView(context: Context): View {
        val frame = FrameLayout(context)
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }
        val scroller = ScrollView(context).apply {
            isFillViewport = true
            addView(list, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }
        frame.addView(scroller)
        stack = frame
        picker = list
        pickerScroller = scroller
        return frame
    }

    override fun start() {
        if (running) return
        running = true
        apps = TemplateApps.installed(appContext)
        // Both argument formats, because a leaf can hold either. A car-app pane
        // saves a flattened ComponentName; a Maps pane may carry a bare package
        // name from `HeadwaySettings.KEY_MAP_APP` or from any layout written
        // before car-app maps existed. Matching only the flattened form dropped
        // those pins silently and sent the driver back to the picker on every
        // rebuild.
        val pinned = argument?.let { text ->
            val component = ComponentName.unflattenFromString(text)
            apps.firstOrNull { it.service == component }
                ?: apps.firstOrNull { it.packageName == text }
        }
        val wanted = chosen ?: pinned
        // The same gate the picker applies, applied to the pinned path too. A
        // pane that opens a saved app on start() skips the picker entirely, so
        // without this a compat install binds an app that cannot accept it every
        // time the pane appears -- which is the flow a saved layout takes on
        // every single drive. renderPicker() shows why instead.
        if (wanted != null && CarHostCapability.available(appContext)) {
            // Through the same gate as a picked app, not straight to `open`. The
            // allow-list used to be applied during discovery, so a pinned app
            // that was not allowed simply never resolved here; now that
            // discovery lists everything, a saved layout would have been the one
            // route onto the car screen that asked nobody.
            chooseOrAsk(wanted)
        } else {
            renderPicker()
        }
    }

    override fun stop() {
        if (!running) return
        running = false
        closeSession()
    }

    override fun describe(): String {
        val open = session ?: return "car app: ${apps.size} app(s) offer a car interface, none open"
        return "car app: ${open.describe()}"
    }

    // --- the session -------------------------------------------------------

    /**
     * Opens the app, or asks for it to be allowed first.
     *
     * The per-app grant the driver asked for is kept — nothing runs on the car
     * screen until it is allowed — but it is now *askable from the seat* rather
     * than only from a settings screen on the phone. Choosing an app in a picker
     * is itself a deliberate act, so one confirmation is the whole ceremony.
     */
    private fun chooseOrAsk(app: TemplateApp) {
        if (app.allowed(appContext)) {
            open(app)
            return
        }
        val shell = CarShell.active()
        if (shell == null) {
            // No car screen to ask on. Allowing silently would defeat the gate,
            // so this says what is missing instead -- and shows the picker, or
            // the pane is left with nothing in it at all. `createView` builds an
            // empty container; until `open` or `renderPicker` runs there is
            // literally nothing to look at and no control to recover with.
            onStep("car app: ${app.label} is not allowed and there is no screen to ask on")
            renderPicker()
            return
        }
        shell.confirm(
            title = "Allow ${app.label}?",
            detail = "It will draw its own interface on the car screen. You can " +
                "change this in Headway on the phone.",
            confirmLabel = "Allow",
            // Same reason: "Not now" and closing the sheet both have to leave
            // the driver something. The picker is the honest answer -- the app
            // they had pinned is in it, still askable.
            onDeclined = { if (running) renderPicker() },
        ) {
            // The sheet outlives the tile: any `render()` -- a divider drag, a
            // tab switch, a theme change, a layout edited from the phone --
            // stops every tile and builds new ones, while this callback is still
            // waiting on the driver. Opening from a stopped tile would bind a
            // third-party car-app service into a view that is no longer in the
            // tree, with nothing left to close it for the rest of the drive.
            //
            // The grant is still worth recording: the driver said yes, and the
            // tile that replaced this one reads the same setting.
            HeadwaySettings.setAllowedApps(
                appContext,
                AllowedApps.allow(HeadwaySettings.allowedApps(appContext), app.packageName),
            )
            onStep("car app: ${app.label} allowed from the car screen")
            if (!running) {
                onStep("car app: ${app.label} was allowed after its pane went away")
                return@confirm
            }
            apps = TemplateApps.installed(appContext)
            open(app)
        }
    }

    /**
     * What to say when no app publishes a car interface.
     *
     * Names the *media* apps if there are any, because they are the reason a
     * driver thinks this is broken. A music or podcast player's "Android Auto
     * screen" is a `MediaBrowserService` browse tree, not an
     * `androidx.car.app` template tree — there is no media category in the Car
     * App Library at all — so it can never resolve here however well it
     * supports Android Auto. A driver asked exactly that about Symfonium.
     *
     * Widening discovery is not the answer: `CarAppSession` binds
     * `Intent(CarAppService.SERVICE_INTERFACE)`, so a media app listed here
     * would be a row that can only ever fail to open. Pointing at the pane that
     * does host it is.
     */
    private fun noCarAppsMessage(): String {
        val media = runCatching { MediaApps.installed(appContext) }.getOrDefault(emptyList())
            .filterNot { candidate -> apps.any { it.packageName == candidate.packageName } }
        if (media.isEmpty()) {
            return "No app on this phone publishes a car interface.\n" +
                "Maps, messengers and points-of-interest apps that do appear here."
        }
        val named = media.take(MEDIA_NAMES_SHOWN).joinToString { it.label }
        val rest = media.size - MEDIA_NAMES_SHOWN
        return "No app on this phone publishes a car interface.\n\n" +
            named + (if (rest > 0) " and $rest more" else "") +
            " play music through Android Auto, but a music app's car screen is a " +
            "different thing that Headway shows in a Music and podcasts panel. " +
            "Add one of those instead."
    }

    private fun open(app: TemplateApp) {
        closeSession()
        val frame = stack ?: return
        chosen = app
        // Before the session, not after: opening can fail, and a driver whose
        // chosen app refuses should still find it selected rather than being
        // handed the picker again with no memory of what they tried.
        runCatching { onChosen(app.service) }
        // The scroller as well as the list inside it. Hiding only the inner
        // LinearLayout left a full-screen VISIBLE ScrollView sitting above the
        // map surface, and a ScrollView consumes ACTION_DOWN -- so pan, pinch
        // and tap never reached `CarAppSurfaceView.onTouchEvent` and the map was
        // an image rather than a map.
        picker?.visibility = View.GONE
        pickerScroller?.visibility = View.GONE

        val next = CarAppSession(appContext, app, onStep)
        val drawing = TemplateRenderer(frame.context, next, onStep) { leave() }
        session = next
        renderer = drawing
        frame.addView(
            drawing.view,
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT),
        )
        next.connect(listener)
    }

    private fun closeSession() {
        renderer?.release()
        renderer?.view?.let { view -> (view.parent as? FrameLayout)?.removeView(view) }
        renderer = null
        session?.close()
        session = null
        detachSurface()
    }

    private fun leave() {
        chosen = null
        closeSession()
        renderPicker()
    }

    // --- painting ----------------------------------------------------------

    private fun paint(state: HostState, template: Template?) {
        // The app called finishCarApp(). It is done, and the honest thing to
        // show is the picker rather than the last screen of a shut-down app.
        if (state == HostState.IDLE) {
            leave()
            return
        }
        val drawing = renderer ?: return
        drawing.render(state, template)
        // The surface has to be re-parented after every render, because the
        // renderer rebuilds its column from scratch and would otherwise leave
        // the map inside a vertical stack where it is zero pixels tall.
        attachSurface()
    }

    /**
     * Puts the app's map surface behind everything, once.
     *
     * Idempotent, and that is the whole point: re-parenting a `SurfaceView`
     * destroys its surface and hands the app a new one, so doing it on every
     * template refresh would tear a navigating app's map down once a second.
     * The renderer never touches the parenting for the same reason.
     */
    private fun attachSurface() {
        val frame = stack ?: return
        val surface = renderer?.mapSurface() ?: return
        if (surface.parent === frame) return
        (surface.parent as? android.view.ViewGroup)?.removeView(surface)
        // Index 0: behind the picker and behind the template column.
        frame.addView(surface, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun detachSurface() {
        val frame = stack ?: return
        // Any leftover SurfaceView from a session that has gone; leaving one
        // attached holds a Surface the app still believes it owns.
        for (index in frame.childCount - 1 downTo 0) {
            val child = frame.getChildAt(index)
            if (child is dev.headway.app.carapp.CarAppSurfaceView) frame.removeViewAt(index)
        }
    }

    private fun renderPicker() {
        val list = picker ?: return
        val context = list.context
        // Unconditionally visible. open() hides it and only leave() used to
        // show it again, so a stop()/start() cycle while an app was open filled
        // an invisible view and left the pane a black rectangle with no app, no
        // picker and no control of any kind.
        list.visibility = View.VISIBLE
        // And its scroller, which `open()` hides so its touches stop swallowing
        // the map's. Showing one without the other leaves the picker built and
        // unreachable.
        pickerScroller?.visibility = View.VISIBLE
        list.removeAllViews()

        // Said before a single bind is attempted. On a build that cannot hold
        // the renderer permission every one of these apps will refuse, and the
        // driver's remedy is a different APK rather than a different app -- see
        // CarHostCapability. Offering the list anyway spends a bind and a
        // handshake per tap to arrive at that same sentence.
        val blocked = CarHostCapability.explain(context)
        if (blocked != null) {
            list.gravity = Gravity.CENTER
            list.addView(CarStyle.emptyState(context, blocked))
            return
        }
        if (apps.isEmpty()) {
            list.gravity = Gravity.CENTER
            // `emptyState` and not `label`: `CarStyle.label` is one line with an
            // ellipsis, so the sentence naming the remedy is exactly the part
            // that would be cut off on an 800-pixel panel.
            list.addView(CarStyle.emptyState(context, noCarAppsMessage()))
            return
        }
        list.gravity = Gravity.TOP
        list.addView(
            CarStyle.label(context, 17f, CarStyle.TEXT, bold = true).apply {
                text = "Car apps"
                val gap = CarStyle.gutter(context)
                setPadding(gap, gap, gap, gap / 2)
            },
        )
        apps.forEach { app -> list.addView(appRow(context, app)) }
    }

    private fun appRow(context: Context, app: TemplateApp): View {
        val gap = CarStyle.gutter(context)
        val target = CarStyle.dp(context, CarStyle.PRIMARY_TARGET_DP)
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = target
            setPadding(gap, gap / 2, gap, gap / 2)
            isFocusable = true
            contentDescription = app.label
            Headway.pressable(this, CarStyle.radius(context)) { chooseOrAsk(app) }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = gap / 4
            }
        }
        val size = (target * 0.72f).toInt()
        line.addView(
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageDrawable(app.icon(context))
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = gap }
            },
        )
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(CarStyle.label(context, 16f, CarStyle.TEXT).apply { text = app.label })
        // An app that still needs a grant is listed and *marked*, never hidden.
        // Hiding it was the previous behaviour and it made the gate look like a
        // missing feature: a driver with five car apps saw one, with nothing on
        // the car screen saying why or how to change it.
        val detail = if (app.allowed(appContext)) {
            app.describeCategories()
        } else {
            "Tap to allow on the car screen"
        }
        column.addView(
            CarStyle.label(context, 13f, CarStyle.DIM).apply { text = detail },
        )
        line.addView(column, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        line.addView(
            CarStyle.label(context, 20f, CarStyle.ACCENT).apply {
                text = "›"
                gravity = Gravity.CENTER
            },
        )
        return line
    }
}

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
import dev.headway.app.carapp.HostState
import dev.headway.app.carapp.TemplateApp
import dev.headway.app.carapp.TemplateApps
import dev.headway.app.carapp.TemplateRenderer
import dev.headway.app.dash.DashTile
import dev.headway.app.ui.theme.Headway
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
) : DashTile {

    private val appContext: Context = context.applicationContext

    override val kind: String = PaneKind.CAR_APP

    /** Everything: the map surface at the back, the template in front. */
    private var stack: FrameLayout? = null
    private var picker: LinearLayout? = null

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
        frame.addView(
            ScrollView(context).apply {
                isFillViewport = true
                addView(list, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            },
        )
        stack = frame
        picker = list
        return frame
    }

    override fun start() {
        if (running) return
        running = true
        apps = TemplateApps.installed(appContext)
        val pinned = argument
            ?.let { ComponentName.unflattenFromString(it) }
            ?.let { component -> apps.firstOrNull { it.service == component } }
        val wanted = chosen ?: pinned
        if (wanted != null) open(wanted) else renderPicker()
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

    private fun open(app: TemplateApp) {
        closeSession()
        val frame = stack ?: return
        chosen = app
        picker?.visibility = View.GONE

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
        list.removeAllViews()

        if (apps.isEmpty()) {
            list.gravity = Gravity.CENTER
            list.addView(
                CarStyle.emptyState(
                    context,
                    "No app on this phone offers a car interface.\n" +
                        "Apps that do — maps, podcast players, messengers — appear here " +
                        "and are drawn by Headway rather than mirrored.",
                ),
            )
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
            Headway.pressable(this, CarStyle.radius(context)) { open(app) }
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
        column.addView(
            CarStyle.label(context, 13f, CarStyle.DIM).apply { text = app.describeCategories() },
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

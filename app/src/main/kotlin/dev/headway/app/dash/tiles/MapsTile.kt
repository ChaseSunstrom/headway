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

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import dev.headway.app.dash.CarShell
import dev.headway.app.dash.DashTile
import dev.headway.app.nav.NavigationFeed
import dev.headway.app.nav.NavigationStep
import dev.headway.app.ui.HeadwaySettings
import dev.headway.app.ui.theme.Headway
import dev.headway.dash.PaneKind

/**
 * The next turn, and one tap to the map itself.
 *
 * ## What this pane can and cannot be
 *
 * It cannot be a map. A map is another app's rendering, and no unprivileged app
 * can place another app's window inside a pane of its own — ADR 0004 settles
 * that on three independent grounds and none of them has moved. What it *can*
 * be is the two things a driver actually needs from a map on a dashboard:
 *
 * 1. **The next instruction, large.** Drawn from [NavigationFeed], which reads
 *    the ongoing notification every navigator publishes. That is a model, and
 *    rendering somebody else's model at the car's own size is exactly what
 *    Android Auto does.
 * 2. **A way to the map itself.** One tap hands the whole car screen to the
 *    driver's chosen map app, at full size, with the floating Home button to
 *    come back. That route renders *any* map app — Organic Maps, HERE WeGo,
 *    Google Maps, OsmAnd — because it is the app drawing, not Headway.
 *
 * ## Choosing the map app
 *
 * By what resolves `geo:`, which is the platform's own definition of "a map
 * app" and needs no allowlist to maintain. The driver's choice is remembered;
 * with none made, the first `geo:` handler is offered and the pane says which.
 */
class MapsTile(
    context: Context,
    private val onStep: (String) -> Unit = {},
) : DashTile {

    private val appContext: Context = context.applicationContext

    override val kind: String = PaneKind.MAPS

    private var instruction: TextView? = null
    private var distance: TextView? = null
    private var eta: TextView? = null
    private var idle: View? = null
    private var live: View? = null
    private var openButton: TextView? = null

    private var running = false
    private var showing: NavigationStep? = null

    private val listener = NavigationFeed.Listener { step ->
        showing = step
        render()
    }

    override fun createView(context: Context): View {
        val panel = CarStyle.panel(context)
        val gap = CarStyle.gutter(context)

        // --- navigating -------------------------------------------------------
        val distanceView = CarStyle.label(context, 34f, CarStyle.ACCENT, bold = true)
        val instructionView = CarStyle.label(context, 22f, CarStyle.TEXT, bold = true).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val etaView = CarStyle.label(context, 15f, CarStyle.DIM)
        val liveColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(distanceView)
            addView(instructionView)
            addView(etaView)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        }

        // --- not navigating ---------------------------------------------------
        val idleColumn = CarStyle.emptyState(
            context,
            "No route running.\nOpen your map app and start one.",
        ).apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f) }

        val open = CarStyle.button(context, "Open map", emphasised = true) { openMap() }.apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = gap
            }
        }

        panel.addView(liveColumn)
        panel.addView(idleColumn)
        panel.addView(open)

        distance = distanceView
        instruction = instructionView
        eta = etaView
        live = liveColumn
        idle = idleColumn
        openButton = open
        render()
        return panel
    }

    override fun start() {
        if (running) return
        running = true
        NavigationFeed.observe(listener)
        render()
    }

    override fun stop() {
        if (!running) return
        running = false
        NavigationFeed.unobserve(listener)
    }

    override fun describe(): String {
        val app = chosenMapApp()?.first ?: "no map app"
        val step = showing
            ?: return "maps: idle, $app"
        return "maps: '${step.instruction}' in ${step.distance} from ${step.source}"
    }

    // --- rendering ---------------------------------------------------------

    private fun render() {
        val liveColumn = live ?: return
        val idleColumn = idle ?: return
        val step = showing
        if (step == null) {
            liveColumn.visibility = View.GONE
            idleColumn.visibility = View.VISIBLE
        } else {
            idleColumn.visibility = View.GONE
            if (liveColumn.visibility != View.VISIBLE) {
                liveColumn.visibility = View.VISIBLE
                Headway.revealIn(liveColumn)
            }
            distance?.text = step.distance.orEmpty()
            instruction?.text = step.instruction
            eta?.let {
                it.text = step.eta.orEmpty()
                it.visibility = if (step.eta.isNullOrBlank()) View.GONE else View.VISIBLE
            }
        }
        openButton?.text = chosenMapApp()?.let { "Open ${it.second}" } ?: "No map app installed"
    }

    // --- the map app -------------------------------------------------------

    private fun openMap() {
        val chosen = chosenMapApp() ?: run {
            onStep("maps: nothing on this phone handles a geo: link")
            return
        }
        val intent = appContext.packageManager.getLaunchIntentForPackage(chosen.first) ?: run {
            onStep("maps: ${chosen.second} can no longer be launched")
            return
        }
        // Into the app pane, where the map is drawn by the map app itself and
        // the panes around it keep working. The shell owns the decision; before
        // ADR 0010 this handed the whole car screen to a capture and the
        // dashboard ceased to exist for the rest of the journey.
        val shell = CarShell.active()
        if (shell != null) {
            shell.openApp(chosen.first)
            onStep("maps: opened ${chosen.second} in the app pane")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (!startOnPhoneDisplay(appContext, intent, onStep)) return
        onStep("maps: opened ${chosen.second}")
    }

    /**
     * The driver's map app, or the first one that handles `geo:`.
     *
     * `geo:` rather than a hardcoded list, because the platform already knows
     * what a map app is and a list would go stale the first time someone
     * installed one nobody thought of.
     */
    private fun chosenMapApp(): Pair<String, String>? {
        val candidates = mapApps(appContext)
        if (candidates.isEmpty()) return null
        val preferred = HeadwaySettings.of(appContext)
            .getString(HeadwaySettings.KEY_MAP_APP, null)
        return candidates.firstOrNull { it.first == preferred } ?: candidates.first()
    }

    companion object {

        /**
         * Everything that can show a point on a map, by label.
         *
         * A bare `geo:0,0` query: every map app declares a handler for it and
         * nothing else does, so this is both complete and self-maintaining.
         */
        fun mapApps(context: Context): List<Pair<String, String>> {
            val packages = context.packageManager
            val query = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0"))
            val resolved: List<ResolveInfo> = runCatching {
                packages.queryIntentActivities(query, PackageManager.ResolveInfoFlags.of(0L))
            }.getOrNull().orEmpty()
            return resolved
                .asSequence()
                .filter { it.activityInfo?.packageName != context.packageName }
                .mapNotNull { info ->
                    val name = info.activityInfo?.packageName ?: return@mapNotNull null
                    name to info.loadLabel(packages).toString()
                }
                .distinctBy { it.first }
                .sortedBy { it.second.lowercase() }
                .toList()
        }

        /**
         * Starts navigation to a place, in the driver's map app.
         *
         * `geo:0,0?q=` is the documented search form and every map app
         * implements it, which makes this the one navigation action Headway can
         * offer that works everywhere — including from a voice command.
         */
        fun navigateTo(context: Context, query: String, onStep: (String) -> Unit): Boolean {
            val uri = Uri.parse("geo:0,0?q=" + Uri.encode(query))
            val intent = Intent(Intent.ACTION_VIEW, uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            HeadwaySettings.of(context).getString(HeadwaySettings.KEY_MAP_APP, null)
                ?.let { intent.setPackage(it) }
            val started = runCatching { context.startActivity(intent) }
            if (started.isFailure) {
                // The stored package may have been uninstalled; retry unpinned
                // rather than fail, because the driver asked for a destination
                // and any map app is better than none.
                intent.setPackage(null)
                val retried = runCatching { context.startActivity(intent) }
                if (retried.isFailure) {
                    onStep("maps: nothing could navigate to '$query'")
                    return false
                }
            }
            onStep("maps: navigating to '$query'")
            // The map app is now in front on the app display; bring that pane
            // forward if the layout on screen has one.
            intent.`package`?.let { CarShell.active()?.openApp(it) }
            return true
        }
    }
}

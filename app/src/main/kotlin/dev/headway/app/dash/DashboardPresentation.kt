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

package dev.headway.app.dash

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import dev.headway.app.dash.tiles.ClockTile
import dev.headway.app.dash.tiles.LauncherTile
import dev.headway.app.dash.tiles.MessagesTile
import dev.headway.app.dash.tiles.NowPlayingTile
import dev.headway.app.dash.tiles.WidgetTile
import dev.headway.app.ui.theme.CarMetrics
import dev.headway.app.ui.theme.Headway
import dev.headway.app.ui.theme.HeadwayMark
import dev.headway.app.video.CarSurfaceMode

/**
 * The dashboard, drawn on the car's own display.
 *
 * ## Why a `Presentation`
 *
 * This is the one window type that can reach a virtual display an unprivileged
 * app created. `ActivityTaskSupervisor.isCallerAllowedToLaunchOnDisplay` turns
 * away the *first* activity onto an untrusted display — the `ACTIVITY_EMBEDDING`
 * gate is waived only once the caller already has an activity there, which on a
 * freshly created display is never true, and the `getOwnerUid()` branch sits
 * after that gate rather than instead of it. A `Presentation` is a `Dialog`: a
 * window added through `WindowManager` on a display this process owns, which
 * never enters the activity-launch path. ADR 0004 Finding 4 has the derivation
 * and the correction history.
 *
 * ## Why it is laid out in car pixels
 *
 * The display is created at the head unit's advertised resolution and density,
 * so this window's own pixels *are* car pixels. Every size here comes from
 * [CarMetrics], which computes 48 dp at the car's density rather than the
 * phone's. Under mirroring the two differed by a factor of five, which is what
 * made the old surface unusable; here they are the same number by construction.
 *
 * ## Touch
 *
 * No touch listener, no accessibility service, no coordinate transform.
 * [CarSurface.deliver] synthesises a `MotionEvent` at the car's own coordinates
 * and calls `dispatchTouchEvent` on this dialog. The view tree handles it as it
 * would a finger, because as far as the framework is concerned that is what it
 * is.
 */
class DashboardPresentation(
    outerContext: Context,
    display: Display,
    private val metrics: CarMetrics,
    private val onStep: (String) -> Unit = {},
) : Presentation(outerContext, display) {

    private lateinit var root: FrameLayout

    private val store: DashLayoutStore by lazy { DashLayoutStore.of(context) }

    private var layout: DashLayout = DashLayoutStore.DEFAULT

    /** Live tiles, so they can be stopped. See [DashTile]. */
    private val live = mutableListOf<DashTile>()

    /** Shown until the first layout is up, so the car never sees a black frame. */
    private var splash: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(context).apply { setBackgroundColor(Headway.GROUND) }
        setContentView(root)
        // Dialogs dim what is behind them by default. There is nothing behind
        // this one but the display's own black, and the dim costs a full-screen
        // translucent layer in every encoded frame.
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        window?.setLayout(MATCH_PARENT, MATCH_PARENT)
        // A Dialog dismisses itself on BACK and on a touch outside its bounds.
        // Neither is a gesture anyone would make on purpose here, and both end
        // with the car showing a display that has content but no window — a
        // black screen with no way back, mid-drive. The window is MATCH_PARENT
        // so "outside" should not exist, which is exactly the kind of should
        // that is worth one line of insurance.
        setCancelable(false)
        setCanceledOnTouchOutside(false)

        showSplash()
        layout = store.active()
        render()
    }

    override fun onStart() {
        super.onStart()
        live.forEach { runCatching { it.start() } }
        onStep("dashboard on the car display: ${describe()}")
    }

    override fun onStop() {
        // Tiles observe media sessions, notification streams and widget hosts.
        // One left running after the window goes is a listener for the life of
        // the process, and the only symptom is battery.
        live.forEach { runCatching { it.stop() } }
        super.onStop()
    }

    fun describe(): String = buildString {
        append(metrics.describe())
        append("; ")
        append(live.joinToString("; ") { runCatching { it.describe() }.getOrDefault(it.kind) })
    }

    /**
     * The mark, alone on the ground, until the layout is built.
     *
     * The head unit shows whatever the first decoded frame contains, and it
     * arrives within a frame or two of the surface being attached. Without this
     * the car's first impression of Headway is a black rectangle for as long as
     * the tiles take to inflate and their permissions to resolve.
     */
    private fun showSplash() {
        val holder = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }
        val mark = HeadwayMark(context).apply {
            travelling = true
            layoutParams = LinearLayout.LayoutParams(metrics.unit * 3, metrics.unit * 2)
        }
        holder.addView(mark)
        holder.addView(
            Headway.caption(context, metrics.unit, "Headway").apply {
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    topMargin = metrics.gutter
                }
            }
        )
        root.addView(holder)
        splash = holder
    }

    private fun clearSplash() {
        val current = splash ?: return
        splash = null
        current.animate()
            .alpha(0f)
            .setDuration(Headway.SETTLE_MILLIS)
            .withEndAction { root.removeView(current) }
            .start()
    }

    private fun render() {
        live.forEach { runCatching { it.stop() } }
        live.clear()

        val tree = build(layout.root)
        tree.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        root.addView(tree, 0)
        Headway.revealIn(tree)
        clearSplash()
        if (isShowing) live.forEach { runCatching { it.start() } }
    }

    /**
     * Turns a node into views.
     *
     * A [DashNode.Split] is a weighted `LinearLayout`, so a ratio is a weight
     * and nothing has to be measured by hand. A [DashNode.Leaf] is a tile's own
     * view inside a panel.
     */
    private fun build(node: DashNode): View = when (node) {
        is DashNode.Split -> LinearLayout(context).apply {
            orientation = if (node.orientation == DashNode.Orientation.HORIZONTAL) {
                LinearLayout.HORIZONTAL
            } else {
                LinearLayout.VERTICAL
            }
            weightSum = 1f
            val first = build(node.first)
            val second = build(node.second)
            addView(first, weighted(node.clampedRatio, node.orientation))
            addView(second, weighted(1f - node.clampedRatio, node.orientation))
        }

        is DashNode.Leaf -> buildLeaf(node)
    }

    private fun weighted(
        weight: Float,
        orientation: DashNode.Orientation,
    ): LinearLayout.LayoutParams =
        if (orientation == DashNode.Orientation.HORIZONTAL) {
            LinearLayout.LayoutParams(0, MATCH_PARENT, weight)
        } else {
            LinearLayout.LayoutParams(MATCH_PARENT, 0, weight)
        }

    /** One pane: a rounded panel with a tile in it. */
    private fun buildLeaf(leaf: DashNode.Leaf): View {
        val pane = FrameLayout(context).apply {
            background = Headway.panel(metrics.radius)
            val inset = metrics.gutter / 2
            (layoutParams as? LinearLayout.LayoutParams)?.setMargins(inset, inset, inset, inset)
        }
        val tile = tileFor(leaf)
        if (tile == null) {
            pane.addView(
                Headway.caption(context, metrics.unit, "No tile for '${leaf.kind}'").apply {
                    gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
                        val g = metrics.gutter
                        setMargins(g, g, g, g)
                    }
                }
            )
            return pane
        }
        live += tile
        val view = tile.createView(context)
        view.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        pane.addView(view)
        return pane
    }

    /**
     * Builds a tile, or null for one this display cannot show.
     *
     * ## Why a mirror pane becomes the app grid
     *
     * A [DashTile.Kind.MIRROR] leaf asks for a hole in the dashboard through
     * which display 0 shows. On this display there is no such hole and there
     * cannot be one: it renders Headway's own content, and another app's window
     * cannot be placed on it at all (ADR 0004). The first version of this class
     * drew a note saying so, which meant the *default* layout — whose largest
     * pane is a mirror — came up as a paragraph of apology occupying 62% of the
     * car screen.
     *
     * Mirroring is now a mode rather than a pane: [CarSurfaceMode.MIRROR] gives
     * the whole screen to display 0, and the driver gets there by tapping an app.
     * So the right thing to put where a mirror was asked for is the app grid,
     * which is the door to exactly that. A saved layout written before the mode
     * existed keeps working, and does something useful rather than explaining
     * itself.
     *
     * Deliberately separate from `DashboardActivity`'s factory: that one runs on
     * display 0, where a mirror pane genuinely is a hole onto the screen it is
     * already drawn on.
     */
    private fun tileFor(leaf: DashNode.Leaf): DashTile? = when (leaf.kind) {
        DashTile.Kind.NOW_PLAYING -> NowPlayingTile(context)
        DashTile.Kind.MESSAGES -> MessagesTile(context)
        DashTile.Kind.CLOCK -> ClockTile(context)
        DashTile.Kind.LAUNCHER, DashTile.Kind.MIRROR -> LauncherTile(onStep)
        DashTile.Kind.WIDGET -> WidgetTile.of(leaf, onStep)
        else -> null
    }
}

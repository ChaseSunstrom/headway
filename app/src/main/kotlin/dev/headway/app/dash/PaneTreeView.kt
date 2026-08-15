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

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import dev.headway.app.ui.theme.CarMetrics
import dev.headway.app.ui.theme.Headway
import dev.headway.dash.DashLayout
import dev.headway.dash.DashNode
import dev.headway.dash.DashPath
import dev.headway.dash.PaneKind

/**
 * Turns a [DashLayout] into views, with dividers the driver can drag and — when
 * the layout is unlocked — a way into every pane's menu.
 *
 * ## Why this is not part of the shell
 *
 * Because it used to be in two places and they drifted. `DashboardActivity` had
 * draggable dividers, a long-press pane menu and a kind picker; the
 * `Presentation` that actually draws the car screen had none of it, so every
 * editing feature Headway shipped was on the one surface the driver cannot see
 * while driving. Deleting the activity and lifting the tree-building here leaves
 * exactly one implementation, on the surface that is on the dashboard.
 *
 * ## Why nested `LinearLayout`s
 *
 * A binary split tree maps one-to-one onto nested weighted rows and columns, and
 * a weight *is* a [DashNode.Split.ratio]. Dragging a divider is then two float
 * assignments and a `requestLayout`, with no measure pass to write and no chance
 * of the drawn geometry disagreeing with the saved geometry. A bespoke
 * `ViewGroup` would save one view per split and cost a layout implementation
 * that has to be right on the first drive.
 *
 * ## Sizes come from the car
 *
 * Nothing here is in `dp`. [CarMetrics] computes 48 dp at the *head unit's*
 * density, and a divider is one whole touch target thick while editing — it is
 * the only control on this screen that has to be grabbed rather than tapped, by
 * a thumb, in a moving car. At rest it is a hairline, because a locked layout
 * has nothing to grab.
 */
class PaneTreeView(
    private val context: Context,
    private val metrics: CarMetrics,
    /** Builds the tile for a leaf, or null for a kind this build does not have. */
    private val tileFor: (DashNode.Leaf, DashPath) -> DashTile?,
    /** A divider was released at a new ratio; [path] names the split. */
    private val onRatioChanged: (DashPath, Float) -> Unit,
    /** A pane was tapped while editing. */
    private val onPaneTapped: (DashPath) -> Unit,
) {

    /** Every tile of the current build, so the shell can start and stop them. */
    val tiles: MutableList<DashTile> = mutableListOf()

    /** The tiles by path, for the shell's app-pane bookkeeping. */
    val tilesByPath: MutableMap<DashPath, DashTile> = mutableMapOf()

    private var editing = false

    fun build(layout: DashLayout, editing: Boolean): View {
        this.editing = editing
        tiles.clear()
        tilesByPath.clear()
        return node(layout.root, emptyList())
    }

    private fun node(node: DashNode, path: DashPath): View = when (node) {
        is DashNode.Split -> split(node, path)
        is DashNode.Leaf -> leaf(node, path)
    }

    private fun split(split: DashNode.Split, path: DashPath): View {
        val horizontal = split.orientation == DashNode.Orientation.HORIZONTAL
        val container = LinearLayout(context).apply {
            orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        val ratio = split.clampedRatio
        val first = node(split.first, path + false)
        val second = node(split.second, path + true)
        val divider = divider(split, path, container, horizontal)

        container.addView(first, weighted(ratio, horizontal))
        container.addView(divider)
        container.addView(second, weighted(1f - ratio, horizontal))
        return container
    }

    private fun weighted(weight: Float, horizontal: Boolean): LinearLayout.LayoutParams =
        if (horizontal) {
            LinearLayout.LayoutParams(0, MATCH_PARENT, weight)
        } else {
            LinearLayout.LayoutParams(MATCH_PARENT, 0, weight)
        }

    /**
     * The grab handle between two panes.
     *
     * Thin and inert on a locked layout, a whole touch target thick and marked
     * when unlocked. The width is part of the layout — the two panes divide
     * what is left after it — so a locked and an unlocked layout are not quite
     * the same geometry, which is the honest way round: the space the handle
     * needs is space the panes do not have while it is there.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun divider(
        split: DashNode.Split,
        path: DashPath,
        container: LinearLayout,
        horizontal: Boolean,
    ): View {
        val thickness = if (editing) metrics.unit / 2 else GUTTER_PX
        val view = View(context).apply {
            layoutParams = if (horizontal) {
                LinearLayout.LayoutParams(thickness, MATCH_PARENT)
            } else {
                LinearLayout.LayoutParams(MATCH_PARENT, thickness)
            }
            // A locked divider is a *gap*, not a rule. It used to be painted
            // `OUTLINE`, which drew a visible line between every pair of panes on
            // top of the gap the panes' own cards already leave -- a driver
            // reported it as "a weird line between panels" and they were right:
            // the panes are already separated by their card edges, so the line
            // was a second, thinner border with nothing to delimit.
            //
            // While editing it stays visible on purpose. Then it is a control,
            // and a control the driver cannot see is one they cannot drag.
            setBackgroundColor(if (editing) Headway.ACCENT_DIM else Headway.GROUND)
        }
        if (!editing) return view

        view.contentDescription = "Resize"
        var dragging = false
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    view.setBackgroundColor(Headway.ACCENT)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return@setOnTouchListener false
                    val ratio = ratioFrom(event, view, container, horizontal)
                    applyRatio(container, ratio, horizontal)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) return@setOnTouchListener false
                    dragging = false
                    view.setBackgroundColor(Headway.ACCENT_DIM)
                    val ratio = ratioFrom(event, view, container, horizontal)
                    applyRatio(container, ratio, horizontal)
                    // Written once, on release. Persisting every MOVE would
                    // write to storage sixty times a second on the thread that
                    // is drawing the car screen.
                    onRatioChanged(path, ratio)
                    true
                }

                else -> false
            }
        }
        return view
    }

    /**
     * Where the finger is, as a fraction of the container.
     *
     * The event's coordinates are relative to the divider, which is itself
     * moving, so they are converted to the container's frame first. Without
     * that the ratio drifts by the divider's own offset on every move and the
     * pane creeps away from the thumb.
     */
    private fun ratioFrom(
        event: MotionEvent,
        divider: View,
        container: LinearLayout,
        horizontal: Boolean,
    ): Float {
        val position: Float
        val extent: Int
        if (horizontal) {
            position = divider.x + event.x
            extent = container.width
        } else {
            position = divider.y + event.y
            extent = container.height
        }
        if (extent <= 0) return DashNode.MIN_RATIO
        return (position / extent).coerceIn(DashNode.MIN_RATIO, DashNode.MAX_RATIO)
    }

    private fun applyRatio(container: LinearLayout, ratio: Float, horizontal: Boolean) {
        if (container.childCount < 3) return
        val first = container.getChildAt(0).layoutParams as LinearLayout.LayoutParams
        val second = container.getChildAt(2).layoutParams as LinearLayout.LayoutParams
        first.weight = ratio
        second.weight = 1f - ratio
        if (horizontal) {
            first.width = 0
            second.width = 0
        } else {
            first.height = 0
            second.height = 0
        }
        container.requestLayout()
    }

    /** One pane: a rounded panel with a tile in it, and an edit affordance over it. */
    private fun leaf(leaf: DashNode.Leaf, path: DashPath): View {
        val pane = FrameLayout(context).apply {
            background = Headway.panel(metrics.radius)
            clipToOutline = true
        }
        val tile = tileFor(leaf, path)
        if (tile == null) {
            pane.addView(
                Headway.caption(context, metrics.unit, "No pane for '${leaf.kind}'").apply {
                    gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                },
            )
        } else {
            tiles += tile
            tilesByPath[path] = tile
            val view = tile.createView(context)
            view.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            pane.addView(view)
            tile.onEditingChanged(editing)
        }
        if (editing) pane.addView(editScrim(leaf, path))
        return pane
    }

    /**
     * What a pane looks like while the layout is unlocked.
     *
     * A translucent wash, the pane's name, and one instruction. It takes the
     * tap itself rather than letting it through, which is the point: while
     * editing, every pane is a button that opens its own menu, and a media
     * browser that started playing something because the driver meant to move
     * it would be the worst possible outcome of a layout edit.
     */
    private fun editScrim(leaf: DashNode.Leaf, path: DashPath): View {
        val scrim = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            background = Headway.panel(metrics.radius, fill = SCRIM, stroke = Headway.ACCENT)
            isClickable = true
            setOnClickListener { onPaneTapped(path) }
            contentDescription = "Edit ${PaneKind.describe(leaf.kind)}"
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER)
        }
        column.addView(
            Headway.title(context, metrics.unit, PaneKind.describe(leaf.kind)).apply {
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            },
        )
        column.addView(
            Headway.caption(context, metrics.unit, "Tap to change").apply {
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            },
        )
        scrim.addView(column)
        return scrim
    }

    private companion object {
        /**
         * The gap between two locked panes, in pixels.
         *
         * Wide enough to read as a seam and narrow enough not to waste a car
         * screen's very limited height. Painted in the dashboard's own ground
         * colour, so it is a space rather than a line.
         */
        const val GUTTER_PX = 2

        /** Dark enough to read a label over any tile, light enough to see which. */
        const val SCRIM = 0xC0101418.toInt()
    }
}

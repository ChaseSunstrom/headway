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

import org.json.JSONArray
import org.json.JSONObject

/**
 * What the car screen is divided into, and what goes in each division.
 *
 * ## Why a tree rather than a grid
 *
 * A grid fixes the number of cells up front and makes "give the map two thirds
 * and stack music above messages beside it" express as spans and gaps. A binary
 * split tree says that directly — every node is either a pane or a division of
 * two children at a ratio — and it is what every tiling window manager settles
 * on for the same reason: the operations a user actually performs (split this
 * pane, drag this divider, close this pane) are single edits on a tree and
 * awkward rewrites on a grid.
 *
 * It also matches what can be *drawn*. Each [Split] becomes one weighted
 * container with a draggable divider, and dragging is a change to one [ratio].
 *
 * ## Why this is Headway's own data and not the window manager's
 *
 * ADR 0004: no unprivileged app can set another app's window bounds — the APIs
 * SystemUI uses for that (`TaskOrganizer`, `WindowContainerTransaction`) are in
 * neither the public nor the `@SystemApi` SDK. So a saved layout can never
 * describe where another app's window goes. It describes where *Headway's own*
 * panes go and what each one renders, which is a thing no permission governs and
 * which persists trivially.
 */
sealed interface DashNode {

    /** A division of the available space between two children. */
    data class Split(
        val orientation: Orientation,
        /**
         * Fraction of the long axis given to [first], 0..1 exclusive.
         *
         * Clamped rather than validated on the way in: a divider dragged to the
         * edge should pin at the minimum, not throw in the middle of a gesture
         * on a car screen.
         */
        val ratio: Float,
        val first: DashNode,
        val second: DashNode,
    ) : DashNode {
        val clampedRatio: Float get() = ratio.coerceIn(MIN_RATIO, MAX_RATIO)
    }

    /** A pane showing one tile. */
    data class Leaf(
        /** Which kind of tile; see `DashTile.Kind`. */
        val kind: String,
        /**
         * What the tile is bound to, when it needs binding — a package name for
         * a mirror or launcher shortcut, a widget id for a widget. Null for
         * tiles that are complete in themselves, like the clock.
         */
        val argument: String? = null,
    ) : DashNode

    enum class Orientation { HORIZONTAL, VERTICAL }

    companion object {
        /**
         * How small a pane may be dragged.
         *
         * Fifteen percent of an 800-pixel car panel is 120 pixels, which is
         * about one 48 dp touch target at car density — below that a pane is
         * not a pane, it is a stripe nobody can hit.
         */
        const val MIN_RATIO = 0.15f
        const val MAX_RATIO = 0.85f
    }
}

/**
 * A named arrangement the driver can save and come back to.
 *
 * @param name what the driver called it; also its key.
 * @param root the pane tree.
 */
data class DashLayout(val name: String, val root: DashNode) {

    /** Every leaf, left-to-right and top-to-bottom. */
    fun leaves(): List<DashNode.Leaf> = collect(root)

    private fun collect(node: DashNode): List<DashNode.Leaf> = when (node) {
        is DashNode.Leaf -> listOf(node)
        is DashNode.Split -> collect(node.first) + collect(node.second)
    }

    /**
     * Returns a copy with the divider at [path] moved to [ratio].
     *
     * A path is the sequence of child choices from the root — false for
     * [DashNode.Split.first], true for second — which is the only stable way to
     * name an interior node in an immutable tree. Index-based addressing would
     * shift under every structural edit.
     */
    fun withRatio(path: List<Boolean>, ratio: Float): DashLayout =
        copy(root = replaceRatio(root, path, ratio))

    private fun replaceRatio(node: DashNode, path: List<Boolean>, ratio: Float): DashNode {
        if (node !is DashNode.Split) return node
        if (path.isEmpty()) return node.copy(ratio = ratio.coerceIn(DashNode.MIN_RATIO, DashNode.MAX_RATIO))
        val rest = path.drop(1)
        return if (path.first()) {
            node.copy(second = replaceRatio(node.second, rest, ratio))
        } else {
            node.copy(first = replaceRatio(node.first, rest, ratio))
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put(KEY_NAME, name)
        .put(KEY_ROOT, encode(root))

    private fun encode(node: DashNode): JSONObject = when (node) {
        is DashNode.Leaf -> JSONObject()
            .put(KEY_TYPE, TYPE_LEAF)
            .put(KEY_KIND, node.kind)
            .apply { node.argument?.let { put(KEY_ARGUMENT, it) } }

        is DashNode.Split -> JSONObject()
            .put(KEY_TYPE, TYPE_SPLIT)
            .put(KEY_ORIENTATION, node.orientation.name)
            .put(KEY_RATIO, node.ratio.toDouble())
            .put(KEY_FIRST, encode(node.first))
            .put(KEY_SECOND, encode(node.second))
    }

    companion object {
        private const val KEY_NAME = "name"
        private const val KEY_ROOT = "root"
        private const val KEY_TYPE = "type"
        private const val KEY_KIND = "kind"
        private const val KEY_ARGUMENT = "arg"
        private const val KEY_ORIENTATION = "orientation"
        private const val KEY_RATIO = "ratio"
        private const val KEY_FIRST = "first"
        private const val KEY_SECOND = "second"
        private const val TYPE_LEAF = "leaf"
        private const val TYPE_SPLIT = "split"

        /**
         * Reads a layout back, or null when the JSON is not one.
         *
         * Null rather than an exception because this parses user-editable
         * storage that also survives app updates: a layout saved by an older
         * build naming a tile kind this one no longer has should cost the
         * driver their layout, not their dashboard.
         */
        fun fromJson(json: JSONObject): DashLayout? = runCatching {
            val root = decode(json.getJSONObject(KEY_ROOT)) ?: return null
            DashLayout(json.getString(KEY_NAME), root)
        }.getOrNull()

        private fun decode(json: JSONObject): DashNode? = when (json.optString(KEY_TYPE)) {
            TYPE_LEAF -> DashNode.Leaf(
                kind = json.getString(KEY_KIND),
                argument = json.optString(KEY_ARGUMENT).takeIf { it.isNotEmpty() },
            )

            TYPE_SPLIT -> {
                val first = decode(json.getJSONObject(KEY_FIRST))
                val second = decode(json.getJSONObject(KEY_SECOND))
                val orientation = runCatching {
                    DashNode.Orientation.valueOf(json.getString(KEY_ORIENTATION))
                }.getOrNull()
                if (first == null || second == null || orientation == null) {
                    null
                } else {
                    DashNode.Split(
                        orientation = orientation,
                        ratio = json.optDouble(KEY_RATIO, 0.5).toFloat(),
                        first = first,
                        second = second,
                    )
                }
            }

            else -> null
        }

        /** Serialises a whole set of layouts, for [DashLayoutStore]. */
        fun encodeAll(layouts: List<DashLayout>): String {
            val array = JSONArray()
            layouts.forEach { array.put(it.toJson()) }
            return JSONObject().put(KEY_LAYOUTS, array).toString()
        }

        /** The inverse of [encodeAll]; unreadable entries are skipped, not fatal. */
        fun decodeAll(text: String?): List<DashLayout> {
            if (text.isNullOrBlank()) return emptyList()
            val array = runCatching {
                JSONObject(text).getJSONArray(KEY_LAYOUTS)
            }.getOrNull() ?: return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                runCatching { fromJson(array.getJSONObject(index)) }.getOrNull()
            }
        }

        private const val KEY_LAYOUTS = "layouts"
    }
}

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

package dev.headway.dash

import org.json.JSONArray
import org.json.JSONObject

/**
 * Which child of a [DashNode.Split] to descend into: `false` is
 * [DashNode.Split.first], `true` is [DashNode.Split.second].
 *
 * A path is the only stable way to name a node in an immutable tree. Index-based
 * addressing — "the third pane" — shifts under every structural edit, so a
 * driver who splits a pane would find their next drag landing somewhere else.
 */
typealias DashPath = List<Boolean>

/**
 * What the car screen is divided into, and what goes in each division.
 *
 * ## Why a tree rather than a grid
 *
 * A grid fixes the number of cells up front and makes "give the map two thirds
 * and stack music above messages beside it" express as spans and gaps. A binary
 * split tree says that directly — every node is either a pane or a division of
 * two children at a ratio — and it is what every tiling window manager settles
 * on for the same reason: the operations a driver actually performs (split this
 * pane, drag this divider, close this pane) are single edits on a tree and
 * awkward rewrites on a grid. It is also what makes "any number of panels" true
 * without a size limit anywhere: a pane splits into two, either of those splits
 * again, and the drawing code never learns a new case.
 *
 * It also matches what can be *drawn*. Each [Split] becomes one weighted
 * container with a draggable divider, and dragging is a change to one [ratio].
 *
 * ## Why this is Headway's own data and not the window manager's
 *
 * ADR 0004: no unprivileged app can set another app's window bounds — the APIs
 * SystemUI uses for that (`TaskOrganizer`, `WindowContainerTransaction`) are in
 * neither the public nor the `@SystemApi` SDK. So a saved layout can never
 * describe where another app's *window* goes. It describes where Headway's own
 * panes go and what each one renders, which is a thing no permission governs.
 *
 * A pane may still show a real third-party app: ADR 0010's app pane points the
 * session's one projection at that pane's surface, so the app's own pixels
 * arrive as *content* inside a pane Headway placed. The tree still owns the
 * geometry; the app never does.
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
        /** Which kind of tile; see [PaneKind]. */
        val kind: String,
        /**
         * What the tile is bound to, when it needs binding — a package name for
         * an app pane or launcher shortcut, a widget id for a widget, a
         * flattened `ComponentName` for a car app. Null for tiles that are
         * complete in themselves, like the clock.
         */
        val argument: String? = null,
    ) : DashNode

    enum class Orientation {
        HORIZONTAL,
        VERTICAL;

        fun flipped(): Orientation = if (this == HORIZONTAL) VERTICAL else HORIZONTAL
    }

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

/** A leaf together with the path that reaches it. */
data class PanePath(val path: DashPath, val leaf: DashNode.Leaf)

/**
 * A named arrangement the driver can save, edit, lock and come back to.
 *
 * @param name what the driver called it; also its key, and the label on its rail
 *   button.
 * @param root the pane tree.
 * @param locked whether the car screen refuses to edit it. Default true for
 *   anything the driver saves, because the failure this exists to prevent is a
 *   layout rearranged by a thumb steadying itself on a dashboard at speed.
 */
data class DashLayout(
    val name: String,
    val root: DashNode,
    val locked: Boolean = true,
) {

    /** Every leaf, left-to-right and top-to-bottom. */
    fun leaves(): List<DashNode.Leaf> = leafPaths().map { it.leaf }

    /** How many panes this layout has. */
    val paneCount: Int get() = leafPaths().size

    /** Every leaf with the path that names it, in draw order. */
    fun leafPaths(): List<PanePath> = collect(root, emptyList())

    private fun collect(node: DashNode, here: DashPath): List<PanePath> = when (node) {
        is DashNode.Leaf -> listOf(PanePath(here, node))
        is DashNode.Split -> collect(node.first, here + false) + collect(node.second, here + true)
    }

    /** The node at [path], or null when the path does not name one. */
    fun nodeAt(path: DashPath): DashNode? {
        var node: DashNode = root
        for (step in path) {
            val split = node as? DashNode.Split ?: return null
            node = if (step) split.second else split.first
        }
        return node
    }

    /** Returns a copy with the node at [path] replaced by [replacement]. */
    fun replaceAt(path: DashPath, replacement: DashNode): DashLayout =
        copy(root = replaceNode(root, path, replacement))

    private fun replaceNode(node: DashNode, path: DashPath, replacement: DashNode): DashNode {
        if (path.isEmpty()) return replacement
        val split = node as? DashNode.Split ?: return node
        val rest = path.drop(1)
        return if (path.first()) {
            split.copy(second = replaceNode(split.second, rest, replacement))
        } else {
            split.copy(first = replaceNode(split.first, rest, replacement))
        }
    }

    /**
     * Splits the pane at [path] in two, keeping what was there as the first
     * child and putting [addition] beside or below it.
     *
     * A no-op when [path] does not name a pane, because the caller is a finger
     * on a car screen and the tree may have changed under it.
     */
    fun splitAt(
        path: DashPath,
        orientation: DashNode.Orientation,
        addition: DashNode.Leaf,
        ratio: Float = 0.5f,
    ): DashLayout {
        val existing = nodeAt(path) as? DashNode.Leaf ?: return this
        return replaceAt(
            path,
            DashNode.Split(
                orientation = orientation,
                ratio = ratio.coerceIn(DashNode.MIN_RATIO, DashNode.MAX_RATIO),
                first = existing,
                second = addition,
            ),
        )
    }

    /**
     * Removes the pane at [path]; its sibling takes the space.
     *
     * Removing the only pane is refused rather than obeyed. A layout with no
     * panes is a black car screen with no way to fix it from the car, which is
     * the one edit no driver can mean.
     */
    fun removeAt(path: DashPath): DashLayout {
        if (path.isEmpty()) return this
        val parentPath = path.dropLast(1)
        val parent = nodeAt(parentPath) as? DashNode.Split ?: return this
        val sibling = if (path.last()) parent.first else parent.second
        return replaceAt(parentPath, sibling)
    }

    /** Returns a copy with the pane at [path] showing [kind]/[argument] instead. */
    fun setPane(path: DashPath, kind: String, argument: String? = null): DashLayout {
        if (nodeAt(path) !is DashNode.Leaf) return this
        return replaceAt(path, DashNode.Leaf(kind, argument))
    }

    /**
     * Returns a copy with the divider at [path] moved to [ratio].
     *
     * [path] names the [DashNode.Split] itself, not one of its children.
     */
    fun withRatio(path: DashPath, ratio: Float): DashLayout {
        val split = nodeAt(path) as? DashNode.Split ?: return this
        return replaceAt(
            path,
            split.copy(ratio = ratio.coerceIn(DashNode.MIN_RATIO, DashNode.MAX_RATIO)),
        )
    }

    /** Returns a copy locked or unlocked. */
    fun withLocked(locked: Boolean): DashLayout = copy(locked = locked)

    /** Returns a copy under a new name; the name is the storage key. */
    fun renamed(newName: String): DashLayout = copy(name = newName)

    /** The path of the first pane of [kind], or null. */
    fun firstPaneOf(kind: String): DashPath? =
        leafPaths().firstOrNull { it.leaf.kind == kind }?.path

    /** One line for a log. */
    fun describe(): String =
        "$name: $paneCount pane(s) [${leaves().joinToString(", ") { it.kind }}]" +
            if (locked) ", locked" else ", unlocked"

    fun toJson(): JSONObject = JSONObject()
        .put(KEY_NAME, name)
        .put(KEY_LOCKED, locked)
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
        private const val KEY_LOCKED = "locked"
        private const val KEY_TYPE = "type"
        private const val KEY_KIND = "kind"
        private const val KEY_ARGUMENT = "arg"
        private const val KEY_ORIENTATION = "orientation"
        private const val KEY_RATIO = "ratio"
        private const val KEY_FIRST = "first"
        private const val KEY_SECOND = "second"
        private const val TYPE_LEAF = "leaf"
        private const val TYPE_SPLIT = "split"
        private const val KEY_LAYOUTS = "layouts"

        /**
         * Reads a layout back, or null when the JSON is not one.
         *
         * Null rather than an exception because this parses user-editable
         * storage that also survives app updates: a layout saved by an older
         * build naming a tile kind this one no longer has should cost the
         * driver their layout, not their dashboard.
         *
         * `locked` defaults to false when absent, which is what layouts written
         * before locking existed should be — they were editable when they were
         * saved, and silently freezing them on upgrade would look like a bug.
         */
        fun fromJson(json: JSONObject): DashLayout? = runCatching {
            val root = decode(json.getJSONObject(KEY_ROOT)) ?: return null
            DashLayout(
                name = json.getString(KEY_NAME),
                root = root,
                locked = json.optBoolean(KEY_LOCKED, false),
            )
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

        /** Serialises a whole set of layouts, for the store. */
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
    }
}

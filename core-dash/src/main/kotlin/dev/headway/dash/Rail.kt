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
 * One thing on the rail across the top of the car screen.
 *
 * ## What the rail is allowed to contain
 *
 * Three things, in this order and no others: settings, the microphone, and
 * whatever the driver pinned. The first two are not [RailItem]s — they are
 * fixtures, always present, always in the same place, because a control whose
 * position depends on configuration is a control the driver has to look for.
 * Everything after them is this type.
 *
 * A pinned item is either a **layout** — switch the whole car screen to that
 * arrangement, which is what a tab was — or an **app** — open it, in the pane
 * that hosts apps. Deliberately the same list rather than two: from the driver's
 * side both are "the thing I reach for", they compete for the same handful of
 * touch targets across the top of an 800-pixel panel, and making them one list
 * is what lets somebody who only ever uses Maps and Spotify have exactly two
 * buttons.
 */
sealed interface RailItem {

    /** Switch to the saved layout called [name]. */
    data class Layout(val name: String) : RailItem

    /** Open [packageName] in the app pane. */
    data class App(val packageName: String) : RailItem

    /** Stable key, for de-duplication and for the reorder editor. */
    val key: String
        get() = when (this) {
            is Layout -> "L:$name"
            is App -> "A:$packageName"
        }

    companion object {
        /** The inverse of [key], or null for anything unreadable. */
        fun fromKey(key: String): RailItem? = when {
            key.startsWith("L:") -> key.removePrefix("L:").takeIf { it.isNotEmpty() }?.let(::Layout)
            key.startsWith("A:") -> key.removePrefix("A:").takeIf { it.isNotEmpty() }?.let(::App)
            else -> null
        }
    }
}

/**
 * The pinned part of the rail: an ordered list, persisted as its own thing.
 *
 * ## Why order is stored and a `Set` is not enough
 *
 * The pinned-app grid was a `Set<String>` because a grid sorted itself by label.
 * The rail cannot: it is six-or-so buttons the driver reaches for without
 * looking, so the third one has to still be the third one tomorrow. A set
 * re-orders itself on any write, which on a dashboard is indistinguishable from
 * the buttons moving on their own.
 */
object Rail {

    private const val KEY_ITEMS = "items"

    /** How many pinned items fit across a car panel before the rail scrolls. */
    const val COMFORTABLE_LIMIT = 6

    fun encodeAll(items: List<RailItem>): String {
        val array = JSONArray()
        items.map { it.key }.distinct().forEach { array.put(it) }
        return JSONObject().put(KEY_ITEMS, array).toString()
    }

    /** Reads back [encodeAll]; unreadable entries are skipped, not fatal. */
    fun decodeAll(text: String?): List<RailItem> {
        if (text.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONObject(text).getJSONArray(KEY_ITEMS) }.getOrNull()
            ?: return emptyList()
        return (0 until array.length())
            .mapNotNull { runCatching { array.getString(it) }.getOrNull() }
            .mapNotNull(RailItem.Companion::fromKey)
            .distinctBy { it.key }
    }

    /**
     * What to show a driver who has pinned nothing.
     *
     * Their saved layouts, in order, and nothing else. Seeding it with apps
     * would mean guessing which of their apps matters, and the cost of guessing
     * wrong is a dashboard whose top row is somebody else's idea of driving.
     */
    fun seed(layoutNames: List<String>): List<RailItem> =
        layoutNames.take(COMFORTABLE_LIMIT).map(RailItem::Layout)

    /**
     * Adds [item] at the end if it is not already pinned; otherwise unchanged.
     *
     * Idempotent because the caller is a long press on a car screen, which is
     * exactly the gesture that gets delivered twice.
     */
    fun pin(items: List<RailItem>, item: RailItem): List<RailItem> =
        if (items.any { it.key == item.key }) items else items + item

    fun unpin(items: List<RailItem>, item: RailItem): List<RailItem> =
        items.filterNot { it.key == item.key }

    /** Moves the item at [from] to [to], clamping both. A no-op when they match. */
    fun move(items: List<RailItem>, from: Int, to: Int): List<RailItem> {
        if (items.isEmpty()) return items
        val source = from.coerceIn(items.indices)
        val target = to.coerceIn(items.indices)
        if (source == target) return items
        val mutable = items.toMutableList()
        mutable.add(target, mutable.removeAt(source))
        return mutable
    }

    /**
     * Drops pins that no longer name anything.
     *
     * A layout the driver deleted, or an app they uninstalled, leaves a button
     * that does nothing when pressed — which on a dashboard reads as the car
     * screen having frozen. Called whenever the rail is read.
     */
    fun prune(
        items: List<RailItem>,
        layoutNames: Set<String>,
        installedPackages: Set<String>,
    ): List<RailItem> = items.filter { item ->
        when (item) {
            is RailItem.Layout -> item.name in layoutNames
            is RailItem.App -> item.packageName in installedPackages
        }
    }
}

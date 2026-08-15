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

/**
 * The icons a layout may wear on the rail instead of its name.
 *
 * ## Why a name here and a drawing somewhere else
 *
 * A layout is stored data and has to survive an update, so what it records is a
 * *name* — a short stable token like `MAP`. The shapes live in the app module,
 * drawn on a `Canvas` at the car's own scale (`CarGlyph`), because a stroke
 * width that is a fraction of the head unit's touch target is the only way one
 * icon set reads correctly on both a 160 dpi 800x480 panel and a 240 dpi one.
 * Keeping the vocabulary here means `core-dash` can validate and round-trip a
 * layout without depending on any of that, and an icon this build does not know
 * is read as "no icon" rather than costing the driver their layout.
 *
 * ## Why the name is kept when an icon is set
 *
 * A layout's name is its identity: `DashLayoutStore` keys on it, `setActive`
 * takes it, and the rail's pinned items name it. So an icon *replaces the
 * label*, not the name — the layout is still "Drive" to everything that has to
 * find it, and the phone's editor still shows the word. That is also what makes
 * an unknown icon harmless: the label comes back.
 */
enum class TabIcon {
    MAP,
    MUSIC,
    PHONE,
    MESSAGE,
    CAR,
    CLOCK,
    HOME,
    STAR,
    COMPASS,
    RADIO,
    LIST,
    GRID,
    GAUGE,
    NAVIGATION,
    HEADPHONES,
    VIDEO,
    CAMERA,
    BELL,
    HEART,
    BOLT,
    COFFEE,
    WRENCH,
    ;

    companion object {

        /**
         * Reads a stored token, or null for anything this build does not draw.
         *
         * Null rather than a default, because a layout that named an icon added
         * in a later build should fall back to its own name — which is always
         * there — rather than to somebody else's picture.
         */
        fun of(name: String?): TabIcon? =
            name?.takeIf { it.isNotBlank() }
                ?.let { token -> entries.firstOrNull { it.name.equals(token, ignoreCase = true) } }

        /** Every icon, in the order a picker should offer them. */
        val ALL: List<TabIcon> = entries.toList()
    }
}

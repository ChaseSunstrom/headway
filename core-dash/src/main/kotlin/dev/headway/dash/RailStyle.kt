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

import org.json.JSONObject

/** Which side of the car screen the rail is fixed to. */
enum class RailEdge {
    TOP,
    BOTTOM,
    LEFT,
    RIGHT,
    ;

    /** True when the rail runs down a side, so its items stack vertically. */
    val vertical: Boolean get() = this == LEFT || this == RIGHT

    /** One line for a settings row. */
    fun describe(): String = when (this) {
        TOP -> "Top"
        BOTTOM -> "Bottom"
        LEFT -> "Left"
        RIGHT -> "Right"
    }

    companion object {
        val DEFAULT: RailEdge = TOP

        fun of(name: String?): RailEdge =
            entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) } ?: DEFAULT
    }
}

/**
 * Where the rail sits, how big it is, and what it shows besides its buttons.
 *
 * ## Why this is configurable at all
 *
 * The rail costs a strip of a screen that is 480 pixels tall on the target
 * vehicle, and where that strip goes is not a question with one right answer: a
 * unit whose physical buttons sit along the bottom bezel wants the rail at the
 * top, a tall panel wants it down a side so the map keeps its height, and a
 * driver who never uses pinned items wants it small. So it is a setting rather
 * than a constant.
 *
 * ## Why the clock lives here
 *
 * It was a *pane*, which meant spending a whole panel on eight characters. On
 * an 800x480 screen the smallest useful pane is a fifth of the dashboard, and
 * nobody wants a fifth of their dashboard to be a clock. The rail already runs
 * the full width and already has room at its end, so the clock goes there and
 * the Clock pane becomes what it should always have been: optional.
 *
 * @param edge which side the rail is fixed to.
 * @param scale a multiplier on the rail's thickness and its glyphs. Clamped to
 *   [MIN_SCALE]..[MAX_SCALE]; 1.0 is one touch target thick.
 * @param showClock whether the time appears at the far end of the rail.
 * @param showDate whether the date appears under (or beside) the time. Ignored
 *   when [showClock] is false, because a date with no time reads as a label
 *   rather than a clock.
 */
data class RailStyle(
    val edge: RailEdge = RailEdge.DEFAULT,
    val scale: Float = 1.0f,
    val showClock: Boolean = true,
    val showDate: Boolean = false,
) {

    /** The scale actually applied, with a value from an edited file clamped. */
    val effectiveScale: Float get() = scale.coerceIn(MIN_SCALE, MAX_SCALE)

    /** True when the date should really be drawn. */
    val clockShowsDate: Boolean get() = showClock && showDate

    /** The rail's thickness in car pixels, given the layout unit. */
    fun thicknessPx(unitPx: Int): Int = (unitPx * effectiveScale).toInt().coerceAtLeast(1)

    fun describe(): String = buildString {
        append(edge.describe())
        append(", ")
        append(percentOf(effectiveScale))
        if (showClock) append(", clock") else append(", no clock")
        if (clockShowsDate) append(" and date")
    }

    fun toJson(): JSONObject = JSONObject()
        .put(KEY_EDGE, edge.name)
        .put(KEY_SCALE, effectiveScale.toDouble())
        .put(KEY_CLOCK, showClock)
        .put(KEY_DATE, showDate)

    companion object {
        /** Half a touch target. Below this the glyphs stop being hittable in a car. */
        const val MIN_SCALE: Float = 0.7f

        /** Any larger and the rail is taking a third of an 800x480 panel. */
        const val MAX_SCALE: Float = 1.6f

        val DEFAULT: RailStyle = RailStyle()

        /** The sizes a settings screen should offer, smallest first. */
        val SCALE_CHOICES: List<Float> = listOf(0.7f, 0.85f, 1.0f, 1.25f, 1.6f)

        private const val KEY_EDGE = "edge"
        private const val KEY_SCALE = "scale"
        private const val KEY_CLOCK = "clock"
        private const val KEY_DATE = "date"

        /** A label for one of [SCALE_CHOICES]. */
        fun percentOf(scale: Float): String = "${(scale * 100).toInt()}%"

        /**
         * Reads a stored style, falling back to [DEFAULT] for anything unreadable.
         *
         * Never throws. The car screen has to come up: a rail that refuses to
         * build because a preference was corrupted is a black screen mid-drive,
         * and every field here has a sane default.
         */
        fun fromJson(text: String?): RailStyle {
            if (text.isNullOrBlank()) return DEFAULT
            return runCatching {
                val json = JSONObject(text)
                RailStyle(
                    edge = RailEdge.of(json.optString(KEY_EDGE, null)),
                    scale = json.optDouble(KEY_SCALE, 1.0).toFloat(),
                    showClock = json.optBoolean(KEY_CLOCK, true),
                    showDate = json.optBoolean(KEY_DATE, false),
                )
            }.getOrDefault(DEFAULT)
        }
    }
}

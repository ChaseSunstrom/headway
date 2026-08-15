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
 * @param scale a multiplier on every control the rail carries -- buttons,
 *   pills, icons, the clock -- and so on the rail's thickness, which is
 *   whatever those need. Clamped to [MIN_SCALE]..[MAX_SCALE]. 1.0 is a button
 *   one touch target square, and it is the *smallest*: the rail grows, it does
 *   not shrink, because the touch target is already the minimum a moving car
 *   allows. See [MIN_SCALE].
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
        /**
         * The comfortable touch target, and the smallest the rail can be.
         *
         * The rail grows and does not shrink, because there is nothing below
         * this to shrink into: CLAUDE.md asks for "minimum 48 dp targets scaled
         * to the head unit density", `CarMetrics.touchTargetPx` is exactly that
         * number with an absolute 44-pixel floor under it, and the car screen
         * applies size by scaling those metrics.
         *
         * Offering smaller was worse than refusing to. On a 160 dpi 800x480 unit
         * the target is 48 px, so 0.7 computed 33 and 0.85 computed 40 -- and
         * both were floored back to 44, along with the gutter and the radius
         * that derive from them. Two of the five chips in the picker produced a
         * byte-identical rail: the selection moved and the screen did not.
         */
        const val MIN_SCALE: Float = 1.0f

        /** Any larger and the rail is taking a third of an 800x480 panel. */
        const val MAX_SCALE: Float = 1.9f

        val DEFAULT: RailStyle = RailStyle()

        /**
         * The sizes a settings screen should offer, smallest first.
         *
         * Spaced so that each one is a different number of pixels at the
         * densities a head unit actually reports -- see `RailStyleTest`, which
         * measures that rather than trusting it.
         */
        val SCALE_CHOICES: List<Float> = listOf(1.0f, 1.2f, 1.4f, 1.6f, 1.9f)

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

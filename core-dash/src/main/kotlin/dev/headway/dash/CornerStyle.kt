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
 * How rounded the car screen's corners are.
 *
 * ## Why it is a multiplier rather than a number of pixels
 *
 * Because every radius in the car UI is already derived from the touch target,
 * which is derived from the head unit's negotiated density — a pane's corner, a
 * button's, a card's and a switch's track are all proportional to each other by
 * construction. A fixed pixel radius would break that relationship on the first
 * head unit with a different density, and would make a driver's choice mean
 * something different on every car. A multiplier keeps the whole set in step.
 *
 * [SQUARE] is exactly zero rather than nearly zero, because a driver who asks
 * for square corners is asking for square corners.
 */
enum class CornerStyle(
    /** What every radius in the car UI is multiplied by. */
    val scale: Float,
    val displayName: String,
    val explanation: String,
) {
    SQUARE(0f, "Square", "No rounding anywhere"),
    SLIGHT(0.5f, "Slight", "Barely rounded"),
    ROUNDED(1f, "Rounded", "The usual"),
    SOFT(1.6f, "Soft", "Noticeably rounder"),
    PILL(2.6f, "Pill", "As round as the shapes allow"),
    ;

    companion object {

        val DEFAULT: CornerStyle = ROUNDED

        /** Reads a stored name, falling back to [DEFAULT] for anything else. */
        fun of(name: String?): CornerStyle =
            name?.let { token -> entries.firstOrNull { it.name.equals(token, ignoreCase = true) } }
                ?: DEFAULT

        /** Every style, in the order a picker should offer them. */
        val ALL: List<CornerStyle> = entries.toList()
    }
}

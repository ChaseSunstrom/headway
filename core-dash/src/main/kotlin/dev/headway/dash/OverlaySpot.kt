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

/**
 * Where a floating card sits on the car screen, and how big it is.
 *
 * ## Why this is not part of a `DashLayout`
 *
 * Because a `DashNode` is a strict binary split tree — every node is a leaf or
 * a split of two children at a ratio — and a free rectangle floating *over*
 * that tree is not expressible in it. Adding a node type for one would break
 * `leaves`, `paneCount`, `leafPaths`, `replaceAt`, `splitAt`, `removeAt`, the
 * JSON codec and the weighted layouts `PaneTreeView` builds from it, for
 * something that is not a pane in the first place.
 *
 * It is also not *per layout*. A call arrives over whichever tab happens to be
 * showing, so where the card appears has to be one preference, like the rail's
 * edge — not a property of the tab a driver happened to be looking at.
 *
 * ## Why fractions rather than pixels
 *
 * The head unit chooses the resolution, and a driver who changes cars, or whose
 * unit negotiates a different mode, should not find their call card off the
 * side of the screen. Everything here is a fraction of the panel and is
 * multiplied by the negotiated geometry at draw time.
 */
data class OverlaySpot(
    /** Left edge, as a fraction of the panel's width. */
    val x: Float = DEFAULT_X,
    /** Top edge, as a fraction of the panel's height. */
    val y: Float = DEFAULT_Y,
    /** Width, as a fraction of the panel's width. */
    val width: Float = DEFAULT_WIDTH,
    /** Height, as a fraction of the panel's height. */
    val height: Float = DEFAULT_HEIGHT,
) {

    /** [width], clamped to something that can hold a card. */
    val effectiveWidth: Float get() = width.coerceIn(MIN_SIZE, 1f)

    /** [height], clamped to something that can hold a card. */
    val effectiveHeight: Float get() = height.coerceIn(MIN_SIZE, 1f)

    /**
     * [x], clamped so the card cannot be dragged off the panel.
     *
     * A card whose left edge is at 0.95 would be a sliver, and a driver who put
     * it there could not get hold of it again to move it back.
     */
    val effectiveX: Float get() = x.coerceIn(0f, 1f - effectiveWidth)

    /** [y], clamped the same way. */
    val effectiveY: Float get() = y.coerceIn(0f, 1f - effectiveHeight)

    fun leftPx(panelWidth: Int): Int = (effectiveX * panelWidth).toInt()

    fun topPx(panelHeight: Int): Int = (effectiveY * panelHeight).toInt()

    fun widthPx(panelWidth: Int): Int =
        (effectiveWidth * panelWidth).toInt().coerceAtLeast(1)

    fun heightPx(panelHeight: Int): Int =
        (effectiveHeight * panelHeight).toInt().coerceAtLeast(1)

    /** One line for the settings row and the log. */
    fun describe(): String =
        "${percent(effectiveWidth)}×${percent(effectiveHeight)} at " +
            "${percent(effectiveX)},${percent(effectiveY)}"

    fun toJson(): JSONObject = JSONObject()
        .put(KEY_X, x.toDouble())
        .put(KEY_Y, y.toDouble())
        .put(KEY_WIDTH, width.toDouble())
        .put(KEY_HEIGHT, height.toDouble())

    companion object {

        /**
         * Where a card goes before anybody moves it.
         *
         * The lower-left quarter, roughly. Not the middle: the middle of a car
         * screen is where a map's own position marker and a template's primary
         * action both live. Not the top: that is where every car app puts its
         * own header. The bottom-left is the emptiest part of the panel across
         * the apps this project has looked at, and it is on the driver's side
         * in a left-hand-drive car, which is the only guess available.
         */
        const val DEFAULT_X: Float = 0.04f
        const val DEFAULT_Y: Float = 0.46f
        const val DEFAULT_WIDTH: Float = 0.46f
        const val DEFAULT_HEIGHT: Float = 0.48f

        /** Below this the card cannot hold a name and two buttons. */
        const val MIN_SIZE: Float = 0.2f

        val DEFAULT: OverlaySpot = OverlaySpot()

        /** The sizes a picker offers, as (label, width, height). */
        val SIZES: List<Triple<String, Float, Float>> = listOf(
            Triple("Small", 0.34f, 0.36f),
            Triple("Medium", 0.46f, 0.48f),
            Triple("Large", 0.62f, 0.66f),
            Triple("Half the screen", 0.5f, 1f),
        )

        /** The corners a picker offers, as (label, x, y) for the given size. */
        fun corners(width: Float, height: Float): List<Triple<String, Float, Float>> = listOf(
            Triple("Top left", 0.04f, 0.04f),
            Triple("Top right", 1f - width - 0.04f, 0.04f),
            Triple("Bottom left", 0.04f, 1f - height - 0.04f),
            Triple("Bottom right", 1f - width - 0.04f, 1f - height - 0.04f),
            Triple("Middle", (1f - width) / 2f, (1f - height) / 2f),
        )

        private fun percent(value: Float): String = "${(value * 100).toInt()}%"

        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        private const val KEY_WIDTH = "w"
        private const val KEY_HEIGHT = "h"

        /**
         * Reads a stored spot.
         *
         * Never throws, for the reason `RailStyle.fromJson` gives: every field
         * has a sane default, so a hand-edited or truncated value costs the
         * driver a position rather than a car screen.
         */
        fun fromJson(json: JSONObject?): OverlaySpot {
            if (json == null) return DEFAULT
            return runCatching {
                OverlaySpot(
                    x = json.optDouble(KEY_X, DEFAULT_X.toDouble()).toFloat(),
                    y = json.optDouble(KEY_Y, DEFAULT_Y.toDouble()).toFloat(),
                    width = json.optDouble(KEY_WIDTH, DEFAULT_WIDTH.toDouble()).toFloat(),
                    height = json.optDouble(KEY_HEIGHT, DEFAULT_HEIGHT.toDouble()).toFloat(),
                )
            }.getOrDefault(DEFAULT)
        }

        /** Reads a stored spot from its text form. */
        fun decode(text: String?): OverlaySpot {
            if (text.isNullOrBlank()) return DEFAULT
            return fromJson(runCatching { JSONObject(text) }.getOrNull())
        }

        fun encode(spot: OverlaySpot): String = spot.toJson().toString()
    }
}

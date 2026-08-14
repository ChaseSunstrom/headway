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

import kotlin.math.max
import kotlin.math.roundToInt

/** An integer rectangle in car pixels. Left/top inclusive, right/bottom exclusive. */
data class PaneRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    fun contains(x: Int, y: Int): Boolean = x >= left && x < right && y >= top && y < bottom
    override fun toString(): String = "${width}x$height at ($left,$top)"
}

/** A point on the source display, in that display's own pixels. */
data class SourcePoint(val x: Int, val y: Int)

/**
 * Where an app's picture goes inside a pane, and where a touch on it lands.
 *
 * ## Why this arithmetic exists at all
 *
 * An app pane shows a *different display* — the simulated secondary display the
 * app was launched onto, or the phone's own screen when there is none. That
 * display has its own shape: 720x480 for the simulated one, 1080x2404 for a
 * Pixel. The pane has a third shape, decided by the driver's layout. Something
 * has to reconcile them, and there are only two honest choices: letterbox, or
 * crop. Headway letterboxes, because cropping a map hides the part of it the
 * driver was looking for.
 *
 * ## Why Headway does the fitting rather than the platform
 *
 * `MediaProjection` will scale a captured display into whatever surface it is
 * given, preserving aspect and filling the remainder with black. Left to it, an
 * app pane would contain a black bar that is *inside* the surface — so the pane
 * would have a hard black edge instead of the dashboard's own background, and
 * every touch would have to be un-letterboxed before it could be sent anywhere.
 *
 * Instead the surface is created at exactly [fit]'s rectangle, which has the
 * source's aspect ratio by construction. There is then no letterbox inside the
 * surface at all: the picture fills it, the dashboard's own background shows
 * around it, and the touch transform is one linear map with no special cases.
 *
 * All of it is integer arithmetic on car pixels, and all of it is here rather
 * than in a `View`, because this is the part that is wrong on the first drive if
 * it is wrong at all.
 */
object PaneFit {

    /**
     * The largest rectangle with [sourceWidth]:[sourceHeight] proportions that
     * fits inside a [paneWidth]x[paneHeight] pane, centred.
     *
     * Returns a rectangle relative to the pane's own top-left. Degenerate inputs
     * yield an empty rectangle rather than a division by zero: a pane that has
     * not been measured yet legitimately has a size of zero, and it will be
     * measured a frame later.
     */
    fun fit(sourceWidth: Int, sourceHeight: Int, paneWidth: Int, paneHeight: Int): PaneRect {
        if (sourceWidth <= 0 || sourceHeight <= 0 || paneWidth <= 0 || paneHeight <= 0) {
            return PaneRect(0, 0, 0, 0)
        }
        // Compare source and pane aspect with a cross-multiplication rather than
        // a float divide, so the branch cannot flip on rounding.
        val sourceIsWider = sourceWidth.toLong() * paneHeight > paneWidth.toLong() * sourceHeight
        val width: Int
        val height: Int
        if (sourceIsWider) {
            width = paneWidth
            height = max(1, (paneWidth.toLong() * sourceHeight / sourceWidth).toInt())
        } else {
            height = paneHeight
            width = max(1, (paneHeight.toLong() * sourceWidth / sourceHeight).toInt())
        }
        val left = (paneWidth - width) / 2
        val top = (paneHeight - height) / 2
        return PaneRect(left, top, left + width, top + height)
    }

    /**
     * Maps a touch at ([x], [y]) — in the pane's own coordinates — onto the
     * source display, or null when the touch landed on the letterbox rather than
     * on the picture.
     *
     * Null is a real answer and callers must honour it. A touch on the bar is a
     * touch on Headway's dashboard, not on the app, and forwarding it would put
     * a tap at the app's edge for every miss.
     */
    fun toSource(
        x: Int,
        y: Int,
        picture: PaneRect,
        sourceWidth: Int,
        sourceHeight: Int,
    ): SourcePoint? {
        if (picture.width <= 0 || picture.height <= 0) return null
        if (!picture.contains(x, y)) return null
        val fx = (x - picture.left).toFloat() / picture.width
        val fy = (y - picture.top).toFloat() / picture.height
        // Clamped to the last addressable pixel: a touch on the right edge maps
        // to sourceWidth exactly, which is one past the display and is refused by
        // gesture dispatch.
        val sx = (fx * sourceWidth).roundToInt().coerceIn(0, sourceWidth - 1)
        val sy = (fy * sourceHeight).roundToInt().coerceIn(0, sourceHeight - 1)
        return SourcePoint(sx, sy)
    }

    /**
     * How far a pane's picture can be off its ideal size before the virtual
     * display is resized.
     *
     * Resizing reallocates buffers and drops a frame or two, and a pane's
     * measured size can wobble by a pixel as neighbouring text reflows. Two
     * pixels of slack costs nothing visible and stops a layout pass from
     * becoming a stutter.
     */
    const val RESIZE_SLACK_PX = 2

    /** Whether a display currently [currentWidth]x[currentHeight] should be resized to [target]. */
    fun needsResize(currentWidth: Int, currentHeight: Int, target: PaneRect): Boolean {
        if (target.width <= 0 || target.height <= 0) return false
        return kotlin.math.abs(currentWidth - target.width) > RESIZE_SLACK_PX ||
            kotlin.math.abs(currentHeight - target.height) > RESIZE_SLACK_PX
    }
}

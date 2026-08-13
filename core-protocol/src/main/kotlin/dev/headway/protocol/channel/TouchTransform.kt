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

package dev.headway.protocol.channel

/** A point in the car's video coordinate space. */
data class CarPoint(val x: Double, val y: Double)

/** A point in the phone's screen coordinate space. */
data class PhonePoint(val x: Double, val y: Double)

/** A rectangle in the car's video coordinate space. */
data class CarRect(val left: Double, val top: Double, val width: Double, val height: Double) {
    val right: Double get() = left + width
    val bottom: Double get() = top + height
}

/** One finger, mapped into phone screen coordinates. */
data class PhonePointer(val id: Int, val x: Double, val y: Double)

/** A [CarInputEvent.Touch] mapped into phone screen coordinates. */
data class PhoneTouch(
    val timestampMicros: Long,
    val action: TouchAction,
    val actionIndex: Int,
    val pointers: List<PhonePointer>,
    val surface: TouchSurface,
) {
    val changedPointer: PhonePointer? get() = pointers.getOrNull(actionIndex)
}

/**
 * Maps head-unit touch coordinates onto phone screen coordinates, and back.
 *
 * ## Why this is not a scale factor
 *
 * The phone mirrors its own screen into the video stream the car displays. Those
 * two surfaces have wildly different shapes: a Chevrolet Infotainment 3 panel is
 * 800x480 (1.67:1 landscape), a Pixel is 1080x2400 (0.45:1 portrait). Stretching
 * one onto the other independently per axis would render the UI unusable, so the
 * image is scaled **uniformly** and centred, and the leftover strips of the car
 * screen are blank — pillarbox bars for a portrait phone, letterbox bars for a
 * landscape one.
 *
 * A naive `phoneX = carX * phoneWidth / carWidth` ignores those bars, so every
 * touch lands progressively further from what the driver actually pointed at —
 * worst at the edges, which is where the buttons are.
 *
 * ## The maths
 *
 * ```text
 *   scale     = min(carW / phoneW, carH / phoneH)
 *   contentW  = phoneW * scale          contentH = phoneH * scale
 *   left      = (carW - contentW) / 2   top      = (carH - contentH) / 2
 *
 *   car -> phone:   px = (cx - left) / scale     py = (cy - top) / scale
 *   phone -> car:   cx = px * scale + left       cy = py * scale + top
 * ```
 *
 * `min` picks the axis that runs out first; that axis fits exactly and gets
 * offset 0, so **at most one of `left` and `top` is non-zero**. The two
 * directions are exact algebraic inverses, which is what makes a round trip
 * lossless up to floating-point.
 *
 * Coordinates are treated as positions on a continuous plane spanning
 * `[0, W] x [0, H]`, not as pixel indices. The far edge therefore maps to the far
 * edge rather than to `W - 1`, and the corners of the content rectangle map to
 * the corners of the phone screen. Callers that need a pixel index round.
 *
 * ## Points in the bars map to nothing
 *
 * [toPhone] returns null for a car point outside the content rectangle. It
 * deliberately does **not** clamp to the nearest edge: clamping would turn the
 * blank bar into a strip that activates whatever the phone happens to be drawing
 * at its edge, so a palm resting on the bezel-adjacent black area would press a
 * button. An inert bar is what the driver sees, so an inert bar is what the
 * transform must produce.
 *
 * ## The renderer must agree
 *
 * [contentRect] is the rectangle the phone's video encoder has to draw into for
 * this transform to be true. If the encoder letterboxes differently — a
 * different rounding, a different centring — every touch is offset by the
 * difference. Both sides read it from here.
 *
 * ## What "car coordinates" means
 *
 * The head unit rescales from its physical panel geometry into the projected
 * display geometry before sending, `x = (pos.x / touchscreen.width) *
 * display.width` (`openauto/src/autoapp/Projection/InputDevice.cpp` L391-L392).
 * So [carWidth]/[carHeight] are the **advertised video resolution**, not the
 * `InputSourceService.TouchScreen` width/height — those two are often equal but
 * are not the same field, and using the panel size when they differ silently
 * skews everything.
 */
class TouchTransform(
    val carWidth: Int,
    val carHeight: Int,
    val phoneWidth: Int,
    val phoneHeight: Int,
) {
    init {
        require(carWidth > 0 && carHeight > 0) { "car size must be positive: ${carWidth}x$carHeight" }
        require(phoneWidth > 0 && phoneHeight > 0) {
            "phone size must be positive: ${phoneWidth}x$phoneHeight"
        }
    }

    /** Uniform scale from phone pixels to car pixels. Less than 1 when the phone is the larger surface. */
    val scale: Double =
        minOf(carWidth.toDouble() / phoneWidth, carHeight.toDouble() / phoneHeight)

    /** Where the mirrored phone screen sits inside the car's frame. */
    val contentRect: CarRect = run {
        val width = phoneWidth * scale
        val height = phoneHeight * scale
        CarRect(
            left = (carWidth - width) / 2.0,
            top = (carHeight - height) / 2.0,
            width = width,
            height = height,
        )
    }

    /** True when the bars run down the left and right sides (a portrait phone on a landscape screen). */
    val pillarboxed: Boolean get() = contentRect.left > BAR_EPSILON

    /** True when the bars run along the top and bottom. */
    val letterboxed: Boolean get() = contentRect.top > BAR_EPSILON

    /**
     * Maps a car point onto the phone screen.
     *
     * @return null if the point lies in a bar, i.e. there is no phone pixel
     *   behind it.
     */
    fun toPhone(carX: Double, carY: Double): PhonePoint? {
        // Tested in car space so the tolerance is half a *car* pixel regardless
        // of scale. It absorbs a fractional content-rect edge landing between two
        // integer touch coordinates; without it the outermost row of the mirrored
        // image would intermittently read as bar.
        if (carX < contentRect.left - EDGE_TOLERANCE) return null
        if (carX > contentRect.right + EDGE_TOLERANCE) return null
        if (carY < contentRect.top - EDGE_TOLERANCE) return null
        if (carY > contentRect.bottom + EDGE_TOLERANCE) return null

        val x = (carX - contentRect.left) / scale
        val y = (carY - contentRect.top) / scale
        // Only points already inside the tolerance band reach this, so the clamp
        // is the rounding fix described above and not the edge-clamping that the
        // class doc rules out.
        return PhonePoint(
            x.coerceIn(0.0, phoneWidth.toDouble()),
            y.coerceIn(0.0, phoneHeight.toDouble()),
        )
    }

    /** Integer overload, for the `uint32` coordinates that arrive on the wire. */
    fun toPhone(carX: Int, carY: Int): PhonePoint? = toPhone(carX.toDouble(), carY.toDouble())

    /**
     * The inverse: where a phone point appears on the car's screen.
     *
     * Always defined — every phone pixel is inside the content rectangle by
     * construction — so unlike [toPhone] this returns non-null. Used to drive the
     * emulator and to place overlays the car should see.
     */
    fun toCar(phoneX: Double, phoneY: Double): CarPoint = CarPoint(
        x = phoneX * scale + contentRect.left,
        y = phoneY * scale + contentRect.top,
    )

    /**
     * Maps a whole touch event.
     *
     * A multi-touch event can straddle the boundary: one finger on the mirrored
     * UI, one on the bar. The rules, in order:
     *
     * 1. If the pointer the event is *about* — the one at `actionIndex` — is in a
     *    bar, the whole event is dropped **unless it is a lift**. Delivering a
     *    DOWN whose coordinates we had to invent would start a gesture the driver
     *    did not start; but dropping the UP that ends a real gesture leaves the
     *    injected touch held on the phone forever, because the accessibility
     *    dispatcher built a stroke from the DOWN/MOVED stream and never sees the
     *    finger come up. A lift in a bar is therefore delivered, clamped to the
     *    nearest phone pixel — the finger did leave the screen there.
     * 2. Pointers that are in a bar are removed from the list, and `actionIndex`
     *    is rebased onto the surviving list so it still points at the same finger.
     * 3. Pointer ids are preserved, since that is the only stable handle across
     *    reports.
     *
     * @return null if the event has nothing to deliver.
     */
    fun map(touch: CarInputEvent.Touch): PhoneTouch? {
        val changed = touch.pointers.getOrNull(touch.actionIndex) ?: return null
        val lift = touch.action == TouchAction.UP || touch.action == TouchAction.POINTER_UP
        if (toPhone(changed.x, changed.y) == null && !lift) return null

        val mapped = ArrayList<PhonePointer>(touch.pointers.size)
        var actionIndex = -1
        for ((index, pointer) in touch.pointers.withIndex()) {
            val isAction = index == touch.actionIndex
            // The action pointer of a lift is kept even in a bar, clamped; every
            // other bar pointer is dropped as before.
            val point = toPhone(pointer.x, pointer.y)
                ?: if (isAction && lift) clampToPhone(pointer.x, pointer.y) else continue
            if (isAction) actionIndex = mapped.size
            mapped += PhonePointer(pointer.id, point.x, point.y)
        }
        if (actionIndex < 0) return null

        return PhoneTouch(
            timestampMicros = touch.timestampMicros,
            action = touch.action,
            actionIndex = actionIndex,
            pointers = mapped,
            surface = touch.surface,
        )
    }

    /**
     * Where a car point lands once projected onto the phone and clamped into it.
     *
     * Unlike [toPhone] this never returns null: a bar point is pulled to the
     * nearest edge of the content area. Only for the finger-lift case above,
     * where the exact pixel does not matter but the event must be delivered.
     */
    private fun clampToPhone(carX: Int, carY: Int): PhonePoint = PhonePoint(
        ((carX - contentRect.left) / scale).coerceIn(0.0, phoneWidth.toDouble()),
        ((carY - contentRect.top) / scale).coerceIn(0.0, phoneHeight.toDouble()),
    )

    override fun toString(): String =
        "TouchTransform(car=${carWidth}x$carHeight phone=${phoneWidth}x$phoneHeight " +
            "scale=%.4f content=%.1f,%.1f %.1fx%.1f)".format(
                scale, contentRect.left, contentRect.top, contentRect.width, contentRect.height,
            )

    private companion object {
        /**
         * How far outside the content rectangle a point may sit and still count
         * as inside. Half a car pixel: enough to cover the fractional edge, small
         * enough that no visibly-blank area becomes touchable.
         */
        const val EDGE_TOLERANCE = 0.5

        /** Below this, a bar is a rounding artefact rather than a visible band. */
        const val BAR_EPSILON = 1e-9
    }
}

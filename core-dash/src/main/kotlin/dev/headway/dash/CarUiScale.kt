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
 * How large a hosted app draws inside its panel, per app.
 *
 * ## Why per app and not once
 *
 * Because the thing being scaled is somebody else's rendering. A car app sizes
 * its own map markers, labels and strokes from the density the host hands it,
 * and every app picks its own numbers against that — so a map that reads well
 * at 100% sits beside a messenger whose rows are twice the height they need to
 * be. One global setting would be a compromise between apps that have nothing
 * to do with each other. A driver asked for exactly this: "so text looks
 * bigger/smaller for different apps".
 *
 * ## What it actually moves
 *
 * The density the panel reports, which is two things at once:
 *
 *  - the `Configuration` and `SurfaceContainer` density handed to the app, so
 *    its own drawing — the location triangle, road labels, its own text —
 *    scales; and
 *  - the `Context` Headway draws the template chrome with, because every size
 *    in `CarStyle` is a dp or sp literal resolved against that context. Wrapping
 *    the context is what makes one number move both, with no call site changed.
 *
 * ## Range
 *
 * Deliberately wide, because the fault it exists to correct was large: Headway
 * used to send the *phone's* configuration to the app while drawing its own
 * chrome at the car's density, so an app sized its map for a 560 dpi handset
 * and drew it on a 160 dpi panel. The floor is well below 1.0 for anyone still
 * living with an app that sizes generously.
 */
data class CarUiScale(val packageName: String, val scale: Float) {

    /** The scale actually applied, with a value from an edited file clamped. */
    val effective: Float get() = scale.coerceIn(MIN, MAX)

    /** [densityDpi] at this scale, never zero. */
    fun densityFor(densityDpi: Int): Int =
        (densityDpi * effective).toInt().coerceAtLeast(MIN_DENSITY_DPI)

    companion object {

        const val MIN: Float = 0.5f
        const val MAX: Float = 2.0f
        const val DEFAULT: Float = 1.0f

        /**
         * A floor under the computed density.
         *
         * `Configuration.densityDpi` of zero means "undefined" and makes every
         * dp resolve to nothing; a very small one makes a car app's own touch
         * targets unhittable. 80 is half of the 160 dpi baseline, which is as
         * far as the smallest offered scale can reach anyway.
         */
        const val MIN_DENSITY_DPI: Int = 80

        /** The sizes a picker should offer, smallest first. */
        val CHOICES: List<Float> = listOf(0.5f, 0.65f, 0.8f, 1.0f, 1.25f, 1.5f, 2.0f)

        /** A label for one of [CHOICES]. */
        fun percentOf(scale: Float): String = "${(scale * 100).toInt()}%"

        /**
         * Reads the stored map of package name to scale.
         *
         * Never throws: an unreadable or hand-edited value costs the driver a
         * size, not a car screen. Unknown packages and out-of-range numbers are
         * dropped rather than clamped on read, so the file says what was meant
         * and [effective] decides what happens.
         */
        fun readAll(text: String?): Map<String, Float> {
            if (text.isNullOrBlank()) return emptyMap()
            return runCatching {
                val json = JSONObject(text)
                buildMap {
                    json.keys().forEach { key ->
                        val value = json.optDouble(key, Double.NaN)
                        if (!value.isNaN() && key.isNotBlank()) put(key, value.toFloat())
                    }
                }
            }.getOrDefault(emptyMap())
        }

        /** The stored form of [scales]. */
        fun writeAll(scales: Map<String, Float>): String {
            val json = JSONObject()
            // Defaults are removed rather than written, so a driver who puts an
            // app back to 100% leaves no trace of having changed it.
            scales.forEach { (name, value) ->
                if (name.isNotBlank() && value != DEFAULT) json.put(name, value.toDouble())
            }
            return json.toString()
        }

        /** The scale for [packageName], or [DEFAULT]. */
        fun forApp(scales: Map<String, Float>, packageName: String?): Float =
            packageName?.let { scales[it] }?.coerceIn(MIN, MAX) ?: DEFAULT
    }
}

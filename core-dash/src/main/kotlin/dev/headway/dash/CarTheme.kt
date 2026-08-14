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

import kotlin.math.abs
import kotlin.math.pow

/**
 * Every colour the car screen and the phone app draw with.
 *
 * Plain `Int`s in ARGB, not `android.graphics.Color`, so the palettes — and the
 * contrast rules that keep them legible — are testable without a device. The
 * Android side reads these straight: `Color` values *are* packed ARGB ints.
 */
data class CarPalette(
    /** Page background. */
    val ground: Int,
    /** A panel sitting on [ground]. */
    val surface: Int,
    /** A panel that is pressed, focused, or otherwise raised. */
    val surfaceRaised: Int,
    /** Hairline between panels. */
    val outline: Int,
    val text: Int,
    val textMuted: Int,
    /** The one accent. */
    val accent: Int,
    /** The accent at rest, for rules and inactive marks. */
    val accentDim: Int,
    /** What is legible *on* [accent] — a filled pill's label. */
    val onAccent: Int,
    val warn: Int,
    val fault: Int,
    val good: Int,
) {
    /** True when this palette is dark-on-light rather than light-on-dark. */
    val isLight: Boolean get() = Contrast.luminance(ground) > 0.5
}

/**
 * The two independent choices a driver makes about how the car screen looks.
 *
 * Split in two on purpose, because the request they answer is two requests:
 * "light or dark", and "with or without the blue". Folding them into one list of
 * named themes would make six entries out of nine combinations and hide the
 * second question inside the first.
 */
data class ThemeChoice(
    val base: ThemeBase = ThemeBase.DARK,
    val accent: ThemeAccent = ThemeAccent.HEADWAY,
) {
    fun encode(): String = "${base.name}:${accent.name}"

    companion object {
        val DEFAULT = ThemeChoice()

        /** Reads back [encode]; anything unreadable is [DEFAULT], never an error. */
        fun decode(text: String?): ThemeChoice {
            if (text.isNullOrBlank()) return DEFAULT
            val parts = text.split(':')
            val base = runCatching { ThemeBase.valueOf(parts[0]) }.getOrNull() ?: return DEFAULT
            val accent = parts.getOrNull(1)
                ?.let { runCatching { ThemeAccent.valueOf(it) }.getOrNull() }
                ?: DEFAULT.accent
            return ThemeChoice(base, accent)
        }
    }
}

/** How dark the car screen is. */
enum class ThemeBase(val displayName: String, val explanation: String) {
    /** Near-black with lifted surfaces. What Headway has always looked like. */
    DARK("Dark", "Near-black, for night driving"),

    /**
     * True black.
     *
     * Not a gimmick on a car screen: an infotainment panel at night with a
     * near-black background is a visible grey rectangle in the corner of the
     * driver's eye, and several head units are OLED.
     */
    MIDNIGHT("Midnight", "True black, nothing glows at night"),

    /** Dark-on-light, for direct sunlight. */
    LIGHT("Light", "Dark on white, for bright daylight"),
}

/**
 * The one hue that is allowed to be a hue.
 *
 * [NONE] exists because the request that produced this enum was literally
 * "without the blue headway theme": it is not a colour choice so much as the
 * option to have no colour choice, and it renders every accent as the same
 * neutral the text is in.
 */
enum class ThemeAccent(val displayName: String, private val dark: Int, private val light: Int) {
    HEADWAY("Headway cyan", 0xFF4FC3F7.toInt(), 0xFF0277BD.toInt()),
    NONE("None — greyscale", 0xFFE0E6EB.toInt(), 0xFF37474F.toInt()),
    AMBER("Amber", 0xFFFFB74D.toInt(), 0xFFB34700.toInt()),
    GREEN("Green", 0xFF66D99A.toInt(), 0xFF1B5E20.toInt()),
    VIOLET("Violet", 0xFFB39DFF.toInt(), 0xFF4527A0.toInt()),
    RED("Red", 0xFFFF7B7B.toInt(), 0xFFC62828.toInt());

    /** The accent for [base]: dark themes want a light accent and vice versa. */
    fun colorFor(base: ThemeBase): Int = if (base == ThemeBase.LIGHT) light else dark
}

/**
 * Turns a [ThemeChoice] into a [CarPalette].
 *
 * ## Why the accent is composed rather than listed
 *
 * Six accents times three bases is eighteen palettes, and eighteen hand-written
 * palettes drift: one of them ends up with unreadable muted text and nobody
 * notices until it is on a dashboard in the sun. Here the base decides every
 * neutral and the accent decides exactly two colours, so there are three things
 * to get right instead of eighteen — and `CarThemeTest` checks the contrast of
 * all eighteen anyway.
 */
object CarTheme {

    fun palette(choice: ThemeChoice): CarPalette {
        val accent = choice.accent.colorFor(choice.base)
        return when (choice.base) {
            ThemeBase.DARK -> CarPalette(
                ground = 0xFF0B0E11.toInt(),
                surface = 0xFF151A1F.toInt(),
                surfaceRaised = 0xFF1E252C.toInt(),
                outline = 0xFF2A323A.toInt(),
                text = 0xFFECEFF1.toInt(),
                textMuted = 0xFF90A4AE.toInt(),
                accent = accent,
                accentDim = dim(accent, 0.45f, 0xFF0B0E11.toInt()),
                onAccent = legibleOn(accent, 0xFF0B0E11.toInt()),
                warn = 0xFFFFB34D.toInt(),
                fault = 0xFFFF6B6B.toInt(),
                good = 0xFF66D99A.toInt(),
            )

            ThemeBase.MIDNIGHT -> CarPalette(
                ground = 0xFF000000.toInt(),
                surface = 0xFF0C0C0D.toInt(),
                surfaceRaised = 0xFF17181A.toInt(),
                outline = 0xFF24262A.toInt(),
                text = 0xFFF2F4F6.toInt(),
                textMuted = 0xFF9AA4AD.toInt(),
                accent = accent,
                accentDim = dim(accent, 0.45f, 0xFF000000.toInt()),
                onAccent = legibleOn(accent, 0xFF000000.toInt()),
                warn = 0xFFFFB34D.toInt(),
                fault = 0xFFFF6B6B.toInt(),
                good = 0xFF66D99A.toInt(),
            )

            ThemeBase.LIGHT -> CarPalette(
                ground = 0xFFF3F5F7.toInt(),
                surface = 0xFFFFFFFF.toInt(),
                surfaceRaised = 0xFFE7ECF0.toInt(),
                outline = 0xFFC6CFD6.toInt(),
                text = 0xFF11171C.toInt(),
                textMuted = 0xFF4A5760.toInt(),
                accent = accent,
                accentDim = dim(accent, 0.35f, 0xFFF3F5F7.toInt()),
                onAccent = legibleOn(accent, 0xFF11171C.toInt()),
                warn = 0xFF8A4B00.toInt(),
                fault = 0xFFB00020.toInt(),
                good = 0xFF16643C.toInt(),
            )
        }
    }

    /**
     * Black or white on top of [accent], whichever can actually be read.
     *
     * Fixing this per theme is the obvious thing and it is wrong: a filled pill
     * is the *one* place two chosen colours meet, and which of them wins depends
     * on the accent rather than on how dark the rest of the screen is. Light
     * amber with a white label measures 3.8:1, which is below the floor and
     * looks it in sunlight. Choosing per accent makes every combination legible
     * by construction, and `CarThemeTest` checks all eighteen.
     */
    private fun legibleOn(accent: Int, dark: Int): Int {
        val white = 0xFFFFFFFF.toInt()
        return if (Contrast.ratio(white, accent) >= Contrast.ratio(dark, accent)) white else dark
    }

    /** [color] mixed [amount] of the way towards [towards]. */
    private fun dim(color: Int, amount: Float, towards: Int): Int {
        fun mix(shift: Int): Int {
            val a = (color shr shift) and 0xFF
            val b = (towards shr shift) and 0xFF
            return (a + (b - a) * amount).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    /** Every combination, for a picker and for the contrast test. */
    fun all(): List<ThemeChoice> =
        ThemeBase.entries.flatMap { base -> ThemeAccent.entries.map { ThemeChoice(base, it) } }
}

/**
 * WCAG relative luminance and contrast, so a palette can be *checked* rather
 * than eyeballed.
 *
 * A car screen is read at arm's length, in sunlight, by someone who must look
 * away again immediately. 4.5:1 for body text is the floor, not the target.
 */
object Contrast {

    fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val v = ((color shr shift) and 0xFF) / 255.0
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    /** The WCAG contrast ratio between two opaque colours, 1.0 .. 21.0. */
    fun ratio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /**
     * CIE lightness, 0..100.
     *
     * Not the same thing as [luminance], and the difference is the whole reason
     * this function exists. Relative luminance is linear, so Headway's near-black
     * ground and the panel that sits on it differ by 0.005 of it — a number that
     * says "identical" about two greys anybody can tell apart. L* is the
     * perceptual scale, where the same pair differ by about five, and it is what
     * a question like "can the driver see this panel" has to be asked in.
     */
    fun lightness(color: Int): Double {
        val y = luminance(color)
        return if (y > 0.008856) 116.0 * y.pow(1.0 / 3.0) - 16.0 else 903.3 * y
    }

    /**
     * True when [a] and [b] are near enough to be indistinguishable at a glance.
     *
     * Two points of L* is roughly twice the just-noticeable difference for a
     * large flat area, which is the shape of every surface on this screen.
     */
    fun indistinguishable(a: Int, b: Int): Boolean = abs(lightness(a) - lightness(b)) < 2.0
}

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

package dev.headway.app.ui.theme

import android.content.Context
import dev.headway.app.ui.HeadwaySettings
import dev.headway.dash.CarPalette
import dev.headway.dash.CarTheme
import dev.headway.dash.ThemeChoice
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Which palette [Headway] is drawing with, and who to tell when it changes.
 *
 * ## Why the theme is a process-wide variable
 *
 * Because the alternative is threading a palette through every view builder in
 * the app, and there are hundreds. `Headway.ACCENT` is read from tiles, from the
 * rail, from the phone's setting cards and from three custom `View`s, always as
 * a bare colour with no context to hand. Making it a parameter would be a
 * mechanical change to every one of those call sites for a value that is the
 * same everywhere at any instant — a global by nature, so a global in fact.
 *
 * The cost of a global is the one this class pays for: nothing recomposes on its
 * own. Android views hold resolved colours in drawables and paints, so a change
 * has to be followed by a *rebuild*, and [observe] is how the two surfaces that
 * matter — the car shell and the phone screen — learn to do it.
 *
 * ## Why it is not `AppCompatDelegate.setDefaultNightMode`
 *
 * That switches resource qualifiers, and Headway has no colour resources: every
 * car surface is constructed in code because its geometry comes from the head
 * unit at run time (see [Headway]). Night mode would also follow the *phone's*
 * setting, and the phone is in a pocket while the screen being themed is on a
 * dashboard in daylight. The two are unrelated, and conflating them is how you
 * end up with a car screen that turns white in a tunnel.
 */
object HeadwayTheme {

    fun interface Listener {
        fun onThemeChanged(choice: ThemeChoice)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile
    private var current: ThemeChoice = ThemeChoice.DEFAULT

    /** What the driver has chosen. */
    val choice: ThemeChoice get() = current

    /** The colours in force. Same object [Headway] reads. */
    val palette: CarPalette get() = Headway.palette

    /**
     * Loads the stored choice into the process. Idempotent, cheap, and safe to
     * call from anywhere that is about to draw.
     *
     * Called from `HeadwayApp.onCreate` so that the very first frame of the very
     * first surface is already in the right palette — a car screen that appears
     * in the default theme and corrects itself a moment later looks like a
     * fault, and on a startup animation it is unmissable.
     */
    fun load(context: Context) {
        val stored = runCatching {
            HeadwaySettings.of(context).getString(HeadwaySettings.KEY_THEME, null)
        }.getOrNull()
        apply(ThemeChoice.decode(stored), notify = false)
    }

    /** Records [choice], applies it, and tells everyone drawing. */
    fun set(context: Context, choice: ThemeChoice) {
        runCatching {
            HeadwaySettings.of(context).edit()
                .putString(HeadwaySettings.KEY_THEME, choice.encode())
                .apply()
        }
        apply(choice, notify = true)
    }

    private fun apply(choice: ThemeChoice, notify: Boolean) {
        current = choice
        Headway.palette = CarTheme.palette(choice)
        if (notify) listeners.forEach { runCatching { it.onThemeChanged(choice) } }
    }

    fun observe(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun unobserve(listener: Listener) {
        listeners.remove(listener)
    }

    /** One line for the session log. */
    fun describe(): String =
        "theme ${current.base.displayName.lowercase()}, accent ${current.accent.displayName.lowercase()}"
}

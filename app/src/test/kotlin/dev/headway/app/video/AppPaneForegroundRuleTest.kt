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

package dev.headway.app.video

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A driver reported that talking to the assistant "crashes Headway".
 *
 * Nothing crashed. The pane hides itself when the app in front stops being the
 * one the driver opened -- which is right when they close it, and wrong when an
 * assistant overlay appears over it. Wrong twice over, because dismissing an
 * overlay does not change the state of the window underneath, so no second
 * window event is owed and the pane stayed covered for the rest of the drive.
 * From the passenger seat that is indistinguishable from the app dying.
 */
class AppPaneForegroundRuleTest {

    private val maps = "com.google.android.apps.maps"
    private val assistant = "com.example.assistant"

    @Test
    fun `an ordinary app coming forward replaces the remembered one`() {
        assertEquals(
            maps,
            ForegroundApp.after(
                maps,
                previous = "com.example.launcher",
                assistant = assistant,
                opened = null,
            ),
        )
    }

    @Test
    fun `the assistant coming forward leaves the remembered app alone`() {
        assertEquals(
            maps,
            ForegroundApp.after(assistant, previous = maps, assistant = assistant, opened = null),
            "an assistant session is an overlay over the driver's app, not the driver leaving it",
        )
    }

    @Test
    fun `with no assistant configured every window is an ordinary one`() {
        assertEquals(
            assistant,
            ForegroundApp.after(assistant, previous = maps, assistant = null, opened = null),
            "a phone with no assistant set must not have some package treated as special",
        )
    }

    @Test
    fun `the assistant coming forward first leaves nothing remembered`() {
        // Null means "cannot tell", which shows the picture rather than hiding
        // it. An assistant arriving before any app must not turn that into a
        // confident wrong answer.
        assertEquals(
            null,
            ForegroundApp.after(assistant, previous = null, assistant = assistant, opened = null),
        )
    }

    @Test
    fun `the assistant the driver opened is an app, not an overlay`() {
        // The regression the `opened` parameter exists to stop. The assistant
        // is also an app with an icon, and a driver may pin it and tap it.
        // Treating that window as an overlay leaves the remembered app naming
        // whatever came before, so the pane compares what it was asked to show
        // against a package that can never arrive, decides it was closed, and
        // covers itself -- for good, because no later event can name the
        // assistant either.
        assertEquals(
            assistant,
            ForegroundApp.after(assistant, previous = maps, assistant = assistant, opened = assistant),
        )
        assertFalse(
            ForegroundApp.isAssistantSession(assistant, assistant, opened = assistant),
            "no 'is listening' card over the assistant the driver asked to see",
        )
        assertTrue(
            ForegroundApp.isAssistantSession(assistant, assistant, opened = maps),
            "an assistant over the driver's app is still a voice session",
        )
    }
}

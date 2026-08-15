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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TabIconTest {

    @Test
    fun `an icon this build cannot draw is read as no icon`() {
        // The forward-compatibility rule: a layout saved by a later build that
        // names an icon this one has never heard of must fall back to its own
        // name rather than to somebody else's picture.
        assertNull(TabIcon.of("TELEPORTER"))
        assertNull(TabIcon.of(null))
        assertNull(TabIcon.of(""))
        assertNull(TabIcon.of("   "))
    }

    @Test
    fun `a stored token is read whatever its case`() {
        assertEquals(TabIcon.MAP, TabIcon.of("MAP"))
        assertEquals(TabIcon.MAP, TabIcon.of("map"))
        assertEquals(TabIcon.MUSIC, TabIcon.of("Music"))
    }

    @Test
    fun `an unknown icon survives a round trip rather than being erased`() {
        // The reason `iconName` is a String and `icon` is the resolved value: a
        // layout edited on a build that cannot draw its icon must not silently
        // lose it on the next save.
        val future = DashLayout(
            name = "Drive",
            root = DashNode.Leaf(PaneKind.APP),
            iconName = "TELEPORTER",
        )
        val back = DashLayout.fromJson(future.toJson())
        assertNotNull(back)
        assertEquals("TELEPORTER", back?.iconName, "the token was not preserved")
        assertNull(back?.icon, "an unknown token must not resolve to a drawable icon")
        assertEquals("Drive", back?.name, "the name is the fallback and must survive")
    }

    @Test
    fun `a known icon round-trips`() {
        val layout = DashLayout(
            name = "Music",
            root = DashNode.Leaf(PaneKind.BROWSE),
            iconName = TabIcon.MUSIC.name,
        )
        val back = DashLayout.fromJson(layout.toJson())
        assertEquals(TabIcon.MUSIC, back?.icon)
        assertEquals(layout, back?.copy(locked = layout.locked))
    }

    @Test
    fun `a layout with no icon writes no icon key`() {
        // Keeps a store written by this build byte-comparable with one written
        // before icons existed, which is what makes the upgrade invisible.
        val plain = DashLayout(name = "Home", root = DashNode.Leaf(PaneKind.LAUNCHER))
        assertTrue(!plain.toJson().has("icon"), "an absent icon should not be written")
        assertNull(DashLayout.fromJson(plain.toJson())?.icon)
    }

    @Test
    fun `every offered icon resolves back to itself`() {
        TabIcon.ALL.forEach { icon ->
            assertEquals(icon, TabIcon.of(icon.name), "${icon.name} did not round-trip")
        }
    }
}

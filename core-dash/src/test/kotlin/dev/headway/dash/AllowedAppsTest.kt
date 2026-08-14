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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Which apps may reach the car screen, and the rule that nothing does by default. */
class AllowedAppsTest {

    private val maps = "org.organicmaps"
    private val music = "org.videolan.vlc"

    @Test
    @DisplayName("a fresh install allows nothing at all")
    fun nothingByDefault() {
        assertTrue(AllowedApps.NONE.isEmpty())
        assertFalse(AllowedApps.allows(AllowedApps.NONE, maps))
    }

    @Test
    @DisplayName("allowing is per app and does not touch the others")
    fun allowOne() {
        val allowed = AllowedApps.allow(AllowedApps.NONE, maps)
        assertTrue(AllowedApps.allows(allowed, maps))
        assertFalse(AllowedApps.allows(allowed, music))
    }

    @Test
    @DisplayName("toggling flips exactly one entry back and forth")
    fun toggle() {
        val once = AllowedApps.toggle(AllowedApps.NONE, maps)
        assertTrue(AllowedApps.allows(once, maps))
        val twice = AllowedApps.toggle(once, maps)
        assertFalse(AllowedApps.allows(twice, maps))
        assertEquals(AllowedApps.NONE, twice)
    }

    @Test
    @DisplayName("an app with no name is never allowed, however the list got that way")
    fun blankIsNeverAllowed() {
        val allowed = setOf(maps, "")
        assertFalse(AllowedApps.allows(allowed, ""))
        assertFalse(AllowedApps.allows(allowed, null))
        assertFalse(AllowedApps.allows(allowed, "   "))
        assertEquals(allowed, AllowedApps.allow(allowed, ""))
    }

    @Test
    @DisplayName("filtering keeps the caller's order, which was sorted for a human")
    fun filterKeepsOrder() {
        val candidates = listOf("com.zebra", maps, "com.alpha", music)
        val allowed = setOf(music, "com.zebra", maps)
        assertEquals(listOf("com.zebra", maps, music), AllowedApps.filter(allowed, candidates))
    }

    @Test
    @DisplayName("an uninstalled app's decision is dropped rather than kept for a reinstall")
    fun prune() {
        val allowed = setOf(maps, music)
        assertEquals(setOf(maps), AllowedApps.prune(allowed, setOf(maps, "com.other")))
        assertEquals(AllowedApps.NONE, AllowedApps.prune(allowed, emptySet()))
    }

    @Test
    @DisplayName("denying something that was never allowed is not an error")
    fun denyIsIdempotent() {
        assertEquals(AllowedApps.NONE, AllowedApps.deny(AllowedApps.NONE, maps))
        val allowed = AllowedApps.allow(AllowedApps.NONE, maps)
        assertEquals(AllowedApps.NONE, AllowedApps.deny(AllowedApps.deny(allowed, maps), maps))
    }
}

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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The pinned buttons across the top of the car screen. */
class RailTest {

    private val maps = RailItem.Layout("Maps")
    private val media = RailItem.Layout("Media")
    private val vlc = RailItem.App("org.videolan.vlc")

    @Test
    @DisplayName("order is preserved exactly, because the driver reaches without looking")
    fun orderIsPreserved() {
        val items = listOf(vlc, maps, media)
        assertEquals(items, Rail.decodeAll(Rail.encodeAll(items)))
    }

    @Test
    @DisplayName("pinning something twice pins it once")
    fun pinIsIdempotent() {
        val once = Rail.pin(emptyList(), maps)
        val twice = Rail.pin(once, RailItem.Layout("Maps"))
        assertEquals(1, twice.size)
        assertSame(once, twice)
    }

    @Test
    @DisplayName("unpinning removes exactly one item and keeps the order of the rest")
    fun unpin() {
        val items = listOf(maps, vlc, media)
        assertEquals(listOf(maps, media), Rail.unpin(items, vlc))
        assertEquals(items, Rail.unpin(items, RailItem.App("com.absent")))
    }

    @Test
    @DisplayName("moving an item reorders without losing one")
    fun move() {
        val items = listOf(maps, media, vlc)
        assertEquals(listOf(media, vlc, maps), Rail.move(items, 0, 2))
        assertEquals(listOf(vlc, maps, media), Rail.move(items, 2, 0))
        assertEquals(items, Rail.move(items, 1, 1))
        // Out-of-range indices clamp instead of throwing: the caller is a drag
        // on a car screen that may have ended past the end of the rail.
        assertEquals(listOf(media, vlc, maps), Rail.move(items, 0, 99))
        assertEquals(emptyList<RailItem>(), Rail.move(emptyList(), 0, 1))
    }

    @Test
    @DisplayName("a pin whose layout or app has gone is dropped rather than left dead")
    fun prune() {
        val items = listOf(maps, vlc, media)
        val pruned = Rail.prune(items, setOf("Maps"), setOf("org.videolan.vlc"))
        assertEquals(listOf(maps, vlc), pruned)
        assertEquals(emptyList<RailItem>(), Rail.prune(items, emptySet(), emptySet()))
    }

    @Test
    @DisplayName("keys round trip, and nonsense keys decode to nothing")
    fun keys() {
        listOf(maps, media, vlc).forEach { assertEquals(it, RailItem.fromKey(it.key)) }
        listOf("", "X:thing", "L:", "A:", "no colon").forEach { assertNull(RailItem.fromKey(it)) }
    }

    @Test
    @DisplayName("an empty rail is seeded from the driver's own layouts and nothing else")
    fun seed() {
        val seeded = Rail.seed(listOf("Maps", "Media", "Phone"))
        assertEquals(listOf(maps, media, RailItem.Layout("Phone")), seeded)
        assertTrue(seeded.all { it is RailItem.Layout })
        assertEquals(Rail.COMFORTABLE_LIMIT, Rail.seed((1..20).map { "Tab $it" }).size)
    }

    @Test
    @DisplayName("nothing about decoding a rail throws, whatever the file says")
    fun decodingNeverThrows() {
        listOf(null, "", "  ", "not json", "{}", """{"items":3}""", """{"items":[1,{}]}""")
            .forEach { assertTrue(Rail.decodeAll(it).isEmpty(), "for input $it") }
    }
}

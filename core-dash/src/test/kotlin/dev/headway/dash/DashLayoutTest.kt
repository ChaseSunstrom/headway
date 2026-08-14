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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The edits a driver makes to a layout on the car screen, checked as data.
 *
 * Every one of these is a gesture on a dashboard — split this pane, drag that
 * divider, take that pane away — and the only place they can be checked without
 * a car is here, which is why this module is pure JVM.
 */
class DashLayoutTest {

    private fun leaf(kind: String, argument: String? = null) = DashNode.Leaf(kind, argument)

    private fun single(kind: String = PaneKind.MAPS) = DashLayout("Test", leaf(kind))

    @Test
    @DisplayName("a single pane is one leaf at the empty path")
    fun singlePane() {
        val layout = single()
        assertEquals(1, layout.paneCount)
        assertEquals(listOf(emptyList<Boolean>()), layout.leafPaths().map { it.path })
        assertEquals(PaneKind.MAPS, (layout.nodeAt(emptyList()) as DashNode.Leaf).kind)
    }

    @Test
    @DisplayName("splitting a pane keeps what was there and puts the new pane beside it")
    fun split() {
        val layout = single(PaneKind.MAPS)
            .splitAt(emptyList(), DashNode.Orientation.HORIZONTAL, leaf(PaneKind.NOW_PLAYING))

        assertEquals(2, layout.paneCount)
        assertEquals(
            listOf(PaneKind.MAPS, PaneKind.NOW_PLAYING),
            layout.leaves().map { it.kind },
        )
        val root = layout.root as DashNode.Split
        assertEquals(DashNode.Orientation.HORIZONTAL, root.orientation)
        assertEquals(0.5f, root.ratio)
    }

    @Test
    @DisplayName("panes can be split without limit, and every one keeps its own path")
    fun splitsCompose() {
        var layout = single(PaneKind.MAPS)
        // Split the second child each time: a right-hand column of five panes.
        repeat(4) { index ->
            val path = layout.leafPaths().last().path
            layout = layout.splitAt(
                path,
                DashNode.Orientation.VERTICAL,
                leaf(PaneKind.ALL[index % PaneKind.ALL.size]),
            )
        }
        assertEquals(5, layout.paneCount)
        // Every path resolves to the leaf it names, and no two paths are equal.
        val paths = layout.leafPaths()
        assertEquals(paths.size, paths.map { it.path }.distinct().size)
        paths.forEach { assertEquals(it.leaf, layout.nodeAt(it.path)) }
    }

    @Test
    @DisplayName("removing a pane gives its space to its sibling")
    fun removePromotesSibling() {
        val layout = single(PaneKind.MAPS)
            .splitAt(emptyList(), DashNode.Orientation.HORIZONTAL, leaf(PaneKind.PHONE))
            .removeAt(listOf(true))

        assertEquals(1, layout.paneCount)
        assertEquals(PaneKind.MAPS, (layout.root as DashNode.Leaf).kind)
    }

    @Test
    @DisplayName("removing a pane from a deep tree leaves every other pane alone")
    fun removeDeep() {
        val layout = single(PaneKind.MAPS)
            .splitAt(emptyList(), DashNode.Orientation.HORIZONTAL, leaf(PaneKind.PHONE))
            .splitAt(listOf(true), DashNode.Orientation.VERTICAL, leaf(PaneKind.MESSAGES))

        assertEquals(
            listOf(PaneKind.MAPS, PaneKind.PHONE, PaneKind.MESSAGES),
            layout.leaves().map { it.kind },
        )
        val pruned = layout.removeAt(listOf(true, false))
        assertEquals(
            listOf(PaneKind.MAPS, PaneKind.MESSAGES),
            pruned.leaves().map { it.kind },
        )
    }

    @Test
    @DisplayName("the last pane cannot be removed, because a layout with none is a black screen")
    fun cannotRemoveLastPane() {
        val layout = single()
        assertSame(layout, layout.removeAt(emptyList()))
        assertEquals(1, layout.paneCount)
    }

    @Test
    @DisplayName("an edit naming a path that is not there changes nothing")
    fun staleEditsAreIgnored() {
        val layout = single()
        assertSame(layout, layout.removeAt(listOf(true, true, false)))
        assertEquals(layout, layout.setPane(listOf(false, true), PaneKind.CLOCK))
        assertEquals(layout, layout.withRatio(listOf(true), 0.3f))
        assertEquals(
            layout,
            layout.splitAt(listOf(false), DashNode.Orientation.VERTICAL, leaf(PaneKind.CLOCK)),
        )
    }

    @Test
    @DisplayName("a divider ratio is clamped rather than refused")
    fun ratioClamped() {
        val layout = single()
            .splitAt(emptyList(), DashNode.Orientation.HORIZONTAL, leaf(PaneKind.CLOCK))

        assertEquals(DashNode.MIN_RATIO, (layout.withRatio(emptyList(), -4f).root as DashNode.Split).ratio)
        assertEquals(DashNode.MAX_RATIO, (layout.withRatio(emptyList(), 9f).root as DashNode.Split).ratio)
        assertEquals(0.72f, (layout.withRatio(emptyList(), 0.72f).root as DashNode.Split).ratio)
    }

    @Test
    @DisplayName("setting a pane's content keeps the tree shape")
    fun setPane() {
        val layout = single(PaneKind.MAPS)
            .splitAt(emptyList(), DashNode.Orientation.VERTICAL, leaf(PaneKind.CLOCK))
            .setPane(listOf(true), PaneKind.APP, "org.videolan.vlc")

        val second = layout.nodeAt(listOf(true)) as DashNode.Leaf
        assertEquals(PaneKind.APP, second.kind)
        assertEquals("org.videolan.vlc", second.argument)
        assertEquals(2, layout.paneCount)
    }

    @Test
    @DisplayName("a layout survives a JSON round trip, lock included")
    fun jsonRoundTrip() {
        val layout = DashLayout(
            name = "Drive",
            root = DashNode.Split(
                orientation = DashNode.Orientation.HORIZONTAL,
                ratio = 0.62f,
                first = leaf(PaneKind.APP, "com.example.maps"),
                second = DashNode.Split(
                    orientation = DashNode.Orientation.VERTICAL,
                    ratio = 0.4f,
                    first = leaf(PaneKind.NOW_PLAYING),
                    second = leaf(PaneKind.MESSAGES),
                ),
            ),
            locked = true,
        )

        val back = DashLayout.fromJson(JSONObject(layout.toJson().toString()))
        assertNotNull(back)
        assertEquals(layout, back)
    }

    @Test
    @DisplayName("a layout saved before locking existed comes back unlocked, not frozen")
    fun legacyLayoutsAreUnlocked() {
        val legacy = JSONObject(
            """{"name":"Old","root":{"type":"leaf","kind":"maps"}}""",
        )
        val back = DashLayout.fromJson(legacy)
        assertNotNull(back)
        assertFalse(back!!.locked)
        assertEquals("Old", back.name)
    }

    @Test
    @DisplayName("an unreadable layout is skipped, and the rest of the file still loads")
    fun oneBadEntryDoesNotLoseTheRest() {
        val good = DashLayout("Good", leaf(PaneKind.CLOCK))
        val text = DashLayout.encodeAll(listOf(good))
        val corrupted = text.replace("\"layouts\":[", "\"layouts\":[{\"name\":\"Bad\"},")

        val back = DashLayout.decodeAll(corrupted)
        assertEquals(listOf("Good"), back.map { it.name })
    }

    @Test
    @DisplayName("nothing about decoding throws, whatever the file says")
    fun decodingNeverThrows() {
        listOf(null, "", "   ", "not json", "{}", """{"layouts":7}""", """{"layouts":[1,2]}""")
            .forEach { assertTrue(DashLayout.decodeAll(it).isEmpty(), "for input $it") }
    }

    @Test
    @DisplayName("a legacy mirror pane resolves as an app pane")
    fun legacyMirrorIsAnAppPane() {
        assertEquals(PaneKind.APP, PaneKind.canonical(PaneKind.LEGACY_MIRROR))
        assertTrue(PaneKind.isApp(PaneKind.LEGACY_MIRROR))
        assertFalse(PaneKind.ALL.contains(PaneKind.LEGACY_MIRROR))
    }

    @Test
    @DisplayName("firstPaneOf finds the pane an app should open into, or says there is none")
    fun firstPaneOf() {
        val withApp = single(PaneKind.CLOCK)
            .splitAt(emptyList(), DashNode.Orientation.HORIZONTAL, leaf(PaneKind.APP))
        assertEquals(listOf(true), withApp.firstPaneOf(PaneKind.APP))
        assertNull(single(PaneKind.CLOCK).firstPaneOf(PaneKind.APP))
    }

    @Test
    @DisplayName("locking is a copy, not a mutation")
    fun locking() {
        val layout = single().withLocked(false)
        assertFalse(layout.locked)
        assertTrue(layout.withLocked(true).locked)
        assertFalse(layout.locked)
    }
}

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

package dev.headway.app.dash

import dev.headway.dash.DashLayout
import dev.headway.dash.DashNode
import dev.headway.dash.PaneKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The saved-dashboard store, and the layout tree it persists.
 *
 * ## Why this one runs on the JVM when `HeadUnitQuirksTest` does not
 *
 * That test argues, correctly, that the quirk file must be exercised on a device
 * because `org.json` on Android is not the `org.json` jar a JVM classpath
 * supplies, and the quirk parser leans on exactly the corners where they differ:
 * what `optString` does with a missing key, the iteration order of
 * `JSONObject.keys()`, and what counts as a parse error. That parser is also the
 * recovery mechanism for a car that will not connect, so a stand-in is not good
 * enough.
 *
 * None of that applies here. `DashLayout`'s codec writes and reads its own
 * output, so every round-trip assertion below is closed over one implementation
 * and cannot be sensitive to a difference between two. The only place this test
 * depends on absolute `org.json` behaviour is that malformed input throws rather
 * than parsing, and both implementations throw on all of [CORRUPT] — a missing
 * `{`, an unterminated array, an array key holding an object. The behaviour
 * under test is the *degradation policy*, which is Kotlin in
 * [DashLayoutStore] and `DashLayout.decodeAll`, and which has no device in it.
 *
 * ## What this does not prove
 *
 * That the arrangement in [DashLayoutStore.DEFAULT] is legible in a car. There
 * is no car (BLOCKERS.md B-001) and there is no renderer in this module's test
 * scope, so the default is checked for the structure its KDoc claims and for
 * ratios inside the draggable range — not for whether 304 pixels really holds a
 * transport row on a 2021 Chevrolet Infotainment 3 panel.
 */
class DashLayoutStoreTest {

    // --- the shipped default -------------------------------------------------

    @Test
    fun `the default is the app grid beside a clock stacked over now-playing`() {
        val root = DashLayoutStore.DEFAULT.root as? DashNode.Split
            ?: error("the default must divide the screen")

        assertEquals(DashNode.Orientation.HORIZONTAL, root.orientation)
        assertEquals(DashNode.Leaf(PaneKind.LAUNCHER), root.first)

        val column = root.second as? DashNode.Split
            ?: error("the second pane must be the stacked column")

        assertEquals(DashNode.Orientation.VERTICAL, column.orientation)
        assertEquals(DashNode.Leaf(PaneKind.CLOCK), column.first)
        assertEquals(DashNode.Leaf(PaneKind.NOW_PLAYING), column.second)

        // No legacy mirror pane. It meant "a hole through which the whole
        // mirrored phone screen shows", which the drawn dashboard cannot make;
        // ADR 0010 replaced it with an app pane that hosts a real app inside
        // itself. A shipped default still naming the old kind would resolve to
        // an app pane through PaneKind.canonical -- correct, but by accident.
        assertEquals(
            0,
            DashLayoutStore.DEFAULT.leaves().count { it.kind == PaneKind.LEGACY_MIRROR },
            "the shipped default must not name a pane kind that only exists for old files",
        )
    }

    @Test
    fun `exactly one shipped layout is an app pane, and it is the one apps open into`() {
        val appLayouts = DashLayoutStore.DEFAULT_TABS.filter { layout ->
            layout.leaves().any { PaneKind.isApp(it.kind) }
        }
        assertEquals(1, appLayouts.size, "apps need somewhere to go, and only one somewhere")
        assertEquals(DashLayoutStore.APP_LAYOUT_NAME, appLayouts.single().name)
    }

    @Test
    fun `the default's dividers sit inside the range a driver can drag them to`() {
        DashLayoutStore.DEFAULT.splits().forEach { split ->
            assertEquals(
                split.ratio,
                split.clampedRatio,
                "a shipped default that has to be clamped on read is a shipped default that is wrong",
            )
        }
        // Browse is the larger share: it is a list of titles read at arm's
        // length, and it is the only pane on the dashboard a driver operates
        // rather than glances at.
        assertTrue(
            DashLayoutStore.DEFAULT_BROWSE_SHARE > 0.5f,
            "the library must take more than half the width",
        )
    }

    // --- the codec -----------------------------------------------------------

    @Test
    fun `a nested tree survives encodeAll and decodeAll unchanged`() {
        val layouts = listOf(sample(), DashLayout("Second", DashNode.Leaf(PaneKind.CLOCK)))

        val decoded = DashLayout.decodeAll(DashLayout.encodeAll(layouts))

        // Order matters: it is the order of the picker the driver reads.
        assertEquals(layouts, decoded, "the layout set is not what was written")
    }

    @Test
    fun `a leaf argument survives the round trip`() {
        // The widget leaf in the sample carries "17". An argument that vanished
        // in storage would bind the widget host to nothing on the next drive,
        // and the pane would come up blank rather than broken.
        val decoded = DashLayout.decodeAll(DashLayout.encodeAll(listOf(sample()))).single()
        assertEquals(
            listOf(null, null, null, null, WIDGET_ID),
            decoded.leaves().map { it.argument },
        )
    }

    // --- withRatio -----------------------------------------------------------

    @Test
    fun `withRatio moves the divider the path names and leaves every other one alone`() {
        val before = sample()

        val after = before.withRatio(PATH_INNER, 0.25f)

        assertEquals(0.25f, after.splitNodeAt(PATH_INNER).ratio, "the addressed divider did not move")
        assertEquals(before.splitNodeAt(emptyList()).ratio, after.splitNodeAt(emptyList()).ratio, "the root moved")
        assertEquals(
            before.splitNodeAt(PATH_LEFT).ratio,
            after.splitNodeAt(PATH_LEFT).ratio,
            "the left column moved",
        )
        assertEquals(
            before.splitNodeAt(PATH_RIGHT).ratio,
            after.splitNodeAt(PATH_RIGHT).ratio,
            "the right column moved",
        )
        // Structure and content are untouched; only one number changed.
        assertEquals(before.leaves(), after.leaves(), "moving a divider rearranged the panes")
    }

    @Test
    fun `an empty path addresses the root`() {
        // 0.45 rather than any value already in the tree, so the assertions
        // below cannot pass by coincidence.
        val after = sample().withRatio(emptyList(), 0.45f)

        assertEquals(0.45f, after.splitNodeAt(emptyList()).ratio)
        assertEquals(sample().splitNodeAt(PATH_INNER).ratio, after.splitNodeAt(PATH_INNER).ratio)
    }

    @Test
    fun `a path that runs past a leaf changes nothing`() {
        // Paths are held by whatever drew the dividers, and a layout can be
        // replaced underneath it. Addressing a node that is now a leaf has to be
        // a no-op rather than a crash on a car screen.
        val before = sample()

        val after = before.withRatio(listOf(false, false, true, false), 0.3f)

        assertEquals(before, after)
    }

    // --- clamping ------------------------------------------------------------

    @Test
    fun `withRatio clamps a divider dragged past either end`() {
        val layout = sample()

        assertEquals(
            DashNode.MIN_RATIO,
            layout.withRatio(PATH_INNER, 0f).splitNodeAt(PATH_INNER).ratio,
            "a divider dragged to the left edge must pin at the minimum",
        )
        assertEquals(
            DashNode.MAX_RATIO,
            layout.withRatio(PATH_INNER, 1f).splitNodeAt(PATH_INNER).ratio,
            "a divider dragged to the right edge must pin at the maximum",
        )
        // A gesture that overshoots the widget, which is the case the clamp
        // exists for -- a fling does not stop at the edge of the view.
        assertEquals(
            DashNode.MIN_RATIO,
            layout.withRatio(PATH_INNER, -4f).splitNodeAt(PATH_INNER).ratio,
        )
        assertEquals(
            DashNode.MAX_RATIO,
            layout.withRatio(PATH_INNER, 9f).splitNodeAt(PATH_INNER).ratio,
        )
        // Exactly on the bound is inside it, not outside.
        assertEquals(
            DashNode.MIN_RATIO,
            layout.withRatio(PATH_INNER, DashNode.MIN_RATIO).splitNodeAt(PATH_INNER).ratio,
        )
        assertEquals(
            DashNode.MAX_RATIO,
            layout.withRatio(PATH_INNER, DashNode.MAX_RATIO).splitNodeAt(PATH_INNER).ratio,
        )
        // And a legal value is passed through untouched.
        assertEquals(0.5f, layout.withRatio(PATH_INNER, 0.5f).splitNodeAt(PATH_INNER).ratio)
    }

    @Test
    fun `a stored ratio outside the bounds is kept as written and clamped on read`() {
        // Decoding deliberately does not clamp: the stored file is the driver's,
        // and rewriting their numbers on load would mean a layout that had been
        // hand-edited silently became a different layout. clampedRatio is where
        // the guard lives, so an out-of-range value costs a squashed pane at
        // render time and nothing else.
        val extreme = DashLayout(
            name = "Extreme",
            root = DashNode.Split(
                orientation = DashNode.Orientation.HORIZONTAL,
                ratio = 0.99f,
                first = DashNode.Leaf(PaneKind.LEGACY_MIRROR),
                second = DashNode.Leaf(PaneKind.CLOCK),
            ),
        )

        val decoded = DashLayout.decodeAll(DashLayout.encodeAll(listOf(extreme))).single()
        val split = decoded.splitNodeAt(emptyList())

        assertEquals(0.99f, split.ratio, "the stored value was rewritten on load")
        assertEquals(DashNode.MAX_RATIO, split.clampedRatio, "the render-time guard did not clamp")
    }

    // --- degradation ---------------------------------------------------------

    @Test
    fun `a corrupt store degrades to the default`() {
        CORRUPT.forEach { (text, what) ->
            val store = DashLayoutStore(FakeStorage(DashLayoutStore.KEY_LAYOUTS to text))

            assertEquals(DashLayoutStore.DEFAULT_TABS, store.list(), "list() survived $what")
            assertEquals(DashLayoutStore.DEFAULT, store.active(), "active() survived $what")
        }
    }

    @Test
    fun `a store whose read throws degrades to the default`() {
        // getString throws ClassCastException when the key holds another type,
        // which a restored or hand-edited preferences file can arrange.
        val store = DashLayoutStore(ThrowingStorage())

        assertEquals(DashLayoutStore.DEFAULT_TABS, store.list())
        assertEquals(DashLayoutStore.DEFAULT, store.active())
    }

    @Test
    fun `an unreadable entry costs its own layout and not the others`() {
        val good = DashLayout.encodeAll(listOf(sample()))
        // Splice one unreadable object into an otherwise valid document, the way
        // a partial write or a hand edit would.
        val spliced = good.replace("\"layouts\":[", "\"layouts\":[{\"name\":\"gone\"},")
        // Without this the test would pass by doing nothing if the encoder's
        // output ever stopped matching the pattern above.
        assertNotEquals(good, spliced, "the splice found nothing to splice into")

        val store = DashLayoutStore(FakeStorage(DashLayoutStore.KEY_LAYOUTS to spliced))

        assertEquals(listOf(sample()), store.list(), "a bad neighbour took out a good layout")
    }

    // --- the shipped tabs ------------------------------------------------------

    /**
     * The five tabs are well-formed, which nothing else checks.
     *
     * They are a hand-written literal that the dashboard renders directly, so a
     * typo in a kind string or a duplicated name is a pane that says "No tile
     * for 'maps '" — or two tab pills that both highlight — on a car screen, and
     * the first person to see it would be the driver.
     */
    @Test
    fun `the shipped tabs are renderable and distinct`() {
        val tabs = DashLayoutStore.DEFAULT_TABS
        assertEquals(
            tabs.size,
            tabs.map { it.name }.toSet().size,
            "two shipped tabs share a name, so the tab bar cannot tell them apart",
        )
        assertTrue(
            tabs.any { it.name == DashLayoutStore.DEFAULT_NAME },
            "DEFAULT is looked up by name in the shipped set and must be in it",
        )
        tabs.forEach { tab ->
            kinds(tab.root).forEach { kind ->
                assertTrue(
                    kind in PaneKind.ALL,
                    "the ${tab.name} tab asks for '$kind', which no factory builds",
                )
            }
        }
    }

    /** Every leaf kind in a tree, in no particular order. */
    private fun kinds(node: DashNode): List<String> = when (node) {
        is DashNode.Leaf -> listOf(node.kind)
        is DashNode.Split -> kinds(node.first) + kinds(node.second)
    }

    /**
     * A saved set replaces the shipped one wholesale rather than merging.
     *
     * The alternative — shipped tabs always appended — reads as helpful and is
     * not: a driver who deletes Messages would find it back after the next
     * restart, permanently, with no way to say no.
     */
    @Test
    fun `saving one layout hides the shipped tabs`() {
        val store = DashLayoutStore(FakeStorage())
        store.save(sample())
        assertEquals(listOf(sample()), store.list())
    }

    // --- what the tab editor needs -------------------------------------------

    /**
     * `hasSaved` tells the substitution apart from a real save, which `list`
     * deliberately cannot.
     *
     * The tab editor writes all six shipped tabs before its first edit, and it
     * decides whether to on this. Get it wrong and saving one tab makes the
     * other five disappear — the substitution stops applying the moment
     * anything is stored.
     */
    @Test
    fun `hasSaved distinguishes the shipped tabs from a real one`() {
        val store = DashLayoutStore(FakeStorage())
        assertFalse(store.hasSaved(), "nothing is stored yet")
        assertEquals(DashLayoutStore.DEFAULT_TABS, store.list())

        store.save(sample())
        assertTrue(store.hasSaved(), "a saved layout is a saved layout")
    }

    /** `replaceAll` rewrites the set in one go and leaves the active tab alone. */
    @Test
    fun `replaceAll reorders without losing the active tab`() {
        val store = DashLayoutStore(FakeStorage())
        store.replaceAll(DashLayoutStore.DEFAULT_TABS)
        store.setActive("Phone")

        val reordered = DashLayoutStore.DEFAULT_TABS.reversed()
        store.replaceAll(reordered)

        assertEquals(reordered, store.list())
        assertEquals(
            "Phone",
            store.active().name,
            "reordering must not put the driver back on the first tab",
        )
    }

    /** Replacing with nothing falls back to the shipped set rather than breaking. */
    @Test
    fun `replaceAll with an empty list returns the shipped tabs`() {
        val store = DashLayoutStore(FakeStorage())
        store.save(sample())
        store.replaceAll(emptyList())

        assertFalse(store.hasSaved())
        assertEquals(DashLayoutStore.DEFAULT_TABS, store.list())
    }

    /**
     * The primitives the tab editor's rename is built from.
     *
     * Rename used to be delete-then-save, which had two defects that only show
     * up in sequence: `delete` clears the active pointer when it removes the
     * active layout, and `save` appends an unrecognised name to the end. So a
     * renamed tab jumped to the far right of the car's tab bar and, if the
     * driver was on it, the next drive opened on the default tab instead. This
     * pins the behaviour the fixed version relies on.
     */
    @Test
    fun `delete clears the active pointer and save appends an unknown name`() {
        val store = DashLayoutStore(FakeStorage())
        store.replaceAll(DashLayoutStore.DEFAULT_TABS)
        store.setActive("Drive")
        assertEquals("Drive", store.active().name)

        store.delete("Drive")
        assertNotEquals(
            "Drive",
            store.active().name,
            "delete must drop the pointer it invalidated",
        )

        store.save(DashLayout("Navigation", DashNode.Leaf(PaneKind.MAPS)))
        assertEquals(
            "Navigation",
            store.list().last().name,
            "an unknown name goes to the end, which is why rename must not use save",
        )
    }

    /**
     * Replacing in place keeps the position, which is what rename needs.
     */
    @Test
    fun `replaceAll can rename in place without moving the tab`() {
        val store = DashLayoutStore(FakeStorage())
        store.replaceAll(DashLayoutStore.DEFAULT_TABS)
        store.setActive("Drive")

        val updated = DashLayoutStore.DEFAULT_TABS.toMutableList()
        updated[0] = DashLayout("Navigation", updated[0].root)
        store.replaceAll(updated)
        store.setActive("Navigation")

        assertEquals("Navigation", store.list().first().name, "the tab moved")
        assertEquals("Navigation", store.active().name, "the active tab was lost")
        assertEquals(
            DashLayoutStore.DEFAULT_TABS.size,
            store.list().size,
            "renaming changed the tab count",
        )
    }

    /**
     * Saving over an existing name is a merge, which is why rename must refuse
     * it rather than call through.
     */
    @Test
    fun `saving under a name already in use replaces that layout`() {
        val store = DashLayoutStore(FakeStorage())
        store.replaceAll(DashLayoutStore.DEFAULT_TABS)
        val before = store.list().size

        store.save(DashLayout("Drive", DashNode.Leaf(PaneKind.PHONE)))

        assertEquals(before, store.list().size, "save is keyed on the name")
        assertEquals(
            DashNode.Leaf(PaneKind.PHONE),
            store.list().first { it.name == "Drive" }.root,
            "the existing Maps layout should have been overwritten in place",
        )
    }

    // --- the store's bookkeeping ---------------------------------------------

    @Test
    fun `the default is never written to storage`() {
        val storage = FakeStorage()
        val store = DashLayoutStore(storage)

        assertEquals(DashLayoutStore.DEFAULT_TABS, store.list())
        assertEquals(DashLayoutStore.DEFAULT, store.active())

        // Reading must not seed. If it did, the shipped layout would become an
        // ordinary row frozen at this version of Headway -- see the note on
        // DashLayoutStore.DEFAULT.
        assertEquals(0, storage.writes, "reading the store wrote to it")
        assertTrue(storage.values.isEmpty(), "the default was persisted")
    }

    @Test
    fun `saving the first layout replaces the default rather than joining it`() {
        val storage = FakeStorage()
        val store = DashLayoutStore(storage)

        store.save(sample())

        assertEquals(listOf(sample()), store.list())
        assertFalse(
            store.list().contains(DashLayoutStore.DEFAULT),
            "the default is a fallback, not a row",
        )
    }

    @Test
    fun `save replaces by name and keeps the entry where it was`() {
        val store = DashLayoutStore(FakeStorage())
        val first = DashLayout("First", DashNode.Leaf(PaneKind.CLOCK))
        val last = DashLayout("Last", DashNode.Leaf(PaneKind.LAUNCHER))
        store.save(first)
        store.save(sample())
        store.save(last)

        val edited = sample().withRatio(PATH_INNER, 0.3f)
        store.save(edited)

        assertEquals(listOf(first, edited, last), store.list(), "the edited layout moved in the picker")
        assertNotEquals(sample(), store.list()[1], "the edit was not saved")
    }

    @Test
    fun `setActive selects a saved layout and an unknown name falls back`() {
        val store = DashLayoutStore(FakeStorage())
        val first = DashLayout("First", DashNode.Leaf(PaneKind.CLOCK))
        val second = DashLayout("Second", DashNode.Leaf(PaneKind.LAUNCHER))
        store.save(first)
        store.save(second)

        store.setActive("Second")
        assertEquals(second, store.active())

        // Selecting before saving is allowed; active() simply falls back until
        // the layout arrives, and then picks it up.
        store.setActive("Third")
        assertEquals(first, store.active(), "an unknown name must fall back to the first saved layout")
        val third = DashLayout("Third", DashNode.Leaf(PaneKind.MESSAGES))
        store.save(third)
        assertEquals(third, store.active())
    }

    @Test
    fun `delete forgets the layout and the pointer to it`() {
        val storage = FakeStorage()
        val store = DashLayoutStore(storage)
        val keep = DashLayout("Keep", DashNode.Leaf(PaneKind.CLOCK))
        store.save(keep)
        store.save(sample())
        store.setActive(sample().name)

        store.delete(sample().name)

        assertEquals(listOf(keep), store.list())
        assertEquals(keep, store.active())
        assertFalse(
            storage.values.containsKey(DashLayoutStore.KEY_ACTIVE_LAYOUT),
            "a dangling active name would resurrect the moment the name was reused",
        )

        // Reusing the name must not silently make the new layout active.
        val reused = DashLayout(sample().name, DashNode.Leaf(PaneKind.WIDGET, WIDGET_ID))
        store.save(reused)
        assertEquals(keep, store.active())
    }

    @Test
    fun `deleting everything returns to the default`() {
        val store = DashLayoutStore(FakeStorage())
        store.save(sample())

        store.delete(sample().name)

        assertEquals(DashLayoutStore.DEFAULT_TABS, store.list())
        assertEquals(DashLayoutStore.DEFAULT, store.active())
    }

    @Test
    fun `deleting a name nobody used is not an error`() {
        val store = DashLayoutStore(FakeStorage())
        store.save(sample())

        store.delete("never existed")

        assertEquals(listOf(sample()), store.list())
    }

    // --- fixtures ------------------------------------------------------------

    /**
     * A four-way tree whose dividers all differ, so a test that moves one can
     * tell which one moved.
     *
     * ```
     * root .50  +-------------+-------------+
     *           |  clock .30  | now playing |
     *           +-------------+------.70----+
     *           |  launcher   | msgs .40 wid|
     *           +-------------+-------------+
     * ```
     */
    private fun sample(): DashLayout = DashLayout(
        name = "Sample",
        root = DashNode.Split(
            orientation = DashNode.Orientation.HORIZONTAL,
            ratio = 0.50f,
            first = DashNode.Split(
                orientation = DashNode.Orientation.VERTICAL,
                ratio = 0.30f,
                first = DashNode.Leaf(PaneKind.CLOCK),
                second = DashNode.Leaf(PaneKind.LAUNCHER),
            ),
            second = DashNode.Split(
                orientation = DashNode.Orientation.VERTICAL,
                ratio = 0.70f,
                first = DashNode.Leaf(PaneKind.NOW_PLAYING),
                second = DashNode.Split(
                    orientation = DashNode.Orientation.HORIZONTAL,
                    ratio = 0.40f,
                    first = DashNode.Leaf(PaneKind.MESSAGES),
                    second = DashNode.Leaf(PaneKind.WIDGET, WIDGET_ID),
                ),
            ),
        ),
    )

    /**
     * Walks a path the way `DashLayout.withRatio` documents it — false for
     * `first`, true for `second` — and insists on finding a divider there.
     *
     * Written independently of the production walker on purpose: a test that
     * navigated with the code under test would agree with it about a wrong
     * answer.
     */
    private fun DashLayout.splitNodeAt(path: List<Boolean>): DashNode.Split {
        var node: DashNode = root
        path.forEach { goSecond ->
            val split = node as? DashNode.Split ?: error("no divider along $path")
            node = if (goSecond) split.second else split.first
        }
        return node as? DashNode.Split ?: error("$path names a pane, not a divider")
    }

    private fun DashLayout.splits(): List<DashNode.Split> = collectSplits(root)

    private fun collectSplits(node: DashNode): List<DashNode.Split> = when (node) {
        is DashNode.Leaf -> emptyList()
        is DashNode.Split -> listOf(node) + collectSplits(node.first) + collectSplits(node.second)
    }

    /** [DashLayoutStore.Storage] as a map, so the rules can run off a phone. */
    private class FakeStorage(vararg initial: Pair<String, String>) : DashLayoutStore.Storage {

        val values: MutableMap<String, String> = initial.toMap().toMutableMap()

        var writes: Int = 0
            private set

        override fun read(key: String): String? = values[key]

        override fun write(edits: Map<String, String?>) {
            writes++
            edits.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
            }
        }
    }

    /** What a preferences file holding the wrong type under a key looks like. */
    private class ThrowingStorage : DashLayoutStore.Storage {

        override fun read(key: String): String? =
            throw ClassCastException("$key holds a java.util.HashSet, not a String")

        override fun write(edits: Map<String, String?>) = Unit
    }

    private companion object {

        /** The widget leaf's binding; any non-empty string, only its survival matters. */
        const val WIDGET_ID = "17"

        /** The innermost divider of `sample()`: root -> second -> second. */
        val PATH_INNER = listOf(true, true)

        /** The left column of `sample()`. */
        val PATH_LEFT = listOf(false)

        /** The right column of `sample()`. */
        val PATH_RIGHT = listOf(true)

        /**
         * Stored text that is not a layout set, with what is wrong with each.
         *
         * Every one of these is rejected identically by Android's `org.json` and
         * by the JVM jar — none of them turns on a leniency the two disagree
         * about. They are the shapes a preferences file actually arrives in when
         * something has gone wrong: emptied, truncated mid-write, restored from
         * a backup written by different software, or edited by hand.
         */
        val CORRUPT: List<Pair<String, String>> = listOf(
            "" to "an empty value",
            "    " to "whitespace",
            "not json at all" to "text that is not JSON",
            "{\"layouts\":[" to "a document truncated mid-write",
            "{\"layouts\":{}}" to "a layouts key holding an object",
            "{\"somethingElse\":1}" to "a document with no layouts key",
            "{\"layouts\":[1,2,3]}" to "entries that are not objects",
            "{\"layouts\":[{\"name\":\"rootless\"}]}" to "an entry with no root",
            "{\"layouts\":[{\"name\":\"n\",\"root\":{\"type\":\"nonsense\"}}]}" to "an unknown node type",
            "{\"layouts\":[{\"root\":{\"type\":\"leaf\",\"kind\":\"clock\"}}]}" to "an entry with no name",
        )
    }
}

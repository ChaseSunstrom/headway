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

import android.content.Context
import android.content.SharedPreferences
import dev.headway.app.ui.HeadwaySettings
import dev.headway.dash.DashLayout
import dev.headway.dash.Rail
import dev.headway.dash.RailItem
import dev.headway.dash.DashNode
import dev.headway.dash.PaneKind
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The dashboards the driver has saved, and which one is on the car screen.
 *
 * ## Where this lives
 *
 * In the same `SharedPreferences` file as everything else the user has chosen,
 * for the reason `HeadwaySettings` already gives: no migration story is needed,
 * and one fewer dependency is one less thing for an F-Droid audit to read. It
 * also means the driver's layouts, their pinned apps and their safety-notice
 * acknowledgement are one file — they are cleared together by "clear app data"
 * and restored together, which is what a user expects of settings and would not
 * be true if layouts lived off in `filesDir` on their own.
 *
 * The two keys are declared here rather than in `HeadwaySettings` because they
 * are not shared. `HeadwaySettings` exists so that two activities writing the
 * same scalar cannot disagree about its name; these two are read and written by
 * this class alone, and their *values* are meaningless without the codec in
 * `DashLayout`. Putting the key in one file and the format that fills it in
 * another would separate the two things that have to change together.
 *
 * ## Nothing here throws
 *
 * Same policy as `QuirkStore`: a store that cannot be read degrades to
 * [DEFAULT]. [DashLayout.decodeAll] already promises to skip entries it cannot
 * read rather than fail the batch, so the layer this class adds is around the
 * *read itself*. `SharedPreferences.getString` throws `ClassCastException` when
 * the key holds a value of some other type. No build of Headway can put one
 * there — the keys are distinct from the pinned-app string set and from the
 * booleans — but this file survives cloud backup, restore onto a different
 * phone, and hand editing on a rooted one, and one wrong type in it should cost
 * the driver their saved layouts, not their dashboard.
 *
 * ## Why the storage is behind an interface
 *
 * `SharedPreferences` is an Android interface, and on the unit-test classpath it
 * is the stub that throws from every method. The part of this class that has
 * bugs in it is the degradation rules and the save/delete/active bookkeeping,
 * none of which is about Android, so [Storage] is the seam that lets those run
 * on the build machine — the same shape as `QuirkStore` taking a `File` and
 * offering an `inAppStorage` factory beside it.
 */
class DashLayoutStore(private val storage: Storage) {

    /**
     * Told when the tab set has been edited. See [Companion.announceChanged].
     *
     * Nested in the class rather than in the companion so that callers write
     * `DashLayoutStore.Listener` — a classifier inside a companion object is
     * reached as `DashLayoutStore.Companion.Listener`, which nothing should
     * have to know.
     */
    fun interface Listener {
        /**
         * @param source whatever the writer passed to [Companion.announceChanged],
         *   so a listener can recognise its own edit and not rebuild itself in
         *   response to it. The car shell both writes layouts and draws them;
         *   without this it would tear down and re-inflate every pane — dropping
         *   a live car-app session and a media browser mid-list — on each nudge
         *   of a divider.
         */
        fun onLayoutsChanged(source: Any?)
    }

    /**
     * Everything the driver has saved, or just [DEFAULT] when they have saved
     * nothing readable.
     *
     * Never empty, which is what makes [active] total.
     */
    fun list(): List<DashLayout> = stored().ifEmpty { DEFAULT_TABS }

    /**
     * The layout that should be on the car screen.
     *
     * Falls back twice: to [DEFAULT] when the recorded name matches nothing, and
     * to the first tab when even that is gone. A dashboard that comes up showing
     * the wrong arrangement is a nuisance; one that comes up showing nothing
     * because a name went stale is a black screen in a moving car.
     *
     * The middle step is why [DEFAULT] is consulted by name rather than the
     * first tab simply being taken: the shipped set can be reordered by a later
     * build, and "wherever the driver left off, else the one we call the
     * default, else whatever exists" is the only ordering of those fallbacks
     * that never opens on an empty pane.
     */
    fun active(): DashLayout {
        val all = list()
        val wanted = activeName()
        return all.firstOrNull { it.name == wanted }
            ?: all.firstOrNull { it.name == DEFAULT_NAME }
            ?: all.first()
    }

    /**
     * Saves [layout], replacing any existing one of the same name.
     *
     * Name is the key — `DashLayout.name` is documented as such — so saving over
     * a name is how the driver edits a layout, including how they replace the
     * shipped [DEFAULT_NAME] one with their own.
     *
     * The replacement keeps the old entry's position rather than moving it to
     * the end. A driver who nudges a divider and saves is not asking for the
     * picker to reorder itself under their finger.
     */
    fun save(layout: DashLayout) {
        val existing = stored()
        val index = existing.indexOfFirst { it.name == layout.name }
        val updated = if (index < 0) {
            existing + layout
        } else {
            existing.toMutableList().also { it[index] = layout }
        }
        storage.write(mapOf(KEY_LAYOUTS to DashLayout.encodeAll(updated)))
    }

    /**
     * Forgets the layout called [name]. Deleting something that is not there is
     * not an error.
     *
     * If the deleted layout was the active one, the pointer to it is dropped in
     * the same write. Leaving it would be invisible today — [active] falls back —
     * but it would come back to life the moment the driver saved a *different*
     * layout under the reused name, which would then silently be on screen.
     */
    fun delete(name: String) {
        val remaining = stored().filterNot { it.name == name }
        val edits = mutableMapOf<String, String?>(
            KEY_LAYOUTS to DashLayout.encodeAll(remaining),
        )
        if (activeName() == name) edits[KEY_ACTIVE_LAYOUT] = null
        storage.write(edits)
    }

    /**
     * Whether the driver has saved anything, as opposed to [list] substituting
     * the shipped set.
     *
     * The tab editor needs this and cannot get it from [list], which by design
     * cannot tell the two apart. The first edit of any kind has to write *all*
     * of the shipped tabs first, or saving one would make the other five
     * disappear the moment the substitution stopped applying.
     */
    fun hasSaved(): Boolean = stored().isNotEmpty()

    /**
     * Replaces the whole set, in one write.
     *
     * For reordering, which is the one edit that is not about a single layout.
     * Doing it as delete-then-save per tab would work and would also drop the
     * active pointer the moment the active tab's turn came round, putting the
     * driver back on the first tab every time they nudged the order.
     */
    fun replaceAll(layouts: List<DashLayout>) {
        storage.write(mapOf(KEY_LAYOUTS to DashLayout.encodeAll(layouts)))
    }

    /**
     * Records which layout should be on screen.
     *
     * Deliberately not checked against [list]. A caller that creates a layout and
     * selects it may do the two in either order, and an unknown name is already
     * handled by [active]'s fallback — so validating here would buy nothing and
     * would make the order matter.
     */
    fun setActive(name: String) {
        storage.write(mapOf(KEY_ACTIVE_LAYOUT to name))
    }

    /**
     * The rail: what the driver pinned across the top of the car screen, in
     * their order.
     *
     * Seeded from the layouts they have rather than left empty, because an empty
     * rail is a car screen with two buttons on it and no way to reach the second
     * layout. Pruned on the way out, so a pin naming a layout they deleted is
     * gone rather than dead — see `Rail.prune`; the caller supplies the set of
     * installed packages because this class has no `PackageManager`.
     */
    fun rail(installedPackages: Set<String>): List<RailItem> {
        val stored = Rail.decodeAll(runCatching { storage.read(KEY_RAIL) }.getOrNull())
        val names = list().map { it.name }
        val pruned = Rail.prune(stored, names.toSet(), installedPackages)
        return pruned.ifEmpty { Rail.seed(names) }
    }

    /** Records the rail exactly as given, order included. */
    fun setRail(items: List<RailItem>) {
        storage.write(mapOf(KEY_RAIL to Rail.encodeAll(items)))
    }

    /** The name recorded by [setActive], or null if there is none or it is unreadable. */
    private fun activeName(): String? = runCatching { storage.read(KEY_ACTIVE_LAYOUT) }.getOrNull()

    /**
     * Exactly what is in storage, which may legitimately be nothing.
     *
     * Separate from [list] so that [save] and [delete] operate on the real set
     * and never accidentally write [DEFAULT] into it — see the note on [DEFAULT]
     * for why that matters.
     */
    private fun stored(): List<DashLayout> =
        DashLayout.decodeAll(runCatching { storage.read(KEY_LAYOUTS) }.getOrNull())

    /**
     * The two string slots this class needs, so the store can be exercised
     * without Android.
     *
     * Writes are batched because [delete] changes two keys and a half-applied
     * delete — layouts gone, active pointer still naming one of them — is a state
     * no reader should ever see. `SharedPreferences.Editor` is already a batch,
     * so this costs nothing to honour.
     */
    interface Storage {

        /** The stored value, or null when the key is absent. */
        fun read(key: String): String?

        /** Applies every entry at once. A null value removes that key. */
        fun write(edits: Map<String, String?>)
    }

    companion object {

        /** The serialised layout set; see `DashLayout.encodeAll`. */
        const val KEY_LAYOUTS: String = "dash_layouts"

        /** The name of the layout to show. */
        const val KEY_ACTIVE_LAYOUT: String = "dash_active_layout"

        /** The pinned rail; see `Rail.encodeAll`. */
        const val KEY_RAIL: String = HeadwaySettings.KEY_RAIL

        /** The tab a fresh install lands on. */
        const val DEFAULT_NAME: String = "Home"

        /**
         * Width given to the browse pane in the Media tab.
         *
         * A fraction and not a pixel count on purpose: `CarVideoStream`
         * negotiates the real geometry from the configuration the head unit
         * selects, so nothing here may assume 800x480 even though that is the
         * class of panel the target vehicle has. On a panel that *is* 800 wide
         * this leaves 440 pixels for the library list and 360 for the column
         * beside it, and 360 is about seven 48 dp targets at the density such
         * panels report.
         *
         * The draggable range is deliberately wider than the useful one. At
         * [DashNode.MAX_RATIO] the column would be 120 pixels and hold no
         * transport row at all; the default is set where both sides still work,
         * and the driver is left free to trade one against the other.
         */
        const val DEFAULT_BROWSE_SHARE: Float = 0.55f

        /**
         * Height given to now-playing in the Media tab's right-hand column.
         *
         * The larger share goes to the pane that cannot shrink. Now-playing has
         * a floor — artwork, two lines of text and a 48 dp transport row do not
         * compress — whereas an app grid reflows to whatever height it is given.
         */
        const val DEFAULT_NOW_PLAYING_SHARE: Float = 0.55f

        /**
         * The tabs a driver gets before they have made any.
         *
         * ## Why tabs at all
         *
         * A single split tree on a 480-pixel-tall panel runs out of room at
         * three panes, which is how Messages came to be dropped from the
         * shipped arrangement — a bad trade, and an unnecessary one. Tabs are
         * the obvious answer and they cost almost nothing here: this store
         * already keeps *named* layouts and an active name, so a tab is a
         * layout and the tab bar is a picker over [list].
         *
         * ## Why these five
         *
         * They are the destinations a car has, and each one is a *layout* the
         * driver can edit, rename, unpin or throw away — not a fixture. The
         * shipped set is an opening offer.
         *
         * ```
         * Home    the app grid ∥ (clock / now playing)
         * Drive   the map ∥ (now playing / messages)
         * Media   library ∥ (now playing / apps)
         * Phone   recent calls ∥ now playing
         * App     one pane, an app in it, full width
         * ```
         *
         * `App` is the one that would not have been possible before ADR 0010:
         * its single pane hosts a real third-party app's own rendering, which
         * is where a tap on the rail or in the grid now sends one.
         *
         * ## Why this is never written to storage
         *
         * [list] substitutes these when the store is empty instead of seeding
         * them on first run. Seeding would make the shipped tabs ordinary rows
         * the driver could delete and never get back, and would freeze them at
         * whatever version of Headway happened to be installed first — a later
         * build with better defaults would never reach an existing phone. Kept
         * virtual, a driver who deletes everything lands back on the current
         * set, and the current set is always this one.
         */
        val DEFAULT_TABS: List<DashLayout> = listOf(
            DashLayout(
                name = DEFAULT_NAME,
                root = DashNode.Split(
                    orientation = DashNode.Orientation.HORIZONTAL,
                    ratio = 0.58f,
                    first = DashNode.Leaf(PaneKind.LAUNCHER),
                    second = DashNode.Split(
                        orientation = DashNode.Orientation.VERTICAL,
                        ratio = 0.42f,
                        // Not CLOCK: the rail carries the time by default, and
                        // a shipped layout that puts a second clock beside it
                        // spends a pane on something already on screen. The car
                        // pane is the one that cannot be anywhere else.
                        first = DashNode.Leaf(PaneKind.SENSORS),
                        second = DashNode.Leaf(PaneKind.NOW_PLAYING),
                    ),
                ),
            ),
            DashLayout(
                name = "Drive",
                root = DashNode.Split(
                    orientation = DashNode.Orientation.HORIZONTAL,
                    ratio = 0.62f,
                    first = DashNode.Leaf(PaneKind.MAPS),
                    second = DashNode.Split(
                        orientation = DashNode.Orientation.VERTICAL,
                        ratio = DEFAULT_NOW_PLAYING_SHARE,
                        first = DashNode.Leaf(PaneKind.NOW_PLAYING),
                        second = DashNode.Leaf(PaneKind.MESSAGES),
                    ),
                ),
            ),
            DashLayout(
                name = "Media",
                root = DashNode.Split(
                    orientation = DashNode.Orientation.HORIZONTAL,
                    ratio = DEFAULT_BROWSE_SHARE,
                    first = DashNode.Leaf(PaneKind.BROWSE),
                    second = DashNode.Split(
                        orientation = DashNode.Orientation.VERTICAL,
                        ratio = DEFAULT_NOW_PLAYING_SHARE,
                        first = DashNode.Leaf(PaneKind.NOW_PLAYING),
                        second = DashNode.Leaf(PaneKind.LAUNCHER),
                    ),
                ),
            ),
            DashLayout(
                name = "Phone",
                root = DashNode.Split(
                    orientation = DashNode.Orientation.HORIZONTAL,
                    ratio = 0.55f,
                    first = DashNode.Leaf(PaneKind.PHONE),
                    second = DashNode.Leaf(PaneKind.NOW_PLAYING),
                ),
            ),
            DashLayout(
                name = APP_LAYOUT_NAME,
                root = DashNode.Leaf(PaneKind.APP),
            ),
        )

        /**
         * The layout an app opens into when the one on screen has no app pane.
         *
         * One pane, the whole screen, the app in it. It is what "full screen"
         * means now that there is no full-screen *mode* — a layout like any
         * other, which the driver can rename, re-split or unpin.
         */
        const val APP_LAYOUT_NAME: String = "App"

        /** The tab shown when nothing else is chosen. */
        val DEFAULT: DashLayout = DEFAULT_TABS.first { it.name == DEFAULT_NAME }

        /**
         * Told when the tab set has been edited, so a live car screen follows.
         *
         * The car reads the store once, when its surface is built, and a
         * session lasts a whole drive — so a tab renamed or deleted on the
         * phone left the car showing pills that no longer existed, and tapping
         * one wrote a dead name into the active pointer, which sent the *next*
         * drive to the default tab. Nothing else connects the two: the editor
         * is an Activity on the phone and the dashboard is a Presentation on a
         * virtual display, with no handle from either to the other.
         *
         * Deliberately fired by the editor alone, never by the store's own
         * writes. `show()` on the car calls `setActive` on every tab switch,
         * and a store that announced its own writes would make the car reload
         * itself in response to its own tap.
         */
        private val listeners = CopyOnWriteArrayList<Listener>()

        fun observeChanges(listener: Listener) {
            listeners.addIfAbsent(listener)
        }

        fun unobserveChanges(listener: Listener) {
            listeners.remove(listener)
        }

        /**
         * Called after any change the other surfaces should see.
         *
         * @param source the object that made the edit, or null when it has no
         *   identity worth reporting. Listeners compare it against themselves.
         */
        fun announceChanged(source: Any? = null) {
            listeners.forEach { runCatching { it.onLayoutsChanged(source) } }
        }

        /** The store backed by the app's ordinary preferences. */
        fun of(context: Context): DashLayoutStore =
            DashLayoutStore(PreferenceStorage(HeadwaySettings.of(context)))

        /**
         * [Storage] over the real preferences file.
         *
         * `apply()` rather than `commit()`, matching every other write in the app:
         * the change is visible to the next read through the same
         * `SharedPreferences` instance immediately, and `HeadwaySettings.of`
         * returns the process-wide instance for this file, so a save followed by a
         * read sees the new value without a disk write on the caller's thread.
         * Dragging a divider writes on every gesture end, and that thread is the
         * one drawing the car screen.
         */
        private class PreferenceStorage(private val prefs: SharedPreferences) : Storage {

            override fun read(key: String): String? = prefs.getString(key, null)

            override fun write(edits: Map<String, String?>) {
                val editor = prefs.edit()
                edits.forEach { (key, value) ->
                    if (value == null) editor.remove(key) else editor.putString(key, value)
                }
                editor.apply()
            }
        }
    }
}

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
     * Everything the driver has saved, or just [DEFAULT] when they have saved
     * nothing readable.
     *
     * Never empty, which is what makes [active] total.
     */
    fun list(): List<DashLayout> = stored().ifEmpty { listOf(DEFAULT) }

    /**
     * The layout that should be on the car screen.
     *
     * Falls back twice: to the first saved layout when the recorded name matches
     * nothing, and to [DEFAULT] when nothing is saved. A dashboard that comes up
     * showing the wrong arrangement is a nuisance; one that comes up showing
     * nothing because a name went stale is a black screen in a moving car.
     */
    fun active(): DashLayout {
        val all = list()
        val wanted = activeName()
        return all.firstOrNull { it.name == wanted } ?: all.first()
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

        /** What the shipped arrangement is called in a picker. */
        const val DEFAULT_NAME: String = "Dashboard"

        /**
         * Width given to the browse pane in [DEFAULT].
         *
         * A fraction and not a pixel count on purpose: `CarVideoStream` negotiates
         * the real geometry from the configuration the head unit selects, so
         * nothing here may assume 800x480 even though that is the class of panel
         * the target vehicle has. On a panel that *is* 800 wide this leaves 440
         * pixels for the library list and 360 for the column beside it, and 360
         * is about seven 48 dp targets at the density such panels report — enough
         * for album art beside a title with a row of transport buttons under it.
         *
         * The draggable range is deliberately wider than the useful one. At
         * [DashNode.MAX_RATIO] the column would be 120 pixels and hold no
         * transport row at all; the default is set where both sides still work,
         * and the driver is left free to trade one against the other.
         */
        const val DEFAULT_BROWSE_SHARE: Float = 0.55f

        /**
         * Height given to now-playing in [DEFAULT]'s right-hand column.
         *
         * The larger share goes to the pane that cannot shrink. Now-playing has a
         * floor — artwork, two lines of text and a 48 dp transport row do not
         * compress — whereas an app grid reflows to whatever height it is given
         * and simply shows fewer rows. So now-playing takes what it needs and the
         * grid absorbs the remainder, rather than both being squeezed equally
         * until neither works.
         */
        const val DEFAULT_NOW_PLAYING_SHARE: Float = 0.55f

        /**
         * The arrangement a driver gets before they have made one.
         *
         * ```
         * +---------------------+-------------+
         * |                     | Now playing |
         * |  Music & podcasts   +-------------+
         * |                     |    Apps     |
         * +---------------------+-------------+
         * ```
         *
         * ## Why browse is the big pane
         *
         * Because it is the pane a driver actually operates. Until this build the
         * dashboard could show what was *already* playing and could not start
         * anything: no library, no playlists, no podcast episodes. That made the
         * whole surface read-only for the one thing a car stereo is for.
         * `MediaBrowseTile` walks the tree an app publishes through
         * `MediaBrowserService` — which is precisely, and not merely
         * analogously, what Android Auto does with a music app — and it needs the
         * width because it is a list of titles.
         *
         * The previous default gave the big pane to [DashTile.Kind.MIRROR], from
         * when the car screen was a copy of the phone's. ADR 0006 ended that: the
         * dashboard is drawn at the car's own resolution and a mirror pane cannot
         * exist on it, so `DashboardPresentation` resolves a MIRROR leaf to the
         * app grid. Saved layouts from before keep working; the shipped default
         * no longer pretends there is a hole to look through.
         *
         * Beside it go the two panes that exist precisely because they *cannot*
         * be another app's window: now-playing, drawn from `MediaSessionManager`,
         * and the app grid, which is the door to full-screen mirroring for
         * anything Headway cannot model. Messages is one layout edit away and is
         * left out of the default because three scrolling lists on a 480-pixel
         * panel is one too many.
         *
         * [DashNode.Orientation.HORIZONTAL] is read here as "children side by
         * side, divider vertical" — the `LinearLayout.HORIZONTAL` sense — which
         * makes [DashNode.Split.ratio] a fraction of the width. The renderer has
         * to take the same reading or this default comes out rotated; it is
         * written down here because the tree itself cannot say which way round it
         * meant.
         *
         * ## Why this is never written to storage
         *
         * [list] substitutes it when the store is empty instead of seeding it on
         * first run. Seeding would make the shipped layout an ordinary row the
         * driver could delete and never get back, and it would freeze it at
         * whatever version of Headway happened to be installed first — a later
         * build with a better default would never reach an existing phone. Kept
         * virtual, a driver who deletes everything lands back on the current
         * default, and the current default is always this one.
         */
        val DEFAULT: DashLayout = DashLayout(
            name = DEFAULT_NAME,
            root = DashNode.Split(
                orientation = DashNode.Orientation.HORIZONTAL,
                ratio = DEFAULT_BROWSE_SHARE,
                first = DashNode.Leaf(DashTile.Kind.BROWSE),
                second = DashNode.Split(
                    orientation = DashNode.Orientation.VERTICAL,
                    ratio = DEFAULT_NOW_PLAYING_SHARE,
                    first = DashNode.Leaf(DashTile.Kind.NOW_PLAYING),
                    second = DashNode.Leaf(DashTile.Kind.LAUNCHER),
                ),
            ),
        )

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

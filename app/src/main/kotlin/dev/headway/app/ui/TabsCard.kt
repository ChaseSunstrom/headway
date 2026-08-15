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

package dev.headway.app.ui

import android.app.Activity
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import dev.headway.app.carapp.TemplateApps
import dev.headway.app.dash.DashLayoutStore
import dev.headway.app.dash.tiles.MapsTile
import dev.headway.app.ui.theme.Headway
import dev.headway.app.ui.theme.Phone
import dev.headway.dash.DashLayout
import dev.headway.dash.DashNode
import dev.headway.dash.PaneKind

/**
 * Tab management, on the phone, where there is a keyboard.
 *
 * ## Why here and not on the car screen
 *
 * Adding a tab needs a name, renaming one needs a name, and choosing what goes
 * in one needs a list of eleven options. All three are keyboard-and-scrolling
 * work, and the dashboard lives on a private virtual display where Android does
 * not guarantee an IME will even appear — see `MessagesTile`, which hit the same
 * wall for inline replies. The car screen switches between tabs; the phone
 * decides what they are.
 *
 * ## What it edits
 *
 * [DashLayoutStore], the same store the dashboard reads. A tab *is* a saved
 * layout, so "add a tab" is "save a layout" and the tab bar is a picker over
 * `list()`. Nothing here knows about the car.
 *
 * ## The shipped tabs are virtual until they are touched
 *
 * `DashLayoutStore.list()` substitutes `DEFAULT_TABS` when nothing is stored,
 * and does not seed them. So the first edit of any kind has to write *all* of
 * them, or the other five would vanish the moment one was added — which is
 * exactly the bug the substitution invites. [materialise] is that write, and
 * every mutation goes through it.
 */
internal class TabsCard(
    private val activity: Activity,
    private val onChanged: () -> Unit = {},
) {

    private val store = DashLayoutStore.of(activity)

    /** Rebuilt in place, so the card does not have to be re-added to the page. */
    private val rows = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
    }

    val view: View = Phone.card(activity, "Layouts").apply {
        addView(
            Phone.body(
                activity,
                "Each layout is an arrangement of panels, and the ones you pin " +
                    "appear on the car screen's rail. Arranging them is quicker " +
                    "on the car itself — settings there, then Edit this layout — " +
                    "but naming one needs a keyboard, so that is here.",
            ),
        )
        addView(rows, Phone.spaced(activity, 12f))
        addView(
            Phone.button(activity, "Add a layout") { addTab() },
        )
        addView(mapAppRow())
        addView(
            Phone.disclosure(
                activity,
                "What each kind of pane shows",
                PANE_HELP,
            ),
        )
    }

    init {
        refresh()
    }

    fun refresh() {
        rows.removeAllViews()
        val tabs = store.list()
        val active = store.active().name
        tabs.forEachIndexed { index, tab ->
            rows.addView(tabRow(tab, index, tabs.size, active))
        }
    }

    // --- one row ---------------------------------------------------------------

    private fun tabRow(tab: DashLayout, index: Int, count: Int, active: String): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = Phone.dp(activity, TOUCH_TARGET_DP)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        row.addView(
            Phone.dot(activity, if (tab.name == active) Phone.Level.GOOD else Phone.Level.IDLE),
        )
        row.addView(
            TextView(activity).apply {
                text = tab.name
                setTextColor(Headway.TEXT)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                    marginStart = Phone.dp(activity, 10f)
                }
            },
        )
        row.addView(
            TextView(activity).apply {
                text = describe(tab.root)
                setTextColor(Headway.TEXT_MUTED)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.2f)
            },
        )
        // Up and down rather than drag: a drag handle inside a scrolling page
        // fights the scroll, and the list is six rows long.
        row.addView(
            Phone.inlineButton(activity, "↑") { move(index, index - 1) }.apply {
                isEnabled = index > 0
                alpha = if (index > 0) 1f else DISABLED_ALPHA
            },
        )
        row.addView(
            Phone.inlineButton(activity, "↓") { move(index, index + 1) }.apply {
                isEnabled = index < count - 1
                alpha = if (index < count - 1) 1f else DISABLED_ALPHA
            },
        )
        row.addView(Phone.inlineButton(activity, "Edit") { editTab(tab) })
        return row
    }

    /** "Maps", or "Music and podcasts + Now playing", for the summary column. */
    private fun describe(node: DashNode): String = when (node) {
        is DashNode.Leaf -> PaneKind.describe(node.kind)
        is DashNode.Split -> describe(node.first) + " + " + describe(node.second)
    }

    // --- editing ---------------------------------------------------------------

    private fun addTab() {
        val field = nameField("")
        AlertDialog.Builder(activity)
            .setTitle("New tab")
            .setView(field)
            .setPositiveButton("Add") { _, _ ->
                val name = field.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val existing = materialise()
                if (existing.any { it.name == name }) {
                    toast("There is already a layout called \"$name\"")
                    return@setPositiveButton
                }
                // A new tab starts as the app grid, which is the one pane that
                // is useful with nothing configured and is the door to
                // everything else.
                store.save(DashLayout(name, DashNode.Leaf(PaneKind.LAUNCHER)))
                changed()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Rename, re-point, or delete.
     *
     * One dialog rather than three, because all three are the same decision
     * from the driver's side — "what is this tab" — and three menu levels on a
     * setup screen is two more than the job needs.
     */
    private fun editTab(tab: DashLayout) {
        val field = nameField(tab.name)
        val kinds = PaneKind.ALL
        val current = (tab.root as? DashNode.Leaf)?.kind
        var chosen = kinds.indexOf(current).takeIf { it >= 0 } ?: -1

        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = Phone.dp(activity, 20f)
            setPadding(pad, pad / 2, pad, 0)
            addView(field)
            addView(
                Phone.note(
                    activity,
                    if (current == null) {
                        "This tab is a split of several panes. Choosing a pane below " +
                            "replaces the whole split; leave it alone to keep it."
                    } else {
                        "What this tab shows."
                    },
                ),
            )
        }

        AlertDialog.Builder(activity)
            .setTitle(tab.name)
            .setView(column)
            .setSingleChoiceItems(
                kinds.map { PaneKind.describe(it) }.toTypedArray(),
                chosen,
            ) { _, which -> chosen = which }
            .setPositiveButton("Save") { _, _ ->
                val name = field.text.toString().trim().ifEmpty { tab.name }
                val kind = kinds.getOrNull(chosen)
                val root = when {
                    // Untouched split: keep it exactly as it is.
                    kind == null -> tab.root
                    kind == current -> tab.root
                    else -> DashNode.Leaf(kind)
                }
                val existing = materialise()
                // A rename onto a name already in use is a *merge*, not a
                // rename: save() keys on the name and overwrites in place, so
                // renaming "Phone" to "Maps" replaced the real Maps tab with
                // the Phone layout and dropped the tab count by one, silently
                // and with no undo. addTab already guards this; edit did not.
                if (name != tab.name && existing.any { it.name == name }) {
                    toast("There is already a layout called \"$name\"")
                    return@setPositiveButton
                }
                // Substituted in place rather than deleted and re-saved.
                // delete() clears the active pointer when it removes the active
                // tab, and save() appends an unknown name to the end — so the
                // old two-step moved the tab under the driver's finger to the
                // far right of the bar and, if they were on it, sent the next
                // drive back to the default tab. Exactly the trap `move()`
                // documents avoiding.
                val wasActive = store.active().name == tab.name
                val index = existing.indexOfFirst { it.name == tab.name }
                val updated = existing.toMutableList()
                val renamed = DashLayout(name, root)
                if (index >= 0) updated[index] = renamed else updated += renamed
                store.replaceAll(updated)
                if (wasActive) store.setActive(name)
                changed()
                if ((root as? DashNode.Leaf)?.kind == PaneKind.CAR_APP) chooseCarApp(name)
            }
            .setNeutralButton("Delete") { _, _ ->
                materialise()
                store.delete(tab.name)
                // hasSaved(), not list().isEmpty(). list() substitutes the
                // shipped set when storage is empty and its own KDoc says it is
                // "never empty", so the old condition was dead code — and
                // deleting the last tab silently resurrected six of them, which
                // reads as the delete having failed.
                if (!store.hasSaved()) toast("The shipped layouts are back")
                changed()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun move(from: Int, to: Int) {
        val tabs = materialise().toMutableList()
        if (from !in tabs.indices || to !in tabs.indices) return
        val moved = tabs.removeAt(from)
        tabs.add(to, moved)
        // One write. save() keeps an existing name in its place, so reordering
        // through it would need a delete per tab first -- and deleting the
        // active tab clears the active pointer, which would put the driver back
        // on the first tab every time they nudged the order.
        store.replaceAll(tabs)
        changed()
    }

    /**
     * Writes the shipped tabs into storage if nothing is there yet.
     *
     * Called before every mutation. Without it the first edit would store one
     * layout, `list()` would stop substituting, and the other five shipped tabs
     * would silently disappear.
     */
    private fun materialise(): List<DashLayout> {
        if (store.hasSaved()) return store.list()
        store.replaceAll(DashLayoutStore.DEFAULT_TABS)
        return DashLayoutStore.DEFAULT_TABS
    }

    /**
     * For a Car app tab, which app it opens.
     *
     * Offered as a follow-up dialog rather than a second list in the first one,
     * because it only applies to one of the eleven kinds and only after that
     * one has been picked. "Show the picker" is the first option and the
     * default: a tab that opens a list of car apps is useful on day one, and a
     * tab pinned to an app that has since been uninstalled is not.
     */
    private fun chooseCarApp(name: String) {
        val apps = TemplateApps.installed(activity)
        val labels = listOf("Show the picker") + apps.map { it.label }
        val stored = store.list().firstOrNull { it.name == name }
        val pinned = (stored?.root as? DashNode.Leaf)?.argument
        val selected = apps.indexOfFirst { it.service.flattenToString() == pinned } + 1

        if (apps.isEmpty()) {
            toast("No app on this phone offers a car interface yet")
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("$name opens")
            .setSingleChoiceItems(labels.toTypedArray(), selected) { dialog, which ->
                val argument = apps.getOrNull(which - 1)?.service?.flattenToString()
                materialise()
                store.save(DashLayout(name, DashNode.Leaf(PaneKind.CAR_APP, argument)))
                changed()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- the map app -------------------------------------------------------------

    /**
     * Which app the Maps pane opens and trusts.
     *
     * Here rather than on the car screen because it is a choice made once, from
     * a list that can be long, and because the Maps pane needs the answer before
     * it can show anything useful.
     */
    private fun mapAppRow(): View {
        val row = Phone.StatusRow(activity, "Map app")
        fun paint() {
            val chosen = HeadwaySettings.of(activity)
                .getString(HeadwaySettings.KEY_MAP_APP, null)
            val apps = MapsTile.mapApps(activity)
            val label = apps.firstOrNull { it.first == chosen }?.second
            row.set(
                when {
                    apps.isEmpty() -> Phone.Level.WARN
                    label != null -> Phone.Level.GOOD
                    else -> Phone.Level.IDLE
                },
                when {
                    apps.isEmpty() -> "Nothing on this phone handles a map link."
                    label != null -> label
                    else -> "Not chosen; using ${apps.first().second}"
                },
            )
        }
        row.withAction("Choose") {
            val apps = MapsTile.mapApps(activity)
            if (apps.isEmpty()) {
                toast("No app on this phone handles a map link")
                return@withAction
            }
            val chosen = HeadwaySettings.of(activity)
                .getString(HeadwaySettings.KEY_MAP_APP, null)
            AlertDialog.Builder(activity)
                .setTitle("Map app")
                .setSingleChoiceItems(
                    apps.map { it.second }.toTypedArray(),
                    apps.indexOfFirst { it.first == chosen },
                ) { dialog, which ->
                    HeadwaySettings.of(activity).edit()
                        .putString(HeadwaySettings.KEY_MAP_APP, apps[which].first)
                        .apply()
                    paint()
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        paint()
        return row.view
    }

    // --- plumbing ----------------------------------------------------------------

    private fun nameField(initial: String): EditText = EditText(activity).apply {
        setText(initial)
        hint = "Tab name"
        setTextColor(Headway.TEXT)
        setHintTextColor(Headway.TEXT_MUTED)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setSingleLine()
        minHeight = Phone.dp(activity, TOUCH_TARGET_DP)
    }

    private fun changed() {
        refresh()
        // A car session already running reads the store only once, when its
        // surface is built. Without this the driver renames a tab on the phone
        // and the car keeps showing the old pill.
        DashLayoutStore.announceChanged()
        onChanged()
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TOUCH_TARGET_DP = 48f
        const val DISABLED_ALPHA = 0.3f

        val PANE_HELP: String = buildString {
            appendLine("Maps — the next instruction from whichever app is navigating, and one tap to that app full screen.")
            appendLine()
            appendLine("Music and podcasts — any media app's library, walked and drawn by Headway. Tap a track and it plays.")
            appendLine()
            appendLine("Now playing — what is playing anywhere on the phone, with working transport controls.")
            appendLine()
            appendLine("Phone — the live call, and the last twelve calls from the call log.")
            appendLine()
            appendLine("Car app — a third-party app's own car interface, drawn by Headway rather than mirrored. Maps, messengers and points-of-interest apps that publish one appear in a picker. Music apps do not: a player's car screen is a media browse tree, which the Music and podcasts panel shows instead.")
            appendLine()
            appendLine("Messages — recent conversations, with the app's own inline reply.")
            appendLine()
            appendLine("Widget — an app's own home-screen widget, if it ships one.")
            appendLine()
            appendLine("Apps — your pinned apps. Tapping one hands the whole car screen to it.")
            appendLine()
            appendLine("Clock — the time, the date, and the link state.")
            appendLine()
            append("Phone screen — the mirrored phone, for anything none of the above covers.")
        }
    }
}

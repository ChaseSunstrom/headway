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
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import dev.headway.app.dash.CarGlyph
import dev.headway.app.dash.CarGlyphView
import dev.headway.app.ui.theme.Headway
import dev.headway.app.ui.theme.Phone
import dev.headway.dash.TabIcon

/**
 * The car screen's icon grid, at phone sizes.
 *
 * ## Why a second picker rather than a shared one
 *
 * Because the car's is built from `CarSheet` and `CarMetrics`, which size
 * everything against the head unit's negotiated density on a display that does
 * not exist while the phone is parked in a driveway. The *vocabulary* is shared
 * — `TabIcon` names the icons and `CarGlyph` draws them — so the two pickers
 * cannot drift apart about what an icon is or what it looks like, only about
 * how large it is drawn.
 *
 * A driver reported that there was no way to set a layout's icon. There was, on
 * the car screen, in two places — but not on the phone, which is where somebody
 * sets a layout up in the first place.
 */
internal object TabIconPicker {

    fun show(activity: Activity, current: TabIcon?, onPicked: (TabIcon?) -> Unit) {
        val cell = Phone.dp(activity, CELL_DP)
        val pad = Phone.dp(activity, 8f)
        val grid = GridLayout(activity).apply {
            columnCount = COLUMNS
            setPadding(pad * 2, pad, pad * 2, pad)
        }

        lateinit var dialog: AlertDialog

        fun addCell(icon: TabIcon?, glyph: CarGlyph?) {
            val selected = icon == current
            val holder = FrameLayout(activity).apply {
                background = Headway.panel(
                    radiusPx = cell / 2f,
                    fill = if (selected) Headway.SURFACE_RAISED else Headway.SURFACE,
                    stroke = if (selected) Headway.ACCENT else Headway.OUTLINE,
                )
                contentDescription = icon?.name ?: "No icon"
                setOnClickListener {
                    onPicked(icon)
                    dialog.dismiss()
                }
                layoutParams = GridLayout.LayoutParams().apply {
                    width = cell
                    height = cell
                    setMargins(pad / 2, pad / 2, pad / 2, pad / 2)
                }
            }
            val inset = Phone.dp(activity, 12f)
            holder.addView(
                if (glyph == null) {
                    // The "no icon" cell. A cross would read as delete; the word
                    // is what the rail will actually show.
                    Phone.note(activity, "Name").apply {
                        gravity = Gravity.CENTER
                        layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
                    }
                } else {
                    CarGlyphView(activity, glyph) { Headway.TEXT }.apply {
                        layoutParams = FrameLayout.LayoutParams(
                            cell - inset * 2,
                            cell - inset * 2,
                        ).apply { gravity = Gravity.CENTER }
                    }
                },
            )
            grid.addView(holder)
        }

        addCell(null, null)
        TabIcon.ALL.forEach { icon -> addCell(icon, CarGlyph.forTab(icon)) }

        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                Phone.note(
                    activity,
                    "An icon replaces the tab's name on the car screen. The name is " +
                        "kept — it is still how the layout is found.",
                ),
            )
            addView(grid)
        }

        dialog = AlertDialog.Builder(activity)
            .setTitle("Icon")
            .setView(ScrollView(activity).apply { addView(body) })
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
    }

    private const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT

    /** Big enough to recognise a stroked glyph on a phone, small enough for five across. */
    private const val CELL_DP = 60f

    private const val COLUMNS = 5
}

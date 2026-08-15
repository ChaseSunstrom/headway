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
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.headway.app.ui.theme.CarMetrics
import dev.headway.app.ui.theme.Headway

/**
 * A full-height panel of choices, over the dashboard.
 *
 * ## Why every car-side decision is one of these
 *
 * Because a dialog is the wrong shape for a dashboard and a menu is the wrong
 * shape for a thumb. What works on a head unit is a column of full-width rows,
 * each a whole touch target tall, each with a name and — where it helps — one
 * line saying what it does. That is the only interaction pattern here, used for
 * settings, for the theme, for which app goes in a pane, for the rail, and for
 * the pane menu. One pattern, learned once, in a moving car.
 *
 * Sheets are built by the shell and shown in its overlay host. They cover the
 * dashboard rather than replacing it, so dismissing one is instant and never
 * costs a tile restart.
 */
class CarSheet(
    private val context: Context,
    private val metrics: CarMetrics,
) {

    /** One row. [detail] is optional; [selected] fills the row in the accent. */
    data class Row(
        val title: String,
        val detail: String? = null,
        val icon: Drawable? = null,
        val selected: Boolean = false,
        val destructive: Boolean = false,
        /** True for a heading. See [section]. */
        val isSection: Boolean = false,
        val onChosen: () -> Unit,
    )

    /**
     * Builds the panel.
     *
     * @param chips a compact row of mutually exclusive choices shown above the
     *   rows — used where the options are short words and there are several of
     *   them, like a palette, where a full-width row each would push the rest of
     *   the panel off the screen.
     */
    fun build(
        title: String,
        rows: List<Row>,
        chips: List<Row> = emptyList(),
        chipsTitle: String? = null,
        /**
         * A second labelled chip row, under the first.
         *
         * Two rows and not two sheets because the two numbers they set are only
         * meaningful beside each other: the car-app size sheet offers "how large
         * the app draws" and "how much to shrink what ignored that", and a
         * driver choosing between them is comparing, not navigating.
         */
        extraChips: List<Row> = emptyList(),
        extraChipsTitle: String? = null,
        /** Appended under the rows; see [buildIcons], its only caller. */
        extra: View? = null,
        onClose: () -> Unit,
    ): View {
        val scrim = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            setBackgroundColor(SCRIM)
            // Swallows every touch that misses the panel, so a tap on the
            // dashboard behind an open sheet cannot start playing something.
            isClickable = true
            setOnClickListener { onClose() }
        }

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Headway.panel(metrics.radius, fill = Headway.GROUND, stroke = Headway.OUTLINE)
            isClickable = true
            val inset = metrics.gutter
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
                setMargins(inset, inset, inset, inset)
            }
        }

        panel.addView(header(title, onClose))

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = metrics.gutter / 2
            setPadding(pad, 0, pad, pad)
        }
        if (chips.isNotEmpty()) {
            chipsTitle?.let { body.addView(sectionLabel(it)) }
            body.addView(chipRow(chips))
        }
        if (extraChips.isNotEmpty()) {
            extraChipsTitle?.let { body.addView(sectionLabel(it)) }
            body.addView(chipRow(extraChips))
        }
        rows.forEach { body.addView(if (it.isSection) sectionLabel(it.title) else row(it)) }
        extra?.let { body.addView(it) }

        val scroller = ScrollView(context).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            addView(body, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        panel.addView(scroller)
        scrim.addView(panel)
        return scrim
    }

    /**
     * A choice shown as the icon itself rather than as a word for it.
     *
     * @param glyph what is drawn.
     * @param description what a screen reader says, and what the log records.
     */
    class IconChoice(
        val glyph: CarGlyph,
        val description: String,
        val selected: Boolean = false,
        val onChosen: () -> Unit,
    )

    /**
     * [build], with a grid of icons under the rows.
     *
     * A separate entry point rather than another optional parameter on `build`,
     * which already carries five: this one lays out a wrapping grid rather than
     * a list, and the two have nothing in common but the panel around them.
     *
     * The icons are drawn at the car's own touch-target size, so choosing one is
     * a matter of looking at the thing that will appear rather than reading a
     * list of names for shapes.
     */
    fun buildIcons(
        title: String,
        rows: List<Row>,
        icons: List<IconChoice>,
        onClose: () -> Unit,
    ): View {
        val grid = GridLayout(context).apply {
            val pad = metrics.gutter / 2
            setPadding(pad, pad, pad, pad)
            // Whatever fits, at least two. The rail's panel can be narrow.
            columnCount = maxOf(2, (metrics.widthPx / (metrics.touchTargetPx * 2)).coerceAtMost(8))
        }
        icons.forEach { choice ->
            grid.addView(
                CarGlyphButton(
                    context,
                    metrics,
                    choice.glyph,
                    choice.description,
                    active = choice.selected,
                ) { choice.onChosen() },
            )
        }
        return build(title = title, rows = rows, extra = grid, onClose = onClose)
    }

    private fun header(title: String, onClose: () -> Unit): View {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = metrics.gutter
            setPadding(pad, pad / 2, pad / 2, pad / 2)
        }
        bar.addView(
            Headway.title(context, metrics.unit, title).apply {
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            },
        )
        bar.addView(
            CarGlyphButton(context, metrics, CarGlyph.CLOSE, "Close") { onClose() },
        )
        return bar
    }

    private fun sectionLabel(text: String): View =
        Headway.caption(context, metrics.unit, text).apply {
            val pad = metrics.gutter / 2
            setPadding(pad, pad, pad, pad / 2)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }

    /** A horizontally scrolling row of short choices. */
    private fun chipRow(chips: List<Row>): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = metrics.gutter / 2
            setPadding(pad, 0, pad, pad / 2)
        }
        chips.forEach { chip ->
            val gap = metrics.gutter / 3
            row.addView(
                Headway.title(context, metrics.unit, chip.title).apply {
                    gravity = Gravity.CENTER
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, metrics.unit * CHIP_TEXT)
                    setTextColor(if (chip.selected) Headway.ON_ACCENT else Headway.TEXT)
                    minHeight = metrics.touchTargetPx
                    val pad = metrics.gutter
                    setPadding(pad, 0, pad, 0)
                    background = Headway.panel(
                        radiusPx = metrics.touchTargetPx / 2f,
                        fill = if (chip.selected) Headway.ACCENT else Headway.SURFACE,
                        stroke = if (chip.selected) null else Headway.OUTLINE,
                    )
                    isClickable = true
                    setOnClickListener { chip.onChosen() }
                    layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                        setMargins(gap, gap, gap, gap)
                    }
                },
            )
        }
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            addView(row)
        }
    }

    private fun row(row: Row): View {
        val holder = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = metrics.touchTargetPx + metrics.gutter / 2
            val pad = metrics.gutter
            setPadding(pad, metrics.gutter / 2, pad, metrics.gutter / 2)
            background = Headway.panel(
                radiusPx = metrics.radius,
                fill = if (row.selected) Headway.ACCENT else Headway.SURFACE,
                stroke = if (row.selected) null else Headway.OUTLINE,
            )
            isClickable = true
            setOnClickListener { row.onChosen() }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                val gap = metrics.gutter / 3
                setMargins(gap, gap, gap, gap)
            }
        }
        row.icon?.let { drawable ->
            holder.addView(
                ImageView(context).apply {
                    setImageDrawable(drawable)
                    layoutParams = LinearLayout.LayoutParams(
                        metrics.touchTargetPx,
                        metrics.touchTargetPx,
                    ).apply { rightMargin = metrics.gutter }
                },
            )
        }
        val text = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        text.addView(
            Headway.title(context, metrics.unit, row.title).apply {
                setTextColor(
                    when {
                        row.selected -> Headway.ON_ACCENT
                        row.destructive -> Headway.FAULT
                        else -> Headway.TEXT
                    },
                )
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            } as TextView,
        )
        row.detail?.takeIf { it.isNotBlank() }?.let { detail ->
            text.addView(
                Headway.caption(context, metrics.unit, detail).apply {
                    if (row.selected) setTextColor(Headway.ON_ACCENT)
                    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                },
            )
        }
        holder.addView(text)
        return holder
    }

    companion object {

        /**
         * A heading between groups of rows.
         *
         * A sheet of nine equally weighted rows is a list the driver has to read
         * end to end every time. Headings turn it into four groups they can
         * skip, which matters more here than in an app: this is read at a
         * glance, in a car, often while the engine is running.
         *
         * Modelled as a [Row] rather than a second type so a caller can build
         * one list in one order, and `build` renders it as a label instead of a
         * target. A section is never pressable, so its `onChosen` is never
         * called.
         *
         * On the companion, not the instance: callers write
         * `CarSheet.section(...)` beside `CarSheet.Row(...)`, and the second of
         * those works only because `Row` is a nested *class*. A function needs
         * to be here to be reachable the same way.
         */
        fun section(title: String): Row = Row(title = title, isSection = true) {}

        // The companion had to become public so `section` is reachable the way
        // `Row` is; these stay private, because they are this file's business.

        /** Dark enough that the dashboard behind reads as "not now". */
        private const val SCRIM = 0xCC05070A.toInt()
        private const val CHIP_TEXT = 0.30f
    }
}

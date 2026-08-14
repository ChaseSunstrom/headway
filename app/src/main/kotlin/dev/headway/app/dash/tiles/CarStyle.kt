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

package dev.headway.app.dash.tiles

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import dev.headway.app.ui.theme.Headway
import dev.headway.app.ui.theme.HeadwayMark

/**
 * The look every tile shares, and the reason the numbers here are plain dp.
 *
 * `CarLauncherActivity` has to compute its touch targets backwards from the car,
 * because it draws on display 0 and the car sees a scaled, letterboxed copy — a
 * 48 dp button there does not arrive as 48 dp. The dashboard is the opposite
 * case. Its views live on the `CarDisplay` virtual display, which is created at
 * the width, height and *density* the head unit negotiated, so the resources of
 * any `Context` derived from that display already speak in car pixels. 48 dp
 * here is 48 dp on the panel, with no correction needed and none applied.
 *
 * ## The palette is not this file's any more
 *
 * It used to be: a private set of six ARGB literals, copied from
 * `CarLauncherActivity`'s own private set of six ARGB literals, with
 * `MainActivity` holding a third that had already drifted a shade. Three copies
 * of one palette is three chances to change two of them. Everything here now
 * defers to [Headway], which derives from the launcher icon, and the only thing
 * this object still owns is the geometry.
 *
 * The visible change beyond the colours is that a pane is a *panel* — rounded,
 * lifted a little off the ground, with a hairline — rather than a black
 * rectangle. `DashboardPresentation` draws that background; this object stops
 * painting over it, which the old [panel] did, corners and all.
 */
internal object CarStyle {

    val BACKGROUND: Int = Headway.GROUND
    val SURFACE: Int = Headway.SURFACE_RAISED
    val TEXT: Int = Headway.TEXT
    val DIM: Int = Headway.TEXT_MUTED
    val ACCENT: Int = Headway.ACCENT
    val GOOD: Int = Headway.GOOD
    val BAD: Int = Headway.FAULT

    /**
     * The size of anything a driver taps.
     *
     * Above the platform's 48 dp floor on purpose. That floor is the minimum for
     * a person looking at the screen; CLAUDE.md asks the car UI to "assume
     * imprecise touches", which is a different and larger requirement.
     */
    const val PRIMARY_TARGET_DP = 64f

    fun dp(context: Context, value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics,
    ).toInt()

    fun sp(context: Context, value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, value, context.resources.displayMetrics,
    )

    /** The outer padding and inter-row gap; one number so panes stay aligned. */
    fun gutter(context: Context): Int = dp(context, 12f)

    fun radius(context: Context): Float = dp(context, 14f).toFloat()

    /**
     * A tile's own root.
     *
     * Transparent, deliberately. The pane behind it already carries the rounded
     * panel `DashboardPresentation.buildLeaf` gave it, and the opaque rectangle
     * this used to paint covered exactly the corners that made it look like a
     * panel rather than a hole.
     */
    fun panel(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = gutter(context)
        setPadding(pad, pad, pad, pad)
    }

    fun label(
        context: Context,
        sizeSp: Float,
        colour: Int = TEXT,
        bold: Boolean = false,
    ): TextView = TextView(context).apply {
        setTextColor(colour)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(context, sizeSp))
        if (bold) setTypeface(Typeface.DEFAULT_BOLD)
        // Truncation rather than wrapping for the single-line strings: a title
        // that reflows changes the height of every row below it, and a pane
        // whose rows move while the car is moving is a pane nobody can hit.
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    /**
     * A pill, built on `TextView` rather than `Button`.
     *
     * A Material `Button` arrives with its own tint, elevation and 8 dp inset,
     * all of which have to be undone before a custom background is visible, and
     * its default surface is a mid-grey that is exactly what the car palette
     * avoids. There is nothing left of a `Button` worth keeping once those are
     * gone but the click listener.
     *
     * The parameter is `caption` and not `text` because inside `apply` an
     * unqualified `text` finds the view's own property, so a parameter of that
     * name would read as though it were assigning to itself.
     */
    fun button(
        context: Context,
        caption: String,
        emphasised: Boolean = false,
        onClick: () -> Unit,
    ): TextView = TextView(context).apply {
        text = caption
        gravity = Gravity.CENTER
        setTextColor(if (emphasised) Headway.GROUND else TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(context, 15f))
        if (emphasised) setTypeface(Typeface.DEFAULT_BOLD)
        minHeight = dp(context, PRIMARY_TARGET_DP)
        minWidth = dp(context, PRIMARY_TARGET_DP * 1.4f)
        val side = gutter(context)
        setPadding(side, 0, side, 0)
        background = Headway.panel(
            radiusPx = dp(context, PRIMARY_TARGET_DP / 2f).toFloat(),
            fill = if (emphasised) Headway.ACCENT else Headway.SURFACE_RAISED,
            stroke = if (emphasised) null else Headway.OUTLINE,
        )
        isClickable = true
        isFocusable = true
        // The caption is also the accessibility name; TalkBack on the phone reads
        // the dashboard when a user is setting it up there.
        contentDescription = caption
        setOnClickListener { onClick() }
    }

    /**
     * The state a pane shows when it has nothing to show.
     *
     * Centred, with the mark above it. An empty pane that is simply blank reads
     * as a broken pane — which is exactly how the driver read the empty Now
     * playing pane, and they were half right: it was empty because a grant was
     * missing and nothing on either screen said so.
     */
    fun emptyState(context: Context, message: String): View {
        val holder = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val pad = gutter(context) * 2
            setPadding(pad, pad, pad, pad)
        }
        val markSize = dp(context, 40f)
        holder.addView(
            HeadwayMark(context).apply {
                alpha = 0.5f
                layoutParams = LinearLayout.LayoutParams(markSize * 2, markSize)
            },
        )
        holder.addView(
            label(context, 15f, DIM).apply {
                text = message
                gravity = Gravity.CENTER
                maxLines = 4
                ellipsize = null
                setLineSpacing(0f, 1.25f)
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    topMargin = gutter(context)
                }
            },
        )
        return holder
    }
}

/**
 * An app's launcher icon, or null for one uninstalled since it was named.
 *
 * File-level because two tiles need it: [MessagesTile] draws it beside every
 * conversation, and [NowPlayingTile] falls back to it when a session publishes
 * no album art.
 */
internal fun appIcon(context: Context, packageName: String): Drawable? = runCatching {
    context.packageManager.getApplicationIcon(packageName)
}.getOrNull()

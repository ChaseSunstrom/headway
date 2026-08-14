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
import android.view.View
import dev.headway.dash.DashNode

/**
 * One pane's worth of content on the car dashboard.
 *
 * ## What a tile can and cannot be
 *
 * A tile renders *content*, not another app's window. That distinction is the
 * whole architecture and it is not a design preference — ADR 0004 establishes
 * that placing a third-party activity on a display Headway owns requires that
 * app to declare `android:allowEmbedded="true"`, and that positioning any window
 * but your own has no public API at any protection level.
 *
 * What is left is better than it sounds, because it is what Android Auto itself
 * does: apps hand over *models* and the host draws them. So a tile can show any
 * app's now-playing state with working transport controls, any media app's whole
 * library, the dialer's live call, the navigator's next turn, any app's
 * notifications with working inline replies, and any app's own live `RemoteViews`
 * if it ships a widget — all through user-granted, unprivileged APIs.
 *
 * [Kind.CAR_APP] takes that to its conclusion. An app that exports a
 * `CarAppService` publishes its *entire interface* as models — a list, a grid, a
 * navigation strip — and Headway draws every pixel of it, which is exactly what
 * Gearhead does and is still a tile rendering content rather than hosting a
 * window. ADR 0007 has how Headway is accepted as a host; ADR 0008 has what
 * happens for an app that publishes nothing at all.
 *
 * The one exception is [Kind.MIRROR], which is not drawn by Headway at all: the
 * car is already mirroring the phone's display, so a mirror tile is a hole in
 * the dashboard through which the real screen shows. There can be only one, and
 * only one app can be live behind it, because Android composites one app at a
 * time on display 0.
 *
 * ## Lifecycle
 *
 * [createView] once, then [start] when the pane becomes visible and [stop] when
 * it does not. Tiles observe things that cost battery — media sessions,
 * notification streams, widget updates — so a tile that is off screen must stop
 * observing, and a driver switching layouts must not leak the old one's
 * listeners.
 */
interface DashTile {

    /** Which kind this is; matches [DashNode.Leaf.kind]. */
    val kind: String

    /**
     * Builds the pane's view. Called once per tile instance.
     *
     * The view is sized by the dashboard, which knows the car's geometry; a tile
     * must not assume a size and must survive being narrow.
     */
    fun createView(context: Context): View

    /** Begin observing. Idempotent. */
    fun start()

    /** Stop observing and release anything held. Idempotent. */
    fun stop()

    /** One line for the session log. */
    fun describe(): String

    /**
     * How this tile behaves while the driver is rearranging the layout.
     *
     * Default: nothing. Most tiles are already inert under an edit overlay —
     * they draw text and take taps that the overlay swallows. The one that is
     * not is the app pane, whose picture is a `SurfaceView` punched through the
     * window: an overlay drawn over it would be invisible, so it detaches its
     * surface for the duration and shows a card the driver can actually see.
     */
    fun onEditingChanged(editing: Boolean) = Unit
}

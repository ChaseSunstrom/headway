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
 * app's now-playing state with working transport controls, any app's
 * notifications with working inline replies, and any app's own live `RemoteViews`
 * if it ships a widget — all through user-granted, unprivileged APIs.
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
     * The tile kinds Headway ships.
     *
     * Strings rather than an enum because they are persisted in saved layouts:
     * a layout written by a build that had a kind this one does not must fail
     * to resolve one pane, not fail to parse the file.
     */
    object Kind {
        /** Now playing, from `MediaSessionManager`. Any media app. */
        const val NOW_PLAYING = "now_playing"

        /**
         * Browse a media app's library, from `MediaBrowserService`.
         *
         * The pane that makes Headway able to *start* something rather than only
         * control what is already going, and the closest thing in the product to
         * what Android Auto does with a music app: walk the tree the app
         * publishes and draw it here. See `MediaBrowseTile`.
         */
        const val BROWSE = "browse"

        /**
         * The map, and the controls that go with it.
         *
         * Turn-by-turn drawn from whatever the navigating app publishes, plus a
         * one-tap hand-off to that app full screen. See `MapsTile`.
         */
        const val MAPS = "maps"

        /**
         * Recent calls, contacts and whatever call is in progress.
         *
         * Android Auto's phone screen is a model rather than a rendering of the
         * dialer, and so is this one. See `PhoneTile`.
         */
        const val PHONE = "phone"

        /**
         * A third-party app's own car interface, drawn by Headway.
         *
         * The template host: the app publishes `androidx.car.app` templates and
         * Headway renders them with its own widgets at the car's size. This is
         * what Android Auto does with a navigation or messaging app, and it is
         * the only route by which another app's *interface* reaches the car
         * screen without mirroring the phone. See `CarAppTile`.
         *
         * The argument is the flattened `ComponentName` of the app's
         * `CarAppService`; with none, the pane offers a picker.
         */
        const val CAR_APP = "car_app"

        /** Recent notifications with inline reply, from `NotificationListenerService`. */
        const val MESSAGES = "messages"

        /** An app's own `RemoteViews`, hosted with `AppWidgetHost`. */
        const val WIDGET = "widget"

        /** Headway's pinned-app grid. */
        const val LAUNCHER = "launcher"

        /** Clock, date, and link status. */
        const val CLOCK = "clock"

        /**
         * A hole showing the mirrored phone screen.
         *
         * At most one per layout, and the dashboard enforces that: two holes
         * would both show the same display, which is confusing rather than
         * useful.
         */
        const val MIRROR = "mirror"

        /** Everything, in the order a picker should offer them. */
        val ALL: List<String> = listOf(
            NOW_PLAYING, BROWSE, MAPS, PHONE, CAR_APP, MESSAGES, WIDGET, LAUNCHER, CLOCK, MIRROR,
        )

        /** A human name for a picker or a log line. */
        fun describe(kind: String): String = when (kind) {
            NOW_PLAYING -> "Now playing"
            BROWSE -> "Music and podcasts"
            MAPS -> "Maps"
            PHONE -> "Phone"
            CAR_APP -> "Car app"
            MESSAGES -> "Messages"
            WIDGET -> "Widget"
            LAUNCHER -> "Apps"
            CLOCK -> "Clock"
            MIRROR -> "Phone screen"
            else -> kind
        }
    }
}

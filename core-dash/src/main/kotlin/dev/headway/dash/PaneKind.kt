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

/**
 * What a pane can be showing.
 *
 * Strings rather than an enum because they are persisted inside saved layouts: a
 * layout written by a build that had a kind this one does not must fail to
 * resolve *one pane*, not fail to parse the file.
 *
 * ## The two families
 *
 * Most kinds are **models another app already publishes**, drawn by Headway at
 * the car's own size: now playing, a media library, the next turn, a call, a
 * notification with its reply action, a car app's templates. That is the same
 * mechanism Android Auto uses, it needs no capture and no scaling, and it is
 * what `DashTile` documents.
 *
 * [APP] is the other family, and there is exactly one of it. It shows a real
 * third-party app's own pixels inside the pane, by pointing the session's single
 * `MediaProjection` virtual display at that pane's surface (ADR 0010). One pane
 * can be live at a time, because a `MediaProjection` may own one virtual display
 * (`MediaProjection.createVirtualDisplay` throws `SecurityException` on the
 * second, targeting SDK 34+) — but *which* pane is live is a call to
 * `VirtualDisplay.setSurface`, which costs nothing and asks the driver nothing.
 *
 * ## Several different apps at once
 *
 * Possible, and not through [APP]. A screen-capture grant is one grant, one
 * virtual display, one shared app — the platform allows a second of none of
 * those. What *is* unlimited is the model family: [CAR_APP] renders a different
 * third-party app's own interface in every pane that asks for one, and [WIDGET]
 * does the same with `RemoteViews`. Four panes can show four apps at once that
 * way, live, with no capture involved.
 *
 * So the shape that works is a layout with one [APP] pane for whatever is being
 * shared right now, and [CAR_APP] or [WIDGET] panes beside it for everything
 * else. The pickers say so rather than leaving a driver to discover it by
 * building a layout of four app panes and finding three of them dormant.
 */
object PaneKind {

    /** Now playing, from `MediaSessionManager`. Any media app. */
    const val NOW_PLAYING = "now_playing"

    /** Browse a media app's library, from `MediaBrowserService`. */
    const val BROWSE = "browse"

    /** Turn-by-turn drawn from whatever the navigating app publishes. */
    const val MAPS = "maps"

    /** Recent calls, contacts, and whatever call is in progress. */
    const val PHONE = "phone"

    /** A third-party app's own car interface, drawn by Headway from its templates. */
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
     * A real app, rendered into this pane.
     *
     * The argument is the package name to open here, or null for "whatever the
     * driver last opened".
     */
    const val APP = "app"

    /**
     * What [APP] used to be called, when it meant "a hole through which the
     * whole mirrored phone screen shows".
     *
     * Kept only so that a layout saved by an older build still resolves; every
     * reader maps it to [APP] through [canonical]. Never offered in a picker.
     */
    const val LEGACY_MIRROR = "mirror"

    /** Everything, in the order a picker should offer them. */
    val ALL: List<String> = listOf(
        APP, MAPS, NOW_PLAYING, BROWSE, PHONE, MESSAGES, CAR_APP, LAUNCHER, WIDGET, CLOCK,
    )

    /**
     * The kind a stored string really means.
     *
     * One place, so that the tile factory, the picker and the "is this pane an
     * app pane" test cannot disagree about a legacy layout.
     */
    fun canonical(kind: String): String = if (kind == LEGACY_MIRROR) APP else kind

    /** Whether [kind] shows another app's own pixels rather than a model. */
    fun isApp(kind: String): Boolean = canonical(kind) == APP

    /** A human name for a picker or a log line. */
    fun describe(kind: String): String = when (canonical(kind)) {
        NOW_PLAYING -> "Now playing"
        BROWSE -> "Music and podcasts"
        MAPS -> "Maps"
        PHONE -> "Phone"
        CAR_APP -> "Car app"
        MESSAGES -> "Messages"
        WIDGET -> "Widget"
        LAUNCHER -> "All apps"
        CLOCK -> "Clock"
        APP -> "App"
        else -> kind
    }

    /** One line under the name in a picker, saying what the pane will do. */
    fun explain(kind: String): String = when (canonical(kind)) {
        NOW_PLAYING -> "Whatever is playing, with transport controls"
        BROWSE -> "Walk a media app's library and start something"
        MAPS -> "The next turn, from the app that is navigating"
        PHONE -> "Recent calls, and the call in progress"
        CAR_APP -> "An app's own car interface — several of these can run at once"
        MESSAGES -> "Incoming messages, with replies"
        WIDGET -> "An app's own widget — several of these can run at once"
        LAUNCHER -> "A grid of every app, to open one here"
        CLOCK -> "Time, date and link status"
        APP -> "A shared app, live. One pane at a time shows it"
        else -> ""
    }

    /** Whether choosing this kind should immediately ask "which one?". */
    fun needsArgument(kind: String): Boolean = when (canonical(kind)) {
        APP, CAR_APP, WIDGET -> true
        else -> false
    }
}

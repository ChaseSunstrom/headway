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
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.browse.MediaBrowser
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.headway.app.dash.DashTile
import dev.headway.app.media.BrowseState
import dev.headway.app.media.CarMediaBrowser
import dev.headway.app.media.MediaApp
import dev.headway.app.media.MediaApps
import dev.headway.app.ui.HeadwaySettings
import dev.headway.app.ui.theme.Headway
import dev.headway.dash.PaneKind

/**
 * Browse any media app's library, and start something playing.
 *
 * ## Why this pane is the answer to "run apps like Android Auto does"
 *
 * It is, almost exactly, what Gearhead does with a music app. The app publishes
 * a tree through `MediaBrowserService`; the host walks it and draws it with its
 * own widgets at the car's own size; a tap on a leaf sends `playFromMediaId`
 * back. The app never draws anything, its activity never starts, and none of it
 * needs a permission Headway cannot have. [dev.headway.app.media.MediaApps] has
 * the full derivation and the one honest limit — an app decides in its own
 * `onGetRoot` whether to serve an unrecognised caller, and some say no.
 *
 * ## Shape
 *
 * Two states, one view:
 *
 * - **No app chosen** — the list of apps that publish a library, by icon and
 *   name. This is the top of the tree and Back returns to it.
 * - **Inside an app** — a header with the breadcrumb and a Back target, then
 *   the current node's children. Browsable rows carry a chevron; playable rows
 *   do not. An item that is both is treated as browsable, because a container
 *   you cannot open is worse than a track you have to tap twice.
 *
 * Rows are a full touch target tall and the whole row is the target — a car
 * digitizer through sunlight does not hit a chevron.
 */
class MediaBrowseTile(
    context: Context,
    private val onStep: (String) -> Unit = {},
) : DashTile {

    private val appContext: Context = context.applicationContext

    override val kind: String = PaneKind.BROWSE

    private var header: LinearLayout? = null
    private var headerLabel: TextView? = null
    private var backTarget: View? = null
    private var rows: LinearLayout? = null

    private var running = false

    /** The app being browsed, or null while the app list is showing. */
    private var session: CarMediaBrowser? = null

    /**
     * What is playing, at the foot of the library, the way Android Auto has it.
     *
     * A driver asked for exactly this and asked for the separate panel to stop
     * being the answer: "symfonium doesnt show the now playing UI inside of it,
     * I dont want to have a seperate now playing panel". It is a real
     * [NowPlayingTile] in its compact form rather than a reimplementation, so
     * the artwork, the metadata reads and the transport all behave identically
     * to the pane — and it follows *this* app's session, so the controls under
     * a library always belong to the library above them.
     */
    private val nowPlaying = NowPlayingTile(
        context = context,
        compact = true,
        preferPackage = { session?.app?.packageName },
    )

    private var apps: List<MediaApp> = emptyList()

    /** The last thing the current session said, for [describe]. */
    private var lastState: BrowseState = BrowseState.UNKNOWN
    private var lastCount: Int = 0

    private val listener = CarMediaBrowser.Listener { state, items ->
        lastState = state
        lastCount = items.size
        renderBrowse(state, items)
    }

    override fun createView(context: Context): View {
        val panel = CarStyle.panel(context)
        val gap = CarStyle.gutter(context)

        val label = CarStyle.label(context, 17f, CarStyle.TEXT, bold = true)
        val back = CarStyle.button(context, "Back") { goBack() }.apply {
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                marginEnd = gap
            }
        }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(back)
            addView(label, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = gap / 2
            }
        }

        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(bar)
        panel.addView(
            ScrollView(context).apply {
                isFillViewport = true
                addView(list, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            },
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f),
        )
        // Below the list and outside the scroller: it must stay put while the
        // library scrolls, which is the whole point of a now-playing bar.
        panel.addView(
            nowPlaying.createView(context),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )

        header = bar
        headerLabel = label
        backTarget = back
        rows = list
        // Deliberately *not* renderApps() here. createView runs before start(),
        // so `apps` is still empty and the pane would flash "No app on this
        // phone publishes a media library" every time it is built. Show the
        // header and let start() fill it.
        headerLabel?.text = "Music and podcasts"
        backTarget?.visibility = View.GONE
        return panel
    }

    override fun start() {
        if (running) return
        running = true
        // Started first, so a session that is already playing is on screen
        // before the library finishes binding.
        runCatching { nowPlaying.start() }
        // Re-read on every start rather than once: an app installed mid-drive
        // should appear, and the query is one cheap PackageManager call.
        // Allowed, not merely installed: every row here opens an app, and a row
        // the allow list would refuse is a dead button.
        apps = MediaApps.allowed(appContext)
        // The driver's chosen player, if they have one and it is still
        // installed. A panel that opens on a list of one app every drive is a
        // tap nobody wanted; the same reasoning as the Maps panel's own app
        // setting, which a driver asked to have here too.
        //
        // Falls through to the list when nothing is chosen, when the chosen
        // package has been uninstalled, or when it no longer publishes a
        // library -- all three of which look identical from here and all three
        // of which the list answers.
        val preferred = HeadwaySettings.mediaApp(appContext)
            ?.let { wanted -> apps.firstOrNull { it.packageName == wanted } }
        if (preferred != null) {
            openApp(preferred)
            return
        }
        // Otherwise the app list: stop() closes and nulls the session, so there
        // is never one to resume. The branch that pretended otherwise was dead.
        renderApps()
    }

    override fun stop() {
        if (!running) return
        running = false
        // The strip holds a MediaController callback and a session listener of
        // its own; leaving them registered outlives the pane that owns them.
        runCatching { nowPlaying.stop() }
        // The session goes with the pane. A browser left connected holds a
        // binding into another app's process, and its subscriptions call back
        // into views that are about to be thrown away.
        session?.close()
        session = null
        lastState = BrowseState.UNKNOWN
        lastCount = 0
    }

    override fun describe(): String {
        val current = session
            ?: return "media browse: ${apps.size} app(s) with a library, none open"
        return "media browse: ${current.app.label}, ${lastState.name.lowercase()}, " +
            "$lastCount item(s); ${nowPlaying.describe()}"
    }

    // --- navigation ------------------------------------------------------------

    private fun openApp(app: MediaApp) {
        // Remembered on every open, including the driver picking from the list.
        // Choosing an app in a picker *is* the act of setting a default; making
        // them say it twice, once here and once in a settings screen on the
        // phone, would be the kind of ceremony this pane exists to avoid. The
        // phone's *Music app* row is where it is changed deliberately, and
        // where "no default" can be chosen again.
        runCatching { HeadwaySettings.setMediaApp(appContext, app.packageName) }
        session?.close()
        val next = CarMediaBrowser(appContext, app, onStep)
        session = next
        // The strip prefers this app's session, and the preference has just
        // changed. Without this it keeps showing whatever it bound at start()
        // until that session next says something.
        runCatching { nowPlaying.reconsider() }
        next.connect(listener)
    }

    /**
     * One level up, or out of the app entirely when already at its root.
     *
     * Leaving the app closes the browser rather than parking it: holding a
     * binding to an app the driver has navigated out of is exactly the leak
     * [CarMediaBrowser.close] exists to prevent, and reconnecting costs one
     * bind.
     */
    private fun goBack() {
        val current = session
        if (current == null) return
        if (current.canGoBack()) {
            current.back()
            return
        }
        current.close()
        session = null
        lastState = BrowseState.UNKNOWN
        lastCount = 0
        runCatching { nowPlaying.reconsider() }
        renderApps()
    }

    // --- rendering -------------------------------------------------------------

    private fun renderApps() {
        val list = rows ?: return
        val context = list.context
        list.removeAllViews()
        headerLabel?.text = "Music and podcasts"
        // Nothing to go back to from the top of the tree.
        backTarget?.visibility = View.GONE

        if (apps.isEmpty()) {
            list.gravity = Gravity.CENTER
            list.addView(
                CarStyle.emptyState(
                    context,
                    "No app on this phone publishes a media library.\n" +
                        "Install one, or use the Now playing pane to control whatever is " +
                        "already playing.",
                )
            )
            return
        }
        list.gravity = Gravity.TOP
        apps.forEach { app ->
            list.addView(
                row(
                    context = context,
                    icon = app.icon(context),
                    title = app.label,
                    subtitle = null,
                    browsable = true,
                ) { openApp(app) }
            )
        }
    }

    private fun renderBrowse(state: BrowseState, items: List<MediaBrowser.MediaItem>) {
        val list = rows ?: return
        val current = session ?: return
        val context = list.context
        list.removeAllViews()
        backTarget?.visibility = View.VISIBLE
        headerLabel?.text = current.breadcrumb().ifBlank { current.app.label }

        val message = when (state) {
            BrowseState.WORKING -> "Loading…"
            BrowseState.REFUSED ->
                "${current.app.label} does not let other players browse its library.\n" +
                    "That is the app's own setting, not a Headway limitation — its " +
                    "playback controls still work in the Now playing pane."
            BrowseState.TIMED_OUT ->
                "${current.app.label} did not answer.\n" +
                    "Open it once on the phone and try again."
            BrowseState.EMPTY -> "Nothing here."
            else -> null
        }
        if (message != null) {
            list.gravity = Gravity.CENTER
            list.addView(CarStyle.emptyState(context, message))
            return
        }

        list.gravity = Gravity.TOP
        items.forEach { item ->
            val description = item.description
            val title = description.title?.toString()?.takeIf { it.isNotBlank() } ?: "Untitled"
            // Browsable wins when an item is both, for the reason in the class
            // KDoc.
            val browsable = (item.flags and MediaBrowser.MediaItem.FLAG_BROWSABLE) != 0
            val playable = (item.flags and MediaBrowser.MediaItem.FLAG_PLAYABLE) != 0
            list.addView(
                row(
                    context = context,
                    icon = artOf(description),
                    title = title,
                    subtitle = description.subtitle?.toString()?.takeIf { it.isNotBlank() },
                    browsable = browsable,
                ) {
                    // Browsable wins only when it can actually be entered. An
                    // item flagged browsable with no usable id used to fall
                    // through to play() and fail silently on the same missing
                    // id; a dual-flag item could never be played at all.
                    val id = item.mediaId?.takeIf { it.isNotBlank() }
                    when {
                        browsable && id != null -> current.open(id, title)
                        playable -> if (!current.play(item)) {
                            onStep("media: ${current.app.label} would not start '$title'")
                        }
                        browsable -> onStep("media: '$title' is a folder with no id")
                        else -> onStep("media: '$title' is neither browsable nor playable")
                    }
                }
            )
        }
    }

    /**
     * A row: art, two lines, and a chevron when it goes somewhere.
     *
     * Deliberately not a `RecyclerView`. A browse level is tens of items, not
     * thousands, the pane is 480 pixels tall, and a recycler would add an
     * adapter, a view holder and a dependency to draw at most a screen and a
     * half of rows that are rebuilt wholesale on every navigation anyway.
     */
    private fun row(
        context: Context,
        icon: Drawable?,
        title: String,
        subtitle: String?,
        browsable: Boolean,
        onClick: () -> Unit,
    ): View {
        val gap = CarStyle.gutter(context)
        val target = CarStyle.dp(context, CarStyle.PRIMARY_TARGET_DP)
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = target
            setPadding(gap, gap / 2, gap, gap / 2)
            isFocusable = true
            contentDescription = title
            Headway.pressable(this, CarStyle.radius(context)) { onClick() }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = gap / 4
            }
        }

        val artSize = (target * ART_FRACTION).toInt()
        line.addView(
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageDrawable(icon)
                // Hidden rather than left blank: a column of empty squares down
                // the left of a playlist is worse than no column at all.
                visibility = if (icon == null) View.GONE else View.VISIBLE
                layoutParams = LinearLayout.LayoutParams(artSize, artSize).apply {
                    marginEnd = gap
                }
            },
        )

        // Named `column` and not `text`: a local called `text` shadows the
        // TextView's own property inside every `apply` block below it, which
        // Kotlin resolves silently in favour of the local.
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(CarStyle.label(context, 16f, CarStyle.TEXT).apply { this.text = title })
        if (subtitle != null) {
            column.addView(CarStyle.label(context, 13f, CarStyle.DIM).apply { this.text = subtitle })
        }
        line.addView(column, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        if (browsable) {
            line.addView(
                CarStyle.label(context, 20f, CarStyle.ACCENT).apply {
                    // U+203A, a single right-pointing angle quotation mark. In
                    // every Latin font there is; unlike the arrows and the media
                    // glyphs, which are not.
                    this.text = "\u203a"
                    gravity = Gravity.CENTER
                },
            )
        }
        return line
    }

    /**
     * The item's own art, when it shipped a bitmap of a sane size.
     *
     * Only the bitmap, not `iconUri`. Resolving a URI means a content-resolver
     * read or an HTTP fetch per row, on the thread drawing the car screen, for
     * artwork nobody navigates by — and Headway does no network.
     *
     * The size check is not defensive padding. Every `getIconBitmap()` is a
     * fresh bitmap unparceled into this process and the framework caps nothing;
     * a hundred rows of 512x512 art is roughly a hundred megabytes, on a phone
     * that is simultaneously encoding H.264 for the car. `CarMediaBrowser` asks
     * for smaller art in its root hints, which androidx and Media3 services
     * honour — this is the backstop for one that does not.
     */
    private fun artOf(description: android.media.MediaDescription): Drawable? {
        val bitmap = description.iconBitmap ?: return null
        if (bitmap.byteCount > MAX_ART_BYTES) return null
        return BitmapDrawable(appContext.resources, bitmap)
    }

    private companion object {
        /** Art is a little smaller than the row so the row breathes. */
        const val ART_FRACTION = 0.72f

        /**
         * The largest single row image that will be drawn.
         *
         * 512 KiB is a 362x362 ARGB_8888 bitmap — comfortably above the 128 px
         * asked for in the root hints, and far below the point where a full
         * level becomes a memory problem.
         */
        const val MAX_ART_BYTES = 512 * 1024
    }
}

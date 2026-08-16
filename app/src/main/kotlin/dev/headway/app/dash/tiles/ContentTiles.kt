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

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.InputType
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import dev.headway.app.dash.DashTile
import dev.headway.app.log.SessionLog
import dev.headway.app.nav.NavigationFeed
import dev.headway.app.phone.CarPhone
import dev.headway.app.service.HeadwayService
import dev.headway.app.ui.HeadwaySettings
import dev.headway.app.ui.theme.Headway
import dev.headway.app.ui.theme.HeadwayMark
import dev.headway.app.ui.theme.ProgressRule
import dev.headway.app.ui.theme.TransportButton
import dev.headway.dash.PaneKind
import dev.headway.transport.LinkState
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG = "HeadwayDashTiles"

/**
 * The setting that says which notification listeners the user has enabled.
 *
 * World-readable, so observing it needs no permission -- which is what lets a
 * pane notice the grant arriving instead of staying empty until a rebuild.
 * Spelled out rather than referenced through the `@hide`
 * `Settings.Secure.ENABLED_NOTIFICATION_LISTENERS`.
 */
private const val ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners"

/** The art box's fallback size, used before the view has been measured. */
private const val ART_BOX_DP = 84f

/** A ceiling on the halving, so a malformed image cannot loop. */
private const val MAX_ART_SAMPLE = 32

// ---------------------------------------------------------------------------
// Now playing
// ---------------------------------------------------------------------------

/**
 * What is playing, from whichever app is playing it, with working controls.
 *
 * ## Why this is a data tile and not a window
 *
 * ADR 0004: Headway cannot put Spotify's own now-playing screen on a display it
 * owns. What it can do is what Android Auto itself does — read the *model* the
 * app publishes and draw it. `MediaSession` is that model, and it is complete:
 * title, artist, album art and a set of transport commands, published by every
 * media app on the phone because the lock screen and the notification shade
 * consume the same thing.
 *
 * ## The permission, and why a `SecurityException` is not an error here
 *
 * [MediaSessionManager.getActiveSessions] takes the `ComponentName` of one of the
 * caller's own enabled `NotificationListenerService`s and throws
 * `SecurityException` when that service is not enabled. That is the platform's
 * only gate on reading other apps' sessions, it is a user-granted special access
 * in the same class as the accessibility grant, and it is off until somebody
 * turns it on in Settings. So "not granted" is the *normal* first state of this
 * tile, not a fault: it renders an instruction and keeps working, and it will
 * pick the sessions up the moment the grant appears, because [start] re-reads
 * them and the grant survives.
 *
 * There is deliberately no "grant it" button on the tile. Tapping one would
 * launch Settings onto display 0 — the phone — where a driver looking at the car
 * screen would never see it. [notificationAccessIntent] exists so the phone-side
 * setup screen can offer that button in the one place the resulting Settings
 * page is visible.
 *
 * ## Which session
 *
 * The one that is actually playing, else the first the platform offers.
 * `getActiveSessions` returns sessions in the platform's own priority order, so
 * "the first one" is already "the most recently active"; preferring a playing
 * session over that ordering matters when a paused podcast sorts above the music
 * that is audible in the cabin, which is the case a driver notices.
 */
class NowPlayingTile(
    context: Context,
    /**
     * Draw as a strip inside another pane rather than as a pane of its own.
     *
     * The reason this exists is a driver's: "symfonium doesnt show the now
     * playing UI inside of it, I dont want to have a seperate now playing
     * panel, I want it to be just like the android auto version". Android Auto
     * puts what is playing at the foot of the app's own library screen, not in
     * a second panel beside it, and a car screen 480 pixels tall cannot afford
     * to spend a whole pane on three lines and three buttons.
     *
     * Same session plumbing, same rendering, a shorter layout — and one
     * behavioural difference: with nothing playing the strip disappears instead
     * of explaining itself, because the pane around it is not about playback
     * and a permanent "nothing is playing" would be a permanent tax on the
     * library list.
     */
    private val compact: Boolean = false,
    /**
     * A package whose session should win when several are active.
     *
     * The strip belongs to the app being browsed, so it must follow that app
     * and not whichever session happens to be first — otherwise opening a music
     * library while a podcast is paused shows the podcast's controls under the
     * music app's list. Null, or a package with no session, falls back to the
     * ordinary "playing first" choice.
     */
    private val preferPackage: () -> String? = { null },
    /**
     * Called when the strip is tapped, with the session it is showing.
     *
     * Null means the strip is not a target. The pane that embeds it decides
     * what a tap means -- the music pane opens the play queue, which is the
     * other half of "just like the android auto version" and the half a
     * transport row cannot express.
     */
    private val onOpen: ((MediaController) -> Unit)? = null,
) : DashTile {

    private val appContext: Context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    override val kind: String = PaneKind.NOW_PLAYING

    /** The whole tile, so [compact] can collapse it when nothing is playing. */
    private var rootView: View? = null

    private var art: ImageView? = null
    private var titleText: TextView? = null
    private var artistText: TextView? = null
    private var sourceText: TextView? = null
    private var progress: ProgressRule? = null
    private var content: View? = null
    private var empty: View? = null
    private var emptyMessage: TextView? = null
    private var transport: LinearLayout? = null
    private var playPause: TransportButton? = null

    private var running = false

    /** The session being shown, or null when nothing is. */
    private var controller: MediaController? = null

    /** Null while [running] is false; held so [stop] can unregister exactly it. */
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null

    /**
     * Live updates for the session currently bound.
     *
     * Registered per controller rather than once, because a `MediaController` is
     * a handle to one session: switching from the podcast to the music app means
     * unregistering from the first and registering on the second, and forgetting
     * the first half leaks a callback into a session Headway no longer draws.
     *
     * The parameters are nullable because the platform genuinely passes null —
     * an app that clears its metadata reports the clearing as a null, and a
     * non-null Kotlin signature would turn that into a crash in a car.
     */
    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = render()

        override fun onMetadataChanged(metadata: MediaMetadata?) = render()

        override fun onSessionDestroyed() {
            // The controller is dead, not merely idle: rebind rather than
            // re-render, or the tile shows a track from an app that has exited.
            refresh()
        }
    }

    /**
     * Album art, the track, a progress rule, and three drawn controls.
     *
     * Laid out as two mutually exclusive children of one frame — the content and
     * the empty state — rather than by hiding four views individually, which is
     * what the first version did and which produced a pane containing one
     * left-aligned grey sentence and nothing else.
     */
    override fun createView(context: Context): View {
        if (compact) return createStrip(context)
        val root = FrameLayout(context)
        val panel = CarStyle.panel(context)
        val gap = CarStyle.gutter(context)

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val artSize = CarStyle.dp(context, 84f)
        val artView = ImageView(context).apply {
            // FIT_CENTER rather than CENTER_CROP: album art is square by
            // convention but not by rule, and cropping a podcast's wide cover
            // art removes the part with the wordmark on it.
            scaleType = ImageView.ScaleType.FIT_CENTER
            clipToOutline = true
            background = Headway.panel(CarStyle.radius(context), Headway.SURFACE_RAISED)
            layoutParams = LinearLayout.LayoutParams(artSize, artSize).apply {
                marginEnd = gap
            }
        }
        val titleView = CarStyle.label(context, 21f, CarStyle.TEXT, bold = true)
        val artistView = CarStyle.label(context, 16f, CarStyle.DIM)
        // The app the audio is coming from, in the accent and small. A driver
        // with a podcast paused and music playing needs to know which one these
        // buttons are about, and the artist line is not it.
        val sourceView = CarStyle.label(context, 13f, CarStyle.ACCENT).apply {
            letterSpacing = 0.06f
        }
        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(sourceView)
            addView(titleView)
            addView(artistView)
        }
        header.addView(artView)
        header.addView(textColumn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        val progressView = ProgressRule(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                MATCH_PARENT,
                CarStyle.dp(context, 4f),
            ).apply { topMargin = gap }
        }

        val controlSize = CarStyle.dp(context, CarStyle.PRIMARY_TARGET_DP)
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = gap
            }
        }
        fun control(kind: Int, emphasis: Boolean, onPress: () -> Unit) =
            TransportButton(context, kind, onPress).apply {
                emphasised = emphasis
                layoutParams = LinearLayout.LayoutParams(controlSize, controlSize).apply {
                    marginStart = gap / 2
                    marginEnd = gap / 2
                }
            }
        controls.addView(
            control(TransportButton.PREVIOUS, false) { command { it.skipToPrevious() } },
        )
        val playPauseView = control(TransportButton.PLAY, true) { togglePlayback() }
        controls.addView(playPauseView)
        controls.addView(control(TransportButton.NEXT, false) { command { it.skipToNext() } })

        panel.addView(header)
        panel.addView(progressView)
        panel.addView(controls)

        val emptyView = CarStyle.emptyState(context, NO_ACCESS_HINT)
        emptyView.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        panel.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        root.addView(panel)
        root.addView(emptyView)

        art = artView
        titleText = titleView
        artistText = artistView
        sourceText = sourceView
        progress = progressView
        transport = controls
        playPause = playPauseView
        content = panel
        empty = emptyView
        emptyMessage = (emptyView as? LinearLayout)?.getChildAt(1) as? TextView
        rootView = root

        render()
        return root
    }

    /**
     * The same tile as one row: art, two lines, three controls, a rule.
     *
     * Horizontal rather than stacked, because the space this occupies is height
     * taken from the list above it. Everything else — the fields, the binding,
     * [render] — is shared with the full pane, so a fix to either reaches both.
     */
    private fun createStrip(context: Context): View {
        val gap = CarStyle.gutter(context)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val artSize = CarStyle.dp(context, 52f)
        val artView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            clipToOutline = true
            background = Headway.panel(CarStyle.radius(context), Headway.SURFACE_RAISED)
            layoutParams = LinearLayout.LayoutParams(artSize, artSize).apply { marginEnd = gap }
        }
        val titleView = CarStyle.label(context, 16f, CarStyle.TEXT, bold = true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val artistView = CarStyle.label(context, 13f, CarStyle.DIM).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        // The source line is dropped, not hidden: inside a library the app is
        // named by the pane's own header, and repeating it costs a line the
        // strip does not have. The field still has to exist -- `render` reads
        // it -- so it is a detached view nothing adds.
        val sourceView = CarStyle.label(context, 13f, CarStyle.ACCENT)
        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleView)
            addView(artistView)
        }

        val controlSize = CarStyle.dp(context, CarStyle.PRIMARY_TARGET_DP)
        fun control(kind: Int, emphasis: Boolean, onPress: () -> Unit) =
            TransportButton(context, kind, onPress).apply {
                emphasised = emphasis
                layoutParams = LinearLayout.LayoutParams(controlSize, controlSize).apply {
                    marginStart = gap / 2
                }
            }
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(control(TransportButton.PREVIOUS, false) { command { it.skipToPrevious() } })
        }
        val playPauseView = control(TransportButton.PLAY, true) { togglePlayback() }
        controls.addView(playPauseView)
        controls.addView(control(TransportButton.NEXT, false) { command { it.skipToNext() } })

        row.addView(artView)
        row.addView(textColumn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(controls)
        // The art and the text, not the whole row: the transport buttons are
        // in the row too, and a parent that also took taps would make every
        // missed skip open the queue instead.
        onOpen?.let { open ->
            val target = View.OnClickListener { controller?.let(open) }
            artView.setOnClickListener(target)
            textColumn.setOnClickListener(target)
            textColumn.isClickable = true
        }

        val progressView = ProgressRule(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                MATCH_PARENT,
                CarStyle.dp(context, 3f),
            ).apply { topMargin = gap / 2 }
        }

        val strip = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(gap, gap / 2, gap, gap / 2)
            addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(progressView)
        }

        // The strip is one row when there is width for one and two when there
        // is not. A driver put a music panel down the side of their car screen
        // and got cover, title, artist and three transport buttons crushed into
        // a single line about a hundred pixels wide -- so below [STACK_BELOW_DP]
        // the controls move under the art and the text instead, which is the
        // same content in the shape the space actually has.
        //
        // Measured rather than assumed: the panel is a pane in a tree the
        // driver can re-split and drag at any moment, so this has to answer on
        // every layout pass and not once at build time.
        strip.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val widthDp = (right - left) / context.resources.displayMetrics.density
            val stacked = widthDp > 0 && widthDp < STACK_BELOW_DP
            val wanted = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            if (row.orientation == wanted) return@addOnLayoutChangeListener
            // Posted: this runs during layout, and re-parenting a child from
            // inside a layout pass is the classic requestLayout-during-layout
            // loop.
            strip.post {
                if (row.orientation == wanted) return@post
                row.orientation = wanted
                row.gravity = if (stacked) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
                textColumn.layoutParams = if (stacked) {
                    LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                } else {
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                }
                artView.layoutParams = LinearLayout.LayoutParams(artSize, artSize).apply {
                    if (stacked) {
                        gravity = Gravity.CENTER_HORIZONTAL
                        bottomMargin = gap / 2
                    } else {
                        marginEnd = gap
                    }
                }
                controls.layoutParams = if (stacked) {
                    LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                        topMargin = gap / 2
                    }
                } else {
                    LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                }
                controls.gravity = Gravity.CENTER
                textColumn.gravity = if (stacked) Gravity.CENTER_HORIZONTAL else Gravity.NO_GRAVITY
                titleView.gravity = textColumn.gravity
                artistView.gravity = textColumn.gravity
            }
        }

        art = artView
        titleText = titleView
        artistText = artistView
        sourceText = sourceView
        progress = progressView
        transport = controls
        playPause = playPauseView
        content = strip
        empty = null
        emptyMessage = null
        rootView = strip

        render()
        return strip
    }

    override fun start() {
        if (running) return
        running = true

        val manager = appContext.getSystemService(MediaSessionManager::class.java)
        if (manager == null) {
            SessionLog.shared.warn(TAG, "no MediaSessionManager; now playing cannot start")
            render()
            return
        }

        // A lambda, held in a field, because removal is by identity: building a
        // second one at stop() time would remove nothing and leak this tile for
        // as long as the process lives.
        val listener = MediaSessionManager.OnActiveSessionsChangedListener { refresh() }
        val registered = runCatching {
            manager.addOnActiveSessionsChangedListener(listener, listenerComponent(appContext), handler)
        }
        if (registered.isFailure) {
            // Expected until the user grants notification access. Logged at info
            // rather than warn so a first-run log does not read like a fault.
            SessionLog.shared.info(TAG, "media sessions unavailable: notification access not granted")
            // And watched for, which is the half that was missing. `running` was
            // already true by here and `start()` opens with `if (running)
            // return`, so nothing ever tried again: a driver who followed the
            // pane's own advice, granted access on the phone and came back saw
            // the identical empty state for the rest of the drive. Only a full
            // tile rebuild recovered it, and that needs a tab switch.
            //
            // The grant is readable without any permission, so this is a
            // ContentObserver on the setting rather than a poll.
            watchForGrant()
            render()
            return
        }
        sessionsListener = listener
        refresh()
    }

    /**
     * Re-arms once the driver grants notification access, without a rebuild.
     *
     * `Settings.Secure.enabled_notification_listeners` is world-readable, so
     * observing it costs nothing and needs nothing. When it changes, the
     * registration is simply tried again: if it now succeeds the pane fills in,
     * and if it does not this stays armed.
     */
    private fun watchForGrant() {
        if (grantWatcher != null) return
        val resolver = runCatching { appContext.contentResolver }.getOrNull() ?: return
        val uri = runCatching {
            android.provider.Settings.Secure.getUriFor(ENABLED_NOTIFICATION_LISTENERS)
        }.getOrNull() ?: return
        val observer = object : android.database.ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                // Posted to the main looper rather than run inline, so this can
                // arrive *after* `stop()` has already unregistered the observer
                // -- and a second one can be sitting queued behind the first,
                // because the setting it watches is a process-wide list that
                // any app's grant rewrites. Either way a registration made here
                // would never come back out: `stop()` returns early when
                // `running` is false, and `sessionsListener` remembers only the
                // newest, orphaning the one it replaced into a process
                // singleton that holds this whole pane's view tree.
                if (!running || sessionsListener != null) {
                    stopWatchingForGrant()
                    return
                }
                val manager = appContext.getSystemService(MediaSessionManager::class.java) ?: return
                val listener = MediaSessionManager.OnActiveSessionsChangedListener { refresh() }
                val ok = runCatching {
                    manager.addOnActiveSessionsChangedListener(
                        listener,
                        listenerComponent(appContext),
                        handler,
                    )
                }.isSuccess
                if (!ok) return
                SessionLog.shared.info(TAG, "notification access granted; now playing is live")
                sessionsListener = listener
                stopWatchingForGrant()
                refresh()
            }
        }
        runCatching { resolver.registerContentObserver(uri, false, observer) }
            .onSuccess { grantWatcher = observer }
    }

    private fun stopWatchingForGrant() {
        grantWatcher?.let { observer ->
            grantWatcher = null
            runCatching { appContext.contentResolver.unregisterContentObserver(observer) }
        }
    }

    private var grantWatcher: android.database.ContentObserver? = null

    /** So the refusal is said once a session rather than once a repaint. */
    private var refusalLogged: Boolean = false

    override fun stop() {
        stopWatchingForGrant()
        if (!running) return
        running = false

        sessionsListener?.let { listener ->
            val manager = appContext.getSystemService(MediaSessionManager::class.java)
            runCatching { manager?.removeOnActiveSessionsChangedListener(listener) }
        }
        sessionsListener = null
        bind(null)
        render()
    }

    override fun describe(): String {
        if (!notificationAccessGranted(appContext)) return "now playing: no notification access"
        val current = controller ?: return "now playing: nothing active"
        val metadata = current.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "unknown track"
        val state = current.playbackState?.state
        return "now playing: '$title' from ${current.packageName}, ${stateName(state)}"
    }

    // --- session plumbing ----------------------------------------------------

    /**
     * Bind again, because the answer to [preferPackage] has changed.
     *
     * A strip inside a library follows the app that library belongs to, and
     * that app changes when the driver opens a different one. Nothing in the
     * media-session world announces that -- the sessions did not change, the
     * preference did -- so the pane holding the strip has to say so.
     */
    fun reconsider() {
        if (!running) return
        refresh()
    }

    /** Re-reads the active sessions and binds whichever one should be shown. */
    private fun refresh() {
        if (!running) {
            bind(null)
            render()
            return
        }
        val manager = appContext.getSystemService(MediaSessionManager::class.java)
        val sessions = runCatching {
            manager?.getActiveSessions(listenerComponent(appContext))
        }.onFailure {
            // Said once. This used to be `.getOrNull()` with nothing logged at
            // all, so an exported log from a drive where the pane was empty
            // contained no trace of why -- against the one rule that makes a
            // real-car log worth having.
            if (!refusalLogged) {
                refusalLogged = true
                SessionLog.shared.warn(TAG, "getActiveSessions refused: $it")
            }
        }.getOrNull().orEmpty()
        val wanted = runCatching { preferPackage() }.getOrNull()
        bind(
            wanted?.let { name -> sessions.firstOrNull { it.packageName == name } }
                ?: sessions.firstOrNull { isActive(it.playbackState?.state) }
                ?: sessions.firstOrNull(),
        )
        render()
    }

    /**
     * Cover art published as a `content://` URI rather than as a bitmap.
     *
     * Decoded at the size the pane actually shows -- `inSampleSize` against the
     * art box -- because a full-resolution cover is several megabytes and this
     * runs on the thread drawing the car screen. Cached by URI so a repaint,
     * which happens on every playback-state push, does not decode again.
     *
     * Every failure is a null: a cross-app `content://` may refuse, and a pane
     * that falls back to the app's icon is the behaviour that was already there.
     */
    private fun artFromUri(metadata: MediaMetadata?): android.graphics.Bitmap? {
        val uri = listOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
        ).firstNotNullOfOrNull { key ->
            metadata?.getString(key)?.takeIf { it.isNotBlank() }
        } ?: return null
        cachedArtUri?.let { if (it == uri) return cachedArt }
        // `art`, the field -- `artView` is a local inside `render()` and is not
        // in scope here. Falls back to the nominal box before the first layout.
        val box = art?.width?.takeIf { it > 0 } ?: CarStyle.dp(appContext, ART_BOX_DP)
        val decoded = runCatching {
            val parsed = android.net.Uri.parse(uri)
            val bounds = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            appContext.contentResolver.openInputStream(parsed)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, bounds)
            }
            val sample = generateSequence(1) { it * 2 }
                .first { bounds.outWidth / it <= box || it >= MAX_ART_SAMPLE }
            val options = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sample
            }
            appContext.contentResolver.openInputStream(parsed)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, options)
            }
        }.getOrNull()
        cachedArtUri = uri
        cachedArt = decoded
        if (decoded == null) SessionLog.shared.info(TAG, "cover art at $uri could not be read")
        return decoded
    }

    private var cachedArtUri: String? = null
    private var cachedArt: android.graphics.Bitmap? = null

    private fun bind(next: MediaController?) {
        val current = controller
        if (current?.sessionToken == next?.sessionToken) {
            // Same session, new handle. Rebinding would drop and re-add the
            // callback for no reason and briefly blank the tile.
            if (next != null) controller = next
            return
        }
        if (current != null) runCatching { current.unregisterCallback(controllerCallback) }
        controller = next
        if (next != null) {
            runCatching { next.registerCallback(controllerCallback, handler) }
        }
    }

    private fun togglePlayback() {
        val current = controller ?: return
        val playing = isActive(current.playbackState?.state)
        runCatching {
            if (playing) current.transportControls.pause() else current.transportControls.play()
        }
    }

    // Named `command` rather than `transport` so it cannot be confused with the
    // property of that name holding the button row.
    private fun command(action: (MediaController.TransportControls) -> Unit) {
        val current = controller ?: return
        // Not gated on PlaybackState.getActions(). Apps under-declare that
        // bitmask routinely — a session with actions == 0 that skips perfectly
        // well is common — and a greyed-out Next that would have worked is worse
        // in a car than a Next that occasionally does nothing.
        runCatching { action(current.transportControls) }
            .onFailure { SessionLog.shared.warn(TAG, "transport command refused: $it") }
    }

    // --- rendering -----------------------------------------------------------

    private fun render() {
        val artView = art ?: return
        val titleView = titleText ?: return
        val artistView = artistText ?: return
        val sourceView = sourceText ?: return
        val panel = content ?: return

        val current = controller
        val message = when {
            !notificationAccessGranted(appContext) -> NO_ACCESS_HINT
            current == null -> NOTHING_PLAYING_HINT
            else -> null
        }
        if (message != null) {
            // A strip says nothing and takes no room. The pane it sits in has
            // its own subject and its own empty state; a second sentence
            // explaining that no music is playing would be under a library the
            // driver is at that moment looking through in order to start some.
            val blank = empty ?: run {
                rootView?.visibility = View.GONE
                return
            }
            emptyMessage?.text = message
            if (blank.visibility != View.VISIBLE) {
                blank.visibility = View.VISIBLE
                Headway.revealIn(blank)
            }
            panel.visibility = View.GONE
            return
        }
        empty?.visibility = View.GONE
        rootView?.let { root ->
            if (root.visibility != View.VISIBLE) {
                root.visibility = View.VISIBLE
                if (compact) Headway.revealIn(root)
            }
        }
        if (panel.visibility != View.VISIBLE) {
            panel.visibility = View.VISIBLE
            Headway.revealIn(panel)
        }
        // Smart-cast does not survive the null check above, because `controller`
        // is a mutable field a callback can change between the two reads.
        val session = current ?: return

        val metadata = session.metadata
        titleView.text = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: "Playing"
        // Artist and album on one line, the way a car screen has room for and
        // the way Android Auto reads: "Artist - Album". The album was never
        // read at all before, which a driver noticed.
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION)
        artistView.text = listOfNotNull(
            artist?.takeIf { it.isNotBlank() },
            album?.takeIf { it.isNotBlank() && it != artist },
        ).joinToString(" \u00b7 ")
        artistView.visibility =
            if (artistView.text.isNullOrBlank()) View.GONE else View.VISIBLE
        sourceView.text = appLabel(session.packageName).uppercase()

        // Three keys because apps disagree about which one is the cover: music
        // players fill ALBUM_ART, podcast and radio apps often fill only
        // DISPLAY_ICON, and ART is the generic fallback the framework defines.
        val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            // And by URI, which is how a Media3 app publishes it. Media3's
            // legacy bridge fills the *_ART_URI keys and only fills the bitmap
            // keys when raw artwork bytes happen to exist -- so for a Media3
            // player the three reads above are all null and the pane fell back
            // to the app's launcher icon. A driver reported exactly that about
            // Symfonium: no cover.
            ?: artFromUri(metadata)
        if (bitmap == null) {
            // The app's own icon rather than an empty square: a radio stream
            // with no cover art still has a recognisable source, and a hole
            // where the art goes makes the pane look half-loaded.
            artView.setImageDrawable(appIcon(appContext, session.packageName))
            artView.setPadding(
                CarStyle.gutter(artView.context),
                CarStyle.gutter(artView.context),
                CarStyle.gutter(artView.context),
                CarStyle.gutter(artView.context),
            )
        } else {
            artView.setImageBitmap(bitmap)
            artView.setPadding(0, 0, 0, 0)
        }

        val state = session.playbackState
        progress?.set(
            state?.position ?: 0L,
            metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
        )

        val playing = isActive(state?.state)
        playPause?.kind = if (playing) TransportButton.PAUSE else TransportButton.PLAY
    }

    /** The posting app's display name, or its package when it has none. */
    private fun appLabel(packageName: String): String = runCatching {
        val packages = appContext.packageManager
        packages.getApplicationLabel(
            packages.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L)),
        ).toString()
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: packageName

    private fun isActive(state: Int?): Boolean =
        state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING

    private fun stateName(state: Int?): String = when (state) {
        PlaybackState.STATE_PLAYING -> "playing"
        PlaybackState.STATE_BUFFERING -> "buffering"
        PlaybackState.STATE_PAUSED -> "paused"
        null -> "no playback state"
        else -> "state $state"
    }

    companion object {

        /**
         * Names the exact toggle, because the old wording did not.
         *
         * It said "open Headway on the phone and turn it on there", and Headway
         * on the phone had no such control — the setup screen never mentioned
         * notification access at all. A driver following that instruction found
         * nothing, which is how this pane came to be reported as broken. The
         * phone screen now carries the row and the button; this says which one.
         */
        private const val NO_ACCESS_HINT =
            "Turn on \"Now playing and messages\" in Headway on the phone " +
                "to control any app's music from here."

        private const val NOTHING_PLAYING_HINT = "Nothing is playing."

        /**
         * Below this width, the compact strip stacks instead of crowding.
         *
         * 260 dp is about where a 52 dp cover, a transport row of three touch
         * targets and any readable amount of title stop fitting on one line --
         * three 48 dp buttons plus the art is already 200 of it, which leaves a
         * title column narrower than a word. A driver with a music panel down
         * the side of their screen reported exactly that crush.
         */
        private const val STACK_BELOW_DP = 260f

        /**
         * The component whose enablement is the grant.
         *
         * `getActiveSessions` does not ask for a permission string; it asks for
         * the name of a `NotificationListenerService` belonging to the caller and
         * checks that the user has enabled *that*. Headway has exactly one, so
         * the same grant serves this tile and [MessagesTile], and a user who
         * turns it on for messages gets media control for free.
         */
        fun listenerComponent(context: Context): ComponentName =
            HeadwayNotificationListener.componentOf(context)

        /**
         * Whether the user has enabled Headway's notification listener.
         *
         * Asked of `NotificationManager` rather than parsed out of the
         * `enabled_notification_listeners` secure setting, which is the older
         * trick and is both undocumented and wrong for secondary users.
         */
        fun notificationAccessGranted(context: Context): Boolean {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return false
            return runCatching {
                manager.isNotificationListenerAccessGranted(listenerComponent(context))
            }.getOrDefault(false)
        }

        /**
         * The Settings page where the grant is made, for the phone-side setup UI.
         *
         * The plain listener *list* rather than
         * `ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS`, which would land
         * directly on Headway's own row: the detail screen needs the component
         * passed as an extra and is not present on every build, whereas the list
         * has existed unchanged since API 22 and always resolves. One extra tap
         * is worth an intent that cannot fail to open.
         *
         * `NEW_TASK` because the caller may be a service or a tile rather than an
         * activity.
         */
        fun notificationAccessIntent(): Intent =
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

// ---------------------------------------------------------------------------
// Messages
// ---------------------------------------------------------------------------

/**
 * One notification, flattened to what a car screen can show at a glance.
 *
 * Deliberately not [CarMessage]: that one models a *conversation* -- a sender, a
 * reply slot, a thread -- and most of the shade is not one. A delivery, a
 * calendar reminder and a low-battery warning have an app, a line, and a time,
 * and that is all this needs to carry.
 */
data class CarNotice(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postedAtMillis: Long,
    /** True for something still happening -- a download, a timer, a nav route. */
    val ongoing: Boolean,
    /**
     * The notification's own small icon, already resolved to a drawable.
     *
     * Resolved in the listener rather than in the tile, because
     * `Icon.loadDrawable` on a cross-package icon needs the *posting* package's
     * resources and the listener is the one place that still has the
     * `StatusBarNotification` to ask. Null falls back to the app's launcher
     * icon, which is always available and is what a driver recognises anyway.
     */
    val icon: android.graphics.drawable.Drawable? = null,
    /** The colour the app asked its icon be tinted, or 0 for none. */
    val accent: Int = 0,
)

/**
 * One conversation notification, reduced to what a car screen can show.
 *
 * Deliberately not a `data class`: [reply] holds a `PendingIntent` and an array,
 * neither of which has useful value equality, and a generated `equals` over them
 * would quietly compare arrays by identity and make the result look meaningful.
 * Identity is what the feed actually compares anyway — [key] is the platform's
 * own notification key and is the stable identifier.
 */
class CarMessage(

    /** `StatusBarNotification.getKey()`; unique and stable across updates. */
    val key: String,
    val packageName: String,
    /**
     * Who sent it, best effort, falling back to the posting app's own label.
     *
     * Never the raw package name: "org.thoughtcrime.securesms" on a car screen
     * tells a driver nothing they can act on, and the label is one cheap
     * `PackageManager` lookup away.
     */
    val sender: String,
    val text: String,
    val postedAtMillis: Long,
    /** Present only when the posting app offered an inline reply. */
    val reply: ReplySlot?,
)

/**
 * The inline-reply action a messaging app attached to its notification.
 *
 * @param label the app's own word for it, usually "Reply".
 * @param pendingIntent fired to deliver the reply; belongs to the posting app and
 *   runs as that app, which is the whole point.
 * @param inputs every `RemoteInput` on the action. All of them are passed to
 *   `RemoteInput.addResultsToIntent`, because the receiving app looks the value
 *   up by its own result key and only it knows which key that is.
 * @param choices canned replies the app supplied, if any.
 */
class ReplySlot(
    val label: String,
    val pendingIntent: PendingIntent,
    val inputs: Array<RemoteInput>,
    val choices: List<CharSequence>,
)

/**
 * Recent conversations, with replies that go back through the app that sent them.
 *
 * ## Why replying works this way and no other
 *
 * Headway has no access to any messaging app's private data. It cannot read a
 * conversation out of Signal's database, it cannot call Signal's send API, and it
 * must not try. What it *can* do is use the reply the app itself published: a
 * `Notification.Action` carrying one or more `RemoteInput`s, which is the
 * contract an app opts into precisely so that a remote surface — Wear, Android
 * Auto, a car — can answer without being that app.
 *
 * The mechanism is exact and there is no substitute for any part of it.
 * `RemoteInput.addResultsToIntent` writes the typed text into an `Intent` under
 * the result key the app chose; `RemoteInput.setResultsSource` records whether it
 * was typed or picked from the app's own suggestions; then the action's
 * `PendingIntent` is sent, and the *app* — not Headway — does the sending, in its
 * own process, with its own credentials, over its own transport. Headway never
 * holds the conversation, the account, or the keys. Any other route to a reply
 * would need either the app's private storage or a privileged API, and both are
 * out of bounds under CLAUDE.md constraint 2.
 *
 * ## Where the data comes from
 *
 * [HeadwayNotificationListener], which is the only unprivileged way to observe
 * other apps' notifications and needs the same user grant
 * [NowPlayingTile.notificationAccessGranted] reports on. Until it is granted the
 * tile says so and shows nothing, which is correct rather than broken.
 *
 * ## Typing, honestly
 *
 * The text field is there, but the canned [ReplySlot.choices] are the path meant
 * for a moving car. Two reasons. The obvious one is that typing while driving is
 * the thing this project should not encourage. The other is mechanical: the
 * dashboard lives on a private virtual display, and the platform does not
 * guarantee an IME on such a display — the keyboard may well appear on the
 * phone's own screen instead of the car's, which makes the field useful when the
 * phone is in hand and useless when it is in a pocket. Apps that supply choices
 * give the driver a one-tap reply that needs no keyboard at all.
 *
 * ## Why the list freezes while a reply is open
 *
 * A new notification arriving mid-reply would otherwise reorder the rows under
 * the driver's thumb and take the half-typed reply with them. Incoming updates
 * are held and applied when the editor closes; one message stale for a few
 * seconds is a far better failure than a reply sent to the wrong conversation.
 */
/**
 * Everything the phone is showing, newest first.
 *
 * The Messages pane's superset, and the pane a driver asked for after finding
 * that Messages "is still only showing messages": same
 * `NotificationListenerService` stream, no conversation filter, so a delivery,
 * a calendar reminder or a low-battery warning arrives instead of being dropped
 * for not looking like a chat.
 *
 * Read-only on purpose. A notification's actions are arbitrary `PendingIntent`s
 * an app can put anything behind, and a row of unlabelled buttons at
 * seventy miles an hour is a worse idea than a list you glance at. Messages
 * keeps its inline reply because a reply slot is a specific, named thing.
 */
class NotificationsTile(context: Context) : DashTile {

    private val appContext: Context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    override val kind: String = PaneKind.NOTIFICATIONS

    private var list: LinearLayout? = null

    /**
     * Holds the empty state, which is a `View` rather than a `TextView`.
     *
     * `CarStyle.emptyState` returns the styled block, not the label inside it,
     * so the message is replaced by rebuilding rather than by assigning `.text`.
     */
    private var emptyHolder: FrameLayout? = null
    private var shown: List<CarNotice> = emptyList()
    private var running = false

    /**
     * Held in a field: removal is by identity, and the feed publishes from
     * whichever thread the platform used, so the hop to the main thread happens
     * here rather than in every caller.
     */
    private val feed: (List<CarNotice>) -> Unit = { notices ->
        handler.post {
            shown = notices
            render()
        }
    }

    override fun createView(context: Context): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        val holder = FrameLayout(context)
        val frame = FrameLayout(context).apply {
            addView(
                ScrollView(context).apply {
                    isFillViewport = true
                    addView(column, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                    layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                },
            )
            addView(holder, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
        }
        list = column
        emptyHolder = holder
        render()
        return frame
    }

    override fun start() {
        if (running) return
        running = true
        HeadwayNotificationListener.observeNotices(feed)
    }

    override fun stop() {
        if (!running) return
        running = false
        HeadwayNotificationListener.unobserveNotices(feed)
    }

    override fun describe(): String =
        "notifications: ${shown.size} showing" +
            if (HeadwayNotificationListener.isConnected) "" else " (no notification access)"

    private fun render() {
        val column = list ?: return
        val context = column.context
        column.removeAllViews()
        val holder = emptyHolder
        if (shown.isEmpty()) {
            holder?.removeAllViews()
            holder?.addView(
                CarStyle.emptyState(
                    context,
                    if (HeadwayNotificationListener.isConnected) {
                        "Nothing on the phone right now."
                    } else {
                        GRANT_HINT
                    },
                ),
            )
            holder?.visibility = View.VISIBLE
            return
        }
        holder?.visibility = View.GONE
        shown.forEach { notice -> column.addView(row(context, notice)) }
    }

    private fun row(context: Context, notice: CarNotice): View {
        val gap = CarStyle.gutter(context)
        val card = LinearLayout(context).apply {
            // Horizontal now: the icon beside the words rather than above them,
            // which is the shape every notification shade uses and the one a
            // driver reads without stopping to work out what they are seeing.
            orientation = LinearLayout.HORIZONTAL
            background = Headway.panel(CarStyle.radius(context), CarStyle.SURFACE)
            setPadding(gap, gap / 2, gap, gap / 2)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = gap / 3
            }
        }
        val size = CarStyle.dp(context, NOTICE_ICON_DP)
        card.addView(
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                // The notification's own icon, or the posting app's. A small
                // icon is a monochrome glyph the platform expects to be tinted,
                // so it is tinted with the colour the app asked for and left
                // alone otherwise -- an untinted white glyph is legible on this
                // screen and a black one would not be.
                val drawable = notice.icon ?: appIcon(appContext, notice.packageName)
                setImageDrawable(drawable)
                if (notice.icon != null && notice.accent != 0) {
                    runCatching { setColorFilter(notice.accent) }
                }
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = gap
                    gravity = Gravity.CENTER_VERTICAL
                }
            },
        )
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        column.addView(
            CarStyle.label(context, 13f, CarStyle.DIM).apply {
                // App and time on one line: which app it came from is how a
                // driver decides whether it matters before reading the words.
                text = listOf(notice.appLabel, timeOf(notice.postedAtMillis))
                    .filter { it.isNotBlank() }
                    .joinToString(" \u00b7 ")
            },
        )
        notice.title.takeIf { it.isNotBlank() }?.let { title ->
            column.addView(
                CarStyle.label(context, 17f, CarStyle.TEXT, bold = true).apply { text = title },
            )
        }
        notice.text.takeIf { it.isNotBlank() }?.let { body ->
            column.addView(CarStyle.label(context, 15f, CarStyle.TEXT).apply { text = body })
        }
        card.addView(column)
        return card
    }

    private fun timeOf(millis: Long): String = runCatching {
        if (millis <= 0L) "" else android.text.format.DateFormat
            .getTimeFormat(appContext)
            .format(java.util.Date(millis))
    }.getOrDefault("")

    private companion object {
        const val GRANT_HINT: String =
            "Headway needs notification access to show what the phone is showing.\n" +
                "Turn it on in Headway on the phone, under Now playing and messages."

        /**
         * How large a notification's icon is drawn.
         *
         * Below a touch target on purpose -- the row is not a button, so the
         * icon is identification rather than something to hit, and a 48 dp
         * square beside two lines of text would push the text into a column too
         * narrow to read on a panel this wide.
         */
        const val NOTICE_ICON_DP: Float = 32f
    }
}

class MessagesTile(context: Context) : DashTile {

    private val appContext: Context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    override val kind: String = PaneKind.MESSAGES

    private var rows: LinearLayout? = null

    private var running = false

    /** What is on screen now. */
    private var shown: List<CarMessage> = emptyList()

    /** An update that arrived while [replyingTo] was open; applied on close. */
    private var pending: List<CarMessage>? = null

    /** [CarMessage.key] of the row whose reply editor is expanded, if any. */
    private var replyingTo: String? = null

    /**
     * Held in a field so [stop] removes this exact instance.
     *
     * The listener may publish from whichever thread the platform used, so the
     * hop onto the main looper is not optional: everything below touches views.
     */
    private val observer: (List<CarMessage>) -> Unit = { messages ->
        handler.post { onFeed(messages) }
    }

    override fun createView(context: Context): View {
        val panel = CarStyle.panel(context)
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(
            ScrollView(context).apply {
                isFillViewport = true
                addView(list, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            },
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f),
        )
        rows = list
        renderRows()
        return panel
    }

    override fun start() {
        if (running) return
        running = true

        // A listener the user enabled before an app update can be left unbound
        // until something asks for it back. requestRebind is the documented way
        // to ask, it is a no-op when already connected, and without it the tile
        // sits empty on the first run after every update.
        if (NowPlayingTile.notificationAccessGranted(appContext) &&
            !HeadwayNotificationListener.isConnected
        ) {
            runCatching {
                NotificationListenerService.requestRebind(
                    HeadwayNotificationListener.componentOf(appContext),
                )
            }
        }

        HeadwayNotificationListener.observe(observer)
    }

    override fun stop() {
        if (!running) return
        running = false
        HeadwayNotificationListener.unobserve(observer)
        handler.removeCallbacksAndMessages(null)
        replyingTo = null
        pending = null
    }

    override fun describe(): String {
        val access = if (NowPlayingTile.notificationAccessGranted(appContext)) {
            if (HeadwayNotificationListener.isConnected) "listener connected" else "listener not bound"
        } else {
            "no notification access"
        }
        return "messages: ${shown.size} shown, $access"
    }

    // --- feed ----------------------------------------------------------------

    private fun onFeed(messages: List<CarMessage>) {
        if (replyingTo != null) {
            pending = messages
            return
        }
        shown = messages
        renderRows()
    }

    private fun collapseReply() {
        replyingTo = null
        pending?.let {
            shown = it
            pending = null
        }
        renderRows()
    }

    // --- rendering -----------------------------------------------------------

    private fun renderRows() {
        val list = rows ?: return
        val context = list.context
        list.removeAllViews()

        // Centred while empty, top-aligned once there are rows. The ScrollView
        // fills the viewport, so this is what puts the empty state in the
        // middle of the pane instead of in its top-left corner.
        val empty = when {
            !NowPlayingTile.notificationAccessGranted(context) -> NO_ACCESS_HINT
            !HeadwayNotificationListener.isConnected ->
                "Waiting for the notification listener to connect."
            shown.isEmpty() -> "No recent messages."
            else -> null
        }
        list.gravity = if (empty != null) Gravity.CENTER else Gravity.TOP
        if (empty != null) {
            list.addView(hint(context, empty))
            return
        }
        shown.forEach { list.addView(buildRow(context, it)) }
    }

    /**
     * The pane with nothing in it.
     *
     * Centred under the mark rather than a grey sentence pinned to the top-left
     * corner, which is what a pane with no messages used to look like and which
     * reads as a rendering failure rather than as an empty inbox.
     */
    private fun hint(context: Context, line: String): View =
        CarStyle.emptyState(context, line)

    private fun buildRow(context: Context, message: CarMessage): View {
        val gap = CarStyle.gutter(context)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // A rounded panel rather than a flat grey block: the rows are the
            // only repeated element on the dashboard, so square edges here are
            // what would make the whole surface look unfinished.
            background = Headway.panel(CarStyle.radius(context), CarStyle.SURFACE)
            setPadding(gap, gap, gap, gap)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = gap / 2
            }
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val iconSize = CarStyle.dp(context, 40f)
        header.addView(
            ImageView(context).apply {
                setImageDrawable(appIcon(context, message.packageName))
                contentDescription = message.packageName
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                    marginEnd = gap
                }
            },
        )
        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                CarStyle.label(context, 17f, CarStyle.TEXT, bold = true).apply {
                    text = message.sender
                },
            )
            addView(
                CarStyle.label(context, 15f, CarStyle.DIM).apply {
                    text = message.text
                    // Two lines: enough for a real sentence, few enough that the
                    // rows stay a predictable height as messages arrive.
                    maxLines = 2
                },
            )
        }
        header.addView(textColumn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        val slot = message.reply
        if (slot != null) {
            header.addView(
                CarStyle.button(context, slot.label) {
                    if (replyingTo == message.key) collapseReply() else expandReply(message.key)
                },
            )
        }
        row.addView(header)

        if (slot != null && replyingTo == message.key) {
            row.addView(buildReplyEditor(context, message, slot))
        }
        return row
    }

    private fun expandReply(key: String) {
        replyingTo = key
        renderRows()
    }

    private fun buildReplyEditor(context: Context, message: CarMessage, slot: ReplySlot): View {
        val gap = CarStyle.gutter(context)
        val editor = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = gap
            }
        }

        // The app's own suggestions first, and as full-size buttons: this is the
        // path that works with no keyboard and one glance.
        if (slot.choices.isNotEmpty()) {
            val choiceRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            slot.choices.take(MAX_CHOICES).forEach { choice ->
                choiceRow.addView(
                    CarStyle.button(context, choice.toString()) {
                        send(message, slot, choice, RemoteInput.SOURCE_CHOICE)
                    },
                )
            }
            editor.addView(choiceRow)
        }

        // Only offered when the app accepts free text. An app whose RemoteInputs
        // are all choice-only would silently drop anything typed here.
        if (slot.inputs.any { it.allowFreeFormInput }) {
            val field = EditText(context).apply {
                setHint(slot.inputs.firstOrNull { it.allowFreeFormInput }?.label ?: "Reply")
                setHintTextColor(CarStyle.DIM)
                setTextColor(CarStyle.TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, CarStyle.sp(context, 16f))
                minHeight = CarStyle.dp(context, CarStyle.PRIMARY_TARGET_DP)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
            val sendRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    topMargin = gap / 2
                }
            }
            sendRow.addView(field, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            sendRow.addView(
                CarStyle.button(context, "Send") {
                    val typed = field.text?.toString().orEmpty().trim()
                    if (typed.isNotEmpty()) {
                        send(message, slot, typed, RemoteInput.SOURCE_FREE_FORM_INPUT)
                    }
                },
            )
            editor.addView(sendRow)
        }

        editor.addView(
            CarStyle.button(context, "Cancel") { collapseReply() },
        )
        return editor
    }

    /**
     * Hands the reply to the app that asked for it.
     *
     * @param source distinguishes a tapped suggestion from typed text. Apps use
     *   it for their own suggestion quality metrics and some ignore it, but
     *   reporting a tapped choice as free-form input would be a small lie told
     *   to every messaging app on the phone.
     */
    private fun send(message: CarMessage, slot: ReplySlot, text: CharSequence, source: Int) {
        val intent = Intent()
        val results = Bundle()
        // Written under every free-form key rather than only the first: an action
        // may legitimately carry several inputs and the app reads only the key it
        // minted, so guessing which one is "the" reply key is a way to send
        // nothing. When there is no free-form input the value still travels under
        // the choice-only input's key — it is simply expected to be one of the
        // strings that input offered, which by construction it is.
        val freeForm = slot.inputs.filter { it.allowFreeFormInput }
        val targets = if (freeForm.isNotEmpty()) freeForm else slot.inputs.toList()
        targets.forEach { results.putCharSequence(it.resultKey, text) }

        // The full array, not just the targets: addResultsToIntent uses it to
        // build the clip data the receiving app unpacks, and handing it a subset
        // describes an action that does not exist.
        RemoteInput.addResultsToIntent(slot.inputs, intent, results)
        RemoteInput.setResultsSource(intent, source)

        val sent = runCatching { slot.pendingIntent.send(appContext, 0, intent) }
        if (sent.isFailure) {
            // The usual cause is a cancelled PendingIntent, which means the app
            // withdrew the notification between the tile drawing it and the
            // driver tapping. Nothing to retry.
            SessionLog.shared.warn(
                TAG,
                "reply to ${message.packageName} not delivered: ${sent.exceptionOrNull()}",
            )
        } else {
            SessionLog.shared.info(TAG, "inline reply sent to ${message.packageName}")
        }
        collapseReply()
    }

    private companion object {
        // Names the toggle. The old wording sent the driver to a phone screen
        // that had no such control on it; see NowPlayingTile.NO_ACCESS_HINT.
        private const val NO_ACCESS_HINT =
            "Turn on \"Now playing and messages\" in Headway on the phone " +
                "to read and reply from here."

        /**
         * Apps offer up to about five suggestions; three is what fits beside a
         * 40 dp icon on an 800-pixel panel without the buttons shrinking below
         * the touch floor.
         */
        private const val MAX_CHOICES = 3
    }
}

// ---------------------------------------------------------------------------
// The notification listener behind MessagesTile
// ---------------------------------------------------------------------------

/**
 * The only unprivileged way to see other apps' notifications.
 *
 * ## Why the state is static
 *
 * The system owns this service's lifecycle completely: it binds it when the user
 * grants access, unbinds it when they revoke it, and rebinds it on its own
 * schedule after an update or a crash. Nothing in Headway may create it, and a
 * tile cannot hold a reference to one that may not exist yet. So the service
 * publishes into companion state and tiles observe that — the same shape, and for
 * the same reason, as `HeadwayService.linkState`.
 *
 * ## What is kept
 *
 * A bounded list of the most recent conversation notifications, and nothing else.
 * No message is written to disk, none leaves the device, and [latest] is cleared
 * when the listener disconnects. The filter in [conversationOf] is deliberately
 * narrow: a notification qualifies only if the app called it a conversation
 * (`CATEGORY_MESSAGE`), styled it as one (`MessagingStyle`), or attached an
 * inline reply. Everything else — download progress, sync status, the media
 * notification [NowPlayingTile] already draws — is dropped rather than shown to a
 * driver as if it were a text message.
 *
 * ## Manifest
 *
 * This class does nothing unless it is declared with
 * `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE` and the
 * `android.service.notification.NotificationListenerService` intent filter. The
 * permission is what stops anything but the system binding it.
 */
class HeadwayNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        SessionLog.shared.info(TAG, "notification listener connected")
        publish()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        connected = false
        // Cleared rather than left stale: a revoked grant must not leave the last
        // conversation someone had sitting on a car screen indefinitely. Same for
        // the turn instruction and the call, which are read from the same feed.
        deliver(emptyList())
        // Same scoping as the sweep: a revoked notification grant must not
        // erase a route that never came from a notification.
        NavigationFeed.clearScraped()
        CarPhone.clear()
        SessionLog.shared.info(TAG, "notification listener disconnected")
    }

    /**
     * Nullable because the platform's parameter is, and a car is a poor place to
     * discover that a null slipped through a non-null Kotlin signature.
     */
    override fun onNotificationPosted(sbn: StatusBarNotification?) = publish()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = publish()

    /**
     * Rebuilds the whole list from `getActiveNotifications` rather than patching
     * it from the posted or removed notification.
     *
     * Patching would need this to track group summaries, updates that reuse a
     * key, and removals it never saw because the listener was unbound at the
     * time. The active set is the truth, it is a single cheap binder call, and it
     * is only made when something actually changed.
     */
    private fun publish() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return

        // The navigation and phone models are rebuilt from the same sweep, and
        // for the same reason: the active set is the truth. A route that ended
        // or a call that was hung up while the listener happened to be unbound
        // produces no removal callback at all, so anything driven purely off
        // onNotificationRemoved would leave a stale turn arrow — or a stale
        // Answer button — on the car screen indefinitely.
        val mapPackages = runCatching {
            HeadwaySettings.of(this).getString(HeadwaySettings.KEY_MAP_APP, null)
        }.getOrNull()?.let { setOf(it) }.orEmpty()
        var navigating = false
        var calling = false
        for (sbn in active) {
            if (sbn == null) continue
            if (!navigating && NavigationFeed.offer(sbn, mapPackages)) navigating = true
            if (!calling && CarPhone.offer(this, sbn)) calling = true
        }
        // clearScraped, not clear: a structured Trip from a car app is
        // invisible to this sweep and NavigationFeed.offer even declines the
        // notification while one is held, so `navigating` is structurally
        // false and an unconditional clear wiped a live route on every
        // notification the phone received.
        if (!navigating) NavigationFeed.clearScraped()
        if (!calling) CarPhone.clear()

        val messages = active
            .mapNotNull { conversationOf(this, it) }
            .sortedByDescending { it.postedAtMillis }
            .take(MAX_MESSAGES)
        deliver(messages)

        // The same sweep, unfiltered, for the Notifications pane. Built here
        // rather than in a second listener because the platform binds one
        // component per app and this one already has the active set in hand.
        deliverNotices(
            active.mapNotNull { noticeOf(this, it) }
                .sortedByDescending { it.postedAtMillis }
                .take(MAX_NOTICES),
        )
    }

    companion object {

        /**
         * How many conversations the feed keeps.
         *
         * The tile shows as many as its pane has room for and scrolls the rest.
         * Eight is roughly two screens on a 480-pixel-tall panel, which is as far
         * back as anyone scrolls at a traffic light.
         */
        const val MAX_MESSAGES: Int = 8

        @Volatile
        private var connected: Boolean = false

        /** Whether the system currently has this service bound. */
        val isConnected: Boolean get() = connected

        @Volatile
        private var current: List<CarMessage> = emptyList()

        /** The most recent feed, for a tile that has not observed yet. */
        val latest: List<CarMessage> get() = current

        /**
         * Copy-on-write because the service publishes from the platform's thread
         * while tiles register and unregister from the main one, and the write is
         * rare while the read happens on every notification.
         */
        private val observers = CopyOnWriteArrayList<(List<CarMessage>) -> Unit>()

        // --- the unfiltered feed ------------------------------------------

        /**
         * How many notifications the feed keeps.
         *
         * More than [MAX_MESSAGES] because this is the whole shade rather than
         * the conversations in it, and a driver looking at it is looking for
         * one thing among many. Still bounded: a pane is not a scrollback.
         */
        const val MAX_NOTICES: Int = 24

        @Volatile
        private var currentNotices: List<CarNotice> = emptyList()

        /** The most recent unfiltered feed, for a tile that has not observed yet. */
        val latestNotices: List<CarNotice> get() = currentNotices

        private val noticeObservers = CopyOnWriteArrayList<(List<CarNotice>) -> Unit>()

        fun observeNotices(observer: (List<CarNotice>) -> Unit) {
            noticeObservers.addIfAbsent(observer)
            observer(currentNotices)
        }

        fun unobserveNotices(observer: (List<CarNotice>) -> Unit) {
            noticeObservers.remove(observer)
        }

        private fun deliverNotices(notices: List<CarNotice>) {
            currentNotices = notices
            noticeObservers.forEach { observer ->
                runCatching { observer(notices) }
                    .onFailure { SessionLog.shared.warn(TAG, "notice observer failed: $it") }
            }
        }

        /**
         * Turns any notification into a [CarNotice], or null when it is noise.
         *
         * Far more permissive than [conversationOf], which is the point -- this
         * pane exists because a driver wanted "all notifications on the phone",
         * not the subset that looks like a chat. Only three things are dropped:
         * a group summary, because it repeats its children; Headway's own,
         * because a pane reporting the service drawing it is a mirror facing a
         * mirror; and one with neither a title nor any text, which would draw as
         * an empty row.
         */
        private fun noticeOf(context: Context, sbn: StatusBarNotification): CarNotice? {
            val notification = sbn.notification ?: return null
            if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return null
            if (sbn.packageName == context.packageName) return null
            val extras = notification.extras
            val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
            val text = (
                extras?.getCharSequence(Notification.EXTRA_TEXT)
                    ?: extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ?: extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)
                )?.toString()?.trim()
            if (title.isNullOrBlank() && text.isNullOrBlank()) return null
            return CarNotice(
                key = sbn.key.orEmpty(),
                packageName = sbn.packageName.orEmpty(),
                appLabel = appLabelOf(context, sbn.packageName.orEmpty()),
                title = title.orEmpty(),
                text = text.orEmpty(),
                postedAtMillis = sbn.postTime,
                ongoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0,
                icon = iconOf(context, notification),
                // Only when the app said it means it. `color` is 0 unless set,
                // and tinting a monochrome small icon with 0 paints it black on
                // a black car screen.
                accent = if (notification.color != 0) notification.color else 0,
            )
        }

        /**
         * The small icon a notification posted, as something a car pane can draw.
         *
         * `Notification.getSmallIcon()` is an `Icon`, not a drawable, and
         * loading it is a cross-package resource lookup that can fail for an app
         * that has since been updated or uninstalled -- so every failure is a
         * null and the pane falls back to the launcher icon.
         *
         * The large icon is preferred where there is one: for a message it is
         * the sender's photo, which is more use at a glance than the app's
         * glyph, and it is already the right shape.
         */
        private fun iconOf(
            context: Context,
            notification: Notification,
        ): android.graphics.drawable.Drawable? = runCatching {
            (notification.getLargeIcon() ?: notification.smallIcon)?.loadDrawable(context)
        }.getOrNull()

        /**
         * Registers [observer] and immediately hands it the current feed.
         *
         * The immediate call matters: a tile started long after the listener
         * connected would otherwise show nothing until the next notification,
         * which on a quiet drive is never.
         */
        fun observe(observer: (List<CarMessage>) -> Unit) {
            observers.addIfAbsent(observer)
            observer(current)
        }

        /** Removal is by identity, so callers must pass the same lambda instance. */
        fun unobserve(observer: (List<CarMessage>) -> Unit) {
            observers.remove(observer)
        }

        /** The name the user's grant is recorded against. */
        fun componentOf(context: Context): ComponentName =
            ComponentName(context.applicationContext, HeadwayNotificationListener::class.java)

        private fun deliver(messages: List<CarMessage>) {
            current = messages
            observers.forEach { observer ->
                // One tile throwing must not stop the others being updated.
                runCatching { observer(messages) }
                    .onFailure { SessionLog.shared.warn(TAG, "message observer failed: $it") }
            }
        }

        /**
         * Turns a notification into a [CarMessage], or null when it is not a
         * conversation.
         *
         * Sender and text come from `MessagingStyle` when the app used it, which
         * is the only source that names the *person* rather than the thread: a
         * group chat's `EXTRA_TITLE` is the group's name, and showing that as the
         * sender tells a driver nothing about who just spoke. The extras are the
         * fallback for apps that never adopted the style.
         *
         * `NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification`
         * rather than the framework class: `android.app.Notification.MessagingStyle`
         * has no public extraction method — confirmed absent from the android-35
         * `android.jar` — and reading `EXTRA_MESSAGES` by hand means depending on
         * the `@hide` bundle key names inside each message.
         */
        private fun conversationOf(context: Context, sbn: StatusBarNotification): CarMessage? {
            val notification = sbn.notification ?: return null

            // Group summaries duplicate their children; ongoing notifications are
            // status, not conversation, and that is what a media notification is.
            // Parenthesised because `and` is an infix function whose precedence
            // relative to `!=` is a thing readers have to look up.
            if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return null
            if ((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return null

            // A call belongs to the phone pane, which draws it far larger and
            // with working Answer and Hang up. Most dialers mark the call
            // notification ongoing and it would already have been dropped above,
            // but a missed-call notification is not ongoing and would otherwise
            // arrive here looking like an unread text.
            if (notification.category == Notification.CATEGORY_CALL) return null

            val style = runCatching {
                NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
            }.getOrNull()
            val slot = replySlotOf(notification)

            val conversational = style != null ||
                slot != null ||
                notification.category == Notification.CATEGORY_MESSAGE
            if (!conversational) return null

            val extras = notification.extras
            val last = style?.messages?.lastOrNull()
            val sender = last?.person?.name?.toString()?.takeIf { it.isNotBlank() }
                ?: style?.conversationTitle?.toString()?.takeIf { it.isNotBlank() }
                ?: extras?.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
                    ?.takeIf { it.isNotBlank() }
                ?: extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                    ?.takeIf { it.isNotBlank() }
                ?: appLabelOf(context, sbn.packageName)

            val text = last?.text?.toString()?.takeIf { it.isNotBlank() }
                ?: extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                    ?.takeIf { it.isNotBlank() }
                ?: extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                    ?.takeIf { it.isNotBlank() }
                ?: return null

            return CarMessage(
                key = sbn.key,
                packageName = sbn.packageName,
                sender = sender,
                // Collapsed to one logical line: a multi-line body would make the
                // row's height depend on the message, and the rows must not move.
                // `\s+` rather than `\R`, which java.util.regex accepts but which
                // Android's ICU-backed engine has not always supported.
                text = text.replace(WHITESPACE_RUN, " ").trim(),
                postedAtMillis = sbn.postTime,
                reply = slot,
            )
        }

        /**
         * The first action carrying a `RemoteInput`, which is the convention every
         * messaging app follows for its reply action.
         *
         * `isAuthenticationRequired` actions are skipped: the app has said the
         * device must be unlocked before this action runs, and a reply button on a
         * car screen that silently does nothing on a locked phone is worse than no
         * button. Actions with only data-only inputs are skipped too — those take
         * an image or a file, not a sentence.
         */
        private fun replySlotOf(notification: Notification): ReplySlot? {
            val actions = notification.actions ?: return null
            for (action in actions) {
                val inputs = action.remoteInputs ?: continue
                if (inputs.isEmpty()) continue
                if (inputs.all { it.isDataOnly }) continue
                if (action.isAuthenticationRequired) continue
                val intent = action.actionIntent ?: continue
                val choices = inputs
                    .flatMap { it.choices?.toList().orEmpty() }
                    .filter { it.isNotBlank() }
                return ReplySlot(
                    label = action.title?.toString()?.takeIf { it.isNotBlank() } ?: "Reply",
                    pendingIntent = intent,
                    inputs = inputs,
                    choices = choices,
                )
            }
            return null
        }

        /**
         * The posting app's display name, or its package name if it has none.
         *
         * Resolved here rather than in the tile so the tile has a printable
         * sender in every branch. The lookup needs no permission beyond the
         * `QUERY_ALL_PACKAGES` the manifest already declares.
         *
         * Shared by both feeds. The notice feed briefly had its own copy with
         * the same signature in the same companion, which is a compile error
         * and not a subtle one -- there is nothing here worth two of.
         */
        private fun appLabelOf(context: Context, packageName: String): String = runCatching {
            val packages = context.packageManager
            packages.getApplicationLabel(
                packages.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0L),
                ),
            ).toString()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: packageName

        private val WHITESPACE_RUN = Regex("\\s+")
    }
}

// ---------------------------------------------------------------------------
// Clock
// ---------------------------------------------------------------------------

/**
 * Time, date, and whether the car link is up.
 *
 * The link line is not decoration. Everything else on the dashboard keeps
 * drawing whether or not the AAP session is healthy — the tiles read the phone's
 * own state, not the car's — so without this the difference between "the car is
 * connected" and "the car is showing the last frame it received" is invisible.
 *
 * State is read from `HeadwayService.linkState` on each tick rather than
 * collected: this tile already has a one-second timer for the clock, a second
 * mechanism would need a coroutine scope to cancel in [stop], and a link change
 * that takes up to a second to appear is a change nobody was watching for.
 */
class ClockTile(context: Context) : DashTile {

    private val appContext: Context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    override val kind: String = PaneKind.CLOCK

    private var clockText: TextView? = null
    private var dateText: TextView? = null
    private var statusText: TextView? = null
    private var linkMark: HeadwayMark? = null

    private var running = false

    /**
     * Re-posts on the next whole second rather than every 1000 ms.
     *
     * A fixed interval drifts, and a clock
     * that changes a third of a second after the minute does looks broken to
     * anyone glancing between it and the car's own clock.
     */
    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1_000L - (System.currentTimeMillis() % 1_000L))
        }
    }

    /**
     * Time, big; date, small; the link, with the mark beside it.
     *
     * The mark travels while the link is coming back up, which is the one thing
     * on the dashboard that can change without the driver having touched
     * anything. A pane that says "Reconnecting in 4s" in static grey and a pane
     * that says it under a moving mark are read at different speeds, and this
     * one is read at a glance from a moving car.
     */
    override fun createView(context: Context): View {
        val panel = CarStyle.panel(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val clock = CarStyle.label(context, 46f, CarStyle.TEXT, bold = true).apply {
            includeFontPadding = false
            letterSpacing = -0.02f
        }
        val date = CarStyle.label(context, 15f, CarStyle.DIM)

        val markSize = CarStyle.dp(context, 22f)
        val markView = HeadwayMark(context).apply {
            layoutParams = LinearLayout.LayoutParams(markSize * 2, markSize).apply {
                marginEnd = CarStyle.gutter(context) / 2
            }
        }
        val status = CarStyle.label(context, 15f, CarStyle.DIM)
        val statusRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = CarStyle.gutter(context) / 2
            }
            addView(markView)
            addView(status)
        }

        panel.addView(clock)
        panel.addView(date)
        panel.addView(statusRow)

        clockText = clock
        dateText = date
        statusText = status
        markView.also { linkMark = it }

        render()
        return panel
    }

    override fun start() {
        if (running) return
        running = true
        render()
        handler.post(tick)
    }

    override fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(tick)
    }

    override fun describe(): String {
        val now = DateFormat.getTimeFormat(appContext).format(Date())
        return "clock: $now, link ${statusOf(HeadwayService.linkState.value)}"
    }

    private fun render() {
        val now = Date()
        // The user's own 12/24-hour choice and locale, from the system settings
        // rather than a hardcoded pattern.
        clockText?.text = DateFormat.getTimeFormat(appContext).format(now)
        dateText?.text = DateFormat.getMediumDateFormat(appContext).format(now)

        val state = HeadwayService.linkState.value
        statusText?.let {
            it.text = statusOf(state)
            it.setTextColor(
                when (state) {
                    is LinkState.Connected -> CarStyle.GOOD
                    is LinkState.GaveUp -> CarStyle.BAD
                    else -> CarStyle.DIM
                },
            )
        }
        // Setting this every tick is free — the property returns early when the
        // value has not changed, which it has not for all but one tick in a
        // session.
        linkMark?.travelling =
            state is LinkState.Connecting || state is LinkState.WaitingToRetry
    }

    private fun statusOf(state: LinkState): String = when (state) {
        is LinkState.Idle -> "Not connected"
        is LinkState.Connecting -> "Connecting"
        is LinkState.Connected -> "Connected"
        is LinkState.WaitingToRetry -> "Reconnecting in ${state.delayMillis / 1000}s"
        is LinkState.GaveUp -> "Disconnected"
    }
}

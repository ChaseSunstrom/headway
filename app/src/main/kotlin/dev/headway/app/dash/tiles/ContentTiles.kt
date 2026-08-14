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
import android.graphics.Typeface
import android.graphics.drawable.Drawable
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
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import dev.headway.app.dash.DashTile
import dev.headway.app.log.SessionLog
import dev.headway.app.service.HeadwayService
import dev.headway.transport.LinkState
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG = "HeadwayDashTiles"

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
 * The palette is `CarLauncherActivity`'s, deliberately: the launcher and the
 * dashboard are seen in the same glance and must not look like two products.
 * [SURFACE] is the one addition — messages need visible row boundaries, and it is
 * dark enough (contrast against [TEXT] is roughly nineteen to one) that it does
 * not break the "no mid-greys" rule the launcher's KDoc sets out.
 */
private object CarStyle {

    val BACKGROUND: Int = 0xFF000000.toInt()
    val SURFACE: Int = 0xFF141414.toInt()
    val TEXT: Int = 0xFFFFFFFF.toInt()
    val DIM: Int = 0xFFB0BEC5.toInt()
    val GOOD: Int = 0xFF7BE38B.toInt()
    val BAD: Int = 0xFFFF7A7A.toInt()

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

    fun panel(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(BACKGROUND)
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

    // The parameter is `caption` and not `text` because inside `apply` an
    // unqualified `text` finds the Button's own property, so a parameter of that
    // name would read as though it were assigning to itself.
    fun button(
        context: Context,
        caption: String,
        onClick: () -> Unit,
    ): Button = Button(context).apply {
        text = caption
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(context, 16f))
        minHeight = dp(context, PRIMARY_TARGET_DP)
        minWidth = dp(context, PRIMARY_TARGET_DP * 1.5f)
        // The caption is also the accessibility name; TalkBack on the phone reads
        // the dashboard when a user is setting it up there.
        contentDescription = caption
        setOnClickListener { onClick() }
    }
}

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
class NowPlayingTile(context: Context) : DashTile {

    private val appContext: Context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    override val kind: String = DashTile.Kind.NOW_PLAYING

    private var art: ImageView? = null
    private var titleText: TextView? = null
    private var artistText: TextView? = null
    private var hintText: TextView? = null
    private var transport: LinearLayout? = null
    private var playPause: Button? = null

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

    override fun createView(context: Context): View {
        val panel = CarStyle.panel(context)
        val gap = CarStyle.gutter(context)

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val artSize = CarStyle.dp(context, 88f)
        val artView = ImageView(context).apply {
            // FIT_CENTER rather than CENTER_CROP: album art is square by
            // convention but not by rule, and cropping a podcast's wide cover
            // art removes the part with the wordmark on it.
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(artSize, artSize).apply {
                marginEnd = gap
            }
        }
        val titleView = CarStyle.label(context, 20f, CarStyle.TEXT, bold = true)
        val artistView = CarStyle.label(context, 16f, CarStyle.DIM)
        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleView)
            addView(artistView)
        }
        header.addView(artView)
        header.addView(textColumn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        val hintView = CarStyle.label(context, 16f, CarStyle.DIM).apply {
            // The one label allowed to wrap: it carries sentences, not names,
            // and it is never on screen at the same time as the rows whose
            // height it would disturb.
            maxLines = 3
            ellipsize = null
        }

        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = gap
            }
        }
        controls.addView(CarStyle.button(context, "Previous") { command { it.skipToPrevious() } })
        val playPauseView = CarStyle.button(context, "Play") { togglePlayback() }
        controls.addView(playPauseView)
        controls.addView(CarStyle.button(context, "Next") { command { it.skipToNext() } })

        panel.addView(header)
        panel.addView(hintView)
        panel.addView(controls)

        art = artView
        titleText = titleView
        artistText = artistView
        hintText = hintView
        transport = controls
        playPause = playPauseView

        render()
        return panel
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
            render()
            return
        }
        sessionsListener = listener
        refresh()
    }

    override fun stop() {
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
        }.getOrNull().orEmpty()
        bind(sessions.firstOrNull { isActive(it.playbackState?.state) } ?: sessions.firstOrNull())
        render()
    }

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
        val hintView = hintText ?: return
        val controls = transport ?: return

        if (!notificationAccessGranted(appContext)) {
            artView.visibility = View.GONE
            titleView.visibility = View.GONE
            artistView.visibility = View.GONE
            controls.visibility = View.GONE
            hintView.visibility = View.VISIBLE
            hintView.text = NO_ACCESS_HINT
            return
        }

        val current = controller
        if (current == null) {
            artView.visibility = View.GONE
            titleView.visibility = View.GONE
            artistView.visibility = View.GONE
            controls.visibility = View.GONE
            hintView.visibility = View.VISIBLE
            hintView.text = "Nothing is playing."
            return
        }

        hintView.visibility = View.GONE
        titleView.visibility = View.VISIBLE
        artistView.visibility = View.VISIBLE
        controls.visibility = View.VISIBLE

        val metadata = current.metadata
        titleView.text = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: "Playing"
        artistView.text = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: current.packageName

        // Three keys because apps disagree about which one is the cover: music
        // players fill ALBUM_ART, podcast and radio apps often fill only
        // DISPLAY_ICON, and ART is the generic fallback the framework defines.
        val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (bitmap == null) {
            artView.setImageDrawable(null)
            artView.visibility = View.GONE
        } else {
            artView.setImageBitmap(bitmap)
            artView.visibility = View.VISIBLE
        }

        val playing = isActive(current.playbackState?.state)
        playPause?.let {
            it.text = if (playing) "Pause" else "Play"
            it.contentDescription = it.text
        }
    }

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

        private const val NO_ACCESS_HINT =
            "Grant notification access to show what is playing. " +
                "Open Headway on the phone and turn it on there."

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
class MessagesTile(context: Context) : DashTile {

    private val appContext: Context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    override val kind: String = DashTile.Kind.MESSAGES

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

        if (!NowPlayingTile.notificationAccessGranted(context)) {
            list.addView(hint(context, NO_ACCESS_HINT))
            return
        }
        if (!HeadwayNotificationListener.isConnected) {
            list.addView(hint(context, "Waiting for the notification listener to connect."))
            return
        }
        if (shown.isEmpty()) {
            list.addView(hint(context, "No recent messages."))
            return
        }
        shown.forEach { list.addView(buildRow(context, it)) }
    }

    // `line` and not `text`, for the reason CarStyle.button's parameter is
    // `label`: inside `apply` the receiver's own `text` is what an unqualified
    // name would find.
    private fun hint(context: Context, line: String): View =
        CarStyle.label(context, 16f, CarStyle.DIM).apply {
            text = line
            maxLines = 3
            ellipsize = null
        }

    private fun buildRow(context: Context, message: CarMessage): View {
        val gap = CarStyle.gutter(context)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(CarStyle.SURFACE)
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

    /** Null for an app uninstalled between the notification and the draw. */
    private fun appIcon(context: Context, packageName: String): Drawable? = runCatching {
        context.packageManager.getApplicationIcon(packageName)
    }.getOrNull()

    private companion object {
        private const val NO_ACCESS_HINT =
            "Grant notification access to show messages. " +
                "Open Headway on the phone and turn it on there."

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
        // conversation someone had sitting on a car screen indefinitely.
        deliver(emptyList())
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
        val messages = active
            .mapNotNull { conversationOf(this, it) }
            .sortedByDescending { it.postedAtMillis }
            .take(MAX_MESSAGES)
        deliver(messages)
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
         * `QUERY_ALL_PACKAGES` the manifest already declares, and it only runs
         * for notifications that survived [conversationOf]'s filter.
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

    override val kind: String = DashTile.Kind.CLOCK

    private var clockText: TextView? = null
    private var dateText: TextView? = null
    private var statusText: TextView? = null

    private var running = false

    /**
     * Re-posts on the next whole second rather than every 1000 ms.
     *
     * Same trick as `CarLauncherActivity`: a fixed interval drifts, and a clock
     * that changes a third of a second after the minute does looks broken to
     * anyone glancing between it and the car's own clock.
     */
    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1_000L - (System.currentTimeMillis() % 1_000L))
        }
    }

    override fun createView(context: Context): View {
        val panel = CarStyle.panel(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val clock = CarStyle.label(context, 44f, CarStyle.TEXT).apply {
            includeFontPadding = false
        }
        val date = CarStyle.label(context, 15f, CarStyle.DIM)
        val status = CarStyle.label(context, 15f, CarStyle.DIM)
        panel.addView(clock)
        panel.addView(date)
        panel.addView(status)

        clockText = clock
        dateText = date
        statusText = status

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
    }

    private fun statusOf(state: LinkState): String = when (state) {
        is LinkState.Idle -> "Not connected"
        is LinkState.Connecting -> "Connecting"
        is LinkState.Connected -> "Connected"
        is LinkState.WaitingToRetry -> "Reconnecting in ${state.delayMillis / 1000}s"
        is LinkState.GaveUp -> "Disconnected"
    }
}

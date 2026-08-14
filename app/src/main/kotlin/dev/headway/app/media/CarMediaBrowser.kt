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

package dev.headway.app.media

import android.content.Context
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * One app's library, browsed a level at a time.
 *
 * ## The protocol, exactly
 *
 * 1. `MediaBrowser(context, service, ConnectionCallback, rootHints)` then
 *    `connect()`. The app's `onGetRoot` decides whether to serve us.
 * 2. On `onConnected`, `getRoot()` names the top node. `subscribe(id, cb)`
 *    delivers its children as `MediaBrowser.MediaItem`s.
 * 3. An item with `FLAG_BROWSABLE` is another node: subscribe to its id.
 *    An item with `FLAG_PLAYABLE` is a track: `getSessionToken()` gives a
 *    `MediaController` and `transportControls.playFromMediaId(id, null)` starts
 *    it. An item may be both, which the caller resolves.
 *
 * ## Subscriptions are the part that leaks
 *
 * `subscribe` registers a callback that stays live until `unsubscribe`, and the
 * *service* holds it. A driver walking six levels into a podcast app and then
 * switching layouts would otherwise leave six live subscriptions in somebody
 * else's process, each of which can call back into a view tree that no longer
 * exists. So this tracks every subscribed id and [close] unsubscribes all of
 * them before disconnecting.
 *
 * ## Root hints
 *
 * Passed empty, deliberately. The documented hints
 * (`android.service.media.extra.RECENT`, `EXTRA_OFFLINE`, `EXTRA_SUGGESTED`)
 * each ask an app for a *different* tree — the recents tree in particular is a
 * short flat list meant for a resume-playback button, not a library — and
 * asking for one of those here would silently give the driver the wrong thing
 * for apps that honour it. The plain root is what the browse UI wants.
 *
 * ## Threading
 *
 * `MediaBrowser` is main-thread-only and calls back on the main thread. The
 * dashboard is drawn from the main thread too, so nothing here hops threads;
 * what it does do is guard every callback against arriving after [close],
 * because a disconnect does not retract callbacks already in flight.
 */
class CarMediaBrowser(
    private val context: Context,
    val app: MediaApp,
    private val onStep: (String) -> Unit = {},
) {

    /** What the caller is shown after any state change. */
    fun interface Listener {
        fun onBrowseChanged(state: BrowseState, items: List<MediaBrowser.MediaItem>)
    }

    private val main = Handler(Looper.getMainLooper())

    private var browser: MediaBrowser? = null
    private var listener: Listener? = null
    private var closed = false

    /** Ids currently subscribed, so [close] can unsubscribe exactly them. */
    private val subscribed = mutableSetOf<String>()

    /** The node whose children are on screen; null before the root arrives. */
    var currentId: String? = null
        private set

    /**
     * The path from the root to [currentId], for the breadcrumb and for Back.
     *
     * Holds ids, not items: an item is a snapshot of a title that the app may
     * republish, and the id is the only part the protocol promises is stable.
     * Titles for the breadcrumb are carried alongside in [trailTitles].
     */
    private val trail = mutableListOf<String>()
    private val trailTitles = mutableListOf<String>()

    var state: BrowseState = BrowseState.UNKNOWN
        private set

    private var items: List<MediaBrowser.MediaItem> = emptyList()

    /**
     * Bumped on every [close], so a callback in flight cannot reach a listener
     * that has moved on.
     *
     * `MediaBrowseTile` shares one listener across sessions and swaps
     * `session` synchronously. Without this, a `REFUSED` posted by the browser
     * the driver just left arrives after the swap and paints *the new app's*
     * name onto the old app's refusal.
     */
    private var generation: Int = 0

    /** The pending connect or load watchdog, so it can be disarmed. */
    private var watchdog: Runnable? = null

    /** Set when the root looked like an allowlist stub. See [looksLikeAnEmptyStub]. */
    private var refusedPolitely: Boolean = false

    /** Where the driver is, for the header. Empty at the root. */
    fun breadcrumb(): String = trailTitles.joinToString(" › ")

    /** Whether [back] would do anything. */
    fun canGoBack(): Boolean = trail.size > 1

    private val connectionCallback = object : MediaBrowser.ConnectionCallback() {
        override fun onConnected() {
            if (closed) return
            disarm()
            val active = browser ?: return
            // No runCatching: inside onConnected the connection is live, so
            // getRoot() can neither throw nor return null. It can return "",
            // which is a real answer from a service with nothing to offer.
            val root = active.root
            if (root.isEmpty()) {
                publish(BrowseState.EMPTY, emptyList())
                return
            }
            // An allowlist rejection that took the polite route rather than
            // returning null: connected, but the root is a stub with no
            // children. The extras are the tell -- an app that means to serve
            // its library advertises how it wants that library drawn. This is
            // the most common refusal shape and it used to read as "Nothing
            // here", which sounds like an empty music collection.
            refusedPolitely = looksLikeAnEmptyStub(active)
            onStep(
                "media: connected to ${app.label}, root '$root'" +
                    if (refusedPolitely) " (looks like an allowlist stub)" else ""
            )
            trail.clear()
            trailTitles.clear()
            // Cleared so open() cannot early-return on `id == currentId` after a
            // reconnect, which would leave the trail empty and nothing
            // subscribed -- the driver stranded with a blank breadcrumb.
            currentId = null
            open(root, app.label)
        }

        override fun onConnectionSuspended() {
            if (closed) return
            // The service's process went away. BIND_AUTO_CREATE brings it back
            // and onConnected fires again; the subscriptions the framework
            // re-registers on our behalf are for ids this object may no longer
            // be showing, which is why onConnected rebuilds the trail.
            onStep("media: ${app.label} suspended the connection")
            subscribed.clear()
            publish(BrowseState.WORKING, emptyList())
        }

        override fun onConnectionFailed() {
            if (closed) return
            disarm()
            // The framework has already run forceCloseConnection(), so the
            // object is dead. Dropping it is what lets connect() be retried --
            // the `if (browser != null)` guard would otherwise hand the caller
            // a corpse forever.
            browser = null
            // The overwhelmingly common cause is the app's own onGetRoot
            // returning null for a caller it does not recognise. That is a
            // policy decision by the app, not a bug here, and the message says
            // so rather than implying Headway broke.
            onStep("media: ${app.label} refused to serve its library to Headway")
            publish(BrowseState.REFUSED, emptyList())
        }
    }

    private val subscriptionCallback = object : MediaBrowser.SubscriptionCallback() {
        override fun onChildrenLoaded(parentId: String, children: List<MediaBrowser.MediaItem>) {
            if (closed) return
            // A late delivery for a node the driver has already navigated away
            // from would replace the list under their thumb.
            if (parentId != currentId) return
            disarm()
            val empty = children.isEmpty()
            publish(
                when {
                    !empty -> BrowseState.OPEN
                    // An empty *root* on a connection that advertised content
                    // styling is the polite refusal; an empty node deeper in is
                    // just an empty folder.
                    refusedPolitely && trail.size <= 1 -> BrowseState.REFUSED
                    else -> BrowseState.EMPTY
                },
                children,
            )
        }

        override fun onError(parentId: String) {
            if (closed) return
            if (parentId != currentId) return
            disarm()
            onStep("media: ${app.label} reported an error loading '$parentId'")
            publish(BrowseState.EMPTY, emptyList())
        }
    }

    /**
     * Binds the service. Idempotent.
     *
     * ## Root hints, and the one that is not optional
     *
     * The tree hints (`EXTRA_RECENT`, `EXTRA_OFFLINE`, `EXTRA_SUGGESTED`) are
     * deliberately absent: each asks for a *different* tree, and the recents
     * tree in particular is a short flat list meant for a resume button rather
     * than a library.
     *
     * The size hint is different — it is a defence. Every
     * `MediaDescription.getIconBitmap()` arrives as a fresh bitmap unparceled
     * into this process, and the framework caps nothing. A hundred rows of
     * 512x512 art is ~100 MB, on the heap, on a phone that is also encoding
     * H.264. `BROWSER_ROOT_HINTS_KEY_MEDIA_ART_SIZE_PIXELS` is the documented
     * way to ask for art the size the car will actually draw, and androidx and
     * Media3 services honour it.
     */
    fun connect(listener: Listener) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            // MediaBrowser binds its internal Handler to the constructing
            // thread's Looper and documents itself as not thread-safe. Being on
            // the main thread today is an accident of the call site.
            main.post { connect(listener) }
            return
        }
        this.listener = listener
        if (browser != null) {
            listener.onBrowseChanged(state, items)
            return
        }
        closed = false
        refusedPolitely = false
        state = BrowseState.WORKING
        listener.onBrowseChanged(state, emptyList())

        val hints = Bundle().apply {
            putInt(HINT_MEDIA_ART_SIZE_PIXELS, ART_PIXELS)
            putInt(HINT_ROOT_CHILDREN_LIMIT, CHILDREN_LIMIT)
        }
        val created = runCatching {
            MediaBrowser(context, app.service, connectionCallback, hints)
        }.getOrNull()
        if (created == null) {
            publish(BrowseState.REFUSED, emptyList())
            return
        }
        browser = created
        val started = runCatching { created.connect() }
        if (started.isFailure) {
            onStep("media: could not bind ${app.label} (${started.exceptionOrNull()})")
            browser = null
            publish(BrowseState.REFUSED, emptyList())
            return
        }
        // A service that has not called setSessionToken() by the time its
        // onGetRoot returns produces **no callback at all** -- not onConnected,
        // not onConnectionFailed. AOSP's ServiceState.connectOnHandler sends
        // onConnect only `if (mSession != null)`, and Google's own UAMP sample
        // documents the silence. Without this the pane waits forever while
        // holding a live bind into another app's process.
        arm(CONNECT_TIMEOUT_MILLIS) {
            onStep("media: ${app.label} never answered the connection")
            browser = null
            publishNow(BrowseState.TIMED_OUT, emptyList())
        }
    }

    /**
     * Descends into a browsable node.
     *
     * ## Exactly one live subscription, always
     *
     * The parent is unsubscribed before the child is subscribed, and that is
     * not tidiness — it is the difference between Back working and Back hanging
     * forever. `MediaBrowserService.addSubscriptionOnHandler` dedupes on
     * `(token, options)` and **returns without calling
     * `performLoadChildrenOnHandler`** when the pair is already registered, and
     * the compatibility entry point beside it,
     * `ServiceBinder.addSubscriptionDeprecated`, is literally a do-nothing
     * method. So re-subscribing an id that is still subscribed produces no
     * `onLoadChildren`, no `onChildrenLoaded`, and a pane stuck on "Loading…"
     * with no error and no timeout.
     *
     * A first version of this class left every level subscribed on the way down
     * and re-subscribed on the way up, which hit exactly that. Holding one
     * subscription also makes the leak note in the class KDoc true rather than
     * aspirational, and costs nothing: Media3 does not deliver
     * `notifyChildrenChanged` to a framework `MediaBrowser` anyway.
     */
    fun open(id: String, title: String) {
        val active = browser ?: return
        if (closed) return
        if (id.isBlank()) {
            // An empty media id is reachable: MediaItem's Parcel constructor
            // skips the non-empty check the public one enforces, and
            // `subscribe` throws on a blank parent.
            onStep("media: ${app.label} offered '$title' with no id")
            return
        }
        // Re-entering the node already shown is a no-op rather than a reload:
        // a double tap on a car touchscreen is normal.
        if (id == currentId) return

        release(currentId)

        currentId = id
        trail += id
        trailTitles += title
        publishNow(BrowseState.WORKING, emptyList())
        subscribe(active, id)
    }

    /** Goes back up one level. No-op at the root. */
    fun back() {
        val active = browser ?: return
        if (closed) return
        if (trail.size <= 1) return

        release(trail.removeAt(trail.lastIndex))
        trailTitles.removeAt(trailTitles.lastIndex)

        val target = trail.last()
        currentId = target
        publishNow(BrowseState.WORKING, emptyList())
        subscribe(active, target)
    }

    /**
     * Subscribes [id] and arms the watchdog.
     *
     * The set is written only after the call returns, because `subscribe`
     * throws on a blank parent and a set that records a subscription which does
     * not exist makes [close] call `unsubscribe` on it.
     */
    private fun subscribe(active: MediaBrowser, id: String) {
        val started = runCatching { active.subscribe(id, subscriptionCallback) }
        if (started.isFailure) {
            onStep("media: ${app.label} would not open '$id' (${started.exceptionOrNull()})")
            publishNow(BrowseState.EMPTY, emptyList())
            return
        }
        subscribed += id
        arm(LOAD_TIMEOUT_MILLIS) {
            onStep("media: ${app.label} never answered for '$id'")
            publishNow(BrowseState.TIMED_OUT, emptyList())
        }
    }

    private fun release(id: String?) {
        val target = id ?: return
        if (!subscribed.remove(target)) return
        runCatching { browser?.unsubscribe(target) }
    }

    /**
     * Plays a leaf.
     *
     * Through the app's own `MediaSession`, which is the only route: there is
     * no "play this" on `MediaBrowser` itself. `playFromMediaId` is the
     * documented pair to a browse tree and is what Android Auto sends.
     *
     * @return false when the id is unusable or the session has since died.
     *   An earlier version of this documented "the app published no session
     *   token", which cannot happen: a service that has not set a token never
     *   completes the connection at all.
     */
    fun play(item: MediaBrowser.MediaItem): Boolean {
        val active = browser ?: return false
        // Blank as well as null: MediaItem's Parcel constructor skips the
        // non-empty check the public constructor enforces, so an empty id is
        // reachable over the wire and playFromMediaId would silently do nothing.
        val id = item.mediaId?.takeIf { it.isNotBlank() } ?: run {
            onStep("media: ${app.label} offered an item with no id")
            return false
        }
        val token = runCatching { active.sessionToken }.getOrNull() ?: run {
            onStep("media: the connection to ${app.label} died before it could play")
            return false
        }
        val controller = runCatching { MediaController(context, token) }.getOrNull() ?: return false
        val sent = runCatching { controller.transportControls.playFromMediaId(id, null) }
        if (sent.isFailure) {
            onStep("media: ${app.label} refused playFromMediaId (${sent.exceptionOrNull()})")
            return false
        }
        onStep("media: asked ${app.label} to play '${item.description.title}'")
        return true
    }

    /** Unsubscribes everything and disconnects. Idempotent. */
    fun close() {
        if (closed) return
        closed = true
        // Bumped before anything else: a callback already queued on the main
        // looper must not reach a listener that has moved to another app.
        generation++
        disarm()
        val active = browser
        browser = null
        listener = null
        if (active != null) {
            subscribed.forEach { runCatching { active.unsubscribe(it) } }
            runCatching { active.disconnect() }
        }
        subscribed.clear()
        trail.clear()
        trailTitles.clear()
        currentId = null
        items = emptyList()
        refusedPolitely = false
        state = BrowseState.UNKNOWN
    }

    /**
     * A root that connected but is a stub.
     *
     * Google's own UAMP sample established the pattern and 139 repositories
     * copied it: rather than return null from `onGetRoot` and be disconnected,
     * an app that does not recognise the caller returns a root with no
     * children. Headway used to render that as "Nothing here.", which reads as
     * an empty music collection rather than as a refusal.
     *
     * The tell is the extras. An app that intends to serve its library says how
     * it wants that library drawn — the content-style and search keys are what
     * `BrowserRoot` carries for exactly that. A root advertising neither, that
     * then delivers nothing, is a stub.
     */
    private fun looksLikeAnEmptyStub(active: MediaBrowser): Boolean {
        val extras = runCatching { active.extras }.getOrNull() ?: return true
        return !extras.containsKey(EXTRA_CONTENT_STYLE_SUPPORTED) &&
            !extras.containsKey(EXTRA_SEARCH_SUPPORTED)
    }

    private fun arm(delayMillis: Long, onExpiry: () -> Unit) {
        disarm()
        val fired = Runnable {
            watchdog = null
            if (!closed) onExpiry()
        }
        watchdog = fired
        main.postDelayed(fired, delayMillis)
    }

    private fun disarm() {
        val pending = watchdog ?: return
        watchdog = null
        main.removeCallbacks(pending)
    }

    /** Delivers on the next loop turn, guarded against a session swap. */
    private fun publish(next: BrowseState, children: List<MediaBrowser.MediaItem>) {
        val era = generation
        val target = listener ?: run { record(next, children); return }
        record(next, children)
        // Posted rather than called inline so a listener that rebuilds its view
        // tree cannot re-enter this object from inside a framework callback.
        main.post { if (era == generation && !closed) target.onBrowseChanged(next, children) }
    }

    /** Delivers immediately; for state this object raised itself. */
    private fun publishNow(next: BrowseState, children: List<MediaBrowser.MediaItem>) {
        record(next, children)
        listener?.onBrowseChanged(next, children)
    }

    private fun record(next: BrowseState, children: List<MediaBrowser.MediaItem>) {
        state = next
        items = children
    }

    private companion object {
        /**
         * How long to wait for a connection before calling it dead.
         *
         * Ten seconds because a cold app has to start its process, and because
         * the failure this guards is silence rather than an error -- there is
         * nothing else that will ever wake the pane.
         */
        const val CONNECT_TIMEOUT_MILLIS = 10_000L

        /** A level that has not loaded in this long is not going to. */
        const val LOAD_TIMEOUT_MILLIS = 10_000L

        /**
         * The art size asked for in the root hints.
         *
         * Sized for the row it is drawn in on an 800x480 panel, not for the
         * phone: a car row is about 46 px of art, and 128 covers a denser head
         * unit with room to spare while keeping a hundred-row level under two
         * megabytes rather than a hundred.
         */
        const val ART_PIXELS = 128

        /** Enough to scroll; small enough that a pathological library cannot stall the pane. */
        const val CHILDREN_LIMIT = 500

        // Defined in androidx.media(3) MediaConstants, which Headway does not
        // depend on; the strings are the wire contract and are stable.
        const val HINT_MEDIA_ART_SIZE_PIXELS =
            "android.media.extras.MEDIA_ART_SIZE_HINT_PIXELS"
        const val HINT_ROOT_CHILDREN_LIMIT =
            "androidx.media.MediaBrowserCompat.Extras.KEY_ROOT_CHILDREN_LIMIT"
        const val EXTRA_CONTENT_STYLE_SUPPORTED =
            "android.media.browse.CONTENT_STYLE_SUPPORTED"
        const val EXTRA_SEARCH_SUPPORTED =
            "android.media.browse.SEARCH_SUPPORTED"
    }
}

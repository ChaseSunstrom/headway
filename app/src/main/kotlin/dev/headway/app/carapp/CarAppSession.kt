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

package dev.headway.app.carapp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.graphics.Rect
import android.location.Location
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Surface
import androidx.car.app.AppInfo
import androidx.car.app.CarAppService
import androidx.car.app.FailureResponse
import androidx.car.app.HandshakeInfo
import androidx.car.app.IAppHost
import androidx.car.app.IAppManager
import androidx.car.app.ICarApp
import androidx.car.app.ICarHost
import androidx.car.app.IOnDoneCallback
import androidx.car.app.ISurfaceCallback
import androidx.car.app.SessionInfo
import androidx.car.app.SessionInfoIntentEncoder
import androidx.car.app.SurfaceContainer
import androidx.car.app.constraints.IConstraintHost
import androidx.car.app.model.Template
import androidx.car.app.model.TemplateWrapper
import androidx.car.app.navigation.INavigationHost
import androidx.car.app.serialization.Bundleable
import androidx.car.app.versioning.CarAppApiLevels
import dev.headway.app.log.SessionLog

private const val TAG = "HeadwayCarApp"

/** Where a [CarAppSession] has got to. */
enum class HostState {
    /** Nothing bound yet. */
    IDLE,

    /** Bound or binding; no template has arrived. */
    WORKING,

    /** A template is on screen. */
    RUNNING,

    /**
     * The app looked at Headway and said no.
     *
     * Distinct from [FAILED] because it is not a bug and retrying will not help
     * — see [CarAppSession]'s note on `HostValidator`.
     */
    REFUSED,

    /** Bound, but nothing came back in time. */
    TIMED_OUT,

    /** Something else went wrong; [CarAppSession.fault] says what. */
    FAILED,
}

/**
 * Headway as a car-app *host*: the other end of `androidx.car.app`.
 *
 * ## What this is
 *
 * Android Auto does not put an app's window on the car screen. It binds a
 * service the app exports, the app hands back a **template** — a `ListTemplate`,
 * a `NavigationTemplate`, a `GridTemplate` — and the *host* draws it with its own
 * widgets at the car's own size. The app never renders a pixel. That is why
 * Android Auto's screens all look alike, why they work at 800x480, and why they
 * are legible at a glance.
 *
 * This class is that host. It is the answer to "render an app without mirroring
 * the phone", and it is the only correct one: a template is a model the app
 * publishes on purpose, so nothing here needs a privileged API, a hidden call,
 * or the app's cooperation beyond what the app already published.
 *
 * ## Why it depends on androidx.car.app instead of reimplementing it
 *
 * Because the wire format is not a protocol you can read off a document. Binder
 * transaction codes are assigned by *declaration order* in the AIDL, so a
 * hand-written `ICarApp` with the methods in a different order compiles, binds,
 * and then silently calls `onAppPause` when it means `onAppStart`. And every
 * payload crossing the boundary is a `Bundleable`, whose encoding is
 * `Bundler`'s reflective field-name mangling — 900 lines whose contract is
 * "whatever the library does". Using the library's own stubs makes both exact by
 * construction, and it is Apache-2.0, which is one-way compatible with GPLv3.
 *
 * ## The handshake, in the order it must happen
 *
 * Derived from `CarAppBinder` in androidx.car.app:app:1.7.0, decompiled:
 *
 * 1. `getAppInfo` — the app reports the API levels it supports.
 * 2. `onHandshakeCompleted(HandshakeInfo(hostPackage, level))`. The app rejects
 *    a level below its minimum or above its latest, so the level must be chosen
 *    from step 1 and not assumed.
 * 3. **Validation happens here, inside the app.** It builds
 *    `HostInfo(claimedPackage, Binder.getCallingUid())` and runs its own
 *    `HostValidator`. Note what that means: the package name Headway sends is
 *    cross-checked against the real calling uid, so a host cannot claim to be
 *    someone else. Headway sends its own name and is accepted on the
 *    `android.car.permission.TEMPLATE_RENDERER` branch — see the manifest for
 *    the derivation.
 * 4. `onAppCreate(carHost, intent, configuration)` — `carHost` is Headway's own
 *    [ICarHost], through which the app asks for the host services below.
 * 5. `onAppStart`, `onAppResume`.
 * 6. `getManager("app")` returns the app's `IAppManager`; `getTemplate` on it
 *    returns the first `TemplateWrapper`.
 *
 * After that the app calls `IAppHost.invalidate()` whenever its screen changes
 * and the host fetches the next template. That is the entire steady state.
 *
 * ## Threading
 *
 * Every binder callback arrives on a pool thread. Everything here hops to the
 * main looper before touching state, because the state feeds views. The one
 * exception is [IConstraintHost.getContentLimit], which is a *synchronous*
 * binder call from the app and must answer on the calling thread — it therefore
 * reads only immutable fields.
 */
class CarAppSession(
    context: Context,
    val app: TemplateApp,
    private val onStep: (String) -> Unit = {},
) {

    fun interface Listener {
        fun onHostState(state: HostState, template: Template?)
    }

    private val appContext: Context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    /** Guards every late callback: a closed session must publish nothing. */
    private var closed = false

    /**
     * Bumped on every [close]. A callback carrying a stale generation is from a
     * session the driver has already navigated away from, and applying it would
     * paint one app's screen into another app's pane.
     */
    private var generation = 0

    private var connection: ServiceConnection? = null
    private var carApp: ICarApp? = null
    private var appManager: IAppManager? = null

    /** Set by the app when it wants to draw its own map; see [surfaceReady]. */
    private var surfaceCallback: ISurfaceCallback? = null
    private var surface: SurfaceHandle? = null

    private var listener: Listener? = null

    var state: HostState = HostState.IDLE
        private set

    /** The last template the app sent, or null before the first one. */
    var template: Template? = null
        private set

    /** Why [HostState.FAILED] or [HostState.REFUSED], in words a log can carry. */
    var fault: String? = null
        private set

    /** Negotiated in the handshake; the app is told this and holds it. */
    private var apiLevel: Int = CarAppApiLevels.UNKNOWN

    // --- lifecycle ---------------------------------------------------------

    /**
     * Binds the app and runs the handshake.
     *
     * Must be called on the main thread — the whole class assumes it, and the
     * watchdog below is posted from here.
     */
    fun connect(listener: Listener) {
        this.listener = listener
        publish(HostState.WORKING)

        val intent = Intent(CarAppService.SERVICE_INTERFACE).setComponent(app.service)
        // The session id goes in the intent's *identifier*, which is what makes
        // two sessions against one service distinct bindings rather than the
        // platform handing back the same binder twice. Without it a second
        // session silently shares the first one's screen stack.
        runCatching { SessionInfoIntentEncoder.encode(SessionInfo.DEFAULT_SESSION_INFO, intent) }

        val binding = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val bound = service ?: return
                main.post { onBound(bound) }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                main.post { fail("${app.label} stopped responding") }
            }

            override fun onNullBinding(name: ComponentName?) {
                // A CarAppService that returns null from onBind has decided not
                // to serve this caller at all, which is a refusal and not a
                // fault.
                main.post { refuse("${app.label} would not open a car session") }
            }
        }
        connection = binding

        val started = runCatching {
            appContext.bindService(intent, binding, Context.BIND_AUTO_CREATE)
        }
        if (started.getOrDefault(false) != true) {
            connection = null
            fail("could not bind ${app.label}: ${started.exceptionOrNull() ?: "refused"}")
            return
        }
        armWatchdog(BIND_TIMEOUT_MILLIS, "${app.label} did not answer the handshake")
    }

    /**
     * Ends the session and releases the binding.
     *
     * Idempotent, and safe from any state: a half-finished handshake leaves a
     * binding held into another app's process, which keeps that process alive
     * for the rest of the drive.
     */
    fun close() {
        if (closed) return
        closed = true
        generation++
        main.removeCallbacksAndMessages(null)

        val running = carApp
        carApp = null
        appManager = null
        surfaceCallback = null
        surface?.release()
        surface = null

        if (running != null) {
            // Best effort and in order. An app that has already died throws
            // DeadObjectException from every one of these, which is not an
            // error worth reporting during teardown.
            runCatching { running.onAppPause(NoopCallback) }
            runCatching { running.onAppStop(NoopCallback) }
        }
        connection?.let { binding ->
            connection = null
            runCatching { appContext.unbindService(binding) }
        }
        listener = null
    }

    fun describe(): String = buildString {
        append(app.label)
        append(": ")
        append(state.name.lowercase())
        if (apiLevel != CarAppApiLevels.UNKNOWN) append(" (api $apiLevel)")
        template?.let { append(", ").append(it.javaClass.simpleName) }
        fault?.let { append(" — ").append(it) }
    }

    // --- the handshake -----------------------------------------------------

    private fun onBound(binder: IBinder) {
        if (closed) return
        val remote = runCatching { ICarApp.Stub.asInterface(binder) }.getOrNull()
        if (remote == null) {
            fail("${app.label} returned a binder that is not a car app")
            return
        }
        carApp = remote
        send("getAppInfo") { remote.getAppInfo(callback("getAppInfo") { onAppInfo(it) }) }
    }

    private fun onAppInfo(payload: Any?) {
        val info = payload as? AppInfo ?: run {
            fail("${app.label} sent no app info")
            return
        }
        // The library rejects a level below the app's minimum and above its
        // latest, with two different errors. Clamping to the app's ceiling and
        // then checking its floor is the only choice that satisfies both, and
        // it is why getAppInfo has to come first rather than a level being
        // assumed.
        val level = minOf(HOST_API_LEVEL, info.latestCarAppApiLevel)
        if (level < info.minCarAppApiLevel) {
            refuse(
                "${app.label} needs car API level ${info.minCarAppApiLevel} and Headway " +
                    "speaks up to $HOST_API_LEVEL",
            )
            return
        }
        apiLevel = level
        onStep("car app: ${app.label} at api $level (${info.libraryDisplayVersion})")

        val remote = carApp ?: return
        val handshake = runCatching {
            Bundleable.create(HandshakeInfo(appContext.packageName, level))
        }.getOrNull() ?: run {
            fail("could not encode the handshake for ${app.label}")
            return
        }
        send("onHandshakeCompleted") {
            remote.onHandshakeCompleted(handshake, callback("handshake") { onHandshakeDone() })
        }
    }

    private fun onHandshakeDone() {
        val remote = carApp ?: return
        val launch = Intent(Intent.ACTION_MAIN).setComponent(app.service)
        val configuration = Configuration(appContext.resources.configuration)
        send("onAppCreate") {
            remote.onAppCreate(
                CarHost(),
                launch,
                configuration,
                callback("onAppCreate") { onCreated() },
            )
        }
    }

    private fun onCreated() {
        val remote = carApp ?: return
        send("onAppStart") {
            remote.onAppStart(
                callback("onAppStart") {
                    val started = carApp ?: return@callback
                    send("onAppResume") {
                        started.onAppResume(callback("onAppResume") { onResumed() })
                    }
                },
            )
        }
    }

    private fun onResumed() {
        val remote = carApp ?: return
        send("getManager") {
            remote.getManager(
                APP_MANAGER,
                callback("getManager") { payload ->
                    val manager = payload as? IAppManager ?: run {
                        fail("${app.label} has no app manager")
                        return@callback
                    }
                    appManager = manager
                    refreshTemplate()
                },
            )
        }
    }

    /**
     * Asks for the current screen.
     *
     * Called once after the handshake and then every time the app invalidates.
     * There is no push: `getTemplate` is a pull and `invalidate` is only the
     * doorbell, which is worth knowing because an app that invalidates in a loop
     * costs one binder round trip per loop and nothing else.
     */
    fun refreshTemplate() {
        val manager = appManager ?: return
        armWatchdog(TEMPLATE_TIMEOUT_MILLIS, "${app.label} sent no screen")
        send("getTemplate") {
            manager.getTemplate(
                callback("getTemplate") { payload ->
                    val wrapper = payload as? TemplateWrapper
                    val next = wrapper?.template ?: run {
                        fail("${app.label} sent something that is not a template")
                        return@callback
                    }
                    template = next
                    publish(HostState.RUNNING)
                },
            )
        }
    }

    /** The Back gesture, handed to the app so its own screen stack unwinds. */
    fun back(): Boolean {
        val manager = appManager ?: return false
        send("onBackPressed") { manager.onBackPressed(NoopCallback) }
        return true
    }

    // --- the surface -------------------------------------------------------

    /**
     * Hands the app a real [Surface] to draw its map on.
     *
     * This is the half of the protocol that lets a navigation app draw the map
     * *itself* while Headway draws everything around it. The app receives a
     * `SurfaceContainer` and renders into it directly, so the map is the app's
     * own rendering at the car's own resolution — not a scaled phone screen, and
     * not something Headway has to understand.
     *
     * Called by the renderer once the surface view is ready, and again with the
     * new size when it changes.
     */
    fun surfaceReady(handle: SurfaceHandle, visible: Rect, stable: Rect) {
        surface?.takeIf { it !== handle }?.release()
        surface = handle
        val callback = surfaceCallback ?: return
        val container = runCatching {
            Bundleable.create(
                SurfaceContainer(handle.surface, handle.width, handle.height, handle.dpi),
            )
        }.getOrNull() ?: return
        send("onSurfaceAvailable") { callback.onSurfaceAvailable(container, NoopCallback) }
        send("onVisibleAreaChanged") { callback.onVisibleAreaChanged(visible, NoopCallback) }
        send("onStableAreaChanged") { callback.onStableAreaChanged(stable, NoopCallback) }
    }

    /** The surface is going away; the app must stop drawing before it does. */
    fun surfaceGone() {
        val callback = surfaceCallback
        val handle = surface
        surface = null
        if (callback == null || handle == null) {
            handle?.release()
            return
        }
        val container = runCatching {
            Bundleable.create(
                SurfaceContainer(handle.surface, handle.width, handle.height, handle.dpi),
            )
        }.getOrNull()
        if (container != null) {
            send("onSurfaceDestroyed") { callback.onSurfaceDestroyed(container, NoopCallback) }
        }
        handle.release()
    }

    fun scroll(distanceX: Float, distanceY: Float) {
        val callback = surfaceCallback ?: return
        send("onScroll") { callback.onScroll(distanceX, distanceY) }
    }

    fun fling(velocityX: Float, velocityY: Float) {
        val callback = surfaceCallback ?: return
        send("onFling") { callback.onFling(velocityX, velocityY) }
    }

    fun scale(focusX: Float, focusY: Float, factor: Float) {
        val callback = surfaceCallback ?: return
        send("onScale") { callback.onScale(focusX, focusY, factor) }
    }

    fun click(x: Float, y: Float) {
        val callback = surfaceCallback ?: return
        send("onClick") { callback.onClick(x, y) }
    }

    // --- the host services the app asks for --------------------------------

    /**
     * What the app calls back into.
     *
     * `getHost` is the app asking for one of the host's own services by the same
     * names `CarContext` uses. Returning null for an unknown one is correct and
     * expected: the library treats a null host service as "this host does not
     * offer that", and an app that needs it degrades rather than crashes.
     */
    private inner class CarHost : ICarHost.Stub() {

        override fun startCarApp(intent: Intent?) {
            val wanted = intent ?: return
            main.post { onStep("car app: ${app.label} asked to start $wanted") }
        }

        override fun getHost(type: String?): IBinder? = when (type) {
            APP_MANAGER -> AppHost().asBinder()
            CONSTRAINT_MANAGER -> ConstraintHost().asBinder()
            NAVIGATION_MANAGER -> NavigationHost().asBinder()
            else -> null
        }

        override fun finish() {
            main.post {
                if (closed) return@post
                onStep("car app: ${app.label} finished")
                // Published *before* close, which nulls the listener. Without
                // this the pane keeps drawing the last screen of an app that
                // has shut itself down, and the driver taps a dead template.
                publish(HostState.IDLE)
                close()
            }
        }
    }

    private inner class AppHost : IAppHost.Stub() {

        /** The doorbell: the app's screen changed, so fetch the new template. */
        override fun invalidate() {
            main.post { if (!closed) refreshTemplate() }
        }

        override fun showToast(text: CharSequence?, duration: Int) {
            val message = text?.toString() ?: return
            main.post { onStep("car app: ${app.label} says \"$message\"") }
        }

        override fun setSurfaceCallback(callback: ISurfaceCallback?) {
            main.post {
                if (closed) return@post
                surfaceCallback = callback
                // A surface may already exist — the renderer creates it as soon
                // as the pane is laid out, which can easily beat the app asking
                // for it. Re-offering it here is what makes the ordering not
                // matter.
                surface?.let { surfaceReady(it, it.bounds(), it.bounds()) }
            }
        }

        override fun sendLocation(location: Location?) = Unit

        override fun showAlert(alert: Bundleable?) = Unit

        override fun dismissAlert(alertId: Int) = Unit

        /**
         * Not offered.
         *
         * The car microphone belongs to the AAP session and reaches Headway's
         * own voice pipeline; handing its stream to a third-party app is a
         * different consent than the one the driver gave, and there is nowhere
         * on the car screen to ask for it. Returning null is the documented way
         * for a host to say the microphone is unavailable.
         */
        override fun openMicrophone(request: Bundleable?): Bundleable? = null
    }

    /**
     * How much the car screen can hold.
     *
     * Answered honestly rather than generously. These numbers are what the app
     * uses to truncate its own lists, and a host that overstates them gets a
     * list it cannot draw — which on a 480-pixel panel means rows the driver
     * scrolls past rather than reads.
     *
     * Synchronous: the app blocks on this call, so it must not touch anything
     * the main thread owns.
     */
    private inner class ConstraintHost : IConstraintHost.Stub() {

        override fun getContentLimit(contentType: Int): Int = when (contentType) {
            CONTENT_LIMIT_LIST -> LIST_LIMIT
            CONTENT_LIMIT_GRID -> GRID_LIMIT
            CONTENT_LIMIT_PANE -> PANE_LIMIT
            CONTENT_LIMIT_PLACE_LIST -> PLACE_LIMIT
            CONTENT_LIMIT_ROUTE_LIST -> ROUTE_LIMIT
            else -> LIST_LIMIT
        }

        /**
         * True, and it is the whole point of this host.
         *
         * App-driven refresh is what lets an app update a row's text without
         * counting the change against the step limit a real car imposes for
         * driver distraction. Headway is not a certified car UI and does not
         * pretend to be one; it says so here rather than silently throttling
         * updates a driver asked for.
         */
        override fun isAppDrivenRefreshEnabled(): Boolean = true
    }

    private inner class NavigationHost : INavigationHost.Stub() {

        override fun navigationStarted() {
            main.post { onStep("car app: ${app.label} started navigating") }
        }

        override fun navigationEnded() {
            main.post { onStep("car app: ${app.label} stopped navigating") }
        }

        /**
         * A `Trip`: the structured turn-by-turn a navigation app publishes.
         *
         * Decoded and handed to [CarAppTrips], which is where the AAP
         * navigation-status service and the Maps pane read it from. This is the
         * good version of what `NavigationFeed` scrapes out of a notification —
         * real maneuver codes, real distances, and a unit to interpret them in.
         */
        override fun updateTrip(trip: Bundleable?) {
            val payload = trip ?: return
            val decoded = runCatching { payload.get() }.getOrNull() ?: return
            main.post { if (!closed) CarAppTrips.offer(app.packageName, decoded) }
        }
    }

    // --- plumbing ----------------------------------------------------------

    /**
     * One binder call, with the remote's death treated as an ordinary outcome.
     *
     * Every call here can throw `DeadObjectException` the instant the app is
     * killed — which happens routinely, because the app is a background service
     * on a phone under memory pressure. Catching it per call and reporting once
     * is the difference between "Maps closed" and a crash on the car screen.
     */
    private inline fun send(what: String, block: () -> Unit) {
        val result = runCatching(block)
        result.exceptionOrNull()?.let { error ->
            SessionLog.shared.warn(TAG, "$what failed against ${app.packageName}: $error")
            main.post { fail("${app.label} stopped responding") }
        }
    }

    /**
     * An [IOnDoneCallback] that hops to the main thread and drops stale replies.
     *
     * The generation check is not belt and braces. A reply from a session the
     * driver closed a moment ago arrives on a pool thread with no idea the pane
     * has moved on, and applying it paints one app's template into another
     * app's pane — the same defect the media browser had, from the same cause.
     */
    private fun callback(what: String, onDone: (Any?) -> Unit): IOnDoneCallback {
        val issued = generation
        return object : IOnDoneCallback.Stub() {
            override fun onSuccess(response: Bundleable?) {
                val payload = response?.let { runCatching { it.get() }.getOrNull() }
                main.post {
                    if (closed || issued != generation) return@post
                    main.removeCallbacksAndMessages(WATCHDOG)
                    onDone(payload)
                }
            }

            override fun onFailure(response: Bundleable?) {
                val failure = response?.let { runCatching { it.get() }.getOrNull() }
                    as? FailureResponse
                val why = failure?.let { describeFailure(it) } ?: "no reason given"
                main.post {
                    if (closed || issued != generation) return@post
                    main.removeCallbacksAndMessages(WATCHDOG)
                    // A security error at any point in the handshake is the app
                    // declining Headway, not a bug in Headway. Telling the two
                    // apart matters because one of them is worth retrying.
                    if (failure?.errorType == FailureResponse.SECURITY_EXCEPTION ||
                        failure?.errorType == FailureResponse.INVALID_PARAMETER_EXCEPTION
                    ) {
                        refuse("${app.label} declined Headway as a car host ($what: $why)")
                    } else {
                        fail("${app.label} failed at $what: $why")
                    }
                }
            }
        }
    }

    /**
     * The first line of the app's stack trace, which is the part that names the
     * problem. The rest is pages of binder frames.
     */
    private fun describeFailure(failure: FailureResponse): String =
        failure.stackTrace.lineSequence().firstOrNull()?.trim()?.take(200)
            ?: "error type ${failure.errorType}"

    /**
     * A deadline, because an app that never answers produces no callback at all.
     *
     * The same failure the media browser had: a service that binds and then
     * simply does not reply leaves the pane on "Loading…" until the drive ends.
     * Every step of the handshake is guarded, and the token is shared so the
     * next step's watchdog replaces the last one's.
     */
    private fun armWatchdog(millis: Long, message: String) {
        main.removeCallbacksAndMessages(WATCHDOG)
        main.postAtTime(
            {
                if (closed) return@postAtTime
                fault = message
                publish(HostState.TIMED_OUT)
                onStep("car app: $message")
            },
            WATCHDOG,
            android.os.SystemClock.uptimeMillis() + millis,
        )
    }

    private fun fail(message: String) {
        if (closed || state == HostState.REFUSED) return
        fault = message
        SessionLog.shared.warn(TAG, message)
        onStep("car app: $message")
        publish(HostState.FAILED)
    }

    private fun refuse(message: String) {
        if (closed) return
        fault = message
        SessionLog.shared.info(TAG, message)
        onStep("car app: $message")
        publish(HostState.REFUSED)
    }

    private fun publish(next: HostState) {
        state = next
        if (next != HostState.RUNNING && next != HostState.WORKING) {
            main.removeCallbacksAndMessages(WATCHDOG)
        }
        listener?.onHostState(next, template)
    }

    private object NoopCallback : IOnDoneCallback.Stub() {
        override fun onSuccess(response: Bundleable?) = Unit
        override fun onFailure(response: Bundleable?) = Unit
    }

    companion object {

        /**
         * The car API level Headway speaks.
         *
         * `getLatest()` and not a literal: it is what the bundled library
         * supports, so it moves with the dependency instead of quietly capping
         * apps at whatever level was current when this was written. The app's
         * own ceiling is applied on top of it in [onAppInfo].
         */
        val HOST_API_LEVEL: Int = CarAppApiLevels.getLatest()

        /**
         * `CarContext`'s service names, which are the same strings on both
         * sides of the binder — the app asks `getHost("app")` and the host asks
         * `getManager("app")`.
         */
        const val APP_MANAGER = "app"
        const val NAVIGATION_MANAGER = "navigation"
        const val CONSTRAINT_MANAGER = "constraints"

        /**
         * `ConstraintManager`'s content types, spelled out.
         *
         * The constants live on `androidx.car.app.constraints.ConstraintManager`
         * as `CONTENT_LIMIT_TYPE_*`; their values are the contract with the app
         * and are stable API.
         */
        const val CONTENT_LIMIT_LIST = 0
        const val CONTENT_LIMIT_GRID = 1
        const val CONTENT_LIMIT_PLACE_LIST = 2
        const val CONTENT_LIMIT_ROUTE_LIST = 3
        const val CONTENT_LIMIT_PANE = 4

        /**
         * How much a Headway pane can actually show.
         *
         * A dashboard pane on an 800x480 panel is roughly six rows tall, and
         * these are scroll limits rather than screen limits — the numbers a
         * certified car UI uses, which is the right neighbourhood for a screen
         * a driver glances at.
         */
        const val LIST_LIMIT = 12
        const val GRID_LIMIT = 8
        const val PANE_LIMIT = 4
        const val PLACE_LIMIT = 12
        const val ROUTE_LIMIT = 3

        /** Ten seconds to bind and hand over a first template. */
        const val BIND_TIMEOUT_MILLIS = 10_000L

        /** A redraw is a round trip; five seconds is already generous. */
        const val TEMPLATE_TIMEOUT_MILLIS = 5_000L

        /** The token every watchdog is posted under, so one cancels the last. */
        private val WATCHDOG = Any()
    }
}

/**
 * A surface and the geometry that goes with it, kept together.
 *
 * The app is told a width, a height and a dpi, and it renders to exactly that.
 * Passing them separately is how they end up disagreeing after a pane resize.
 */
class SurfaceHandle(
    val surface: Surface,
    val width: Int,
    val height: Int,
    val dpi: Int,
    private val onRelease: () -> Unit = {},
) {
    fun bounds(): Rect = Rect(0, 0, width, height)

    fun release() = runCatching { onRelease() }.let { }
}

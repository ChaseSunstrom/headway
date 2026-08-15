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

package dev.headway.app.input

import aap_protobuf.service.ServiceOuterClass
import aap_protobuf.service.inputsource.InputSourceServiceOuterClass.InputSourceService
import aap_protobuf.service.media.shared.message.MediaCodecTypeOuterClass.MediaCodecType
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.WindowManager
import dev.headway.app.dash.CarShell
import dev.headway.app.video.AppPaneHost
import dev.headway.app.video.CarAppDisplay
import dev.headway.dash.PaneRect
import dev.headway.input.CarGesture
import dev.headway.input.GestureBuilder
import dev.headway.input.GestureConfig
import dev.headway.protocol.channel.CarInputEvent
import dev.headway.protocol.channel.InputChannel
import dev.headway.protocol.channel.InputChannelException
import dev.headway.protocol.channel.InputChannelMessage
import dev.headway.protocol.channel.InputKeyCodes
import dev.headway.protocol.channel.TouchSurface
import dev.headway.protocol.channel.CarRect
import dev.headway.protocol.channel.TouchTransform
import dev.headway.protocol.framing.ChannelId
import dev.headway.protocol.io.MessageChannel
import dev.headway.protocol.session.HeadUnitProfile
import dev.headway.video.VideoResolution
import java.io.EOFException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Everything between "the driver touched the car's screen" and "the phone
 * behaved as though the driver had touched *its* screen".
 *
 * ## The chain, and where each link already lives
 *
 * ```text
 *   InputReport on the wire
 *     -> InputChannel.handle          decode: CarInputEvent, in car coordinates
 *     -> TouchTransform.map           car coordinates -> phone coordinates
 *     -> GestureBuilder.accept        a stream of points -> a dispatchable gesture
 *     -> CarGestureDispatcher.submit  -> AccessibilityService.dispatchGesture
 * ```
 *
 * Every link was already written and tested in isolation; none of them was
 * connected to the live session. This class is only the wiring, which is why it
 * holds no protocol knowledge of its own beyond choosing the right service and
 * the right coordinate spaces. Both of those choices are easy to get wrong in a
 * way that produces a car screen that *almost* works — touches landing a
 * consistent distance from what the driver pointed at — so both are argued
 * below rather than assumed.
 *
 * ## Reading: handle(), not receiveMessage()
 *
 * [InputChannel] offers both, and its KDoc is explicit that `receiveMessage()`
 * reads the connection directly and is only correct when nothing else is
 * reading it. In a live session the [dev.headway.protocol.io.ChannelDemultiplexer]
 * owns the socket, so this class reads its *view* of the input channel and hands
 * each message to [InputChannel.handle]. The two are not equivalent even though
 * a view only ever yields messages for one channel: `handle()` throws if a
 * message for another channel reaches it, which turns a demultiplexer routing
 * bug into a loud failure instead of a message silently swallowed by
 * `receiveMessage()`'s filter loop.
 *
 * ## Which service, and which coordinate space
 *
 * The service is found by content — an `input_source_service` that advertises at
 * least one touchscreen — for the reason [dev.headway.app.video.CarVideoStream]
 * gives: channel ids are the head unit's to assign, and matching on Headway's own
 * [ChannelId] table would work against the emulator and fail against a car that
 * numbers things differently.
 *
 * The *car* side of [TouchTransform] is the geometry the head unit rescales its
 * panel coordinates into before sending, which is the projected video
 * resolution rather than the `TouchScreen` width and height — see the transform's
 * own KDoc, and `openauto/src/autoapp/Projection/InputDevice.cpp` L391-L392. So
 * the advertised video resolution is preferred whenever every advertised
 * configuration agrees on one size, and the touchscreen geometry is the fallback
 * for a unit that offers several. On a 2021 Chevrolet Infotainment 3 unit both
 * are 800x480 and the distinction is invisible; on a unit where they differ,
 * using the panel size skews every touch, so the log always records which was
 * used and says so when they disagree.
 *
 * The *phone* side is the full display, because that is what
 * [dev.headway.video.ScreenEncoder] mirrors: the platform scales the default
 * display uniformly into the virtual display's surface and pillarboxes the
 * remainder, which is exactly the geometry [TouchTransform] models. The two must
 * agree or every touch is offset by the difference.
 *
 * A rotation changes that geometry. The transform is built once at [start] and
 * is not rebuilt, so casting from a phone that rotates mid-session puts the bars
 * in the wrong place until the session restarts; Headway's own surfaces are
 * portrait and the launcher holds the screen on, which is what makes this
 * survivable rather than fixed.
 *
 * ## Keys are logged, not mapped
 *
 * The head unit advertises `keycodes_supported` and the phone binds a subset —
 * and asking for one that was not advertised fails the *whole* request, so this
 * binds exactly what was advertised. What arrives is then logged with its
 * keycode, because the point of Phase 3 on a real car is to find out which
 * physical steering-wheel button sends which code; a mapping invented here would
 * be indistinguishable in the log from a verified one. The single exception is
 * BACK, which the platform itself defines an equivalent for
 * (`GLOBAL_ACTION_BACK`), so honouring it is reading the platform rather than
 * guessing at the car — and the voice key, which is forwarded to [onVoiceKey]
 * rather than interpreted here. Media keys still belong to a `MediaSession` and
 * are not this class's to claim.
 *
 * The rotary knob is logged for the same reason and cannot do more: turning a
 * detent into focus movement needs to know what is on screen, and
 * `accessibility_service_config.xml` sets `canRetrieveWindowContent="false"` as
 * a promise to the user that Headway injects but does not observe.
 *
 * ## Without the accessibility grant
 *
 * The user may never have enabled [HeadwayAccessibilityService], or may revoke
 * it mid-drive. That is a supported state, not an error: the session stays up,
 * video keeps streaming, and the log says once that touches cannot be injected.
 * Gestures built while there is no dispatcher are dropped and the builder is
 * cancelled with them, because a later slice of a drag is a `continueStroke` on
 * a stroke the platform never saw.
 */
class CarInputStream(
    private val channel: InputChannel,
    /**
     * The demultiplexer's view of the same channel. Held separately from
     * [channel] because the reading strategy above needs the raw message before
     * [InputChannel.handle] decodes it.
     */
    private val view: MessageChannel,
    private val transform: TouchTransform,
    /** Exactly the keycodes the head unit advertised. See the class KDoc. */
    private val keycodes: List<Int>,
    private val builder: GestureBuilder,
    /**
     * Called when the steering-wheel voice key is released.
     *
     * A callback rather than a direct reference to the voice stream because the
     * two are built in sequence by the session and input comes first; a lambda
     * closing over the later one keeps the ordering harmless.
     */
    private val onVoiceKey: () -> Unit = {},
    /**
     * Where a touch goes when Headway owns the car display.
     *
     * Returns true when it took the touch, in which case none of the transform,
     * gesture-building or accessibility machinery below runs. That whole chain
     * exists to aim a synthetic gesture at *somebody else's* window on the
     * phone's display; when the target is Headway's own window on a display the
     * size of the car's panel, the car's coordinates are already the right
     * coordinates and the event can simply be dispatched. See `CarSurface`.
     */
    private val deliverDirect: (CarInputEvent.Touch) -> Boolean = { false },
    private val onStep: (String) -> Unit = {},
) {

    private val jobs: MutableList<Job> = mutableListOf()

    /**
     * Set from the platform's `onInterrupt` callback, which arrives on the main
     * thread while this class's reader runs elsewhere.
     *
     * [GestureBuilder] is documented as not thread safe, so the callback cannot
     * cancel it directly; it raises this flag instead and the reader acts on it
     * before the next event. Input arrives continuously during a gesture, so the
     * delay is one event, and the alternative — touching the builder from two
     * threads — corrupts the very chain the cancellation exists to protect.
     */
    private val interrupted = AtomicBoolean(false)

    @Volatile
    private var accessibilityAvailable: Boolean = false

    @Volatile
    private var gesturesSubmitted: Long = 0L

    @Volatile
    private var gesturesDropped: Long = 0L

    @Volatile
    private var barTouches: Long = 0L

    @Volatile
    private var keyEvents: Long = 0L

    @Volatile
    private var knobEvents: Long = 0L

    /**
     * Reports and touch events straight off the wire, before any interpretation.
     *
     * These exist because the first real-car session reported zeroes on every
     * other counter, and zeroes could not distinguish "the head unit sent
     * nothing" from "reports arrived and something downstream dropped them all".
     * [barTouches] narrowed it — it counts touches the transform rejects, so a
     * zero there means nothing was decoded — but only these two say whether
     * anything arrived at all.
     */
    @Volatile
    private var reportsReceived: Long = 0L

    @Volatile
    private var touchEventsSeen: Long = 0L

    /** Whether the head unit ever answered the key binding. See [watchKeyBinding]. */
    @Volatile
    private var keyBindingAnswered: Boolean = false

    /** Reports hex-dumped so far; the first few only. */
    @Volatile
    private var reportsDumped: Int = 0

    /** Touches handed straight to the car surface. Diagnostics. */
    @Volatile
    private var directTouches: Long = 0L

    private var announcedTouchpad = false

    /**
     * Binds the advertised keycodes, then reads the channel until [scope] is
     * cancelled or the head unit goes away.
     *
     * @return true, always. The Boolean mirrors
     *   [dev.headway.app.video.CarVideoStream.start], where the head unit can
     *   refuse the stream outright and the session must carry on without it.
     *   Input has no such step: the head unit never refuses the channel, and the
     *   one thing it can refuse — a key binding — is answered asynchronously on
     *   the channel itself and reported to the log there. Reporting a refusal
     *   here would mean waiting for that answer before touch could start, which
     *   would cost the driver the first seconds of the session for nothing.
     */
    suspend fun start(scope: CoroutineScope): Boolean {
        onStep("input: $transform")
        if (transform.pillarboxed || transform.letterboxed) {
            // Worth stating outright: on a portrait phone and a landscape panel
            // most of the car's screen is inert bar, and a driver who has not
            // read that will report the far side of the screen as broken.
            onStep(
                ("input: the mirrored image occupies %.0fx%.0f of the car's %dx%d screen; " +
                    "touches outside it do nothing").format(
                    transform.contentRect.width,
                    transform.contentRect.height,
                    transform.carWidth,
                    transform.carHeight,
                )
            )
        }

        // Sent unconditionally, empty list included. `KeyBindingRequest` is not
        // a key-only negotiation: openauto reaches `inputDevice_->start()` from
        // nowhere but its binding handler
        // (`openauto/openauto/Service/InputService.cpp` L118-L121), so a phone
        // that never asks receives no `InputReport` at all -- touch included.
        // An empty list still answers OK, because the validation loop at L103-L113
        // iterates `scan_codes_size()` and never runs.
        //
        // The list is exactly what was advertised: openauto rejects the whole
        // request on the first keycode it never offered, so a superset binds
        // nothing.
        channel.requestKeyBinding(keycodes)
        onStep(
            if (keycodes.isEmpty()) {
                "input: the head unit advertised no keycodes; binding an empty set, which is " +
                    "what starts the input device sending touches"
            } else {
                "input: asked to bind ${keycodes.size} advertised keycode(s)"
            }
        )

        jobs += scope.launch { watchAccessibility() }
        jobs += scope.launch { watchKeyBinding() }
        jobs += scope.launch { read() }
        onStep("input stream started")
        return true
    }

    /**
     * Says so, once, if the head unit never answers the key binding.
     *
     * openauto reaches `inputDevice_->start()` from exactly one place — its
     * binding handler, under `if(status == OK)`
     * (`openauto/openauto/Service/InputService.cpp` L118-L121) — and until then
     * `eventHandler_` is null and every touch is dropped before it reaches the
     * wire. So an unanswered binding is not a lost keyboard: it is a head unit
     * that will never send a single touch.
     *
     * Nothing said so before. A silent head unit and a head unit that answered
     * "bound" and then sent nothing produced identical logs, and those two have
     * completely different causes.
     */
    private suspend fun watchKeyBinding() {
        delay(KEY_BINDING_TIMEOUT_MILLIS)
        if (keyBindingAnswered) return
        onStep(
            "input: the head unit has not answered the KeyBindingRequest after " +
                "${KEY_BINDING_TIMEOUT_MILLIS / 1000} s. Per openauto it starts its input " +
                "device only when it answers, so no touch will arrive until it does"
        )
    }

    /** Gestures injected and touches discarded, for the log. */
    fun describe(): String = "input: $reportsReceived report(s) received, " +
        "$touchEventsSeen touch event(s) in them, " +
        (if (directTouches > 0) "$directTouches delivered to the car display, " else "") +
        "$gesturesSubmitted gesture(s) injected, " +
        "$gesturesDropped dropped with no accessibility grant, " +
        "$barTouches touch(es) in the letterbox bar, " +
        "$keyEvents key event(s), $knobEvents knob event(s); key binding " +
        (if (keyBindingAnswered) "answered" else "UNANSWERED") +
        "; accessibility " +
        (if (accessibilityAvailable) "available" else "not available")

    /**
     * Stops reading and lifts whatever finger the platform still believes is
     * down.
     *
     * The flush matters more than it looks: a session that ends mid-drag leaves
     * the last stroke marked `willContinue`, and the platform holds that finger
     * on whatever app is in front until something else injects. The final
     * gesture is submitted rather than dropped for exactly that reason, and it
     * can be — the dispatcher's worker lives on the accessibility service's own
     * scope, which outlives this session.
     */
    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        runCatching { builder.flush() }.getOrNull()?.let { submit(it) }
        // Leaving a callback pointing at a dead session would raise the
        // interrupt flag of a stream nobody is reading.
        HeadwayAccessibilityService.instance.value?.onInterrupted = {}
    }

    // --- reading -------------------------------------------------------------

    private suspend fun read() {
        try {
            while (currentCoroutineContext().isActive) {
                val message = view.receive()
                // Raised by the platform, consumed here: see [interrupted].
                if (interrupted.compareAndSet(true, false)) {
                    builder.cancel()
                    onStep("input: gesture in progress abandoned after an accessibility interrupt")
                }
                // Before decoding, so a report the decoder rejects is still
                // visible as bytes. A malformed report and no report at all
                // otherwise look the same in the log.
                if (reportsDumped < REPORTS_HEX_DUMPED) {
                    reportsDumped++
                    onStep(
                        "input: raw message 0x%04x (%d bytes) %s".format(
                            message.messageId,
                            message.payload.size,
                            message.payload.take(HEX_LIMIT)
                                .joinToString(" ") { "%02x".format(it) },
                        )
                    )
                }
                try {
                    dispatch(channel.handle(message))
                } catch (e: InputChannelException) {
                    // One malformed report is not a reason to stop reading the
                    // channel: the next one is very likely fine, and a dead input
                    // channel is indistinguishable to the driver from a dead app.
                    onStep("input: ${e.message}")
                } catch (e: RuntimeException) {
                    // Nothing inside that block suspends, so this cannot be a
                    // coroutine cancellation being swallowed — it is a malformed
                    // event, or a platform object refusing to be built from one.
                    // Input must not take the session down with it: video and
                    // audio remain useful without touch, and a crash here reads
                    // to the driver as the car screen going black.
                    onStep("input: ${e.javaClass.simpleName} handling a report: ${e.message}")
                    builder.cancel()
                }
            }
        } catch (e: EOFException) {
            // The ordinary end of a session. The supervisor reconnects; this is
            // not an error and must not be reported as one.
            onStep("input: channel closed (${e.message})")
        }
    }

    private fun dispatch(message: InputChannelMessage) {
        when (message) {
            is InputChannelMessage.Report -> {
                reportsReceived++
                message.events.forEach(::onEvent)
            }

            is InputChannelMessage.KeyBindingResult -> {
                // Recorded whether or not the binding succeeded: what the
                // timeout warning is about is *silence*, and a refusal is an
                // answer. A refusal has its own, different consequence below.
                keyBindingAnswered = true
                onStep(
                    if (message.bound) {
                        "input: the head unit bound every requested keycode"
                    } else {
                        // STATUS_KEYCODE_NOT_BOUND here would mean the unit refused a
                        // code it had itself advertised, which no reference does.
                        "input: key binding refused with status ${message.status}; " +
                            "openauto starts its input device only on a successful bind, so " +
                            "touch may not arrive either"
                    }
                )
            }

            // The phone originates this one, so receiving it means the peer is
            // behaving as a phone. Worth seeing rather than ignoring.
            is InputChannelMessage.KeyBinding -> onStep(
                "input: the peer sent a KeyBindingRequest for ${message.keycodes.size} " +
                    "keycode(s); Headway is the phone and does not answer it"
            )

            is InputChannelMessage.Feedback -> onStep("input: feedback event ${message.event}")

            is InputChannelMessage.Unhandled -> onStep(
                "input: unhandled message 0x%04x (%d bytes)".format(
                    message.messageId, message.payloadSize,
                )
            )
        }
    }

    private fun onEvent(event: CarInputEvent) {
        when (event) {
            is CarInputEvent.Touch -> onTouch(event)
            is CarInputEvent.Key -> onKey(event)
            is CarInputEvent.Relative -> onRelative(event)
            is CarInputEvent.Absolute -> onStep(
                "input: absolute axis ${describeKeycode(event.keycode)} = ${event.value}"
            )
        }
    }

    // --- touch ---------------------------------------------------------------

    private fun onTouch(touch: CarInputEvent.Touch) {
        touchEventsSeen++
        // The car-native path, when there is one. No transform, no letterbox,
        // no accessibility grant: 1:1 into Headway's own view tree.
        if (deliverDirect(touch)) {
            directTouches++
            return
        }
        if (touch.surface != TouchSurface.TOUCHSCREEN && !announcedTouchpad) {
            announcedTouchpad = true
            // GestureBuilder drops these itself; without this line the driver of
            // a touchpad-equipped unit sees a dead pad and no explanation.
            onStep(
                "input: the head unit is sending touchpad events. Those are relative to the " +
                    "pad's own geometry, not positions on the mirrored screen, so Headway " +
                    "maps only the touchscreen"
            )
        }

        val mapped = activeTransform().map(touch)
        if (mapped == null) {
            // A touch in the bar, or a lift with nothing left to deliver. Counted
            // rather than logged: a palm on the blank strip would otherwise fill
            // the ring buffer and push out the reason a session died.
            barTouches++
            return
        }

        val gesture = builder.accept(mapped) ?: return
        submit(gesture)
    }

    /**
     * The transform in force for this touch.
     *
     * Two shapes, and which one applies changes as the driver rearranges the
     * dashboard. When an app pane holds a picture, the app occupies *that
     * rectangle* of the car screen and the map is car frame -> pane -> app
     * display. When none does, the session is on the fallback path where the car
     * shows a raw capture of the phone, and the map is the whole-screen one
     * built at [Companion.of].
     *
     * Memoised on the published rectangle, because a divider drag changes it
     * sixty times a second and building a `TouchTransform` per touch would
     * allocate on the input path for no reason. The rectangle is published by
     * the shell whenever the view tree settles — see `AppPaneHost.pictureRect`.
     */
    private fun activeTransform(): TouchTransform {
        val rect = AppPaneHost.pictureRect
        if (rect.width <= 0 || rect.height <= 0) return transform
        val sourceWidth = AppPaneHost.sourceWidth
        val sourceHeight = AppPaneHost.sourceHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) return transform
        // The source is part of the key, not just the pane: a shared app that
        // rotates or is replaced changes the capture's size and its origin while
        // the pane it draws into stays exactly where it was, and a transform
        // memoised on the pane alone would go on mapping into the old geometry.
        val source = SourceGeometry(
            sourceWidth,
            sourceHeight,
            AppPaneHost.sourceOriginX,
            AppPaneHost.sourceOriginY,
        )
        val cached = paneTransform
        if (cached != null && paneRect == rect && paneSource == source) return cached
        val built = runCatching {
            TouchTransform(
                carWidth = transform.carWidth,
                carHeight = transform.carHeight,
                phoneWidth = sourceWidth,
                phoneHeight = sourceHeight,
                explicitContent = CarRect(
                    left = rect.left.toDouble(),
                    top = rect.top.toDouble(),
                    width = rect.width.toDouble(),
                    height = rect.height.toDouble(),
                ),
                // Non-zero when the driver shared one app rather than the whole
                // display: the capture then starts at the app window's corner,
                // and a gesture is dispatched in screen coordinates. See
                // `AppPaneHost.sourceOriginX`.
                phoneOriginX = AppPaneHost.sourceOriginX.toDouble(),
                phoneOriginY = AppPaneHost.sourceOriginY.toDouble(),
            )
        }.getOrNull() ?: return transform
        paneRect = rect
        paneSource = source
        paneTransform = built
        onStep(
            "input: touches inside the app pane map $rect -> ${sourceWidth}x$sourceHeight " +
                "at ${AppPaneHost.sourceOriginX},${AppPaneHost.sourceOriginY}",
        )
        return built
    }

    /**
     * Whether the missing-grant message has already been shown this session.
     *
     * Once, not per touch: a driver dragging across a dead pane produces a
     * gesture every few milliseconds, and a banner per gesture would be its own
     * fault report.
     */
    private var warnedAboutAccessibility: Boolean = false

    /** The rectangle [paneTransform] was built for. */
    private var paneRect: PaneRect? = null

    /** The capture geometry [paneTransform] was built for. */
    private var paneSource: SourceGeometry? = null
    private var paneTransform: TouchTransform? = null

    /** The half of the mapping that comes from the capture rather than the pane. */
    private data class SourceGeometry(
        val width: Int,
        val height: Int,
        val originX: Int,
        val originY: Int,
    )

    private fun submit(gesture: CarGesture) {
        // Re-read every time: an unbind between two touches is normal (the user
        // opened Settings), and a cached dispatcher belongs to a service the
        // platform no longer knows about.
        val dispatcher = HeadwayAccessibilityService.instance.value?.dispatcher
        if (dispatcher == null || !dispatcher.submit(gesture)) {
            // Said once, on the car screen, the first time a touch is thrown
            // away. This is the single gate the whole app-pane path hangs on,
            // and until now it failed in total silence: the rail, the tabs and
            // the panes all kept working, so a driver saw a dashboard that
            // responded everywhere except inside the app and had nothing
            // anywhere telling them a grant was missing.
            if (dispatcher == null && !warnedAboutAccessibility) {
                warnedAboutAccessibility = true
                onStep(
                    "input: a touch reached the app pane but Headway's accessibility service " +
                        "is not bound, so it was dropped. Enable Headway under Settings > " +
                        "Accessibility. On Android 13 and later a sideloaded app is behind " +
                        "\"Restricted setting\": App info > three-dot menu > Allow restricted " +
                        "settings, then enable it",
                )
                CarShell.active()?.showVoiceMessage("Touch needs the Car touchscreen grant")
            }
            gesturesDropped++
            // Whatever comes next in this chain would be a continueStroke on a
            // stroke the platform never received, so the chain ends here. The
            // next finger down starts a fresh one and recovers on its own.
            builder.cancel()
            return
        }
        gesturesSubmitted++
    }

    // --- keys and the knob ---------------------------------------------------

    private fun onKey(key: CarInputEvent.Key) {
        keyEvents++
        // Logged on press and release, with the raw code, because identifying a
        // real car's steering-wheel buttons from a single drive's log is the
        // whole point of Phase 3 on hardware.
        onStep(
            "input: key ${describeKeycode(key.keycode)} ${if (key.down) "down" else "up"}" +
                (if (key.longPress) " (long)" else "") +
                (if (key.metaState != 0) " meta=0x%02x".format(key.metaState) else "")
        )

        // On release only: press and release arrive as separate reports, and
        // acting on both would fire everything twice.
        if (key.down) return

        when (key.keycode) {
            InputKeyCodes.BACK -> {
                val service = HeadwayAccessibilityService.instance.value ?: return
                val performed = runCatching {
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                }.getOrDefault(false)
                if (!performed) onStep("input: the platform refused the back action")
            }

            // The steering-wheel voice button, if this car sends it. Free to
            // wire and the best possible trigger — hands stay on the wheel —
            // but not something to depend on: whether the code ever arrives
            // depends on the unit advertising it in keycodes_supported, which
            // `input advertised:` at the top of the log now records.
            InputKeyCodes.SEARCH -> {
                onStep("input: the voice key was pressed")
                onVoiceKey()
            }

            else -> Unit
        }
    }

    private fun onRelative(event: CarInputEvent.Relative) {
        knobEvents++
        // Log only. Turning a detent into focus movement requires knowing what is
        // on screen, and this app's accessibility service is configured without
        // window-content access on purpose.
        onStep(
            if (event.isRotary) {
                "input: rotary knob ${if (event.delta > 0) "+" else ""}${event.delta} " +
                    "(no mapping: focus navigation needs window content, which Headway " +
                    "deliberately cannot read)"
            } else {
                "input: relative axis ${describeKeycode(event.keycode)} delta ${event.delta}"
            }
        )
    }

    // --- the accessibility grant ---------------------------------------------

    /**
     * Follows the grant for the life of the session.
     *
     * A `StateFlow` rather than a one-off check because the user may enable the
     * service after the car link is already up — which is the common case the
     * first time, since the settings screen is where the app sends them — and a
     * session that had checked once at start-up would stay mute until the next
     * reconnect. It emits its current value immediately, so the "not enabled"
     * line is logged once at start and only again on a real transition.
     */
    private suspend fun watchAccessibility() {
        HeadwayAccessibilityService.instance.collect { service ->
            accessibilityAvailable = service != null
            if (service == null) {
                onStep(
                    "input: the accessibility service is not enabled, so car touches cannot be " +
                        "injected. The session stays up and everything else keeps working; " +
                        "enable Headway under Settings > Accessibility to use the car's screen"
                )
                return@collect
            }
            service.onInterrupted = { interrupted.set(true) }
            onStep("input: accessibility service available; car touches will be injected")
        }
    }

    companion object {

        /**
         * How long to wait for a `KeyBindingResult` before saying it never came.
         *
         * Generous on purpose. This is a diagnostic, not a deadline — nothing
         * is retried or abandoned when it fires — so it should only speak when
         * the answer is genuinely absent rather than merely slow.
         */
        const val KEY_BINDING_TIMEOUT_MILLIS: Long = 10_000

        /** Reports hex-dumped at the top of a session. */
        private const val REPORTS_HEX_DUMPED = 3

        /** Bytes shown per dumped report; a touch report is far shorter than this. */
        private const val HEX_LIMIT = 48

        /**
         * Finds the head unit's input service by content, not by channel id.
         *
         * A touchscreen is the requirement: a unit that advertises an input
         * service with keys only has nothing for the transform to map, and
         * building one from a zero-sized panel would throw.
         */
        fun inputServiceOf(profile: HeadUnitProfile): ServiceOuterClass.Service? =
            profile.services.firstOrNull {
                it.hasInputSourceService() && it.inputSourceService.touchscreenCount > 0
            }

        /**
         * Builds the stream for a profile, or null when the car offers no
         * touchscreen or the phone will not say how large its display is.
         *
         * @param context any application-lifetime context; used for the device's
         *   real touch slop and its display size, never retained for UI.
         */
        fun of(
            profile: HeadUnitProfile,
            connectionFor: (Int) -> MessageChannel,
            context: Context,
            onVoiceKey: () -> Unit = {},
            deliverDirect: (CarInputEvent.Touch) -> Boolean = { false },
            onStep: (String) -> Unit = {},
        ): CarInputStream? {
            val service = inputServiceOf(profile) ?: return null
            val input = service.inputSourceService

            // Echoed verbatim, before anything can fail. Everything downstream
            // is derived from these four facts, and the first real-car session
            // recorded none of them -- so a log showing no touches could not say
            // whether the panel geometry was sane or whether this unit even
            // offers the voice key that would trigger a microphone session.
            onStep(
                "input advertised: channel ${service.id}, ${input.touchscreenCount} touchscreen(s) " +
                    input.touchscreenList.joinToString(prefix = "[", postfix = "]") {
                        "${it.width}x${it.height}${if (it.isSecondary) " secondary" else ""}"
                    } +
                    ", ${input.keycodesSupportedCount} keycode(s) " +
                    input.keycodesSupportedList.joinToString(prefix = "[", postfix = "]")
            )

            val panel = primaryTouchscreen(input) ?: return null

            // Where the driver's apps are actually drawn. Normally the
            // phone's own screen, which the car mirrors; when Headway is
            // rendering apps on a simulated secondary display (ADR 0008), that
            // display instead -- and the difference is the whole transform,
            // because a 720x480 target maps into an 800x480 panel one to one
            // while a 1080x2404 one is squeezed into a fifth of the width.
            val appDisplay = CarAppDisplay.active
            val phone = appDisplay?.let { it.width to it.height } ?: phoneDisplaySize(context)
            if (phone == null) {
                onStep("input: the platform reported no display size, so touches cannot be mapped")
                return null
            }
            if (appDisplay != null) {
                onStep("input: touches will be aimed at $appDisplay, not at the phone screen")
            }

            val car = carSurfaceOf(profile, panel, onStep)
            if (car.first <= 0 || car.second <= 0) {
                // TouchTransform requires positive dimensions and says so with an
                // exception. Refusing here keeps a head unit that advertises a
                // nonsensical panel from taking the whole session down with it —
                // video is still worth having on a car whose touch is unusable.
                onStep(
                    "input: the head unit advertised a ${car.first}x${car.second} touch surface, " +
                        "which cannot be mapped; touch is disabled for this session"
                )
                return null
            }
            val transform = TouchTransform(car.first, car.second, phone.first, phone.second)

            val view = connectionFor(service.id)
            return CarInputStream(
                channel = InputChannel(view, service.id, onStep),
                view = view,
                transform = transform,
                keycodes = input.keycodesSupportedList.toList(),
                // The gesture has to name the display too. A perfectly
                // transformed touch dispatched to display 0 while the driver is
                // looking at display 1 lands on whatever happens to be on the
                // phone, which is both useless and alarming.
                builder = GestureBuilder(
                    GestureConfig.forDevice(context).copy(displayId = CarAppDisplay.displayId),
                ),
                onVoiceKey = onVoiceKey,
                deliverDirect = deliverDirect,
                onStep = onStep,
            )
        }

        /**
         * The panel the driver actually touches.
         *
         * `is_secondary` marks a rear-seat or passenger screen
         * (`InputSourceService.proto` L12-L17); mirroring is a single-display
         * feature, so the primary one wins and the first entry is the fallback
         * for a unit that flags none of them.
         */
        private fun primaryTouchscreen(
            input: InputSourceService,
        ): InputSourceService.TouchScreen? =
            input.touchscreenList.firstOrNull { !it.isSecondary } ?: input.touchscreenList.firstOrNull()

        /**
         * The coordinate space the head unit sends touches in.
         *
         * See the class KDoc: the advertised video resolution is correct, the
         * panel geometry is the fallback, and a disagreement between them is
         * worth a line in the log because it is otherwise invisible — every touch
         * simply lands slightly wrong.
         */
        private fun carSurfaceOf(
            profile: HeadUnitProfile,
            panel: InputSourceService.TouchScreen,
            onStep: (String) -> Unit,
        ): Pair<Int, Int> {
            val panelSize = panel.width to panel.height
            val video = advertisedVideoSize(profile)
            if (video == null) {
                onStep(
                    "input: mapping touches against the advertised ${panelSize.first}x" +
                        "${panelSize.second} touch panel; the head unit offers no single " +
                        "unambiguous video resolution to prefer"
                )
                return panelSize
            }
            if (video != panelSize) {
                onStep(
                    "input: the head unit advertises a ${video.first}x${video.second} video sink " +
                        "but a ${panelSize.first}x${panelSize.second} touch panel. Using the " +
                        "video resolution: the unit rescales panel coordinates into display " +
                        "geometry before sending them (openauto InputDevice.cpp L391-L392)"
                )
            }
            return video
        }

        /**
         * The one resolution every advertised H.264 sink agrees on, or null.
         *
         * Deliberately not "the first one": which configuration the video channel
         * ends up starting is negotiated at run time and this class never sees the
         * answer, so a unit offering several sizes gives no basis for a choice and
         * the panel geometry is the safer guess.
         */
        private fun advertisedVideoSize(profile: HeadUnitProfile): Pair<Int, Int>? =
            profile.services
                .filter {
                    it.hasMediaSinkService() &&
                        it.mediaSinkService.availableType == MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP
                }
                .flatMap { it.mediaSinkService.videoConfigsList }
                .filter { it.hasCodecResolution() }
                .mapNotNull { VideoResolution.of(it.codecResolution) }
                .map { it.width to it.height }
                .distinct()
                .singleOrNull()

        /**
         * The phone's full display, in its current rotation.
         *
         * `maximumWindowMetrics` rather than the deprecated `Display.getSize` or
         * an activity's own bounds: what is mirrored is the whole display, not a
         * window, and this is the API that reports the display's bounds from a
         * `Service` context. `resources.displayMetrics` is the fallback for a
         * context the window manager will not serve; it can be smaller than the
         * display on a device with a persistent system bar inset, which would
         * shift the transform, so it is a last resort rather than a preference.
         */
        private fun phoneDisplaySize(context: Context): Pair<Int, Int>? {
            val bounds = runCatching {
                context.getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds
            }.getOrNull()
            if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                return bounds.width() to bounds.height()
            }
            val metrics = context.resources.displayMetrics
            if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                return metrics.widthPixels to metrics.heightPixels
            }
            return null
        }

        /**
         * A readable name for a keycode, falling back to the raw number.
         *
         * The named set is the short list [InputKeyCodes] documents rather than a
         * transcription of all 270-odd `KeyCode` values; anything else is printed
         * as a number, which is exactly what is needed to identify an unknown
         * steering-wheel button from a log.
         */
        fun describeKeycode(keycode: Int): String = when (keycode) {
            InputKeyCodes.HOME -> "HOME"
            InputKeyCodes.BACK -> "BACK"
            InputKeyCodes.CALL -> "CALL"
            InputKeyCodes.ENDCALL -> "ENDCALL"
            InputKeyCodes.DPAD_UP -> "DPAD_UP"
            InputKeyCodes.DPAD_DOWN -> "DPAD_DOWN"
            InputKeyCodes.DPAD_LEFT -> "DPAD_LEFT"
            InputKeyCodes.DPAD_RIGHT -> "DPAD_RIGHT"
            InputKeyCodes.DPAD_CENTER -> "DPAD_CENTER"
            InputKeyCodes.SEARCH -> "SEARCH/VOICE"
            InputKeyCodes.MEDIA_PLAY_PAUSE -> "MEDIA_PLAY_PAUSE"
            InputKeyCodes.MEDIA_NEXT -> "MEDIA_NEXT"
            InputKeyCodes.MEDIA_PREVIOUS -> "MEDIA_PREVIOUS"
            InputKeyCodes.MEDIA_PLAY -> "MEDIA_PLAY"
            InputKeyCodes.MEDIA_PAUSE -> "MEDIA_PAUSE"
            InputKeyCodes.ROTARY_CONTROLLER -> "ROTARY_CONTROLLER"
            InputKeyCodes.MEDIA -> "MEDIA"
            InputKeyCodes.NAVIGATION -> "NAVIGATION"
            InputKeyCodes.TEL -> "TEL"
            else -> "keycode $keycode"
        }
    }
}

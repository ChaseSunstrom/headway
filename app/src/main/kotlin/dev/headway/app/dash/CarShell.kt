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

import android.app.Presentation
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.headway.app.carapp.CarHostCapability
import dev.headway.app.carapp.TemplateApps
import dev.headway.app.dash.tiles.AppPaneTile
import dev.headway.app.dash.tiles.CarAppTile
import dev.headway.app.dash.tiles.CarStyle
import dev.headway.app.dash.tiles.ClockTile
import dev.headway.app.dash.tiles.LauncherTile
import dev.headway.app.dash.tiles.MapsTile
import dev.headway.app.dash.tiles.MediaBrowseTile
import dev.headway.app.dash.tiles.MessagesTile
import dev.headway.app.dash.tiles.CarNotice
import dev.headway.app.dash.tiles.HeadwayNotificationListener
import dev.headway.app.dash.tiles.NotificationsTile
import dev.headway.app.dash.tiles.appIcon
import dev.headway.app.dash.tiles.NowPlayingTile
import dev.headway.app.dash.tiles.PhoneTile
import dev.headway.app.dash.tiles.SensorsTile
import dev.headway.app.dash.tiles.WidgetSetup
import dev.headway.app.dash.tiles.WidgetTile
import dev.headway.app.input.HeadwayAccessibilityService
import dev.headway.app.service.HeadwayService
import dev.headway.app.ui.HeadwaySettings
import dev.headway.app.ui.theme.CarMetrics
import dev.headway.app.ui.theme.Headway
import dev.headway.app.ui.theme.HeadwayTheme
import dev.headway.app.video.AppPaneHost
import dev.headway.app.video.CarAppDisplay
import dev.headway.app.phone.CarPhone
import dev.headway.app.phone.LiveCall
import dev.headway.app.video.OverlayDisplay
import dev.headway.app.video.PhoneRotation
import dev.headway.dash.CarUiScale
import dev.headway.dash.CornerStyle
import dev.headway.dash.CarUnits
import dev.headway.dash.DashLayout
import dev.headway.dash.DashNode
import dev.headway.dash.DashPath
import dev.headway.dash.OverlaySpot
import dev.headway.dash.PaneKind
import dev.headway.dash.PaneRect
import dev.headway.dash.Rail
import dev.headway.dash.RailEdge
import dev.headway.dash.RailItem
import dev.headway.dash.RailStyle
import dev.headway.dash.TabIcon
import dev.headway.dash.ThemeAccent
import dev.headway.dash.ThemeBase

/**
 * The car screen: a rail, a stage, and whatever the driver put on it.
 *
 * ## What is on it, and what is deliberately not
 *
 * Across the top: **settings, the microphone, and the driver's pinned items.**
 * That is the whole rail. The five tabs Headway used to ship — Maps, Media,
 * Phone, Car apps, Messages, Apps — were five arrangements *Headway* chose, and
 * a driver who used two of them still paid six touch targets across an
 * 800-pixel panel for the other four. A pinned layout is a tab; a pinned app
 * opens in the app pane; both are the driver's choice and both live in the same
 * ordered list (`Rail`).
 *
 * Below it: the pane tree, which may be any shape and any depth, editable here —
 * on the surface the driver is actually looking at — and locked by default so a
 * thumb steadying itself on a dashboard cannot rearrange it.
 *
 * ## Why this is a `Presentation`
 *
 * It is the one window type that can reach a virtual display an unprivileged app
 * created. `ActivityTaskSupervisor.isCallerAllowedToLaunchOnDisplay` turns away
 * the *first* activity onto an untrusted display — the `ACTIVITY_EMBEDDING` gate
 * is waived only once the caller already has an activity there, which on a
 * freshly created display is never true. A `Presentation` is a `Dialog`: a window
 * added through `WindowManager` on a display this process owns, which never
 * enters the activity-launch path. ADR 0004 Finding 4 has the derivation.
 *
 * ## Why it is laid out in car pixels
 *
 * The display is created at the head unit's advertised resolution and density, so
 * this window's own pixels *are* car pixels. Every size comes from [CarMetrics],
 * which computes 48 dp at the car's density rather than the phone's.
 *
 * ## Touch
 *
 * No transform and no accessibility service for anything Headway draws:
 * `CarSurface.deliver` synthesises a `MotionEvent` at the car's own coordinates
 * and dispatches it into this window. The exception is the live app pane, whose
 * picture belongs to another app on another display — [appPictureRect] is how the
 * router knows where that rectangle is, and everything inside it is forwarded
 * rather than dispatched. See ADR 0010.
 */
class CarShell(
    outerContext: Context,
    display: Display,
    private val metrics: CarMetrics,
    private val onStep: (String) -> Unit = {},
) : Presentation(outerContext, display) {

    private lateinit var root: FrameLayout
    private lateinit var column: LinearLayout
    private lateinit var railItems: LinearLayout
    private lateinit var stage: FrameLayout
    private lateinit var overlayHost: FrameLayout

    /** Where a floating card lives. See its construction in [onCreate]. */
    private lateinit var floats: FrameLayout
    private lateinit var banners: FrameLayout
    private lateinit var micButton: CarGlyphButton

    private val store: DashLayoutStore by lazy { DashLayoutStore.of(context) }
    private val main = Handler(Looper.getMainLooper())

    private var tabs: List<DashLayout> = DashLayoutStore.DEFAULT_TABS
    private var layout: DashLayout = DashLayoutStore.DEFAULT
    private var tree: PaneTreeView? = null
    private var live = mutableListOf<DashTile>()

    /** True while the driver is rearranging panes. Always false for a locked layout. */
    private var editing = false

    /** The app pane holding the picture, of however many the layout has. */
    private var liveAppPane: AppPaneTile? = null

    private var startup: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(context).apply { setBackgroundColor(Headway.GROUND) }
        column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }
        stage = FrameLayout(context)
        // Above the panes, below the sheets, and never clickable: a banner that
        // took touches would be a target the driver could hit by accident while
        // it was fading.
        banners = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            isClickable = false
            isFocusable = false
        }
        overlayHost = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            visibility = View.GONE
        }
        root.addView(column)
        // Between the panes and the sheets, and non-modal on purpose.
        //
        // Not `overlayHost`: that one is modal by contract -- `claimsAppTouch`
        // returns false outright while it is visible, so every car touch goes
        // to Headway and the live app pane stops receiving any. A call card
        // floating over a map while the driver keeps using the map must not
        // take that away. `showOverlay` also begins by emptying it, and forty
        // call sites would evict a card mid-ring.
        floats = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            isClickable = false
            isFocusable = false
            visibility = View.GONE
        }
        root.addView(floats)
        // Banners last, above the sheets. They used to be added *below*
        // `overlayHost`, so any message raised while a sheet was open -- which
        // is exactly when the app picker calls back into `openApp` -- was
        // painted behind an opaque full-screen sheet and never seen. The layer
        // is `isClickable = false` with a non-clickable child, so being on top
        // costs the sheet underneath no touches.
        root.addView(overlayHost)
        root.addView(banners)
        setContentView(root)

        // Dialogs dim what is behind them by default. There is nothing behind
        // this one but the display's own black, and the dim costs a full-screen
        // translucent layer in every encoded frame.
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        window?.setLayout(MATCH_PARENT, MATCH_PARENT)
        // A Dialog dismisses itself on BACK and on a touch outside its bounds.
        // Neither is a gesture anyone would make on purpose here, and both end
        // with the car showing a display that has content but no window — a
        // black screen with no way back, mid-drive.
        setCancelable(false)
        setCanceledOnTouchOutside(false)

        // Before anything is drawn: every shape below bakes the radius into a
        // `GradientDrawable` at construction, so a style applied afterwards
        // would leave this session's first screen at the previous one.
        Headway.corners = HeadwaySettings.cornerStyle(context)
        tabs = store.list()
        layout = openingLayout()
        // The active pointer means "what is on the car screen", and `reload()`
        // re-reads it after any edit made from the phone. Leaving it naming the
        // tab the *last* drive ended on would jump this drive back to that tab
        // the first time a divider moved on the phone.
        runCatching { store.setActive(layout.name) }
        releaseOrphanedWidgets()
        layOutRailAndStage()
        showStartup()
        render()

        // The live pane's rectangle is read from the view that is on screen
        // rather than recomputed, so it has to be republished whenever anything
        // moves: a divider drag, a tab switch, a sheet opening over it.
        root.viewTreeObserver.addOnGlobalLayoutListener { publishAppPaneRect() }
    }

    override fun onStart() {
        super.onStart()
        // A shell that has been torn down is finished. Nothing dismisses and
        // re-shows one -- `CarSurface` builds a new shell per session -- and
        // re-registering from a dead instance would put the observers back with
        // no `onStop` to take them out again.
        if (tornDown) return
        DashLayoutStore.observeChanges(layoutsChanged)
        HeadwayTheme.observe(themeChanged)
        AppPaneHost.observe(appPaneChanged)
        // The call card follows the phone for the whole session, not just while
        // a Phone pane happens to be on screen -- a call arrives over whatever
        // tab the driver is looking at, which is the point of it floating.
        CarPhone.observe(callWatcher)
        HeadwayNotificationListener.observeNotices(noticeWatcher)
        shown = this
        startClockTicks()
        live.forEach { runCatching { it.start() } }
        onStep("car screen up: ${describe()}")
    }

    override fun onStop() {
        // No `super.onStop()` here: [teardown] makes that call, so that the
        // paths which reach it *without* a working `onStop` get it too.
        teardown()
    }

    /** True once [teardown] has run, so a second call is free. */
    private var tornDown = false

    /**
     * Everything this shell registered, given back — without needing `onStop`.
     *
     * `onStop` is not a guarantee. `Dialog.show()` runs `dispatchOnCreate()`,
     * then `onStart()`, and only then `WindowManager.addView`, setting
     * `mShowing` last; `dismissDialog()` returns immediately when `mShowing` is
     * false. So a window refused at `addView` -- a display torn down between
     * being created and being shown, which is a car that was switched off
     * mid-bring-up -- leaves a shell that has fully started and can never stop.
     *
     * What that used to leak is not one receiver. It is the three observers
     * `onStart` takes out, the clock's minute tick, every tile's media-session
     * and notification binding, and `shown` still pointing at the dead shell --
     * which `active()` then hands to the touch router and the car-app pane for
     * the life of the process.
     *
     * `super.onStop()` is part of it, and has to be: `Presentation.onStart`
     * registers a `DisplayListener` with `DisplayManager` and `Presentation.onStop`
     * is the only thing that unregisters it. `DisplayManagerGlobal` is a process
     * singleton holding a strong reference, and the listener is a non-static
     * inner class of the `Presentation` — so the shell, its context and its whole
     * view tree are pinned for the life of the process, once per refused
     * bring-up, which reconnection repeats.
     *
     * Idempotent, because the caller that catches a failed `show()` and the
     * `onStop` of a shell that did come up can both reach it. Main-thread only,
     * like every other thing that touches these views: the tiles' `stop()`
     * detach views, and a `requestLayout` from a pool thread throws
     * `CalledFromWrongThreadException` — which the per-tile `runCatching` would
     * swallow, silently skipping the widget host, media session and surface
     * releases that follow it.
     */
    fun teardown() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { teardown() }
            return
        }
        if (tornDown) return
        tornDown = true
        runCatching { CarPhone.unobserve(callWatcher) }
        runCatching { HeadwayNotificationListener.unobserveNotices(noticeWatcher) }
        runCatching { main.removeCallbacks(dismissPopup) }
        DashLayoutStore.unobserveChanges(layoutsChanged)
        HeadwayTheme.unobserve(themeChanged)
        AppPaneHost.unobserve(appPaneChanged)
        // Same reason as the tiles below: a minute-tick receiver left registered
        // outlives the window that used it and keeps firing for the life of the
        // process, with nothing but battery to show for it.
        stopClockTicks()
        if (shown === this) shown = null
        main.removeCallbacksAndMessages(null)
        // Tiles observe media sessions, notification streams and widget hosts.
        // One left running after the window goes is a listener for the life of
        // the process, and the only symptom is battery.
        live.forEach { runCatching { it.stop() } }
        AppPaneHost.pictureRect = EMPTY_RECT
        // Last, and the reason this is not simply the old `onStop` body: it is
        // what gives the platform's display listener back. Called directly
        // rather than inside a `runCatching`, because Kotlin forbids a `super`
        // call from a lambda; it is safe uncaught, since
        // `unregisterDisplayListener` no-ops on a listener that was never
        // registered and `Dialog.onStop` is empty.
        super.onStop()
    }

    // --- what the outside world asks of the shell -----------------------------

    /**
     * Where the live app's picture is, in car coordinates; empty when none.
     *
     * The touch router compares against this before dispatching: inside it, the
     * touch belongs to another app on another display and is forwarded; outside
     * it, the touch is Headway's own and goes into this view tree.
     */
    fun appPictureRect(): PaneRect = liveAppPane?.pictureRect() ?: EMPTY_RECT

    /**
     * Whether a car touch at ([x], [y]) belongs to the app rather than to Headway.
     *
     * Answered from the *published* rectangle rather than by re-reading the
     * view, so that this and the touch router cannot disagree. They are two
     * halves of one decision — this one routes the event, `CarInputStream` maps
     * it — and if the pane has just moved, a fresh answer here against a stale
     * transform there sends the touch to the app at the wrong coordinates,
     * which is worse than either being a frame behind together.
     */
    fun claimsAppTouch(x: Int, y: Int): Boolean {
        if (editing) return false
        if (overlayHost.visibility == View.VISIBLE) return false
        val rect = AppPaneHost.pictureRect
        return rect.width > 0 && rect.contains(x, y)
    }

    /**
     * Opens [packageName] on the app display and brings it into a pane.
     *
     * Called by the rail, by the app grid, and by the voice command engine. If
     * the layout on screen has no app pane, the one that is nothing but an app
     * pane is brought up — which is what "full screen" means now that there is
     * no full-screen mode.
     */
    fun openApp(packageName: String) {
        if (!HeadwaySettings.allowsApp(context, packageName)) {
            // Every route in -- the rail, the grid, a maps hand-off, a voice
            // command -- lands here, so this is the one place the rule has to
            // hold. A pinned button for an app whose permission was withdrawn
            // must not still work.
            onStep("car screen: $packageName is not allowed on the car screen")
            showVoiceMessage("Allow this app in Headway on the phone first")
            return
        }
        val pane = liveAppPane
        if (pane == null) {
            val appLayout = tabs.firstOrNull { it.leaves().any { leaf -> PaneKind.isApp(leaf.kind) } }
                ?: makeAppLayout()
            if (appLayout == null) {
                onStep("no layout has an app pane and one could not be made for $packageName")
                showVoiceMessage("Could not make a panel to open it in")
                return
            }
            if (appLayout.name != layout.name) show(appLayout)
        }
        val opened = OverlayDisplay.launch(context, packageName, CarAppDisplay.displayId, onStep)
        // Every branch out of here now says something on the *car* screen. It
        // used to say everything through `onStep`, which is the session log --
        // so a driver whose tap did nothing visible had no way to find out why,
        // and after the first tap the layout is already the app layout, so
        // there is not even a switch to notice. A driver reported it as pinned
        // apps doing nothing at all.
        if (!opened) {
            showVoiceMessage("That app would not open")
            return
        }
        if (!AppPaneHost.available) {
            // The common case on a default install, and the one that looks most
            // like a broken button: the app really did launch, on the phone,
            // and the pane has no screen-sharing grant with which to show it.
            showVoiceMessage("Opened on the phone — allow screen sharing to see it here")
        }
        // The pane may only have been created a moment ago by show(); binding
        // happens when its surface arrives, so nothing more is needed here.
        main.post { publishAppPaneRect() }
    }

    /**
     * The full-screen App layout, made on demand when no layout has an app pane.
     *
     * `DashLayoutStore.APP_LAYOUT_NAME` has always been documented as "the
     * layout an app opens into when the one on screen has no app pane", and it
     * ships in `DEFAULT_TABS` -- but a driver who edits their layouts replaces
     * that whole set, and nothing put it back. Tapping a pinned app then hit a
     * refusal instead of the thing the constant exists to guarantee, which a
     * driver reported as the rail saying "no app pane to open this in" rather
     * than opening the app.
     *
     * Making it is better than refusing, and better than converting a pane the
     * driver is looking at: it is one pane holding the app, which is what
     * "full screen" means here, and it can be renamed, re-split or unpinned
     * like any other layout.
     *
     * @return the layout, or null when it could not be saved -- in which case
     *   the caller has something to say rather than a silent failure.
     */
    private fun makeAppLayout(): DashLayout? {
        val made = DashLayout(
            name = DashLayoutStore.APP_LAYOUT_NAME,
            root = DashNode.Leaf(PaneKind.APP),
        )
        val saved = runCatching { store.save(made) }.isSuccess
        if (!saved) return null
        onStep("no layout had an app pane, so the '${made.name}' layout was created")
        tabs = runCatching { store.list() }.getOrDefault(tabs + made)
        return tabs.firstOrNull { it.name == made.name } ?: made
    }

    /** Back to the layout a session opens on. Bound to the voice "go home" command. */
    fun goHome() {
        closeOverlay()
        if (editing) setEditing(false)
        val home = tabs.firstOrNull { it.name == DashLayoutStore.DEFAULT_NAME } ?: tabs.firstOrNull()
        home?.let { show(it) }
    }

    /**
     * Says something on the car screen for a couple of seconds.
     *
     * The voice pipeline's only outputs used to be the session log and a spoken
     * reply, neither of which tells a driver in a moving car whether the
     * microphone heard them. This is what "it works" looks like from the seat:
     * the button fills, a line appears, and what was heard is quoted back.
     */
    fun showVoiceMessage(text: String) {
        main.post { runCatching { banner(text) } }
    }

    private fun banner(text: String) {
        if (!::banners.isInitialized) return
        // Above the startup view too, which is added to `root` after the layers
        // were ordered in `onCreate`. Cheap, and it means a message raised in
        // the first seconds of a session is not the one nobody sees.
        banners.bringToFront()
        banners.removeAllViews()
        val card = Headway.title(context, metrics.unit, text).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(Headway.ON_ACCENT)
            val pad = metrics.gutter
            setPadding(pad * 2, pad, pad * 2, pad)
            background = Headway.panel(
                radiusPx = metrics.touchTargetPx / 2f,
                fill = Headway.ACCENT,
                stroke = null,
            )
            layoutParams = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                bottomMargin = metrics.gutter * 2
                leftMargin = metrics.gutter
                rightMargin = metrics.gutter
            }
        }
        banners.addView(card)
        Headway.revealIn(card)
        main.postDelayed(
            {
                runCatching {
                    card.animate()
                        .alpha(0f)
                        .setDuration(Headway.SETTLE_MILLIS)
                        .withEndAction { runCatching { banners.removeView(card) } }
                        .start()
                }
            },
            BANNER_MILLIS,
        )
    }

    /** Reflects the voice pipeline's state in the rail's microphone. */
    fun setListening(listening: Boolean) {
        if (!::micButton.isInitialized) return
        main.post {
            micButton.setGlyph(
                if (listening) CarGlyph.MIC_LISTENING else CarGlyph.MIC,
                active = listening,
            )
        }
    }

    fun describe(): String = buildString {
        append(metrics.describe())
        append("; ")
        append(layout.describe())
        if (editing) append(" (editing)")
        append("; ")
        append(HeadwayTheme.describe())
        append("; ")
        append(AppPaneHost.describe())
    }

    // --- the rail -------------------------------------------------------------

    /**
     * Where the rail goes, read once when the shell is built.
     *
     * Not per-frame: moving the rail rebuilds the car screen anyway, and a value
     * that could change between `onCreate` and `buildRail` would let the column's
     * orientation and the rail's own disagree.
     */
    private var railStyle: RailStyle = RailStyle.DEFAULT

    /**
     * [metrics] at the rail's chosen size — what every control on the rail uses.
     *
     * The size setting used to move only the rail's thickness, which on a top or
     * bottom rail did nothing at all (that edge sizes itself to its contents)
     * and on a side rail was worse than nothing: below about 1.45 the rail came
     * out *narrower than its own buttons* and clipped Settings and Voice off the
     * edge of the screen. Scaling the metrics instead moves the buttons, the
     * pills, the icons, the clock and the thickness together, so the setting
     * means one thing on all four edges and cannot produce a rail too small for
     * what is in it. `CarMetrics.scaled` keeps the 44-pixel touch floor.
     */
    private var railMetrics: CarMetrics = metrics

    /**
     * Puts the rail and the stage into the column, in the order the edge implies.
     *
     * Both the column's orientation and the rail's own follow the same setting,
     * so they are decided in one place: a side rail makes the column horizontal,
     * a top or bottom rail makes it vertical, and TOP/LEFT put the rail first.
     *
     * Called again when the driver moves the rail. That is a rebuild rather than
     * a re-render because the edge changes the window's whole layout, but it is
     * a rebuild of the *content* -- the `Presentation` and the car display are
     * untouched, so the car never loses its picture for it.
     */
    private fun layOutRailAndStage() {
        railStyle = HeadwaySettings.railStyle(context)
        val style = railStyle
        railMetrics = metrics.scaled(style.effectiveScale)
        // The rail about to be discarded may carry a clock, whose receiver is
        // registered against this window rather than against the view.
        stopClockTicks()
        clockPaint = null
        column.removeAllViews()
        (stage.parent as? ViewGroup)?.removeView(stage)
        column.orientation = if (style.edge.vertical) {
            LinearLayout.HORIZONTAL
        } else {
            LinearLayout.VERTICAL
        }
        val rail = buildRail()
        val stageParams = if (style.edge.vertical) {
            LinearLayout.LayoutParams(0, MATCH_PARENT, 1f)
        } else {
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        }
        if (style.edge == RailEdge.TOP || style.edge == RailEdge.LEFT) {
            column.addView(rail)
            column.addView(stage, stageParams)
        } else {
            column.addView(stage, stageParams)
            column.addView(rail)
        }
    }

    /**
     * Settings, the microphone, and the pins.
     *
     * The first two are fixtures at a fixed position, because a control that
     * moves with configuration is a control the driver has to look for. The pins
     * scroll, because six is comfortable on an 800-pixel panel and the driver is
     * allowed a seventh.
     */
    private fun buildRail(): View {
        val style = railStyle
        val m = railMetrics
        val vertical = style.edge.vertical
        val bar = LinearLayout(context).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (vertical) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
            val pad = m.gutter / 2
            setPadding(pad, pad, pad, if (vertical) pad else pad / 2)
            layoutParams = if (vertical) {
                // Wide enough for a button and its margins, by construction
                // rather than by a number that has to be kept in step with them.
                LinearLayout.LayoutParams(railThicknessPx(m), MATCH_PARENT)
            } else {
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            }
        }
        bar.addView(
            CarGlyphButton(context, m, CarGlyph.SETTINGS, "Settings") { showSettings() },
        )
        micButton = CarGlyphButton(context, m, CarGlyph.MIC, "Voice") {
            val request = onVoiceRequested
            if (request == null) {
                // Never nothing. A button that does nothing when pressed is
                // indistinguishable from a car screen that has frozen, and this
                // is the one control a driver presses without looking.
                onStep("voice: the microphone is not available in this session")
                showVoiceMessage("Voice is not available on this connection")
            } else {
                showVoiceMessage("Listening\u2026")
                runCatching { request() }
                    .onFailure { showVoiceMessage("Voice could not start") }
            }
        }
        bar.addView(micButton)

        railItems = LinearLayout(context).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (vertical) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
        }
        // A side rail scrolls down rather than across, or a driver with six pins
        // on a 480-pixel-tall screen can reach only the first two.
        bar.addView(
            if (vertical) {
                ScrollView(context).apply {
                    isVerticalScrollBarEnabled = false
                    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
                    addView(railItems)
                }
            } else {
                HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                    addView(railItems)
                }
            },
        )
        if (style.showClock) bar.addView(buildRailClock(style))
        fillRail()
        return bar
    }

    /**
     * How thick a side rail has to be to hold one glyph button.
     *
     * `CarGlyphButton` is one touch target square with a `gutter / 3` margin on
     * each side, and the rail adds `gutter / 2` of padding on each side of that.
     * Anything less clips the button rather than shrinking it.
     */
    private fun railThicknessPx(m: CarMetrics): Int =
        m.touchTargetPx + 2 * (m.gutter / 3) + 2 * (m.gutter / 2)

    /**
     * The time at the far end of the rail.
     *
     * It used to be a whole pane, which on an 800x480 screen meant spending a
     * fifth of the dashboard on eight characters. The rail already runs the
     * full length of an edge and already has space after the pins.
     *
     * Builds the views and the repaint; [startClockTicks] is what makes it tick.
     */
    private fun buildRailClock(style: RailStyle): View {
        val m = railMetrics
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val pad = m.gutter / 2
            setPadding(pad, 0, pad, 0)
        }
        // `Headway.title` rather than `CarStyle.label`: CarStyle is internal to
        // the tiles package and the shell has its own idiom, used by the rail's
        // pinned items a few lines below. Sizes come off the rail's own metrics,
        // so the clock grows and shrinks with the size setting like everything
        // else on the rail.
        val unit = m.unit
        val time = Headway.title(context, unit, "").apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        column.addView(time)
        val date = if (style.clockShowsDate) {
            Headway.title(context, (unit * DATE_FRACTION).toInt(), "")
                .apply { setTextColor(Headway.TEXT_MUTED) }
                .also { column.addView(it) }
        } else {
            null
        }
        clockPaint = {
            val now = java.util.Date()
            time.text = android.text.format.DateFormat.getTimeFormat(context).format(now)
            date?.text = android.text.format.DateFormat.getMediumDateFormat(context).format(now)
        }
        clockPaint?.invoke()
        // Registered by [startClockTicks] rather than here. A receiver taken out
        // while the rail is being built belongs to the *window*, not to the view,
        // and the window's guarantee is `onStart`/`onStop` -- so registering at
        // build time left it live for the life of the process on any path where
        // the shell was built and never started.
        startClockTicks()
        return column
    }

    /** Repaints the rail clock. Null when the rail carries no clock. */
    private var clockPaint: (() -> Unit)? = null

    /** Registered only between `onStart` and `onStop`, and only with a clock. */
    private var clockTicks: android.content.BroadcastReceiver? = null

    /**
     * Starts the minute tick, if the rail has a clock and the window is up.
     *
     * `ACTION_TIME_TICK` fires on the minute, which is exactly the resolution a
     * clock without seconds needs, and costs nothing between ticks.
     * `ACTION_TIME_CHANGED` and `ACTION_TIMEZONE_CHANGED` are there so crossing
     * a time zone does not leave the wrong hour on the car screen until the next
     * minute.
     */
    private fun startClockTicks() {
        if (clockTicks != null) return
        val paint = clockPaint ?: return
        if (shown !== this) return
        paint()
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = paint()
        }
        val registered = runCatching {
            context.registerReceiver(
                receiver,
                android.content.IntentFilter(Intent.ACTION_TIME_TICK).apply {
                    addAction(Intent.ACTION_TIME_CHANGED)
                    addAction(Intent.ACTION_TIMEZONE_CHANGED)
                },
            )
            true
        }.onFailure { onStep("car screen: the clock could not be ticked: $it") }
            .getOrDefault(false)
        if (registered) clockTicks = receiver
    }

    /** Stops the minute tick. Safe to call when it was never started. */
    private fun stopClockTicks() {
        clockTicks?.let { receiver ->
            clockTicks = null
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private fun fillRail() {
        railItems.removeAllViews()
        rail().forEach { item ->
            val view = when (item) {
                is RailItem.Layout -> layoutPill(item)
                is RailItem.App -> appButton(item)
            }
            railItems.addView(view)
        }
    }

    private fun rail(): List<RailItem> =
        runCatching { store.rail(installedPackages()) }.getOrDefault(emptyList())

    /** A layout pill on the rail, sized by the rail's own metrics. */
    private fun layoutPill(item: RailItem.Layout): View {
        val selected = item.name == layout.name
        // An icon replaces the *label*, never the name: the layout is still
        // found, activated and pinned by name, and a build that cannot draw the
        // icon a later one saved falls back to the word. See `TabIcon`.
        val icon = tabs.firstOrNull { it.name == item.name }?.icon
        CarGlyph.forTab(icon)?.let { glyph ->
            return CarGlyphButton(
                context,
                railMetrics,
                glyph,
                // Still the name, because this is the only thing a driver using
                // TalkBack or reading the log has to go on once the word is gone.
                item.name,
                active = selected,
            ) { switchTo(item.name) }.apply {
                setOnLongClickListener {
                    showRailItemMenu(item)
                    true
                }
            }
        }
        return Headway.title(context, railMetrics.unit, item.name).apply {
            gravity = Gravity.CENTER
            setTextColor(if (selected) Headway.ON_ACCENT else Headway.TEXT_MUTED)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, railMetrics.unit * RAIL_TEXT)
            minHeight = railMetrics.touchTargetPx
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            val pad = railMetrics.gutter
            setPadding(pad, 0, pad, 0)
            contentDescription = item.name
            background = Headway.panel(
                radiusPx = railMetrics.touchTargetPx / 2f,
                fill = if (selected) Headway.ACCENT else Headway.SURFACE,
                stroke = if (selected) null else Headway.OUTLINE,
            )
            isClickable = true
            setOnClickListener { switchTo(item.name) }
            setOnLongClickListener {
                showRailItemMenu(item)
                true
            }
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                val gap = railMetrics.gutter / 3
                setMargins(gap, gap, gap, gap)
            }
        }
    }

    private fun appButton(item: RailItem.App): View {
        val icon = runCatching {
            context.packageManager.getApplicationIcon(item.packageName)
        }.getOrNull()
        val label = runCatching {
            val manager = context.packageManager
            manager.getApplicationLabel(manager.getApplicationInfo(item.packageName, 0)).toString()
        }.getOrDefault(item.packageName)

        return ImageView(context).apply {
            setImageDrawable(icon)
            contentDescription = label
            val pad = railMetrics.gutter / 3
            setPadding(pad, pad, pad, pad)
            background = Headway.panel(
                radiusPx = railMetrics.touchTargetPx / 2f,
                fill = Headway.SURFACE,
                stroke = Headway.OUTLINE,
            )
            isClickable = true
            setOnClickListener { openApp(item.packageName) }
            setOnLongClickListener {
                showRailItemMenu(item)
                true
            }
            layoutParams = LinearLayout.LayoutParams(
                railMetrics.touchTargetPx,
                railMetrics.touchTargetPx,
            ).apply {
                val gap = railMetrics.gutter / 3
                setMargins(gap, gap, gap, gap)
            }
        }
    }

    // --- the stage ------------------------------------------------------------

    private fun render() {
        // Every tile from the outgoing layout is stopped before the incoming
        // ones are built. A media browser or notification observer left running
        // for a layout nobody is looking at is a binding into another app's
        // process for the rest of the drive.
        live.forEach { runCatching { it.stop() } }
        live.clear()
        liveAppPane = null
        stage.removeAllViews()

        val builder = PaneTreeView(
            // The *scaled* context, and this is the whole of "Panel size".
            //
            // Every tile builds its views from the context `createView` is
            // handed, not from the one its constructor took -- most of them keep
            // only `context.applicationContext` from that -- so passing
            // `paneContext` to the constructors moved nothing at all. This is
            // the one call site that decides what a panel's contents resolve
            // their dp and sp against, because `PaneTreeView.leaf` passes this
            // straight to `tile.createView`.
            //
            // The panel furniture is unaffected on purpose: the radius, the
            // divider and the empty-pane caption all size from `metrics`, which
            // is pixels the head unit negotiated. The frame stays put and what
            // is inside it scales, which is what was asked for.
            context = paneContext,
            metrics = metrics,
            tileFor = ::tileFor,
            onRatioChanged = ::persistRatio,
            onPaneTapped = ::showPaneMenu,
        )
        tree = builder
        val view = builder.build(layout, editing)
        view.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        stage.addView(view)
        live.addAll(builder.tiles)

        // Exactly one app pane may hold the picture; the first one in draw order
        // gets it until the driver taps another.
        val appPanes = live.filterIsInstance<AppPaneTile>()
        liveAppPane = appPanes.firstOrNull()
        appPanes.forEach { it.setLive(it === liveAppPane) }

        Headway.revealIn(view)
        if (isShowing) live.forEach { runCatching { it.start() } }
        main.post { publishAppPaneRect() }
    }

    /**
     * The context every pane's content is built with, at the driver's panel size.
     *
     * One wrapper, and every size on every panel follows it: `CarStyle`'s whole
     * vocabulary is dp and sp literals resolved against whatever context it is
     * handed, so a configuration with a different density moves the text, the
     * rows, the gutters and the corner radii of all of them together with no
     * call site changed. It is the same lever the car-app pane already used for
     * its own chrome, applied where a driver asked for it: "I want to be able
     * to scale all UI in ALL panels, not just the maps one".
     *
     * Rebuilt with the shell rather than cached across sessions, because the
     * car's own density is part of it.
     */
    private var paneContextAt: Context? = null

    private val paneContext: Context
        get() = paneContextAt ?: buildPaneContext().also { paneContextAt = it }

    private fun buildPaneContext(): Context {
        val scale = HeadwaySettings.panelScale(context)
        val base = context.resources.configuration.densityDpi
        val scaled = CarUiScale("", scale).densityFor(base)
        return if (scaled == base) {
            context
        } else {
            runCatching {
                context.createConfigurationContext(
                    Configuration(context.resources.configuration).apply {
                        densityDpi = scaled
                        // The phone's accessibility text size has no business
                        // deciding how large a car panel reads from a metre away.
                        fontScale = 1f
                    },
                )
            }.getOrDefault(context)
        }
    }

    private fun tileFor(leaf: DashNode.Leaf, path: DashPath): DashTile? =
        when (PaneKind.canonical(leaf.kind)) {
            PaneKind.NOW_PLAYING -> NowPlayingTile(paneContext)
            PaneKind.PLAYING -> NowPlayingTile(paneContext, full = true)
            PaneKind.BROWSE -> MediaBrowseTile(paneContext, onStep)
            // A real map when one of the driver's allowed apps offers a car
            // interface -- the app draws its own map into the pane's surface and
            // Headway draws its templates over it -- and the turn card when none
            // does. Both are the Maps pane; which one a phone gets depends on
            // what is installed and allowed, not on what the driver picked.
            PaneKind.MAPS -> mapsTileFor(leaf)
            PaneKind.PHONE -> PhoneTile(paneContext, onStep)
            // No host guard here on purpose: unlike a Maps pane, a Car app pane
            // has nothing to degrade *to* -- its whole content is the car app.
            // CarAppTile itself refuses to bind and says why, which is the
            // honest answer for a pane the driver explicitly asked to be a car
            // app. See CarHostCapability.
            PaneKind.CAR_APP -> CarAppTile(paneContext, leaf.argument, onStep) { service ->
                // The pick has to reach the layout, or it lasts only as long as
                // this view: render() rebuilds tiles on every divider drag and
                // tab switch, and the pane would come back a picker each time.
                rememberPaneArgument(path, PaneKind.CAR_APP, service.flattenToString())
            }
            PaneKind.MESSAGES -> MessagesTile(paneContext)
            PaneKind.NOTIFICATIONS -> NotificationsTile(paneContext)
            // The one pane whose content comes from the car rather than from the
            // phone: it draws whatever the AAP sensor channel reports, and says
            // so plainly when that is nothing.
            PaneKind.SENSORS -> SensorsTile(paneContext)
            PaneKind.CLOCK -> ClockTile(paneContext)
            PaneKind.LAUNCHER -> LauncherTile(onStep)
            PaneKind.WIDGET -> WidgetTile.of(leaf, onStep)
            PaneKind.APP -> AppPaneTile(
                metrics = metrics,
                packageName = leaf.argument,
                onStep = onStep,
                fill = HeadwaySettings.appPaneFill(context),
                onFocusRequested = ::focusAppPane,
            )

            else -> null
        }

    /**
     * Hands the picture to [pane], taking it from whichever pane had it.
     *
     * The projection is not restarted and the driver is not asked anything: the
     * one virtual display is re-pointed with `VirtualDisplay.setSurface`, which
     * is what makes several app panes affordable in the first place.
     */
    private fun focusAppPane(pane: AppPaneTile) {
        if (liveAppPane === pane && pane.isLive) {
            // Already live and tapped anyway: the driver is asking for something
            // to be in it. A pane bound to a package opens that; an unbound one
            // offers the list.
            pane.preferredPackage?.let { openApp(it) } ?: showAppPicker { openApp(it) }
            return
        }
        liveAppPane?.setLive(false)
        liveAppPane = pane
        pane.setLive(true)
        pane.preferredPackage?.let { openApp(it) }
        main.post { publishAppPaneRect() }
    }

    private fun publishAppPaneRect() {
        AppPaneHost.pictureRect = appPictureRect()
    }

    private fun persistRatio(path: DashPath, ratio: Float) {
        layout = layout.withRatio(path, ratio)
        saveLayout()
    }

    // --- switching, editing, saving -------------------------------------------

    /**
     * The tab a session comes up on.
     *
     * Not `store.active()` any more, which is the tab the *last* drive ended on.
     * A car that opens on whatever was last on screen makes the map something
     * to go and find, from the seat, at the start of every drive -- which is
     * what a driver reported: "the map view doesnt pull up automatically, it
     * only goes to the home view".
     *
     * The default resolves a *capability*, not a name, so it survives the tabs
     * being renamed, re-split or replaced wholesale: the first layout with a
     * pane that can show a map in it. Falling back to the remembered tab when
     * there is no such layout matters -- somebody who deleted every map pane is
     * telling you they do not want one, and a mapless start tab chosen on their
     * behalf would be worse than remembering where they were.
     */
    private fun openingLayout(): DashLayout {
        val wanted = HeadwaySettings.startLayout(context)
        HeadwaySettings.startLayoutName(wanted)?.let { name ->
            return tabs.firstOrNull { it.name == name } ?: store.active()
        }
        if (wanted != HeadwaySettings.START_LAYOUT_DRIVING) return store.active()
        // A real Maps pane wins outright, and only then a car-app pane. Both
        // are in [MAP_PANES] because a navigation app draws its map in a car-app
        // pane -- but so does a messenger, a weather app and anything else with
        // a car interface, and a single pass would open a session on whichever
        // came first in the driver's own tab order.
        return tabs.firstOrNull { tab -> tab.leaves().any { it.kind == PaneKind.MAPS } }
            ?: tabs.firstOrNull { tab -> tab.leaves().any { it.kind in MAP_PANES } }
            ?: store.active()
    }

    private fun switchTo(name: String) {
        // Re-read rather than trust the pill: the driver can edit layouts from
        // the phone mid-drive, so a pill may name something that no longer
        // exists, and writing that name into the active pointer would send the
        // *next* session to the default with no error anywhere.
        val wanted = store.list().firstOrNull { it.name == name }
        if (wanted == null) {
            onStep("car screen: the $name layout no longer exists; reloading the rail")
            reload()
            return
        }
        show(wanted)
    }

    private fun show(target: DashLayout) {
        if (target.name == layout.name && !editing) return
        if (editing) setEditing(false)
        layout = target
        runCatching { store.setActive(target.name) }
        fillRail()
        onStep("car screen: showing ${target.name}")
        render()
    }

    private fun setEditing(editing: Boolean) {
        if (this.editing == editing) return
        this.editing = editing
        if (!editing) saveLayout()
        onStep(if (editing) "car screen: editing ${layout.name}" else "car screen: ${layout.name} saved and locked")
        render()
    }

    /**
     * Writes the layout the driver is looking at.
     *
     * The announcement carries `this`, so the shell's own listener recognises
     * the edit and does not rebuild every pane in response to it — which would
     * drop a live car-app session and a media browser mid-list on every nudge of
     * a divider.
     */
    private fun saveLayout() {
        runCatching {
            if (!store.hasSaved()) store.replaceAll(tabs)
            store.save(layout)
            tabs = store.list()
            DashLayoutStore.announceChanged(this)
        }
    }

    /** Re-reads everything after an edit made somewhere else. */
    fun reload() {
        tabs = store.list()
        val wanted = store.active()
        val same = wanted == layout
        layout = wanted
        fillRail()
        if (!same) {
            onStep("car screen: layouts were edited; now showing ${layout.name}")
            render()
        }
    }

    private val layoutsChanged = DashLayoutStore.Listener { source ->
        if (source === this) return@Listener
        main.post { runCatching { reload() } }
    }

    private val themeChanged = HeadwayTheme.Listener {
        main.post {
            runCatching {
                root.setBackgroundColor(Headway.GROUND)
                // Colours are baked into drawables and paints when a view is
                // built, so a palette change is a rebuild rather than an
                // invalidate. The rail and the stage are both rebuilt; the
                // startup animation reads the palette every frame and needs
                // neither.
                fillRail()
                render()
            }
        }
    }

    /**
     * The grant appeared, moved, or was taken away.
     *
     * Every app pane re-decides what it can show — the live one may now have a
     * picture, and after a revoke it must stop claiming to have one, because a
     * pane that keeps a dead `SurfaceView` visible goes on swallowing every
     * touch inside its rectangle and forwarding them to a display that is no
     * longer being captured.
     */
    private val appPaneChanged = AppPaneHost.Listener {
        main.post {
            runCatching {
                live.filterIsInstance<AppPaneTile>().forEach { it.refresh() }
                publishAppPaneRect()
            }
        }
    }

    // --- the startup animation ------------------------------------------------

    /**
     * The mark, becoming itself, until the dashboard is up.
     *
     * The head unit shows whatever the first decoded frame contains, and it
     * arrives within a frame or two of the surface attaching. Without this the
     * car's first impression of Headway is a black rectangle for as long as the
     * tiles take to inflate and their permissions to resolve — which looks
     * exactly like a head unit that has stopped.
     */
    private fun showStartup() {
        val view = CarStartupView(context, metrics) { finishStartup() }
        view.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        root.addView(view)
        startup = view
    }

    private fun finishStartup() {
        val current = startup ?: return
        startup = null
        current.animate()
            .alpha(0f)
            .setDuration(Headway.SETTLE_MILLIS)
            .withEndAction { runCatching { root.removeView(current) } }
            .start()
    }

    // --- sheets ---------------------------------------------------------------

    private fun sheet(): CarSheet = CarSheet(context, metrics)

    /**
     * A sheet a *tile* wants to show, which it cannot build for itself.
     *
     * A tile has the context of its own pane and no access to the shell's
     * overlay host, so anything it needs to ask has to come through here. Kept
     * to a title, a sentence and a row of chips because that is what the callers
     * need; a tile that wants more should be asking for less.
     */
    fun showSheet(
        title: String,
        detail: String?,
        chips: List<CarSheet.Row>,
        chipsTitle: String? = null,
        extraChips: List<CarSheet.Row> = emptyList(),
        extraChipsTitle: String? = null,
    ) {
        val rows = detail?.let { listOf(CarSheet.Row(it, null) {}) }.orEmpty()
        showOverlay(
            sheet().build(
                title = title,
                rows = rows,
                chips = chips,
                chipsTitle = chipsTitle,
                extraChips = extraChips,
                extraChipsTitle = extraChipsTitle,
                onClose = ::closeOverlay,
            ),
        )
    }

    /** Closes whatever [showSheet] opened. */
    fun closeSheet() = closeOverlay()

    // --- the floating card ----------------------------------------------------

    private val callWatcher = CarPhone.Listener { call -> main.post { showCall(call) } }

    /**
     * Shows a notification briefly as it arrives, the way a phone does.
     *
     * ## What counts as "arrives"
     *
     * The feed is a whole list, re-delivered on every change -- a notification
     * being dismissed elsewhere, a download updating its progress, the listener
     * reconnecting. Only keys never seen before are popped, and the very first
     * delivery is absorbed rather than shown: connecting to the car should not
     * replay every notification already sitting on the phone.
     *
     * ## Why not the newest by time
     *
     * A notification an app *updates* keeps its key and its original post time,
     * so "newest by postedAt" would re-pop a music player's ongoing card every
     * few seconds. Identity is the only thing that answers "is this new".
     */
    private val noticeWatcher: (List<CarNotice>) -> Unit = { notices ->
        main.post { onNotices(notices) }
    }

    /** Notification keys already seen, so an update is not mistaken for an arrival. */
    private val seenNotices = mutableSetOf<String>()

    /** False until the first feed has been absorbed. See [noticeWatcher]. */
    private var noticesPrimed = false

    private val dismissPopup = Runnable {
        if (popupShowing) {
            popupShowing = false
            hideFloat()
        }
    }

    /** True while the float layer is showing a notification rather than a call. */
    private var popupShowing = false

    /**
     * Whether a call is live, tracked here rather than asked of `CarPhone`.
     *
     * `CarPhone` keeps its current call private and hands it out through the
     * listener, which is the right shape -- this class is already told every
     * time it changes, and reaching back in for a copy would be a second source
     * of the same truth.
     */
    private var callUp = false

    private fun onNotices(notices: List<CarNotice>) {
        val fresh = notices.filter { seenNotices.add(it.key) }
        if (!noticesPrimed) {
            noticesPrimed = true
            return
        }
        if (!HeadwaySettings.notificationPopups(context)) return
        // A call owns the float layer outright. A notification arriving mid-call
        // must not cover Answer and Hang up.
        if (callUp) return
        // Ongoing notifications are state, not events: a download at 40%, a
        // navigation route, a media player's card. Popping those would put
        // something over the driver's map every few seconds.
        val notice = fresh.lastOrNull { !it.ongoing } ?: return
        showNoticePopup(notice)
    }

    /**
     * Draws one notification over the panes and takes it away again.
     *
     * Deliberately not touchable. A phone's heads-up notification is tappable
     * because a phone is in your hand; this is in front of a driver, it appears
     * without being asked for, and a control that moves under a finger already
     * reaching for something else is worse than no control. The Notifications
     * pane is where a driver acts on one.
     */
    private fun showNoticePopup(notice: CarNotice) {
        val gap = CarStyle.gutter(context)
        val card = CarStyle.panel(context).apply {
            background = Headway.panel(
                radiusPx = CarStyle.radius(context) * 2f,
                fill = Headway.SURFACE,
                stroke = Headway.OUTLINE,
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(gap, gap / 2, gap, gap / 2)
            // Swallows touches without acting on them, so a tap meant for the
            // pane underneath does not land on the map by surprise either.
            isClickable = true
        }
        val side = CarStyle.dp(context, POPUP_ICON_DP)
        card.addView(
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageDrawable(
                    notice.icon ?: appIcon(context, notice.packageName),
                )
                // The same rule the pane uses: a monochrome glyph may be
                // coloured, finished artwork may not. See `CarNotice`.
                if (notice.iconIsMonochrome) {
                    runCatching { setColorFilter(CarStyle.TEXT) }
                }
                layoutParams = LinearLayout.LayoutParams(side, side).apply { marginEnd = gap }
            },
        )
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        column.addView(
            CarStyle.label(context, 13f, CarStyle.DIM).apply {
                text = notice.appLabel
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
        )
        column.addView(
            CarStyle.label(context, 17f, CarStyle.TEXT, bold = true).apply {
                text = notice.title.takeIf { it.isNotBlank() } ?: notice.text
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
        )
        notice.text.takeIf { it.isNotBlank() && it != notice.title }?.let { body ->
            column.addView(
                CarStyle.label(context, 15f, CarStyle.DIM).apply {
                    text = body
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
            )
        }
        card.addView(column)
        popupShowing = true
        showFloat(card, OverlaySpot.notificationBanner())
        Headway.revealIn(card)
        main.removeCallbacks(dismissPopup)
        main.postDelayed(dismissPopup, POPUP_MILLIS)
    }

    /**
     * Raises or drops the call card.
     *
     * A card rather than a pane, and floating rather than in the tree, because
     * a call is not something a driver arranged their screen around -- it
     * arrives over whatever tab happens to be showing, and it has to leave
     * again without taking a pane's place. The panes underneath keep working
     * and keep taking touches, which is the whole reason this is not a sheet.
     *
     * The car's own microphone is the call's microphone and Headway is not in
     * that path at all: the phone is paired to the car over Bluetooth, and
     * telephony routes a live call to the HFP headset natively, with the car's
     * own echo cancellation. That is what real Android Auto does, and it is why
     * this card carries no audio of its own. See `BLOCKERS.md` B-027 for the
     * privileged API that would be needed to do anything else.
     */
    private fun showCall(call: LiveCall?) {
        // Either way the call owns the layer from here: a popup still on screen
        // is cancelled rather than left to time out over the call card, and a
        // call ending must not have its `hideFloat` undone by a stale timer.
        main.removeCallbacks(dismissPopup)
        popupShowing = false
        callUp = call != null
        if (call == null) {
            hideFloat()
            return
        }
        val spot = HeadwaySettings.overlaySpot(context)
        val card = CarStyle.panel(context).apply {
            background = Headway.panel(
                radiusPx = CarStyle.radius(context) * 2f,
                fill = Headway.SURFACE,
                stroke = Headway.ACCENT,
            )
            // Takes its own touches so a tap on Answer does not fall through to
            // whatever pane is behind it; everything outside it still does.
            isClickable = true
        }
        card.addView(
            CarStyle.label(context, 13f, CarStyle.ACCENT).apply {
                letterSpacing = 0.06f
                text = if (call.ringing) "INCOMING CALL" else "ON A CALL"
            },
        )
        card.addView(
            CarStyle.label(context, 24f, CarStyle.TEXT, bold = true).apply {
                text = call.who
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
        )
        card.addView(
            CarStyle.label(context, 13f, CarStyle.DIM).apply { text = call.source },
        )
        card.addView(
            View(context),
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f),
        )
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        // The dialer's own actions, fired as the dialer published them. Headway
        // is not a phone and does not want to be -- the `InCallService` a car
        // wants needs a privileged role, and becoming the default dialer would
        // mean owning emergency calling. See `CarPhone`.
        call.answer?.takeIf { call.ringing }?.let { intent ->
            buttons.addView(
                CarStyle.button(context, "Answer", emphasised = true) {
                    runCatching { intent.send() }
                        .onFailure { onStep("phone: the dialer refused Answer: $it") }
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                        marginEnd = CarStyle.gutter(context) / 2
                    }
                },
            )
        }
        call.hangUp?.let { intent ->
            buttons.addView(
                CarStyle.button(context, if (call.ringing) "Decline" else "Hang up") {
                    runCatching { intent.send() }
                        .onFailure { onStep("phone: the dialer refused Hang up: $it") }
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                },
            )
        }
        if (buttons.childCount > 0) card.addView(buttons)
        showFloat(card, spot)
        onStep("car screen: ${if (call.ringing) "a call is ringing" else "a call is up"}")
    }

    /** Puts [view] on the floating layer at [spot]. */
    private fun showFloat(view: View, spot: OverlaySpot) {
        val width = root.width.takeIf { it > 0 } ?: metrics.widthPx
        val height = root.height.takeIf { it > 0 } ?: metrics.heightPx
        floats.removeAllViews()
        floats.addView(
            view,
            FrameLayout.LayoutParams(spot.widthPx(width), spot.heightPx(height)).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = spot.leftPx(width)
                topMargin = spot.topPx(height)
            },
        )
        floats.visibility = View.VISIBLE
        Headway.revealIn(view)
    }

    private fun hideFloat() {
        floats.removeAllViews()
        floats.visibility = View.GONE
    }

    /** Where a floating card sits, and how big. */
    private fun showOverlaySpot() {
        val spot = HeadwaySettings.overlaySpot(context)
        val rows = mutableListOf<CarSheet.Row>()
        rows += CarSheet.section("Size")
        OverlaySpot.SIZES.forEach { (label, width, height) ->
            rows += CarSheet.Row(
                title = label,
                selected = kotlin.math.abs(spot.effectiveWidth - width) < 0.01f &&
                    kotlin.math.abs(spot.effectiveHeight - height) < 0.01f,
            ) {
                // The corner is kept and re-clamped rather than reset: a driver
                // resizing a card meant to resize it, not to move it.
                applyOverlaySpot(spot.copy(width = width, height = height))
            }
        }
        rows += CarSheet.section("Where")
        OverlaySpot.corners(spot.effectiveWidth, spot.effectiveHeight)
            .forEach { (label, x, y) ->
                rows += CarSheet.Row(
                    title = label,
                    selected = kotlin.math.abs(spot.effectiveX - x) < 0.01f &&
                        kotlin.math.abs(spot.effectiveY - y) < 0.01f,
                ) { applyOverlaySpot(spot.copy(x = x, y = y)) }
            }
        showOverlay(
            sheet().build(
                title = "The call card",
                rows = rows,
                onClose = ::closeOverlay,
            ),
        )
    }

    private fun applyOverlaySpot(spot: OverlaySpot) {
        HeadwaySettings.setOverlaySpot(context, spot)
        closeOverlay()
        onStep("car screen: the call card is ${spot.describe()}")
        // Redrawn where it now belongs, if one is up. A driver moving it while
        // a call is live is the likeliest time to be moving it at all.
        runCatching { CarPhone.call?.let { showCall(it) } }
    }

    private fun showOverlay(view: View) {
        overlayHost.removeAllViews()
        overlayHost.addView(view)
        overlayHost.visibility = View.VISIBLE
        Headway.revealIn(view)
    }

    private fun closeOverlay() {
        overlayHost.removeAllViews()
        overlayHost.visibility = View.GONE
        publishAppPaneRect()
    }

    /** Everything the driver can change from the car. */
    private fun showSettings() {
        val rows = mutableListOf<CarSheet.Row>()
        rows += CarSheet.section("This layout")
        rows += CarSheet.Row(
            title = if (layout.locked) "Edit this layout" else "Save and lock",
            detail = if (layout.locked) {
                "Move dividers, split panes, choose what each one shows"
            } else {
                "${layout.name} is unlocked — nothing is saved until you lock it"
            },
        ) {
            closeOverlay()
            unlockOrLock()
        }
        rows += CarSheet.Row("Layouts", "Switch, add or delete a layout") { showLayouts() }
        // Where a driver actually looks for it. The picker already existed but
        // only behind a tap on a *pinned* rail item, so a driver whose layout
        // was not pinned -- or who simply did not think to tap the rail -- had
        // no way to reach it, and reported that there was none: "theres no way
        // for me to set the icon of layouts, only the name".
        rows += CarSheet.Row(
            title = "Icon for ${layout.name}",
            detail = layout.icon?.name?.lowercase()?.replaceFirstChar { it.uppercase() }
                ?: "Showing its name in words",
        ) { showTabIconPicker(layout.name) }
        rows += CarSheet.Row("Opens on", startLayoutSummary()) { showStartLayout() }
        rows += CarSheet.Row(
            "Notification popups",
            if (HeadwaySettings.notificationPopups(context)) {
                "On — a new notification shows briefly at the top"
            } else {
                "Off — notifications only appear in the Notifications pane"
            },
        ) {
            val next = !HeadwaySettings.notificationPopups(context)
            HeadwaySettings.setNotificationPopups(context, next)
            if (!next) {
                main.removeCallbacks(dismissPopup)
                if (popupShowing) {
                    popupShowing = false
                    hideFloat()
                }
            }
            showSettings()
        }

        rows += CarSheet.section("Apps")
        rows += CarSheet.Row("Open an app", "Show a running app in the app pane") {
            showAppPicker { openApp(it) }
        }
        rows += CarSheet.Row("Apps and panels", appSourceSummary()) { showAppSettings() }

        rows += CarSheet.section("Appearance")
        rows += CarSheet.Row("Theme", themeSummary()) { showThemes() }
        rows += CarSheet.Row("The rail", railStyle.describe()) { showRailStyle() }
        rows += CarSheet.Row(
            title = "Corners",
            detail = HeadwaySettings.cornerStyle(context).let {
                "${it.displayName} — ${it.explanation}"
            },
        ) { showCornerStyle() }
        rows += CarSheet.Row("Pinned", "What sits on the rail, and in what order") { showRailEditor() }
        rows += CarSheet.Row("Units", unitsSummary()) { showUnits() }
        rows += CarSheet.Row(
            title = "The call card",
            detail = "Where a call floats over your panels — " +
                HeadwaySettings.overlaySpot(context).describe(),
        ) { showOverlaySpot() }
        rows += CarSheet.Row(
            title = "Panel size",
            detail = "How large everything inside the panels draws — " +
                CarUiScale.percentOf(HeadwaySettings.panelScale(context)),
        ) { showPanelScale() }
        // Only while a car app is actually open, because the size is that app's
        // and the sentence would be meaningless without one. The pane itself has
        // no room for a control -- it is full of somebody else's map.
        live.filterIsInstance<CarAppTile>().firstOrNull { it.openAppLabel != null }?.let { tile ->
            rows += CarSheet.Row(
                title = "${tile.openAppLabel} size",
                detail = "How large it draws inside its panel",
            ) { tile.showScalePicker() }
        }

        rows += CarSheet.section("This drive")
        if (HeadwayAccessibilityService.instance.value?.covering == true) {
            rows += CarSheet.Row(
                title = "Show the phone screen",
                detail = "Uncover the phone without ending the drive",
            ) {
                closeOverlay()
                // Through the service rather than straight to the accessibility
                // service, so the notification's own "Show phone screen" action
                // comes off with the cover. Both routes have to end in the same
                // place or the shade offers to undo something already undone.
                runCatching {
                    context.startService(
                        Intent(context, HeadwayService::class.java)
                            .setAction(HeadwayService.ACTION_SHOW_PHONE),
                    )
                }
            }
        }
        rows += CarSheet.Row("About this session", sessionSummary()) { }

        showOverlay(
            sheet().build(title = "Settings", rows = rows, onClose = ::closeOverlay),
        )
    }

    /**
     * Where the rail sits, how big it is, and whether it carries the clock.
     *
     * Every change rebuilds the car screen, because the rail's edge decides the
     * orientation of the column that holds it and the stage — the two cannot be
     * changed independently and there is nothing to gain from trying.
     */
    private fun showRailStyle() {
        val current = railStyle
        val rows = mutableListOf<CarSheet.Row>()
        rows += CarSheet.section("Edge")
        RailEdge.entries.forEach { edge ->
            rows += CarSheet.Row(
                title = edge.describe(),
                detail = if (edge.vertical) "Down the side" else "Across the screen",
                selected = current.edge == edge,
            ) { applyRailStyle(current.copy(edge = edge)) }
        }
        val chips = RailStyle.SCALE_CHOICES.map { scale ->
            CarSheet.Row(
                title = RailStyle.percentOf(scale),
                selected = kotlin.math.abs(current.effectiveScale - scale) < 0.001f,
            ) { applyRailStyle(current.copy(scale = scale)) }
        }
        rows += CarSheet.section("Clock")
        rows += CarSheet.Row(
            title = if (current.showClock) "Hide the clock" else "Show the clock",
            detail = "The time sits at the end of the rail",
        ) { applyRailStyle(current.copy(showClock = !current.showClock)) }
        if (current.showClock) {
            rows += CarSheet.Row(
                title = if (current.showDate) "Hide the date" else "Show the date",
                detail = "Under the time",
            ) { applyRailStyle(current.copy(showDate = !current.showDate)) }
        }
        showOverlay(
            sheet().build(
                title = "The rail",
                rows = rows,
                chips = chips,
                // Five unlabelled percentages above a list of edges is a puzzle.
                chipsTitle = "Size",
                onClose = ::closeOverlay,
            ),
        )
    }

    private fun unitsSummary(): String {
        val chosen = HeadwaySettings.carUnits(context)
        return if (chosen == CarUnits.AUTOMATIC) {
            "Automatic — ${effectiveUnits().detail()}"
        } else {
            chosen.detail()
        }
    }

    /** What AUTOMATIC currently resolves to, so the summary can say it. */
    private fun effectiveUnits(): CarUnits {
        val country = runCatching { context.resources.configuration.locales.get(0).country }
            .getOrNull()?.takeIf { it.isNotBlank() }
        return if (CarUnits.imperialFor(country)) CarUnits.IMPERIAL else CarUnits.METRIC
    }

    /**
     * Which units the car pane reads in.
     *
     * On the car screen rather than only on the phone because it is a
     * *reading* preference: the moment a driver notices it is wrong is the
     * moment they are looking at the number, and that number is on the car.
     */
    /** What the *Opens on* row says without opening it. */
    private fun startLayoutSummary(): String = when (val wanted = HeadwaySettings.startLayout(context)) {
        HeadwaySettings.START_LAYOUT_LAST -> "Whichever tab the last drive ended on"
        HeadwaySettings.START_LAYOUT_DRIVING ->
            openingLayout().let { "The map — currently ${it.name}" }
        else -> HeadwaySettings.startLayoutName(wanted)
            ?.let { name ->
                if (tabs.any { it.name == name }) name else "$name — no longer a tab"
            }
            ?: "Whichever tab the last drive ended on"
    }

    /**
     * Which tab the next drive starts on.
     *
     * Offered on the car screen and not only on the phone, because the driver
     * who wants this is the one sitting in front of the wrong tab.
     */
    private fun showStartLayout() {
        val current = HeadwaySettings.startLayout(context)
        val rows = mutableListOf<CarSheet.Row>()
        rows += CarSheet.Row(
            title = "The map",
            detail = "Whichever tab can show a map — right now ${openingLayout().name}. " +
                "Follows your tabs if you rename or rebuild them.",
            selected = current == HeadwaySettings.START_LAYOUT_DRIVING,
        ) { chooseStartLayout(HeadwaySettings.START_LAYOUT_DRIVING, "the map") }
        rows += CarSheet.Row(
            title = "Where you left off",
            detail = "The tab that was on screen when the last drive ended",
            selected = current == HeadwaySettings.START_LAYOUT_LAST,
        ) { chooseStartLayout(HeadwaySettings.START_LAYOUT_LAST, "where you left off") }
        rows += CarSheet.section("A tab, every time")
        tabs.forEach { tab ->
            val value = HeadwaySettings.START_LAYOUT_NAMED + tab.name
            rows += CarSheet.Row(
                title = tab.name,
                detail = tab.leaves().joinToString(", ") { PaneKind.describe(it.kind) },
                selected = current == value,
            ) { chooseStartLayout(value, tab.name) }
        }
        showOverlay(
            sheet().build(title = "Opens on", rows = rows, onClose = ::closeOverlay),
        )
    }

    private fun chooseStartLayout(value: String, said: String) {
        HeadwaySettings.setStartLayout(context, value)
        closeOverlay()
        onStep("car screen: the next drive opens on $said")
    }

    /**
     * How rounded everything on the car screen is.
     *
     * A rebuild rather than a re-render, because a shape is baked into the
     * `GradientDrawable` each view was built with -- the same reason panel size
     * rebuilds -- and the price of one setting moving every corner at once is
     * that changing it builds them again.
     */
    private fun showCornerStyle() {
        val current = HeadwaySettings.cornerStyle(context)
        val rows = CornerStyle.ALL.map { style ->
            CarSheet.Row(
                title = style.displayName,
                detail = style.explanation,
                selected = style == current,
            ) {
                HeadwaySettings.setCornerStyle(context, style)
                Headway.corners = style
                closeOverlay()
                onStep("car screen: corners are now ${style.displayName.lowercase()}")
                // The rail's own shapes were built with the old radius too.
                layOutRailAndStage()
                render()
            }
        }
        showOverlay(
            sheet().build(title = "Corners", rows = rows, onClose = ::closeOverlay),
        )
    }

    private fun showUnits() {
        val current = HeadwaySettings.carUnits(context)
        val rows = CarUnits.entries.map { choice ->
            CarSheet.Row(
                title = choice.describe(),
                detail = if (choice == CarUnits.AUTOMATIC) {
                    "${choice.detail()} — currently ${effectiveUnits().detail()}"
                } else {
                    choice.detail()
                },
                selected = choice == current,
            ) {
                HeadwaySettings.setCarUnits(context, choice)
                closeOverlay()
                onStep("car screen: units are now ${choice.describe().lowercase()}")
                // Every reading is formatted at render time, so re-rendering the
                // stage is the whole of applying this.
                render()
            }
        }
        showOverlay(
            sheet().build(title = "Units", rows = rows, onClose = ::closeOverlay),
        )
    }

    /**
     * How large every panel's content draws.
     *
     * A rebuild rather than a re-render, because the size is carried by the
     * context each tile was *built* with -- that is what makes one setting move
     * every dp and sp in every pane at once, and the price is that changing it
     * builds them again.
     */
    private fun showPanelScale() {
        val current = HeadwaySettings.panelScale(context)
        val chips = CarUiScale.CHOICES.map { choice ->
            CarSheet.Row(
                title = CarUiScale.percentOf(choice),
                selected = kotlin.math.abs(current - choice) < 0.001f,
            ) {
                HeadwaySettings.setPanelScale(context, choice)
                closeOverlay()
                onStep("car screen: panels now draw at ${CarUiScale.percentOf(choice)}")
                paneContextAt = null
                render()
            }
        }
        showOverlay(
            sheet().build(
                title = "Panel size",
                rows = emptyList(),
                chips = chips,
                chipsTitle = "Everything inside a panel",
                onClose = ::closeOverlay,
            ),
        )
    }

    private fun applyRailStyle(style: RailStyle) {
        HeadwaySettings.setRailStyle(context, style)
        closeOverlay()
        // The rail's edge decides the whole window's layout, so this is a rebuild
        // rather than a re-render. Said out loud because it is the one setting
        // whose effect is a visible flicker.
        onStep("car screen: rail style is now ${style.describe()}")
        // `layOutRailAndStage` stops the outgoing rail's clock and the rebuilt
        // one starts its own, so there is nothing to unregister here.
        layOutRailAndStage()
        render()
        publishAppPaneRect()
    }

    /**
     * Where apps run and how their picture is placed — the two questions the
     * app pane raises, answerable from the car.
     *
     * On the car because the answer changes with the drive. The simulated
     * display gives a car-shaped picture and costs a Developer options toggle
     * and a phone screen that must stay on; the phone's own screen costs
     * neither and gives a portrait strip unless it is cropped. A driver who has
     * to stop and find the phone to switch between those will simply never
     * switch.
     */
    private fun showAppSettings() {
        val simulated = OverlayDisplay.find(context)
        val onSimulated = CarAppDisplay.enabled(context)
        val fill = HeadwaySettings.appPaneFill(context)
        val rows = listOf(
            CarSheet.Row(
                title = "Run apps on the phone's screen",
                detail = "Nothing to set up. The picture is portrait, so crop it to fill",
                selected = !onSimulated,
            ) {
                setAppSource(false)
            },
            CarSheet.Row(
                title = "Run apps on a simulated display",
                detail = simulated?.let { "Car-shaped picture, on $it" }
                    ?: "Needs Developer options \u2192 Simulate secondary displays first",
                selected = onSimulated,
            ) {
                setAppSource(true)
            },
            CarSheet.Row(
                title = if (fill) "Fit the picture inside the panel" else "Crop the picture to fill the panel",
                detail = if (fill) {
                    "Show all of it, with a bar where the shapes differ"
                } else {
                    "Fill the panel and lose the edges \u2014 better for a map"
                },
            ) {
                runCatching {
                    HeadwaySettings.of(context).edit()
                        .putBoolean(HeadwaySettings.KEY_APP_PANE_FILL, !fill)
                        .apply()
                }
                closeOverlay()
                render()
            },
            landscapeRow(),
            carAppLocationRow(),
        )
        showOverlay(sheet().build("Apps and panels", rows, onClose = ::closeOverlay))
    }

    /**
     * Turning the phone sideways so a mirrored app lays itself out wide.
     *
     * Offered here rather than on the phone because it is the answer to the
     * complaint the two rows above it only half-answer: cropping a portrait
     * picture fills the pane, but the app is still *drawing* for a tall screen.
     * `PhoneRotation` has the whole derivation, including the honest limit —
     * an app that locks itself to portrait wins, and this does nothing for it.
     */
    /**
     * Whether a hosted car app may use the phone's location.
     *
     * Offered here because the fault it fixes is one you see from the seat: a
     * navigation app's own marker sitting still, or pointing the wrong way,
     * except while a route is running. Headway reads no location itself; this
     * is what lets it pass a while-in-use capability down to the app it is
     * hosting, which otherwise has none because it is a bound background
     * service with no screen of its own.
     */
    private fun carAppLocationRow(): CarSheet.Row {
        val on = HeadwaySettings.carAppLocation(context)
        val granted = runCatching {
            context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        return CarSheet.Row(
            title = "Let car apps use your location",
            detail = when {
                on && granted -> "Their own map markers track, from the next session"
                on -> "On, but the phone has not granted location — finish it on the phone"
                else -> "Off — a map app's marker only moves while it is navigating"
            },
            selected = on && granted,
        ) {
            HeadwaySettings.setCarAppLocation(context, !on)
            closeOverlay()
            if (on) {
                showVoiceMessage("Car apps go back to their own location")
                return@Row
            }
            if (granted) {
                showVoiceMessage("Reconnect for this to take effect")
                return@Row
            }
            // The grant itself has to be asked for from an activity, and there
            // is none on the car screen. Saying so is the honest thing: a row
            // that switches on and then silently does nothing is the failure
            // this whole setting exists to correct.
            showVoiceMessage("Open Headway on the phone to allow location, then reconnect")
        }
    }

    private fun landscapeRow(): CarSheet.Row {
        val on = HeadwaySettings.landscapeApps(context)
        val granted = PhoneRotation.granted(context)
        return CarSheet.Row(
            title = "Turn the phone sideways for apps",
            detail = when {
                on && !granted -> "On, but the phone has not granted it — tap to fix on the phone"
                on -> "Apps that can lay out wide will, from the next session"
                granted -> "Off — apps mirror the phone's portrait screen"
                else -> "Needs one permission on the phone; apps that lock to portrait are unaffected"
            },
            selected = on && granted,
        ) {
            if (!granted) {
                // Switched on first, so granting the permission is the only
                // remaining step rather than the first of two. Nothing is
                // written to the phone's rotation until a session starts and
                // the grant is re-checked.
                HeadwaySettings.setLandscapeApps(context, true)
                closeOverlay()
                val sent = runCatching {
                    context.applicationContext.startActivity(
                        PhoneRotation.accessIntent(context.applicationContext),
                    )
                    true
                }.getOrDefault(false)
                showVoiceMessage(
                    if (sent) {
                        "Allow Headway to change system settings on the phone"
                    } else {
                        "Grant “Modify system settings” to Headway in the phone's Settings"
                    },
                )
                return@Row
            }
            HeadwaySettings.setLandscapeApps(context, !on)
            closeOverlay()
            if (on) runCatching { PhoneRotation.restore(context) { onStep(it) } }
            showVoiceMessage(
                if (on) "The phone goes back to portrait" else "Reconnect for this to take effect",
            )
        }
    }

    private fun setAppSource(simulated: Boolean) {
        runCatching {
            HeadwaySettings.of(context).edit()
                .putBoolean(HeadwaySettings.KEY_NATIVE_APP_DISPLAY, simulated)
                .apply()
        }
        closeOverlay()
        // The choice decides which display apps are launched onto *and* what
        // the projection is assumed to be recording, and both are resolved once
        // per session. Saying so is the honest thing: this cannot take effect
        // until the next connection.
        showVoiceMessage("Reconnect for this to take effect")
        onStep("car screen: apps will run on ${if (simulated) "a simulated display" else "the phone's screen"} from the next session")
    }

    private fun appSourceSummary(): String {
        val where = if (CarAppDisplay.enabled(context)) "a simulated display" else "the phone's screen"
        val how = if (HeadwaySettings.appPaneFill(context)) "cropped to fill" else "fitted inside"
        return "Apps run on $where, $how"
    }

    private fun themeSummary(): String {
        val choice = HeadwayTheme.choice
        return "${choice.base.displayName}, ${choice.accent.displayName.lowercase()}"
    }

    private fun sessionSummary(): String = buildString {
        append(metrics.widthPx).append('x').append(metrics.heightPx)
        append(" at ").append(metrics.densityDpi).append(" dpi")
        append(if (AppPaneHost.available) ", screen sharing on" else ", screen sharing off")
        CarAppDisplay.active?.let { append(", apps on ").append(it.name) }
    }

    private fun unlockOrLock() {
        if (layout.locked) {
            layout = layout.withLocked(false)
            saveLayout()
            setEditing(true)
        } else {
            layout = layout.withLocked(true)
            // Saved here rather than left to setEditing, which returns early
            // when editing is already false -- the state a layout is in when it
            // was decoded unlocked (every layout saved before locking existed)
            // and the driver has not opened the editor. Locking one of those
            // would have been a no-op that looked like it worked.
            saveLayout()
            setEditing(false)
            render()
        }
    }

    private fun showThemes() {
        val current = HeadwayTheme.choice
        val chips = ThemeAccent.entries.map { accent ->
            CarSheet.Row(
                title = accent.displayName,
                selected = accent == current.accent,
            ) {
                HeadwayTheme.set(context, current.copy(accent = accent))
                showThemes()
            }
        }
        val rows = ThemeBase.entries.map { base ->
            CarSheet.Row(
                title = base.displayName,
                detail = base.explanation,
                selected = base == current.base,
            ) {
                HeadwayTheme.set(context, HeadwayTheme.choice.copy(base = base))
                showThemes()
            }
        }
        showOverlay(
            sheet().build(
                title = "Theme",
                rows = rows,
                chips = chips,
                chipsTitle = "Accent",
                onClose = ::closeOverlay,
            ),
        )
    }

    private fun showLayouts() {
        val rows = mutableListOf<CarSheet.Row>()
        tabs.forEach { candidate ->
            rows += CarSheet.Row(
                title = candidate.name,
                detail = candidate.describe().substringAfter(": "),
                selected = candidate.name == layout.name,
            ) {
                closeOverlay()
                show(candidate)
            }
        }
        rows += CarSheet.Row("New layout", "One empty pane, ready to split") {
            closeOverlay()
            addLayout()
        }
        if (tabs.size > 1) {
            rows += CarSheet.Row(
                title = "Delete ${layout.name}",
                detail = "Removes this layout and unpins it",
                destructive = true,
            ) {
                closeOverlay()
                deleteLayout()
            }
        }
        showOverlay(sheet().build("Layouts", rows, onClose = ::closeOverlay))
    }

    /**
     * A new layout, named for what it is rather than asking.
     *
     * There is no keyboard on the car screen and there should not be one: an IME
     * on a dashboard is a text field the driver types into while moving. A
     * generated name is renameable from the phone, where a keyboard is
     * appropriate.
     */
    private fun addLayout() {
        val existing = tabs.map { it.name }.toSet()
        var index = tabs.size + 1
        while ("Layout $index" in existing) index++
        val created = DashLayout(
            name = "Layout $index",
            root = DashNode.Leaf(PaneKind.LAUNCHER),
            locked = false,
        )
        runCatching {
            if (!store.hasSaved()) store.replaceAll(tabs)
            store.save(created)
            store.setRail(Rail.pin(rail(), RailItem.Layout(created.name)))
            tabs = store.list()
            DashLayoutStore.announceChanged(this)
        }
        layout = created
        runCatching { store.setActive(created.name) }
        fillRail()
        editing = true
        render()
        onStep("car screen: added ${created.name}")
    }

    private fun deleteLayout() {
        if (tabs.size <= 1) return
        val going = layout.name
        runCatching {
            if (!store.hasSaved()) store.replaceAll(tabs)
            store.delete(going)
            store.setRail(Rail.unpin(rail(), RailItem.Layout(going)))
            tabs = store.list()
            DashLayoutStore.announceChanged(this)
        }
        editing = false
        layout = store.active()
        fillRail()
        render()
        onStep("car screen: deleted $going")
    }

    private fun showRailEditor() {
        val pinned = rail()
        val rows = mutableListOf<CarSheet.Row>()
        pinned.forEachIndexed { index, item ->
            rows += CarSheet.Row(
                title = railLabel(item),
                detail = when {
                    index == 0 -> "First on the rail — tap to move it later"
                    else -> "Tap to move it one place earlier"
                },
                selected = false,
            ) {
                runCatching { store.setRail(Rail.move(pinned, index, if (index == 0) pinned.size - 1 else index - 1)) }
                fillRail()
                showRailEditor()
            }
        }
        rows += CarSheet.Row("Pin a layout", "Add one of your layouts to the rail") {
            showPinLayoutPicker()
        }
        rows += CarSheet.Row("Pin an app", "Add an app you open often") {
            showAppPicker { packageName ->
                runCatching { store.setRail(Rail.pin(rail(), RailItem.App(packageName))) }
                fillRail()
                showRailEditor()
            }
        }
        pinned.forEach { item ->
            rows += CarSheet.Row(
                title = "Unpin ${railLabel(item)}",
                destructive = true,
            ) {
                runCatching { store.setRail(Rail.unpin(rail(), item)) }
                fillRail()
                showRailEditor()
            }
        }
        showOverlay(sheet().build("Pinned", rows, onClose = ::closeOverlay))
    }

    private fun railLabel(item: RailItem): String = when (item) {
        is RailItem.Layout -> item.name
        is RailItem.App -> runCatching {
            val manager = context.packageManager
            manager.getApplicationLabel(manager.getApplicationInfo(item.packageName, 0)).toString()
        }.getOrDefault(item.packageName)
    }

    private fun showPinLayoutPicker() {
        val pinned = rail()
        val rows = tabs
            .filterNot { candidate -> pinned.any { it == RailItem.Layout(candidate.name) } }
            .map { candidate ->
                CarSheet.Row(candidate.name, candidate.describe().substringAfter(": ")) {
                    runCatching { store.setRail(Rail.pin(rail(), RailItem.Layout(candidate.name))) }
                    fillRail()
                    showRailEditor()
                }
            }
        showOverlay(
            sheet().build(
                title = "Pin a layout",
                rows = rows.ifEmpty {
                    listOf(CarSheet.Row("Every layout is already pinned", null) { showRailEditor() })
                },
                onClose = ::closeOverlay,
            ),
        )
    }

    // --- the pane menu --------------------------------------------------------

    private fun showPaneMenu(path: DashPath) {
        val leaf = layout.nodeAt(path) as? DashNode.Leaf ?: return
        val rows = mutableListOf<CarSheet.Row>()
        rows += CarSheet.Row("Change what this shows", PaneKind.describe(leaf.kind)) {
            showKindPicker(path)
        }
        rows += CarSheet.Row("Split left and right", "Two panes side by side") {
            closeOverlay()
            splitPane(path, DashNode.Orientation.HORIZONTAL)
        }
        rows += CarSheet.Row("Split top and bottom", "One pane above the other") {
            closeOverlay()
            splitPane(path, DashNode.Orientation.VERTICAL)
        }
        if (layout.paneCount > 1) {
            rows += CarSheet.Row(
                title = "Remove this pane",
                detail = "The pane beside it takes the space",
                destructive = true,
            ) {
                closeOverlay()
                layout = layout.removeAt(path)
                render()
            }
        }
        rows += CarSheet.Row("Done editing", "Save this layout and lock it") {
            closeOverlay()
            unlockOrLock()
        }
        showOverlay(
            sheet().build(PaneKind.describe(leaf.kind), rows, onClose = ::closeOverlay),
        )
    }

    private fun splitPane(path: DashPath, orientation: DashNode.Orientation) {
        layout = layout.splitAt(path, orientation, DashNode.Leaf(PaneKind.CLOCK))
        render()
    }

    private fun showKindPicker(path: DashPath) {
        val appPanes = layout.leaves().count { PaneKind.isApp(it.kind) }
        val rows = PaneKind.ALL.map { kind ->
            CarSheet.Row(
                title = PaneKind.describe(kind),
                detail = if (kind == PaneKind.APP && appPanes >= 1) {
                    // Said before the driver builds a layout of four app panes
                    // and finds three of them dormant. One capture grant shares
                    // one app; Car app and Widget panes each render a different
                    // app and there is no limit on those.
                    "A second one is a place to move the shared app to. For two apps at " +
                        "once, use Car app or Widget panes"
                } else {
                    PaneKind.explain(kind)
                },
                selected = PaneKind.canonical(
                    (layout.nodeAt(path) as? DashNode.Leaf)?.kind.orEmpty(),
                ) == kind,
            ) {
                when (kind) {
                    PaneKind.APP -> showAppPicker { packageName ->
                        closeOverlay()
                        layout = layout.setPane(path, PaneKind.APP, packageName)
                        render()
                        openApp(packageName)
                    }
                    PaneKind.WIDGET -> showWidgetPicker(path)
                    else -> {
                        closeOverlay()
                        layout = layout.setPane(path, kind)
                        render()
                    }
                }
            }
        }
        showOverlay(sheet().build("Show in this pane", rows, onClose = ::closeOverlay))
    }

    /**
     * Drops widget ids that no saved layout mentions any more.
     *
     * Ids outlive the panes that referenced them: removing a widget pane, or
     * deleting a layout, rewrites the tree and says nothing to `AppWidgetService`,
     * which goes on believing Headway hosts that widget and goes on asking its
     * provider for updates. Nothing else can find them, because they appear in no
     * layout.
     *
     * The keep set has to be **every id in every stored layout**, not just the
     * active one -- see `WidgetTile.releaseOrphans`, where passing a partial set
     * silently empties panes the driver has not opened yet.
     */
    private fun releaseOrphanedWidgets() {
        val keep = runCatching {
            tabs.flatMap { it.leaves() }
                .filter { PaneKind.canonical(it.kind) == PaneKind.WIDGET }
                .map { WidgetTile.widgetIdOf(it.argument) }
                .filter { it != AppWidgetManager.INVALID_APPWIDGET_ID }
                .toSet()
        }.getOrNull() ?: return
        // Plus whatever an add is part way through. This runs on every car-screen
        // bring-up, and reconnection makes those frequent, so without the
        // in-flight id a driver who is still in a provider's setup screen when
        // the car reconnects has the widget deleted out from under them.
        val inFlight = WidgetSetup.pendingId
            .takeIf { it != AppWidgetManager.INVALID_APPWIDGET_ID }
        runCatching { WidgetTile.releaseOrphans(context, keep + setOfNotNull(inFlight), onStep) }
    }

    /**
     * Writes a pane's chosen argument into the layout without redrawing it.
     *
     * Deliberately not `render()`: this is called *from* a tile that is in the
     * middle of opening, and rebuilding the tile tree underneath it would
     * destroy the view that is running. The tree already shows the right thing;
     * only the saved layout was out of date.
     */
    private fun rememberPaneArgument(path: DashPath, kind: String, argument: String) {
        val leaf = layout.nodeAt(path) as? DashNode.Leaf ?: return
        if (leaf.argument == argument) return
        layout = layout.setPane(path, kind, argument)
        // saveLayout() writes without rebuilding the tile tree, which is exactly
        // what is needed here: the caller is a tile in the middle of opening.
        saveLayout()
        onStep("car screen: remembered $argument for the ${PaneKind.describe(kind)} pane")
    }

    /**
     * A real map when one can be drawn, and the turn card when one cannot.
     *
     * Three things have to be true for the map: this build can host car apps at
     * all, a navigator among the allowed apps offers a car interface, and the
     * argument -- if the layout has one -- resolves to something installed.
     *
     * The host check is the one that was missing, and it is not cosmetic. On a
     * `compat` install no car app can ever accept Headway (see
     * [CarHostCapability]), so building a `CarAppTile` there guarantees a bind, a
     * handshake, and a pane that ends up showing the app's own refusal. The turn
     * card is a working Maps pane; a refusal is not. Degrading is strictly
     * better than explaining.
     *
     * The argument is resolved rather than trusted because a `MAPS` leaf can
     * legitimately hold either format: a bare package name, which is what
     * `HeadwaySettings.KEY_MAP_APP` and every layout saved before car-app maps
     * existed contain, or a flattened `ComponentName`, which is what a car-app
     * pane saves. Passing a bare package straight through left
     * `ComponentName.unflattenFromString` returning null, the pin silently
     * dropped, and -- because the elvis bound to the argument first -- the
     * navigator fallback disabled for exactly the drivers who already had a map
     * app chosen.
     */
    private fun mapsTileFor(leaf: DashNode.Leaf): DashTile {
        if (!CarHostCapability.available(context)) return MapsTile(context, onStep)
        // Allowed only: this pane opens a navigator with no further prompt, so
        // an app the driver has never approved must not start here. The Car app
        // pane's picker shows every car app and asks.
        val navigators = TemplateApps.allowedNavigators(context)
        if (navigators.isEmpty()) return MapsTile(context, onStep)
        val wanted = leaf.argument?.let { argument ->
            navigators.firstOrNull { it.service.flattenToString() == argument }
                ?: navigators.firstOrNull { it.packageName == argument }
        }
        val app = wanted ?: navigators.first()
        // `pinned`: this pane resolved its own navigator, so the template's
        // "Apps" button would lead to a picker the driver never came from and
        // its label would name an app they never picked. Both are drawn over
        // the map, which is the pane's whole content.
        return CarAppTile(
            context = context,
            argument = app.service.flattenToString(),
            onStep = onStep,
            pinned = true,
        )
    }

    /**
     * Asks a yes/no question on the car screen and runs [onConfirmed] for yes.
     *
     * Public because tiles need it: a pane that has to ask permission for
     * something cannot build its own sheet, since the overlay host belongs to the
     * shell. Cancel is the default -- closing the sheet any other way does
     * nothing -- because every caller so far is granting an app access to the car
     * screen, and the safe answer to a question a driver did not mean to open is
     * no.
     */
    fun confirm(
        title: String,
        detail: String,
        confirmLabel: String,
        onDeclined: () -> Unit = {},
        onConfirmed: () -> Unit,
    ) {
        // Runs once, whichever way the sheet is left. A caller that has nothing
        // on screen until one of the two answers arrives -- the car-app pane is
        // one -- would otherwise be left blank for the rest of the drive by a
        // driver who tapped "Not now" or closed the sheet.
        var answered = false
        fun decline() {
            if (answered) return
            answered = true
            closeOverlay()
            runCatching { onDeclined() }
                .onFailure { onStep("car screen: the declined action failed: $it") }
        }
        showOverlay(
            sheet().build(
                title = title,
                rows = listOf(
                    CarSheet.Row(confirmLabel, detail) {
                        answered = true
                        closeOverlay()
                        runCatching { onConfirmed() }
                            .onFailure { onStep("car screen: the confirmed action failed: $it") }
                    },
                    CarSheet.Row("Not now", null) { decline() },
                ),
                onClose = ::decline,
            ),
        )
    }

    // --- the widget list ------------------------------------------------------

    /**
     * The widgets the driver may put in a pane, and the route that adds one.
     *
     * This is the only pane kind that renders a *different* third-party app in
     * every pane at once, and it is why the layout editor is worth having: screen
     * sharing gives one app on the whole car screen at a time, while four widget
     * panes are four apps drawn simultaneously, live, by the apps themselves.
     *
     * Filtered by the same allow-list as the app picker. A widget draws that
     * app's data on the car screen, so the decision to put it there is the same
     * decision, and it is made on the phone while parked.
     */
    private fun showWidgetPicker(path: DashPath) {
        val allowed = HeadwaySettings.allowedApps(context)
        val manager = context.packageManager
        val providers = WidgetTile.installedProviders(context)
            .filter { it.provider.packageName in allowed }
        val rows = providers.map { info ->
            val app = runCatching {
                manager.getApplicationLabel(
                    manager.getApplicationInfo(info.provider.packageName, 0),
                ).toString()
            }.getOrDefault(info.provider.packageName)
            CarSheet.Row(
                title = runCatching { info.loadLabel(manager) }.getOrNull()
                    ?: info.provider.shortClassName,
                detail = app,
                icon = runCatching { info.loadIcon(context, 0) }.getOrNull(),
            ) {
                closeOverlay()
                addWidget(path, info.provider)
            }
        }
        showOverlay(
            sheet().build(
                title = "Widgets",
                rows = rows.ifEmpty {
                    listOf(
                        CarSheet.Row(
                            title = if (allowed.isEmpty()) {
                                "No apps are allowed yet"
                            } else {
                                "None of the allowed apps has a widget"
                            },
                            detail = "Open Headway on the phone to allow more apps",
                        ) { closeOverlay() },
                    )
                },
                onClose = ::closeOverlay,
            ),
        )
    }

    /**
     * Adds one widget, which happens on the phone -- see [WidgetSetupActivity].
     *
     * The pane is only rewritten once an id comes back bound. A failed attempt
     * leaves the pane exactly as it was, and says so, rather than replacing
     * something the driver could see with a widget pane that is empty.
     */
    private fun addWidget(path: DashPath, provider: ComponentName) {
        showVoiceMessage("Look at the phone — it is asking whether Headway may add widgets")
        WidgetSetup.request(context, provider) { widgetId, bound ->
            // The driver was on the phone while this ran, and a session can end
            // there: a reconnect, a car switched off, an unplugged head unit.
            // Writing to a dismissed Presentation's view tree would take the
            // process with it, and the widget is bound either way -- the next
            // session's picker will find it.
            if (!isShowing) {
                onStep("widget added while the car screen was gone; not placing it")
                return@request
            }
            if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID || bound == null) {
                showVoiceMessage(
                    "The phone's permission dialog was dismissed. " +
                        "Try again, and tick \"Always allow\".",
                )
                return@request
            }
            layout = layout.setPane(
                path,
                PaneKind.WIDGET,
                WidgetTile.argumentFor(widgetId, bound),
            )
            render()
        }
    }

    // --- the app list ---------------------------------------------------------

    private fun showAppPicker(onPicked: (String) -> Unit) {
        val rows = launchableApps().map { (packageName, label, icon) ->
            CarSheet.Row(title = label, icon = icon) { onPicked(packageName) }
        }
        showOverlay(
            sheet().build(
                title = "Apps",
                rows = rows.ifEmpty {
                    listOf(
                        CarSheet.Row(
                            "No apps are allowed yet",
                            "Open Headway on the phone and allow the ones you want here",
                        ) { closeOverlay() },
                    )
                },
                onClose = ::closeOverlay,
            ),
        )
    }

    /**
     * The apps the driver has allowed onto the car screen, and nothing else.
     *
     * Putting an app here is a decision made on the phone while parked -- see
     * `AllowedApps` -- so this is a filter over the launcher list rather than
     * the launcher list itself. An empty result is the correct answer for a
     * fresh install and the picker says so instead of showing everything.
     */
    private fun launchableApps(): List<Triple<String, String, android.graphics.drawable.Drawable?>> {
        val allowed = HeadwaySettings.allowedApps(context)
        if (allowed.isEmpty()) return emptyList()
        val manager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return runCatching {
            manager.queryIntentActivities(intent, 0)
                .asSequence()
                .filter { it.activityInfo?.packageName != context.packageName }
                .filter { it.activityInfo?.packageName in allowed }
                .map { resolved ->
                    Triple(
                        resolved.activityInfo.packageName,
                        resolved.loadLabel(manager).toString(),
                        runCatching { resolved.loadIcon(manager) }.getOrNull(),
                    )
                }
                .distinctBy { it.first }
                .sortedBy { it.second.lowercase() }
                .toList()
        }.getOrDefault(emptyList())
    }

    /**
     * Every package with a launcher entry, by name only.
     *
     * Deliberately *not* [launchableApps]: that one loads a label and a drawable
     * for every app on the phone, and this is called on every rail fill — which
     * happens on a tab switch, on a theme change and after every layout edit, on
     * the thread drawing the car screen. Pruning the rail needs names and
     * nothing else.
     */
    private fun installedPackages(): Set<String> {
        val manager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return runCatching {
            manager.queryIntentActivities(intent, 0)
                .mapNotNullTo(mutableSetOf()) { it.activityInfo?.packageName }
        }.getOrDefault(emptySet())
    }

    private fun showRailItemMenu(item: RailItem) {
        val rows = mutableListOf<CarSheet.Row>()
        if (item is RailItem.Layout) {
            val current = tabs.firstOrNull { it.name == item.name }?.icon
            rows += CarSheet.Row(
                title = "Icon",
                detail = current?.name?.lowercase()?.replaceFirstChar { it.uppercase() }
                    ?: "The layout's name, in words",
            ) { showTabIconPicker(item.name) }
        }
        rows += CarSheet.Row("Unpin", "Take it off the rail", destructive = true) {
            runCatching { store.setRail(Rail.unpin(rail(), item)) }
            fillRail()
            closeOverlay()
        }
        rows += CarSheet.Row("Pinned items", "Reorder everything on the rail") {
            showRailEditor()
        }
        showOverlay(
            sheet().build(title = railLabel(item), rows = rows, onClose = ::closeOverlay),
        )
    }

    /**
     * Chooses the icon a layout wears on the rail, or goes back to its name.
     *
     * On the car screen because that is where the rail is: the reason to want an
     * icon is that six words across an 800-pixel panel do not fit, which is a
     * thing you notice while looking at it.
     *
     * The chips are the icons themselves, drawn at the rail's own size, so the
     * choice is made by looking at the thing rather than at a list of words for
     * it.
     */
    private fun showTabIconPicker(name: String) {
        val layoutFor = tabs.firstOrNull { it.name == name } ?: return
        fun apply(icon: TabIcon?) {
            runCatching {
                // The same guard `saveLayout`, `addLayout` and `deleteLayout`
                // all carry, and this was the one mutator without it.
                // `list()` substitutes the shipped tabs while nothing is
                // stored, and `save()` writes into what *is* stored -- so
                // setting an icon on a fresh install wrote a one-element array,
                // the substitution stopped, and Drive, Media, Phone and App
                // silently vanished.
                if (!store.hasSaved()) store.replaceAll(tabs)
                store.save(layoutFor.copy(iconName = icon?.name))
                DashLayoutStore.announceChanged(this)
            }
            tabs = runCatching { store.list() }.getOrDefault(tabs)
            if (layout.name == name) layout = tabs.firstOrNull { it.name == name } ?: layout
            closeOverlay()
            onStep("car screen: '$name' now shows ${icon?.name?.lowercase() ?: "its name"}")
            fillRail()
        }
        val rows = mutableListOf<CarSheet.Row>()
        rows += CarSheet.Row(
            title = "Use the name",
            detail = "Show \"$name\" in words",
            selected = layoutFor.icon == null,
        ) { apply(null) }
        showOverlay(
            sheet().buildIcons(
                title = "Icon for $name",
                rows = rows,
                icons = TabIcon.ALL.map { icon ->
                    CarSheet.IconChoice(
                        glyph = CarGlyph.forTab(icon) ?: CarGlyph.APPS,
                        description = icon.name.lowercase(),
                        selected = layoutFor.icon == icon,
                    ) { apply(icon) }
                },
                onClose = ::closeOverlay,
            ),
        )
    }

    companion object {

        /**
         * The panes that can put a map in front of the driver.
         *
         * `MAPS` draws the turn card and opens a map app into the app pane;
         * `CAR_APP` is where a navigation app draws its own map, which is the
         * route the driver on the reported car is actually using. `APP` is
         * deliberately absent: a bare app pane may hold anything, so treating
         * it as a map would make the *App* tab the opening one on any phone.
         */
        private val MAP_PANES: Set<String> = setOf(PaneKind.MAPS, PaneKind.CAR_APP)

        /**
         * Installed by `CarVoiceStream` while a session has a microphone.
         *
         * A static hook rather than a constructor parameter for the same reason
         * the launcher's was: the voice pipeline and the car screen are built by
         * different parts of the session bring-up, neither owns the other, and
         * the alternative is threading a callback through `HeadwayService` into
         * a `Presentation` that may not exist yet.
         */
        @Volatile
        var onVoiceRequested: (() -> Unit)? = null

        /** The shell currently on the car display, for the voice command engine. */
        @Volatile
        private var shown: CarShell? = null

        fun active(): CarShell? = shown

        private const val RAIL_TEXT = 0.30f

        /** The date's size relative to the time above it. */
        private const val DATE_FRACTION = 0.55f

        /** Long enough to read a short phrase at a glance, short enough not to linger. */
        private const val BANNER_MILLIS = 2_600L

        /**
         * How long an arriving notification stays over the panes.
         *
         * Longer than the status banner above, because this one carries words a
         * driver has to read rather than a state they already know about, and
         * short enough that it is gone before it becomes something to look at.
         */
        private const val POPUP_MILLIS = 5_000L

        /** The glyph beside a popped notification, in dp. */
        private const val POPUP_ICON_DP = 30f
        private val EMPTY_RECT = PaneRect(0, 0, 0, 0)
    }
}

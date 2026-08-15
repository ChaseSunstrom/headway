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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import dev.headway.app.carapp.CarHostCapability
import dev.headway.app.carapp.TemplateApps
import dev.headway.app.dash.tiles.AppPaneTile
import dev.headway.app.dash.tiles.CarAppTile
import dev.headway.app.dash.tiles.ClockTile
import dev.headway.app.dash.tiles.LauncherTile
import dev.headway.app.dash.tiles.MapsTile
import dev.headway.app.dash.tiles.MediaBrowseTile
import dev.headway.app.dash.tiles.MessagesTile
import dev.headway.app.dash.tiles.NowPlayingTile
import dev.headway.app.dash.tiles.PhoneTile
import dev.headway.app.dash.tiles.WidgetSetup
import dev.headway.app.dash.tiles.WidgetTile
import dev.headway.app.ui.HeadwaySettings
import dev.headway.app.ui.theme.CarMetrics
import dev.headway.app.ui.theme.Headway
import dev.headway.app.ui.theme.HeadwayTheme
import dev.headway.app.video.AppPaneHost
import dev.headway.app.video.CarAppDisplay
import dev.headway.app.video.OverlayDisplay
import dev.headway.dash.DashLayout
import dev.headway.dash.DashNode
import dev.headway.dash.DashPath
import dev.headway.dash.PaneKind
import dev.headway.dash.PaneRect
import dev.headway.dash.Rail
import dev.headway.dash.RailItem
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
        root.addView(banners)
        root.addView(overlayHost)
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

        tabs = store.list()
        layout = store.active()
        releaseOrphanedWidgets()
        column.addView(buildRail())
        column.addView(stage, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        showStartup()
        render()

        // The live pane's rectangle is read from the view that is on screen
        // rather than recomputed, so it has to be republished whenever anything
        // moves: a divider drag, a tab switch, a sheet opening over it.
        root.viewTreeObserver.addOnGlobalLayoutListener { publishAppPaneRect() }
    }

    override fun onStart() {
        super.onStart()
        DashLayoutStore.observeChanges(layoutsChanged)
        HeadwayTheme.observe(themeChanged)
        AppPaneHost.observe(appPaneChanged)
        shown = this
        live.forEach { runCatching { it.start() } }
        onStep("car screen up: ${describe()}")
    }

    override fun onStop() {
        DashLayoutStore.unobserveChanges(layoutsChanged)
        HeadwayTheme.unobserve(themeChanged)
        AppPaneHost.unobserve(appPaneChanged)
        if (shown === this) shown = null
        main.removeCallbacksAndMessages(null)
        // Tiles observe media sessions, notification streams and widget hosts.
        // One left running after the window goes is a listener for the life of
        // the process, and the only symptom is battery.
        live.forEach { runCatching { it.stop() } }
        AppPaneHost.pictureRect = EMPTY_RECT
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
            if (appLayout != null && appLayout.name != layout.name) {
                show(appLayout)
            } else if (appLayout == null) {
                onStep("no layout has an app pane, so $packageName has nowhere to open")
                return
            }
        }
        OverlayDisplay.launch(context, packageName, CarAppDisplay.displayId, onStep)
        // The pane may only have been created a moment ago by show(); binding
        // happens when its surface arrives, so nothing more is needed here.
        main.post { publishAppPaneRect() }
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
     * Settings, the microphone, and the pins.
     *
     * The first two are fixtures at a fixed position, because a control that
     * moves with configuration is a control the driver has to look for. The pins
     * scroll, because six is comfortable on an 800-pixel panel and the driver is
     * allowed a seventh.
     */
    private fun buildRail(): View {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = metrics.gutter / 2
            setPadding(pad, pad, pad, pad / 2)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        bar.addView(
            CarGlyphButton(context, metrics, CarGlyph.SETTINGS, "Settings") { showSettings() },
        )
        micButton = CarGlyphButton(context, metrics, CarGlyph.MIC, "Voice") {
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
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                addView(railItems)
            },
        )
        fillRail()
        return bar
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

    private fun layoutPill(item: RailItem.Layout): View {
        val selected = item.name == layout.name
        return Headway.title(context, metrics.unit, item.name).apply {
            gravity = Gravity.CENTER
            setTextColor(if (selected) Headway.ON_ACCENT else Headway.TEXT_MUTED)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, metrics.unit * RAIL_TEXT)
            minHeight = metrics.touchTargetPx
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            val pad = metrics.gutter
            setPadding(pad, 0, pad, 0)
            contentDescription = item.name
            background = Headway.panel(
                radiusPx = metrics.touchTargetPx / 2f,
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
                val gap = metrics.gutter / 3
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
            val pad = metrics.gutter / 3
            setPadding(pad, pad, pad, pad)
            background = Headway.panel(
                radiusPx = metrics.touchTargetPx / 2f,
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
                metrics.touchTargetPx,
                metrics.touchTargetPx,
            ).apply {
                val gap = metrics.gutter / 3
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
            context = context,
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

    private fun tileFor(leaf: DashNode.Leaf, path: DashPath): DashTile? =
        when (PaneKind.canonical(leaf.kind)) {
            PaneKind.NOW_PLAYING -> NowPlayingTile(context)
            PaneKind.BROWSE -> MediaBrowseTile(context, onStep)
            // A real map when one of the driver's allowed apps offers a car
            // interface -- the app draws its own map into the pane's surface and
            // Headway draws its templates over it -- and the turn card when none
            // does. Both are the Maps pane; which one a phone gets depends on
            // what is installed and allowed, not on what the driver picked.
            PaneKind.MAPS -> mapsTileFor(leaf)
            PaneKind.PHONE -> PhoneTile(context, onStep)
            // No host guard here on purpose: unlike a Maps pane, a Car app pane
            // has nothing to degrade *to* -- its whole content is the car app.
            // CarAppTile itself refuses to bind and says why, which is the
            // honest answer for a pane the driver explicitly asked to be a car
            // app. See CarHostCapability.
            PaneKind.CAR_APP -> CarAppTile(context, leaf.argument, onStep) { service ->
                // The pick has to reach the layout, or it lasts only as long as
                // this view: render() rebuilds tiles on every divider drag and
                // tab switch, and the pane would come back a picker each time.
                rememberPaneArgument(path, PaneKind.CAR_APP, service.flattenToString())
            }
            PaneKind.MESSAGES -> MessagesTile(context)
            PaneKind.CLOCK -> ClockTile(context)
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
        rows += CarSheet.Row("Pinned", "What sits on the rail, and in what order") { showRailEditor() }
        rows += CarSheet.Row("Open an app", "Show a running app in the app pane") {
            showAppPicker { openApp(it) }
        }
        rows += CarSheet.Row("Theme", themeSummary()) { showThemes() }
        rows += CarSheet.Row("Apps and panels", appSourceSummary()) { showAppSettings() }
        rows += CarSheet.Row("About this session", sessionSummary()) { }

        showOverlay(
            sheet().build(title = "Settings", rows = rows, onClose = ::closeOverlay),
        )
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
        )
        showOverlay(sheet().build("Apps and panels", rows, onClose = ::closeOverlay))
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
        runCatching { WidgetTile.releaseOrphans(context, keep, onStep) }
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
        val navigators = TemplateApps.navigators(context)
        if (navigators.isEmpty()) return MapsTile(context, onStep)
        val wanted = leaf.argument?.let { argument ->
            navigators.firstOrNull { it.service.flattenToString() == argument }
                ?: navigators.firstOrNull { it.packageName == argument }
        }
        val app = wanted ?: navigators.first()
        return CarAppTile(context, app.service.flattenToString(), onStep)
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
        showVoiceMessage("Finish adding this widget on the phone")
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
                showVoiceMessage("That widget was not added")
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
        showOverlay(
            sheet().build(
                title = railLabel(item),
                rows = listOf(
                    CarSheet.Row("Unpin", "Take it off the rail", destructive = true) {
                        runCatching { store.setRail(Rail.unpin(rail(), item)) }
                        fillRail()
                        closeOverlay()
                    },
                    CarSheet.Row("Pinned items", "Reorder everything on the rail") {
                        showRailEditor()
                    },
                ),
                onClose = ::closeOverlay,
            ),
        )
    }

    companion object {

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

        /** Long enough to read a short phrase at a glance, short enough not to linger. */
        private const val BANNER_MILLIS = 2_600L
        private val EMPTY_RECT = PaneRect(0, 0, 0, 0)
    }
}

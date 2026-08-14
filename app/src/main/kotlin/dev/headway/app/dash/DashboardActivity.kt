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

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import dev.headway.app.dash.tiles.ClockTile
import dev.headway.app.dash.tiles.LauncherTile
import dev.headway.app.dash.tiles.MapsTile
import dev.headway.app.dash.tiles.MediaBrowseTile
import dev.headway.app.dash.tiles.MessagesTile
import dev.headway.app.dash.tiles.MirrorTile
import dev.headway.app.dash.tiles.NowPlayingTile
import dev.headway.app.dash.tiles.PhoneTile
import dev.headway.app.dash.tiles.WidgetTile
import dev.headway.app.log.SessionLog
import dev.headway.app.ui.CarLauncherActivity
import kotlin.math.max
import kotlin.math.min

/**
 * The tiling surface itself: a [DashLayout] turned into views, with dividers the
 * driver can drag.
 *
 * ## What this draws, and what it deliberately does not
 *
 * Every pane here is Headway painting something itself. ADR 0004 settles why:
 * an unprivileged app cannot place another app's activity on a display it owns —
 * `ActivityTaskSupervisor.isCallerAllowedToLaunchOnDisplay` wants the *target*
 * app to have declared `android:allowEmbedded="true"` — and it cannot set another
 * app's window bounds either, because `TaskOrganizer` and
 * `WindowContainerTransaction` appear in neither `current.txt` nor
 * `system-current.txt`. So this is not a window manager and never will be. It is
 * a layout of Headway's own views, fed by other apps' *data* through
 * user-granted APIs, exactly as `DashTile` describes.
 *
 * The single exception is [DashTile.Kind.MIRROR], which is a hole: `MirrorTile`
 * draws nothing, this activity paints no background behind it, and the phone
 * screen that the car is already mirroring shows through. That is also why the
 * background colour is applied per pane rather than once to the root — a root
 * fill would put black behind the hole and there would be no hole.
 *
 * That only holds when the dashboard is on a display of its own. Until
 * `CarDisplay`'s own-content path is confirmed on hardware (BLOCKERS B-011) the
 * dashboard runs on display 0 and is mirrored like the launcher, and a hole
 * there would be a window onto the dashboard itself — so on the default display
 * the pane becomes `MirrorDoorTile`, which closes the dashboard instead.
 * [tileFor] chooses, and the rest of this file treats a real hole as "the tile
 * is a `MirrorTile`" rather than "the leaf says MIRROR".
 *
 * ## Why nested `LinearLayout`s and not a custom `ViewGroup`
 *
 * A binary split tree maps one-to-one onto nested weighted `LinearLayout`s, and
 * a weight is exactly a [DashNode.Split.ratio]. Dragging a divider is then two
 * float assignments and a `requestLayout`, with no measure pass to write and no
 * chance of the drawn geometry disagreeing with the saved geometry. A bespoke
 * `ViewGroup` would buy one less view per split and cost a measure/layout
 * implementation that has to be right on the first drive.
 *
 * ## Sizes come from the car, not the phone
 *
 * This activity draws on a phone-sized surface that the car sees scaled and
 * letterboxed, so a 48 dp divider here is not 48 dp there. [carMinimumTargetPx]
 * is the same back-computation `CarLauncherActivity` uses, and dividers are one
 * whole car touch target thick because they are the only control on this screen
 * that has to be grabbed rather than merely tapped, by a thumb, in a moving car.
 * That is expensive in pixels; a divider too thin to catch is worse, because the
 * miss lands on whatever pane is underneath and does something else instead.
 *
 * ## Edit mode
 *
 * A long press on a pane opens its menu. The mirror pane is the exception and
 * declines the press: a long press over the hole is meant for the app showing
 * through it — selecting text in a browser, dropping a pin on a map — and
 * swallowing it here would make the largest pane of the default layout behave
 * differently from the phone it is showing. The way in is a long press on any
 * other pane, and when the whole dashboard is a single mirror leaf there is no
 * other pane, so in that one case the mirror does take the press rather than
 * leaving the driver with a dashboard they cannot edit.
 *
 * Edit mode itself only changes what is visible: `MirrorTile` outlines and names
 * itself, and the divider grips brighten. Nothing about the edits requires it —
 * it exists so a driver rearranging panes can see the one pane that is invisible
 * by construction.
 */
class DashboardActivity : AppCompatActivity() {

    private val store: DashLayoutStore by lazy { DashLayoutStore.of(this) }

    private lateinit var root: FrameLayout

    /** What is on screen. Always equals what is in the store for this name. */
    private var layout: DashLayout = DashLayoutStore.DEFAULT

    /**
     * The tiles of the current render, in leaf order.
     *
     * Held so they can be stopped. Tiles observe media sessions, notification
     * streams and widget updates; one that is dropped without [DashTile.stop] is
     * a listener that keeps running for the life of the process, and the driver's
     * only symptom is a battery figure.
     */
    private val live = mutableListOf<DashTile>()

    private var started = false
    private var editing = false

    /**
     * Whether a mirror hole has already been placed in this render.
     *
     * `DashTile.Kind.MIRROR` says the dashboard enforces the one-mirror rule.
     * Storage can still hold two after a hand edit or a layout written by some
     * future build, and two transparent panes showing the same display is a
     * dashboard with a missing half rather than an error, so the second is
     * rendered as a note saying so.
     */
    private var mirrorRendered = false

    /**
     * A widget part way through being added.
     *
     * The bind-permission dialog and the provider's own configure activity both
     * return through [onActivityResult], so the id, the provider and what to do
     * with the finished leaf have to outlive the call that started them. If the
     * process dies in between, the id is stranded — [releaseUnusedWidgets] on the
     * next start is what collects it.
     */
    private var pendingWidget: PendingWidget? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The car shows whatever the phone shows, so a phone that dims takes the
        // car screen with it. Same reasoning as CarLauncherActivity.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = FrameLayout(this)
        setContentView(root)
        layout = store.active()
        render()
        // Ids stranded by a crash between allocating and saving are invisible
        // everywhere else; WidgetTile.releaseOrphans asks to be run at start-up
        // for exactly this reason.
        releaseUnusedWidgets()
    }

    override fun onStart() {
        super.onStart()
        started = true
        live.forEach { tile ->
            runCatching { tile.start() }.onFailure {
                SessionLog.shared.error(TAG, "the ${tile.kind} pane failed to start", it)
            }
        }
    }

    override fun onStop() {
        SessionLog.shared.info(TAG, describe())
        started = false
        live.forEach { tile ->
            runCatching { tile.stop() }.onFailure {
                SessionLog.shared.error(TAG, "the ${tile.kind} pane failed to stop", it)
            }
        }
        super.onStop()
    }

    /**
     * The widget flows, which are the only things here that leave the activity.
     *
     * The deprecated request-code API rather than `registerForActivityResult`
     * because `WidgetTile.configure` is documented as having no choice:
     * `AppWidgetHost.startAppWidgetConfigureActivityForResult` is the only route
     * that carries the host's temporary permission to start a configure activity
     * the provider has not exported, and it takes an `Activity` and a request
     * code. Running the bind dialog through the modern API and the configure step
     * through this one would put one flow in two places.
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_BIND_WIDGET ->
                if (resultCode == RESULT_OK) configureOrFinishWidget() else abandonWidget()

            REQUEST_CONFIGURE_WIDGET ->
                if (resultCode == RESULT_OK) finishWidget() else abandonWidget()

            // A pane that was already on screen asked for the binding grant.
            // WidgetTile.start is documented as the way to re-attempt a binding
            // the user has since approved, so this is a retry, not a rebuild.
            REQUEST_REBIND_WIDGET -> if (resultCode == RESULT_OK) {
                live.filterIsInstance<WidgetTile>().forEach { runCatching { it.start() } }
            }
        }
    }

    /** One line for the session log: which layout, and how each pane is faring. */
    fun describe(): String {
        val panes = if (live.isEmpty()) "no panes" else live.joinToString("; ") { it.describe() }
        return "dashboard \"${layout.name}\"${if (editing) " (editing)" else ""}: $panes"
    }

    // --- rendering ------------------------------------------------------------

    /**
     * Throws the whole view tree away and builds it again.
     *
     * A full rebuild rather than a diff because the structural edits are rare and
     * the tree is small, whereas a partial update that gets the paths wrong
     * produces a dashboard whose dividers save the wrong ratios — a bug the driver
     * would only find on the next drive. The cost is that a pane's transient
     * state goes with it, which is why dragging a divider does *not* re-render:
     * the weights on screen are already the new ones.
     */
    private fun render() {
        live.forEach { tile ->
            runCatching { tile.stop() }.onFailure {
                SessionLog.shared.error(TAG, "the ${tile.kind} pane failed to stop", it)
            }
        }
        live.clear()
        mirrorRendered = false
        root.removeAllViews()
        root.addView(
            buildNode(layout.root, emptyList()),
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT),
        )
        if (started) {
            live.forEach { tile ->
                runCatching { tile.start() }.onFailure {
                    SessionLog.shared.error(TAG, "the ${tile.kind} pane failed to start", it)
                }
            }
        }
    }

    /**
     * @param path the choices taken from the root to reach [node] — false for
     *   [DashNode.Split.first], true for second. This is the addressing
     *   [DashLayout.withRatio] documents, and tracking it on the way down is the
     *   only place it can be built without searching the tree afterwards.
     */
    private fun buildNode(node: DashNode, path: List<Boolean>): View = when (node) {
        is DashNode.Leaf -> buildLeaf(node, path)
        is DashNode.Split -> buildSplit(node, path)
    }

    private fun buildSplit(split: DashNode.Split, path: List<Boolean>): View {
        // HORIZONTAL reads as "children side by side, divider vertical", which is
        // the LinearLayout.HORIZONTAL sense and the reading DashLayoutStore.DEFAULT
        // is written against. Taking it the other way rotates the shipped layout.
        val sideBySide = split.orientation == DashNode.Orientation.HORIZONTAL
        val container = LinearLayout(this).apply {
            orientation = if (sideBySide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            // Pinned so the two weights are read as fractions of one whole. Without
            // it LinearLayout divides by whatever the weights happen to sum to, and
            // a rounding error mid-drag would rescale both panes.
            weightSum = 1f
        }

        val ratio = split.clampedRatio
        val firstParams = weightedParams(sideBySide, ratio)
        val secondParams = weightedParams(sideBySide, 1f - ratio)

        container.addView(buildNode(split.first, path + false), firstParams)
        container.addView(buildDivider(split, path, container, sideBySide, firstParams, secondParams))
        container.addView(buildNode(split.second, path + true), secondParams)
        return container
    }

    /**
     * A pane, its tile, and its long-press menu.
     *
     * The background is painted here rather than on the root so that the mirror
     * pane can be left unpainted; see the class KDoc.
     */
    private fun buildLeaf(leaf: DashNode.Leaf, path: List<Boolean>): View {
        val pane = FrameLayout(this)
        val duplicateMirror = leaf.kind == DashTile.Kind.MIRROR && mirrorRendered
        val tile = if (duplicateMirror) {
            null
        } else {
            tileFor(
                context = this,
                kind = leaf.kind,
                argument = leaf.argument,
                editing = editing,
                onStep = ::step,
                // Leaving the dashboard is what "show me the phone screen"
                // means while the dashboard is itself the thing being
                // mirrored. See tileFor's MIRROR branch.
                onLeave = { finish() },
            )
        }
        // A hole only when the pane really is transparent. On the default
        // display the mirror pane is a door instead, and a door needs its
        // background painted like any other pane.
        val hole = tile is MirrorTile
        if (hole) {
            mirrorRendered = true
        } else {
            // Painted per pane, never on the root, so the hole stays a hole.
            pane.setBackgroundColor(BACKGROUND)
        }

        if (tile == null) {
            val excuse = if (duplicateMirror) {
                "Only one pane can show the phone screen."
            } else {
                // A layout written by a build with a tile kind this one does not
                // have. DashLayout.fromJson keeps such a leaf on purpose rather
                // than failing the whole file, so the pane has to say something.
                "This pane needs a newer Headway: ${leaf.kind}"
            }
            pane.addView(placeholder(excuse), centredParams())
        } else {
            live += tile
            if (tile is WidgetTile) {
                // A pane that comes up needing the binding grant can ask for it
                // and repair itself, which a tile with no Activity cannot do.
                tile.onBindPermissionNeeded = { request ->
                    @Suppress("DEPRECATION")
                    startActivityForResult(request, REQUEST_REBIND_WIDGET)
                }
            }
            pane.addView(tile.createView(this), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }

        // See the class KDoc: the hole's long presses belong to the app behind it,
        // unless there is nowhere else to press or the driver is already editing.
        val passesTouchThrough = hole && !editing && layout.root is DashNode.Split
        if (!passesTouchThrough) {
            pane.setOnLongClickListener {
                showPaneMenu(path, leaf)
                true
            }
        }
        return pane
    }

    /**
     * The grab handle between a split's two children.
     *
     * The view is one whole car touch target thick, but only a narrow pill inside
     * it is painted. A bar that wide drawn solid reads as a structural gap the
     * driver has to account for; drawn as a hairline with a large invisible catch
     * area it reads as a seam, which is what it is.
     *
     * The ratio is recomputed from the distance dragged rather than from the
     * touch's absolute position, so nothing here needs to know where the
     * container sits on screen — which matters because it sits somewhere
     * different in every layout.
     */
    @Suppress("ClickableViewAccessibility")
    private fun buildDivider(
        split: DashNode.Split,
        path: List<Boolean>,
        container: LinearLayout,
        sideBySide: Boolean,
        firstParams: LinearLayout.LayoutParams,
        secondParams: LinearLayout.LayoutParams,
    ): View {
        val thickness = dividerThicknessPx()
        val bar = LinearLayout(this).apply {
            orientation = if (sideBySide) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(BACKGROUND)
            // The pane menu is a long press and this is a drag, so an explicit
            // description is the only thing that names the control for a screen
            // reader. There is no click to perform, which is why the
            // accessibility lint above is suppressed rather than satisfied.
            contentDescription = if (sideBySide) {
                "Divider. Drag left or right to resize."
            } else {
                "Divider. Drag up or down to resize."
            }
        }

        val gripThickness = max(2, (thickness * GRIP_THICKNESS_FRACTION).toInt())
        val gripLength = (thickness * GRIP_LENGTH_MULTIPLIER).toInt()
        bar.addView(
            View(this).apply {
                background = GradientDrawable().apply {
                    setColor(if (editing) GRIP_EDITING else GRIP)
                    cornerRadius = gripThickness / 2f
                }
            },
            if (sideBySide) {
                LinearLayout.LayoutParams(gripThickness, gripLength)
            } else {
                LinearLayout.LayoutParams(gripLength, gripThickness)
            },
        )

        var downAt = 0f
        var downRatio = split.clampedRatio
        var ratio = downRatio
        bar.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = if (sideBySide) event.rawX else event.rawY
                    // Read from the live params, not from the node: an earlier
                    // drag moved the weights without rebuilding the tree, so the
                    // node's ratio is one gesture out of date.
                    downRatio = firstParams.weight
                    ratio = downRatio
                    // Nothing above intercepts today, but a pane whose tile is a
                    // scroller might tomorrow, and losing the divider mid-drag is
                    // a pane that snaps back for no visible reason.
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val span = if (sideBySide) {
                        container.width - view.width
                    } else {
                        container.height - view.height
                    }
                    // Zero before the first layout pass, and dividing by it would
                    // send the ratio to infinity and pin the pane at a limit.
                    if (span > 0) {
                        val moved = (if (sideBySide) event.rawX else event.rawY) - downAt
                        ratio = (downRatio + moved / span)
                            .coerceIn(DashNode.MIN_RATIO, DashNode.MAX_RATIO)
                        firstParams.weight = ratio
                        secondParams.weight = 1f - ratio
                        // Mutating the weight fields in place does not invalidate
                        // anything on its own; setLayoutParams would, but it would
                        // also allocate two objects per motion event.
                        container.requestLayout()
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    // Cancel saves too. A gesture the system took away mid-drag
                    // still leaves the panes where the driver dragged them, and a
                    // dashboard that silently reverts on the next start is worse
                    // than one that keeps an ending nobody chose.
                    persistRatio(path, ratio)
                    true
                }

                else -> false
            }
        }

        bar.layoutParams = if (sideBySide) {
            LinearLayout.LayoutParams(thickness, MATCH_PARENT)
        } else {
            LinearLayout.LayoutParams(MATCH_PARENT, thickness)
        }
        return bar
    }

    private fun weightedParams(sideBySide: Boolean, weight: Float): LinearLayout.LayoutParams =
        // The measured dimension along the split axis is zero so that the whole of
        // it is decided by the weight; the cross axis fills.
        if (sideBySide) {
            LinearLayout.LayoutParams(0, MATCH_PARENT, weight)
        } else {
            LinearLayout.LayoutParams(MATCH_PARENT, 0, weight)
        }

    // The parameter is not called `text`: inside `apply` the receiver's own `text`
    // property is the nearer scope, so `text = text` would silently assign the
    // view's empty string to itself.
    private fun placeholder(message: String): TextView = TextView(this).apply {
        text = message
        setTextColor(DIM)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, carTextSizePx(PLACEHOLDER_SP))
        gravity = Gravity.CENTER
        val pad = carMinimumTargetPx() / 3
        setPadding(pad, pad, pad, pad)
    }

    private fun centredParams(): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { gravity = Gravity.CENTER }

    // --- editing --------------------------------------------------------------

    private fun showPaneMenu(path: List<Boolean>, leaf: DashNode.Leaf) {
        val menu = ActionList()
        menu.add("Change tile…") { changeTile(path, leaf) }
        menu.add("Split left and right") { splitPane(path, DashNode.Orientation.HORIZONTAL) }
        menu.add("Split top and bottom") { splitPane(path, DashNode.Orientation.VERTICAL) }
        // An empty path is the root, and removing the root leaves no dashboard.
        if (path.isNotEmpty()) menu.add("Remove this pane") { removePane(path) }
        menu.add(if (editing) "Hide pane outlines" else "Show pane outlines") { setEditing(!editing) }
        menu.add("Layouts…") { showLayoutsMenu() }
        showMenu(DashTile.Kind.describe(leaf.kind), menu)
    }

    private fun setEditing(on: Boolean) {
        if (editing == on) return
        editing = on
        // A rebuild rather than poking MirrorTile.editMode, because edit mode also
        // decides the grip colour and whether the mirror pane accepts a long press,
        // and those are decided while the views are being built.
        render()
    }

    private fun changeTile(path: List<Boolean>, leaf: DashNode.Leaf) {
        showKindPicker("What goes in this pane?", leaf) { kind ->
            placeLeaf(kind) { replacement ->
                commit(layout.root.replacedAt(path, replacement), "changed a pane to $kind")
            }
        }
    }

    private fun splitPane(path: List<Boolean>, orientation: DashNode.Orientation) {
        // The kind is chosen before the split rather than after, so the driver
        // never sees a placeholder pane appear and then change under them, and so
        // a cancelled picker leaves the layout exactly as it was.
        showKindPicker("What goes in the new pane?", null) { kind ->
            placeLeaf(kind) { addition ->
                val existing = layout.root.nodeAt(path) ?: return@placeLeaf
                val split = DashNode.Split(orientation, NEW_SPLIT_RATIO, existing, addition)
                commit(layout.root.replacedAt(path, split), "split a pane into $kind")
            }
        }
    }

    private fun removePane(path: List<Boolean>) {
        val remaining = layout.root.removedAt(path)
        if (remaining == null) {
            toast("A dashboard needs at least one pane")
            return
        }
        // Any widget id the removed pane held is collected by commit's sweep, which
        // works from the saved layouts rather than from this edit — so an id that
        // another saved layout still references is correctly left alone.
        commit(remaining, "removed a pane")
    }

    /**
     * Offers the tile kinds, minus the ones that cannot go here.
     *
     * @param replacing the leaf being replaced, or null when a pane is being
     *   added. It matters only for the mirror: swapping a mirror for a mirror is
     *   allowed, adding a second one is not.
     */
    private fun showKindPicker(
        title: String,
        replacing: DashNode.Leaf?,
        onPicked: (String) -> Unit,
    ) {
        val mirrors = layout.leaves().count { it.kind == DashTile.Kind.MIRROR }
        val mirrorAvailable = mirrors == 0 || (mirrors == 1 && replacing?.kind == DashTile.Kind.MIRROR)
        val kinds = DashTile.Kind.ALL.filter { it != DashTile.Kind.MIRROR || mirrorAvailable }
        val menu = ActionList()
        kinds.forEach { kind -> menu.add(DashTile.Kind.describe(kind)) { onPicked(kind) } }
        showMenu(title, menu)
    }

    /**
     * Turns a chosen kind into a leaf, which for a widget takes several dialogs
     * and a trip through another app.
     */
    private fun placeLeaf(kind: String, place: (DashNode.Leaf) -> Unit) {
        if (kind == DashTile.Kind.WIDGET) chooseWidget(place) else place(DashNode.Leaf(kind))
    }

    // --- widgets --------------------------------------------------------------

    private fun chooseWidget(place: (DashNode.Leaf) -> Unit) {
        val providers = WidgetTile.installedProviders(this)
        if (providers.isEmpty()) {
            toast("No widgets are installed")
            return
        }
        val menu = ActionList()
        providers.forEach { info ->
            menu.add(info.loadLabel(packageManager)) { beginWidget(info, place) }
        }
        showMenu("Choose a widget", menu)
    }

    /**
     * Allocates an id and gets as far as it can without leaving the activity.
     *
     * From here the id is owned by [pendingWidget] and every exit has to either
     * finish or [abandonWidget]; `WidgetTile.allocate` is explicit that an id
     * dropped on the floor stays allocated forever.
     */
    private fun beginWidget(info: AppWidgetProviderInfo, place: (DashNode.Leaf) -> Unit) {
        val provider: ComponentName = info.provider ?: run {
            toast("That widget does not name a provider")
            return
        }
        val widgetId = WidgetTile.allocate(this, ::step)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            toast("Could not reserve a widget slot")
            return
        }
        pendingWidget = PendingWidget(widgetId, provider, place)
        if (WidgetTile.bind(this, widgetId, provider)) {
            configureOrFinishWidget()
        } else {
            @Suppress("DEPRECATION")
            startActivityForResult(
                WidgetTile.bindPermissionIntent(widgetId, provider),
                REQUEST_BIND_WIDGET,
            )
        }
    }

    /**
     * Runs the provider's configure activity when it has one.
     *
     * `WidgetTile.configure` returns false both when there is nothing to
     * configure and when the system refused to start it. Both cases end the same
     * way — keep the widget, show whatever it renders unconfigured — because a
     * widget that cannot be configured is still a widget, and discarding the id
     * here would leave the driver with a pane they asked for and did not get.
     */
    private fun configureOrFinishWidget() {
        val pending = pendingWidget ?: return
        if (!WidgetTile.configure(this, pending.widgetId, REQUEST_CONFIGURE_WIDGET)) finishWidget()
    }

    private fun finishWidget() {
        val pending = pendingWidget ?: return
        pendingWidget = null
        pending.place(WidgetTile.leafFor(pending.widgetId, pending.provider))
    }

    /** Gives back an id whose flow the user cancelled. */
    private fun abandonWidget() {
        val pending = pendingWidget ?: return
        pendingWidget = null
        WidgetTile.forget(this, pending.widgetId, ::step)
    }

    /**
     * Hands back every widget id no saved layout refers to.
     *
     * `WidgetTile.releaseOrphans` insists the keep set be *every* id in *every*
     * saved layout plus anything a picker is holding, and it means it: a partial
     * set unbinds widgets that are still in use, and the driver's only symptom is
     * panes that come back empty on the next drive. The on-screen layout is added
     * as well even though [commit] saves before sweeping, because [saveAs] can
     * leave the screen showing a layout under a name the store has not seen yet.
     */
    private fun releaseUnusedWidgets() {
        val keep = mutableSetOf<Int>()
        (store.list() + layout).forEach { saved ->
            saved.leaves()
                .filter { it.kind == DashTile.Kind.WIDGET }
                .forEach { keep += WidgetTile.widgetIdOf(it.argument) }
        }
        pendingWidget?.let { keep += it.widgetId }
        keep -= AppWidgetManager.INVALID_APPWIDGET_ID
        WidgetTile.releaseOrphans(this, keep, ::step)
    }

    // --- layouts --------------------------------------------------------------

    private fun showLayoutsMenu() {
        val menu = ActionList()
        menu.add("Save as a new layout…") { saveAs() }
        menu.add("Switch layout…") { switchLayout() }
        menu.add("Delete a layout…") { deleteLayout() }
        showMenu("Layouts", menu)
    }

    private fun saveAs() {
        val field = EditText(this).apply {
            setSingleLine()
            setHint("Layout name")
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, carTextSizePx(FIELD_SP))
        }
        AlertDialog.Builder(this)
            .setTitle("Save this arrangement as")
            .setView(field)
            .setPositiveButton("Save") { _, _ ->
                val name = field.text.toString().trim()
                if (name.isEmpty()) {
                    toast("A layout needs a name")
                    return@setPositiveButton
                }
                // The store keys on the name, so saving over an existing one is
                // how a driver edits it rather than an error.
                layout = DashLayout(name, layout.root)
                store.save(layout)
                store.setActive(name)
                SessionLog.shared.info(TAG, "saved the dashboard as \"$name\"")
                toast("Saved as $name")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun switchLayout() {
        val all = store.list()
        val names = all.map { it.name }
        AlertDialog.Builder(this)
            .setTitle("Show which layout")
            .setSingleChoiceItems(names.toTypedArray(), names.indexOf(layout.name)) { dialog, which ->
                dialog.dismiss()
                store.setActive(names[which])
                layout = all[which]
                SessionLog.shared.info(TAG, "switched the dashboard to \"${layout.name}\"")
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteLayout() {
        val names = store.list().map { it.name }
        val menu = ActionList()
        names.forEach { name -> menu.add(name) { confirmDelete(name) } }
        showMenu("Delete which layout", menu)
    }

    private fun confirmDelete(name: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete \"$name\"?")
            .setMessage(
                "The arrangement is forgotten. Any widget in it that no other " +
                    "layout uses is handed back to the app that provides it.",
            )
            .setPositiveButton("Delete") { _, _ ->
                store.delete(name)
                // Reload before sweeping: the sweep keeps whatever is on screen,
                // and what is on screen has just changed if this was the active one.
                if (name == layout.name) {
                    layout = store.active()
                    render()
                }
                releaseUnusedWidgets()
                SessionLog.shared.info(TAG, "deleted the layout \"$name\"")
            }
            .setNegativeButton("Keep", null)
            .show()
    }

    // --- persistence ----------------------------------------------------------

    /** Saves a structural edit, redraws, and collects anything it orphaned. */
    private fun commit(updated: DashNode, note: String) {
        layout = layout.copy(root = updated)
        store.save(layout)
        // Pinned explicitly so that a first edit made while the virtual default was
        // on screen leaves the store naming the layout the driver is looking at.
        store.setActive(layout.name)
        SessionLog.shared.info(TAG, "$note in \"${layout.name}\"")
        render()
        releaseUnusedWidgets()
    }

    /**
     * Writes a dragged divider back.
     *
     * Deliberately without a re-render: the weights on screen are already the new
     * ones, and rebuilding here would tear down and restart every tile at the end
     * of every gesture.
     */
    private fun persistRatio(path: List<Boolean>, ratio: Float) {
        layout = layout.withRatio(path, ratio)
        store.save(layout)
        store.setActive(layout.name)
        SessionLog.shared.info(
            TAG,
            "divider ${pathName(path)} moved to ${(ratio * 100).toInt()}% in \"${layout.name}\"",
        )
    }

    private fun pathName(path: List<Boolean>): String =
        if (path.isEmpty()) "at the root" else path.joinToString("/") { if (it) "second" else "first" }

    // --- sizing ---------------------------------------------------------------

    /**
     * Phone pixels that arrive at the head unit as 48 dp.
     *
     * Copied in form from `CarLauncherActivity.carMinimumTargetPx`, and the `min`
     * has to stay the same expression `TouchTransform` uses: if the two disagree
     * then the dividers drawn here are not the dividers the car's touches land on.
     *
     * Falls back to the phone's own 48 dp when the session has not supplied the
     * car geometry, which is also the case when the dashboard is opened on the
     * phone to arrange it before a drive.
     */
    private fun carMinimumTargetPx(): Int {
        val phoneMinimum = (MIN_TOUCH_TARGET_DP * resources.displayMetrics.density).toInt()
        val carWidth = intent.getIntExtra(CarLauncherActivity.EXTRA_CAR_WIDTH, 0)
        val carHeight = intent.getIntExtra(CarLauncherActivity.EXTRA_CAR_HEIGHT, 0)
        val carDpi = intent.getIntExtra(CarLauncherActivity.EXTRA_CAR_DENSITY_DPI, 0)
        if (carWidth <= 0 || carHeight <= 0 || carDpi <= 0) return phoneMinimum

        val phoneWidth = resources.displayMetrics.widthPixels.toDouble()
        val phoneHeight = resources.displayMetrics.heightPixels.toDouble()
        val projectionScale = min(carWidth / phoneWidth, carHeight / phoneHeight)
        if (projectionScale <= 0.0) return phoneMinimum

        val carTargetPx = MIN_TOUCH_TARGET_DP * carDpi / 160.0
        return max(phoneMinimum, (carTargetPx / projectionScale).toInt())
    }

    /**
     * One whole car touch target.
     *
     * Not scaled down. A divider is the one thing on this screen that must be
     * caught rather than aimed at, and a miss does not do nothing — it lands on a
     * pane and operates whatever is there.
     */
    private fun dividerThicknessPx(): Int = carMinimumTargetPx()

    /** Text scaled the same way the targets are, so it stays legible in the car. */
    private fun carTextSizePx(sp: Float): Float {
        // applyDimension rather than scaledDensity, which is deprecated on API 35.
        val phonePx =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
        val inflation = carMinimumTargetPx().toDouble() /
            (MIN_TOUCH_TARGET_DP * resources.displayMetrics.density)
        return (phonePx * inflation).toFloat()
    }

    // --- odds and ends --------------------------------------------------------

    private fun showMenu(title: String, menu: ActionList) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(menu.labels()) { _, which -> menu.select(which) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun step(message: String) {
        SessionLog.shared.info(TAG, message)
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    /**
     * Labels paired with what they do.
     *
     * `setItems` addresses its entries by index, and the menus here vary — the
     * remove entry is absent at the root, the mirror entry is absent when one is
     * already placed — so an index-to-action `when` would have to be rewritten
     * every time an entry became conditional. Keeping the action beside its label
     * makes that class of mistake impossible rather than merely unlikely.
     */
    private class ActionList {
        private val items = mutableListOf<Pair<String, () -> Unit>>()

        fun add(label: String, action: () -> Unit) {
            items += label to action
        }

        fun labels(): Array<String> = items.map { it.first }.toTypedArray()

        /** Does nothing for an index the dialog should never produce. */
        fun select(index: Int) {
            items.getOrNull(index)?.second?.invoke()
        }
    }

    /** A widget id the driver has asked for but has not finished paying for. */
    private class PendingWidget(
        val widgetId: Int,
        val provider: ComponentName,
        /** What to do with the leaf once the id is bound and configured. */
        val place: (DashNode.Leaf) -> Unit,
    )

    companion object {
        private const val TAG = "HeadwayDashboard"

        private const val MIN_TOUCH_TARGET_DP = 48
        private const val PLACEHOLDER_SP = 16f
        private const val FIELD_SP = 18f

        /** A new split divides evenly; the driver drags from there. */
        private const val NEW_SPLIT_RATIO = 0.5f

        /**
         * How much of the divider's width the painted pill occupies.
         *
         * About 6 dp of a 48 dp catch area, which is a visible seam rather than a
         * bar. The rest is invisible and exists only to be hit.
         */
        private const val GRIP_THICKNESS_FRACTION = 0.12

        /** The pill's length along the divider, as a multiple of its thickness. */
        private const val GRIP_LENGTH_MULTIPLIER = 1.5

        private val BACKGROUND: Int = 0xFF000000.toInt()
        private val TEXT: Int = 0xFFFFFFFF.toInt()
        private val DIM: Int = 0xFFB0BEC5.toInt()
        private val GRIP: Int = 0xFF546E7A.toInt()

        /** The launcher's "good" green, so edit mode reads as the same product. */
        private val GRIP_EDITING: Int = 0xFF7BE38B.toInt()

        private const val REQUEST_BIND_WIDGET = 0x0D01
        private const val REQUEST_CONFIGURE_WIDGET = 0x0D02
        private const val REQUEST_REBIND_WIDGET = 0x0D03

        /**
         * Opens the dashboard, telling it the geometry the car will display it at.
         *
         * The extras are `CarLauncherActivity`'s rather than a second set of the
         * same three strings. There is one geometry contract between the session
         * and the car-facing surfaces, and two copies of it would drift the first
         * time one was renamed.
         */
        fun intent(
            context: Context,
            carWidth: Int = 0,
            carHeight: Int = 0,
            carDensityDpi: Int = 0,
        ): Intent = Intent(context, DashboardActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(CarLauncherActivity.EXTRA_CAR_WIDTH, carWidth)
            .putExtra(CarLauncherActivity.EXTRA_CAR_HEIGHT, carHeight)
            .putExtra(CarLauncherActivity.EXTRA_CAR_DENSITY_DPI, carDensityDpi)
    }
}

// --- tree edits ---------------------------------------------------------------
//
// Pure functions on the tree, kept out of the activity so they can be reasoned
// about — and tested — without a Context. They use the same addressing
// DashLayout.withRatio documents: a path is the sequence of child choices from
// the root, false for first and true for second, which is the only stable way to
// name a node in a tree that is rebuilt on every edit.

/** The node at [path], or null when the path runs off the end of the tree. */
fun DashNode.nodeAt(path: List<Boolean>): DashNode? {
    var node: DashNode = this
    for (step in path) {
        val split = node as? DashNode.Split ?: return null
        node = if (step) split.second else split.first
    }
    return node
}

/**
 * A copy of the tree with [replacement] at [path].
 *
 * A path that does not resolve returns the tree unchanged rather than throwing.
 * These are driven by menus built from a tree that may since have been edited by
 * a second dialog, and a stale path should cost the driver one tap, not the app.
 */
fun DashNode.replacedAt(path: List<Boolean>, replacement: DashNode): DashNode {
    if (path.isEmpty()) return replacement
    val split = this as? DashNode.Split ?: return this
    val rest = path.drop(1)
    return if (path.first()) {
        split.copy(second = split.second.replacedAt(rest, replacement))
    } else {
        split.copy(first = split.first.replacedAt(rest, replacement))
    }
}

/**
 * A copy of the tree with the node at [path] gone, or null when that would empty
 * it.
 *
 * Removing one child of a split removes the split as well: the surviving sibling
 * takes the parent's place and inherits its share of the screen. The alternative
 * — a split with one child — is a divider with nothing on one side of it.
 *
 * The parent's ratio is discarded with it, which is correct. It described how to
 * divide space between two panes and one of them no longer exists.
 */
fun DashNode.removedAt(path: List<Boolean>): DashNode? {
    if (path.isEmpty()) return null
    val parentPath = path.dropLast(1)
    val parent = nodeAt(parentPath) as? DashNode.Split ?: return this
    val survivor = if (path.last()) parent.first else parent.second
    return replacedAt(parentPath, survivor)
}

/**
 * Builds the tile for a saved leaf, or null when this build has no such kind.
 *
 * Null is a real answer and not a failure: `DashTile.Kind` stores kinds as
 * strings precisely so that a layout written by a build with a tile this one does
 * not have loses one pane rather than the whole file, and the dashboard renders
 * the unresolved pane as a note saying so.
 *
 * The [context] parameter is here because the tiles need one and a top-level
 * function cannot conjure it — `NowPlayingTile`, `MessagesTile` and `ClockTile`
 * each take the application context off it at construction. [editing] and
 * [onStep] are defaulted so a caller that only wants a tile for a kind can ignore
 * both; they exist because `MirrorTile` is invisible unless told it is being
 * edited, and because the hosted tiles narrate themselves into the exportable
 * log rather than into logcat.
 */
fun tileFor(
    context: Context,
    kind: String,
    argument: String? = null,
    editing: Boolean = false,
    onStep: (String) -> Unit = {},
    /** Invoked by the mirror pane on the default display; see below. */
    onLeave: () -> Unit = {},
): DashTile? = when (kind) {
    DashTile.Kind.NOW_PLAYING -> NowPlayingTile(context)
    DashTile.Kind.BROWSE -> MediaBrowseTile(context, onStep)
    DashTile.Kind.MAPS -> MapsTile(context, onStep)
    DashTile.Kind.PHONE -> PhoneTile(context, onStep)
    DashTile.Kind.MESSAGES -> MessagesTile(context)
    DashTile.Kind.CLOCK -> ClockTile(context)
    DashTile.Kind.LAUNCHER -> LauncherTile(onStep)
    // Via the companion factory rather than the constructor: the id and the
    // provider are two halves of one saved argument, and WidgetTile owns that
    // encoding. It always returns a tile, including for an argument it cannot
    // read, which is why this branch has no null case of its own.
    DashTile.Kind.WIDGET -> WidgetTile.of(DashNode.Leaf(kind, argument), onStep)

    // A hole only makes sense when the dashboard is on a display of its own and
    // something composites it over the capture of display 0. Until CarDisplay's
    // own-content path is confirmed on hardware (BLOCKERS B-011) the dashboard
    // runs on display 0 and is mirrored like the launcher — and a hole there
    // would be a window onto itself.
    //
    // So on the default display the pane becomes the door out instead: it says
    // what it is and, when tapped, leaves the dashboard, which reveals whatever
    // app is behind it and turns the car screen back into a plain mirror. Same
    // pane, same place in the layout, and the meaning a driver would guess.
    DashTile.Kind.MIRROR ->
        if (onMirroredDisplay(context)) MirrorDoorTile(onLeave) else MirrorTile(editing)

    else -> null
}

/**
 * What the mirror pane becomes while the dashboard is itself being mirrored.
 *
 * [MirrorTile] is a hole: it draws nothing so the phone screen shows through.
 * That works when the dashboard is on a display of its own and something
 * composites it over a capture of display 0. Until `CarDisplay`'s own-content
 * path is confirmed on this phone (BLOCKERS B-011) the dashboard runs *on*
 * display 0, so a hole would be a window onto the dashboard itself.
 *
 * Rather than draw a pane that lies about what it is, the same rectangle becomes
 * the way out: a labelled button that closes the dashboard, revealing whatever
 * app is behind it and leaving the car screen as a plain mirror. The driver
 * loses nothing they had — there was never a live app in that rectangle to lose
 * — and the gesture is the one they would guess.
 */
private class MirrorDoorTile(private val onLeave: () -> Unit) : DashTile {

    override val kind: String get() = DashTile.Kind.MIRROR

    private var taps = 0

    override fun createView(context: Context): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        addView(
            TextView(context).apply {
                text = DashTile.Kind.describe(DashTile.Kind.MIRROR)
                setTextColor(FOREGROUND)
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SP)
            }
        )
        addView(
            TextView(context).apply {
                text = "Tap to leave the dashboard and mirror the phone"
                setTextColor(SUBDUED)
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, SUBTITLE_SP)
            }
        )
        setOnClickListener {
            taps++
            onLeave()
        }
    }

    override fun start() = Unit

    override fun stop() = Unit

    override fun describe(): String = "phone screen: door, tapped $taps time(s)"

    private companion object {
        const val TITLE_SP = 22f
        const val SUBTITLE_SP = 13f
        val FOREGROUND = android.graphics.Color.rgb(0xEC, 0xEC, 0xF0)
        val SUBDUED = android.graphics.Color.rgb(0x9A, 0x9A, 0xA4)
    }
}

/**
 * Whether this context is on the phone's own display rather than one Headway
 * created.
 *
 * `Display.DEFAULT_DISPLAY` is 0 and is the display `MediaProjection` captures,
 * so a dashboard here is inside the picture rather than composited over it.
 */
private fun onMirroredDisplay(context: Context): Boolean =
    runCatching { context.display?.displayId == Display.DEFAULT_DISPLAY }.getOrDefault(true)

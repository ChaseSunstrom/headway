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
import android.graphics.Rect
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import dev.headway.app.dash.DashTile
import dev.headway.app.ui.theme.CarMetrics
import dev.headway.app.ui.theme.Headway
import dev.headway.app.video.AppPaneHost
import dev.headway.dash.PaneFit
import dev.headway.dash.PaneKind
import dev.headway.dash.PaneRect

/**
 * A pane containing a real app, running.
 *
 * ## How another app's pixels get in here
 *
 * They are not this app's pixels and this class never touches them. The pane
 * owns a `SurfaceView`; the session's one `MediaProjection` renders the display
 * the app is running on into that `SurfaceView`'s `Surface`; SurfaceFlinger
 * composes the result into the car display along with every other pane, and the
 * encoder sends the whole frame. Headway draws the frame around the picture and
 * nothing inside it. See [AppPaneHost] and ADR 0010.
 *
 * This is the same shape `CarAppSurfaceView` already uses to let a car app draw
 * its own map into a pane — a `SurfaceView`, a `holder.surface`, and somebody
 * else's rendering. The difference is only which "somebody else".
 *
 * ## Live and dormant
 *
 * A layout may contain several app panes; **one** may show a picture, because a
 * projection owns one virtual display. The rest are dormant: they show the app's
 * icon and say that a tap will bring it here. That is a real state with a real
 * explanation, not a failure, and it is drawn as one.
 *
 * The `SurfaceView` is `GONE` while dormant, deliberately. A `SurfaceView`
 * punches a transparent hole through its window wherever it sits, so anything
 * this class drew *over* it — the dormant card, the editing outline — would be
 * invisible. Hiding it closes the hole, and the card is drawn in an ordinary
 * window with no z-order question to get wrong.
 *
 * ## Why the picture is sized rather than stretched
 *
 * The app is laid out for its own display's shape. [PaneFit] computes the
 * largest rectangle of that shape which fits the pane, and the `SurfaceView` is
 * laid out to exactly that — so the capture fills its surface with no letterbox
 * of its own, the dashboard's background shows around it instead of black bars,
 * and the touch transform is one linear map. The arithmetic lives in `core-dash`
 * and is tested there.
 */
class AppPaneTile(
    private val metrics: CarMetrics,
    /** The package this pane prefers to show; null means "whatever is running". */
    private val packageName: String?,
    private val onStep: (String) -> Unit,
    /** Asks the shell to give this pane the picture. */
    private val onFocusRequested: (AppPaneTile) -> Unit,
) : DashTile {

    override val kind: String get() = PaneKind.APP

    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var dormantCard: View

    /** Set by the shell. Only one app pane in a layout is ever true. */
    private var live = false

    private var editing = false

    /** True between `surfaceCreated` and `surfaceDestroyed`. */
    private var surfaceReady = false

    private var lastBoundWidth = 0
    private var lastBoundHeight = 0

    override fun createView(context: Context): View {
        root = FrameLayout(context).apply {
            background = Headway.panel(metrics.radius, fill = Headway.SURFACE)
            isClickable = true
            // A tap on a dormant pane is the gesture that moves the picture here.
            // A tap on a live one never arrives: the shell routes touches inside
            // the picture to the app before the view tree sees them.
            setOnClickListener { onFocusRequested(this@AppPaneTile) }
        }

        surfaceView = SurfaceView(context).apply {
            visibility = View.GONE
            holder.addCallback(holderCallback)
        }
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER),
        )

        dormantCard = buildDormantCard(context)
        root.addView(
            dormantCard,
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER),
        )

        root.addOnLayoutChangeListener { _, left, top, right, bottom, oldL, oldT, oldR, oldB ->
            if (right - left != oldR - oldL || bottom - top != oldB - oldT) resizePicture()
        }
        return root
    }

    private val holderCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            surfaceReady = true
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            surfaceReady = true
            if (!shouldShowPicture()) return
            // The first surface can arrive before the pane has been measured, in
            // which case the view is still MATCH_PARENT and this size is the
            // whole pane rather than the source's shape. Binding anyway is
            // right — something on the car beats nothing — and this asks for the
            // fit again so the next layout pass corrects it.
            resizePicture()
            if (AppPaneHost.bind(this@AppPaneTile, holder.surface, width, height)) {
                lastBoundWidth = width
                lastBoundHeight = height
                dormantCard.visibility = View.GONE
            } else {
                showDormant()
            }
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            surfaceReady = false
            AppPaneHost.unbind(this@AppPaneTile)
        }
    }

    override fun start() {
        applyState()
    }

    override fun stop() {
        AppPaneHost.unbind(this)
        if (::surfaceView.isInitialized) surfaceView.visibility = View.GONE
        surfaceReady = false
    }

    override fun onEditingChanged(editing: Boolean) {
        this.editing = editing
        applyState()
    }

    /**
     * Re-evaluates whether this pane can show anything.
     *
     * The grant does not arrive with the pane. Video bring-up creates the car
     * surface — which renders the layout and starts every tile — and only then
     * does the session hand the projection to [AppPaneHost]; a grant obtained
     * after the link is up (which is every automatically started session)
     * arrives later still. Without this the pane would say "screen sharing is
     * off" for the whole drive, having decided that once, before it could have
     * been true. The shell calls this from `AppPaneHost`'s listener.
     */
    fun refresh() {
        applyState()
    }

    /** Told by the shell which app pane holds the picture. */
    fun setLive(live: Boolean) {
        if (this.live == live) return
        this.live = live
        applyState()
    }

    /** Whether this pane currently holds the picture. */
    val isLive: Boolean get() = live

    /** The package this pane wants, for the shell's launch decision. */
    val preferredPackage: String? get() = packageName

    /**
     * Where the app's picture is, in the car's own coordinates.
     *
     * Empty when there is no picture. The shell hands this to the touch router,
     * which uses it as the content rectangle of a `TouchTransform` — so this
     * rectangle *is* the contract between what the car shows and where a touch
     * goes, and it is read from the view that is actually on screen rather than
     * recomputed from the layout.
     */
    fun pictureRect(): PaneRect {
        if (!::surfaceView.isInitialized) return EMPTY
        if (surfaceView.visibility != View.VISIBLE || !live) return EMPTY
        val location = IntArray(2)
        surfaceView.getLocationInWindow(location)
        val width = surfaceView.width
        val height = surfaceView.height
        if (width <= 0 || height <= 0) return EMPTY
        return PaneRect(location[0], location[1], location[0] + width, location[1] + height)
    }

    override fun describe(): String = when {
        !live -> "app pane (dormant${packageName?.let { ", wants $it" } ?: ""})"
        !surfaceReady -> "app pane (live, waiting for a surface)"
        else -> "app pane (live, ${lastBoundWidth}x$lastBoundHeight)"
    }

    private fun shouldShowPicture(): Boolean = live && !editing && AppPaneHost.available

    private fun applyState() {
        if (!::root.isInitialized) return
        if (shouldShowPicture()) {
            dormantCard.visibility = View.GONE
            resizePicture()
            surfaceView.visibility = View.VISIBLE
        } else {
            AppPaneHost.unbind(this)
            surfaceView.visibility = View.GONE
            showDormant()
        }
    }

    private fun showDormant() {
        dormantCard.visibility = View.VISIBLE
        updateDormantText()
    }

    /**
     * Lays the `SurfaceView` out at the source's proportions inside the pane.
     *
     * Called on every layout change, because a divider drag changes the pane's
     * size continuously and a picture that only re-fitted on release would
     * stretch for the length of the gesture.
     */
    private fun resizePicture() {
        if (!::surfaceView.isInitialized) return
        val paneWidth = root.width
        val paneHeight = root.height
        val sourceWidth = AppPaneHost.sourceWidth
        val sourceHeight = AppPaneHost.sourceHeight
        if (paneWidth <= 0 || paneHeight <= 0 || sourceWidth <= 0 || sourceHeight <= 0) return
        val fitted = PaneFit.fit(sourceWidth, sourceHeight, paneWidth, paneHeight)
        if (fitted.width <= 0 || fitted.height <= 0) return
        val params = surfaceView.layoutParams as FrameLayout.LayoutParams
        if (params.width == fitted.width && params.height == fitted.height) return
        params.width = fitted.width
        params.height = fitted.height
        params.gravity = Gravity.CENTER
        surfaceView.layoutParams = params
    }

    private fun buildDormantCard(context: Context): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val pad = metrics.gutter
            setPadding(pad, pad, pad, pad)
        }
        val icon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(metrics.unit, metrics.unit).apply {
                bottomMargin = metrics.gutter / 2
            }
        }
        val label = Headway.title(context, metrics.unit, "").apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        }
        val hint = Headway.caption(context, metrics.unit, "").apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                topMargin = metrics.gutter / 3
            }
        }
        column.addView(icon)
        column.addView(label)
        column.addView(hint)
        this.dormantIcon = icon
        this.dormantLabel = label
        this.dormantHint = hint
        updateDormantText()
        return column
    }

    private var dormantIcon: ImageView? = null
    private var dormantLabel: android.widget.TextView? = null
    private var dormantHint: android.widget.TextView? = null

    private fun updateDormantText() {
        val label = dormantLabel ?: return
        val hint = dormantHint ?: return
        val context = label.context
        val app = packageName?.let { name ->
            runCatching {
                val manager = context.packageManager
                val info = manager.getApplicationInfo(name, 0)
                manager.getApplicationLabel(info).toString() to manager.getApplicationIcon(info)
            }.getOrNull()
        }
        dormantIcon?.setImageDrawable(app?.second)
        dormantIcon?.visibility = if (app?.second != null) View.VISIBLE else View.GONE
        label.text = app?.first ?: "App"
        hint.text = when {
            editing -> "This pane shows a running app"
            !AppPaneHost.available -> "Screen sharing is off. Turn it on in Headway on the phone"
            AppPaneHost.live -> "Tap to bring the app here"
            app != null -> "Tap to open"
            else -> "Tap to choose an app"
        }
    }

    private companion object {
        val EMPTY = PaneRect(0, 0, 0, 0)
    }
}

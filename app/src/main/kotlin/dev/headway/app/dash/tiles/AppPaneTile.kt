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
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
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
import dev.headway.app.video.ProjectionRequestActivity
import dev.headway.dash.PaneFit
import dev.headway.dash.PaneKind
import dev.headway.dash.PaneRect

/**
 * A pane containing a real app, running.
 *
 * ## How another app's pixels get in here
 *
 * They are not this app's pixels and this class never touches them. The pane
 * owns a `TextureView`; the session's one `MediaProjection` renders the display
 * the app is running on into that view's `SurfaceTexture`; the car display's
 * window draws it like any other view, and the encoder sends the whole frame.
 * Headway draws the frame around the picture and nothing inside it. See
 * [AppPaneHost] and ADR 0010.
 *
 * ## Why a `TextureView` and not a `SurfaceView`
 *
 * A `SurfaceView` is not drawn by the window at all. It is a separate
 * `SurfaceControl` layer that SurfaceFlinger composites onto the display, with
 * a hole punched through the window where it sits. That works on a physical
 * display and it is one more thing that has to be true on a *virtual* display
 * whose output is a codec input surface — a second composition path, a second
 * set of z-order rules, and a black rectangle if any of it is not honoured. On
 * a car screen the symptom is indistinguishable from "the feature does not
 * work", which is exactly what the first drive with this pane reported.
 *
 * A `TextureView` has none of that. Its frames arrive in a `SurfaceTexture` and
 * are drawn *by the window*, in the ordinary view pass that produces every other
 * pixel of the dashboard, so if the dashboard reaches the car then so does the
 * app. It costs a GPU copy per frame, which on an 800x480 panel is nothing, and
 * it buys two things besides certainty: anything drawn over the pane (the edit
 * overlay, a sheet) is actually visible, and the pane can be hidden and shown
 * without tearing a hole in the window.
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
    /** Crop to fill the pane rather than fitting inside it. */
    private val fill: Boolean = false,
    /** Asks the shell to give this pane the picture. */
    private val onFocusRequested: (AppPaneTile) -> Unit,
) : DashTile {

    override val kind: String get() = PaneKind.APP

    private lateinit var root: FrameLayout
    private lateinit var picture: TextureView
    private lateinit var dormantCard: View

    /** The surface wrapping [picture]'s texture, owned here and released here. */
    private var surface: Surface? = null

    /** Set by the shell. Only one app pane in a layout is ever true. */
    private var live = false

    private var editing = false

    /** True while the texture exists and can be rendered into. */
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
            //
            // With no grant at all the tap means something else, and it is the
            // only way out of that state from the driver's seat: open the
            // consent sheet on the phone. A pane that said "screen sharing is
            // off" and did nothing when pressed was a dead end -- the driver
            // would have had to disconnect and reconnect from the phone to get
            // a dialog that this can raise directly.
            setOnClickListener {
                if (AppPaneHost.available) {
                    onFocusRequested(this@AppPaneTile)
                } else {
                    onStep("app pane: asking for screen sharing")
                    ProjectionRequestActivity.ask(context)
                }
            }
        }

        picture = TextureView(context).apply {
            isOpaque = true
            visibility = View.GONE
            surfaceTextureListener = textureListener
        }
        root.addView(
            picture,
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

    private val textureListener = object : TextureView.SurfaceTextureListener {

        override fun onSurfaceTextureAvailable(
            texture: SurfaceTexture,
            width: Int,
            height: Int,
        ) {
            surfaceReady = true
            attach(texture, width, height)
        }

        override fun onSurfaceTextureSizeChanged(
            texture: SurfaceTexture,
            width: Int,
            height: Int,
        ) {
            attach(texture, width, height)
        }

        /**
         * @return true to let the platform release the texture. The surface
         *   wrapping it is released here first — the virtual display is pointed
         *   at it, and letting the texture go while a display is still rendering
         *   into it is the one ordering that produces a dead pane rather than an
         *   error.
         */
        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            surfaceReady = false
            AppPaneHost.unbind(this@AppPaneTile)
            surface?.let { runCatching { it.release() } }
            surface = null
            return true
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    }

    /**
     * Points the projection at this pane's texture, at [width]x[height].
     *
     * The texture can become available before the pane has been measured, in
     * which case the view is still MATCH_PARENT and this size is the whole pane
     * rather than the source's shape. Binding anyway is right — something on the
     * car beats nothing — and [resizePicture] asks for the fit again so the next
     * layout pass corrects it.
     */
    private fun attach(texture: SurfaceTexture, width: Int, height: Int) {
        if (!shouldShowPicture()) return
        resizePicture()
        // The texture's own buffer size has to match, or the picture is scaled
        // twice: once by the projection into the buffer, and again by the view
        // when it draws a buffer of a different size.
        runCatching { texture.setDefaultBufferSize(width, height) }
        val existing = surface
        val target = if (existing != null && existing.isValid) {
            existing
        } else {
            runCatching { existing?.release() }
            Surface(texture).also { surface = it }
        }
        if (AppPaneHost.bind(this@AppPaneTile, target, width, height)) {
            lastBoundWidth = width
            lastBoundHeight = height
            dormantCard.visibility = View.GONE
            checkForBlackFrames()
        } else {
            showDormant()
        }
    }

    /**
     * Says so, once, if the picture is arriving black.
     *
     * ## Why this is worth the code
     *
     * Because the one failure this feature has that produces *no error at all*
     * is capturing the wrong display. The consent dialog decides what is
     * recorded; Headway decides where apps are launched and how touches are
     * mapped; and when those two disagree the frames still flow, the pane still
     * fills, and what it fills with is a display nobody is drawing on. From the
     * driver's seat that is "the app pane is broken", and from the log it is
     * indistinguishable from working.
     *
     * `TextureView.getBitmap` is the only way to look at what actually arrived —
     * a `SurfaceView` cannot be read back at all, which is one more reason this
     * pane is not one. Sixteen pixels are enough to answer "is any of this
     * anything".
     */
    private fun checkForBlackFrames() {
        if (blackFrameCheckPending) return
        blackFrameCheckPending = true
        picture.postDelayed(
            {
                blackFrameCheckPending = false
                if (!live || picture.visibility != View.VISIBLE) return@postDelayed
                val sample = runCatching { picture.getBitmap(SAMPLE, SAMPLE) }.getOrNull()
                    ?: return@postDelayed
                var brightest = 0
                for (x in 0 until sample.width) {
                    for (y in 0 until sample.height) {
                        val pixel = sample.getPixel(x, y)
                        val value = maxOf(
                            (pixel shr 16) and 0xFF,
                            (pixel shr 8) and 0xFF,
                            pixel and 0xFF,
                        )
                        if (value > brightest) brightest = value
                    }
                }
                runCatching { sample.recycle() }
                if (brightest > BLACK_THRESHOLD) return@postDelayed
                onStep(
                    "app pane: the picture is arriving black. Screen sharing is recording a " +
                        "display that nothing is drawing on — if the consent dialog offered " +
                        "a choice, it was the wrong row; if apps are set to run on a simulated " +
                        "display, the phone's screen is the safer setting",
                )
            },
            BLACK_FRAME_DELAY_MILLIS,
        )
    }

    private var blackFrameCheckPending = false

    override fun start() {
        applyState()
    }

    override fun stop() {
        AppPaneHost.unbind(this)
        if (::picture.isInitialized) picture.visibility = View.GONE
        surface?.let { runCatching { it.release() } }
        surface = null
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
        if (!::picture.isInitialized) return EMPTY
        if (picture.visibility != View.VISIBLE || !live) return EMPTY
        val location = IntArray(2)
        picture.getLocationInWindow(location)
        val width = picture.width
        val height = picture.height
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
            picture.visibility = View.VISIBLE
            // A texture that already exists gets no fresh callback when the pane
            // merely becomes live again, so the bind is re-driven here. Without
            // it, a pane that lost focus and got it back would stay black.
            val texture = picture.surfaceTexture
            if (texture != null && picture.width > 0 && picture.height > 0) {
                attach(texture, picture.width, picture.height)
            }
        } else {
            AppPaneHost.unbind(this)
            picture.visibility = View.GONE
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
        if (!::picture.isInitialized) return
        val paneWidth = root.width
        val paneHeight = root.height
        val sourceWidth = AppPaneHost.sourceWidth
        val sourceHeight = AppPaneHost.sourceHeight
        if (paneWidth <= 0 || paneHeight <= 0 || sourceWidth <= 0 || sourceHeight <= 0) return
        val fitted = PaneFit.place(sourceWidth, sourceHeight, paneWidth, paneHeight, fill)
        if (fitted.width <= 0 || fitted.height <= 0) return
        val params = picture.layoutParams as FrameLayout.LayoutParams
        if (params.width == fitted.width && params.height == fitted.height) return
        params.width = fitted.width
        params.height = fitted.height
        params.gravity = Gravity.CENTER
        picture.layoutParams = params
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
            !AppPaneHost.available -> "Tap here, then allow screen sharing on the phone"
            AppPaneHost.live -> "Tap to bring the app here"
            app != null -> "Tap to open"
            else -> "Tap to choose an app"
        }
    }

    private companion object {
        val EMPTY = PaneRect(0, 0, 0, 0)

        /** Long enough for a launched app to have drawn something. */
        const val BLACK_FRAME_DELAY_MILLIS = 3_000L

        /** A 16x16 read is a rounding error next to one encoded frame. */
        const val SAMPLE = 16

        /** Below this on every channel of every sampled pixel is "nothing at all". */
        const val BLACK_THRESHOLD = 12
    }
}

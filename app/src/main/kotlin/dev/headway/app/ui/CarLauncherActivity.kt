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

package dev.headway.app.ui

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import dev.headway.app.log.SessionLog
import dev.headway.app.service.HeadwayService
import dev.headway.app.ui.theme.Headway
import dev.headway.app.ui.theme.HeadwayMark
import dev.headway.app.video.CarAppDisplay
import dev.headway.app.video.CarSurfaceMode
import dev.headway.app.video.CarVideoStream
import dev.headway.transport.LinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Date
import kotlin.math.max
import kotlin.math.min

/**
 * The surface Headway casts to the car: clock, connection state, pinned apps,
 * voice.
 *
 * ## What is actually being sized
 *
 * This activity draws on the **phone's** screen. The car sees a uniformly scaled,
 * letterboxed copy of that screen (see `TouchTransform`), so a 48 dp button here
 * is not 48 dp there — it is 48 dp multiplied by the projection scale, then
 * reinterpreted at the head unit's own density. On a 1080-wide phone mirrored
 * into an 800x480 panel the scale is well under one, and a phone-sized target
 * arrives at the car as a target too small to hit with a thumb on a moving
 * vehicle's resistive-feeling digitizer.
 *
 * So the sizes are computed backwards from the car: given the car geometry
 * (passed in as extras when the session knows it), [carMinimumTargetPx] returns
 * the phone-pixel size that lands at 48 dp *on the head unit*, and the layout
 * uses that as its floor. With no car geometry it falls back to the phone's own
 * 48 dp, which is the platform minimum and the best guess available.
 *
 * The multiplier on top of the floor is deliberate and is not a design flourish:
 * CLAUDE.md says to "assume imprecise touches", and the whole grid is aimed at a
 * person not looking at it.
 *
 * ## Contrast
 *
 * Pure black behind near-white text, no mid-greys, no thin type. This is read
 * through a windscreen's worth of glare, and a dark theme also keeps a night
 * drive from being lit up by the dashboard.
 */
class CarLauncherActivity : AppCompatActivity() {

    private lateinit var clockText: TextView
    private lateinit var dateText: TextView
    private lateinit var statusText: TextView
    private lateinit var grid: GridLayout

    private val handler = Handler(Looper.getMainLooper())
    private var uiScope: CoroutineScope? = null

    /** Re-posts itself on the next whole second rather than every 1000 ms of drift. */
    /** Travels while the link is coming up. Null until the bar is built. */
    private var linkMark: HeadwayMark? = null

    private val tick = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 1_000L - (System.currentTimeMillis() % 1_000L))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The car screen shows whatever the phone shows; a phone that dims takes
        // the car display with it. CLAUDE.md accepts keep-screen-on as the
        // shipped behaviour until true screen-off mirroring is proven possible.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildContent())
        populateGrid()
    }

    override fun onStart() {
        super.onStart()
        val scope = CoroutineScope(Dispatchers.Main.immediate)
        uiScope = scope
        scope.launch { HeadwayService.linkState.collect { showLinkState(it) } }
        updateClock()
        handler.post(tick)
    }

    override fun onResume() {
        super.onResume()
        // An app installed or uninstalled while the launcher was in the
        // background would otherwise leave a tile that launches nothing.
        populateGrid()
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        uiScope?.cancel()
        uiScope = null
        super.onStop()
    }

    // --- layout -------------------------------------------------------------

    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND)
            setPadding(gutter(), gutter(), gutter(), gutter())
        }
        root.addView(buildTopBar())
        grid = GridLayout(this).apply {
            columnCount = columnsThatFit()
            alignmentMode = GridLayout.ALIGN_BOUNDS
        }
        root.addView(
            ScrollView(this).apply {
                isFillViewport = true
                addView(grid, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            },
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f),
        )
        return root
    }

    /**
     * The mark, the clock, the link, and the two controls.
     *
     * The mark is here for the same reason it is on the phone's hero and in the
     * dashboard's clock pane: it is the one thing that moves, and while the link
     * is coming up that motion is the whole message. Everything else on this bar
     * is static text a driver has to actually read.
     */
    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val markHeight = carMinimumTargetPx() / 2
        linkMark = HeadwayMark(this).apply {
            layoutParams = LinearLayout.LayoutParams(markHeight * 2, markHeight).apply {
                marginEnd = gutter()
            }
        }
        bar.addView(linkMark)

        val clockColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clockText = TextView(this).apply {
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, carTextSizePx(34f))
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            includeFontPadding = false
        }
        dateText = TextView(this).apply {
            setTextColor(DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, carTextSizePx(14f))
        }
        clockColumn.addView(clockText)
        clockColumn.addView(dateText)
        bar.addView(clockColumn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        statusText = TextView(this).apply {
            setTextColor(DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, carTextSizePx(16f))
            gravity = Gravity.CENTER
            text = "Not connected"
        }
        bar.addView(statusText, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        bar.addView(carButton("Apps") { showAppPicker() })
        bar.addView(carButton("Voice", emphasised = true) { requestVoice() })
        return bar
    }

    /**
     * One app tile: icon over label, the whole thing tappable.
     *
     * The label is kept even though the icon is recognisable — icons at car
     * viewing distance through glare are not, and two similar green icons are a
     * wrong-app launch at 70 mph.
     */
    private fun buildTile(entry: AppEntry): View {
        val size = tileSizePx()
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(gutter() / 2, gutter() / 2, gutter() / 2, gutter() / 2)
            isFocusable = true
            minimumWidth = size
            minimumHeight = size
            contentDescription = entry.label
            // pressable() gives it the panel background and the dip on tap; the
            // long press is the only thing it does not cover.
            Headway.pressable(this, radiusPx = size * TILE_RADIUS) { launchApp(entry) }
            setOnLongClickListener {
                showTileOptions(entry)
                true
            }
        }
        val iconSize = (size * ICON_FRACTION).toInt()
        tile.addView(
            ImageView(this).apply {
                setImageDrawable(entry.icon())
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            },
        )
        tile.addView(
            TextView(this).apply {
                text = entry.label
                setTextColor(TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, carTextSizePx(14f))
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
        )
        tile.layoutParams = GridLayout.LayoutParams().apply {
            width = size
            height = WRAP_CONTENT
            setMargins(gutter() / 2, gutter() / 2, gutter() / 2, gutter() / 2)
        }
        return tile
    }

    /**
     * A pill, for the reason `CarStyle.button` gives: a Material `Button`'s
     * default surface is the mid-grey this palette exists to avoid, and undoing
     * its tint, elevation and insets leaves nothing of it worth keeping.
     */
    private fun carButton(
        label: String,
        emphasised: Boolean = false,
        onClick: () -> Unit,
    ) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(if (emphasised) Headway.GROUND else TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, carTextSizePx(16f))
        if (emphasised) setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        minWidth = carMinimumTargetPx() * 2
        minHeight = carMinimumTargetPx()
        contentDescription = label
        background = Headway.panel(
            radiusPx = carMinimumTargetPx() / 2f,
            fill = if (emphasised) Headway.ACCENT else Headway.SURFACE_RAISED,
            stroke = if (emphasised) null else Headway.OUTLINE,
        )
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            marginStart = gutter() / 2
        }
    }

    // --- content ------------------------------------------------------------

    private fun populateGrid() {
        val apps = pinnedApps()
        grid.removeAllViews()
        grid.columnCount = columnsThatFit()
        if (apps.isEmpty()) {
            grid.addView(
                TextView(this).apply {
                    text = "No apps pinned yet — tap Apps to choose some."
                    setTextColor(DIM)
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, carTextSizePx(18f))
                    setPadding(gutter(), gutter(), gutter(), gutter())
                },
            )
            return
        }
        apps.forEach { grid.addView(buildTile(it)) }
    }

    private fun showLinkState(state: LinkState) {
        statusText.text = when (state) {
            is LinkState.Idle -> "Not connected"
            is LinkState.Connecting -> "Connecting"
            is LinkState.Connected -> "Connected"
            is LinkState.WaitingToRetry -> "Reconnecting in ${state.delayMillis / 1000}s"
            is LinkState.GaveUp -> "Disconnected"
        }
        statusText.setTextColor(
            when (state) {
                is LinkState.Connected -> GOOD
                is LinkState.GaveUp -> BAD
                else -> DIM
            },
        )
        linkMark?.travelling =
            state is LinkState.Connecting || state is LinkState.WaitingToRetry
    }

    private fun updateClock() {
        val now = Date()
        clockText.text = DateFormat.getTimeFormat(this).format(now)
        dateText.text = DateFormat.getMediumDateFormat(this).format(now)
    }

    private fun launchApp(entry: AppEntry) {
        val intent = packageManager.getLaunchIntentForPackage(entry.packageName)
        if (intent == null) {
            toast("${entry.label} can no longer be launched")
            populateGrid()
            return
        }
        // NEW_TASK so the launched app becomes its own task rather than stacking
        // on top of the launcher; CLEAR_TOP so returning to an already-running
        // app lands on what the user last saw rather than a fresh copy.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        // Where the app is drawn. Normally this display, which the car mirrors;
        // when a simulated secondary display is in use it is that one instead,
        // and the app lays itself out for the car's size rather than the
        // phone's. ADR 0008; CarAppDisplay decides once per session.
        val target = CarAppDisplay.displayId
        if (target != android.view.Display.DEFAULT_DISPLAY) {
            intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(target)
        val started = runCatching { startActivity(intent, options.toBundle()) }
        if (started.isFailure) {
            SessionLog.shared.warn(TAG, "cannot launch ${entry.packageName}: ${started.exceptionOrNull()}")
            toast("Could not open ${entry.label}")
        } else {
            SessionLog.shared.info(
                TAG,
                "launched ${entry.packageName} from the car launcher on display #$target",
            )
            // Give the car screen to it if a session is up and currently drawing
            // the dashboard. No-op when Headway is already mirroring — which is
            // the case whenever this activity is the thing being mirrored — so
            // the call is safe from either surface.
            CarVideoStream.showOnCar(CarSurfaceMode.MIRROR)
        }
    }

    private fun requestVoice() {
        val hook = onVoiceRequested
        if (hook == null) {
            // Honest failure rather than a button that appears to do nothing:
            // the voice pipeline lives in the session, and there is no session.
            toast("Voice needs an active car session")
            return
        }
        SessionLog.shared.info(TAG, "voice requested from the car launcher")
        hook()
    }

    private fun showTileOptions(entry: AppEntry) {
        AlertDialog.Builder(this)
            .setTitle(entry.label)
            .setItems(arrayOf("Open", "Remove from the car screen")) { _, which ->
                if (which == 0) launchApp(entry) else unpin(entry.packageName)
            }
            .show()
    }

    private fun showAppPicker() {
        val all = launchableApps()
        if (all.isEmpty()) {
            toast("No launchable apps found")
            return
        }
        val pinned = pinnedPackages()
        val labels = all.map { it.label }.toTypedArray()
        val checked = BooleanArray(all.size) { index -> pinned == null || all[index].packageName in pinned }
        AlertDialog.Builder(this)
            .setTitle("Show on the car screen")
            .setMultiChoiceItems(labels, checked) { _, index, isChecked -> checked[index] = isChecked }
            .setPositiveButton("Save") { _, _ ->
                val selected = all.filterIndexed { index, _ -> checked[index] }
                    .map { it.packageName }
                    .toSet()
                savePinned(selected)
                populateGrid()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun unpin(packageName: String) {
        val current = pinnedPackages() ?: launchableApps().map { it.packageName }.toSet()
        savePinned(current - packageName)
        populateGrid()
    }

    private fun savePinned(packages: Set<String>) {
        HeadwaySettings.of(this).edit()
            .putStringSet(HeadwaySettings.KEY_PINNED_APPS, packages)
            .apply()
    }

    /** Null means "the user has never chosen", which shows everything. */
    private fun pinnedPackages(): Set<String>? =
        HeadwaySettings.of(this).getStringSet(HeadwaySettings.KEY_PINNED_APPS, null)

    private fun pinnedApps(): List<AppEntry> {
        val pinned = pinnedPackages() ?: return launchableApps()
        return launchableApps().filter { it.packageName in pinned }
    }

    /**
     * Everything with a launcher entry, minus Headway itself.
     *
     * Headway is excluded because tapping it from its own launcher does nothing
     * visible — `singleTask` brings this same activity back to the front — and it
     * occupies a slot the user has better uses for.
     */
    private fun launchableApps(): List<AppEntry> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = packageManager.queryIntentActivities(
            intent,
            PackageManager.ResolveInfoFlags.of(0L),
        )
        return resolved
            .asSequence()
            .filter { it.activityInfo.packageName != packageName }
            .map { AppEntry(it.activityInfo.packageName, it.loadLabel(packageManager).toString(), it) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    private inner class AppEntry(
        val packageName: String,
        val label: String,
        private val resolveInfo: ResolveInfo,
    ) {
        fun icon() = resolveInfo.loadIcon(packageManager)
    }

    // --- sizing -------------------------------------------------------------

    /**
     * Phone pixels that arrive at the head unit as 48 dp.
     *
     * ```text
     *   projectionScale = min(carW / phoneW, carH / phoneH)   car px per phone px
     *   carTargetPx     = 48 * carDpi / 160                   48 dp at the car
     *   phonePx         = carTargetPx / projectionScale
     * ```
     *
     * The scale expression is the same `min` that `TouchTransform` uses, and it
     * has to stay the same: if the two disagree the buttons drawn here are not
     * the buttons the touches land on.
     *
     * Falls back to the phone's own 48 dp when the session has not told us the
     * car geometry, which is also what happens when this activity is opened on
     * the phone to configure it.
     */
    private fun carMinimumTargetPx(): Int {
        val phoneMinimum = (MIN_TOUCH_TARGET_DP * resources.displayMetrics.density).toInt()
        val carWidth = intent.getIntExtra(EXTRA_CAR_WIDTH, 0)
        val carHeight = intent.getIntExtra(EXTRA_CAR_HEIGHT, 0)
        val carDpi = intent.getIntExtra(EXTRA_CAR_DENSITY_DPI, 0)
        if (carWidth <= 0 || carHeight <= 0 || carDpi <= 0) return phoneMinimum

        val phoneWidth = resources.displayMetrics.widthPixels.toDouble()
        val phoneHeight = resources.displayMetrics.heightPixels.toDouble()
        val projectionScale = min(carWidth / phoneWidth, carHeight / phoneHeight)
        if (projectionScale <= 0.0) return phoneMinimum

        val carTargetPx = MIN_TOUCH_TARGET_DP * carDpi / 160.0
        return max(phoneMinimum, (carTargetPx / projectionScale).toInt())
    }

    /** The floor, enlarged for a driver who is not looking at the screen. */
    private fun tileSizePx(): Int = (carMinimumTargetPx() * TILE_MULTIPLIER).toInt()

    private fun gutter(): Int = (carMinimumTargetPx() * GUTTER_FRACTION).toInt().coerceAtLeast(4)

    /** Text scaled the same way the targets are, so it stays legible in the car. */
    private fun carTextSizePx(sp: Float): Float {
        // applyDimension rather than scaledDensity, which is deprecated on API 35.
        val phonePx =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
        val inflation = carMinimumTargetPx().toDouble() /
            (MIN_TOUCH_TARGET_DP * resources.displayMetrics.density)
        return (phonePx * inflation).toFloat()
    }

    private fun columnsThatFit(): Int {
        val usable = resources.displayMetrics.widthPixels - 2 * gutter()
        return max(1, usable / (tileSizePx() + gutter()))
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "HeadwayCarUi"

        /** Advertised car video width in pixels, so targets can be sized for it. */
        const val EXTRA_CAR_WIDTH: String = "dev.headway.app.extra.CAR_WIDTH"
        const val EXTRA_CAR_HEIGHT: String = "dev.headway.app.extra.CAR_HEIGHT"
        const val EXTRA_CAR_DENSITY_DPI: String = "dev.headway.app.extra.CAR_DENSITY_DPI"

        private const val MIN_TOUCH_TARGET_DP = 48
        private const val TILE_MULTIPLIER = 2.4
        private const val ICON_FRACTION = 0.45
        private const val GUTTER_FRACTION = 0.25

        // Deferred to the shared palette rather than held as literals here.
        // This file used to own one of three copies of the same six colours,
        // and the three had already drifted apart by a shade.
        private val BACKGROUND: Int = Headway.GROUND
        private val TEXT: Int = Headway.TEXT
        private val DIM: Int = Headway.TEXT_MUTED
        private val GOOD: Int = Headway.GOOD
        private val BAD: Int = Headway.FAULT

        /** A tile's corner radius, as a fraction of its side. */
        private const val TILE_RADIUS = 0.14f

        /**
         * Invoked when the driver presses Voice.
         *
         * A settable hook rather than an intent to the service because the voice
         * pipeline belongs to a live session — it needs the car's microphone
         * channel, which only exists while one is up. The session sets this when
         * it opens the AV-input channel and clears it when it closes, so a press
         * with no session gives the user a straight answer instead of silence.
         */
        @Volatile
        var onVoiceRequested: (() -> Unit)? = null

        /** Opens the launcher, telling it the geometry the car will display it at. */
        fun intent(
            context: Context,
            carWidth: Int = 0,
            carHeight: Int = 0,
            carDensityDpi: Int = 0,
        ): Intent = Intent(context, CarLauncherActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_CAR_WIDTH, carWidth)
            .putExtra(EXTRA_CAR_HEIGHT, carHeight)
            .putExtra(EXTRA_CAR_DENSITY_DPI, carDensityDpi)
    }
}

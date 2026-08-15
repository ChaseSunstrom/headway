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
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.headway.app.dash.DashTile
import dev.headway.app.sensor.CarSensorStream
import dev.headway.app.ui.theme.Headway
import dev.headway.dash.PaneKind
import dev.headway.protocol.channel.CarSensors
import java.util.Locale

/**
 * What the car says about itself: speed, revs, fuel, tyres, the outside
 * temperature, the odometer.
 *
 * ## The one pane that needs the car
 *
 * Every other tile draws a model the *phone* publishes — a media session, a
 * notification, a widget — and keeps working with the car unplugged. This one
 * draws the AAP sensor channel, so it is empty until a session is up, and it
 * says so in those words rather than showing a speedometer reading zero. A
 * blank gauge and a gauge reading zero are different claims and only one of them
 * is true when nothing is connected.
 *
 * ## "Not reported" is a normal state, not an error
 *
 * A head unit advertises the sensor types it supports and is free to advertise
 * one and never send a value, or to refuse the subscription outright
 * (`STATUS_INVALID_SENSOR`). Most cars will report a handful of these and
 * nothing else. So every row appears only when the car has actually said
 * something, the pane shows a plain sentence when it has said nothing at all,
 * and neither case is coloured as a fault. The [CarStyle.BAD] colour is reserved
 * for the two readings that *are* warnings — the low-fuel light and an engaged
 * parking brake at speed — because a driver glancing at this must be able to
 * tell a warning from a number.
 *
 * ## Units
 *
 * Speed is shown in the unit the driver's own locale uses for road speed, from
 * [CarSensors.speedKph] and [CarSensors.speedMph]. There is no setting for it
 * and deliberately so: a preference that must be found before the speedometer is
 * right is worse than a default that is right for almost everybody, and both
 * conversions already exist and are tested.
 *
 * Fuel level and range are shown as the bare numbers the car sent, with no unit
 * at all, because **no reference states one** — see [CarSensors.fuelLevel] and
 * BLOCKERS.md B-022. Writing "45%" would be a guess printed in 34-point type on
 * a car screen, which is the worst possible place to put one.
 */
class SensorsTile(context: Context) : DashTile {

    private val appContext: Context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    override val kind: String = PaneKind.SENSORS

    private var content: LinearLayout? = null
    private var speedValue: TextView? = null
    private var speedUnit: TextView? = null
    private var rows: LinearLayout? = null
    private var empty: View? = null
    private var emptyMessage: TextView? = null

    private var running = false

    /** What is on screen. Starts empty, which is the honest state between sessions. */
    private var showing: CarSensors = CarSensors.UNKNOWN

    /**
     * Held in a field so [stop] removes this exact instance, and hopping to the
     * main looper because the publisher is the session's reader coroutine on the
     * IO dispatcher — everything below this line touches views.
     */
    private val listener = CarSensorStream.Listener { sensors ->
        handler.post {
            showing = sensors
            render()
        }
    }

    /**
     * A speed the size of the pane, and everything else as rows beneath it.
     *
     * Two mutually exclusive children of one frame — the content and the empty
     * state — rather than hiding rows individually, which is the shape
     * [NowPlayingTile] arrived at after the alternative produced a pane holding
     * one left-aligned grey sentence and nothing else.
     */
    override fun createView(context: Context): View {
        val root = FrameLayout(context)
        val panel = CarStyle.panel(context)
        val gap = CarStyle.gutter(context)

        val value = CarStyle.label(context, 52f, CarStyle.TEXT, bold = true).apply {
            includeFontPadding = false
            letterSpacing = -0.02f
        }
        val unit = CarStyle.label(context, 14f, CarStyle.ACCENT).apply {
            letterSpacing = 0.06f
        }
        val speedRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(value)
            addView(
                unit,
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    marginStart = gap / 2
                    bottomMargin = CarStyle.dp(context, 8f)
                },
            )
        }

        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroller = ScrollView(context).apply {
            isFillViewport = true
            addView(list, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply {
                topMargin = gap
            }
        }

        panel.addView(speedRow)
        panel.addView(scroller)

        val blank = CarStyle.emptyState(context, NOT_CONNECTED_HINT)
        blank.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        panel.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        root.addView(panel)
        root.addView(blank)

        content = panel
        speedValue = value
        speedUnit = unit
        rows = list
        empty = blank
        emptyMessage = (blank as? LinearLayout)?.getChildAt(1) as? TextView

        render()
        return root
    }

    override fun start() {
        if (running) return
        running = true
        CarSensorStream.observe(listener)
        render()
    }

    override fun stop() {
        if (!running) return
        running = false
        CarSensorStream.unobserve(listener)
        handler.removeCallbacksAndMessages(null)
    }

    override fun describe(): String = "car sensors pane: ${showing.describe()}"

    // --- rendering -----------------------------------------------------------

    private fun render() {
        val panel = content ?: return
        val blank = empty ?: return

        if (!showing.any) {
            emptyMessage?.text = NOT_CONNECTED_HINT
            if (blank.visibility != View.VISIBLE) {
                blank.visibility = View.VISIBLE
                Headway.revealIn(blank)
            }
            panel.visibility = View.GONE
            return
        }
        blank.visibility = View.GONE
        if (panel.visibility != View.VISIBLE) {
            panel.visibility = View.VISIBLE
            Headway.revealIn(panel)
        }

        renderSpeed()
        renderRows()
    }

    private fun renderSpeed() {
        val value = speedValue ?: return
        val unit = speedUnit ?: return
        val imperial = usesMilesPerHour()
        val speed = if (imperial) showing.speedMph else showing.speedKph
        if (speed == null) {
            // A car that reports fuel but not speed is perfectly ordinary. An
            // em dash rather than "0" for the reason in the class KDoc.
            value.text = "—"
            unit.text = "NO SPEED REPORTED"
            return
        }
        value.text = "%.0f".format(speed)
        unit.text = if (imperial) "MPH" else "KM/H"
    }

    private fun renderRows() {
        val list = rows ?: return
        val context = list.context
        list.removeAllViews()

        showing.rpm?.let { addRow(list, "Engine", "%.0f rpm".format(it)) }
        // No unit on either. See the class KDoc and BLOCKERS.md B-022.
        showing.fuelLevel?.let {
            addRow(list, "Fuel level", it.toString(), warn = showing.lowFuel == true)
        }
        showing.range?.let { addRow(list, "Range", it.toString()) }
        if (showing.lowFuel == true && showing.fuelLevel == null) {
            addRow(list, "Fuel", "Low", warn = true)
        }
        if (showing.tyrePressuresKpa.isNotEmpty()) {
            addRow(
                list,
                "Tyres",
                showing.tyrePressuresKpa.joinToString(" / ") { "%.0f".format(it) } + " kPa",
            )
        }
        showing.outsideTemperatureCelsius?.let { addRow(list, "Outside", "%.1f °C".format(it)) }
        showing.odometerKm?.let { addRow(list, "Odometer", "%,.0f km".format(it)) }
        showing.cruiseEngaged?.let { addRow(list, "Cruise", if (it) "On" else "Off") }
        showing.parkingBrake?.let {
            // A parking brake reported on while the car reports movement is the
            // one combination worth colouring: it is either a dragging brake or
            // a sensor the head unit is lying about, and both are worth a glance.
            val moving = (showing.speedMetersPerSecond ?: 0.0) > MOVING_METERS_PER_SECOND
            addRow(list, "Parking brake", if (it) "On" else "Off", warn = it && moving)
        }
        showing.nightMode?.let { addRow(list, "Cabin light", if (it) "Night" else "Day") }
        showing.restricted?.let {
            addRow(list, "Driving status", if (it) "Restricted" else "Unrestricted")
        }

        if (list.childCount == 0) {
            // Speed and nothing else: say so rather than leaving a void under a
            // big number, which reads as a pane that failed to finish loading.
            list.addView(
                CarStyle.label(context, 14f, CarStyle.DIM).apply {
                    text = "The car reports nothing else."
                },
            )
        }
    }

    private fun addRow(list: LinearLayout, label: String, value: String, warn: Boolean = false) {
        val context = list.context
        val gap = CarStyle.gutter(context)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Headway.panel(CarStyle.radius(context), CarStyle.SURFACE)
            setPadding(gap, gap / 2, gap, gap / 2)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = gap / 3
            }
        }
        row.addView(
            CarStyle.label(context, 15f, CarStyle.DIM).apply { text = label },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
        )
        row.addView(
            CarStyle.label(context, 17f, if (warn) CarStyle.BAD else CarStyle.TEXT, bold = true)
                .apply {
                    text = value
                    gravity = Gravity.END
                },
        )
        list.addView(row)
    }

    /**
     * Whether this driver's locale measures road speed in miles per hour.
     *
     * A short explicit list rather than `android.icu.util.LocaleData`'s
     * measurement system, which distinguishes SI from US and UK but is a heavier
     * dependency than one comparison deserves, and rather than a
     * metric/imperial guess from the language, which puts Canada on miles. The
     * list is the countries that post road speeds in mph; anywhere else, and
     * anywhere with no country in its locale, gets km/h.
     */
    private fun usesMilesPerHour(): Boolean {
        val country = runCatching {
            appContext.resources.configuration.locales.get(0).country
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: Locale.getDefault().country
        return country.uppercase(Locale.ROOT) in MPH_COUNTRIES
    }

    private companion object {

        private const val NOT_CONNECTED_HINT =
            "The car has not reported anything yet.\n" +
                "This pane fills in while a session is running, with whatever the head unit sends."

        /** Above walking pace: below this, "moving" is sensor noise. */
        private const val MOVING_METERS_PER_SECOND = 2.0

        /**
         * ISO 3166-1 alpha-2 codes for the places that sign roads in mph.
         *
         * The United Kingdom and the United States, plus Liberia and Myanmar and
         * the Caribbean and Pacific territories that follow the US convention.
         * Deliberately short and deliberately explicit — anything not on it gets
         * km/h, which is the right answer for the overwhelming majority.
         */
        private val MPH_COUNTRIES: Set<String> = setOf(
            "US", "GB", "LR", "MM",
            "AS", "BS", "BZ", "DM", "FM", "GD", "GU", "KN", "KY", "LC", "MH", "MP",
            "MS", "PR", "PW", "SH", "VC", "VG", "VI", "WS",
        )
    }
}

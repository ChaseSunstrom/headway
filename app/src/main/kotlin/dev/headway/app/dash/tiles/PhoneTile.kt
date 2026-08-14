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

import android.Manifest
import android.content.Context
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.headway.app.dash.DashTile
import dev.headway.app.phone.CarPhone
import dev.headway.app.phone.LiveCall
import dev.headway.app.phone.RecentCall
import dev.headway.app.ui.theme.Headway

/**
 * The phone pane: whoever is calling, and everyone who called before.
 *
 * ## Two states, and the first one takes the whole pane
 *
 * While a call is live it is the only thing here — big name, big Answer and
 * Hang up. That is not a styling choice: a ringing phone in a moving car is the
 * one moment where hunting for a control is genuinely dangerous, so the control
 * is the pane. When nothing is ringing the pane is the recent-calls list, which
 * is most of what Android Auto's phone screen ever shows.
 *
 * ## What it is drawn from
 *
 * [CarPhone], which reads the dialer's own notification for the live call and
 * `CallLog.Calls` for the list. Neither is a rendering of the dialer — they are
 * models, drawn here at the car's size with the car's touch targets, which is
 * the same trade the media panes make.
 *
 * ## The permission states are shown, not swallowed
 *
 * Three separate grants sit behind this pane and each can be missing on its own:
 * the notification listener (live call), `READ_CALL_LOG` (the list) and
 * `CALL_PHONE` (dialling straight out rather than opening the dialer). An empty
 * pane that is empty because of a missing grant looks exactly like an empty pane
 * that is empty because nobody has called, and the driver reported precisely
 * that confusion about Now playing. So a missing grant says so, and says where
 * to fix it.
 */
class PhoneTile(
    context: Context,
    private val onStep: (String) -> Unit = {},
) : DashTile {

    private val appContext: Context = context.applicationContext

    override val kind: String = DashTile.Kind.PHONE

    private var callColumn: LinearLayout? = null
    private var callerLabel: TextView? = null
    private var stateLabel: TextView? = null
    private var answerButton: TextView? = null
    private var hangUpButton: TextView? = null

    private var listColumn: LinearLayout? = null
    private var rows: LinearLayout? = null
    private var listHeading: TextView? = null

    private var running = false
    private var live: LiveCall? = null
    private var recents: List<RecentCall> = emptyList()

    private val listener = CarPhone.Listener { call ->
        // A call ending is the one moment the recent list is guaranteed wrong:
        // the call that just finished is in the log and not in `recents`, so a
        // driver looking to call back finds the previous caller instead.
        val ended = live != null && call == null
        live = call
        if (ended && running) recents = CarPhone.recentCalls(appContext)
        render()
    }

    override fun createView(context: Context): View {
        val panel = CarStyle.panel(context)
        val gap = CarStyle.gutter(context)

        // --- a call in progress ------------------------------------------------
        val caller = CarStyle.label(context, 30f, CarStyle.TEXT, bold = true).apply {
            gravity = Gravity.CENTER
        }
        val state = CarStyle.label(context, 15f, CarStyle.DIM).apply {
            gravity = Gravity.CENTER
        }
        val answer = CarStyle.button(context, "Answer", emphasised = true) { answer() }
        val hangUp = CarStyle.button(context, "Hang up") { hangUp() }.apply {
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                marginStart = gap
            }
        }
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(answer)
            addView(hangUp)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = gap
            }
        }
        val call = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(caller)
            addView(state)
            addView(buttons)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }

        // --- the recent-calls list ---------------------------------------------
        val heading = CarStyle.label(context, 17f, CarStyle.TEXT, bold = true).apply {
            text = "Recent calls"
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = gap / 2
            }
        }
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val listHolder = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(heading)
            addView(
                ScrollView(context).apply {
                    isFillViewport = true
                    addView(list, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                },
                LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f),
            )
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }

        panel.addView(call)
        panel.addView(listHolder)

        callColumn = call
        callerLabel = caller
        stateLabel = state
        answerButton = answer
        hangUpButton = hangUp
        listColumn = listHolder
        listHeading = heading
        rows = list
        render()
        return panel
    }

    override fun start() {
        if (running) return
        running = true
        // Re-read each time the pane comes up. The log changes while the pane is
        // off screen and a stale list would offer the wrong "call back".
        recents = CarPhone.recentCalls(appContext)
        CarPhone.observe(listener)
        render()
    }

    override fun stop() {
        if (!running) return
        running = false
        CarPhone.unobserve(listener)
    }

    override fun describe(): String {
        val current = live
            ?: return "phone: idle, ${recents.size} recent call(s)"
        return "phone: ${if (current.ringing) "ringing" else "in call"} — ${current.who}"
    }

    // --- actions ---------------------------------------------------------------

    private fun answer() {
        if (!CarPhone.answer(appContext)) {
            onStep(
                "phone: nothing could answer — the dialer published no answer action and " +
                    "ANSWER_PHONE_CALLS is not granted",
            )
        }
    }

    private fun hangUp() {
        if (!CarPhone.hangUp(appContext)) {
            onStep(
                "phone: nothing could hang up — the dialer published no hang-up action and " +
                    "ANSWER_PHONE_CALLS is not granted",
            )
        }
    }

    // --- rendering --------------------------------------------------------------

    private fun render() {
        val call = callColumn ?: return
        val list = listColumn ?: return
        val current = live
        if (current == null) {
            call.visibility = View.GONE
            list.visibility = View.VISIBLE
            renderRecents()
            return
        }
        list.visibility = View.GONE
        if (call.visibility != View.VISIBLE) {
            call.visibility = View.VISIBLE
            Headway.revealIn(call)
        }
        callerLabel?.text = current.who
        stateLabel?.text = if (current.ringing) "Incoming call" else "On a call"
        // Answering something already answered is a no-op that looks like a bug;
        // hanging up is valid in both states, so only Answer comes and goes.
        answerButton?.visibility = if (current.ringing) View.VISIBLE else View.GONE
        hangUpButton?.text = if (current.ringing) "Decline" else "Hang up"
    }

    private fun renderRecents() {
        val list = rows ?: return
        val context = list.context
        list.removeAllViews()

        if (!CarPhone.granted(appContext, Manifest.permission.READ_CALL_LOG)) {
            list.gravity = Gravity.CENTER
            list.addView(
                CarStyle.emptyState(
                    context,
                    "Headway cannot read the call log yet.\n" +
                        "Grant it on the phone, in Headway's setup screen, and the last " +
                        "twelve calls appear here.",
                ),
            )
            return
        }
        if (recents.isEmpty()) {
            list.gravity = Gravity.CENTER
            list.addView(CarStyle.emptyState(context, "No recent calls."))
            return
        }
        list.gravity = Gravity.TOP
        recents.forEach { entry -> list.addView(recentRow(context, entry)) }
    }

    /**
     * One call-log row, the whole of it a target.
     *
     * The direction is a word rather than an arrow glyph, for the same reason
     * [MediaBrowseTile] uses U+203A and nothing else: the arrow characters are
     * missing from most of the fonts a head unit's density ends up selecting, and
     * a missing glyph draws as a box that reads as corruption.
     */
    private fun recentRow(context: Context, entry: RecentCall): View {
        val gap = CarStyle.gutter(context)
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = CarStyle.dp(context, CarStyle.PRIMARY_TARGET_DP)
            setPadding(gap, gap / 2, gap, gap / 2)
            isFocusable = true
            contentDescription = "Call ${entry.name}"
            Headway.pressable(this, CarStyle.radius(context)) {
                CarPhone.dial(appContext, entry.number, onStep)
            }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = gap / 4
            }
        }

        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(
            CarStyle.label(
                context,
                17f,
                if (entry.missed) CarStyle.BAD else CarStyle.TEXT,
            ).apply { this.text = entry.name },
        )
        column.addView(
            CarStyle.label(context, 13f, CarStyle.DIM).apply {
                this.text = subtitleFor(entry)
            },
        )
        line.addView(column, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        line.addView(
            CarStyle.label(context, 20f, CarStyle.ACCENT).apply {
                this.text = "›"
                gravity = Gravity.CENTER
            },
        )
        return line
    }

    /**
     * "Missed · 20 minutes ago", and the number too when the name is not it.
     *
     * `DateUtils.getRelativeTimeSpanString` rather than a formatted timestamp:
     * a driver reads "2 hours ago" at a glance and has to do arithmetic on
     * "13:42".
     */
    private fun subtitleFor(entry: RecentCall): String {
        val direction = when {
            entry.missed -> "Missed"
            entry.outgoing -> "Outgoing"
            else -> "Incoming"
        }
        val ago = DateUtils.getRelativeTimeSpanString(
            entry.whenMillis,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        )
        val tail = if (entry.name == entry.number) "" else " · ${entry.number}"
        return "$direction · $ago$tail"
    }
}

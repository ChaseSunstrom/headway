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

package dev.headway.app.ui.theme

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The phone screen's half of the design system.
 *
 * [Headway] holds what both surfaces share — the palette taken from the launcher
 * icon, the motion timings, the mark. This file holds the parts that only make
 * sense on a phone: everything here is measured in `dp` against the phone's own
 * density, which is exactly what the car side must never do.
 *
 * ## Why the phone screen needed rebuilding at all
 *
 * It was a single scrolling column of eighteen paragraphs and fourteen
 * identical grey buttons, on flat black, with no visual hierarchy of any kind.
 * Every sentence had the same weight as every other, so the four things a user
 * has to *do* were indistinguishable from the explanations of why. Finding out
 * whether Headway was ready to connect meant reading the whole page.
 *
 * The fix is structural rather than cosmetic:
 *
 * - **Cards.** Each concern is a raised panel with a title. Scanning for the one
 *   you want is a glance rather than a read.
 * - **Status before prose.** Every card leads with what its state *is* — a
 *   coloured dot and six words — and the explanation is folded away behind
 *   [disclosure]. The text is all still there for the person who wants it; it
 *   is no longer in the way of the person who does not.
 * - **One primary action per screen.** Connect is filled in the accent. Nothing
 *   else on the page is, so there is never a question about which button is the
 *   one.
 *
 * ## Why not Material Components
 *
 * The same reason `MainActivity`'s KDoc gives for not using Compose: this app
 * has to be auditable for F-Droid and its phone UI is a column of cards. A
 * `Button` from the Material library also arrives with its own tint, elevation
 * and inset behaviour that has to be fought before a custom background shows at
 * all, so [button] builds on `TextView` and gets exactly the pill it draws.
 */
object Phone {

    /** Android's floor, and the size of anything tappable here. */
    const val TOUCH_TARGET_DP: Float = 48f

    fun dp(context: Context, value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics,
    ).toInt()

    // --- containers -----------------------------------------------------------

    /** The page: a vertical column with the standard side margins. */
    fun page(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Headway.GROUND)
        val side = dp(context, 16f)
        setPadding(side, dp(context, 20f), side, dp(context, 40f))
    }

    /**
     * One concern, as a raised rounded panel.
     *
     * @param title the card's heading, or null for a card that leads with its
     *   own content — the hero at the top of the page does.
     */
    fun card(context: Context, title: String? = null): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Headway.panel(dp(context, 18f).toFloat())
            val pad = dp(context, 18f)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = dp(context, 12f)
            }
            if (title != null) addView(cardTitle(context, title))
        }

    fun cardTitle(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(Headway.TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
        setTypeface(Typeface.DEFAULT_BOLD)
        setPadding(0, 0, 0, dp(context, 10f))
    }

    /** A section break between groups of cards. */
    fun sectionLabel(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text.uppercase()
        setTextColor(Headway.ACCENT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        letterSpacing = 0.14f
        setTypeface(Typeface.DEFAULT_BOLD)
        setPadding(dp(context, 4f), dp(context, 18f), 0, dp(context, 8f))
    }

    // --- text -----------------------------------------------------------------

    fun body(context: Context, text: CharSequence): TextView = TextView(context).apply {
        this.text = text
        setTextColor(Headway.TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setLineSpacing(0f, 1.25f)
    }

    fun note(context: Context, text: CharSequence): TextView = TextView(context).apply {
        this.text = text
        setTextColor(Headway.TEXT_MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setLineSpacing(0f, 1.3f)
    }

    // --- controls -------------------------------------------------------------

    /**
     * A pill button.
     *
     * @param primary fills it in the accent. There is one of these per screen;
     *   a page with two primary actions has no primary action.
     */
    fun button(
        context: Context,
        caption: String,
        primary: Boolean = false,
        onClick: () -> Unit,
    ): TextView = TextView(context).apply {
        text = caption
        contentDescription = caption
        gravity = Gravity.CENTER
        minHeight = dp(context, TOUCH_TARGET_DP)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (primary) 17f else 15f)
        setTypeface(if (primary) Typeface.DEFAULT_BOLD else Typeface.DEFAULT)
        val side = dp(context, 18f)
        setPadding(side, dp(context, 12f), side, dp(context, 12f))
        applyPill(this, primary)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(context, 10f)
        }
    }

    /**
     * A small button that sits at the end of a [statusRow].
     *
     * Deliberately not full width: it is the *remedy* for one line's state, and
     * a full-width bar under every checklist row would make the list five times
     * as tall as the thing it summarises.
     */
    fun inlineButton(context: Context, caption: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = caption
            contentDescription = caption
            gravity = Gravity.CENTER
            minHeight = dp(context, TOUCH_TARGET_DP)
            minWidth = dp(context, 72f)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(Typeface.DEFAULT_BOLD)
            val side = dp(context, 14f)
            setPadding(side, dp(context, 8f), side, dp(context, 8f))
            applyPill(this, primary = false)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                marginStart = dp(context, 12f)
            }
        }

    /**
     * Repaints a pill in place.
     *
     * Exposed because the Connect button changes meaning — and therefore
     * colour — as the link comes up, and rebuilding the view to recolour it
     * would drop its position in the layout.
     */
    fun applyPill(view: TextView, primary: Boolean) {
        val context = view.context
        val radius = dp(context, 26f).toFloat()
        val fill = if (primary) Headway.ACCENT else Headway.SURFACE_RAISED
        val face = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(fill)
            if (!primary) setStroke(dp(context, 1f).coerceAtLeast(1), Headway.OUTLINE)
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(Color.WHITE)
        }
        view.background = RippleDrawable(
            ColorStateList.valueOf(if (primary) Headway.GROUND else Headway.ACCENT_DIM),
            face,
            mask,
        )
        view.setTextColor(if (primary) Headway.GROUND else Headway.TEXT)
    }

    // --- state ----------------------------------------------------------------

    /** What a [statusRow]'s dot is saying. */
    enum class Level { GOOD, WARN, FAULT, IDLE }

    fun colourOf(level: Level): Int = when (level) {
        Level.GOOD -> Headway.GOOD
        Level.WARN -> Headway.WARN
        Level.FAULT -> Headway.FAULT
        Level.IDLE -> Headway.TEXT_MUTED
    }

    /** A filled circle, the size of a full stop, in the state's colour. */
    fun dot(context: Context, level: Level): View = View(context).apply {
        val size = dp(context, 10f)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(colourOf(level))
        }
        layoutParams = LinearLayout.LayoutParams(size, size).apply {
            marginEnd = dp(context, 12f)
            topMargin = dp(context, 6f)
        }
    }

    /**
     * One line of the setup checklist: dot, name, current state, remedy.
     *
     * The whole point of the card this lives in is that a user can tell in one
     * glance whether the phone is ready for a car. So the row is built around
     * the dot: colour first, words second, and the button only when there is
     * something to press.
     */
    class StatusRow(context: Context, label: String) {

        private var level: Level = Level.IDLE

        private val dotView: View = dot(context, level)
        private val nameView: TextView = TextView(context).apply {
            text = label
            setTextColor(Headway.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(Typeface.DEFAULT_BOLD)
        }
        private val stateView: TextView = note(context, "").apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        private var action: TextView? = null

        /**
         * Keeps the remedy button on screen even when the row reads green.
         *
         * The default coupling — hide the button once the state is GOOD — is
         * right for a row that is either granted or not. It is wrong for one
         * that is partly granted: the phone-permission row went green, named
         * the permission still missing, and hid the only button that could ask
         * for it, leaving the user to find "Open Headway's system settings"
         * further down the card and guess which toggle.
         */
        private var alwaysOfferAction: Boolean = false

        val view: LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 8f), 0, dp(context, 8f))
            addView(dotView)
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(nameView)
                    addView(stateView)
                },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
            )
        }

        /** Keeps [withAction]'s button visible in every state. See the field. */
        fun withPersistentAction(): StatusRow {
            alwaysOfferAction = true
            action?.visibility = View.VISIBLE
            return this
        }

        /** Adds the remedy button. Called once, at build time. */
        fun withAction(caption: String, onClick: () -> Unit): StatusRow {
            val button = inlineButton(view.context, caption, onClick)
            action = button
            view.addView(button)
            return this
        }

        /**
         * Sets the state.
         *
         * The remedy hides itself once the state is [Level.GOOD]: a "Grant"
         * button beside something already granted is a button that will be
         * pressed, and it leads to a Settings page with nothing to change.
         */
        fun set(level: Level, state: String) {
            this.level = level
            (dotView.background as? GradientDrawable)?.setColor(colourOf(level))
            stateView.text = state
            stateView.setTextColor(
                if (level == Level.GOOD) Headway.TEXT_MUTED else colourOf(level),
            )
            action?.visibility =
                if (level == Level.GOOD && !alwaysOfferAction) View.GONE else View.VISIBLE
        }
    }

    // --- disclosure -------------------------------------------------------------

    /**
     * Long prose, folded away until asked for.
     *
     * Headway's explanations are genuinely worth reading — why accessibility is
     * needed, what the MAC randomisation problem is, why the bundled certificate
     * expired — and they are also the reason the screen was unreadable. Both
     * facts are true, so the text stays and the default state is closed.
     *
     * The arrow rotates rather than swapping glyphs, and the body fades in, so
     * the change reads as the same object opening instead of two different
     * screens.
     */
    fun disclosure(context: Context, summary: String, detail: CharSequence): View {
        val holder = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(context, 6f)
            }
        }

        val chevron = TextView(context).apply {
            text = "›"
            setTextColor(Headway.ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            rotation = 90f
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                marginEnd = dp(context, 8f)
            }
        }
        val label = TextView(context).apply {
            text = summary
            setTextColor(Headway.ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        val body = note(context, detail).apply {
            visibility = View.GONE
            setPadding(0, dp(context, 8f), 0, 0)
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(context, TOUCH_TARGET_DP)
            isClickable = true
            isFocusable = true
            contentDescription = summary
            addView(chevron)
            addView(label)
            setOnClickListener {
                val opening = body.visibility != View.VISIBLE
                chevron.animate()
                    .rotation(if (opening) 270f else 90f)
                    .setDuration(Headway.QUICK_MILLIS)
                    .start()
                if (opening) {
                    body.alpha = 0f
                    body.visibility = View.VISIBLE
                    body.animate().alpha(1f).setDuration(Headway.SETTLE_MILLIS).start()
                } else {
                    body.animate()
                        .alpha(0f)
                        .setDuration(Headway.QUICK_MILLIS)
                        .withEndAction { body.visibility = View.GONE }
                        .start()
                }
            }
        }

        holder.addView(header)
        holder.addView(body)
        return holder
    }

    /** Standard spacing between a card's own children. */
    fun spaced(context: Context, topDp: Float = 10f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(context, topDp)
        }

    /** A hairline the width of a card's content. */
    fun divider(context: Context): View = View(context).apply {
        setBackgroundColor(Headway.OUTLINE)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 1).apply {
            topMargin = dp(context, 10f)
            bottomMargin = dp(context, 4f)
        }
    }

    /** Fades a whole page in, one card after another. */
    fun stagger(column: ViewGroup) {
        for (index in 0 until column.childCount) {
            Headway.revealIn(column.getChildAt(index), index * STAGGER_STEP_MILLIS)
        }
    }

    private const val STAGGER_STEP_MILLIS = 28L
}

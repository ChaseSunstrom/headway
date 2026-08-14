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

package dev.headway.app.voice

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import dev.headway.app.ui.theme.Headway
import dev.headway.app.ui.theme.HeadwayMark

/**
 * Headway's chrome, floating over whatever app is on screen.
 *
 * Two controls, stacked at the bottom-right of the car screen: **Home**, which
 * takes the car back to Headway's drawn dashboard, and the **microphone**.
 *
 * ## Why the launcher's button is not enough
 *
 * Headway's car launcher has a Voice button, and it is unreachable in practice.
 * The car mirrors the phone's display, so the moment the driver opens Maps the
 * launcher — and its button — is gone. A voice trigger that only exists on
 * Headway's own screen can only be used from Headway's own screen, which is
 * precisely when the driver least needs to talk to it.
 *
 * An overlay window is drawn above every app, so it is on the car screen
 * whatever is running, and car touches reach it through the input path that
 * already exists: the head unit sends a touch, `CarInputStream` maps it into
 * phone coordinates, and the accessibility service dispatches it at those
 * coordinates on display 0 — where this window is.
 *
 * ## The permission, and why it is in bounds
 *
 * `SYSTEM_ALERT_WINDOW` is a special access the user grants in Settings, in the
 * same class as the accessibility grant Headway already asks for. It is not a
 * privileged permission, not `signature`, and not ADB-granted, so it satisfies
 * CLAUDE.md's second hard constraint. It is also genuinely optional: without it
 * the session runs exactly as before and only the floating button is missing.
 *
 * ## Why Home lives here and nowhere else
 *
 * Once the car is showing a real app, every pixel on screen belongs to that app.
 * Headway has no title bar there, no gesture it may register, and no way to
 * place a control inside somebody else's window. An overlay is the only surface
 * left, so it is the only possible way back to the dashboard — without it,
 * opening Maps would be a one-way trip for the rest of the drive.
 *
 * ## Deliberately small and dumb
 *
 * Circular buttons, no panel, no drag handle, `FLAG_NOT_FOCUSABLE` so the window
 * never takes input focus away from the app underneath and never blocks the
 * keyboard. Sized from the car's touch geometry rather than the phone's, because
 * the finger that presses it is aiming at an 800x480 panel through a scaled
 * mirror.
 */
class VoiceOverlay(
    private val context: Context,
    private val onPressed: () -> Unit,
    /**
     * Takes the car back to the dashboard. Null leaves the button off, which is
     * right when there is no dashboard to go back to.
     */
    private val onHome: (() -> Unit)? = null,
    private val onStep: (String) -> Unit = {},
) {

    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null

    /** True once the button is on screen. */
    val isShowing: Boolean get() = view != null

    /**
     * Adds the button, if the user has granted the overlay permission.
     *
     * @return false when the permission is missing, which is an ordinary state
     *   and not an error — the session is unaffected and the log says how to fix
     *   it.
     */
    fun show(sizePx: Int): Boolean {
        // Views may only be constructed on a thread with a Looper, and this is
        // called from the session's coroutine, which has none: a real drive
        // failed with "Can't create handler inside thread
        // Thread[DefaultDispatcher-worker-1] that has not called
        // Looper.prepare()" every time. Everything below — the permission
        // check, the WindowManager call, the TextView — belongs on the main
        // thread, so the whole body is posted there rather than only the add.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { show(sizePx) }
            // Optimistic: the real outcome is logged from the posted call. The
            // caller uses this only to decide whether to say anything, and it
            // has nothing useful to say about work that has not happened yet.
            return true
        }
        if (!canDraw(context)) {
            onStep(
                "voice: the floating microphone button needs \"Display over other apps\", which " +
                    "is not granted. Everything else works; enable it in Settings to talk to " +
                    "Headway from inside another app"
            )
            return false
        }
        if (view != null) return true

        val manager = context.getSystemService(WindowManager::class.java) ?: return false
        val button = buildChrome(sizePx)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_FOCUSABLE keeps the app underneath focused, so typing still
            // works; WATCH_OUTSIDE_TOUCH is deliberately absent, because this
            // must not see touches meant for anything else.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = sizePx / 3
            y = sizePx / 2
        }

        return runCatching {
            manager.addView(button, params)
            view = button
            onStep(
                "voice: floating controls shown (${sizePx}px" +
                    (if (onHome != null) ", with Home" else "") + ")"
            )
            true
        }.getOrElse {
            onStep("voice: the floating microphone button could not be shown ($it)")
            false
        }
    }

    fun hide() {
        val current = view ?: return
        view = null
        // The window manager is main-thread-only, and stop() runs on whatever
        // coroutine the session died on.
        main.post {
            runCatching {
                context.getSystemService(WindowManager::class.java)?.removeView(current)
            }
        }
    }

    /**
     * The stack: Home above the microphone, when there is a dashboard to go to.
     *
     * Vertical rather than horizontal because the mirrored phone screen is a
     * tall strip in the middle of a wide panel, and two buttons side by side at
     * the bottom of it would sit under the app's own bottom navigation. Down the
     * right-hand edge they are clear of it, and clear of each other by a full
     * gap so a thumb aimed at one cannot take the other.
     */
    private fun buildChrome(sizePx: Int): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.END
        val home = onHome
        if (home != null) {
            addView(
                homeButton(sizePx),
                LinearLayout.LayoutParams(sizePx, sizePx).apply {
                    bottomMargin = sizePx / 3
                },
            )
        }
        addView(micButton(sizePx), LinearLayout.LayoutParams(sizePx, sizePx))
    }

    private fun micButton(sizePx: Int): View = TextView(context).apply {
        text = LABEL
        // A glyph rather than an icon: no drawable to scale, legible at any
        // size, and it survives the car's rescaling of the mirrored image.
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx * TEXT_FRACTION)
        gravity = Gravity.CENTER
        setTextColor(Headway.GROUND)
        background = Headway.panel(
            radiusPx = sizePx / 2f,
            fill = Headway.ACCENT,
            stroke = null,
        )
        contentDescription = "Headway voice"
        isClickable = true
        setOnClickListener { onPressed() }
    }

    /**
     * The mark itself, as the way home.
     *
     * No word and no arrow: the icon on the phone's home screen and the button
     * that returns the car to Headway are then the same object, which is a
     * thing a driver learns once. It is also the only control on this window
     * that is *not* the accent, so the two are never confused at a glance.
     */
    private fun homeButton(sizePx: Int): View {
        val home = onHome
        val holder = android.widget.FrameLayout(context).apply {
            background = Headway.panel(
                radiusPx = sizePx / 2f,
                fill = Headway.SURFACE_RAISED,
                stroke = Headway.ACCENT_DIM,
            )
            contentDescription = "Headway home"
            isClickable = true
            setOnClickListener { home?.invoke() }
        }
        val inset = sizePx / 4
        holder.addView(
            HeadwayMark(context),
            android.widget.FrameLayout.LayoutParams(
                sizePx - inset * 2,
                sizePx - inset * 2,
            ).apply { gravity = Gravity.CENTER },
        )
        return holder
    }

    companion object {
        /** U+1F3A4, drawn by the system font at whatever size the car needs. */
        private const val LABEL = "🎤"

        private const val TEXT_FRACTION = 0.5f

        /** Whether the user has granted "Display over other apps". */
        fun canDraw(context: Context): Boolean =
            Settings.canDrawOverlays(context)

        /**
         * The Settings screen that grants it.
         *
         * Package-scoped, so it lands on Headway's own entry rather than the
         * full list — the same courtesy the accessibility prompt gets.
         */
        fun permissionIntent(context: Context): Intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + context.packageName),
        )
    }
}

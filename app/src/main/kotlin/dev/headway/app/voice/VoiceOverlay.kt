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
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * A microphone button that floats over whatever app is on screen.
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
 * ## Deliberately small and dumb
 *
 * One circular button, no panel, no drag handle, `FLAG_NOT_FOCUSABLE` so it
 * never takes input focus away from the app underneath and never blocks the
 * keyboard. It is sized from the car's touch geometry rather than the phone's,
 * because the finger that presses it is aiming at an 800x480 panel through a
 * scaled mirror.
 */
class VoiceOverlay(
    private val context: Context,
    private val onPressed: () -> Unit,
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
        val button = buildButton(sizePx)
        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
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
            onStep("voice: floating microphone button shown (${sizePx}px)")
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

    private fun buildButton(sizePx: Int): View = TextView(context).apply {
        text = LABEL
        // A glyph rather than an icon: no drawable to scale, legible at any
        // size, and it survives the car's rescaling of the mirrored image.
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx * TEXT_FRACTION)
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            // Opaque, not translucent: this has to be findable against a map or
            // a video in direct sunlight.
            setColor(BACKGROUND)
            setStroke(sizePx / STROKE_DIVISOR, Color.WHITE)
        }
        contentDescription = "Headway voice"
        setOnClickListener { onPressed() }
    }

    companion object {
        /** U+1F3A4, drawn by the system font at whatever size the car needs. */
        private const val LABEL = "🎤"

        private const val TEXT_FRACTION = 0.5f
        private const val STROKE_DIVISOR = 24
        private val BACKGROUND = Color.rgb(0x1B, 0x1B, 0x1F)

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

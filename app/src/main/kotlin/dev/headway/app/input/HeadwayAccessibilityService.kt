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

package dev.headway.app.input

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import dev.headway.app.log.SessionLog
import dev.headway.input.CarGestureDispatcher
import dev.headway.input.GestureDispatchOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The unprivileged path from a car touch to a synthesised gesture on the phone.
 *
 * `dispatchGesture` is only callable on a bound, connected `AccessibilityService`,
 * and the alternative — `INJECT_EVENTS` — is a system permission that CLAUDE.md
 * puts out of bounds. So this service exists purely to be that binding: it owns
 * a [CarGestureDispatcher] for as long as the platform keeps it connected, and
 * hands it to whoever holds the AAP input channel.
 *
 * ## It cannot read the screen, by configuration
 *
 * `accessibility_service_config.xml` sets `canRetrieveWindowContent="false"`.
 * That is a promise to the user — Headway injects, it does not observe — and the
 * settings screen says so in as many words. Nothing here may come to depend on
 * window content, because the platform will not supply it: `rootInActiveWindow`
 * is null and event nodes are absent. [onAccessibilityEvent] is therefore empty,
 * and `typeWindowStateChanged` is declared in the config only because an
 * accessibility service with no declared event types is treated as inert by some
 * platform versions.
 *
 * ## Lifetime
 *
 * The platform binds and unbinds this service whenever the user toggles it in
 * Settings, and rebinds it after an app update or a crash. Nothing else in
 * Headway can assume it exists: the session runs without it, minus touch. That
 * is why [instance] is a `StateFlow` rather than a plain nullable — the video
 * path may come up before the user has granted accessibility, and it needs to
 * start feeding gestures at the moment the grant lands, not fail once at start-up.
 */
class HeadwayAccessibilityService : AccessibilityService() {

    /**
     * Main-thread scope.
     *
     * `dispatchGesture`'s result callback is delivered on the main looper when no
     * handler is supplied, and [CarGestureDispatcher] suspends its worker while
     * waiting for it, so running the worker on the main dispatcher costs nothing
     * and keeps the whole injection path on one thread — which removes any
     * question of ordering between the queue and the callbacks.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var gestures: CarGestureDispatcher? = null

    /**
     * The dispatcher, or null while the service is not connected.
     *
     * Callers must re-read it rather than caching: an unbind between two touches
     * is normal (the user opened Settings), and a cached dispatcher would throw
     * on a service the platform no longer knows about.
     */
    val dispatcher: CarGestureDispatcher? get() = gestures

    /**
     * Invoked when the platform asks the service to stop what it is doing.
     *
     * Set by the input pipeline so it can cancel its in-flight
     * `GestureBuilder` chain; a stroke continued after an interrupt is dropped by
     * the platform and would leave the builder waiting for a finger that never
     * lifts.
     */
    @Volatile
    var onInterrupted: () -> Unit = {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        gestures = CarGestureDispatcher(
            service = this,
            scope = scope,
            onOutcome = { gesture, outcome ->
                // Only failures are logged. A completed gesture at touch rates
                // would fill the ring buffer and push out the reason the session
                // died, which is what the export is for.
                if (outcome !is GestureDispatchOutcome.Completed) {
                    SessionLog.shared.warn(
                        TAG,
                        "gesture ${gesture.kind} slice ${gesture.sliceIndex} -> $outcome",
                    )
                }
            },
        )
        connected.value = this
        SessionLog.shared.info(TAG, "accessibility service connected; gesture injection available")
    }

    /**
     * Intentionally empty.
     *
     * See the class KDoc: this service is configured without window-content
     * access, so an event carries nothing worth acting on. Left as an explicit
     * override so that "does nothing" reads as a decision rather than an
     * oversight.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        SessionLog.shared.info(TAG, "accessibility service interrupted by the platform")
        onInterrupted()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        scope.cancel()
        super.onDestroy()
    }

    private fun teardown() {
        connected.compareAndSet(this, null)
        hideBlackout()
        gestures?.close()
        gestures = null
    }

    // --- covering the simulated display's preview window ----------------------

    /** The blackout view, or null when the phone screen is its normal self. */
    private var blackout: View? = null

    /**
     * Covers the phone screen, including the Developer-options preview window.
     *
     * ## Why this needs the accessibility service
     *
     * The window that appears when "Simulate secondary displays" is on is not a
     * preview of the car screen — it *is* the display's output surface. Its
     * `TextureView`'s `SurfaceTexture` is what the platform hands to
     * SurfaceFlinger as display 17's device surface, and destroying the window
     * destroys the display with it. So it cannot be closed, and Android offers
     * no setting that hides it: `OverlayDisplayAdapter.parseFlags` recognises
     * `secure`, `own_content_only`, `should_show_system_decorations`,
     * `fixed_content_mode`, `disable_window_interaction`, `unique_id=` and the
     * display-type and gravity tokens, and not one of them affects visibility.
     * It also cannot be shrunk away: `MIN_SCALE` is 0.3.
     *
     * What is left is covering it, and that turns entirely on z-order.
     * `WindowManagerPolicy.getWindowLayerFromTypeLw` puts
     * `TYPE_DISPLAY_OVERLAY` — the preview — at layer **29**.
     * `TYPE_APPLICATION_OVERLAY`, the only type `SYSTEM_ALERT_WINDOW` buys an
     * ordinary app, is layer **11**, so Headway's voice button can never cover
     * it. `TYPE_ACCESSIBILITY_OVERLAY` is layer **31**, and
     * `WindowManagerService.sanitizeWindowType` allows it only from a bound
     * accessibility service. This service is one.
     *
     * ## What it deliberately does not do
     *
     * The screen stays *on*, and this window is what holds it on:
     * `FLAG_KEEP_SCREEN_ON`. Everything the car needs lives on display 0 — a
     * shared app stops drawing when its display sleeps, Android 15 tears a
     * capture down at the lock screen and asks again on the next unlock, and a
     * simulated display takes its power state verbatim from display 0 because
     * `OverlayDisplayWindow` forwards it. So a cover that let the phone sleep
     * would give the driver a car screen that works, goes black a minute in, and
     * returns only when they pick the phone up. This is a black view over a live
     * screen, not a way to turn the screen off, and B-020 is the record of why
     * those are not the same thing.
     *
     * A window flag rather than a wake lock, deliberately: no permission, scoped
     * to exactly as long as this view exists, and unable to outlive the session
     * the way a leaked `PARTIAL_WAKE_LOCK` can.
     *
     * It is also not a privacy-indicator bypass. The preview exists because the
     * user turned on a developer setting; it says "a simulated display exists",
     * not "you are being recorded". Recording is signalled by the projection's
     * own notification and the status-bar chip, and Headway suppresses neither
     * — its foreground-service notification stays exactly as loud as it was.
     * The honest cost is that a full-screen overlay does cover the status bar,
     * which is why one tap removes it.
     */
    fun showBlackout(onDismissed: () -> Unit = {}) {
        if (blackout != null) return
        val windows = getSystemService(WindowManager::class.java) ?: run {
            SessionLog.shared.warn(TAG, "no WindowManager, so the phone screen cannot be covered")
            return
        }
        val view = View(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            contentDescription = "Headway is projecting. Tap to show the phone screen."
            setOnClickListener {
                hideBlackout()
                onDismissed()
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Not FLAG_NOT_TOUCHABLE: the tap is the way back out, and a cover
            // the driver cannot remove would be a trap rather than a feature.
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                // The flag that makes this cover worth having. Without it the
                // phone sleeps a minute in, and everything that needs display 0
                // goes with it: a shared app stops drawing when its display
                // sleeps, and Android 15 tears a capture down at the lock screen
                // and asks again on the next unlock. The driver's symptom is a
                // car screen that works, goes black, and comes back only if they
                // pick the phone up -- which is worse than not covering it.
                //
                // A window flag rather than a wake lock: it needs no permission,
                // it is scoped to exactly as long as this view exists, and it
                // cannot outlive the session the way a leaked PARTIAL_WAKE_LOCK
                // can. The screen is on and black, which is B-020's approximation
                // of "works when locked" and the closest an unprivileged app can
                // get.
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            android.graphics.PixelFormat.OPAQUE,
        )
        val added = runCatching { windows.addView(view, params) }
        if (added.isFailure) {
            SessionLog.shared.warn(TAG, "the phone screen could not be covered: ${added.exceptionOrNull()}")
            return
        }
        blackout = view
        SessionLog.shared.info(TAG, "phone screen covered; tap it to bring the phone back")
    }

    /** Removes the cover. Idempotent, and safe from any thread the service uses. */
    fun hideBlackout() {
        val view = blackout ?: return
        blackout = null
        runCatching { getSystemService(WindowManager::class.java)?.removeView(view) }
        SessionLog.shared.info(TAG, "phone screen uncovered")
    }

    companion object {
        private const val TAG = "HeadwayA11y"

        private val connected = MutableStateFlow<HeadwayAccessibilityService?>(null)

        /** The connected service, or null. Emits as the user grants and revokes. */
        val instance: StateFlow<HeadwayAccessibilityService?> = connected.asStateFlow()

        /**
         * Whether the user has enabled Headway's service, without needing it bound.
         *
         * Read from `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`, which is
         * world-readable and needs no permission — the write side is what is
         * privileged. The setting is a `:`-separated list of
         * `package/class` component names; the platform's own
         * `AccessibilityManager.getEnabledAccessibilityServiceList` is not used
         * because it reports only services matching a feedback type mask and
         * returns nothing for a service the caller cannot see.
         *
         * A false here with [instance] non-null is possible for a moment after a
         * revoke, since the setting changes before the unbind arrives.
         */
        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, HeadwayAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false

            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            for (component in splitter) {
                // flattenToString() and the stored form can differ in how they
                // spell a class in the app's own package (".input.Foo" vs the
                // fully-qualified name), so compare parsed components.
                if (ComponentName.unflattenFromString(component) == expected) return true
            }
            return false
        }

        /**
         * The Settings screen where the user grants it.
         *
         * There is no API to grant this — that is the point of the opt-in — so
         * the best the app can do is take the user to the right page and explain
         * what to look for.
         */
        /**
         * The component the Settings deep link addresses, and the same string
         * the platform stores in `enabled_accessibility_services`.
         */
        fun componentName(context: Context): ComponentName =
            ComponentName(context, HeadwayAccessibilityService::class.java)

        /**
         * Opens Accessibility settings, scrolled to Headway where the platform
         * allows it.
         *
         * The grant is lost on uninstall and on force-stop, and cannot be
         * restored from inside the app — a service that could re-enable itself
         * would be a keylogger, which is exactly why the platform forbids it. So
         * the only thing worth optimising is the number of taps between noticing
         * and fixing it, and that means landing on Headway's own entry rather
         * than the top of a list.
         *
         * `:settings:fragment_args_key` is the documented way to highlight a
         * preference, and Settings ignores extras it does not understand, so a
         * device that does not support it simply opens the plain list as before.
         */
        fun settingsIntent(context: Context? = null): Intent =
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .apply {
                    val component = context?.let { componentName(it) } ?: return@apply
                    val key = component.flattenToString()
                    putExtra(EXTRA_FRAGMENT_ARG_KEY, key)
                    putExtra(
                        EXTRA_SHOW_FRAGMENT_ARGUMENTS,
                        android.os.Bundle().apply { putString(EXTRA_FRAGMENT_ARG_KEY, key) },
                    )
                }

        /** `Settings.EXTRA_FRAGMENT_ARG_KEY`, which is public but not constant-exported. */
        private const val EXTRA_FRAGMENT_ARG_KEY: String = ":settings:fragment_args_key"
        private const val EXTRA_SHOW_FRAGMENT_ARGUMENTS: String =
            ":settings:show_fragment_args"
    }
}

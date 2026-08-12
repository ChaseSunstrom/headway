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
        gestures?.close()
        gestures = null
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
        fun settingsIntent(): Intent =
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

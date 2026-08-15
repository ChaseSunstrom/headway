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

package dev.headway.app.carapp

import android.content.Context
import android.content.pm.PackageManager
import dev.headway.app.BuildConfig

/**
 * Whether a third-party car app can accept Headway as its host on this install.
 *
 * ## Why anything has to ask before binding
 *
 * `HostValidator` runs in the *app's* process and has four ways to say yes; the
 * only one Headway can reach is "the caller holds
 * `android.car.permission.TEMPLATE_RENDERER`" (ADR 0007, B-012). A permission
 * cannot be held unless something on the device defines it, and the `compat`
 * flavour deliberately defines neither that permission nor the
 * `androidx.car.app.connection` authority — that is the whole point of the
 * flavour, because a device where Google's Android Auto already owns those two
 * names refuses to install the `host` APK at all (ADR 0009).
 *
 * So on a `compat` install, **every** car app refuses, always, by construction.
 * Binding one anyway costs a service bind, a handshake and a watchdog timeout,
 * and ends with the app's own refusal being rendered on the car screen. Asking
 * first turns that into a pane that says something useful.
 *
 * ## Why the refusal used to read as a crash
 *
 * The app throws inside its validator; `androidx.car.app` packages the throwable
 * into a `FailureResponse` whose `errorType` for `IllegalArgumentException` is
 * `INVALID_PARAMETER_EXCEPTION`; Headway took the first line of the remote stack
 * trace and put it on the car screen. A driver read `java.lang.
 * IllegalArgumentException` on their dashboard and reasonably reported it as a
 * Headway crash, when it was another app's sentence quoted back at them. See
 * [CarAppSession.describeFailure], which no longer does that.
 */
object CarHostCapability {

    /**
     * True when this build even claims to be a car-app host.
     *
     * A build-time constant, so it is the cheap half of the answer and the one
     * that is true for every app on the device at once.
     */
    val declared: Boolean get() = BuildConfig.CAR_APP_HOST

    /**
     * True when the platform actually granted the renderer permission.
     *
     * Checked rather than assumed, because [declared] only says the manifest
     * asked. A `host` APK installed beside something else that already defines
     * `TEMPLATE_RENDERER` gets the permission from *that* definer, and a
     * definition Headway does not own can be removed out from under it when the
     * other app is uninstalled.
     */
    fun granted(context: Context): Boolean = runCatching {
        context.packageManager.checkPermission(TEMPLATE_RENDERER, context.packageName) ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** True when binding a car app has any chance of being accepted. */
    fun available(context: Context): Boolean = declared && granted(context)

    /**
     * One sentence for the car screen when [available] is false, or null when it
     * is true.
     *
     * Plain language and actionable: the driver's next step is installing the
     * other APK, and no part of that is helped by knowing which exception the
     * app threw.
     */
    fun explain(context: Context): String? = when {
        available(context) -> null
        !declared ->
            "This is the compat build of Headway, which cannot host car apps. " +
                "Install the -host APK to use them."
        else ->
            "Android has not granted Headway the car renderer permission, so car " +
                "apps will refuse it. Reinstall the -host APK."
    }

    private const val TEMPLATE_RENDERER = "android.car.permission.TEMPLATE_RENDERER"
}

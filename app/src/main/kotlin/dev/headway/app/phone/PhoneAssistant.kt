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

package dev.headway.app.phone

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/**
 * Which app on this phone is the assistant.
 *
 * ## Why a settings key rather than an API
 *
 * `RoleManager.getRoleHolders(ROLE_ASSISTANT)` is the obvious call and it is
 * guarded by `MANAGE_ROLE_HOLDERS`, which is `signature|privileged` — out of
 * bounds by CLAUDE.md. `Settings.Secure` is world-readable, needs no
 * permission, and holds the same answer: the driver's chosen assistant, as a
 * flattened `package/class`.
 *
 * Two keys, because the platform has used both and a phone may have only one
 * of them set. `assistant` is the modern one and wins; `voice_interaction_service`
 * is what a device with a voice interaction service but no separate assistant
 * app fills in. Neither constant is public API — `Settings.Secure.ASSISTANT` is
 * `@hide` — so the *names* are used as literals here rather than imported,
 * which is the difference between reading a documented-in-AOSP setting and
 * depending on a hidden symbol the compiler would refuse.
 *
 * Every failure is a null, and null means "no idea", which callers treat as
 * "not the assistant" rather than guessing.
 */
object PhoneAssistant {

    private const val KEY_ASSISTANT = "assistant"
    private const val KEY_VOICE_INTERACTION = "voice_interaction_service"

    /**
     * The assistant's package name, or null.
     *
     * Re-read rather than cached: a driver can change their assistant without
     * restarting Headway, and this is consulted on a window change rather than
     * per frame.
     */
    fun packageName(context: Context): String? {
        val resolver = context.contentResolver
        val raw = runCatching { Settings.Secure.getString(resolver, KEY_ASSISTANT) }.getOrNull()
            ?: runCatching { Settings.Secure.getString(resolver, KEY_VOICE_INTERACTION) }.getOrNull()
        return componentPackage(raw)
    }

    /** The package half of a flattened component, or of a bare package name. */
    internal fun componentPackage(raw: String?): String? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        ComponentName.unflattenFromString(text)?.let { return it.packageName }
        // Some devices store a bare package. A value with no slash and no
        // spaces is that; anything else is a shape this does not understand,
        // and guessing at it would put the wrong app's name on a car screen.
        return text.takeIf { !it.contains('/') && !it.contains(' ') }
    }
}

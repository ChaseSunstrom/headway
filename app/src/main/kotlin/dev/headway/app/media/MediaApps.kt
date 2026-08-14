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

package dev.headway.app.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable

/**
 * The media apps on this phone that publish a browsable library.
 *
 * ## What this is, and why it is the answer to "run apps like Android Auto"
 *
 * Android Auto does not run other apps. It never has. What it does with a music
 * app is bind to a service the app publishes and ask it for a *tree* — root,
 * then children, then children of those — and then Gearhead draws that tree
 * with its own list widgets at the car's own resolution. Tapping a leaf sends
 * `playFromMediaId` back. The app's own screens are never involved and its
 * process never draws a pixel on the car.
 *
 * That protocol is `MediaBrowserService`, it is public API from API 21, and it
 * needs no privileged anything. It is the single largest thing Headway was
 * missing: the dashboard could show *what is already playing*, but there was no
 * way to start something, browse a library, pick a playlist or choose a podcast
 * episode — which is most of what anyone does with a car stereo.
 *
 * ## Discovery
 *
 * An app declares the service with the
 * [android.service.media.MediaBrowserService.SERVICE_INTERFACE] intent filter,
 * which is exactly what makes it visible to Android Auto, to Wear, and to the
 * Assistant. Finding them is one `queryIntentServices`, and the only thing that
 * makes it work on Android 11+ is `QUERY_ALL_PACKAGES`, which the manifest
 * already declares for the launcher grid.
 *
 * **Media3 apps have to be looked for separately, and this was missed at
 * first.** Media3 documents the legacy `android.media.browse.MediaBrowserService`
 * filter as *optional* — an app may declare only
 * `androidx.media3.session.MediaLibraryService`. But `MediaSessionService.onBind`
 * switches on the **intent action**, not on the declared filters, and its
 * `MediaBrowserServiceCompat.SERVICE_INTERFACE` branch hands back the legacy
 * binder regardless. An explicit-component bind bypasses filter matching
 * entirely. So a Media3-only app is fully browsable with the framework
 * `MediaBrowser` and was simply invisible to a sweep that asked for one action.
 *
 * ## The honest limit
 *
 * Whether an app *serves* Headway is the app's decision, taken in its own
 * `onGetRoot(clientPackageName, clientUid, rootHints)`. The common pattern —
 * Google's own UAMP sample, and every app that copied it — is a
 * `PackageValidator` reading an XML allowlist of caller package names and
 * signing certificates, typically naming the system, Android Auto
 * (`com.google.android.projection.gearhead`), Wear and the Assistant. An app
 * that does that will return null for Headway and the connection fails.
 *
 * Headway cannot and should not try to defeat that. The `clientPackageName`
 * argument is in fact trivially spoofable — `MediaBrowser` passes
 * `mContext.getPackageName()` straight through — but every real validator
 * re-derives the package from `clientUid` and checks the signing certificate,
 * which is not. (An earlier version of this comment said the package name
 * itself could not be forged; the conclusion was right and the reason was
 * wrong.) So this reports per-app what happened and the car screen says the app
 * does not allow other players to browse it, rather than silently showing an
 * empty list.
 *
 * A second refusal shape matters as much as the first: Google's UAMP sample
 * returns an **empty root** rather than null, and a great many apps copied it.
 * That connects successfully and delivers nothing, which looks identical to an
 * empty library. `CarMediaBrowser` tells them apart by the root's extras.
 *
 * On the phone Headway targets — GrapheneOS, F-Droid-heavy, usually without
 * Spotify or YouTube Music — most installed media apps serve a full tree.
 * AntennaPod and VLC accept every caller; so, in practice, do Apple Music,
 * Amazon Music and Podcast Addict. Pocket Casts, Spotify and YouTube Music
 * refuse browsing and remain fully controllable in the Now playing pane.
 */
data class MediaApp(
    val packageName: String,
    /** The `MediaBrowserService` to bind. */
    val service: ComponentName,
    val label: String,
) {
    fun icon(context: Context): Drawable? = runCatching {
        context.packageManager.getApplicationIcon(packageName)
    }.getOrNull()
}

/** What happened the last time Headway tried to browse an app. */
enum class BrowseState {
    /** Never tried. */
    UNKNOWN,

    /** Connecting or loading. */
    WORKING,

    /**
     * The app never answered.
     *
     * A distinct state from [REFUSED] because the causes and the advice differ:
     * a service that has not called `setSessionToken()` by the time its
     * `onGetRoot` returns produces no callback at all, which is a bug in that
     * app rather than a decision by it.
     */
    TIMED_OUT,

    /** Connected and serving a tree. */
    OPEN,

    /**
     * `onGetRoot` returned null, or the service refused the bind.
     *
     * The app's own choice, not a fault. See the class KDoc.
     */
    REFUSED,

    /** Connected, but the node has no children. */
    EMPTY,
}

object MediaApps {

    /**
     * The intent filter every browsable media app declares.
     *
     * Spelled out rather than referenced through
     * `android.service.media.MediaBrowserService.SERVICE_INTERFACE` so that
     * reading this file tells you the actual string being matched. The constant
     * has held this value since API 21 and is what
     * `MediaBrowserServiceCompat` puts in its own manifest template.
     */
    const val SERVICE_INTERFACE: String = "android.media.browse.MediaBrowserService"

    /**
     * Media3's own filters.
     *
     * Both are queried because an app may declare either: a
     * `MediaLibraryService` has a browse tree, and a plain `MediaSessionService`
     * may still answer the legacy binder. Binding is always by explicit
     * `ComponentName`, so what the app *advertises* only affects discovery.
     */
    const val MEDIA3_LIBRARY_SERVICE: String = "androidx.media3.session.MediaLibraryService"
    const val MEDIA3_SESSION_SERVICE: String = "androidx.media3.session.MediaSessionService"

    /**
     * Every app on the phone offering a browse tree, by label.
     *
     * Headway's own package is excluded even though it publishes nothing:
     * defensive, and free.
     *
     * `MATCH_ALL` is not used and neither is any disabled-component flag. A
     * service the user has disabled is one they do not want, and listing it
     * would produce a row that can only ever fail to connect.
     */
    fun installed(context: Context): List<MediaApp> {
        val packages = context.packageManager
        // Legacy first, so that when an app declares both it is the legacy
        // component that wins distinctBy -- it is the one whose contract with a
        // framework MediaBrowser is explicit rather than incidental.
        val resolved: List<ResolveInfo> =
            listOf(SERVICE_INTERFACE, MEDIA3_LIBRARY_SERVICE, MEDIA3_SESSION_SERVICE)
                .flatMap { action ->
                    runCatching {
                        packages.queryIntentServices(
                            Intent(action),
                            PackageManager.ResolveInfoFlags.of(0L),
                        )
                    }.getOrNull().orEmpty()
                }

        return resolved
            .asSequence()
            .mapNotNull { info ->
                val service = info.serviceInfo ?: return@mapNotNull null
                if (service.packageName == context.packageName) return@mapNotNull null
                MediaApp(
                    packageName = service.packageName,
                    service = ComponentName(service.packageName, service.name),
                    // The *application* label, not the service label: a service
                    // is usually called something like "MusicService", and a row
                    // reading "MusicService" tells a driver nothing.
                    label = runCatching {
                        packages.getApplicationLabel(
                            packages.getApplicationInfo(
                                service.packageName,
                                PackageManager.ApplicationInfoFlags.of(0L),
                            ),
                        ).toString()
                    }.getOrNull()?.takeIf { it.isNotBlank() } ?: service.packageName,
                )
            }
            // One row per app. An app may publish several browser services —
            // some publish a separate one for Wear, and a Media3 app matches
            // more than one of the three actions above — and they lead to the
            // same library.
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .toList()
    }
}

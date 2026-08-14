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

import android.media.MediaDescription
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.os.Bundle
import android.service.media.MediaBrowserService

/**
 * A real media library, three levels deep, for driving [CarMediaBrowser] against.
 *
 * ## Why a real service and not a mock
 *
 * Because the bug this exists to catch lives in the platform, not in Headway.
 * `MediaBrowserService.addSubscriptionOnHandler` dedupes on `(token, options)`
 * and **returns without calling `performLoadChildrenOnHandler`** when that pair
 * is already registered, and the compatibility entry point beside it,
 * `ServiceBinder.addSubscriptionDeprecated`, is a do-nothing method. So
 * subscribing an id that is already subscribed produces no `onLoadChildren`, no
 * `onChildrenLoaded`, and no error — the caller simply waits forever.
 *
 * The first version of [CarMediaBrowser] left every level subscribed on the way
 * down and re-subscribed on the way up, so **Back never loaded anything**. No
 * amount of mocking `MediaBrowser` would have shown that; only the real service
 * has the dedupe in it.
 *
 * ## Shape
 *
 * ```
 * root
 *  ├── folder-a   (browsable) ── a-1 … a-3 (playable)
 *  └── folder-b   (browsable) ── b-1 … b-3 (playable)
 * ```
 *
 * Every caller is served: this is the test app binding its own service, and a
 * validator here would only test the validator.
 */
class FakeLibraryService : MediaBrowserService() {

    /** How many times each parent id was actually asked to load. */
    private val loads = mutableMapOf<String, Int>()

    override fun onCreate() {
        super.onCreate()
        // The token must exist by the time onGetRoot returns, or the platform
        // completes no connection *and reports nothing* -- the silent failure
        // CarMediaBrowser's connect watchdog exists to bound. Setting it here
        // is what makes this service a well-behaved one.
        val session = MediaSession(this, "FakeLibrary")
        sessionToken = session.sessionToken
        sessions += session
    }

    override fun onDestroy() {
        sessions.forEach { runCatching { it.release() } }
        sessions.clear()
        super.onDestroy()
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?,
    ): BrowserRoot {
        lastRootHints = rootHints
        // Advertised so CarMediaBrowser.looksLikeAnEmptyStub does not mistake
        // an empty level for an allowlist refusal.
        val extras = Bundle().apply {
            putBoolean("android.media.browse.CONTENT_STYLE_SUPPORTED", true)
        }
        return BrowserRoot(ROOT_ID, extras)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowser.MediaItem>>,
    ) {
        loads[parentId] = (loads[parentId] ?: 0) + 1
        loadCounts = loads.toMap()

        val children: MutableList<MediaBrowser.MediaItem> = when (parentId) {
            ROOT_ID -> mutableListOf(folder(FOLDER_A, "Folder A"), folder(FOLDER_B, "Folder B"))
            FOLDER_A -> (1..3).map { track("a-$it", "A track $it") }.toMutableList()
            FOLDER_B -> (1..3).map { track("b-$it", "B track $it") }.toMutableList()
            else -> mutableListOf()
        }
        result.sendResult(children)
    }

    private fun folder(id: String, title: String) = MediaBrowser.MediaItem(
        MediaDescription.Builder().setMediaId(id).setTitle(title).build(),
        MediaBrowser.MediaItem.FLAG_BROWSABLE,
    )

    private fun track(id: String, title: String) = MediaBrowser.MediaItem(
        MediaDescription.Builder().setMediaId(id).setTitle(title).build(),
        MediaBrowser.MediaItem.FLAG_PLAYABLE,
    )

    companion object {
        const val ROOT_ID = "root"
        const val FOLDER_A = "folder-a"
        const val FOLDER_B = "folder-b"

        private val sessions = mutableListOf<MediaSession>()

        /**
         * Per-parent load counts, published statically.
         *
         * The service is constructed by the system, so a test cannot hold a
         * reference to it. This is the same shape, and for the same reason, as
         * `HeadwayNotificationListener`'s companion state.
         */
        @Volatile
        var loadCounts: Map<String, Int> = emptyMap()
            private set

        /** The hints the last connection asked with, so the test can assert them. */
        @Volatile
        var lastRootHints: Bundle? = null
            private set

        fun reset() {
            loadCounts = emptyMap()
            lastRootHints = null
        }
    }
}

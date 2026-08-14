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
import android.media.browse.MediaBrowser
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives [CarMediaBrowser] against a real [FakeLibraryService].
 *
 * On-device rather than on the JVM because the behaviour under test is the
 * platform's: `MediaBrowserService`'s subscription dedupe, and the fact that a
 * redundant `subscribe` produces no callback at all. A stubbed `MediaBrowser`
 * would pass every one of these tests while the real thing hung.
 */
@RunWith(AndroidJUnit4::class)
class CarMediaBrowserTest {

    private lateinit var context: Context
    private lateinit var app: MediaApp
    private var browser: CarMediaBrowser? = null

    @Before
    fun setUp() {
        // The *target* context. FakeLibraryService is a debug-variant
        // component of the app APK, so it lives in dev.headway.app, is
        // instantiated in the app process, and shares that process with the
        // instrumentation — which is what makes the statics it publishes
        // readable from here at all. See app/src/debug/AndroidManifest.xml for
        // why it is not in androidTest.
        context = InstrumentationRegistry.getInstrumentation().targetContext
        FakeLibraryService.reset()
        app = MediaApp(
            packageName = context.packageName,
            service = ComponentName(context, FakeLibraryService::class.java),
            label = "Fake library",
        )
    }

    @After
    fun tearDown() {
        val open = browser
        browser = null
        if (open != null) onMain { open.close() }
    }

    /**
     * The regression that shipped: walking down and back up must load every
     * level, every time.
     *
     * Before the fix this hung on the first Back — `open()` left the parent
     * subscribed, so re-subscribing it on the way up hit
     * `addSubscriptionOnHandler`'s `(token, options)` dedupe and no
     * `onLoadChildren` was ever sent. The pane sat on WORKING with no error and
     * no timeout.
     *
     * The assertion is deliberately on the *service's* load counts and not only
     * on the delivered items: a cached redraw would satisfy the items and still
     * mean the platform never spoke.
     */
    @Test
    fun walkingDownAndBackUpLoadsEveryLevel() {
        val session = connectAndAwaitRoot()

        // Root is loaded once by the connection itself.
        assertEquals(
            "the root should have been loaded exactly once on connect",
            1,
            FakeLibraryService.loadCounts[FakeLibraryService.ROOT_ID],
        )

        val intoA = awaitItems { onMain { session.open(FakeLibraryService.FOLDER_A, "Folder A") } }
        assertEquals("folder A should hold three tracks", 3, intoA.size)

        val backToRoot = awaitItems { onMain { session.back() } }
        assertEquals("back should land on the root's two folders", 2, backToRoot.size)
        assertEquals(
            "back must make the service load the root again, not silently no-op",
            2,
            FakeLibraryService.loadCounts[FakeLibraryService.ROOT_ID],
        )

        val intoB = awaitItems { onMain { session.open(FakeLibraryService.FOLDER_B, "Folder B") } }
        assertEquals("folder B should hold three tracks", 3, intoB.size)

        val backAgain = awaitItems { onMain { session.back() } }
        assertEquals("a second back must also work", 2, backAgain.size)
        assertEquals(
            3,
            FakeLibraryService.loadCounts[FakeLibraryService.ROOT_ID],
        )
    }

    /** Back at the root is a no-op rather than an underflow. */
    @Test
    fun backAtTheRootDoesNothing() {
        val session = connectAndAwaitRoot()
        assertTrue("the root is not a place you can go back from", !session.canGoBack())
        onMain { session.back() }
        assertEquals(BrowseState.OPEN, session.state)
    }

    /** Re-opening the node already shown must not reload or corrupt the trail. */
    @Test
    fun openingTheCurrentNodeAgainIsIgnored() {
        val session = connectAndAwaitRoot()
        val loadsBefore = FakeLibraryService.loadCounts[FakeLibraryService.ROOT_ID]
        onMain { session.open(FakeLibraryService.ROOT_ID, "root again") }
        assertEquals(
            "a double tap on the current node must not re-subscribe",
            loadsBefore,
            FakeLibraryService.loadCounts[FakeLibraryService.ROOT_ID],
        )
        assertTrue(!session.canGoBack())
    }

    /**
     * The art-size hint reaches the service.
     *
     * Not cosmetic: it is the only thing standing between a hundred-row level
     * of full-resolution album art and the heap.
     */
    @Test
    fun theConnectionAsksForCarSizedArtwork() {
        connectAndAwaitRoot()
        val hints = FakeLibraryService.lastRootHints
        assertTrue("the root hints should have been sent", hints != null)
        assertTrue(
            "the art-size hint should be present",
            hints!!.containsKey("android.media.extras.MEDIA_ART_SIZE_HINT_PIXELS"),
        )
    }

    // --- plumbing --------------------------------------------------------------

    private fun connectAndAwaitRoot(): CarMediaBrowser {
        val session = CarMediaBrowser(context, app)
        browser = session
        val items = awaitItems { onMain { session.connect(collector) } }
        // The state is in the message because the three ways this fails look
        // identical from the item count alone: REFUSED means the bind was
        // turned away (the manifest), TIMED_OUT means the service never
        // answered, EMPTY means it answered with nothing.
        assertEquals(
            "the root should list two folders; browser said $latestState",
            2,
            items.size,
        )
        return session
    }

    /** The latest delivery, and a latch the test arms before each action. */
    @Volatile
    private var latest: List<MediaBrowser.MediaItem> = emptyList()

    @Volatile
    private var settled: CountDownLatch = CountDownLatch(1)

    /** The state that came with [latest]; part of every failure message. */
    @Volatile
    private var latestState: BrowseState = BrowseState.UNKNOWN

    private val collector = CarMediaBrowser.Listener { state, items ->
        // WORKING is the intermediate the browser publishes on the way; only a
        // settled state counts as an answer.
        if (state == BrowseState.WORKING) return@Listener
        latest = items
        latestState = state
        settled.countDown()
    }

    private fun awaitItems(action: () -> Unit): List<MediaBrowser.MediaItem> {
        settled = CountDownLatch(1)
        action()
        assertTrue(
            "the browser produced no settled state within $TIMEOUT_SECONDS s",
            settled.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )
        return latest
    }

    /**
     * Runs on the main thread and waits.
     *
     * `MediaBrowser` binds its internal handler to the constructing thread's
     * looper and documents itself as main-thread-only; the instrumentation
     * thread is neither.
     */
    private fun onMain(block: () -> Unit) {
        val done = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            runCatching { block() }
            done.countDown()
        }
        done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private companion object {
        /** Generous: a cold bind on a software-emulated device is not fast. */
        const val TIMEOUT_SECONDS = 15L
    }
}

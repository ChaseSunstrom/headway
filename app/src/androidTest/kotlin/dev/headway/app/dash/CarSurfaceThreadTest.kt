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

package dev.headway.app.dash

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.headway.video.EncoderConfiguration
import dev.headway.video.ScreenEncoder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The car surface must be buildable from a thread with no `Looper`.
 *
 * ## The bug this is the regression test for
 *
 * `CarSurface.create` is called from `HeadwayService`'s session scope, which is
 * `Dispatchers.Default` — a pool thread with no looper. It used to construct
 * `DashboardPresentation` there, and `Presentation` extends `Dialog`, whose
 * constructor builds a `Handler`. A `Handler` on a looper-less thread throws
 * `RuntimeException: Can't create handler inside thread … that has not called
 * Looper.prepare()`, **from the constructor**, before `show()` is ever reached.
 *
 * On 2026-08-14 that threw on a real car, escaped an unguarded `video?.start()`,
 * and tore down an AAP session that had come fully up 21 ms earlier — thirteen
 * channels, TLS, authentication, all of it. The log showed no reason at all,
 * because nothing on that path narrated, and the teardown's own message read as
 * though the head unit had hung up.
 *
 * ## What is asserted
 *
 * Only what can be: that calling `create` off the main thread does not throw,
 * and returns without deadlocking. It will usually return null on a CI emulator
 * — no encoder, or a display the system declines — and null is a fine answer.
 * **Throwing is not.** The failure this catches is an exception where a null
 * belonged, which is exactly the shape of the original bug.
 */
@RunWith(AndroidJUnit4::class)
class CarSurfaceThreadTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test(timeout = TIMEOUT_MILLIS)
    fun creatingTheCarSurfaceOffTheMainThreadDoesNotThrow() {
        val thrown = AtomicReference<Throwable?>(null)
        val returned = AtomicReference<CarSurface?>(null)
        val done = CountDownLatch(1)

        // A plain Thread, deliberately: it has no Looper, which is the whole
        // point. Running this on an executor that happens to have prepared one
        // would pass while the real call site still failed.
        val worker = Thread {
            check(Looper.myLooper() == null) { "this test needs a looper-less thread" }
            try {
                returned.set(
                    CarSurface.create(
                        context = context,
                        configuration = EncoderConfiguration(
                            width = WIDTH,
                            height = HEIGHT,
                            frameRate = FRAME_RATE,
                            bitRateBitsPerSecond = BIT_RATE,
                            densityDpi = DENSITY,
                        ),
                        sink = NoopSink,
                    )
                )
            } catch (t: Throwable) {
                thrown.set(t)
            } finally {
                done.countDown()
            }
        }
        worker.start()

        assertTrue("CarSurface.create never returned", done.await(TIMEOUT_MILLIS / 2, TimeUnit.MILLISECONDS))
        assertFalse(
            "CarSurface.create threw off the main thread instead of returning null: " +
                "${thrown.get()}. That exception ends the whole AAP session.",
            thrown.get() != null,
        )
        // Whatever it returned, put it back: on a device where this succeeds it
        // holds a virtual display and a window.
        returned.get()?.let { surface ->
            runCatching { surface.stop() }
        }
    }

    private object NoopSink : ScreenEncoder.Sink {
        override fun onCodecConfig(codecConfig: ByteArray) = Unit

        override fun onFrame(
            data: ByteArray,
            length: Int,
            presentationTimeUs: Long,
            keyFrame: Boolean,
        ) = Unit
    }

    private companion object {
        const val WIDTH = 800
        const val HEIGHT = 480
        const val FRAME_RATE = 30
        const val BIT_RATE = 1_700_000
        const val DENSITY = 160
        const val TIMEOUT_MILLIS = 30_000L
    }
}

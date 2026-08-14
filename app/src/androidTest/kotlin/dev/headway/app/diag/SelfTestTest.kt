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

package dev.headway.app.diag

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.headway.app.BuildConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The self-test on a phone with nothing installed.
 *
 * This is the case that matters most in CI and the one most likely to break:
 * the AOSP emulator has no car app, no media app and no simulated display, so
 * every check here takes its empty branch. A report that crashed or hung on
 * that path would be a self-test that only works when everything already does.
 *
 * The interesting assertion is not any single line — it is that [SelfTest.run]
 * *returns*, on a device where nothing answers. Every check it makes posts work
 * to the main looper and blocks on a latch; the failure mode is a deadlock, and
 * the failure mode's symptom is this test timing out rather than failing.
 *
 * What is deliberately not asserted is any verdict. On a device with car apps
 * installed — a developer's phone, running the same suite — "0 accepted" is the
 * wrong expectation and "1 accepted" is the wrong expectation, so the test
 * checks the report's *shape* and leaves the verdicts to the driver reading it.
 */
@RunWith(AndroidJUnit4::class)
class SelfTestTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * It finishes, and it names every section.
     *
     * Timed, because a hang is the realistic failure and JUnit would otherwise
     * report it as the whole suite dying rather than as this test.
     */
    @Test(timeout = RUN_TIMEOUT_MILLIS)
    fun theReportRunsToCompletionWithNothingInstalled() {
        val report = SelfTest.run(context)

        for (heading in HEADINGS) {
            assertTrue(
                "the report never reached the \"$heading\" section:\n$report",
                report.contains(heading),
            )
        }
    }

    /**
     * The grants section reports the permission the whole host depends on.
     *
     * Verifying the report *says something true*, not merely that it emitted a
     * heading: `TEMPLATE_RENDERER` is granted to Headway at install (ADR 0007),
     * so a self-test that printed "NO" against it would be reading the wrong
     * package or the wrong permission name — and would send a driver hunting a
     * problem that is not there.
     */
    @Test(timeout = RUN_TIMEOUT_MILLIS)
    fun theGrantsSectionAgreesWithThePackageManager() {
        // Both claims below belong to the host variant by construction; the
        // compat variant exists precisely because it makes neither. See
        // tools/check-install-claims.sh, which asserts that difference on the
        // built APKs where it is a fact rather than an expectation.
        assumeTrue("the compat build claims neither name", BuildConfig.CAR_APP_HOST)
        val report = SelfTest.run(context)
        assertTrue(
            "TEMPLATE_RENDERER should read granted on any device Headway installed on:\n$report",
            report.contains("yes  android.car.permission.TEMPLATE_RENDERER"),
        )
        assertTrue(
            "the provider Headway itself owns should answer:\n$report",
            report.contains("yes  car-connection provider answers"),
        )
    }

    /**
     * The collision section names Headway as the owner of both.
     *
     * This is B-013 and B-014 answered rather than predicted. If some other
     * package on this device owned either, the report would say so by name and
     * this assertion would fail loudly — which is the right outcome, because a
     * device where that is true is a device where the host is silently dead.
     */
    @Test(timeout = RUN_TIMEOUT_MILLIS)
    fun nothingElseOwnsWhatHeadwayClaims() {
        assumeTrue("the compat build claims neither name", BuildConfig.CAR_APP_HOST)
        val report = SelfTest.run(context)
        assertTrue(
            "another package defines TEMPLATE_RENDERER on this device:\n$report",
            report.contains("TEMPLATE_RENDERER is defined by: ${context.packageName}"),
        )
        assertTrue(
            "another package owns the car-connection authority:\n$report",
            report.contains("androidx.car.app.connection is owned by: ${context.packageName}"),
        )
        assertFalse("B-013 fired unexpectedly:\n$report", report.contains("B-013: another package"))
        assertFalse("B-014 fired unexpectedly:\n$report", report.contains("B-014: another package"))
    }

    /**
     * Calling it on the main thread is refused rather than deadlocked.
     *
     * The guard exists because the failure it prevents is invisible in review:
     * every bind is posted to main and awaited, so running the whole thing *on*
     * main blocks the looper that would have completed it — once per installed
     * app, for the full timeout each. The driver sees an ANR, not a report.
     */
    @Test(timeout = RUN_TIMEOUT_MILLIS)
    fun runningItOnTheMainThreadIsRefused() {
        val thrown = arrayOfNulls<Throwable>(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            check(Looper.myLooper() == Looper.getMainLooper())
            thrown[0] = assertThrows(IllegalStateException::class.java) { SelfTest.run(context) }
        }
        val message = thrown[0]?.message.orEmpty()
        assertTrue("the guard should say why:\n$message", message.contains("main thread"))
    }

    private companion object {
        /**
         * Generous on purpose.
         *
         * The bare emulator has nothing to bind, so the run is near-instant
         * there; a developer's phone with several car and media apps installed
         * spends `ASK_TIMEOUT_SECONDS` on each one that never answers. This is
         * a deadlock detector, not a performance budget.
         */
        const val RUN_TIMEOUT_MILLIS = 240_000L

        val HEADINGS = listOf(
            "Car apps",
            "Displays",
            "Grants",
            "Install collisions",
            "Media apps",
            "Still needs the car",
        )
    }
}

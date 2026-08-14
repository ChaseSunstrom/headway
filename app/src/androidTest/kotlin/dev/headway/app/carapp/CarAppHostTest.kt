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

import android.content.pm.PackageManager
import androidx.car.app.HandshakeInfo
import androidx.car.app.model.Action
import androidx.car.app.model.CarText
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.TemplateWrapper
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.serialization.Bundleable
import androidx.car.app.versioning.CarAppApiLevels
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The parts of the template host that can be checked without a car app.
 *
 * On-device rather than on the JVM for two reasons that are the same reason:
 * `Bundleable` is a `Parcelable` and `Bundler` is reflection over Android
 * `Bundle`s, and neither exists on a unit-test classpath — the SDK stub throws
 * from every method. A JVM test here would pass while the real thing failed,
 * which is the failure mode this project has already paid for once.
 *
 * What is *not* here is a fake `CarAppService` to bind. That was tried and it
 * proves nothing: the interesting half of the handshake is `HostValidator`
 * running inside somebody else's process against `Binder.getCallingUid()`, and a
 * fake service in Headway's own package is accepted by the "local service call"
 * branch before the validator is consulted at all. A green test against a fake
 * would say nothing about whether Organic Maps answers. The bench test for that
 * is a real app, and the README says so.
 */
@RunWith(AndroidJUnit4::class)
class CarAppHostTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * The permission that decides whether any car app talks to Headway.
     *
     * The single most load-bearing line in the manifest, and the one nothing
     * else would notice the loss of until a real app refused: everything would
     * build, bind, and then answer "Unknown host". `HostValidator.validateHost`
     * reads it as `REQUESTED_PERMISSION_GRANTED` on the caller's PackageInfo,
     * so requesting it is not enough — it has to actually be granted, which is
     * what the signature-level self-declaration buys.
     */
    @Test
    fun headwayHoldsTheTemplateRendererPermission() {
        val granted = context.packageManager.checkPermission(
            TEMPLATE_RENDERER,
            context.packageName,
        )
        assertEquals(
            "without this permission every car app answers 'Unknown host'",
            PackageManager.PERMISSION_GRANTED,
            granted,
        )
    }

    /**
     * The provider every car app queries to learn it is being projected.
     *
     * Queried the way the library queries it, through a `ContentResolver` at the
     * well-known authority, because that is the only path that proves the
     * manifest declaration is right as well as the class.
     */
    @Test
    fun theCarConnectionProviderAnswers() {
        val uri = android.net.Uri.parse("content://${CarConnectionProvider.AUTHORITY}")
        val cursor = context.contentResolver.query(
            uri,
            arrayOf(CarConnectionProvider.CAR_CONNECTION_STATE),
            null,
            null,
            null,
        )
        assertNotNull("nothing answered the car-connection authority", cursor)
        cursor!!.use {
            assertTrue("the provider returned no row", it.moveToFirst())
            val state = it.getInt(it.getColumnIndexOrThrow(CarConnectionProvider.CAR_CONNECTION_STATE))
            // No car in CI, so the honest answer is NOT_CONNECTED. Asserting the
            // value and not merely the shape: a provider that always said
            // PROJECTION would make every app believe it is in a car forever.
            assertEquals(CarConnectionProvider.CONNECTION_TYPE_NOT_CONNECTED, state)
        }
    }

    /**
     * A `HandshakeInfo` survives the `Bundler` round trip.
     *
     * This is the first thing that crosses the boundary and the one whose
     * failure looks like nothing: a `Bundleable` that does not decode makes the
     * app answer `onFailure` with a `BundlerException`, which Headway would
     * report as "the app declined" when in fact the host encoded it wrong.
     */
    @Test
    fun theHandshakeSurvivesTheWireFormat() {
        val sent = HandshakeInfo(context.packageName, CarAppApiLevels.getLatest())
        val decoded = Bundleable.create(sent).get() as HandshakeInfo
        assertEquals(context.packageName, decoded.hostPackageName)
        assertEquals(CarAppApiLevels.getLatest(), decoded.hostCarAppApiLevel)
    }

    /**
     * A whole template survives it too, with its rows and its click intact.
     *
     * `TemplateWrapper` is what `getTemplate` actually returns, so it is what
     * gets round-tripped rather than a bare `Template`. The row count and the
     * title are checked because those are what the renderer reads; the click
     * delegate is checked for presence because a row that decodes without one
     * is a row the driver taps and nothing happens.
     */
    @Suppress("DEPRECATION") // setTitle/getTitle: still what shipping apps send.
    @Test
    fun aListTemplateSurvivesTheWireFormat() {
        val template = ListTemplate.Builder()
            .setTitle("Places")
            .setSingleList(
                ItemList.Builder()
                    .addItem(
                        Row.Builder()
                            .setTitle("Home")
                            .addText("2.4 mi")
                            .setBrowsable(true)
                            .setOnClickListener { }
                            .build(),
                    )
                    .addItem(Row.Builder().setTitle("Work").build())
                    .build(),
            )
            .build()

        val decoded = Bundleable.create(TemplateWrapper.wrap(template)).get() as TemplateWrapper
        val list = decoded.template as ListTemplate

        assertEquals("Places", list.title?.toCharSequence().toString())
        val rows = list.singleList?.items.orEmpty()
        assertEquals(2, rows.size)
        val first = rows[0] as Row
        assertEquals("Home", first.title?.toCharSequence().toString())
        assertEquals("2.4 mi", first.texts.firstOrNull()?.toCharSequence().toString())
        assertTrue("a browsable row lost its flag", first.isBrowsable)
        assertNotNull("a row lost its click on the way across", first.onClickDelegate)
    }

    /** A standard action decodes as the standard action, not as a blank pill. */
    @Test
    fun aStandardActionSurvivesTheWireFormat() {
        val decoded = Bundleable.create(Action.BACK).get() as Action
        assertEquals(Action.TYPE_BACK, decoded.type)
        assertTrue(decoded.isStandard)
    }

    /**
     * Every maneuver type has words.
     *
     * The renderer falls back to these whenever an app sends a maneuver with no
     * cue string, which navigation apps routinely do for the simple turns. A
     * gap here is a blank instruction at a junction.
     */
    @Test
    fun everyManeuverTypeHasAnInstruction() {
        for (type in Maneuver.TYPE_UNKNOWN..LAST_MANEUVER_TYPE) {
            val words = CarAppTrips.describeManeuver(type)
            assertTrue("maneuver $type produced an empty instruction", words.isNotBlank())
        }
    }

    /** The host offers a level the library actually knows about. */
    @Test
    fun theHostApiLevelIsValid() {
        assertTrue(
            "the host would be rejected by every app",
            CarAppApiLevels.isValid(CarAppSession.HOST_API_LEVEL),
        )
    }

    /** Discovery runs and excludes Headway itself. */
    @Test
    fun discoveryNeverOffersHeadway() {
        val found = TemplateApps.installed(context)
        assertTrue(
            "Headway must not offer to host itself",
            found.none { it.packageName == context.packageName },
        )
    }

    /** `CarText` is unwrapped as text, not as its debug rendering. */
    @Test
    fun carTextComesOutAsWords() {
        assertEquals("Turn right", CarText.create("Turn right").plain())
        assertEquals(null, CarText.create("").plain())
        assertEquals(null, (null as CarText?).plain())
    }

    private companion object {
        const val TEMPLATE_RENDERER = "android.car.permission.TEMPLATE_RENDERER"

        /** `Maneuver.TYPE_FERRY_TRAIN_RIGHT`, the highest defined at 1.7.0. */
        const val LAST_MANEUVER_TYPE = 50
    }
}

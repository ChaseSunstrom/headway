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

package dev.headway.app.quirks

import aap_protobuf.service.control.message.HeadUnitInfoOuterClass.HeadUnitInfo
import aap_protobuf.service.control.message.ServiceDiscoveryResponseOuterClass.ServiceDiscoveryResponse
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.headway.protocol.channel.CarPoint
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The quirk file on a real device.
 *
 * On-device rather than on the JVM for two reasons that are not ceremony:
 * `org.json` is an Android platform class whose behaviour differs from the
 * `org.json` jar the JVM test classpath would supply — `optString` on a missing
 * key, `JSONObject.keys()` ordering, and what counts as a parse error all differ
 * — and this file is the recovery mechanism for a car that will not connect, so
 * it has to work on the platform it will be used on rather than on a stand-in.
 */
@RunWith(AndroidJUnit4::class)
class HeadUnitQuirksTest {

    private lateinit var file: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        file = File(context.cacheDir, "quirks-test-${System.nanoTime()}.json")
    }

    @After
    fun tearDown() {
        file.delete()
    }

    @Test
    fun defaultsAreTheValuesTheReferencesDocument() {
        val defaults = HeadUnitQuirks.DEFAULT
        // aasdk cMaxFramePayloadSize, MessageOutStream.hpp L62.
        assertEquals(0x4000, defaults.maxFragmentSize)
        // aasdk Version.hpp L22-L23.
        assertEquals(1, defaults.announcedVersionMajor)
        assertEquals(6, defaults.announcedVersionMinor)
        // AACS VideoChannelHandler.cpp L73-L74 key-int-max.
        assertEquals(25, defaults.keyframeIntervalFrames)
        // Media audio goes over AAP, not A2DP. CLAUDE.md L75 says the opposite
        // and rests on a premise that is false on the target vehicle: the head
        // unit disables its A2DP route the moment projection starts, so
        // "coexists with the AAP session" is silence rather than a fallback.
        // Gearhead's own capture from this unit does the same thing —
        // "disabling A2dp route while in projection" — and then streams the
        // driver's music over the AAP MEDIA channel. ADR 0005 records the
        // reversal and the evidence.
        assertTrue(defaults.mediaAudioOverAap)
        assertTrue(defaults.touch.isIdentity)
    }

    /**
     * A quirk file written before the default flipped must not pin the old one.
     *
     * This is the case that made the fix invisible on a real drive. The phone
     * had a version-1 file on it — written by an earlier build, containing
     * `"mediaAudioOverAap": false` because that was the default at the time and
     * every knob is serialised whether or not the user chose it. Flipping the
     * default in code changed nothing for that phone: the file said false, the
     * parser believed it, and the car stayed silent.
     *
     * The schema version is what distinguishes "the user turned this off" from
     * "a build that no longer exists wrote down its own default". Below
     * [QuirkStore.MEDIA_ROUTE_DEFAULT_CHANGED_IN] the key is ignored and
     * this build's default wins; at or above it, the file is authoritative
     * because a file at that version could only have got its value from someone
     * choosing it.
     */
    @Test
    fun aPreVersionTwoFileDoesNotPinTheOldMediaDefault() {
        file.writeText(
            """
            {
              "version": 1,
              "profiles": [
                { "make": "*", "model": "*", "mediaAudioOverAap": false }
              ]
            }
            """.trimIndent(),
        )
        val loaded = QuirkStore(file).load()
        val quirks = QuirkStore.resolve(loaded.profiles, HeadUnitIdentity(make = "Chevrolet"))
        assertTrue(
            "a version-1 file must not hold media audio on the old default",
            quirks.mediaAudioOverAap,
        )
        // Said out loud rather than applied silently: a driver reading the log
        // has to be able to see that a value in their file was overridden.
        assertTrue(
            "the override must be reported",
            loaded.warnings.any { it.contains("media audio", ignoreCase = true) },
        )
    }

    /** At the current schema version the file wins, because someone chose it. */
    @Test
    fun aCurrentVersionFileCanStillTurnMediaAudioOff() {
        file.writeText(
            """
            {
              "version": ${QuirkStore.SCHEMA_VERSION},
              "profiles": [
                { "make": "*", "model": "*", "mediaAudioOverAap": false }
              ]
            }
            """.trimIndent(),
        )
        val loaded = QuirkStore(file).load()
        val quirks = QuirkStore.resolve(loaded.profiles, HeadUnitIdentity(make = "Chevrolet"))
        assertFalse(quirks.mediaAudioOverAap)
    }

    @Test
    fun everyKnobSurvivesAJsonRoundTrip() {
        val profile = QuirkProfile(
            makePattern = "Chevrolet",
            modelPattern = "Infotainment*",
            quirks = HeadUnitQuirks(
                maxFragmentSize = 2000,
                announcedVersionMajor = 1,
                announcedVersionMinor = 1,
                mediaAudioOverAap = true,
                keyframeIntervalFrames = 30,
                // The Wi-Fi and certificate knobs were added later and were not
                // in this list, so "every knob" had quietly stopped being true.
                // They are the ones a real car actually needs adjusting, which
                // makes them the worst ones to let drift.
                hiddenSsid = true,
                announceWifiChannel = true,
                pinBssid = true,
                certificate = "internal",
                suggestCarNetwork = true,
                touch = TouchQuirks(
                    invertY = true,
                    swapAxes = true,
                    scaleX = 1.25,
                    offsetY = -4.5,
                    useTouchscreenGeometry = true,
                ),
            ),
        )

        val load = QuirkStore.parse(QuirkStore.serialize(listOf(profile)))

        assertEquals("round trip should warn about nothing: ${load.warnings}", 0, load.warnings.size)
        assertEquals(1, load.profiles.size)
        assertEquals(profile, load.profiles[0])
    }

    /**
     * Headway must not warn about its own output.
     *
     * `serialize` writes the template users edit, and `parse` reports any key it
     * does not recognise. A knob added to one and not the other means a user
     * sets a documented setting and is told it was ignored — which is worse
     * than not having the knob, because it reads as their mistake. `certificate`
     * shipped in exactly that state, so this is a regression test, not a
     * precaution.
     */
    @Test
    fun everyKeyTheTemplateWritesIsAKeyTheParserKnows() {
        // Every nullable knob set, because serialize omits the ones left null —
        // and those are exactly the ones that slipped through before.
        val everything = QuirkProfile(
            makePattern = "Chevrolet",
            modelPattern = "Infotainment*",
            quirks = HeadUnitQuirks.DEFAULT.copy(
                pinBssid = true,
                certificate = "internal",
                suggestCarNetwork = true,
            ),
        )
        // serialize() wraps the profiles in {"version":N,"profiles":[...]}.
        val written = org.json.JSONObject(QuirkStore.serialize(listOf(everything)))
            .getJSONArray("profiles")
            .getJSONObject(0)
            .keys().asSequence().toSet()

        val unknown = written - QuirkStore.knownProfileKeys
        assertTrue(
            "serialize writes $unknown, which parse would report as unknown keys. " +
                "Add them to PROFILE_KEYS.",
            unknown.isEmpty(),
        )
    }

    @Test
    fun unknownKeysAreIgnoredAndReported() {
        // The case the warnings exist for: a user hand-edits the file, mistypes a
        // key, and would otherwise be left believing the knob does nothing.
        file.writeText(
            """
            {
              "version": 1,
              "surpriseTopLevelKey": true,
              "profiles": [
                {
                  "make": "Chevrolet",
                  "maxFragmentsize": 2000,
                  "keyframeIntervalFrames": 12,
                  "touch": { "invertZ": true, "invertY": true }
                }
              ]
            }
            """.trimIndent(),
        )

        val load = QuirkStore(file).load()

        assertTrue(
            "unknown top-level key not reported: ${load.warnings}",
            load.warnings.any { it.contains("surpriseTopLevelKey") },
        )
        assertTrue(
            "misspelled profile key not reported: ${load.warnings}",
            load.warnings.any { it.contains("maxFragmentsize") },
        )
        assertTrue(
            "unknown touch key not reported: ${load.warnings}",
            load.warnings.any { it.contains("invertZ") },
        )

        val quirks = QuirkStore.resolve(load.profiles, HeadUnitIdentity(make = "Chevrolet"))
        // The misspelled key left the default in place...
        assertEquals(HeadUnitQuirks.DEFAULT.maxFragmentSize, quirks.maxFragmentSize)
        // ...while everything the file spelled correctly still applied.
        assertEquals(12, quirks.keyframeIntervalFrames)
        assertTrue(quirks.touch.invertY)
    }

    @Test
    fun garbageInAValueDoesNotDiscardTheWholeProfile() {
        file.writeText(
            """
            {"profiles":[{"make":"*","maxFragmentSize":"enormous",
             "announcedVersion":"six","keyframeIntervalFrames":99999,
             "mediaAudioOverAap":true}]}
            """.trimIndent(),
        )

        val load = QuirkStore(file).load()
        val quirks = QuirkStore.resolve(load.profiles, HeadUnitIdentity())

        assertEquals(3, load.warnings.size)
        assertEquals(HeadUnitQuirks.DEFAULT.maxFragmentSize, quirks.maxFragmentSize)
        assertEquals(HeadUnitQuirks.DEFAULT.announcedVersionMinor, quirks.announcedVersionMinor)
        // 99999 frames is out of range and falls back rather than being clamped
        // to something the user did not ask for.
        assertEquals(HeadUnitQuirks.DEFAULT.keyframeIntervalFrames, quirks.keyframeIntervalFrames)
        assertTrue("the one valid field should still apply", quirks.mediaAudioOverAap)
    }

    @Test
    fun invalidJsonLeavesTheBuiltInDefaultInPlace() {
        file.writeText("{ not json at all")

        val load = QuirkStore(file).load()

        assertTrue(load.hasWarnings)
        // The built-in catch-all is still there, so a broken file cannot stop a
        // session from being attempted.
        assertEquals(
            HeadUnitQuirks.DEFAULT,
            QuirkStore.resolve(load.profiles, HeadUnitIdentity(make = "Anything")),
        )
    }

    @Test
    fun anAbsentFileBehavesExactlyLikeAnEmptyOne() {
        assertFalse(file.exists())
        val load = QuirkStore(file).load()
        assertFalse(load.hasWarnings)
        assertEquals(
            HeadUnitQuirks.DEFAULT,
            QuirkStore.resolve(load.profiles, HeadUnitIdentity(make = "Chevrolet")),
        )
    }

    @Test
    fun theMostSpecificMatchWins() {
        val wildcard = QuirkProfile(quirks = HeadUnitQuirks(maxFragmentSize = 1000))
        val byMake = QuirkProfile("Chevrolet", quirks = HeadUnitQuirks(maxFragmentSize = 2000))
        val byPrefix = QuirkProfile(
            "Chevrolet", "Infotainment*", HeadUnitQuirks(maxFragmentSize = 3000),
        )
        val exact = QuirkProfile(
            "Chevrolet", "Infotainment 3", HeadUnitQuirks(maxFragmentSize = 4000),
        )
        // Listed least-specific last on purpose: order must not decide this.
        val profiles = listOf(exact, byPrefix, byMake, wildcard)

        assertEquals(
            4000,
            QuirkStore.resolve(profiles, HeadUnitIdentity("Chevrolet", "Infotainment 3"))
                .maxFragmentSize,
        )
        assertEquals(
            3000,
            QuirkStore.resolve(profiles, HeadUnitIdentity("Chevrolet", "Infotainment 4"))
                .maxFragmentSize,
        )
        assertEquals(
            2000,
            QuirkStore.resolve(profiles, HeadUnitIdentity("Chevrolet", "Something else"))
                .maxFragmentSize,
        )
        assertEquals(
            1000,
            QuirkStore.resolve(profiles, HeadUnitIdentity("Ford", "Sync 3")).maxFragmentSize,
        )
    }

    @Test
    fun matchingIgnoresCaseAndSurroundingSpace() {
        val profiles = listOf(QuirkProfile("chevrolet", "infotainment 3"))
        assertTrue(profiles[0].matches(HeadUnitIdentity("  CHEVROLET ", "Infotainment 3")))
        assertFalse(profiles[0].matches(HeadUnitIdentity("Chevrolet", null)))
    }

    @Test
    fun aStoreInAppStorageWritesATemplateOnceAndThenLeavesItAlone() {
        val store = QuirkStore(file)

        assertTrue("first call should create the file", store.writeTemplateIfAbsent())
        val edited = QuirkStore.serialize(
            listOf(QuirkProfile("Ford", "*", HeadUnitQuirks(maxFragmentSize = 8192))),
        )
        file.writeText(edited)

        assertFalse("second call must not clobber a user's edits", store.writeTemplateIfAbsent())
        assertEquals(edited, file.readText())
        assertEquals(
            8192,
            store.quirksFor(HeadUnitIdentity("Ford", "Sync 4")).maxFragmentSize,
        )
    }

    @Test
    fun identityPrefersTheNonDeprecatedHeadUnitInfo() {
        @Suppress("DEPRECATION")
        val response = ServiceDiscoveryResponse.newBuilder()
            .setMake("DeprecatedVehicleMake")
            .setModel("DeprecatedVehicleModel")
            .setHeadUnitMake("DeprecatedRadioMake")
            .setHeadunitInfo(
                HeadUnitInfo.newBuilder()
                    .setMake("Chevrolet")
                    .setModel("Malibu")
                    .setYear("2021")
                    .setHeadUnitMake("GM")
                    .setHeadUnitModel("Infotainment 3")
                    .setHeadUnitSoftwareVersion("12.3")
                    .build(),
            )
            .build()

        val identity = HeadUnitIdentity.from(response)

        // The radio, not the vehicle: quirks belong to the head unit, which is
        // shared across models.
        assertEquals("GM", identity.make)
        assertEquals("Infotainment 3", identity.model)
        assertEquals("2021", identity.year)
        assertEquals("12.3", identity.softwareVersion)
    }

    @Test
    fun identityFallsBackToTheDeprecatedFieldsAndToleratesAnEmptyResponse() {
        @Suppress("DEPRECATION")
        val onlyDeprecated = ServiceDiscoveryResponse.newBuilder()
            .setHeadUnitMake("Pioneer")
            .setHeadUnitModel("SPH-DA")
            .build()
        val identity = HeadUnitIdentity.from(onlyDeprecated)
        assertEquals("Pioneer", identity.make)
        assertEquals("SPH-DA", identity.model)

        val empty = HeadUnitIdentity.from(ServiceDiscoveryResponse.newBuilder().build())
        assertEquals(null, empty.make)
        assertEquals(null, empty.model)
        // A unit that identifies itself as nothing still gets the default profile.
        assertEquals(
            HeadUnitQuirks.DEFAULT,
            QuirkStore.resolve(listOf(QuirkProfile()), empty),
        )
    }

    @Test
    fun touchCorrectionIsSkippedEntirelyWhenItIsTheIdentity() {
        val point = CarPoint(123.0, 45.0)
        assertEquals(point, TouchQuirks().correct(point, 800, 480))
    }

    @Test
    fun touchCorrectionAppliesSwapThenInvertThenScaleThenOffset() {
        val quirks = TouchQuirks(invertY = true, swapAxes = true, scaleX = 2.0, offsetY = 10.0)

        // (100, 200) -> swap (200, 100) -> invertY (200, 480-100=380)
        //            -> scale (400, 380) -> offset (400, 390)
        val corrected = quirks.correct(CarPoint(100.0, 200.0), carWidth = 800, carHeight = 480)

        assertEquals(400.0, corrected.x, 1e-9)
        assertEquals(390.0, corrected.y, 1e-9)
        assertNotEquals(CarPoint(100.0, 200.0), corrected)
    }

    @Test
    fun announcedVersionIsUsableAsAProtocolVersion() {
        val quirks = HeadUnitQuirks(announcedVersionMajor = 1, announcedVersionMinor = 1)
        assertEquals("1.1", quirks.announcedVersion.toString())
    }
}

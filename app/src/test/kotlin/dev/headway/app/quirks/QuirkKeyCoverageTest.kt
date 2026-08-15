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

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The three lists that describe a quirk profile must agree.
 *
 * ## Why this exists
 *
 * `QuirkStore` has three independent descriptions of what a profile contains:
 * the keys `serialize` writes, the keys the parser reads, and `PROFILE_KEYS`,
 * which is what "unknown key" warnings are measured against. Nothing made them
 * agree, and `knownProfileKeys` was added with the comment "Exposed so a test
 * can prove [PROFILE_KEYS] covers everything written" — and then no test was
 * written.
 *
 * They duly drifted. `videoFocusRequest` was parsed and honoured, and named in
 * the KDoc of the field it sets, but was in neither `PROFILE_KEYS` nor
 * `serialize`. A driver who followed that KDoc got a file that worked *and* a
 * warning saying the key was ignored — the worst of both, because the warning is
 * indistinguishable from a typo and would send them chasing a problem that was
 * not there.
 *
 * This is a pure-JVM test: `org.json` is on the unit-test classpath for real
 * (see the note in `app/build.gradle.kts`), so no device and no Robolectric.
 */
class QuirkKeyCoverageTest {

    @Test
    fun `every key the template writes is a key the store recognises`() {
        val written = writtenKeys()
        val unknown = written - QuirkStore.knownProfileKeys
        assertTrue(
            unknown.isEmpty(),
            "serialize() writes $unknown, which PROFILE_KEYS does not list, so a file " +
                "round-tripped through Headway warns about its own output",
        )
    }

    @Test
    fun `the template offers every knob the store recognises`() {
        // `pinBssid` and `certificate` are deliberately absent from a fresh
        // template: an absent key is what "let Headway choose" means for both,
        // and writing a value would pin behaviour the driver did not ask for.
        // See the comments in `serialize`.
        val optional = setOf("pinBssid", "certificate")
        val missing = QuirkStore.knownProfileKeys - writtenKeys() - optional
        assertTrue(
            missing.isEmpty(),
            "PROFILE_KEYS lists $missing, which the template never writes, so the " +
                "starting point Headway generates hides a knob it accepts",
        )
    }

    @Test
    fun `a template round-trips without warnings`() {
        val loaded = QuirkStore.parse(templateJson())
        assertEquals(
            emptyList<String>(),
            loaded.warnings,
            "a file Headway generated must load with nothing to say about it",
        )
    }

    private fun writtenKeys(): Set<String> {
        val root = JSONObject(templateJson())
        val profile = root.getJSONArray("profiles").getJSONObject(0)
        return profile.keys().asSequence().toSet()
    }

    private fun templateJson(): String = QuirkStore.serialize(listOf(QuirkProfile()))
}

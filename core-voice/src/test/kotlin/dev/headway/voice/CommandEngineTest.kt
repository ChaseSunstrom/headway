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

package dev.headway.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * The grammar, with no speech model in the way.
 *
 * `Phase5VoiceAcceptanceTest` runs the whole audio-to-command path and is the
 * acceptance test, but it needs a 68 MB model and skips without one. The
 * grammar itself is pure string handling and deserves a test that always runs —
 * especially the ordering between rules, which is the part that breaks silently
 * when a new rule is added in the wrong place.
 */
class CommandEngineTest {

    private val apps = listOf(
        InstalledApp("com.android.calculator2", "Calculator"),
        InstalledApp("app.organicmaps", "Organic Maps"),
        InstalledApp("org.videolan.vlc", "VLC"),
    )

    private fun engine() = CommandEngine(apps)

    // --- navigation ---------------------------------------------------------

    @Test
    fun `every navigation verb produces a destination`() {
        val cases = mapOf(
            "navigate to the airport" to "airport",
            "directions to euston station" to "euston station",
            "take me to work" to "work",
            "drive to the beach" to "beach",
            "route to home depot" to "home depot",
            "find the nearest petrol station" to "petrol station",
        )
        cases.forEach { (spoken, expected) ->
            val command = engine().parse(spoken)
            val navigate = assertInstanceOf(
                VoiceCommand.Navigate::class.java,
                command,
                "\"$spoken\" did not parse as navigation",
            )
            assertEquals(expected, navigate.destination, "wrong destination from \"$spoken\"")
        }
    }

    /**
     * "find" is a search verb *and* the start of a navigation phrase.
     *
     * The ordering that resolves it is deliberate and easy to break: navigation
     * is matched first because typing a destination into whatever happens to be
     * on screen is a driver looking at a phone, and the more specific reading
     * wins. A plain "find" with no "nearest" is still a search.
     */
    @Test
    fun `navigation beats search only where it is more specific`() {
        assertInstanceOf(
            VoiceCommand.Navigate::class.java,
            engine().parse("find the nearest car park"),
        )
        val search = assertInstanceOf(
            VoiceCommand.Search::class.java,
            engine().parse("find a recipe for bread"),
        )
        assertEquals("a recipe for bread", search.text)
    }

    /**
     * A bare place name is not a navigation request.
     *
     * The verb is required here where it is optional for a launch, because
     * starting a route hands the car screen to a map app: doing that because
     * somebody said a word that happened to be a place is a worse failure than
     * not understanding them.
     */
    @Test
    fun `a bare place name never starts navigation`() {
        val command = engine().parse("the airport")
        assertEquals(VoiceCommand.Unrecognised("the airport"), command)
    }

    /** A verb with nothing after it is not a destination. */
    @Test
    fun `a navigation verb alone is not a route`() {
        listOf("navigate to", "take me to", "directions").forEach { spoken ->
            val command = engine().parse(spoken)
            assertEquals(
                VoiceCommand.Unrecognised(spoken),
                command,
                "\"$spoken\" invented a destination",
            )
        }
    }

    /**
     * A destination whose last word is a media word is still a destination.
     *
     * `matchMedia` used to accept any transcript merely *ending* with a media
     * phrase, and it ran before `matchNavigate`. MEDIA_PHRASES contains bare
     * "stop", "next" and "back", so "navigate to the nearest bus stop" halted
     * the music and started no route. The single-word fixtures in the
     * acceptance suite could never see it.
     */
    @Test
    fun `a destination ending in a media word still routes`() {
        val cases = mapOf(
            "navigate to the nearest bus stop" to "nearest bus stop",
            "take me to the truck stop" to "truck stop",
            "drive to the next stop" to "next stop",
            "directions to paddington station" to "paddington station",
        )
        cases.forEach { (spoken, expected) ->
            val navigate = assertInstanceOf(
                VoiceCommand.Navigate::class.java,
                engine().parse(spoken),
                "\"$spoken\" was not treated as a route",
            )
            assertEquals(expected, navigate.destination, "wrong destination from \"$spoken\"")
        }
    }

    /**
     * A transport command surrounded by filler is still a transport command.
     *
     * The other half of the same fix: tightening the media rule must not cost
     * the mis-hearings it was loose for in the first place.
     */
    @Test
    fun `filler around a transport word does not stop it matching`() {
        listOf("pause please", "just pause", "pause the music", "and pause", "next track")
            .forEach { spoken ->
                assertInstanceOf(
                    VoiceCommand.Media::class.java,
                    engine().parse(spoken),
                    "\"$spoken\" stopped being a transport command",
                )
            }
    }

    // --- the rules navigation had to be threaded between ---------------------

    @Test
    fun `the rules that were already there still win where they should`() {
        assertEquals(VoiceCommand.GoHome, engine().parse("go home"))
        assertEquals(VoiceCommand.Media(MediaAction.PAUSE), engine().parse("pause"))
        assertEquals(
            VoiceCommand.Volume(VolumeDirection.UP, 2),
            engine().parse("volume up two"),
        )
    }

    /**
     * "go home" is a home request and "drive to home depot" is not.
     *
     * Both start with a word in `LAUNCH_VERBS`, and `GO_HOME_PHRASES` contains
     * the bare word "home". The distinction is exact-phrase matching for home
     * and prefix matching for navigation, which is the kind of thing that holds
     * only until somebody adds a phrase to the wrong set.
     */
    @Test
    fun `home and a destination containing home stay apart`() {
        assertEquals(VoiceCommand.GoHome, engine().parse("home"))
        val navigate = assertInstanceOf(
            VoiceCommand.Navigate::class.java,
            engine().parse("drive to home depot"),
        )
        assertEquals("home depot", navigate.destination)
    }

    @Test
    fun `an app name still launches without a verb`() {
        val launch = assertInstanceOf(
            VoiceCommand.LaunchApp::class.java,
            engine().parse("calculator"),
        )
        assertEquals("com.android.calculator2", launch.packageName)
    }
}

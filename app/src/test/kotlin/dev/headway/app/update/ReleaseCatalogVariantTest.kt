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

package dev.headway.app.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The contract between the release job's filenames and the updater.
 *
 * `ReleaseCatalog.chooseApk` matches on a `-host.apk` / `-compat.apk` suffix,
 * and its own KDoc warns that renaming the assets without changing it "silently
 * sends every updater down the single-APK fallback". A release now attaches
 * *four* APKs rather than two — a release build and a debug build of each
 * variant — which makes that warning live rather than theoretical: the debug
 * names have to stay outside the suffix the updater matches, or an install
 * would follow itself onto the wrong build type.
 *
 * These are the names the release workflow actually produces, spelled out here
 * so that changing them fails a test rather than a driver's next update.
 */
class ReleaseCatalogVariantTest {

    private val stem = "headway-0.1.0-dev-build200-abc1234"

    private fun apk(name: String) = Triple(name, "https://example.invalid/$name", 1L)

    /** What the release job attaches: release first, then the debug fallbacks. */
    private val published = listOf(
        apk("$stem-host.apk"),
        apk("$stem-compat.apk"),
        apk("$stem-host-debug.apk"),
        apk("$stem-compat-debug.apk"),
    )

    @Test
    fun `an install follows its own variant onto the release build`() {
        assertEquals("$stem-host.apk", ReleaseCatalog.chooseApk(published, "host")?.first)
        assertEquals("$stem-compat.apk", ReleaseCatalog.chooseApk(published, "compat")?.first)
    }

    @Test
    fun `a debug asset never satisfies a variant match`() {
        // The whole risk of adding two more APKs. `-host-debug.apk` must not
        // end with `-host.apk`, or half of all installs would be handed a debug
        // build by an updater that believed it had matched.
        val debugOnly = listOf(apk("$stem-host-debug.apk"), apk("$stem-compat-debug.apk"))
        assertNull(
            ReleaseCatalog.chooseApk(debugOnly, "host"),
            "a debug asset was accepted as the host variant",
        )
        assertNull(
            ReleaseCatalog.chooseApk(debugOnly, "compat"),
            "a debug asset was accepted as the compat variant",
        )
    }

    @Test
    fun `a release with one APK is still readable`() {
        // Every release up to build 77 looks like this, and an update from one
        // of those has to work. The single-APK fallback is what makes it.
        val single = listOf(apk("$stem.apk"))
        assertEquals("$stem.apk", ReleaseCatalog.chooseApk(single, "host")?.first)
    }

    @Test
    fun `several APKs and no match is skipped rather than guessed`() {
        // Leaving the driver on a build that works is the better failure.
        assertNull(ReleaseCatalog.chooseApk(published, "wear"))
    }

    @Test
    fun `no APKs at all is not a crash`() {
        assertNull(ReleaseCatalog.chooseApk(emptyList(), "host"))
    }
}

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

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The update decision logic, against the shape GitHub actually returns.
 *
 * An instrumentation test rather than a unit test because it parses with
 * `org.json`, whose JVM stub throws on every call — testing it off-device would
 * be testing a different parser than the one that ships.
 */
@RunWith(AndroidJUnit4::class)
class ReleaseCatalogTest {

    /** Trimmed from a real response; the fields Headway reads are verbatim. */
    private fun feed(
        vararg entries: String,
    ): String = "[${entries.joinToString(",")}]"

    private fun release(
        tag: String,
        assetName: String? = "headway-0.1.0-dev-build19-abc1234-debug.apk",
        draft: Boolean = false,
        prerelease: Boolean = true,
        size: Long = 12_345_678,
    ): String {
        val assets = if (assetName == null) "[]" else """
            [{"name":"$assetName",
              "size":$size,
              "browser_download_url":"https://github.com/o/r/releases/download/$tag/$assetName"}]
        """.trimIndent()
        return """
            {"tag_name":"$tag",
             "name":"Headway 0.1.0-dev ($tag)",
             "draft":$draft,
             "prerelease":$prerelease,
             "body":"notes",
             "assets":$assets}
        """.trimIndent()
    }

    @Test
    fun buildNumberIsReadFromTheTag() {
        assertEquals(19, ReleaseCatalog.buildNumberOf("v0.1.0-dev-build.19"))
        assertEquals(7, ReleaseCatalog.buildNumberOf("v0.1.0-dev-build.7"))
        assertEquals(123, ReleaseCatalog.buildNumberOf("v9.9.9-build.123"))
    }

    @Test
    fun tagsThatAreNotOursAreIgnored() {
        // A hand-cut tag must not be mistaken for a build, or the updater offers
        // something whose versionCode Android will refuse as a downgrade.
        assertNull(ReleaseCatalog.buildNumberOf("v0.2.0"))
        assertNull(ReleaseCatalog.buildNumberOf("nightly"))
        assertNull(ReleaseCatalog.buildNumberOf("v0.1.0-dev-build."))
    }

    @Test
    fun theNewestBuildWins() {
        // GitHub returns newest first, but ordering is not promised anywhere, so
        // the highest build number must win regardless of position.
        val json = feed(
            release("v0.1.0-dev-build.17"),
            release("v0.1.0-dev-build.21"),
            release("v0.1.0-dev-build.19"),
        )
        val found = ReleaseCatalog.newestSince(json, currentBuild = 16)
        assertEquals(21, found?.buildNumber)
    }

    @Test
    fun anUpToDateInstallIsOfferedNothing() {
        val json = feed(release("v0.1.0-dev-build.19"))
        assertNull(ReleaseCatalog.newestSince(json, currentBuild = 19))
        assertNull(ReleaseCatalog.newestSince(json, currentBuild = 20))
    }

    @Test
    fun prereleasesAreOfferedBecauseEveryReleaseIsOne() {
        // Skipping prereleases would offer nothing, ever: the publish job passes
        // --prerelease on every build.
        val json = feed(release("v0.1.0-dev-build.19", prerelease = true))
        assertEquals(19, ReleaseCatalog.newestSince(json, currentBuild = 18)?.buildNumber)
    }

    @Test
    fun draftsAreSkipped() {
        val json = feed(release("v0.1.0-dev-build.20", draft = true))
        assertNull(ReleaseCatalog.newestSince(json, currentBuild = 18))
    }

    @Test
    fun aReleaseWithNoApkDoesNotMaskTheOneBelowIt() {
        // A publish that failed after creating the release leaves exactly this.
        // Reporting it would offer an update that cannot be downloaded; hiding
        // the good build under it would be worse.
        val json = feed(
            release("v0.1.0-dev-build.20", assetName = null),
            release("v0.1.0-dev-build.19"),
        )
        val found = ReleaseCatalog.newestSince(json, currentBuild = 18)
        assertEquals(19, found?.buildNumber)
    }

    @Test
    fun theApkAssetIsTheOneSelected() {
        val json = feed(release("v0.1.0-dev-build.19"))
        val found = ReleaseCatalog.newestSince(json, currentBuild = 1)
        assertTrue(found!!.apkName.endsWith(".apk"))
        assertTrue(found.apkUrl.startsWith("https://"))
        assertEquals(12_345_678L, found.sizeBytes)
    }

    @Test
    fun anEmptyFeedIsNotAnError() {
        // A repository with no releases yet, which is a normal state and must
        // not read as a failure to the user.
        assertNull(ReleaseCatalog.newestSince("[]", currentBuild = 1))
    }

    @Test
    fun aMalformedFeedFailsLoudly() {
        // Better a reported failure than silently deciding there is no update.
        assertThrows(JSONException::class.java) {
            ReleaseCatalog.newestSince("not json", currentBuild = 1)
        }
    }

    @Test
    fun theFeedUrlIsHttpsAndAsksForTheRepository() {
        val url = ReleaseCatalog.feedUrl("ChaseSunstrom/headway")
        assertTrue(url, url.startsWith("https://api.github.com/repos/ChaseSunstrom/headway/releases"))
    }

    // --- picking between the two published APKs -------------------------------

    private fun apk(name: String) = Triple(name, "https://example/$name", 1L)

    /**
     * An update keeps the variant it started on.
     *
     * Both directions are real failures, not tidiness. Handing a `host` install
     * the compat APK silently removes the car-app host on an ordinary update —
     * the app keeps working and the Car apps tab quietly goes dark. Handing a
     * `compat` install the host APK reproduces the exact "App not installed"
     * that made the split necessary, on a phone that had a working Headway a
     * moment earlier.
     */
    @Test
    fun theUpdaterStaysOnItsOwnVariant() {
        val both = listOf(
            apk("headway-0.1.0-dev-build90-abc1234-host.apk"),
            apk("headway-0.1.0-dev-build90-abc1234-compat.apk"),
        )
        assertEquals(
            "headway-0.1.0-dev-build90-abc1234-host.apk",
            ReleaseCatalog.chooseApk(both, "host")?.first,
        )
        assertEquals(
            "headway-0.1.0-dev-build90-abc1234-compat.apk",
            ReleaseCatalog.chooseApk(both, "compat")?.first,
        )
        // Order must not decide it: GitHub promises nothing about asset order.
        assertEquals(
            "headway-0.1.0-dev-build90-abc1234-compat.apk",
            ReleaseCatalog.chooseApk(both.reversed(), "compat")?.first,
        )
    }

    /**
     * A release from before the split still updates.
     *
     * Every release up to build 77 carries one APK with no variant in its name.
     * A strict match would make those invisible and strand anyone sitting on an
     * older build, which is the opposite of what this whole change is for.
     */
    @Test
    fun aSingleUnlabelledApkIsStillTaken() {
        val legacy = listOf(apk("headway-0.1.0-dev-build77-abb7a62-debug.apk"))
        assertEquals(
            "headway-0.1.0-dev-build77-abb7a62-debug.apk",
            ReleaseCatalog.chooseApk(legacy, "host")?.first,
        )
        assertEquals(
            "headway-0.1.0-dev-build77-abb7a62-debug.apk",
            ReleaseCatalog.chooseApk(legacy, "compat")?.first,
        )
    }

    /**
     * Several APKs and none of them ours: refuse rather than guess.
     *
     * Skipping the release leaves the user on a build that works. Picking the
     * first one would be a coin flip between "silently lose the car-app host"
     * and "fail to install", decided by whatever order GitHub happened to
     * return.
     */
    @Test
    fun anAmbiguousReleaseIsSkippedRatherThanGuessed() {
        val unlabelled = listOf(apk("one-debug.apk"), apk("two-debug.apk"))
        assertNull(ReleaseCatalog.chooseApk(unlabelled, "host"))
        assertNull(ReleaseCatalog.chooseApk(emptyList(), "host"))
    }

    /** And the whole path end to end, through the feed. */
    @Test
    fun theFeedYieldsTheVariantsApk() {
        val assets = listOf("host", "compat").joinToString(",") { variant ->
            val name = "headway-0.1.0-dev-build90-abc1234-$variant.apk"
            """{"name":"$name","size":99,"browser_download_url":"https://example/$name"}"""
        }
        val json = """
            [{"tag_name":"v0.1.0-dev-build.90","name":"n","draft":false,
              "prerelease":true,"body":"","assets":[$assets]}]
        """.trimIndent()

        assertEquals(
            "headway-0.1.0-dev-build90-abc1234-compat.apk",
            ReleaseCatalog.newestSince(json, currentBuild = 89, variant = "compat")?.apkName,
        )
        assertEquals(
            "headway-0.1.0-dev-build90-abc1234-host.apk",
            ReleaseCatalog.newestSince(json, currentBuild = 89, variant = "host")?.apkName,
        )
    }
}

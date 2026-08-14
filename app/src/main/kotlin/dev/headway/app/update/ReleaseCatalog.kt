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

import org.json.JSONArray

/**
 * One published build that could be installed over this one.
 *
 * @param buildNumber the CI run number, parsed from the tag. This is also the
 *   APK's `versionCode`, which is what Android compares when deciding whether an
 *   install is an upgrade, so the two cannot disagree.
 */
data class AvailableRelease(
    val tag: String,
    val name: String,
    val buildNumber: Int,
    val apkName: String,
    val apkUrl: String,
    val sizeBytes: Long,
    val notes: String,
) {
    /** For the button label; bytes are not a unit anyone reads. */
    val sizeMegabytes: String get() = "%.1f MB".format(sizeBytes / 1_048_576.0)
}

/**
 * Reads GitHub's releases feed and decides whether a newer build exists.
 *
 * Deliberately separated from everything that touches the network or the package
 * installer, because this is the part with the decisions in it — which release
 * counts as newer, which asset is the APK, what to do with a malformed feed —
 * and that part should be testable without either.
 *
 * ## A note on CLAUDE.md
 *
 * Hard constraint 3 says "no telemetry, no update checks". This is an update
 * check, added at the user's explicit request, and the constraint is worth
 * honouring in the ways that actually matter rather than deleting:
 *
 * - Nothing here ever runs on its own. There is no background poll, no check on
 *   launch, no schedule. It runs when the user presses the button and at no
 *   other time, so the app still starts and drives with no network at all.
 * - It is not on the car path. The session uses a socket bound to the car's
 *   access point; this uses the default network, and the two never meet. A
 *   phone in airplane mode with Wi-Fi and Bluetooth still connects to the car.
 * - Nothing is sent. The request is an unauthenticated GET of a public feed,
 *   with no identifier, no version string in a query, and no response body
 *   posted anywhere.
 */
object ReleaseCatalog {

    /** GitHub's releases feed, newest first. */
    fun feedUrl(repository: String): String =
        "https://api.github.com/repos/$repository/releases?per_page=20"

    /**
     * The build number encoded in a release tag, or null if the tag is not one
     * of ours.
     *
     * Tags look like `v0.1.0-dev-build.19`. The version segment is deliberately
     * ignored: it has not changed across any release so far, so ordering by it
     * would rank every build equal. The build number is the only part that
     * increases, and it is the same number the APK carries as its `versionCode`.
     */
    fun buildNumberOf(tag: String): Int? =
        Regex("""build\.(\d+)$""").find(tag)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Picks the newest installable release strictly newer than [currentBuild].
     *
     * Returns null when the feed has nothing newer, which is the normal case and
     * not an error. Draft releases are skipped because their assets are not
     * public; prereleases are *not* skipped, since every Headway release is one.
     *
     * A release whose assets contain no APK is skipped rather than reported: a
     * publish that failed halfway leaves exactly that, and it should not mask
     * the last good build underneath it.
     */
    fun newestSince(
        json: String,
        currentBuild: Int,
        variant: String = "",
    ): AvailableRelease? {
        val releases = JSONArray(json)
        var best: AvailableRelease? = null

        for (i in 0 until releases.length()) {
            val release = releases.optJSONObject(i) ?: continue
            if (release.optBoolean("draft", false)) continue

            val tag = release.optString("tag_name").orEmpty()
            val build = buildNumberOf(tag) ?: continue
            if (build <= currentBuild) continue
            if (best != null && build <= best.buildNumber) continue

            val assets = release.optJSONArray("assets") ?: continue
            val apks = mutableListOf<Triple<String, String, Long>>()
            for (j in 0 until assets.length()) {
                val asset = assets.optJSONObject(j) ?: continue
                val name = asset.optString("name").orEmpty()
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                apks += Triple(
                    name,
                    asset.optString("browser_download_url").orEmpty(),
                    asset.optLong("size", 0L),
                )
            }
            val chosen = chooseApk(apks, variant) ?: continue
            val (apkName, apkUrl, apkSize) = chosen
            if (apkUrl.isEmpty()) continue

            best = AvailableRelease(
                tag = tag,
                name = release.optString("name").ifEmpty { tag },
                buildNumber = build,
                apkName = apkName,
                apkUrl = apkUrl,
                sizeBytes = apkSize,
                notes = release.optString("body").orEmpty(),
            )
        }
        return best
    }

    /**
     * Picks the APK belonging to the variant that is running.
     *
     * A release carries two APKs that differ only in whether they declare the
     * car-app host's permission and provider — see `app/build.gradle.kts`.
     * Handing a `host` install the `compat` APK would silently strip the car-app
     * host on an ordinary update, and handing a `compat` install the `host` APK
     * would fail with the very "App not installed" the split exists to prevent.
     * Neither is acceptable as a silent outcome, so the variant is matched by
     * the suffix the release job puts on the filename.
     *
     * Falls back to the single APK when a release has exactly one, which is what
     * every release up to build 77 looks like and what an update from one of
     * those has to be able to read. Deliberately does *not* guess when there are
     * several and none matches: skipping the release leaves the user on a build
     * that works, which is the better failure.
     */
    fun chooseApk(
        apks: List<Triple<String, String, Long>>,
        variant: String,
    ): Triple<String, String, Long>? {
        if (apks.isEmpty()) return null
        if (variant.isNotEmpty()) {
            val suffix = "-$variant.apk"
            apks.firstOrNull { it.first.endsWith(suffix, ignoreCase = true) }?.let { return it }
        }
        return apks.singleOrNull()
    }
}

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

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import dev.headway.app.BuildConfig
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Raised when an update cannot be fetched or handed to the installer. */
class UpdateException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * Checks for, downloads and installs a newer Headway from GitHub releases.
 *
 * Nothing here runs unless the user asks it to — see the note on
 * [ReleaseCatalog] about CLAUDE.md's "no update checks" constraint and how this
 * stays inside it.
 *
 * ## Why `PackageInstaller` and not an install intent
 *
 * Both are unprivileged and both end at the same system confirmation dialog.
 * `PackageInstaller` is used because it reports *why* an install failed through
 * its status callback, where `ACTION_VIEW` on an APK just returns and leaves the
 * app guessing. Given the failure this was written to fix showed up as a bare
 * "App not installed" with no cause anywhere, that reporting is the point.
 */
class AppUpdater(private val context: Context) {

    /**
     * Asks GitHub what the newest published build is.
     *
     * @return the newer release, or null when this build is already current.
     */
    suspend fun check(
        repository: String = BuildConfig.UPDATE_REPOSITORY,
        currentBuild: Int = BuildConfig.VERSION_CODE,
        // The running flavour, so an update never silently swaps it. `host` and
        // `compat` differ only in two manifest declarations, but crossing
        // between them either strips the car-app host or fails to install --
        // see ReleaseCatalog.chooseApk.
        variant: String = BuildConfig.FLAVOR,
    ): AvailableRelease? = withContext(Dispatchers.IO) {
        val body = get(ReleaseCatalog.feedUrl(repository))
        try {
            ReleaseCatalog.newestSince(body, currentBuild, variant)
        } catch (e: Exception) {
            throw UpdateException("GitHub returned a release feed we could not read", e)
        }
    }

    /**
     * Downloads the release's APK into this app's cache directory.
     *
     * The cache directory rather than Downloads: it needs no storage permission,
     * it is private to Headway so nothing can swap the file between download and
     * install, and the system reclaims it if the install never happens.
     *
     * @param onProgress whole percentages, delivered on the main thread and only
     *   when the number changes, so a caller may touch views directly.
     */
    suspend fun download(
        release: AvailableRelease,
        onProgress: (Int) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val target = File(context.cacheDir, "updates").apply { mkdirs() }
            .resolve(release.apkName)
        if (target.exists()) target.delete()

        val connection = open(release.apkUrl)
        try {
            val total = release.sizeBytes.takeIf { it > 0 }
                ?: connection.contentLengthLong.takeIf { it > 0 }
            var written = 0L
            connection.inputStream.use { source ->
                target.outputStream().use { sink ->
                    val buffer = ByteArray(64 * 1024)
                    var reported = -1
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                        written += read
                        if (total == null) continue
                        val percent = ((written * 100) / total).toInt().coerceIn(0, 100)
                        // Only on a change, and only on the main thread. This
                        // ran per 64 KiB chunk on the IO thread, so a caller
                        // that updated a TextView -- the obvious thing to do
                        // with a progress callback, and what Headway's own UI
                        // does -- touched a view from the wrong thread a couple
                        // of hundred times per download. Android logs that today
                        // and has said it will throw in future versions.
                        if (percent != reported) {
                            reported = percent
                            withContext(Dispatchers.Main) { onProgress(percent) }
                        }
                    }
                }
            }
            if (total != null && written != total) {
                target.delete()
                throw UpdateException("download stopped early: $written of $total bytes")
            }
        } catch (e: UpdateException) {
            throw e
        } catch (e: IOException) {
            target.delete()
            throw UpdateException("could not download ${release.apkName}: ${e.message}", e)
        } finally {
            connection.disconnect()
        }
        withContext(Dispatchers.Main) { onProgress(100) }
        target
    }

    /**
     * Hands the APK to the system installer.
     *
     * Returns once the session is committed; the user still has to confirm in
     * the system dialog, and the outcome arrives at [UpdateReceiver].
     */
    suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(context.packageName)
        }

        val sessionId = try {
            installer.createSession(params)
        } catch (e: IOException) {
            throw UpdateException("could not start an install session: ${e.message}", e)
        }

        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite(SESSION_ENTRY, 0, apk.length()).use { sink ->
                    apk.inputStream().use { it.copyTo(sink) }
                    session.fsync(sink)
                }
                // FLAG_MUTABLE is required: the installer fills in the session's
                // status and the confirmation intent before delivering this.
                val callback = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    Intent(UpdateReceiver.ACTION_INSTALL_STATUS)
                        .setPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(callback.intentSender)
            }
        } catch (e: IOException) {
            runCatching { installer.abandonSession(sessionId) }
            throw UpdateException("could not stage the update: ${e.message}", e)
        }
    }

    /**
     * Whether the user has allowed Headway to install packages.
     *
     * A normal user-grantable setting, not a privileged permission: the platform
     * requires it per-app for any sideloaded install, and it is the one thing
     * the user must turn on for this feature to work at all.
     */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Sends the user to the screen where that is granted. */
    fun installPermissionIntent(): Intent =
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(android.net.Uri.parse("package:${context.packageName}"))

    // --- plumbing ----------------------------------------------------------

    private fun get(url: String): String {
        val connection = open(url)
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            throw UpdateException("could not reach GitHub: ${e.message}", e)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val parsed = URL(url)
        // Refuse plaintext outright rather than relying on the network security
        // config: this fetches code that is about to be installed.
        if (!parsed.protocol.equals("https", ignoreCase = true)) {
            throw UpdateException("refusing a non-HTTPS update URL")
        }
        val connection = try {
            parsed.openConnection() as HttpsURLConnection
        } catch (e: IOException) {
            throw UpdateException("could not open $url: ${e.message}", e)
        }
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        // GitHub rejects requests with no User-Agent.
        connection.setRequestProperty("User-Agent", "Headway/${BuildConfig.VERSION_NAME}")
        connection.setRequestProperty("Accept", "application/vnd.github+json")

        val code = try {
            connection.responseCode
        } catch (e: IOException) {
            connection.disconnect()
            throw UpdateException("no response from ${parsed.host}: ${e.message}", e)
        }
        if (code == 403 || code == 429) {
            connection.disconnect()
            throw UpdateException(
                "GitHub is rate limiting this network (HTTP $code). Try again later."
            )
        }
        if (code !in 200..299) {
            connection.disconnect()
            throw UpdateException("GitHub answered HTTP $code for ${parsed.path}")
        }
        return connection
    }

    private companion object {
        const val SESSION_ENTRY = "headway.apk"
    }
}

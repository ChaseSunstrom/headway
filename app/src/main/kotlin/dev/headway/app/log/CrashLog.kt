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

package dev.headway.app.log

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Writes the session log to disk when the process is about to die.
 *
 * ## The gap this closes
 *
 * [SessionLog] is a ring buffer in memory, and the in-app export writes it out
 * on demand. Both of those are fine right up until the process dies, at which
 * point everything it holds goes with it -- so a crash mid-drive left the
 * driver with nothing to send and the report it produced was, in full,
 * "headway keeps crashing in the middle of drives for no reason". There was no
 * reason available to them: Android had the stack in its own crash log, which
 * needs adb, and CLAUDE.md is explicit that a drive has to be diagnosable from
 * the in-app export alone.
 *
 * ## What it does, and what it deliberately does not
 *
 * On an uncaught throwable it appends the stack to the session log, writes the
 * whole buffer to a file beside the ordinary exports, and then **hands the
 * throwable to the handler that was already installed**. It does not swallow
 * it, does not restart anything and does not show a dialog: the process still
 * dies exactly as it would have, Android still records it, and the only
 * difference is that the evidence survives.
 *
 * Everything here is wrapped, because a handler that throws replaces a
 * diagnosable crash with an undiagnosable one.
 */
object CrashLog {

    private const val TAG = "CrashLog"

    /** Marks a file as this rather than a hand-made export. */
    private const val PREFIX = "headway-crash-"

    /** Where the pending report is left for the next start to notice. */
    private const val PENDING = "last-crash.txt"

    /**
     * Installs the handler. Safe to call more than once.
     *
     * @param context application context; a shorter-lived one would be a leak
     *   held for the life of the process by the handler.
     */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(app, previous))
    }

    /**
     * Reports a crash left by the previous run, if there was one, and clears it.
     *
     * Called at start-up so the line lands near the top of the next export --
     * which is the export the driver sends after the drive that crashed.
     */
    fun reportPrevious(context: Context) {
        runCatching {
            val pending = File(context.applicationContext.filesDir, PENDING)
            if (!pending.isFile) return
            val text = pending.readText().trim()
            pending.delete()
            if (text.isEmpty()) return
            SessionLog.shared.warn(
                TAG,
                "the previous run ended in a crash, and this is it. The full log from that " +
                    "drive was written next to the other exports:\n$text",
            )
        }
    }

    private class Handler(
        private val context: Context,
        private val previous: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {

        override fun uncaughtException(thread: Thread, thrown: Throwable) {
            runCatching { record(thread, thrown) }
            // Always, and last. Without this the process would hang in a dead
            // state rather than dying, which is worse than the crash.
            previous?.uncaughtException(thread, thrown)
        }

        private fun record(thread: Thread, thrown: Throwable) {
            val stack = StringWriter().also { writer ->
                PrintWriter(writer).use { thrown.printStackTrace(it) }
            }.toString()
            val summary = "crashed on thread '${thread.name}': $stack"
            // Into the buffer first, so the file written below contains it in
            // its proper place among the lines that led up to it.
            runCatching { SessionLog.shared.error(TAG, summary) }
            runCatching {
                File(context.filesDir, PENDING).writeText(summary)
            }
            runCatching {
                val directory = File(
                    context.getExternalFilesDir(null) ?: context.filesDir,
                    SessionLog.DIRECTORY,
                )
                if (directory.exists() || directory.mkdirs()) {
                    val file = File(directory, PREFIX + System.currentTimeMillis() + ".txt")
                    file.bufferedWriter().use { out -> SessionLog.shared.writeTo(out) }
                }
            }
        }
    }
}

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
import android.os.Build
import android.util.Log
import dev.headway.app.BuildConfig
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Local time, not UTC: the user correlates this with "it dropped out just after
 * I pulled onto the motorway", and they remember that in their own clock. The
 * numeric offset is printed so a reader elsewhere can still place it absolutely.
 *
 * A `ThreadLocal` because `SimpleDateFormat` is not thread-safe and this is
 * called from whatever thread logged the line.
 */
private val TIMESTAMP_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSZ", Locale.US)
}

private val FILE_STAMP_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
}

private fun stamp(millis: Long): String = TIMESTAMP_FORMAT.get()!!.format(Date(millis))

/**
 * In-app session log with an export a user can email without touching adb.
 *
 * ## Why this exists at all
 *
 * The one validation step this project cannot perform is a real car. CLAUDE.md
 * makes the consequence explicit: "make the protocol layer log every frame
 * (behind a debug flag ...) so a single drive's log from the user is enough to
 * fix incompatibilities", and the Phase 6 acceptance criteria call for
 * "`adb`-free, in-app log export". Logcat is not that — it needs a computer, a
 * cable, developer options, and it has already rolled over by the time the user
 * gets home.
 *
 * ## Why redaction is done on the way in, not on the way out
 *
 * These files get emailed to strangers. Two secrets can reach them: the car's
 * Wi-Fi passphrase (which is a credential to a network in the user's driveway)
 * and TLS key material (which decrypts the whole session, including anything
 * the phone mirrored).
 *
 * Redaction happens in [log], before the line enters the buffer, so the secret
 * is never resident in the ring buffer, never in a heap dump, and cannot be
 * missed by an export path that forgot to call the filter. The cost is that a
 * redacted line cannot be un-redacted later, which is the correct trade: nothing
 * a passphrase would tell you about a failed handshake is worth mailing it out.
 *
 * [protect] extends this to values that carry no label — call it with the
 * passphrase as soon as the Bluetooth handshake yields one, and every later line
 * containing that literal string is scrubbed no matter who wrote it or how they
 * phrased it.
 *
 * ## Frame logging and key material
 *
 * [frame] is dropped unless [protocolDebug]; per-frame lines at 30 fps would
 * evict everything else from the buffer within seconds, and they are only useful
 * to someone diffing against `docs/protocol-notes.md`.
 *
 * [tlsKeyMaterial] is dropped unless [protocolDebug] **and** is the only path
 * that may write key material at all. The flag comes from
 * `BuildConfig.PROTOCOL_DEBUG`, which `app/build.gradle.kts` sets true only for
 * the debug build type, so a release build physically has no code path that
 * writes a session key to disk. It is a constructor parameter rather than a
 * direct `BuildConfig` read so that the instrumentation test can assert the
 * release behaviour from a debug build.
 */
class SessionLog(
    /**
     * Lines retained. At the default, a bad session's whole story fits and the
     * export stays small enough to attach to an email.
     */
    val capacity: Int = DEFAULT_CAPACITY,
    /** See the class KDoc. Defaults to the build's own flag. */
    val protocolDebug: Boolean = BuildConfig.PROTOCOL_DEBUG,
    /** Overridden by tests so timestamps are deterministic. */
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** Mirrors to logcat as well. Off in tests to keep their output readable. */
    private val alsoLogcat: Boolean = true,
) {

    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    enum class Level(val label: String) {
        DEBUG("D"),
        INFO("I"),
        WARN("W"),
        ERROR("E"),

        /** Per-frame protocol detail; only present when [protocolDebug]. */
        FRAME("F"),

        /** TLS secrets. Debug builds only; see the class KDoc. */
        KEYS("K"),
    }

    data class Entry(
        val timestampMillis: Long,
        val level: Level,
        val tag: String,
        val message: String,
    ) {
        fun format(): String = "${stamp(timestampMillis)} ${level.label}/$tag: $message"
    }

    private val buffer = ArrayDeque<Entry>(minOf(capacity, 256))

    /** Literal secrets to scrub. Guarded by [buffer]'s monitor, like everything else. */
    private val secrets = mutableListOf<String>()

    private var evicted = 0L

    /** How many lines the ring buffer has thrown away, for the export header. */
    val droppedCount: Long get() = synchronized(buffer) { evicted }

    val size: Int get() = synchronized(buffer) { buffer.size }

    /**
     * Registers a literal string to scrub from every subsequent line.
     *
     * Short values are rejected: scrubbing a two-character "secret" would mangle
     * unrelated text into unreadability while protecting nothing. Values already
     * registered are ignored, so calling this on every reconnect is free.
     */
    fun protect(secret: String?) {
        if (secret == null || secret.length < MIN_PROTECTED_LENGTH) return
        synchronized(buffer) {
            if (secrets.none { it == secret }) secrets += secret
        }
    }

    fun debug(tag: String, message: String): Unit = log(Level.DEBUG, tag, message)

    fun info(tag: String, message: String): Unit = log(Level.INFO, tag, message)

    fun warn(tag: String, message: String): Unit = log(Level.WARN, tag, message)

    fun error(tag: String, message: String, cause: Throwable? = null) {
        log(Level.ERROR, tag, if (cause == null) message else "$message: $cause")
    }

    /** Per-frame protocol detail. Silently discarded unless [protocolDebug]. */
    fun frame(tag: String, message: String) {
        if (!protocolDebug) return
        log(Level.FRAME, tag, message)
    }

    /**
     * TLS secrets, in the NSS key-log format a packet capture tool consumes.
     *
     * Discarded entirely unless [protocolDebug]. This is the single exemption
     * from redaction — the whole value of the line is the key — and it is why the
     * flag is checked here rather than only at the call site.
     */
    fun tlsKeyMaterial(line: String) {
        if (!protocolDebug) return
        append(Entry(clock(), Level.KEYS, TAG_TLS, line))
    }

    fun log(level: Level, tag: String, message: String) {
        val safe = redact(message, snapshotSecrets())
        append(Entry(clock(), level, tag, safe))
        if (alsoLogcat) {
            when (level) {
                Level.ERROR -> Log.e(tag, safe)
                Level.WARN -> Log.w(tag, safe)
                Level.INFO -> Log.i(tag, safe)
                else -> Log.d(tag, safe)
            }
        }
    }

    /** Everything currently retained, oldest first. */
    fun snapshot(): List<Entry> = synchronized(buffer) { buffer.toList() }

    fun clear() {
        synchronized(buffer) {
            buffer.clear()
            evicted = 0
        }
    }

    /**
     * Writes the log to a file the user can find without a computer.
     *
     * Target is the app's external files directory — `Android/data/dev.headway
     * .app/files/logs` — because that is browsable from the phone's own Files app
     * and attachable to an email, which `filesDir` is not. When no external
     * volume is mounted it falls back to `filesDir` and the caller still gets a
     * path to show.
     *
     * @return the file written.
     * @throws IOException if the storage is not writable.
     */
    @Throws(IOException::class)
    fun export(context: Context, at: Long = clock()): File {
        val directory = File(context.getExternalFilesDir(null) ?: context.filesDir, DIRECTORY)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("cannot create ${directory.absolutePath}")
        }
        val name = "headway-" + FILE_STAMP_FORMAT.get()!!.format(Date(at)) + ".txt"
        val file = File(directory, name)
        file.bufferedWriter().use { writer -> writeTo(writer, at) }
        return file
    }

    /**
     * Renders the export body.
     *
     * Separated from [export] so the format can be asserted without touching
     * storage, and so the settings screen can show the same text on screen.
     */
    fun writeTo(out: Appendable, at: Long = clock()) {
        val entries = snapshot()
        val dropped = droppedCount
        out.append("Headway session log\n")
        out.append("generated ${stamp(at)}\n")
        out.append("app ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ")
        out.append("build=${BuildConfig.BUILD_TYPE} protocolDebug=$protocolDebug\n")
        out.append("device ${Build.MANUFACTURER} ${Build.MODEL} ")
        out.append("android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        out.append("lines ${entries.size} of $capacity; $dropped older line(s) dropped\n")
        // Stated in the file so the person receiving it knows the omission is
        // deliberate and does not go asking the user to disable it.
        out.append(
            "Wi-Fi passphrases are redacted. TLS key material is written only by " +
                "debug builds.\n",
        )
        out.append("----\n")
        for (entry in entries) {
            out.append(entry.format())
            out.append('\n')
        }
    }

    /** Old exports, newest first, so the UI can offer them and prune them. */
    fun exports(context: Context): List<File> {
        val directory = File(context.getExternalFilesDir(null) ?: context.filesDir, DIRECTORY)
        val files = directory.listFiles() ?: return emptyList()
        return files.filter { it.isFile && it.name.endsWith(".txt") }
            .sortedByDescending { it.lastModified() }
    }

    private fun snapshotSecrets(): List<String> = synchronized(buffer) { secrets.toList() }

    private fun append(entry: Entry) {
        synchronized(buffer) {
            // Evicting before adding keeps the buffer from momentarily holding
            // capacity + 1 entries, which matters only because `capacity` is what
            // the export header claims and a reader will check it.
            while (buffer.size >= capacity) {
                buffer.pollFirst()
                evicted++
            }
            buffer.addLast(entry)
        }
    }

    companion object {
        private const val TAG_TLS = "tls"
        const val DEFAULT_CAPACITY: Int = 4_000
        const val DIRECTORY: String = "logs"

        /** Below this a "secret" is too generic to scrub without destroying the log. */
        const val MIN_PROTECTED_LENGTH: Int = 4

        const val REDACTED: String = "<redacted>"

        /**
         * The process-wide log.
         *
         * A singleton because the interesting failures span the service, the
         * activities and the accessibility service, and a log that only holds one
         * of the three is not diagnostic. There is no injection framework here to
         * do it more politely.
         */
        val shared: SessionLog by lazy { SessionLog() }

        /**
         * Keys whose value is a secret.
         *
         * Matches `key = value`, `key: value` and `key=value` with optional
         * quotes, which covers Kotlin string templates, `toString()` output and
         * hand-written messages alike. The value is taken up to the first comma,
         * closing bracket, quote or end of line, because those are what actually
         * terminate a value in every one of those forms.
         */
        private val SECRET_KEY_PATTERN = Regex(
            """(?i)\b(pass|passphrase|password|psk|pre[-_]?shared[-_]?key|wifi[-_]?key|""" +
                """secret|master[-_]?secret|session[-_]?key|key[-_]?material|""" +
                """private[-_]?key)\b\s*[:=]\s*"?([^",;)\]}\s]+)"?""",
        )

        /** An NSS key-log line: the label, the client random, then the secret. */
        private val KEY_LOG_PATTERN = Regex(
            """(?i)\b(CLIENT_RANDOM|CLIENT_HANDSHAKE_TRAFFIC_SECRET|""" +
                """SERVER_HANDSHAKE_TRAFFIC_SECRET|CLIENT_TRAFFIC_SECRET_0|""" +
                """SERVER_TRAFFIC_SECRET_0|EXPORTER_SECRET)\b.*""",
        )

        /** A PEM block, however it got into a message. */
        private val PEM_PATTERN = Regex(
            """-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----""",
        )

        /**
         * Strips secrets from one line.
         *
         * Exposed for the test and for anything writing to a file by another
         * route; [log] applies it automatically.
         *
         * The three patterns are complementary, not redundant: labelled key/value
         * pairs are what Kotlin code produces, key-log lines are what a TLS stack
         * produces, and a PEM block is what a user pastes in when reporting a
         * problem. [extraSecrets] covers the unlabelled case that no pattern can
         * see — a bare passphrase in a sentence.
         */
        fun redact(line: String, extraSecrets: List<String> = emptyList()): String {
            var result = line
            for (secret in extraSecrets) {
                if (secret.length >= MIN_PROTECTED_LENGTH) result = result.replace(secret, REDACTED)
            }
            result = PEM_PATTERN.replace(result, REDACTED)
            result = KEY_LOG_PATTERN.replace(result) { match -> "${match.groupValues[1]} $REDACTED" }
            result = SECRET_KEY_PATTERN.replace(result) { match ->
                // The already-redacted marker must survive untouched, or a second
                // pass would produce "<<redacted>>" and look like a bug.
                if (match.groupValues[2] == REDACTED) {
                    match.value
                } else {
                    "${match.groupValues[1]}=$REDACTED"
                }
            }
            return result
        }
    }
}

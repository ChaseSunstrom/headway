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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The log that gets emailed to strangers.
 *
 * The redaction tests are the point of this file. Everything else here would be
 * an inconvenience if it broke; a leaked passphrase or a leaked session key in a
 * file the user is being *encouraged* to share is a security failure caused by
 * the diagnostic feature itself.
 *
 * Run on a device because the export writes to real app storage, whose location
 * and writability are platform behaviour, and because [SessionLog] reads
 * `Build` and `BuildConfig` that only exist on one.
 */
@RunWith(AndroidJUnit4::class)
class SessionLogTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val written = mutableListOf<File>()

    @After
    fun tearDown() {
        written.forEach { it.delete() }
    }

    private fun log(
        capacity: Int = 100,
        protocolDebug: Boolean = false,
        clock: () -> Long = { FIXED_TIME },
    ) = SessionLog(
        capacity = capacity,
        protocolDebug = protocolDebug,
        clock = clock,
        // Logcat noise would swamp the run and proves nothing.
        alsoLogcat = false,
    )

    @Test
    fun theBufferIsBoundedAndDropsTheOldestLines() {
        val session = log(capacity = 10)

        repeat(1_000) { index -> session.info("t", "line $index") }

        assertEquals(10, session.size)
        assertEquals(990L, session.droppedCount)
        val messages = session.snapshot().map { it.message }
        assertEquals("line 990", messages.first())
        assertEquals("line 999", messages.last())
    }

    @Test
    fun aLabelledPassphraseNeverReachesTheBuffer() {
        val session = log()

        session.info("wifi", "joining AndroidAuto-4f2a passphrase=hunter2correcthorse")
        session.info("wifi", "creds{ssid=AA, psk: \"s3cr3t-psk-value\", port=5288}")

        val text = session.snapshot().joinToString("\n") { it.message }
        assertFalse(text, text.contains("hunter2correcthorse"))
        assertFalse(text, text.contains("s3cr3t-psk-value"))
        // Redaction must not eat the diagnostic value of the line.
        assertTrue(text, text.contains("AndroidAuto-4f2a"))
        assertTrue(text, text.contains("port=5288"))
        assertTrue(text, text.contains(SessionLog.REDACTED))
    }

    @Test
    fun aRegisteredSecretIsScrubbedEvenWithNothingToLabelIt() {
        val session = log()
        // The realistic case: the Bluetooth handshake yields the passphrase, and
        // some later line quotes it in prose no pattern would recognise.
        session.protect("Tr0ub4dor-and-3")

        session.info("wifi", "association failed, tried Tr0ub4dor-and-3 three times")

        val text = session.snapshot().single().message
        assertFalse(text, text.contains("Tr0ub4dor-and-3"))
        assertTrue(text, text.contains(SessionLog.REDACTED))
    }

    @Test
    fun aSecretRegisteredAfterALineWasLoggedCannotBeUnwritten() {
        // Stated as a test because it is a real limitation of redacting on the
        // way in: protect() must be called as soon as the value exists.
        val session = log()
        session.info("wifi", "raw value verylongsecret before protect")
        session.protect("verylongsecret")

        assertTrue(session.snapshot().single().message.contains("verylongsecret"))
    }

    @Test
    fun shortValuesAreNotAcceptedAsSecrets() {
        val session = log()
        session.protect("ab")

        session.info("t", "a bunch of ordinary text about a cab and a crab")

        // Scrubbing a two-character string would destroy the log to protect
        // nothing, so it is refused outright.
        assertFalse(session.snapshot().single().message.contains(SessionLog.REDACTED))
    }

    @Test
    fun frameDetailIsDroppedUnlessProtocolDebugIsOn() {
        val quiet = log(protocolDebug = false)
        quiet.frame("aap", "ch=3 flags=0x0b len=1024")
        assertEquals(0, quiet.size)

        val loud = log(protocolDebug = true)
        loud.frame("aap", "ch=3 flags=0x0b len=1024")
        assertEquals(1, loud.size)
        assertEquals(SessionLog.Level.FRAME, loud.snapshot().single().level)
    }

    @Test
    fun aReleaseBuildHasNoPathThatWritesTlsKeyMaterial() {
        val release = log(protocolDebug = false)

        release.tlsKeyMaterial("CLIENT_RANDOM abcdef0123456789 0011223344556677")

        assertEquals("key material must not survive in a release build", 0, release.size)
    }

    @Test
    fun keyMaterialLoggedThroughTheOrdinaryPathIsRedactedEvenInDebug() {
        val debug = log(protocolDebug = true)

        // Not the tlsKeyMaterial() path: something else logging a key log line or
        // a pasted PEM block. Only the dedicated method is exempt from redaction.
        debug.info("tls", "CLIENT_RANDOM abcdef0123456789 00112233445566778899aabb")
        debug.info(
            "tls",
            "material -----BEGIN RSA PRIVATE KEY-----\nMIIBOgIBAAJB\n-----END RSA PRIVATE KEY----- end",
        )

        val text = debug.snapshot().joinToString("\n") { it.message }
        assertFalse(text, text.contains("00112233445566778899aabb"))
        assertFalse(text, text.contains("MIIBOgIBAAJB"))
        assertTrue(text, text.contains("CLIENT_RANDOM"))
        assertTrue("surrounding text should survive: $text", text.contains("end"))
    }

    @Test
    fun redactingTwiceDoesNotNestTheMarker() {
        val once = SessionLog.redact("psk=topsecretvalue")
        val twice = SessionLog.redact(once)
        assertEquals(once, twice)
        assertFalse(twice.contains("<<"))
    }

    @Test
    fun theExportIsAReadableFileWithAHeaderAndNoSecrets() {
        val session = log(capacity = 5)
        session.protect("passphrase-from-the-car")
        session.info("link", "connecting with passphrase=passphrase-from-the-car")
        repeat(10) { session.info("link", "attempt $it") }

        val file = session.export(context).also { written += it }

        assertTrue("export not created: ${file.absolutePath}", file.exists())
        assertTrue("export is empty", file.length() > 0)
        val text = file.readText()

        assertFalse("the passphrase reached the exported file", text.contains("passphrase-from-the-car"))
        assertTrue(text, text.startsWith("Headway session log"))
        assertTrue("no device line: $text", text.contains("android "))
        assertTrue("the drop count must be stated: $text", text.contains("older line(s) dropped"))
        assertTrue(
            "the file must say what was withheld: $text",
            text.contains("Wi-Fi passphrases are redacted"),
        )
        // Bounded on the way out as well as in memory.
        assertEquals(5, text.lines().count { it.contains(" I/link: ") })
        assertTrue(text, text.contains("attempt 9"))
    }

    @Test
    fun everyExportIsADistinctFileInsideTheAppsOwnStorage() {
        val session = log()
        session.info("t", "one")

        val first = session.export(context, at = FIXED_TIME).also { written += it }
        val second = session.export(context, at = FIXED_TIME + 60_000L).also { written += it }

        // A second export must not overwrite the first, or a user who exports
        // twice loses the log they meant to send.
        assertFalse("both exports share the name ${first.name}", first.name == second.name)

        val listed = session.exports(context)
        assertTrue("$first not listed in $listed", listed.contains(first))
        assertTrue("$second not listed in $listed", listed.contains(second))

        // App-private storage, not the shared Downloads collection: no storage
        // permission, nothing left behind on uninstall, and still reachable from
        // the phone's own Files app, which is the whole point of an adb-free
        // export.
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        assertTrue(
            "export escaped the app's storage: ${second.absolutePath}",
            second.absolutePath.startsWith(root.absolutePath),
        )
    }

    @Test
    fun clearResetsBothTheBufferAndTheDropCount() {
        val session = log(capacity = 2)
        repeat(10) { session.info("t", "$it") }
        assertTrue(session.droppedCount > 0)

        session.clear()

        assertEquals(0, session.size)
        assertEquals(0L, session.droppedCount)
    }

    @Test
    fun entriesCarryTheirLevelAndTagInAGreppableForm() {
        val session = log()
        session.warn("link", "car network lost")

        val formatted = session.snapshot().single().format()

        assertTrue(formatted, formatted.contains("W/link: car network lost"))
        // Sortable timestamp first, so a log can be merged with another by hand.
        assertTrue(formatted, formatted.startsWith("20"))
    }

    private companion object {
        /** Arbitrary but fixed, so nothing here depends on when it runs. */
        const val FIXED_TIME = 1_700_000_000_000L
    }
}

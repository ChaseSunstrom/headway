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

package dev.headway.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.headway.protocol.framing.ChannelId
import dev.headway.protocol.framing.FrameHeader
import dev.headway.protocol.framing.FrameType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the instrumentation harness itself works, and that the protocol core
 * behaves identically on Android to how it behaves on the JVM.
 *
 * The second half matters more than it looks. Every acceptance test in this
 * repo runs the protocol on a desktop JVM; this is the only place that runs it
 * on the platform it ships to. Dalvik/ART differences in byte and integer
 * handling are exactly the kind of thing that would pass every JVM test and then
 * misframe on a phone.
 */
@RunWith(AndroidJUnit4::class)
class HarnessSmokeTest {

    @Test
    fun runsOnARealAndroidDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("dev.headway.app", context.packageName)
        assertTrue("expected API 33+", android.os.Build.VERSION.SDK_INT >= 33)
    }

    @Test
    fun noGooglePlayServicesOnThisDevice() {
        // The project forbids GMS. The image CI uses (aosp_atd) ships none, and
        // asserting it here means a switch to a google_apis image is caught
        // rather than quietly widening what the app could depend on.
        val packages = InstrumentationRegistry.getInstrumentation().targetContext
            .packageManager.getInstalledPackages(0).map { it.packageName }
        assertFalse(
            "a Play Services package is present: ${packages.filter { it.startsWith("com.google.android.gms") }}",
            packages.any { it.startsWith("com.google.android.gms") },
        )
    }

    @Test
    fun frameCodecProducesTheSameBytesOnAndroidAsOnTheJvm() {
        // The same fixture asserted in core-protocol's FrameHeaderTest.
        val header = FrameHeader(
            channelId = ChannelId.MEDIA_SINK_VIDEO.id,
            frameType = FrameType.FIRST,
            control = false,
            encrypted = true,
            payloadLength = 0x4000,
            totalMessageLength = 0x5000L,
        )
        assertEquals(
            "03 09 40 00 00 00 50 00",
            header.encode().joinToString(" ") { "%02x".format(it) },
        )
        assertEquals(header, FrameHeader.decode(header.encode()))
    }

    @Test
    fun bulkFramesDoNotGainAPhantomSizeFieldOnAndroid() {
        // The masked-equality trap from FrameHeaderTest, re-checked on ART.
        assertTrue(FrameHeader.hasExtendedSize(FrameType.FIRST.bits))
        assertFalse(FrameHeader.hasExtendedSize(FrameType.BULK.bits))
    }
}

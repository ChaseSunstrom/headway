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

package dev.headway.app.audio

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The silence test the media pump decides audio focus with.
 *
 * Worth a test of its own because the alternative it replaced --
 * `AudioManager.isMusicActive()` -- is what made music cut out: it goes false
 * across a gapless track change, so focus was released and the car switched its
 * source away and back. Getting *this* wrong in the other direction is just as
 * audible, and it is two lines of bit arithmetic over a signed byte array,
 * which is exactly the shape of thing that is wrong by a sign.
 */
class Pcm16SilenceTest {

    private fun pcm(vararg samples: Int): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            bytes[index * 2] = (sample and 0xff).toByte()
            bytes[index * 2 + 1] = ((sample shr 8) and 0xff).toByte()
        }
        return bytes
    }

    @Test
    fun `all zero samples are silence`() {
        val buffer = pcm(0, 0, 0, 0)
        assertTrue(isPcm16Silence(buffer, buffer.size, 64), "digital silence")
    }

    @Test
    fun `dither below the level is still silence`() {
        val buffer = pcm(0, 1, -1, 7, -7, 64, -64)
        assertTrue(isPcm16Silence(buffer, buffer.size, 64), "noise at or under the level")
    }

    @Test
    fun `one loud positive sample is not silence`() {
        val buffer = pcm(0, 0, 5000, 0)
        assertFalse(isPcm16Silence(buffer, buffer.size, 64), "a positive sample above the level")
    }

    @Test
    fun `one loud negative sample is not silence`() {
        // The sign is the whole point: reading the high byte unsigned makes
        // every negative sample look enormous, and reading the pair unsigned
        // makes quiet negative dither look like full-scale audio.
        val buffer = pcm(0, 0, -5000, 0)
        assertFalse(isPcm16Silence(buffer, buffer.size, 64), "a negative sample below the level")
    }

    @Test
    fun `full scale in both directions is not silence`() {
        assertFalse(isPcm16Silence(pcm(32767), 2, 64), "positive full scale")
        assertFalse(isPcm16Silence(pcm(-32768), 2, 64), "negative full scale")
    }

    @Test
    fun `only the stated length is read`() {
        // The pump passes a fixed buffer and the count actually captured. Music
        // left in the tail from the previous read must not count as playing.
        val buffer = pcm(0, 0, 30000, 30000)
        assertTrue(isPcm16Silence(buffer, 4, 64), "the loud half is past the length")
        assertFalse(isPcm16Silence(buffer, buffer.size, 64), "and is found when it is not")
    }

    @Test
    fun `a length beyond the buffer does not overrun`() {
        val buffer = pcm(0, 0)
        assertTrue(isPcm16Silence(buffer, buffer.size * 4, 64), "clamped to the array")
    }

    @Test
    fun `a trailing odd byte is ignored rather than guessed at`() {
        val buffer = pcm(0, 0) + byteArrayOf(0x7f)
        assertTrue(isPcm16Silence(buffer, buffer.size, 64), "half a sample is not a sample")
    }
}

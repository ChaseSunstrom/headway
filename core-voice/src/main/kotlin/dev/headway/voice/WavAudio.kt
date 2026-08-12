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

import java.io.InputStream

/** 16-bit PCM audio with its sample rate. */
class PcmAudio(val sampleRateHz: Int, val channels: Int, val samples: ByteArray) {
    val frameCount: Int get() = samples.size / (2 * channels)
    val durationSeconds: Double get() = frameCount.toDouble() / sampleRateHz
}

/**
 * A minimal RIFF/WAVE reader for 16-bit PCM.
 *
 * The JDK ships `javax.sound.sampled`, but it is absent on Android, and the
 * emulator's fake-microphone feed and the phone both need to read the same
 * fixture files. Twenty lines of RIFF parsing is a smaller cost than a
 * platform-conditional audio path.
 *
 * Deliberately strict: an unexpected format is rejected rather than
 * reinterpreted, because silently treating a 24-bit or stereo file as 16-bit
 * mono produces audio that sounds like noise and a transcript that looks like a
 * recogniser bug.
 */
object WavAudio {

    fun read(stream: InputStream): PcmAudio {
        val bytes = stream.readBytes()
        require(bytes.size >= 44) { "not a WAV file: only ${bytes.size} bytes" }
        require(ascii(bytes, 0, 4) == "RIFF") { "not a WAV file: missing RIFF tag" }
        require(ascii(bytes, 8, 4) == "WAVE") { "not a WAV file: missing WAVE tag" }

        var sampleRate = -1
        var channels = -1
        var bitsPerSample = -1
        var data: ByteArray? = null

        // Walk the chunk list rather than assuming the canonical 44-byte layout:
        // real encoders interleave LIST/fact chunks and the data chunk moves.
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val id = ascii(bytes, offset, 4)
            val size = le32(bytes, offset + 4)
            val body = offset + 8
            require(size >= 0 && body + size <= bytes.size) {
                "WAV chunk '$id' claims $size bytes but only ${bytes.size - body} remain"
            }
            when (id) {
                "fmt " -> {
                    val format = le16(bytes, body)
                    require(format == 1) { "only uncompressed PCM is supported, got format $format" }
                    channels = le16(bytes, body + 2)
                    sampleRate = le32(bytes, body + 4)
                    bitsPerSample = le16(bytes, body + 14)
                }
                "data" -> data = bytes.copyOfRange(body, body + size)
            }
            // Chunks are word-aligned; an odd size carries a pad byte.
            offset = body + size + (size and 1)
        }

        require(sampleRate > 0 && channels > 0) { "WAV file has no fmt chunk" }
        require(bitsPerSample == 16) { "only 16-bit PCM is supported, got $bitsPerSample-bit" }
        val payload = requireNotNull(data) { "WAV file has no data chunk" }
        return PcmAudio(sampleRate, channels, payload)
    }

    /** Serialises [audio] as a canonical 44-byte-header WAV. */
    fun write(audio: PcmAudio): ByteArray {
        val byteRate = audio.sampleRateHz * audio.channels * 2
        val out = ByteArray(44 + audio.samples.size)
        ascii(out, 0, "RIFF")
        le32(out, 4, 36 + audio.samples.size)
        ascii(out, 8, "WAVE")
        ascii(out, 12, "fmt ")
        le32(out, 16, 16)
        le16(out, 20, 1)
        le16(out, 22, audio.channels)
        le32(out, 24, audio.sampleRateHz)
        le32(out, 28, byteRate)
        le16(out, 32, audio.channels * 2)
        le16(out, 34, 16)
        ascii(out, 36, "data")
        le32(out, 40, audio.samples.size)
        audio.samples.copyInto(out, 44)
        return out
    }

    private fun ascii(b: ByteArray, off: Int, len: Int) = String(b, off, len, Charsets.US_ASCII)
    private fun ascii(b: ByteArray, off: Int, s: String) =
        s.toByteArray(Charsets.US_ASCII).copyInto(b, off)

    private fun le16(b: ByteArray, off: Int) =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, off: Int) =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun le16(b: ByteArray, off: Int, v: Int) {
        b[off] = v.toByte(); b[off + 1] = (v ushr 8).toByte()
    }

    private fun le32(b: ByteArray, off: Int, v: Int) {
        b[off] = v.toByte(); b[off + 1] = (v ushr 8).toByte()
        b[off + 2] = (v ushr 16).toByte(); b[off + 3] = (v ushr 24).toByte()
    }
}

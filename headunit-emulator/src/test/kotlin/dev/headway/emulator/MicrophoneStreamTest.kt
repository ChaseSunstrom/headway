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

package dev.headway.emulator

import aap_protobuf.service.media.shared.message.MediaCodecTypeOuterClass.MediaCodecType
import dev.headway.protocol.channel.MicrophoneChannel
import dev.headway.protocol.channel.MicrophoneFormat
import dev.headway.protocol.channel.PcmChunk
import dev.headway.protocol.framing.ChannelId
import dev.headway.protocol.io.FramedConnection
import dev.headway.protocol.session.AapSession
import dev.headway.protocol.session.PhoneIdentity
import dev.headway.transport.LoopbackTransport
import dev.headway.transport.tls.AapTls
import dev.headway.transport.tls.TlsSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * **Phase 5, protocol half — the car microphone.**
 *
 * CLAUDE.md:
 *
 * > **Phase 5 — Voice.** *Accepted when:* WAV-injected "open calculator"
 * > through the emulator's fake mic launches the calculator on the phone with no
 * > network access, end-to-end under 2 s after end of speech.
 *
 * The recogniser and the command engine are somebody else's half. This test owns
 * the sentence's first clause — *WAV-injected ... through the emulator's fake
 * mic* — and answers exactly one question: **do the samples that go into the
 * WAV come out of [MicrophoneChannel] unaltered?** Everything downstream of that
 * is a speech problem; everything upstream of a wrong answer here is
 * unrecoverable, because a recogniser fed byte-swapped or misaligned PCM fails
 * in ways no amount of model tuning fixes.
 *
 * So the injected audio is a **sine sweep** rather than speech. A sweep touches
 * every frequency in the band, is generated rather than stored, and — unlike
 * speech — any corruption of it is visible as an exact sample mismatch instead
 * of "the recogniser did worse". The comparison is bit-for-bit over all
 * [SWEEP_FRAMES] samples.
 *
 * The WAV is written to a real file by [wavBytes], which shares no code with
 * [WavPcm]: the writer builds the RIFF header by hand from the format
 * definition, the reader parses it back. A bug in either is a test failure, not
 * a matching pair of bugs.
 *
 * ## Evidence tier
 *
 * **Tier A for sample integrity, Tier D for the microphone itself**
 * (`docs/completion-plan.md`).
 *
 * Tier A — executed on real bytes: a real WAV file on disk, parsed by the real
 * reader, streamed through the real channel implementation over the real framing
 * and TLS stack, compared sample for sample. Chunk boundaries, chunk count and
 * presentation timestamps are asserted against arithmetic done from the
 * advertised format rather than against numbers copied from the implementation.
 *
 * Tier D — not provable here, and not claimed anywhere below:
 *
 * - **that a real car microphone produces what this injects.** There is no car
 *   (BLOCKERS.md B-001). A vehicle's cabin mic delivers noisy, echoey, AGC'd
 *   audio at whatever rate the unit advertises; a clean sweep proves the channel
 *   does not corrupt audio, not that a recogniser will cope with a cabin;
 * - **the criterion's "under 2 s".** Both ends run in one process over an
 *   in-memory pipe, so any timing measured here is this machine's scheduler.
 *   [MicrophoneConfig.paceRealTime] can make the stream arrive at mic speed, and
 *   is left off for exactly that reason: it would add wall-clock cost without
 *   adding evidence;
 * - **that a real head unit's microphone lifecycle matches the emulator's.**
 *   Per ADR 0002 the emulator shares `core-protocol` with the phone, so a green
 *   run is self-consistency. The one place that is partly mitigated is the reply
 *   ids, where the emulator can be switched to aasdk's behaviour and the phone
 *   still has to cope.
 */
class MicrophoneStreamTest {

    // --- the acceptance criterion's first clause ----------------------------

    @Test
    fun `a WAV injected at the head unit arrives at the phone sample for sample`(
        @TempDir dir: File,
    ) {
        val sweep = sineSweep(SWEEP_FRAMES)
        val wav = File(dir, "sweep.wav")
        wav.writeBytes(wavBytes(sweep, SAMPLE_RATE_HZ, channels = 1))

        val run = micSession(wav) { mic, chunkCount ->
            mic.open()
            mic.startCapture()
            val chunks = mic.pcm().take(chunkCount).toList()
            mic.stopCapture()
            mic.awaitCaptureResponse()
            chunks
        }

        // Chunking: 20 ms at 16 kHz mono is 320 frames, and the recording
        // divides evenly into them.
        assertEquals(EXPECTED_CHUNKS, run.chunks.size, "wrong number of DATA messages")
        assertTrue(
            run.chunks.all { it.samples.size == CHUNK_FRAMES },
            "every chunk of an evenly divisible recording must be a full ${CHUNK_FRAMES}-frame chunk",
        )

        // Content and order: the concatenation is the file, byte for byte.
        assertArrayEquals(sweep, run.received(), "the received audio is not the audio that was injected")

        // Timestamps: strictly increasing, on the 20 ms grid the frames were cut on.
        val timestamps = run.chunks.map { it.timestampMicros }
        assertTrue(
            timestamps.zipWithNext().all { (earlier, later) -> later > earlier },
            "presentation timestamps must strictly increase",
        )
        assertEquals(
            (0 until EXPECTED_CHUNKS).map { it.toLong() * CHUNK_MICROS },
            timestamps,
            "each chunk's timestamp must be the position of its first frame",
        )

        // The channel's own bookkeeping agrees with what arrived.
        assertEquals(EXPECTED_CHUNKS.toLong(), run.channel.chunksReceived)
        assertEquals(SWEEP_FRAMES.toLong(), run.channel.framesReceived)

        // Every DATA was acknowledged, with the session id the head unit gave —
        // which for an openauto-shaped unit is -1.
        assertEquals(EXPECTED_CHUNKS.toLong(), run.channel.acksSent)
        assertEquals(EXPECTED_CHUNKS.toLong(), run.microphone.acksReceived)
        assertTrue(
            run.microphone.acknowledgedSessionIds.all { it == OPENAUTO_SESSION_ID },
            "every Ack must echo the head unit's session id",
        )
    }

    @Test
    fun `the lifecycle the head unit sees is setup, open, audio, close`(@TempDir dir: File) {
        val sweep = sineSweep(SWEEP_FRAMES)
        val wav = File(dir, "sweep.wav")
        wav.writeBytes(wavBytes(sweep, SAMPLE_RATE_HZ, channels = 1))

        val run = micSession(wav) { mic, chunkCount ->
            val setup = mic.open()
            assertTrue(setup.ready, "the head unit answered setup with ${setup.status}")
            assertEquals(1, setup.maxUnacked, "openauto advertises a window of 1")
            assertEquals(listOf(0), setup.configurationIndices)

            val opened = mic.startCapture()
            assertTrue(opened.ok, "the head unit refused to open its microphone: ${opened.status}")
            assertTrue(opened.opening)
            assertEquals(OPENAUTO_SESSION_ID, opened.sessionId)

            val chunks = mic.pcm().take(chunkCount).toList()
            mic.stopCapture()
            val closed = mic.awaitCaptureResponse()
            assertTrue(closed.ok)
            assertFalse(closed.opening, "the reply to a close request must not read as an open")
            chunks
        }

        assertEquals(MediaCodecType.MEDIA_CODEC_AUDIO_PCM, run.microphone.requestedCodec)
        assertTrue(run.microphone.closed, "the head unit must see the phone's close request")
        assertTrue(
            run.microphone.unhandledMessageIds.isEmpty(),
            "the head unit saw an unexpected message id: ${run.microphone.unhandledMessageIds}",
        )

        // The two requests, with the fields decompiled Gearhead sends.
        val requests = run.microphone.microphoneRequests
        assertEquals(2, requests.size)
        assertTrue(requests[0].open)
        assertFalse(requests[0].ancEnabled)
        assertFalse(requests[0].ecEnabled)
        assertEquals(2, requests[0].maxUnacked)
        assertFalse(requests[1].open)

        assertFalse(run.channel.capturing, "the phone must clear its capture state on the close reply")
    }

    @Test
    fun `a recording that does not divide evenly ends in a short chunk`(@TempDir dir: File) {
        // A real recording rarely ends on a chunk boundary. Truncating the tail
        // would silently lose the end of an utterance — exactly where a wake
        // phrase's last syllable lives.
        val frames = CHUNK_FRAMES * 3 + 57
        val sweep = sineSweep(frames)
        val wav = File(dir, "ragged.wav")
        wav.writeBytes(wavBytes(sweep, SAMPLE_RATE_HZ, channels = 1))

        val run = micSession(wav) { mic, chunkCount ->
            mic.open()
            mic.startCapture()
            val chunks = mic.pcm().take(chunkCount).toList()
            mic.stopCapture()
            mic.awaitCaptureResponse()
            chunks
        }

        assertEquals(4, run.chunks.size)
        assertEquals(listOf(CHUNK_FRAMES, CHUNK_FRAMES, CHUNK_FRAMES, 57), run.chunks.map { it.samples.size })
        assertArrayEquals(sweep, run.received())
    }

    @Test
    fun `a head unit that replies on aasdk's message ids is still understood`(@TempDir dir: File) {
        // openauto is built on aasdk, which labels the setup response with
        // MEDIA_MESSAGE_SETUP (0x8000) and the MicrophoneResponse with
        // MEDIA_MESSAGE_MICROPHONE_REQUEST (0x8005) — the ids the *phone* sends
        // on — rather than 0x8003/0x8006
        // (aasdk/src/Channel/MediaSource/MediaSourceService.cpp L67, L105-L106).
        // A phone that insisted on the observed ids would hang against every
        // Raspberry Pi head unit in the field.
        val sweep = sineSweep(CHUNK_FRAMES * 4)
        val wav = File(dir, "aasdk-ids.wav")
        wav.writeBytes(wavBytes(sweep, SAMPLE_RATE_HZ, channels = 1))

        val run = micSession(wav, useAasdkReplyIds = true) { mic, chunkCount ->
            assertTrue(mic.open().ready)
            assertTrue(mic.startCapture().ok)
            val chunks = mic.pcm().take(chunkCount).toList()
            mic.stopCapture()
            assertTrue(mic.awaitCaptureResponse().ok)
            chunks
        }

        assertArrayEquals(sweep, run.received())
        assertTrue(run.microphone.closed)
        assertFalse(run.channel.capturing)
    }

    // --- the WAV reader -----------------------------------------------------

    @Test
    fun `a written WAV round-trips through the reader`(@TempDir dir: File) {
        val sweep = sineSweep(CHUNK_FRAMES * 5)
        val wav = File(dir, "round-trip.wav")
        wav.writeBytes(wavBytes(sweep, SAMPLE_RATE_HZ, channels = 1))

        val decoded = WavPcm.read(wav)
        assertEquals(MicrophoneFormat.CAR_MICROPHONE, decoded.format)
        assertArrayEquals(sweep, decoded.samples)
        assertEquals(sweep.size, decoded.frameCount)
        assertEquals(100_000L, decoded.durationMicros, "5 chunks of 20 ms is 100 ms")
    }

    @Test
    fun `a chunk before the data chunk is walked over, not assumed away`() {
        // The reason the reader parses chunks instead of skipping 44 bytes: a
        // LIST INFO chunk before the audio is common, and its odd length is
        // followed by a pad byte that is not counted in the size field. A reader
        // that mishandles either lands mid-chunk and decodes the tail as audio.
        val samples = shortArrayOf(1, -1, 2, -2)
        val list = "INFOISFT".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x41) // 9 bytes, odd
        val bytes = wavBytes(samples, SAMPLE_RATE_HZ, channels = 1, extraChunks = listOf("LIST" to list))

        val decoded = WavPcm.parse(bytes)
        assertArrayEquals(samples, decoded.samples)
    }

    @Test
    fun `a malformed header is an error, never audio`() {
        val good = wavBytes(shortArrayOf(1, 2, 3, 4), SAMPLE_RATE_HZ, channels = 1)

        // Each case corrupts exactly one thing about an otherwise valid file.
        val cases = listOf(
            "a file too short to hold a RIFF header" to good.copyOf(8),
            "a non-RIFF magic" to good.copyOf().also { "RIFX".toByteArray().copyInto(it, 0) },
            "a form type that is not WAVE" to good.copyOf().also { "AVI ".toByteArray().copyInto(it, 8) },
            "a RIFF size larger than the file" to good.copyOf().also { le32(it, 4, good.size.toLong()) },
            "a fmt chunk claiming 8 bits per sample" to good.copyOf().also { le16(it, FMT_BODY + 14, 8) },
            "a fmt chunk claiming IEEE float, not PCM" to good.copyOf().also { le16(it, FMT_BODY, 3) },
            "a fmt chunk whose block align contradicts its channels" to
                good.copyOf().also { le16(it, FMT_BODY + 12, 4) },
            "a fmt chunk claiming zero channels" to good.copyOf().also { le16(it, FMT_BODY + 2, 0) },
            "a data chunk claiming more bytes than the form holds" to
                good.copyOf().also { le32(it, dataSizeOffset(good), 4096) },
            "a data chunk holding half a frame" to good.copyOf(good.size - 1)
                .also {
                    le32(it, 4, (it.size - 8).toLong())
                    le32(it, dataSizeOffset(good), 7)
                },
            "a file with no chunks at all" to good.copyOf(12).also { le32(it, 4, 4) },
        )

        for ((description, bytes) in cases) {
            val error = assertThrows(WavFormatException::class.java, { WavPcm.parse(bytes) }, description)
            assertTrue(
                error.message!!.isNotBlank(),
                "$description must fail with an explanation, not an empty message",
            )
        }
    }

    @Test
    fun `a WAV whose format is not the advertised one is refused rather than resampled`(
        @TempDir dir: File,
    ) {
        // 44.1 kHz down a 16 kHz channel would be bit-perfect and about three
        // times too slow — audio that passes every integrity check and is
        // useless to a recogniser.
        val wav = File(dir, "cd-rate.wav")
        wav.writeBytes(wavBytes(sineSweep(1024), sampleRate = 44_100, channels = 1))

        LoopbackTransport.pair().use { pair ->
            val microphone = EmulatedMicrophone(connection = FramedConnection(pair.headUnit))
            val error = assertThrows(WavFormatException::class.java) { microphone.loadWav(wav) }
            assertTrue(error.message!!.contains("44100"), error.message)
        }
    }

    // --- harness ------------------------------------------------------------

    private class MicRun(
        val microphone: EmulatedMicrophone,
        val channel: MicrophoneChannel,
        val chunks: List<PcmChunk>,
    ) {
        /** Every sample received, concatenated in arrival order. */
        fun received(): ShortArray {
            val out = ShortArray(chunks.sumOf { it.samples.size })
            var offset = 0
            for (chunk in chunks) {
                chunk.samples.copyInto(out, offset)
                offset += chunk.samples.size
            }
            return out
        }
    }

    /**
     * Brings a session up the way Phase 1 does — real version handshake, real
     * TLS, real service discovery over the fake transport — loads [wav] into an
     * [EmulatedMicrophone] on the channel the head unit advertised, and runs
     * [phone] against it.
     *
     * Going through the whole bring-up is what makes the channel id and the PCM
     * format come from the advertisement rather than from a constant. On this
     * channel that matters more than on most: the format the phone decodes with
     * is the head unit's to declare.
     *
     * [phone] is handed the number of `DATA` messages the recording will produce
     * so that it can stop collecting; a real phone stops when the user stops
     * talking, which is not a thing a test can wait for.
     */
    private fun micSession(
        wav: File,
        useAasdkReplyIds: Boolean = false,
        timeoutMillis: Long = 120_000,
        phone: suspend (MicrophoneChannel, Int) -> List<PcmChunk>,
    ): MicRun = runBlocking {
        LoopbackTransport.pair().use { pair ->
            val phoneConnection = FramedConnection(pair.phone)
            val headUnitConnection = FramedConnection(pair.headUnit)

            val headUnit = EmulatedHeadUnit(
                connection = headUnitConnection,
                tls = TlsSession(AapTls.headUnitEngine()),
            )
            val session = AapSession(
                connection = phoneConnection,
                tls = TlsSession(AapTls.phoneEngine()),
                identity = PhoneIdentity(deviceName = "Headway Test"),
            )

            val wanted = listOf(ChannelId.MEDIA_SOURCE_MICROPHONE.id)
            val phoneBringUp = async(Dispatchers.IO) { session.connect { wanted } }
            val headUnitBringUp = async(Dispatchers.IO) { headUnit.run(channelOpens = wanted.size) }
            val profile = withTimeout(60_000) { phoneBringUp.await() }
            withTimeout(60_000) { headUnitBringUp.await() }

            val service = profile.services.first {
                it.hasMediaSourceService() &&
                    it.mediaSourceService.availableType == MediaCodecType.MEDIA_CODEC_AUDIO_PCM
            }
            val format = MicrophoneFormat.fromAdvertisement(service.mediaSourceService.audioConfig)
            assertEquals(MicrophoneFormat.CAR_MICROPHONE, format, "advertised microphone format")

            val microphone = EmulatedMicrophone(
                connection = headUnitConnection,
                channelId = service.id,
                config = MicrophoneConfig(
                    format = format,
                    sessionId = OPENAUTO_SESSION_ID,
                    useAasdkReplyIds = useAasdkReplyIds,
                ),
            )
            microphone.loadWav(wav)
            val channel = MicrophoneChannel(phoneConnection, channelId = service.id, format = format)

            val headUnitSide = async(Dispatchers.IO) { microphone.run() }
            val phoneSide = async(Dispatchers.IO) { phone(channel, microphone.expectedChunkCount) }

            val chunks = withTimeout(timeoutMillis) { phoneSide.await() }
            withTimeout(timeoutMillis) { headUnitSide.await() }
            MicRun(microphone, channel, chunks)
        }
    }

    // --- fixtures -----------------------------------------------------------

    private companion object {
        const val SAMPLE_RATE_HZ = 16_000

        /** 20 ms at 16 kHz — the emulator's chunk length. */
        const val CHUNK_FRAMES = 320
        const val CHUNK_MICROS = 20_000L

        /** Two seconds of audio: 100 chunks, and long enough for a short command. */
        const val SWEEP_FRAMES = SAMPLE_RATE_HZ * 2
        const val EXPECTED_CHUNKS = SWEEP_FRAMES / CHUNK_FRAMES

        /** openauto's never-assigned session id (`MediaSourceService.cpp` L28). */
        const val OPENAUTO_SESSION_ID = -1

        /** Offset of the `fmt ` chunk body in a file written by [wavBytes] with no extra chunks. */
        const val FMT_BODY = 20

        /**
         * Peak amplitude of the sweep. Short of full scale so that neither the
         * sweep nor any arithmetic on it can clip, which would flatten samples
         * and hide a mismatch.
         */
        const val AMPLITUDE = 30_000.0

        const val SWEEP_START_HZ = 200.0
        const val SWEEP_END_HZ = 3_400.0

        /**
         * A linear sine sweep from [SWEEP_START_HZ] to [SWEEP_END_HZ] across
         * [frames] frames — the telephone band, which is what a 16 kHz car
         * microphone is built to carry.
         *
         * Phase is accumulated rather than computed from `sin(2*pi*f(t)*t)`,
         * which is the usual mistake: that formula sweeps at twice the intended
         * rate and is discontinuous, so its output is not the signal its
         * parameters describe.
         */
        fun sineSweep(frames: Int): ShortArray {
            val samples = ShortArray(frames)
            var phase = 0.0
            for (i in 0 until frames) {
                val progress = if (frames <= 1) 0.0 else i.toDouble() / (frames - 1)
                val frequency = SWEEP_START_HZ + (SWEEP_END_HZ - SWEEP_START_HZ) * progress
                samples[i] = (AMPLITUDE * sin(phase)).roundToInt().toShort()
                phase += 2.0 * PI * frequency / SAMPLE_RATE_HZ
            }
            return samples
        }

        /**
         * Writes a canonical RIFF/WAVE file: `RIFF` header, `fmt ` chunk, any
         * [extraChunks], then `data`.
         *
         * Deliberately written by hand rather than with [WavPcm]'s constants, so
         * that reader and writer are independent implementations of the same
         * format.
         */
        fun wavBytes(
            samples: ShortArray,
            sampleRate: Int,
            channels: Int,
            bitsPerSample: Int = 16,
            extraChunks: List<Pair<String, ByteArray>> = emptyList(),
        ): ByteArray {
            val bytesPerFrame = channels * bitsPerSample / 8

            val pcm = ByteArray(samples.size * 2)
            for (i in samples.indices) {
                val value = samples[i].toInt()
                pcm[i * 2] = (value and 0xFF).toByte()
                pcm[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
            }

            val fmt = ByteArrayOutputStream().apply {
                writeLe16(1) // WAVE_FORMAT_PCM
                writeLe16(channels)
                writeLe32(sampleRate.toLong())
                writeLe32(sampleRate.toLong() * bytesPerFrame) // byte rate
                writeLe16(bytesPerFrame) // block align
                writeLe16(bitsPerSample)
            }.toByteArray()

            val chunks = buildList {
                add("fmt " to fmt)
                addAll(extraChunks)
                add("data" to pcm)
            }

            val body = ByteArrayOutputStream()
            body.write("WAVE".toByteArray(Charsets.US_ASCII))
            for ((id, payload) in chunks) {
                body.write(id.toByteArray(Charsets.US_ASCII))
                body.writeLe32(payload.size.toLong())
                body.write(payload)
                // RIFF chunks are word-aligned; the pad byte is not counted in
                // the size field.
                if (payload.size % 2 == 1) body.write(0)
            }
            val bodyBytes = body.toByteArray()

            val out = ByteArrayOutputStream()
            out.write("RIFF".toByteArray(Charsets.US_ASCII))
            out.writeLe32(bodyBytes.size.toLong())
            out.write(bodyBytes)
            return out.toByteArray()
        }

        /** Offset of the `data` chunk's size field in a file written by [wavBytes]. */
        fun dataSizeOffset(bytes: ByteArray): Int {
            var offset = 12
            while (offset + 8 <= bytes.size) {
                val id = String(bytes, offset, 4, Charsets.US_ASCII)
                val size = (bytes[offset + 4].toLong() and 0xFF) or
                    ((bytes[offset + 5].toLong() and 0xFF) shl 8) or
                    ((bytes[offset + 6].toLong() and 0xFF) shl 16) or
                    ((bytes[offset + 7].toLong() and 0xFF) shl 24)
                if (id == "data") return offset + 4
                offset += 8 + size.toInt() + (size.toInt() and 1)
            }
            throw IllegalStateException("no data chunk in the fixture")
        }

        fun le16(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = (value and 0xFF).toByte()
            bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }

        fun le32(bytes: ByteArray, offset: Int, value: Long) {
            for (i in 0 until 4) {
                bytes[offset + i] = ((value shr (8 * i)) and 0xFF).toByte()
            }
        }

        fun ByteArrayOutputStream.writeLe16(value: Int) {
            write(value and 0xFF)
            write((value shr 8) and 0xFF)
        }

        fun ByteArrayOutputStream.writeLe32(value: Long) {
            for (i in 0 until 4) {
                write(((value shr (8 * i)) and 0xFF).toInt())
            }
        }
    }
}

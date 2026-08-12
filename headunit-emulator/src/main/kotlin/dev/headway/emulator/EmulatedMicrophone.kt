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

import aap_protobuf.service.media.shared.message.ConfigOuterClass.Config
import aap_protobuf.service.media.shared.message.MediaCodecTypeOuterClass.MediaCodecType
import aap_protobuf.service.media.shared.message.SetupOuterClass.Setup
import aap_protobuf.service.media.source.message.AckOuterClass.Ack
import aap_protobuf.service.media.source.message.MicrophoneRequestOuterClass.MicrophoneRequest
import aap_protobuf.service.media.source.message.MicrophoneResponseOuterClass.MicrophoneResponse
import dev.headway.protocol.channel.AvMessageId
import dev.headway.protocol.channel.MediaFrame
import dev.headway.protocol.channel.MicrophoneFormat
import dev.headway.protocol.channel.PcmDecoder
import dev.headway.protocol.framing.AapMessage
import dev.headway.protocol.framing.ChannelId
import dev.headway.protocol.io.FramedConnection
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** Raised when a file does not decode as the 16-bit PCM WAV it claims to be. */
class WavFormatException(message: String) : RuntimeException(message)

/** The audio a WAV file contained, with the format its header declared. */
class WavAudio(val format: MicrophoneFormat, val samples: ShortArray) {
    /** Sample frames — samples divided by the channel count. */
    val frameCount: Int get() = samples.size / format.channels

    /** How long the file plays, in microseconds. */
    val durationMicros: Long get() = format.durationMicros(frameCount.toLong())

    override fun toString(): String = "WavAudio($format, $frameCount frames)"
}

/**
 * A RIFF/WAVE reader, strict enough that a broken header is an error rather
 * than audio.
 *
 * ## This is not an AAP construct
 *
 * WAV is a Microsoft RIFF container and has nothing to do with the Android Auto
 * protocol; there is no reference in `references/` to cite for it, and none is
 * needed — the layout below is the published RIFF/WAVE format. It is here
 * because Phase 5's acceptance test injects a real recording, and reading one
 * with `skip 44 bytes and hope` is how a test ends up asserting on garbage: the
 * 44-byte header is only the *common* case, and any file carrying a `LIST`
 * INFO chunk, a `fact` chunk, or an extensible `fmt ` block has a longer one.
 *
 * ## Strictness
 *
 * Every check below exists because failing it silently produces plausible-looking
 * noise rather than an obvious failure:
 *
 * - **byte order** — RIFF is little-endian throughout. Read big-endian, a 16 kHz
 *   rate becomes 1 109 393 408 Hz, which no downstream check would catch;
 * - **chunk walking** — chunks are padded to an even length, and a reader that
 *   forgets the pad byte lands one byte into the next chunk id and reads the
 *   rest of the file misaligned;
 * - **declared sizes** — a chunk claiming more bytes than the file holds means
 *   the file is truncated, and clamping would hand back a half-frame of audio;
 * - **`data` size divisible by the frame size** — a partial frame at the end is
 *   the signature of a truncated recording;
 * - **bit depth** — only 16-bit is accepted, because that is all the microphone
 *   channel carries (see [dev.headway.protocol.channel.PcmDecoder]).
 *
 * A file whose RIFF size field is `0xFFFFFFFF` — the convention for a WAV still
 * being streamed — is rejected rather than special-cased. Nothing produces one
 * here, and accepting it would mean accepting every truncated file too.
 */
object WavPcm {

    /** Reads and decodes [file]. */
    fun read(file: File): WavAudio {
        if (!file.isFile) throw WavFormatException("not a file: $file")
        return parse(file.readBytes())
    }

    /**
     * Decodes a whole WAV file held in memory.
     *
     * @throws WavFormatException on anything that is not a 16-bit PCM WAVE.
     */
    fun parse(bytes: ByteArray): WavAudio {
        if (bytes.size < RIFF_HEADER_SIZE) {
            throw WavFormatException(
                "file is ${bytes.size} bytes, too short for a $RIFF_HEADER_SIZE-byte RIFF header"
            )
        }
        if (fourCc(bytes, 0) != "RIFF") {
            throw WavFormatException("not a RIFF file: magic is '${fourCc(bytes, 0)}', expected 'RIFF'")
        }
        val riffSize = uint32(bytes, 4)
        if (riffSize < FOUR_CC_SIZE) {
            throw WavFormatException("RIFF size field is $riffSize, too small to hold a form type")
        }
        if (riffSize > bytes.size - RIFF_SIZE_FIELD_END) {
            throw WavFormatException(
                "RIFF size field claims $riffSize bytes but only " +
                    "${bytes.size - RIFF_SIZE_FIELD_END} follow; the file is truncated"
            )
        }
        if (fourCc(bytes, 8) != "WAVE") {
            throw WavFormatException("RIFF form type is '${fourCc(bytes, 8)}', expected 'WAVE'")
        }

        // The RIFF size field, not the file length, bounds the chunk list: bytes
        // past it are not part of the form and must not be walked into.
        val end = (RIFF_SIZE_FIELD_END + riffSize).toInt()

        var format: MicrophoneFormat? = null
        var blockAlign = 0
        var data: ByteArray? = null

        var offset = RIFF_HEADER_SIZE
        while (offset < end) {
            if (offset + CHUNK_HEADER_SIZE > end) {
                throw WavFormatException(
                    "chunk header at offset $offset runs past the end of the RIFF form"
                )
            }
            val id = fourCc(bytes, offset)
            val size = uint32(bytes, offset + FOUR_CC_SIZE)
            val body = offset + CHUNK_HEADER_SIZE
            if (size > end - body) {
                throw WavFormatException(
                    "chunk '$id' at offset $offset declares $size bytes but only ${end - body} " +
                        "remain in the form"
                )
            }

            when (id) {
                "fmt " -> {
                    if (size < FMT_CHUNK_SIZE) {
                        throw WavFormatException(
                            "'fmt ' chunk is $size bytes, shorter than the $FMT_CHUNK_SIZE-byte " +
                                "PCM format block"
                        )
                    }
                    val audioFormat = uint16(bytes, body)
                    if (audioFormat != WAVE_FORMAT_PCM) {
                        throw WavFormatException(
                            "'fmt ' declares audio format $audioFormat; only uncompressed PCM " +
                                "($WAVE_FORMAT_PCM) is decoded"
                        )
                    }
                    val channels = uint16(bytes, body + 2)
                    val sampleRate = uint32(bytes, body + 4).toInt()
                    val declaredBlockAlign = uint16(bytes, body + 12)
                    val bits = uint16(bytes, body + 14)

                    if (channels < 1) throw WavFormatException("'fmt ' declares $channels channels")
                    if (sampleRate < 1) {
                        throw WavFormatException("'fmt ' declares a sample rate of $sampleRate Hz")
                    }
                    if (bits != BITS_PER_SAMPLE) {
                        throw WavFormatException(
                            "'fmt ' declares $bits bits per sample; the microphone channel carries " +
                                "$BITS_PER_SAMPLE-bit PCM and nothing else"
                        )
                    }
                    val expectedBlockAlign = channels * (bits / 8)
                    if (declaredBlockAlign != expectedBlockAlign) {
                        // Self-inconsistent header: one of the three fields is
                        // wrong and there is no way to tell which.
                        throw WavFormatException(
                            "'fmt ' declares a block align of $declaredBlockAlign, which does not " +
                                "match $channels channels of $bits bits ($expectedBlockAlign)"
                        )
                    }
                    blockAlign = declaredBlockAlign
                    format = MicrophoneFormat(sampleRate, bits, channels)
                }

                "data" -> data = bytes.copyOfRange(body, body + size.toInt())
            }

            // Chunks are word-aligned: an odd size is followed by a pad byte
            // that is not counted in the size field.
            offset = body + size.toInt() + (size.toInt() and 1)
        }

        val decodedFormat = format
            ?: throw WavFormatException("no 'fmt ' chunk; the file does not declare a format")
        val payload = data
            ?: throw WavFormatException("no 'data' chunk; the file declares a format but no audio")
        if (payload.size % blockAlign != 0) {
            throw WavFormatException(
                "'data' chunk is ${payload.size} bytes, not a whole number of $blockAlign-byte " +
                    "frames; the recording is truncated"
            )
        }
        return WavAudio(decodedFormat, PcmDecoder.decodeS16Le(payload))
    }

    private fun fourCc(bytes: ByteArray, offset: Int): String =
        String(bytes, offset, FOUR_CC_SIZE, Charsets.US_ASCII)

    /** Returned as a [Long] so a size near 4 GiB does not come back negative. */
    private fun uint32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    private fun uint16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    const val FOUR_CC_SIZE: Int = 4

    /** `RIFF` + size field + form type. */
    const val RIFF_HEADER_SIZE: Int = 12

    /** Offset just past the RIFF size field; the field counts from here. */
    const val RIFF_SIZE_FIELD_END: Int = 8

    /** Chunk id + chunk size. */
    const val CHUNK_HEADER_SIZE: Int = 8

    /** Bytes of a `fmt ` chunk needed for uncompressed PCM. */
    const val FMT_CHUNK_SIZE: Int = 16

    /** `WAVE_FORMAT_PCM`. */
    const val WAVE_FORMAT_PCM: Int = 1

    /** The only sample width this reader accepts. */
    const val BITS_PER_SAMPLE: Int = 16
}

/** How the emulated microphone answers, and how it paces what it sends. */
data class MicrophoneConfig(
    /**
     * What the unit advertises in `MediaSourceService.audio_config` and what it
     * actually sends. openauto's is `QtAudioInput(1, 16, 16000)`
     * (`openauto/src/autoapp/Service/ServiceFactory.cpp` L194).
     */
    val format: MicrophoneFormat = MicrophoneFormat.CAR_MICROPHONE,
    /**
     * Milliseconds of audio per `DATA` message. 20 ms is the frame length every
     * voice pipeline is built around; at 16 kHz mono it is 320 samples, 640
     * bytes.
     *
     * **Inferred, not observed.** No reference states a chunk size for this
     * channel. openauto's is an artefact of a fixed buffer — it reads up to
     * `cSampleSize = 2056` bytes per read
     * (`openauto/include/f1x/openauto/autoapp/Projection/QtAudioInput.hpp` L67,
     * used at `QtAudioInput.cpp` L159-L161), which at 16 kHz mono is 1028
     * samples, about 64 ms, and an odd number of bytes at that, so the last
     * sample of every read is split across two messages. Headway does not copy
     * that; 20 ms is chosen because it is a whole number of frames and matches
     * what a real capture callback delivers.
     */
    val chunkMillis: Int = 20,
    /**
     * `Config.status` in the setup response. openauto answers `STATUS_READY`
     * unconditionally on this channel — unlike its video service, it does not
     * gate on the input having initialised
     * (`openauto/src/autoapp/Service/MediaSource/MediaSourceService.cpp`
     * L144-L148).
     */
    val setupStatus: Config.Status = Config.Status.STATUS_READY,
    /** `Config.max_unacked`; openauto sends 1 (same lines). */
    val maxUnacked: Int = 1,
    /** `Config.configuration_indices`; openauto sends `[0]` (same lines). */
    val configurationIndices: List<Int> = listOf(0),
    /**
     * `MicrophoneResponse.session_id`.
     *
     * -1 reproduces openauto, which initialises `session_` to -1 and never
     * assigns it (`openauto/.../MediaSourceService.cpp` L28; the only uses are
     * the `set_session_id(session_)` calls at L190, L208 and L229). A phone that
     * rejects a negative session id would work against every other head unit and
     * fail against a Raspberry Pi, so the default here is the awkward value.
     */
    val sessionId: Int = -1,
    /**
     * `MicrophoneResponse.status` for an open request. 0 is
     * `MessageStatus.STATUS_SUCCESS`; openauto sends -7
     * (`STATUS_INTERNAL_ERROR`) when its audio input fails to start
     * (`openauto/.../MediaSourceService.cpp` L189-L192).
     */
    val openStatus: Int = 0,
    /** `MicrophoneResponse.status` for a close request; openauto always succeeds (L207-L210). */
    val closeStatus: Int = 0,
    /**
     * Reply on the ids aasdk emits — `Config` as 0x8000 and `MicrophoneResponse`
     * as 0x8005 — instead of the 0x8003/0x8006 a real head unit was observed
     * using.
     *
     * Both are real behaviours in the field: aasdk (and therefore openauto)
     * echoes the request id back (`aasdk/src/Channel/MediaSource/MediaSourceService.cpp`
     * L67 and L105-L106), while aa-proxy-rs decodes real traffic on the
     * response ids (`aa-proxy-rs/src/mitm.rs` L2572-L2617). See
     * [dev.headway.protocol.channel.MicrophoneChannel] for why the phone accepts
     * both; this switch is how that tolerance gets tested rather than assumed.
     */
    val useAasdkReplyIds: Boolean = false,
    /** Presentation timestamp of the first sample, in microseconds. */
    val startTimestampMicros: Long = 0L,
    /**
     * Sleep for each chunk's duration before sending it, so the stream arrives
     * at roughly the rate a real microphone produces it.
     *
     * Off by default: a test that asserts on content and ordering gains nothing
     * from running in real time, and a 10-second recording would cost 10 seconds
     * of CI.
     */
    val paceRealTime: Boolean = false,
)

/**
 * The **head-unit** side of the car-microphone channel: it answers setup, opens
 * and closes on request, and streams a caller-supplied recording as `DATA`
 * messages.
 *
 * Mirrors `openauto/src/autoapp/Service/MediaSource/MediaSourceService.cpp`,
 * with its audio input replaced by a fixed buffer so that what the phone
 * receives can be compared byte for byte against what was loaded. That
 * comparison is the point: it is the one property a shared-`core-protocol`
 * round trip cannot fake, because a symmetric bug in the framing layer still
 * has to deliver the same samples in the same order.
 *
 * ## Concurrency
 *
 * [run] services inbound messages and, while capture is open, a child coroutine
 * sends audio. Both are needed at once: the phone acknowledges every `DATA`, so
 * a head unit that streamed without reading would fill the transport and
 * deadlock against a phone that was doing exactly the right thing.
 *
 * The streaming coroutine is stopped by clearing a flag it checks between
 * chunks, never by cancellation. Cancelling mid-`send` would abandon a
 * fragmented message part-way and desynchronise the reassembler on the other
 * end — a failure that would look like a framing bug rather than a shutdown
 * bug.
 */
class EmulatedMicrophone(
    private val connection: FramedConnection,
    /**
     * The channel this source serves. Not a protocol constant — whatever id the
     * unit advertised for its `media_source_service`.
     */
    val channelId: Int = ChannelId.MEDIA_SOURCE_MICROPHONE.id,
    val config: MicrophoneConfig = MicrophoneConfig(),
    private val onStep: (String) -> Unit = {},
) {

    /** Sample frames per `DATA` message; the last one may be shorter. */
    val chunkFrames: Int = config.format.sampleRateHz * config.chunkMillis / MILLIS_PER_SECOND

    private var source: ShortArray = ShortArray(0)

    @Volatile
    private var capturing = false

    /** The codec the phone asked for in `Setup`, once it has. */
    var requestedCodec: MediaCodecType? = null
        private set

    /** Every `MicrophoneRequest` received, in arrival order. */
    private val requests = ArrayList<MicrophoneRequest>()
    val microphoneRequests: List<MicrophoneRequest> get() = synchronized(requests) { requests.toList() }

    /** `DATA` messages sent. */
    @Volatile
    var chunksSent: Long = 0L
        private set

    /** Sample frames sent. */
    @Volatile
    var framesSent: Long = 0L
        private set

    /** Acknowledgements the phone sent. */
    var acksReceived: Long = 0L
        private set

    /** Session ids carried by those acknowledgements, in arrival order. */
    private val ackSessionIds = ArrayList<Int>()
    val acknowledgedSessionIds: List<Int> get() = synchronized(ackSessionIds) { ackSessionIds.toList() }

    /** Message ids this source did not implement, in arrival order. */
    private val unhandled = ArrayList<Int>()
    val unhandledMessageIds: List<Int> get() = synchronized(unhandled) { unhandled.toList() }

    /** True once the phone has asked to close the microphone. */
    var closed: Boolean = false
        private set

    // --- the recording ------------------------------------------------------

    /**
     * Sets the audio this microphone will produce: interleaved signed 16-bit
     * samples in the configured [MicrophoneConfig.format].
     */
    fun load(samples: ShortArray) {
        require(samples.size % config.format.channels == 0) {
            "${samples.size} samples is not a whole number of ${config.format.channels}-channel frames"
        }
        source = samples.copyOf()
    }

    /**
     * Loads a real WAV recording, refusing one whose format is not the format
     * this unit advertises.
     *
     * Resampling is deliberately not attempted. The head unit advertises exactly
     * one `audio_config` and the phone decodes against it, so quietly playing a
     * 44.1 kHz file down a 16 kHz channel would produce audio that is
     * bit-perfect and three times too slow — which is precisely the kind of
     * failure a test suite should not be able to produce by accident.
     */
    fun loadWav(file: File) {
        val wav = WavPcm.read(file)
        if (wav.format != config.format) {
            throw WavFormatException(
                "$file is ${wav.format}, but this microphone advertises ${config.format}"
            )
        }
        load(wav.samples)
        onStep("loaded $file: ${wav.frameCount} frames, ${wav.durationMicros / 1000} ms")
    }

    /** Sample frames loaded. */
    val frameCount: Int get() = source.size / config.format.channels

    /** How many `DATA` messages [frameCount] frames will be sent as. */
    val expectedChunkCount: Int
        get() = if (frameCount == 0) 0 else (frameCount + chunkFrames - 1) / chunkFrames

    // --- the channel --------------------------------------------------------

    /**
     * Services the channel until the phone asks to close the microphone.
     *
     * @param untilClose when false, return after the *open* has been answered
     *   and the recording fully sent, without waiting for a close. Useful for a
     *   phone that walks away.
     */
    suspend fun run(untilClose: Boolean = true): Unit = coroutineScope {
        var streaming: Job? = null
        while (true) {
            val message = connection.receive()
            if (message.channelId != channelId) {
                throw IllegalStateException(
                    "message for ${ChannelId.describe(message.channelId)} arrived on the " +
                        "microphone source's connection"
                )
            }

            when (message.messageId) {
                AvMessageId.SETUP -> onSetup(message.payload)

                AvMessageId.MICROPHONE_REQUEST -> {
                    val request = MicrophoneRequest.parseFrom(message.payload)
                    synchronized(requests) { requests += request }
                    if (request.open) {
                        onStep(
                            "microphone open requested (anc=${request.ancEnabled}, " +
                                "ec=${request.ecEnabled}, max_unacked=${request.maxUnacked})"
                        )
                        // openauto answers before the first sample, then starts
                        // reading its input (MediaSourceService.cpp L225-L238).
                        sendMicrophoneResponse(config.openStatus)
                        capturing = config.openStatus == STATUS_SUCCESS
                        if (capturing) streaming = launch { streamSource() }
                        if (!untilClose) {
                            streaming?.join()
                            return@coroutineScope
                        }
                    } else {
                        onStep("microphone close requested")
                        capturing = false
                        streaming?.join()
                        sendMicrophoneResponse(config.closeStatus)
                        closed = true
                        return@coroutineScope
                    }
                }

                AvMessageId.ACK -> {
                    val ack = Ack.parseFrom(message.payload)
                    acksReceived++
                    synchronized(ackSessionIds) { ackSessionIds += ack.sessionId }
                }

                else -> {
                    // aasdk logs an unhandled id and re-arms rather than failing
                    // the channel (MediaSourceService.cpp L92-L95).
                    synchronized(unhandled) { unhandled += message.messageId }
                    onStep("unhandled ${AvMessageId.describe(message.messageId)}")
                }
            }
        }
    }

    private suspend fun onSetup(payload: ByteArray) {
        val setup = Setup.parseFrom(payload)
        requestedCodec = setup.type
        onStep("setup request: ${setup.type.name}")

        val response = Config.newBuilder()
            .setStatus(config.setupStatus)
            .setMaxUnacked(config.maxUnacked)
            .addAllConfigurationIndices(config.configurationIndices)
            .build()
        val id = if (config.useAasdkReplyIds) AvMessageId.SETUP else AvMessageId.CONFIG
        connection.send(specific(id, response.toByteArray()))
        onStep("config sent: ${config.setupStatus.name}, window ${config.maxUnacked}")
    }

    private suspend fun sendMicrophoneResponse(status: Int) {
        val response = MicrophoneResponse.newBuilder()
            .setStatus(status)
            .setSessionId(config.sessionId)
            .build()
        val id = if (config.useAasdkReplyIds) {
            AvMessageId.MICROPHONE_REQUEST
        } else {
            AvMessageId.MICROPHONE_RESPONSE
        }
        connection.send(specific(id, response.toByteArray()))
    }

    // --- streaming ----------------------------------------------------------

    /**
     * Sends the loaded recording as `DATA` messages and returns when it runs out
     * or capture is closed.
     *
     * Timestamps are the position of each chunk's first frame on the recording's
     * own timeline: `start + frames_sent * 1e6 / sample_rate`. openauto instead
     * stamps with `high_resolution_clock::now()` at the moment it happens to
     * send (`openauto/.../MediaSourceService.cpp` L252-L254), which is a wall
     * clock, not a media clock — the timestamps it produces drift with
     * scheduling and are not reproducible. A synthetic timeline is both
     * deterministic and what the field is for; the difference is worth stating
     * because a phone that assumed openauto's semantics would treat these as
     * suspiciously regular.
     */
    private suspend fun streamSource() {
        val channels = config.format.channels
        val chunkSamples = chunkFrames * channels
        var offset = 0
        while (capturing && offset < source.size) {
            if (config.paceRealTime) delay(config.chunkMillis.toLong())

            val length = minOf(chunkSamples, source.size - offset)
            val timestamp = config.startTimestampMicros + config.format.durationMicros(framesSent)
            connection.send(
                MediaFrame.data(
                    channelId = channelId,
                    media = PcmDecoder.encodeS16Le(source, offset, length),
                    timestampMicros = timestamp,
                )
            )
            offset += length
            chunksSent++
            framesSent += (length / channels).toLong()
        }
        onStep("streamed $chunksSent chunk(s), $framesSent frame(s)")
    }

    // --- helpers ------------------------------------------------------------

    /** Encrypted, `MessageType::SPECIFIC`, like every other media message. */
    private fun specific(messageId: Int, payload: ByteArray) = AapMessage(
        channelId = channelId,
        control = false,
        encrypted = true,
        messageId = messageId,
        payload = payload,
    )

    companion object {
        /** `MessageStatus.STATUS_SUCCESS` (`aasdk/protobuf/aap_protobuf/shared/MessageStatus.proto` L6). */
        const val STATUS_SUCCESS: Int = 0

        const val MILLIS_PER_SECOND: Int = 1000
    }
}

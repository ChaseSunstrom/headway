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

import aap_protobuf.service.ServiceOuterClass
import aap_protobuf.service.control.message.AudioFocusRequestTypeOuterClass.AudioFocusRequestType
import aap_protobuf.service.control.message.AudioFocusStateTypeOuterClass.AudioFocusStateType
import aap_protobuf.service.media.shared.message.MediaCodecTypeOuterClass.MediaCodecType
import aap_protobuf.service.media.sink.message.AudioStreamTypeOuterClass.AudioStreamType
import dev.headway.protocol.channel.AudioChannel
import dev.headway.protocol.channel.AudioChannelException
import dev.headway.protocol.channel.AudioFocus
import dev.headway.protocol.channel.MediaAudioRoute
import dev.headway.protocol.channel.PcmFormat
import dev.headway.protocol.framing.ChannelId
import dev.headway.protocol.io.FramedConnection
import dev.headway.protocol.session.AapSession
import dev.headway.protocol.session.PhoneIdentity
import dev.headway.transport.LoopbackTransport
import dev.headway.transport.tls.AapTls
import dev.headway.transport.tls.TlsSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * **Phase 4 acceptance — audio output and focus.**
 *
 * CLAUDE.md:
 *
 * > **Phase 4 — Audio.** *Accepted when:* the emulator receives a spoken TTS
 * > prompt over the speech channel while a music app plays over (simulated)
 * > A2DP, with correct duck/resume messages on the wire.
 *
 * ## Evidence tier
 *
 * **Tier A for the wire sequence, Tier D for the car** (`PROGRESS.md`).
 *
 * Tier A — executed, on real bytes: the prompt is real audio, a 440 Hz sine
 * generated at whatever sample rate the head unit advertised, streamed as PCM on
 * the guidance channel and compared byte for byte against what the emulated
 * unit's speaker would have been handed. The focus exchange is real protocol:
 * control messages out and back, asserted in the order they crossed the wire.
 *
 * Tier D — not provable here, and no assertion below pretends otherwise:
 *
 * - **that a real Chevrolet ducks its radio** when it grants focus. Nothing
 *   Headway can send makes that observable; the car either lowers its own source
 *   or it does not, and the only evidence would be a drive (BLOCKERS.md B-001);
 * - **that A2DP and AAP audio coexist** in a vehicle. A2DP is a flag here and
 *   has to be — Headway routes third-party media over the car's own Bluetooth
 *   link rather than the AAP media channel (CLAUDE.md, "Audio"), so for that
 *   path there is no AAP traffic to observe. The *absence* of traffic is what is
 *   asserted, which catches the failure a naive implementation produces — the
 *   music duplicated over AAP and played twice — and says nothing about whether
 *   the two links stay in sync in a car;
 * - **that a real head unit's focus policy resembles the emulator's.** Per
 *   ADR 0002 the emulator shares `core-protocol` with the phone, so a green run
 *   is self-consistency, not compatibility.
 *
 * The acceptance criterion below therefore runs against [AudioFocusPolicy.OPENAUTO],
 * the only head-unit focus policy any reference implements: every gain flavour
 * collapses to `GAIN` and only `RELEASE` produces a `LOSS`. A unit that answers
 * a transient request with a transient grant is handled too, but in a separate
 * test, because no reference and no capture shows one existing.
 *
 * What is left, and it is the part that would silently break: Headway asks for
 * focus before it speaks, releases it when it stops, and puts the samples on the
 * wire unaltered.
 */
class Phase4AudioAcceptanceTest {

    // --- the acceptance criterion -------------------------------------------

    @Test
    fun `a spoken prompt reaches the speech channel while media stays on A2DP`() {
        lateinit var prompt: ByteArray
        lateinit var music: AudioChannel

        val run = audioSession(
            stream = AudioStreamType.AUDIO_STREAM_GUIDANCE,
            phone = { session ->
                // The music app is playing. Headway does not carry that audio:
                // it is already on the car's Bluetooth link.
                music = AudioChannel.fromService(
                    connection = session.connection,
                    service = session.serviceFor(AudioStreamType.AUDIO_STREAM_MEDIA),
                    route = MediaAudioRoute.BLUETOOTH_A2DP,
                )
                assertFalse(music.transmits, "media audio must not be on the AAP channel")
                assertEquals(0, music.streamPcm(MUSIC_BYTES))

                // Not merely silent: the channel refuses to be set up at all, so
                // the car is never left waiting for a stream that will not come.
                // runCatching rather than assertThrows because these are suspend
                // calls and Executable is not a suspend interface.
                val refused = runCatching { music.sendSetup() }.exceptionOrNull()
                assertTrue(
                    refused is AudioChannelException,
                    "setting up an A2DP-routed channel must be refused, not silently accepted",
                )

                // Something needs saying. Ask for the speakers first, briefly,
                // and allow the car to duck rather than stop what it is playing.
                session.focus.requestTransient(mayDuck = true)
                val granted = session.focus.awaitNotification()
                assertTrue(granted.holdsFocus, "the prompt cannot play without focus")
                assertTrue(granted.allows(AudioStreamType.AUDIO_STREAM_GUIDANCE))

                // `granted.transient` is deliberately not asserted. openauto
                // answers every gain flavour with a plain GAIN
                // (AndroidAutoEntity.cpp L236-L246), so a phone that required
                // the grant to mirror the request would work only against a head
                // unit nobody has seen.

                val speech = session.channel
                speech.sendSetup()
                val config = speech.awaitConfig()
                check(config.ready) { "head unit answered setup with ${config.status}" }
                speech.sendStart(
                    sessionId = SESSION_ID,
                    configurationIndex = config.configurationIndices.first(),
                )

                // Generated at the negotiated rate, not at an assumed one.
                prompt = sineWave(speech.format!!, PROMPT_MICROS)
                speech.streamPcm(prompt)
                speech.awaitAllAcks()
                speech.sendStop()

                // Give the speakers back so the car's own audio comes up again.
                session.focus.release()
                val released = session.focus.awaitNotification()
                assertTrue(released.mustStop, "a release must be answered with a loss")
            },
            headUnit = { sink ->
                sink.run(mediaMessages = PROMPT_BUFFERS)
                // Stop, then the release request that the release notification
                // answers.
                sink.runMessages(2)
            },
        )

        val sink = run.sink

        // The prompt arrived, bit for bit.
        assertArrayEquals(prompt, sink.pcmBytes(), "the PCM the car would play must be the PCM sent")
        assertEquals(PROMPT_BUFFERS, sink.receivedBuffers.size)
        assertEquals(PROMPT_MICROS, sink.receivedDurationMicros())
        assertEquals(GUIDANCE_FORMAT, sink.selectedFormat, "the speech channel is mono 16 kHz here")

        // On a 20 ms grid with no gap and no overlap: a repeated or dropped
        // timestamp is what a stutter sounds like.
        val timestamps = sink.receivedBuffers.map { it.timestampMicros }
        assertEquals(List(PROMPT_BUFFERS) { it * BUFFER_MICROS }, timestamps)

        // The focus messages, in the order they crossed the wire. Order is the
        // whole assertion: the same four messages with the request after the
        // audio, or the release before it, is a car whose radio stays ducked.
        assertEquals(
            listOf(
                EmulatedAudioSink.FocusEvent.Requested(
                    AudioFocusRequestType.AUDIO_FOCUS_GAIN_TRANSIENT_MAY_DUCK
                ),
                EmulatedAudioSink.FocusEvent.Notified(
                    AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN,
                    unsolicited = false,
                ),
                EmulatedAudioSink.FocusEvent.Requested(AudioFocusRequestType.AUDIO_FOCUS_RELEASE),
                EmulatedAudioSink.FocusEvent.Notified(
                    AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS,
                    unsolicited = false,
                ),
            ),
            sink.focusEvents,
        )

        // Nothing of the music reached the AAP media channel. The emulated sink
        // throws on a message for any channel but its own, so a leak would have
        // failed the run rather than gone unnoticed here.
        assertEquals(MUSIC_BYTES.size.toLong(), music.bytesSuppressed)
        assertEquals(0L, music.bytesSent)
        assertEquals(0L, music.mediaMessagesSent)

        // Flow control settled and the session closed cleanly.
        assertEquals(0, run.channel.unacknowledgedMessages)
        assertEquals(PROMPT_BUFFERS.toLong(), sink.acksSent)
        assertTrue(sink.stopped, "the head unit must see the phone's Stop")
        assertEquals(
            EmulatedAudioSink.NO_SESSION,
            sink.sessionId,
            "openauto forgets the session on Stop; so does this",
        )
        assertTrue(sink.unhandledMessageIds.isEmpty(), "the sink saw an unexpected message id")
    }

    /**
     * The same exchange against a head unit that answers a transient request
     * with a transient grant.
     *
     * **Evidence about Headway, not about cars.** No reference implements this
     * policy and no capture in this repository shows one — see
     * [AudioFocusPolicy.TRANSIENT_AWARE]. Under [AudioFocusPolicy.OPENAUTO] the
     * phone's transient-grant handling is unreachable, and unreachable code is
     * where bugs live; this is the only way to execute it at all.
     */
    @Test
    fun `a head unit that grants transient focus is understood as transient`() {
        val run = audioSession(
            stream = AudioStreamType.AUDIO_STREAM_GUIDANCE,
            sinkConfig = AudioSinkConfig(focusPolicy = AudioFocusPolicy.TRANSIENT_AWARE),
            phone = { session ->
                session.focus.requestTransient(mayDuck = true)
                val granted = session.focus.awaitNotification()
                assertTrue(granted.holdsFocus)
                assertTrue(granted.transient, "a transient grant must be recognised as short-lived")
                assertTrue(granted.allows(AudioStreamType.AUDIO_STREAM_GUIDANCE))

                session.focus.release()
                assertTrue(session.focus.awaitNotification().mustStop)
            },
            // Two requests, each answered; no media on this channel at all.
            headUnit = { sink -> sink.runMessages(2) },
        )

        assertEquals(
            listOf(
                AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_TRANSIENT,
                AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS,
            ),
            run.focus.notifications.map { it.state },
        )
        assertEquals(0, run.sink.bufferCount, "no audio was sent in this exchange")
    }

    // --- the other direction ------------------------------------------------

    /**
     * The car can take the speakers back without being asked — the driver
     * presses the radio's source button, or a reversing chime needs the
     * amplifier. openauto describes exactly this and does not implement it
     * ("When HU starts playing music, we should send a STATE LOSS to stop MD
     * music and guidance", `AndroidAutoEntity.cpp` L228), so the emulator sends
     * it and this pins what Headway is supposed to make of it.
     *
     * Ducking and stopping are different instructions and Headway must not
     * collapse them: `LOSS_TRANSIENT_CAN_DUCK` means keep talking, quieter.
     */
    @Test
    fun `the head unit can duck and resume Headway's audio unprompted`() {
        val run = audioSession(
            stream = AudioStreamType.AUDIO_STREAM_GUIDANCE,
            phone = { session ->
                val ducked = session.focus.awaitNotification()
                assertTrue(ducked.unsolicited, "nobody asked for this one")
                assertTrue(ducked.mustDuck)
                assertFalse(ducked.mustStop, "ducking is not stopping")
                assertTrue(
                    ducked.allows(AudioStreamType.AUDIO_STREAM_GUIDANCE),
                    "a ducked stream still plays",
                )

                val resumed = session.focus.awaitNotification()
                assertTrue(resumed.holdsFocus)
                assertFalse(resumed.mustDuck, "full gain means back to normal volume")
            },
            headUnit = { sink ->
                sink.sendUnsolicitedFocus(
                    AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS_TRANSIENT_CAN_DUCK
                )
                sink.sendUnsolicitedFocus(AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN)
            },
        )

        assertEquals(
            listOf(
                AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS_TRANSIENT_CAN_DUCK,
                AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN,
            ),
            run.focus.notifications.map { it.state },
        )
    }

    // --- the settings toggle ------------------------------------------------

    /**
     * CLAUDE.md keeps the AAP media channel as an option "behind a settings
     * toggle for apps that allow capture". With the toggle on, the same channel
     * that was silent above carries music at the car's media format — stereo
     * 48 kHz here, not the speech channel's mono 16 kHz, which is the reason the
     * format is negotiated per channel rather than per session.
     */
    @Test
    fun `music streams over the AAP media channel when the toggle is on`() {
        lateinit var music: ByteArray

        val run = audioSession(
            stream = AudioStreamType.AUDIO_STREAM_MEDIA,
            route = MediaAudioRoute.AAP_MEDIA_CHANNEL,
            phone = { session ->
                val media = session.channel
                assertTrue(media.transmits, "the toggle is on")
                media.sendSetup()
                val config = media.awaitConfig()
                media.sendStart(
                    sessionId = SESSION_ID,
                    configurationIndex = config.configurationIndices.first(),
                )

                music = sineWave(media.format!!, MUSIC_MICROS)
                media.streamPcm(music)
                media.awaitAllAcks()
                media.sendStop()
            },
            headUnit = { sink ->
                sink.run(mediaMessages = MUSIC_BUFFERS)
                sink.runMessages(1)
            },
        )

        assertEquals(MEDIA_FORMAT, run.sink.selectedFormat)
        assertArrayEquals(music, run.sink.pcmBytes())
        assertEquals(MUSIC_BUFFERS, run.sink.receivedBuffers.size)
        assertEquals(MUSIC_MICROS, run.sink.receivedDurationMicros())
    }

    // --- harness ------------------------------------------------------------

    /** One side of a running session, from the phone's point of view. */
    private class PhoneSide(
        val connection: FramedConnection,
        val channel: AudioChannel,
        val focus: AudioFocus,
        val services: List<ServiceOuterClass.Service>,
    ) {
        /** The advertised audio sink carrying [stream]. */
        fun serviceFor(stream: AudioStreamType): ServiceOuterClass.Service = services.first {
            it.hasMediaSinkService() &&
                it.mediaSinkService.hasAudioType() &&
                it.mediaSinkService.audioType == stream
        }
    }

    private class AudioRun(
        val sink: EmulatedAudioSink,
        val channel: AudioChannel,
        val focus: AudioFocus,
    )

    /**
     * Brings a session up the way Phase 1 does — real version handshake, real
     * TLS, real service discovery over the fake transport — then runs [phone]
     * against [EmulatedAudioSink] on the audio channel the head unit advertised
     * for [stream].
     *
     * Going through the whole bring-up is what makes the channel id, the stream
     * type and the sample format come from the advertisement rather than from
     * constants on both sides of the same file.
     *
     * The sink answers focus requests as well as media, because
     * [EmulatedHeadUnit] hands its connection over after bring-up and there is
     * no router yet; see [EmulatedAudioSink].
     */
    private fun audioSession(
        stream: AudioStreamType,
        route: MediaAudioRoute = MediaAudioRoute.BLUETOOTH_A2DP,
        // openauto's policy by default: the only one a reference implements.
        sinkConfig: AudioSinkConfig = AudioSinkConfig(),
        timeoutMillis: Long = 60_000,
        phone: suspend (PhoneSide) -> Unit,
        headUnit: suspend (EmulatedAudioSink) -> Unit,
    ): AudioRun = runBlocking {
        LoopbackTransport.pair().use { pair ->
            val phoneConnection = FramedConnection(pair.phone)
            val headUnitConnection = FramedConnection(pair.headUnit)

            val emulator = EmulatedHeadUnit(
                connection = headUnitConnection,
                tls = TlsSession(AapTls.headUnitEngine()),
            )
            val session = AapSession(
                connection = phoneConnection,
                tls = TlsSession(AapTls.phoneEngine()),
                identity = PhoneIdentity(deviceName = "Headway Test"),
            )

            // Every audio sink is opened even though only one is driven: which
            // channels exist is a service-discovery question, and which of them
            // carries audio is the routing decision under test.
            val audioChannels = emulator.config.advertisedChannels.count { it in AUDIO_CHANNELS }
            val bringUp = async(Dispatchers.IO) {
                session.connect { profile ->
                    profile.services
                        .filter { it.hasMediaSinkService() && it.mediaSinkService.hasAudioType() }
                        .map { it.id }
                }
            }
            val emulatorBringUp = async(Dispatchers.IO) { emulator.run(channelOpens = audioChannels) }
            val profile = withTimeout(timeoutMillis) { bringUp.await() }
            withTimeout(timeoutMillis) { emulatorBringUp.await() }

            val service = profile.services.first {
                it.hasMediaSinkService() &&
                    it.mediaSinkService.hasAudioType() &&
                    it.mediaSinkService.audioType == stream
            }
            check(service.mediaSinkService.availableType == MediaCodecType.MEDIA_CODEC_AUDIO_PCM) {
                "the head unit advertised ${service.mediaSinkService.availableType} for $stream"
            }

            val channel = AudioChannel.fromService(phoneConnection, service, route = route)
            val focus = AudioFocus(phoneConnection)
            val sink = EmulatedAudioSink.fromService(headUnitConnection, service, sinkConfig)

            val headUnitSide = async(Dispatchers.IO) { headUnit(sink) }
            val phoneSide = async(Dispatchers.IO) {
                phone(PhoneSide(phoneConnection, channel, focus, profile.services))
            }

            withTimeout(timeoutMillis) { phoneSide.await() }
            withTimeout(timeoutMillis) { headUnitSide.await() }
            AudioRun(sink, channel, focus)
        }
    }

    // --- synthetic audio ----------------------------------------------------

    private companion object {

        /** The three audio sinks the emulated unit advertises. */
        val AUDIO_CHANNELS = setOf(
            ChannelId.MEDIA_SINK_MEDIA_AUDIO,
            ChannelId.MEDIA_SINK_GUIDANCE_AUDIO,
            ChannelId.MEDIA_SINK_SYSTEM_AUDIO,
        )

        /** What [HeadUnitConfig] advertises for guidance and system audio. */
        val GUIDANCE_FORMAT = PcmFormat(16_000, 16, 1)

        /** What [HeadUnitConfig] advertises for media audio. */
        val MEDIA_FORMAT = PcmFormat(48_000, 16, 2)

        const val SESSION_ID = 1

        /** [AudioChannel.DEFAULT_BUFFER_MICROS]; repeated here so the arithmetic is visible. */
        const val BUFFER_MICROS = 20_000L

        /** Half a second — the length of a short navigation prompt. */
        const val PROMPT_MICROS = 500_000L
        const val PROMPT_BUFFERS = (PROMPT_MICROS / BUFFER_MICROS).toInt()

        /** A fifth of a second of "music", enough to fill several buffers. */
        const val MUSIC_MICROS = 200_000L
        const val MUSIC_BUFFERS = (MUSIC_MICROS / BUFFER_MICROS).toInt()

        /**
         * The music the A2DP link is carrying. Only its size matters — the
         * point is that none of it reaches the AAP media channel.
         */
        val MUSIC_BYTES: ByteArray = ByteArray(MEDIA_FORMAT.byteCountFor(MUSIC_MICROS))

        /**
         * A 440 Hz sine at [format]'s rate, as signed 16-bit little-endian
         * samples with every channel carrying the same waveform.
         *
         * Little-endian signed 16-bit is what the head unit expects: openauto's
         * Qt output declares `LittleEndian` + `SignedInt` at 16 bits
         * (`openauto/src/autoapp/Projection/QtAudioOutput.cpp` L36-L43) and its
         * RtAudio path opens the device as `RTAUDIO_SINT16`
         * (`RtAudioOutput.cpp` L67).
         *
         * A sine rather than random bytes because a byte-order or frame-size bug
         * turns it into audible noise rather than into a diff no one can read,
         * and because it is what a text-to-speech engine's output actually is:
         * a waveform, not a blob.
         */
        fun sineWave(
            format: PcmFormat,
            durationMicros: Long,
            frequencyHz: Double = 440.0,
            amplitude: Double = 0.5,
        ): ByteArray {
            require(format.bitsPerSample == 16) { "only 16-bit PCM is generated here" }
            val frames = (durationMicros * format.sampleRateHz / 1_000_000L).toInt()
            val out = ByteArray(frames * format.bytesPerFrame)

            var index = 0
            for (frame in 0 until frames) {
                val phase = 2.0 * PI * frequencyHz * frame / format.sampleRateHz
                val sample = (sin(phase) * amplitude * Short.MAX_VALUE).roundToInt().toShort()
                repeat(format.channelCount) {
                    out[index++] = (sample.toInt() and 0xFF).toByte()
                    out[index++] = ((sample.toInt() shr 8) and 0xFF).toByte()
                }
            }
            return out
        }
    }
}

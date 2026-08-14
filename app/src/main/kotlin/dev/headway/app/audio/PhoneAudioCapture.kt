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

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Process
import dev.headway.protocol.channel.PcmFormat

/**
 * Taps whatever the phone is playing, so it can be sent to the car.
 *
 * ## Why this exists at all, against the project spec
 *
 * `CLAUDE.md` says not to do this: *"do **not** attempt playback capture as the
 * primary path (`AudioPlaybackCapture` is opt-out-able and adds latency).
 * Default strategy: instruct/steer media audio over the car's normal Bluetooth
 * A2DP link, **which coexists with the AAP session**."*
 *
 * The coexistence premise is false on the target vehicle, and there is a capture
 * of real Android Auto against that exact head unit which says so in Google's
 * own words:
 *
 * ```text
 * W CAR.BT.SVC.LITE: disabling A2dp route while in projection
 * W CAR.BT.SVC.LITE: A2DP playing while in projection. Trying disabling
 *   ANDROID_AUTO_BLUETOOTH_A2DP_DISCONNECTED
 * ```
 *
 * A2DP is not a fallback while projecting — it is silence. The same capture
 * shows Gearhead doing precisely what this class does, one privilege level up:
 * `Registering audio policy`, then
 * `Will record from REMOTE_SUBMIX at full fixed volume`, then streaming a named
 * third-party player over the AAP media channel. `AudioPlaybackCapture` is the
 * unprivileged spelling of that.
 *
 * The two stated objections both survive contact and neither is fatal. Latency:
 * the same capture shows Google's privileged pipeline settling at 44-52 ms, so
 * the extra hop is inaudible for music. Opt-out: real, and handled by saying so
 * (see [describeCapturePolicy]) rather than by going mysteriously quiet.
 *
 * ## What can and cannot be captured
 *
 * Only `USAGE_MEDIA`, `USAGE_GAME` and `USAGE_UNKNOWN`, and only from apps whose
 * `android:allowAudioPlaybackCapture` is true — the default for anything
 * targeting API 29 or later, but explicitly disabled by some DRM-sensitive
 * players. Voice calls are never capturable at any privilege level, which is
 * correct and is also why telephony stays on Bluetooth HFP: the same real-car
 * capture shows Gearhead keeping HFP connected while it kills A2DP.
 *
 * ## Capture is a tap, not a redirect
 *
 * `AudioPlaybackCaptureConfiguration.Builder` offers matching and exclusion
 * filters and nothing else — no mute, no divert. The audio still goes wherever
 * it was going. While the car has taken over as the audio route that is
 * harmless, since the phone's own output has nowhere audible to go; if the
 * phone's speaker is the active route the driver will hear it twice, which is a
 * device-state question rather than something this class can fix.
 */
class PhoneAudioCapture(
    private val projection: MediaProjection,
    private val format: PcmFormat,
    private val onStep: (String) -> Unit = {},
) : AutoCloseable {

    private var record: AudioRecord? = null

    /** Bytes read off the tap. Diagnostics. */
    @Volatile
    var bytesCaptured: Long = 0L
        private set

    /** Reads that returned an error rather than audio. Diagnostics. */
    @Volatile
    var readErrors: Long = 0L
        private set

    /** Bytes one [read] can return; also the natural send granularity. */
    val bufferBytes: Int = bufferSizeFor(format)

    /**
     * Opens the tap.
     *
     * @return false when the platform refused, which is an ordinary outcome —
     *   a session with a working screen and no music is still worth having.
     */
    @SuppressLint("MissingPermission") // RECORD_AUDIO is in the manifest and checked by the caller.
    fun start(): Boolean {
        if (record != null) return true
        val configuration = AudioPlaybackCaptureConfiguration.Builder(projection)
            // The three usages the platform will ever hand over. Asking for
            // them explicitly rather than relying on the default documents what
            // Headway is and is not tapping — notably not voice calls.
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            // Without this, Headway's own spoken replies would be captured and
            // sent back to the car on the media channel underneath themselves.
            .excludeUid(Process.myUid())
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(format.sampleRateHz)
            .setChannelMask(channelMaskFor(format.channelCount))
            .build()

        val opened = runCatching {
            AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferBytes * BUFFERS_IN_FLIGHT)
                .setAudioPlaybackCaptureConfig(configuration)
                .build()
        }.getOrElse {
            onStep("media capture could not be opened: $it")
            return false
        }

        if (opened.state != AudioRecord.STATE_INITIALIZED) {
            onStep("media capture initialised in state ${opened.state}; no music will be sent")
            runCatching { opened.release() }
            return false
        }
        runCatching { opened.startRecording() }.onFailure {
            onStep("media capture would not start: $it")
            runCatching { opened.release() }
            return false
        }
        record = opened
        onStep(
            "media capture open at $format, $bufferBytes B per read " +
                "(~${format.durationMicros(bufferBytes) / 1000} ms)"
        )
        return true
    }

    /**
     * Fills [into] with captured audio.
     *
     * Blocking, which is what makes the caller's loop self-pacing: the tap
     * delivers at real time, so a coroutine that reads and forwards runs at
     * exactly the rate the music is playing without any timing code.
     *
     * @return bytes written, or -1 when the tap has failed or been closed.
     */
    fun read(into: ByteArray): Int {
        val active = record ?: return -1
        val read = active.read(into, 0, into.size)
        if (read < 0) {
            readErrors++
            // ERROR_INVALID_OPERATION arrives on a stopped record, which is the
            // ordinary shape of close() racing a read; the rest are real.
            if (read != AudioRecord.ERROR_INVALID_OPERATION) {
                onStep("media capture read failed with $read")
            }
            return -1
        }
        bytesCaptured += read
        return read
    }

    fun describe(): String =
        "media capture: ${bytesCaptured / 1024} KiB read, $readErrors read error(s)"

    override fun close() {
        val active = record ?: return
        record = null
        runCatching { active.stop() }
        runCatching { active.release() }
    }

    companion object {

        /**
         * Reads in flight inside `AudioRecord`.
         *
         * Four is enough that a scheduling hiccup on the sending coroutine does
         * not overrun the tap, and short enough that recovery after one is
         * immediate rather than playing a backlog.
         */
        const val BUFFERS_IN_FLIGHT = 4

        /** Audio per read. Matches the ~43 ms buffer real Android Auto uses at 48 kHz. */
        const val BUFFER_MICROS = 40_000L

        private fun channelMaskFor(channels: Int): Int = when (channels) {
            1 -> AudioFormat.CHANNEL_IN_MONO
            else -> AudioFormat.CHANNEL_IN_STEREO
        }

        /**
         * Bytes per read, never below the platform's own minimum.
         *
         * `getMinBufferSize` is the floor `AudioRecord` will accept; asking for
         * less fails to construct. The nominal figure is the one that matters
         * for latency, so it wins whenever it is the larger.
         */
        fun bufferSizeFor(format: PcmFormat): Int {
            val nominal = format.byteCountFor(BUFFER_MICROS).coerceAtLeast(format.bytesPerFrame)
            val minimum = runCatching {
                AudioRecord.getMinBufferSize(
                    format.sampleRateHz,
                    channelMaskFor(format.channelCount),
                    AudioFormat.ENCODING_PCM_16BIT,
                )
            }.getOrDefault(0)
            return maxOf(nominal, if (minimum > 0) minimum else 0)
        }

        /**
         * Whether anything is playing that would be worth capturing.
         *
         * Public, unprivileged, and the reason Headway does not hold the car's
         * audio focus for the whole drive: taking `AUDIO_FOCUS_GAIN` mutes the
         * car's own radio, so it must be taken when there is music to play and
         * given back when there is not.
         */
        fun isMusicPlaying(audio: AudioManager?): Boolean =
            runCatching { audio?.isMusicActive == true }.getOrDefault(false)

        /**
         * A plain-language note about what capture will and will not pick up.
         *
         * Logged once per session because the failure it describes is otherwise
         * indistinguishable from a Headway bug: a player that opts out produces
         * a perfectly healthy stream of digital silence.
         */
        fun describeCapturePolicy(): String =
            "media capture takes USAGE_MEDIA, GAME and UNKNOWN from apps that allow playback " +
                "capture — the default for apps targeting API 29+, but some players opt out " +
                "and those arrive as silence. Voice calls are never capturable and stay on " +
                "Bluetooth HFP."
    }
}

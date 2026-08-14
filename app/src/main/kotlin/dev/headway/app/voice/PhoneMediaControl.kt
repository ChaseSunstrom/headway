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

package dev.headway.app.voice

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import dev.headway.voice.MediaAction
import dev.headway.voice.VolumeDirection

/**
 * Carries out "pause", "next" and "volume up" on whatever app is playing.
 *
 * ## Why this is a media *key*, not a `MediaController`
 *
 * The obvious API is `MediaSessionManager.getActiveSessions`, which hands back a
 * `MediaController` per playing app and lets you call `transportControls.pause()`
 * directly. It is also the wrong one here: that method requires the caller to be
 * an enabled notification listener, and it throws `SecurityException` otherwise.
 * Headway asks for the accessibility grant already, and asking a driver for a
 * second, scarier-sounding grant ("Headway wants to read all your
 * notifications") to make the word "pause" work is a bad trade.
 *
 * `AudioManager.dispatchMediaKeyEvent` needs no permission at all. The platform
 * routes the key to the same place a Bluetooth headset's pause button goes — the
 * media session that most recently held audio focus — which is exactly the app
 * the driver means. The cost is that it is fire-and-forget: nothing reports which
 * app received the key, or whether any did.
 *
 * That trade is stated here rather than hidden because it is the reason the log
 * says "sent" and never "paused".
 *
 * ## Why each key is a down *and* an up
 *
 * `KeyEvent.ACTION_DOWN` alone is a key held forever. `MediaSession` implementations
 * commonly act on the up, or use the interval between the two to tell a tap from
 * a long press (a long PLAY_PAUSE is "start assistant" on many apps). Sending
 * only the down half therefore does nothing at all in some apps and something
 * unintended in others. Both halves carry the same `downTime`, so the interval
 * reads as an instantaneous tap.
 */
class PhoneMediaControl(
    context: Context,
    private val onStep: (String) -> Unit = {},
) {

    private val audio: AudioManager? =
        context.applicationContext.getSystemService(AudioManager::class.java)

    /**
     * Sends the media key for [action].
     *
     * @return false only when this device has no `AudioManager` or the platform
     *   threw. A true means the key was dispatched, not that anything played:
     *   see the class KDoc.
     */
    fun perform(action: MediaAction): Boolean {
        val keyCode = when (action) {
            MediaAction.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaAction.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaAction.PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            MediaAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            MediaAction.STOP -> KeyEvent.KEYCODE_MEDIA_STOP
        }
        return dispatch(keyCode, action.name)
    }

    /**
     * Raises, lowers or mutes the music stream.
     *
     * `STREAM_MUSIC` rather than the car's own volume: Headway has no protocol
     * message for a head unit's amplifier, and the media the driver is asking
     * about is playing from the phone over A2DP, where the phone's music volume
     * is the one that applies (CLAUDE.md routes third-party media to A2DP by
     * default).
     *
     * `ADJUST_TOGGLE_MUTE` rather than `ADJUST_MUTE`, so a second "mute" unmutes
     * — which is what a driver who has said it twice means, and the only way to
     * recover by voice from having said it once.
     */
    fun adjust(direction: VolumeDirection, steps: Int): Boolean {
        val manager = audio ?: run {
            onStep("voice: this device has no AudioManager, so volume cannot be changed")
            return false
        }
        val adjustment = when (direction) {
            VolumeDirection.UP -> AudioManager.ADJUST_RAISE
            VolumeDirection.DOWN -> AudioManager.ADJUST_LOWER
            VolumeDirection.MUTE -> AudioManager.ADJUST_TOGGLE_MUTE
        }
        // One call per step. adjustStreamVolume moves exactly one index, and
        // setStreamVolume -- which could jump -- is a different thing: it ignores
        // the device's own volume curve and takes an absolute index that "volume
        // up twice" has no way to compute.
        val repeats = if (direction == VolumeDirection.MUTE) 1 else steps.coerceIn(1, MAX_STEPS)
        return runCatching {
            repeat(repeats) {
                manager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    adjustment,
                    // Shows the phone's volume panel. Harmless while mirroring
                    // -- it is feedback the driver can see on the car screen --
                    // and the only confirmation available that anything happened.
                    AudioManager.FLAG_SHOW_UI,
                )
            }
            onStep("voice: volume ${direction.name.lowercase()} x$repeats on the music stream")
            true
        }.getOrElse {
            onStep("voice: the platform refused the volume change ($it)")
            false
        }
    }

    private fun dispatch(keyCode: Int, name: String): Boolean {
        val manager = audio ?: run {
            onStep("voice: this device has no AudioManager, so $name cannot be sent")
            return false
        }
        val downTime = SystemClock.uptimeMillis()
        return runCatching {
            manager.dispatchMediaKeyEvent(
                KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0)
            )
            manager.dispatchMediaKeyEvent(
                KeyEvent(downTime, downTime, KeyEvent.ACTION_UP, keyCode, 0)
            )
            onStep(
                "voice: sent $name to whichever app holds the media session. Nothing reports " +
                    "back, so this says sent rather than done"
            )
            true
        }.getOrElse {
            onStep("voice: the platform refused $name ($it)")
            false
        }
    }

    private companion object {
        /**
         * Most steps one spoken command may move the volume.
         *
         * A misheard number must not be able to take the cabin from quiet to
         * full; five is more than any phrase in the grammar produces and well
         * short of the ~15 indices a typical device has.
         */
        const val MAX_STEPS = 5
    }
}

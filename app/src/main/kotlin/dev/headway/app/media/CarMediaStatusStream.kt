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
 */

package dev.headway.app.media

import aap_protobuf.service.ServiceOuterClass
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import dev.headway.app.dash.tiles.NowPlayingTile
import dev.headway.app.log.SessionLog
import dev.headway.protocol.channel.MediaPlaybackChannel
import dev.headway.protocol.io.MessageChannel
import dev.headway.protocol.session.HeadUnitProfile

private const val TAG = "HeadwayMediaStatus"

/**
 * Tells the car what is playing, so its own buttons have something to act on.
 *
 * ## The fault this exists to fix
 *
 * A driver reported that the physical skip buttons on their steering wheel and
 * head unit answered **"Action unavailable"**, while Headway's own on-screen
 * transport worked on the same track. That string is not Headway's — it appears
 * nowhere in this repository — and it cannot be a reply to a key, because the
 * input channel has no per-event acknowledgement: its four message ids carry
 * reports, a key-binding request and its response, and nothing that says "the
 * phone did or did not act on that". The head unit is not reporting a refusal.
 * It is deciding, from its own state, not to send the key at all.
 *
 * The state it decides from is this channel. The target vehicle advertises
 * `MEDIA_PLAYBACK_STATUS` — it is in the real 2021 Chevrolet Infotainment 3
 * service list in `docs/protocol-notes.md` — and `AapSession.connect` opens
 * every advertised service, so the channel has been open and silent for the
 * whole life of this project. A head unit told nothing about any media has
 * nothing to skip.
 *
 * ## Where the content comes from
 *
 * The same `MediaController` the now-playing pane draws, chosen by the same
 * rule, so the car's dashboard and the car's screen never disagree about what
 * is playing. That needs notification access; without it this stream simply
 * does not start and says so once, because a car that behaves exactly as it did
 * before is the correct outcome of a grant nobody gave.
 *
 * ## Why the position is pushed on a timer
 *
 * `PlaybackState.getPosition()` is a snapshot taken at
 * `getLastPositionUpdateTime()`, and a session that is playing normally emits
 * no callback between tracks — so a purely event-driven stream would leave a
 * head unit's elapsed time frozen at whatever it was when the track started.
 * The tick is slow (one second) because a dashboard shows whole seconds.
 */
class CarMediaStatusStream(
    private val context: Context,
    private val connection: MessageChannel,
    private val channelId: Int,
    private val onStep: (String) -> Unit = {},
) {

    private val appContext: Context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private var running = false
    private var controller: MediaController? = null
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null

    /** What was last put on the wire, so an unchanged track is not resent. */
    private var lastMetadata: MediaPlaybackChannel.Metadata? = null
    private var lastState: MediaPlaybackChannel.Playback? = null

    private var sent = 0

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()

        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()

        override fun onSessionDestroyed() = rebind()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            publishStatus()
            main.postDelayed(this, TICK_MILLIS)
        }
    }

    fun start() {
        if (running) return
        val manager = appContext.getSystemService(MediaSessionManager::class.java)
        if (manager == null) {
            onStep("media status: no MediaSessionManager, so the car is told nothing")
            return
        }
        val listener = MediaSessionManager.OnActiveSessionsChangedListener { rebind() }
        val registered = runCatching {
            manager.addOnActiveSessionsChangedListener(
                listener,
                NowPlayingTile.listenerComponent(appContext),
                main,
            )
        }
        if (registered.isFailure) {
            // Expected, and not a fault: it is what "notification access has
            // not been granted" looks like. Said once, at info, because a
            // first-run log should not read like a failure.
            SessionLog.shared.info(
                TAG,
                "notification access is not granted, so the car cannot be told what is playing " +
                    "-- its own transport buttons will report the action as unavailable",
            )
            return
        }
        running = true
        sessionsListener = listener
        rebind()
        main.postDelayed(tick, TICK_MILLIS)
    }

    fun stop() {
        if (!running) return
        running = false
        main.removeCallbacks(tick)
        sessionsListener?.let { listener ->
            runCatching {
                appContext.getSystemService(MediaSessionManager::class.java)
                    ?.removeOnActiveSessionsChangedListener(listener)
            }
        }
        sessionsListener = null
        bind(null)
        // One last frame, so a head unit does not keep offering transport for a
        // session that has gone with the drive.
        runCatching { send(MediaPlaybackChannel.Playback.STOPPED, null) }
    }

    fun describe(): String {
        val open = controller ?: return "media status: nothing bound, $sent frame(s) sent"
        return "media status: ${open.packageName}, ${lastState?.name?.lowercase() ?: "unknown"}, " +
            "$sent frame(s) sent"
    }

    // --- session plumbing ------------------------------------------------------

    private fun rebind() {
        if (!running) return
        val manager = appContext.getSystemService(MediaSessionManager::class.java)
        val sessions = runCatching {
            manager?.getActiveSessions(NowPlayingTile.listenerComponent(appContext))
        }.getOrNull().orEmpty()
        bind(
            sessions.firstOrNull { isActive(it.playbackState?.state) } ?: sessions.firstOrNull(),
        )
        publish()
    }

    private fun bind(next: MediaController?) {
        if (controller === next) return
        runCatching { controller?.unregisterCallback(callback) }
        controller = next
        next?.let { runCatching { it.registerCallback(callback, main) } }
    }

    // --- the wire --------------------------------------------------------------

    private fun publish() {
        val open = controller
        val metadata = metadataOf(open)
        if (metadata != lastMetadata) {
            lastMetadata = metadata
            runCatching {
                connection.send(MediaPlaybackChannel.metadata(channelId, metadata))
                sent++
            }.onFailure { SessionLog.shared.warn(TAG, "could not send metadata: $it") }
        }
        publishStatus()
    }

    private fun publishStatus() {
        val open = controller
        val state = when {
            open == null -> MediaPlaybackChannel.Playback.STOPPED
            isActive(open.playbackState?.state) -> MediaPlaybackChannel.Playback.PLAYING
            open.playbackState?.state == PlaybackState.STATE_STOPPED ->
                MediaPlaybackChannel.Playback.STOPPED
            else -> MediaPlaybackChannel.Playback.PAUSED
        }
        send(state, open)
    }

    private fun send(state: MediaPlaybackChannel.Playback, open: MediaController?) {
        val position = positionSeconds(open)
        val status = MediaPlaybackChannel.Status(
            state = state,
            source = open?.packageName?.let { appLabel(it) },
            positionSeconds = position,
        )
        runCatching {
            connection.send(MediaPlaybackChannel.status(channelId, status))
            sent++
        }.onFailure { SessionLog.shared.warn(TAG, "could not send playback status: $it") }
        if (lastState != state) {
            lastState = state
            onStep("media status: told the car ${state.name.lowercase()}")
        }
    }

    /**
     * How far into the track, extrapolated.
     *
     * `getPosition()` is a snapshot taken at `getLastPositionUpdateTime()`, so
     * the honest current value is that plus the elapsed wall time times the
     * playback speed. Reading it raw would leave a dashboard's elapsed time
     * frozen wherever the last callback happened to land.
     */
    private fun positionSeconds(open: MediaController?): Int {
        val state = open?.playbackState ?: return -1
        val base = state.position
        if (base < 0) return -1
        val since = state.lastPositionUpdateTime
        val elapsed = if (since > 0 && isActive(state.state)) {
            ((android.os.SystemClock.elapsedRealtime() - since) * state.playbackSpeed).toLong()
        } else {
            0L
        }
        return ((base + elapsed) / 1000L).coerceAtLeast(0L).toInt()
    }

    private fun metadataOf(open: MediaController?): MediaPlaybackChannel.Metadata {
        val metadata = open?.metadata ?: return MediaPlaybackChannel.Metadata()
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        return MediaPlaybackChannel.Metadata(
            song = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
            durationSeconds = if (duration > 0) (duration / 1000L).toInt() else -1,
        )
    }

    private fun appLabel(packageName: String): String = runCatching {
        val packages = appContext.packageManager
        packages.getApplicationLabel(
            packages.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L)),
        ).toString()
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: packageName

    private fun isActive(state: Int?): Boolean =
        state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING

    companion object {

        /**
         * How often the elapsed position is pushed.
         *
         * A dashboard shows whole seconds, so anything faster is bytes on a
         * link that is also carrying video for no visible gain.
         */
        const val TICK_MILLIS: Long = 1_000L

        /** The advertised service, or null when the head unit offers none. */
        fun serviceOf(profile: HeadUnitProfile): ServiceOuterClass.Service? =
            profile.services.firstOrNull { it.hasMediaPlaybackService() }

        /** The stream for [profile], or null when the car does not offer the channel. */
        fun of(
            profile: HeadUnitProfile,
            connectionFor: (Int) -> MessageChannel,
            context: Context,
            onStep: (String) -> Unit = {},
        ): CarMediaStatusStream? {
            val service = serviceOf(profile) ?: return null
            return CarMediaStatusStream(
                context = context,
                connection = connectionFor(service.id),
                channelId = service.id,
                onStep = onStep,
            )
        }
    }
}

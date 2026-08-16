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
import java.io.EOFException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

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

    /**
     * Frames waiting to go out.
     *
     * `MessageChannel.send` suspends and every producer here is a
     * `MediaController.Callback` or a `Handler` tick on the main looper, which
     * is not a coroutine and must not block. A conflated queue drained by one
     * coroutine is the whole of the bridge -- and conflated on purpose: if the
     * link is slow, the *newest* status is the one worth sending, not a backlog
     * of stale positions arriving a second apart.
     */
    private val outgoing = Channel<dev.headway.protocol.framing.AapMessage>(Channel.CONFLATED)

    private var pump: kotlinx.coroutines.Job? = null

    /** Drains the channel so a head-unit frame is never silently evicted. */
    private var reader: kotlinx.coroutines.Job? = null
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

    fun start(scope: CoroutineScope) {
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
        // One writer, off the main thread. Started before the first rebind so
        // no frame produced during bring-up is dropped on the floor.
        pump = scope.launch {
            for (message in outgoing) {
                runCatching { connection.send(message) }
                    .onFailure { SessionLog.shared.warn(TAG, "could not send a frame: $it") }
            }
        }
        reader = scope.launch { readFromCar() }
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
        // session that has gone with the drive. Sent before the pump is
        // cancelled, and conflated like every other -- if the link is already
        // gone it simply never leaves, which is the same outcome as not trying.
        runCatching { send(MediaPlaybackChannel.Playback.STOPPED, null) }
        pump?.cancel()
        pump = null
        reader?.cancel()
        reader = null
        outgoing.close()
    }

    fun describe(): String {
        // Always stated, including the zero. "The car sent nothing on this
        // channel" is the finding when a skip button did nothing, and a line
        // that only appears when the count is non-zero cannot report it.
        val heard = ", $framesRead frame(s) received from the car"
        val open = controller
            ?: return "media status: nothing bound, $sent frame(s) sent$heard"
        return "media status: ${appLabel(open.packageName)}, " +
            "${lastState?.name?.lowercase() ?: "unknown"}, $sent frame(s) sent$heard"
    }

    /**
     * Reads the channel, so that what the head unit sends here is not thrown away.
     *
     * ## Why this exists even though nothing acts on the result
     *
     * This stream used to only write. The channel's queue still existed --
     * `ChannelDemultiplexer` makes one per channel Headway opens -- so anything
     * the head unit put on it went into a buffer nobody drained, was evicted
     * when the buffer filled, and was counted in a `droppedMessages` field that
     * was reported nowhere. A frame arriving here left no trace at all.
     *
     * That matters for one open question in particular. A driver reports that
     * the car's physical skip buttons do nothing, and the input channel shows
     * the head unit binding all six keycodes it advertised and then sending
     * none of them. `MEDIA_PLAYBACK_INPUT` (32770) is the other way a head unit
     * could deliver those presses -- `docs/protocol-notes.md` records it as
     * "HU -> phone, instrument-cluster input" -- and with this channel unread,
     * a log could not distinguish "the car sent nothing" from "Headway dropped
     * it". Now it can.
     *
     * ## Why it logs rather than acts
     *
     * No reference this project has read decodes `MediaPlaybackInput`, and
     * there is no `.proto` for it in the schema tree -- only the message id.
     * CLAUDE.md forbids guessing a protocol constant, and a wrong guess here
     * would be a skip button that does something other than skip. So the bytes
     * go in the log, and a single drive with a button press in it is enough to
     * write the decoder from.
     */
    private suspend fun readFromCar() {
        while (currentCoroutineContext().isActive) {
            val message = try {
                connection.receive()
            } catch (closed: EOFException) {
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                SessionLog.shared.warn(TAG, "media status: could not read the channel: $failed")
                return
            }
            val name = when (message.messageId) {
                MediaPlaybackChannel.MediaPlaybackMessageId.MEDIA_PLAYBACK_INPUT ->
                    "MEDIA_PLAYBACK_INPUT"
                else -> "unnamed"
            }
            // Bounded, because this is a diagnostic and not a firehose: a head
            // unit that chatters here must not be able to fill a log export.
            if (framesRead < MAX_LOGGED_INBOUND) {
                onStep(
                    "media status: the car sent $name (0x%04x), %d byte(s): %s"
                        .format(message.messageId, message.payload.size, hex(message.payload))
                )
            }
            framesRead++
        }
    }

    /** Head-unit frames seen on this channel; reported by [describe]. */
    private var framesRead: Long = 0L

    private fun hex(bytes: ByteArray): String =
        bytes.take(MAX_LOGGED_BYTES).joinToString(" ") { "%02x".format(it) } +
            if (bytes.size > MAX_LOGGED_BYTES) " ..." else ""

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
            offer(MediaPlaybackChannel.metadata(channelId, metadata))
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
        offer(MediaPlaybackChannel.status(channelId, status))
        if (lastState != state) {
            lastState = state
            onStep("media status: told the car ${state.name.lowercase()}")
        }
    }

    /** Queues a frame from whatever thread produced it. Never blocks. */
    private fun offer(message: dev.headway.protocol.framing.AapMessage) {
        if (outgoing.trySend(message).isSuccess) sent++
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

    /**
     * Display names by package, because this is read from the tick.
     *
     * `getApplicationInfo` is a binder round trip into the package manager, and
     * the status push runs once a second on the main looper -- the same thread
     * that draws the car screen. An app's label does not change while it is
     * installed, so asking the system every second was a round trip per second
     * for an answer that was already known.
     */
    private val labels = mutableMapOf<String, String>()

    private fun appLabel(packageName: String): String =
        labels.getOrPut(packageName) { readAppLabel(packageName) }

    private fun readAppLabel(packageName: String): String = runCatching {
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

        /** Inbound frames logged in full before the channel goes quiet in the log. */
        private const val MAX_LOGGED_INBOUND = 20L

        /** Bytes of an inbound frame shown; these payloads are small. */
        private const val MAX_LOGGED_BYTES = 64

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

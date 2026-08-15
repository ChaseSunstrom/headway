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

package dev.headway.app.sensor

import aap_protobuf.service.ServiceOuterClass
import dev.headway.protocol.channel.CarSensors
import dev.headway.protocol.channel.SensorChannel
import dev.headway.protocol.channel.SensorChannelException
import dev.headway.protocol.channel.SensorChannelMessage
import dev.headway.protocol.io.MessageChannel
import dev.headway.protocol.session.HeadUnitProfile
import java.io.EOFException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Everything between "the car reported a reading" and "the dashboard shows it".
 *
 * ## The chain, and where each link already lives
 *
 * ```text
 *   SensorBatch on the wire
 *     -> SensorChannel.handle       decode: scaled integers -> CarSensors
 *     -> CarSensors.mergedWith      a batch is a delta; keep what it omitted
 *     -> CarSensorStream.publish    -> SensorsTile, and anything else observing
 * ```
 *
 * Like [dev.headway.app.input.CarInputStream] this class is only the wiring: the
 * decoding and the merging are `core-protocol`'s and are tested there. What it
 * owns is the one decision the protocol layer cannot make — *who gets told* —
 * and the guarantee that nothing on this channel can cost the driver anything
 * else.
 *
 * ## Reading: handle(), not receiveMessage()
 *
 * [SensorChannel] offers both, and its KDoc is explicit that `receiveMessage()`
 * and `sensors()` read the connection directly and are only correct when nothing
 * else is reading it. In a live session the
 * [dev.headway.protocol.io.ChannelDemultiplexer] owns the socket, so this class
 * reads its *view* of the sensor channel and hands each message to
 * [SensorChannel.handle]. The two are not equivalent even though a view only
 * ever yields messages for one channel: `handle()` throws if a message for
 * another channel reaches it, which turns a demultiplexer routing bug into a
 * loud failure instead of a message silently swallowed by a filter loop.
 *
 * ## Why the state is static
 *
 * Same reason [dev.headway.app.nav.NavigationFeed] and
 * [dev.headway.app.phone.CarPhone] are: a tile is built by `CarShell` deep
 * inside video bring-up and has no handle on the session, and the session has no
 * handle on a tile that may not exist yet. So the stream publishes into
 * companion state and tiles observe it.
 *
 * The feed is cleared by [stop], which matters more here than for the other
 * feeds: every other pane draws something the phone knows and stays true with
 * the car unplugged, while a speed left on the car screen after the link dropped
 * is a number that is actively wrong.
 *
 * ## Sensors must never take the session down
 *
 * The session is worth more than every gauge on it. A malformed batch is logged
 * and the next one is read; a channel that closes ends the reader and nothing
 * else; and `HeadwayService.startSubsystem` catches anything that escapes
 * [start]. A driver whose fuel gauge is blank still has a car screen.
 */
class CarSensorStream(
    private val channel: SensorChannel,
    /**
     * The demultiplexer's view of the same channel. Held separately from
     * [channel] because the reading strategy above needs the raw message before
     * [SensorChannel.handle] decodes it.
     */
    private val view: MessageChannel,
    private val onStep: (String) -> Unit = {},
) {

    /**
     * Told whenever the car's readings change.
     *
     * Declared on the class rather than inside [Companion], because a classifier
     * nested in a companion object resolves as `CarSensorStream.Companion.Listener`
     * and every call site would have to say so.
     */
    fun interface Listener {
        fun onSensorsChanged(sensors: CarSensors)
    }

    private val jobs: MutableList<Job> = mutableListOf()

    /**
     * Subscribes to every advertised sensor, then reads until [scope] is
     * cancelled or the head unit goes away.
     *
     * @return true when at least one sensor was subscribed to. False is not an
     *   error: a head unit that advertises a sensor service with nothing in it
     *   is a car with nothing to say, and the tile renders that.
     */
    suspend fun start(scope: CoroutineScope): Boolean {
        val subscribed = channel.subscribe()
        // Publish immediately, so a tile built before the first batch shows an
        // honest empty state rather than whatever the last session left behind.
        publish(channel.latest)
        jobs += scope.launch { read() }
        onStep("sensor stream started")
        return subscribed > 0
    }

    /** What arrived and what the car reports, for the log. */
    fun describe(): String = channel.describe()

    /** Stops reading and takes the readings off the dashboard. */
    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        // Cleared rather than left stale: a speed from the last session, sitting
        // on the car screen after the link dropped, is worse than a blank one.
        publish(CarSensors.UNKNOWN)
    }

    private suspend fun read() {
        try {
            while (currentCoroutineContext().isActive) {
                val message = view.receive()
                try {
                    val decoded = channel.handle(message)
                    if (decoded is SensorChannelMessage.Batch) publish(decoded.accumulated)
                } catch (e: SensorChannelException) {
                    // One malformed batch is not a reason to stop reading: the
                    // next one is very likely fine, and there is nothing to
                    // resynchronise — each batch stands alone.
                    onStep("sensors: ${e.message}")
                } catch (e: RuntimeException) {
                    // Nothing inside that block suspends, so this cannot be a
                    // coroutine cancellation being swallowed.
                    onStep("sensors: ${e.javaClass.simpleName} handling a batch: ${e.message}")
                }
            }
        } catch (e: EOFException) {
            // The ordinary end of a session. The supervisor reconnects; this is
            // not an error and must not be reported as one.
            onStep("sensors: channel closed (${e.message})")
        }
    }

    companion object {

        /**
         * Copy-on-write because the session's reader publishes from a coroutine
         * on the IO dispatcher while tiles register and unregister from the main
         * thread, and registration is rare while publication is not.
         */
        private val listeners = CopyOnWriteArrayList<Listener>()

        @Volatile
        private var current: CarSensors = CarSensors.UNKNOWN

        /** Everything the car has reported this session. Empty between sessions. */
        val latest: CarSensors get() = current

        /**
         * Registers [listener] and immediately hands it the current readings.
         *
         * The immediate call matters: a tile started after the first batch would
         * otherwise show nothing until the car next says something, which for a
         * parked car with the engine off is never.
         */
        fun observe(listener: Listener) {
            listeners.addIfAbsent(listener)
            listener.onSensorsChanged(current)
        }

        /** Removal is by identity, so callers must pass the same instance. */
        fun unobserve(listener: Listener) {
            listeners.remove(listener)
        }

        private fun publish(sensors: CarSensors) {
            current = sensors
            listeners.forEach {
                // One tile throwing must not stop the others being updated.
                runCatching { it.onSensorsChanged(sensors) }
            }
        }

        /**
         * Finds the head unit's sensor service by content, not by channel id.
         *
         * The advertisement is the authority — `ChannelId.SENSOR` is Headway's
         * own convention and a real unit numbers its channels how it likes — and
         * what identifies this one is simply that `Service.sensor_source_service`
         * is set. It is field 2 of `Service`, and the head unit is the side that
         * sends `Service` entries, so the car is the sensor source and Headway is
         * the subscriber.
         */
        fun sensorServiceOf(profile: HeadUnitProfile): ServiceOuterClass.Service? =
            SensorChannel.sensorServiceOf(profile.services)

        /**
         * Builds the stream for a profile, or null when the car advertises no
         * sensor service.
         *
         * Null is the ordinary answer for a head unit that has no sensors to
         * offer, not a failure: everything else in the session comes up exactly
         * as it would have.
         */
        fun of(
            profile: HeadUnitProfile,
            connectionFor: (Int) -> MessageChannel,
            /** See `HeadUnitQuirks.odometerScale`. */
            odometerScale: Int = CarSensors.ODOMETER_SCALE_E1,
            onStep: (String) -> Unit = {},
        ): CarSensorStream? {
            val service = sensorServiceOf(profile) ?: return null
            // The view is taken once, here, and handed to the channel — this
            // class needs the *same* one, because it reads raw messages from it
            // and hands each to `handle()`. `SensorChannel.of` reads the
            // advertisement and logs it, so the constant lambda below is only
            // saying "you already have your connection".
            val view = connectionFor(service.id)
            val channel = SensorChannel.of(
                services = profile.services,
                connectionFor = { view },
                odometerScale = odometerScale,
                onStep = onStep,
            ) ?: return null
            return CarSensorStream(channel = channel, view = view, onStep = onStep)
        }
    }
}

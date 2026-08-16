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

import aap_protobuf.service.sensorsource.message.SensorTypeOuterClass.SensorType
import dev.headway.protocol.channel.CarSensors
import dev.headway.protocol.channel.SensorChannel
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **The sensor channel, end to end over the fake transport.**
 *
 * The car's own sensors are not one of CLAUDE.md's six phases — they arrive with
 * the dashboard rather than with a phase gate — so this is an acceptance test in
 * the same shape as [Phase4AudioAcceptanceTest] rather than a phase's criterion.
 * What it accepts is the sentence the feature is worth: **a head unit that
 * advertises sensors ends up driving a dashboard that shows them.**
 *
 * The whole bring-up runs first — real version handshake, real TLS, real service
 * discovery, real channel open over the loopback transport — so the channel id,
 * the sensor list and the subscription all come from the advertisement rather
 * than from a constant written twice.
 *
 * ## Evidence tier
 *
 * **Tier A for the wire sequence, Tier D for the car** (`PROGRESS.md`).
 *
 * Tier A — executed on real bytes: real `SensorRequest`s go out encrypted on the
 * advertised channel, real `SensorResponse`s come back, real `SensorBatch`es are
 * serialised by the emulated unit and parsed by the phone, and the scaled
 * integers are converted by the code the app uses.
 *
 * Tier D — not provable here, and not claimed below:
 *
 * - **which sensors a real 2021 Chevrolet Infotainment 3 unit offers.** It
 *   advertises a SENSOR service (`docs/protocol-notes.md` §7.1, capture
 *   2026-08-13) and the capture does not enumerate the `sensors` inside it. The
 *   list this emulator offers is a plausible one, not an observed one;
 * - **what `FuelData.fuel_level` and `FuelData.range` mean.** No reference
 *   states a unit for either (BLOCKERS.md B-022), so the assertions below check
 *   that the numbers arrive unaltered and nothing more;
 * - **that a real unit answers each request separately, or at all.** Per ADR
 *   0002 the emulator shares `core-protocol` with the phone, so a green run is
 *   self-consistency. The emulator does model openauto's answer-then-stream
 *   order, which is the one part any reference pins down
 *   (`openauto/src/autoapp/Service/Sensor/SensorService.cpp` L122-L157).
 */
class SensorChannelAcceptanceTest {

    // --- the acceptance criterion -------------------------------------------

    @Test
    fun `the car's advertised sensors reach the phone as readings a dashboard can draw`() {
        val run = sensorSession(
            phone = { channel ->
                // Exactly what was advertised, one request each. Asking for a
                // sensor the unit never offered is a way to be lied to: openauto
                // answers STATUS_SUCCESS to any type and then streams nothing.
                assertEquals(SENSORS.size, channel.subscribe())
                channel.sensors().take(3).toList()
            },
            headUnit = { sensors ->
                sensors.answerRequests(SENSORS.size)

                // Parked, with the engine running and the trip computer awake.
                sensors.sendReadings(
                    speedE3 = 0,
                    rpmE3 = 700_000,
                    fuelLevel = 45,
                    range = 380,
                    lowFuelWarning = false,
                    tyrePressuresE2 = listOf(22_100, 22_000, 21_900, 22_300),
                    temperatureE3 = 18_500,
                    kmsE1 = 2_000_000,
                    parkingBrake = true,
                    nightMode = false,
                )
                // Moving. A real batch carries only what changed, which is the
                // shape that catches a phone treating each batch as a snapshot.
                sensors.sendReadings(speedE3 = 27_778, rpmE3 = 2_100_000, parkingBrake = false)
                // And the light goes off, alone.
                sensors.sendReadings(nightMode = true)
            },
        )

        // Every advertised sensor was subscribed to, in advertisement order.
        assertEquals(SENSORS, run.requestedByCar)

        val (parked, moving, dark) = run.emissions
        assertEquals(0.0, parked.speedKph!!, 0.0001, "a reported zero is data, not absence")
        assertEquals(700.0, parked.rpm!!, 0.0001)
        assertEquals(45, parked.fuelLevel)
        assertEquals(380, parked.range)
        assertEquals(false, parked.lowFuel)
        assertEquals(listOf(221.0, 220.0, 219.0, 223.0), parked.tyrePressuresKpa)
        assertEquals(18.5, parked.outsideTemperatureCelsius!!, 0.0001)
        assertEquals(200_000.0, parked.odometerKm!!, 0.0001)
        assertEquals(true, parked.parkingBrake)
        assertEquals(false, parked.nightMode)

        // 100 km/h, and nothing the second batch omitted was forgotten. This is
        // the assertion the whole accumulate-with-mergedWith design exists for.
        assertEquals(100.0, moving.speedKph!!, 0.01)
        assertEquals(2100.0, moving.rpm!!, 0.0001)
        assertEquals(false, moving.parkingBrake)
        assertEquals(45, moving.fuelLevel, "a speed-only batch must not blank the fuel gauge")
        assertEquals(listOf(221.0, 220.0, 219.0, 223.0), moving.tyrePressuresKpa)
        assertEquals(200_000.0, moving.odometerKm!!, 0.0001)

        assertEquals(true, dark.nightMode)
        assertEquals(100.0, dark.speedKph!!, 0.01)

        // The channel's own accounting, which is what a session log carries.
        assertEquals(SENSORS.size.toLong(), run.channel.requestsSent)
        assertEquals(SENSORS.size.toLong(), run.channel.responsesReceived)
        assertEquals(0L, run.channel.sensorsRefused)
        assertEquals(3L, run.channel.batchesReceived)
        assertEquals(0L, run.channel.batchesWithNothingKnown)
        assertEquals(dark, run.channel.latest)
        assertTrue(run.channel.describe().contains("100.0 km/h"), run.channel.describe())
    }

    // --- the states that are not errors --------------------------------------

    /**
     * A head unit that advertises a sensor and then refuses to start it.
     *
     * `MessageStatus.STATUS_INVALID_SENSOR` (-9) exists for exactly this and no
     * reference ever sends it — openauto answers `STATUS_SUCCESS` to any type
     * whatsoever and simply never streams it (`SensorService.cpp` L127-L143). A
     * phone must survive both, and the driver must lose one gauge rather than the
     * session.
     */
    @Test
    fun `a refused sensor costs one gauge, not the channel`() {
        val run = sensorSession(
            // The unit advertises the fuel sensor and then refuses every request
            // for a type it does not really have.
            sensors = listOf(SensorType.SENSOR_SPEED, SensorType.SENSOR_TOLL_CARD),
            reallyHas = listOf(SensorType.SENSOR_SPEED),
            phone = { channel ->
                channel.subscribe()
                channel.sensors().take(1).toList()
            },
            headUnit = { sensors ->
                sensors.answerRequests(2)
                sensors.sendReadings(speedE3 = 13_889)
            },
        )

        assertEquals(1L, run.channel.sensorsRefused)
        assertEquals(50.0, run.emissions.single().speedKph!!, 0.01)
        assertNull(run.emissions.single().fuelLevel, "the refused sensor simply never reports")
    }

    /**
     * A car that says nothing at all.
     *
     * "Not reported" is the normal state of most of these fields on most cars,
     * and the dashboard has to render it as a fact rather than as a fault.
     */
    @Test
    fun `a silent car leaves the readings unreported rather than zeroed`() {
        val run = sensorSession(
            phone = { channel ->
                channel.subscribe()
                // One batch, carrying a sensor CarSensors does not model.
                channel.sensors().take(1).toList()
            },
            headUnit = { sensors ->
                sensors.answerRequests(SENSORS.size)
                sensors.sendReadings()
            },
        )

        val nothing = run.emissions.single()
        assertFalse(nothing.any)
        assertNull(nothing.speedKph)
        assertNull(nothing.fuelLevel)
        assertNull(nothing.restricted)
        assertEquals(CarSensors.UNKNOWN, nothing)
        assertEquals("sensors: nothing reported", nothing.describe())
        assertEquals(1L, run.channel.batchesWithNothingKnown)
    }

    // --- harness -------------------------------------------------------------

    private class SensorRun(
        val channel: SensorChannel,
        val emissions: List<CarSensors>,
        val requestedByCar: List<SensorType>,
    )

    /**
     * Brings a session up the way Phase 1 does, then runs [phone] against
     * [EmulatedSensorSource] on the channel the head unit advertised for its
     * sensor service.
     *
     * The phone reads [FramedConnection] directly rather than a demultiplexer
     * view: only one channel is open, so there is exactly one reader, which is
     * the condition [SensorChannel] documents for `sensors()`. The app wires the
     * demultiplexer in; see `CarSensorStream`.
     */
    private fun sensorSession(
        sensors: List<SensorType> = SENSORS,
        reallyHas: List<SensorType> = sensors,
        timeoutMillis: Long = 60_000,
        phone: suspend (SensorChannel) -> List<CarSensors>,
        headUnit: suspend (EmulatedSensorSource) -> Unit,
    ): SensorRun = runBlocking {
        LoopbackTransport.pair().use { pair ->
            val phoneConnection = FramedConnection(pair.phone)
            val headUnitConnection = FramedConnection(pair.headUnit)

            val emulator = EmulatedHeadUnit(
                connection = headUnitConnection,
                tls = TlsSession(AapTls.headUnitEngine()),
                config = HeadUnitConfig(sensors = sensors),
            )
            val session = AapSession(
                connection = phoneConnection,
                tls = TlsSession(AapTls.phoneEngine()),
                identity = PhoneIdentity(deviceName = "Headway Test"),
            )

            val bringUp = async(Dispatchers.IO) {
                session.connect { profile ->
                    profile.services.filter { it.hasSensorSourceService() }.map { it.id }
                }
            }
            val emulatorBringUp = async(Dispatchers.IO) { emulator.run(channelOpens = 1) }
            val profile = withTimeout(timeoutMillis) { bringUp.await() }
            withTimeout(timeoutMillis) { emulatorBringUp.await() }

            val service = profile.services.single { it.hasSensorSourceService() }
            // The head unit advertises SENSOR first, as a real one does, and its
            // id is the head unit's to choose -- asserted rather than assumed,
            // because the phone must read it from the advertisement.
            assertEquals(ChannelId.SENSOR.id, service.id)

            // The schema's own reading, said explicitly. These fixtures were
            // written against `kms_e1` meaning kilometres-times-ten, and the
            // shipped default is now the metres reading a real head unit sends
            // -- so leaving this implicit would make the test assert whichever
            // default happened to be current rather than the conversion it is
            // about. `SensorChannelTest` covers the other direction, and the
            // wiring that carries a chosen scale into the decode.
            val channel = SensorChannel.of(
                services = profile.services,
                connectionFor = { phoneConnection },
                odometerScale = CarSensors.ODOMETER_SCALE_E1,
            )!!
            val source = EmulatedSensorSource(
                connection = headUnitConnection,
                channelId = service.id,
                advertised = reallyHas,
                refuseUnadvertised = reallyHas != sensors,
            )

            val headUnitSide = async(Dispatchers.IO) { headUnit(source) }
            val phoneSide = async(Dispatchers.IO) { phone(channel) }
            val emissions = withTimeout(timeoutMillis) { phoneSide.await() }
            withTimeout(timeoutMillis) { headUnitSide.await() }
            SensorRun(channel, emissions, source.requested.toList())
        }
    }

    private companion object {
        /** What the emulated unit advertises in these tests. */
        val SENSORS: List<SensorType> = EmulatedHeadUnit.ADVERTISED_SENSORS
    }
}

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

package dev.headway.protocol.channel

import aap_protobuf.service.ServiceOuterClass
import aap_protobuf.service.sensorsource.SensorSourceServiceOuterClass.SensorSourceService
import aap_protobuf.service.sensorsource.message.EnvironmentDataOuterClass.EnvironmentData
import aap_protobuf.service.sensorsource.message.FuelDataOuterClass.FuelData
import aap_protobuf.service.sensorsource.message.GyroscopeDataOuterClass.GyroscopeData
import aap_protobuf.service.sensorsource.message.NightModeDataOuterClass.NightModeData
import aap_protobuf.service.sensorsource.message.OdometerDataOuterClass.OdometerData
import aap_protobuf.service.sensorsource.message.ParkingBrakeDataOuterClass.ParkingBrakeData
import aap_protobuf.service.sensorsource.message.RpmDataOuterClass.RpmData
import aap_protobuf.service.sensorsource.message.SensorBatchOuterClass.SensorBatch
import aap_protobuf.service.sensorsource.message.SensorErrorOuterClass.SensorError
import aap_protobuf.service.sensorsource.message.SensorErrorTypeOuterClass.SensorErrorType
import aap_protobuf.service.sensorsource.message.SensorOuterClass.Sensor
import aap_protobuf.service.sensorsource.message.SensorRequestOuterClass.SensorRequest
import aap_protobuf.service.sensorsource.message.SensorResponseOuterClass.SensorResponse
import aap_protobuf.service.sensorsource.message.SensorTypeOuterClass.SensorType
import aap_protobuf.service.sensorsource.message.SpeedDataOuterClass.SpeedData
import aap_protobuf.service.sensorsource.message.TirePressureDataOuterClass.TirePressureData
import aap_protobuf.shared.MessageStatusOuterClass.MessageStatus
import dev.headway.protocol.framing.AapMessage
import dev.headway.protocol.framing.ChannelId
import dev.headway.protocol.io.Cryptor
import dev.headway.protocol.io.MessageChannel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.EOFException

/**
 * The sensor channel's decoder, against batches whose bytes are built by hand.
 *
 * Every quantity in `SensorBatch` is an integer scaled by a power of ten, and
 * three things can go wrong that all look like a working dashboard:
 *
 * 1. a scale factor off by ten — 0.1 km/h or 1000 km/h, nothing to see in a log;
 * 2. a batch treated as a snapshot rather than a delta, which blanks every gauge
 *    the car did not mention in the latest update;
 * 3. an absent field read as zero, which is the difference between "the car has
 *    not said" and "the car says you are stopped".
 *
 * Each has a test below. The scale factors themselves are pinned in
 * [CarSensorsTest]; what is pinned here is that the decoder applies the right
 * one to the right field.
 *
 * The messages are real protobufs serialised to real bytes and fed through
 * [SensorChannel.handle] as [AapMessage]s, so the message ids and the framing
 * flags are exercised rather than assumed.
 */
class SensorChannelTest {

    // --- decoding a batch ----------------------------------------------------

    @Test
    fun `a batch of known scaled integers decodes to the right quantities`() {
        val channel = SensorChannel(
            NullChannel(),
            odometerScale = CarSensors.ODOMETER_SCALE_E1,
        )
        val message = channel.handle(
            batchMessage(
                SensorBatch.newBuilder()
                    // 27.778 m/s is 100 km/h.
                    .addSpeedData(SpeedData.newBuilder().setSpeedE3(27_778).setCruiseEngaged(true))
                    .addRpmData(RpmData.newBuilder().setRpmE3(2_100_000))
                    .addFuelData(
                        FuelData.newBuilder().setFuelLevel(45).setRange(380).setLowFuelWarning(false)
                    )
                    // 220 kPa is about 32 psi, a normal cold tyre.
                    .addTirePressureData(
                        TirePressureData.newBuilder()
                            .addAllTirePressuresE2(listOf(22_100, 22_000, 21_900, 22_300))
                    )
                    .addEnvironmentData(EnvironmentData.newBuilder().setTemperatureE3(18_500))
                    // kms_e1 is kilometres in tenths, so under the *schema's*
                    // reading this is 200 000 km. The channel below is built
                    // with that scale explicitly, because the shipped default
                    // is now the metres reading a real head unit sends.
                    .addOdometerData(OdometerData.newBuilder().setKmsE1(2_000_000))
                    .addParkingBrakeData(ParkingBrakeData.newBuilder().setParkingBrake(false))
                    .addNightModeData(NightModeData.newBuilder().setNightMode(true))
                    .build()
            )
        )

        val batch = assertBatch(message)
        val sensors = batch.accumulated
        assertEquals(27.778, sensors.speedMetersPerSecond!!, 0.0001)
        assertEquals(100.0, sensors.speedKph!!, 0.01)
        assertEquals(true, sensors.cruiseEngaged)
        assertEquals(2100.0, sensors.rpm!!, 0.0001)
        // Carried verbatim: no reference states a unit for either. See the
        // fields' KDoc and BLOCKERS.md B-022.
        assertEquals(45, sensors.fuelLevel)
        assertEquals(380, sensors.range)
        assertEquals(false, sensors.lowFuel)
        assertEquals(listOf(221.0, 220.0, 219.0, 223.0), sensors.tyrePressuresKpa)
        assertEquals(18.5, sensors.outsideTemperatureCelsius!!, 0.0001)
        assertEquals(200_000.0, sensors.odometerKm!!, 0.0001)
        assertEquals(false, sensors.parkingBrake)
        assertEquals(true, sensors.nightMode)
        assertEquals(1L, channel.batchesReceived)
        assertEquals(0L, channel.batchesWithNothingKnown)
    }

    @Test
    fun `a batch is a delta, so one sensor does not erase the others`() {
        // The bug this exists for: a car reports fuel once and speed sixty times
        // a second. A decoder that returned each batch as the whole picture
        // would blank the fuel gauge on the very next update.
        val channel = SensorChannel(NullChannel())
        channel.handle(
            batchMessage(
                SensorBatch.newBuilder()
                    .addFuelData(FuelData.newBuilder().setFuelLevel(45))
                    .addTirePressureData(
                        TirePressureData.newBuilder().addAllTirePressuresE2(listOf(22_000, 22_100))
                    )
                    .build()
            )
        )
        val second = assertBatch(
            channel.handle(
                batchMessage(
                    SensorBatch.newBuilder()
                        .addSpeedData(SpeedData.newBuilder().setSpeedE3(13_889))
                        .build()
                )
            )
        )

        // The update itself carries only what arrived...
        assertNull(second.update.fuelLevel, "the update is the delta, not the picture")
        assertTrue(second.update.tyrePressuresKpa.isEmpty())

        // ...and the accumulated view carries everything.
        assertEquals(45, second.accumulated.fuelLevel)
        assertEquals(listOf(220.0, 221.0), second.accumulated.tyrePressuresKpa)
        assertEquals(50.0, second.accumulated.speedKph!!, 0.01)
        assertEquals(second.accumulated, channel.latest)
    }

    @Test
    fun `an absent sensor reads as not reported, not as zero`() {
        val channel = SensorChannel(NullChannel())
        val batch = assertBatch(
            channel.handle(
                batchMessage(SensorBatch.newBuilder().addRpmData(RpmData.newBuilder().setRpmE3(0)))
            )
        )

        // A reported zero is data...
        assertEquals(0.0, batch.accumulated.rpm!!, 0.0001)
        // ...and everything the car did not mention is absent, not zero. The
        // difference is a speedometer reading 0 km/h on a moving car.
        assertNull(batch.accumulated.speedMetersPerSecond)
        assertNull(batch.accumulated.speedKph)
        assertNull(batch.accumulated.fuelLevel)
        assertNull(batch.accumulated.parkingBrake)
        assertNull(batch.accumulated.restricted)
        assertTrue(batch.accumulated.tyrePressuresKpa.isEmpty())
    }

    @Test
    fun `a batch carrying only sensors Headway does not draw is counted, not an error`() {
        // `SensorBatch` has 22 fields and CarSensors models eleven. A car
        // streaming gyroscope samples must not look like a car streaming
        // nothing, or a log cannot tell a silent head unit from a chatty one.
        val channel = SensorChannel(NullChannel())
        val batch = assertBatch(
            channel.handle(
                batchMessage(
                    SensorBatch.newBuilder().addGyroscopeData(
                        GyroscopeData.newBuilder()
                            .setRotationSpeedXE3(0)
                            .setRotationSpeedYE3(0)
                            .setRotationSpeedZE3(0)
                    )
                )
            )
        )
        assertFalse(batch.update.any)
        assertFalse(batch.accumulated.any)
        assertEquals(1L, channel.batchesReceived)
        assertEquals(1L, channel.batchesWithNothingKnown)
        assertTrue(channel.describe().contains("carrying nothing Headway reads"))
    }

    @Test
    fun `an empty batch is decoded rather than refused`() {
        // Legal: every field of SensorBatch is optional-by-repetition. A head
        // unit that sends one is being useless, not malformed, and refusing it
        // would end the channel over nothing.
        val channel = SensorChannel(NullChannel())
        val batch = assertBatch(channel.handle(batchMessage(SensorBatch.newBuilder())))
        assertFalse(batch.accumulated.any)
        assertEquals("sensors: nothing reported", batch.accumulated.describe())
    }

    @Test
    fun `the channel's own odometer scale reaches the decode`() {
        // The bug this exists for, and the reason two previous "fixes" were
        // invisible on a real car: the scale was threaded from the quirk file
        // through `CarSensorStream` into this class's constructor and then
        // dropped, because `onBatch` called the companion `decodeBatch(batch)`
        // without it and quietly took the companion's own default. The property
        // was never read by anything.
        //
        // Asserted through `handle()` rather than through `decodeBatch`,
        // because `decodeBatch` was always right -- it was the wiring that was
        // not, and only a live path proves the wiring.
        val channel = SensorChannel(
            NullChannel(),
            odometerScale = CarSensors.ODOMETER_SCALE_METERS,
        )
        val message = channel.handle(
            batchMessage(
                SensorBatch.newBuilder()
                    // Metres. 140 012.9 km is 87 000 miles, which is the
                    // reading the driver's own dashboard shows.
                    .addOdometerData(OdometerData.newBuilder().setKmsE1(140_012_900))
                    .build()
            )
        )
        val batch = assertBatch(message)
        assertEquals(140_012.9, batch.accumulated.odometerKm!!, 0.001)
    }

    @Test
    fun `the last entry wins when a batch repeats a sensor`() {
        // Every field is `repeated` and every reference producer adds exactly
        // one. When there are several the newest is what a gauge wants; see
        // SensorChannel.decodeBatch.
        val batch = SensorChannel.decodeBatch(
            SensorBatch.newBuilder()
                .addSpeedData(SpeedData.newBuilder().setSpeedE3(1_000))
                .addSpeedData(SpeedData.newBuilder().setSpeedE3(27_778))
                .build(),
            // Said explicitly, because the parameter no longer has a default.
            // It had one, and that is exactly how `onBatch` came to call this
            // with one argument and decode every real batch at the wrong scale.
            CarSensors.ODOMETER_SCALE_E1,
        )
        assertEquals(100.0, batch.speedKph!!, 0.01)
    }

    // --- subscribing ---------------------------------------------------------

    @Test
    fun `one request goes out per advertised sensor, and each answer is attributed`() = runTest {
        // The trap: SensorRequest.type is a single value, so a phone that wants
        // three sensors sends three requests -- and SensorResponse carries no
        // field saying which one it answers, so the only way to attribute an
        // answer is the order the requests went out in.
        val wire = RecordingChannel()
        val channel = SensorChannel(
            wire,
            advertised = listOf(SensorType.SENSOR_SPEED, SensorType.SENSOR_FUEL),
        )
        assertEquals(2, channel.subscribe())

        assertEquals(2, wire.sent.size)
        wire.sent.forEach {
            assertEquals(SensorMessageId.REQUEST, it.messageId)
            assertTrue(it.encrypted, "aasdk sends every sensor message ENCRYPTED")
            assertFalse(it.control, "and SPECIFIC, i.e. without the CONTROL bit")
            assertEquals(ChannelId.SENSOR.id, it.channelId)
        }
        val requests = wire.sent.map { SensorRequest.parseFrom(it.payload) }
        assertEquals(
            listOf(SensorType.SENSOR_SPEED, SensorType.SENSOR_FUEL),
            requests.map { it.type },
        )
        // Zero is the one value whose meaning does not depend on the unit, which
        // no reference states. See SensorChannel.minUpdatePeriod.
        assertTrue(requests.all { it.minUpdatePeriod == 0L })

        val first = channel.handle(responseMessage(MessageStatus.STATUS_SUCCESS))
        val second = channel.handle(responseMessage(MessageStatus.STATUS_INVALID_SENSOR))
        assertEquals(SensorType.SENSOR_SPEED, (first as SensorChannelMessage.Started).requested)
        assertTrue(first.ok)
        assertEquals(SensorType.SENSOR_FUEL, (second as SensorChannelMessage.Started).requested)
        assertFalse(second.ok, "STATUS_INVALID_SENSOR is a refusal")
        assertEquals(2L, channel.responsesReceived)
        assertEquals(1L, channel.sensorsRefused)

        // A third answer nobody asked for is reported rather than dropped.
        val extra = channel.handle(responseMessage(MessageStatus.STATUS_SUCCESS))
        assertNull((extra as SensorChannelMessage.Started).requested)
    }

    @Test
    fun `a service advertising no sensors is subscribed to with no requests`() = runTest {
        val wire = RecordingChannel()
        assertEquals(0, SensorChannel(wire).subscribe())
        assertTrue(wire.sent.isEmpty())
    }

    // --- the rest of the channel ---------------------------------------------

    @Test
    fun `an error message is decoded and does not end the channel`() {
        val channel = SensorChannel(NullChannel())
        val message = channel.handle(
            AapMessage(
                channelId = ChannelId.SENSOR.id,
                control = false,
                encrypted = true,
                messageId = SensorMessageId.ERROR,
                payload = SensorError.newBuilder()
                    .setSensorType(SensorType.SENSOR_TIRE_PRESSURE_DATA)
                    .setSensorErrorType(SensorErrorType.SENSOR_ERROR_TRANSIENT)
                    .build()
                    .toByteArray(),
            )
        )
        val failure = message as SensorChannelMessage.Failure
        assertEquals(SensorType.SENSOR_TIRE_PRESSURE_DATA, failure.error!!.sensorType)
        assertEquals(1L, channel.errorsReceived)
    }

    @Test
    fun `an error payload that is not a SensorError is reported rather than thrown on`() {
        // Nothing observed confirms this id carries a SensorError at all -- see
        // SensorMessageId.ERROR -- so bytes that are not one are a gap in what
        // Headway knows, not a malformed message worth killing the channel for.
        val channel = SensorChannel(NullChannel())
        val failure = channel.handle(
            AapMessage(
                channelId = ChannelId.SENSOR.id,
                control = false,
                encrypted = true,
                messageId = SensorMessageId.ERROR,
                // Field 1 as a length-delimited string where an enum belongs.
                payload = byteArrayOf(0x0A, 0x02, 0x61, 0x62),
            )
        ) as SensorChannelMessage.Failure
        assertNull(failure.error)
        assertEquals(4, failure.payloadSize)
        assertEquals(1L, channel.errorsReceived)
    }

    @Test
    fun `an unknown message id is surfaced rather than thrown on`() {
        val channel = SensorChannel(NullChannel())
        val message = channel.handle(
            AapMessage(
                channelId = ChannelId.SENSOR.id,
                control = false,
                encrypted = true,
                messageId = 0x8123,
                payload = byteArrayOf(1, 2, 3),
            )
        )
        assertEquals(SensorChannelMessage.Unhandled(0x8123, 3), message)
    }

    @Test
    fun `a message for another channel is refused`() {
        val channel = SensorChannel(NullChannel(), channelId = ChannelId.SENSOR.id)
        assertThrows(SensorChannelException::class.java) {
            channel.handle(
                batchMessage(SensorBatch.newBuilder(), channelId = ChannelId.MEDIA_SINK_VIDEO.id)
            )
        }
    }

    @Test
    fun `a malformed batch is refused with a channel exception`() {
        val channel = SensorChannel(NullChannel())
        assertThrows(SensorChannelException::class.java) {
            channel.handle(
                AapMessage(
                    channelId = ChannelId.SENSOR.id,
                    control = false,
                    encrypted = true,
                    messageId = SensorMessageId.BATCH,
                    // A varint field header promising bytes that are not there.
                    payload = byteArrayOf(0x0A, 0x7F),
                )
            )
        }
    }

    // --- the flow ------------------------------------------------------------

    @Test
    fun `the flow emits the accumulated picture, once per batch`() = runTest {
        val wire = RecordingChannel()
        wire.inbound += responseMessage(MessageStatus.STATUS_SUCCESS)
        wire.inbound += batchMessage(
            SensorBatch.newBuilder().addFuelData(FuelData.newBuilder().setFuelLevel(45))
        )
        wire.inbound += batchMessage(
            SensorBatch.newBuilder().addSpeedData(SpeedData.newBuilder().setSpeedE3(27_778))
        )

        val emissions = SensorChannel(wire).sensors().take(2).toList()

        // The response passed through without an emission: a gauge has nothing
        // to do with a subscription being answered.
        assertEquals(2, emissions.size)
        assertEquals(45, emissions[0].fuelLevel)
        assertNull(emissions[0].speedKph)
        assertEquals(45, emissions[1].fuelLevel, "the second emission still knows the fuel level")
        assertEquals(100.0, emissions[1].speedKph!!, 0.01)
    }

    // --- picking the service out of an advertisement -------------------------

    @Test
    fun `the service is found by content and its sensors are read from it`() {
        val services = listOf(
            ServiceOuterClass.Service.newBuilder().setId(3).build(),
            ServiceOuterClass.Service.newBuilder()
                .setId(1)
                .setSensorSourceService(
                    SensorSourceService.newBuilder()
                        .addSensors(Sensor.newBuilder().setSensorType(SensorType.SENSOR_SPEED))
                        .addSensors(Sensor.newBuilder().setSensorType(SensorType.SENSOR_FUEL))
                        // Duplicates are the head unit's to send and not ours to
                        // ask about twice.
                        .addSensors(Sensor.newBuilder().setSensorType(SensorType.SENSOR_SPEED))
                )
                .build(),
        )
        var asked = -1
        val channel = SensorChannel.of(services, { id -> asked = id; NullChannel() })!!
        assertEquals(1, channel.channelId)
        assertEquals(1, asked, "the view must be taken for the advertised id")
        assertEquals(listOf(SensorType.SENSOR_SPEED, SensorType.SENSOR_FUEL), channel.advertised)
    }

    @Test
    fun `a head unit with no sensor service produces no channel`() {
        val services = listOf(ServiceOuterClass.Service.newBuilder().setId(3).build())
        assertNull(SensorChannel.of(services, { NullChannel() }))
    }

    // --- helpers -------------------------------------------------------------

    private fun assertBatch(message: SensorChannelMessage): SensorChannelMessage.Batch {
        assertTrue(message is SensorChannelMessage.Batch, "expected a batch, got $message")
        return message as SensorChannelMessage.Batch
    }

    private fun batchMessage(
        batch: SensorBatch.Builder,
        channelId: Int = ChannelId.SENSOR.id,
    ): AapMessage = batchMessage(batch.build(), channelId)

    private fun batchMessage(
        batch: SensorBatch,
        channelId: Int = ChannelId.SENSOR.id,
    ): AapMessage = SensorChannel.sensorBatch(channelId, batch)

    private fun responseMessage(status: MessageStatus): AapMessage = AapMessage(
        channelId = ChannelId.SENSOR.id,
        control = false,
        encrypted = true,
        messageId = SensorMessageId.RESPONSE,
        payload = SensorResponse.newBuilder().setStatus(status).build().toByteArray(),
    )

    /** A connection that is never read and never written. */
    private class NullChannel : MessageChannel {
        override var cryptor: Cryptor? = null

        override suspend fun send(message: AapMessage) = Unit

        override suspend fun receive(): AapMessage = throw EOFException("nothing to read")
    }

    /** Records what the channel sends and hands back a scripted inbound stream. */
    private class RecordingChannel : MessageChannel {
        override var cryptor: Cryptor? = null

        val sent: MutableList<AapMessage> = mutableListOf()
        val inbound: MutableList<AapMessage> = mutableListOf()

        override suspend fun send(message: AapMessage) {
            sent += message
        }

        override suspend fun receive(): AapMessage =
            inbound.removeFirstOrNull() ?: throw EOFException("script exhausted")
    }
}

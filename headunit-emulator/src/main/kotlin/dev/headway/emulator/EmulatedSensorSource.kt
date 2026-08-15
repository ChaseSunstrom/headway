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

import aap_protobuf.service.sensorsource.message.EnvironmentDataOuterClass.EnvironmentData
import aap_protobuf.service.sensorsource.message.FuelDataOuterClass.FuelData
import aap_protobuf.service.sensorsource.message.NightModeDataOuterClass.NightModeData
import aap_protobuf.service.sensorsource.message.OdometerDataOuterClass.OdometerData
import aap_protobuf.service.sensorsource.message.ParkingBrakeDataOuterClass.ParkingBrakeData
import aap_protobuf.service.sensorsource.message.RpmDataOuterClass.RpmData
import aap_protobuf.service.sensorsource.message.SensorBatchOuterClass.SensorBatch
import aap_protobuf.service.sensorsource.message.SensorErrorOuterClass.SensorError
import aap_protobuf.service.sensorsource.message.SensorErrorTypeOuterClass.SensorErrorType
import aap_protobuf.service.sensorsource.message.SensorRequestOuterClass.SensorRequest
import aap_protobuf.service.sensorsource.message.SensorTypeOuterClass.SensorType
import aap_protobuf.service.sensorsource.message.SpeedDataOuterClass.SpeedData
import aap_protobuf.service.sensorsource.message.TirePressureDataOuterClass.TirePressureData
import aap_protobuf.shared.MessageStatusOuterClass.MessageStatus
import dev.headway.protocol.channel.SensorChannel
import dev.headway.protocol.channel.SensorMessageId
import dev.headway.protocol.framing.ChannelId
import dev.headway.protocol.io.FramedConnection

/**
 * The **head-unit** side of the sensor channel: a car that can be told what to
 * report.
 *
 * The car is the sensor *source* — see [SensorChannel]'s KDoc for why that is
 * the opposite of what the name suggests — so this half owns the readings and
 * the phone subscribes to them. What it models is the part of a real unit's
 * behaviour that a phone can actually get wrong:
 *
 * - **one request per sensor type**, each answered separately, because
 *   `SensorRequest.type` is a single value and a phone that sent one request for
 *   thirteen sensors would be answered once and hear nothing else;
 * - **a refusal for a type that was never advertised**, with
 *   `STATUS_INVALID_SENSOR` (-9). Nothing in the references does this — openauto
 *   answers `STATUS_SUCCESS` to any type and then simply never streams it
 *   (`openauto/src/autoapp/Service/Sensor/SensorService.cpp` L127-L143) — so
 *   [refuseUnadvertised] defaults to the openauto behaviour and the strict one
 *   is opt-in. A phone must survive both;
 * - **batches as deltas**, since `SensorBatch` carries only the fields that
 *   changed and a phone that treats each batch as a snapshot blanks its own
 *   dashboard on every update.
 *
 * Per ADR 0002 this shares `core-protocol` with the phone, so a round trip
 * through it proves symmetry rather than correctness; the byte-level assertions
 * in `SensorChannelTest` are what pin the wire format.
 */
class EmulatedSensorSource(
    private val connection: FramedConnection,
    private val channelId: Int = ChannelId.SENSOR.id,
    /** The types this unit admits to having. Answered with success; others may be refused. */
    private val advertised: List<SensorType> = EmulatedHeadUnit.ADVERTISED_SENSORS,
    /**
     * Whether a request for an unadvertised type is answered
     * `STATUS_INVALID_SENSOR` rather than `STATUS_SUCCESS`. Off by default; see
     * the class KDoc.
     */
    private val refuseUnadvertised: Boolean = false,
    private val onStep: (String) -> Unit = {},
) {

    /** Every type the phone has asked for, in the order it asked. */
    val requested: MutableList<SensorType> = mutableListOf()

    /** Batches this unit has put on the wire. */
    var batchesSent: Int = 0
        private set

    // --- subscription --------------------------------------------------------

    /**
     * Reads one `SensorRequest` and answers it.
     *
     * @return the type that was asked for, or null when the phone sent a type
     *   outside the enum — which proto2 files into unknown fields, leaving the
     *   `required` field unset and the message unparseable, so in practice this
     *   is unreachable and exists so the caller is not lied to if it ever is.
     */
    suspend fun answerRequest(): SensorType? {
        val message = connection.receive()
        check(message.channelId == channelId) {
            "expected a sensor-channel message, got one on ${ChannelId.describe(message.channelId)}"
        }
        check(message.messageId == SensorMessageId.REQUEST) {
            "expected SENSOR_MESSAGE_REQUEST, got ${SensorMessageId.describe(message.messageId)}"
        }
        val request = SensorRequest.parseFrom(message.payload)
        val type = if (request.hasType()) request.type else null
        if (type != null) requested += type

        val status = when {
            type == null -> MessageStatus.STATUS_INVALID_SENSOR
            refuseUnadvertised && type !in advertised -> MessageStatus.STATUS_INVALID_SENSOR
            else -> MessageStatus.STATUS_SUCCESS
        }
        connection.send(SensorChannel.sensorResponse(channelId, status))
        onStep(
            "sensor ${type?.name ?: "unknown"} requested with min_update_period " +
                "${request.minUpdatePeriod}; answered ${status.name}"
        )
        return type
    }

    /** Answers [count] requests in a row. */
    suspend fun answerRequests(count: Int): List<SensorType> =
        (0 until count).mapNotNull { answerRequest() }

    // --- readings ------------------------------------------------------------

    /**
     * Sends one batch carrying whatever is named, and nothing else.
     *
     * Every parameter is null by default because that is what a real batch looks
     * like: it is a delta, not a snapshot, and a car reporting only that it has
     * started moving sends `speed_data` alone.
     *
     * The arguments are the **scaled integers the wire carries**, not the
     * quantities a person reads. That is deliberate — the whole point of the
     * phone-side test is that it converts them, and a helper taking `27.778` and
     * multiplying by a thousand here would move the conversion under test into
     * the thing testing it.
     */
    suspend fun sendReadings(
        speedE3: Int? = null,
        cruiseEngaged: Boolean? = null,
        rpmE3: Int? = null,
        fuelLevel: Int? = null,
        range: Int? = null,
        lowFuelWarning: Boolean? = null,
        tyrePressuresE2: List<Int>? = null,
        temperatureE3: Int? = null,
        kmsE1: Int? = null,
        parkingBrake: Boolean? = null,
        nightMode: Boolean? = null,
    ): Int {
        val batch = SensorBatch.newBuilder()
        if (speedE3 != null || cruiseEngaged != null) {
            val speed = SpeedData.newBuilder()
            // `speed_e3` is required, so a batch mentioning cruise control at all
            // has to carry a speed with it.
            speed.setSpeedE3(speedE3 ?: 0)
            cruiseEngaged?.let { speed.setCruiseEngaged(it) }
            batch.addSpeedData(speed)
        }
        rpmE3?.let { batch.addRpmData(RpmData.newBuilder().setRpmE3(it)) }
        if (fuelLevel != null || range != null || lowFuelWarning != null) {
            val fuel = FuelData.newBuilder()
            fuelLevel?.let { fuel.setFuelLevel(it) }
            range?.let { fuel.setRange(it) }
            lowFuelWarning?.let { fuel.setLowFuelWarning(it) }
            batch.addFuelData(fuel)
        }
        tyrePressuresE2?.let {
            batch.addTirePressureData(TirePressureData.newBuilder().addAllTirePressuresE2(it))
        }
        temperatureE3?.let {
            batch.addEnvironmentData(EnvironmentData.newBuilder().setTemperatureE3(it))
        }
        kmsE1?.let { batch.addOdometerData(OdometerData.newBuilder().setKmsE1(it)) }
        parkingBrake?.let {
            batch.addParkingBrakeData(ParkingBrakeData.newBuilder().setParkingBrake(it))
        }
        nightMode?.let { batch.addNightModeData(NightModeData.newBuilder().setNightMode(it)) }
        return sendBatch(batch.build())
    }

    /** Sends a batch built by the caller, for a reading this class has no parameter for. */
    suspend fun sendBatch(batch: SensorBatch): Int {
        connection.send(SensorChannel.sensorBatch(channelId, batch))
        batchesSent++
        onStep("sent sensor batch $batchesSent")
        return batchesSent
    }

    /**
     * Sends a `SENSOR_MESSAGE_ERROR`.
     *
     * **No reference sends one**, so this exercises a message whose direction and
     * payload are inferred from the schema alone — see [SensorMessageId.ERROR].
     * It is here so the phone's handling of it is executed by something rather
     * than being unreachable code.
     */
    suspend fun sendError(
        type: SensorType,
        error: SensorErrorType = SensorErrorType.SENSOR_ERROR_TRANSIENT,
    ) {
        connection.send(
            SensorChannel.sensorError(
                channelId,
                SensorError.newBuilder().setSensorType(type).setSensorErrorType(error).build(),
            )
        )
        onStep("sent sensor error ${error.name} for ${type.name}")
    }
}

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

import aap_protobuf.service.sensorsource.SensorSourceServiceOuterClass.SensorSourceService
import aap_protobuf.service.sensorsource.message.SensorBatchOuterClass.SensorBatch
import aap_protobuf.service.sensorsource.message.SensorErrorOuterClass.SensorError
import aap_protobuf.service.sensorsource.message.SensorRequestOuterClass.SensorRequest
import aap_protobuf.service.sensorsource.message.SensorResponseOuterClass.SensorResponse
import aap_protobuf.service.sensorsource.message.SensorTypeOuterClass.SensorType
import aap_protobuf.shared.MessageStatusOuterClass.MessageStatus
import dev.headway.protocol.framing.AapMessage
import dev.headway.protocol.framing.ChannelId
import dev.headway.protocol.io.FramedConnection
import dev.headway.protocol.io.MessageChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Message ids on the sensor channel.
 *
 * Source: `aap_protobuf/service/sensorsource/SensorMessageId.proto` L5-L11,
 * vendored verbatim from aasdk. aa-proxy-rs's flat schema carries the identical
 * four values (`aa-proxy-rs/src/protos/protos.proto` L1539-L1544), so nothing
 * here rests on a single tree.
 *
 * Named for what each id carries rather than for its direction, because the
 * direction is not symmetric: [REQUEST] is the only one the **phone** sends, and
 * everything else flows head unit -> phone. That is the opposite of what the
 * word "source" suggests to anyone reading `sensor_source_service` for the first
 * time — the car is the source, Headway is the subscriber.
 */
object SensorMessageId {

    /**
     * Phone -> HU. Payload is a `SensorRequest { type, min_update_period }`.
     *
     * aasdk's head-unit channel dispatches this id to `onSensorStartRequest`
     * (`aasdk/src/Channel/SensorSource/SensorSourceService.cpp` L63-L74), and
     * aa-proxy-rs sees real Gearhead traffic on it (`src/mitm.rs` L2206-L2280).
     */
    const val REQUEST: Int = 32769

    /**
     * HU -> phone. Payload is `{ required MessageStatus status = 1 }`.
     *
     * **Two schema names, one wire message.** aasdk defines both
     * `SensorResponse.proto` and `SensorStartResponseMessage.proto` with
     * byte-identical bodies, and its own channel sends the
     * `SensorStartResponseMessage` flavour on this id
     * (`SensorSourceService.cpp` L90-L102); openauto builds the same flavour
     * (`openauto/src/autoapp/Service/Sensor/SensorService.cpp` L127-L143) while
     * aa-proxy-rs synthesises a `SensorResponse` on the same id
     * (`aa-proxy-rs/src/mitm.rs` L2235-L2240). Headway parses `SensorResponse`;
     * which name is used cannot be observed on the wire.
     */
    const val RESPONSE: Int = 32770

    /**
     * HU -> phone. Payload is a `SensorBatch`: the readings that changed.
     *
     * aasdk calls the send `sendSensorEventIndication`
     * (`SensorSourceService.cpp` L77-L88).
     */
    const val BATCH: Int = 32771

    /**
     * `SensorError { sensor_type, sensor_error_type }`, per the schema.
     *
     * **The payload type is inferred from the name and nothing else.** aasdk
     * never sends this id and never handles it — its dispatcher has cases for
     * `SENSOR_MESSAGE_REQUEST` and `CHANNEL_OPEN_REQUEST` alone
     * (`SensorSourceService.cpp` L63-L74) — openauto likewise, and aa-proxy-rs's
     * pretty-printer, which decodes the other three ids into their messages,
     * prints this one as `SENSOR_MESSAGE_ERROR raw_len=..` rather than parsing
     * it (`aa-proxy-rs/src/mitm_prettyprint.rs` L861-L868). So Headway attempts
     * a `SensorError` and reports the raw bytes when that fails, rather than
     * pretending to know.
     */
    const val ERROR: Int = 32772

    fun describe(id: Int): String = when (id) {
        REQUEST -> "SENSOR_MESSAGE_REQUEST"
        RESPONSE -> "SENSOR_MESSAGE_RESPONSE"
        BATCH -> "SENSOR_MESSAGE_BATCH"
        ERROR -> "SENSOR_MESSAGE_ERROR"
        else -> "UNKNOWN_SENSOR(0x%04x)".format(id)
    }
}

/** A decoded message from the sensor channel. */
sealed interface SensorChannelMessage {

    /**
     * `SENSOR_MESSAGE_RESPONSE`: the head unit's answer to one [SensorRequest].
     *
     * @property requested the sensor this answers, or null when more responses
     *   arrived than were asked for. **The message carries no correlation
     *   field** — its whole body is `{ status }` — so the only way to know which
     *   request it answers is to remember the order they were sent in, which is
     *   what [SensorChannel] does. Both reference head units answer in order:
     *   openauto replies inside `onSensorStartRequest` before returning to the
     *   receive loop (`SensorService.cpp` L122-L145).
     */
    data class Started(
        val requested: SensorType?,
        val status: MessageStatus?,
    ) : SensorChannelMessage {
        /**
         * `MessageStatus.STATUS_SUCCESS` = 0
         * (`aap_protobuf/shared/MessageStatus.proto` L7). A head unit asked for
         * a sensor it does not have answers `STATUS_INVALID_SENSOR` = -9 (L16).
         */
        val ok: Boolean get() = status == MessageStatus.STATUS_SUCCESS
    }

    /**
     * `SENSOR_MESSAGE_BATCH`: readings, decoded.
     *
     * @property update what this batch alone carried — everything the car did
     *   not send in it is null.
     * @property accumulated [update] merged over every batch before it, which is
     *   what a dashboard wants: a batch carries only what changed, so the
     *   speed-only batch that follows a fuel reading would otherwise blank the
     *   fuel gauge.
     */
    data class Batch(
        val update: CarSensors,
        val accumulated: CarSensors,
    ) : SensorChannelMessage

    /**
     * `SENSOR_MESSAGE_ERROR`. [error] is null when the payload did not parse as
     * a `SensorError`, which is possible because nothing observed confirms that
     * is what this id carries — see [SensorMessageId.ERROR].
     */
    data class Failure(val error: SensorError?, val payloadSize: Int) : SensorChannelMessage

    /** Anything else that arrived here. Kept rather than thrown so a log shows it. */
    data class Unhandled(val messageId: Int, val payloadSize: Int) : SensorChannelMessage
}

/** The peer sent something on the sensor channel that does not parse. */
class SensorChannelException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * The **phone** side of the sensor channel: the car tells Headway about itself.
 *
 * ## The direction is the first thing to get right
 *
 * `sensor_source_service` is field 2 of `Service`
 * (`aap_protobuf/service/Service.proto` L24), and `Service` entries travel in
 * the `ServiceDiscoveryResponse` that the **head unit** sends. So the car is the
 * sensor source and Headway is a subscriber; `SensorRequest.min_update_period`
 * is a rate cap a consumer imposes on a producer, not a poll interval. openauto
 * advertises its sensors from `fillFeatures`
 * (`openauto/src/autoapp/Service/Sensor/SensorService.cpp` L89-L103), which is
 * the head-unit half of exactly this.
 *
 * Nothing on this channel has to be brought up: `AapSession.connect` opens every
 * advertised service by default, and a real 2021 Chevrolet Infotainment 3 unit
 * advertises SENSOR first of its thirteen services (`docs/protocol-notes.md`
 * §7.1, real-vehicle capture 2026-08-13). What was missing until this class
 * existed was a subscriber: with no `SensorRequest` sent, a head unit streams
 * nothing, and anything it did stream would land in the demultiplexer's
 * `onUnroutable` and be counted rather than read.
 *
 * ## Sequence
 *
 * ```text
 *  phone (Headway)                              head unit (car)
 *  ---------------                              ---------------
 *  ServiceDiscoveryRequest         ------->
 *                                 <-------      ServiceDiscoveryResponse
 *                                                [sensor_source_service.sensors:
 *                                                 the types this car can send]
 *  ChannelOpenRequest              ------->
 *                                 <-------      ChannelOpenResponse
 *  SensorRequest { SPEED, 0 }      32769 -->
 *                                 <-- 32770     SensorResponse { STATUS_SUCCESS }
 *                                 <-- 32771     SensorBatch { speed_data }
 *  SensorRequest { FUEL, 0 }       32769 -->
 *                                 <-- 32770     SensorResponse { ... }
 *                                 <-- 32771     SensorBatch { fuel_data }
 *  ...                                          ... for the rest of the session
 * ```
 *
 * **One request per sensor type.** `SensorRequest.type` is a single `required
 * SensorType`, not a list (`.../message/SensorRequest.proto` L8-L11), so a phone
 * that wants five sensors sends five requests. openauto's handler starts exactly
 * the stream that was asked for and ignores the rest (`SensorService.cpp`
 * L132-L141); aa-proxy-rs observes real Gearhead requesting types one at a time
 * and rewriting a single one of them (`aa-proxy-rs/src/mitm.rs` L2206-L2276).
 *
 * **Ask only for what was advertised.** [of] takes the types out of the
 * advertisement for that reason. Nothing in the references says what a head unit
 * does with a request for a sensor it never offered — `MessageStatus` has a
 * `STATUS_INVALID_SENSOR` (-9) that looks made for it, and openauto simply
 * answers `STATUS_SUCCESS` and then sends nothing (`SensorService.cpp`
 * L127-L143) — so asking is a way to be lied to rather than a way to find out.
 *
 * ## Framing
 *
 * Every message here is `ENCRYPTED` and `SPECIFIC`: aasdk builds both the batch
 * and the start response with
 * `EncryptionType::ENCRYPTED, MessageType::SPECIFIC`
 * (`aasdk/src/Channel/SensorSource/SensorSourceService.cpp` L77-L102), and
 * aa-proxy-rs sends its injected batches with `ENCRYPTED | FIRST | LAST` and no
 * control bit (`src/mitm.rs` L3746-L3752). Only `ChannelOpenRequest`/`Response`,
 * which also travel on this channel id, set CONTROL.
 *
 * ## Reading
 *
 * [handle] is the real entry point: a session demultiplexes frames by channel id
 * and hands this the ones that belong to it, exactly as [InputChannel]
 * documents. [receiveMessage] and [sensors] read the connection directly and are
 * only correct when nothing else is reading it — a single-channel driver, or a
 * test. `FramedConnection.receive` returns whatever arrives next on *any*
 * channel, so two competing readers steal each other's messages.
 *
 * ## Nothing here can fail the session
 *
 * A malformed batch throws [SensorChannelException] from [handle] and no
 * further: sensors are the one subsystem whose complete absence costs the driver
 * nothing but a tile that says so. Callers are expected to log and carry on, and
 * `HeadwayService.startSubsystem` isolates the bring-up for the same reason.
 */
class SensorChannel(
    private val connection: MessageChannel,
    /**
     * The channel number the head unit advertised for its `SensorSourceService`.
     *
     * **Not a protocol constant.** [ChannelId] explains why at length: aasdk's
     * own header says "In AA, Channel Id's are dynamic", and the authority is the
     * `required int32 id` of the advertised `Service`
     * (`aap_protobuf/service/Service.proto` L23). aasdk's static table puts the
     * sensor channel at 1 (`ChannelId::SENSOR`, used by
     * `aasdk/src/Channel/SensorSource/SensorSourceService.cpp` L27-L30) and a
     * real 2021 Chevrolet Infotainment 3 unit happens to agree, but [of] reads
     * the advertisement rather than trusting either.
     */
    val channelId: Int = ChannelId.SENSOR.id,
    /**
     * The sensor types to subscribe to, in the order the car advertised them.
     *
     * Empty is legal and means "subscribe to nothing", which is what a head unit
     * that advertises a sensor service with no sensors in it deserves.
     */
    val advertised: List<SensorType> = emptyList(),
    /**
     * `SensorRequest.min_update_period`, sent with every request.
     *
     * **No reference states its unit and none states a value.** The field is a
     * `required int64` with no comment (`.../message/SensorRequest.proto` L10);
     * aasdk hands the whole request to an event handler without reading it,
     * openauto branches on `type` alone (`SensorService.cpp` L132-L141), and
     * aa-proxy-rs only ever rewrites `type`. Zero is therefore the default here
     * because **zero is the one value whose meaning does not depend on the
     * unit**: no minimum period, i.e. send updates as fast as you like. A phone
     * that guessed milliseconds and sent 1000 against a unit reading
     * microseconds would ask for one update per millisecond and never know.
     */
    val minUpdatePeriod: Long = NO_UPDATE_CAP,
    /** Narrates each step; wired to the debug log export in the app. */
    private val onStep: (String) -> Unit = {},
) {

    /**
     * Sensors asked for and not yet answered, oldest first.
     *
     * `SensorResponse` carries no field saying which request it answers (see
     * [SensorChannelMessage.Started]), so this is the only way to attribute one.
     * A response that arrives with the queue empty is reported with a null
     * `requested` rather than dropped: an unsolicited one is a head unit
     * behaviour no reference shows, and it should be visible in a log rather
     * than swallowed.
     */
    private val awaitingResponse: ArrayDeque<SensorType> = ArrayDeque()

    /** Everything the car has said so far, merged. Never null; starts empty. */
    var latest: CarSensors = CarSensors.UNKNOWN
        private set

    /** `SensorRequest`s sent. */
    var requestsSent: Long = 0L
        private set

    /** `SensorResponse`s received, refusals included. */
    var responsesReceived: Long = 0L
        private set

    /** Responses whose status was not `STATUS_SUCCESS`. */
    var sensorsRefused: Long = 0L
        private set

    /** `SensorBatch`es received. */
    var batchesReceived: Long = 0L
        private set

    /**
     * Batches that carried no reading this class decodes.
     *
     * Not an error: `SensorBatch` has 22 fields and [CarSensors] models eleven of
     * them, so a car streaming gyroscope or GPS-satellite data produces these by
     * the hundred. Counted so a log can distinguish "the car sent nothing" from
     * "the car sent plenty, all of it something Headway does not draw".
     */
    var batchesWithNothingKnown: Long = 0L
        private set

    /** `SENSOR_MESSAGE_ERROR`s received. */
    var errorsReceived: Long = 0L
        private set

    // --- subscribing ---------------------------------------------------------

    /**
     * Sends one `SensorRequest` per advertised type.
     *
     * Send-only: the answers arrive on the channel and are decoded by [handle]
     * like everything else. Waiting for each response in turn would be the other
     * shape, and it is the wrong one here — a head unit that never answers the
     * third of thirteen requests would then leave the other ten unsent forever,
     * and sensors are not worth a bring-up that can stall.
     *
     * @return how many requests were sent.
     */
    suspend fun subscribe(): Int {
        if (advertised.isEmpty()) {
            onStep("sensors: the head unit advertised a sensor service with no sensors in it")
            return 0
        }
        for (type in advertised) {
            connection.send(
                specific(
                    SensorMessageId.REQUEST,
                    SensorRequest.newBuilder()
                        .setType(type)
                        .setMinUpdatePeriod(minUpdatePeriod)
                        .build()
                        .toByteArray(),
                )
            )
            awaitingResponse.addLast(type)
            requestsSent++
        }
        onStep(
            "sensors: subscribed to ${advertised.size} advertised sensor(s) " +
                advertised.joinToString(prefix = "[", postfix = "]") { describeType(it) } +
                ", min_update_period $minUpdatePeriod"
        )
        return advertised.size
    }

    // --- reading -------------------------------------------------------------

    /**
     * Decodes one message addressed to this channel and folds it into [latest].
     *
     * @throws SensorChannelException if it is addressed elsewhere or does not
     *   parse.
     */
    fun handle(message: AapMessage): SensorChannelMessage {
        if (message.channelId != channelId) {
            throw SensorChannelException(
                "message for ${ChannelId.describe(message.channelId)} handed to the sensor " +
                    "channel (${ChannelId.describe(channelId)})"
            )
        }
        return decode(message)
    }

    /** Reads the connection until a message for this channel arrives, and decodes it. */
    suspend fun receiveMessage(): SensorChannelMessage {
        while (true) {
            val message = connection.receive()
            if (message.channelId == channelId) return decode(message)
            onStep("sensors: ignored a message on ${ChannelId.describe(message.channelId)}")
        }
    }

    /**
     * Everything the car has said, accumulated, one emission per batch.
     *
     * Cold: collecting starts reading the channel, and only one collector may run
     * at a time (see the class KDoc on reading). Each emission is the *whole*
     * picture rather than the delta, because a batch carries only what changed
     * and a consumer that had to merge for itself would get it wrong the first
     * time a car sent speed alone.
     *
     * Responses and errors are logged through `onStep` as they pass rather than
     * emitted: this flow is what a gauge reads, and a gauge has nothing to do
     * with a subscription being refused.
     */
    fun sensors(): Flow<CarSensors> = flow {
        while (true) {
            val message = receiveMessage()
            if (message is SensorChannelMessage.Batch) emit(message.accumulated)
        }
    }

    /** What arrived and what the car reports, for the log. */
    fun describe(): String = "sensors: $requestsSent request(s), $responsesReceived response(s) " +
        "($sensorsRefused refused), $batchesReceived batch(es) " +
        "($batchesWithNothingKnown carrying nothing Headway reads), $errorsReceived error(s); " +
        latest.describe()

    private fun decode(message: AapMessage): SensorChannelMessage = when (message.messageId) {
        SensorMessageId.BATCH -> onBatch(message.payload)
        SensorMessageId.RESPONSE -> onResponse(message.payload)
        SensorMessageId.ERROR -> onError(message.payload)

        // The phone originates this one, so receiving it means the peer is
        // behaving as a phone. Worth seeing rather than ignoring.
        SensorMessageId.REQUEST -> {
            onStep("sensors: the peer sent a SensorRequest; Headway is the phone and does not answer it")
            SensorChannelMessage.Unhandled(message.messageId, message.payload.size)
        }

        else -> {
            onStep(
                "sensors: unhandled ${SensorMessageId.describe(message.messageId)} " +
                    "(${message.payload.size} bytes)"
            )
            SensorChannelMessage.Unhandled(message.messageId, message.payload.size)
        }
    }

    private fun onResponse(payload: ByteArray): SensorChannelMessage {
        val response = parse("SensorResponse") { SensorResponse.parseFrom(payload) }
        responsesReceived++
        // Guarded rather than read straight, and the reason is a genuine trap:
        // proto2 defaults an enum field to its FIRST declared value, and
        // MessageStatus declares STATUS_UNSOLICITED_MESSAGE = 1 before
        // STATUS_SUCCESS = 0 (`MessageStatus.proto` L6-L7). An absent or
        // out-of-enum status therefore reads as "unsolicited", not as success.
        val status = if (response.hasStatus()) response.status else null
        val requested = awaitingResponse.removeFirstOrNull()
        if (status != MessageStatus.STATUS_SUCCESS) sensorsRefused++
        onStep(
            "sensors: ${requested?.let(::describeType) ?: "an unrequested sensor"} answered " +
                (status?.name ?: "with a status outside MessageStatus")
        )
        return SensorChannelMessage.Started(requested, status)
    }

    private fun onBatch(payload: ByteArray): SensorChannelMessage {
        val batch = parse("SensorBatch") { SensorBatch.parseFrom(payload) }
        batchesReceived++
        val update = decodeBatch(batch)
        if (!update.any) batchesWithNothingKnown++
        latest = latest.mergedWith(update)
        return SensorChannelMessage.Batch(update, latest)
    }

    private fun onError(payload: ByteArray): SensorChannelMessage {
        errorsReceived++
        // Deliberately not routed through `parse`: nothing observed confirms that
        // this id carries a SensorError at all (see SensorMessageId.ERROR), so a
        // payload that does not parse is a gap in Headway's knowledge rather than
        // a malformed message, and throwing would kill the channel over it.
        val error = runCatching { SensorError.parseFrom(payload) }.getOrNull()
        onStep(
            if (error == null) {
                "sensors: SENSOR_MESSAGE_ERROR with ${payload.size} byte(s) that are not a SensorError"
            } else {
                "sensors: ${describeType(error.sensorType)} reported ${error.sensorErrorType.name}"
            }
        )
        return SensorChannelMessage.Failure(error, payload.size)
    }

    /** Encrypted, `MessageType::SPECIFIC` — see the class KDoc for the citations. */
    private fun specific(messageId: Int, payload: ByteArray) = AapMessage(
        channelId = channelId,
        control = false,
        encrypted = true,
        messageId = messageId,
        payload = payload,
    )

    companion object {

        /**
         * `SensorRequest.min_update_period` meaning "no cap". See the parameter's
         * own KDoc for why zero rather than a number of milliseconds.
         */
        const val NO_UPDATE_CAP: Long = 0L

        /**
         * Builds the channel for a profile's advertised sensor service, or null
         * when the head unit offers none.
         *
         * Found by content — a `Service` with a `sensor_source_service` — not by
         * channel id, for the reason [dev.headway.protocol.channel.VideoChannel]
         * and its siblings give: ids belong to the head unit, and matching on
         * Headway's own [ChannelId] table would work against the emulator and
         * fail against a car that numbers things differently.
         *
         * @param connectionFor the demultiplexer's view of a channel id.
         */
        fun of(
            services: List<aap_protobuf.service.ServiceOuterClass.Service>,
            connectionFor: (Int) -> MessageChannel,
            minUpdatePeriod: Long = NO_UPDATE_CAP,
            onStep: (String) -> Unit = {},
        ): SensorChannel? {
            val service = sensorServiceOf(services) ?: return null
            val sensors = advertisedTypes(service.sensorSourceService)
            onStep(
                "sensors advertised: channel ${service.id}, ${sensors.size} sensor(s) " +
                    sensors.joinToString(prefix = "[", postfix = "]") { describeType(it) }
            )
            return SensorChannel(
                connection = connectionFor(service.id),
                channelId = service.id,
                advertised = sensors,
                minUpdatePeriod = minUpdatePeriod,
                onStep = onStep,
            )
        }

        /** The advertised sensor service, or null. */
        fun sensorServiceOf(
            services: List<aap_protobuf.service.ServiceOuterClass.Service>,
        ): aap_protobuf.service.ServiceOuterClass.Service? =
            services.firstOrNull { it.hasSensorSourceService() }

        /**
         * The sensor types a service advertises, de-duplicated and in order.
         *
         * `Sensor.sensor_type` is `required`, so a parsed entry has one — unless
         * the car sent a number outside the enum, which proto2 files into unknown
         * fields and leaves `hasSensorType()` false. Those are dropped: Headway
         * cannot request a type it cannot name, and echoing an unknown number
         * back is a good way to be answered `STATUS_INVALID_SENSOR`.
         */
        fun advertisedTypes(service: SensorSourceService): List<SensorType> =
            service.sensorsList
                .filter { it.hasSensorType() }
                .map { it.sensorType }
                .distinct()

        /**
         * Turns one `SensorBatch` into the readings it carried.
         *
         * Everything the batch did not carry is null, which is what makes
         * [CarSensors.mergedWith] the right way to accumulate: a batch is a
         * *delta*, not a snapshot.
         *
         * ## Which entry, when a field repeats
         *
         * The last. Every field in `SensorBatch` is `repeated`, and every
         * reference producer adds exactly one entry — openauto calls
         * `add_driving_status_data()` once per batch (`SensorService.cpp`
         * L147-L157) — while every reference consumer reads index 0
         * (aa-proxy-rs, e.g. `msg.fuel_data[0].fuel_level()`, `src/mitm.rs`
         * L2414). For one entry those are the same entry. For several, "last" is
         * the newer reading and "first" is the staler one, and a dashboard wants
         * the newer; no reference produces the case, so nothing is contradicted.
         *
         * ## Scale factors
         *
         * Each conversion is [CarSensors]'s, and each is cited there. Two of them
         * are confirmed by aa-proxy-rs's own comments on code that builds these
         * messages: `kms_e1` "stores kilometers in tenths (0.1 km resolution)"
         * and `tire_pressures_e2` "stores kPa in hundredths"
         * (`aa-proxy-rs/src/mitm.rs`, `send_odometer_data` and
         * `send_tire_pressure_data`).
         */
        fun decodeBatch(batch: SensorBatch): CarSensors {
            val speed = batch.speedDataList.lastOrNull()
            val rpm = batch.rpmDataList.lastOrNull()
            val fuel = batch.fuelDataList.lastOrNull()
            val tyres = batch.tirePressureDataList.lastOrNull()
            val environment = batch.environmentDataList.lastOrNull()
            val odometer = batch.odometerDataList.lastOrNull()
            val brake = batch.parkingBrakeDataList.lastOrNull()
            val night = batch.nightModeDataList.lastOrNull()
            val driving = batch.drivingStatusDataList.lastOrNull()

            // `has` guards even on the fields the schema marks `required`. A
            // successful proto2 parse does imply the required ones are present,
            // so these branches are unreachable rather than wrong -- but the
            // schema's required/optional split is not a thing this file should
            // have to be right about to avoid reporting 0 km/h for "no reading".
            return CarSensors(
                speedMetersPerSecond = speed
                    ?.takeIf { it.hasSpeedE3() }
                    ?.let { CarSensors.speedFromE3(it.speedE3) },
                cruiseEngaged = speed?.takeIf { it.hasCruiseEngaged() }?.cruiseEngaged,
                rpm = rpm
                    ?.takeIf { it.hasRpmE3() }
                    ?.let { CarSensors.rpmFromE3(it.rpmE3) },
                fuelLevel = fuel?.takeIf { it.hasFuelLevel() }?.fuelLevel,
                range = fuel?.takeIf { it.hasRange() }?.range,
                lowFuel = fuel?.takeIf { it.hasLowFuelWarning() }?.lowFuelWarning,
                tyrePressuresKpa = tyres
                    ?.tirePressuresE2List
                    ?.map { CarSensors.pressureFromE2(it) }
                    .orEmpty(),
                outsideTemperatureCelsius = environment
                    ?.takeIf { it.hasTemperatureE3() }
                    ?.let { CarSensors.temperatureFromE3(it.temperatureE3) },
                odometerKm = odometer
                    ?.takeIf { it.hasKmsE1() }
                    ?.let { CarSensors.odometerKmFromE1(it.kmsE1) },
                parkingBrake = brake?.takeIf { it.hasParkingBrake() }?.parkingBrake,
                nightMode = night?.takeIf { it.hasNightMode() }?.nightMode,
                drivingStatus = driving?.takeIf { it.hasStatus() }?.status,
            )
        }

        /** Builds the `SENSOR_MESSAGE_BATCH` message. Used by the head-unit emulator. */
        fun sensorBatch(channelId: Int, batch: SensorBatch): AapMessage = AapMessage(
            channelId = channelId,
            control = false,
            encrypted = true,
            messageId = SensorMessageId.BATCH,
            payload = batch.toByteArray(),
        )

        /** Builds the `SENSOR_MESSAGE_RESPONSE` message. Used by the head-unit emulator. */
        fun sensorResponse(channelId: Int, status: MessageStatus): AapMessage = AapMessage(
            channelId = channelId,
            control = false,
            encrypted = true,
            messageId = SensorMessageId.RESPONSE,
            payload = SensorResponse.newBuilder().setStatus(status).build().toByteArray(),
        )

        /** Builds the `SENSOR_MESSAGE_ERROR` message. Used by the head-unit emulator. */
        fun sensorError(channelId: Int, error: SensorError): AapMessage = AapMessage(
            channelId = channelId,
            control = false,
            encrypted = true,
            messageId = SensorMessageId.ERROR,
            payload = error.toByteArray(),
        )

        /**
         * A short name for a sensor type, for a log line.
         *
         * The `SENSOR_` prefix is dropped because every value has it and a log
         * line listing thirteen of them is unreadable otherwise.
         */
        fun describeType(type: SensorType): String = type.name.removePrefix("SENSOR_")

        private inline fun <T> parse(what: String, body: () -> T): T = try {
            body()
        } catch (e: Exception) {
            throw SensorChannelException("malformed $what on the sensor channel", e)
        }
    }
}

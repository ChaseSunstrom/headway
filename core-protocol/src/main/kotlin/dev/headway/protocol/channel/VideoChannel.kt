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

import aap_protobuf.service.media.shared.message.ConfigOuterClass.Config
import aap_protobuf.service.media.shared.message.MediaCodecTypeOuterClass.MediaCodecType
import aap_protobuf.service.media.shared.message.SetupOuterClass.Setup
import aap_protobuf.service.media.shared.message.StartOuterClass.Start
import aap_protobuf.service.media.shared.message.StopOuterClass.Stop
import aap_protobuf.service.media.source.message.AckOuterClass.Ack
import aap_protobuf.service.media.video.message.VideoFocusModeOuterClass.VideoFocusMode
import aap_protobuf.service.media.video.message.VideoFocusNotificationOuterClass.VideoFocusNotification
import aap_protobuf.service.media.video.message.VideoFocusReasonOuterClass.VideoFocusReason
import aap_protobuf.service.media.video.message.VideoFocusRequestNotificationOuterClass.VideoFocusRequestNotification
import dev.headway.protocol.framing.AapMessage
import dev.headway.protocol.framing.ChannelId
import dev.headway.protocol.io.FramedConnection
import dev.headway.protocol.io.MessageChannel

/** Raised when the head unit violates the video channel's expected sequence. */
class VideoChannelException(message: String) : RuntimeException(message)

/**
 * The **phone** side of the video channel: Headway encodes the screen, the head
 * unit decodes and displays it.
 *
 * Every reference implements the head-unit half of this exchange, so the
 * polarity here is inverted relative to aasdk and openauto: what
 * `aasdk/src/Channel/MediaSink/Video/VideoMediaSinkService.cpp` *receives*, this
 * class *sends*. AACS's `AAServer/src/VideoChannelHandler.cpp` is the only
 * reference on this side, and it is the source for the two hand-encoded payloads
 * cited below.
 *
 * ## Sequence
 *
 * ```text
 *  phone (Headway)                          head unit
 *  ---------------                          ---------
 *  Setup    { H264_BP }        0x8000 -->
 *                              <-- 0x8003   Config { status, max_unacked, indices }
 *                              <-- 0x8008   VideoFocusNotification { PROJECTED }
 *  Start    { session, index }  0x8001 -->
 *  CodecConfig (SPS/PPS)        0x0001 -->
 *                              <-- 0x8004   Ack { session, 1 }
 *  Data (pts + Annex-B)         0x0000 -->
 *                              <-- 0x8004   Ack { session, 1 }
 *  ...
 *  Stop {}                      0x8002 -->
 * ```
 *
 * openauto chains the focus notification onto the successful send of the Config
 * response (`openauto/src/autoapp/Service/MediaSink/VideoMediaSinkService.cpp`
 * L120-L125), and AACS's phone side treats the arrival of `0x8008` — not the
 * Config — as its cue to send Start
 * (`AACS/AAServer/src/VideoChannelHandler.cpp` L181-L183). Headway waits for the
 * Config, which carries the information Start needs, and tolerates the focus
 * notification arriving on either side of it. [awaitConfig] and [awaitAck]
 * therefore skip over events they are not waiting for rather than failing on
 * them.
 *
 * ## Ownership of the connection
 *
 * This class reads directly from [connection] and refuses messages addressed to
 * another channel. A session that runs video alongside audio and input needs a
 * demultiplexer above [FramedConnection] that fans messages out by channel id;
 * that router does not exist yet, so for now a [VideoChannel] owns its
 * connection. The refusal is deliberate — silently dropping another channel's
 * message would lose data that a router will later need.
 *
 * ## Encryption and message type
 *
 * Every video message is sent encrypted and with aasdk's `MessageType::SPECIFIC`
 * — i.e. *without* the control flag — except the `ChannelOpenResponse`, which is
 * the head unit's to send and belongs to the control message space
 * (`aasdk/src/Channel/MediaSink/Video/VideoMediaSinkService.cpp` L53-L54 CONTROL
 * vs L66-L67, L79-L80, L94-L95 SPECIFIC). AACS agrees on the encryption
 * (`FrameType::Bulk | EncryptionType::Encrypted` on every video send,
 * `AACS/AAServer/src/VideoChannelHandler.cpp` L138-L145, L152-L161, L44-L46) but
 * names the flag bit the other way round; see [dev.headway.protocol.framing.FrameHeader]
 * for that disagreement, which is settled in favour of aasdk.
 */
class VideoChannel(
    private val connection: MessageChannel,
    /**
     * The channel number the head unit assigned to its video service.
     *
     * **Not a protocol constant.** aasdk's own header says AA channel ids are
     * dynamic and that its static table is an implementation convenience
     * (`aasdk/include/aasdk/Messenger/ChannelId.hpp` L22-L27); the authoritative
     * value is the `Service.id` the head unit advertised in its
     * `ServiceDiscoveryResponse`. The default is Headway's own advertised
     * assignment, and callers with a real profile should pass the advertised id.
     */
    val channelId: Int = ChannelId.MEDIA_SINK_VIDEO.id,
    /** Narrates each step; wired to the debug log export in the app. */
    private val onStep: (String) -> Unit = {},
) {

    /** What the head unit answered [sendSetup] with. */
    data class SetupResponse(
        val status: Config.Status,
        /**
         * Flow-control window: how many media messages may be outstanding
         * before the phone must wait for an [Ack]. Null when the head unit left
         * the optional field unset. openauto sends 1
         * (`openauto/src/autoapp/Service/MediaSink/VideoMediaSinkService.cpp` L117).
         */
        val maxUnacked: Int?,
        /**
         * Indices into the `video_configs` list the head unit advertised in its
         * `ServiceDiscoveryResponse` that it is willing to receive. openauto and
         * aa-proxy-rs both send exactly `[0]`
         * (`openauto/.../VideoMediaSinkService.cpp` L118;
         * `aa-proxy-rs/src/display.rs` L308-L316).
         */
        val configurationIndices: List<Int>,
    ) {
        /** True when the head unit's video output initialised and it will accept frames. */
        val ready: Boolean get() = status == Config.Status.STATUS_READY
    }

    /** Something the head unit sent on this channel. */
    sealed interface Event {
        /** The setup response, message id `0x8003`. */
        data class ConfigReceived(val response: SetupResponse) : Event

        /** A media acknowledgement, message id `0x8004`. */
        data class Acknowledged(val sessionId: Int, val ack: Int?) : Event

        /** A focus notification, message id `0x8008`. */
        data class FocusChanged(val focus: VideoFocusMode?, val unsolicited: Boolean) : Event

        /**
         * A message id this channel does not implement.
         *
         * aasdk logs and re-arms its receive rather than tearing the channel
         * down (`aasdk/src/Channel/MediaSink/Video/VideoMediaSinkService.cpp`
         * L128-L132), and the id space extends past what aasdk itself knows
         * about (`aa-proxy-rs/src/protos/protos.proto` L1592-L1601), so an
         * unknown id is surfaced rather than treated as an error.
         */
        data class Unhandled(val messageId: Int, val payload: ByteArray) : Event
    }

    /** The head unit's setup response, once [awaitConfig] has read it. */
    var setupResponse: SetupResponse? = null
        private set

    /** The session id passed to [sendStart]; the head unit echoes it in every [Ack]. */
    var sessionId: Int? = null
        private set

    /** The video configuration index passed to [sendStart]. */
    var configurationIndex: Int? = null
        private set

    /** Latest focus state the head unit reported, or null if it has not reported one. */
    var videoFocus: VideoFocusMode? = null
        private set

    /** Media messages sent but not yet acknowledged. Bounded by [SetupResponse.maxUnacked]. */
    var unacknowledgedMessages: Int = 0
        private set

    /** Media messages sent — codec configuration plus frames. */
    var mediaMessagesSent: Long = 0L
        private set

    /** Acknowledgements read off the channel. */
    var acksReceived: Long = 0L
        private set

    // --- setup --------------------------------------------------------------

    /**
     * Sends the setup request, message id `0x8000`.
     *
     * `Setup { required MediaCodecType type = 1 }` with
     * `MEDIA_CODEC_VIDEO_H264_BP` (3) serialises to the two bytes `08 03`, which
     * is exactly what AACS pushes by hand
     * (`AACS/AAServer/src/VideoChannelHandler.cpp` L138-L145) — independent
     * confirmation of both the field number and the codec value.
     */
    suspend fun sendSetup(codec: MediaCodecType = MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP) {
        connection.send(
            specific(AvMessageId.SETUP, Setup.newBuilder().setType(codec).build().toByteArray())
        )
        onStep("setup requested (${codec.name})")
    }

    /**
     * Reads until the head unit's `Config` arrives and returns it.
     *
     * A `STATUS_WAIT` response is returned rather than thrown on: it means the
     * head unit's video output has not initialised yet, which is a state the
     * caller may want to retry through, and no reference specifies what follows
     * it. Any focus notification that arrives first is recorded in [videoFocus].
     */
    suspend fun awaitConfig(): SetupResponse {
        while (true) {
            when (val event = receiveEvent()) {
                is Event.ConfigReceived -> return event.response
                else -> Unit
            }
        }
    }

    /**
     * Sends the start indication, message id `0x8001`.
     *
     * `Start { required int32 session_id = 1; required uint32 configuration_index = 2 }`.
     * With both fields zero this serialises to `08 00 10 00`, the bytes AACS
     * hand-encodes (`AACS/AAServer/src/VideoChannelHandler.cpp` L152-L161).
     *
     * @param configurationIndex must be one of the indices the head unit offered
     *   in [SetupResponse.configurationIndices]; it selects which advertised
     *   `VideoConfiguration` the phone will encode for.
     */
    suspend fun sendStart(sessionId: Int, configurationIndex: Int) {
        val offered = setupResponse?.configurationIndices
        if (offered != null && offered.isNotEmpty() && configurationIndex !in offered) {
            throw VideoChannelException(
                "configuration index $configurationIndex was not offered by the head unit " +
                    "(it offered $offered)"
            )
        }
        connection.send(
            specific(
                AvMessageId.START,
                Start.newBuilder()
                    .setSessionId(sessionId)
                    .setConfigurationIndex(configurationIndex)
                    .build()
                    .toByteArray(),
            )
        )
        this.sessionId = sessionId
        this.configurationIndex = configurationIndex
        onStep("started session $sessionId on configuration $configurationIndex")
    }

    /** Sends the stop indication, message id `0x8002`, whose payload is the empty `Stop`. */
    suspend fun sendStop() {
        connection.send(specific(AvMessageId.STOP, Stop.newBuilder().build().toByteArray()))
        onStep("stopped session ${sessionId ?: "-"}")
    }

    // --- media --------------------------------------------------------------

    /**
     * Sends the H.264 codec configuration (SPS/PPS), message id `0x0001`.
     *
     * **No timestamp header.** aasdk routes this id straight to
     * `onMediaIndication` with no offset applied
     * (`aasdk/src/Channel/MediaSink/Video/VideoMediaSinkService.cpp` L121-L123);
     * prefixing eight bytes here would leave the head unit decoding the SPS from
     * the middle of the timestamp.
     *
     * It counts against the acknowledgement window because openauto funnels it
     * into the same handler as a timestamped frame — `onMediaIndication` calls
     * `onMediaWithTimestampIndication(0, buffer)`, which acks
     * (`openauto/src/autoapp/Service/MediaSink/VideoMediaSinkService.cpp`
     * L190-L192 into L171-L187). A phone that did not expect that ack would run
     * one ack ahead for the rest of the session.
     */
    suspend fun sendCodecConfig(spsPps: ByteArray) {
        awaitCredit()
        connection.send(MediaFrame.codecConfig(channelId, spsPps))
        mediaMessagesSent++
        unacknowledgedMessages++
        onStep("codec config sent (${spsPps.size} B)")
    }

    /**
     * Sends one access unit, message id `0x0000`: an 8-byte big-endian
     * microsecond presentation timestamp followed by the H.264 Annex-B bytes.
     * See [MediaFrame] for the layout and its citations.
     *
     * Suspends first if [SetupResponse.maxUnacked] frames are already
     * outstanding, consuming acknowledgements until the window reopens. With
     * openauto's window of 1 that makes the channel strictly lock-step: one
     * frame in flight at a time.
     */
    suspend fun sendFrame(h264: ByteArray, timestampMicros: Long) {
        awaitCredit()
        connection.send(MediaFrame.data(channelId, h264, timestampMicros))
        mediaMessagesSent++
        unacknowledgedMessages++
    }

    /**
     * Reads until an acknowledgement arrives and returns it.
     *
     * The head unit acks with `Ack { session_id = <the id from Start>, ack = 1 }`
     * (`openauto/.../VideoMediaSinkService.cpp` L177-L186). aa-proxy-rs sends an
     * incrementing counter in the same field instead
     * (`aa-proxy-rs/src/display.rs` L324-L331), so the value is reported but not
     * checked — nothing in either reference reacts to it.
     */
    suspend fun awaitAck(): Event.Acknowledged {
        while (true) {
            when (val event = receiveEvent()) {
                is Event.Acknowledged -> return event
                else -> Unit
            }
        }
    }

    /** Consumes acknowledgements until nothing is outstanding. */
    suspend fun awaitAllAcks() {
        while (unacknowledgedMessages > 0) {
            awaitAck()
        }
    }

    // --- video focus --------------------------------------------------------

    /**
     * Asks the head unit to change video focus, message id `0x8007`.
     *
     * `VideoFocusRequestNotification { disp_channel_id = 1 [deprecated],
     * mode = 2, reason = 3 }`. Field 1 is deprecated in the schema and openauto
     * reads it only to log it (`openauto/.../VideoMediaSinkService.cpp`
     * L207-L214), so Headway leaves it unset.
     *
     * The head unit answers with a `VideoFocusNotification`; openauto answers
     * every request with `VIDEO_FOCUS_PROJECTED` regardless of what was asked
     * for, and separately acts on `VIDEO_FOCUS_NATIVE` by relinquishing the
     * display (`openauto/.../VideoMediaSinkService.cpp` L216-L231, L233-L245).
     */
    suspend fun requestVideoFocus(
        mode: VideoFocusMode,
        reason: VideoFocusReason = VideoFocusReason.UNKNOWN,
    ) {
        connection.send(
            specific(
                AvMessageId.VIDEO_FOCUS_REQUEST,
                VideoFocusRequestNotification.newBuilder()
                    .setMode(mode)
                    .setReason(reason)
                    .build()
                    .toByteArray(),
            )
        )
        onStep("video focus requested: ${mode.name} (${reason.name})")
    }

    /** Reads until the head unit reports a focus state and returns it. */
    suspend fun awaitFocus(): Event.FocusChanged {
        while (true) {
            when (val event = receiveEvent()) {
                is Event.FocusChanged -> return event
                else -> Unit
            }
        }
    }

    // --- receive ------------------------------------------------------------

    /**
     * Reads exactly one message from the channel and decodes it, updating the
     * channel's state as a side effect.
     */
    suspend fun receiveEvent(): Event {
        val message = connection.receive()
        if (message.channelId != channelId) {
            throw VideoChannelException(
                "message for ${ChannelId.describe(message.channelId)} arrived on the video " +
                    "channel's connection; a session with more than one open channel needs a " +
                    "router above FramedConnection"
            )
        }

        return when (message.messageId) {
            AvMessageId.CONFIG -> {
                val config = Config.parseFrom(message.payload)
                val response = SetupResponse(
                    status = config.status,
                    maxUnacked = if (config.hasMaxUnacked()) config.maxUnacked else null,
                    configurationIndices = config.configurationIndicesList.toList(),
                )
                setupResponse = response
                onStep(
                    "config: ${response.status.name}, window ${response.maxUnacked ?: "unstated"}, " +
                        "indices ${response.configurationIndices}"
                )
                Event.ConfigReceived(response)
            }

            AvMessageId.ACK -> {
                val ack = Ack.parseFrom(message.payload)
                acksReceived++
                if (unacknowledgedMessages > 0) unacknowledgedMessages--
                Event.Acknowledged(
                    sessionId = ack.sessionId,
                    ack = if (ack.hasAck()) ack.ack else null,
                )
            }

            AvMessageId.VIDEO_FOCUS_NOTIFICATION -> {
                val notification = VideoFocusNotification.parseFrom(message.payload)
                val focus = if (notification.hasFocus()) notification.focus else null
                videoFocus = focus
                onStep("video focus: ${focus?.name ?: "unstated"}")
                Event.FocusChanged(focus, notification.unsolicited)
            }

            else -> {
                onStep("unhandled ${AvMessageId.describe(message.messageId)}")
                Event.Unhandled(message.messageId, message.payload)
            }
        }
    }

    // --- helpers ------------------------------------------------------------

    /**
     * Blocks until the acknowledgement window has room.
     *
     * No reference states what an absent or zero `max_unacked` means. Headway
     * treats both as "no window" and sends freely; that is an inference, and the
     * alternative — treating a zero window as "one frame at a time" — would be
     * an equally unsupported guess that additionally risks a stall if the head
     * unit never acks.
     */
    private suspend fun awaitCredit() {
        val window = setupResponse?.maxUnacked ?: return
        if (window <= 0) return
        while (unacknowledgedMessages >= window) {
            awaitAck()
        }
    }

    /** All video messages are encrypted and carry `MessageType::SPECIFIC`, i.e. no control flag. */
    private fun specific(messageId: Int, payload: ByteArray) = AapMessage(
        channelId = channelId,
        control = false,
        encrypted = true,
        messageId = messageId,
        payload = payload,
    )
}

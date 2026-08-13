<!--
This file is part of Headway.
Copyright (C) 2026 The Headway Authors
SPDX-License-Identifier: GPL-3.0-or-later
-->

# Android Auto Protocol — implementation notes

Every protocol constant Headway relies on, with a citation to the file it was
read from.

## Why this document exists

`CLAUDE.md` hard constraint 4:

> **Do not guess protocol constants from memory.** Every message ID, port
> number, protobuf schema, channel ID, and handshake sequence must be extracted
> from the reference implementations listed below and recorded in
> `docs/protocol-notes.md` with a citation to the source file you derived it
> from.

AAP is reverse-engineered. There is no specification to check against, so a
constant that "looks right" is worthless and a constant that came from a
plausible-sounding recollection is worse than worthless — it produces a head unit
that fails in a way nobody can debug. Everything below was read out of a file in
`references/`, and the citation is how you check it.

## How to read the citations

Paths are relative to `references/`, which is gitignored. Clone the sources with
the commands in the [README](../README.md#working-on-the-protocol) to follow
along. Line numbers refer to the `HEAD` of each repository at the time of
extraction; they drift, so treat the symbol name as authoritative and the line
number as a starting point.

Where the references disagree, `CLAUDE.md` directs us to prefer aasdk's
protobufs and record the discrepancy. Each section ends with the disagreements
found in that area — those subsections are the most valuable part of this
document, because every one of them is a place a naive implementation silently
does the wrong thing.

## Verification status

Constants were extracted by reading the reference sources, then re-checked
against those files. Confidence is not uniform, and pretending otherwise would
defeat the purpose of the document:

| Section | Status |
|---|---|
| 1. Framing | **Implemented, pinned by byte fixtures, and independently re-verified.** 61 of 66 constants confirmed exactly against the cited files; every numeric value checked out. The 5 exceptions are citation-precision problems, not wrong values — see below. |
| 2. Version handshake and TLS | Extracted and cited; not yet exercised against a live peer. |
| 3. Wireless Bluetooth handshake | Extracted and cited, and **partly confirmed against a real vehicle**: the RFCOMM service UUID, the framing and the `WifiVersionRequest` field layout are all corroborated by a live capture from a 2021 Chevrolet Infotainment 3 unit. The RFCOMM UUID and TCP port were also confirmed by hand in three and two references respectively. |
| 4. Control channel and service discovery | Extracted and cited; not yet exercised against a live peer. |
| 5. Video channel | Extracted and cited; not implemented (Phase 2). |
| 6. Input channel | Extracted and cited; not implemented (Phase 3). |

Sections 5 and 6 are recorded ahead of the phases that use them so the extraction
happens once, while the references are to hand, rather than being repeated later.
The audio and car-microphone channels are not yet documented.

### Framing constants flagged on re-verification

None of these changes a value Headway depends on; they are recorded so the next
reader is not misled by an imprecise citation.

- **Fragment type selection** — the original wording ("remaining-size > 0") reads
  ambiguously. The actual source is
  `frameType = offset_ == 0 ? FIRST : (remainingSize_ - size > 0 ? MIDDLE : LAST)`
  (`aasdk/src/Messenger/MessageOutStream.cpp` L70-73): MIDDLE when bytes remain
  *after this chunk*, LAST otherwise. `MessageFragmenter` implements the
  equivalent `offset + size == total → LAST`.
- **Message-complete condition** — the quoted guard dropped a conjunct
  (`isValidFrame_`). Harmless: that flag is only ever assigned true.
- **aa-proxy-rs message-id position / presence rule** — the cited lines sit
  inside `ssl_trace_msg_hint()` and `ssl_decapsulate_write()`, which are a debug
  trace formatter and a decapsulation path rather than the wire-format
  definition. The values agree with aasdk, which is the authority here; treat
  those two rows as corroboration, not as primary sources.
- **AACS client reassembly loop** — cited correctly but described more strongly
  than the code supports.

No vehicle, phone, or radio exists in the build environment — see
[`BLOCKERS.md`](../BLOCKERS.md) B-001 — so a green CI run demonstrates
self-consistency, not compatibility with a Chevrolet Infotainment 3 unit. The one
exception is the Bluetooth version exchange, which has now been observed against
a real vehicle; that capture is below.

## Evidence from a real head unit

A 2021 Chevrolet Infotainment 3 unit was captured over RFCOMM from a phone
running Headway. This is the only real-hardware evidence in the project, and it
falsified an assumption the code was built on.

### The capture

```text
rx id=4 (WIFI_VERSION_REQUEST)   08 01 10 00 18 00 20 f1 2c
tx id=5 (WIFI_VERSION_RESPONSE)  08 01 10 01 20 02
                                 ...head unit then sent nothing further
```

Decoded under the field layout in §3.2 — which this document had recorded
correctly all along, from `aa-proxy-rs/src/bluetooth.rs` L1438-L1493:

| Field | Wire | Value | Meaning |
|---|---|---|---|
| 1 | varint | 1 | `major_version` |
| 2 | varint | 0 | `minor_version` |
| 3 | varint | 0 | `supported_wifi_channels[0]`, a placeholder |
| 4 | varint | 5745 | a second frequency in MHz — 5745 MHz is 5 GHz channel 149 |

Note 5745 is a **frequency, not a port**. The resemblance to a TCP port number is
a coincidence worth naming, because it is an easy misreading.

### What went wrong, and why the citation did not prevent it

Headway's reply set field 4 to `2`. Field 4 of the *response* is a **status**, and
`STATUS_SUCCESS` is `0` (`aa-proxy-rs/src/bluetooth.rs` L1619-L1639, and §3.2).
The head unit read a non-success status, concluded the phone had declined, and
stopped — with no error message, because from its point of view nothing had gone
wrong.

The failure was not a missing citation. §3.2 already carried the correct field
layout for both messages. The failure was that the *code* was generated from
aasdk's schemas instead, and aasdk

- declares `WifiVersionRequest` as an **empty message**, so the four fields the
  car actually sent decoded to nothing and there was no way to notice; and
- names the response fields `unknown_value_a` through `unknown_value_d`, so
  field 4 looked like a free slot rather than a status.

Preferring aasdk's protobufs is the rule in `CLAUDE.md`, and it is the right
default — but aasdk is explicitly *unfinished* for these two messages, and an
"unknown" field name is not a licence to put anything in it. The corrected
schemas live in `core-protocol/src/main/proto/headway/aaw_version.proto`, which
cites this section, and the captured bytes above are pinned as a test fixture in
`WirelessHandshakeTest` so the decode cannot regress.

### What Headway now sends, and what it deliberately does not

The response carries fields 1, 2 and 4 — the three aasdk marks `required` — with
the major and minor mirrored back from what the head unit announced, and the
status set to `0`.

Field 5, `selected_wifi_channel_type`, is **left out by default**. aa-proxy-rs
reads it from real phone-side frames, so real phones do send it, but no reference
records an observed *value*, and the name says "channel type", which is not
obviously the MHz frequency the head unit advertised. aasdk's schema stops at
field 4 and does not have the field at all. Filling an unverified field with a
plausible-looking number is precisely what produced the bug above, one field
earlier, so the `announceSelectedWifiChannel` flag on `WirelessHandshake` exists
and is off. Turn it on only against a log from a unit that needs it.

### The second capture: the car answers, but never offers an endpoint

With the status corrected, the same vehicle produced a complete credentials
exchange — and revealed a second, independent problem.

```text
rx id=4 (WIFI_VERSION_REQUEST)   08 01 10 00 18 00 20 f1 2c
tx id=5 (WIFI_VERSION_RESPONSE)  08 01 10 00 20 00
                                 ...6.2s of silence...
tx id=2 (WIFI_INFO_REQUEST)
rx id=3 (WIFI_INFO_RESPONSE)     0a 0f 6d 79 43 68 ... 20 08
                                 ...and nothing further, ever.
```

The `WifiInfoResponse` decodes cleanly under aasdk's schema:

| Field | Wire | Value |
|---|---|---|
| 1 | string | SSID |
| 2 | string | passphrase |
| 3 | string | BSSID (note: *not* the same as the Bluetooth MAC) |
| 4 | varint | `security_mode` = 8 |
| 5 | — | `access_point_type` absent |

Two things follow, and both are behavioural rather than schema-level.

**The head unit does not speak first here.** It sent its version request, then
waited. It answers `WifiInfoRequest` whenever asked — it was asked twice and
answered identically both times — but volunteers nothing. openauto's head unit
sends `WifiVersionRequest` and `WifiStartRequest` back to back without waiting
for the version response at all
(`openauto/src/btservice/AndroidBluetoothServer.cpp` L79-L86), and answers
`WifiInfoRequest` in any order (L160-L178). A phone that waits to be offered
credentials therefore works against openauto and hangs against this Chevrolet.
Headway now asks immediately after answering the version request.

**`WifiStartRequest` is sent inconsistently.** In this capture there is none at
all: no endpoint arrives over Bluetooth, and none is carried in the version
request either — the request is nine bytes with no field 5. aa-proxy-rs handles
units that omit `WifiStartRequest` by synthesising one from the projection
endpoint (`bluetooth.rs` L2042-L2060), but that requires an endpoint to exist
somewhere, and in this capture none does.

A later capture from the same vehicle **does** contain one:

```text
rx id=1 (WIFI_START_REQUEST)   0a 0b "192.168.5.1" 10 d9 36
                               -> 192.168.5.1:7001
```

So the absence is not a property of this head unit, and code must not treat it
as one. **7001 is the observed AAP port for Chevrolet Infotainment 3**, against
the reference default of 5288 (§3.2) — which is why the port a unit names is
remembered per Bluetooth address rather than being re-guessed each session. The
correlation worth recording is that the captures carrying `WifiStartRequest` are
the ones where the access point was up; a unit that names where to connect is a
unit that is ready to project.

So the endpoint is not obtainable over Bluetooth for this unit. It is, however,
*discoverable*: the head unit hosts the access point, so it is the DHCP server
on its own network, and `LinkProperties.getDhcpServerAddress()` on the joined
`Network` names it without a guess. The default-route gateway is the fallback,
and the port defaults to 5288 (§3.2) and is configurable. `CarNetworkCredentials`
therefore carries a **nullable** endpoint: absent is normal, not an error.

### The third capture: reachable, and refusing

With `CHANGE_NETWORK_STATE` declared, the phone joined the car's access point and
found the head unit without being told where it was:

```text
tx id=2 (WIFI_INFO_REQUEST)
rx id=3 (WIFI_INFO_RESPONSE)   SSID 'myChevrolet1189'
requesting myChevrolet1189 (ce:44:26:bf:18:ec)
joined myChevrolet1189                          ...7.6s later
head unit is the DHCP server at 192.168.5.1
opening the AAP session to 192.168.5.1:5288
  -> ECONNREFUSED
```

`ECONNREFUSED` is the useful part. The head unit answered — it is on the network,
routing works, the address is right — and only refused because nothing was bound
to the port. A wrong address gives a timeout; this is a live host saying no.

The cause is a missing acknowledgement, not a missing address. aa-proxy-rs drives
genuine Android Auto phones, and its `send_params`
(`aa-proxy-rs/src/bluetooth.rs` L6207-L6245) records what a real phone sends:

```text
head unit -> phone   WifiStartRequest {ip_address, port}
phone -> head unit   WifiInfoRequest
head unit -> phone   WifiInfoResponse {ssid, key, bssid, ...}
phone -> head unit   WifiStartResponse     <- status at field 3
phone -> head unit   WifiConnectStatus     <- status at field 1
```

Both of those last two are **read blocking** by the head-unit side, so a unit
that follows this sequence is still waiting for them when a phone that sends
neither goes ahead and connects. It has not been told the phone is on its
network, so it has no reason to be accepting AAP connections yet.

Note the status field numbers differ between the two messages and are not
interchangeable: `WifiStartResponse.status` is field 3 because field 1 is
`ip_address` (aa-proxy-rs comments on exactly this trap at L917-L920), while
`WifiConnectionStatus.status` is field 1. Both confirmed against the vendored
`aap_protobuf/aaw/WifiStartResponse.proto` and `WifiConnectionStatus.proto`.

Headway now sends `WifiStartResponse` on receiving the credentials, and
`WifiConnectionStatus` after the Wi-Fi association — the latter necessarily
after, since it is the announcement that the association happened, which means
the RFCOMM link has to stay open across the join.

### The field 4/5 ambiguity, and the trap in it

Two schema revisions circulate. Decompiled Gearhead puts
`WifiProjectionProtocolInfo` at request field 5; MBUX captures put `HeadUnitInfo`
there and a frequency varint at field 4. The observed Chevrolet matches the MBUX
shape.

Both `WifiProjectionProtocolInfo` and `HeadUnitInfo` begin with a
length-delimited string at field 1 — `ip_address` in one, `car_make` in the
other. So a `HeadUnitInfo` read as a projection endpoint yields
`ip_address = "Chevrolet"` and no port, and an unguarded implementation will try
to connect to it. Headway requires the address to parse as an IP literal and the
port to be non-zero before believing it, mirroring
`wifi_projection_info_looks_valid` (`aa-proxy-rs/src/bluetooth.rs` L1412-L1421).
Deliberately not `InetAddress.getByName`, which would resolve a non-literal —
and the phone is attached to a car with no internet, where a DNS attempt is a
stall rather than an answer.

## The one thing to check first when a real car fails

If a real head unit rejects the session, check the **`CONTROL` flag polarity**
(§1.4) before anything else. aasdk and AACS name bit 2 of the flags byte in
opposite ways, both self-consistently. We follow aasdk. If that choice is wrong,
essentially every message is misrouted and the failure will look like a generic
handshake timeout rather than like a flag bug.

---

## 1. Framing and channel multiplexing

The byte-level wire format: frame headers, flags, fragmentation, channel ids. This section is implemented in `core-protocol` and pinned by byte fixtures in `FrameHeaderTest` / `MessageFramingTest`.

### 1.1 Sequence

```text
WIRE LAYOUT (all multi-byte fields big-endian):

  offset 0 : uint8  channel id        (aasdk: aasdk/src/Messenger/FrameHeader.cpp L26 — `channelId_ = static_cast<ChannelId>(buffer.cdata[0]);`)
  offset 1 : uint8  flags             (aasdk/src/Messenger/FrameHeader.cpp L27-L29, L57-L59)
                    bit0 0x01 FIRST   (aasdk/include/aasdk/Messenger/FrameType.hpp L29)
                    bit1 0x02 LAST    (FrameType.hpp L30); 0x03 = BULK (single frame); 0x00 = MIDDLE
                    bit2 0x04 CONTROL vs SPECIFIC (aasdk/include/aasdk/Messenger/MessageType.hpp L26-L29)
                    bit3 0x08 ENCRYPTED (aasdk/include/aasdk/Messenger/EncryptionType.hpp L26)
                    bits4-7 unused/ignored by every reference reader
  offset 2 : uint16 BE frame payload size — bytes on the wire for THIS frame; when the ENCRYPTED bit is set this is the ciphertext (TLS record) length (aasdk/src/Messenger/FrameSize.cpp L38, L51; aa-proxy-rs/src/mitm.rs L1075-L1076)
  offset 4 : uint32 BE total size — PRESENT ONLY when (flags & 0x03) == 0x01 (exactly FIRST, not BULK). Total plaintext length of the entire reassembled message (aasdk/src/Messenger/FrameSize.cpp L44, L56; MessageOutStream.cpp L118 passes `message_->getPayload().size()`; aa-proxy-rs/src/mitm.rs L1076-L1082; AACS/AAServer/src/AaCommunicator.cpp L433-L437)
  offset 4 or 8 : payload, `frame payload size` bytes

Header is therefore 4 bytes normally, 8 bytes on exactly-FIRST frames. Confirmed independently by aasdk (2 + FrameSize::getSizeOf() = 2+2 or 2+6, aasdk/src/Messenger/FrameSize.cpp L73-L75), aa-proxy-rs (HEADER_LENGTH=4, +4 on FIRST, src/mitm.rs L139 & L3929-L3941), WirelessAndroidAutoDongle (proxyHandler.cpp L41-L53), and AACS (AAServer/src/AaCommunicator.cpp L420-L422).

SEND PATH (aasdk/src/Messenger/MessageOutStream.cpp):
 1. Channel builds a Message with (ChannelId, EncryptionType, MessageType) and inserts the 2-byte BE message id into the payload first, then the serialized protobuf (ControlServiceChannel.cpp L83-L85; MessageId.cpp L38-L42).
 2. MessageOutStream::stream — if payload.size() >= 0x4000, fragment; else emit one BULK frame (L44-L50).
 3. Per fragment: size = min(remaining, 0x4000); frameType = FIRST at offset 0, MIDDLE while bytes remain after this chunk, LAST for the final chunk (L70-L73).
 4. compoundFrame: emit 2-byte header, reserve 2 or 6 bytes for size, encrypt (or copy) the plaintext chunk, then back-patch the size region via memcpy at offset FrameHeader::getSizeOf() (L103-L127). FIRST gets FrameSize(payloadSize, totalSize); everything else gets FrameSize(payloadSize).

RECEIVE PATH (aasdk/src/Messenger/MessageInStream.cpp):
 1. Read exactly FrameHeader::getSizeOf() == 2 bytes (L49, L177).
 2. Parse channel id and flags; look up the per-channel partial message in std::map<ChannelId, Message::Pointer> messageBuffer_ (L67). FIRST/BULK discards any existing partial and starts fresh; MIDDLE/LAST with no partial is rejected as MESSENGER_INTERTWINED_CHANNELS (L75-L94).
 3. Read FrameSize::getSizeOf(FIRST ? EXTENDED : SHORT) == 6 or 2 more bytes (L97-L98, L112).
 4. Read exactly frameSize.getFrameSize() payload bytes (L128-L130). NOTE: aasdk records only frameSize_; totalSize is parsed but never used for reassembly.
 5. If ENCRYPTED, cryptor_->decrypt(payload, buffer, frameSize_) appends plaintext (frameLength - 29 bytes expected, Cryptor.cpp L149-L151); else insertPayload appends raw bytes (L133-L147).
 6. If frame type is BULK or LAST, resolve the message upward; otherwise store the partial back into messageBuffer_ and loop to step 1 (L149-L178).
 7. Messenger routes the resolved Message to whichever channel's receive promise is pending for that ChannelId, or queues it (aasdk/src/Messenger/Messenger.cpp L67-L80 of file / inStreamMessageHandler).
 8. The receiving channel reads MessageId from payload[0..2] big-endian and hands protobuf the payload from offset 2 (ControlServiceChannel.cpp L192-L193).

MESSAGE ID PLACEMENT: the 2-byte BE message id sits at the start of the REASSEMBLED plaintext message, i.e. only in the FIRST (or BULK) fragment's plaintext. MIDDLE/LAST fragments carry raw continuation bytes with no message id (aa-proxy-rs/src/mitm.rs L890-L902).

CHANNEL MULTIPLEXING: byte 0 is the channel id. Channel 0 is CONTROL. Channels are advertised, not fixed by the protocol: each service in ServiceDiscoveryResponse carries `required int32 id = 1` (aasdk/protobuf/aap_protobuf/service/Service.proto L23), and openauto sets it from the aasdk ChannelId (openauto/src/autoapp/Service/MediaSink/VideoMediaSinkService.cpp L76). aasdk's ChannelId enum is a static convention and the header itself notes AA channel ids are really dynamic (aasdk/include/aasdk/Messenger/ChannelId.hpp L22-L27). NOTE: aasdk's enum is CONTROL, INPUT, SENSOR, VIDEO, MEDIA_AUDIO, SPEECH_AUDIO, SYSTEM_AUDIO, AV_INPUT, BLUETOOTH, NAVIGATION, MEDIA_STATUS, NONE=255 -- it is NOT the CONTROL=0..WIFI_PROJECTION=18 list Headway uses, which this document previously implied. Headway's list is its own assignment; only CONTROL=0 is corroborated. See ChannelId.kt's KDoc. Frames from different channels may interleave at frame granularity; each channel independently accumulates its own partial message.
```

### 1.2 Constants

| Constant | Value | Meaning | Source |
|---|---|---|---|
| `FrameHeader::getSizeOf()` | 2 | The fixed AAP frame header is exactly 2 bytes: byte[0] = channel id, byte[1] = flags. Everything after it (frame size, optional total size, payload) is separate. | `aasdk/include/aasdk/Messenger/FrameHeader.hpp` L30-L53 |
| `FRAME_HEADER_BYTE0_CHANNEL_ID` | buffer.cdata[0] | Byte 0 of every frame is the raw 8-bit channel id (service id). It is not masked or shifted. | `aasdk/src/Messenger/FrameHeader.cpp` L25-L30 |
| `FRAME_HEADER_BYTE1_FLAGS_COMPOSITION` | encryptionType \| messageType \| frameType | Byte 1 of every frame is a bitwise OR of the encryption bit (0x08), the message-type bit (0x04) and the 2-bit frame type (0x03). Serialization order in getData() confirms channel id first, flags second. | `aasdk/src/Messenger/FrameHeader.cpp` L54-L62 |
| `FrameType::MIDDLE` | 0 | Frame-type bits (mask 0x03) in flags byte. MIDDLE = neither FIRST nor LAST set: a continuation fragment in the middle of a multi-frame message. | `aasdk/include/aasdk/Messenger/FrameType.hpp` L27-L32 |
| `FrameType::FIRST` | 1 (0x01) | Bit 0 of the flags byte. First fragment of a multi-frame message. A frame whose masked frame-type equals exactly FIRST carries the extra 4-byte total-size field after the 2-byte frame size. | `aasdk/include/aasdk/Messenger/FrameType.hpp` L27-L32 |
| `FrameType::LAST` | 2 (0x02) | Bit 1 of the flags byte. Final fragment of a multi-frame message; on receipt the accumulated message is complete and is dispatched. | `aasdk/include/aasdk/Messenger/FrameType.hpp` L27-L32 |
| `FrameType::BULK` | 3 (0x03) | FIRST\|LAST both set = a complete, unfragmented single-frame message. Also used as the frame-type mask when parsing byte 1. | `aasdk/include/aasdk/Messenger/FrameType.hpp` L27-L32 |
| `EncryptionType::PLAIN` | 0 | Encryption bit clear in the flags byte: payload is cleartext (used for version request/response and the encapsulated-SSL handshake messages). | `aasdk/include/aasdk/Messenger/EncryptionType.hpp` L24-L27 |
| `EncryptionType::ENCRYPTED` | 8 (0x08) | Bit 3 of the flags byte. When set, the frame payload is a TLS record (ciphertext) and the 2-byte frame size counts ciphertext bytes. | `aasdk/include/aasdk/Messenger/EncryptionType.hpp` L24-L27 |
| `MessageType::SPECIFIC` | 0 | Bit 2 of the flags byte clear = service-specific message (aasdk naming). | `aasdk/include/aasdk/Messenger/MessageType.hpp` L26-L29 |
| `MessageType::CONTROL` | 4 (0x04) | Bit 2 of the flags byte set = control message (aasdk naming). NOTE: AACS assigns the opposite meaning to this bit — see discrepancies. | `aasdk/include/aasdk/Messenger/MessageType.hpp` L26-L29 |
| `FRAME_HEADER_FLAG_MASKS` | frameType &= 0x03, encryption &= 0x08, messageType &= 0x04 | Parsing masks applied to flags byte 1. Bits 4..7 (0xF0) and bit 4 (0x10) are not interpreted by aasdk at all. | `aasdk/src/Messenger/FrameHeader.cpp` L26-L29 |
| `FrameSizeType::SHORT` | 0 (enum ordinal); on-wire size = 2 bytes | Non-FIRST frames (MIDDLE, LAST, BULK) carry only a 2-byte frame size after the 2-byte header, i.e. a 4-byte total header. | `aasdk/include/aasdk/Messenger/FrameSizeType.hpp` L24-L27 |
| `FrameSizeType::EXTENDED` | 1 (enum ordinal); on-wire size = 6 bytes | FIRST frames carry 2-byte frame size + 4-byte total size = 6 bytes after the 2-byte header, i.e. an 8-byte total header. | `aasdk/include/aasdk/Messenger/FrameSizeType.hpp` L24-L27 |
| `FrameSize::getSizeOf(EXTENDED / SHORT)` | 6 / 2 | Authoritative byte width of the size region: 6 bytes for EXTENDED (FIRST frames), 2 bytes otherwise. | `aasdk/src/Messenger/FrameSize.cpp` L73-L75 |
| `FRAME_SIZE_ENCODING` | big-endian uint16 at frame offset 2 | The per-frame payload length is a 16-bit big-endian value immediately after the 2-byte header. It counts on-wire payload bytes (ciphertext bytes when the ENCRYPTED bit is set). | `aasdk/src/Messenger/FrameSize.cpp` L35-L46 |
| `TOTAL_SIZE_ENCODING` | big-endian uint32 at frame offset 4 (FIRST frames only) | On FIRST frames only, a 4-byte big-endian total size follows the 2-byte frame size. Serialization confirms native_to_big for both the uint16 frame size and the uint32 total size, emitted in that order. | `aasdk/src/Messenger/FrameSize.cpp` L48-L62 |
| `TOTAL_SIZE_SEMANTICS` | message_->getPayload().size() (full plaintext message length) | The 4-byte total-size field on a FIRST frame is the total PLAINTEXT payload length of the whole message (including the 2-byte message id), while the 2-byte per-frame size is the on-wire (possibly ciphertext) length of that one frame. | `aasdk/src/Messenger/MessageOutStream.cpp` L103-L127 |
| `cMaxFramePayloadSize` | 0x4000 (16384) | aasdk's maximum plaintext payload carried in one frame. A message whose payload size is >= this value is split; each fragment carries min(remaining, 0x4000) plaintext bytes. | `aasdk/include/aasdk/Messenger/MessageOutStream.hpp` L62 |
| `FRAGMENTATION_THRESHOLD_AND_TYPE_SELECTION` | payload.size() >= 0x4000 -> split; frameType = offset==0 ? FIRST : (remaining-size > 0 ? MIDDLE : LAST) | Exact aasdk fragmentation rule: single BULK frame when payload < 0x4000; otherwise FIRST for the chunk at offset 0, MIDDLE while bytes remain after this chunk, LAST for the final chunk. | `aasdk/src/Messenger/MessageOutStream.cpp` L44-L50, L66-L74 |
| `MessageId::getSizeOf()` | 2 | Every AAP message payload begins with a 2-byte message id (message type) prefix, ahead of the protobuf bytes. | `aasdk/include/aasdk/Messenger/MessageId.hpp` L26-L36 |
| `MESSAGE_ID_ENCODING` | big-endian uint16 at payload offset 0 | The 2-byte message id is stored big-endian at the start of the reassembled plaintext message payload; parsing uses big_to_native, serialization uses native_to_big. | `aasdk/src/Messenger/MessageId.cpp` L30-L42 |
| `MESSAGE_ID_STRIP_ON_RECEIVE` | payload offset = MessageId::getSizeOf() = 2 | On receive, a channel reads MessageId from the front of the reassembled payload and hands the protobuf parser the payload from byte offset 2 onward. | `aasdk/src/Channel/Control/ControlServiceChannel.cpp` L188-L197 |
| `MESSAGE_ID_PREPEND_ON_SEND` | insertPayload(MessageId(...).getData()) then insertPayload(protobuf) | On send, the 2-byte message id is inserted into the message payload first, then the serialized protobuf is appended. Framing then wraps the resulting payload. | `aasdk/src/Channel/Control/ControlServiceChannel.cpp` L77-L88 |
| `ChannelId::CONTROL` | 0 | aasdk static channel id enum. No explicit initializers except NONE, so ordinals run sequentially from CONTROL = 0. Channel 0 is the control channel. | `aasdk/include/aasdk/Messenger/ChannelId.hpp` L30-L51 |
| `ChannelId::SENSOR` | 1 | Sensor source channel. Ordinal 1 (second enumerator after CONTROL). | `aasdk/include/aasdk/Messenger/ChannelId.hpp` L30-L33 |
| `ChannelId::MEDIA_SINK` | 2 | Generic media sink channel. Ordinal 2. | `aasdk/include/aasdk/Messenger/ChannelId.hpp` L31-L34 |
| `ChannelId::MEDIA_SINK_VIDEO` | 3 | Video media sink channel. Ordinal 3. | `aasdk/include/aasdk/Messenger/ChannelId.hpp` L32-L35 |
| `ChannelId::MEDIA_SINK_MEDIA_AUDIO` | 4 | Media (music) audio sink channel. Ordinal 4. | `aasdk/include/aasdk/Messenger/ChannelId.hpp` L33-L36 |
| `ChannelId::MEDIA_SINK_GUIDANCE_AUDIO` | 5 | Navigation guidance audio sink channel. Ordinal 5. | `aasdk/include/aasdk/Messenger/ChannelId.hpp` L34-L37 |
| `ChannelId::MEDIA_SINK_SYSTEM_AUDIO` | 6 | System audio sink channel. Ordinal 6. | `aasdk/include/aasdk/Messenger/ChannelId.hpp` L35-L38 |
| `ChannelId::MEDIA_SINK_TELEPHONY_AUDIO` | 7 | Telephony audio sink channel. Ordinal 7. | `aasdk/include/aasdk/Messenger/ChannelId.hpp` L36-L39 |
| `ChannelId::INPUT_SOURCE` | 8 | Input source (touch/button) channel. Ordinal 8. | `aasdk/include/aasdk/Messenger/ChannelId.hpp` L37-L40 |
| `ChannelId::MEDIA_SOURCE_MICROPHONE` | 9 | Microphone media source channel. Ordinal 9. | `aasdk/include/aasdk/Messenger/ChannelId.hpp` L38-L41 |
| `ChannelId::BLUETOOTH` | 10 (0x0A) | Bluetooth service channel. Ordinal 10. | `aasdk/include/aasdk/Messenger/ChannelId.hpp` L39-L42 |
| `ChannelId::RADIO` | 11 (0x0B) | Radio service channel. Ordinal 11. | `aasdk/include/aasdk/Messenger/ChannelId.hpp` L40-L43 |
| `ChannelId::NAVIGATION_STATUS` | 12 (0x0C) | Navigation status service channel. Ordinal 12. | `aasdk/include/aasdk/Messenger/ChannelId.hpp` L41-L44 |
| `ChannelId::MEDIA_PLAYBACK_STATUS` | 13 (0x0D) | Media playback status service channel. Ordinal 13. | `aasdk/include/aasdk/Messenger/ChannelId.hpp` L42-L45 |
| `ChannelId::PHONE_STATUS` | 14 (0x0E) | Phone status service channel. Ordinal 14. | `aasdk/include/aasdk/Messenger/ChannelId.hpp` L43-L46 |
| `ChannelId::MEDIA_BROWSER` | 15 (0x0F) | Media browser service channel. Ordinal 15. | `aasdk/include/aasdk/Messenger/ChannelId.hpp` L44-L47 |
| `ChannelId::VENDOR_EXTENSION` | 16 (0x10) | Vendor extension service channel. Ordinal 16. | `aasdk/include/aasdk/Messenger/ChannelId.hpp` L45-L48 |
| `ChannelId::GENERIC_NOTIFICATION` | 17 (0x11) | Generic notification service channel. Ordinal 17. | `aasdk/include/aasdk/Messenger/ChannelId.hpp` L46-L49 |
| `ChannelId::WIFI_PROJECTION` | 18 (0x12) | Wifi projection service channel. Ordinal 18 — the highest real channel. | `aasdk/include/aasdk/Messenger/ChannelId.hpp` L47-L50 |
| `ChannelId::NONE` | 255 (0xFF) | Sentinel 'no channel'. Explicitly initialized, the only non-sequential value in the enum. | `aasdk/include/aasdk/Messenger/ChannelId.hpp` L49-L51 |
| `ServiceId enum (aasdk)` | CONTROL=0, SENSOR=1, MEDIA_SINK=2, INPUT_SOURCE=3, MEDIA_SOURCE=4, BLUETOOTH=5, RADIO=6, NAVIGATION_STATUS=7, MEDIA_PLAYBACK_STATUS=8, PHONE_STATUS=9, MEDIA_BROWSER=10, VENDOR_EXTENSION=11, GENERIC_NOTIFICATION=12, WIFI_PROJECTION=13, NONE=255 | A SEPARATE enum from ChannelId with a DIFFERENT numbering (it lacks the four extra MEDIA_SINK_* audio/video splits). Do not use ServiceId values as frame-header channel bytes; aasdk puts ChannelId in byte 0. | `aasdk/include/aasdk/Messenger/ServiceId.hpp` L24-L40 |
| `CHANNEL_ID_IS_ADVERTISED_SERVICE_ID` | service->set_id(static_cast<uint32_t>(channel_->getId())) | Channel multiplexing is negotiated: each service advertised in ServiceDiscoveryResponse carries Service.id, which is set from the channel's ChannelId and becomes byte 0 of every frame on that channel. | `openauto/src/autoapp/Service/MediaSink/VideoMediaSinkService.cpp` L76 |
| `aasdk SSL record overhead assumed on decrypt` | 29 | When decrypting an ENCRYPTED frame, aasdk computes plaintext length as frameLength - 29, i.e. the 2-byte frame-size field counts the TLS record including a 29-byte overhead. This is why the frame size on an encrypted frame exceeds the plaintext chunk size. | `aasdk/src/Messenger/Cryptor.cpp` L149-L152 |
| `aasdk DataSink::cChunkSize` | 16384 | Transport-layer receive buffer chunk size in aasdk (16 KiB), matching cMaxFramePayloadSize. | `aasdk/include/aasdk/Transport/DataSink.hpp` L42 |
| `aa-proxy-rs HEADER_LENGTH` | 4 | Independent confirmation that the base frame header (channel + flags + 2-byte length) is 4 bytes, extended to 8 on FIRST frames. | `aa-proxy-rs/src/mitm.rs` L138-L144 |
| `aa-proxy-rs frame serialization order` | [channel][flags][len_hi][len_lo]([tot31..24][tot23..16][tot15..8][tot7..0])[payload] | Byte-for-byte confirmation of the wire layout, including that the optional 4-byte total length is big-endian and only present when final_length is set (FIRST frames). | `aa-proxy-rs/src/mitm.rs` L1066-L1085 |
| `aa-proxy-rs extended-header trigger condition` | (flags & FRAME_TYPE_MASK) == FRAME_TYPE_FIRST | The extra 4 bytes are present only when the masked frame type equals EXACTLY FIRST (0x01) — not for BULK (0x03). Reader adds 4 to header_size and parses the big-endian u32. | `aa-proxy-rs/src/mitm.rs` L3919-L3942 |
| `aa-proxy-rs message-id position` | u16::from_be_bytes(payload[0..=1]) | Confirms the 2-byte big-endian message id occupies the first two bytes of the (decrypted) frame payload. | `aa-proxy-rs/src/mitm.rs` L1088-L1092 |
| `aa-proxy-rs message-id presence rule` | frame_type == FIRST \|\| frame_type == BULK \|\| channel == 0 \|\| (flags & CONTROL) | The 2-byte message id is present only on FIRST and BULK frames; MIDDLE (0) and LAST frames carry raw continuation payload with no message id. | `aa-proxy-rs/src/mitm.rs` L890-L906 |
| `aa-proxy-rs DEFAULT_FIRST_FRAGMENT_PAYLOAD_BYTES` | 16120 | Observed plaintext payload size of a FIRST fragment in real AA streams (before TLS encryption). Differs from aasdk's 0x4000. | `aa-proxy-rs/src/packet_fragment.rs` L3-L10 |
| `aa-proxy-rs DEFAULT_CONTINUATION_FRAGMENT_PAYLOAD_BONUS` | 4 | MIDDLE/LAST fragments carry 4 more plaintext bytes than FIRST fragments (16124 vs 16120), because their outer header is 4 bytes shorter — keeping total on-wire frame size constant. | `aa-proxy-rs/src/packet_fragment.rs` L12-L16 |
| `aa-proxy-rs fragmentation flag assignment` | single: FIRST\|LAST with final_length=None; else FIRST(+final_length), then bare base_flags (MIDDLE), then LAST | Independent confirmation of the fragmentation rule, including that a single-frame message sets FIRST\|LAST and omits the 4-byte total-size field entirely. | `aa-proxy-rs/src/packet_fragment.rs` L62-L103 |
| `WirelessAndroidAutoDongle header parsing` | header_length = 4; message_length = (buffer[2] << 8) + buffer[3]; +4 if masked frame type == FIRST | Third independent confirmation of the 4-byte base header, big-endian 16-bit length at offset 2, and the extra 4 bytes on exactly-FIRST frames. | `WirelessAndroidAutoDongle/aa_wireless_dongle/package/aawg/src/proxyHandler.cpp` L40-L54 |
| `AACS FrameType` | First = 1, Last = 2, Bulk = 3 | AACS agrees with aasdk on the frame-type bits but does not define a named MIDDLE (it is simply 0). | `AACS/include/enums.h` L10-L14 |
| `AACS EncryptionType` | Plain = 0, Encrypted = 8 | AACS agrees with aasdk on the encryption bit (1 << 3). | `AACS/include/enums.h` L5-L8 |
| `AACS MessageTypeFlags` | Control = 0, Specific = 4 | AACS assigns bit 2 (0x04) the OPPOSITE meaning from aasdk. See discrepancies — prefer aasdk (SPECIFIC = 0, CONTROL = 1 << 2). | `AACS/include/enums.h` L16-L19 |
| `AACS server fragmentation maxSize` | 2000 | AACS AAServer splits encrypted messages into 2000-byte plaintext chunks, with a comment that ~16k should work but hardware issues were observed. | `AACS/AAServer/src/AaCommunicator.cpp` L371-L372 |
| `AACS client fragmentation maxSize` | 10000 | AACS AAClient uses a different chunk size (10000) than AAServer (2000) and than aasdk (16384). | `AACS/AAClient/src/AaCommunicator.cpp` L281 |
| `AACS send-side extended header write` | offset = 4; if ((flags & Bulk) == First) offset += 4; encBuf[4..7] = totalLength BE32 | AACS writes the 4-byte total (plaintext) length as big-endian at bytes 4..7 on exactly-FIRST frames, and the 2-byte ciphertext length at bytes 2..3, matching aasdk. | `AACS/AAServer/src/AaCommunicator.cpp` L419-L438 |
| `AACS client receive reassembly loop` | do { ... } while (fullContent.size() < totalLength) | AACS client reassembles by concatenating decrypted per-frame payloads until the accumulated plaintext reaches the totalLength read from the FIRST frame — i.e. the 4-byte field IS usable as a plaintext-length reassembly target. | `AACS/AAClient/src/AaCommunicator.cpp` L445-L466 |
| `aasdk reassembly error: MESSENGER_INTERTWINED_CHANNELS` | MIDDLE or LAST arriving with no buffered message for that channel | Reassembly rule: aasdk keeps one in-progress message per ChannelId in a std::map. A MIDDLE/LAST frame for a channel with no buffered partial message is a protocol error; a FIRST/BULK frame discards any existing partial for that channel and starts over. | `aasdk/src/Messenger/MessageInStream.cpp` L67-L98 |
| `aasdk message-complete condition` | thisFrameType_ == BULK \|\| thisFrameType_ == LAST | A message is delivered upward only when the frame type is BULK or LAST; FIRST and MIDDLE frames append to the per-channel buffer and the reader loops for the next 2-byte header. | `aasdk/src/Messenger/MessageInStream.cpp` L149-L161 |

### 1.3 Message definitions


#### `aap_protobuf.service.Service`

Source: `aasdk/protobuf/aap_protobuf/service/Service.proto`

```proto
message Service
{
    required int32 id = 1;
    optional sensorsource.SensorSourceService sensor_source_service = 2;
    optional media.sink.MediaSinkService media_sink_service = 3;
    optional inputsource.InputSourceService input_source_service = 4;
    optional media.source.MediaSourceService media_source_service = 5;
    optional bluetooth.BluetoothService bluetooth_service = 6;
    optional radio.RadioService radio_service = 7;
    optional navigationstatus.NavigationStatusService navigation_status_service = 8;
    optional mediaplayback.MediaPlaybackStatusService media_playback_service = 9;
    optional phonestatus.PhoneStatusService phone_status_service = 10;
    optional mediabrowser.MediaBrowserService media_browser_service = 11;
    optional vendorextension.VendorExtensionService vendor_extension_service = 12;
    optional genericnotification.GenericNotificationService generic_notification_service = 13;
    optional wifiprojection.WifiProjectionService wifi_projection_service = 14;
}
```

### 1.4 Where the references disagree


**Bit 2 (0x04) meaning: CONTROL vs SPECIFIC — inverted between aasdk and AACS**

aasdk (aasdk/include/aasdk/Messenger/MessageType.hpp L26-L29) defines `SPECIFIC = 0, CONTROL = 1 << 2`. AACS (AACS/include/enums.h L16-L19) defines `Control = 0, Specific = 1 << 2` — the exact opposite. AACS's own usage is self-consistent with its definition (e.g. AACS/AAClient/src/AaCommunicator.cpp L149 sends the version request with `FrameType::Bulk | EncryptionType::Plain | MessageTypeFlags::Control`, which evaluates to 0x03, and AAServer/src/AaCommunicator.cpp L166-L167 tests `message.flags & MessageTypeFlags::Specific`). aa-proxy-rs names the bit `_CONTROL: u8 = 1 << 2` (src/mitm.rs L143), agreeing with aasdk's naming. Per the instruction to prefer aasdk: treat 0x04 SET as CONTROL and 0x04 CLEAR as SPECIFIC. Note this is a naming disagreement about which polarity is called what — the wire bit is the same bit; verify against a capture before relying on it.

**Maximum plaintext payload per frame differs across every reference**

aasdk: 0x4000 = 16384 (aasdk/include/aasdk/Messenger/MessageOutStream.hpp L62). aa-proxy-rs: 16120 for FIRST and 16124 for MIDDLE/LAST, explicitly documented as 'observed in AA streams' and as mirroring OpenAuto/aasdk framing while keeping total on-wire frame size constant given the 4-byte-longer FIRST header (aa-proxy-rs/src/packet_fragment.rs L3-L16). AACS AAServer: 2000, with the comment 'it should work up to about 16k, but we might get some weird hardware issues' (AACS/AAServer/src/AaCommunicator.cpp L371-L372). AACS AAClient: 10000 (AACS/AAClient/src/AaCommunicator.cpp L281). Receivers must not assume any particular fragment size; only the 16-bit frame size field bounds a frame (max 65535).

**aasdk ignores the 4-byte total-size field during reassembly**

aasdk parses totalSize into FrameSize (aasdk/src/Messenger/FrameSize.cpp L42-L45) but MessageInStream::receiveFrameSizeHandler only uses frameSize.getFrameSize() (aasdk/src/Messenger/MessageInStream.cpp L128-L130); reassembly terminates purely on the LAST/BULK frame-type bit (L152). AACS AAClient instead loops `while (fullContent.size() < totalLength)` (AACS/AAClient/src/AaCommunicator.cpp L464) and never inspects the LAST bit. Both work against a conformant sender; a robust implementation should terminate on LAST and use totalSize only as a sanity check / preallocation hint.

**AACS AAServer receive path does not skip the extended 4-byte header**

AACS/AAServer/src/AaCommunicator.cpp L344-L362 unconditionally reads the length at byteView[2..3] and copies the payload from byteView+4, with no check for `(flags & Bulk) == First`. If the phone ever sent an exactly-FIRST frame to the server, the 4 total-size bytes would be treated as payload. AACS AAClient (L449-L453) and every other reference do handle it. Not a wire-format disagreement — an AACS bug; follow aasdk/aa-proxy-rs/WAAD.

**The extended header applies to FIRST only, not to BULK — masked equality, not bit test**

Every reader tests `(flags & 0x03) == 0x01`, i.e. masked equality against FIRST, not `flags & FIRST` (aa-proxy-rs/src/mitm.rs L3925 and L3932; WirelessAndroidAutoDongle/.../proxyHandler.cpp L51; AACS/AAServer/src/AaCommunicator.cpp L421 and L432). aasdk expresses the same thing as `frameHeader.getType() == FrameType::FIRST` after the type has already been masked with BULK (aasdk/src/Messenger/MessageInStream.cpp L97-L98). Using a plain bit test would wrongly add 4 bytes to every BULK frame.

**ChannelId enum vs ServiceId enum use different numbering for the same names**

aasdk has two enums: ChannelId (aasdk/include/aasdk/Messenger/ChannelId.hpp L30-L51) with CONTROL=0, SENSOR=1, MEDIA_SINK=2, MEDIA_SINK_VIDEO=3 ... WIFI_PROJECTION=18, and ServiceId (aasdk/include/aasdk/Messenger/ServiceId.hpp L24-L40) with CONTROL=0, SENSOR=1, MEDIA_SINK=2, INPUT_SOURCE=3 ... WIFI_PROJECTION=13. They diverge from index 3 onward because ChannelId splits MEDIA_SINK into four audio/video sub-channels. Frame header byte 0 carries ChannelId in aasdk. ServiceId appears to be vestigial in the framing layer — no framing code in aasdk/src/Messenger uses it.

**aasdk channel ids are a static convention, not protocol-mandated**

aasdk/include/aasdk/Messenger/ChannelId.hpp L22-L27 carries an explicit TODO: 'In AA, Channel Id's are dynamic. We use ChannelId here for a static implementation, which, while acceptable, may cause more channels to be open than needs to be.' The authoritative binding is ServiceDiscoveryResponse: each advertised Service has `required int32 id = 1` (aasdk/protobuf/aap_protobuf/service/Service.proto L23) and openauto sets it from the channel's ChannelId (openauto/src/autoapp/Service/**/\*Service.cpp, e.g. VideoMediaSinkService.cpp L76). A clean-room implementation should treat the numeric ChannelId list as this project's chosen assignment and route by the advertised ids, not by hardcoded constants (channel 0 = CONTROL is the one fixed point — aa-proxy-rs special-cases `pkt.channel == 0` at src/mitm.rs L893 and L913).

**Frame size field counts ciphertext, but the total-size field counts plaintext**

On an ENCRYPTED FIRST frame the two size fields are in different units. The 2-byte frame size is the TLS record length written after encryption (aasdk/src/Messenger/MessageOutStream.cpp L111-L118, where payloadSize comes from cryptor_->encrypt; aa-proxy-rs/src/mitm.rs L1070 takes len from the already-encrypted payload). The 4-byte total size is the plaintext message length (aasdk passes message_->getPayload().size(); aa-proxy-rs/src/packet_fragment.rs L25-L28 documents 'Total plaintext/application payload length ... It is not an encrypted/ciphertext length'). aasdk assumes a fixed 29-byte TLS record overhead when converting back (aasdk/src/Messenger/Cryptor.cpp L149-L151) — that constant is cipher-suite dependent and is a likely source of breakage; prefer letting the TLS layer report the plaintext length.

**aa-proxy-rs treats a bare LAST frame as a complete-frame boundary alongside BULK**

aa-proxy-rs/src/mitm.rs L695-L698: `fn is_complete_frame_boundary(flags: u8) -> bool { let frame_type = flags & FRAME_TYPE_MASK; frame_type == (FRAME_TYPE_FIRST | FRAME_TYPE_LAST) || frame_type == FRAME_TYPE_LAST }` — same rule as aasdk's `thisFrameType_ == BULK || thisFrameType_ == LAST` (aasdk/src/Messenger/MessageInStream.cpp L152). These agree; noting it because AACS AAClient's reassembly loop uses totalLength instead and would not terminate correctly on a message whose total length was never advertised.

**AACS fragments only encrypted messages**

AACS/AAServer/src/AaCommunicator.cpp L377 branches on `if (msg.flags & EncryptionType::Encrypted)`; the plain branch (L440-L449 region) writes a single frame with a 2-byte length and never splits, so a plaintext message larger than 65535 bytes would silently corrupt the length field. aasdk fragments regardless of encryption (MessageOutStream.cpp L44). Follow aasdk.

---

## 2. Version handshake and TLS

The plaintext version exchange, then the TLS handshake carried *inside* control-channel messages rather than on the raw socket.

### 2.1 Sequence

```text
ROLES. The head unit is the TLS **client** (SSL_set_connect_state / SSL_connect) and the phone is the TLS **server** (SSL_set_accept_state / SSL_accept). aasdk: aasdk/src/Transport/SSLWrapper.cpp L137-L140 ("SSL_set_connect_state(ssl); SSL_set_verify(ssl, SSL_VERIFY_NONE, nullptr);"). AACS head-unit side: AACS/AAClient/src/AaCommunicator.cpp L186-L194 ("SSL_set_connect_state(ssl); SSL_set_bio(ssl, readBio, writeBio);"). AACS phone side: AACS/AAServer/src/AaCommunicator.cpp L292-L300 ("SSL_set_accept_state(ssl); SSL_set_bio(ssl, readBio, writeBio);"). aa-proxy-rs states the same mapping explicitly in aa-proxy-rs/src/ssl_rustls.rs L430-L438 ("ProxyType::HeadUnit -> set_accept_state() -> TLS **server** (phone connects to us) / ProxyType::MobileDevice -> set_connect_state() -> TLS **client** (we connect to HU)" - i.e. from the proxy's viewpoint, whichever side faces the phone is the TLS client).

FRAMING. Every step below is one AAP frame on channel 0x00: byte0 = channel (0x00), byte1 = flags (encryptionType | messageType | frameType, aasdk/src/Messenger/FrameHeader.cpp L54-L62), bytes2-3 = big-endian payload length, plus 4 extra big-endian total-length bytes only when frameType == FIRST (aasdk/src/Messenger/MessageInStream.cpp L97-L98; aa-proxy-rs/src/mitm.rs L1066-L1085). All handshake frames are BULK (flags bits 0-1 = 0x03) and PLAIN (bit 3 clear). Payload always begins with a 2-byte big-endian message ID (aasdk/include/aasdk/Messenger/MessageId.hpp L34; aasdk/src/Messenger/MessageId.cpp L30-L42).

STEP 1 - head unit sends VersionRequest FIRST. openauto's AndroidAutoEntity::start() calls sendVersionRequest immediately after starting the services and only then arms a receive (openauto/src/autoapp/Service/AndroidAutoEntity.cpp L46-L61: "controlServiceChannel_->sendVersionRequest(std::move(versionRequestPromise)); controlServiceChannel_->receive(this->shared_from_this());"). aa-proxy-rs confirms the HU starts transmission (aa-proxy-rs/src/mitm.rs L4100-L4102: "// waiting for initial version frame (HU is starting transmission)"). Wire bytes: channel 0x00, flags 0x03, len 0x0006, payload = 00 01 | major_BE | minor_BE. aasdk sends major=1 minor=6 (aasdk/include/aasdk/Version.hpp L22-L23), AACS sends 1,1 (AACS/AAClient/src/AaCommunicator.cpp L56). Source of the construction: aasdk/src/Channel/Control/ControlServiceChannel.cpp L37-L51 (EncryptionType::PLAIN, MessageType::SPECIFIC).

STEP 2 - phone replies VersionResponse. channel 0x00, flags 0x03, len 0x0008, payload = 00 02 | major_BE | minor_BE | status_BE. Status 0 == version match; STATUS_NO_COMPATIBLE_VERSION (-1) aborts. AACS phone side accepts any major==1 and answers 1.5 with status 0 (AACS/AAServer/src/AaCommunicator.cpp L75-L92). Head-unit-side validation requires exactly 8 payload bytes and a zero status word (AACS/AAClient/src/AaCommunicator.cpp L248-L254). aasdk dispatches this on MESSAGE_VERSION_RESPONSE (aasdk/src/Channel/Control/ControlServiceChannel.cpp L197-L200) and parses it in handleVersionResponse (L238-L251). openauto quits on STATUS_NO_COMPATIBLE_VERSION and otherwise proceeds (openauto/src/autoapp/Service/AndroidAutoEntity.cpp L109-L120).

STEP 3 - head unit starts TLS. openauto calls cryptor_->doHandshake() with an empty read BIO, which drives SSL_do_handshake into producing the ClientHello, then ships whatever the write BIO holds as MESSAGE_ENCAPSULATED_SSL (openauto/src/autoapp/Service/AndroidAutoEntity.cpp L121-L130: "cryptor_->doHandshake(); ... controlServiceChannel_->sendHandshake(cryptor_->readHandshakeBuffer(), std::move(handshakePromise));"). AACS does the same shape - it enters doSslHandshake with an empty message (AACS/AAClient/src/AaCommunicator.cpp L59-L61: "auto message = vector<uint8_t>(); while (!doSslHandshake(message)) message = getMessage().content;"). Frame: channel 0x00, flags 0x03, payload = 00 03 | raw TLS records (aasdk/src/Channel/Control/ControlServiceChannel.cpp L53-L63; aa-proxy-rs/src/mitm.rs L3875-L3887).

STEP 4 - handshake round trips. Each inbound MESSAGE_ENCAPSULATED_SSL is written into the read BIO and the SSL engine is pumped; whatever lands in the write BIO is sent back as another MESSAGE_ENCAPSULATED_SSL. The loop is data-driven, not fixed-count: aasdk returns false on SSL_ERROR_WANT_READ and true on SSL_ERROR_NONE (aasdk/src/Messenger/Cryptor.cpp L116-L128); openauto loops on that boolean (openauto/src/autoapp/Service/AndroidAutoEntity.cpp L138-L152: "cryptor_->writeHandshakeBuffer(payload); if (!cryptor_->doHandshake()) { ... sendHandshake(...) } else { ... }"); phone side loops on SSL_accept returning -1 with SSL_ERROR_WANT_READ (AACS/AAServer/src/AaCommunicator.cpp L270-L290); aa-proxy-rs loops until !ssl_conn.is_handshaking() (aa-proxy-rs/src/mitm.rs L4134-L4192 and L4237-L4295) and counts the iterations as "stage #N". For a plain TLS 1.2 full handshake with client certificate this is the usual 2 round trips of encapsulated frames per side (ClientHello -> ServerHello..ServerHelloDone -> Certificate/ClientKeyExchange/CertificateVerify/CCS/Finished -> CCS/Finished), but no reference hard-codes a count.

STEP 5 - session becomes encrypted. aasdk sets Cryptor::isActive_ = true exactly when SSL_do_handshake returns SSL_ERROR_NONE (aasdk/src/Messenger/Cryptor.cpp L116-L128). aa-proxy-rs logs "SSL init complete, negotiated cipher: ..." when process() reports still_handshaking == false (aa-proxy-rs/src/mitm.rs L4164-L4170). From this point on, any frame whose flags byte has bit 3 (0x08) set is routed through SSL_write/SSL_read (aasdk/src/Messenger/MessageOutStream.cpp L103-L120; aasdk/src/Messenger/MessageInStream.cpp L133-L147). Note there is no separate "turn on encryption" message - the per-frame ENCRYPTED bit is the only switch.

STEP 6 - head unit sends AuthComplete. Message ID 4, payload is an AuthResponse protobuf with status = STATUS_SUCCESS (0), sent PLAIN on channel 0 (aasdk/src/Channel/Control/ControlServiceChannel.cpp L65-L75 uses EncryptionType::PLAIN; openauto/src/autoapp/Service/AndroidAutoEntity.cpp L152-L162: "aap_protobuf::service::control::message::AuthResponse authCompleteIndication; authCompleteIndication.set_status(aap_protobuf::shared::MessageStatus::STATUS_SUCCESS); ... sendAuthComplete(...)"). Exact bytes as emitted by AACS: 00 04 08 00, frame flags Bulk|Plain|Control (AACS/AAClient/src/AaCommunicator.cpp L143-L151). So success is signalled purely by AuthResponse.status == 0; a negative MessageStatus (e.g. -2 STATUS_CERTIFICATE_ERROR, -3 STATUS_AUTHENTICATION_FAILURE, -24 STATUS_AUTHENTICATION_FAILURE_CERT_EXPIRED) is how failure would be reported.

STEP 7 - phone reacts to AuthComplete by sending the first ENCRYPTED frame. AACS/AAServer/src/AaCommunicator.cpp L237-L239 ("} else if (messageType == MessageType::AuthComplete) { cout << \"auth complete\" << endl; sendServiceDiscoveryRequest();") and sendServiceDiscoveryRequest uses "EncryptionType::Encrypted | FrameType::Bulk" (L104 region, AACS/AAServer/src/AaCommunicator.cpp L96-L104). The head unit replies with MESSAGE_SERVICE_DISCOVERY_RESPONSE, also ENCRYPTED (aasdk/src/Channel/Control/ControlServiceChannel.cpp L77-L88). AuthComplete is therefore the last plaintext control message of the session.

NOTE on the receive dispatcher: aasdk's ControlServiceChannel::messageHandler has cases for MESSAGE_VERSION_RESPONSE and MESSAGE_ENCAPSULATED_SSL but deliberately none for MESSAGE_AUTH_COMPLETE, because the head unit only ever sends it (aasdk/src/Channel/Control/ControlServiceChannel.cpp L197-L235).
```

### 2.2 Constants

| Constant | Value | Meaning | Source |
|---|---|---|---|
| `ControlMessageType::MESSAGE_VERSION_REQUEST` | 1 | Control-channel (channel 0) message ID for the head-unit -> phone version request. Encoded as a big-endian uint16 at the start of the frame payload. | `aasdk/protobuf/aap_protobuf/service/control/ControlMessageType.proto` L5-L10 |
| `ControlMessageType::MESSAGE_VERSION_RESPONSE` | 2 | Phone -> head-unit version response message ID. | `aasdk/protobuf/aap_protobuf/service/control/ControlMessageType.proto` L5-L10 |
| `ControlMessageType::MESSAGE_ENCAPSULATED_SSL` | 3 | Message ID carrying raw TLS handshake records (the encapsulated SSL blob follows the 2-byte ID). Used bidirectionally for every handshake round trip. | `aasdk/protobuf/aap_protobuf/service/control/ControlMessageType.proto` L5-L10 |
| `ControlMessageType::MESSAGE_AUTH_COMPLETE` | 4 | Head-unit -> phone message ID sent once TLS handshake completes; payload is an AuthResponse protobuf. Sent PLAIN (unencrypted). | `aasdk/protobuf/aap_protobuf/service/control/ControlMessageType.proto` L5-L10 |
| `ControlMessageType::MESSAGE_SERVICE_DISCOVERY_REQUEST` | 5 | First ENCRYPTED control message after AuthComplete; marks the practical boundary of the encrypted session. | `aasdk/protobuf/aap_protobuf/service/control/ControlMessageType.proto` L11-L12 |
| `AASDK_MAJOR` | 1 | Major protocol version aasdk/openauto advertises in the version request (written big-endian at payload offset 0 after the 2-byte message ID). | `aasdk/include/aasdk/Version.hpp` L22-L23 |
| `AASDK_MINOR` | 6 | Minor protocol version aasdk/openauto advertises in the version request. | `aasdk/include/aasdk/Version.hpp` L22-L23 |
| `version request payload layout` | [u16 BE msgId=1][u16 BE major][u16 BE minor]  (6 bytes total) | aasdk builds a 4-byte buffer holding major then minor, both native_to_big, appended after the 2-byte message ID. Not a protobuf. | `aasdk/src/Channel/Control/ControlServiceChannel.cpp` L37-L51 |
| `version response payload layout` | [u16 BE msgId=2][u16 major][u16 minor][u16 BE status]  (8 bytes total) | aasdk reads the payload after the 2-byte message ID as three uint16 words: [0]=major, [1]=minor, [2]=status (status is big_to_native converted; major/minor are NOT converted - see discrepancies). | `aasdk/src/Channel/Control/ControlServiceChannel.cpp` L238-L251 |
| `version response length check (AACS head-unit side)` | 8 bytes; content[6]==0 && content[7]==0 | AACS client requires exactly 8 payload bytes and a zero big-endian status word, confirming the 2+2+2+2 layout and that status 0 == version match. | `AACS/AAClient/src/AaCommunicator.cpp` L248-L254 |
| `MessageStatus::STATUS_SUCCESS` | 0 | Version-response status meaning "version match"; also the value put in AuthResponse.status on successful handshake. | `aasdk/protobuf/aap_protobuf/shared/MessageStatus.proto` L5-L11 |
| `MessageStatus::STATUS_NO_COMPATIBLE_VERSION` | -1 | Version-response status meaning version negotiation failed; openauto aborts the session on this value. | `aasdk/protobuf/aap_protobuf/shared/MessageStatus.proto` L5-L11 |
| `MessageStatus::STATUS_CERTIFICATE_ERROR` | -2 | TLS/auth failure status: certificate problem. | `aasdk/protobuf/aap_protobuf/shared/MessageStatus.proto` L5-L11 |
| `MessageStatus::STATUS_AUTHENTICATION_FAILURE` | -3 | TLS/auth failure status: authentication failed. | `aasdk/protobuf/aap_protobuf/shared/MessageStatus.proto` L5-L11 |
| `MessageStatus::STATUS_AUTHENTICATION_FAILURE_CERT_NOT_YET_VALID` | -23 | Auth failure because the presented certificate's notBefore is in the future. | `aasdk/protobuf/aap_protobuf/shared/MessageStatus.proto` L30-L32 |
| `MessageStatus::STATUS_AUTHENTICATION_FAILURE_CERT_EXPIRED` | -24 | Auth failure because the presented certificate has expired - relevant because one shipped reference cert is expired. | `aasdk/protobuf/aap_protobuf/shared/MessageStatus.proto` L30-L32 |
| `EncryptionType::PLAIN / EncryptionType::ENCRYPTED` | PLAIN = 0, ENCRYPTED = 1 << 3 (0x08) | Bit 3 of the frame flags byte. Version request/response, all MESSAGE_ENCAPSULATED_SSL frames, and MESSAGE_AUTH_COMPLETE are sent with PLAIN; everything from ServiceDiscovery onwards is ENCRYPTED. | `aasdk/include/aasdk/Messenger/EncryptionType.hpp` L81-L84 |
| `MessageType::SPECIFIC / MessageType::CONTROL` | SPECIFIC = 0, CONTROL = 1 << 2 (0x04) | Bit 2 of the frame flags byte. aasdk uses SPECIFIC (=0) for version request, encapsulated SSL, and auth complete, so the flags byte for those frames is 0x03 (BULK\|PLAIN\|SPECIFIC). | `aasdk/include/aasdk/Messenger/MessageType.hpp` L26-L29 |
| `FrameType::MIDDLE/FIRST/LAST/BULK` | MIDDLE = 0, FIRST = 1, LAST = 2, BULK = 3 | Bits 0-1 of the frame flags byte. Handshake frames are BULK (0x03). | `aasdk/include/aasdk/Messenger/FrameType.hpp` L114-L119 |
| `frame flags byte composition` | flags = encryptionType \| messageType \| frameType | Byte 1 of every frame header; byte 0 is the channel ID. For the version request this yields 0x00\|0x00\|0x03 = 0x03 on channel 0x00. | `aasdk/src/Messenger/FrameHeader.cpp` L54-L62 |
| `frame flags parsing masks` | frameType = flags & 0x03; encryptionType = flags & 0x08; messageType = flags & 0x04 | Receive-side decomposition of the flags byte; the ENCRYPTED bit alone decides whether the frame payload goes through the Cryptor. | `aasdk/src/Messenger/FrameHeader.cpp` L25-L30 |
| `ChannelId::CONTROL` | 0 (first enumerator, no explicit value) | Control service channel used for the whole version/TLS handshake; frame header byte 0 = 0x00. | `aasdk/include/aasdk/Messenger/ChannelId.hpp` L153-L157 |
| `MessageId::getSizeOf()` | 2 | Every control-channel payload begins with a 2-byte big-endian message ID. | `aasdk/include/aasdk/Messenger/MessageId.hpp` L34 |
| `message ID byte order` | big-endian uint16 | MessageId is serialised/parsed with boost::endian native_to_big / big_to_native. | `aasdk/src/Messenger/MessageId.cpp` L30-L42 |
| `SSL method (head-unit / client role)` | TLSv1_2_client_method() if OPENSSL_VERSION_NUMBER < 0x10100000L, else TLS_client_method() | aasdk head unit is the TLS *client*. On OpenSSL < 1.1 it hard-pins TLS 1.2; on >= 1.1 it uses the version-flexible TLS_client_method with no explicit min/max version set. | `aasdk/src/Transport/SSLWrapper.cpp` L97-L103 |
| `SSL_VERIFY_NONE (aasdk peer verification)` | SSL_set_verify(ssl, SSL_VERIFY_NONE, nullptr) | aasdk head unit performs NO peer certificate verification and sets connect (client) state. Peer verification is effectively disabled. | `aasdk/src/Transport/SSLWrapper.cpp` L137-L140 |
| `Cryptor maxBufferSize_` | 1024 * 20 = 20480 | BIO write buffer size applied to both the read and write memory BIOs via BIO_set_write_buf_size. | `aasdk/src/Messenger/Cryptor.cpp` L28-L31 |
| `TLS record overhead used for decrypt sizing` | 29 | aasdk subtracts 29 bytes from the AAP frame length to derive the plaintext length when decrypting an ENCRYPTED frame (TLS 1.2 record header + IV + MAC/tag assumption). | `aasdk/src/Messenger/Cryptor.cpp` L149-L151 |
| `Cryptor::doHandshake() completion semantics` | SSL_ERROR_WANT_READ -> false (need more data); SSL_ERROR_NONE -> isActive_ = true, return true; anything else -> throw SSL_HANDSHAKE | This is what marks the session as encrypted in aasdk: isActive_ flips to true exactly when SSL_do_handshake returns SSL_ERROR_NONE. | `aasdk/src/Messenger/Cryptor.cpp` L116-L128 |
| `aasdk embedded head-unit certificate` | PEM X.509, serial 0x1B (27), Issuer C=US/ST=California/L=Mountain View/O=Google Automotive Link, Subject C=JP/ST=Tokyo/L=Hachioji/O=JVC Kenwood/OU=01, RSA 2048, sha256WithRSAEncryption, X.509 v1, validity notBefore Jul  4 07:00:00 2014 / notAfter Apr 29 21:28:38 2045 | Compiled-in client certificate used by aasdk's Cryptor::init(). Byte-identical to aasdk/cert/headunit.crt (verified: modulus md5 0acfbc22b3f8dc54953d7f26897ab267 matches aasdk/cert/headunit.key). Validity dates read via `openssl x509 -noout -dates`, not literal text in the file. | `aasdk/src/Messenger/Cryptor.cpp` L253-L258 |
| `aasdk embedded private key` | PEM "RSA PRIVATE KEY" (PKCS#1), 2048-bit | Compiled-in private key matching the embedded certificate; identical to aasdk/cert/headunit.key. | `aasdk/src/Messenger/Cryptor.cpp` L273-L276 |
| `aasdk/cert/headunit.crt` | X.509 v1 PEM, serial 27, Google Automotive Link -> JVC Kenwood OU=01, RSA 2048, notBefore Jul  4 07:00:00 2014, notAfter Apr 29 21:28:38 2045 | On-disk copy of the head-unit certificate shipped in the aasdk reference (1159 bytes, 19 lines). Dates obtained by decoding with openssl, not literal file text. | `aasdk/cert/headunit.crt` L1-L4 |
| `aasdk/cert/headunit.key` | PEM PKCS#1 RSA PRIVATE KEY, 2048-bit, 27 lines / 1675 bytes | On-disk private key for headunit.crt; modulus matches the certificate. | `aasdk/cert/headunit.key` L1-L2 |
| `AACS MessageType (control message IDs)` | VersionRequest=1, VersionResponse=2, SslHandshake=3, AuthComplete=4 | Independent confirmation of the four handshake message IDs from a second implementation. | `AACS/include/enums.h` L21-L28 |
| `AACS EncryptionType` | Plain = 0, Encrypted = 1 << 3 | Matches aasdk's ENCRYPTED bit position exactly. | `AACS/include/enums.h` L5-L8 |
| `AACS FrameType` | First = 1, Last = 2, Bulk = First \| Last = 3 | Matches aasdk frame-type bits. | `AACS/include/enums.h` L10-L14 |
| `AACS MessageTypeFlags` | Control = 0, Specific = 1 << 2 | NOTE: the names of bit 2 are inverted relative to aasdk (aasdk: SPECIFIC=0, CONTROL=1<<2). Both implementations still emit flags 0x03 for handshake frames because both use the zero-valued enumerator. | `AACS/include/enums.h` L16-L19 |
| `AACS head-unit advertised version` | major=1, minor=1 | Version the AACS client (head-unit emulation) requests. | `AACS/AAClient/src/AaCommunicator.cpp` L52-L58 |
| `AACS phone-side version response` | accepts any major==1, replies major=1 minor=5 status=0 | Phone-side (AAServer) version negotiation logic and the fact that status 0 means "version match". | `AACS/AAServer/src/AaCommunicator.cpp` L75-L92 |
| `AuthComplete wire bytes (AACS)` | payload = 00 04 08 00 | 2-byte message ID 0x0004 followed by the serialized AuthResponse protobuf {status: 0} = 0x08 0x00 (field 1, varint, value 0). Frame flags = Bulk\|Plain\|Control = 0x03, channel 0. | `AACS/AAClient/src/AaCommunicator.cpp` L143-L151 |
| `AACS TLS context options (both sides)` | SSLv23_client_method()/SSLv23_server_method(), SSL_CTX_set_ecdh_auto(ctx,1), SSL_CTX_set_tmp_dh(2048-bit DH from dhparam.pem), SSL_CTX_set_verify(ctx, SSL_VERIFY_PEER, cb) with cb returning 1 unconditionally, SSL_CTX_set_options(ctx, SSL_OP_NO_TLSv1_3) | Effective ceiling is TLS 1.2 (TLS 1.3 explicitly disabled). SSL_VERIFY_PEER is requested but the callback always returns 1, so verification is effectively bypassed. | `AACS/AAClient/src/AaCommunicator.cpp` L196-L236 |
| `AACS phone-side TLS role` | SSL_set_accept_state(ssl) + SSL_accept(); SSL_ERROR_WANT_READ tolerated | The phone is the TLS server; the head unit is the TLS client. Each MESSAGE_ENCAPSULATED_SSL received drives one SSL_accept() and the resulting write-BIO contents are sent back as another MESSAGE_ENCAPSULATED_SSL (PLAIN\|Bulk, channel 0). | `AACS/AAServer/src/AaCommunicator.cpp` L270-L300 |
| `AACS cert file names` | AAClient: CRT_FILE="headunit.crt", PRIVKEY_FILE="headunit.key", DHPARAM_FILE="dhparam.pem" | Head-unit-side material file names; dhparam.pem is NOT present in the repository. | `AACS/AAClient/src/AaCommunicator.cpp` L21-L23 |
| `AACS server cert file names` | AAServer: CRT_FILE="android_auto.crt", PRIVKEY_FILE="android_auto.key", DHPARAM_FILE="dhparam.pem" | Phone-side material file names. | `AACS/AAServer/src/AaCommunicator.cpp` L32-L34 |
| `AACS/AAClient/ssl/headunit.crt` | Issuer C=US/ST=California/L=Mountain View/O=Google Automotive Link; Subject C=US/ST=California/L=Mountain View/O=Android-Auto-Internal/OU=01; serial 0x0111; notBefore Jul  4 07:00:00 2014; notAfter Aug  1 17:21:23 2048 | Head-unit certificate shipped with AACS. Dates/subject read via `openssl x509 -noout -subject -issuer -dates -serial`. | `AACS/AAClient/ssl/headunit.crt` L1-L2 |
| `AACS/AAServer/ssl/android_auto.crt` | Issuer O=Google Automotive Link; Subject C=US/ST=California/L=Mountain View/O=CarService/OU=53; serial 0x0308; notBefore Jul  4 00:00:00 2014 GMT; notAfter Aug 24 12:29:12 2022 GMT (EXPIRED) | Phone/server-side certificate shipped with AACS; already past its notAfter date. | `AACS/AAServer/ssl/android_auto.crt` L1-L2 |
| `AACS key format` | -----BEGIN PRIVATE KEY----- (PKCS#8) | Both AACS keys are PKCS#8 PEM, unlike aasdk's PKCS#1 "RSA PRIVATE KEY". | `AACS/AAClient/ssl/headunit.key` L1 |
| `WifiInfoResponse.access_point_type` | STATIC = 0, DYNAMIC = 1 | Field 5 of the RFCOMM `WifiInfoResponse`, telling the phone whether the head unit's access point assigns addresses. Every reference sends DYNAMIC except openauto, which sends STATIC (0) alongside a security mode its own TODO admits may be wrong (`AndroidBluetoothServer.cpp` L162-L176) — so a real unit sending STATIC is not something any reference has modelled. Headway logs it as its own line when it is STATIC, because it explains a missing DHCP lease entirely and independently of the GrapheneOS MAC problem below. **CONFIRMED on the target vehicle, 2026-08-13:** a 2021 Chevrolet Infotainment 3 unit sends `20 08`-terminated `WifiInfoResponse` frames carrying STATIC on every handshake, i.e. it advertises that it assigns no addresses. Its `IP_PROVISIONING` failures were therefore the unit behaving as announced, and a static IP on the phone is the correct configuration rather than a workaround. **No reference implementation sends STATIC to a phone**, so nothing in the prior art models this. | `aap_protobuf/service/wifiprojection/message/AccessPointType.proto`; senders at `aa-proxy-rs/src/bluetooth.rs` L6226-L6237, `openauto/src/btservice/AndroidBluetoothServer.cpp` L162-L176 |
| `keepalive during channel open` | The phone MUST answer `PING_REQUEST` while it is waiting for a `CHANNEL_OPEN_RESPONSE`, not merely tolerate it | **Observed on a real head unit, 2026-08-13.** A 2021 Chevrolet Infotainment 3 unit completed authentication and service discovery, the phone began opening SENSOR, the unit sent `PING_REQUEST` on CONTROL, the phone skipped it as an interleaved message — and the unit dropped the link 9 ms later. Answering keepalives only in the *running* session leaves this window open, and it is precisely when a unit checks whether the phone it just authenticated is present. No reference documents this window separately, because every reference implements the head unit rather than the phone. `Phase1HandshakeAcceptanceTest` asserts the answer is sent. | Real-vehicle capture 2026-08-13 15:04:23; `AapSession.openChannel` |
| `services a 2021 Chevrolet Infotainment 3 advertises` | SENSOR, MEDIA_SINK, MEDIA_SINK_VIDEO, MEDIA_SINK_MEDIA_AUDIO, MEDIA_SINK_GUIDANCE_AUDIO, MEDIA_SINK_SYSTEM_AUDIO, MEDIA_SINK_TELEPHONY_AUDIO, INPUT_SOURCE, MEDIA_SOURCE_MICROPHONE, BLUETOOTH, RADIO, NAVIGATION_STATUS, MEDIA_PLAYBACK_STATUS; head unit name `myChevrolet` | **First real-vehicle service discovery, 2026-08-13.** Confirms the polarity recorded in `AapSession`'s KDoc — the *head unit* advertises services and the phone opens them — against real hardware rather than only against the references. Note it offers MEDIA_SOURCE_MICROPHONE, which is the car-mic channel Phase 5 needs. | Real-vehicle capture 2026-08-13 15:04:23.602 |
| `GrapheneOS Android Auto Wi-Fi carve-out` | `if (GmsCompat.isAndroidAuto()) { macRandomizationSetting = RANDOMIZATION_PERSISTENT; mIsSendDhcpHostnameEnabled = true; }` | **Platform behaviour, not an AAP constant, and the reason an unprivileged clean-room phone implementation cannot get an address from a car that Google's app can.** GrapheneOS defaults `macRandomizationSetting` to a GrapheneOS-only `RANDOMIZATION_ALWAYS` (= 100, `WifiConfiguration.java` L1913) that re-randomizes on every connect, and defaults `mIsSendDhcpHostnameEnabled` to false where AOSP defaults it true — then flips both back for the Gearhead package only, citing os-issue-tracker#4139. A `WifiNetworkSpecifier` connection inherits the *requesting app's* `WifiConfiguration` (`WifiNetworkFactory.java` L1160-L1161 copy-constructs it) so Headway's is always `RANDOMIZATION_ALWAYS`. `setSendDhcpHostnameEnabled` is `@SystemApi`; no builder equivalent exists on either specifier or suggestion in android-35 (checked with `javap`). See BLOCKERS.md B-006. | GrapheneOS `WifiConfiguration.java` L3400-L3405, L1913; AOSP `WifiNetworkFactory.java` L1160-L1161 |
| `certificates available to present as the phone` | 3, one CA, two unexpired: `phone` (O=CarService OU=53, notAfter 2022-08-24, EXPIRED), `internal` (O=Android-Auto-Internal OU=01, notAfter 2048-08-01), `headunit` (O=JVC Kenwood OU=01, notAfter 2045-04-29) | **Derived observation, not a protocol constant.** All three certificates in the reference pool share the issuer `C=US/ST=California/L=Mountain View/O=Google Automotive Link`, and only the phone-role one has expired. The other two were issued for the head-unit role, which is the only stated difference between them and a usable phone certificate — nothing in any reference says a head unit checks the subject or a role attribute, and nothing says it does not. Headway therefore presents them in turn on successive authentication rejections (`AapTls.bundledPhoneCredentials`). Issuers and dates read with `openssl x509 -noout -subject -issuer -dates`; each key checked against its certificate on the RSA modulus. **Untested against a real head unit** — BLOCKERS.md B-003 workaround 0. | `AACS/AAServer/ssl/android_auto.crt`, `AACS/AAClient/ssl/headunit.crt`, `aasdk/src/Messenger/Cryptor.cpp` L253-L276 |
| `aa-proxy-rs frame flag constants` | HEADER_LENGTH = 4, FRAME_TYPE_FIRST = 1<<0, FRAME_TYPE_LAST = 1<<1, FRAME_TYPE_MASK = 3, _CONTROL = 1<<2, ENCRYPTED = 1<<3 | Third-implementation confirmation of the frame header: 1 byte channel, 1 byte flags, 2 bytes big-endian length (plus 4 more length bytes when FIRST). | `aa-proxy-rs/src/mitm.rs` L138-L144 |
| `aa-proxy-rs SSL encapsulation frame` | channel = 0x00, flags = FRAME_TYPE_FIRST \| FRAME_TYPE_LAST (0x03), payload = [msgId 0x0003 BE][TLS bytes] | Exact bytes of a MESSAGE_ENCAPSULATED_SSL frame: no CONTROL bit, no ENCRYPTED bit. | `aa-proxy-rs/src/mitm.rs` L3875-L3887 |
| `aa-proxy-rs SSL decapsulation` | if payload[0..2] BE == MESSAGE_ENCAPSULATED_SSL then feed payload[2..] into TLS | Confirms the TLS record stream begins immediately after the 2-byte message ID with no length prefix of its own. | `aa-proxy-rs/src/mitm.rs` L1087-L1094 |
| `aa-proxy-rs version field offsets` | payload[2..4] = major BE, payload[4..6] = minor BE, payload[6..8] = status BE (response only) | Byte-exact confirmation of the version request/response layout from a third implementation. | `aa-proxy-rs/src/mitm.rs` L1426-L1428 |
| `aa-proxy-rs version response status extraction` | u16::from_be_bytes([payload[6], payload[7]]) when payload.len() >= 8 | Status is the 4th big-endian u16 word and only present when the payload is at least 8 bytes. | `aa-proxy-rs/src/mitm.rs` L1541-L1548 |
| `aa-proxy-rs default protocol version override` | major = 5, minor = 1 (override disabled by default) | aa-proxy-rs can rewrite the negotiated version; its default target is 5.1, indicating real-world AAP versions well above aasdk's 1.6. | `aa-proxy-rs/src/config.rs` L1025-L1027 |
| `aa-proxy-rs TLS version pin` | rustls with_protocol_versions(&[&TLS12]) on both server and client sides | aa-proxy-rs pins TLS 1.2 exactly for both the head-unit-facing (TLS server) and phone-facing (TLS client) connections, and disables session resumption / TLS 1.3 tickets. | `aa-proxy-rs/src/ssl_rustls.rs` L447-L484 |
| `aa-proxy-rs TLS SNI / server name` | "android.auto" | Server name aa-proxy-rs uses when acting as the TLS client toward the head unit. | `aa-proxy-rs/src/ssl_rustls.rs` L477-L479 |
| `aa-proxy-rs certificate/key file names` | {keys_path}/md_cert.pem + md_key.pem (HeadUnit side), {keys_path}/hu_cert.pem + hu_key.pem (MobileDevice side); default keys_path /etc/aa-proxy-rs/ | aa-proxy-rs ships no certificates; it loads them at runtime from the config dir. README additionally lists galroot_cert.pem, which the code never reads. | `aa-proxy-rs/src/ssl_rustls.rs` L435-L442 |
| `observed negotiated cipher suite (example in log string)` | "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256" | Documented example of the cipher suite negotiated on an AAP TLS session; the actual suite is whatever rustls/OpenSSL negotiates - no suite list is pinned anywhere in the references. | `aa-proxy-rs/src/ssl_rustls.rs` L334-L336 |
| `encrypted-frame gate (send)` | message_->getEncryptionType() == EncryptionType::ENCRYPTED -> cryptor_->encrypt() | Nothing else gates encryption: the per-message EncryptionType flag alone decides. There is no explicit "session encrypted" state check on the wire path. | `aasdk/src/Messenger/MessageOutStream.cpp` L103-L120 |
| `encrypted-frame gate (receive)` | message_->getEncryptionType() == EncryptionType::ENCRYPTED -> cryptor_->decrypt(payload, buffer, frameSize_) | Receive-side counterpart; the frame's ENCRYPTED bit alone routes the payload through TLS decryption. | `aasdk/src/Messenger/MessageInStream.cpp` L133-L147 |

### 2.3 Message definitions


#### `AuthResponse`

Source: `aasdk/protobuf/aap_protobuf/service/control/message/AuthResponse.proto`

```proto
syntax="proto2";

package aap_protobuf.service.control.message;

message AuthResponse {
    required int32 status = 1;
}
```

#### `AuthResponse (aa-proxy-rs copy, identical)`

Source: `aa-proxy-rs/src/protos/protos.proto`

```proto
message AuthResponse { required int32 status = 1; }
```

#### `MessageStatus (enum used as AuthResponse.status and version-response status)`

Source: `aasdk/protobuf/aap_protobuf/shared/MessageStatus.proto`

```proto
syntax="proto2";

package aap_protobuf.shared;

enum MessageStatus {
  STATUS_UNSOLICITED_MESSAGE = 1;
  STATUS_SUCCESS = 0;
  STATUS_NO_COMPATIBLE_VERSION = -1;
  STATUS_CERTIFICATE_ERROR = -2;
  STATUS_AUTHENTICATION_FAILURE = -3;
  STATUS_INVALID_SERVICE = -4;
  STATUS_INVALID_CHANNEL = -5;
  STATUS_INVALID_PRIORITY = -6;
  STATUS_INTERNAL_ERROR = -7;
  STATUS_MEDIA_CONFIG_MISMATCH = -8;
  STATUS_INVALID_SENSOR = -9;
  STATUS_BLUETOOTH_PAIRING_DELAYED = -10;
  STATUS_BLUETOOTH_UNAVAILABLE = -11;
  STATUS_BLUETOOTH_INVALID_ADDRESS = -12;
  STATUS_BLUETOOTH_INVALID_PAIRING_METHOD = -13;
  STATUS_BLUETOOTH_INVALID_AUTH_DATA = -14;
  STATUS_BLUETOOTH_AUTH_DATA_MISMATCH = -15;
  STATUS_BLUETOOTH_HFP_ANOTHER_CONNECTION = -16;
  STATUS_BLUETOOTH_HFP_CONNECTION_FAILURE = -17;
  STATUS_KEYCODE_NOT_BOUND = -18;
  STATUS_RADIO_INVALID_STATION = -19;
  STATUS_INVALID_INPUT = -20;
  STATUS_RADIO_STATION_PRESETS_NOT_SUPPORTED = -21;
  STATUS_RADIO_COMM_ERROR = -22;
  STATUS_AUTHENTICATION_FAILURE_CERT_NOT_YET_VALID = -23;
  STATUS_AUTHENTICATION_FAILURE_CERT_EXPIRED = -24;
  STATUS_PING_TIMEOUT = -25;
  STATUS_COMMAND_NOT_SUPPORTED = -250;
  STATUS_FRAMING_ERROR = -251;
  STATUS_UNEXPECTED_MESSAGE = -253;
  STATUS_BUSY = -254;
  STATUS_OUT_OF_MEMORY = -255;
}
```

#### `ControlMessageType`

Source: `aasdk/protobuf/aap_protobuf/service/control/ControlMessageType.proto`

```proto
syntax="proto2";

package aap_protobuf.service.control.message;

enum ControlMessageType
{
    MESSAGE_VERSION_REQUEST = 1;
    MESSAGE_VERSION_RESPONSE = 2;
    MESSAGE_ENCAPSULATED_SSL = 3;
    MESSAGE_AUTH_COMPLETE = 4;
    MESSAGE_SERVICE_DISCOVERY_REQUEST = 5;
    MESSAGE_SERVICE_DISCOVERY_RESPONSE = 6;
    MESSAGE_CHANNEL_OPEN_REQUEST = 7;
    MESSAGE_CHANNEL_OPEN_RESPONSE = 8;
    MESSAGE_CHANNEL_CLOSE_NOTIFICATION = 9;
    MESSAGE_PING_REQUEST = 11;
    MESSAGE_PING_RESPONSE = 12;
    MESSAGE_NAV_FOCUS_REQUEST = 13;
    MESSAGE_NAV_FOCUS_NOTIFICATION = 14;
    MESSAGE_BYEBYE_REQUEST = 15;
    MESSAGE_BYEBYE_RESPONSE = 16;
    MESSAGE_VOICE_SESSION_NOTIFICATION = 17;
    MESSAGE_AUDIO_FOCUS_REQUEST = 18;
    MESSAGE_AUDIO_FOCUS_NOTIFICATION = 19;
    MESSAGE_CAR_CONNECTED_DEVICES_REQUEST = 20;
    MESSAGE_CAR_CONNECTED_DEVICES_RESPONSE = 21;
    MESSAGE_USER_SWITCH_REQUEST = 22;
    MESSAGE_BATTERY_STATUS_NOTIFICATION = 23;
    MESSAGE_CALL_AVAILABILITY_STATUS = 24;
    MESSAGE_USER_SWITCH_RESPONSE = 25;
    MESSAGE_SERVICE_DISCOVERY_UPDATE = 26;
    MESSAGE_UNEXPECTED_MESSAGE = 255;
    MESSAGE_FRAMING_ERROR = 65535;
}
```

#### `VersionRequestOptions`

Source: `aasdk/protobuf/aap_protobuf/service/control/message/VersionRequestOptions.proto`

```proto
syntax="proto2";

package aap_protobuf.channel.control.version;

message VersionRequestOptions {
  optional int64 snapshot_version = 1;
}
```

#### `VersionResponseOptions`

Source: `aasdk/protobuf/aap_protobuf/service/control/message/VersionResponseOptions.proto`

```proto
syntax="proto2";

package aap_protobuf.service.control.message;

import "aap_protobuf/service/control/message/ConnectionConfiguration.proto";

message VersionResponseOptions {
  optional ConnectionConfiguration connection_configuration = 1;
}
```

### 2.4 Where the references disagree


**Advertised protocol version differs across every reference**

aasdk/openauto advertises 1.6 (aasdk/include/aasdk/Version.hpp L22-L23). AACS's head-unit client advertises 1.1 (AACS/AAClient/src/AaCommunicator.cpp L56) and its phone-side server answers 1.5 for any major==1 (AACS/AAServer/src/AaCommunicator.cpp L89-L90). aa-proxy-rs's version-override default target is 5.1 (aa-proxy-rs/src/config.rs L1025-L1027) and its comment says a real HU may already advertise 6.0 (aa-proxy-rs/src/mitm.rs L1424). Prefer aasdk's 1.6 as the safe baseline, but note the negotiation is major-gated only in the AACS phone implementation.

**Bit 2 of the frame flags byte has inverted names in aasdk vs AACS**

aasdk: MessageType::SPECIFIC = 0, CONTROL = 1<<2 (aasdk/include/aasdk/Messenger/MessageType.hpp L26-L29). AACS: MessageTypeFlags::Control = 0, Specific = 1<<2 (AACS/include/enums.h L16-L19). The names are swapped. On the wire both agree, because both use their zero-valued enumerator for handshake frames: aasdk uses MessageType::SPECIFIC (0) for version request / encapsulated SSL / auth complete, AACS uses MessageTypeFlags::Control (0) for version request and auth complete and nothing (0) for encapsulated SSL - so all of them emit flags byte 0x03. aa-proxy-rs never sets its _CONTROL bit for handshake frames either (aa-proxy-rs/src/mitm.rs L3883). A clean-room implementation should emit 0x03 and not trust either naming.

**aasdk does not byte-swap the major/minor of the version response**

aasdk/src/Channel/Control/ControlServiceChannel.cpp L243-L250 converts only versionResponse[2] (status) via boost::endian::big_to_native and passes versionResponse[0]/[1] through raw, so the reported major/minor are byte-swapped on little-endian hosts (1 is logged as 256). The wire format itself is unambiguously big-endian for all three fields (aa-proxy-rs/src/mitm.rs L1426-L1428, L1547-L1548). Treat this as an aasdk bug, not as protocol truth.

**Version-response status is read as an unsigned 16-bit value but MessageStatus is a signed int32 enum**

aasdk/src/Channel/Control/ControlServiceChannel.cpp L245-L246 does static_cast<MessageStatus>(big_to_native(uint16)). A wire status of 0xFFFF (STATUS_NO_COMPATIBLE_VERSION = -1 per aasdk/protobuf/aap_protobuf/shared/MessageStatus.proto L8) therefore becomes 65535, so openauto's comparison against STATUS_NO_COMPATIBLE_VERSION at openauto/src/autoapp/Service/AndroidAutoEntity.cpp L115 never fires. A correct implementation should sign-extend the 16-bit status before comparing to MessageStatus.

**Peer certificate verification is disabled everywhere, by three different mechanisms**

aasdk: SSL_set_verify(ssl, SSL_VERIFY_NONE, nullptr) (aasdk/src/Transport/SSLWrapper.cpp L139). AACS (both sides): SSL_CTX_set_verify(ctx, SSL_VERIFY_PEER, cb) but the callback unconditionally returns 1 (AACS/AAClient/src/AaCommunicator.cpp L229-L236 and AACS/AAServer/src/AaCommunicator.cpp L335-L342). aa-proxy-rs: custom ServerCertVerifier/ClientCertVerifier that accept anything, reached via .dangerous() (aa-proxy-rs/src/ssl_rustls.rs L102-L110, L472-L473). aa-proxy-rs's stated reason is that the Google Automotive Link certs are X.509 v1 and rustls rejects them (aa-proxy-rs/src/ssl_rustls.rs L4-L6); the aasdk cert is indeed 'Version: 1 (0x0)'. Practical consequence: the peer certificate must be *presented* (mutual TLS) but is not validated.

**TLS version ceiling is specified three different ways**

aasdk pins TLSv1_2_client_method() only on OpenSSL < 1.1.0 and otherwise uses the version-flexible TLS_client_method() with no min/max set (aasdk/src/Transport/SSLWrapper.cpp L97-L103), so on modern OpenSSL it would offer TLS 1.3. AACS uses SSLv23_*_method() plus SSL_CTX_set_options(ctx, SSL_OP_NO_TLSv1_3), capping at TLS 1.2 (AACS/AAClient/src/AaCommunicator.cpp L199, L230; AACS/AAServer/src/AaCommunicator.cpp L305, L336). aa-proxy-rs pins exactly &[&TLS12] on both sides (aa-proxy-rs/src/ssl_rustls.rs L454, L471). Two of three references force TLS 1.2; a clean-room implementation should target TLS 1.2.

**No cipher suite is pinned anywhere**

None of the references configure a cipher list. The only concrete suite named in any file is the doc-comment example "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256" in aa-proxy-rs/src/ssl_rustls.rs L334. AACS additionally loads a 2048-bit DH parameter file and calls SSL_CTX_set_ecdh_auto (AACS/AAServer/src/AaCommunicator.cpp L312, L321-L334), implying DHE/ECDHE suites are expected; that dhparam.pem file is not present in the repository.

**aasdk cert/README.md describes runtime certificate file loading that Cryptor.cpp does not implement**

aasdk/cert/README.md L27-L33 claims the application tries /etc/openauto/headunit.crt, /usr/share/aasdk/cert/headunit.crt, ./cert/headunit.crt, ../cert/headunit.crt and falls back to an embedded certificate, and L56-L59 shows a loadCertificate() helper. The actual code in aasdk/src/Messenger/Cryptor.cpp L37-L46 reads only the compiled-in strings cCertificate/cPrivateKey (defined at L253-L299); there is no file-path search anywhere in Cryptor.cpp. The on-disk aasdk/cert/headunit.crt and .key are byte-equivalent to the embedded strings (verified: RSA modulus md5 0acfbc22b3f8dc54953d7f26897ab267 for both cert and key).

**Certificate identities and validity differ between references; one is expired**

aasdk/cert/headunit.crt: Google Automotive Link -> JVC Kenwood OU=01, serial 27, valid Jul 4 2014 -> Apr 29 2045. AACS/AAClient/ssl/headunit.crt: Google Automotive Link -> Android-Auto-Internal OU=01, serial 0x0111, valid Jul 4 2014 -> Aug 1 2048. AACS/AAServer/ssl/android_auto.crt: Google Automotive Link -> CarService OU=53, serial 0x0308, valid Jul 4 2014 -> Aug 24 2022 - already EXPIRED. Key encodings also differ: aasdk ships PKCS#1 ("BEGIN RSA PRIVATE KEY"), AACS ships PKCS#8 ("BEGIN PRIVATE KEY"). All validity data was read with `openssl x509 -noout -subject -issuer -dates -serial`, not from literal file text.

**aa-proxy-rs ships no certificates and its README lists a file the code never reads**

aa-proxy-rs/README.md L148-L153 requires hu_key.pem, hu_cert.pem, md_key.pem, md_cert.pem and galroot_cert.pem in /etc/aa-proxy-rs/. aa-proxy-rs/src/ssl_rustls.rs L435-L442 only loads {prefix}_cert.pem and {prefix}_key.pem; grepping src/ finds no reference to galroot at all. So no root/CA certificate is used at runtime, consistent with verification being disabled.

**aasdk_proto/ vs protobuf/aap_protobuf/ do not overlap**

aasdk/aasdk_proto/ contains only five wireless-projection files (WifiChannelMessageIdsEnum.proto, WifiInfoRequestMessage.proto, WifiInfoResponseMessage.proto, WifiSecurityRequestMessage.proto, WifiSecurityResponseMessage.proto). aasdk/protobuf/aap_protobuf/ contains 254 .proto files including ControlMessageType.proto, MessageStatus.proto, AuthResponse.proto, VersionRequestOptions.proto and VersionResponseOptions.proto. No file name appears in both trees, so there is no version/TLS handshake definition to compare between them - protobuf/aap_protobuf/ is the sole source in aasdk for this domain.

**MESSAGE_ENCAPSULATED_SSL frame flags: aasdk vs AACS naming, and whether the CONTROL bit is set**

aasdk sends the handshake frame with MessageType::SPECIFIC (aasdk/src/Channel/Control/ControlServiceChannel.cpp L55-L56), AACS sends it with no MessageTypeFlags argument at all - just 'EncryptionType::Plain | FrameType::Bulk' (AACS/AAClient/src/AaCommunicator.cpp L181, AACS/AAServer/src/AaCommunicator.cpp L289) - and aa-proxy-rs sets only FRAME_TYPE_FIRST|FRAME_TYPE_LAST (aa-proxy-rs/src/mitm.rs L3883). All three therefore put 0x03 in the flags byte, but AACS sets bit 2 (its Specific=1<<2 is never used here, and its Control=0) for VersionRequest/AuthComplete while aasdk never sets bit 2 for any of the three handshake messages. Net wire result is identical (0x03) in every case; only aasdk's ServiceDiscovery/onward frames add the 0x08 ENCRYPTED bit.

**VersionRequestOptions / VersionResponseOptions protobufs are defined but not used on the version messages**

aasdk/protobuf/aap_protobuf/service/control/message/VersionRequestOptions.proto and VersionResponseOptions.proto exist and aa-proxy-rs's pretty-printer parses MESSAGE_VERSION_REQUEST/RESPONSE as these protobufs (aa-proxy-rs/src/mitm_prettyprint.rs L827-L828). But aasdk builds and parses the version messages as raw big-endian uint16 triples/quads, not protobuf (aasdk/src/Channel/Control/ControlServiceChannel.cpp L46-L49 and L243-L250), and aa-proxy-rs's own byte-level override code also treats them as raw u16s at fixed offsets (aa-proxy-rs/src/mitm.rs L1426-L1428, L1491-L1492). Note the package names also disagree: VersionRequestOptions is in package aap_protobuf.channel.control.version while VersionResponseOptions is in aap_protobuf.service.control.message. Treat the version messages as raw big-endian words; any trailing protobuf options bytes would follow the fixed header.

---

## 3. Wireless Bluetooth handshake

The RFCOMM exchange that hands the phone the car's Wi-Fi credentials and TCP endpoint. This is what makes wireless Android Auto work and it is the least documented part of the protocol.

### 3.1 Sequence

```text
ROLES. The head unit (HU) — or the dongle/proxy impersonating one — is the RFCOMM SERVER and the Wi-Fi ACCESS POINT and the TCP SERVER. The phone is the RFCOMM CLIENT, the Wi-Fi STATION, and the TCP CLIENT. The HU speaks first on RFCOMM.

STEP 0 — HU brings up its local Bluetooth state. Adapter powered on, alias set, discoverable + pairable. aa-proxy-rs: `adapter.set_alias(...); adapter.set_powered(true); adapter.set_pairable(true); ... adapter.set_discoverable(true); adapter.set_discoverable_timeout(0);` (aa-proxy-rs/src/bluetooth.rs L4500-L4507). Dongle: `setPower(true); setPairable(true);` (WirelessAndroidAutoDongle/.../bluetoothHandler.cpp L260-L271, powerOn()). openauto: `localDevice_->powerOn(); localDevice_->setHostMode(QBluetoothLocalDevice::HostDiscoverable);` (openauto/src/btservice/BluetoothHandler.cpp L36-L39).

STEP 1 — HU registers the AA Wireless RFCOMM SERVER profile, UUID 4de17a00-52cb-11e6-bdf4-0800200c9a66. aa-proxy-rs and aawgd both pin RFCOMM channel 8, role "server", auth/authorization not required (aa-proxy-rs/src/bluetooth.rs L4513-L4522; WirelessAndroidAutoDongle/.../bluetoothHandler.cpp L126-L130). openauto instead lets Qt pick a dynamic channel and publishes it in the SDP record's ProtocolDescriptorList as [L2CAP],[RFCOMM, portNumber] (openauto/src/btservice/AndroidBluetoothService.cpp L46-L54, driven by BluetoothHandler.cpp L41-L56). A clean-room implementation must therefore be able to BOTH publish and DISCOVER the channel, not assume 8 — aa-proxy-rs's `discover_rfcomm_channel_via_internal_sdp` issues an SDP ServiceSearchAttributeRequest over L2CAP PSM 0x0001 for the 128-bit AA Wireless UUID with MaximumAttributeByteCount 0x03f0 and AttributeIDList range 0x0000..0xffff, then scans the returned ProtocolDescriptorList for the RFCOMM UUID16 0x0003 (`19 00 03`) followed by the channel byte (aa-proxy-rs/src/bluetooth.rs L54-L62, L87-L128, L157-L199, L341-L356).

STEP 1b — HU also registers the HSP HS profile (00001108-…). aa-proxy-rs registers it as a plain profile with no channel pin (bluetooth.rs L7703-L7711) and, critically, ACCEPTS the incoming HSP RFCOMM control stream and immediately DROPS it, because holding it open without a real HSP/HFP AT state machine makes Android route calls to the device and hang (bluetooth.rs L7739-L7755). aawgd registers HSP HS only when not in DONGLE_MODE (bluetoothHandler.cpp L133-L143).

STEP 1c (dongle mode only) — the dongle additionally starts a BLE advertisement of type "peripheral" whose ServiceUUIDs list contains the AA Wireless UUID and whose LocalName is the adapter alias "AndroidAuto-Dongle-<suffix>" (WirelessAndroidAutoDongle/.../bluetoothHandler.cpp L146-L163, L252-L254, L268-L270).

STEP 2 — HU binds its TCP listener BEFORE the handshake. aa-proxy-rs binds 0.0.0.0:5288 (and 0.0.0.0:5277 for DHU) at io_loop start, then blocks on `tcp_start.notified()` and only begins accepting once the Bluetooth handshake signals (aa-proxy-rs/src/proxy.rs L674-L685, L794-L802). aawgd starts the TCP server on getWifiInfo().port and only then powers on Bluetooth in PHONE_FIRST/USB_FIRST strategies (WirelessAndroidAutoDongle/.../aawgd.cpp L32-L43; proxyHandler.cpp L225-L257). openauto's acceptor is constructed on port 5000 in the App constructor and startServerSocket() runs at App::start (openauto/src/autoapp/App.cpp L34, L169-L177).

STEP 3 — HU pokes the phone so Android Auto wakes up and initiates. Both aa-proxy-rs and aawgd call ConnectProfile with the HSP AG UUID 00001112-… on the paired phone: aawgd `connectProfile(isDongleMode ? "" : HSP_AG_UUID)` in a 20-second retry loop (bluetoothHandler.cpp L200-L234); aa-proxy-rs `device.connect_profile(&HSP_AG_UUID)` with 3 attempts per address (bluetooth.rs L6183-L6195) and exponential backoff (bluetooth.rs L5936-L5967). In dongle mode aa-proxy-rs instead forces a plain BR/EDR `device.connect()` to devices whose name starts with "AndroidAuto-" (bluetooth.rs L5968-L6002).

STEP 4 — PHONE opens the RFCOMM connection to the HU's AA Wireless profile. This is the only inbound connection; the HU waits on its profile handle for a NewConnection/ProfileRequest and accepts, obtaining the RFCOMM fd/stream (aa-proxy-rs bluetooth.rs L6008-L6009 → wait_for_phone_aa_profile_request, and L6043-L6068 for the accept path; aawgd AAWirelessProfile::NewConnection → AAWirelessLauncher(fd).launch(), bluetoothProfiles.cpp L164-L170; openauto onClientConnected, AndroidBluetoothServer.cpp L65-L89).

STEP 5 — Framing is established. Every message on this RFCOMM socket is: [u16 payload_length, big-endian][u16 message_id, big-endian][payload_length bytes of proto2-serialized protobuf]. The length field EXCLUDES the 4-byte header. Confirmed three ways: aa-proxy-rs send (bluetooth.rs L4681-L4689) and receive (L4708-L4715); aawgd SendMessage using htons and total = messageSize + 4 (bluetoothProfiles.cpp L99-L112) and ReadMessage using ntohs (L125-L152); openauto using QDataStream `ds << (uint16_t) byteSize; ds << type;` with the payload written at out.data()+4 (AndroidBluetoothServer.cpp L210-L218) and parsing `stream >> length; ... stream >> rawMessageId;` after checking `buffer.length() < length + 4` (L97-L117). Note openauto relies on QDataStream's default byte order rather than an explicit htons.

STEP 6 — HU sends first. Two observed openings:
  (a) Minimal (aa-proxy-rs, aawgd): HU sends WifiStartRequest (id 1) immediately containing ip_address = the HU's AP-side IPv4 and port = the AAP TCP port. aa-proxy-rs: bluetooth.rs L6214-L6221; aawgd: bluetoothProfiles.cpp L37-L44.
  (b) Versioned (openauto, and real Gearhead head units): HU sends WifiVersionRequest (id 4) FIRST and then WifiStartRequest (id 1) — openauto/src/btservice/AndroidBluetoothServer.cpp L79-L85. The phone answers the version exchange with WifiVersionResponse (id 5). aa-proxy-rs's MITM path treats any HU frames arriving before WifiStartRequest as "pre-bootstrap" frames and forwards them, and explicitly waits for the phone's WifiVersionResponse to relay back (bluetooth.rs L1861-L1875, L1980-L1998). Newer head units may also emit WifiSetupInfo (id 11) in this pre-bootstrap window (bluetooth.rs L1922-L1954). Some head units omit WifiStartRequest entirely and only carry the endpoint inside WifiVersionRequest's WifiProjectionProtocolInfo sub-message (ip at field 1, port at field 2); aa-proxy-rs waits up to 3s and then synthesizes WifiStartRequest from it (bluetooth.rs L2042-L2060).

STEP 7 — PHONE sends WifiInfoRequest (id 2), an EMPTY payload (length 0). aa-proxy-rs reads it as stage 2 (bluetooth.rs L6224); aawgd asserts on it and aborts if it's anything else (bluetoothProfiles.cpp L46-L51); aa-proxy-rs's probe mode sends it with a zero-length body (`&[]`, bluetooth.rs L7594-L7600).

STEP 8 — HU replies WifiInfoResponse (id 3) with ssid, key/password, bssid, security_mode, access_point_type. aa-proxy-rs sends WPA2_PERSONAL (wire value 8) and DYNAMIC (1) (bluetooth.rs L6226-L6237); aawgd sends the same pair from Config (bluetoothProfiles.cpp L53-L61, common.cpp L57-L67); openauto sends the wifiprojection symbol WPA2_ENTERPRISE — whose numeric value is also 8 — and STATIC (0), with a TODO noting the enum mismatch (AndroidBluetoothServer.cpp L162-L176). The bssid is the HU's wlan0 MAC; the phone uses it to build its Wi-Fi NetworkSpecifier, and aa-proxy-rs warns that an empty bssid makes the phone not even attempt the Wi-Fi connect (bluetooth.rs L7175-L7193).

STEP 9 — PHONE joins the HU's Wi-Fi AP using those credentials (WPA2-PSK/CCMP in the dongle's hostapd config), obtaining an address (dnsmasq hands out 10.0.0.2-10.0.0.20 in the dongle image).

STEP 10 — PHONE sends WifiStartResponse (id 7): optional ip_address (field 1), optional port (field 2), required status (field 3, aaw Status enum, 0 = STATUS_SUCCESS). aa-proxy-rs reads it as stage 4 (bluetooth.rs L6240) and, when synthesizing one, encodes the status at field 3 explicitly (L916-L920).

STEP 11 — PHONE sends WifiConnectStatus / WifiConnectionStatus (id 6): required status at field 1, optional error_message at field 2. Success on the wire is literally [0x08, 0x00]; the observed failure frame is [0x08, FD FF FF FF FF FF FF FF FF 01] (= -i64::MAX). aa-proxy-rs aborts the session if byte 1 is non-zero (bluetooth.rs L4738-L4747, L6243). aawgd simply does two blind ReadMessage() calls to consume steps 10 and 11 (bluetoothProfiles.cpp L63-L64).

STEP 12 — HU releases its TCP accept. aa_handshake calls `Self::send_params(...)` and, on success, `tcp_start.notify_one()` (aa-proxy-rs/src/bluetooth.rs L7794, L7807), which unblocks the accept in proxy.rs (L794-L802). The RFCOMM link is deliberately kept open afterwards in quick_reconnect mode so the handshake can be replayed without re-pairing (bluetooth.rs L7809-L7826).

STEP 13 — PHONE opens a TCP connection to ip_address:port from WifiStartRequest and the normal AAP session (version request, SSL/TLS handshake, service discovery, channels) begins on that socket. The port is entirely HU-chosen and carried in-band: 5288 for aa-proxy-rs and aawgd, 5000 for openauto. GalConstants declares WIFI_PORT = 30515 but no reference implementation here binds it.

SEPARATE, LATER MECHANISM — once the AAP session is up, there is an in-band WifiProjection SERVICE channel (ChannelDescriptor field 14, WifiProjectionService{car_wifi_bssid=1}) with message ids 0x8001 WifiCredentialsRequest / 0x8002 WifiCredentialsResponse{car_wifi_password=1, car_wifi_security_mode=2, car_wifi_ssid=3, supported_wifi_channels=4, access_point_type=5}. This is how the phone re-fetches credentials over an already-established (typically USB) session to bootstrap a later wireless session; it is NOT part of the RFCOMM handshake and uses the SEQUENTIAL 0..9 WifiSecurityMode numbering. openauto implements it in src/autoapp/Service/WifiProjection/WifiProjectionService.cpp L73-L94, sending WPA2_PERSONAL (value 5) with a comment "Might need to set WPA2_ENTERPRISE".
```

### 3.2 Constants

| Constant | Value | Meaning | Source |
|---|---|---|---|
| `AAWG_PROFILE_UUID (AA Wireless service UUID) — Rust` | 4de17a00-52cb-11e6-bdf4-0800200c9a66 | The 128-bit Bluetooth SDP/RFCOMM service UUID for the Android Auto Wireless projection profile. The head unit registers this as an RFCOMM *server* profile; the phone connects to it as client. | `aa-proxy-rs/src/bluetooth.rs` L381 |
| `AAWG_PROFILE_UUID (AA Wireless service UUID) — C++ dongle` | 4de17a00-52cb-11e6-bdf4-0800200c9a66 | Same AA Wireless UUID, string form, registered with BlueZ ProfileManager1.RegisterProfile. | `WirelessAndroidAutoDongle/aa_wireless_dongle/package/aawg/src/bluetoothHandler.cpp` L23-L24 |
| `AA Wireless service UUID — openauto` | 4de17a00-52cb-11e6-bdf4-0800200c9a66 | Third independent confirmation of the AA Wireless UUID; openauto also puts SerialPort (0x1101) into ServiceClassIds alongside it and names the record "AndroidAuto WiFi projection automatic setup". | `openauto/src/btservice/AndroidBluetoothService.cpp` L26-L36 |
| `HSP_HS_UUID` | 00001108-0000-1000-8000-00805f9b34fb | Headset Profile - Headset (HS) role UUID. The head unit registers this locally so the phone sees a headset; required for Android to start Android Auto over the BT link. | `aa-proxy-rs/src/bluetooth.rs` L382 |
| `HSP_AG_UUID` | 00001112-0000-1000-8000-00805f9b34fb | Headset Profile - Audio Gateway (AG) role UUID. The head unit calls ConnectProfile(HSP_AG_UUID) *on the phone* to trigger/wake Android Auto, which then makes the phone connect back to the AA Wireless RFCOMM profile. | `aa-proxy-rs/src/bluetooth.rs` L383 |
| `HSP_AG_UUID / HSP_HS_UUID — C++ dongle` | 00001112-0000-1000-8000-00805f9b34fb / 00001108-0000-1000-8000-00805f9b34fb | Same two HSP UUIDs in the dongle firmware; HSP_AG_UUID is the one passed to Device1.ConnectProfile to kick the phone. | `WirelessAndroidAutoDongle/aa_wireless_dongle/package/aawg/src/bluetoothHandler.cpp` L26-L28 |
| `AA Wireless RFCOMM channel` | 8 | RFCOMM channel number the head unit's AA Wireless server profile is registered on (BlueZ Profile 'Channel' property), role = server, no authentication/authorization required. | `aa-proxy-rs/src/bluetooth.rs` L4513-L4521 |
| `AA Wireless RFCOMM channel — C++ dongle` | 8 | Identical registration via BlueZ ProfileManager1.RegisterProfile: Name="AA Wireless", Role="server", Channel=8. | `WirelessAndroidAutoDongle/aa_wireless_dongle/package/aawg/src/bluetoothHandler.cpp` L126-L130 |
| `HEADER_LEN` | 4 | RFCOMM frame header size: 2-byte payload length + 2-byte message id. | `aa-proxy-rs/src/bluetooth.rs` L43 |
| `MessageId::WifiStartRequest` | 1 | RFCOMM message id for WifiStartRequest (HU -> phone: here is my IP and TCP port). | `aa-proxy-rs/src/bluetooth.rs` L406-L417 |
| `MessageId::WifiInfoRequest` | 2 | RFCOMM message id for WifiInfoRequest (phone -> HU: give me your Wi-Fi credentials). Empty body. | `aa-proxy-rs/src/bluetooth.rs` L408 |
| `MessageId::WifiInfoResponse` | 3 | RFCOMM message id for WifiInfoResponse (HU -> phone: SSID/key/BSSID/security/AP type). | `aa-proxy-rs/src/bluetooth.rs` L409 |
| `MessageId::WifiVersionRequest` | 4 | RFCOMM message id for WifiVersionRequest (HU -> phone: protocol version + head unit info + supported Wi-Fi channels; may also carry a WifiProjectionProtocolInfo ip/port). | `aa-proxy-rs/src/bluetooth.rs` L410 |
| `MessageId::WifiVersionResponse` | 5 | RFCOMM message id for WifiVersionResponse (phone -> HU: protocol version, device serial, status, selected wifi channel type, device info). | `aa-proxy-rs/src/bluetooth.rs` L411 |
| `MessageId::WifiConnectStatus` | 6 | RFCOMM message id for WifiConnectStatus / WifiConnectionStatus (phone -> HU: did I manage to join your AP). | `aa-proxy-rs/src/bluetooth.rs` L412 |
| `MessageId::WifiStartResponse` | 7 | RFCOMM message id for WifiStartResponse (phone -> HU: ack of WifiStartRequest, with optional ip/port and a required status). | `aa-proxy-rs/src/bluetooth.rs` L413 |
| `MessageId::WifiPingRequest` | 8 | RFCOMM keepalive request. Not present in aasdk/dongle/openauto enums — only observed/used by aa-proxy-rs, which sends it as a synthetic keepalive toward a real head unit. | `aa-proxy-rs/src/bluetooth.rs` L414 |
| `MessageId::WifiPingResponse` | 9 | RFCOMM keepalive response. Only in aa-proxy-rs. | `aa-proxy-rs/src/bluetooth.rs` L415 |
| `MessageId::WifiSetupInfo` | 11 | RFCOMM message id observed from newer Gearhead head units; carries top-level major/minor protocol version varints at fields 1 and 2. Only in aa-proxy-rs. NOTE 10 is unassigned in every reference. | `aa-proxy-rs/src/bluetooth.rs` L416 |
| `aasdk aaw MessageId enum` | WIFI_START_REQUEST=1, WIFI_INFO_REQUEST=2, WIFI_INFO_RESPONSE=3, WIFI_VERSION_REQUEST=4, WIFI_VERSION_RESPONSE=5, WIFI_CONNECTION_STATUS=6, WIFI_START_RESPONSE=7 | Canonical aasdk protobuf enum for the RFCOMM handshake message ids. Agrees exactly with aa-proxy-rs and the dongle for 1..7; stops at 7 (no 8/9/11). | `aasdk/protobuf/aap_protobuf/aaw/MessageId.proto` L5-L13 |
| `dongle MessageId enum` | Invalid=-1, WifiStartRequest=1 … WifiStartResponse=7 | Third confirmation of the id assignment, from the aawgd C++ implementation. | `WirelessAndroidAutoDongle/aa_wireless_dongle/package/aawg/src/bluetoothProfiles.cpp` L68-L77 |
| `RFCOMM frame layout (send)` | [u16 payload_len BE][u16 message_id BE][payload bytes] | Exact wire framing of every AA Wireless RFCOMM message. Note the length field counts ONLY the protobuf payload, not the 4-byte header. | `aa-proxy-rs/src/bluetooth.rs` L4681-L4689 |
| `RFCOMM frame layout (receive)` | read 4-byte header, len = BE u16 at [0..2], message_id = BE u16 at [2..4], then read exactly len more bytes | Receive-side confirmation of the framing and byte order. | `aa-proxy-rs/src/bluetooth.rs` L4708-L4715 |
| `RFCOMM framing — C++ dongle (htons/ntohs)` | htons(messageSize) then htons(messageId), payload at offset 4; total length = messageSize + 4 | Independent confirmation that both 16-bit header fields are network byte order (big-endian) and that the length field excludes the header. | `WirelessAndroidAutoDongle/aa_wireless_dongle/package/aawg/src/bluetoothProfiles.cpp` L99-L112 |
| `RFCOMM framing — receive, C++ dongle` | ntohs of first 2 bytes = length, ntohs of next 2 bytes = messageId | Read side of the same framing. | `WirelessAndroidAutoDongle/aa_wireless_dongle/package/aawg/src/bluetoothProfiles.cpp` L126-L143 |
| `TCP_SERVER_PORT` | 5288 | TCP port the head unit (aa-proxy-rs) listens on for the AAP session; this is the value put into WifiStartRequest.port that tells the phone where to connect after joining the AP. | `aa-proxy-rs/src/config.rs` L25-L26 |
| `TCP_DHU_PORT` | 5277 | Separate TCP port aa-proxy-rs binds for Google's Desktop Head Unit (DHU), not used in the phone handshake. | `aa-proxy-rs/src/config.rs` L26 |
| `WifiStartRequest.port source (aa-proxy-rs)` | TCP_SERVER_PORT (5288) | Proof that the port advertised over RFCOMM is exactly the port the TCP server binds. | `aa-proxy-rs/src/main.rs` L491-L497 |
| `TCP listener bind (aa-proxy-rs)` | 0.0.0.0:5288 (MD) and 0.0.0.0:5277 (DHU) | The listeners are bound at startup, before the Bluetooth handshake completes; the accept only begins after tcp_start is notified by the BT handshake. | `aa-proxy-rs/src/proxy.rs` L674-L685 |
| `AAWG_PROXY_PORT (dongle default TCP port)` | 5288 | Dongle firmware default for the AAP TCP port announced in WifiStartRequest; overridable via env AAWG_PROXY_PORT. | `WirelessAndroidAutoDongle/aa_wireless_dongle/package/aawg/src/common.cpp` L57-L67 |
| `AAWG_PROXY_IP_ADDRESS (dongle default HU IP)` | 10.0.0.1 | IP put in WifiStartRequest.ip_address; matches the static address of wlan0 when the dongle is the AP. | `WirelessAndroidAutoDongle/aa_wireless_dongle/board/common/rootfs_overlay/etc/network/interfaces` L12-L16 |
| `aa-proxy-rs default wlan subnet / HU IP` | 10.0.0 → 10.0.0.1 (fallback if iface has no address) | aa-proxy-rs derives WifiStartRequest.ip_address from the live address of the configured interface, falling back to <wlan_subnet>.1. | `aa-proxy-rs/src/main.rs` L468-L485 |
| `openauto AAP TCP port` | 5000 | openauto listens for the Wi-Fi/TCP AAP client on 5000 and announces 5000 in WifiStartRequest.port. Differs from the 5288 used by aa-proxy-rs and the dongle — the port is HU-chosen and carried in-band, not fixed by the protocol. | `openauto/src/autoapp/App.cpp` L34 |
| `openauto WifiStartRequest.port` | 5000 | Confirms the announced port equals the TCP acceptor port in openauto, and that the HU sends WIFI_VERSION_REQUEST before WIFI_START_REQUEST. | `openauto/src/btservice/AndroidBluetoothServer.cpp` L79-L85 |
| `GalConstants.WIFI_PORT` | 30515 | A wireless-related port constant inside the AAP control protobufs (GalConstants). This is NOT the port used by the RFCOMM handshake in any reference implementation here — all of them carry their own port in WifiStartRequest.port. Record it as the Gearhead-declared default wireless projection port. | `aasdk/protobuf/aap_protobuf/channel/control/GalConstants.proto` L5-L9 |
| `GalConstants (aa-proxy-rs copy)` | WIFI_PORT = 30515; PROTOCOL_MAJOR_VERSION = 1; PROTOCOL_MINOR_VERSION = 6 | Identical GalConstants in aa-proxy-rs's protos.proto — the two independent copies agree. | `aa-proxy-rs/src/protos/protos.proto` L2064-L2068 |
| `SecurityMode (AAW/RFCOMM variant) — WPA2_PERSONAL` | 8 | Security mode enum used in the RFCOMM WifiInfoResponse by aa-proxy-rs and the dongle. WPA2_PERSONAL is 8 here, and the values are bit-flag-like (4/8/12, 20/24/28). | `aa-proxy-rs/src/protos/WifiInfoResponse.proto` L9-L20 |
| `SecurityMode (AAW/RFCOMM variant) — dongle copy` | WPA2_PERSONAL = 8, WPA_WPA2_PERSONAL = 12, WPA_ENTERPRISE = 20, WPA2_ENTERPRISE = 24, WPA_WPA2_ENTERPRISE = 28 | Byte-identical enum in WirelessAndroidAutoDongle, confirming the 4/8/12/20/24/28 numbering for the RFCOMM message. | `WirelessAndroidAutoDongle/aa_wireless_dongle/package/aawg/src/proto/WifiInfoResponse.proto` L9-L20 |
| `WifiSecurityMode (AAP WifiProjection variant) — aasdk canonical` | WPA2_PERSONAL = 5, WPA2_ENTERPRISE = 8 | The AAP-channel WifiProjection enum uses sequential 0..9 numbering. aasdk's aaw/WifiInfoResponse.proto IMPORTS THIS ENUM for the RFCOMM message, which conflicts with the 4/8/12/... numbering used by aa-proxy-rs and the dongle. See discrepancies. | `aasdk/protobuf/aap_protobuf/service/wifiprojection/message/WifiSecurityMode.proto` L7-L18 |
| `AccessPointType` | STATIC = 0, DYNAMIC = 1 | Access point role field carried in WifiInfoResponse (RFCOMM) and WifiCredentialsResponse (AAP). All four references agree on these two values. DYNAMIC is used by aa-proxy-rs and the dongle; STATIC by openauto. | `aasdk/protobuf/aap_protobuf/service/wifiprojection/message/AccessPointType.proto` L7-L10 |
| `AccessPointType (RFCOMM copy)` | STATIC = 0, DYNAMIC = 1 | Same enum embedded in the standalone RFCOMM WifiInfoResponse.proto used by aa-proxy-rs / dongle. | `aa-proxy-rs/src/protos/WifiInfoResponse.proto` L4-L7 |
| `Values actually sent by aa-proxy-rs in WifiInfoResponse` | security_mode = WPA2_PERSONAL (8), access_point_type = DYNAMIC (1) | The concrete enum values a working head unit puts on the wire. | `aa-proxy-rs/src/bluetooth.rs` L6231-L6237 |
| `Values actually sent by openauto in WifiInfoResponse` | security_mode = WifiSecurityMode::WPA2_ENTERPRISE (wire value 8), access_point_type = STATIC (0) | openauto sends the wifiprojection-enum symbol WPA2_ENTERPRISE, whose numeric value is 8 — i.e. the same wire byte that the AAW enum calls WPA2_PERSONAL. The in-file TODO acknowledges the enum mismatch. Strong evidence that the correct wire value for WPA2-PSK over RFCOMM is 8. | `openauto/src/btservice/AndroidBluetoothServer.cpp` L165-L176 |
| `AAW Status enum` | STATUS_UNSOLICITED_MESSAGE=1, STATUS_SUCCESS=0, STATUS_NO_COMPATIBLE_VERSION=-1, STATUS_WIFI_INACCESSIBLE_CHANNEL=-2, STATUS_WIFI_INCORRECT_CREDENTIALS=-3, STATUS_PROJECTION_ALREADY_STARTED=-4, STATUS_WIFI_DISABLED=-5, STATUS_WIFI_NOT_YET_STARTED=-6, STATUS_INVALID_HOST=-7, STATUS_NO_SUPPORTED_WIFI_CHANNELS=-8, STATUS_INSTRUCT_USER_TO_CHECK_THE_PHONE=-9, STATUS_PHONE_WIFI_DISABLED=-10, STATUS_WIFI_NETWORK_UNAVAILABLE=-11 | Status enum used by WifiStartResponse.status and WifiConnectionStatus.status over RFCOMM. 0 = success; negatives are failures. | `aasdk/protobuf/aap_protobuf/aaw/Status.proto` L5-L19 |
| `WifiConnectStatus.status field number` | 1 | Field number of the status varint inside WifiConnectStatus/WifiConnectionStatus, used by aa-proxy-rs to synthesize a success frame [0x08, 0x00]. | `aa-proxy-rs/src/bluetooth.rs` L911-L914 |
| `WifiStartResponse.status field number` | 3 | Field number of the status varint inside WifiStartResponse (fields 1/2 are ip_address/port). Emitting a generic [0x08,0x00] here would be an invalid string field. | `aa-proxy-rs/src/bluetooth.rs` L916-L920 |
| `WifiConnectStatus success/failure payload bytes` | success = [0x08, 0x00]; failure (=-i64::MAX) = [0x08, 0xFD, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01] | Observed raw payloads for WifiConnectStatus. A non-zero second byte means the phone failed to join the AP. | `aa-proxy-rs/src/bluetooth.rs` L4738-L4747 |
| `WifiPingRequest.timestamp field number` | 1 (varint int64) | Field layout of the aa-proxy-rs synthetic keepalive (message id 8). | `aa-proxy-rs/src/bluetooth.rs` L930-L936 |
| `WifiProjectionMessageId (AAP channel, not RFCOMM)` | WIFI_MESSAGE_CREDENTIALS_REQUEST = 32769 (0x8001), WIFI_MESSAGE_CREDENTIALS_RESPONSE = 32770 (0x8002) | Message ids for the *in-band AAP* WifiProjection service channel, which is a separate later mechanism from the RFCOMM handshake. Same numbers appear as 0x8001/0x8002 in the legacy aasdk_proto WifiChannelMessage enum. | `aasdk/protobuf/aap_protobuf/service/wifiprojection/WifiProjectionMessageId.proto` L7-L10 |
| `WifiChannelMessage (legacy aasdk_proto)` | NONE = 0x0000, CREDENTIALS_REQUEST = 0x8001, CREDENTIALS_RESPONSE = 0x8002 | Legacy aasdk_proto spelling of the same AAP WifiProjection channel message ids, in hex. | `aasdk/aasdk_proto/WifiChannelMessageIdsEnum.proto` L23-L31 |
| `UserSwitchStatus RFCOMM-related errors` | ERROR_NO_RFCOMM_CONNECTION = -1, ERROR_INCOMPATIBLE_PHONE_PROTOCOL_VERSION = -4, ERROR_PHONE_UNABLE_TO_CONNECT_WIFI = -5 | AAP-level status codes that reference the wireless bring-up failures, showing the RFCOMM link is a first-class dependency of the session. | `aa-proxy-rs/src/protos/protos.proto` L1483-L1492 |
| `SDP query constants used to discover the HU's AA Wireless RFCOMM channel` | SDP_PSM = 0x0001, ServiceSearchAttributeRequest PDU = 0x06, response PDU = 0x07, error PDU = 0x01, MaximumAttributeByteCount = 0x03f0, AttributeIDList range = 0x0000ffff | Exact SDP-over-L2CAP query a phone issues for the AA Wireless UUID. Asking only for ProtocolDescriptorList with 0xffff max bytes made a real head unit answer with an SDP ErrorResponse — the phone-like 0x03f0 / full-range query works. | `aa-proxy-rs/src/bluetooth.rs` L54-L62 |
| `SDP data element type bytes used to build the request` | 0x35 = DataElementSequence(u8 len), 0x1c = UUID128, 0x0a = uint32 | Data-element tags needed to hand-build the ServiceSearchAttributeRequest for the 128-bit AA Wireless UUID. | `aa-proxy-rs/src/bluetooth.rs` L64-L85 |
| `SDP PDU header layout` | [u8 pdu_id][u16 transaction_id BE][u16 param_len BE][params] | SDP framing over L2CAP PSM 0x0001 as built/parsed by aa-proxy-rs. | `aa-proxy-rs/src/bluetooth.rs` L117-L128 |
| `SDP RFCOMM protocol descriptor encoding` | UUID16 0x0003 encoded as bytes 19 00 03, followed by the channel number | How to locate the RFCOMM channel inside the head unit's ProtocolDescriptorList SDP attribute. | `aa-proxy-rs/src/bluetooth.rs` L191-L199 |
| `openauto SDP ProtocolDescriptorList` | [L2CAP], [RFCOMM, portNumber] | The SDP record the head unit publishes: L2CAP then RFCOMM with the dynamically-assigned server channel from QBluetoothServer::serverPort(). | `openauto/src/btservice/AndroidBluetoothService.cpp` L46-L54 |
| `BLE advertisement contents (dongle mode)` | Type="peripheral", ServiceUUIDs=[4de17a00-52cb-11e6-bdf4-0800200c9a66], LocalName=adapter alias | In DONGLE_MODE the dongle advertises the AA Wireless UUID over BLE so a head unit can discover it. Only aawgd does this. | `WirelessAndroidAutoDongle/aa_wireless_dongle/package/aawg/src/bluetoothHandler.cpp` L152-L162 |
| `Bluetooth adapter alias prefixes (dongle)` | "WirelessAADongle-" (normal) / "AndroidAuto-Dongle-" (dongle mode) | Adapter names used so head units/phones recognise the device. aa-proxy-rs matches names beginning with "AndroidAuto-" in dongle mode. | `WirelessAndroidAutoDongle/aa_wireless_dongle/package/aawg/src/bluetoothHandler.cpp` L8-L9 |
| `Connection strategies (dongle)` | DONGLE_MODE = 0, PHONE_FIRST = 1 (default), USB_FIRST = 2 | Ordering variants for the wireless bring-up relative to the USB accessory side. | `WirelessAndroidAutoDongle/aa_wireless_dongle/package/aawg/src/common.h` L20-L24 |
| `Head unit AP security configuration (dongle hostapd)` | wpa=2, wpa_key_mgmt=WPA-PSK, rsn_pairwise=CCMP, hw_mode=g, channel=6, ssid=AAWirelessDongle | The actual AP the head unit brings up — WPA2-PSK/CCMP, which is what the WifiInfoResponse security_mode value must describe. | `WirelessAndroidAutoDongle/aa_wireless_dongle/board/common/rootfs_overlay/etc/hostapd.conf.in` L1-L16 |
| `aa-proxy-rs default SSID / passphrase` | "aa-proxy" / "aa-proxy" | Defaults for the head unit AP credentials advertised in WifiInfoResponse. | `aa-proxy-rs/src/config.rs` L17 |
| `STAGES (handshake stage count)` | 5 | aa-proxy-rs numbers the RFCOMM handshake as 5 stages: send WifiStartRequest, read WifiInfoRequest, send WifiInfoResponse, read WifiStartResponse, read WifiConnectStatus. | `aa-proxy-rs/src/bluetooth.rs` L44 |
| `WifiVersionRequest field numbers (reverse-engineered)` | 1=major_version (varint), 2=minor_version (varint), 3=supported_wifi_channels (repeated int32, packed or unpacked), 4=HeadUnitInfo submessage OR a channel/frequency varint, 5=WifiProjectionProtocolInfo submessage OR HeadUnitInfo (revision-dependent) | Field layout aa-proxy-rs parses out of real WifiVersionRequest frames. Two revisions exist in the wild: decompiled Gearhead puts WifiProjectionProtocolInfo at field 5, while MBUX HCI captures put HeadUnitInfo at 5 and a channel/frequency varint (e.g. 5240/5765) at 4. | `aa-proxy-rs/src/bluetooth.rs` L1438-L1493 |
| `WifiVersionRequest.HeadUnitInfo field numbers` | 1=car_make, 2=car_model, 3=car_year, 4=vehicle_id, 5=head_unit_make, 6=head_unit_model, 7=head_unit_software_build, 8=head_unit_software_version (all length-delimited strings) | Sub-message the head unit sends identifying itself; parsed by aa-proxy-rs from live traffic. | `aa-proxy-rs/src/bluetooth.rs` L1533-L1543 |
| `WifiProjectionProtocolInfo field numbers` | 1=ip_address (string), 2=port (varint) | Optional sub-message inside WifiVersionRequest carrying the AAP TCP endpoint; some head units omit WifiStartRequest entirely and only supply the endpoint here. | `aa-proxy-rs/src/bluetooth.rs` L1569-L1587 |
| `WifiVersionResponse field numbers (reverse-engineered)` | 1=major_version (varint), 2=minor_version (varint), 3=device_serial (string), 4=status (varint, signed), 5=selected_wifi_channel_type (varint), 6=WifiDeviceInfo submessage | Field layout aa-proxy-rs parses out of real phone-side WifiVersionResponse frames. Compare with aasdk's aaw/WifiVersionResponse.proto which names them unknown_value_a..d. | `aa-proxy-rs/src/bluetooth.rs` L1619-L1639 |
| `WifiVersionResponse.WifiDeviceInfo field numbers` | 1=device_id (string), 2=connectivity_lifetime_id (string) | Phone-identifying sub-message inside WifiVersionResponse. | `aa-proxy-rs/src/bluetooth.rs` L1677-L1681 |
| `WifiSetupInfo (id 11) field numbers` | 1=major_version (varint), 2=minor_version (varint) | Field layout of the message id 11 frame observed from newer head units. | `aa-proxy-rs/src/bluetooth.rs` L1745-L1747 |
| `aa-proxy-rs protocol version override target default` | major = 5, minor = ... (protocol_version_override_enabled = false by default) | aa-proxy-rs can rewrite the major/minor version fields in WifiVersionRequest / WifiVersionResponse / WifiSetupInfo before forwarding; default target major is 5 and the override is off by default. Relevant because head units below the phone's minimum version get rejected. | `aa-proxy-rs/src/config.rs` L1024-L1026 |
| `aa-proxy-rs bt_wireless_proxy_listen_port default` | 5288 | Default listen port for the bt-wireless MITM proxy mode, matching TCP_SERVER_PORT. | `aa-proxy-rs/src/config.rs` L920 |

### 3.3 Message definitions


#### `WifiStartRequest (RFCOMM id 1) — aasdk canonical`

Source: `aasdk/protobuf/aap_protobuf/aaw/WifiStartRequest.proto`

```proto
syntax="proto2";

package aap_protobuf.aaw;

message WifiStartRequest {
  required string ip_address = 1;
  required uint32 port = 2;
}
```

#### `WifiStartRequest (RFCOMM id 1) — aa-proxy-rs / dongle variant (int32 port)`

Source: `aa-proxy-rs/src/protos/WifiStartRequest.proto`

```proto
syntax = "proto2";
option optimize_for = LITE_RUNTIME;

message WifiStartRequest {
    required string ip_address = 1;
    required int32 port = 2;
}
```

#### `WifiStartRequest (RFCOMM id 1) — WirelessAndroidAutoDongle copy (byte-identical to aa-proxy-rs)`

Source: `WirelessAndroidAutoDongle/aa_wireless_dongle/package/aawg/src/proto/WifiStartRequest.proto`

```proto
syntax = "proto2";
option optimize_for = LITE_RUNTIME;

message WifiStartRequest {
    required string ip_address = 1;
    required int32 port = 2;
}
```

#### `WifiInfoRequest (RFCOMM id 2) — aasdk canonical, empty body`

Source: `aasdk/protobuf/aap_protobuf/aaw/WifiInfoRequest.proto`

```proto
syntax="proto2";

package aap_protobuf.aaw;

message WifiInfoRequest {

}
```

#### `WifiInfoResponse (RFCOMM id 3) — aasdk canonical (field 2 named 'password', ap_type optional, imports the wifiprojection enums)`

Source: `aasdk/protobuf/aap_protobuf/aaw/WifiInfoResponse.proto`

```proto
syntax="proto2";

import "aap_protobuf/service/wifiprojection/message/AccessPointType.proto";
import "aap_protobuf/service/wifiprojection/message/WifiSecurityMode.proto";

package aap_protobuf.aaw;

message WifiInfoResponse {
  required string ssid = 1;
  required string password = 2;
  required string bssid = 3;
  required service.wifiprojection.message.WifiSecurityMode security_mode = 4;
  optional service.wifiprojection.message.AccessPointType access_point_type = 5;
}
```

#### `WifiInfoResponse (RFCOMM id 3) — aa-proxy-rs / dongle variant (field 2 named 'key', ap_type required, own enums with 4/8/12/20/24/28 numbering)`

Source: `aa-proxy-rs/src/protos/WifiInfoResponse.proto`

```proto
syntax = "proto2";
option optimize_for = LITE_RUNTIME;

enum AccessPointType {
    STATIC = 0;
    DYNAMIC = 1;
}

enum SecurityMode {
    UNKNOWN_SECURITY_MODE = 0;
    OPEN = 1;
    WEP_64 = 2;
    WEP_128 = 3;
    WPA_PERSONAL = 4;
    WPA2_PERSONAL = 8;
    WPA_WPA2_PERSONAL = 12;
    WPA_ENTERPRISE = 20;
    WPA2_ENTERPRISE = 24;
    WPA_WPA2_ENTERPRISE = 28;
}

message WifiInfoResponse {
    required string ssid = 1;
    required string key = 2;
    required string bssid = 3;
    required SecurityMode security_mode = 4;
    required AccessPointType access_point_type = 5;
}
```

#### `WifiVersionRequest (RFCOMM id 4) — aasdk canonical (declared empty; real traffic carries fields, see constants)`

Source: `aasdk/protobuf/aap_protobuf/aaw/WifiVersionRequest.proto`

```proto
syntax="proto2";

package aap_protobuf.aaw;

message WifiVersionRequest {

}
```

#### `WifiVersionResponse (RFCOMM id 5) — aasdk canonical (fields not yet named)`

Source: `aasdk/protobuf/aap_protobuf/aaw/WifiVersionResponse.proto`

```proto
syntax="proto2";

package aap_protobuf.aaw;

message WifiVersionResponse {
  required uint32 unknown_value_a = 1;
  required uint32 unknown_value_b = 2;
  optional string unknown_value_c = 3;
  required uint32 unknown_value_d = 4;
}
```

#### `WifiConnectionStatus (RFCOMM id 6) — aasdk canonical`

Source: `aasdk/protobuf/aap_protobuf/aaw/WifiConnectionStatus.proto`

```proto
syntax="proto2";

import "aap_protobuf/aaw/Status.proto";

package aap_protobuf.aaw;

message WifiConnectionStatus {
  required Status status = 1;
  optional string error_message = 2;
}
```

#### `WifiStartResponse (RFCOMM id 7) — aasdk canonical`

Source: `aasdk/protobuf/aap_protobuf/aaw/WifiStartResponse.proto`

```proto
syntax="proto2";

import "aap_protobuf/aaw/Status.proto";

package aap_protobuf.aaw;

message WifiStartResponse {
  optional string ip_address = 1;
  optional uint32 port = 2;
  required Status status = 3;
}
```

#### `Status (shared by WifiStartResponse and WifiConnectionStatus)`

Source: `aasdk/protobuf/aap_protobuf/aaw/Status.proto`

```proto
syntax="proto2";

package aap_protobuf.aaw;

enum Status {
  STATUS_UNSOLICITED_MESSAGE = 1;
  STATUS_SUCCESS = 0;
  STATUS_NO_COMPATIBLE_VERSION = -1;
  STATUS_WIFI_INACCESSIBLE_CHANNEL = -2;
  STATUS_WIFI_INCORRECT_CREDENTIALS = -3;
  STATUS_PROJECTION_ALREADY_STARTED = -4;
  STATUS_WIFI_DISABLED = -5;
  STATUS_WIFI_NOT_YET_STARTED = -6;
  STATUS_INVALID_HOST = -7;
  STATUS_NO_SUPPORTED_WIFI_CHANNELS = -8;
  STATUS_INSTRUCT_USER_TO_CHECK_THE_PHONE = -9;
  STATUS_PHONE_WIFI_DISABLED = -10;
  STATUS_WIFI_NETWORK_UNAVAILABLE = -11;
}
```

#### `MessageId (RFCOMM handshake ids)`

Source: `aasdk/protobuf/aap_protobuf/aaw/MessageId.proto`

```proto
syntax="proto2";

package aap_protobuf.aaw;

enum MessageId {
  WIFI_START_REQUEST = 1;
  WIFI_INFO_REQUEST = 2;
  WIFI_INFO_RESPONSE = 3;
  WIFI_VERSION_REQUEST = 4;
  WIFI_VERSION_RESPONSE = 5;
  WIFI_CONNECTION_STATUS = 6;
  WIFI_START_RESPONSE = 7;
}
```

#### `WifiSecurityMode (imported by aasdk's aaw WifiInfoResponse and by AAP WifiCredentialsResponse)`

Source: `aasdk/protobuf/aap_protobuf/service/wifiprojection/message/WifiSecurityMode.proto`

```proto
syntax="proto2";

option optimize_for=SPEED;

package aap_protobuf.service.wifiprojection.message;

enum WifiSecurityMode {
  UNKNOWN_SECURITY_MODE = 0;
  OPEN = 1;
  WEP_64 = 2;
  WEP_128 = 3;
  WPA_PERSONAL = 4;
  WPA2_PERSONAL = 5;
  WPA_WPA2_PERSONAL = 6;
  WPA_ENTERPRISE = 7;
  WPA2_ENTERPRISE = 8;
  WPA_WPA2_ENTERPRISE = 9;
}
```

#### `AccessPointType`

Source: `aasdk/protobuf/aap_protobuf/service/wifiprojection/message/AccessPointType.proto`

```proto
syntax="proto2";

option optimize_for=SPEED;

package aap_protobuf.service.wifiprojection.message;

enum AccessPointType {
  STATIC = 0;
  DYNAMIC = 1;
}
```

#### `WifiCredentialsRequest (AAP WifiProjection channel id 0x8001) — NOT the RFCOMM handshake`

Source: `aasdk/protobuf/aap_protobuf/service/wifiprojection/message/WifiCredentialsRequest.proto`

```proto
syntax="proto2";

option optimize_for=SPEED;

package aap_protobuf.service.wifiprojection.message;

message WifiCredentialsRequest {

}
```

#### `WifiCredentialsResponse (AAP WifiProjection channel id 0x8002) — NOT the RFCOMM handshake`

Source: `aasdk/protobuf/aap_protobuf/service/wifiprojection/message/WifiCredentialsResponse.proto`

```proto
syntax="proto2";

option optimize_for=SPEED;

import "aap_protobuf/service/wifiprojection/message/AccessPointType.proto";
import "aap_protobuf/service/wifiprojection/message/WifiSecurityMode.proto";

package aap_protobuf.service.wifiprojection.message;

message WifiCredentialsResponse {
  optional string car_wifi_password = 1;
  optional WifiSecurityMode car_wifi_security_mode = 2;
  optional string car_wifi_ssid = 3;
  repeated int32 supported_wifi_channels = 4;
  optional AccessPointType access_point_type = 5;
}
```

#### `WifiProjectionService (service-discovery descriptor, ChannelDescriptor field 14)`

Source: `aasdk/protobuf/aap_protobuf/service/wifiprojection/WifiProjectionService.proto`

```proto
syntax="proto2";

package aap_protobuf.service.wifiprojection;

message WifiProjectionService {
    optional string car_wifi_bssid = 1;
}
```

#### `WirelessTcpConfiguration (AAP ConnectionConfiguration field 2) — tuning for the wireless TCP session`

Source: `aasdk/protobuf/aap_protobuf/service/control/message/WirelessTcpConfiguration.proto`

```proto
syntax="proto2";

package aap_protobuf.service.control.message;

message WirelessTcpConfiguration {
  optional uint32 socket_receive_buffer_size_kb = 1;
  optional uint32 socket_send_buffer_size_kb = 2;
  optional uint32 socket_read_timeout_ms = 3;
}
```

#### `LEGACY aasdk_proto WifiInfoRequest — semantically equal to the modern WifiStartRequest (misnamed)`

Source: `aasdk/aasdk_proto/WifiInfoRequestMessage.proto`

```proto
syntax="proto2";

package aasdk.proto.messages;

message WifiInfoRequest
{
    required string ip_address = 1;
    optional uint32 port = 2;
}
```

#### `LEGACY aasdk_proto WifiInfoResponse — semantically equal to the modern WifiStartResponse (misnamed)`

Source: `aasdk/aasdk_proto/WifiInfoResponseMessage.proto`

```proto
syntax = "proto2";

package aasdk.proto.messages;

message WifiInfoResponse {
    optional string ip_address = 1;
    optional uint32 port = 2;
    required Status status = 3;

    enum Status {
        STATUS_UNSOLICITED_MESSAGE = 1;
        STATUS_SUCCESS = 0;
        STATUS_NO_COMPATIBLE_VERSION = -1;
        STATUS_WIFI_INACCESSIBLE_CHANNEL = -2;
        STATUS_WIFI_INCORRECT_CREDENTIALS = -3;
        STATUS_PROJECTION_ALREADY_STARTED = -4;
        STATUS_WIFI_DISABLED = -5;
        STATUS_WIFI_NOT_YET_STARTED = -6;
        STATUS_INVALID_HOST = -7;
        STATUS_NO_SUPPORTED_WIFI_CHANNELS = -8;
        STATUS_INSTRUCT_USER_TO_CHECK_THE_PHONE = -9;
        STATUS_PHONE_WIFI_DISABLED = -10;
    }
}
```

#### `LEGACY aasdk_proto WifiSecurityResponse — the credentials message, with the 4/8/12/20/24/28 SecurityMode numbering`

Source: `aasdk/aasdk_proto/WifiSecurityResponseMessage.proto`

```proto
syntax = "proto2";

package aasdk.proto.messages;

message WifiSecurityResponse {
    optional string key = 1;
    optional SecurityMode security_mode = 2;
    optional string ssid = 3;
    repeated int32 supported_wifi_channels = 4;
    optional AccessPointType access_point_type = 5;

    enum SecurityMode {
        UNKNOWN_SECURITY_MODE = 0;
        OPEN = 1;
        WEP_64 = 2;
        WEP_128 = 3;
        WPA_PERSONAL = 4;
        WPA2_PERSONAL = 8;
        WPA_WPA2_PERSONAL = 12;
        WPA_ENTERPRISE = 20;
        WPA2_ENTERPRISE = 24;
        WPA_WPA2_ENTERPRISE = 28;
    }

    enum AccessPointType {
        STATIC = 0;
        DYNAMIC = 1;
    }
}
```

#### `WifiCredentialsResponse (aa-proxy-rs consolidated protos.proto copy, enum symbols prefixed A_)`

Source: `aa-proxy-rs/src/protos/protos.proto`

```proto
message WifiProjectionService { optional string car_wifi_bssid = 1; }

message WifiCredentialsRequest {}

message WifiCredentialsResponse {
  optional string car_wifi_password = 1;
  optional WifiSecurityMode car_wifi_security_mode = 2;
  optional string car_wifi_ssid = 3;
  repeated int32 supported_wifi_channels = 4;
  optional A_AccessPointType access_point_type = 5;
}

// (L1634-L1650)
enum WifiSecurityMode {
  A_UNKNOWN_SECURITY_MODE = 0;
  A_OPEN = 1;
  A_WEP_64 = 2;
  A_WEP_128 = 3;
  A_WPA_PERSONAL = 4;
  A_WPA2_PERSONAL = 5;
  A_WPA_WPA2_PERSONAL = 6;
  A_WPA_ENTERPRISE = 7;
  A_WPA2_ENTERPRISE = 8;
  A_WPA_WPA2_ENTERPRISE = 9;
}

enum A_AccessPointType {
  A_STATIC = 0;
  A_DYNAMIC = 1;
}
```

### 3.4 Where the references disagree


**SecurityMode enum numbering — THE most dangerous conflict in this domain**

Two incompatible numberings exist for the same-named symbols. The standalone RFCOMM enum used by aa-proxy-rs (src/protos/WifiInfoResponse.proto L9-L20), WirelessAndroidAutoDongle (src/proto/WifiInfoResponse.proto L16-L27) and the legacy aasdk_proto/WifiSecurityResponseMessage.proto (L30-L41) uses a flag-like numbering: WPA_PERSONAL=4, WPA2_PERSONAL=8, WPA_WPA2_PERSONAL=12, WPA_ENTERPRISE=20, WPA2_ENTERPRISE=24, WPA_WPA2_ENTERPRISE=28. The AAP WifiProjection enum (aasdk/protobuf/aap_protobuf/service/wifiprojection/message/WifiSecurityMode.proto L7-L18, and aa-proxy-rs/src/protos/protos.proto L1634-L1645 with A_ prefixes) uses sequential numbering: WPA_PERSONAL=4, WPA2_PERSONAL=5, WPA_WPA2_PERSONAL=6, WPA_ENTERPRISE=7, WPA2_ENTERPRISE=8, WPA_WPA2_ENTERPRISE=9. Crucially, aasdk's own aap_protobuf/aaw/WifiInfoResponse.proto (L12) IMPORTS the sequential wifiprojection enum for the RFCOMM message — so aasdk-generated code sending 'WPA2_PERSONAL' over RFCOMM would put 5 on the wire, whereas every working head-unit implementation puts 8. openauto works around this by explicitly sending the symbol WPA2_ENTERPRISE (=8) in AndroidBluetoothServer.cpp L172-L174 with the comment '// TODO: AAP uses different values than WiFiProjection....'. CONCLUSION for clean-room: the RFCOMM WifiInfoResponse.security_mode wire value for WPA2-PSK is 8. Do not follow aasdk's import here; follow aa-proxy-rs/dongle. This overrides rule 3 for this one field, and I flag it explicitly rather than silently.

**WifiStartRequest.port type: uint32 vs int32**

aasdk/protobuf/aap_protobuf/aaw/WifiStartRequest.proto L7 declares 'required uint32 port = 2;'. aa-proxy-rs/src/protos/WifiStartRequest.proto L6 and WirelessAndroidAutoDongle/.../proto/WifiStartRequest.proto L6 declare 'required int32 port = 2;'. Wire-compatible for all realistic port values (both are plain varints, positive), but aa-proxy-rs explicitly guards against port > i32::MAX because of its generated setter (bluetooth.rs L1778-L1784). Prefer uint32 per aasdk; the encoding is identical for 1..65535.

**WifiInfoResponse field 2 name and field 5 optionality**

aasdk (aap_protobuf/aaw/WifiInfoResponse.proto L10, L13) names field 2 'password' and makes access_point_type OPTIONAL. aa-proxy-rs and the dongle name field 2 'key' and make access_point_type REQUIRED. Field numbers and wire types are identical, so this is a naming/optionality difference only. Prefer aasdk's names; emit field 5 always for safety since two of three implementations require it.

**Legacy aasdk_proto/ vs modern aasdk/protobuf/aap_protobuf/ — message names are SWAPPED**

The old aasdk_proto/ directory misnames the RFCOMM handshake messages. aasdk_proto/WifiInfoRequestMessage.proto L23-L27 defines WifiInfoRequest{ip_address=1, port=2} — that is structurally the MODERN WifiStartRequest, not WifiInfoRequest (which is empty in aap_protobuf/aaw/WifiInfoRequest.proto). aasdk_proto/WifiInfoResponseMessage.proto L23-L41 defines WifiInfoResponse{ip_address=1, port=2, status=3} — that is structurally the MODERN WifiStartResponse. The actual credentials message lives in aasdk_proto/WifiSecurityResponseMessage.proto as WifiSecurityResponse{key=1, security_mode=2, ssid=3, supported_wifi_channels=4, access_point_type=5} — which is structurally the AAP WifiCredentialsResponse, NOT the RFCOMM WifiInfoResponse (different field order: RFCOMM is ssid=1,key=2,bssid=3,security_mode=4,ap_type=5). Use aasdk/protobuf/aap_protobuf/aaw/ exclusively for the RFCOMM handshake.

**aasdk_proto/WifiSecurityRequestMessage.proto is not a proto file at all**

aasdk/aasdk_proto/WifiSecurityRequestMessage.proto L20 contains '#define BOOST_TEST_MODULE aasdk_ut#include <boost/test/unit_test.hpp>' — a C++ test stub accidentally committed under a .proto name. There is no WifiSecurityRequest message definition anywhere in the references. The task brief mentions 'WifiSecurityResponse / WifiCredentialsResponse'; the request side exists only as the empty WifiCredentialsRequest (aap_protobuf/service/wifiprojection/message/WifiCredentialsRequest.proto).

**Status enum: aasdk aaw has one extra value vs legacy**

aasdk/protobuf/aap_protobuf/aaw/Status.proto L18 adds STATUS_WIFI_NETWORK_UNAVAILABLE = -11, which is absent from the legacy aasdk_proto/WifiInfoResponseMessage.proto nested Status enum (which stops at -10). All other values are identical. Use the aaw/Status.proto list.

**Message ids 8, 9, 11 exist in the wild but not in aasdk**

aasdk/protobuf/aap_protobuf/aaw/MessageId.proto stops at WIFI_START_RESPONSE = 7, and WirelessAndroidAutoDongle's enum stops at 7 too. aa-proxy-rs (src/bluetooth.rs L406-L417 and L422-L433) additionally defines WifiPingRequest = 8, WifiPingResponse = 9, WifiSetupInfo = 11 — all observed against real head units. Id 10 is unassigned in every reference. Do not assume the id space ends at 7.

**WifiVersionRequest / WifiVersionResponse field layouts are undocumented in aasdk**

aasdk declares WifiVersionRequest as an EMPTY message (aap_protobuf/aaw/WifiVersionRequest.proto L5-L7) and WifiVersionResponse with placeholder names unknown_value_a..d (aap_protobuf/aaw/WifiVersionResponse.proto L5-L10). Real traffic carries much more, reverse-engineered in aa-proxy-rs: WifiVersionRequest has 1=major, 2=minor, 3=supported_wifi_channels, 4=HeadUnitInfo-or-channel-varint, 5=WifiProjectionProtocolInfo-or-HeadUnitInfo (bluetooth.rs L1438-L1493); WifiVersionResponse has 1=major, 2=minor, 3=device_serial, 4=status, 5=selected_wifi_channel_type, 6=WifiDeviceInfo (bluetooth.rs L1619-L1639). Mapping aasdk's unknown_value_a=1/b=2/c=3/d=4 onto aa-proxy-rs's names gives a=major, b=minor, c=device_serial, d=status — consistent. Also note aa-proxy-rs documents TWO revisions of WifiVersionRequest in the wild that disagree on whether field 4 or field 5 holds HeadUnitInfo (bluetooth.rs L1444-L1492); a parser must probe both.

**RFCOMM channel: fixed 8 vs SDP-discovered**

aa-proxy-rs (bluetooth.rs L4516) and aawgd (bluetoothHandler.cpp L129) pin channel 8. openauto uses whatever Qt's QBluetoothServer assigns and publishes it via SDP (AndroidBluetoothService.cpp L46-L54, BluetoothHandler.cpp L41-L56). aa-proxy-rs itself implements SDP discovery when acting as the client toward a real head unit (bluetooth.rs L341-L356), and its config allows an explicit override. A clean-room implementation should publish on 8 (for maximum phone compatibility) but must SDP-discover when connecting outward.

**TCP port is not a protocol constant**

aa-proxy-rs uses 5288 (config.rs L25), aawgd uses 5288 by default via AAWG_PROXY_PORT (common.cpp L65), openauto uses 5000 (App.cpp L34, AndroidBluetoothServer.cpp L82), and GalConstants declares WIFI_PORT = 30515 (aasdk/protobuf/aap_protobuf/channel/control/GalConstants.proto L6) which nothing here binds. The authoritative value is whatever the HU puts in WifiStartRequest.port (or WifiProjectionProtocolInfo.port); the phone always reads it in-band.

**AACS contains no wireless Bluetooth code**

Grepping AACS/ for wifistart|wifiinfo|WifiSecurity|WifiCredential|4de17a00|rfcomm produced only one hit, in AACS/doc/kernel_config. Its proto/ directory (29 files) has no Wifi* message and no WifiProjection channel. AACS implements the phone/server side of the AAP session only, not the RFCOMM bring-up. It contributes nothing to this domain.

**aasdk implements the AAP WifiProjection channel but NOT the RFCOMM handshake**

aasdk has src/Channel/WifiProjection/WifiProjectionService.cpp and include/aasdk/Channel/WifiProjection/*, plus protobuf/aap_protobuf/aaw/*.proto, but a grep of aasdk/src and aasdk/include for '4de17a00' returned nothing and there is no RFCOMM/BlueZ code. aasdk supplies the canonical .proto definitions for the handshake; the only executable references for the handshake itself are aa-proxy-rs/src/bluetooth.rs, WirelessAndroidAutoDongle/.../bluetoothProfiles.cpp, and openauto/src/btservice/AndroidBluetoothServer.cpp.

**AccessPointType: DYNAMIC vs STATIC disagreement in practice**

aa-proxy-rs sends DYNAMIC=1 (bluetooth.rs L6235) and the dongle sends DYNAMIC=1 (common.cpp L63); openauto sends STATIC=0 in both the RFCOMM WifiInfoResponse (AndroidBluetoothServer.cpp L174) and the AAP WifiCredentialsResponse (WifiProjectionService.cpp L80). The enum values themselves are agreed everywhere (STATIC=0, DYNAMIC=1); only the choice differs. Both apparently work; DYNAMIC has two independent votes.

**WifiConnectStatus name differs: aasdk 'WifiConnectionStatus' vs aa-proxy-rs/dongle 'WifiConnectStatus'**

aasdk names id 6 WIFI_CONNECTION_STATUS / message WifiConnectionStatus (aap_protobuf/aaw/MessageId.proto L11, WifiConnectionStatus.proto L7). aa-proxy-rs and the dongle call it WifiConnectStatus. Same id, same layout (status at field 1). Naming only.

**openauto sends WifiInfoResponse with a hard-coded id literal**

openauto/src/btservice/AndroidBluetoothServer.cpp L176 calls sendMessage(response, 3) using a bare 3 rather than aap_protobuf::aaw::MessageId::WIFI_INFO_RESPONSE, while every other send in that file uses the enum. Value is correct (3) but worth noting when cross-checking.

---

## 4. Control channel and service discovery

Channel 0 message ids, service discovery, focus and ping.

### 4.1 Sequence

```text
POST-TLS CONTROL CHANNEL SEQUENCE (channel 0 = ChannelId::CONTROL, first member of aasdk's ChannelId enum). Every payload is [uint16 message ID][protobuf or raw bytes]; MessageId::getSizeOf()==2 (aasdk/include/aasdk/Messenger/MessageId.hpp L34).

STEP 1 - HEAD UNIT sends MESSAGE_VERSION_REQUEST (1), channel 0, flags PLAIN|SPECIFIC. Payload after the ID is 4 raw bytes: uint16 BE major then uint16 BE minor. aasdk uses AASDK_MAJOR=1, AASDK_MINOR=6 (aasdk/include/aasdk/Version.hpp L22-23), which matches GalConstants PROTOCOL_MAJOR_VERSION=1 / PROTOCOL_MINOR_VERSION=6 (aasdk/protobuf/aap_protobuf/channel/control/GalConstants.proto L7-8). Source: aasdk/src/Channel/Control/ControlServiceChannel.cpp L37-51; openauto/src/autoapp/Service/AndroidAutoEntity.cpp L53-59 shows this is the very first thing start() does.

STEP 2 - PHONE replies MESSAGE_VERSION_RESPONSE (2), PLAIN. Payload is three uint16 BE words: major, minor, status. aasdk reads versionResponse[2] as a MessageStatus (ControlServiceChannel.cpp L238-251). STATUS_NO_COMPATIBLE_VERSION (-1) makes openauto abort the session (AndroidAutoEntity.cpp L115-117). AACS's phone-side implementation answers 1.5 with a trailing 0 (AACS/AAServer/src/AaCommunicator.cpp L75-92).

STEP 3 - TLS. HEAD UNIT calls doHandshake() and sends MESSAGE_ENCAPSULATED_SSL (3), PLAIN, with the raw bytes pulled out of the SSL BIO (ControlServiceChannel.cpp L53-63). Each inbound MESSAGE_ENCAPSULATED_SSL is fed back into the BIO and, if the handshake is not yet complete, another MESSAGE_ENCAPSULATED_SSL is emitted; this ping-pongs until doHandshake() returns true (AndroidAutoEntity.cpp L138-152). Message ID 3 is dispatched straight to onHandshake with no protobuf parsing (ControlServiceChannel.cpp L201-203).

STEP 4 - HEAD UNIT sends MESSAGE_AUTH_COMPLETE (4), still PLAIN|SPECIFIC, payload = AuthResponse{status = STATUS_SUCCESS (0)} (ControlServiceChannel.cpp L65-75; AndroidAutoEntity.cpp L155-161). This is the last plaintext control message.

STEP 5 - PHONE sends MESSAGE_SERVICE_DISCOVERY_REQUEST (5), ENCRYPTED. AACS's phone side sends it immediately upon receiving AUTH_COMPLETE, with Encrypted|Bulk flags (AACS/AAServer/src/AaCommunicator.cpp L237-239 dispatch, L95-104 the send). Payload is ServiceDiscoveryRequest: icons, label_text, device_name, phone_info{instance_id, connectivity_lifetime_id}. The head unit only receives this; aasdk parses it and hands it to onServiceDiscoveryRequest (ControlServiceChannel.cpp L253-262).

STEP 6 - HEAD UNIT answers MESSAGE_SERVICE_DISCOVERY_RESPONSE (6), ENCRYPTED|SPECIFIC (ControlServiceChannel.cpp L77-88). The head unit builds it once and lets every registered service append its own Service entry via fillFeatures (AndroidAutoEntity.cpp L172-213). Contents openauto sets: driver_position, display_name, probe_for_support=false, connection_configuration.ping_configuration{tracked_ping_count=5, timeout_ms=3000, interval_ms=1000, high_latency_threshold_ms=200}, headunit_info{make, model, year, vehicle_id, head_unit_make, head_unit_model, head_unit_software_build, head_unit_software_version}, and the repeated `channels` list. Each entry is a Service{ required int32 id; plus exactly one of the 13 optional service-type sub-messages }. Service.id is written as the numeric ChannelId of that service (openauto .../VideoMediaSinkService.cpp L76 and the identical line in all 14 service classes), so id doubles as the channel number the phone will address. Fields make/model/year/vehicle_id/head_unit_* (2-5, 7-10) and can_play_native_media_during_vr (11) are marked deprecated in favour of headunit_info (17); field 12 is absent from the schema.

STEP 7 - PHONE opens each service channel: MESSAGE_CHANNEL_OPEN_REQUEST (7) sent ON THAT SERVICE'S CHANNEL (not channel 0), Encrypted|Specific, payload ChannelOpenRequest{priority (sint32), service_id (int32)} (AACS/AAServer/src/ChannelHandler.cpp L34-46 does exactly this and then blocks waiting for the response). HEAD UNIT replies on the same channel with MESSAGE_CHANNEL_OPEN_RESPONSE (8), ENCRYPTED with the CONTROL message-type flag (0x04) rather than SPECIFIC, payload ChannelOpenResponse{status = shared.MessageStatus} (aasdk/src/Channel/MediaSink/Video/VideoMediaSinkService.cpp L50-60; the same pattern exists in every service channel class). Every service channel therefore must handle message ID 7 in its own dispatcher (VideoMediaSinkService.cpp L109-110).

STEP 8 - STEADY STATE on channel 0, all encrypted (except aasdk's ping, see discrepancies):
  * Ping: HEAD UNIT -> phone MESSAGE_PING_REQUEST (11) with PingRequest{timestamp (int64, microseconds in openauto), bug_report, data}; PHONE -> head unit MESSAGE_PING_RESPONSE (12) echoing the timestamp. openauto drives this on a timer via schedulePing()/sendPing() and treats a missed pong as fatal (AndroidAutoEntity.cpp L343-372). The head unit also handles an inbound PING_REQUEST and can answer with sendPingResponse (ControlServiceChannel.cpp L153-163, L331-340).
  * Audio focus: PHONE -> head unit MESSAGE_AUDIO_FOCUS_REQUEST (18), AudioFocusRequest{audio_focus_type: AUDIO_FOCUS_GAIN|GAIN_TRANSIENT|GAIN_TRANSIENT_MAY_DUCK|RELEASE}. HEAD UNIT -> phone MESSAGE_AUDIO_FOCUS_NOTIFICATION (19), AudioFocusNotification{focus_state: AudioFocusStateType, unsolicited}. The head unit may send 19 unsolicited (unsolicited=true) to yank focus back when native audio starts. openauto's policy: RELEASE -> AUDIO_FOCUS_STATE_LOSS, anything else -> AUDIO_FOCUS_STATE_GAIN (AndroidAutoEntity.cpp L216-252).
  * Navigation focus: PHONE -> head unit MESSAGE_NAV_FOCUS_REQUEST (13) carrying NavFocusRequestNotification{focus_type}; HEAD UNIT -> phone MESSAGE_NAV_FOCUS_NOTIFICATION (14) carrying NavFocusNotification{focus_type: NAV_FOCUS_NATIVE=1 | NAV_FOCUS_PROJECTED=2}. openauto always grants NAV_FOCUS_PROJECTED (AndroidAutoEntity.cpp L274-293).
  * Voice session: PHONE -> head unit MESSAGE_VOICE_SESSION_NOTIFICATION (17), VoiceSessionNotification{status: VOICE_SESSION_START=1 | VOICE_SESSION_END=2}. aasdk parses it and hands it to onVoiceSessionRequest; there is no response message (aasdk's sendVoiceSessionFocusResponse is an empty stub, ControlServiceChannel.cpp L146-151).
  * Phone/car info: PHONE -> head unit MESSAGE_BATTERY_STATUS_NOTIFICATION (23) BatteryStatusNotification{battery_level, time_remaining_s, critical_battery}; MESSAGE_CALL_AVAILABILITY_STATUS (24) CallAvailabilityStatus{call_available}. Device switching uses MESSAGE_CAR_CONNECTED_DEVICES_REQUEST (20, empty), MESSAGE_CAR_CONNECTED_DEVICES_RESPONSE (21, CarConnectedDevices{connected_devices[], unsolicited, final_list=true}), MESSAGE_USER_SWITCH_REQUEST (22, UserSwitchRequest{selected_device}) and MESSAGE_USER_SWITCH_RESPONSE (25, UserSwitchResponse{status: UserSwitchStatus, selected_device}). Head unit identity travels the other way inside ServiceDiscoveryResponse.headunit_info; phone identity inside ServiceDiscoveryRequest.phone_info.
  * Late service additions: MESSAGE_SERVICE_DISCOVERY_UPDATE (26) with ServiceDiscoveryUpdate{service}.

STEP 9 - SHUTDOWN: either side sends MESSAGE_BYEBYE_REQUEST (15), ENCRYPTED, ByeByeRequest{reason: USER_SELECTION=1, DEVICE_SWITCH=2, NOT_SUPPORTED=3, NOT_CURRENTLY_SUPPORTED=4, PROBE_SUPPORTED=5}; the peer answers MESSAGE_BYEBYE_RESPONSE (16) with an empty ByeByeResponse and then tears down. openauto quits after the response is flushed on either path (AndroidAutoEntity.cpp L255-272; ControlServiceChannel.cpp L105-130).

VIDEO FOCUS IS NOT ON THE CONTROL CHANNEL. It is exchanged on the video media sink channel: PHONE -> head unit MEDIA_MESSAGE_VIDEO_FOCUS_REQUEST = 32775 (0x8007) with VideoFocusRequestNotification{disp_channel_id (deprecated), mode: VideoFocusMode, reason: VideoFocusReason}; HEAD UNIT -> phone MEDIA_MESSAGE_VIDEO_FOCUS_NOTIFICATION = 32776 (0x8008) with VideoFocusNotification{focus: VideoFocusMode, unsolicited}, sent ENCRYPTED|SPECIFIC (aasdk/protobuf/aap_protobuf/service/media/sink/MediaMessageId.proto L16-17; aasdk/src/Channel/MediaSink/Video/VideoMediaSinkService.cpp L90-100 and L127-128). AACS confirms 0x8008 as VideoFocusIndication (AACS/include/enums.h L46). Audio focus, by contrast, IS on the control channel (18/19).

MINIMUM HEAD-UNIT INBOUND DISPATCH SET (what aasdk actually handles on channel 0): 2, 3, 5, 11, 12, 13, 15, 16, 17, 18, 23 (ControlServiceChannel.cpp L197-235). IDs 20/21/22/24/25/26 are defined in the enum but fall through to the "not handled" branch in aasdk.
```

### 4.2 Constants

| Constant | Value | Meaning | Source |
|---|---|---|---|
| `ControlMessageType (full enum block)` | see snippet | Canonical control-channel message ID enum used as the 2-byte big-endian message ID prefix on channel 0. aasdk's C++ control channel switches on exactly these values. | `aasdk/protobuf/aap_protobuf/service/control/ControlMessageType.proto` L5-L34 |
| `MESSAGE_VERSION_REQUEST` | 1 | Head unit -> phone. Payload after the 2-byte ID is 4 raw bytes: uint16 BE major, uint16 BE minor. Sent PLAIN. | `aasdk/protobuf/aap_protobuf/service/control/ControlMessageType.proto` L7 |
| `MESSAGE_VERSION_RESPONSE` | 2 | Phone -> head unit. Payload is 3 x uint16 BE: major, minor, status (MessageStatus). | `aasdk/protobuf/aap_protobuf/service/control/ControlMessageType.proto` L8 |
| `MESSAGE_ENCAPSULATED_SSL` | 3 | Both directions, PLAIN. Payload is raw TLS handshake bytes read from/written to the SSL BIO. | `aasdk/protobuf/aap_protobuf/service/control/ControlMessageType.proto` L9 |
| `MESSAGE_AUTH_COMPLETE` | 4 | Head unit -> phone once the TLS handshake finishes. Payload is an AuthResponse protobuf. Sent PLAIN. | `aasdk/protobuf/aap_protobuf/service/control/ControlMessageType.proto` L10 |
| `MESSAGE_SERVICE_DISCOVERY_REQUEST` | 5 | Phone -> head unit, first encrypted message. Payload is ServiceDiscoveryRequest. | `aasdk/protobuf/aap_protobuf/service/control/ControlMessageType.proto` L11 |
| `MESSAGE_SERVICE_DISCOVERY_RESPONSE` | 6 | Head unit -> phone. Payload is ServiceDiscoveryResponse. Sent ENCRYPTED. | `aasdk/protobuf/aap_protobuf/service/control/ControlMessageType.proto` L12 |
| `MESSAGE_CHANNEL_OPEN_REQUEST` | 7 | Phone -> head unit, sent on the target service's own channel (not channel 0). Payload is ChannelOpenRequest. | `aasdk/protobuf/aap_protobuf/service/control/ControlMessageType.proto` L13 |
| `MESSAGE_CHANNEL_OPEN_RESPONSE` | 8 | Head unit -> phone on the same service channel. Payload is ChannelOpenResponse. aasdk sends it ENCRYPTED with the CONTROL message-type flag set. | `aasdk/protobuf/aap_protobuf/service/control/ControlMessageType.proto` L14 |
| `MESSAGE_CHANNEL_CLOSE_NOTIFICATION` | 9 | Channel teardown notification; payload ChannelCloseNotification (empty message). | `aasdk/protobuf/aap_protobuf/service/control/ControlMessageType.proto` L15 |
| `MESSAGE_PING_REQUEST` | 11 | Payload PingRequest. Note ID 10 is unused/absent from the enum. | `aasdk/protobuf/aap_protobuf/service/control/ControlMessageType.proto` L16 |
| `MESSAGE_PING_RESPONSE` | 12 | Payload PingResponse, echoing the request timestamp. | `aasdk/protobuf/aap_protobuf/service/control/ControlMessageType.proto` L17 |
| `MESSAGE_NAV_FOCUS_REQUEST` | 13 | Phone -> head unit. Payload is NavFocusRequestNotification (aasdk parses this type for ID 13). | `aasdk/protobuf/aap_protobuf/service/control/ControlMessageType.proto` L18 |
| `MESSAGE_NAV_FOCUS_NOTIFICATION` | 14 | Head unit -> phone grant/notification. Payload is NavFocusNotification. Sent ENCRYPTED. | `aasdk/protobuf/aap_protobuf/service/control/ControlMessageType.proto` L19 |
| `MESSAGE_BYEBYE_REQUEST` | 15 | Shutdown request, either direction. Payload ByeByeRequest{reason}. Sent ENCRYPTED. | `aasdk/protobuf/aap_protobuf/service/control/ControlMessageType.proto` L20 |
| `MESSAGE_BYEBYE_RESPONSE` | 16 | Shutdown acknowledgement. Payload ByeByeResponse (empty message). Sent ENCRYPTED. | `aasdk/protobuf/aap_protobuf/service/control/ControlMessageType.proto` L21 |
| `MESSAGE_VOICE_SESSION_NOTIFICATION` | 17 | Phone -> head unit voice session start/end. Payload VoiceSessionNotification. | `aasdk/protobuf/aap_protobuf/service/control/ControlMessageType.proto` L22 |
| `MESSAGE_AUDIO_FOCUS_REQUEST` | 18 | Phone -> head unit. aasdk parses payload as AudioFocusRequest{audio_focus_type}. | `aasdk/protobuf/aap_protobuf/service/control/ControlMessageType.proto` L23 |
| `MESSAGE_AUDIO_FOCUS_NOTIFICATION` | 19 | Head unit -> phone audio focus grant/state. Payload AudioFocusNotification. Sent ENCRYPTED. | `aasdk/protobuf/aap_protobuf/service/control/ControlMessageType.proto` L24 |
| `MESSAGE_CAR_CONNECTED_DEVICES_REQUEST` | 20 | Payload CarConnectedDevicesRequest (empty message). | `aasdk/protobuf/aap_protobuf/service/control/ControlMessageType.proto` L25 |
| `MESSAGE_CAR_CONNECTED_DEVICES_RESPONSE` | 21 | Payload is the CarConnectedDevices message (there is no message literally named CarConnectedDevicesResponse). | `aasdk/protobuf/aap_protobuf/service/control/ControlMessageType.proto` L26 |
| `MESSAGE_USER_SWITCH_REQUEST` | 22 | Payload UserSwitchRequest{selected_device}. | `aasdk/protobuf/aap_protobuf/service/control/ControlMessageType.proto` L27 |
| `MESSAGE_BATTERY_STATUS_NOTIFICATION` | 23 | Phone -> head unit phone battery info. Payload BatteryStatusNotification. | `aasdk/protobuf/aap_protobuf/service/control/ControlMessageType.proto` L28 |
| `MESSAGE_CALL_AVAILABILITY_STATUS` | 24 | Payload CallAvailabilityStatus{call_available}. | `aasdk/protobuf/aap_protobuf/service/control/ControlMessageType.proto` L29 |
| `MESSAGE_USER_SWITCH_RESPONSE` | 25 | Payload UserSwitchResponse{status, selected_device}. | `aasdk/protobuf/aap_protobuf/service/control/ControlMessageType.proto` L30 |
| `MESSAGE_SERVICE_DISCOVERY_UPDATE` | 26 | Incremental single-service update after initial discovery. Payload ServiceDiscoveryUpdate{service}. | `aasdk/protobuf/aap_protobuf/service/control/ControlMessageType.proto` L31 |
| `MESSAGE_UNEXPECTED_MESSAGE` | 255 | Error indication for an unexpected message. | `aasdk/protobuf/aap_protobuf/service/control/ControlMessageType.proto` L32 |
| `MESSAGE_FRAMING_ERROR` | 65535 | Error indication for a framing error. | `aasdk/protobuf/aap_protobuf/service/control/ControlMessageType.proto` L33 |
| `AUDIO_FOCUS_GAIN` | 1 | AudioFocusRequestType: phone asks for permanent audio focus. | `aasdk/protobuf/aap_protobuf/service/control/message/AudioFocusRequestType.proto` L5-L11 |
| `AUDIO_FOCUS_GAIN_TRANSIENT` | 2 | AudioFocusRequestType: transient focus request. | `aasdk/protobuf/aap_protobuf/service/control/message/AudioFocusRequestType.proto` L8 |
| `AUDIO_FOCUS_GAIN_TRANSIENT_MAY_DUCK` | 3 | AudioFocusRequestType: transient focus, other audio may duck rather than stop. | `aasdk/protobuf/aap_protobuf/service/control/message/AudioFocusRequestType.proto` L9 |
| `AUDIO_FOCUS_RELEASE` | 4 | AudioFocusRequestType: phone releases audio focus. openauto answers this with AUDIO_FOCUS_STATE_LOSS. | `aasdk/protobuf/aap_protobuf/service/control/message/AudioFocusRequestType.proto` L10 |
| `AudioFocusStateType (full enum block)` | 0..7 | State reported by the head unit in AudioFocusNotification (control message 19). | `aasdk/protobuf/aap_protobuf/service/control/message/AudioFocusStateType.proto` L5-L15 |
| `AUDIO_FOCUS_STATE_INVALID` | 0 | AudioFocusStateType invalid/unset. | `aasdk/protobuf/aap_protobuf/service/control/message/AudioFocusStateType.proto` L7 |
| `AUDIO_FOCUS_STATE_GAIN` | 1 | Head unit grants full audio focus; openauto returns this for any non-RELEASE request. | `aasdk/protobuf/aap_protobuf/service/control/message/AudioFocusStateType.proto` L8 |
| `AUDIO_FOCUS_STATE_GAIN_TRANSIENT` | 2 | Head unit grants transient audio focus. | `aasdk/protobuf/aap_protobuf/service/control/message/AudioFocusStateType.proto` L9 |
| `AUDIO_FOCUS_STATE_LOSS` | 3 | Head unit revokes audio focus (e.g. native radio started); openauto returns this for AUDIO_FOCUS_RELEASE. | `aasdk/protobuf/aap_protobuf/service/control/message/AudioFocusStateType.proto` L10 |
| `AUDIO_FOCUS_STATE_LOSS_TRANSIENT_CAN_DUCK` | 4 | Transient loss, phone may duck instead of stopping. | `aasdk/protobuf/aap_protobuf/service/control/message/AudioFocusStateType.proto` L11 |
| `AUDIO_FOCUS_STATE_LOSS_TRANSIENT` | 5 | Transient loss of audio focus. | `aasdk/protobuf/aap_protobuf/service/control/message/AudioFocusStateType.proto` L12 |
| `AUDIO_FOCUS_STATE_GAIN_MEDIA_ONLY` | 6 | Focus granted for media stream only (guidance suppressed). | `aasdk/protobuf/aap_protobuf/service/control/message/AudioFocusStateType.proto` L13 |
| `AUDIO_FOCUS_STATE_GAIN_TRANSIENT_GUIDANCE_ONLY` | 7 | Transient focus granted for guidance stream only. | `aasdk/protobuf/aap_protobuf/service/control/message/AudioFocusStateType.proto` L14 |
| `NAV_FOCUS_NATIVE` | 1 | NavFocusType: navigation focus held by the head unit's own native navigation. | `aasdk/protobuf/aap_protobuf/service/control/message/NavFocusType.proto` L5-L8 |
| `NAV_FOCUS_PROJECTED` | 2 | NavFocusType: navigation focus given to the projected (Android Auto) navigation. openauto always answers NavFocusRequestNotification with this. | `aasdk/protobuf/aap_protobuf/service/control/message/NavFocusType.proto` L7 |
| `VOICE_SESSION_START` | 1 | VoiceSessionStatus: assistant/voice session started. | `aasdk/protobuf/aap_protobuf/service/control/message/VoiceSessionStatus.proto` L5-L8 |
| `VOICE_SESSION_END` | 2 | VoiceSessionStatus: assistant/voice session ended. | `aasdk/protobuf/aap_protobuf/service/control/message/VoiceSessionStatus.proto` L7 |
| `ByeByeReason (full enum block)` | 1..5 | Shutdown reason carried in ByeByeRequest (control message 15). | `aasdk/protobuf/aap_protobuf/service/control/message/ByeByeReason.proto` L5-L12 |
| `USER_SELECTION` | 1 | ByeByeReason: user chose to end the session. | `aasdk/protobuf/aap_protobuf/service/control/message/ByeByeReason.proto` L7 |
| `DEVICE_SWITCH` | 2 | ByeByeReason: switching to another device. | `aasdk/protobuf/aap_protobuf/service/control/message/ByeByeReason.proto` L8 |
| `NOT_SUPPORTED` | 3 | ByeByeReason: projection not supported. | `aasdk/protobuf/aap_protobuf/service/control/message/ByeByeReason.proto` L9 |
| `NOT_CURRENTLY_SUPPORTED` | 4 | ByeByeReason: not supported at this moment. | `aasdk/protobuf/aap_protobuf/service/control/message/ByeByeReason.proto` L10 |
| `PROBE_SUPPORTED` | 5 | ByeByeReason: session was only a support probe (pairs with ServiceDiscoveryResponse.probe_for_support). | `aasdk/protobuf/aap_protobuf/service/control/message/ByeByeReason.proto` L11 |
| `DriverPosition (full enum block)` | 0..3 | ServiceDiscoveryResponse.driver_position field 6. | `aasdk/protobuf/aap_protobuf/service/control/message/DriverPosition.proto` L5-L10 |
| `UserSwitchStatus (full enum block)` | 0..-9 | Status in UserSwitchResponse (control message 25); all failures are negative. | `aasdk/protobuf/aap_protobuf/service/control/message/UserSwitchStatus.proto` L5-L16 |
| `MessageStatus (full enum block)` | 1..-255 | Shared status enum used by ChannelOpenResponse.status and by the third uint16 of VERSION_RESPONSE. STATUS_SUCCESS=0 is what a head unit sends in AuthResponse and ChannelOpenResponse; STATUS_NO_COMPATIBLE_VERSION=-1 aborts the session. | `aasdk/protobuf/aap_protobuf/shared/MessageStatus.proto` L5-L38 |
| `VideoFocusMode (full enum block)` | 1..4 | Video focus mode used by both VideoFocusRequestNotification.mode and VideoFocusNotification.focus. Video focus is NOT a control-channel message; it rides the video media sink channel. | `aasdk/protobuf/aap_protobuf/service/media/video/message/VideoFocusMode.proto` L5-L11 |
| `VIDEO_FOCUS_PROJECTED` | 1 | Projection (Android Auto) owns the display. | `aasdk/protobuf/aap_protobuf/service/media/video/message/VideoFocusMode.proto` L7 |
| `VIDEO_FOCUS_NATIVE` | 2 | Head unit's native UI owns the display. | `aasdk/protobuf/aap_protobuf/service/media/video/message/VideoFocusMode.proto` L8 |
| `VIDEO_FOCUS_NATIVE_TRANSIENT` | 3 | Native UI temporarily owns the display. | `aasdk/protobuf/aap_protobuf/service/media/video/message/VideoFocusMode.proto` L9 |
| `VIDEO_FOCUS_PROJECTED_NO_INPUT_FOCUS` | 4 | Projection is visible but does not hold input focus. | `aasdk/protobuf/aap_protobuf/service/media/video/message/VideoFocusMode.proto` L10 |
| `VideoFocusReason (full enum block)` | 0..2 | Reason field 3 of VideoFocusRequestNotification. | `aasdk/protobuf/aap_protobuf/service/media/video/message/VideoFocusReason.proto` L5-L10 |
| `MEDIA_MESSAGE_VIDEO_FOCUS_REQUEST` | 32775 | 0x8007. Phone -> head unit video focus request on the video media sink channel; payload VideoFocusRequestNotification. | `aasdk/protobuf/aap_protobuf/service/media/sink/MediaMessageId.proto` L16 |
| `MEDIA_MESSAGE_VIDEO_FOCUS_NOTIFICATION` | 32776 | 0x8008. Head unit -> phone video focus notification on the video media sink channel; payload VideoFocusNotification. aasdk sends it ENCRYPTED with MessageType::SPECIFIC. | `aasdk/protobuf/aap_protobuf/service/media/sink/MediaMessageId.proto` L17 |
| `MediaMessageId (full enum block, aasdk)` | 0..32779 | Media sink channel message IDs; included because video focus and UI-config messages live here rather than on the control channel. | `aasdk/protobuf/aap_protobuf/service/media/sink/MediaMessageId.proto` L5-L21 |
| `WIFI_PORT` | 30515 | GalConstants: TCP port for the wireless Android Auto projection socket. | `aasdk/protobuf/aap_protobuf/channel/control/GalConstants.proto` L5-L9 |
| `PROTOCOL_MAJOR_VERSION` | 1 | GalConstants: AAP major protocol version to send in VERSION_REQUEST. | `aasdk/protobuf/aap_protobuf/channel/control/GalConstants.proto` L7 |
| `PROTOCOL_MINOR_VERSION` | 6 | GalConstants: AAP minor protocol version to send in VERSION_REQUEST. | `aasdk/protobuf/aap_protobuf/channel/control/GalConstants.proto` L8 |
| `AASDK_MAJOR` | 1 | Value aasdk puts in bytes 0-1 (big endian) of the VERSION_REQUEST payload. | `aasdk/include/aasdk/Version.hpp` L22-L23 |
| `AASDK_MINOR` | 6 | Value aasdk puts in bytes 2-3 (big endian) of the VERSION_REQUEST payload. Matches GalConstants.PROTOCOL_MINOR_VERSION. | `aasdk/include/aasdk/Version.hpp` L23 |
| `SessionConfiguration (bitmask enum)` | 1,2,4,8 | Bit flags for ServiceDiscoveryResponse.session_configuration (field 13). | `aasdk/protobuf/aap_protobuf/channel/control/SessionConfiguration.proto` L5-L10 |
| `LocationCharacterization (bitmask enum)` | 1..256 | Bit flags for SensorSourceService.location_characterization (field 2) advertised in the ServiceDiscoveryResponse sensor service descriptor. | `aasdk/protobuf/aap_protobuf/channel/control/LocationCharacterization.proto` L5-L15 |
| `FragInfo (full enum block)` | 0..3 | Fragmentation state values defined alongside the control messages (used by the framing layer). | `aasdk/protobuf/aap_protobuf/service/control/message/FragInfo.proto` L5-L10 |
| `GalVerificationVendorExtensionMessageId (full enum block)` | 32769..32779 | Message IDs of the GAL verification vendor-extension channel (test harness), including its own audio/video focus injection messages. | `aasdk/protobuf/aap_protobuf/channel/control/GalVerificationVendorExtensionMessageId.proto` L5-L17 |
| `GoogleDiagnosticsVendorExtensionMessageId (full enum block)` | 1,2 | Message IDs of the Google diagnostics vendor-extension channel. | `aasdk/protobuf/aap_protobuf/channel/control/GoogleDiagnosticsVendorExtensionMessageId.proto` L1-L4 |
| `InstrumentClusterInput.InstrumentClusterAction (full enum block)` | 0..7 | Instrument-cluster input actions defined next to the control messages. | `aasdk/protobuf/aap_protobuf/channel/control/InstrumentClusterInput.proto` L5-L17 |
| `aasdk ChannelId enum (implicit ordinals)` | CONTROL=0 … WIFI_PROJECTION=18, NONE=255 (values are implicit C++ enum ordinals; only NONE is written explicitly) | aasdk assigns each AAP service a fixed channel number from this enum, and openauto writes exactly this number into Service.id when filling the ServiceDiscoveryResponse (`service->set_id(static_cast<uint32_t>(channel_->getId()))`). CONTROL is the first member, so the control channel is channel 0. | `aasdk/include/aasdk/Messenger/ChannelId.hpp` L30-L51 |
| `Service.id assignment (openauto)` | Service.id = numeric ChannelId | Every openauto service fills its ServiceDiscoveryResponse entry with its channel number as the service id, which is the same number the phone then uses in ChannelOpenRequest.service_id and in the frame's channel byte. | `openauto/src/autoapp/Service/MediaSink/VideoMediaSinkService.cpp` L76 |
| `MessageType (frame flag)` | SPECIFIC=0, CONTROL=4 (1<<2) | Frame flag bit distinguishing service-specific messages from control messages. aasdk sends all control-channel messages with MessageType::SPECIFIC, but sends CHANNEL_OPEN_RESPONSE on a service channel with MessageType::CONTROL. | `aasdk/include/aasdk/Messenger/MessageType.hpp` L26-L29 |
| `EncryptionType (frame flag)` | PLAIN=0, ENCRYPTED=8 (1<<3) | Frame flag bit marking whether the payload is inside the TLS session. VERSION_REQUEST/RESPONSE, ENCAPSULATED_SSL and AUTH_COMPLETE are PLAIN; service discovery and everything after is ENCRYPTED. | `aasdk/include/aasdk/Messenger/EncryptionType.hpp` L56-L59 |
| `MessageId size` | 2 bytes | Every message payload begins with a 2-byte message ID; the protobuf body starts after it. | `aasdk/include/aasdk/Messenger/MessageId.hpp` L34 |
| `VERSION_REQUEST payload layout` | uint16 BE major \|\| uint16 BE minor (4 bytes after the 2-byte message ID) | Head unit's opening frame on channel 0, sent PLAIN with MessageType::SPECIFIC. | `aasdk/src/Channel/Control/ControlServiceChannel.cpp` L37-L51 |
| `VERSION_RESPONSE payload layout` | uint16[0]=major, uint16[1]=minor, uint16[2]=MessageStatus (big endian) | How aasdk parses the phone's version response: the third 16-bit word is a MessageStatus; STATUS_NO_COMPATIBLE_VERSION (-1) aborts. | `aasdk/src/Channel/Control/ControlServiceChannel.cpp` L238-L251 |
| `AUTH_COMPLETE encryption/type` | PLAIN + MessageType::SPECIFIC | aasdk sends the AuthResponse (status = STATUS_SUCCESS) unencrypted immediately after the TLS handshake completes; this is the last plaintext control message. | `aasdk/src/Channel/Control/ControlServiceChannel.cpp` L65-L75 |
| `AuthResponse.status value sent by head unit` | STATUS_SUCCESS (0) | openauto sets AuthResponse.status to MessageStatus::STATUS_SUCCESS when the handshake completed. | `openauto/src/autoapp/Service/AndroidAutoEntity.cpp` L155-L161 |
| `SERVICE_DISCOVERY_RESPONSE encryption/type` | ENCRYPTED + MessageType::SPECIFIC | First encrypted head-unit control message. | `aasdk/src/Channel/Control/ControlServiceChannel.cpp` L77-L88 |
| `CHANNEL_OPEN_RESPONSE encryption/type` | ENCRYPTED + MessageType::CONTROL, sent on the service's own channel | Head unit answers a per-service ChannelOpenRequest on that service channel; note the CONTROL flag (0x04) rather than SPECIFIC. | `aasdk/src/Channel/MediaSink/Video/VideoMediaSinkService.cpp` L50-L60 |
| `Ping encryption in aasdk` | EncryptionType::PLAIN | aasdk sends both PING_REQUEST and PING_RESPONSE with the PLAIN flag even though they occur after authentication. AACS (phone side) sends its ping response ENCRYPTED. See discrepancies. | `aasdk/src/Channel/Control/ControlServiceChannel.cpp` L165-L175 |
| `PingRequest.timestamp units (openauto)` | microseconds since high_resolution_clock epoch | openauto fills PingRequest.timestamp with a microsecond count; PingResponse echoes it back. | `openauto/src/autoapp/Service/AndroidAutoEntity.cpp` L367-L371 |
| `openauto default PingConfiguration values` | tracked_ping_count=5, timeout_ms=3000, interval_ms=1000, high_latency_threshold_ms=200 | Concrete ping parameters a head unit advertises inside ServiceDiscoveryResponse.connection_configuration.ping_configuration. | `openauto/src/autoapp/Service/AndroidAutoEntity.cpp` L187-L191 |
| `Control-channel message IDs handled by aasdk head unit` | 2,3,5,11,12,18,13,17,23,15,16 | Exact set of inbound control message IDs an aasdk head unit dispatches; everything else is logged and ignored. Notably it does not handle 20/21/22/24/25/26. | `aasdk/src/Channel/Control/ControlServiceChannel.cpp` L197-L235 |
| `aa-proxy-rs control message ID name table` | 0x0001..0xFFFF | Independent confirmation of every ControlMessageType numeric value, expressed in hex by a third-party implementation. | `aa-proxy-rs/src/mitm.rs` L773-L804 |
| `AACS legacy MessageType enum (phone side)` | 1..0x13 | Older/partial control message ID list from the C# -> C++ phone-side implementation; agrees numerically with ControlMessageType for the IDs it defines. | `AACS/include/enums.h` L21-L37 |
| `AACS frame flags (phone side)` | Plain=0, Encrypted=8, First=1, Last=2, Bulk=3, Control=0, Specific=4 | Independent confirmation of the encryption / message-type flag bit positions used on the control channel. | `AACS/include/enums.h` L5-L19 |

### 4.3 Message definitions


#### `ServiceDiscoveryRequest`

Source: `aasdk/protobuf/aap_protobuf/service/control/message/ServiceDiscoveryRequest.proto`

```proto
syntax="proto2";

import "aap_protobuf/shared/PhoneInfo.proto";

package aap_protobuf.service.control.message;

message ServiceDiscoveryRequest
{
    optional bytes small_icon = 1;
    optional bytes medium_icon = 2;
    optional bytes large_icon = 3;
    optional string label_text = 4;
    optional string device_name = 5;
    optional shared.PhoneInfo phone_info = 6;
}
```

#### `PhoneInfo`

Source: `aasdk/protobuf/aap_protobuf/shared/PhoneInfo.proto`

```proto
syntax="proto2";

package aap_protobuf.shared;

message PhoneInfo {
  optional string instance_id = 1;
  optional string connectivity_lifetime_id = 2;
}
```

#### `ServiceDiscoveryResponse`

Source: `aasdk/protobuf/aap_protobuf/service/control/message/ServiceDiscoveryResponse.proto`

```proto
syntax="proto2";

import "aap_protobuf/service/Service.proto";
import "aap_protobuf/service/control/message/ConnectionConfiguration.proto";
import "aap_protobuf/service/control/message/DriverPosition.proto";
import "aap_protobuf/service/control/message/HeadUnitInfo.proto";

package aap_protobuf.service.control.message;

message ServiceDiscoveryResponse
{
    repeated service.Service channels = 1;
    optional string make = 2 [deprecated = true];
    optional string model = 3 [deprecated = true];
    optional string year = 4 [deprecated = true];
    optional string vehicle_id = 5 [deprecated = true];
    optional DriverPosition driver_position = 6;
    optional string head_unit_make = 7 [deprecated = true];
    optional string head_unit_model = 8 [deprecated = true];
    optional string head_unit_software_build = 9 [deprecated = true];
    optional string head_unit_software_version = 10 [deprecated = true];
    optional bool can_play_native_media_during_vr = 11 [deprecated = true];
    optional int32 session_configuration = 13;
    optional string display_name = 14;
    optional bool probe_for_support = 15;
    optional ConnectionConfiguration connection_configuration = 16;
    optional HeadUnitInfo headunit_info = 17;
}
```

#### `Service (the per-service ChannelDescriptor)`

Source: `aasdk/protobuf/aap_protobuf/service/Service.proto`

```proto
syntax="proto2";

option optimize_for=SPEED;

import "aap_protobuf/service/sensorsource/SensorSourceService.proto";
import "aap_protobuf/service/media/sink/MediaSinkService.proto";
import "aap_protobuf/service/inputsource/InputSourceService.proto";
import "aap_protobuf/service/media/source/MediaSourceService.proto";
import "aap_protobuf/service/bluetooth/BluetoothService.proto";
import "aap_protobuf/service/navigationstatus/NavigationStatusService.proto";
import "aap_protobuf/service/vendorextension/VendorExtensionService.proto";
import "aap_protobuf/service/wifiprojection/WifiProjectionService.proto";
import "aap_protobuf/service/mediabrowser/MediaBrowserService.proto";
import "aap_protobuf/service/genericnotification/GenericNotificationService.proto";
import "aap_protobuf/service/phonestatus/PhoneStatusService.proto";
import "aap_protobuf/service/mediaplayback/MediaPlaybackStatusService.proto";
import "aap_protobuf/service/radio/RadioService.proto";

package aap_protobuf.service;

message Service
{
    required int32 id = 1;
    optional sensorsource.SensorSourceService sensor_source_service = 2;
    optional media.sink.MediaSinkService media_sink_service = 3;
    optional inputsource.InputSourceService input_source_service = 4;
    optional media.source.MediaSourceService media_source_service = 5;
    optional bluetooth.BluetoothService bluetooth_service = 6;
    optional radio.RadioService radio_service = 7;
    optional navigationstatus.NavigationStatusService navigation_status_service = 8;
    optional mediaplayback.MediaPlaybackStatusService media_playback_service = 9;
    optional phonestatus.PhoneStatusService phone_status_service = 10;
    optional mediabrowser.MediaBrowserService media_browser_service = 11;
    optional vendorextension.VendorExtensionService vendor_extension_service = 12;
    optional genericnotification.GenericNotificationService generic_notification_service = 13;
    optional wifiprojection.WifiProjectionService wifi_projection_service = 14;
}
```

#### `ServiceDiscoveryUpdate`

Source: `aasdk/protobuf/aap_protobuf/service/control/message/ServiceDiscoveryUpdate.proto`

```proto
syntax="proto2";

import "aap_protobuf/service/Service.proto";

package aap_protobuf.service.control.message;

message ServiceDiscoveryUpdate {
  optional service.Service service = 1;
}
```

#### `SensorSourceService (Service field 2)`

Source: `aasdk/protobuf/aap_protobuf/service/sensorsource/SensorSourceService.proto`

```proto
message SensorSourceService
{
    repeated message.Sensor sensors = 1;
    optional uint32 location_characterization = 2;
    repeated message.FuelType supported_fuel_types = 3;
    repeated message.EvConnectorType supported_ev_connector_types = 4;
}
```

#### `MediaSinkService (Service field 3)`

Source: `aasdk/protobuf/aap_protobuf/service/media/sink/MediaSinkService.proto`

```proto
message MediaSinkService
{
    optional shared.message.MediaCodecType available_type = 1 [default = MEDIA_CODEC_AUDIO_PCM];;
    optional message.AudioStreamType audio_type = 2;
    repeated shared.message.AudioConfiguration audio_configs = 3;
    repeated message.VideoConfiguration video_configs = 4;
    optional bool available_while_in_call = 5;
    optional uint32 display_id = 6;
    optional message.DisplayType display_type = 7;
    optional message.KeyCode initial_content_keycode = 8;
}
```

#### `InputSourceService (Service field 4)`

Source: `aasdk/protobuf/aap_protobuf/service/inputsource/InputSourceService.proto`

```proto
message InputSourceService {
    repeated int32 keycodes_supported = 1 [packed = true];

    repeated TouchScreen touchscreen = 2;
    message TouchScreen {
        required int32 width = 1;
        required int32 height = 2;
        optional message.TouchScreenType type = 3;
        optional bool is_secondary = 4;
    }

    repeated TouchPad touchpad = 3;
    message TouchPad {
        required int32 width = 1;
        required int32 height = 2;
        optional bool ui_navigation = 3;
        optional int32 physical_width = 4;
        optional int32 physical_height = 5;
        optional bool ui_absolute = 6;
        optional bool tap_as_select = 7;
        optional int32 sensitivity = 8;
    }

    repeated message.FeedbackEvent feedback_events_supported = 4;
    optional uint32 display_id = 5;
}
```

#### `MediaSourceService (Service field 5)`

Source: `aasdk/protobuf/aap_protobuf/service/media/source/MediaSourceService.proto`

```proto
message MediaSourceService
{
    optional media.shared.message.MediaCodecType available_type = 1 [default = MEDIA_CODEC_AUDIO_PCM];
    optional media.shared.message.AudioConfiguration audio_config = 2;
    optional bool available_while_in_call = 3;
}
```

#### `BluetoothService (Service field 6)`

Source: `aasdk/protobuf/aap_protobuf/service/bluetooth/BluetoothService.proto`

```proto
message BluetoothService
{
    required string car_address = 1;
    repeated message.BluetoothPairingMethod supported_pairing_methods = 2 [packed = true];
}
```

#### `RadioService (Service field 7)`

Source: `aasdk/protobuf/aap_protobuf/service/radio/RadioService.proto`

```proto
message RadioService {
    repeated message.RadioProperties radio_properties = 1;
}
```

#### `NavigationStatusService (Service field 8)`

Source: `aasdk/protobuf/aap_protobuf/service/navigationstatus/NavigationStatusService.proto`

```proto
message NavigationStatusService {
    required int32 minimum_interval_ms = 1;

    required InstrumentClusterType type = 2;
    enum InstrumentClusterType {
        IMAGE = 1;
        ENUM = 2;
    }

    optional ImageOptions image_options = 3;
    message ImageOptions {
        required int32 height = 1;
        required int32 width = 2;
        required int32 colour_depth_bits = 3;
    }
}
```

#### `MediaPlaybackStatusService (Service field 9)`

Source: `aasdk/protobuf/aap_protobuf/service/mediaplayback/MediaPlaybackStatusService.proto`

```proto
message MediaPlaybackStatusService
{

}
```

#### `PhoneStatusService (Service field 10)`

Source: `aasdk/protobuf/aap_protobuf/service/phonestatus/PhoneStatusService.proto`

```proto
message PhoneStatusService
{

}
```

#### `MediaBrowserService (Service field 11)`

Source: `aasdk/protobuf/aap_protobuf/service/mediabrowser/MediaBrowserService.proto`

```proto
message MediaBrowserService
{

}
```

#### `VendorExtensionService (Service field 12)`

Source: `aasdk/protobuf/aap_protobuf/service/vendorextension/VendorExtensionService.proto`

```proto
message VendorExtensionService
{
    required string name = 1;
    repeated string package_white_list = 2;
    optional bytes data = 3;
}
```

#### `GenericNotificationService (Service field 13)`

Source: `aasdk/protobuf/aap_protobuf/service/genericnotification/GenericNotificationService.proto`

```proto
message GenericNotificationService
{

}
```

#### `WifiProjectionService (Service field 14)`

Source: `aasdk/protobuf/aap_protobuf/service/wifiprojection/WifiProjectionService.proto`

```proto
message WifiProjectionService {
    optional string car_wifi_bssid = 1;
}
```

#### `ConnectionConfiguration`

Source: `aasdk/protobuf/aap_protobuf/service/control/message/ConnectionConfiguration.proto`

```proto
message ConnectionConfiguration {
  optional PingConfiguration ping_configuration = 1;
  optional WirelessTcpConfiguration wireless_tcp_configuration = 2;
}
```

#### `PingConfiguration`

Source: `aasdk/protobuf/aap_protobuf/service/control/message/PingConfiguration.proto`

```proto
message PingConfiguration {
  optional uint32 timeout_ms = 1;
  optional uint32 interval_ms = 2;
  optional uint32 high_latency_threshold_ms = 3;
  optional uint32 tracked_ping_count = 4;
}
```

#### `WirelessTcpConfiguration`

Source: `aasdk/protobuf/aap_protobuf/service/control/message/WirelessTcpConfiguration.proto`

```proto
message WirelessTcpConfiguration {
  optional uint32 socket_receive_buffer_size_kb = 1;
  optional uint32 socket_send_buffer_size_kb = 2;
  optional uint32 socket_read_timeout_ms = 3;
}
```

#### `HeadUnitInfo`

Source: `aasdk/protobuf/aap_protobuf/service/control/message/HeadUnitInfo.proto`

```proto
message HeadUnitInfo {
  optional string make = 1;
  optional string model = 2;
  optional string year = 3;
  optional string vehicle_id = 4;
  optional string head_unit_make = 5;
  optional string head_unit_model = 6;
  optional string head_unit_software_build = 7;
  optional string head_unit_software_version = 8;
}
```

#### `AuthResponse`

Source: `aasdk/protobuf/aap_protobuf/service/control/message/AuthResponse.proto`

```proto
message AuthResponse {
    required int32 status = 1;
}
```

#### `ChannelOpenRequest`

Source: `aasdk/protobuf/aap_protobuf/service/control/message/ChannelOpenRequest.proto`

```proto
message ChannelOpenRequest
{
    required sint32 priority = 1;
    required int32 service_id = 2;
}
```

#### `ChannelOpenResponse`

Source: `aasdk/protobuf/aap_protobuf/service/control/message/ChannelOpenResponse.proto`

```proto
message ChannelOpenResponse
{
    required shared.MessageStatus status = 1;
}
```

#### `ChannelCloseNotification`

Source: `aasdk/protobuf/aap_protobuf/service/control/message/ChannelCloseNotification.proto`

```proto
message ChannelCloseNotification {

}
```

#### `PingRequest`

Source: `aasdk/protobuf/aap_protobuf/service/control/message/PingRequest.proto`

```proto
message PingRequest {
    required int64 timestamp = 1;
    optional bool bug_report = 2;
    optional bytes data = 3;
}
```

#### `PingResponse`

Source: `aasdk/protobuf/aap_protobuf/service/control/message/PingResponse.proto`

```proto
message PingResponse {
    required int64 timestamp = 1;
    optional bytes data = 2;
}
```

#### `ByeByeRequest`

Source: `aasdk/protobuf/aap_protobuf/service/control/message/ByeByeRequest.proto`

```proto
message ByeByeRequest
{
    required ByeByeReason reason = 1;
}
```

#### `ByeByeResponse`

Source: `aasdk/protobuf/aap_protobuf/service/control/message/ByeByeResponse.proto`

```proto
message ByeByeResponse
{

}
```

#### `AudioFocusRequest (payload of MESSAGE_AUDIO_FOCUS_REQUEST=18 as parsed by aasdk)`

Source: `aasdk/protobuf/aap_protobuf/service/control/message/AudioFocusRequest.proto`

```proto
message AudioFocusRequest
{
    required AudioFocusRequestType audio_focus_type = 1;
}
```

#### `AudioFocusRequestNotification (alternate name for the same wire shape)`

Source: `aasdk/protobuf/aap_protobuf/service/control/message/AudioFocusRequestNotification.proto`

```proto
message AudioFocusRequestNotification {
  required AudioFocusRequestType request = 1;
}
```

#### `AudioFocusNotification (payload of MESSAGE_AUDIO_FOCUS_NOTIFICATION=19)`

Source: `aasdk/protobuf/aap_protobuf/service/control/message/AudioFocusNotification.proto`

```proto
message AudioFocusNotification {
    required AudioFocusStateType focus_state = 1;
    optional bool unsolicited = 2;
}
```

#### `NavFocusRequestNotification (payload of MESSAGE_NAV_FOCUS_REQUEST=13)`

Source: `aasdk/protobuf/aap_protobuf/service/control/message/NavFocusRequestNotification.proto`

```proto
message NavFocusRequestNotification {
    optional NavFocusType focus_type = 1;
}
```

#### `NavFocusNotification (payload of MESSAGE_NAV_FOCUS_NOTIFICATION=14)`

Source: `aasdk/protobuf/aap_protobuf/service/control/message/NavFocusNotification.proto`

```proto
message NavFocusNotification {
    required NavFocusType focus_type = 1;
}
```

#### `VoiceSessionNotification (payload of MESSAGE_VOICE_SESSION_NOTIFICATION=17)`

Source: `aasdk/protobuf/aap_protobuf/service/control/message/VoiceSessionNotification.proto`

```proto
message VoiceSessionNotification
{
    optional VoiceSessionStatus status = 1;

}
```

#### `VideoFocusRequestNotification (media sink msg 32775, phone -> head unit)`

Source: `aasdk/protobuf/aap_protobuf/service/media/video/message/VideoFocusRequestNotification.proto`

```proto
message VideoFocusRequestNotification {
    optional int32 disp_channel_id = 1 [deprecated = true];
    optional VideoFocusMode mode = 2;
    optional VideoFocusReason reason = 3;
}
```

#### `VideoFocusNotification (media sink msg 32776, head unit -> phone)`

Source: `aasdk/protobuf/aap_protobuf/service/media/video/message/VideoFocusNotification.proto`

```proto
message VideoFocusNotification {
    optional VideoFocusMode focus = 1;
    optional bool unsolicited = 2;
}
```

#### `BatteryStatusNotification (payload of MESSAGE_BATTERY_STATUS_NOTIFICATION=23)`

Source: `aasdk/protobuf/aap_protobuf/service/control/message/BatteryStatusNotification.proto`

```proto
message BatteryStatusNotification {
  required uint32 battery_level = 1;
  optional uint32 time_remaining_s = 2;
  optional bool critical_battery = 3;
}
```

#### `CallAvailabilityStatus (payload of MESSAGE_CALL_AVAILABILITY_STATUS=24)`

Source: `aasdk/protobuf/aap_protobuf/service/control/message/CallAvailabilityStatus.proto`

```proto
message CallAvailabilityStatus {
  optional bool call_available = 1;
}
```

#### `CarConnectedDevicesRequest (payload of message 20)`

Source: `aasdk/protobuf/aap_protobuf/service/control/message/CarConnectedDevicesRequest.proto`

```proto
message CarConnectedDevicesRequest {

}
```

#### `CarConnectedDevices (payload of message 21)`

Source: `aasdk/protobuf/aap_protobuf/service/control/message/CarConnectedDevices.proto`

```proto
message CarConnectedDevices {
  repeated ConnectedDevice connected_devices = 1;
  optional bool unsolicited = 2;
  optional bool final_list = 3 [default = true];
}
```

#### `ConnectedDevice`

Source: `aasdk/protobuf/aap_protobuf/service/control/message/ConnectedDevice.proto`

```proto
message ConnectedDevice {
  optional string device_name = 1;
  optional int32 device_id = 2;
}
```

#### `UserSwitchRequest (payload of message 22)`

Source: `aasdk/protobuf/aap_protobuf/service/control/message/UserSwitchRequest.proto`

```proto
message UserSwitchRequest {
  optional ConnectedDevice selected_device = 1;
}
```

#### `UserSwitchResponse (payload of message 25)`

Source: `aasdk/protobuf/aap_protobuf/service/control/message/UserSwitchResponse.proto`

```proto
message UserSwitchResponse {
  optional UserSwitchStatus status = 1;
  optional ConnectedDevice selected_device = 2;
}
```

#### `VersionRequestOptions`

Source: `aasdk/protobuf/aap_protobuf/service/control/message/VersionRequestOptions.proto`

```proto
syntax="proto2";

package aap_protobuf.channel.control.version;

message VersionRequestOptions {
  optional int64 snapshot_version = 1;
}
```

#### `VersionResponseOptions`

Source: `aasdk/protobuf/aap_protobuf/service/control/message/VersionResponseOptions.proto`

```proto
message VersionResponseOptions {
  optional ConnectionConfiguration connection_configuration = 1;
}
```

#### `UpdateUiConfigRequest / UpdateUiConfigReply (media sink msgs 32777 / 32778)`

Source: `aasdk/protobuf/aap_protobuf/service/control/message/UpdateUiConfigRequest.proto`

```proto
message UpdateUiConfigRequest {
  optional media.shared.message.UiConfig ui_config = 1;
}

// UpdateUiConfigReply.proto:
message UpdateUiConfigReply {
  optional media.shared.message.UiConfig ui_config = 1;
}
```

#### `AACS legacy ServiceDiscoveryRequest (phone side, different schema)`

Source: `AACS/proto/ServiceDiscoveryRequest.proto`

```proto
message ServiceDiscoveryRequest
{
    required string model = 4;
    required string manufacturer = 5;
}
```

#### `AACS legacy Channel (equivalent of Service)`

Source: `AACS/proto/Channel.proto`

```proto
message UnknownChannel1 {}
message UnknownChannel2 {}

message Channel
{
    required uint32 channel_id = 1;
    optional SensorChannel sensor_channel = 2;
    optional MediaChannel media_channel = 3;
    optional InputChannel input_channel = 4;
    optional MediaInputChannel media_input_channel = 5;
    optional BluetoothChannel bluetooth_channel = 6;
    optional NavigationChannel navigation_channel = 8;
    optional UnknownChannel1 unknown_channel_1 = 10;
    optional VendorExtensionChannel vendor_extension_channel = 12;
    optional UnknownChannel2 unknown_channel_2 = 13;
}
```

### 4.4 Where the references disagree


**aasdk_proto/ does not contain the control protobufs in this checkout**

references/aasdk/aasdk_proto/ contains ONLY WifiChannelMessageIdsEnum.proto, WifiInfoRequestMessage.proto, WifiInfoResponseMessage.proto, WifiSecurityRequestMessage.proto, WifiSecurityResponseMessage.proto. There is no ControlMessageIdsEnum.proto, ServiceDiscoveryResponseMessage.proto, ChannelDescriptorData.proto, AudioFocus*.proto or VideoFocus*.proto anywhere under aasdk_proto/ (verified by find over all of references/). The canonical control definitions in this tree live under aasdk/protobuf/aap_protobuf/service/control/ and aasdk/protobuf/aap_protobuf/service/media/video/. All constants above are taken from that tree.

**ServiceDiscoveryResponse field 1 name: channels vs services**

aasdk declares `repeated service.Service channels = 1;` (aasdk/protobuf/aap_protobuf/service/control/message/ServiceDiscoveryResponse.proto L12) while aa-proxy-rs declares `repeated Service services = 1;` (aa-proxy-rs/src/protos/protos.proto L38). Same tag, same type, wire-identical; only the generated accessor name differs. Everything else in the two ServiceDiscoveryResponse definitions (fields 2-17, including the deprecated markers) is byte-for-byte identical.

**AACS uses an entirely different (older) ServiceDiscoveryRequest schema**

AACS/proto/ServiceDiscoveryRequest.proto declares `required string model = 4; required string manufacturer = 5;` whereas aasdk declares those tags as `optional string label_text = 4; optional string device_name = 5;` and adds icons (1-3) and phone_info (6). Both are string fields at the same tags so they decode, but the semantics are swapped/renamed. Prefer aasdk.

**AACS Channel vs aasdk Service field numbering**

AACS/proto/Channel.proto uses `required uint32 channel_id = 1` and maps 2=sensor, 3=media, 4=input, 5=media_input, 6=bluetooth, 8=navigation, 10=UnknownChannel1, 12=vendor_extension, 13=UnknownChannel2. aasdk's Service.proto shows the missing pieces: 7=radio_service, 9=media_playback_service, 10=phone_status_service, 11=media_browser_service, 13=generic_notification_service, 14=wifi_projection_service. So AACS's UnknownChannel1 is phone_status_service and UnknownChannel2 is generic_notification_service. AACS also renames field 1 from `id` to `channel_id` and widens it from int32 to uint32.

**ChannelOpenRequest field 1: priority vs unknown_field**

aasdk: `required sint32 priority = 1; required int32 service_id = 2;` (ChannelOpenRequest.proto L7-8), matching aa-proxy-rs (protos.proto L294-297). AACS: `required int32 unknown_field = 1; required int32 channel_id = 2;` (AACS/proto/ChannelOpenRequest.proto L9-10) and it sets that field to 0 (AACS/AAServer/src/ChannelHandler.cpp L37). Note sint32 vs int32 is a REAL wire difference (zigzag encoding) for negative priorities; positive values 0 and 1 encode differently too (sint32 0 -> 0x00, sint32 1 -> 0x02 vs int32 1 -> 0x01). Follow aasdk/aa-proxy-rs: sint32.

**ChannelOpenResponse status type**

aasdk: `required shared.MessageStatus status = 1;` (enum). AACS: `required int32 status = 1;`. Wire-compatible (both varint) but aasdk gives the named values; 0 = STATUS_SUCCESS.

**aasdk sends PING_REQUEST and PING_RESPONSE with EncryptionType::PLAIN**

aasdk/src/Channel/Control/ControlServiceChannel.cpp L156 (sendPingResponse) and L168 (sendPingRequest) both construct the Message with messenger::EncryptionType::PLAIN, even though ping only happens after AUTH_COMPLETE. Every other post-auth control message in the same file (service discovery response, audio focus, byebye, nav focus) uses ENCRYPTED. AACS's phone side sends its ping response with EncryptionType::Encrypted (AACS/AAServer/src/AaCommunicator.cpp L267). This looks like an aasdk bug; a clean-room implementation should send ping traffic encrypted like all other post-auth messages.

**aasdk sendVoiceSessionFocusResponse is a no-op stub**

aasdk/src/Channel/Control/ControlServiceChannel.cpp L146-151: the function body only logs "sendVoiceSessionFocusResponse()" and never builds or sends a message. There is no voice-session response message ID in ControlMessageType either (only MESSAGE_VOICE_SESSION_NOTIFICATION=17), so the notification appears to be one-way phone -> head unit.

**AACS control message ID table is incomplete and renames two IDs**

AACS/include/enums.h L21-37 omits 9 (channel close), 15/16 (byebye) and 20-26 entirely. It also names 0x0e "NavigationFocusResponse" (aasdk: MESSAGE_NAV_FOCUS_NOTIFICATION) and 0x13 "AudioFocusResponse" (aasdk: MESSAGE_AUDIO_FOCUS_NOTIFICATION). All numeric values it does define agree with aasdk.

**AACS media message ID names differ but values agree**

AACS/include/enums.h L39-47: MediaWithTimestampIndication=0x0000, MediaIndication=0x0001, SetupRequest=0x8000, StartIndication=0x8001, SetupResponse=0x8003, MediaAckIndication=0x8004, VideoFocusIndication=0x8008. aasdk MediaMessageId.proto: MEDIA_MESSAGE_DATA=0, MEDIA_MESSAGE_CODEC_CONFIG=1, MEDIA_MESSAGE_SETUP=32768, MEDIA_MESSAGE_START=32769, MEDIA_MESSAGE_CONFIG=32771, MEDIA_MESSAGE_ACK=32772, MEDIA_MESSAGE_VIDEO_FOCUS_NOTIFICATION=32776. Same numbers, different names; AACS's naming of 0x0000/0x0001 is inverted relative to aasdk's DATA/CODEC_CONFIG. AACS has no equivalent of 0x8007 (video focus REQUEST), 0x8002 (STOP) or 0x8005/0x8006 (microphone).

**aa-proxy-rs MediaMessageId is a superset of aasdk's**

aasdk's MediaMessageId ends at MEDIA_MESSAGE_AUDIO_UNDERFLOW_NOTIFICATION=32779. aa-proxy-rs/src/protos/protos.proto L1592-1600 continues: MEDIA_MESSAGE_ACTION_TAKEN_NOTIFICATION=32780, MEDIA_MESSAGE_INTEGRATED_OVERLAY_PARAMETERS_NOTIFICATION=32781, ..._START_NOTIFICATION=32782, ..._STOP_NOTIFICATION=32783, ..._SESSION_DATA_UPDATE=32784, MEDIA_MESSAGE_UPDATE_HU_UI_CONFIG_REQUEST=32785, ..._RESPONSE=32786, MEDIA_MESSAGE_MEDIA_STATS=32787, MEDIA_MESSAGE_MEDIA_OPTIONS=32788. The shared range 0-32779 is identical.

**UpdateUiConfigRequest field type disagreement**

aasdk: `optional media.shared.message.UiConfig ui_config = 1;` (UpdateUiConfigRequest.proto L8). aa-proxy-rs: `message UpdateUiConfigRequest { optional AdditionalVideoConfig config = 1; }` (protos.proto L538) with a comment stating the wire ID is 0x8009 inbound (Phone->HU) and 0x800A outbound (HU->Phone), same schema, direction-dependent ID, and that field 1 is required by the phone or it raises PROTOCOL_WRONG_MESSAGE / INVALID_UI_CONFIG. The IDs match aasdk's MEDIA_MESSAGE_UPDATE_UI_CONFIG_REQUEST=32777 (0x8009) / _REPLY=32778 (0x800A), but the sub-message type differs. Unresolved; aa-proxy-rs's comment is more specific about observed behaviour.

**aasdk package declarations are inconsistent across the control protos**

VersionRequestOptions.proto declares `package aap_protobuf.channel.control.version;` while every other file in the same directory declares `package aap_protobuf.service.control.message;`. Separately, channel/control/LocationCharacterization.proto and channel/control/SessionConfiguration.proto both declare `package aap_protobuf.shared;` despite living under channel/control/, and channel/control/GoogleDiagnosticsVendorExtensionMessageId.proto has no `syntax` and no `package` line at all. These are packaging inconsistencies in aasdk, not wire differences.

**Protocol version advertised differs between references**

aasdk advertises 1.6 (Version.hpp AASDK_MAJOR=1 / AASDK_MINOR=6, matching GalConstants PROTOCOL_MAJOR_VERSION=1 / PROTOCOL_MINOR_VERSION=6). AACS's phone-side responds with 1.5 and rejects any major other than 1 (AACS/AAServer/src/AaCommunicator.cpp L85-92).

**openauto nav-focus code contradicts its own comment**

openauto/src/autoapp/Service/AndroidAutoEntity.cpp L279-287: the comment says "If the MD sends NAV_FOCUS_PROJECTED in the request, we should stop any local navigation on the HU and grant NAV_FOCUS_NATIVE in the response", but the code unconditionally sets NAV_FOCUS_PROJECTED. Given openauto has no native navigation, the code is the intended behaviour and the comment is wrong.

**AudioFocusRequest vs AudioFocusRequestNotification**

aasdk ships two messages with the same wire shape for control message 18: AudioFocusRequest{required AudioFocusRequestType audio_focus_type = 1} and AudioFocusRequestNotification{required AudioFocusRequestType request = 1}. The C++ control channel parses AudioFocusRequest for MESSAGE_AUDIO_FOCUS_REQUEST (ControlServiceChannel.cpp L264-273). aa-proxy-rs only defines AudioFocusRequestNotification (protos.proto L542-544). Field names differ, tag and type are identical, so either decodes correctly.

**MESSAGE_CAR_CONNECTED_DEVICES_RESPONSE has no matching *Response message name**

ControlMessageType defines MESSAGE_CAR_CONNECTED_DEVICES_RESPONSE = 21 but the payload message is named CarConnectedDevices (there is no CarConnectedDevicesResponse.proto). Confirmed by directory listing of aasdk/protobuf/aap_protobuf/service/control/message/.

**Message ID 10 is not assigned**

ControlMessageType skips from MESSAGE_CHANNEL_CLOSE_NOTIFICATION = 9 to MESSAGE_PING_REQUEST = 11. Both aasdk (ControlMessageType.proto L15-16) and aa-proxy-rs (mitm.rs L783-784, protos.proto) leave 10 / 0x000A undefined.

**aasdk head unit ignores several defined control messages**

The switch in ControlServiceChannel.cpp L197-235 has no cases for MESSAGE_CAR_CONNECTED_DEVICES_REQUEST (20), _RESPONSE (21), MESSAGE_USER_SWITCH_REQUEST (22), MESSAGE_CALL_AVAILABILITY_STATUS (24), MESSAGE_USER_SWITCH_RESPONSE (25) or MESSAGE_SERVICE_DISCOVERY_UPDATE (26); they hit the default branch which logs "Message Id not Handled" and re-arms the receive. A clean-room head unit should at minimum tolerate these silently.

**Channel numbering is static in aasdk but dynamic in the real protocol**

aasdk/include/aasdk/Messenger/ChannelId.hpp L22-27 carries an explicit TODO: "In AA, Channel Id's are dynamic. We use ChannelId here for a static implementation, which, while acceptable, may cause more channels to be open than needs to be." So the fixed CONTROL=0..WIFI_PROJECTION=18 mapping is an aasdk convention, not a protocol constant — the only value the protocol actually pins is that the control channel is 0 (AACS hardcodes channel 0 for all control traffic, AaCommunicator.cpp L82/L103/L267). Everything else is whatever id the head unit puts in Service.id.

---

## 5. Video channel

Message ids, VideoConfiguration, AV channel setup, and how H.264 frames are carried. Not yet implemented -- Phase 2.

### 5.1 Sequence

```text
VIDEO CHANNEL LIFECYCLE (head-unit / sink perspective, from aasdk/src/Channel/MediaSink/Video/VideoMediaSinkService.cpp L102-L203 and openauto/src/autoapp/Service/MediaSink/VideoMediaSinkService.cpp).

STEP 0 - ADVERTISE. Before any video message, the head unit advertises the video service in ServiceDiscoveryResponse: one Service whose media_sink_service (MediaSinkService) has available_type = MEDIA_CODEC_VIDEO_H264_BP and a repeated video_configs list of VideoConfiguration entries. openauto pushes exactly ONE entry, filling codec_resolution, frame_rate, height_margin, width_margin, density (openauto/src/autoapp/Service/MediaSink/VideoMediaSinkService.cpp L75-L92: "auto *videoConfig1 = videoChannel->add_video_configs(); videoConfig1->set_codec_resolution(...); ... videoConfig1->set_density(videoOutput_->getScreenDPI());"). aa-proxy-rs fills the full set including decoder_additional_depth=0, viewing_distance, pixel_aspect_ratio_e4=10000, real_density and video_codec_type=MEDIA_CODEC_VIDEO_H264_BP (aa-proxy-rs/src/display.rs L192-L203). THE INDEX OF EACH ENTRY IN THIS repeated LIST IS THE "configuration index" referenced later.

STEP 1 - CHANNEL OPEN. Phone sends ControlMessageType::MESSAGE_CHANNEL_OPEN_REQUEST (a CONTROL-type message id) on the video channel; the head unit replies ChannelOpenResponse with status STATUS_SUCCESS (openauto VideoMediaSinkService.cpp L129-L149). Note aasdk sends this one reply with messenger::MessageType::CONTROL while every subsequent video message uses MessageType::SPECIFIC (aasdk VideoMediaSinkService.cpp L53-L54 vs L66-L67).

STEP 2 - SETUP REQUEST (phone -> HU). Message id 0x8000 MEDIA_MESSAGE_SETUP, payload = Setup { required MediaCodecType type = 1 }. For video, type = MEDIA_CODEC_VIDEO_H264_BP (3). AACS emits this literally as bytes 80 00 08 03 (AACS/AAServer/src/VideoChannelHandler.cpp L138-L145). aasdk dispatches it via handleChannelSetupRequest (aasdk VideoMediaSinkService.cpp L112-L113, L137-L146).

STEP 3 - SETUP RESPONSE / CONFIG (HU -> phone). Message id 0x8003 MEDIA_MESSAGE_CONFIG, payload = Config { status, max_unacked, configuration_indices }. status is STATUS_READY (2) if the video output initialised, STATUS_WAIT (1) otherwise. configuration_indices is a repeated uint32 listing WHICH indices into the advertised video_configs array the head unit is willing to use; openauto sends exactly [0] and max_unacked = 1 (openauto VideoMediaSinkService.cpp L109-L125). aasdk's sender is literally named sendChannelSetupResponse. AACS's phone side simply blocks until it sees message id 0x8003 (its "SetupResponse") without parsing it.

STEP 4 - VIDEO FOCUS. openauto chains a VideoFocusNotification (id 0x8008) immediately onto the successful send of the CONFIG response, with focus = VIDEO_FOCUS_PROJECTED and unsolicited = false (openauto VideoMediaSinkService.cpp L120-L125 promise->then(sendVideoFocusIndication), L233-L245). AACS's phone side treats the arrival of 0x8008 as its cue to send the Start indication (AACS/AAServer/src/VideoChannelHandler.cpp L181-L183: "else if (messageType == MediaMessageType::VideoFocusIndication) { sendStartIndication(); }"). Later the phone may send VideoFocusRequestNotification (id 0x8007, fields: deprecated disp_channel_id=1, mode=2, reason=3); openauto answers with another VideoFocusNotification and, if mode == VIDEO_FOCUS_NATIVE, relinquishes the display (openauto VideoMediaSinkService.cpp L207-L231).

STEP 5 - START INDICATION (phone -> HU). Message id 0x8001 MEDIA_MESSAGE_START, payload = Start { required int32 session_id = 1; required uint32 configuration_index = 2 }. configuration_index selects one of the indices the head unit offered in Config.configuration_indices, i.e. it picks which VideoConfiguration from the advertised repeated list the phone will actually encode for. AACS hardcodes 08 00 10 00 => session_id 0, configuration_index 0 (AACS/AAServer/src/VideoChannelHandler.cpp L152-L161). The head unit stores session_id for use in every subsequent Ack (openauto VideoMediaSinkService.cpp L151-L160).

STEP 6a - CODEC CONFIG (phone -> HU). Message id 0x0001 MEDIA_MESSAGE_CODEC_CONFIG. Payload after the 2-byte id is raw codec configuration bytes (H.264 SPS/PPS) with NO timestamp header — aasdk routes it directly to eventHandler->onMediaIndication(payload) (aasdk VideoMediaSinkService.cpp L121-L123), and openauto forwards it into the same write path with timestamp 0 (openauto VideoMediaSinkService.cpp L190-L193). aa-proxy-rs treats it as a distinct "codec config" blob cached separately and re-emitted with pts 0 (aa-proxy-rs/src/media_tap.rs L1029-L1039, L148-L152).

STEP 6b - VIDEO FRAMES (phone -> HU). Message id 0x0000 MEDIA_MESSAGE_DATA. Wire layout is: [2 bytes message id, big-endian][8 bytes presentation timestamp, unsigned 64-bit, BIG-ENDIAN, MICROSECONDS][H.264 Annex-B byte-stream bytes]. Evidence: aasdk gates on payload.size >= sizeof(Timestamp::ValueType) (uint64_t) and offsets the media buffer by that size (aasdk VideoMediaSinkService.cpp L181-L192); byte order comes from boost::endian big_to_native/native_to_big (aasdk/src/Messenger/Timestamp.cpp L29-L38); units and the exact 8-byte header are named explicitly in aa-proxy-rs ("const TIMESTAMP_HEADER: usize = 8; let pts_us = u64::from_be_bytes(payload[..TIMESTAMP_HEADER]...)", aa-proxy-rs/src/media_tap.rs L1041-L1046) and the construction side "Vec::with_capacity(2 + 8 + adapted.len()); push_media_message_id(...); payload.extend_from_slice(&pts_us.to_be_bytes())" (aa-proxy-rs/src/bt_sco_media_bridge.rs L442-L445). AACS's sender divides GStreamer's nanosecond PTS by 1000 before writing the header and its receiver multiplies by 1000 on the way back (AACS/AAServer/src/VideoChannelHandler.cpp L33-L39; AACS/AAClient/src/VideoChannelHandler.cpp L42-L51: "auto ts = bytesToUInt64(data, 2); ... pushDataToPipeline(localTs * 1000, vector(data.begin() + 2 + 8, data.end()));" — note the explicit skip of 2 + 8 bytes). These messages are sent with the BULK frame type (FrameType::First|Last) and ENCRYPTED, and are fragmented by the lower framing layer when larger than the transport MTU (AACS L44-L46; aa-proxy-rs reassembles with reassemble_media_packet before tapping, mitm.rs L2627).

STEP 6c - ACK (HU -> phone). For each received frame the head unit sends message id 0x8004 MEDIA_MESSAGE_ACK with Ack { session_id = the session_id from Start, ack = 1 }. openauto sends it immediately after handing the buffer to the video output (openauto VideoMediaSinkService.cpp L177-L187); aa-proxy-rs builds the same message with an incrementing ack counter (aa-proxy-rs/src/display.rs L324-L331). Config.max_unacked from step 3 is the flow-control window: how many frames the phone may have outstanding before it must wait for an Ack. Ack also has an optional repeated uint64 receive_timestamp_ns = 3, which neither openauto nor aa-proxy-rs populates.

STEP 7 - STOP. Message id 0x8002 MEDIA_MESSAGE_STOP, payload = the empty Stop message. openauto just re-arms its receive (openauto VideoMediaSinkService.cpp L162-L169).

CODEC / PROFILE. The negotiated codec is MEDIA_CODEC_VIDEO_H264_BP = 3, "BP" = Baseline Profile; AACS's encoder pins gst caps to profile=baseline, stream-format=byte-stream (AACS/AAServer/src/VideoChannelHandler.cpp L75-L78) and openauto's Raspberry Pi decoder is configured for OMX_VIDEO_CodingAVC (openauto/src/autoapp/Projection/OMXVideoOutput.cpp L272). NO reference states an H.264 LEVEL. NO reference states a protocol-mandated keyframe interval; the only concrete cadence in any reference is AACS's encoder setting key-int-max = 25 (AACS/AAServer/src/VideoChannelHandler.cpp L74). aa-proxy-rs detects keyframes structurally by Annex-B NAL type 5 = IDR, 1 = non-IDR slice (aa-proxy-rs/src/media_tap.rs L884-L905) and its consumers wait for a live IDR before starting playback — which implies (but does not specify) that the phone emits an IDR at the start of every session and after every focus regain (aa-proxy-rs/src/mitm.rs L2644-L2669).
```

### 5.2 Constants

| Constant | Value | Meaning | Source |
|---|---|---|---|
| `MEDIA_MESSAGE_DATA` | 0 (0x0000) | Video/audio frame payload message id. On the video channel the payload after this 2-byte id is an 8-byte timestamp followed by the H.264 Annex-B bytes. | `aasdk/protobuf/aap_protobuf/service/media/sink/MediaMessageId.proto` L5-L13 |
| `MEDIA_MESSAGE_CODEC_CONFIG` | 1 (0x0001) | Codec configuration message id (for video: the SPS/PPS blob). Carries NO timestamp header — aasdk routes it straight to onMediaIndication and openauto re-enters the timestamped path with timestamp 0. | `aasdk/protobuf/aap_protobuf/service/media/sink/MediaMessageId.proto` L7-L8 |
| `MEDIA_MESSAGE_SETUP` | 32768 (0x8000) | Phone -> head unit: AV/media channel setup request. Payload is the `Setup` message (required MediaCodecType type = 1). | `aasdk/protobuf/aap_protobuf/service/media/sink/MediaMessageId.proto` L9 |
| `MEDIA_MESSAGE_START` | 32769 (0x8001) | Phone -> head unit: AV channel start indication. Payload is `Start` { session_id, configuration_index }. | `aasdk/protobuf/aap_protobuf/service/media/sink/MediaMessageId.proto` L10 |
| `MEDIA_MESSAGE_STOP` | 32770 (0x8002) | Phone -> head unit: AV channel stop indication. Payload is the empty `Stop` message. | `aasdk/protobuf/aap_protobuf/service/media/sink/MediaMessageId.proto` L11 |
| `MEDIA_MESSAGE_CONFIG` | 32771 (0x8003) | Head unit -> phone: the setup RESPONSE. Payload is `Config` { status, max_unacked, configuration_indices }. aasdk's send function for this id is literally named sendChannelSetupResponse(). | `aasdk/src/Channel/MediaSink/Video/VideoMediaSinkService.cpp` L62-L74 |
| `MEDIA_MESSAGE_ACK` | 32772 (0x8004) | Head unit -> phone: media frame acknowledgement. Payload is `Ack` { session_id, ack, receive_timestamp_ns }. | `aasdk/src/Channel/MediaSink/Video/VideoMediaSinkService.cpp` L76-L88 |
| `MEDIA_MESSAGE_MICROPHONE_REQUEST` | 32773 (0x8005) | Microphone request id in the same media message-id space (not used on the video channel). | `aasdk/protobuf/aap_protobuf/service/media/sink/MediaMessageId.proto` L14 |
| `MEDIA_MESSAGE_MICROPHONE_RESPONSE` | 32774 (0x8006) | Microphone response id in the same media message-id space (not used on the video channel). | `aasdk/protobuf/aap_protobuf/service/media/sink/MediaMessageId.proto` L15 |
| `MEDIA_MESSAGE_VIDEO_FOCUS_REQUEST` | 32775 (0x8007) | Phone -> head unit: VideoFocusRequestNotification. aasdk dispatches this to handleVideoFocusRequest. | `aasdk/protobuf/aap_protobuf/service/media/sink/MediaMessageId.proto` L16 |
| `MEDIA_MESSAGE_VIDEO_FOCUS_NOTIFICATION` | 32776 (0x8008) | Head unit -> phone: VideoFocusNotification (focus granted/changed). aasdk sends it via sendVideoFocusIndication(). | `aasdk/src/Channel/MediaSink/Video/VideoMediaSinkService.cpp` L90-L97 |
| `MEDIA_MESSAGE_UPDATE_UI_CONFIG_REQUEST` | 32777 (0x8009) | Runtime UI-config push. aa-proxy-rs documents this as inbound (Phone->HU) and notes the same schema is used outbound with id 0x800A. | `aasdk/protobuf/aap_protobuf/service/media/sink/MediaMessageId.proto` L18-L20 |
| `MEDIA_MESSAGE_UPDATE_UI_CONFIG_REPLY` | 32778 (0x800A) | Reply/outbound half of the runtime UI-config push. | `aasdk/protobuf/aap_protobuf/service/media/sink/MediaMessageId.proto` L19 |
| `MEDIA_MESSAGE_AUDIO_UNDERFLOW_NOTIFICATION` | 32779 (0x800B) | Audio underflow notification (audio sinks only); last id present in aasdk's enum. | `aasdk/protobuf/aap_protobuf/service/media/sink/MediaMessageId.proto` L20-L21 |
| `MEDIA_MESSAGE_ACTION_TAKEN_NOTIFICATION .. MEDIA_MESSAGE_CRITICAL_UI_NOTIFICATION` | 32780..32789 (0x800C..0x8015) | Extended media message ids present ONLY in aa-proxy-rs (absent from aasdk): ACTION_TAKEN=32780, INTEGRATED_OVERLAY_PARAMETERS=32781, INTEGRATED_OVERLAY_START=32782, INTEGRATED_OVERLAY_STOP=32783, INTEGRATED_OVERLAY_SESSION_DATA_UPDATE=32784, UPDATE_HU_UI_CONFIG_REQUEST=32785, UPDATE_HU_UI_CONFIG_RESPONSE=32786, MEDIA_STATS=32787, MEDIA_OPTIONS=32788, CRITICAL_UI_NOTIFICATION=32789. | `aa-proxy-rs/src/protos/protos.proto` L1592-L1601 |
| `VIDEO_800x480` | 1 | VideoCodecResolutionType: 800 x 480 pixels (landscape). openauto maps this enum to QRect(0,0,800,480). | `aasdk/protobuf/aap_protobuf/service/media/sink/message/VideoCodecResolutionType.proto` L5-L15 |
| `VIDEO_1280x720` | 2 | VideoCodecResolutionType: 1280 x 720 pixels. openauto maps this enum value to QRect(0,0,1280,720). | `openauto/src/autoapp/Service/ServiceFactory.cpp` L99-L112 |
| `VIDEO_1920x1080` | 3 | VideoCodecResolutionType: 1920 x 1080 pixels. Confirmed by openauto's QRect(0,0,1920,1080) mapping. | `aasdk/protobuf/aap_protobuf/service/media/sink/message/VideoCodecResolutionType.proto` L8 |
| `VIDEO_2560x1440` | 4 | VideoCodecResolutionType: 2560 x 1440 pixels. aa-proxy-rs parses the alias "1440p" to this value. | `aa-proxy-rs/src/config_types.rs` L121-L133 |
| `VIDEO_3840x2160` | 5 | VideoCodecResolutionType: 3840 x 2160 pixels (4K, landscape). | `aasdk/protobuf/aap_protobuf/service/media/sink/message/VideoCodecResolutionType.proto` L10 |
| `VIDEO_720x1280` | 6 | VideoCodecResolutionType: 720 x 1280 pixels (portrait 720p). | `aasdk/protobuf/aap_protobuf/service/media/sink/message/VideoCodecResolutionType.proto` L11 |
| `VIDEO_1080x1920` | 7 | VideoCodecResolutionType: 1080 x 1920 pixels (portrait 1080p). | `aasdk/protobuf/aap_protobuf/service/media/sink/message/VideoCodecResolutionType.proto` L12 |
| `VIDEO_1440x2560` | 8 | VideoCodecResolutionType: 1440 x 2560 pixels (portrait 1440p). | `aasdk/protobuf/aap_protobuf/service/media/sink/message/VideoCodecResolutionType.proto` L13 |
| `VIDEO_2160x3840` | 9 | VideoCodecResolutionType: 2160 x 3840 pixels (portrait 4K). | `aasdk/protobuf/aap_protobuf/service/media/sink/message/VideoCodecResolutionType.proto` L14 |
| `Resolution enum name encodes WIDTHxHEIGHT` | strip "VIDEO_", split on 'X' -> (width, height) | aa-proxy-rs derives the pixel dimensions programmatically from the enum NAME, confirming that VIDEO_<W>x<H> literally denotes W pixels wide by H pixels high. | `aa-proxy-rs/src/sdr_ui.rs` L1158-L1167 |
| `VIDEO_FPS_60` | 1 | VideoFrameRateType: 60 frames per second. NOTE the counter-intuitive ordering — 60 is 1, 30 is 2. | `aasdk/protobuf/aap_protobuf/service/media/sink/message/VideoFrameRateType.proto` L5-L8 |
| `VIDEO_FPS_30` | 2 | VideoFrameRateType: 30 frames per second. This is openauto's default frame rate. | `aasdk/protobuf/aap_protobuf/service/media/sink/message/VideoFrameRateType.proto` L7 |
| `MEDIA_CODEC_VIDEO_H264_BP` | 3 | MediaCodecType for video: H.264 Baseline Profile. This is the value openauto advertises as available_type on the video sink and the value AACS sends in its Setup request (0x08 0x03). | `aasdk/protobuf/aap_protobuf/service/media/shared/message/MediaCodecType.proto` L5-L14 |
| `MEDIA_CODEC_VIDEO_VP9` | 5 | MediaCodecType alternative video codec: VP9. | `aasdk/protobuf/aap_protobuf/service/media/shared/message/MediaCodecType.proto` L11 |
| `MEDIA_CODEC_VIDEO_AV1` | 6 | MediaCodecType alternative video codec: AV1. | `aasdk/protobuf/aap_protobuf/service/media/shared/message/MediaCodecType.proto` L12 |
| `MEDIA_CODEC_VIDEO_H265` | 7 | MediaCodecType alternative video codec: H.265/HEVC. | `aasdk/protobuf/aap_protobuf/service/media/shared/message/MediaCodecType.proto` L13 |
| `Config.Status.STATUS_WAIT` | 1 | Setup-response status meaning the head unit is not yet ready to receive video. | `aasdk/protobuf/aap_protobuf/service/media/shared/message/Config.proto` L5-L13 |
| `Config.Status.STATUS_READY` | 2 | Setup-response status meaning the head unit's video output initialised successfully and it will accept frames. | `openauto/src/autoapp/Service/MediaSink/VideoMediaSinkService.cpp` L109-L118 |
| `openauto Config.max_unacked` | 1 | openauto's head-unit setup response advertises a flow-control window of exactly 1 unacknowledged media frame, i.e. it acks every frame before the next is expected. | `openauto/src/autoapp/Service/MediaSink/VideoMediaSinkService.cpp` L117-L118 |
| `openauto Config.configuration_indices` | [0] | Head unit accepts video configuration index 0, i.e. the first (and only) entry of the repeated video_configs list it advertised in ServiceDiscoveryResponse. | `openauto/src/autoapp/Service/MediaSink/VideoMediaSinkService.cpp` L118 |
| `aa-proxy-rs Config.configuration_indices` | [0] | Independent confirmation: aa-proxy-rs's synthesized READY Config also pushes a single configuration index 0. | `aa-proxy-rs/src/display.rs` L308-L316 |
| `AACS Start indication payload bytes` | 08 00 10 00 | AACS (phone side) hand-encodes the Start message as field 1 (session_id) varint 0 and field 2 (configuration_index) varint 0 — direct wire confirmation of Start's field numbering and that configuration_index 0 selects the first advertised video config. | `AACS/AAServer/src/VideoChannelHandler.cpp` L152-L161 |
| `AACS Setup request payload bytes` | 08 03 | AACS hand-encodes the Setup message as field 1 (type) varint 3 = MEDIA_CODEC_VIDEO_H264_BP — wire confirmation that Setup.type is field 1 and that the video channel negotiates H.264 Baseline. | `AACS/AAServer/src/VideoChannelHandler.cpp` L138-L145 |
| `Video frame timestamp width` | 8 bytes (uint64) | The timestamp header preceding the H.264 payload in a MEDIA_MESSAGE_DATA message is a 64-bit value. aasdk gates parsing on payload.size >= sizeof(Timestamp::ValueType) and offsets the media buffer by the same size. | `aasdk/src/Channel/MediaSink/Video/VideoMediaSinkService.cpp` L181-L192 |
| `Timestamp::ValueType` | uint64_t | aasdk's declared type for the media frame timestamp. | `aasdk/include/aasdk/Messenger/Timestamp.hpp` L26-L30 |
| `Video frame timestamp byte order` | big-endian (network order) | aasdk converts the 8 header bytes with boost::endian::big_to_native on receive and native_to_big on send. | `aasdk/src/Messenger/Timestamp.cpp` L29-L38 |
| `Video frame timestamp units` | microseconds | aa-proxy-rs names the parsed value pts_us and the constant TIMESTAMP_HEADER = 8, reading it with u64::from_be_bytes over the first 8 payload bytes. | `aa-proxy-rs/src/media_tap.rs` L1041-L1046 |
| `Video frame timestamp units (AACS sender confirmation)` | microseconds (GStreamer ns PTS / 1000) | AACS's phone-side encoder divides the GStreamer nanosecond PTS by 1000 before writing the 8-byte header, and its head-unit-side client multiplies the received value back by 1000 to get nanoseconds — both consistent with microsecond units. | `AACS/AAServer/src/VideoChannelHandler.cpp` L33-L39 |
| `Video DATA wire layout` | [2-byte msg id BE][8-byte pts_us BE][media bytes] | Explicit construction of a MEDIA_MESSAGE_DATA payload in aa-proxy-rs, capacity 2 + 8 + data. | `aa-proxy-rs/src/bt_sco_media_bridge.rs` L442-L445 |
| `MessageId size / byte order` | 2 bytes, big-endian | Every channel-specific message (including all video messages) is prefixed by a 2-byte big-endian message id. | `aasdk/include/aasdk/Messenger/MessageId.hpp` L34 |
| `MessageId big-endian encoding` | boost::endian::native_to_big | Confirms the 2-byte id is serialised network order; AACS's pushBackInt16 does the same by hand ((num >> 8) then (num >> 0)). | `aasdk/src/Messenger/MessageId.cpp` L30-L42 |
| `MessageType::SPECIFIC (aasdk)` | 0 | All video channel messages except the ChannelOpenResponse are sent with MessageType::SPECIFIC; the ChannelOpenResponse uses MessageType::CONTROL (= 1 << 2 = 4). | `aasdk/include/aasdk/Messenger/MessageType.hpp` L26-L29 |
| `VIDEO_FOCUS_PROJECTED` | 1 | VideoFocusMode: Android Auto projection owns the display. openauto replies with this after setup and on every focus request. | `aasdk/protobuf/aap_protobuf/service/media/video/message/VideoFocusMode.proto` L5-L11 |
| `VIDEO_FOCUS_NATIVE` | 2 | VideoFocusMode: head unit's own (native) UI owns the display. openauto treats a request with this mode as 'return to OS'. | `aasdk/protobuf/aap_protobuf/service/media/video/message/VideoFocusMode.proto` L8 |
| `VIDEO_FOCUS_NATIVE_TRANSIENT` | 3 | VideoFocusMode: native UI takes the display temporarily. | `aasdk/protobuf/aap_protobuf/service/media/video/message/VideoFocusMode.proto` L9 |
| `VIDEO_FOCUS_PROJECTED_NO_INPUT_FOCUS` | 4 | VideoFocusMode: projection is displayed but does not hold input focus. | `aasdk/protobuf/aap_protobuf/service/media/video/message/VideoFocusMode.proto` L10 |
| `VideoFocusReason.UNKNOWN / PHONE_SCREEN_OFF / LAUNCH_NATIVE` | 0 / 1 / 2 | Reason codes carried in VideoFocusRequestNotification field 3. | `aasdk/protobuf/aap_protobuf/service/media/video/message/VideoFocusReason.proto` L5-L10 |
| `DISPLAY_TYPE_MAIN / CLUSTER / AUXILIARY` | 0 / 1 / 2 | MediaSinkService.display_type (field 7) values distinguishing the main projection display from the instrument cluster and auxiliary displays. | `aasdk/protobuf/aap_protobuf/service/media/sink/message/DisplayType.proto` L5-L9 |
| `UI_THEME_AUTOMATIC / LIGHT / DARK` | 0 / 1 / 2 | UiConfig.ui_theme (field 4) values inside VideoConfiguration.ui_config. | `aasdk/protobuf/aap_protobuf/service/media/shared/message/UiTheme.proto` L5-L9 |
| `openauto default screen DPI (VideoConfiguration.density)` | 140 | openauto's default value written into VideoConfiguration.density (field 5). | `openauto/src/autoapp/Configuration/Configuration.cpp` L127-L132 |
| `openauto default video margins` | width 0, height 0 | openauto defaults VideoConfiguration.width_margin and height_margin to 0. | `openauto/src/autoapp/Configuration/Configuration.cpp` L181-L185 |
| `aa-proxy-rs default density (DPI)` | 160 | Default VideoConfiguration.density used by aa-proxy-rs when synthesising an injected display's video config. | `aa-proxy-rs/src/inject_displays.rs` L16-L30 |
| `aa-proxy-rs default viewing_distance` | 300 | Default VideoConfiguration.viewing_distance (field 7) value used by aa-proxy-rs. | `aa-proxy-rs/src/inject_displays.rs` L28-L30 |
| `pixel_aspect_ratio_e4 for square pixels` | 10000 | Field 8 is the pixel aspect ratio scaled by 1e4; aa-proxy-rs sets 10000 (= 1.0000) for square pixels. Same code sets decoder_additional_depth to 0. | `aa-proxy-rs/src/display.rs` L192-L203 |
| `width_margin / height_margin relationship to UiConfig.margins insets` | width_margin = margins.left + margins.right; height_margin = margins.top + margins.bottom | aa-proxy-rs derives the scalar VideoConfiguration margin fields (3 and 4) by summing the opposing edges of the UiConfig.margins Insets — i.e. they are total margins, not per-edge. | `aa-proxy-rs/src/sdr_ui.rs` L675-L687 |
| `UiConfig.margins split from scalar margins` | top = bottom = height_margin / 2; left = right = width_margin / 2 | The inverse mapping, in aa-proxy-rs's display-service builder: the scalar margins are split evenly across opposing edges. | `aa-proxy-rs/src/display.rs` L180-L183 |
| `openauto Ack.ack value` | 1 | openauto acknowledges each received video frame with Ack { session_id = <session from Start>, ack = 1 }, sent immediately after handing the frame to the video output. | `openauto/src/autoapp/Service/MediaSink/VideoMediaSinkService.cpp` L177-L186 |
| `openauto session_id source` | Start.session_id | The session id echoed in every Ack is taken verbatim from the Start indication; openauto initialises it to -1 before Start arrives. | `openauto/src/autoapp/Service/MediaSink/VideoMediaSinkService.cpp` L151-L159 |
| `CODEC_CONFIG treated as timestamp-0 media` | timestamp 0 | openauto funnels MEDIA_MESSAGE_CODEC_CONFIG (which has no timestamp header) into the same write path with timestamp 0. aa-proxy-rs likewise broadcasts codec config with pts 0 and consumers skip pts==0 items as non-frames. | `openauto/src/autoapp/Service/MediaSink/VideoMediaSinkService.cpp` L190-L193 |
| `H.264 stream format on the wire` | Annex-B byte-stream | AACS's phone-side encoder pins the caps to video/x-h264, stream-format=byte-stream, profile=baseline; the head-unit-side appsrc declares the identical byte-stream caps. aa-proxy-rs's IDR detector likewise scans for 3- or 4-byte Annex-B start codes. | `AACS/AAServer/src/VideoChannelHandler.cpp` L75-L78 |
| `AACS encoder key-int-max (GOP / keyframe interval)` | 25 | AACS configures x264enc with key-int-max=25, i.e. an IDR at least every 25 frames (~0.83 s at 30 fps). This is the only explicit keyframe-cadence setting in any reference; no reference states a protocol-mandated keyframe requirement. | `AACS/AAServer/src/VideoChannelHandler.cpp` L73-L74 |
| `H.264 IDR NAL unit type` | 5 (nal_type = byte & 0x1F); non-IDR slice = 1 | aa-proxy-rs identifies keyframes in the video payload by walking Annex-B start codes and testing nal_type == 5 (IDR) vs 1 (non-IDR coded slice). | `aa-proxy-rs/src/media_tap.rs` L884-L905 |
| `Head unit H.264 decoder compression format (Raspberry Pi OMX)` | OMX_VIDEO_CodingAVC | openauto's OMX video output configures the hardware decoder input port for AVC/H.264 — no profile or level is specified anywhere in openauto. | `openauto/src/autoapp/Projection/OMXVideoOutput.cpp` L267-L275 |
| `AACS MediaMessageType.MediaWithTimestampIndication` | 0x0000 | AACS's independent name for MEDIA_MESSAGE_DATA — explicitly stating that message id 0 carries a timestamp. Its sibling MediaIndication = 0x0001 (= MEDIA_MESSAGE_CODEC_CONFIG in aasdk) carries none. | `AACS/include/enums.h` L39-L47 |
| `AACS pushBackInt64 (timestamp serialiser)` | MSB-first, 8 bytes | Independent confirmation of big-endian 64-bit timestamp encoding on the wire (and bytesToUInt64 does the mirror decode). | `AACS/src/utils.cpp` L39-L48 |
| `AACS VideoResolution.Enum` | None=0, H480=1, H720=2, H1080=3 | AACS's legacy resolution enum. Values 1/2/3 line up with aasdk's VIDEO_800x480 / VIDEO_1280x720 / VIDEO_1920x1080, but AACS adds a None=0 and names them by height only. | `AACS/proto/VideoResolution.proto` L7-L15 |
| `AACS VideoFps.Enum` | None=0, F30=1, F60=2 | AACS's legacy frame-rate enum. CONTRADICTS aasdk/aa-proxy-rs, which have VIDEO_FPS_60 = 1 and VIDEO_FPS_30 = 2. Use aasdk. | `AACS/proto/VideoFps.proto` L7-L15 |
| `ChannelId::MEDIA_SINK_VIDEO (openauto static assignment)` | 3 (implicit ordinal — 4th entry of an enum with no explicit initialisers, CONTROL=0) | openauto uses a static channel-id table and advertises the video service under static_cast<uint32_t>(ChannelId::MEDIA_SINK_VIDEO). aasdk's own TODO in the same header states real AA channel ids are dynamic, so this number is an openauto convention, NOT a protocol constant. | `aasdk/include/aasdk/Messenger/ChannelId.hpp` L29-L38 |

### 5.3 Message definitions


#### `VideoConfiguration (canonical, aasdk)`

Source: `aasdk/protobuf/aap_protobuf/service/media/sink/message/VideoConfiguration.proto`

```proto
syntax="proto2";

import "aap_protobuf/service/media/shared/message/MediaCodecType.proto";
import "aap_protobuf/service/media/sink/message/VideoFrameRateType.proto";
import "aap_protobuf/service/media/sink/message/VideoCodecResolutionType.proto";
import "aap_protobuf/service/media/shared/message/UiConfig.proto";

package aap_protobuf.service.media.sink.message;

message VideoConfiguration {
    optional VideoCodecResolutionType codec_resolution = 1;
    optional VideoFrameRateType frame_rate = 2;
    optional uint32 width_margin = 3;
    optional uint32 height_margin = 4;
    optional uint32 density = 5;
    optional uint32 decoder_additional_depth = 6;
    optional uint32 viewing_distance = 7;
    optional uint32 pixel_aspect_ratio_e4 = 8;
    optional uint32 real_density = 9;
    optional shared.message.MediaCodecType video_codec_type = 10;
    optional shared.message.UiConfig ui_config = 11;
}
```

#### `VideoCodecResolutionType (enum)`

Source: `aasdk/protobuf/aap_protobuf/service/media/sink/message/VideoCodecResolutionType.proto`

```proto
enum VideoCodecResolutionType {
  VIDEO_800x480 = 1;
  VIDEO_1280x720 = 2;
  VIDEO_1920x1080 = 3;
  VIDEO_2560x1440 = 4;
  VIDEO_3840x2160 = 5;
  VIDEO_720x1280 = 6;
  VIDEO_1080x1920 = 7;
  VIDEO_1440x2560 = 8;
  VIDEO_2160x3840 = 9;
}
```

#### `VideoFrameRateType (enum)`

Source: `aasdk/protobuf/aap_protobuf/service/media/sink/message/VideoFrameRateType.proto`

```proto
enum VideoFrameRateType {
    VIDEO_FPS_60 = 1;
    VIDEO_FPS_30 = 2;
}
```

#### `MediaCodecType (enum)`

Source: `aasdk/protobuf/aap_protobuf/service/media/shared/message/MediaCodecType.proto`

```proto
enum MediaCodecType
{
    MEDIA_CODEC_AUDIO_PCM = 1;
    MEDIA_CODEC_AUDIO_AAC_LC = 2;
    MEDIA_CODEC_VIDEO_H264_BP = 3;
    MEDIA_CODEC_AUDIO_AAC_LC_ADTS = 4;
    MEDIA_CODEC_VIDEO_VP9 = 5;
    MEDIA_CODEC_VIDEO_AV1 = 6;
    MEDIA_CODEC_VIDEO_H265 = 7;
}
```

#### `MediaMessageId (enum)`

Source: `aasdk/protobuf/aap_protobuf/service/media/sink/MediaMessageId.proto`

```proto
enum MediaMessageId
{
    MEDIA_MESSAGE_DATA = 0;
    MEDIA_MESSAGE_CODEC_CONFIG = 1;
    MEDIA_MESSAGE_SETUP = 32768;
    MEDIA_MESSAGE_START = 32769;
    MEDIA_MESSAGE_STOP = 32770;
    MEDIA_MESSAGE_CONFIG = 32771;
    MEDIA_MESSAGE_ACK = 32772;
    MEDIA_MESSAGE_MICROPHONE_REQUEST = 32773;
    MEDIA_MESSAGE_MICROPHONE_RESPONSE = 32774;
    MEDIA_MESSAGE_VIDEO_FOCUS_REQUEST = 32775;
    MEDIA_MESSAGE_VIDEO_FOCUS_NOTIFICATION = 32776;
    MEDIA_MESSAGE_UPDATE_UI_CONFIG_REQUEST = 32777;
    MEDIA_MESSAGE_UPDATE_UI_CONFIG_REPLY = 32778;
    MEDIA_MESSAGE_AUDIO_UNDERFLOW_NOTIFICATION = 32779;
}
```

#### `MediaSinkService (the ServiceDiscoveryResponse entry that advertises video_configs)`

Source: `aasdk/protobuf/aap_protobuf/service/media/sink/MediaSinkService.proto`

```proto
message MediaSinkService
{
    optional shared.message.MediaCodecType available_type = 1 [default = MEDIA_CODEC_AUDIO_PCM];;
    optional message.AudioStreamType audio_type = 2;
    repeated shared.message.AudioConfiguration audio_configs = 3;
    repeated message.VideoConfiguration video_configs = 4;
    optional bool available_while_in_call = 5;
    optional uint32 display_id = 6;
    optional message.DisplayType display_type = 7;
    optional message.KeyCode initial_content_keycode = 8;
}
```

#### `Setup (== AVChannelSetupRequest, msg id 0x8000)`

Source: `aasdk/protobuf/aap_protobuf/service/media/shared/message/Setup.proto`

```proto
message Setup
{
    required service.media.shared.message.MediaCodecType type = 1;
}
```

#### `Config (== AVChannelSetupResponse, msg id 0x8003)`

Source: `aasdk/protobuf/aap_protobuf/service/media/shared/message/Config.proto`

```proto
message Config {
  required Status status = 1;
  enum Status {
    STATUS_WAIT = 1;
    STATUS_READY = 2;
  }

  optional uint32 max_unacked = 2;
  repeated uint32 configuration_indices = 3;
}
```

#### `Start (== AVChannelStartIndication, msg id 0x8001)`

Source: `aasdk/protobuf/aap_protobuf/service/media/shared/message/Start.proto`

```proto
message Start {
    required int32 session_id = 1;
    required uint32 configuration_index = 2;
}
```

#### `Stop (msg id 0x8002)`

Source: `aasdk/protobuf/aap_protobuf/service/media/shared/message/Stop.proto`

```proto
message Stop
{

}
```

#### `Ack (== AVMediaAckIndication, msg id 0x8004)`

Source: `aasdk/protobuf/aap_protobuf/service/media/source/message/Ack.proto`

```proto
message Ack {
  required int32 session_id = 1;
  optional uint32 ack = 2;
  repeated uint64 receive_timestamp_ns = 3;
}
```

#### `UiConfig (VideoConfiguration field 11)`

Source: `aasdk/protobuf/aap_protobuf/service/media/shared/message/UiConfig.proto`

```proto
message UiConfig {
  optional Insets margins = 1;
  optional Insets content_insets = 2;
  optional Insets stable_content_insets = 3;
  optional UiTheme ui_theme = 4;
}
```

#### `Insets`

Source: `aasdk/protobuf/aap_protobuf/service/media/shared/message/Insets.proto`

```proto
message Insets {
  optional uint32 top = 1;
  optional uint32 bottom = 2;
  optional uint32 left = 3;
  optional uint32 right = 4;
}
```

#### `UiTheme (enum)`

Source: `aasdk/protobuf/aap_protobuf/service/media/shared/message/UiTheme.proto`

```proto
enum UiTheme {
  UI_THEME_AUTOMATIC = 0;
  UI_THEME_LIGHT = 1;
  UI_THEME_DARK = 2;
}
```

#### `DisplayType (enum)`

Source: `aasdk/protobuf/aap_protobuf/service/media/sink/message/DisplayType.proto`

```proto
enum DisplayType {
  DISPLAY_TYPE_MAIN = 0;
  DISPLAY_TYPE_CLUSTER = 1;
  DISPLAY_TYPE_AUXILIARY = 2;
}
```

#### `VideoFocusRequestNotification (msg id 0x8007, phone -> HU)`

Source: `aasdk/protobuf/aap_protobuf/service/media/video/message/VideoFocusRequestNotification.proto`

```proto
message VideoFocusRequestNotification {
    optional int32 disp_channel_id = 1 [deprecated = true];
    optional VideoFocusMode mode = 2;
    optional VideoFocusReason reason = 3;
}
```

#### `VideoFocusNotification (msg id 0x8008, HU -> phone)`

Source: `aasdk/protobuf/aap_protobuf/service/media/video/message/VideoFocusNotification.proto`

```proto
message VideoFocusNotification {
    optional VideoFocusMode focus = 1;
    optional bool unsolicited = 2;
}
```

#### `VideoFocusMode (enum)`

Source: `aasdk/protobuf/aap_protobuf/service/media/video/message/VideoFocusMode.proto`

```proto
enum VideoFocusMode
{
    VIDEO_FOCUS_PROJECTED = 1;
    VIDEO_FOCUS_NATIVE = 2;
    VIDEO_FOCUS_NATIVE_TRANSIENT = 3;
    VIDEO_FOCUS_PROJECTED_NO_INPUT_FOCUS = 4;
}
```

#### `VideoFocusReason (enum)`

Source: `aasdk/protobuf/aap_protobuf/service/media/video/message/VideoFocusReason.proto`

```proto
enum VideoFocusReason
{
    UNKNOWN = 0;
    PHONE_SCREEN_OFF = 1;
    LAUNCH_NATIVE = 2;
}
```

#### `GalVerificationVideoFocus (control channel, GAL verification)`

Source: `aasdk/protobuf/aap_protobuf/channel/control/GalVerificationVideoFocus.proto`

```proto
message GalVerificationVideoFocus {
  required media.video.message.VideoFocusMode video_focus_mode = 1;
  optional bool deny = 2;
  optional bool unsolicited = 3;
}
```

#### `AdditionalVideoConfig (aa-proxy-rs ONLY — claimed real wire schema for VideoConfiguration field 11)`

Source: `aa-proxy-rs/src/protos/protos.proto`

```proto
// Real wire schema for VideoConfiguration field 11 / UpdateUiConfigRequest field 1
// (APK class wcb/wcm), per open-android-auto deep-trace verification. Distinct
// from the legacy UiConfig above: fields 1-3 are resolution ranges, not insets;
// margins/insets live in margin_configs (field 7).
message AdditionalVideoConfig {
  optional VideoResolutionRange min_resolution = 1;
  optional VideoResolutionRange max_resolution = 2;
  optional VideoResolutionRange preferred_resolution = 3;
  optional UiTheme ui_theme = 4;
  repeated UIElement hidden_ui_elements = 5;
  repeated VideoResizeAction resize_actions = 6;
  repeated VideoMarginConfig margin_configs = 7;
}
```

#### `VideoResolutionRange / VideoResizeAction / VideoMarginConfig / VideoInsets / UIElement / ResizeActionType (aa-proxy-rs ONLY)`

Source: `aa-proxy-rs/src/protos/protos.proto`

```proto
message VideoResolutionRange {
  optional uint32 width = 1;
  optional uint32 height = 2;
  optional uint32 density = 3;
  optional uint32 fps = 4;
}

enum UIElement {
  UI_ELEMENT_UNKNOWN = 0;
  UI_ELEMENT_CLOCK = 1;
  UI_ELEMENT_BATTERY_LEVEL = 2;
  UI_ELEMENT_PHONE_SIGNAL = 3;
  UI_ELEMENT_NATIVE_UI_AFFORDANCE = 4;
  UI_ELEMENT_NAVIGATION_TURN_DATA_AVAILABLE = 5;
}

enum ResizeActionType {
  ACTION_UNKNOWN = 0;
  ACTION_RESIZE_TO_SMALLER = 1;
  ACTION_RESIZE_TO_LARGER = 2;
}

message VideoResizeAction {
  optional ResizeActionType action = 1;
}

message VideoMarginConfig {
  optional VideoInsets insets = 1;
}

message VideoInsets {
  optional uint32 left = 1;
  optional uint32 top = 2;
  optional uint32 right = 3;
  optional uint32 bottom = 4;
}
```

#### `UpdateUiConfigRequest / UpdateUiConfigReply (aa-proxy-rs ONLY)`

Source: `aa-proxy-rs/src/protos/protos.proto`

```proto
// Bidirectional runtime UI config push (APK class wci). Wire ID is 0x8009
// inbound (Phone->HU) and 0x800A outbound (HU->Phone) -- same message, same
// schema, direction-dependent ID. Field 1 is required by the phone; missing
// it triggers PROTOCOL_WRONG_MESSAGE / INVALID_UI_CONFIG.
message UpdateUiConfigRequest { optional AdditionalVideoConfig config = 1; }

message UpdateUiConfigReply { optional UiConfig ui_config = 1; }
```

#### `VideoConfig (AACS legacy variant of VideoConfiguration)`

Source: `AACS/proto/VideoConfig.proto`

```proto
message VideoConfig
{
    required VideoResolution.Enum video_resolution = 1;
    required VideoFps.Enum video_fps = 2;
    required uint32 margin_width = 3;
    required uint32 margin_height = 4;
    required uint32 dpi = 5;
    optional uint32 additional_depth = 6;
}
```

#### `MediaChannel (AACS legacy variant of MediaSinkService)`

Source: `AACS/proto/MediaChannel.proto`

```proto
message MediaChannel
{
    required MediaStreamType.Enum media_type = 1;
    optional AudioType.Enum audio_type = 2;
    repeated AudioConfig audio_configs = 3;
    repeated VideoConfig video_configs = 4;
}
```

#### `MediaChannelSetupResponse (AACS legacy, un-decoded variant of Config)`

Source: `AACS/proto/MediaChannelSetupResponse.proto`

```proto
message MediaChannelSetupResponse
{
    required uint32 unknown_field_1 = 1;
    required uint32 unknown_field_2 = 2;
    required uint32 unknown_field_3 = 3;
}
```

### 5.4 Where the references disagree


**Frame rate enum values REVERSED between AACS and aasdk/aa-proxy-rs**

aasdk (aasdk/protobuf/aap_protobuf/service/media/sink/message/VideoFrameRateType.proto L5-L8) and aa-proxy-rs (src/protos/protos.proto L1387-L1390) both define VIDEO_FPS_60 = 1 and VIDEO_FPS_30 = 2. AACS (AACS/proto/VideoFps.proto L9-L14) defines None = 0, F30 = 1, F60 = 2 — the 30/60 assignment is swapped. Two independent references agree against AACS; per the rules, USE aasdk: 60 fps = 1, 30 fps = 2. Getting this backwards silently negotiates the wrong frame rate.

**AACS VideoConfig omits fields 7-11 and uses `required` where aasdk uses `optional`**

AACS/proto/VideoConfig.proto L10-L18 stops at field 6 (additional_depth) and marks fields 1-5 required. aasdk's VideoConfiguration.proto L10-L22 has all fields optional and adds viewing_distance = 7, pixel_aspect_ratio_e4 = 8, real_density = 9, video_codec_type = 10, ui_config = 11. Field NUMBERS 1-6 line up, but the names differ: margin_width/margin_height (AACS) vs width_margin/height_margin (aasdk); dpi (AACS) vs density (aasdk); additional_depth (AACS) vs decoder_additional_depth (aasdk). Use aasdk.

**AACS VideoResolution adds a None = 0 value**

AACS/proto/VideoResolution.proto L11 defines None = 0. aasdk's VideoCodecResolutionType (L5-L15) has no zero value and starts at 1. Values 1/2/3 agree in meaning (480p/720p/1080p heights), but AACS names them by height only (H480/H720/H1080) and has no entries for the 1440p/2160p or portrait resolutions 4-9.

**AACS MediaChannelSetupResponse is an un-decoded stand-in for Config**

AACS/proto/MediaChannelSetupResponse.proto L7-L13 declares three required uint32 fields named unknown_field_1/2/3. These are field numbers 1/2/3, which map onto aasdk's Config { status = 1, max_unacked = 2, configuration_indices = 3 }. AACS never actually parses it (AAServer/src/VideoChannelHandler.cpp L178-L180 only checks the message id). Use aasdk's Config — note field 3 is `repeated`, not a scalar uint32 as AACS's placeholder implies.

**aasdk's MediaMessageId enum is TRUNCATED relative to aa-proxy-rs**

aasdk (MediaMessageId.proto L5-L21) stops at MEDIA_MESSAGE_AUDIO_UNDERFLOW_NOTIFICATION = 32779 (0x800B). aa-proxy-rs (src/protos/protos.proto L1577-L1602, corroborated by its own name table at src/mitm.rs L816-L844) continues to 32789 (0x8015) with ACTION_TAKEN, four INTEGRATED_OVERLAY ids, UPDATE_HU_UI_CONFIG request/response, MEDIA_STATS, MEDIA_OPTIONS and CRITICAL_UI_NOTIFICATION. This is an extension, not a contradiction — the 0-32779 range is identical in both. Values 32780+ are unverified by aasdk.

**VideoConfiguration field 11 semantics disputed: UiConfig vs AdditionalVideoConfig**

aasdk declares field 11 as shared.message.UiConfig { margins=1, content_insets=2, stable_content_insets=3, ui_theme=4 } (VideoConfiguration.proto L21, UiConfig.proto L8-L13). aa-proxy-rs ships BOTH: the same legacy UiConfig (protos.proto L128-L133) AND an AdditionalVideoConfig (protos.proto L142-L154) with a comment asserting AdditionalVideoConfig is the "Real wire schema for VideoConfiguration field 11 / UpdateUiConfigRequest field 1 (APK class wcb/wcm), per open-android-auto deep-trace verification" where fields 1-3 are VideoResolutionRange, not Insets. Critically, aa-proxy-rs's OWN production code still writes UiConfig into field 11 (src/display.rs L185-L203, src/sdr_ui.rs L640-L687), so the AdditionalVideoConfig claim is unexercised in that codebase. Per the rules, use aasdk's UiConfig for field 11 and treat AdditionalVideoConfig as an unconfirmed alternative.

**width_margin/height_margin semantics are not stated in any .proto**

No proto documents whether width_margin is per-edge or total. aa-proxy-rs is the only reference that resolves it, and it treats them as TOTALS: width_margin = margins.left + margins.right, height_margin = margins.top + margins.bottom (src/sdr_ui.rs L676-L677), with the inverse split top=bottom=height_margin/2, left=right=width_margin/2 (src/display.rs L180-L183). openauto just copies a QRect's width()/height() straight through with no interpretation (src/autoapp/Service/MediaSink/VideoMediaSinkService.cpp L89-L91). Unverified by aasdk.

**Message-type flag polarity inverted between aasdk and AACS**

aasdk defines MessageType { SPECIFIC = 0, CONTROL = 1 << 2 } (include/aasdk/Messenger/MessageType.hpp L26-L29). AACS defines MessageTypeFlags { Control = 0, Specific = 1 << 2 } (AACS/include/enums.h L16-L19). These directly contradict. This is a framing-layer concern rather than a video-channel one, but it changes the flag byte on every video message sent. Not resolved within this domain — flag bit 2 must be cross-checked against the framing/transport extraction.

**Video channel ID is not a protocol constant**

openauto advertises the video service with service->set_id(static_cast<uint32_t>(channel_->getId())) where getId() returns ChannelId::MEDIA_SINK_VIDEO (openauto/src/autoapp/Service/MediaSink/VideoMediaSinkService.cpp L76, aasdk/src/Channel/MediaSink/Video/Channel/VideoChannel.cpp L24). ChannelId is an unvalued C++ enum class whose ordering makes MEDIA_SINK_VIDEO the 4th entry (= 3), but aasdk's own TODO comment at include/aasdk/Messenger/ChannelId.hpp L22-L27 states: "In AA, Channel Id's are dynamic. We use ChannelId here for a static implementation." A clean-room implementation must NOT hardcode 3; the channel id is whatever the head unit assigns in ServiceDiscoveryResponse. AACS confirms this by passing channelId in as a runtime constructor argument (AAServer/src/VideoChannelHandler.cpp L57).

**Timestamp units are asserted, not specified — and one reference hedges**

Microseconds is supported by aa-proxy-rs naming the variable pts_us (src/media_tap.rs L1045) and by AACS dividing GStreamer's ns PTS by 1000 on send and multiplying by 1000 on receive (AAServer L38, AAClient L48). BUT aa-proxy-rs's own MPEG-TS muxer explicitly refuses to trust it: "The unit is inferred from early frame deltas so the muxer can handle ns/us/ms/90k timestamps without hard-coding a scale" (src/mpegts.rs L322-L329), defaulting to Microseconds only when inference fails (L343-L345). aasdk stores the value as an opaque uint64 and openauto's Qt output discards it entirely (src/autoapp/Projection/QtVideoOutput.cpp L80, unnamed first parameter). Microseconds is the best-supported reading; a decoder should tolerate drift.

**No H.264 level is stated anywhere; keyframe requirement is inferred only**

MEDIA_CODEC_VIDEO_H264_BP names the Baseline Profile and AACS's encoder caps say profile=baseline (AAServer/src/VideoChannelHandler.cpp L76-L77), but NO reference in this tree states an H.264 level (no level_idc, no "Level 3.1" etc.). Likewise no reference states a protocol keyframe requirement: AACS's key-int-max=25 (L74) is one implementation's encoder choice, and aa-proxy-rs's IDR handling (src/media_tap.rs L1057-L1082, src/mitm.rs L2644-L2669) only shows that a decoder must wait for an IDR to start, implying but not proving that the phone emits one at session start and after each focus regain.

**Empty aasdk_proto directory — no legacy VideoConfig protos in this checkout**

The task brief asks to compare aasdk/aasdk_proto/ against aasdk/protobuf/aap_protobuf/. In this checkout aasdk/aasdk_proto/ contains ONLY five Wifi protos (WifiChannelMessageIdsEnum, WifiInfoRequestMessage, WifiInfoResponseMessage, WifiSecurityRequestMessage, WifiSecurityResponseMessage). There is no VideoConfig*, AVChannel*, or MediaMessageId proto there, so no aasdk-internal comparison was possible. Similarly, no file anywhere under references/ defines a message literally named AVChannelSetupRequest / AVChannelSetupResponse / AVChannelStartIndication — those legacy aasdk names survive only as C++ method names (aasdk/src/Channel/MediaSource/MediaSourceService.cpp L84, L128 handleAVChannelSetupRequest) and now map onto Setup / Config / Start respectively.

---

## 6. Input channel

Touch, key and rotary events, which the phone RECEIVES from the head unit. Not yet implemented -- Phase 3.

### 6.1 Sequence

```text
INPUT CHANNEL LIFECYCLE (phone is the receiver of input; head unit is the InputSource).

STEP 1 - Head unit advertises the input channel in ServiceDiscoveryResponse.
The HU adds a Service entry whose field 4 (input_source_service) is an InputSourceService descriptor.
It carries: keycodes_supported (field 1, packed repeated int32 - the raw KeyCode numbers the HU can generate), zero or more TouchScreen sub-messages (field 2: required width=1, required height=2, optional TouchScreenType type=3, optional bool is_secondary=4), zero or more TouchPad sub-messages (field 3), feedback_events_supported (field 4), and display_id (field 5).
Cite: aasdk/protobuf/aap_protobuf/service/Service.proto L26; aasdk/protobuf/aap_protobuf/service/inputsource/InputSourceService.proto L8-L33.
Concrete producer: openauto/src/autoapp/Service/InputSource/InputSourceService.cpp L62-L84 - it does `service->set_id(channel_->getId())`, `auto *inputChannel = service->mutable_input_source_service()`, loops `inputChannel->add_keycodes_supported(buttonCode)` over the configured button codes, then if `inputDevice_->hasTouchscreen()` adds one touchscreen with only width/height set (type and is_secondary left unset).
Second concrete producer: aa-proxy-rs/src/display.rs L222-L246 - sets keycodes_supported to [19,20,21,22,23] for a cluster display or [3,4,5,6,84,85,87,88,126,127,65537,65538,65540] for an auxiliary display, sets touchscreen width/height, `set_type(TouchScreenType::RESISTIVE)`, `set_is_secondary(...)`, and `set_display_id(...)`.
Note: it is the HEAD UNIT that advertises keycodes_supported. The phone does not advertise keycodes; it selects from what the HU offered.

STEP 2 - Phone opens the channel.
Phone sends ControlMessageType::MESSAGE_CHANNEL_OPEN_REQUEST (ChannelOpenRequest{service_id, priority}) on the input channel. The HU replies MESSAGE_CHANNEL_OPEN_RESPONSE with MessageStatus STATUS_SUCCESS (=0), sent as EncryptionType::ENCRYPTED + MessageType::CONTROL.
Cite: aasdk/src/Channel/InputSource/InputSourceService.cpp L70-L81 and L115-L124; openauto/src/autoapp/Service/InputSource/InputSourceService.cpp L86-L100.

STEP 3 - Phone sends KeyBindingRequest (message id 32770 / 0x8002).
Payload: KeyBindingRequest { repeated int32 keycodes = 1 [packed=true] }. This is how the phone declares WHICH keycodes it wants delivered. AACS's phone side does exactly this, echoing back the button list the HU advertised: AACS/AAServer/src/InputChannelHandler.cpp L22-L38 (`pushBackInt16(plainMsg, InputChannelMessageType::HandshakeRequest)` where HandshakeRequest = 0x8002, then `handshakeRequest.add_available_buttons(...)` for each of `available_buttons` taken from `ch.input_channel().available_buttons()` in AACS/AAServer/src/AaCommunicator.cpp L119-L124).

STEP 4 - Head unit validates and replies KeyBindingResponse (message id 32771 / 0x8003).
Payload: KeyBindingResponse { required int32 status = 1 }. openauto walks every requested keycode and, if any is absent from its supported list, sets status = MessageStatus::STATUS_KEYCODE_NOT_BOUND (-18) and breaks; otherwise STATUS_SUCCESS (0) and it starts the input device.
Cite: openauto/src/autoapp/Service/InputSource/InputSourceService.cpp L102-L132; aasdk/src/Channel/InputSource/InputSourceService.cpp L57-L68 (framing: ENCRYPTED + SPECIFIC).
AACS's HU-side stub answers 0x8003 with the raw 2 bytes 0x08 0x00 = field 1 varint 0 = STATUS_SUCCESS: AACS/AAClient/src/InputChannelHandler.cpp L50-L59.

STEP 5 - Head unit streams InputReport messages (message id 32769 / 0x8001) for the rest of the session.
Wire form: 2-byte big-endian message id 0x8001 followed by the serialized InputReport; flags ENCRYPTED | FRAME_TYPE_FIRST | FRAME_TYPE_LAST (aa-proxy-rs/src/mitm.rs L3574-L3592), or in aasdk terms EncryptionType::ENCRYPTED + MessageType::SPECIFIC (aasdk/src/Channel/InputSource/InputSourceService.cpp L43-L55).
InputReport always carries `required uint64 timestamp = 1` plus exactly one of the event sub-messages:
  - touch_event (3) / touchpad_event (7): TouchEvent{ repeated Pointer pointer_data = 1 {x=1, y=2, pointer_id=3}; optional uint32 action_index = 2; optional PointerAction action = 3 }.
  - key_event (4): KeyEvent{ repeated Key keys = 1 { keycode=1, down=2, metastate=3, longpress=4 } }.
  - absolute_event (5): AbsoluteEvent{ repeated Abs data = 1 { keycode=1, value=2 } }.
  - relative_event (6): RelativeEvent{ repeated Rel data = 1 { keycode=1, delta=2 } }.
  - disp_channel_id (2) is deprecated.

STEP 5a - Touch semantics.
`action` is a PointerAction: ACTION_DOWN(0) first finger down, ACTION_UP(1) last finger up, ACTION_MOVED(2) drag, ACTION_POINTER_DOWN(5) an extra finger went down, ACTION_POINTER_UP(6) one of several fingers lifted. `action_index` is the index (into pointer_data) of the pointer whose state changed; it is 0 for DOWN/UP/MOVED and the changed-pointer index for POINTER_DOWN/POINTER_UP.
Reference multi-touch producer: openauto/src/autoapp/Projection/InputDevice.cpp L257-L377 - QEvent::TouchBegin -> ACTION_DOWN with actionIndex 0; TouchEnd -> ACTION_UP with actionIndex = index of released point; TouchUpdate with a newly pressed point -> ACTION_POINTER_DOWN + that index; TouchUpdate with a released point -> ACTION_POINTER_UP + that index; otherwise ACTION_MOVED with actionIndex 0; TouchCancel is mapped to ACTION_UP because "Android Auto protocol doesn't support ACTION_CANCEL" (L333-L340).
pointer_id is a small sequential id assigned per finger and reused across the gesture (openauto InputDevice.cpp L379-L398, `touchPointIdMap_`). Coordinates are scaled from the physical touchscreen geometry into the projected display geometry: `x = (pos.x() / touchscreenGeometry_.width()) * displayGeometry_.width()` (same file L391-L392), i.e. x/y are in the projected video's pixel space, not the panel's.
Every pointer currently down is sent in pointer_data on every report; points in the Released state are skipped unless the action is ACTION_UP/ACTION_POINTER_UP (openauto InputDevice.cpp L347-L366).

STEP 5b - Button semantics.
A button press becomes key_event with one Key: keycode = the KeyCode value, down = true on press / false on release, metastate = 0, longpress = false in the openauto and aa-proxy-rs producers. openauto/src/autoapp/Service/InputSource/InputSourceService.cpp L157-L163; aa-proxy-rs/src/mitm.rs L3561-L3565.
Press and release are separate InputReports; aa-proxy-rs's injector sends DOWN then UP as two packets with the SAME timestamp (mitm.rs L3554-L3603).

STEP 5c - Rotary semantics.
KEYCODE_ROTARY_CONTROLLER (65536) is NOT delivered as a key_event. It is delivered as relative_event with one Rel { keycode = 65536, delta = signed step count }. openauto sends delta -1 for a left/counter-clockwise detent and +1 otherwise (InputSourceService.cpp L153-L156); aa-proxy-rs documents "absolute value of 1 = single UI step, scales linearly", positive = clockwise (mitm.rs L3608-L3619). openauto additionally only emits the rotary event on key RELEASE, to avoid doubling (InputDevice.cpp L191-L194).

STEP 6 - Timestamp.
`required uint64 timestamp = 1`. No unit is stated in any .proto. Implementations use MICROSECONDS since the epoch: openauto uses `std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::high_resolution_clock::now().time_since_epoch()).count()` (InputSourceService.cpp L145-L151 for buttons, L177-L183 for touch); aa-proxy-rs uses `SystemTime::now().duration_since(UNIX_EPOCH).as_micros() as u64` in send_key_event (mitm.rs L3554-L3557) and send_rotary_event (mitm.rs L3612-L3615). One aa-proxy-rs helper (send_input_key, mitm.rs L3655) uses milliseconds instead - see discrepancies.

STEP 7 - InputFeedback (message id 32772 / 0x8004).
InputFeedback { optional FeedbackEvent event = 1 } with FeedbackEvent in {FEEDBACK_SELECT=1, FEEDBACK_FOCUS_CHANGE=2, FEEDBACK_DRAG_SELECT=3, FEEDBACK_DRAG_START=4, FEEDBACK_DRAG_END=5}. The HU declares which of these it understands via InputSourceService.feedback_events_supported (field 4). No reference here sends or handles this message; only aa-proxy-rs's packet pretty-printer decodes it (aa-proxy-rs/src/mitm_prettyprint.rs L949-L959). Direction is therefore unverified in these sources.

SECONDARY INPUT PATHS (also HU -> phone, not on the input channel):
- PhoneStatus channel, PHONE_STATUS_INPUT = 32770: PhoneStatusInput { InstrumentClusterInput input = 1; string caller_number = 2; string caller_id = 3 }.
- MediaBrowser channel, MEDIA_BROWSE_INPUT = 32774: MediaBrowserInput { InstrumentClusterInput input = 1; string path = 2 }.
InstrumentClusterInput carries a single required InstrumentClusterAction in {UNKNOWN=0, UP=1, DOWN=2, LEFT=3, RIGHT=4, ENTER=5, BACK=6, CALL=7}. These are the rotary-controller / cluster-knob navigation actions for cars without a touchscreen.
- Control channel, GAL_VERIFICATION_INJECT_INPUT: GalVerificationInjectInput { required InputReport input = 1 } - a certification-harness path for injecting a synthetic InputReport.

CHANNEL DISPATCH ON THE RECEIVER SIDE.
A phone implementing this channel must switch on the 2-byte big-endian message id at the head of the payload and route: 32770 -> KeyBindingRequest, 32771 -> KeyBindingResponse, 32769 -> InputReport, 32772 -> InputFeedback, plus the control ids MESSAGE_CHANNEL_OPEN_REQUEST/RESPONSE which also arrive on this channel. aasdk's HU-side switch is aasdk/src/Channel/InputSource/InputSourceService.cpp L83-L102; aa-proxy-rs's exhaustive match is aa-proxy-rs/src/mitm_prettyprint.rs L949-L960.
```

### 6.2 Constants

| Constant | Value | Meaning | Source |
|---|---|---|---|
| `INPUT_MESSAGE_INPUT_REPORT` | 32769 (0x8001) | Input channel message id for an input event report (HU -> phone). Payload is a serialized InputReport. This is the message the phone RECEIVES for every touch / key / rotary event. | `aasdk/protobuf/aap_protobuf/service/inputsource/InputMessageId.proto` L5-L11 |
| `INPUT_MESSAGE_KEY_BINDING_REQUEST` | 32770 (0x8002) | Phone -> HU. Payload is KeyBindingRequest { repeated int32 keycodes }. The phone asks the head unit to bind (deliver) the listed keycodes. | `aasdk/protobuf/aap_protobuf/service/inputsource/InputMessageId.proto` L5-L11 |
| `INPUT_MESSAGE_KEY_BINDING_RESPONSE` | 32771 (0x8003) | HU -> phone. Payload is KeyBindingResponse { required int32 status }. status is a MessageStatus value. | `aasdk/protobuf/aap_protobuf/service/inputsource/InputMessageId.proto` L5-L11 |
| `INPUT_MESSAGE_INPUT_FEEDBACK` | 32772 (0x8004) | Input channel feedback message. Payload is InputFeedback { optional FeedbackEvent event = 1 }. Present in the protos and decoded by aa-proxy-rs; never sent or handled by aasdk/openauto in these references. | `aasdk/protobuf/aap_protobuf/service/inputsource/InputMessageId.proto` L5-L11 |
| `InputChannelMessageType::Event (AACS name for 32769)` | 0x8001 | AACS's name for the input event message on the input channel; numerically identical to INPUT_MESSAGE_INPUT_REPORT. | `AACS/include/enums.h` L49-L54 |
| `InputChannelMessageType::HandshakeRequest (AACS name for 32770)` | 0x8002 | AACS's name for KeyBindingRequest. AACS's phone-side (AAServer) sends this with the list of buttons it wants. | `AACS/include/enums.h` L49-L54 |
| `InputChannelMessageType::HandshakeResponse (AACS name for 32771)` | 0x8003 | AACS's name for KeyBindingResponse. AACS's HU-side stub replies with a 2-byte body 0x08 0x00 (proto field 1 varint = 0 = STATUS_SUCCESS). | `AACS/AAClient/src/InputChannelHandler.cpp` L50-L59 |
| `ACTION_DOWN` | 0 | PointerAction: first finger down (aasdk canonical name). Equivalent to AACS TouchAction::Press. | `aasdk/protobuf/aap_protobuf/service/inputsource/message/PointerAction.proto` L5-L12 |
| `ACTION_UP` | 1 | PointerAction: last finger up. Equivalent to AACS TouchAction::Release. | `aasdk/protobuf/aap_protobuf/service/inputsource/message/PointerAction.proto` L5-L12 |
| `ACTION_MOVED` | 2 | PointerAction: pointer(s) moved / drag. Equivalent to AACS TouchAction::Drag. | `aasdk/protobuf/aap_protobuf/service/inputsource/message/PointerAction.proto` L5-L12 |
| `ACTION_POINTER_DOWN` | 5 | PointerAction: an additional (non-primary) finger went down; action_index identifies which pointer. Equivalent to AACS TouchAction::Down. | `aasdk/protobuf/aap_protobuf/service/inputsource/message/PointerAction.proto` L5-L12 |
| `ACTION_POINTER_UP` | 6 | PointerAction: one finger lifted while others remain; action_index identifies which pointer. Equivalent to AACS TouchAction::Up. | `aasdk/protobuf/aap_protobuf/service/inputsource/message/PointerAction.proto` L5-L12 |
| `TouchAction::Press (AACS)` | 0 | AACS spelling of PointerAction ACTION_DOWN. | `AACS/proto/TouchAction.proto` L7-L14 |
| `TouchAction::Release (AACS)` | 1 | AACS spelling of PointerAction ACTION_UP. | `AACS/proto/TouchAction.proto` L7-L14 |
| `TouchAction::Drag (AACS)` | 2 | AACS spelling of PointerAction ACTION_MOVED. | `AACS/proto/TouchAction.proto` L7-L14 |
| `TouchAction::Down (AACS)` | 5 | AACS spelling of PointerAction ACTION_POINTER_DOWN. | `AACS/proto/TouchAction.proto` L7-L14 |
| `TouchAction::Up (AACS)` | 6 | AACS spelling of PointerAction ACTION_POINTER_UP. | `AACS/proto/TouchAction.proto` L7-L14 |
| `TouchScreenType::CAPACITIVE` | 1 | Value for InputSourceService.TouchScreen.type (field 3) in service discovery. Note there is no 0 value defined. | `aasdk/protobuf/aap_protobuf/service/inputsource/message/TouchScreenType.proto` L5-L9 |
| `TouchScreenType::RESISTIVE` | 2 | Value for InputSourceService.TouchScreen.type. aa-proxy-rs hard-codes this for injected displays. | `aasdk/protobuf/aap_protobuf/service/inputsource/message/TouchScreenType.proto` L5-L9 |
| `TouchScreenType::INFRARED` | 3 | Value for InputSourceService.TouchScreen.type. | `aasdk/protobuf/aap_protobuf/service/inputsource/message/TouchScreenType.proto` L5-L9 |
| `FeedbackEvent::FEEDBACK_SELECT` | 1 | Feedback event kind, used in InputSourceService.feedback_events_supported (field 4) and InputFeedback.event (field 1). | `aasdk/protobuf/aap_protobuf/service/inputsource/message/FeedbackEvent.proto` L5-L11 |
| `FeedbackEvent::FEEDBACK_FOCUS_CHANGE` | 2 | Feedback event kind. | `aasdk/protobuf/aap_protobuf/service/inputsource/message/FeedbackEvent.proto` L5-L11 |
| `FeedbackEvent::FEEDBACK_DRAG_SELECT` | 3 | Feedback event kind. | `aasdk/protobuf/aap_protobuf/service/inputsource/message/FeedbackEvent.proto` L5-L11 |
| `FeedbackEvent::FEEDBACK_DRAG_START` | 4 | Feedback event kind. | `aasdk/protobuf/aap_protobuf/service/inputsource/message/FeedbackEvent.proto` L5-L11 |
| `FeedbackEvent::FEEDBACK_DRAG_END` | 5 | Feedback event kind. | `aasdk/protobuf/aap_protobuf/service/inputsource/message/FeedbackEvent.proto` L5-L11 |
| `Service.input_source_service field number` | 4 | In the ServiceDiscoveryResponse Service descriptor, the InputSourceService sub-message occupies field 4. This is how a channel is declared to be the input channel. | `aasdk/protobuf/aap_protobuf/service/Service.proto` L21-L30 |
| `Channel.input_channel field number (AACS)` | 4 | AACS's equivalent of Service.input_source_service; same field number 4, confirming wire compatibility. | `AACS/proto/Channel.proto` L18-L24 |
| `ChannelId::INPUT_SOURCE` | 8 (positional ordinal - the C++ enum has no explicit initializers; count from CONTROL = 0) | aasdk's internal logical channel identifier used as the AAP channel number for the input service. NOTE: the enum is unnumbered, so 8 follows from its position; the value is NOT written literally in the file. | `aasdk/include/aasdk/Messenger/ChannelId.hpp` L29-L51 |
| `MessageStatus::STATUS_SUCCESS` | 0 | KeyBindingResponse.status value when all requested keycodes are bound. | `aasdk/protobuf/aap_protobuf/shared/MessageStatus.proto` L5-L8 |
| `MessageStatus::STATUS_KEYCODE_NOT_BOUND` | -18 | KeyBindingResponse.status value returned when the phone requested a keycode the head unit did not advertise in keycodes_supported. | `aasdk/protobuf/aap_protobuf/shared/MessageStatus.proto` L24-L26 |
| `KeyCode::KEYCODE_UNKNOWN` | 0 | Base of the KeyCode enum. Values 0-263 mirror Android KeyEvent constants exactly (value V is on line V+6 of this file). | `aasdk/protobuf/aap_protobuf/service/media/sink/message/KeyCode.proto` L5-L11 |
| `KeyCode::KEYCODE_HOME` | 3 | Steering-wheel / dashboard HOME button. Used by openauto (Input.HomeButton) and by aa-proxy-rs auxiliary-display keycode list. | `aasdk/protobuf/aap_protobuf/service/media/sink/message/KeyCode.proto` L9-L12 |
| `KeyCode::KEYCODE_BACK` | 4 | BACK button (openauto Input.BackButton). | `aasdk/protobuf/aap_protobuf/service/media/sink/message/KeyCode.proto` L9-L12 |
| `KeyCode::KEYCODE_CALL` | 5 | Steering-wheel phone/pick-up button (openauto Input.PhoneButton). | `aasdk/protobuf/aap_protobuf/service/media/sink/message/KeyCode.proto` L9-L12 |
| `KeyCode::KEYCODE_ENDCALL` | 6 | Steering-wheel hang-up button (openauto Input.CallEndButton). | `aasdk/protobuf/aap_protobuf/service/media/sink/message/KeyCode.proto` L9-L12 |
| `KeyCode::KEYCODE_DPAD_UP` | 19 | Rotary/D-pad navigation up. aa-proxy-rs advertises {19,20,21,22,23} for a cluster display. | `aasdk/protobuf/aap_protobuf/service/media/sink/message/KeyCode.proto` L25-L29 |
| `KeyCode::KEYCODE_DPAD_DOWN` | 20 | Rotary/D-pad navigation down. | `aasdk/protobuf/aap_protobuf/service/media/sink/message/KeyCode.proto` L25-L29 |
| `KeyCode::KEYCODE_DPAD_LEFT` | 21 | Rotary/D-pad navigation left. | `aasdk/protobuf/aap_protobuf/service/media/sink/message/KeyCode.proto` L25-L29 |
| `KeyCode::KEYCODE_DPAD_RIGHT` | 22 | Rotary/D-pad navigation right. | `aasdk/protobuf/aap_protobuf/service/media/sink/message/KeyCode.proto` L25-L29 |
| `KeyCode::KEYCODE_DPAD_CENTER` | 23 | Rotary push / ENTER (openauto Input.EnterButton). | `aasdk/protobuf/aap_protobuf/service/media/sink/message/KeyCode.proto` L25-L29 |
| `KeyCode::KEYCODE_VOLUME_UP` | 24 | Volume up hard key. | `aasdk/protobuf/aap_protobuf/service/media/sink/message/KeyCode.proto` L30-L31 |
| `KeyCode::KEYCODE_VOLUME_DOWN` | 25 | Volume down hard key. | `aasdk/protobuf/aap_protobuf/service/media/sink/message/KeyCode.proto` L30-L31 |
| `KeyCode::KEYCODE_HEADSETHOOK` | 79 | Headset hook / SWC pick-up-answer button. | `aasdk/protobuf/aap_protobuf/service/media/sink/message/KeyCode.proto` L85-L85 |
| `KeyCode::KEYCODE_MENU` | 82 | MENU hard key. | `aasdk/protobuf/aap_protobuf/service/media/sink/message/KeyCode.proto` L88-L90 |
| `KeyCode::KEYCODE_SEARCH` | 84 | VOICE COMMAND / Assistant button. openauto maps its 'Input.VoiceCommandButton' to this keycode - this is the canonical steering-wheel voice button in these references. | `aasdk/protobuf/aap_protobuf/service/media/sink/message/KeyCode.proto` L88-L91 |
| `KeyCode::KEYCODE_MEDIA_PLAY_PAUSE` | 85 | Media toggle play/pause (openauto Input.TogglePlayButton). | `aasdk/protobuf/aap_protobuf/service/media/sink/message/KeyCode.proto` L91-L96 |
| `KeyCode::KEYCODE_MEDIA_STOP` | 86 | Media stop. | `aasdk/protobuf/aap_protobuf/service/media/sink/message/KeyCode.proto` L91-L96 |
| `KeyCode::KEYCODE_MEDIA_NEXT` | 87 | Next track (openauto Input.NextTrackButton; aa-proxy-rs intercepts this for long-press handling). | `aasdk/protobuf/aap_protobuf/service/media/sink/message/KeyCode.proto` L91-L96 |
| `KeyCode::KEYCODE_MEDIA_PREVIOUS` | 88 | Previous track (openauto Input.PreviousTrackButton). | `aasdk/protobuf/aap_protobuf/service/media/sink/message/KeyCode.proto` L91-L96 |
| `KeyCode::KEYCODE_MEDIA_REWIND` | 89 | Rewind (aa-proxy-rs always drops this one). | `aasdk/protobuf/aap_protobuf/service/media/sink/message/KeyCode.proto` L91-L96 |
| `KeyCode::KEYCODE_MEDIA_FAST_FORWARD` | 90 | Fast forward (aa-proxy-rs always drops this one). | `aasdk/protobuf/aap_protobuf/service/media/sink/message/KeyCode.proto` L91-L96 |
| `KeyCode::KEYCODE_MUTE` | 91 | Microphone mute key. | `aasdk/protobuf/aap_protobuf/service/media/sink/message/KeyCode.proto` L97-L97 |
| `KeyCode::KEYCODE_MEDIA_PLAY` | 126 | Explicit media play (openauto Input.PlayButton). | `aasdk/protobuf/aap_protobuf/service/media/sink/message/KeyCode.proto` L132-L133 |
| `KeyCode::KEYCODE_MEDIA_PAUSE` | 127 | Explicit media pause (openauto Input.PauseButton). | `aasdk/protobuf/aap_protobuf/service/media/sink/message/KeyCode.proto` L132-L133 |
| `KeyCode::KEYCODE_VOLUME_MUTE` | 164 | Audio mute key. | `aasdk/protobuf/aap_protobuf/service/media/sink/message/KeyCode.proto` L170-L170 |
| `KeyCode::KEYCODE_ASSIST` | 219 | Android assist key. | `aasdk/protobuf/aap_protobuf/service/media/sink/message/KeyCode.proto` L225-L225 |
| `KeyCode::KEYCODE_MEDIA_AUDIO_TRACK` | 222 | Audio track select key. | `aasdk/protobuf/aap_protobuf/service/media/sink/message/KeyCode.proto` L228-L228 |
| `KeyCode::KEYCODE_VOICE_ASSIST` | 231 | Voice assist key (Android's KEYCODE_VOICE_ASSISTANT equivalent). Distinct from KEYCODE_SEARCH which openauto uses for the voice button. | `aasdk/protobuf/aap_protobuf/service/media/sink/message/KeyCode.proto` L237-L237 |
| `KeyCode::KEYCODE_SENTINEL` | 65535 | Boundary marker separating the Android-standard keycodes from the Android-Auto-specific extension range that follows. | `aasdk/protobuf/aap_protobuf/service/media/sink/message/KeyCode.proto` L274-L284 |
| `KeyCode::KEYCODE_ROTARY_CONTROLLER` | 65536 | Rotary/scroll-wheel controller. This keycode is carried in a RelativeEvent.Rel (keycode + signed delta), NOT in a KeyEvent. openauto emits delta -1 for LEFT and +1 for RIGHT; aa-proxy-rs documents \|1\| = one UI step, scaling linearly. | `aasdk/protobuf/aap_protobuf/service/media/sink/message/KeyCode.proto` L274-L284 |
| `KeyCode::KEYCODE_MEDIA` | 65537 | AA-specific 'MEDIA' hard key (jump to media app). | `aasdk/protobuf/aap_protobuf/service/media/sink/message/KeyCode.proto` L274-L284 |
| `KeyCode::KEYCODE_NAVIGATION` | 65538 | AA-specific 'NAV' hard key (openauto Input.NavButton). | `aasdk/protobuf/aap_protobuf/service/media/sink/message/KeyCode.proto` L274-L284 |
| `KeyCode::KEYCODE_RADIO` | 65539 | AA-specific 'RADIO' hard key. | `aasdk/protobuf/aap_protobuf/service/media/sink/message/KeyCode.proto` L274-L284 |
| `KeyCode::KEYCODE_TEL` | 65540 | AA-specific 'TEL' (telephone) hard key. | `aasdk/protobuf/aap_protobuf/service/media/sink/message/KeyCode.proto` L274-L284 |
| `KeyCode::KEYCODE_PRIMARY_BUTTON` | 65541 | AA-specific generic OEM button 1. | `aasdk/protobuf/aap_protobuf/service/media/sink/message/KeyCode.proto` L274-L284 |
| `KeyCode::KEYCODE_SECONDARY_BUTTON` | 65542 | AA-specific generic OEM button 2. | `aasdk/protobuf/aap_protobuf/service/media/sink/message/KeyCode.proto` L274-L284 |
| `KeyCode::KEYCODE_TERTIARY_BUTTON` | 65543 | AA-specific generic OEM button 3. | `aasdk/protobuf/aap_protobuf/service/media/sink/message/KeyCode.proto` L274-L284 |
| `KeyCode::KEYCODE_TURN_CARD` | 65544 | AA-specific 'turn card' (navigation turn-by-turn card) key. Last value of the enum. | `aasdk/protobuf/aap_protobuf/service/media/sink/message/KeyCode.proto` L274-L284 |
| `AACS ButtonCode::SCROLL_WHEEL` | 65536 | AACS's name for KEYCODE_ROTARY_CONTROLLER; confirms the 65536 value independently. | `AACS/proto/ButtonsEvent.proto` L9-L31 |
| `AACS ButtonCode::MICROPHONE_1` | 0x54 (84) | AACS's voice/mic button; numerically equal to KEYCODE_SEARCH = 84, matching openauto's voice-command mapping. | `AACS/proto/ButtonsEvent.proto` L23-L25 |
| `InstrumentClusterAction::UNKNOWN` | 0 | Cluster/rotary controller navigation action delivered to the phone over the PhoneStatus (PHONE_STATUS_INPUT) and MediaBrowser (MEDIA_BROWSE_INPUT) channels - a second input path distinct from the input channel. | `aasdk/protobuf/aap_protobuf/shared/InstrumentClusterInput.proto` L5-L17 |
| `InstrumentClusterAction::UP` | 1 | Cluster controller up. | `aasdk/protobuf/aap_protobuf/shared/InstrumentClusterInput.proto` L5-L17 |
| `InstrumentClusterAction::DOWN` | 2 | Cluster controller down. | `aasdk/protobuf/aap_protobuf/shared/InstrumentClusterInput.proto` L5-L17 |
| `InstrumentClusterAction::LEFT` | 3 | Cluster controller left. | `aasdk/protobuf/aap_protobuf/shared/InstrumentClusterInput.proto` L5-L17 |
| `InstrumentClusterAction::RIGHT` | 4 | Cluster controller right. | `aasdk/protobuf/aap_protobuf/shared/InstrumentClusterInput.proto` L5-L17 |
| `InstrumentClusterAction::ENTER` | 5 | Cluster controller select/enter. | `aasdk/protobuf/aap_protobuf/shared/InstrumentClusterInput.proto` L5-L17 |
| `InstrumentClusterAction::BACK` | 6 | Cluster controller back. | `aasdk/protobuf/aap_protobuf/shared/InstrumentClusterInput.proto` L5-L17 |
| `InstrumentClusterAction::CALL` | 7 | Cluster controller call button. | `aasdk/protobuf/aap_protobuf/shared/InstrumentClusterInput.proto` L5-L17 |
| `PhoneStatusMessageId::PHONE_STATUS_INPUT` | 32770 | Message id on the PhoneStatus channel carrying PhoneStatusInput { InstrumentClusterInput input; caller_number; caller_id } - HU cluster-controller input the phone receives. | `aasdk/protobuf/aap_protobuf/service/phonestatus/PhoneStatusMessageId.proto` L7-L10 |
| `MediaBrowserMessageId::MEDIA_BROWSE_INPUT` | 32774 | Message id on the MediaBrowser channel carrying MediaBrowserInput { InstrumentClusterInput input; string path } - browse navigation input the phone receives. | `aasdk/protobuf/aap_protobuf/service/mediabrowser/MediaBrowserMessageId.proto` L5-L11 |
| `InputReport.timestamp units (openauto)` | microseconds since epoch | openauto stamps every InputReport with std::chrono::microseconds taken from high_resolution_clock::now().time_since_epoch(). Same conversion in both onButtonEvent and onTouchEvent. | `openauto/src/autoapp/Service/InputSource/InputSourceService.cpp` L145-L151 |
| `InputReport.timestamp units (aa-proxy-rs send_key_event)` | microseconds since UNIX_EPOCH | aa-proxy-rs computes the timestamp as SystemTime::now().duration_since(UNIX_EPOCH).as_micros() when injecting synthetic key events into the input channel. | `aa-proxy-rs/src/mitm.rs` L3553-L3557 |
| `InputReport.timestamp units (aa-proxy-rs send_input_key)` | milliseconds since UNIX_EPOCH | A second injection helper in the SAME file uses milliseconds instead of microseconds - an internal inconsistency in aa-proxy-rs (its own comment says 'FIXME: make single function from this and send_key_event above'). | `aa-proxy-rs/src/mitm.rs` L3646-L3655 |
| `RelativeEvent rotary delta convention` | delta magnitude 1 == one UI step, scales linearly; positive = clockwise, negative = counterclockwise | Semantics of RelativeEvent.Rel.delta when keycode == KEYCODE_ROTARY_CONTROLLER, per aa-proxy-rs's in-code comment. | `aa-proxy-rs/src/mitm.rs` L3608-L3619 |
| `openauto rotary delta values` | -1 for WheelDirection::LEFT, +1 otherwise | openauto emits exactly +/-1 per detent for KEYCODE_ROTARY_CONTROLLER, and routes that keycode to relative_event rather than key_event. | `openauto/src/autoapp/Service/InputSource/InputSourceService.cpp` L153-L163 |
| `aa-proxy-rs cluster-display advertised keycodes` | [19, 20, 21, 22, 23] | Concrete keycodes_supported list an implementation advertises for a DISPLAY_TYPE_CLUSTER input source: DPAD_UP/DOWN/LEFT/RIGHT/CENTER. | `aa-proxy-rs/src/display.rs` L222-L232 |
| `aa-proxy-rs auxiliary-display advertised keycodes` | [3, 4, 5, 6, 84, 85, 87, 88, 126, 127, 65537, 65538, 65540] | HOME, BACK, CALL, ENDCALL, SEARCH, MEDIA_PLAY_PAUSE, MEDIA_NEXT, MEDIA_PREVIOUS, MEDIA_PLAY, MEDIA_PAUSE, KEYCODE_MEDIA, KEYCODE_NAVIGATION, KEYCODE_TEL - a realistic head-unit keycode advertisement. | `aa-proxy-rs/src/display.rs` L222-L232 |
| `openauto full configurable button-code set` | MEDIA_PLAY(126), MEDIA_PAUSE(127), MEDIA_PLAY_PAUSE(85), MEDIA_NEXT(87), MEDIA_PREVIOUS(88), HOME(3), CALL(5), ENDCALL(6), SEARCH(84), DPAD_LEFT(21), DPAD_RIGHT(22), DPAD_UP(19), DPAD_DOWN(20), ROTARY_CONTROLLER(65536), BACK(4), DPAD_CENTER(23), NAVIGATION(65538) | The complete list of keycodes a head unit built on openauto can advertise in InputSourceService.keycodes_supported; each is gated by an INI flag (default false via insertButtonCode's get<bool>(key, false)). | `openauto/src/autoapp/Configuration/Configuration.cpp` L728-L747 |
| `InputReport frame flags (aa-proxy-rs)` | ENCRYPTED \| FRAME_TYPE_FIRST \| FRAME_TYPE_LAST | Input reports are sent as a single encrypted frame (first+last set). aasdk uses EncryptionType::ENCRYPTED with MessageType::SPECIFIC for the same message. | `aa-proxy-rs/src/mitm.rs` L3587-L3593 |
| `InputReport message framing (aasdk)` | EncryptionType::ENCRYPTED, MessageType::SPECIFIC, 2-byte big-endian message id prefix | How the input report is put on the wire by aasdk: a Message on the INPUT_SOURCE channel, encrypted, 'specific' (not control), with the 16-bit message id inserted ahead of the serialized protobuf. | `aasdk/src/Channel/InputSource/InputSourceService.cpp` L43-L55 |
| `KeyBindingResponse framing (aasdk)` | EncryptionType::ENCRYPTED, MessageType::SPECIFIC | The key binding response is a 'specific' (non-control) encrypted message on the input channel, unlike ChannelOpenResponse which uses MessageType::CONTROL. | `aasdk/src/Channel/InputSource/InputSourceService.cpp` L57-L68 |

### 6.3 Message definitions


#### `InputReport (the canonical 'input event indication' - message id 32769)`

Source: `aasdk/protobuf/aap_protobuf/service/inputsource/message/InputReport.proto`

```proto
syntax="proto2";

import "aap_protobuf/service/inputsource/message/TouchEvent.proto";
import "aap_protobuf/service/inputsource/message/AbsoluteEvent.proto";
import "aap_protobuf/service/inputsource/message/RelativeEvent.proto";
import "aap_protobuf/service/inputsource/message/KeyEvent.proto";

package aap_protobuf.service.inputsource.message;

message InputReport
{
    required uint64 timestamp = 1;
    optional int32 disp_channel_id = 2 [deprecated = true];
    optional TouchEvent touch_event = 3;
    optional KeyEvent key_event = 4;
    optional AbsoluteEvent absolute_event = 5;
    optional RelativeEvent relative_event = 6;
    optional TouchEvent touchpad_event = 7;
}
```

#### `TouchEvent (with nested Pointer)`

Source: `aasdk/protobuf/aap_protobuf/service/inputsource/message/TouchEvent.proto`

```proto
syntax="proto2";

import "aap_protobuf/service/inputsource/message/PointerAction.proto";

package aap_protobuf.service.inputsource.message;

message TouchEvent {
    repeated Pointer pointer_data = 1;
    message Pointer {
        required uint32 x = 1;
        required uint32 y = 2;
        required uint32 pointer_id = 3;
    }

    optional uint32 action_index = 2;
    optional PointerAction action = 3;
}
```

#### `KeyEvent (with nested Key)`

Source: `aasdk/protobuf/aap_protobuf/service/inputsource/message/KeyEvent.proto`

```proto
syntax="proto2";

package aap_protobuf.service.inputsource.message;

message KeyEvent {
  repeated Key keys = 1;
  message Key {
    required uint32 keycode = 1;
    required bool down = 2;
    required uint32 metastate = 3;
    optional bool longpress = 4;
  }
}
```

#### `RelativeEvent (with nested Rel) - rotary / relative axes`

Source: `aasdk/protobuf/aap_protobuf/service/inputsource/message/RelativeEvent.proto`

```proto
syntax="proto2";

package aap_protobuf.service.inputsource.message;

message RelativeEvent {
    repeated Rel data = 1;
    message Rel {
        required uint32 keycode = 1;
        required int32 delta = 2;
    }
}
```

#### `AbsoluteEvent (with nested Abs) - absolute axes`

Source: `aasdk/protobuf/aap_protobuf/service/inputsource/message/AbsoluteEvent.proto`

```proto
syntax="proto2";

package aap_protobuf.service.inputsource.message;

message AbsoluteEvent {
    repeated Abs data = 1;
    message Abs {
        required uint32 keycode = 1;
        required int32 value = 2;
    }
}
```

#### `PointerAction (touch action enum)`

Source: `aasdk/protobuf/aap_protobuf/service/inputsource/message/PointerAction.proto`

```proto
syntax="proto2";

package aap_protobuf.service.inputsource.message;

enum PointerAction
{
    ACTION_DOWN = 0;
    ACTION_UP = 1;
    ACTION_MOVED = 2;
    ACTION_POINTER_DOWN = 5;
    ACTION_POINTER_UP = 6;
}
```

#### `TouchScreenType`

Source: `aasdk/protobuf/aap_protobuf/service/inputsource/message/TouchScreenType.proto`

```proto
syntax="proto2";

package aap_protobuf.service.inputsource.message;

enum TouchScreenType {
    CAPACITIVE = 1;
    RESISTIVE = 2;
    INFRARED = 3;
}
```

#### `InputSourceService (service-discovery descriptor: keycodes, touchscreen, touchpad, feedback)`

Source: `aasdk/protobuf/aap_protobuf/service/inputsource/InputSourceService.proto`

```proto
syntax="proto2";

import "aap_protobuf/service/inputsource/message/FeedbackEvent.proto";
import "aap_protobuf/service/inputsource/message/TouchScreenType.proto";

package aap_protobuf.service.inputsource;

message InputSourceService {
    repeated int32 keycodes_supported = 1 [packed = true];

    repeated TouchScreen touchscreen = 2;
    message TouchScreen {
        required int32 width = 1;
        required int32 height = 2;
        optional message.TouchScreenType type = 3;
        optional bool is_secondary = 4;
    }

    repeated TouchPad touchpad = 3;
    message TouchPad {
        required int32 width = 1;
        required int32 height = 2;
        optional bool ui_navigation = 3;
        optional int32 physical_width = 4;
        optional int32 physical_height = 5;
        optional bool ui_absolute = 6;
        optional bool tap_as_select = 7;
        optional int32 sensitivity = 8;
    }

    repeated message.FeedbackEvent feedback_events_supported = 4;
    optional uint32 display_id = 5;
}
```

#### `InputMessageId`

Source: `aasdk/protobuf/aap_protobuf/service/inputsource/InputMessageId.proto`

```proto
syntax="proto2";

package aap_protobuf.service.inputsource;

enum InputMessageId
{
    INPUT_MESSAGE_INPUT_REPORT = 32769;
    INPUT_MESSAGE_KEY_BINDING_REQUEST = 32770;
    INPUT_MESSAGE_KEY_BINDING_RESPONSE = 32771;
    INPUT_MESSAGE_INPUT_FEEDBACK = 32772;
}
```

#### `InputFeedback (message id 32772)`

Source: `aasdk/protobuf/aap_protobuf/service/inputsource/message/InputFeedback.proto`

```proto
syntax="proto2";

import "aap_protobuf/service/inputsource/message/FeedbackEvent.proto";

package aap_protobuf.service.inputsource.message;

message InputFeedback {
  optional FeedbackEvent event = 1;
}
```

#### `FeedbackEvent`

Source: `aasdk/protobuf/aap_protobuf/service/inputsource/message/FeedbackEvent.proto`

```proto
syntax="proto2";

package aap_protobuf.service.inputsource.message;

enum FeedbackEvent {
  FEEDBACK_SELECT = 1;
  FEEDBACK_FOCUS_CHANGE = 2;
  FEEDBACK_DRAG_SELECT = 3;
  FEEDBACK_DRAG_START = 4;
  FEEDBACK_DRAG_END = 5;
}
```

#### `KeyBindingRequest (message id 32770, phone -> HU) - NOTE: lives under service/media/sink/ in aasdk`

Source: `aasdk/protobuf/aap_protobuf/service/media/sink/message/KeyBindingRequest.proto`

```proto
syntax="proto2";

package aap_protobuf.service.media.sink.message;

message KeyBindingRequest {
    repeated int32 keycodes = 1 [packed = true];
}
```

#### `KeyBindingResponse (message id 32771, HU -> phone)`

Source: `aasdk/protobuf/aap_protobuf/service/media/sink/message/KeyBindingResponse.proto`

```proto
syntax="proto2";

import "aap_protobuf/shared/MessageStatus.proto";

package aap_protobuf.service.media.sink.message;

message KeyBindingResponse {
    required int32 status = 1;
}
```

#### `Service (service-discovery entry; input_source_service = field 4)`

Source: `aasdk/protobuf/aap_protobuf/service/Service.proto`

```proto
message Service
{
    required int32 id = 1;
    optional sensorsource.SensorSourceService sensor_source_service = 2;
    optional media.sink.MediaSinkService media_sink_service = 3;
    optional inputsource.InputSourceService input_source_service = 4;
    optional media.source.MediaSourceService media_source_service = 5;
    optional bluetooth.BluetoothService bluetooth_service = 6;
    optional radio.RadioService radio_service = 7;
    optional navigationstatus.NavigationStatusService navigation_status_service = 8;
    optional mediaplayback.MediaPlaybackStatusService media_playback_service = 9;
    optional phonestatus.PhoneStatusService phone_status_service = 10;
    optional mediabrowser.MediaBrowserService media_browser_service = 11;
    optional vendorextension.VendorExtensionService vendor_extension_service = 12;
    optional genericnotification.GenericNotificationService generic_notification_service = 13;
    optional wifiprojection.WifiProjectionService wifi_projection_service = 14;
}
```

#### `GalVerificationInjectInput (control channel; wraps an InputReport for GAL certification injection)`

Source: `aasdk/protobuf/aap_protobuf/channel/control/GalVerificationInjectInput.proto`

```proto
syntax="proto2";

import "aap_protobuf/service/inputsource/message/InputReport.proto";

package aap_protobuf.service.control.message;

message GalVerificationInjectInput {
  required inputsource.message.InputReport input = 1;
}
```

#### `InstrumentClusterInput (secondary input path: cluster/rotary controller actions)`

Source: `aasdk/protobuf/aap_protobuf/shared/InstrumentClusterInput.proto`

```proto
syntax="proto2";

package aap_protobuf.shared;

message InstrumentClusterInput {
    required InstrumentClusterAction action = 1;
    enum InstrumentClusterAction {
        UNKNOWN = 0;
        UP = 1;
        DOWN = 2;
        LEFT = 3;
        RIGHT = 4;
        ENTER = 5;
        BACK = 6;
        CALL = 7;
    }
}
```

#### `PhoneStatusInput (PHONE_STATUS_INPUT = 32770, HU -> phone)`

Source: `aasdk/protobuf/aap_protobuf/service/phonestatus/message/PhoneStatusInput.proto`

```proto
syntax="proto2";

import "aap_protobuf/shared/InstrumentClusterInput.proto";

package aap_protobuf.service.phonestatus.message;

message PhoneStatusInput {
  required shared.InstrumentClusterInput input = 1;
  optional string caller_number = 2;
  optional string caller_id = 3;
}
```

#### `MediaBrowserInput (MEDIA_BROWSE_INPUT = 32774, HU -> phone)`

Source: `aasdk/protobuf/aap_protobuf/service/mediabrowser/message/MediaBrowserInput.proto`

```proto
syntax="proto2";

import "aap_protobuf/shared/InstrumentClusterInput.proto";

package aap_protobuf.service.mediabrowser.message;

message MediaBrowserInput {
  required shared.InstrumentClusterInput input = 1;
  required string path = 2;
}
```

#### `AACS InputChannel + InputChannelHandshakeRequest (independent reconstruction)`

Source: `AACS/proto/InputChannel.proto`

```proto
// Distributed under GPLv3 only as specified in repository's root LICENSE file

syntax="proto2";

package tag.aas;

import "TouchConfig.proto";
import "ButtonsEvent.proto";

message InputChannel
{
    repeated ButtonCode.Enum available_buttons = 1;
    optional TouchConfig screen_config = 2;
}

message InputChannelHandshakeRequest
{
    repeated ButtonCode.Enum available_buttons = 1;
}
```

#### `AACS TouchConfig (touchscreen width/height, field 2 of InputChannel)`

Source: `AACS/proto/TouchConfig.proto`

```proto
// Distributed under GPLv3 only as specified in repository's root LICENSE file

syntax="proto2";

package tag.aas;

message TouchConfig
{
    required uint32 width = 1;
    required uint32 height = 2;
}
```

#### `AACS InputEvent (their name for InputReport)`

Source: `AACS/proto/InputEvent.proto`

```proto
// Distributed under GPLv3 only as specified in repository's root LICENSE file

syntax="proto2";

package tag.aas;

import "TouchEvent.proto";
import "ButtonsEvent.proto";

message InputEvent
{
    optional uint64 timestamp = 1;
    optional TouchEvent touch_event = 3;
    optional ButtonsEvent buttons_event = 4;
}
```

#### `AACS TouchEvent + TouchLocation`

Source: `AACS/proto/TouchEvent.proto`

```proto
message TouchEvent
{
    repeated TouchLocation touch_location = 1;
    required TouchAction touch_action = 3;
}

// AACS/proto/TouchLocation.proto:
message TouchLocation
{
    required uint32 x = 1;
    required uint32 y = 2;
    required uint32 pid = 3;
}
```

#### `AACS ButtonCode / ButtonEvent / ButtonsEvent (their KeyEvent equivalent)`

Source: `AACS/proto/ButtonsEvent.proto`

```proto
message ButtonCode
{
    enum Enum
    {
        NONE = 0x00;
        MICROPHONE_2 = 0x01;
        MENU = 0x02;
        HOME = 0x03;
        BACK = 0x04;
        PHONE = 0x05;
        CALL_END = 0x06;
        UP = 0x13;
        DOWN = 0x14;
        LEFT = 0x15;
        RIGHT = 0x16;
        ENTER = 0x17;
        UNKNOWN_1 = 0x42;
        MICROPHONE_1 = 0x54;
        TOGGLE_PLAY = 0x55;
        NEXT = 0x57;
        PREV = 0x58;
        PLAY = 0x7E;
        PAUSE = 0x7F;
        SCROLL_WHEEL = 65536;
    }
}

message ButtonEvent
{
    required ButtonCode.Enum scan_code = 1;
    required bool is_pressed = 2;
    required uint32 meta = 3;
    required bool long_press = 4;
}

message ButtonsEvent
{
    repeated ButtonEvent button_events = 1;
}
```

#### `openauto internal touch/button structs (implementation shape of a decoded InputReport)`

Source: `openauto/include/f1x/openauto/autoapp/Projection/InputEvent.hpp`

```proto
enum class ButtonEventType
{
    NONE,
    PRESS,
    RELEASE
};

enum class WheelDirection
{
    NONE,
    LEFT,
    RIGHT
};

struct ButtonEvent
{
    ButtonEventType type;
    WheelDirection wheelDirection;
    aap_protobuf::service::media::sink::message::KeyCode code;
};

struct TouchPoint
{
    uint32_t x;
    uint32_t y;
    uint32_t pointerId;
};

struct TouchEvent
{
    aap_protobuf::service::inputsource::message::PointerAction type;
    std::vector<TouchPoint> pointers;
    uint32_t actionIndex; // Index of the pointer that changed state
};
```

#### `IInputSourceService / IInputSourceServiceEventHandler (channel API surface: exactly 3 sends, 2 receives)`

Source: `aasdk/include/aasdk/Channel/InputSource/IInputSourceService.hpp`

```proto
// IInputSourceService.hpp
virtual void receive(IInputSourceServiceEventHandler::Pointer eventHandler) = 0;
virtual void
sendChannelOpenResponse(const aap_protobuf::service::control::message::ChannelOpenResponse &response,
                        SendPromise::Pointer promise) = 0;
virtual void sendInputReport(const aap_protobuf::service::inputsource::message::InputReport &indication,
                                      SendPromise::Pointer promise) = 0;
virtual void sendKeyBindingResponse(const aap_protobuf::service::media::sink::message::KeyBindingResponse &response,
                                 SendPromise::Pointer promise) = 0;

// IInputSourceServiceEventHandler.hpp
virtual void onChannelOpenRequest(const aap_protobuf::service::control::message::ChannelOpenRequest &request) = 0;
virtual void onKeyBindingRequest(const aap_protobuf::service::media::sink::message::KeyBindingRequest &request) = 0;
virtual void onChannelError(const error::Error &e) = 0;
```

### 6.4 Where the references disagree


**There is no message named 'InputEventIndication' anywhere in the references**

The task brief asks for 'InputEventIndication'. A grep of all five reference trees turns up no such identifier. The canonical aasdk name is InputReport (aasdk/protobuf/aap_protobuf/service/inputsource/message/InputReport.proto), carried by INPUT_MESSAGE_INPUT_REPORT = 32769. AACS calls the same wire message InputEvent (AACS/proto/InputEvent.proto) with message type InputChannelMessageType::Event = 0x8001. Use InputReport as the ground-truth definition; 'InputEventIndication' appears to be older/third-party naming.

**aasdk_proto/ does NOT contain input protos in this checkout**

The brief says to compare aasdk/aasdk_proto/ against aasdk/protobuf/aap_protobuf/. In this checkout aasdk/aasdk_proto/ contains only five files, all Wifi*: WifiChannelMessageIdsEnum.proto, WifiInfoRequestMessage.proto, WifiInfoResponseMessage.proto, WifiSecurityRequestMessage.proto, WifiSecurityResponseMessage.proto. There is no input/touch/key proto there, so there is no aasdk_proto-vs-aap_protobuf conflict to resolve for this domain. The authoritative set is aasdk/protobuf/aap_protobuf/service/inputsource/.

**TouchAction naming: aasdk PointerAction vs AACS TouchAction - numbers agree, names do not**

The brief asks for a TouchAction enum with PRESS/RELEASE/DRAG/POINTER_DOWN/POINTER_UP. aasdk names the enum PointerAction with ACTION_DOWN=0, ACTION_UP=1, ACTION_MOVED=2, ACTION_POINTER_DOWN=5, ACTION_POINTER_UP=6 (aasdk/protobuf/aap_protobuf/service/inputsource/message/PointerAction.proto L5-L12). AACS names it TouchAction with Press=0, Release=1, Drag=2, Down=5, Up=6 (AACS/proto/TouchAction.proto L7-L14). The wire values are IDENTICAL; only the symbol names differ. Prefer aasdk's PointerAction naming. Note the gap: 3 and 4 are unassigned in both.

**AACS TouchEvent omits action_index (field 2)**

aasdk TouchEvent has pointer_data=1, action_index=2, action=3. AACS TouchEvent (AACS/proto/TouchEvent.proto L10-L14) declares only `repeated TouchLocation touch_location = 1;` and `required TouchAction touch_action = 3;` - field 2 is simply absent. Field numbers 1 and 3 line up, so AACS decodes aasdk-produced TouchEvents correctly but discards action_index, which is required to know WHICH finger went down/up on a multi-touch POINTER_DOWN/POINTER_UP. Also AACS makes touch_action required while aasdk makes action optional.

**AACS ButtonEvent makes long_press required; aasdk KeyEvent.Key makes longpress optional**

aasdk KeyEvent.Key: `required uint32 keycode = 1; required bool down = 2; required uint32 metastate = 3; optional bool longpress = 4;`. AACS ButtonEvent: `required ButtonCode.Enum scan_code = 1; required bool is_pressed = 2; required uint32 meta = 3; required bool long_press = 4;` (AACS/proto/ButtonsEvent.proto L34-L40). A parser that treats field 4 as required will reject reports from head units that omit it. Prefer aasdk (optional). Field names also differ (keycode/down/metastate vs scan_code/is_pressed/meta) but field numbers and wire types match.

**KeyBindingRequest/KeyBindingResponse are filed under service/media/sink/ in aasdk although they are input-channel messages**

aasdk places KeyBindingRequest.proto and KeyBindingResponse.proto under aasdk/protobuf/aap_protobuf/service/media/sink/message/ (package aap_protobuf.service.media.sink.message) even though their message ids 32770/32771 belong to InputMessageId and they travel on the input channel. aasdk's own code confirms the cross-import: aasdk/include/aasdk/Channel/InputSource/IInputSourceService.hpp L25 includes <aap_protobuf/service/media/sink/message/KeyBindingResponse.pb.h>. aa-proxy-rs and aasdk/docs/protos.proto keep them in a single flat namespace with no such split. This is a packaging quirk of the aasdk tree, not a wire difference.

**KeyCode enum is also filed under service/media/sink/**

aasdk/protobuf/aap_protobuf/service/media/sink/message/KeyCode.proto holds the full KeyCode enum (0..65544) used by the INPUT channel's KeyEvent.Key.keycode, AbsoluteEvent.Abs.keycode and RelativeEvent.Rel.keycode. Those input protos declare those fields as plain uint32 and do not import KeyCode, so the enum is documentation-only at the proto level - any uint32 is legal on the wire. A clean-room implementation must not assume the field is enum-validated.

**InstrumentClusterInput is defined TWICE in aasdk with different packages**

aasdk/protobuf/aap_protobuf/shared/InstrumentClusterInput.proto declares package aap_protobuf.shared, and aasdk/protobuf/aap_protobuf/channel/control/InstrumentClusterInput.proto declares package aap_protobuf.service.control.message. Both bodies are byte-identical (required InstrumentClusterAction action = 1 with UNKNOWN..CALL = 0..7). PhoneStatusInput.proto and MediaBrowserInput.proto import the shared/ one. The duplication is harmless on the wire but a codegen hazard.

**Timestamp units are unspecified in the proto and inconsistent across implementations**

InputReport.timestamp is `required uint64` with no comment. openauto uses microseconds from high_resolution_clock::now().time_since_epoch() (openauto/src/autoapp/Service/InputSource/InputSourceService.cpp L145-L151, L177-L183) - note high_resolution_clock's epoch is NOT guaranteed to be the Unix epoch. aa-proxy-rs's send_key_event and send_rotary_event use microseconds since UNIX_EPOCH (aa-proxy-rs/src/mitm.rs L3554-L3557, L3612-L3615). But aa-proxy-rs's send_input_key in the SAME file uses MILLISECONDS: `let ts = SystemTime::now().duration_since(UNIX_EPOCH)?.as_millis() as u64;` (mitm.rs L3655), with an adjacent FIXME admitting the duplication. Best-supported reading: microseconds since the Unix epoch. AACS makes the field `optional uint64 timestamp = 1` rather than required (AACS/proto/InputEvent.proto L12).

**AACS makes InputEvent.timestamp optional while aasdk makes InputReport.timestamp required**

aasdk: `required uint64 timestamp = 1;`. AACS: `optional uint64 timestamp = 1;`. A strict proto2 parser built from the aasdk schema will fail on a report that omits the timestamp. Prefer aasdk (required) when generating, but tolerate absence when parsing.

**AACS ButtonCode names do not always match the Android keycode at that value**

AACS/proto/ButtonsEvent.proto L11-L31 uses hex Android keycodes but with its own labels. Cross-checking against aasdk KeyCode.proto: MICROPHONE_2 = 0x01 = 1 = KEYCODE_SOFT_LEFT (not a microphone); UNKNOWN_1 = 0x42 = 66 = KEYCODE_ENTER; MICROPHONE_1 = 0x54 = 84 = KEYCODE_SEARCH (this one does match openauto's voice-button mapping). MENU=0x02=2=KEYCODE_SOFT_RIGHT, not KEYCODE_MENU(82). HOME/BACK/PHONE/CALL_END (0x03-0x06) and UP/DOWN/LEFT/RIGHT/ENTER (0x13-0x17) and TOGGLE_PLAY/NEXT/PREV/PLAY/PAUSE (0x55,0x57,0x58,0x7E,0x7F) and SCROLL_WHEEL(65536) all match aasdk. Trust the aasdk KeyCode numbers, not the AACS labels.

**Two different keycodes are used for the voice/assistant button**

openauto maps its 'Input.VoiceCommandButton' to KEYCODE_SEARCH = 84 (openauto/src/autoapp/Configuration/Configuration.cpp L738), and aa-proxy-rs includes 84 in its auxiliary-display keycode list (aa-proxy-rs/src/display.rs L226). But the KeyCode enum also defines KEYCODE_VOICE_ASSIST = 231 and KEYCODE_ASSIST = 219 (aasdk KeyCode.proto L237, L225), neither of which is used by any implementation here. The de-facto steering-wheel voice button in these references is KEYCODE_SEARCH (84).

**openauto never populates touchscreen type, is_secondary, touchpad, or feedback_events_supported**

openauto/src/autoapp/Service/InputSource/InputSourceService.cpp L77-L83 sets only width and height on the single TouchScreen entry. It never sets `type` (TouchScreenType), never sets is_secondary, never adds a TouchPad, never adds feedback_events_supported, and never sets display_id. aa-proxy-rs by contrast sets type=RESISTIVE, is_secondary, and display_id (aa-proxy-rs/src/display.rs L233-L241). So all of those fields are genuinely optional on the wire and phones must handle their absence.

**InputFeedback (32772) direction and usage are not established by any reference**

aasdk's InputSourceService channel implements exactly three sends (ChannelOpenResponse, InputReport, KeyBindingResponse) and handles exactly two receives (KeyBindingRequest, ChannelOpenRequest) - see aasdk/src/Channel/InputSource/InputSourceService.cpp L83-L102. INPUT_MESSAGE_INPUT_FEEDBACK is never sent or handled. openauto likewise ignores it. The only code touching it is aa-proxy-rs's pretty-printer (aa-proxy-rs/src/mitm_prettyprint.rs L958), which is direction-agnostic. Its semantics (presumably phone -> HU haptic/audible feedback requests for touchpad interaction, given FEEDBACK_DRAG_START/DRAG_END) are inferred from the enum names only and are NOT confirmed by any source here.

**InputSourceService.TouchScreen.type is namespace-qualified in the split tree but not in the flat trees**

aasdk/protobuf/aap_protobuf/service/inputsource/InputSourceService.proto L15 writes `optional message.TouchScreenType type = 3;` (qualified into the nested `message` package), whereas aasdk/docs/protos.proto L156 and aa-proxy-rs/src/protos/protos.proto L213 write `optional TouchScreenType type = 3;` flat. Same field number, same enum, purely a packaging difference.

**AACS InputChannel descriptor is a strict subset of aasdk InputSourceService**

AACS/proto/InputChannel.proto L10-L14 defines only `repeated ButtonCode.Enum available_buttons = 1;` and `optional TouchConfig screen_config = 2;`. Field 1 matches aasdk keycodes_supported; field 2 matches aasdk touchscreen but is singular-optional rather than repeated, and TouchConfig has only width(1)/height(2) with no type or is_secondary. Fields 3 (touchpad), 4 (feedback_events_supported) and 5 (display_id) are absent from AACS entirely. Prefer the aasdk definition.

**ChannelId::INPUT_SOURCE has no literal value in the source**

aasdk/include/aasdk/Messenger/ChannelId.hpp L30-L51 declares `enum class ChannelId` with no explicit initializers except `NONE = 255`. INPUT_SOURCE is the 9th entry, so its value is 8 by C++ ordinal rules, but that number is nowhere written in the file. Furthermore this is aasdk's INTERNAL channel numbering; the actual AAP channel number for the input service is whatever `Service.id` the head unit assigns in ServiceDiscoveryResponse (see openauto InputSourceService.cpp L67 `service->set_id(static_cast<uint32_t>(channel_->getId()))` - openauto happens to reuse the aasdk enum ordinal as the wire service id). A clean-room phone must read the id from service discovery, never hard-code 8.

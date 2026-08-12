# Headway — progress

Phase status against the plan in `CLAUDE.md`. A phase is **Done** only when its
acceptance test is green in CI; anything else is In progress or Not started. The
point of this table is to be accurate, not encouraging.

| Phase | Title | Status | Acceptance test |
|-------|-------|--------|-----------------|
| 0 | Test harness first | In progress | Scripted session vs stub client, green in CI |
| 1 | Handshake | In progress | Discovery 20/20 over fake transport in CI + real BT/Wi-Fi |
| 2 | Video out | Not started | Emulator shows live screen ≥25 fps for 10 min, no stall >1 s |
| 3 | Input | Not started | Scripted touches drive a real third-party app |
| 4 | Audio + focus | Not started | TTS over speech channel with correct duck/resume on the wire |
| 5 | Voice | Not started | WAV "open calculator" launches calculator offline, <2 s |
| 6 | Reconnection, polish, packaging | Not started | All suites green, reproducible APK, docs complete |

## What works today

**59 tests green** across three modules, on a bare JDK with no device, no
Android SDK and no NDK.

- **Toolchain** — Gradle 8.14.3 / Kotlin 2.0.21 multi-module build; protobuf
  codegen wired; Android SDK 35 verified installable. CI enforces the no-GMS
  rule, GPLv3 headers, and BLOCKERS-tracked TODOs.
- **Transport** — `Transport` abstraction with a stream-backed implementation
  covering TCP, Bluetooth RFCOMM and an in-process fake. Backpressure and prompt
  EOF on peer close are tested, the latter because reconnection depends on it.
- **Framing** — full AAP frame codec: headers, flags, fragmentation, per-channel
  reassembly. Pinned by hand-derived byte fixtures.
- **Version handshake** — both roles, exchanged 20/20 consecutively over the
  fake transport, with the wire bytes pinned by fixture.
- **Protocol notes** — `docs/protocol-notes.md`, 301 constants with file+line
  citations across framing, TLS, the wireless Bluetooth handshake and the
  control channel, plus a "where the references disagree" section per area.

## Phase 1 — what is done and what is not

Done: the plaintext version exchange, 20/20 over the fake transport.

Not done, and required before Phase 1 can be called finished:

1. **TLS session.** The handshake is carried inside control-channel
   `ENCAPSULATED_SSL` messages rather than on the socket, so it needs an
   in-memory `SSLEngine` pumped by hand. Note the polarity: **the phone is the
   TLS server.** Certificate material ships in the references.
2. **Service discovery.** `ServiceDiscoveryRequest`/`Response` over the
   now-encrypted control channel, advertising the channels Headway supports.
3. **Bluetooth RFCOMM handshake.** The protobuf exchange that yields the car's
   Wi-Fi credentials — service UUID `4de17a00-52cb-11e6-bdf4-0800200c9a66`, TCP
   port 5288, both confirmed across multiple references and documented.
4. **Wi-Fi join with a bound network.** `WifiNetworkSpecifier` plus socket
   binding to the returned `Network`, because the car's AP has no internet and
   Android will not route to it by default.
5. **Real-hardware run.** Cannot be done here at all — see B-001.

Items 1–2 are pure JVM and fully testable in CI. Items 3–4 are Android-only and
will be written against the interfaces in `core-transport`, exercised over the
fake transport, and remain unverified until someone runs them on a phone.

## Next action

Implement the TLS session over `ENCAPSULATED_SSL` control messages using
`javax.net.ssl.SSLEngine` in server mode, with the certificate material from
`references/aasdk/cert/`, and extend the Phase 1 acceptance test to run the full
handshake through to `AUTH_COMPLETE`.

## What cannot be done in this environment

Stated so the gap between "CI green" and "works in a car" is never implied away:

- **No real hardware.** No phone, no Bluetooth radio, no Wi-Fi AP, no car. Every
  Bluetooth and Wi-Fi path is written against the references and exercised over
  the fake transport only.
- **Real-car validation is impossible here**, and `CLAUDE.md` acknowledges it as
  the one step that cannot be self-performed. The compensating design — frame
  logging and in-app log export — is a Phase 6 deliverable.
- **On-device acceptance criteria** (Phases 2–5 measure fps, latency and gesture
  injection on a physical Pixel) can be implemented and unit-tested here, but
  their acceptance tests need a device to pass.
- **A green emulator run is not proof of car compatibility.** The emulator shares
  `core-protocol` with the phone, so a wrong-but-symmetric constant round-trips
  cleanly. The byte fixtures and, later, Google's DHU are the real oracle. See
  ADR 0002.

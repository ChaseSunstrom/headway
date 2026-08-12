# Headway — progress

Phase status against the plan in `CLAUDE.md`. The point of this table is to be
accurate, not encouraging.

Each phase carries the **tier of evidence** behind it, defined in
[`docs/completion-plan.md`](docs/completion-plan.md):

- **A — Executed:** real code runs against real bytes and is asserted in CI.
- **B — Framework-executed:** runs on a real Android device or emulator image.
- **C — Compiled:** type-checked against the real Android SDK; behaviour unverified.
- **D — Unverifiable here:** needs a phone, a car, or both.

| Phase | Title | Status | Evidence |
|-------|-------|--------|----------|
| 0 | Test harness | **Done** | A — the emulator drives every acceptance test in CI |
| 1 | Handshake | **Done (CI half)** | A — full bring-up 20/20 over the fake transport *and* over real TCP; D for the real BT/Wi-Fi half |
| 2 | Video out | **Done (channel)** | A — 10 min of 30 fps stream in order, byte-identical, real NAL parsing; C for `MediaCodec` capture |
| 3 | Input | **Done (channel)** | A — event decode and letterbox transform; C for `AccessibilityService` dispatch |
| 4 | Audio + focus | In progress | A — focus states modelled; the channel is landing |
| 5 | Voice | **Done (pipeline)** | A — real Vosk on real speech, "open calculator" resolved in ~720 ms; C for `startActivity` |
| 6 | Reconnection, polish, packaging | In progress | A — supervisor; B — the APK installs on a real API 35 device |

## What is genuinely verified

**200+ tests green** on a bare JDK, plus an APK that installs on a real device.

- **Framing** — the full AAP frame codec, pinned by hand-derived byte fixtures.
- **TLS** — a real handshake between the two roles with the real vendored
  certificates, negotiating ECDHE_RSA TLS 1.2.
- **Session** — version, TLS, auth, service discovery and channel open, 20/20
  consecutive over the in-process fake **and** over genuine kernel sockets.
- **Bluetooth handshake** — the RFCOMM credentials exchange over the fake
  transport, including the malformed and hostile cases.
- **Video channel** — ten minutes of 30 fps stream time, in order, with no gap
  over one second, H.264 bytes bit-identical, and real Annex-B NAL parsing.
- **Input channel** — event decode and a letterbox-aware coordinate transform.
- **Voice** — the real Vosk model on real synthesised speech at the car mic's
  exact format; all six commands resolved correctly, all under 750 ms.
- **Reconnection** — the backoff and state machine, driven by induced failures.
- **Android** — the debug APK builds and installs on an API 35 AOSP image, and
  the platform reports exactly the intended permission set, with no location.

## What is not verified, and cannot be here

Stated so the gap between "CI green" and "works in a car" is never implied away.

- **No car, no phone, no radio.** Bluetooth and Wi-Fi association,
  `MediaCodec` screen capture, `AccessibilityService` gesture dispatch and A2DP
  coexistence are compiled and type-checked, never executed. See B-001.
- **The public phone certificate expired in 2022** and cannot be reissued
  without Google's CA key. This is the single most likely reason a real Malibu
  refuses the session. See B-003.
- **A green emulator run proves self-consistency, not car compatibility.** The
  emulator shares `core-protocol` with the phone, so a wrong-but-symmetric
  constant round-trips cleanly. The byte fixtures — and later Google's DHU —
  are the real oracle. See ADR 0002.
- **Latency and fps on hardware** (the 250 ms touch-to-photon budget, ≥25 fps
  sustained) are properties of a Pixel's encoder and cannot be measured from a
  JVM test.

## Next actions

1. Land the audio and microphone channels with their acceptance tests.
2. Wire the Android adapters — `MediaProjection` to `MediaCodec`,
   `AccessibilityService` gesture dispatch, `WifiNetworkSpecifier` binding and
   the RFCOMM socket — onto the protocol objects that already exist.
3. Build the car-facing launcher UI, quirk configuration and in-app log export.
4. Reproducible release build and F-Droid metadata.

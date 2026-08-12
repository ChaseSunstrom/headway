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
| 2 | Video out | **Done** | A — 10 min of 30 fps stream in order, byte-identical, real NAL parsing; B — device encodes real H.264 with SPS/PPS; C for `MediaProjection` capture |
| 3 | Input | **Done** | A — event decode and letterbox transform; B — gesture building on-device; C for gesture *dispatch* (needs an enabled service) |
| 4 | Audio + focus | **Done** | A — three sinks, focus duck/resume asserted on the wire; B — resampling and AudioManager focus on-device; D for real A2DP |
| 5 | Voice | **Done** | A — real Vosk on real speech, "open calculator" resolved in ~720 ms; C for `startActivity` |
| 6 | Reconnection, polish, packaging | **Done (except release signing)** | A — supervisor, quirks, log redaction; B — APK installs and 29 app tests pass on a real device |

## What is genuinely verified

**222 JVM tests green** on a bare JDK, plus **71 instrumentation tests green on a
real Android 15 (API 35) AOSP device** — app 29, core-audio 13, core-input 12,
core-video 17.

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
- **Audio** — the three sinks with their advertised formats, and the focus
  exchange asserted as an ordered message sequence on the wire.
- **Microphone** — car-mic PCM decoded off the AV-input channel.
- **Reconnection** — the backoff and state machine, driven by induced failures.
- **Android, on the device** — the debug APK builds, installs and runs its tests
  on an API 35 AOSP image with zero Play Services packages present. The frame
  codec produces byte-identical output on ART to the JVM fixtures. The device
  encodes real H.264 for the advertised configuration, with SPS and PPS in
  csd-0, keyframes, and rising presentation timestamps. Gesture building, PCM
  resampling and AudioManager focus all run on-device.

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

## What is left

1. **Real-hardware validation.** The one step that cannot be self-performed, and
   the only thing between this and a working car link.
2. **Release signing and F-Droid metadata.** The build is reproducible in shape;
   it has no signing key, which is the user's to hold.
3. **A replacement phone certificate**, if a real head unit rejects the expired
   one. Nothing in this project can produce it — see B-003.

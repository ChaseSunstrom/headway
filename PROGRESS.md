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
| 1 | Handshake | **Done (CI half)** | A — full bring-up 20/20 over the fake transport *and* over real TCP; the Bluetooth version exchange is now pinned to bytes captured from a real Chevrolet head unit; D for the rest of the real BT/Wi-Fi half |
| 2 | Video out | **Module done, not wired** | A — 10 min of 30 fps stream in order, byte-identical, real NAL parsing; B — device encodes real H.264 with SPS/PPS; C for `MediaProjection` capture |
| 3 | Input | **Module done, not wired** | A — event decode and letterbox transform; B — gesture building on-device; C for gesture *dispatch* (needs an enabled service) |
| 4 | Audio + focus | **Module done, not wired** | A — three sinks, focus duck/resume asserted on the wire; B — resampling and AudioManager focus on-device; D for real A2DP |
| 5 | Voice | **Module done, not wired** | A — real Vosk on real speech, "open calculator" resolved in ~720 ms; C for `startActivity` |
| 6 | Reconnection, polish, packaging | **Done (except release signing)** | A — supervisor, quirks, log redaction; B — APK installs and 29 app tests pass on a real device |

## The gap between "module done" and "the app does it"

Phases 2 to 5 build and test real code, and none of it runs in the shipped app.
`HeadwayService.runChannels` — the loop that owns a live session — receives
frames, answers control keepalives, and logs everything else as unhandled.
There is no subclass and no override. So on a phone that reaches a session
today: no screen is captured, no video is encoded, no touch arrives, no audio
is sent, and no voice is recognised.

The modules themselves are real and tested, so this is integration rather than
implementation: obtain a `MediaProjection` from `MainActivity`, feed
`ScreenEncoder` into `VideoChannel`, route `InputChannel` events into
`CarGestureDispatcher`, and drive `MicrophoneChannel` into the voice pipeline.
Two things have to land with it: `core-voice` declares Vosk `compileOnly` and
the app adds no `vosk-android` runtime, so speech recognition would throw
`NoClassDefFoundError` on device; and the ~41 MB model is not shipped as an
asset.

This is stated here because the phase table above says "Done" for those phases
and would otherwise be read as "the app does this". It does not, yet — and
until the Wi-Fi join succeeds on a real car, none of it can be exercised
against one anyway.

## What is genuinely verified

**248 JVM tests green** on a bare JDK, plus **69 instrumentation tests green on a
real Android 15 (API 35) AOSP device** — app 29, core-audio 13, core-input 12,
core-video 15.

One environment caveat, since it looks like a failure and is not: this machine
has no KVM, so the emulator runs under software emulation and its software AVC
encoder becomes unstable after sustained use, eventually killing the
instrumentation process. core-video's suite is therefore run in two batches
here. Every test listed above has passed on the device; none of them has ever
failed on it.

- **Framing** — the full AAP frame codec, pinned by hand-derived byte fixtures.
- **TLS** — a real handshake between the two roles with the real vendored
  certificates, negotiating ECDHE_RSA TLS 1.2.
- **Session** — version, TLS, auth, service discovery and channel open, 20/20
  consecutive over the in-process fake **and** over genuine kernel sockets.
- **Bluetooth handshake** — the RFCOMM credentials exchange over the fake
  transport, including the malformed and hostile cases, plus the version
  exchange pinned to **bytes captured from a real 2021 Chevrolet Infotainment 3
  head unit**. That capture found a real bug: Headway was answering the car's
  version request with a non-success status, and the car was quietly giving up.
  See `docs/protocol-notes.md` § "Evidence from a real head unit".
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

- **No car, no phone, no radio in the build environment.** Wi-Fi association,
  `MediaCodec` screen capture, `AccessibilityService` gesture dispatch and A2DP
  coexistence are compiled and type-checked, never executed. See B-001. The one
  exception is the Bluetooth version exchange, which a user has now run against
  a real vehicle and whose bytes are pinned as a fixture — everything after it
  on the real link is still unrun.
- **The public phone certificate expired in 2022** and cannot be reissued
  without Google's CA key. A real Malibu *does* refuse the session over it —
  confirmed, not predicted. Headway now presents the two unexpired certificates
  signed by the same CA in turn before giving up, but they were issued for the
  head-unit role and whether a car accepts one from the phone side has not been
  tried in a car. See B-003.
- **A green emulator run proves self-consistency, not car compatibility.** The
  emulator shares `core-protocol` with the phone, so a wrong-but-symmetric
  constant round-trips cleanly. The byte fixtures — and later Google's DHU —
  are the real oracle. See ADR 0002.
- **Latency and fps on hardware** (the 250 ms touch-to-photon budget, ≥25 fps
  sustained) are properties of a Pixel's encoder and cannot be measured from a
  JVM test.

## Where the real car currently gets to

A 2021 Chevrolet Infotainment 3 unit. The last capture is from build 32; what
follows is where that build got to and what has changed since.

- **Bluetooth: works, every time.** SDP lookup, RFCOMM connect on channel 3,
  version exchange, and the credentials handshake all complete in under a
  second. The car hands over SSID, passphrase, BSSID and — inconsistently —
  its endpoint, 192.168.5.1:7001.
- **Wi-Fi association: works, with the BSSID pinned.** The instrumented build
  settled this. The phone authenticates and associates with the car's access
  point; the log's `IP_PROVISIONING` verdict is only reachable after that
  succeeds.
- **DHCP: the current wall.** The head unit accepts the phone onto the radio
  and then never issues it an address. GrapheneOS's default per-connection MAC
  randomization makes every attempt a new device to the head unit, whose DHCP
  table is small and does not evict; GrapheneOS documents the failure. Android
  exposes no way for an app to influence the MAC of a `WifiNetworkSpecifier`
  connection, so Headway now reports it, stops retrying (each retry consumes
  another address), and tells the user the two steps that clear it. See the
  README.

What has changed since that capture, in rough order of how likely each is to be
the cause:

1. **The BSSID is pinned again**, alternating with SSID-only matching. The one
   join this project has ever completed was pinned (`docs/protocol-notes.md`
   §"The third capture"); the builds that matched on SSID alone could not join
   at all, and GM puts the vehicle hotspot and the projection access point on
   the same SSID across two BSSs.
2. **The join no longer cancels itself.** 75 s was passed to `requestNetwork`'s
   timeout variant, which does not merely stop waiting — it releases the
   request and tears down whatever association the platform is mid-way through.
   AOSP allows up to 120 s measured from the user's tap.
3. **Bluetooth is now serviced for the whole join**, and keepalive pings
   (message ids 8/9) are answered. Nothing read that socket during the join
   before, so a head unit pinging into it heard nothing back.
4. **The security mode decodes correctly.** The enum was numbered sequentially
   where every reference uses a bitfield, so wire value 8 read as
   `WPA2_ENTERPRISE` instead of `WPA2_PERSONAL`, and four legal values would
   have failed the parse outright.
5. **The failure now says what happened**: the platform's own verdict where it
   gives one, a scan counter, screen and keyguard state, process importance,
   whether the phone associated with anything at all, and a heartbeat every
   five seconds. A capture that stalls again should be diagnosable from the log
   alone rather than needing a theory.

`hiddenSsid`, `pinBssid` and `announceWifiChannel` are in the quirk file, so the
remaining hypotheses can be tested with a text edit instead of a rebuild.

## What is left

1. **Real-hardware validation.** The one step that cannot be self-performed, and
   the only thing between this and a working car link.
2. **Release signing and F-Droid metadata.** The build is reproducible in shape;
   it has no signing key, which is the user's to hold.
3. **A car log saying which certificate the Malibu accepts**, if any. The
   rotation through the two unexpired candidates is a well-founded guess with a
   cheap test attached; one drive settles it. If all three are refused, a
   replacement pair is the user's to obtain — nothing in this project can
   produce one. See B-003.

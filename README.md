# Headway

An open-source Android app that replaces Google's Android Auto phone app
entirely. It speaks the reverse-engineered Android Auto Protocol (AAP) directly
to a factory head unit — wirelessly — and casts *any* app on the phone to the car
screen, with touch input coming back from the car and the car's microphone driven
by fully on-device speech recognition.

No Google services. No network. No root. No system privileges.

> **Status: in development. Only the Bluetooth handshake has ever reached a
> real car.** The protocol stack, the test harness and the voice pipeline are
> built and exercised in CI; the Android adapters that touch real radios and
> real hardware encoders are written but, past the Bluetooth exchange, never
> executed. [`PROGRESS.md`](PROGRESS.md) records the tier of evidence behind
> every phase — read it before believing anything here works.

## Why

Android Auto is the only sanctioned way to get your phone onto a modern car's
screen, and it is closed, requires Google Play services, phones home, and only
runs the handful of apps Google approves. The protocol underneath it has been
reverse-engineered for years. Headway is what happens when you implement the
phone side of that protocol yourself: your car screen becomes an ordinary
external display for your phone, and *you* decide what runs on it.

## Design constraints

These are hard rules, not preferences:

- **Wireless only.** Bluetooth is used solely for discovery and the credentials
  handshake; the session itself is TCP over the head unit's 5 GHz Wi-Fi. No USB.
- **Nothing privileged.** No `INJECT_EVENTS`, no hidden APIs, no root, no system
  signature. If a feature needs privilege, it does not ship — it gets documented
  in [`BLOCKERS.md`](BLOCKERS.md) with whatever unprivileged workaround exists.
- **Fully local.** No telemetry, no update checks, no cloud speech recognition,
  no Google endpoints. Works in airplane mode with Wi-Fi and Bluetooth on. The
  car's access point has no internet and the app never assumes otherwise.
- **No protocol constant is guessed.** Every message id, port, channel id and
  schema is transcribed from a reference implementation and cited, file and line,
  in [`docs/protocol-notes.md`](docs/protocol-notes.md).

## Target hardware

Developed against a **2021 Chevrolet Malibu** (Chevrolet Infotainment 3, wireless
Android Auto) and **GrapheneOS on Pixel**. The head unit is treated as a generic
wireless AAP unit — no Malibu-specific behaviour is hardcoded; quirks are
configuration.

## Architecture

The entire protocol stack is **pure Kotlin/JVM with no Android dependencies**
([ADR 0001](docs/adr/0001-kotlin-protocol-core.md)). That is the load-bearing
decision in this repo: it means the head-unit emulator links the same code from
the other side, and the full handshake plus every channel state machine runs
under `./gradlew test` on a bare JDK — no device, no NDK, no emulator image.

```
core-protocol/      AAP framing, channel multiplexing, protobuf schemas     (JVM)
core-transport/     TCP, in-memory TLS, Bluetooth handshake codec, fakes    (JVM)
headunit-emulator/  Head-unit test harness; hosts the acceptance suite      (JVM)
app/                UI, foreground service, permissions, Bluetooth, Wi-Fi   (Android)
core-video/         MediaProjection capture -> MediaCodec H.264             (Android)
core-input/         Car touch/keys -> AccessibilityService gestures         (Android)
core-audio/         Audio channels, focus signalling, A2DP coexistence      (Android)
core-voice/         Car mic -> on-device STT -> command engine              (JVM)
```

## Building

Requires a JDK 17+ (JDK 21 works). The Android SDK is only needed for the
`app/` module and Android libraries.

```bash
# Protocol core and its byte-fixture tests — no Android SDK required
./gradlew :core-protocol:test :core-transport:test

# Phase acceptance suite against the head-unit emulator
./gradlew :headunit-emulator:test

# Voice pipeline against a real speech model (fetch it first, ~41 MB)
./tools/fetch-vosk-model.sh
./gradlew :core-voice:test

# Hard-constraint checks
./gradlew checkNoGms
./tools/check-license-headers.sh
./tools/check-todos.sh
```

## Verifying it yourself

The emulator is a runnable head unit, so you can check the stack without a car.

```bash
# 1. On your laptop alone. Proves framing, TLS, auth, discovery, channel open.
./gradlew :headunit-emulator:run --args="--self-test"

# 2. Wait for your phone. Prints the addresses to point Headway at.
./gradlew :headunit-emulator:run --args="--listen"

# 3. Check reachability without a phone, from this or another machine.
./gradlew :headunit-emulator:run --args="--connect 192.168.1.50"
```

`--listen` runs a real head unit on TCP 5288 and reports what arrives: codec
configuration, video frame count, and the frame rate measured from the
presentation timestamps. That is the strongest verification available short of a
car — a real phone, real sockets, real TLS, real video off a real encoder.

Two honest caveats. `--self-test` shares protocol code with the phone side, so it
proves self-consistency rather than correctness ([ADR 0002](docs/adr/0002-jvm-headunit-emulator.md)).
And none of it is evidence about a Chevrolet; only a Chevrolet is. When one was
finally asked, it disagreed — see
[`docs/protocol-notes.md`](docs/protocol-notes.md) § "Evidence from a real head
unit" for what a single real capture cost and bought.

## How much of this is actually tested

The honest answer matters more than a badge, so it is written down. Every phase
in [`PROGRESS.md`](PROGRESS.md) carries a tier:

- **Executed** — the real code runs against real bytes in CI. The framing, TLS
  handshake, session bring-up, Bluetooth credentials exchange, video and input
  channels, reconnection logic, and the whole voice pipeline are here. The voice
  tests run a real speech model over real recorded speech; the session tests run
  over genuine kernel sockets as well as an in-process fake.
- **Compiled** — type-checked against the real Android SDK and never run:
  `MediaCodec`, `MediaProjection`, `AccessibilityService`, Bluetooth sockets,
  Wi-Fi binding.
- **Unverifiable without hardware** — anything measured in a car.

One caveat worth stating plainly: the head-unit emulator shares its protocol
code with the phone, so a wrong-but-symmetric constant round-trips cleanly and
proves nothing. The byte fixtures are the real oracle. See
[ADR 0002](docs/adr/0002-jvm-headunit-emulator.md).

For the Android modules, point `ANDROID_HOME` at an SDK with platform 35 and
build-tools 35.0.0, or create a `local.properties` with `sdk.dir=...`.

## Working on the protocol

aasdk's 254 protobuf schemas *are* vendored, under
`core-protocol/src/main/proto/aap_protobuf/` — retyping them is the most
error-prone option available, since one wrong field number parses locally and is
rejected by a real head unit. They keep aasdk's authorship; see
[`THIRD-PARTY.md`](THIRD-PARTY.md).

The reference implementations themselves are not vendored. Clone them locally
(they are gitignored) to follow the citations:

```bash
mkdir -p references && cd references
git clone --depth 1 https://github.com/opencardev/aasdk.git
git clone --depth 1 https://github.com/tomasz-grobelny/AACS.git
git clone --depth 1 https://github.com/nisargjhaveri/WirelessAndroidAutoDongle.git
git clone --depth 1 https://github.com/manio/aa-proxy-rs.git
git clone --depth 1 https://github.com/opencardev/openauto.git
```

`docs/protocol-notes.md` cites these by path and line. When references disagree,
aasdk's protobufs win and the disagreement is recorded rather than resolved
silently.

## Licence

GPLv3. Headway derives its wire format from aasdk, which is GPLv3, so the whole
project is and must remain GPLv3. See [`LICENSE`](LICENSE).

## Safety

Video on a car screen while driving is your responsibility and is illegal in many
jurisdictions. Headway shows this notice on first run and offers an optional
parked-only mode for video apps. It is off by default — this is a user-freedom
project, and the choice is yours to make.

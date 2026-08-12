<!--
This file is part of Headway.
Copyright (C) 2026 The Headway Authors
SPDX-License-Identifier: GPL-3.0-or-later
-->

# Completion plan — Phases 2 to 6

How the remaining phases get built, and — more importantly — how each one is
*actually verified* rather than asserted.

## What "verified" can mean here, precisely

There is no phone, no Bluetooth radio, no Wi-Fi AP and no car in the build
environment, and no KVM either, so a hardware-accelerated Android emulator will
not run. Pretending otherwise would make every claim below worthless, so each
piece of work is tagged with the strongest verification actually available to it:

| Tier | What it means | Applies to |
|---|---|---|
| **A — Executed** | Real code runs and is asserted against in CI, on real bytes | Protocol channels, framing, TLS, session, STT |
| **B — Framework-executed** | Real Android framework code runs under Robolectric (not hand-written mocks) | Android service/lifecycle/manifest logic |
| **C — Compiled** | Type-checked against the real Android SDK; behaviour unverified | `MediaCodec`, `MediaProjection`, `AccessibilityService`, Bluetooth, Wi-Fi |
| **D — Unverifiable here** | Needs a phone or a car | Latency budget, fps on hardware, real-car compatibility |

The rule: **no phase is reported Done above the tier its evidence supports.**
`PROGRESS.md` records the tier per phase.

## Sequencing

Protocol first, Android second. Everything in the protocol layer is Tier A, and
the Android layer is a thin adapter over it — so the more behaviour that lives
below the Android boundary, the more of the system is genuinely executed. This
is the same reasoning as ADR 0001, applied to feature work.

### Stage 1 — Protocol channels (Tier A)

Four independent channel implementations in `core-protocol`, each with an
emulator counterpart in `headunit-emulator` and byte-level tests:

1. **Video** — AV channel setup/start/config/ack, video focus, H.264 frame
   carriage with the timestamp header. Verified by feeding **real H.264 NAL
   units** through the channel and asserting the emulator reassembles a
   bit-identical stream, plus NAL-boundary parsing.
2. **Input** — `InputEventIndication` decode, touch/key events, and the
   letterbox-aware coordinate transform from head-unit to phone space. Verified
   with a property test over resolutions and aspect ratios.
3. **Audio** — the three audio sinks, their configurations, and audio focus on
   the control channel. Verified by round-tripping real PCM and asserting
   duck/resume message sequences on the wire.
4. **Microphone (AV input)** — `MicrophoneRequest`/`Response` and PCM streaming
   from car to phone. Verified with a generated WAV.

### Stage 2 — Voice pipeline (Tier A — genuinely executed)

Phase 5's acceptance criterion is "WAV-injected 'open calculator' launches the
calculator, end-to-end under 2 s, no network". The recogniser half of that is
fully executable here: **Vosk runs on the JVM**, so a real model transcribes a
real WAV and a real command engine dispatches it. Only the final `startActivity`
is Tier C.

Verified by: real Vosk model, real audio, asserted transcript and dispatch, with
the elapsed time measured — and network access is not merely absent but
irrelevant, since Vosk is entirely local.

### Stage 3 — Android modules (Tiers B and C)

`app`, `core-video`, `core-input`, `core-audio`, `core-voice`. Each is a thin
adapter onto a Tier A protocol object. Robolectric covers service lifecycle,
manifest wiring, permission flows and the launcher UI; encoder and capture calls
are Tier C and marked as such in-tree.

### Stage 4 — Phase 6 (mixed)

Reconnection state machine (Tier A — it is pure logic over the transport
interface and is tested with induced failures), quirk configuration, in-app log
export, settings, reproducible release build, README.

### Stage 5 — Review and hardening

An adversarial pass over everything: correctness review of each new channel
against `docs/protocol-notes.md`, plus the full suite green.

## Acceptance mapping

Each phase's CLAUDE.md criterion, and what is achievable:

| Phase | Criterion | Achievable here | Gap |
|---|---|---|---|
| 2 | Emulator shows live screen ≥25 fps, 10 min, no stall >1 s | Channel carries real H.264 at rate with no stall, measured | Real `MediaCodec` capture (Tier C) |
| 3 | Scripted touches operate a real third-party app | Events decode and transform correctly (Tier A); gesture dispatch compiled (Tier C) | Needs a device |
| 4 | TTS over speech channel while music plays, correct duck/resume on the wire | Full wire sequence asserted (Tier A) | Real A2DP (Tier D) |
| 5 | "open calculator" recognised offline, <2 s | Recognition and dispatch executed with real Vosk (Tier A) | `startActivity` (Tier C) |
| 6 | Suites green, reproducible APK, docs complete | All of it, given an SDK | Signing keys |

## Standing rules

- Every constant still comes from `docs/protocol-notes.md` with a citation.
- Every `TODO(B-NNN)` gets a `BLOCKERS.md` entry; CI enforces it.
- No mocks standing in for logic under test. Fakes are transports and hardware
  boundaries only — never the protocol itself.

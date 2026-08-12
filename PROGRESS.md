# Headway — progress

Phase status against the plan in `CLAUDE.md`. A phase is **Done** only when its
acceptance test is green in CI; anything else is In progress or Not started.

| Phase | Title | Status | Acceptance test |
|-------|-------|--------|-----------------|
| 0 | Test harness first | In progress | Scripted session vs stub client, green in CI |
| 1 | Handshake | In progress | Discovery 20/20 over fake transport in CI + real BT/Wi-Fi |
| 2 | Video out | Not started | Emulator shows live screen ≥25 fps for 10 min, no stall >1 s |
| 3 | Input | Not started | Scripted touches drive a real third-party app |
| 4 | Audio + focus | Not started | TTS over speech channel with correct duck/resume on the wire |
| 5 | Voice | Not started | WAV "open calculator" launches calculator offline, <2 s |
| 6 | Reconnection, polish, packaging | Not started | All suites green, reproducible APK, docs complete |

## Current state

**Foundation is in place and the toolchain is verified green:**

- Gradle 8.14.3 / Kotlin 2.0.21 multi-module build, wrapper checked in.
- `core-protocol`, `core-transport`, `headunit-emulator` compile; protobuf-javalite
  code generation wired up with protoc resolved from Maven Central.
- Android SDK 35 + build-tools 35.0.0 verified installable and reachable.
- GPLv3 in place; CI enforces licence headers, TODO tracking, and the no-GMS rule.
- ADR 0001 (Kotlin protocol core, no JNI/aasdk) and ADR 0002 (JVM emulator)
  written and the reasoning recorded.
- All five reference implementations cloned into `references/` (gitignored).

## Next action

Finish transcribing the AAP wire format into `docs/protocol-notes.md` with a
source citation per constant, then implement `core-protocol` framing against
byte fixtures taken from those references.

This is deliberately the first code written: `CLAUDE.md` hard constraint §4
forbids guessing any protocol constant from memory, so the citation pass gates
the implementation rather than following it.

## What cannot be done in this environment

Recorded here so the gap between "CI green" and "works in a car" is never
implied away:

- **No real hardware.** No phone, no Bluetooth radio, no Wi-Fi AP, no car. Every
  Bluetooth/Wi-Fi code path is written against the reference implementations and
  exercised over the fake transport only.
- **Real-car validation is impossible here** and is explicitly the one step
  `CLAUDE.md` acknowledges cannot be self-performed. The compensating design —
  frame-level debug logging and in-app log export — is a Phase 6 deliverable.
- **On-device acceptance criteria** (Phases 2–5 measure fps, latency and
  gesture injection on a physical Pixel) can be implemented and unit-tested here,
  but their acceptance tests require a device to actually pass.

# ADR 0002 — The head-unit emulator is a JVM application, not an openauto fork

- **Status:** Accepted
- **Date:** 2026-08-12
- **Deciders:** Headway maintainers

## Context

`CLAUDE.md` Phase 0 requires a head-unit emulator and suggests basing it on
openauto/aasdk:

> **Phase 0 — Test harness first.** Build `headunit-emulator`: a Linux desktop
> app (base it on openauto/aasdk) that emulates a *wireless* AA head unit […]
> Every subsequent phase's acceptance test runs against this harness, automated
> in CI.

The harness is the foundation of every later acceptance test, so its build must
be trivially reproducible on a CI runner.

## Decision

Build `headunit-emulator` as a **Kotlin/JVM application** in the same Gradle
build, depending on `core-protocol` and `core-transport`.

openauto and aasdk remain in `references/` as the authoritative sources for
protocol behaviour — we read them constantly — but no openauto code is linked or
forked.

## Rationale

Following from ADR 0001, the protocol lives in shared Kotlin modules. Building
the emulator on the same modules means:

- **The emulator is free.** It is a few hundred lines of driver code over
  `core-protocol` — channel state machines already exist; the emulator supplies
  the head-unit polarity.
- **One build, one command.** `./gradlew :headunit-emulator:run` on a bare JDK.
  An openauto-based harness needs Qt5, Boost, OpenSSL, libusb, ALSA, gstreamer
  and a working X display before it prints anything — a heavy and fragile CI
  dependency for a container that has no GPU or sound device.
- **CI needs no display.** The emulator's "renderer" writes decoded frame
  metadata and optional raw H.264 to disk instead of opening a window, so it runs
  headless. A `--display` flag can attach a real renderer for local use.

### The important caveat: an emulator that shares code is a weak oracle

If the emulator and the phone both use `core-protocol`, then a wrong-but-symmetric
framing constant round-trips perfectly and the test still passes. This is the
central risk of the decision and it is mitigated deliberately, not hand-waved:

1. **Byte fixtures are the real oracle.** `core-protocol`'s unit tests assert
   framing against hex fixtures transcribed from the references, not against
   round-trips. A shared bug fails these.
2. **Constants are cited, not invented.** `docs/protocol-notes.md` cites a file
   and line in `references/` for every constant, so correctness is auditable
   against an independent implementation.
3. **Independent second target.** Google's DHU (wireless mode) is the
   cross-check, since it shares no code with us. `CLAUDE.md` correctly treats DHU
   compatibility as a stretch goal, so it gates nothing, but it is how we find
   symmetric bugs.

## Consequences

**Positive**

- Phase 0 acceptance test runs in CI on a stock JDK container, headless.
- Emulator and app evolve together; a protocol change cannot desynchronise them.
- Contributors need no C++ toolchain to work on Headway at all.

**Negative**

- Shared-code blindness, mitigated above. This is the one thing to stay honest
  about: **a green harness run is evidence of self-consistency, and only the byte
  fixtures and DHU make it evidence of correctness.**
- The emulator is not a bit-exact model of a Chevrolet Infotainment 3 unit. It
  models the protocol, not the quirks. Real-car quirks are handled by the
  per-head-unit quirk config (Phase 6) and diagnosed from user-supplied logs.

## Alternatives considered

- **Fork openauto** — heavy dependency chain, needs a display, C++ toolchain for
  all contributors, and still would not model the Malibu's quirks.
- **Run DHU as the only harness** — DHU is closed-source, cannot be scripted at
  the byte level, cannot be extended with a fake mic feed, and is unavailable in
  CI. Excellent as a cross-check, unusable as the primary gate.

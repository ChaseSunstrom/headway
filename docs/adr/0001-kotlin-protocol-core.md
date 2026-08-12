# ADR 0001 — Implement the AAP protocol layer in pure Kotlin, not aasdk via JNI

- **Status:** Accepted
- **Date:** 2026-08-12
- **Deciders:** Headway maintainers

## Context

`CLAUDE.md` leaves this choice explicitly open:

> Single Android app (Kotlin) + one native library (C++ via JNI, embedding aasdk
> or a from-scratch port of its protocol layer — your choice; document the
> decision in `docs/adr/`).

The two candidates:

1. **Embed aasdk** (C++) through JNI. aasdk is the canonical reverse-engineered
   AAP implementation and would give us framing, TLS and channel multiplexing
   more or less for free.
2. **Port the protocol layer to Kotlin** from aasdk's protobufs and headers,
   keeping the whole stack on the JVM.

## Decision

**Port the protocol layer to Kotlin.** No JNI, no NDK, no native library.

## Rationale

### Testability is the deciding factor

Headway's development plan is gated on automated acceptance tests (Phase 0 exists
solely to build the harness that all later phases test against). The protocol
layer must therefore be exercisable in CI on a plain Linux runner.

A pure-JVM protocol core means:

- `core-protocol` and `core-transport` are ordinary Kotlin/JVM libraries. Their
  tests run under `./gradlew test` with nothing but a JDK — no Android SDK, no
  emulator, no NDK toolchain, no device.
- The head-unit emulator (`headunit-emulator`) links **the same** framing and
  channel code, driven from the head-unit side. Every byte the emulator produces
  is produced by the code under test, so a framing bug cannot hide by being
  symmetric — the tests assert against captured byte fixtures, not just
  round-trips.
- Phase 0 and Phase 1 acceptance tests are therefore genuinely runnable in CI,
  which is the difference between a real gate and an aspirational one.

Embedding aasdk would have forced the protocol tests to be either Android
instrumentation tests (needing a device/emulator in CI) or a separate native test
binary built with a second toolchain, and would have made the emulator a third,
independently-built C++ artifact.

### aasdk is a head-unit-shaped library

aasdk implements the **head unit** side. Headway is the **phone** side. Every
channel class would need its request/response polarity inverted, so "embedding"
aasdk overstates the reuse — we would be using its protobufs and its framing
constants, and rewriting its channel logic anyway. The protobufs and constants
are exactly the parts we can take without linking the C++ (see *Licensing*).

### Boring technology

`CLAUDE.md` asks for boring technology. On Android, "no native code" is
substantially more boring than "native code plus a JNI bridge plus a
cross-compiled OpenSSL plus a cross-compiled Boost." aasdk depends on Boost.Asio
and OpenSSL; cross-compiling both for four ABIs, keeping them patched, and
shipping ~8 MB of native libraries per ABI is a large, permanent maintenance
cost.

### The JVM has what we need

The two things one would reach to C++ for are both first-class on the JVM:

- **TLS.** AAP carries its TLS handshake *inside* control-channel messages
  rather than on the raw socket, so an ordinary `SSLSocket` is unusable in either
  language — you need an in-memory TLS state machine you pump by hand. Java's
  `javax.net.ssl.SSLEngine` is precisely that, and it is a supported public API.
  OpenSSL's `BIO` pair (what aasdk uses) is the C++ equivalent. Neither is
  simpler; the JVM one needs no cross-compilation.
- **H.264 encoding.** Handled by `MediaCodec`, which is a hardware-backed Android
  framework API. A native encoder would be slower and would burn battery.

### Licensing

Headway is GPLv3 (`CLAUDE.md` §5). This is unchanged by the decision: we derive
our wire format from aasdk's GPLv3 sources and vendor its `.proto` files, so the
project is a derivative work of aasdk either way. Not linking the C++ does not
dilute that obligation and we do not claim it does — every vendored file keeps
its provenance recorded in `docs/protocol-notes.md`.

## Consequences

**Positive**

- `./gradlew test` on a bare JDK exercises the entire protocol stack.
- One language, one build, one debugger for everything above the socket.
- No native crash surface (no SIGSEGV in a codec buffer taking down the app).
- APK shrinks by several MB per ABI; no ABI splits needed for the protocol.

**Negative**

- We must port aasdk's framing and channel logic by hand, and porting is where
  reverse-engineered protocol detail gets silently dropped. Mitigation: every
  constant is transcribed with a file+line citation into
  `docs/protocol-notes.md`, and byte-level fixtures in `core-protocol`'s tests
  pin the wire format.
- We inherit no future aasdk bug fixes automatically. Mitigation: the citations
  make it mechanical to diff our constants against an updated aasdk.
- JVM allocation pressure on the video path is a real risk at 30 fps.
  Mitigation: the video channel writes from pooled direct `ByteBuffer`s rather
  than allocating per frame.

## Alternatives considered

- **aasdk via JNI** — rejected above.
- **Rust core via JNI** (following `aa-proxy-rs`) — same cross-compilation and
  testability costs as C++ without aasdk's protobufs being directly reusable.

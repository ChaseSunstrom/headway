# Blockers

Every `TODO(B-NNN)` in the codebase must have an entry here — CI enforces it via
`tools/check-todos.sh`. An entry stays open until a workaround ships; the
Definition of Done requires this file to be empty or to contain only items whose
workarounds have shipped.

Format: id, what is blocked, why, and the workaround **that is actually in the
code today** (not one that is merely contemplated).

---

## B-001 — No real head unit, phone, or car available to the build environment

**Status:** Open — permanent for CI, mitigated by design.

**Blocked:** Real-hardware acceptance for every phase; all Bluetooth and Wi-Fi
code paths.

**Why:** Development and CI run in a Linux container with no Bluetooth radio, no
Wi-Fi AP association capability, no Android device, and no vehicle.

**Workaround shipped:** The transport layer is written against a `Transport`
interface with three implementations — real TCP, real Bluetooth RFCOMM (Android
only), and an in-process fake pair used by CI. The head-unit emulator speaks the
same wire format over the fake transport, so the full handshake and every channel
state machine are exercised in CI without hardware. Bluetooth- and Wi-Fi-specific
Android code is isolated in `:app` behind those interfaces so that the untestable
surface is as small as possible.

**Residual risk (not eliminated):** A green CI run proves self-consistency, not
compatibility with a real Chevrolet Infotainment 3 unit. See ADR 0002 for why
byte fixtures and DHU — not the emulator — are the correctness oracle.

---

## B-002 — Reference implementations are head-unit-side; the phone side is barely documented

**Status:** Open — mitigated.

**Blocked:** Confidence in phone-side polarity for each channel.

**Why:** aasdk and openauto implement the head unit. Only AACS implements the
phone/server side, and it is less complete. Where a message is only ever
*received* by the references, the phone-side sending behaviour must be inferred
from the message definition rather than observed.

**Workaround shipped:** `docs/protocol-notes.md` marks every such constant with
its provenance and flags inferred polarity explicitly, so a real-car log can be
diffed against the assumptions. Discrepancies between references are recorded
rather than silently resolved, per `CLAUDE.md` ("Where references disagree,
prefer aasdk's protobufs and note the discrepancy").

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

## B-003 — The public phone-side TLS certificate expired on 2022-08-24

**Status:** Open — mitigated as far as is possible client-side. **This is the
most likely reason a real car will refuse to connect.**

**Blocked:** Authenticating to a head unit that validates the phone's
certificate.

**Why:** AAP authenticates the phone with an X.509 certificate issued by a
"Google Automotive Link" CA. The only publicly available phone-side certificate
is the one extracted by the AACS project
(`references/AACS/AAServer/ssl/android_auto.crt`), and it carries
`notAfter = Aug 24 12:29:12 2022 GMT`. It is therefore expired, and we do not
have the CA key to issue a replacement.

Whether this actually breaks a given car depends on whether its head unit checks
the validity dates. Every reference implementation disables peer verification on
its own side (`aasdk/src/Transport/SSLWrapper.cpp` L137-L140 calls
`SSL_set_verify(ssl, SSL_VERIFY_NONE, nullptr)`), which tells us nothing about
what a factory head unit does with the certificate the phone presents. Some
units are known to be lenient. Assume the Malibu is not until a log proves
otherwise.

**Workaround shipped:** The certificate is not baked in. `AapTls.phoneEngine`
takes `KeyMaterial`, and the bundled pair is only the default, so a user who can
extract current material from a licensed Android Auto installation can supply it
without rebuilding the protocol layer. `TlsSessionTest` asserts the expiry
explicitly, so it is a stated fact in the suite rather than a surprise in a car
park.

**What would actually fix it:** Nothing available to this project. Issuing a
valid certificate needs the Google Automotive Link CA private key. If a real
Malibu rejects the session, the frame log will show the TLS alert, and the
options are user-supplied certificate material or nothing.

---

## B-004 — Phone-side polarity is inferred, not observed, for messages the references only receive

**Status:** Open — mitigated.

**Blocked:** Certainty about which side sends what, for a handful of messages.

**Why:** This is the specific form B-002 takes now that implementation has
started. Two polarity facts were recoverable and are now encoded: the head unit
sends `VersionRequest` first, and the phone is the TLS **server**. Both are
counterintuitive — the phone opens the TCP connection yet acts as the TLS server
— and both are cited in `docs/protocol-notes.md`. Other messages appear in the
references only in the receive direction, so the phone-side send behaviour is
read off the message definition rather than observed.

**Workaround shipped:** Every inferred polarity is marked as such in the KDoc at
the point the code depends on it, so a single real-car frame log can be diffed
against the assumptions rather than requiring the reasoning to be rediscovered.

---

**Workaround shipped:** `docs/protocol-notes.md` marks every such constant with
its provenance and flags inferred polarity explicitly, so a real-car log can be
diffed against the assumptions. Discrepancies between references are recorded
rather than silently resolved, per `CLAUDE.md` ("Where references disagree,
prefer aasdk's protobufs and note the discrepancy").

---

## B-005 — Joining the car's Wi-Fi needs a human tap that no unprivileged app can avoid

**Status:** Open — mitigated.

**Blocked:** `CLAUDE.md`'s "reconnecting automatically within 15 seconds of the
car being available, without user interaction", for the *first* connection to a
given car, and for any connection after Android has forgotten the approval.

**Why:** `WifiNetworkSpecifier` is the only unprivileged way to join a chosen
network, and AOSP shows a system approval prompt before honouring the request
(`WifiNetworkFactory.startUi`, which launches Settings'
`NetworkRequestDialogActivity` in its own task). That prompt has to be tapped by
a person. Real Android Auto does not hit this because Play Services holds
privileged Wi-Fi APIs and never goes through the specifier flow at all.

AOSP does bank the approval per app and access point
(`mUserApprovedAccessPointMap`) and skips the prompt on later requests, so in
principle it is once per car. Two things weaken that: the approval is only
stored once a connection *succeeds*, and on devices with STA+STA local-only
concurrency the bypass is revoked when a secondary interface cannot be created
(`revokeNormalBypass`), which puts the prompt back. Reinstalling the app clears
it too.

**Workarounds shipped:**

- The network request is held across reconnect attempts rather than rebuilt per
  attempt, so a successful join is never thrown away by an unrelated failure and
  the approval actually gets banked (`HeadwayService.carWifi`).
- `CarWifiNetwork.adoptExistingCarNetwork` uses a Wi-Fi network the phone is
  already on when it can be identified as the car's — by the gateway matching
  the address the head unit gave over Bluetooth, or by the shape of the network
  when it gave none. A user who joins the car's Wi-Fi once from Settings
  therefore never sees the prompt again, and the README says so.
- A failed join backs off for 30 s rather than 500 ms, because re-requesting
  immediately lands on Android's stale prompt instead of raising a new one.

**What would remove it:** nothing available to an unprivileged app. If a future
Android exposes a companion-device Wi-Fi association that does not re-prompt,
that is the path.

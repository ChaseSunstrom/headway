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

**Status:** Open — **confirmed against the target car**, mitigated as far as is
possible client-side. This is the reason the 2021 Malibu refuses to connect.

**Blocked:** Authenticating to a head unit that validates the phone's
certificate.

**Why:** AAP authenticates the phone with an X.509 certificate issued by a
"Google Automotive Link" CA. The only publicly available phone-side certificate
is the one extracted by the AACS project
(`references/AACS/AAServer/ssl/android_auto.crt`), and it carries
`notAfter = Aug 24 12:29:12 2022 GMT`. It is therefore expired, and we do not
have the CA key to issue a replacement.

Every reference implementation disables peer verification on its own side
(`aasdk/src/Transport/SSLWrapper.cpp` L137-L140 calls
`SSL_set_verify(ssl, SSL_VERIFY_NONE, nullptr)`), which told us nothing about
what a factory head unit does with the certificate the phone presents. The
Malibu now has: it completes the version handshake, exchanges
`ENCAPSULATED_SSL`, and answers `AUTH_COMPLETE` with
`STATUS_AUTHENTICATION_FAILURE` (-3). On the car screen this surfaces as

> The phone and vehicle calendars are set to different dates and times.

which is that unit's wording for a certificate validity failure — and is
consistent with the protocol having distinct
`STATUS_AUTHENTICATION_FAILURE_CERT_EXPIRED` (-24) and
`..._CERT_NOT_YET_VALID` (-23) statuses at all. Note it returned
`AUTHENTICATION_FAILURE` and not `STATUS_CERTIFICATE_ERROR` (-2), which reads as
"the chain was acceptable, the dates were not".

**Workaround shipped:** Three, tried in that order.

0. *Present a different certificate that has not expired.* The expired
   phone-role certificate is not the only material signed by the **same**
   "Google Automotive Link" CA that the references carry:

   | id | subject | source | expires |
   |----|---------|--------|---------|
   | `phone` | `O=CarService, OU=53` | `AACS/AAServer/ssl/android_auto.crt` | 2022-08-24 |
   | `internal` | `O=Android-Auto-Internal, OU=01` | `AACS/AAClient/ssl/headunit.crt` | **2048-08-01** |
   | `headunit` | `O=JVC Kenwood, OU=01` | `aasdk/src/Messenger/Cryptor.cpp` L275 | **2045-04-29** |

   The last two were issued for the *head unit* role, which is why no phone
   implementation has ever presented one — including this one, until the role
   was noticed to be the only thing separating them from a working certificate.
   Whether a car accepts one from the phone side depends on what it checks. If
   it verifies the chain to the Google Automotive Link CA and the validity
   dates, an unexpired sibling satisfies it and the subject never comes up. If
   it pins the subject or checks a role attribute, it does not.

   Nothing in the references settles that, and it costs one reconnect to find
   out, so `AapTls.bundledPhoneCredentials` lists all three and
   `HeadwayService` advances one place on each `AuthenticationRejectedException`
   — and only on that, since a Wi-Fi or TCP failure says nothing about the
   certificate. `TlsSessionTest` asserts the premise: at least two candidates,
   one CA, at least one unexpired, and every key matching its certificate. The
   quirk file's `"certificate"` key moves a known-good id to the front once a
   log names it.

   **This is untested against a real head unit.** It is a well-founded guess
   with a cheap test attached, not a fix, and it stays in this file until a car
   log says which way it went.

1. *Import a certificate.* The certificate is not baked in.
   `AapTls.phoneEngine` takes `KeyMaterial`, and the bundled pair is only the
   default. `PhoneCertificateStore` lets a user who can extract current material
   from a licensed Android Auto installation drop a PEM + PKCS#8 pair into the
   app's private storage from the settings screen; it is validated on import
   (both halves parse, and the RSA modulus matches) and used for every session
   afterwards. aa-proxy-rs reached the same conclusion and ships no certificate
   at all, loading the pair from an operator-supplied path
   (`src/ssl_rustls.rs` L440-L441). `TlsSessionTest` asserts the bundled
   certificate's expiry explicitly, so it is a stated fact in the suite rather
   than a surprise in a car park.
2. *Move the car's clock.* The head unit judges validity against **its own**
   clock, so setting the car's date back inside 2014-08..2022-08 makes the
   bundled certificate current from its point of view. `FixedKeyManager` exists
   for exactly this: the platform `KeyManagerFactory` filters candidate aliases
   on validity against the *phone's* clock and would drop an expired certificate
   before it was ever sent, failing the handshake as "no cipher suites in
   common". Headway has one certificate and no choice to make, so it always
   presents it and lets the head unit judge.

**The two workarounds are mutually exclusive with Google's Android Auto, and
that trade-off must be stated to users.** Google's own certificate is current,
so a car whose clock has been rolled back to 2016 sees it as *not yet valid* and
refuses Android Auto with the same "calendars are set to different dates" screen
— which is precisely why `..._CERT_NOT_YET_VALID` exists. One car clock cannot
satisfy both an expired certificate and a current one. Rolling the clock back is
therefore a diagnostic and a stopgap, not a configuration to leave in place;
importing current material (workaround 1) is the only route that leaves both
working, and restoring the car's clock is the only route back to Android Auto.

**What would actually fix it:** Issuing a *new* certificate needs the Google
Automotive Link CA private key, which is not available to this project.
Workaround 0 is the only route that does not need one, and whether it works is
a property of the car rather than of anything Headway can change.

Extracting current material from a licensed Android Auto installation is the
other obvious idea, and it is a worse one than it looks. The certificate lives
inside a Google-signed APK whose distribution terms do not contemplate that; the
current phone-side material may be device-bound or attested rather than a static
pair; and either way the result is one user's certificate, not something this
project can carry. That is the shape of the problem `PhoneCertificateStore`
already fits — an import path for material a user obtained themselves — and it
is why aa-proxy-rs bundles nothing and takes a path instead.

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

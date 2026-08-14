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

**Status:** **RESOLVED against the target car (2026-08-13).** The
`Android-Auto-Internal` certificate is accepted; workaround 0 below is the fix
and it is now the answer, not a guess. Kept open only because it is one vehicle:
a unit that checks the certificate's *role* would still refuse, and no such unit
has been seen either way.

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

   **Confirmed on a real head unit, 2026-08-13.** A 2021 Chevrolet
   Infotainment 3 unit refused `phone` (no `TLS established` line — it stopped
   while looking at the certificate) and then, on the next attempt, accepted
   `internal`:

   ```
   14:59:31.964  presenting the Android-Auto-Internal certificate (2 of 3),
                 OU=01,O=Android-Auto-Internal,... valid until Sat Aug 01 2048
   14:59:32.060  TLS established
   14:59:32.070  rx control AUTH_COMPLETE
   14:59:32.072  authentication complete
   ```

   So this unit verifies the chain to the Google Automotive Link CA and the
   validity dates, and does **not** check which role the certificate was issued
   for. The rotation reaches it on attempt 2 with no user action; putting
   `"certificate": "internal"` in the quirk file skips the wasted first attempt.

   This also means the certificate no longer needs the car's clock moved, and
   the clock trade-off with Google's Android Auto no longer has to be taken.

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

---

## B-006 — GrapheneOS gives every network Headway joins a new MAC, and fixes that for Android Auto only

**Status:** Open, but **not the cause on the target car** — see the correction
below. Kept because the platform behaviour is real and will bite a head unit
that *does* run DHCP.

**Correction (2026-08-13, from a real car log):** the 2021 Malibu announces
`access_point_type = STATIC` in every `WifiInfoResponse`. That is the protocol's
way of saying its access point does not assign addresses at all, so no MAC
setting and no DHCP hostname was ever going to produce a lease from it. The
`IP_PROVISIONING` failures were the head unit behaving exactly as announced. A
static IP on a saved car network is the correct configuration for this vehicle,
not a workaround — and with one set, the session reaches authentication and
service discovery. Everything below still stands as platform fact and still
applies to a head unit that advertises `DYNAMIC`; it simply is not this car's
problem.

**Blocked:** Getting a DHCP lease from the head unit without the user editing
Wi-Fi settings by hand.

**Why:** The join reaches `IP_PROVISIONING` — association and authentication
both succeed, and provisioning does not complete. GrapheneOS carries a carve-out
for exactly this failure, keyed on Google's Android Auto package
(`WifiConfiguration.java` L3400-L3405):

```java
if (android.app.compat.gms.GmsCompat.isAndroidAuto()) {
    // Per-connection MAC randomization doesn't work with some cars, see
    // https://github.com/GrapheneOS/os-issue-tracker/issues/4139
    macRandomizationSetting = RANDOMIZATION_PERSISTENT;
    mIsSendDhcpHostnameEnabled = true;
}
```

So this is a known car bug that GrapheneOS has already fixed — for one package,
identified by `GmsCompat.isAndroidAuto()`. Headway is not that package, and
neither half of the fix is reachable from the join it makes:

1. **The MAC.** `WifiNetworkFactory.handleConnectToNetworkUserSelectionInternal()`
   builds `new WifiConfiguration(specifier.wifiConfiguration)`
   (`WifiNetworkFactory.java` L1160-L1161), so the configuration originates in
   the *requesting app's* process. In Headway's process GrapheneOS's default is
   `RANDOMIZATION_ALWAYS` (= 100, a GrapheneOS-only value,
   `WifiConfiguration.java` L1913) which re-randomizes on **every connect** —
   not per network, as AOSP's persistent default does. `WifiNetworkFactory`
   never touches the field and `WifiNetworkSpecifier.Builder` has no setter.
   Worse, GrapheneOS Settings' per-network Privacy control is gated on
   `isSaved()`, and a specifier network is not saved, so the *user* cannot reach
   it either.
2. **The DHCP hostname (option 12).** GrapheneOS defaults
   `mIsSendDhcpHostnameEnabled` to false where AOSP defaults it true, and flips
   it back inside the same carve-out — so GrapheneOS judged a stable MAC alone
   insufficient. `WifiConfiguration.setSendDhcpHostnameEnabled` is `@SystemApi`
   behind `NETWORK_SETTINGS`/`NETWORK_SETUP_WIZARD`, absent from both
   `WifiNetworkSpecifier.Builder` and `WifiNetworkSuggestion.Builder`
   (confirmed with `javap` against `android-35/android.jar`). The only API-35
   hostname method is `WifiManager.setSendDhcpHostnameRestriction`, which is
   privileged and only ever *restricts*. **There is no unprivileged lever for
   this at all.**

**What is not established:** *why* the head unit refuses. Pool exhaustion fits,
but os-issue-tracker#4139 describes exhaustion "after 255 rides", whereas this
phone has never once received a lease from this car — which fits outright
refusal better. A unit that rejects locally-administered MACs, or one that drops
requests carrying no hostname, explains the evidence equally well. Note also
that `IP_PROVISIONING` means "provisioning did not complete", **not** "no
DHCPOFFER arrived": a lease that fails duplicate-address detection lands here
too. Do not write "the pool is exhausted" anywhere as fact.

**Workarounds shipped:**

- `CarWifiNetwork.adviceFor` names both GrapheneOS toggles — Privacy → "Use
  per-network randomized MAC" and "Send device name to network" — because the
  user's own Settings screen is the only place that reaches both.
  `CarWifiNetworkTest` asserts the advice mentions each, so half of it cannot
  drift away.
- **Set up this car's Wi-Fi** in Diagnostics fires
  `Settings.ACTION_WIFI_ADD_NETWORKS` with the SSID and passphrase the head unit
  gave over Bluetooth, so the network gets *saved* — which is what puts those
  two toggles in front of the user — without anyone transcribing a passphrase.
  Public, unprivileged, not `@SystemApi`.
- `"suggestCarNetwork": true` in the quirk file swaps the specifier for a
  `WifiNetworkSuggestion` carrying `RANDOMIZATION_PERSISTENT`, the only public
  API with a MAC randomization preference. Off by default: the first suggestion
  needs an approval notification whose refusal is sticky, the platform rather
  than Headway decides when to associate, and it does nothing for the hostname.
- Addressing failures now get two attempts rather than one
  (`HeadwayService.MAX_ADDRESSING_ATTEMPTS`), because the second distinguishes
  "the table was full and a slot freed" from "this station is refused
  regardless" — and the log records which.
- A static IP on a saved car network remains the documented fallback.

**What would actually fix it:** GrapheneOS making the `isAndroidAuto()`
carve-out reachable by non-GMS Android Auto implementations — an app-level
opt-in, or a widened predicate. That is upstream work in
`os-issue-tracker#4139`, not something this project can ship.

## B-007 — Screen-off operation depends on a display behaviour read from source, not observed

**Status:** Open. Workaround shipped (keep-screen-on for the mirroring path);
the fix it gates is ADR 0004's dashboard.

**Blocked:** Confirming that a Headway activity on a virtual display created
with `VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY` stays **resumed** — still drawing,
still animating — while the phone's own screen is off and the device is locked.

**Why it matters:** ADR 0004 establishes that mirroring the *default* display
can never survive screen-off. `DisplayPolicy.screenTurnedOff` acquires a sleep
token for display 0, `DisplayContent.shouldSleep()` becomes true, activities on
it pause, and the mirror goes black. There is no unprivileged flag, wake lock or
foreground-service type that changes this, because it is a property of the
display being asleep rather than of the capture. So the Definition of Done item
*"App survives: screen lock, 30 min continuous session…"* cannot be met by the
mirroring path at all — it can only be met by rendering to a display Headway
owns.

**Why it is inferred:** the reasoning is drawn from AOSP `android15-release` and
is sound as far as it goes:

- `VirtualDisplayAdapter` derives display state from whether the `Surface` is
  non-null, not from the physical screen.
- Sleep tokens are per-display, so the token taken for display 0 does not reach
  a virtual one.
- `hasAwakeDisplay()` returning true for the virtual display keeps the global
  `mSleeping` false, so its activity is not paused.

Each step is verified in source. What is *not* verified is the composite claim
on a real Pixel running GrapheneOS with the keyguard up — and GrapheneOS carries
its own patches in this area. A behaviour this load-bearing should not be taken
on inference.

**Workaround shipped:** the mirroring path keeps the screen on and says so,
rather than appearing to work and going black in the driver's face.

**How to close it:** on device, create an `OWN_CONTENT_ONLY` virtual display,
launch a Headway activity onto it with `setLaunchDisplayId`, lock the phone, and
confirm from the activity's own lifecycle logging that it stays resumed and its
frame callbacks keep arriving. One session with the in-app log export answers
it.

## B-008 — Nothing unprivileged can create a split-screen, so panes must be drawn rather than hosted

> **Amended 2026-08-14 by [ADR 0010](docs/adr/0010-the-car-screen-is-panels-and-one-of-them-is-a-real-app.md).**
> The finding stands: no app's *window* can be placed in a pane. What has
> changed is that one pane can now contain another app's *pixels*, by pointing
> the session's single `MediaProjection` at that pane's `SurfaceView`. That is
> still not hosting a window — Headway owns the geometry and the app never
> learns it is in a pane — and it is why "panels don't work when I open an app"
> is no longer true.

**Status:** Closed by design change; recorded so the question is not re-opened.

**Blocked:** Putting two *third-party* apps on the car screen side by side.

**Why:** three independent routes, all closed (full citations in ADR 0004):

1. `ActivityTaskSupervisor.isCallerAllowedToLaunchOnDisplay` refuses to place
   another app's activity on a display Headway owns without that app declaring
   `android:allowEmbedded="true"` **and** Headway holding `ACTIVITY_EMBEDDING`
   (`signature|privileged`). The check exists precisely to stop content
   hijacking, so it will not be relaxed.
2. `GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN` (=7) was removed from
   `SystemActionPerformer` between `android11-release` and `android13-release`.
   On Android 13+ it returns `false` silently.
3. `FLAG_ACTIVITY_LAUNCH_ADJACENT` only places into a split that already
   exists, and needs a source `Activity` — a foreground service has no
   `mSourceRecord`.

**Workaround shipped (this is the design, not a compromise):** Headway renders
the panes itself from `MediaSessionManager`, a `NotificationListenerService` and
`AppWidgetHost` — all user-granted, none privileged. This is the same model
Android Auto uses for third-party apps, which send template models rather than
pixels.

**Correction, 2026-08-14:** this entry used to add "and hosts only its *own*
activities on its own display, where the `getOwnerUid() == callingUid` branch
applies". That branch sits *after* the untrusted-display gate, not instead of
it, and the gate refuses the first activity onto an empty own-content display —
`ACTIVITY_EMBEDDING` is privileged and `uidPresentOnDisplay` is false until an
activity is already there. A `Presentation` is the way onto that display; see
ADR 0004 Finding 4 and `CarDisplay`. Nothing about the panes changes.

## B-009 — The app-side video bring-up sequence has no automated test

**Status:** Open. Partial guard shipped.

**Blocked:** Testing `CarVideoStream.start` — the ordering of Setup, Config,
video-focus request, Start, codec config and frames — in CI.

**Why:** `CarVideoStream` takes a `MediaProjection` and constructs a
`ScreenEncoder`, both of which need a device. `:app` has no unit-test source
set, and the JVM acceptance suite cannot instantiate the class.

**Why it matters:** this is where the video-focus bug lived. The phone streamed
1434 acknowledged frames to a car that never displayed one, because nothing had
asked for the screen. Nothing on the wire looked wrong; nothing in CI could see
it.

**Workaround shipped:** the same sequence is written out in
`Phase2VideoAcceptanceTest.openStream`, and
`a head unit that never volunteers focus is asked for it, and projects` fails
if the focus request is removed from it (verified by removing it). That guards
the *sequence*; it does not guard `CarVideoStream` itself, so the two can still
drift apart. Both carry a comment saying so.

**How to close it:** split the protocol negotiation out of `CarVideoStream`
into a piece that takes no `MediaProjection` — it needs the channel and the
advertised service and nothing else — and drive that from the emulator suite.
The encoder construction stays behind the device boundary.

## B-010 — Which of the driver's apps allow their audio to be captured

**Status:** Open, and only a real device answers it. Reported rather than
worked around.

**Blocked:** Knowing, before a drive, whether a given music app's audio will
reach the car.

**Why it matters:** ADR 0005 routes third-party media over the AAP media-audio
channel, sourced from `AudioPlaybackCapture`. That API only hands over audio
from apps whose `android:allowAudioPlaybackCapture` is true. The attribute
defaults to true for anything targeting API 29 or later, so most apps are fine —
but DRM-sensitive players set it false, and the platform offers no way to ask in
advance.

**The failure mode is the problem, not the limitation.** An app that opts out
does not error. It produces a perfectly well-formed stream of digital silence,
which on the wire is indistinguishable from everything working: frames sent,
frames acknowledged, focus granted, no music. That is the same shape as the
video-focus bug that cost a week — a healthy-looking session doing nothing.

**Workaround shipped:** `PhoneAudioCapture.describeCapturePolicy()` states the
rule in the session log once per session, and the audio line reports bytes
actually captured and sent, so a silent drive is diagnosable as "captured
nothing" rather than as an unexplained quiet.

**How to close it:** try the driver's actual players.
`app.symfonik.music.player` is the one Gearhead was streaming in the reference
capture from this vehicle, so it is known to be capturable by *some* mechanism
and is the first thing to test. If a favoured app turns out to opt out, the
honest options are to use a different app or to fall back to A2DP for that app,
and neither is something Headway can decide on the driver's behalf.

## B-011 — Whether Android 17 lets Headway host its own dashboard at all

**Status:** Open. The code takes the path that should work; one device test
confirms or refutes it.

**Blocked:** Confirming that Headway's `Presentation` appears on, and keeps
drawing to, a `DisplayManager`-created `VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY`
display on this phone.

Updated 2026-08-14: this used to say "a Headway *activity* launches onto", and
that question is now settled in the negative rather than open. The untrusted
gate in `isCallerAllowedToLaunchOnDisplay` refuses the *first* activity onto
such a display unconditionally — `ACTIVITY_EMBEDDING` is waived only when the
caller already has an activity there — so no activity can bootstrap it. ADR 0006
moves the dashboard to a `Presentation`, which is a window added through
`WindowManager` and never enters that path. What remains open is whether the
platform accepts that window here, which the fallback below already handles.

**Why:** Android 17 added a gate ahead of every launch check
(`ActivityTaskSupervisor.isCallerAllowedToLaunchOnDisplay` L1308-1311) that
refuses any display whose `canHostTasks()` is false, and
`LogicalDisplay.validateCanHostTasksLocked` returns false for anything
`shouldOnlyMirror()` — which `VirtualDisplayAdapter` defines as *created through
a `MediaProjection`*. So the display `ScreenEncoder` has always created can no
longer host so much as Headway's own activity. `LogicalDisplay` L1096-1105
returns true early for `FLAG_OWN_CONTENT_ONLY`, which is why `CarDisplay`
creates one that way.

Two things remain unverified from source, and they compound:

1. Whether `enable_display_content_mode_management` is actually on in the
   shipping GrapheneOS Pixel 10 Pro XL build. It is a release-config value, not
   a source constant.
2. Whether an activity on an own-content display stays **resumed** with the
   screen off and the phone locked — the claim B-007 already tracks, and the
   one the whole screen-off story rests on.

**Workaround shipped:** the mirroring path is untouched and still uses
`MediaProjection`, so a phone where the dashboard cannot be hosted still casts
exactly as it does today.

**How to close it:** connect once and read the exported log. `car surface
ready:` means the display and the window both came up, and the car is being
drawn for rather than mirrored; `car surface: the dashboard window was refused`
names the exception if the platform turned the `Presentation` away. Then lock
the phone and watch whether frames keep flowing, which answers (2). One session
answers both, and the session degrades to mirroring either way rather than
failing.

---

## B-012 — Whether a real car app accepts Headway as a host

**Status:** Open, and **half of it is now verified**. The permission route works
on a real Android image; what is untested is a real app on the other end.

**Verified 2026-08-14, CI run 92, AOSP emulator API 35:**
`CarAppHostTest.headwayHoldsTheTemplateRendererPermission` passed, so
`PackageManager.checkPermission("android.car.permission.TEMPLATE_RENDERER",
"dev.headway.app")` returns `PERMISSION_GRANTED` — the signature-level
self-declaration is granted at install exactly as the derivation predicted, and
that value is precisely what `HostValidator.hasPermissionGranted` reads. The APK
also installed cleanly, which is the first real evidence against the B-013 and
B-014 collision risks on a Google-free image. The rest of the class passed too:
`HandshakeInfo`, a whole `ListTemplate` with its rows and its click delegate, and
a standard `Action` all survive the `Bundler` round trip.

What that does *not* prove is that a third-party app runs the branch. Route 4 is
the last of four and only reached when the first three miss; an app with a
stricter custom validator, or a future library that drops the branch, would still
refuse. That is what remains open.

**Blocked:** Confirming that a third-party `CarAppService` runs
`HostValidator.isValidHost()` against Headway and returns true, so its templates
reach the car screen.

**Why:** The decision happens in the *app's* process, not Headway's.
`CarAppBinder.onHandshakeCompleted` builds `HostInfo(claimedPackage,
Binder.getCallingUid())` and hands it to whatever `HostValidator` the app's
`createHostValidator()` returned. Decompiled from `androidx.car.app:app:1.7.0`,
`validateHost` accepts on exactly four conditions:

1. `applicationInfo.uid == Process.myUid()` — the app calling itself.
2. The caller's package name and signing digest are in the app's own allowlist.
3. `uid == 1000` — a system binding.
4. The caller holds `android.car.permission.TEMPLATE_RENDERER`, read as
   `REQUESTED_PERMISSION_GRANTED` off the caller's `PackageInfo`.

Headway takes route 4, by declaring that permission at `signature` level and
using it, which grants it at install to the declaring package — itself. The
reasoning is written out in full in the manifest beside the declaration and in
ADR 0007. It is sound from source, and it has not been run against a real app on
real hardware, which is the whole of this entry.

Route 2 is worth recording as the fallback: both shipping FOSS car apps branch
on `FLAG_DEBUGGABLE` and use `ALLOW_ALL_HOSTS_VALIDATOR` in a debug build, so a
locally built debug APK of Organic Maps or OsmAnd accepts any host and is the
bench test that needs no permission at all. Adding Headway permanently is a
one-line diff to each — an upstream ask, not a Google decision.

**Workaround shipped:** the pane distinguishes refusal from failure and says
which. `HostState.REFUSED` prints the app's own error text, so a log from a
single session names the app and the reason rather than showing an empty pane.
Nothing else in Headway depends on the host: maps, media, phone and messages all
work through their own models whether or not a single car app ever answers.

**How to close it:** install any app with a `CarAppService` and press **Run the
self-test** on the setup screen. It binds every installed car app in turn, runs
the real handshake, and prints one line per app: `ACCEPTED` with the negotiated
API level, `refused` with the app's own error text, or `timed out`.

The point worth stating, because it is what kept this entry open for so long:
**none of that needs the car.** A `CarAppService` is bound over local binder, so
Organic Maps on the phone accepts or refuses Headway with no head unit involved,
no Wi-Fi, and no drive. The test that closes this entry takes ten seconds in a
kitchen. What still needs the car is only whether the accepted templates *look*
right on the panel, which is a rendering question, not a host one.

---

## B-013 — The template-renderer permission can block installation

**Status:** **Hit in production 2026-08-14, and fixed.** A user on GrapheneOS
could not install any build after 84; Android's only report was "App not
installed". Workaround shipped: every release now carries a second APK that
declares neither this permission nor the provider in B-014. See
[ADR 0009](docs/adr/0009-two-apks-so-the-host-cannot-block-the-install.md).

The collision itself is still open by construction — two definers of one
permission name cannot coexist and that is not in Headway's gift — but it no
longer costs the whole app.

### What this entry got wrong

It priced the cost as the car-app host. The cost is **the entire application**:
an APK that cannot install takes the car link, video, touch, audio, voice, maps,
phone and media down with a feature that was supposed to be purely additive. And
the stated workaround — "delete the two manifest lines and rebuild" — asks
somebody holding a phone to have a toolchain. Both errors are corrected below.

**Blocked:** Installing Headway on a phone where some other package already
declares `android.car.permission.TEMPLATE_RENDERER`.

**Why:** Android allows exactly one definer per permission name. A second
package declaring one that is already defined fails to install with
`INSTALL_FAILED_DUPLICATE_PERMISSION`. Headway has to declare it — see B-012 —
because holding it is the only route by which a car app will accept Headway as
a host, and a permission cannot be held unless something defines it.

Nothing on an AOSP phone declares this permission: it belongs to Android
Automotive's car service, which is not part of a handset build. The realistic
case is a phone that also has Google's Android Auto installed.

**Workaround shipped:** **install `-compat.apk` instead of `-host.apk`.** Every
release carries both, built from the same commit and signed with the same key.
The compat APK declares neither this permission nor the B-014 provider, so it
installs on any phone; it loses the car-app host and nothing else, and the host
build upgrades it later with no uninstall if the conflicting app is removed.

The mechanism is a product flavour — `app/src/host/AndroidManifest.xml` holds
both declarations, `app/src/compat/AndroidManifest.xml` holds nothing — because
a manifest element cannot be made conditional at install time and a permission
cannot be granted late. `tools/check-install-claims.sh` runs in CI against the
two built APKs and fails if compat declares either name or host declares
neither; both mistakes are otherwise completely silent.

The older manual routes still work and are no longer the only ones: uninstall
the conflicting app, or delete the two declarations and rebuild.

**How to close it:** it closes itself the day a car app adds Headway to its
allowlist, because route 2 needs no permission at all.

**How to check it on a given phone:** press **Run the self-test**. Its "Install
collisions" section names the package that actually defines
`android.car.permission.TEMPLATE_RENDERER` on this device. If Headway is running
at all then no collision blocked the install, and the useful question is the
next one — whether Headway's own declaration is the one in force, which the same
section answers by name.

---

## B-014 — The car-connection authority can block installation

**Status:** **Fixed the same way as B-013**, and by the same change: the
`-compat.apk` in every release declares no provider. See
[ADR 0009](docs/adr/0009-two-apks-so-the-host-cannot-block-the-install.md). The
authority collision is still unavoidable in a single APK; it no longer blocks
the install of an app that has one to fall back on.

**Blocked:** Installing Headway on a phone where some other package owns the
`androidx.car.app.connection` content-provider authority.

**Why:** Provider authorities are exclusive, and a package declaring one that is
taken fails to install with `INSTALL_FAILED_CONFLICTING_PROVIDER`. On a phone
with Android Auto, Gearhead owns it. Headway declares it because
`androidx.car.app.connection.CarConnection` is how every car app asks whether it
is being projected, and on a de-Googled phone nothing answers — so every app
concludes it is not in a car, and several of them then decline to run their car
service at all.

**Workaround shipped:** install `-compat.apk`, which declares no provider. What
is lost is apps knowing they are projected, which affects their behaviour rather
than Headway's rendering; nothing in Headway's own code reads the provider.
(Deleting the `<provider>` block and rebuilding still works and is now the
`compat` flavour's entire content.)

One caveat found in review and *not* resolved: `androidx.car.app:app:1.7.0`'s
own manifest carries
`<queries><provider android:authorities="androidx.car.app.connection" /></queries>`,
which merges into every app that depends on the library. Owning that authority
is therefore one thing that makes Headway visible to a car app under Android
11+ package-visibility filtering, and `HostValidator.validateHost` begins by
resolving the *host's* `PackageInfo` from inside the app's process — a lookup
that returns null for an invisible package and rejects the host outright.
Whether binding the service already grants that visibility on its own is
disputed: two reviewers read AOSP's `AppsFilter` in opposite directions and
neither reading could be settled from source here. So "templates render either
way" is the expectation, not a verified fact. If the provider is removed and
apps start answering "package not found", add
`<queries><package android:name="…" /></queries>` for the car apps in use, or
put the provider back.

**How to close it:** it cannot be closed while both hosts are installed. The
authority is a well-known string precisely so that whichever host is present can
own it, and two hosts on one phone is a case the contract does not model.

**How to check it on a given phone:** press **Run the self-test**. Its "Install
collisions" section names the package that owns `androidx.car.app.connection`,
and its "Grants" section says what that owner answers — `NOT_CONNECTED` with no
car, `PROJECTION` with one. An answer from a package that is not Headway means
the other host is the one apps are reading.

---

## B-015 — Whether MediaProjection will record the simulated display on this build

**Status:** Open. The route is built and gated; one device session settles it.

**Blocked:** Confirming that the system's screen-capture chooser offers the
simulated secondary display as its own row, so Headway can record *that* display
instead of display 0.

**Why:** Android 17's SystemUI lists one chooser row per connected display —
`MediaProjectionPermissionUtils.kt` allows `Display.TYPE_OVERLAY` at L29-35 and
`ShareToAppPermissionDialogDelegate.kt` builds the rows at L114-129 — but the
whole list is behind an aconfig flag, `media_projection_connected_display`,
checked at L83-85: `if (!Flags.mediaProjectionConnectedDisplay()) { return
emptyList() }`. Whether that flag is enabled in the shipping GrapheneOS Pixel
build is a release-config value, not a source constant, and cannot be read from
the tree.

Without the row, the driver can only grant "Entire screen", the projection
records display 0, and the native-rendering path degrades to mirroring an app
that is being drawn on a display nobody is capturing — a black or stale car
screen rather than a graceful failure.

The second unverified item, which the same session answers: whether the driver
picked a usable simulated display at all. The Settings menu lists `(secure)`
entries that look identical and carry `FLAG_SECURE`, which makes the display
unrecordable.

**Also unverified, and stated in the setup screen and the README as though it
were settled:** that Developer options' "Disable screen-share protections for
apps and notifications" is what keeps a projection alive across a screen lock.
Android 15 QPR1 does stop a projection on lock —
`MediaProjectionManagerService` builds a `MediaProjectionStopController` whose
`onKeyguardLockedStateChanged` fires `STOP_REASON_KEYGUARD` — so the *problem*
is real. Whether that toggle is the fix for it, rather than a control over
sensitive-content redaction, could not be settled from source. The wording now
says so.

**Workaround shipped:** the whole route is behind a switch that is off by
default, and `CarAppDisplay.resolve` logs which display it settled on — or that
it found none — on every session. With the switch off, or with no simulated
display present, every path is byte-for-byte what it was before ADR 0008.

**How to close it:** in two halves, and only the second needs the car.

The `(secure)` half: press **Run the self-test** on the setup screen. Its
"Displays" section lists every display with its flags and says which are usable,
which settles outright whether the driver picked a recordable entry.

The chooser half: press Connect with the switch on and look at the consent
dialog. A row named for the simulated display means the flag is enabled and the
route works. If only "Entire screen" and "A single app" are offered, the flag is
off, and the honest answer is that this phone cannot do native rendering for
apps without a car template — turn the switch back off. This is one of only two
things in the whole self-test that a drive is genuinely required for, and the
self-test's last section says so.

---

## B-016 — A test double ships in the published APK

**Status:** Open, cosmetic, with a known fix deliberately deferred.

**Blocked:** Nothing. Recorded because it is a real defect found in review and
leaving it undocumented would be worse than leaving it unfixed.

**Why:** Releases are built with `assembleDebug` — on purpose, because
frame-level protocol logging is what makes a first-connection failure
diagnosable from the in-app log export. That makes `debug` the *published*
source set, so `app/src/debug/AndroidManifest.xml` merges into the APK people
install, and with it `dev.headway.app.media.FakeLibraryService` — a stub
`MediaBrowserService` that exists only so `CarMediaBrowserTest` has a real
framework service to bind rather than a mock of one.

It is `exported="false"`, so nothing outside Headway can bind it, and
`MediaApps.installed()` excludes Headway's own package, so it never appears in
the media pane. The harm is that a shipped APK advertises a component that is
not part of the product.

**Workaround shipped:** none needed; the component is inert in normal use.

**How to close it:** declare it `android:enabled="false"` and have the test turn
it on with `PackageManager.setComponentEnabledSetting`, which an app may do for
its own components with no permission. Deferred rather than done because this
service has already broken the instrumented suite twice — once for
`exported="false"` blocking a cross-package bind, once for
`NoClassDefFoundError` when it lived in the test APK — and destabilising a green
suite to fix a cosmetic wart, while a real install failure was being shipped,
was the wrong order of work.

---

## B-017 — `libvosk.so` is 4 KB-page aligned, and Pixels now run 16 KB pages

**Status:** Open. Latent; not yet observed, and not an install failure.

**Blocked:** Running the voice pipeline on a device booted with 16 KB memory
pages. Android 15 introduced 16 KB page support on 64-bit Pixels, and a shared
object whose `PT_LOAD` segments are aligned to 4 KB cannot be mapped in place.

**Why:** Two things combine. `minSdk 33` makes AGP set
`android:extractNativeLibs="false"`, so the library is loaded straight out of
the APK rather than unpacked, which requires its segment alignment to match the
kernel's page size. The `libvosk.so` inside `com.alphacephei:vosk-android` is
built with the older 4 KB `max-page-size`. On a 16 KB device the load fails with
`UnsatisfiedLinkError` at the first speech attempt.

Everything else is unaffected: the ABI filter, the install, the car link, and
every non-voice feature. Nothing about this blocks installation, which is worth
stating because it was found while investigating one.

**Workaround shipped:** none yet. The voice path already degrades to "the car
microphone works, nothing is transcribed" rather than crashing the app.

**How to close it:** ship a `libvosk.so` linked with
`-Wl,-z,max-page-size=16384`, either by rebuilding `vosk-android` or by
repacking the AAR. In the meantime the load failure should be logged with its
own `UnsatisfiedLinkError` text instead of collapsing into the generic "no
on-device speech model" message, so a 16 KB device is diagnosable from one
exported log rather than mistaken for a missing model.

---

## B-018 — A background service may be refused permission to start an app on the car display

**Status:** Open. Mitigated, and the mitigation is honest rather than complete.

**Blocked:** Nothing, most of the time. This is the failure mode of one action —
opening a third-party app into the app pane — in one situation: a session that
came up on its own, with Headway holding no visible activity.

**Why:** ADR 0010 removed the last activity Headway put on the phone when a
session starts, because a link that comes up automatically must not take the
driver's screen over. That is the right behaviour and it costs this: Android 10's
background-activity-start restrictions gate `startActivity` from a service on the
app having a visible window, a recent one, or one of a list of exemptions. With
no activity at all, `ActivityOptions.setLaunchDisplayId` may be silently ignored
— the call reports success either way, and there is no unprivileged API that
reports otherwise.

What is *not* affected: everything the panes draw themselves. Now playing, the
map's next turn, messages, calls, a car app's templates and the whole rail keep
working, because none of them starts an activity. Only "put this app's own
pixels in the pane" needs one.

**Workaround shipped:** the failure is visible rather than silent. The app pane
says what it is waiting for, and the session log records the launch attempt and
its display id, so a drive where nothing appeared in the pane is diagnosable from
the export. A driver who opens Headway on the phone once during the drive
restores the exemption for the rest of it.

**How to close it:** measure it. The exemption list is long and several entries
plausibly apply to a `CompanionDeviceManager`-associated app started from a
Bluetooth broadcast; whether they do on a real GrapheneOS build is a
device-and-version question that no amount of source reading settles. One drive
with the log exported answers it. If the answer is "refused", the fallback is a
notification the driver taps once per drive, which is worse than automatic and
better than a pane that never fills.


---

## B-019 — A VPN in lockdown mode can still keep Headway off the car's network

**Status:** Open. Mitigated for the ordinary case; the remaining case needs one
setting the driver owns.

**Blocked:** The whole link, for a driver running an always-on VPN with "block
connections without VPN" enabled.

**Why:** A VPN becomes the process's default network. Anything not explicitly
bound to another one goes into the tunnel, and the car's access point has no
route to a VPN concentrator — so the Bluetooth handshake succeeds, the Wi-Fi
join succeeds, and the TCP connect times out with nothing naming the cause. A
driver comparing against Android Auto has no reason to suspect it: Gearhead is a
privileged system app and the platform exempts it.

**Workaround shipped:** Headway now calls
`ConnectivityManager.bindProcessToNetwork` for the car's network for the life of
the session — which CLAUDE.md asked for and which nothing had ever called — so
every socket in the process goes to the car whether or not a VPN is up, and the
binding is undone when the network goes so the phone is not left without
internet. The VPN is also named in the session log, with what to change.

That is enough for a normal VPN. It is *not* enough under lockdown: netd drops
non-VPN traffic for apps that are not exempt, and no unprivileged API exempts
one — `CONNECTIVITY_USE_RESTRICTED_NETWORKS` is `signature|privileged`. The log
now says so in as many words, and names the two settings that work: turn off
"Block connections without VPN", or add Headway to the VPN app's excluded apps.

**How to close it:** it cannot be closed from inside the app. What can be
improved is the diagnosis, and that is done: the VPN line appears before the
first connect attempt rather than after the failure.


---

## B-020 — The shared app needs the phone's display awake, so "works when locked" is approximate

**Status:** Open, and as closed as an unprivileged app can get it.

**Blocked:** Nothing visible, if the driver turns the blackout on. What is
genuinely impossible is a *locked, dark* phone still feeding an app pane.

**Why:** An app's window lives on display 0. `DisplayPolicy.screenTurnedOff`
takes a sleep token for it, `DisplayContent.shouldSleep()` becomes true, its
activities pause, and a capture of that app goes still. Headway's own dashboard
is unaffected — it draws on a virtual display of its own, which ADR 0004
Finding 4 establishes stays awake — so the car keeps its panels, its rail and
its clock. It is only the *shared app's* pixels that stop.

Nothing unprivileged moves a third-party activity off display 0:
`ADD_TRUSTED_DISPLAY` is `signature|role`, and an untrusted virtual display
refuses the first activity outright.

**Workaround shipped:** the screen stays on and goes black. Since app screen
sharing excludes system UI from the capture, an accessibility blackout hides the
phone from the driver without appearing on the car screen — so the phone reads as
off, is off to look at, and keeps drawing. It is off by default because with
whole-display sharing the same overlay would black out the car screen too, and
Headway cannot tell which kind of sharing the driver chose.

**How to close it:** not closable from inside the app. The honest description is
"the phone looks off and behaves as though it were", not "it works locked", and
the README says it that way.

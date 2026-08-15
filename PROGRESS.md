# Headway — progress

Phase status against the plan in `CLAUDE.md`. The point of this table is to be
accurate, not encouraging.

Each phase carries the **tier of evidence** behind it. This is the only
definition of those tiers; nothing is reported Done above the tier its evidence
supports:

- **A — Executed:** real code runs against real bytes and is asserted in CI.
- **B — Framework-executed:** runs on a real Android device or emulator image.
- **C — Compiled:** type-checked against the real Android SDK; behaviour unverified.
- **D — Unverifiable here:** needs a phone, a car, or both.

| Phase | Title | Status | Evidence |
|-------|-------|--------|----------|
| 0 | Test harness | **Done** | A — the emulator drives every acceptance test in CI |
| 1 | Handshake | **Done (CI half)** | A — full bring-up 20/20 over the fake transport *and* over real TCP; the Bluetooth version exchange is now pinned to bytes captured from a real Chevrolet head unit; D for the rest of the real BT/Wi-Fi half |
| 2 | Video out | **Done on real hardware** | A — 10 min of 30 fps stream in order, byte-identical, real NAL parsing; **the 2021 Malibu displayed the phone screen on 2026-08-13: 2754 frames sent, 4 dropped, `focus VIDEO_FOCUS_PROJECTED`** |
| 3 | Input | **Wired; the car has sent nothing yet** | A — event decode and letterbox transform, plus a new test that the emulator sends no report before the key binding; B — gesture building on-device; the first real session received zero reports and could not say why, which four new diagnostics now answer in one drive |
| 4 | Audio + focus | **Wired, unverified on hardware** | A — sinks, focus duck/resume asserted on the wire; B — resampling and AudioManager focus on-device; media now goes over AAP rather than A2DP (ADR 0005), fed by `AudioPlaybackCapture`; D for whether the driver's own apps permit capture |
| 5 | Voice | **Wired, unverified on hardware** | A — real Vosk on real speech, "open calculator" resolved in ~720 ms; the app shipped the *desktop* Vosk jar and no model, so nothing could ever have run on a phone — `vosk-android` and a checksum-pinned model now ship, and three triggers exist where there were none |
| 6 | Reconnection, polish, packaging | **Done (except release signing)** | A — supervisor, quirks, log redaction; B — APK installs and 29 app tests pass on a real device |

## The gap between "module done" and "the app does it"

**Closed.** `HeadwayService.runChannels` now builds the demux and drives video,
audio, input and voice from a live session, and a real 2021 Malibu displayed the
result on 2026-08-13. What remains unverified on hardware is listed under
"What is not verified" below, phase by phase.

## What the car screen is, as of this build

A **rail** along one edge and a **tree of panels** filling the rest.

The rail holds four kinds of thing: a settings button, a microphone button,
whatever the driver pinned — their own layouts and their own apps, in their own
order — and the clock. The six hardcoded tabs are gone; a pinned layout *is* a
tab, and a driver who only uses two of them now has two buttons rather than six.

Its **edge, size and clock are settings** (`RailStyle`, `core-dash`): any of the
four edges, five sizes, clock with or without the date. Size is applied by
scaling the car screen's `CarMetrics`, so every control on the rail moves
together. The scale multiplies what comes *out* of the touch-target floor rather
than what goes in, and the choices start at 1.0 — the rail grows and does not
shrink, because the touch target is already the minimum a moving car allows, and
two sizes that both land on the floor draw the same rail.

A layout is a binary split tree of any depth, so "any number of panels" is true
without a limit anywhere. It is **editable on the car screen** — split a pane,
drag a divider, choose what a pane shows, remove one — and **locked by default**,
because the failure that matters is a layout rearranged by a thumb steadying
itself on a dashboard at speed. Settings → *Edit this layout* unlocks; saving
re-locks.

| Pane kind | Source of the content |
|---|---|
| App | **a real third-party app, rendered into the pane** |
| Maps | **the navigator's own map**, drawn into the pane's `Surface`; the turn card when no allowed app offers a car interface |
| Now playing / Music | `MediaSessionManager`, and `MediaBrowserService` for the library |
| Phone | the dialer's own call notification, and `CallLog.Calls` |
| Car app | `androidx.car.app` templates, drawn by Headway (ADR 0007) |
| Messages | `NotificationListenerService` + the app's own `RemoteInput` |
| Widget | the app's own `RemoteViews`, through `AppWidgetHost` — **any number, all different apps, all at once** |
| Car | the head unit's own AAP sensor channel — speed, revs, fuel, range, tyre pressures, outside temperature, odometer |
| All apps / Clock | Headway's own |

**The App pane is the change that answers "why doesn't opening an app work?".**
It used to: tapping an app called `showOnCar(MIRROR)`, which stopped the car
surface and released the display, so the panels ceased to exist for as long as
the app was open. Now the session's one `MediaProjection` renders into a
`SurfaceView` that lives *in a pane*, and the car display, the window and the
tree survive the whole drive. `VirtualDisplay.setSurface` moves the picture
between panes for nothing, which is what makes several app panes affordable
given that a projection may own exactly one virtual display. ADR 0010 has the
derivation and the four costs.

Everything else about the surface follows from that: there is no full-screen
mode left (`CarSurfaceMode` is deleted), no floating mic-and-Home overlay (it
existed only because opening an app was a one-way trip, and `SYSTEM_ALERT_WINDOW`
went with it), and no activity of Headway's own on the phone when a session
comes up — which is what lets the link come up automatically without taking the
driver's screen.

**Several different apps at the same time is the Widget and Car app panes, not
the App pane.** Screen sharing is one grant, one virtual display, one shared app
— the platform allows a second of none of them — so a layout of four App panes
has one live pane and three destinations. A layout of four Widget panes is four
different apps drawing simultaneously, live, with no capture involved, and the
pane picker says so where the choice is made. Adding one costs a single tap on
the phone the first time ever, because `bindAppWidgetIdIfAllowed` needs the
system's host-approval dialog once and `BIND_APPWIDGET` is `signature|privileged`.

**Nothing reaches the car screen until the driver allows that app**, one at a
time. Empty by default. The app picker, the widget picker, the car-app panel, the
launcher grid and the voice "open X" command all pass through the same gate.

The *asking* happens in two places: on the phone, parked, under *Apps allowed on
the car screen*, and from the seat, where a Car app panel lists every installed
car app and asks for the grant on the one the driver taps. Discovery is
deliberately unfiltered — an app filtered out of a picker is invisible rather
than blocked, with nothing on the car screen saying why.

Themes are three bases (dark, true black, light) times six accents, one of which
is *no* accent. Eighteen combinations, composed rather than hand-written, with
`CarThemeTest` measuring WCAG contrast on all of them; it caught two real
defects the first time it ran.

## What is genuinely verified

**380 JVM tests green** on a bare JDK, plus **69 instrumentation tests green on a
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
- **Sensor channel** — the car's own readings: one `SensorRequest` per
  advertised sensor type, each answer attributed to the request it belongs to,
  and `SensorBatch` decoded from its scaled integers into speed, revs, fuel,
  tyre pressures, temperature and the odometer. Asserted end to end against an
  emulated head unit that advertises a sensor service, including the two states
  that are not errors — a car that reports nothing, and a subscription the unit
  refuses. Until this shipped the SENSOR channel was opened on every real-car
  session and every frame on it was discarded as unroutable.
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
- **Channel open was malformed for the whole life of the project.** Headway
  never set the CONTROL flag on any frame, and a `ChannelOpenRequest` travels on
  its service's own channel where that bit must be set. The target car answered
  by closing the session ~30 ms after discovery, 11 times out of 11. Fixed, with
  a byte fixture and an emulator gate — the emulator previously *accepted* the
  malformed frame, which is exactly the weak-oracle failure ADR 0002 predicted.
- **A real car now reaches service discovery.** On 2026-08-13 a 2021 Chevrolet
  Infotainment 3 unit accepted the `internal` certificate, completed TLS,
  authenticated, and advertised all 13 of its services — the first real-vehicle
  service discovery, and confirmation of the head-unit-advertises polarity
  against hardware rather than only against the references. The session then
  ended while opening SENSOR, on an unanswered keepalive; that is fixed and
  covered by a Phase 1 acceptance test. Nothing past channel open has run on
  real hardware.
- **The target car announces `access_point_type=STATIC`** — it assigns no
  addresses, so its `IP_PROVISIONING` failures were it behaving as advertised. A
  static IP is the correct configuration for this vehicle. See the B-006
  correction.
- **GrapheneOS gives every network Headway joins a new MAC every connect**, and
  fixes that for Google's Android Auto only, via a package-keyed carve-out that
  also re-enables the DHCP hostname. Neither half is reachable from the network
  Headway requests, and the hostname half is not reachable at all. This is why
  the target car issues no address. Mitigated with instructions, a one-tap
  save-this-network step and an optional suggestion path; see B-006.
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

A 2021 Chevrolet Infotainment 3 unit. **The car screen now shows the phone.**
Build 78 completed bring-up and projected: 2754 frames, 4 dropped, focus
granted. What follows is the road there and what is still unproven.

- **Bluetooth: works, every time.** SDP lookup, RFCOMM connect on channel 3,
  version exchange, and the credentials handshake all complete in under a
  second. The car hands over SSID, passphrase, BSSID and — inconsistently —
  its endpoint, 192.168.5.1:7001.
- **Wi-Fi association: works, with the BSSID pinned.** The instrumented build
  settled this. The phone authenticates and associates with the car's access
  point; the log's `IP_PROVISIONING` verdict is only reachable after that
  succeeds.
- **DHCP: solved, and it was never DHCP.** The head unit accepts the phone onto
  the radio and never issues it an address — because it announces
  `access_point_type = STATIC` in every `WifiInfoResponse`, meaning it assigns
  no addresses at all. Every `IP_PROVISIONING` failure was the unit behaving
  exactly as advertised. A static IP on a saved car network is the correct
  configuration for this vehicle, not a workaround. See B-006, which keeps the
  GrapheneOS MAC-randomization analysis because it remains true for a head unit
  that advertises `DYNAMIC`.
- **TLS: solved.** The certificate every reference sends expired in 2022. This
  car checks the chain and the dates but not the role, so it accepts the
  unexpired JVC Kenwood pair, and Headway now remembers per Bluetooth address
  which one worked instead of re-testing rejects. See B-003.
- **Video: working.** The session used to die after 15-19 s with the car stuck
  on "Connecting Android Auto phone" while acknowledging every frame. Headway
  had never sent a `VideoFocusRequest`; openauto volunteers focus and this car
  does not, so the omission was invisible to every test. See ADR 0004 and the
  Phase 2 focus test.

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

## Two APKs per release, since 2026-08-14

A user could not install any build after 84 — "App not installed", no reason
given. The car-app host had added a `<permission>` and a `<provider>` to the
manifest, and both claim names that are **global to the device**: one definer
per permission name, one owner per provider authority. On a phone that already
holds either, the APK does not install at all, and every unrelated feature goes
down with it.

Every release now carries `-host.apk` (declares both, the default) and
`-compat.apk` (declares neither, installs anywhere, loses only the car-app
host). Same package, same key, either upgrades the other.
[ADR 0009](docs/adr/0009-two-apks-so-the-host-cannot-block-the-install.md) has
the derivation, including why the obvious cheaper fix — a disabled provider
enabled at runtime — is refuted from AOSP source rather than merely unattractive.

The exact `INSTALL_FAILED_*` code was never captured, so this is the most likely
cause rather than a proven one. That gap is itself now fixed:
`UpdateReceiver` reads `EXTRA_OTHER_PACKAGE_NAME` and the legacy status, names
the conflicting package, and writes it to the session log and the Updates card
instead of a Toast that fades.

## What is left

0. **One button that answers almost all of it, and it does not need the car.**
   Press **Run the self-test** on the setup screen. It binds every installed car
   app and prints what each said (**B-012**), lists every display with its flags
   so the `(secure)` trap is visible (half of **B-015**), names whatever package
   owns the renderer permission and the connection authority (**B-013**,
   **B-014**), reads back every grant, and connects to each media app's browser.

   The thing that kept these open was the belief that they needed a drive. They
   do not: a `CarAppService` is bound over local binder, so an app on the phone
   accepts or refuses Headway with no head unit involved. One report settles
   four blockers.

   What genuinely still needs the car: whether the capture chooser lists the
   simulated display as its own row (the other half of B-015), and everything
   drawn on the panel. The self-test's last section says exactly that.
1. **Real-hardware validation.** The one step that cannot be self-performed, and
   the only thing between this and a working car link.
2. **Release signing and F-Droid metadata.** The build is reproducible in shape;
   it has no signing key, which is the user's to hold.
3. ~~A car log saying which certificate the Malibu accepts~~ — **answered
   2026-08-13: `internal`**, the Android-Auto-Internal certificate valid to
   2048. The unit checks the chain and the dates and not the role. See B-003.

# Headway

An open-source Android app that replaces Google's Android Auto phone app
entirely. It speaks the reverse-engineered Android Auto Protocol (AAP) directly
to a factory head unit — wirelessly — with touch input coming back from the car
and the car's microphone driven by fully on-device speech recognition.

The car gets **its own screen**, not a copy of the phone's. Headway creates a
display at exactly the resolution and density the head unit advertised and draws
a dashboard on it: what is playing, with working transport controls for any app;
messages, with inline replies; the clock; your apps. Nothing is scaled, touch
lands 1:1, and your notifications and lock screen stay on your phone where they
belong.

A third-party app still reaches the car as **pixels inside a panel** of that
dashboard — the panels around it keep working while it runs. What an
unprivileged app *cannot* do is place someone else's window in a panel of its
own; that limit is the OS's, and it is derived from AOSP source in
[ADR 0004](docs/adr/0004-what-headway-can-put-on-the-car-screen.md).
[ADR 0006](docs/adr/0006-the-car-gets-its-own-screen.md) is why mirroring stopped
being the default and
[ADR 0010](docs/adr/0010-the-car-screen-is-panels-and-one-of-them-is-a-real-app.md)
is how an app ended up inside a panel instead of over everything.

No Google services. No network. No root. No system privileges.

> **Status: in development. A real car is showing Headway.** On
> 2026-08-13 a 2021 Chevrolet Infotainment 3 unit completed the Bluetooth
> handshake, the Wi-Fi join, TCP, the AAP version exchange, TLS, authentication
> and service discovery, opened all 13 of its channels, and then — once Headway
> started asking for video focus, which it had not been doing — put the phone
> screen on the dashboard and held it there: 2754 frames, 4 dropped.
>
> Not yet confirmed on hardware: audio, touch coming back from the car, and
> voice. Each had a distinct cause found in that same log and each has been
> fixed, but "fixed" here means "the reason it could not have worked is gone",
> not "observed working in a car". [`PROGRESS.md`](PROGRESS.md) records the tier
> of evidence behind every phase — read it before believing anything here works.

## How much of this is actually tested

The honest answer matters more than a badge, so it is written down. Every phase
in [`PROGRESS.md`](PROGRESS.md) carries one of four tiers, and that file is the
single definition of them:

| Tier | Means | Examples |
|---|---|---|
| **A — Executed** | Real code runs against real bytes and is asserted in CI | Framing, TLS handshake, session bring-up, the Bluetooth credentials exchange, video and input channels, the reconnection supervisor, the whole voice pipeline against a real speech model |
| **B — Framework-executed** | Runs on a real Android device or emulator image | Gesture building, audio resampling and `AudioManager` focus, the APK installing and its app tests passing on a real phone |
| **C — Compiled** | Type-checked against the real Android SDK, behaviour unverified | Paths that need hardware the emulator image does not have |
| **D — Unverifiable here** | Needs a phone, a car, or both | Anything measured in a car |

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
the other side, and the full handshake plus every channel state machine runs on
a bare JDK — no device, no NDK, no emulator image, and no Android SDK.

Nine modules, five of them Android-free:

```
core-protocol/      AAP framing, channel multiplexing, protobuf schemas     (JVM)
core-transport/     TCP, in-memory TLS, Bluetooth handshake codec, fakes    (JVM)
core-dash/          Pane kinds, layouts, the rail, themes, allow-list,
                    and the arithmetic that fits a picture into a panel     (JVM)
core-voice/         Speech recognition and the command grammar              (JVM)
headunit-emulator/  Head-unit test harness; hosts the acceptance suite      (JVM)
app/                UI, foreground service, permissions, Bluetooth, Wi-Fi,
                    the car screen itself, quirks, self-test, updates       (Android)
core-video/         MediaProjection capture -> MediaCodec H.264             (Android)
core-input/         Car touch/keys -> AccessibilityService gestures         (Android)
core-audio/         Audio channels, focus signalling, playback capture      (Android)
```

`core-dash` is the one worth naming: `PaneKind`, `DashLayout`, `Rail`,
`CarTheme`, `AllowedApps` and `PaneFit` all live there, off Android, so every
rule about what the car screen may show is executable in CI.

## What the car screen actually shows

Not the phone. Headway draws at the head unit's own resolution and density: a
**rail** across the top and a **tree of panels** underneath it.

The rail holds three things and nothing else — a settings button, a microphone
button, and whatever you pinned. A pinned item is either one of your layouts or
an app you open often. Both are your choice, in your order.

A layout is any arrangement of panels, of any depth, and you edit it **on the car
screen**: the rail's settings button, then *Edit this layout*, then split a
panel, drag a divider, choose what each one shows, or take one away. Layouts are
**locked by default**, so a thumb steadying itself on the dashboard cannot
rearrange your car; the same row reads *Save and lock* while you are editing.

Ten kinds of panel, in the order the picker offers them:

| Panel | What it is |
|---|---|
| **App** | **A shared app, live, inside the panel.** One panel at a time shows it |
| **Maps** | The navigating app's **own map**, drawn into the panel — when this build can host car apps and one of your allowed apps offers a navigation interface. Otherwise the next turn, large, and a button that opens your map app in the App panel |
| **Now playing** | Whatever is playing, with transport controls, from `MediaSessionManager`. Any media app |
| **Music and podcasts** | Walk a media app's library and start something, from its `MediaBrowserService` |
| **Phone** | The call in progress, and the last twelve calls from the call log |
| **Messages** | Incoming messages, with the posting app's own inline reply |
| **Car app** | A third-party app's *own* car interface, drawn by Headway — **several of these can run at once** |
| **Widget** | An app's own home-screen widget — **several of these can run at once, all different apps** |
| **All apps** | A grid to open an app from. Every launchable app, or just the ones you pinned |
| **Clock** | Time, date and link status |

Every panel except **App** is a **model** the app already publishes:
`MediaSessionManager` and `MediaBrowserService` for music,
`NotificationListenerService` for messages and the navigation feed,
`AppWidgetHost` for widgets, `androidx.car.app` templates for a car interface.
Headway renders them itself. Nothing is captured, nothing is scaled, and none of
it needs the phone's screen to be on.

**There is no full-screen mode.** "Full screen" is a layout with one App panel
in it, which you can make, pin and reach in one tap. The one thing that still
sends the car a raw capture of the whole phone is the switch
**"Draw the car screen (turn off only to diagnose)"** on the phone's *The car
screen* card. It is on by default and it is labelled that way on purpose: turning
it off gives you no panels at all, and it exists to tell a car that will not show
the drawn screen apart from a Headway bug.

**Several different apps on screen at the same time** is what **Widget** and
**Car app** panels are for. A screen-capture grant is one grant, one virtual
display, one app — Android allows a second of none of them — so a layout of four
App panels has one live panel and three places to move it to. A layout of four
Widget panels is four different apps drawing at once, live, by the apps
themselves, with nothing captured. The panel picker says so where you choose.

Adding a widget costs one tap on the phone the first time ever: Android makes an
app prove it may host widgets, and the permission that skips that dialog is
reserved for system apps. After that, widgets add with no phone interaction.

**Which apps may appear at all is yours to decide, one app at a time**, on the
phone, parked, under *Apps allowed on the car screen* → **Choose apps**. Nothing
is allowed by default. The app picker, the widget picker and the car-app list are
each filtered by that set, and every route that actually *opens* an app — the
rail, the grid, a map hand-off, a voice command — funnels through one check, so
an app you have not allowed cannot reach the car screen by any route.

**Themes**: three bases (dark, true black, light) and six accents, one of which
is no accent at all. Changed from the car screen or from the phone.

### Car apps, and why a third party can host them

An app that offers a car UI exports a `CarAppService` and hands back declarative
templates — a list, a grid, a navigation strip — and the *host* draws every
pixel. Headway is that host. A navigation app additionally gets a real `Surface`,
sized to the pane and told the car's dpi, so its map is its own rendering at the
car's resolution rather than a scaled screenshot; car touches come back as
scroll, fling, scale and click. That is what makes the **Maps** panel a real map
rather than a turn card.

The honest limit: an app decides in its own process whether to accept a host, and
most FOSS car apps ship Google's sample allowlist unchanged. Headway takes the
one route open to it — it declares and holds
`android.car.permission.TEMPLATE_RENDERER`, which is the fourth and last branch
`HostValidator` accepts. The reasoning, the decompiled evidence, and the two
install-time risks that come with it are in
[ADR 0007](docs/adr/0007-headway-as-a-car-app-host.md) and BLOCKERS B-012 to
B-014.

**On the `-compat` APK no car app can ever be hosted, by construction.** That
build deliberately declares neither the permission nor the
`androidx.car.app.connection` authority — see
[which of the two APKs to take](#which-of-the-two-apks-to-take) — so Car app
panels do not work there and the Maps panel falls back to the turn card. Headway
checks this before binding anything and says so in the pane, rather than spending
a bind and a watchdog timeout to render another app's refusal. If an app refuses
for its own reasons, the pane names the app and why.

## How an app's pixels reach a panel

Android will not let Headway draw another app, so an app's picture arrives the
only way it can: through the screen-capture grant you approve once per session.
That capture is pointed at the App panel's surface, and moving it to a different
App panel is a `VirtualDisplay.setSurface` call — instant, and it asks you
nothing.

**Share one app, not your whole screen.** When Android asks what to share, pick
the single-app option. Android then excludes the status bar, the navigation bar
and your notifications from what the car sees, and reports the app's own size
through `onCapturedContentResize` — so the panel gets the app at the app's shape
instead of a phone-shaped rectangle.

**Fit or fill.** A portrait phone inside a landscape panel has to give something
up. On the car screen, settings → **Apps and panels** → *Crop the picture to fill
the panel* / *Fit the picture inside the panel*. Fitting is the default: cropping
hides content, and losing a row of a list is a worse surprise than a bar down
each side. Cropping is the right answer for a map or a video, where the middle is
the point and the edges are chrome.

**Blank the phone screen while driving**, on the phone's *The car screen* card,
is **on by default**. It covers your phone with black for the drive so nothing of
the phone shows and nothing can be tapped by accident. It is safe as a default
only because it is self-gating: it goes up only once the capture has been
*measured* as a single app — where Android excludes system UI, so the car never
sees the cover — and never for a whole-display capture, where the cover would be
the entire picture. It needs the accessibility grant, and says so in the log if
it does not have it. Tap the black screen to bring the phone back.

The screen stays *on*, and that is not a bug to be fixed: Android stops a shared
app drawing when its display sleeps, so a phone that is on and black is the
closest there is to off. Turn the brightness down.

### Optional: the simulated car display

**This used to be the recommended route and is no longer.** It is off by default,
and an existing install that had it on is moved off it once, automatically, with
a line in the log saying so — an App panel renders the phone's own screen just as
well and crops to fill, and the two costs below were being paid on every drive by
drivers who may not remember choosing them.

It is still the only way to get a genuinely *car-shaped* picture, so it stays,
and the reasoning is worth keeping. The arithmetic is the argument: the phone is
1080×2404, the panel is 800×480, the platform min-scales, so a mirrored phone
occupies **216 of 800 columns** and the rest is a black bar. Android refuses to
let an app place another app's window on a display *it* created — but a display
**Settings** creates is trusted, and any app may be launched onto a trusted
display. So an app can be told to lay itself out for 720×480 instead.

1. Settings → System → Developer options → **Simulate secondary displays** →
   `720x480/142`. **Not** an entry labelled `(secure)`; those cannot be recorded
   and produce a black car screen with no error. To check which one you got,
   press **Run the self-test** on the setup screen — its Displays section prints
   every display's flags and says which are usable.
2. Same screen → **Disable screen-share protections for apps and notifications**.
   Android 15 and later stop a screen capture when the phone locks and ask for
   consent again on the next unlock, which would cost the car its picture every
   time; this toggle is the reported mitigation, though whether it is the one
   that governs that behaviour is not something this project has been able to
   confirm from source. Harmless to try, and B-015 records the uncertainty.
3. Headway's setup screen → *How apps reach the car* → **Render apps on the car
   display instead of mirroring**. Or, on the car screen, settings → *Apps and
   panels* → **Run apps on a simulated display**. Either way it takes effect on
   the next connection, and the car screen says so when you change it.
4. When Android asks what to share, pick the row named for that display — not
   "Entire screen".

[ADR 0008](docs/adr/0008-native-app-rendering-on-a-simulated-display.md) has the
derivation and the source citations.

Two costs, both real, and both are why this is no longer the default. The size
list is fixed by Android and has no 800×480, so there is a 40-pixel black bar
down each side. And **a half-size window sits on your phone for the whole
drive** — it is not a preview, it is the simulated display's output surface.
`OverlayDisplayWindow` inflates a `TextureView` and hands its `SurfaceTexture` to
SurfaceFlinger as that display's device surface, so closing the window destroys
the display and the car picture with it.

In order of what does not work about that window:

- **No setting hides it.** `OverlayDisplayAdapter.parseFlags` accepts exactly
  `secure`, `own_content_only`, `should_show_system_decorations`,
  `fixed_content_mode`, `disable_window_interaction`, `unique_id=`, three
  display-type tokens and four gravity tokens. None affects visibility.
- **It cannot be shrunk away.** `MIN_SCALE` is `0.3`, so the 720×480 entry
  bottoms out at 216×144. You *can* pinch it to that minimum and drag it into a
  corner, which is free and helps — but the position and scale are plain
  instance fields and reset on reboot.

What does work is the blanking switch above: `TYPE_ACCESSIBILITY_OVERLAY` is
window layer **31** against the preview's `TYPE_DISPLAY_OVERLAY` at **29**, and
`WindowManagerService.sanitizeWindowType` allows it from a bound accessibility
service — which Headway already is, for touch injection. It suppresses no
recording indicator: Headway's foreground-service notification and the system's
recording chip in the shade are untouched.

**If you are willing to use adb or Shizuku once** — outside Headway's no-adb
promise, and entirely optional — Android will also let you make the window
click-through and pick an exact size, which removes the 40-pixel bars as well:

```
adb shell settings put global overlay_display_devices \
  '800x480/160,disable_window_interaction,gravity_bottom_right'
```

## The Voice button, and what it listens with

The car's cabin microphone, over the AAP AV-input channel, into a Vosk model that
ships in the APK. No network, at any point: recognition happens on the phone and
nothing is written to disk.

The grammar is deterministic and rule-based — no model, no ranking heuristics
that change between runs — and matches on content words rather than requiring a
literal leading verb, because small speech models mis-hear short function words
constantly. It handles opening an app by name (fuzzy-matched against installed
labels), play/pause/next/previous, volume, "go home", and navigation hand-off.
Free-text search is recognised and deliberately **not** executed: typing into
another app's field needs `canRetrieveWindowContent`, and
`accessibility_service_config.xml` sets that false as a promise that Headway
injects input but never reads the screen. `BLOCKERS.md` tracks it.

A head unit that refuses to offer its microphone leaves the button with nothing
to hear. Headway says so, and — if you grant the microphone permission — listens
with the **phone's** microphone instead. That is a fallback and stays one: the
cabin microphone is echo-cancelled, aimed at the driver and above the road noise,
and a phone in a cup holder is none of those. The permission is never requested
at launch; the *Phone microphone (voice fallback)* row in the setup checklist
asks for it only when you press its **Grant** button.

## Quick start

1. **Install** the APK — see [Installing and updating](#installing-and-updating)
   for which of the two to take.
2. **Read the safety notice** that appears on first run.
3. **Work down the "Ready to connect?" checklist.** Six rows, each with its own
   remedy button, and the remedy disappears once the row is green:

   | Row | Button | What it is for |
   |---|---|---|
   | Bluetooth, Wi-Fi and notifications | Grant | Finding the car, joining its network, staying alive with the screen off |
   | Car touchscreen | Open | The accessibility grant, so touches from the car can be injected |
   | Now playing and messages | Turn on | Notification access — without it those two panels have nothing to show |
   | Calls and contacts | Grant | The Phone panel. Optional |
   | Offline speech model | — | Installs itself; nothing to press |
   | Phone microphone (voice fallback) | Grant | Optional, and only if you want voice when the car offers no mic |

4. **Choose which apps may appear on the car screen**: *Apps allowed on the car
   screen* → **Choose apps**. Nothing is allowed by default, and an empty list
   means the car offers nothing.
5. **Press "Run the self-test"**, on the *Self-test* card under *If something is
   wrong*. It answers everything answerable without a car, in one press, in a
   few seconds per app.
6. **Press "Connect to the car."**

### The self-test, and how little of this needs the car

The insight it is built on: a `CarAppService` is bound over **local binder**, so
Organic Maps on the phone accepts or refuses Headway with no head unit involved,
no Wi-Fi, and no drive. Four `BLOCKERS.md` entries sat open on the assumption
that settling them required a car; they did not.

Six sections, and it changes nothing and asks for no permission:

- **Car apps — does anything accept Headway as a host?** Binds every installed
  car app and runs the real `androidx.car.app` handshake, reporting per app
  whether it accepted Headway, refused it (with the app's own error text), or
  never answered.
- **Displays** — every display with its flags, which is the only way to tell a
  usable simulated display from a `(secure)` one.
- **Grants** — the renderer permission, the car-connection provider, notification
  access, accessibility, and the four telephony permissions.
- **Install collisions** — which package on this phone defines
  `android.car.permission.TEMPLATE_RENDERER` and owns the
  `androidx.car.app.connection` authority.
- **Media apps — whose library can Headway walk?** Connects to each media app's
  browser and says whose library opens.
- **Still needs the car** — the short list that genuinely does.

The report can be copied or shared as text, and it also goes into the session
log.

## First connect to a real car

Do these in order. Most first-connect failures are one of the first four.

1. **Pair the phone with the car normally**, in Android's Bluetooth settings.
   Headway does not reimplement pairing and cannot connect without it.
2. **Turn Wi-Fi on** on the phone. It does not need to be connected to
   anything — the car's network has no internet — but the radio has to be on.
   Android refuses Headway's request outright if it is off, and reports that
   refusal in a way no app can distinguish from any other failure.
   On GrapheneOS, also leave Headway's **Network** permission on. It is
   tempting to revoke it on an app that never uses the internet, but that
   toggle makes the platform pretend every network is down, including the
   car's — nothing is sent anywhere either way.
3. **On the car screen**, check the vehicle's own settings: Bluetooth on, Wi-Fi
   on, and Android Auto enabled for this phone. On Chevrolet Infotainment 3
   that is Settings → Bluetooth, Settings → Wi-Fi, and Settings → Apps →
   Android Auto. The head unit's projection access point does not come up until
   Wi-Fi is enabled there, and a projection access point that is not on the air
   looks exactly like a phone that cannot see it.
   *An OnStar data plan is not required* — projection uses the head unit's own
   access point, not the in-vehicle hotspot's internet.
4. **Make sure no other phone is already projecting.** A head unit that is
   busy will still hand over credentials and then refuse the session.
5. Press **Connect to the car** in Headway. Android will show a prompt asking to
   let Headway join the car's network. **Tap the car on that prompt and leave it
   on screen** — do not switch back to Headway to see how it is going. That
   prompt is a separate activity, and covering it is what makes it
   unrecoverable. Android remembers the approval afterwards, so this is a
   once-per-car step, not a once-per-drive one.
6. **Pair with the car's Wi-Fi**: setup screen → *Car Wi-Fi* → **Pair**. Step 5's
   approval prompt is remembered, but Android drops it in several ordinary
   circumstances — a failed join, a reinstall, a secondary Wi-Fi interface it
   cannot bring up. Pairing routes the request through the companion-device
   system, which `WifiNetworkFactory` consults *before* it shows the prompt at
   all. That is what makes reconnecting with the phone in a pocket possible, and
   it is why this step is here rather than in an optional appendix. The car has
   to be on and showing Android Auto when you pair, because the list is built
   from a live scan.

### After that, it connects on its own

**"Connect on its own when the car appears" is on by default**, on *The car
screen* card. From then on you should not have to touch the phone to start a
drive, and turning that switch off is how you stop it.

Two mechanisms, and they cover different failures:

- **A session that is already running reconnects itself.** `SessionSupervisor`
  retries with exponential backoff capped at 8 seconds — deliberately low,
  because the failure being retried is almost always "the car is not in range
  yet", which resolves the instant you get in. Worst case from the car becoming
  available to a completed connection is one full backoff wait plus one connect
  attempt, inside the 15 seconds CLAUDE.md asks for. Retries are unbounded: a
  phone that sat in a driveway overnight should not need a tap in the morning.
- **A session that is not running is started by the car's Bluetooth.** A manifest
  receiver listens for `ACTION_ACL_CONNECTED`, plus boot and self-update, and
  brings the foreground service up. It costs nothing when no car is in range,
  because it is a broadcast rather than a poll.

The device has to prove it is a car before anything starts: Headway remembers the
Bluetooth address that reached a *handshake*, not the one you picked, and
compares against it — otherwise every pair of headphones would bring an AAP
session up. With nothing remembered yet, the device must advertise the Android
Auto wireless RFCOMM service.

Starting a foreground service from the background is normally forbidden since
Android 12. Headway's exemption is not a loophole: an app holding an active
`CompanionDeviceManager` association — which step 6 above creates — and declaring
`REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND` may start one. When
the platform refuses anyway, the refusal becomes a notification you can tap,
never a silent failure.

## Why the same car behaves differently from one attempt to the next

Because a connection is not one thing that works or does not. It is **five
gates in series**, each with an independent cause of failure, and you never
see gate *n+1* until gate *n* happens to pass. Every setting worth fiddling
with moves exactly one gate, which is why changing one can look like it fixed
everything and then look like it fixed nothing.

| # | Gate | Fails when | Moved by |
|---|------|-----------|----------|
| 1 | Bluetooth RFCOMM handshake | the head unit is not offering the AA Wireless service, or thinks a session is already running | the car's **connect-device-first** / priority setting; whether the car is already talking to another phone |
| 2 | The car's access point is on the air | projection Wi-Fi is off in the car, or its AP has not finished coming up | the car's Wi-Fi setting; how far the previous attempt got |
| 3 | Association | wrong passphrase, wrong BSSID, no 5 GHz | `pinBssid`, `hiddenSsid` |
| 4 | Getting an IP | the head unit's address table is full — see `IP_PROVISIONING` below | **DHCP vs static IP** |
| 5 | TLS and authentication | the phone's certificate is outside the head unit's validity window | the imported certificate, or the car's clock |

Read across the variables you have been changing:

- **Static IP versus DHCP** only touches gate 4. It cannot make a car that is
  not offering its access point start offering one, and it cannot do anything
  about the certificate — so "static IP got me further and then it still
  failed" is exactly what a fixed gate 4 in front of a stuck gate 5 looks
  like.
- **Already being on the car's Wi-Fi** skips gates 3 *and* 4 outright, which
  is why a manual join changes so much at once. It also changes something
  subtler: a network Headway requests is *local-only* — it carries no default
  route and every socket has to be bound to it explicitly — whereas a network
  you joined yourself is the phone's ordinary default network. Two quite
  different code paths reach the same head unit.
- **Bluetooth already being connected** changes who is mid-sequence when you
  press Connect. The head unit is the one that speaks first on RFCOMM
  (`docs/protocol-notes.md`, step 6), and it brings its access point up as
  part of that exchange. Arriving while the car is partway through — or after
  it has already given up — is not the same as arriving cold.
- **Connect-device-first** changes gate 1's *initiator*. With it on, the car
  pokes the phone over Bluetooth at ignition and waits for Google's Android
  Auto to answer by opening the RFCOMM channel (step 3). Headway is not
  Android Auto and does not answer that poke; it opens the channel itself when
  you press Connect. So the setting decides whether the car has already
  started, and possibly already abandoned, an attempt of its own before
  Headway arrives.

Practically: if you want repeatable results while debugging, hold gates 1–4
still — car Wi-Fi and Bluetooth on, no other phone paired in, joined by hand
with a static IP — and change one thing at a time. The log names the gate it
reached on every attempt: export it from *Diagnostics* → **Export the session
log** and read the join lines, which say what the platform's own verdict was —
`NOT_FOUND`, `AUTHENTICATION`, `ASSOCIATION`, `IP_PROVISIONING` or `NO_RESPONSE`.
Each points somewhere different.

### If you run a VPN

Headway binds its whole process and every socket to the car's network, which is
enough for an ordinary VPN. It is **not** enough for one set to *block
connections without VPN* — Android's lockdown mode — where the kernel drops
non-VPN traffic for every app that is not exempt, and there is no unprivileged
way to be exempt: the permission Android Auto relies on
(`CONNECTIVITY_USE_RESTRICTED_NETWORKS`) is reserved for system apps. The symptom
is exact and misleading: Bluetooth completes, the Wi-Fi join completes, and then
nothing.

Two fixes, both in your VPN app. The setup screen shows an *A VPN is running*
card whenever one is up, which names it and offers a button that opens it:

- **Add Headway to the VPN's excluded / split-tunnelling apps.** The narrower of
  the two: the rest of your phone stays on the VPN.
- **Or turn off "Block connections without VPN"** (called always-on or lockdown
  depending on the app).

### If the log says `IP_PROVISIONING`

This one has a specific cause and a specific fix. It means the phone got onto
the car's Wi-Fi — the passphrase was right and the radio accepted it — and then
the head unit never handed out an IP address.

There are two candidate causes and they have different fixes, so check the
Bluetooth one first — it is free.

**The head unit may not think a phone is present.** A head unit generally
completes projection bring-up, DHCP included, only for a phone it considers
connected. If the log line `Bluetooth profiles:` reads `headset=disconnected,
a2dp=disconnected`, connect the car for **Phone calls** and **Media audio** in
Bluetooth settings — the head unit uses those profiles to decide a phone is
present, whether or not it later routes audio over them; it will in fact drop
A2DP once projection starts, which is why Headway sends music over AAP instead
([ADR 0005](docs/adr/0005-media-audio-goes-over-aap-not-a2dp.md)) — and check
**Android Auto is still enabled for this phone** on the car screen. Disabling
Android Auto in the *car* is not the same as not using the Android Auto *app*;
Headway takes the place of the app and still needs the car's permission.

**First: does your log say `access_point_type=STATIC`?** If it does, the head
unit is telling you it assigns no addresses at all, and no amount of MAC or DHCP
tuning will get you one — a static IP is simply the correct configuration for
that vehicle. The 2021 Malibu does exactly this. Everything below applies to a
unit that advertises `DYNAMIC` and still withholds a lease.

**Otherwise it is likely the GrapheneOS car bug, which GrapheneOS has already
fixed for Google's Android Auto and cannot fix for anything else.**

GrapheneOS gives every network an app joins a **brand new MAC address on every
single connection**, so the car sees an unfamiliar device every time. It carries
a carve-out for exactly this, keyed on Google's Android Auto package
(`WifiConfiguration.java` L3400-L3405):

```java
if (android.app.compat.gms.GmsCompat.isAndroidAuto()) {
    // Per-connection MAC randomization doesn't work with some cars, see
    // https://github.com/GrapheneOS/os-issue-tracker/issues/4139
    macRandomizationSetting = RANDOMIZATION_PERSISTENT;
    mIsSendDhcpHostnameEnabled = true;
}
```

Note it is **two** settings, and Headway can reach neither from the network it
requests: the MAC comes from a configuration built in Headway's own process
(where the GrapheneOS default re-randomizes every connect), and the DHCP
hostname setter is a system API. Worse, a network an app *requests* is not a
*saved* network, so it has no Settings entry for you to fix either.

**The fix is to make it a saved network and set both by hand — once.**

1. Setup screen → *Car Wi-Fi* → **Set up this car's Wi-Fi**. This hands the
   network name and password Headway learned over Bluetooth to Android's own
   "save this network" panel, so you never type them. (Joining by hand in Wi-Fi
   settings works just as well if you prefer.)
2. On that saved network, open the gear icon and set:
   - **Privacy → "Use per-network randomized MAC"**
   - **"Send device name to network" → on**
3. Press Connect to the car.

Set the first, retry; if it still fails, set the second and retry. **Which one
fixes it is genuinely useful information** — it is the difference between the
car objecting to an unfamiliar MAC and the car objecting to a request with no
hostname, and nobody has established which yet. Please report it.

There is also `"suggestCarNetwork": true` in the quirk file, which makes Headway
register the car as a Wi-Fi *suggestion* rather than requesting it. That is the
only public API carrying a MAC preference, so it reaches the first setting
without a Settings trip — but Android then decides when to connect rather than
Headway, the first one needs you to accept a notification, and it does nothing
about the hostname. It is off by default for those reasons.

`BLOCKERS.md` B-006 has the full account with source citations.

**If neither toggle helps, the head unit's address table may be full** — though
be sceptical of that story if this phone has *never* received an address from
this car. Exhaustion explains a car that worked and then stopped, not one that
never worked. Note too that `IP_PROVISIONING` means "provisioning did not
finish", not "no offer arrived", so an address that arrived and was rejected
looks identical from here.

**You cannot inspect a head unit's address table.** It has no administration
page — the usual advice about clearing one assumes a home router, and none of
that applies to a car. So test it from the outside instead:

- **Join the car's Wi-Fi with a device that has never connected to it** — a
  laptop, another phone. If that device gets an address, the table is not full
  and this is not your problem.
- **Set a static IP** (below). If the session then comes up, leasing was the
  only thing broken.

Note also that DHCP leases expire, typically within a few hours on an embedded
server. A table that fills up during an afternoon of retries should recover on
its own overnight. If the failure persists across a night, exhaustion is
probably *not* the cause and the Bluetooth checks above are the better lead.

To clear it anyway, in increasing order of disruption:

1. **Turn the vehicle's Wi-Fi off and on** in the car's settings. This restarts
   the access point and usually its DHCP server with it. Try this first.
2. **Fully power the infotainment system down.** Switching the ignition off is
   often not enough — these units stay awake for a while. Turn the car off,
   open the driver's door, lock the car and leave it for ten minutes.
3. **Factory-reset the infotainment system** (Settings → System → Return to
   Factory Settings on Chevrolet Infotainment 3). This loses your pairings and
   settings, so it is a last resort.

**To tell projection state and address exhaustion apart:** connect real Android
Auto, let it come fully up, disconnect it, and immediately press Connect to the
car in Headway. If that joins where a cold attempt does not, it is projection
state rather than the address table, and the Bluetooth checks above are the fix.

### Or skip DHCP entirely with a static IP

If the head unit will not hand out an address, stop asking it for one. This
cannot be done through Headway — Android gives an app no IP configuration for a
network it requests — so it goes through the same manual-join path:

1. Join the car's Wi-Fi by hand from Android's Wi-Fi settings.
2. Open the saved network → **Advanced** → **IP settings** → **Static**.
3. Set an address on the head unit's subnet that is not the head unit itself.
   The log line `head unit offers 192.168.5.1:7001` names it, so
   `192.168.5.150` with **gateway** `192.168.5.1`, **prefix length** `24` and
   DNS `192.168.5.1` fits that car. A high address avoids colliding with
   whatever the unit hands out when it is working.
4. Press Connect to the car. Headway notices the phone is already on the car's
   network and adopts that connection, so there is no approval prompt and no new
   MAC on any future attempt.

This is worth doing even if you think the address table is fine, because it
tells you something either way. If the session comes up, addressing was the
whole problem. If the phone is on the network and the AAP connect still fails,
the head unit is not ready to project and no amount of address wrangling will
change that — go back to the Bluetooth checks.

### If the car says the phone and vehicle calendars disagree

It does not mean your clocks are wrong. That message is a Chevrolet Infotainment
3 unit reporting a **certificate validity failure**, and in the log it shows up
as the session completing TLS and then

```
head unit rejected authentication: STATUS_AUTHENTICATION_FAILURE
```

The certificate every open-source Android Auto implementation carries — Headway
and AACS ship the identical one — expired on **2022-08-24**. It is signed by
Google's Automotive Link CA, so it cannot be reissued or re-dated by anyone
outside Google, and a self-signed replacement only helps if the head unit checks
dates without checking the chain. This unit returned
`STATUS_AUTHENTICATION_FAILURE` rather than `STATUS_CERTIFICATE_ERROR`, which
reads as "the chain was fine, the dates were not".

> **Answered on a real car (2026-08-13): the `internal` certificate works.** A
> 2021 Chevrolet Infotainment 3 unit refused the expired phone-role certificate
> and accepted `Android-Auto-Internal` on the next attempt — TLS established,
> authentication complete, service discovery done. Headway gets there on its own
> in two attempts; putting `"certificate": "internal"` in the quirk file skips
> the wasted first one. **You do not need to move the car's clock**, so Google's
> Android Auto keeps working too.

**Headway tries three certificates before you have to do anything.** The
expired phone-role certificate is not the only material signed by that same
Google Automotive Link CA sitting in the reference implementations — two others
are, and neither has expired:

| id | issued for | expires |
|----|-----------|---------|
| `phone` | the phone role, which is correct | 2022-08-24 |
| `internal` | a head unit (`Android-Auto-Internal`) | **2048** |
| `headunit` | a head unit (`JVC Kenwood`) | **2045** |

No phone implementation has ever presented a head-unit certificate, because the
role is wrong. But "wrong role" only matters if the car looks at the role. If it
checks the chain and the dates — which is what its
`STATUS_AUTHENTICATION_FAILURE` rather than `STATUS_CERTIFICATE_ERROR` points at
— then an unexpired sibling passes and the subject never comes up.

Nobody knows which, so Headway finds out: each authentication rejection advances
to the next certificate and reconnects. Two failed sessions, then either it is
connected or all three are refused. The log names the one in use and says why
it is worth a try, and if one is accepted it tells you the `"certificate"` value
to put in the quirk file so future connects start there.

**If all three are refused, there is no automatic fix, and no reference
implementation has one.** aa-proxy-rs, which is actively maintained and works
with real head units, does not bundle a certificate at all — it loads the pair
from a path the operator provides. Headway does the same, with a one-time
import:

Setup screen → *Phone certificate* → **Import a certificate and key**. Pick the
PEM certificate, then its PKCS#8 private key. Every session from then on uses
them; there is nothing to repeat. The card shows which certificate is in use and
when it expires, the session log says the same on every attempt, and **Go back
to the bundled certificate** on the same card undoes it. An imported certificate
also switches the rotation off — that was a deliberate choice and rotating away
from it would undo it.

If your key is in RSA rather than PKCS#8 form, convert it once:

```bash
openssl pkcs8 -topk8 -nocrypt -in phone.key -out phone_key.pem
```

The other option, if you have no certificate to import, is to set the **car's**
clock to a date inside the expired certificate's validity window — it ran from
2014-07-04 to 2022-08-24. The head unit judges validity against its own clock,
so this makes its check pass. Headway always presents its certificate rather
than letting the phone's TLS stack quietly withhold an expired one, so the head
unit gets to make that judgement.

**This breaks Google's Android Auto for as long as the clock is wrong, and the
two cannot both work.** Google's certificate is current, so a car whose clock
says 2016 sees it as *not yet valid* and refuses it — with the same "the phone
and vehicle calendars are set to different dates and times" screen, for the
mirror-image reason. That is why the protocol has a distinct
`STATUS_AUTHENTICATION_FAILURE_CERT_NOT_YET_VALID` (-23) next to
`..._CERT_EXPIRED` (-24). One car clock cannot satisfy an expired certificate
and a current one at the same time.

So treat the clock as a diagnostic, not a setting: use it to prove the
certificate is the only thing left in the way, then put it back. Importing
current material is the only route that leaves both Headway and Android Auto
working, and restoring the car's clock is the only route back to Android Auto.

## Settings, and the quirk file

Every switch, its card and its default:

| Switch | Card | Default |
|---|---|---|
| Connect on its own when the car appears | The car screen | **on** |
| Blank the phone screen while driving | The car screen | **on** (applies only to a single-app share) |
| Draw the car screen (turn off only to diagnose) | The car screen | **on** |
| Render apps on the car display instead of mirroring | How apps reach the car | off |
| Probe for a hidden network name (`hiddenSsid`) | If the car's Wi-Fi is never joined | off |
| Tell the car which Wi-Fi channel we accept (`announceWifiChannel`) | If the car's Wi-Fi is never joined | off |
| Only allow video apps while parked | Video while driving | off |

Two more live on the car screen itself, under settings → *Apps and panels*: where
apps run (the phone's screen, default; or a simulated display) and how their
picture is placed (fitted inside, default; or cropped to fill).

### The quirk file

**The switches above are the supported route, and they write this file for you.**
The file itself lives in Headway's private storage —
`…/files/head-unit-quirks.json`, and the app prints the exact path when it
creates it. No file manager can open that directory without root, and moving it
to `Android/data` would not help either: DocumentsUI has blocked navigation there
since Android 11. On a debug build `adb shell run-as dev.headway.app` reaches it;
on a release build, nothing a driver has in a car does.

Press *If the car's Wi-Fi is never joined* → **Create the head unit quirk file**
and Headway writes a starting point with the real key names in it, so there is no
blank page. This is exactly what it writes:

```json
{
  "version": 2,
  "profiles": [
    {
      "make": "*",
      "model": "*",
      "maxFragmentSize": 16384,
      "announcedVersion": "1.6",
      "mediaAudioOverAap": true,
      "keyframeIntervalFrames": 25,
      "hiddenSsid": false,
      "announceWifiChannel": false,
      "suggestCarNetwork": false,
      "videoFocusRequest": true,
      "touch": {
        "invertX": false,
        "invertY": false,
        "swapAxes": false,
        "scaleX": 1,
        "scaleY": 1,
        "offsetX": 0,
        "offsetY": 0,
        "useTouchscreenGeometry": false
      }
    }
  ]
}
```

`make` and `model` are matched against what the head unit says it is in service
discovery, with `*` meaning "any" and a trailing `*` meaning "starts with"; the
most specific profile wins, and among equals the last one listed wins, so
appending a profile overrides an earlier one. A profile only ever *overrides* the
built-in defaults, so an empty, absent or corrupt file behaves exactly like no
file at all — nothing here throws, and every unknown key, bad number or stray
comma becomes a warning shown in the settings screen and written to the log
rather than a car you cannot connect to.

Two keys are deliberately **absent** from the template, because absent is a
meaningful value:

- `"pinBssid": true` or `false` — whether to require the exact BSSID the head
  unit named. Absent means Headway alternates between the two on successive
  attempts, because both have been necessary on real hardware.
- `"certificate": "phone"`, `"internal"` or `"headunit"` — which bundled
  certificate to offer first. Absent means start at `phone` and advance on each
  rejection. Set it once the log has told you which one this car takes. It moves
  that one to the front rather than pinning it, so a stale value costs an
  attempt, not the connection. An unknown id is ignored rather than fatal.

The rest, briefly: `maxFragmentSize` is the largest plaintext payload put in one
frame before fragmenting (16384 is aasdk's, 2000 is the known-working fallback
for a unit that chokes); `announcedVersion` is what Headway claims in its
version response; `mediaAudioOverAap` sends third-party music over the AAP media
channel rather than leaving it on A2DP, and is on by default because A2DP does
not survive projection on the target vehicle
([ADR 0005](docs/adr/0005-media-audio-goes-over-aap-not-a2dp.md));
`keyframeIntervalFrames` is the forced-IDR cadence for a unit that shows black
until it happens to get one; `suggestCarNetwork` is described above under
`IP_PROVISIONING`; and the `touch` block is speculative headroom for a unit whose
touch coordinates arrive mirrored, rotated or offset. Nothing in the references
documents any unit needing the last one.

## Building

Requires a JDK 17 or later — CI uses 17, and 21 works. Two entry points, and
which one you need depends on whether you are building an APK.

**The command that always works, with no Android SDK and no network beyond Maven
Central:**

```bash
./gradlew -p tools/jvm-only test
```

`tools/jvm-only/settings.gradle.kts` is a second settings file pointing at the
real module directories — no copy, no second source of truth — that includes
exactly the five Android-free modules: `:core-protocol`, `:core-transport`,
`:core-voice`, `:core-dash` and `:headunit-emulator`. It exists because the root
build declares the Android Gradle Plugin, which lives only on Google's Maven
repository, and AGP is resolved while configuring the **root** project. So in a
sandbox, an offline CI runner or an F-Droid-style reproducibility check, even
`./gradlew :core-protocol:test` fails before a single test runs:

```
Plugin [id: 'com.android.application', version: '8.7.3', apply: false] was not found
```

That is not a broken checkout. It is the root build wanting `dl.google.com`.

**Everything else needs the Android SDK.** Point `ANDROID_HOME` at an SDK with
platform 35, or write a `local.properties` with `sdk.dir=…`, and then:

```bash
# Both APK variants
./gradlew :app:assembleHostDebug :app:assembleCompatDebug

# Android unit tests
./gradlew :app:testHostDebugUnitTest

# No Google Play services anywhere in the resolved dependency graph
./gradlew checkNoGms
```

**The Android build downloads a ~41 MB speech model** —
`vosk-model-small-en-us-0.15` from `alphacephei.com`, three attempts with
timeouts, SHA-256 pinned, cached under `.gradle/model-cache`. On a machine that
cannot reach it,
build with **`-Pheadway.model=none`** for a slim APK with no model. The APK is
then smaller and voice does not work on it, which is the trade the flag exists to
let you make deliberately.

Two checks need no JDK at all, because they are shell scripts over the source
tree:

```bash
./tools/check-license-headers.sh    # GPLv3 header on every source file
./tools/check-todos.sh              # every TODO has a BLOCKERS.md entry
```

And two more operate on things already built:

```bash
./tools/check-install-claims.sh        # the two APKs differ in exactly the two names
./tools/run-instrumentation-tests.sh   # instrumented tests over adb, for a no-KVM emulator
```

### The voice pipeline against a real model

```bash
./tools/fetch-vosk-model.sh
./gradlew -p tools/jvm-only :core-voice:test
```

The script unpacks to `/opt/vosk` unless `HEADWAY_VOSK_MODEL_DIR` says otherwise,
and the test reads `/opt/vosk/vosk-model-small-en-us-0.15` unless
`-Pheadway.vosk.model=…` or the `HEADWAY_VOSK_MODEL` environment variable points
somewhere else. **Worth knowing: without the model the Phase 5 acceptance test
skips rather than fails**, so a contributor who does not care about voice still
gets a green build — and a green build proves nothing about voice unless the
model was there.

### CI

Three workflows, and they do different jobs:

- **`.github/workflows/pr.yml`** — the fast gate on every pull request. Licence
  headers and TODOs; `./gradlew -p tools/jvm-only test` for all five JVM modules
  with no SDK installed at all; both APKs assembled, checked for install claims,
  Android unit tests, and `checkNoGms`. One `PR gate` job aggregates the rest so
  branch protection never has to be edited when a job is added.
- **`.github/workflows/ci.yml`** — the full suite, on every branch push. The
  protocol core against its byte fixtures, the phase acceptance suite against the
  emulator, Phase 5 voice against a real Vosk model and real recorded speech, the
  Android build and unit tests, instrumented tests on an AOSP emulator image with
  no GMS, and the hard constraints from CLAUDE.md.
- **`.github/workflows/release.yml`** — on a push to `main`. Calls `ci.yml` as a
  reusable workflow so publishing runs exactly those gates rather than a drifting
  copy, then publishes the APKs as a GitHub release.

## Verifying it yourself with the emulator

The emulator is a runnable head unit, so you can check the stack without a car.
Run it through the JVM-only build and it needs no Android SDK:

```bash
# 1. On your laptop alone. Proves framing, TLS, auth, discovery, channel open.
./gradlew -p tools/jvm-only :headunit-emulator:run --args="--self-test"

# 2. Wait for your phone. Prints the addresses to point Headway at.
./gradlew -p tools/jvm-only :headunit-emulator:run --args="--listen"

# 3. Check reachability without a phone, from this or another machine.
./gradlew -p tools/jvm-only :headunit-emulator:run --args="--connect 192.168.1.50"
```

Also accepted: `--port <n>` (default 5288), `--seconds <n>` for how long a
`--listen` session keeps reporting (default 60), and `--help`, which is also what
you get for no arguments.

`--self-test` narrates both sides and ends with a verdict:

```
  [phone] head unit announced AAP 1.6
  [car ] TLS established
  [car ] sent AuthComplete
  [phone] authentication complete
  [phone] head unit 'Headway Emulator' advertised 6 service(s): id=3 video sink, …
  [phone] session ready; 6 channel(s) open

Result
------
  head unit      Headway Emulator
  AAP version    1.6
  channels open  6
                 MEDIA_SINK_VIDEO
                 MEDIA_SINK_MEDIA_AUDIO
                 MEDIA_SINK_SYSTEM_AUDIO
                 MEDIA_SINK_GUIDANCE_AUDIO
                 INPUT_SOURCE
                 MEDIA_SOURCE_MICROPHONE

PASS - the protocol stack completed a full session.
```

`--listen` runs a real head unit on TCP 5288 and reports what arrives: codec
configuration, video frame count and bytes, and the frame rate measured from the
presentation timestamps, plus a tally of every other message by channel. It
passes only if video actually arrived. That is the strongest verification
available short of a car — a real phone, real sockets, real TLS, real video off a
real encoder.

Two honest caveats. `--self-test` shares protocol code with the phone side, so a
wrong-but-symmetric constant round-trips cleanly and proves self-consistency
rather than correctness; the byte fixtures are the real oracle
([ADR 0002](docs/adr/0002-jvm-headunit-emulator.md)). And none of it is evidence
about a Chevrolet; only a Chevrolet is. When one was finally asked, it
disagreed — see [`docs/protocol-notes.md`](docs/protocol-notes.md) § "Evidence
from a real head unit" for what a single real capture cost and bought.

## Working on the protocol

aasdk's 254 protobuf schemas *are* vendored, under
`core-protocol/src/main/proto/aap_protobuf/` — retyping them is the most
error-prone option available, since one wrong field number parses locally and is
rejected by a real head unit. They keep aasdk's authorship; see
[`THIRD-PARTY.md`](THIRD-PARTY.md). One schema is Headway's own —
`core-protocol/src/main/proto/headway/aaw_version.proto`, for the wireless
Bluetooth version exchange, which aasdk has no equivalent of.

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

## Installing and updating

Builds are published as GitHub releases. Once one is installed, **Check for
updates** on the *Updates* card at the bottom of the main screen fetches the
newest and hands it to Android's installer; you will need to allow Headway to
install unknown apps, a normal per-app setting.

That check is the only thing in Headway that touches the internet, and it runs
only when you press the button — no background poll, no check on launch, nothing
sent. CLAUDE.md's "no update checks" rule is about the app never depending on
connectivity or phoning home, and both still hold: Headway starts, connects to
the car and drives with no network at all.

### Which of the two APKs to take

Every release carries two. They are the same app, built from the same commit,
signed with the same key, and they differ in **two lines of manifest**.

| | `-host.apk` | `-compat.apk` |
|---|---|---|
| Car-app host (Organic Maps, OsmAnd et al. drawing on the car screen) | yes | no |
| Maps panel can show a navigation app's own map | yes | no |
| Declares `android.car.permission.TEMPLATE_RENDERER` | yes | no |
| Owns the `androidx.car.app.connection` authority | yes | no |
| Everything else — car link, video, touch, audio, voice, phone, media, widgets | yes | yes |
| Installs on a phone that already has Android Auto | **maybe not** | yes |

**Take `-host.apk`.** If Android answers "App not installed", take
`-compat.apk` instead — that message means something else on the phone already
holds one of those two names, and the compat build claims neither.

The cost is precise and worth stating plainly: **on `-compat` no car app can ever
accept Headway as its host, by construction**, because holding a permission
requires something on the device to define it and that build defines nothing. So
Car app panels always show why they are empty, and the Maps panel is the turn
card rather than a live map. Everything else is identical. The *Updates* card
tells you which build you are running, so a refusal months later is readable.

Same package name and same key, so you can move between them later with no
uninstall and no data loss. The in-app updater keeps you on whichever you have.

### If it says "App not installed"

Android's installer says this for half a dozen unrelated reasons and tells you
none of them. In order of likelihood, and each answerable without adb:

**1. Something else owns one of the two names the host build claims.** This is
the common one now, and the symptom is that even a *fresh* install fails —
uninstalling Headway first changes nothing. A permission name has exactly one
definer per device and a provider authority exactly one owner; if Google's
Android Auto is installed, it holds both. **Install `-compat.apk` from the same
release.** It claims neither and installs anywhere.

You can confirm it after the fact: install compat, press **Run the self-test**,
and read the "Install collisions" section. It names the package that actually
owns each one.

**2. It is older than what you have.** The build number is the `versionCode`,
and Android refuses to go backwards. Check the number in the Updates card
against the release you are installing.

**3. It is signed with a different key.** Builds up to and including **19** were
each signed with a throwaway key generated on the CI runner that built them, so
no two were signed alike. Uninstall Headway once and install build 20 or later;
those share a stable key (`signing/headway-dev.jks`) and update cleanly from
then on. A locally built APK will also collide this way unless you build with
that keystore, which is the default.

**4. There is not enough free space.** The APK bundles a ~41 MB speech model
unless it was built with `-Pheadway.model=none`.

Updating from inside the app rather than by hand is worth doing for exactly this
reason: `PackageInstaller` reports *which* of the above it was, Headway writes it
to the session log and shows it in the Updates card, and it survives long enough
to read. The system installer's own dialog does not.

That keystore is committed on purpose. It is public and protects nothing — the
point is that it is *stable*, not secret, exactly as the platform-wide Android
debug key is. A real distribution key belongs in CI secrets and is picked up
from `HEADWAY_KEYSTORE`, `HEADWAY_KEYSTORE_PASSWORD`, `HEADWAY_KEY_ALIAS` and
`HEADWAY_KEY_PASSWORD` without a code change.

## Licence

GPLv3. Headway derives its wire format from aasdk, which is GPLv3, so the whole
project is and must remain GPLv3. See [`LICENSE`](LICENSE).

## Safety

Video on a car screen while driving is your responsibility and is illegal in many
jurisdictions. Headway shows this notice on first run and offers an optional
parked-only mode for video apps. It is off by default — this is a user-freedom
project, and the choice is yours to make.

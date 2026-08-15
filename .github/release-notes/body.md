Automated build from the latest green CI run. Every protocol, voice,
Android and on-device gate passed before this was published.

## What is new in this build

**Six things a driver reported from a real car, and the reasons they
happened.**

- **Your map appears.** A maps car app was drawing a live map the whole
  time and Headway was painting over it — the pane was a `SurfaceView`
  with an opaque background, which is a combination that punches a hole
  for the map and then fills it straight back in. You saw the routing
  buttons that drew on top. It is a `TextureView` now, the map shows, and
  it takes pan, pinch and tap (a stray full-screen scroll view above it
  was eating every touch).
- **Widgets add.** Widget setup was being launched onto the car's own
  display, which Android refuses outright — so the car said "finish this
  on the phone" and nothing ever appeared there.
- **The car's readings are in your units.** One choice for the whole
  panel — automatic, metric or imperial — on the car screen under
  *Units*. Before, only the speed followed your region and the odometer,
  temperature and tyre pressures were always metric.
- **Steering-wheel skip buttons work.** They were being logged by name and
  acted on by nothing.
- **The connection stops cutting out.** Headway was ending a healthy
  session every time the car's Bluetooth blipped — which happens
  constantly on a normal drive, because Bluetooth carries only the
  handshake and shares an antenna with the Wi-Fi the session runs on. It
  now stops only when the car is genuinely gone.
- **Music reaches the car.** Several separate faults, the worst of which
  was Headway taking permanent audio focus and thereby telling the very
  player it was recording to stop.

Also: the all-apps panel lists only the apps you have allowed, instead of
every app on the phone with a button that silently does nothing; tapping a
pinned app now always says what happened; and the car-app picker names
your music apps and points at the Music panel rather than leaving you to
conclude they are unsupported.

**About music, because it is not obvious.** Headway sends your music to
the car by capturing what the phone is playing, and Android only allows
that capture through a **screen-sharing grant**. No grant means no music —
and while a session is up the car has switched away from Bluetooth, so
there is no second route. Tap the notification to allow it. The same grant
is what lets a pinned app show on the car screen at all.

**Your odometer may read a hundred times high.** The protocol field says
kilometres-times-ten and one real head unit sends metres instead. The log
now prints the raw value beside the converted one once per session; if it
disagrees with your dashboard, `odometerScale` in the quirk file is the
one-line fix and the log tells you so.

## Before you install this

**The car link itself is proven; most of what is above is not.** A
2021 Chevrolet Infotainment 3 unit has completed the whole bring-up
and displayed the phone screen — 2754 frames, 4 dropped, video focus
granted. Everything in the list above compiles, passes its unit and
on-device tests, and has had at most one drive behind it. Expect to
find things.

Two things that car settled, both fixed in the app:

- **The certificate question is answered.** It refuses the expired
  phone-role certificate and accepts `Android-Auto-Internal`, which is
  valid to 2048 and signed by the same CA. Headway rotates to it on
  its own in two attempts. **You no longer need to move the car's
  clock**, so Google's Android Auto keeps working.
- **That unit announces `access_point_type=STATIC`** — it assigns no
  addresses, so a static IP on a saved car network is simply the
  correct configuration for it, not a workaround. If your log says
  STATIC, stop chasing DHCP.

**If you run a VPN**, Headway now binds itself to the car's network so
the link works through it. If it still fails, your VPN is set to block
connections that do not go through it: turn off "Block connections
without VPN", or exclude Headway in the VPN app.

See `PROGRESS.md` for the evidence behind each phase and `BLOCKERS.md`
for what is known to be missing.

## If it fails in the car

Export the log from inside the app. On a **release** build that log says
what happened at each step, which is usually enough.

If it is not — a handshake that fails, a car that hangs up, anything on the
wire — install the matching **`-debug`** APK below and export again. That
build compiles in the whole RFCOMM and AAP conversation in both directions
with hex, which is what made every real-car bug so far findable. Every
constant is cited in `docs/protocol-notes.md`, so a captured frame can be
decoded by hand against it. Moving between the two is an ordinary update:
same package, same key.

## Updating

Headway can update itself: **Check for updates** at the bottom of the
main screen. It only ever checks when you press it — nothing runs in
the background, and the car link never touches the internet.

It keeps you on the variant you installed — a `host` install is offered
the `host` APK and a `compat` install the `compat` one, matched by
filename, because handing a host install the compat APK would silently
strip the car-app host and the other way round fails to install at all.

**Builds up to 151 were debug builds published under the plain names.** The
plain names now carry the release build, so the next update moves you onto
it. That is deliberate and it is an ordinary update — same package, same
key, no uninstall.

**Coming from build 19 or earlier, uninstall Headway first.** Those
builds were each signed with a throwaway key generated on the CI runner
that built them, so no two were signed alike and Android refused every
upgrade with "App not installed". Builds from 20 onward share one
stable key, so this is a one-time uninstall — and the reason the
in-app updater can work at all.

## Which APK to download

Four are attached. Take a **plain** one — `-host.apk` or `-compat.apk`.

- **`-host.apk` — take this one.** It declares
  `android.car.permission.TEMPLATE_RENDERER` and owns the
  `androidx.car.app.connection` authority, which is what lets third-party
  car apps draw their own interface on the car screen.
- **`-compat.apk` — take this one if `-host.apk` says "App not
  installed".** A permission name has one definer per device and a
  provider authority one owner, so if something else on your phone already
  holds either — realistically Google's Android Auto — the host APK cannot
  install at all. The compat APK claims neither and installs anywhere. You
  lose only the car-app host; the car link, video, touch, audio, voice,
  maps, phone and media are identical.

Same package name and same signing key, so you can switch between them
later with no uninstall and no data loss. The in-app updater keeps you on
whichever you installed — it matches the variant by filename, and the
debug names below are deliberately outside that match so it can never hand
you one by accident.

### The `-debug` APKs, and when you want one

`-host-debug.apk` and `-compat-debug.apk` are the same app built for
diagnosis rather than for driving.

**These are what to install if Headway will not connect to your car, and
what to send a log from.** They compile in frame-level protocol logging —
the whole RFCOMM and AAP conversation in both directions, with hex — which
is what made every real-car bug so far findable at all. The release build
compiles that out, so its log records what happened without recording every
byte it happened to.

Otherwise take a release build. It is minified and resource-shrunk, it does
not carry the protocol logging, and it is the one meant for a drive.

Both build types are signed with the same key and carry the same package
name, so moving between them is an ordinary update with no uninstall and no
data loss.

## About the signing key

Every APK here is signed with the development key committed in `signing/`,
which is public and protects nothing — it exists to be *stable*, not
secret, in exactly the way the platform-wide Android debug key is. A real
distribution key belongs in CI secrets; the build picks one up from
`HEADWAY_KEYSTORE` without a code change.


Automated build from the latest green CI run. Every protocol, voice,
Android and on-device gate passed before this was published.

## What is new in this build

**Music that cut out, and Headway that "crashed", were one thing.** A
drive log settled it: the app never died. The session to the car ended and
came back, and everything went quiet while it did — the music, the screen,
all of it. Two of the five ends in that drive were the car saying goodbye,
at the times a driver would expect. Three were not.

- **A reconnect no longer waits.** Reconnecting takes about 700 ms. The
  silences on that drive were 3.4, 5.1 and 9.3 seconds, because a session
  that broke inherited the backoff meant for a car that is not there yet —
  and each break doubled it. A session that actually ran now starts over
  from the short delay.
- **The car's keepalive gets past the video.** The head unit pings on a
  timer and hangs up when the answers stop; it says so itself in service
  discovery, and Headway had never read what it said. Everything shared one
  queue to the wire, so an answer could sit behind a keyframe. The control
  channel now has its own lane. This is a likely cause rather than a proven
  one, so every ping is now timed against the car's own budget and the
  session log says whether an answer was ever late.
- **The log names what ended a session.** It used to record a cancellation
  raised while tearing down, which named nothing.

**Talking to your assistant no longer blanks the car screen.** An assistant
overlay was being read as you closing the app you had opened, so the pane
covered itself — and because dismissing an overlay owes no notification, it
never uncovered again for the rest of the drive. It is treated as what it
is now, and the car shows a card saying who is listening, the way a call
does.

**A map panel is a map.** An app's Shortcuts / Favourites / Recents were
being drawn as full-width rows down the panel. Over a map they are now a
row of icons laid on it, and the panel is the map.

**Notification icons look like themselves.** They were being tinted a flat
block of colour, so an app with a detailed monochrome icon showed as a
coloured square. New notifications can also appear briefly on the car
screen as they arrive — `Settings → Show notifications as they arrive`, on
by default.

**The car stops showing your phone when you close the app.** Sharing your
whole screen and then closing the app you had opened used to leave the car
mirroring whatever came next.

**Screen sharing is offered on an automatic connect**, instead of only
when you connected by hand.

**If Headway does crash, the log survives it.** The stack and the whole
session buffer are written before the process goes, and a session log is
now exported automatically when a session ends — so a drive can be
diagnosed from the file without catching it in the act.

**Also in this round:** the car's own media buttons control the music, the
odometer reads correctly, the speech engine can no longer take the app down
with it, and layouts, panel sizes, corner radius and tab icons are all
yours to set.

**Apps can lay out in landscape.** A portrait phone mirrored into a wide
panel is about a quarter of it, and no amount of scaling makes a tall map a
wide one — an app's layout is decided by the display it runs on. *Settings →
Apps and panels → Turn the phone sideways for apps* rotates the phone for
the drive and puts it back afterwards. It needs one permission, granted on
the phone, and it cannot help an app that locks itself to portrait.
`BLOCKERS.md` B-025 has the five routes that would have avoided touching the
phone at all, and why each is closed.

**About music, because it is not obvious.** Headway sends your music to
the car by capturing what the phone is playing, and Android only allows
that capture through a **screen-sharing grant**. No grant means no music —
and while a session is up the car has switched away from Bluetooth, so
there is no second route. Tap the notification to allow it. The same grant
is what lets a pinned app show on the car screen at all.

**A simulated display cannot be touched.** If you use the Developer
options "Simulate secondary displays" path, the car can see the app and
cannot tap it. That is a hardcoded exclusion in Android's accessibility
service — by display type, with no permission behind it — so no version of
Headway can fix it. It says so now, on every session. `BLOCKERS.md` B-024
has the AOSP source.

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

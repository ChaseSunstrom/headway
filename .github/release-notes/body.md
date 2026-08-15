Automated build from the latest green CI run. Every protocol, voice,
Android and on-device gate passed before this was published.

## What is new in this build

**The car screen is panels now, and one of them is a real app.**

- A **rail** across the top: settings, the microphone, and the
  layouts and apps *you* pinned. Nothing else.
- **Panels** in any arrangement, any depth, edited on the car screen
  itself — settings there, then *Edit this layout* — and locked by
  default so a thumb on the dashboard cannot rearrange your car.
- An **App panel** that shows a real app running, with touch going
  back to it. Opening an app no longer replaces the whole car screen.
- **Themes**: dark, true black or light, with six accents including
  none at all.
- The link **comes up on its own** when the car's Bluetooth appears,
  and starts nothing on the phone's screen while doing it.

Apps render from the phone's own screen by default — nothing to turn
on in Developer options, nothing to toggle per drive. The simulated
display is still there if you want a car-shaped picture, under
settings → Apps and panels, along with fit-or-crop for the panel.

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

Export the log from inside the app and read the `rx`/`tx` lines. The
whole RFCOMM conversation is logged in both directions with hex, which
is what made the last real-car bug findable at all. Every constant is
cited in `docs/protocol-notes.md`, so a captured frame can be decoded
by hand against it.

## Updating

Headway can update itself: **Check for updates** at the bottom of the
main screen. It only ever checks when you press it — nothing runs in
the background, and the car link never touches the internet.

**Coming from build 19 or earlier, uninstall Headway first.** Those
builds were each signed with a throwaway key generated on the CI runner
that built them, so no two were signed alike and Android refused every
upgrade with "App not installed". Builds from 20 onward share one
stable key, so this is a one-time uninstall — and the reason the
in-app updater can work at all.

## Which APK to download

Two are attached. They are the same app; they differ in two lines of
manifest.

- **`-host.apk` — take this one.** It declares
  `android.car.permission.TEMPLATE_RENDERER` and owns the
  `androidx.car.app.connection` authority, which is what lets
  third-party car apps draw their own interface on the car screen.
- **`-compat.apk` — take this one if `-host.apk` says "App not
  installed".** A permission name has one definer per device and a
  provider authority one owner, so if something else on your phone
  already holds either — realistically Google's Android Auto — the
  host APK cannot install at all. The compat APK claims neither and
  installs anywhere. You lose only the car-app host; the car link,
  video, touch, audio, voice, maps, phone and media are identical.

Same package name and same signing key, so you can switch between them
later with no uninstall and no data loss. The in-app updater keeps you
on whichever you installed.

## About these APKs

They are **debug** builds, and deliberately so: protocol frame logging
is compiled in, which is what makes a first-connection failure
diagnosable from the in-app log export.

It is signed with the development key committed in `signing/`, which is
public and protects nothing — it exists to be *stable*, not secret, in
exactly the way the platform-wide Android debug key is. A real
distribution key belongs in CI secrets; the build picks one up from
`HEADWAY_KEYSTORE` without a code change.

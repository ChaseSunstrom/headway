# ADR 0008 — Third-party apps render on a simulated secondary display, not by mirroring the phone

**Status:** Superseded as the default, 2026-08-15. Still the opt-in.
**Refines [ADR 0004](0004-what-headway-can-put-on-the-car-screen.md) and
[ADR 0006](0006-the-car-gets-its-own-screen.md).**

> **Read this first.** This is no longer how apps reach the car by default.
> [ADR 0010](0010-the-car-screen-is-panels-and-one-of-them-is-a-real-app.md)
> made the default *single-app screen sharing*: the driver picks one app in
> Android's own consent dialog, Android excludes system UI from the capture and
> reports the app's real size, and the pane gets the app at the app's own aspect
> ratio with nothing to configure. That removed the two costs this ADR could not
> — the Developer-options setup, and a preview window that has to sit on the
> phone for the whole drive.
>
> The simulated display remains as an opt-in for a genuinely car-shaped picture:
> `HeadwaySettings.KEY_NATIVE_APP_DISPLAY` still exists, now defaults to false,
> and `migrateAppSource` moves existing installs off it. The derivation below —
> why a display created by *Settings* is trusted and can therefore be launched
> onto, when one Headway creates cannot — is unchanged and is why the option can
> exist at all.

## Context

The driver's requirement, in their words: *"Everything should be possible, even
on GrapheneOS, no screen mirroring, etc. It might just need permissions the user
can set in settings."*

Mirroring is the one thing on the car screen that is genuinely bad, and the
arithmetic from a real drive says why. The phone is 1080×2404 and the panel is
800×480. `ContentRecorder` min-scales, so `min(800/1080, 480/2404) = 0.19966`,
and the phone occupies **216 of 800 columns**. Three quarters of the car screen
is a black bar, the image is a fifth of full size, and the phone's notifications
and lock screen are on show in the car.

[ADR 0007](0007-headway-as-a-car-app-host.md) removes that for apps that publish
car templates. This ADR is about everything else — Organic Maps, HERE WeGo, a
browser, a video app — which publishes no model at all and until now could only
be mirrored.

## The wall, restated precisely

ADR 0004 concluded that Headway cannot host a third-party activity on a display
it creates. That conclusion is correct and this ADR does not weaken it. What is
worth restating is the exact shape of the rule, because the shape is where the
route is:

```java
// ActivityTaskSupervisor.isCallerAllowedToLaunchOnDisplay
if (!display.isTrusted()) {
    // ... requires the TARGET activity to declare android:allowEmbedded="true"
}
```

The `allowEmbedded` requirement is **inside** the untrusted branch. A trusted
display has no such requirement: any app may launch any activity onto it.

So the question is not "can Headway launch another app onto a display" but "can
a trusted display exist on this phone". Every route to *creating* one is closed
to a sideloaded app:

- `ADD_TRUSTED_DISPLAY` is `signature|role`, and every role carrying it
  (`COMPANION_DEVICE_APP_STREAMING`, `COMPANION_DEVICE_NEARBY_DEVICE_STREAMING`,
  `SYSTEM_AUTOMOTIVE_PROJECTION`, `SYSTEM_SHELL`) is `systemOnly="true"` —
  `RequestRoleActivity` refuses before drawing anything.
- `CREATE_VIRTUAL_DEVICE` is the same story through a different door.

## The decision

**Do not create a trusted display. Use the one the driver can create in
Settings.**

Developer options → **Simulate secondary displays** builds an overlay
`LogicalDisplay`, and `OverlayDisplayAdapter` marks it trusted in as many words:

```java
// The display is trusted since it is created by system.
mInfo.flags |= FLAG_TRUSTED;
```

It is trusted, it is not private, and it is not secure — provided the driver
picks an entry whose label does *not* say `(secure)`. Any app may therefore be
launched onto it, and Android 17's `MediaProjection` chooser lists it as its own
row so it can be recorded without display 0 ever being captured.

That is not mirroring by any definition worth arguing about. Organic Maps
launched onto a 720×480 display **lays itself out for 720×480**: its own fonts at
that density, its own layout for that aspect, its own composition. Headway
records that display's frames. The car sees the app's real rendering at 1:1
pixels.

### The geometry

The negotiated panel is 800×480 and the best available simulated display is
720×480 at 142 dpi. Capturing 720×480 into an 800×480 sink makes
`ContentRecorder`'s `min(800/720, 480/480)` evaluate to exactly **1.0** — a
centred 1:1 blit with a 40-pixel bar each side, and no resampling at all. So the
existing capture path needs no change: it already creates the sink at the
negotiated resolution.

**720 of 800 columns, against 216 today.**

### What was built

- `OverlayDisplay` — finds the display structurally (not display 0, not private,
  not secure, presentation-capable, not one of Headway's own), and reports every
  display with its flags for the diagnostics dialog.
- `CarAppDisplay` — one shared answer for the session, resolved before anything
  reads it.
- `startOnPhoneDisplay` and `CarLauncherActivity.launchApp` name that display in
  `ActivityOptions.setLaunchDisplayId`, plus `FLAG_ACTIVITY_MULTIPLE_TASK` so an
  app already open on the phone gets a task here instead of being brought forward
  on display 0 — which from the car looks exactly like the tap being ignored.
- `GestureConfig.displayId` → `GestureDescription.Builder.setDisplayId`, and
  `CarInputStream` builds its `TouchTransform` against the simulated display's
  size. Both halves come from the same resolved value, so they cannot disagree.
- A setup card with the exact Developer-options clicks, and a display report so
  a driver who picked a `(secure)` entry can see that they did.

## Why it is a switch and not automatic

`MediaProjection` will not say which display it is recording. The driver chooses
that in the system consent dialog and there is no public getter afterwards, so
Headway cannot detect the choice — it can only be told.

A driver who turns the switch on and then picks "Entire screen" gets the old
mirrored view with touches aimed at a display they are not looking at. That is a
real failure mode. The only defence available is the sentence beside the switch
saying which row to pick, and the log line naming the display that was resolved.

Off by default, because it does nothing until a Developer options toggle has been
set, and a switch that silently does nothing is worse than one that has to be
found.

## The two costs, neither of which can be removed

1. **The geometry comes from a fixed menu.** `Settings.Global`'s
   `OVERLAY_DISPLAY_DEVICES` needs `WRITE_SECURE_SETTINGS`
   (`signature|privileged|development|role|installer`; the only role holding it
   is `DEVICE_POLICY_MANAGEMENT`, static and `visible="false"`). Headway cannot
   widen SettingsLib's twelve entries and cannot ask for 800×480. Hence the
   40-pixel bars.
2. **The phone's screen must stay on.** `OverlayDisplayWindow` forwards display
   0's power state to the overlay device, so a dark phone is a dark simulated
   display and every app on it stops drawing. Headway holds the screen on; the
   driver turns the brightness down. This is a straight regression against the
   dashboard path, which does work with the screen off — which is exactly why
   the dashboard stays the default and this is opt-in per app launch.

A third thing the driver will see and should be warned about: the simulated
display is *also* drawn on the phone as a half-size, 80%-opaque draggable
window. That is the platform's preview and cannot be hidden. The captured stream
is clean full-resolution content regardless.

## Consequences

- **"No screen mirroring" is now true for every app, at a cost.** Apps with a
  car template need nothing (ADR 0007). Everything else renders natively here,
  with the screen-on cost. Display-0 mirroring remains only as the fallback for a
  phone with no simulated display configured.
- **Three tiers, in the order Headway tries them.** Structured models first
  (media browse, now playing, messages, phone, car-app templates) — true 800×480,
  no Developer options, screen-off safe. Then this. Then mirroring.
- **Touch is exact.** A 720×480 target inside an 800×480 panel is a scale of 1.0
  and an offset of 40, which `TouchTransform` already computes; no new maths and
  no new failure mode.
- **Unverified on hardware.** Two things need one device session:
  `com.android.media.projection.flags.media_projection_connected_display` being
  enabled in the shipping GrapheneOS 17 build — it gates the per-display chooser
  rows — and whether the row appears at all. B-015 records it, and the "Show
  every display this phone has" button is the probe.
- **Nothing regresses if it does not work.** With no simulated display, or with
  the switch off, every path is byte-for-byte what it was.

## Evidence

- `services/core/java/com/android/server/wm/ActivityTaskSupervisor.java`
  L1325-1353 — the `allowEmbedded` requirement inside `if (!display.isTrusted())`,
  and the trusted, non-private path that skips it.
- `services/core/java/com/android/server/display/OverlayDisplayAdapter.java`
  L514-515 — `FLAG_TRUSTED` with the comment quoted above; L491-493 — the
  `secure` token becoming `FLAG_SECURE`; L700-706 and L460-462, L513 — the power
  state forwarded from display 0.
- `services/core/java/com/android/server/display/OverlayDisplayWindow.java`
  L310-315 — the forwarding; L55, L58 — `INITIAL_SCALE = 0.5f`,
  `WINDOW_ALPHA = 0.8f`, the preview window.
- `core/res/AndroidManifest.xml` L9268-9269 — `ADD_TRUSTED_DISPLAY` as
  `signature|role`; L5768-5769 — `WRITE_SECURE_SETTINGS`.
- `packages/modules/Permission/.../roles-main.xml` — `systemOnly="true"` on every
  role carrying `ADD_TRUSTED_DISPLAY`.
- `packages/SettingsLib/res/values/arrays.xml` L411-424 — the twelve simulated
  display entries; L413 is `720x480/142,unique_id=720x480`.
- `services/core/java/com/android/server/wm/ContentRecorder.java` L598-600 —
  `float scale = Math.min(scaleX, scaleY)` and the centring, which is what makes
  720×480 into 800×480 a 1:1 pillarbox.
- `SystemUI` `MediaProjectionPermissionUtils.kt` L29-35 and L83-85 — the
  `Display.TYPE_OVERLAY` allowance and the flag that gates it;
  `ShareToAppPermissionDialogDelegate.kt` L114-129 — one chooser row per display.
- `MediaProjection.java` L95-101, L180, L259 — the chosen display id captured at
  construction and applied through `setDisplayIdToMirror` internally, so Headway
  never touches the `@hide` setter.
- `android-35/android.jar` — `ActivityOptions.setLaunchDisplayId`,
  `GestureDescription.Builder.setDisplayId`, `Display.FLAG_PRIVATE`,
  `FLAG_SECURE`, `FLAG_PRESENTATION`: all public.

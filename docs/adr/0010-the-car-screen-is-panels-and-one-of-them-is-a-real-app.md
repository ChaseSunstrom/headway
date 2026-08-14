<!--
This file is part of Headway.
Copyright (C) 2026 The Headway Authors
SPDX-License-Identifier: GPL-3.0-or-later
-->

# ADR 0010 — The car screen is panels, and one of them can be a real app

**Status:** accepted, 2026-08-14
**Supersedes the pixel-routing half of:** [ADR 0006](0006-the-car-gets-its-own-screen.md),
[ADR 0008](0008-native-app-rendering-on-a-simulated-display.md)
**Builds on:** [ADR 0004](0004-what-headway-can-put-on-the-car-screen.md),
[ADR 0007](0007-headway-as-a-car-app-host.md)

## The complaint

> I don't like how when I open an app it opens on the second casted display from
> my phone, instead of the virtual display in Headway. Fix this, so the panel
> stuff actually **works**.

That is an accurate description of what build 90 does, and the mechanism is
exact. `LauncherTile.launch` starts the activity on `CarAppDisplay.displayId`
(`HostedTiles.kt:1178`) — the *simulated secondary display*, never Headway's own
— and then calls `CarVideoStream.showOnCar(MIRROR)`, which calls
`CarSurface.stop`, which releases the car display and destroys the dashboard
outright (`CarVideoStream.kt:415`, `CarSurface.kt:199`). The panel tree does not
survive the tap. There has never been a code path in which dashboard pixels and
app pixels appear in the same encoded frame.

So "panels don't work" is not a bug in the panels. Opening an app *replaced*
them, by design, and the design was wrong.

## What cannot change

ADR 0004 stands and is not being relitigated. An unprivileged app cannot put a
third-party **window** on a display it created:
`ActivityTaskSupervisor.isCallerAllowedToLaunchOnDisplay` gates on
`display.isTrusted()`, and `VIRTUAL_DISPLAY_FLAG_TRUSTED` needs
`ADD_TRUSTED_DISPLAY`, which is `signature|role`. No amount of layout work
changes that.

## The decision

**Panels stay. Apps arrive as pixels into a panel, not as windows, and the
dashboard is never torn down.**

Concretely: the session's one `MediaProjection` captures the display the app is
running on, and its virtual display renders into **a `SurfaceView` that lives
inside a dashboard pane**. The car display, the `Presentation` and the pane tree
exist continuously for the whole drive. There is no mode switch left to make.

```
  simulated display #2            MediaProjection            dashboard pane
  (app draws itself here)   ->    virtual display      ->    SurfaceView
        720x480                   output = pane Surface      inside the tree
                                                                  |
                                        car display (Headway-owned, 800x480)
                                                                  |
                                             ScreenEncoder -> H.264 -> the car
```

### Why this is legal, in API terms that were checked rather than remembered

| Claim | Evidence |
|---|---|
| A projection can render into any `Surface` | `MediaProjection.createVirtualDisplay(..., Surface surface, ...)`: *"The surface to which the content of the virtual display should be rendered"* — API 21, no permission beyond the grant |
| A pane can supply that `Surface` | A `TextureView`'s `SurfaceTexture`, wrapped in a `Surface`. See the amendment below — this began as a `SurfaceView` and a real drive showed why it could not stay one |
| The picture can move between panes without asking the driver anything | `VirtualDisplay.setSurface(Surface)` — *"Sets the surface that backs the virtual display"* — **API 20**, public |
| A pane that changes size does not need a new projection | `VirtualDisplay.resize(int,int,int)` — **API 21**, public |
| ...but there may only ever be **one** such display | `createVirtualDisplay` throws `SecurityException` *"If the target SDK is U and up, and if this instance has already taken a recording through #createVirtualDisplay, but stop() wasn't invoked"* |

The last row is the constraint that shapes the feature, and the third row is what
makes the constraint survivable.

### What that means for the driver

- A layout may contain **any number of app panes**. Exactly **one of them is live
  at a time** — the platform allows one recording per grant — and switching which
  one is a `setSurface` call: no dialog, no consent, no dropped session.
- The live pane is the one the driver last touched or launched into. Every other
  app pane shows the app's icon and name, and says it will show here when
  chosen. It is a dormant pane, not a broken one, and it says so.
- Full-screen is not a mode any more. It is a *layout* with one pane in it, which
  is a thing the driver can make, save, pin, and leave in one tap.

`CarSurfaceMode` is deleted. Its two values encoded exactly the exclusivity this
ADR removes.

### The rail

The top of the car screen is a settings button, a microphone button, and the
driver's pinned items — layouts and apps, in one ordered list, and nothing else.

The five hardcoded tabs are gone. They were five arrangements Headway chose, and
a driver who only uses two of them was still paying for five touch targets across
an 800-pixel panel. A pinned layout *is* a tab; a pinned app opens in the live
app pane. Both are the driver's choice, so both live in `Rail` (`core-dash`) as
an ordered list, because a button that moves between drives is a button that has
to be read rather than reached for.

### Editing, on the car, behind a lock

Layout editing existed only in `DashboardActivity` — an Activity, therefore on
display 0, therefore unreachable on the car screen, which is the one place a
driver is looking when they want a pane moved. It moves into the shell, and
arrives with the thing that makes it safe to have there: **every layout is locked
by default**. Unlock is deliberate and per layout, editing shows its own
affordances, and saving re-locks.

Layouts saved before this ADR decode as *unlocked*, because they were editable
when they were written and freezing them on upgrade would read as a fault.

### Theming

One accent, chosen by the driver, over one of three bases — dark, true black, or
light — with `NONE` available as an accent, which is the "without the blue
Headway theme" the request asked for. Composed rather than enumerated: three
bases and six accents are eighteen palettes, and eighteen hand-written palettes
drift until one of them is unreadable in sunlight. `CarThemeTest` measures WCAG
contrast on all eighteen; it caught two real defects the first time it ran.

## What this costs, stated plainly

1. **One live app pane, not several.** One `MediaProjection`, one virtual display.
   A second grant is not obtainable —
   `MediaProjectionManager.getMediaProjection` may be redeemed once per consent.
2. **The projection still needs a tap, once.** Android 14 requires consent per
   session. Headway now holds the grant for as long as it is armed instead of
   re-asking on every car connect, so the tap is once per phone unlock rather
   than once per drive. There is no unprivileged way to remove it entirely.
3. **The app's home display is still the platform's choice.** With Developer
   options → *Simulate secondary displays* the app lays itself out for 720x480
   and lands in the pane at native proportions. Without it the projection
   captures display 0 and the pane shows a portrait phone letterboxed into it,
   which is honest, ugly, and exactly what ADR 0008 already said. `PaneFit` does
   that arithmetic and is tested for both shapes.
4. **The phone's screen must stay on** whenever the simulated display is in use —
   `OverlayDisplayWindow` forwards display 0's power state. Unchanged from ADR
   0008, and now the *only* remaining reason the phone screen is involved at all.

## Amendment, 2026-08-14 — after the first drive

Three things the derivation above got right in principle and wrong in practice.

**The pane is a `TextureView`, not a `SurfaceView`.** A `SurfaceView` is not
drawn by the window: it is a separate `SurfaceControl` layer that SurfaceFlinger
composites onto the display, with a hole punched through the window where it
sits. That is one more thing that has to hold on a *virtual* display whose output
is a codec input surface, and when it does not the pane is a black rectangle with
no error anywhere — which is exactly what the drive reported. A `TextureView`'s
frames are drawn by the window in the same pass as every other pixel of the
dashboard, so if the dashboard reaches the car then so does the app. It costs a
GPU copy per frame, which at 800x480 is nothing, and it removes the z-order
problem that made the old code hide the pane whenever anything had to be drawn
over it.

**The grant has to be reachable from the car.** Screen capture cannot be granted
without an activity, and a link that comes up on its own has none — so an
automatically connected session had app panes that said "screen sharing is off"
and no way to turn it on. `ProjectionRequestActivity` is a transparent activity
whose whole job is to ask and hand the result to the running service; tapping a
dormant pane opens it.

**The consent must not be allowed to capture the wrong thing.** The ordinary
`createScreenCaptureIntent()` also offers "a single app", and a driver who picks
it gets a capture of one app that never changes while Headway goes on launching
things and mapping touches as though the whole screen were recorded — frames
arrive, they are simply of the wrong thing.
`MediaProjectionConfig.createConfigForDefaultDisplay()` (API 34) removes the
choice. It is not applied on the simulated-display path, where the driver *must*
be able to pick that display.

**And the simulated display is no longer required.** Apps run on the phone's own
screen by default: nothing to enable in Developer options, nothing to turn on and
off per drive. The cost is a portrait picture in a landscape panel, which is why
`PaneFit.cover` exists — crop to fill instead of fitting inside, chosen per taste
from the car screen. The simulated display stays as the option that gives a
car-shaped picture for anyone willing to pay its two costs.

## Consequences

- `CarSurfaceMode`, the mirror-mode encoder, `CarVideoStream.show` and the
  full-screen hand-off in `LauncherTile`, `MapsTile` and `CarLauncherActivity`
  all go. One encoder, permanently on the car display, for the whole session.
- `MirrorTile.bounds()` — written to tell "whatever composites the mirror
  capture" where the hole is, and never called by anything — becomes the touch
  rectangle of the app pane. It was the right idea two builds early.
- Touch forks at the pane rather than at the session: a touch inside the live
  app pane's picture is transformed by `PaneFit` and dispatched to the app
  display through the accessibility service; every other touch is dispatched into
  Headway's own view tree unchanged, as now.
- The floating overlay window — the mic-and-Headway mark drawn over the app
  display — is removed. It existed because mirror mode was a one-way trip with no
  way back; the dashboard is never left now, and the mic is on the rail.

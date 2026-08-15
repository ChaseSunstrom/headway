# ADR 0006 — The car gets its own screen, and mirroring becomes a mode

Status: **superseded in part**, 2026-08-15.
Supersedes the default chosen in ADR 0004; does not change its findings.

> **Read this first.** The half of this ADR that decides *how an app's pixels
> reach the car* — mirroring as a switchable mode, `CarSurfaceMode`,
> `CarVideoStream.show`, the floating Home button, and the mirror pane resolving
> to the app grid — was replaced by
> [ADR 0010](0010-the-car-screen-is-panels-and-one-of-them-is-a-real-app.md).
> None of it exists in the code: `CarSurfaceMode` and `CarVideoStream.show` are
> deleted, the floating overlay is gone along with `SYSTEM_ALERT_WINDOW`, and
> `PaneKind.canonical` maps the legacy `"mirror"` string to `APP`. Whole-screen
> mirroring survives only as an automatic fallback when the car display cannot
> be built, with no way to select it.
>
> The other half stands and is still the reason the code looks the way it does:
> the car gets a display composed for *its* geometry rather than a scaled
> picture of the phone, that display is driven by a `Presentation` rather than
> an activity, and the encoder is created once for the session.

## Context

Build 83 was the first to put Headway's dashboard in front of a real 2021
Chevrolet Infotainment 3 head unit. It worked, in the sense that frames arrived
and were displayed. The driver's report was:

> The panel thing works, but it sucks, I dont want it to display my phone
> screen, I want it to behave like android auto, because it is finnicky

That is not a taste complaint, and the arithmetic says why. The phone is
1080x2404. The panel is 800x480. Fitting a portrait image inside a landscape
frame without distorting it gives a scale of **0.1997** and a width of **216
pixels**, so:

- **216 of the car's 800 columns carry the image.** The other 584 — 73% of the
  dashboard — are a black bar.
- **Every touch is scaled by a fifth and letterbox-corrected**, so a 48 dp
  target drawn on the phone arrives on the panel about nine pixels wide.
- **The phone's notifications, its lock screen and its keyboard** are all on the
  car's display, because the car's display *is* the phone's display.

Android Auto does not do this, and mirroring is not a thing it has ever done.
The head unit is treated as a display in its own right: content is composed for
its geometry and sent to it. Everything about how a car UI looks — the wide
panels, the two- and three-pane home screens, the enormous touch targets —
follows from that one decision.

## Decision

**Headway composes for the car's geometry.** A `DisplayManager` virtual display
is created at exactly the resolution and density the head unit advertised in the
`Config` reply, a `Presentation` is shown on it, and the encoder takes that
display. The phone's own screen is not involved.

**Mirroring is retained as a mode, not a pane.** `CarSurfaceMode` has two
values, and `CarVideoStream.show` moves between them without renegotiating
anything with the head unit: same resolution, same frame rate, same session id,
only the source of the pixels changes.

**The driver moves between them by using the product.** Tapping an app in the
grid launches it and hands the car screen to it; a floating Home button — the
mark itself — brings the dashboard back.

## Consequences

### What this buys

- **Touch becomes identity.** The car sends a point in its own coordinate
  space, the display is in that same space, and the target is Headway's own
  window, so `CarSurface.deliver` synthesises a `MotionEvent` and calls
  `dispatchTouchEvent`. No transform, no gesture builder, no `dispatchGesture`,
  and **no accessibility grant needed for the dashboard to work at all**. That
  whole chain existed only because the target used to be somebody else's window;
  it is still there, and still required, for mirror mode.
- **The phone screen is free.** The driver can lock the phone or use it without
  either affecting the car.
- **No projection consent to draw.** `MediaProjection` is still wanted for
  capturing *audio* (ADR 0005) and for mirror mode, but the dashboard needs
  none.
- **Screen-off is reachable.** An own-content display's state follows whether a
  surface is attached, not whether the phone is awake (ADR 0004, Finding 4).

### What it costs

Third-party apps cannot appear on the dashboard — not their windows. ADR 0004
established that this is true of *any* display Headway owns, on three
independent grounds, and none of them has moved. Mirroring used to be the escape
hatch; it still is, and it is now reached deliberately rather than being the
permanent state.

### The mirror pane is gone as a concept

`DashLayoutStore.DEFAULT` puts a `DashTile.Kind.MIRROR` leaf in its largest
pane, which on a drawn display cannot be honoured. The first implementation drew
a note explaining that, which meant the default layout came up as a paragraph of
apology occupying 62% of the car screen — a worse outcome than the thing it was
apologising for.

`DashboardPresentation.tileFor` now resolves `MIRROR` to the app grid instead.
That is the door to the thing the leaf was asking for, saved layouts written
before the mode existed keep working, and no pane on the car explains itself
instead of being useful.

## Implementation notes worth keeping

**Why a `Presentation` and not an activity.** ADR 0004 Finding 4, as corrected:
`ActivityTaskSupervisor.isCallerAllowedToLaunchOnDisplay` refuses the *first*
activity onto an untrusted virtual display, because the `ACTIVITY_EMBEDDING`
gate is waived only when `uidPresentOnDisplay` is true, which on a display
created a moment ago is never. The `getOwnerUid() == callingUid` branch sits
*after* that gate rather than instead of it. A `Presentation` is a `Dialog` — a
window added through `WindowManager` on a display this process owns — and never
enters the activity-launch path at all.

**Why the mirror encoder is created once per session.** Since Android 14,
`MediaProjection.createVirtualDisplay` throws `SecurityException` on a
projection that has already produced a display. A fresh `ScreenEncoder` on each
switch back to mirroring would have its own `virtualDisplay` field null, take
`startCapture`'s create branch, and fail on the *second* switch. Reusing the
instance takes the re-point branch instead, which is what
`ScreenEncoder.heldProjection` exists for. `CarVideoStream.stop` calls
`release()` rather than `stop()`, because at that point the projection really is
going away.

**Why `VideoPump.resetForNewStream` is called on every switch.** The pump sends
the codec configuration once per stream. A new encoder produces new SPS/PPS, and
without the reset the pump would consider the config already sent and hand the
head unit's decoder access units it has no parameter sets for. A mid-stream
parameter set plus an IDR is ordinary H.264 and every decoder handles it; a
missing one is a green screen.

## Alternatives rejected

**Keep mirroring and letterbox better.** There is no better. The ratio between a
modern phone's aspect and a car panel's is the whole problem, and no amount of
cropping produces a landscape image from a portrait screen without throwing away
most of it.

**Force the phone into landscape while casting.** Rotating display 0 changes
what the driver's own phone is doing, still shows their notifications and lock
screen on the dashboard, and still leaves every phone-sized UI element a fifth
of its intended size on the panel.

**Host `androidx.car.app` templates.** Examined and rejected in ADR 0004:
`HostValidator` accepts six hardcoded SHA-256/package pairs, three Gearhead and
three Automotive, and the `TEMPLATE_RENDERER` escape is privileged. There is no
host library, and reimplementing the AIDL would work only against debuggable
apps or ones that voluntarily add Headway's certificate.

> **Reversed 2026-08-14 — see [ADR 0007](0007-headway-as-a-car-app-host.md).**
> Two of the three reasons above are wrong. `android.car.permission.TEMPLATE_RENDERER`
> is *not* privileged in any sense that matters here: it is an ordinary custom
> permission name, undefined on a phone, which Headway declares at `signature`
> level and is therefore granted at install — and `HostValidator.hasPermissionGranted`
> asks for nothing more than that. And the AIDL does not need reimplementing,
> because `androidx.car.app:app` exports every host-side stub and the whole
> `Bundler` as public API, so Headway depends on the library rather than forking
> it. The host is built and shipped in `dev.headway.app.carapp`. What was right
> is the allowlist observation: out of the box a third-party host is accepted by
> zero apps, which is exactly why the permission route is the whole of the
> reach.

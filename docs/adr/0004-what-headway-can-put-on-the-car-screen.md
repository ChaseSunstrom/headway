# ADR 0004 — What Headway can put on the car screen

**Status:** Accepted. Narrows the "cast *any* app on the phone" framing in
`CLAUDE.md` to what an unprivileged app can actually do, and changes the
default video source from the phone's own display to a virtual one.

## Context

Two questions were asked of the architecture, and they turn out to have the
same answer:

1. *Why must Headway mirror the phone screen? Why not choose which apps appear
   and lay them out in panes, several at once, the way Android Auto does?*
2. *How can the session keep running when the phone is locked?*

Both were settled against AOSP source (`android15-release`, with
`android11-release` and `android13-release` used to date a removal), not from
memory.

## Finding 1 — a third-party app cannot be placed on a display Headway owns

`ActivityTaskSupervisor.isCallerAllowedToLaunchOnDisplay` (L1201-1279) refuses
the launch unless **the target app** declares `android:allowEmbedded="true"` —
an opt-in belonging to the other developer, essentially never set — **and** the
caller holds `ACTIVITY_EMBEDDING`, which is `signature|privileged`.

`VIRTUAL_DISPLAY_FLAG_TRUSTED` bypasses the check, but it is `@SystemApi`
(non-SDK, already out of bounds under CLAUDE.md constraint 2) and additionally
requires `ADD_TRUSTED_DISPLAY` (`signature|role`). Attempting it writes an AOSP
security-incident EventLog entry.

This is deliberate and will not be relaxed. The commit that introduced the
check (`ed115cd`) states the threat directly: *"If an app creates a Surface and
a virtual display backed by that Surface, it can then launch activities and
hijack their content."* That is exactly the mechanism a third-party panel would
use.

Android Auto is exempt because Gearhead ships in `/system/priv-app` with
`ACTIVITY_EMBEDDING` in a `privapp-permissions` allowlist. The fact that running
Android Auto on a de-Googled device requires editing the system image is the
empirical proof that the capability is not reachable from a sideloaded APK.

## Finding 2 — split-screen is not a way around it

`GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN` (=7) is **dead**. Its handler was removed
from `SystemActionPerformer` between `android11-release` and
`android13-release`; on Android 13 and later `performGlobalAction(7)` returns
`false` with no diagnostic. Anything relying on it silently does nothing.

`FLAG_ACTIVITY_LAUNCH_ADJACENT` (0x1000) still exists and is not deprecated,
but it only *places an activity into a split that already exists*. It requires
`FLAG_ACTIVITY_NEW_TASK` and a source **Activity** — the launch record needs an
`mSourceRecord`, which a `Service` does not have. A foreground service cannot
create a split, and nothing unprivileged can create one on demand.

## Finding 3 — mirroring the default display *cannot* survive screen-off

This is the finding that changes the architecture, and it is the answer to
question 2.

When the screen turns off, `DisplayPolicy.screenTurnedOff` acquires a sleep
token for display 0. That makes `DisplayContent.shouldSleep()` true for that
display, activities on it are paused, and the mirrored content goes black. No
foreground service type, wake lock, or projection flag changes this: it is a
property of the display being asleep, not of the capture.

So "mirror the phone screen with the screen off" is not a thing that can be
made to work. Keep-screen-on is not a stopgap for the mirroring path — it is a
requirement of it.

## Finding 4 — an own-content virtual display *does* survive screen-off

`VirtualDisplayAdapter` derives the display's state from whether its `Surface`
is non-null, not from the physical screen. Sleep tokens are per-display, so the
token taken for display 0 does not touch a virtual one. And
`hasAwakeDisplay()` returning true for the virtual display keeps the global
`mSleeping` false, so the activity on it stays resumed.

Concretely: an activity of **Headway's own** on a `VirtualDisplay` created with
`VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY` keeps rendering with the phone locked
and the screen dark. Headway may launch its own activities there without any
privileged permission, via the `display.getOwnerUid() == callingUid` branch of
the very check that blocks third-party apps.

That inverts the video architecture. The own-content display is not a layout
nicety to add later; it is the only source that meets the screen-locked
requirement, and it needs no `MediaProjection` at all — which also sidesteps the
Android 14 one-display-per-projection limit noted at `ScreenEncoder.kt:145`.

## Decision

Headway supports two video sources, and the *dashboard* is the default:

**1. Headway's own dashboard, on an own-content `VirtualDisplay`.** Headway's
car activities are marked `android:allowEmbedded="true"` and launched with
`setLaunchDisplayId`; several panes come from `setLaunchBounds` or
ActivityEmbedding **within Headway's own UID**, where no permission applies.
Works with the phone locked and the screen off.

The panes render other apps' *content* without hosting their activities — the
same trick Android Auto itself uses, and the reason it barely projects
third-party UI at all:

- **Media** — `MediaSessionManager` (`MediaBrowserService`/`MediaSession`),
  a user-granted notification-listener toggle, not a privileged permission.
- **Messages and alerts** — `NotificationListenerService` + `RemoteInput`.
- **Widgets** — `AppWidgetHost` with `bindAppWidgetIdIfAllowed` and the
  `ACTION_APPWIDGET_BIND` consent dialog. Public API, user-granted.
  RemoteViews-only, and only for apps that ship a widget.

Android Auto's own model is that apps implement `CarAppService` and return
template models, with Gearhead rendering every pixel. That model is entirely
available unprivileged. It is how you get several apps on screen at once with
your own layout — by drawing them, not by hosting them.

**2. Full-screen mirroring of the phone display, as the escape hatch** for
anything not modelled. Ships first, because it is what unblocks a real car
today and is what CLAUDE.md specifies. Carries the screen-off limitation from
Finding 3, and must say so in the UI rather than appearing to fail.

Landscape can be forced for the mirroring path via `ACCELEROMETER_ROTATION` /
`USER_ROTATION`: `WRITE_SETTINGS` is `signature|preinstalled|appop|pre23|role`,
and the `appop` term makes it user-grantable through
`ACTION_MANAGE_WRITE_SETTINGS`.

## Rejected

- **Cross-app picture-in-picture as a pane source.** `enterPictureInPictureMode`
  and friends are instance methods on your own `Activity`. There is no API to
  put another app into PiP.
- **`VIRTUAL_DISPLAY_FLAG_TRUSTED`.** Non-SDK and privileged; see Finding 1.
- **Asking users to grant permissions over ADB.** Explicitly out of bounds
  under CLAUDE.md constraint 2.

## Consequences

- `CLAUDE.md`'s "casts *any* app on the phone to the car screen via screen
  mirroring" is accurate only for the escape-hatch path, and only with the
  screen on. Both qualifications belong in the README.
- The dashboard is not a later nicety. Screen-off operation depends on it, so
  it is on the critical path for the Definition of Done item *"App survives:
  screen lock, 30 min continuous session…"*.
- `ScreenEncoder` must be able to take a `DisplayManager`-created virtual
  display as its source, not only a `MediaProjection`.
- One thing here is inferred from source rather than observed, and should be
  confirmed on device before the dashboard is built on it: that a Headway
  activity on an `OWN_CONTENT_ONLY` virtual display stays resumed with the
  screen off and the phone locked. Tracked in `BLOCKERS.md`.

## Sources

- `services/core/java/com/android/server/wm/ActivityTaskSupervisor.java`
  L1201-1279 (`isCallerAllowedToLaunchOnDisplay`), `android15-release`
- commit `ed115cd`, which introduced that check
- `services/accessibility/java/com/android/server/accessibility/SystemActionPerformer.java`,
  `android11-release` vs `android13-release`
- `services/core/java/com/android/server/wm/DisplayPolicy.java`
  (`screenTurnedOff`), `DisplayContent.shouldSleep`, `android15-release`
- `services/core/java/com/android/server/display/VirtualDisplayAdapter.java`,
  `android15-release`
- `core/res/AndroidManifest.xml` — protection levels for `WRITE_SETTINGS`,
  `ACTIVITY_EMBEDDING`, `ADD_TRUSTED_DISPLAY`

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

> **Revised 2026-08-14** after a second pass against `android17-release` and a
> verifier's pass over the code that followed from it. The verdict is unchanged;
> **five** of the supporting arguments were wrong, and one of them changes what
> gets built. See the *Corrections* section at the end; the text below has been
> fixed in place.

## Finding 1 — a third-party app cannot be placed on a display Headway owns

`ActivityTaskSupervisor.isCallerAllowedToLaunchOnDisplay` (L1284-1371 in
`android17-release`) refuses the launch unless **the target app** declares
`android:allowEmbedded="true"` — an opt-in belonging to the other developer,
essentially never set. `ActivityInfo.java` L726-737 marks the corresponding
`FLAG_ALLOW_EMBEDDED` `@Deprecated` and `@UnsupportedAppUsage(maxTargetSdk = R)`,
with the comment *"TODO(b/191165536): delete this flag since is no longer
used"* — which is the clearest possible statement that no app written this
decade sets it.

`ACTIVITY_EMBEDDING` (`signature|privileged`) is a second gate rather than an
equal one, contrary to this ADR's first version: L1338-1340 waives it when
`uidPresentOnDisplay` is true — that is, once the caller already has an activity
on that display. On an empty display it is not waived, and
`DisplayContent.isUidPresent` matches `ActivityRecord`s only, so a display that
has just been created never satisfies it.

Either way the conclusion for *third-party* apps is unchanged and rests on the
first gate: the target's own `allowEmbedded`, which is outside Headway's control
entirely. The refinement matters for Headway's own content, and Finding 4 is
where it bites.

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
but it only *places an activity into a split that already exists*
(`ActivityStarter` L3104-3120). It requires `FLAG_ACTIVITY_NEW_TASK` and a
source **Activity** — the launch record needs an `mSourceRecord`, which a
`Service` does not have — plus, new in Android 17, a source task that has not
set `isLaunchAdjacentDisabled()`.

This ADR originally read that as ruling the flag out. It does not:
`CarLauncherActivity` *is* an Activity and does have an `mSourceRecord`. So
**Headway can fill a split it did not create** — the user makes one in Recents
once, and Headway then puts apps of their choosing into both panes. Two
arbitrary apps, live, full framerate, touch working. What remains true is that
nothing unprivileged can *create* the split.

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

Concretely: Headway's own content on a `VirtualDisplay` created with
`VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY` keeps rendering with the phone locked
and the screen dark.

**It has to be a `Presentation`, not an activity** — corrected 2026-08-14, and
this is the third time the same check has been misread in this document. The
owner-uid branch is not an alternative to the untrusted gate; it comes *after*
it:

```java
if (!display.isTrusted()) {
    if ((aInfo.flags & FLAG_ALLOW_EMBEDDED) == 0) return false;            // 1
    if (checkPermission(ACTIVITY_EMBEDDING, ...) == PERMISSION_DENIED
            && !uidPresentOnDisplay) return false;                         // 2
}
if (display.getOwnerUid() == callingUid) return true;                      // 3
```

An own-content display is untrusted, so 1 and 2 apply. Headway's own activity
clears 1 by declaring `allowEmbedded` on itself, then fails 2:
`ACTIVITY_EMBEDDING` is `signature|privileged`, and `uidPresentOnDisplay` is
false because `DisplayContent.isUidPresent` matches `ActivityRecord`s and a
freshly created display has none. Check 3 is never reached. The branch only ever
helps a *second* activity once one is already resident — and nothing can put the
first one there.

A `Presentation` can, because it is a `Dialog`: a window added through
`WindowManager` on a display the process owns, which never enters the
activity-launch path. That is the documented purpose of the API, and it is the
only route onto this display.

That inverts the video architecture. The own-content display is not a layout
nicety to add later; it is the only source that meets the screen-locked
requirement, and it needs no `MediaProjection` at all — which also sidesteps the
Android 14 one-display-per-projection limit noted at `ScreenEncoder.kt:145`.

## Finding 5 — on Android 17 a projection-backed display cannot host *anything*

Android 17 adds a check ahead of every other one, at
`ActivityTaskSupervisor.isCallerAllowedToLaunchOnDisplay` L1308-1311:

```java
if (DesktopExperienceFlags.ENABLE_DISPLAY_CONTENT_MODE_MANAGEMENT.isTrue()) {
    if (!displayContent.mDisplay.canHostTasks()) {
        Slog.w(TAG, "Launch on display check: activity launch is not allowed on a "
                + "display that cannot host tasks");
        return false;
    }
}
```

The flag defaults true (`DesktopExperienceFlags.java` L88-90).
`LogicalDisplay.validateCanHostTasksLocked` L1092-1094 returns false when
`shouldOnlyMirror()`, and `VirtualDisplayAdapter` L548-551 defines that as
*"created through a `MediaProjection`"*.

So on Android 17, **nothing at all can be launched onto a MediaProjection-backed
virtual display — not even Headway's own activity.** `ScreenEncoder.kt:268`
creates exactly that kind of display. `LogicalDisplay` L1096-1105 returns true
early for `FLAG_OWN_CONTENT_ONLY`, so the own-content path is unaffected.

This promotes Consequence 3 below from a refactor to a prerequisite: the
dashboard cannot exist on a projection display, so `CarDisplay` creates one
through `DisplayManager` instead — which incidentally needs no projection
consent at all.

Not verifiable from source: whether
`enable_display_content_mode_management` is enabled in the shipping GrapheneOS
Pixel 10 Pro XL build; it comes from the release config. One device test settles
it — launch onto a projection-backed display and grep logcat for
`"display that cannot host tasks"`.

## Finding 6 — an overlay reaches every app, and this ADR missed it

`SYSTEM_ALERT_WINDOW` with `TYPE_APPLICATION_OVERLAY` composites Headway's own
views *on top of whatever app is running* on display 0. It is a user-granted
special access in the same class as the accessibility grant — not privileged,
not `signature`, not ADB-only — so it is in bounds under constraint 2.

Because the car mirrors display 0, an overlay is on the car screen whatever is
running, and car touches already reach it through the existing input path. That
makes it the best available primitive for custom car chrome over a real app,
and it is how Headway's floating voice button works.

## Decision

> **Superseded in part by [ADR 0006](0006-the-car-gets-its-own-screen.md).** The
> findings below stand unchanged. What changed is the *mechanism* in point 1 and
> the status of point 2. An activity cannot be launched onto this display at all
> — see the correction at the end of this file — so the dashboard is a
> `Presentation`, which is a window rather than a task and never enters the
> activity-launch path. Mirroring is no longer a parallel "source" the user
> picks between at connect time; it is a mode the driver switches into by
> tapping an app and out of with a floating Home button.

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

## Corrections, 2026-08-14

The verdict survived a second pass against `android17-release`. Three arguments
did not, and one whole finding was missing. Recorded here rather than quietly
edited, because the errors were the confident kind.

1. **`ACTIVITY_EMBEDDING` was never the blocker** (Finding 1). It is waived when
   the caller already has an activity on the display. The target's
   `allowEmbedded` opt-in is the constraint, and it is worse — Headway cannot
   influence it at all.
2. **`FLAG_ACTIVITY_LAUNCH_ADJACENT` was wrongly written off** (Finding 2). The
   `mSourceRecord` objection applies to a Service, and Headway's launcher is an
   Activity. Filling a user-created split is a real capability that this ADR
   said was unavailable.
3. **The overlay path was missing entirely** (Finding 6). It is the only way to
   put Headway's own controls over a third-party app, and it was not considered.
4. **Android 17's `canHostTasks` gate was not known** (Finding 5), and it breaks
   the pre-existing code rather than merely constraining new code.
5. **The own-content display cannot host an activity either** (Finding 4). This
   ADR said Headway's own activities go on "via the `getOwnerUid() ==
   callingUid` branch", which reads that branch as an alternative to the
   untrusted gate. It is not — it comes after it, and the gate refuses the
   *first* activity onto an empty display. A `Presentation` is the way on. This
   is the same misreading as (1), one step further along, and it had been copied
   into `BLOCKERS.md` B-008 and into `CarDisplay`'s own justification before a
   verifier caught it.

The lesson worth keeping: three of the four are cases where a *true* fact about
one code path was generalised to a conclusion about a different one. Each
citation was real; each inference over-reached.

## Sources

- `services/core/java/com/android/server/wm/ActivityTaskSupervisor.java`
  L1284-1371 (`isCallerAllowedToLaunchOnDisplay`), `android17-release`;
  L1201-1279 in `android15-release`
- commit `ed115cd`, which introduced that check
- `services/core/java/com/android/server/wm/ActivityStarter.java` L3104-3120
  (`LAUNCH_ADJACENT`), `android17-release`
- `services/core/java/com/android/server/wm/LogicalDisplay.java` L1092-1105
  (`validateCanHostTasksLocked`), `android17-release`
- `services/core/java/com/android/server/wm/TaskFragment.java` L839-928
  (cross-app embedding opt-ins), `android17-release`
- `packages/modules/Permission/PermissionController/res/xml/roles.xml` L99-106
  (`virtual_device` permission set) and
  `role-controller/.../model/Role.java` L909-911 (`systemOnly` enforcement)
- `core/api/current.txt` vs `core/api/system-current.txt` vs
  `core/api/test-current.txt` — for `TaskOrganizer`,
  `WindowContainerTransaction`, `setLaunchWindowingMode`,
  `VirtualDeviceManager.createVirtualDevice`, `VIRTUAL_DISPLAY_FLAG_TRUSTED`
- `car/app/app/src/main/res/values/config.xml` and
  `androidx.car.app.HostValidator` — the six hardcoded host signatures
- `services/accessibility/java/com/android/server/accessibility/SystemActionPerformer.java`,
  `android11-release` vs `android13-release`
- `services/core/java/com/android/server/wm/DisplayPolicy.java`
  (`screenTurnedOff`), `DisplayContent.shouldSleep`, `android15-release`
- `services/core/java/com/android/server/display/VirtualDisplayAdapter.java`,
  `android15-release`
- `core/res/AndroidManifest.xml` — protection levels for `WRITE_SETTINGS`,
  `ACTIVITY_EMBEDDING`, `ADD_TRUSTED_DISPLAY`

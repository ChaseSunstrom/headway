# ADR 0007 — Headway is a car-app host, and gets there by declaring the renderer permission

**Status:** Accepted, 2026-08-14
**Supersedes nothing. Corrects [ADR 0004](0004-what-headway-can-put-on-the-car-screen.md).**

## Context

The driver's question was "why can't we run apps like Android Auto does?",
followed by "no screen mirroring — it might just need permissions the user can
set in settings."

The first half has a precise answer. Android Auto does not run apps. It binds a
service the app exports, receives a **template** — a declarative model of a
list, a grid, a pane, a navigation strip — and *Gearhead* draws every pixel of
it. The app never renders anything, its activities never start, and its window
never leaves the phone. That is why every Android Auto screen looks like Android
Auto rather than like the app, why they work at 800×480, and why they are legible
at a glance in a moving car.

ADR 0004 established, on three independent grounds, that Headway cannot host
another app's *window*. That conclusion has not moved and this ADR does not touch
it. What it does is take the route ADR 0004 already named as the alternative and
build it, having found the one thing ADR 0004 got wrong about it.

## What ADR 0004 got wrong

It said the `CarAppService` model is "entirely available unprivileged". That is
true of the client side — anyone can write a car app. It is not true of the host
side without one extra step, and Headway is the host.

Every car app validates its caller in its own process before answering anything.
`CarAppBinder.onHandshakeCompleted` builds
`HostInfo(claimedPackageName, Binder.getCallingUid())` and hands it to whatever
`HostValidator` the app's `createHostValidator()` returned. Decompiled from
`androidx.car.app:app:1.7.0`, `HostValidator.validateHost` accepts on exactly
four conditions, in this order:

1. `packageInfo.applicationInfo.uid == Process.myUid()` — the app calling itself.
2. `isAllowListed(packageName, signatures)` — the caller's package name and the
   SHA-256 of its signing certificate are in the allowlist the app hardcoded.
3. `uid == 1000` — a system binding.
4. `hasPermissionGranted(packageInfo, "android.car.permission.TEMPLATE_RENDERER")`
   — the caller *holds* that permission, checked as
   `requestedPermissionsFlags[i] & REQUESTED_PERMISSION_GRANTED`.

Everything else logs `Unrecognized host` and returns false.

Note what condition 2 is worth in practice. Both shipping open-source car apps
copy Google's sample allowlist verbatim: OsmAnd's `NavigationCarAppService` and
Organic Maps' `CarAppServiceBase` both build from
`R.array.hosts_allowlist_sample`, which is six digests across two Google
packages. Out of the box, a third-party host is accepted by **zero** apps.

## Decision

**Take route 4: declare `android.car.permission.TEMPLATE_RENDERER` at
`signature` level and use it.**

A permission is a name. An app may declare one, and a permission requested by
the package that declared it is granted at install — for `signature` protection,
because the declaring package trivially matches its own signature. No root, no
platform key, no `/system/priv-app`, no `adb shell pm grant`, no hidden API. The
manifest declares it and Android grants it, which is the ordinary documented
behaviour of custom permissions.

This is the "permissions the user can set in settings" answer, one notch better:
the user does not even have to set it.

### Why this is a capability claim and not impersonation

The line worth being careful about. Headway is not claiming to *be* Android Auto,
and structurally cannot:

- The package name Headway sends in the handshake is cross-checked against
  `Binder.getCallingUid()` before the validator runs. A host that lies about its
  identity is rejected with `IllegalStateException` before route 4 is reached.
  Every app sees `dev.headway.app` and Headway's real signing certificate.
- An app that would rather not serve Headway can still refuse it by name — the
  allowlist is consulted first, and an app is free to add a deny.
- The permission names a *role*: rendering car-app templates. Headway performs
  that role. It renders templates on a car screen, over the real Android Auto
  protocol, to a real head unit.

What it does mean is that Headway asserts a capability the platform normally
reserves for a car framework, on a phone where no car framework exists. That is
a deliberate decision and it is written out beside the declaration in the
manifest so nobody has to find this file to understand the line.

### The cost

A permission name has exactly one definer per device. If another package already
declares this one, Headway fails to install with
`INSTALL_FAILED_DUPLICATE_PERMISSION`. Nothing on an AOSP handset declares it —
it belongs to Android Automotive's car service, which is not in a phone build —
so the realistic case is a phone that also has Google's Android Auto. Recorded
as **B-013**, with the workaround: delete the two manifest lines and rebuild;
everything but the host is unaffected, and the host still works against a
debug-built car app.

The same shape applies to the `androidx.car.app.connection` provider (**B-014**),
which is how a car app asks whether it is being projected. On a de-Googled phone
nothing answers that query and every app concludes it is not in a car — several
of them then decline to run their car service at all. Headway answers it with
the truth about its own AAP session, and answers `NOT_CONNECTED` when there is
no car.

## Also decided: depend on `androidx.car.app`, do not fork it

The earlier plan was to fork the library to reuse its `Bundler` and AIDL. On
inspection the AAR already exports everything a host needs as public API —
`ICarApp`/`ICarHost`/`IAppHost`/`ISurfaceCallback`/`IConstraintHost`/
`INavigationHost` stubs, `Bundler`, `Bundleable`, `HandshakeInfo`, `AppInfo`,
`SessionInfoIntentEncoder`, and the entire template model with public getters —
so a fork would buy nothing and cost a permanent divergence.

Two things make the dependency the *correct* choice rather than merely the
cheaper one:

- **Binder transaction codes are assigned by AIDL declaration order.** A
  hand-written `ICarApp` with the methods in a different order compiles, binds,
  and then silently calls `onAppPause` where it meant `onAppStart`. Using the
  library's own generated stubs makes the ordering exact by construction, and
  keeps it exact when the interface grows.
- **Every payload is a `Bundleable`,** whose encoding is `Bundler`'s reflective
  field-name mangling — nine hundred lines whose specification is "whatever the
  library does". Reimplementing that is a guarantee of subtle, app-specific
  breakage that only shows up against software nobody on this project can run.

Apache-2.0, one-way compatible with GPLv3, no Play Services in its dependency
tree — `checkNoGms` covers it like everything else. Guava arrives transitively
for a `ListenableFuture` the host side never touches; R8 drops it from release.

## Consequences

- **A third-party app's interface reaches the car screen without mirroring.**
  This is what the driver asked for. `CarAppTile` binds the app, runs the
  handshake, and `TemplateRenderer` draws the result with Headway's own widgets
  at the head unit's own density — 800×480, Headway's palette, Headway's touch
  targets. The phone's own screen is not involved and does not need to be on.
- **Navigation apps draw their own map.** `ISurfaceCallback` gets a real
  `Surface` sized to the pane and told the car's dpi, so the map is the app's own
  rendering *at the car's resolution* — still not mirroring, because nothing is
  scaled and nothing is captured. Car touches become `onScroll`/`onFling`/
  `onScale`/`onClick`, which is the exact gesture set the protocol carries.
- **Turn-by-turn stops being a scrape.** `INavigationHost.updateTrip` delivers a
  real `Trip`: numeric maneuver types, distances with units, remaining seconds.
  `CarAppTrips` decodes it into the same `NavigationFeed` the notification
  scraper feeds, and a structured step is never overwritten by a scraped one.
- **Reach is honest and bounded.** Route 4 is the whole of it. If a future
  library release drops that branch, or an app ships its own stricter validator,
  those apps go dark and the pane says so by name. **B-012** tracks the fact that
  no real app has yet been tried against a real device.
- **The install-time risks are real.** B-013 and B-014 are the price, and both
  have a one-edit workaround that costs only the host.
- **Nothing else depends on it.** Maps, media, phone and messages all work from
  their own models whether or not a single car app ever answers.

## Evidence

Decompiled with `javap -c` from `androidx.car.app:app:1.7.0`
(`dl.google.com/dl/android/maven2/androidx/car/app/app/1.7.0/app-1.7.0.aar`):

- `androidx/car/app/validation/HostValidator.class` — `validateHost` accepting
  on the four conditions above, in that order; `hasPermissionGranted` reading
  `requestedPermissionsFlags[i] & 2`; `Api28Impl.getPackageInfo` requesting
  `GET_SIGNING_CERTIFICATES | GET_PERMISSIONS` (134221824).
- `androidx/car/app/CarAppBinder.class` — `onHandshakeCompleted` building
  `HostInfo` from `Binder.getCallingUid()`, then range-checking the host's API
  level against `AppInfo.getMinCarAppApiLevel()` and
  `getLatestCarAppApiLevel()`; `getManager` returning `AppManager.getIInterface()`
  for `"app"` and `NavigationManager.getIInterface()` for `"navigation"`.
- `androidx/car/app/ICarApp.class`, `ICarHost.class`, `IAppHost.class`,
  `ISurfaceCallback.class`, `constraints/IConstraintHost.class`,
  `navigation/INavigationHost.class` — the method sets and their order.
- `androidx/car/app/CarAppService.class` — `SERVICE_INTERFACE` =
  `"androidx.car.app.CarAppService"`, and the `androidx.car.app.category.*`
  constants.
- `androidx/car/app/CarContext.class` — `APP_SERVICE` = `"app"`,
  `NAVIGATION_SERVICE` = `"navigation"`, `CONSTRAINT_SERVICE` = `"constraints"`.
- `androidx/car/app/connection/CarConnectionTypeLiveData.class` — the query URI
  `content://androidx.car.app.connection`, projection `["CarConnectionState"]`;
  `CarConnection.class` for the three state constants.
- `androidx/car/app/serialization/Bundler.class`, `Bundleable.class` — the wire
  format, and that `create`/`get` are public.

Allowlist evidence: `car/app/app/src/main/res/values/config.xml`
(`hosts_allowlist_sample`, six digests, two Google packages); OsmAnd
`NavigationCarAppService.java` and Organic Maps `CarAppServiceBase.java`, both
building from that array and both branching on `FLAG_DEBUGGABLE` to
`ALLOW_ALL_HOSTS_VALIDATOR`.

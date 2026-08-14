# ADR 0009 — Two APKs, so the car-app host cannot take the whole app down with it

**Status:** Accepted, 2026-08-14
**Amends [ADR 0007](0007-headway-as-a-car-app-host.md). Resolves BLOCKERS B-013 and B-014.**

## Context

ADR 0007 decided that Headway becomes a car-app host by declaring
`android.car.permission.TEMPLATE_RENDERER` and using it, because
`HostValidator.validateHost`'s fourth branch is the only one an unprivileged
third-party host can reach. The same change added a `<provider>` owning the
`androidx.car.app.connection` authority, which is how a car app asks whether it
is being projected.

That ADR named the cost and priced it wrongly:

> A permission name has exactly one definer per device. If another package
> already declares this one, Headway fails to install with
> `INSTALL_FAILED_DUPLICATE_PERMISSION`. […] Recorded as **B-013**, with the
> workaround: delete the two manifest lines and rebuild.

Two things are wrong with that. The smaller one is that "rebuild it yourself" is
not a workaround available to somebody installing a published APK. The larger
one is the scope: **the cost is not the car-app host, it is the entire
application.** A permission name has one definer per device and a provider
authority one owner, so on a phone that already holds either, the APK does not
install *at all*. The car link, video, touch, audio, voice, the dashboard, maps,
phone, messages and media — none of which is downstream of those two names —
go down with a feature that was supposed to be additive.

It then happened. A user on GrapheneOS running build 84 could not install any
later build, and Android's report for it is the bare string "App not installed",
with no reason attached and nothing written anywhere they could read. That is
the worst property of the whole failure: it is silent, total, and undiagnosable
from the device.

## Decision

**Publish two APKs from every build. They differ in exactly two manifest
declarations and nothing else.**

- **`host`** — declares the permission and the provider. The default, and what
  the release notes point at.
- **`compat`** — declares neither. Installs on any phone, including one with
  Google's Android Auto.

Both carry the same `applicationId` and are signed with the same committed key,
so either upgrades the other in place, with no uninstall and no data loss. The
in-app updater matches the running variant by filename suffix
(`ReleaseCatalog.chooseApk`) so an ordinary update never silently changes which
one you are on — in one direction that would strip the car-app host without
saying so, and in the other it would reproduce the install failure on a phone
that had a working Headway a moment earlier.

The mechanism is a product flavour, not a build flag, because there is no way to
make a manifest element conditional at install time and no way to withdraw a
claim at runtime. `app/src/host/AndroidManifest.xml` holds both declarations;
`app/src/compat/AndroidManifest.xml` is empty and says why.

## Why not the alternatives

- **Declare the provider `android:enabled="false"` and enable it at runtime.**
  This would have been the cheapest fix. It does not work, and the reason is
  worth recording so nobody proposes it again:
  `ComponentResolver.assertProvidersNotDefined` iterates `pkg.getProviders()` —
  the raw parsed provider list straight off the manifest — splits
  `android:authorities` on `;` and throws if any name is already a key in
  `mProvidersByAuthority`. There is no `isEnabled()` call, no
  `MATCH_DISABLED_COMPONENTS`, no package-state lookup anywhere in the method;
  `isEnabledAndMatches` appears in that file only inside the runtime
  intent-resolution paths. **A disabled provider conflicts exactly as a enabled
  one does.**

  The runtime half of the idea is sound and useless: an app may indeed enable
  its own disabled component with no permission —
  `setComponentEnabledSetting` is `@RequiresPermission(…, conditional = true)`
  and the condition is "target components running under the same uid as the
  caller" — but the install has already failed by then.

  And it would have addressed only half the problem regardless: there is no
  equivalent for a `<permission>`, which cannot be declared conditionally or
  granted late.
- **Drop the car-app host.** It is the answer to the question that prompted it
  — "why can't we run apps like Android Auto does?" — and it works. Giving it up
  because of a name collision on some phones is the wrong trade when the
  collision is detectable and the fallback is one file.
- **Ship only `compat` and let people build `host` themselves.** Same objection
  as B-013's original workaround: it asks a user with a phone to have a
  toolchain.
- **Pick the permission name differently.** Not available. The branch Headway
  is accepted on tests that exact string, inside the other app's process.

## Consequences

- **The install failure is bounded to the feature that causes it.** A phone that
  cannot take the host build gets everything except the car-app host, rather
  than nothing.
- **The failure is legible.** `UpdateReceiver` now reads
  `PackageInstaller.EXTRA_OTHER_PACKAGE_NAME` — public API, set only on
  `STATUS_FAILURE_CONFLICT` — so a conflict names the package that already owns
  what Headway tried to claim. That, the platform's own `INSTALL_FAILED_*`
  message, and the advice for each go to the session log and to the Updates
  card, not only to a Toast that has already faded. The self-test's "Install
  collisions" section answers the same question before an install is attempted.
- **The split is checked on the built APKs, not on the intent.**
  `tools/check-install-claims.sh` runs in CI against both APKs and fails if
  `compat` declares either name or `host` declares neither. Both mistakes are
  otherwise silent: one makes the fallback useless on exactly the phones it is
  for, the other disables the host with no error anywhere.
- **The instrumented suite runs the `host` variant.** Its two host-specific
  assertions are `assumeTrue`-gated on `BuildConfig.CAR_APP_HOST` so the compat
  variant does not fail tests for behaving as designed; the shell script covers
  what the assumption skips.
- **CI builds and publishes both**, and every Gradle task name in the workflow
  is now variant-qualified. The instrumented job names all four modules
  explicitly, because `connectedHostDebugAndroidTest` alone would have quietly
  dropped the three library modules that have no flavours.
- **B-013 and B-014 stop being open.** Neither is closed in the sense of the
  collision becoming impossible — that is not in Headway's gift — but both now
  have a shipped fix that costs the user one download rather than a toolchain.

## Evidence

- `HostValidator.validateHost`, decompiled from `androidx.car.app:app:1.7.0` —
  the four acceptance conditions and the `TEMPLATE_RENDERER` branch. See
  ADR 0007's evidence section, unchanged.
- `androidx.car.app:app:1.7.0`'s own manifest contributes
  `<queries><provider android:authorities="androidx.car.app.connection" /></queries>`
  to every dependent, which names the authority without owning it. Confirmed in
  the merged manifest of both variants — it is why
  `tools/check-install-claims.sh` matches on the provider's class name rather
  than on the authority string.
- `android.jar` for API 35: `PackageInstaller.EXTRA_OTHER_PACKAGE_NAME` is
  public SDK API. `EXTRA_LEGACY_STATUS`, which carries the raw
  `INSTALL_FAILED_*` integer, is **not**, so the receiver reads the status
  message text instead of a hidden constant.
- Both APKs verified with `apksigner verify --print-certs` to carry certificate
  SHA-256 `49ec8b1c594bd461785e04fc9a7e6592f955538039327bc70a5ef8d889abd275`,
  matching `signing/headway-dev.jks`. That equality is what makes them
  interchangeable without an uninstall.

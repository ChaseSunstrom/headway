# ADR 0003 — How Headway gets onto the car's Wi-Fi

**Status:** Accepted. Supersedes nothing; records a decision that was previously
implicit.

## Context

Headway must put the phone on the head unit's access point and open a TCP
socket to it. Three unprivileged routes exist on Android 15, and until now only
the first was used without the alternatives having been written down.

The forcing issue: on a 2021 Chevrolet Infotainment 3 unit, the phone
associates and authenticates and then **never receives a DHCP lease**. The
platform reports `STATUS_LOCAL_ONLY_CONNECTION_FAILURE_IP_PROVISIONING`. The
only configuration that works is a manual Wi-Fi join plus a hand-entered static
IP, which is not a shippable user story.

The cause is not a Headway bug. GrapheneOS carries a carve-out for this exact
failure, keyed on Google's Android Auto package
(`WifiConfiguration.java` L3400-L3405):

```java
if (android.app.compat.gms.GmsCompat.isAndroidAuto()) {
    // Per-connection MAC randomization doesn't work with some cars, see
    // https://github.com/GrapheneOS/os-issue-tracker/issues/4139
    macRandomizationSetting = RANDOMIZATION_PERSISTENT;
    mIsSendDhcpHostnameEnabled = true;
}
```

BLOCKERS.md B-006 carries the full derivation. The short form is that both
halves are out of reach: a `WifiNetworkSpecifier` connection inherits the
requesting app's `WifiConfiguration`, whose GrapheneOS default re-randomizes the
MAC on every connect, and `setSendDhcpHostnameEnabled` is `@SystemApi`.

## Options

**A. `ConnectivityManager.requestNetwork(WifiNetworkSpecifier)`** — what
Headway has always done. Connects on demand, hands back a `Network` to bind
sockets to, and the approval is banked per access point after the first success.
Cannot influence the MAC. Produces a *local-only* network that is never the
phone's default route, so every socket must be bound explicitly. The network is
not *saved*, so the user cannot adjust it in Settings either.

**B. `WifiManager.addNetworkSuggestions` with
`setMacRandomizationSetting(RANDOMIZATION_PERSISTENT)`** — the only public API
carrying a MAC preference. Needs no new permission (`CHANGE_WIFI_STATE` is
already declared). But the platform decides when to associate, so there is no
on-demand connect and no bound on reconnect latency; the first suggestion needs
the user to accept a notification and a refusal is sticky
(`STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED` persists until Settings →
Apps → Special app access → Wi-Fi control); and it returns no `Network`, so
acquisition has to happen by watching for one.

**C. `Settings.ACTION_WIFI_ADD_NETWORKS`** — a system panel that saves a
network from credentials the app supplies. Public, unprivileged, not
`@SystemApi`. It connects nothing by itself, and what it produces is an ordinary
saved network on DHCP — byte-for-byte the configuration that already failed by
hand. Its value is elsewhere: a *saved* network is the only kind whose
per-network MAC and DHCP-hostname controls GrapheneOS exposes to the user.

## Decision

**Keep A as the default. Ship C as an explicit setup step. Ship B behind a
quirk-file toggle, off by default.**

`CarWifiNetwork.adoptExistingCarNetwork` is tried before any of them and
becomes load-bearing rather than a fallback: it is how B acquires its network,
and how a user who joined by hand gets a session with no prompt at all.

Rationale for each part:

- **A stays the default** because it is the only route that connects on demand,
  and because the failure it cannot fix is not universal — a head unit that
  issues leases normally has never needed anything else. Making B the default
  would trade a working path for an unproven one on every car.
- **C is a setup step, not a fix.** Calling it a fix would be dishonest: it
  saves a network, and the network still needs two toggles set by hand. What it
  genuinely removes is transcribing a passphrase the user never sees, and it
  creates the object those toggles live on. It must be launched from an Activity
  — background-activity-launch rules make it unreliable from the service, which
  is the only place Headway holds live credentials, hence the small credential
  stash in `SharedPreferences`.
- **B is off by default** because its costs are certain and its benefit is not.
  A sticky rejection from one mis-tap in a moving car is worse than the failure
  it treats, and it addresses only one of GrapheneOS's two ingredients. When
  on, it **replaces** A rather than running alongside it: on STA+STA hardware
  a specifier request would open a second connection to the same access point
  with a fresh random MAC, which is precisely what the suggestion exists to
  prevent.

## Consequences

- Sockets stay bound to the car `Network` under every route. The car AP has no
  internet and will never be validated, so it never becomes the default network
  — and on a bench phone with no SIM it accidentally *does*, which is a bug
  class worth remembering when a test passes that should not.
- A saved car network can be auto-disabled after failing validation
  (`DISABLED_NO_INTERNET_PERMANENT`, threshold 1, cleared only by a manual
  reconnect). Route B has to notice that rather than wait silently.
- Addressing failures get two attempts, not one
  (`HeadwayService.MAX_ADDRESSING_ATTEMPTS`). The second is a measurement: a
  full-but-recoverable address table and an outright refusal make different
  predictions about a repeat association, and the log now records which happened.
- The DHCP-hostname half remains unreachable by any route. That is B-006 and it
  stays open.

## What would change this decision

A real-car log showing which of the two GrapheneOS toggles restores addressing.
If it is the MAC, B is worth promoting toward the default. If it is the
hostname, B buys nothing and should be removed rather than left as a knob that
cannot help.

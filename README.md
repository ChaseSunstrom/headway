# Headway

An open-source Android app that replaces Google's Android Auto phone app
entirely. It speaks the reverse-engineered Android Auto Protocol (AAP) directly
to a factory head unit — wirelessly — and casts *any* app on the phone to the car
screen, with touch input coming back from the car and the car's microphone driven
by fully on-device speech recognition.

No Google services. No network. No root. No system privileges.

> **Status: in development. A real car now reaches service discovery.** On
> 2026-08-13 a 2021 Chevrolet Infotainment 3 unit completed the Bluetooth
> handshake, the Wi-Fi join, TCP, the AAP version exchange, TLS, authentication
> and service discovery, and listed all 13 of its channels. Nothing past channel
> open has run on real hardware: no video has been sent, no touch received, no
> audio played. The protocol stack, the test harness and the voice pipeline are
> exercised in CI; the Android adapters for the encoder, input and audio are
> written but never executed against a car. [`PROGRESS.md`](PROGRESS.md) records
> the tier of evidence behind every phase — read it before believing anything
> here works.

## Why

Android Auto is the only sanctioned way to get your phone onto a modern car's
screen, and it is closed, requires Google Play services, phones home, and only
runs the handful of apps Google approves. The protocol underneath it has been
reverse-engineered for years. Headway is what happens when you implement the
phone side of that protocol yourself: your car screen becomes an ordinary
external display for your phone, and *you* decide what runs on it.

## Design constraints

These are hard rules, not preferences:

- **Wireless only.** Bluetooth is used solely for discovery and the credentials
  handshake; the session itself is TCP over the head unit's 5 GHz Wi-Fi. No USB.
- **Nothing privileged.** No `INJECT_EVENTS`, no hidden APIs, no root, no system
  signature. If a feature needs privilege, it does not ship — it gets documented
  in [`BLOCKERS.md`](BLOCKERS.md) with whatever unprivileged workaround exists.
- **Fully local.** No telemetry, no update checks, no cloud speech recognition,
  no Google endpoints. Works in airplane mode with Wi-Fi and Bluetooth on. The
  car's access point has no internet and the app never assumes otherwise.
- **No protocol constant is guessed.** Every message id, port, channel id and
  schema is transcribed from a reference implementation and cited, file and line,
  in [`docs/protocol-notes.md`](docs/protocol-notes.md).

## Target hardware

Developed against a **2021 Chevrolet Malibu** (Chevrolet Infotainment 3, wireless
Android Auto) and **GrapheneOS on Pixel**. The head unit is treated as a generic
wireless AAP unit — no Malibu-specific behaviour is hardcoded; quirks are
configuration.

## Architecture

The entire protocol stack is **pure Kotlin/JVM with no Android dependencies**
([ADR 0001](docs/adr/0001-kotlin-protocol-core.md)). That is the load-bearing
decision in this repo: it means the head-unit emulator links the same code from
the other side, and the full handshake plus every channel state machine runs
under `./gradlew test` on a bare JDK — no device, no NDK, no emulator image.

```
core-protocol/      AAP framing, channel multiplexing, protobuf schemas     (JVM)
core-transport/     TCP, in-memory TLS, Bluetooth handshake codec, fakes    (JVM)
headunit-emulator/  Head-unit test harness; hosts the acceptance suite      (JVM)
app/                UI, foreground service, permissions, Bluetooth, Wi-Fi   (Android)
core-video/         MediaProjection capture -> MediaCodec H.264             (Android)
core-input/         Car touch/keys -> AccessibilityService gestures         (Android)
core-audio/         Audio channels, focus signalling, A2DP coexistence      (Android)
core-voice/         Car mic -> on-device STT -> command engine              (JVM)
```

## Building

Requires a JDK 17+ (JDK 21 works). The Android SDK is only needed for the
`app/` module and Android libraries.

```bash
# Protocol core and its byte-fixture tests — no Android SDK required
./gradlew :core-protocol:test :core-transport:test

# Phase acceptance suite against the head-unit emulator
./gradlew :headunit-emulator:test

# Voice pipeline against a real speech model (fetch it first, ~41 MB)
./tools/fetch-vosk-model.sh
./gradlew :core-voice:test

# Hard-constraint checks
./gradlew checkNoGms
./tools/check-license-headers.sh
./tools/check-todos.sh
```

## Verifying it yourself

The emulator is a runnable head unit, so you can check the stack without a car.

```bash
# 1. On your laptop alone. Proves framing, TLS, auth, discovery, channel open.
./gradlew :headunit-emulator:run --args="--self-test"

# 2. Wait for your phone. Prints the addresses to point Headway at.
./gradlew :headunit-emulator:run --args="--listen"

# 3. Check reachability without a phone, from this or another machine.
./gradlew :headunit-emulator:run --args="--connect 192.168.1.50"
```

`--listen` runs a real head unit on TCP 5288 and reports what arrives: codec
configuration, video frame count, and the frame rate measured from the
presentation timestamps. That is the strongest verification available short of a
car — a real phone, real sockets, real TLS, real video off a real encoder.

Two honest caveats. `--self-test` shares protocol code with the phone side, so it
proves self-consistency rather than correctness ([ADR 0002](docs/adr/0002-jvm-headunit-emulator.md)).
And none of it is evidence about a Chevrolet; only a Chevrolet is. When one was
finally asked, it disagreed — see
[`docs/protocol-notes.md`](docs/protocol-notes.md) § "Evidence from a real head
unit" for what a single real capture cost and bought.

## How much of this is actually tested

The honest answer matters more than a badge, so it is written down. Every phase
in [`PROGRESS.md`](PROGRESS.md) carries a tier:

- **Executed** — the real code runs against real bytes in CI. The framing, TLS
  handshake, session bring-up, Bluetooth credentials exchange, video and input
  channels, reconnection logic, and the whole voice pipeline are here. The voice
  tests run a real speech model over real recorded speech; the session tests run
  over genuine kernel sockets as well as an in-process fake.
- **Compiled** — type-checked against the real Android SDK and never run:
  `MediaCodec`, `MediaProjection`, `AccessibilityService`, Bluetooth sockets,
  Wi-Fi binding.
- **Unverifiable without hardware** — anything measured in a car.

One caveat worth stating plainly: the head-unit emulator shares its protocol
code with the phone, so a wrong-but-symmetric constant round-trips cleanly and
proves nothing. The byte fixtures are the real oracle. See
[ADR 0002](docs/adr/0002-jvm-headunit-emulator.md).

For the Android modules, point `ANDROID_HOME` at an SDK with platform 35 and
build-tools 35.0.0, or create a `local.properties` with `sdk.dir=...`.

## Working on the protocol

aasdk's 254 protobuf schemas *are* vendored, under
`core-protocol/src/main/proto/aap_protobuf/` — retyping them is the most
error-prone option available, since one wrong field number parses locally and is
rejected by a real head unit. They keep aasdk's authorship; see
[`THIRD-PARTY.md`](THIRD-PARTY.md).

The reference implementations themselves are not vendored. Clone them locally
(they are gitignored) to follow the citations:

```bash
mkdir -p references && cd references
git clone --depth 1 https://github.com/opencardev/aasdk.git
git clone --depth 1 https://github.com/tomasz-grobelny/AACS.git
git clone --depth 1 https://github.com/nisargjhaveri/WirelessAndroidAutoDongle.git
git clone --depth 1 https://github.com/manio/aa-proxy-rs.git
git clone --depth 1 https://github.com/opencardev/openauto.git
```

`docs/protocol-notes.md` cites these by path and line. When references disagree,
aasdk's protobufs win and the disagreement is recorded rather than resolved
silently.

## First connect to a real car

Do these in order. Most first-connect failures are one of the first four.

1. **Pair the phone with the car normally**, in Android's Bluetooth settings.
   Headway does not reimplement pairing and cannot connect without it.
2. **Turn Wi-Fi on** on the phone. It does not need to be connected to
   anything — the car's network has no internet — but the radio has to be on.
   Android refuses Headway's request outright if it is off, and reports that
   refusal in a way no app can distinguish from any other failure.
   On GrapheneOS, also leave Headway's **Network** permission on. It is
   tempting to revoke it on an app that never uses the internet, but that
   toggle makes the platform pretend every network is down, including the
   car's — nothing is sent anywhere either way.
3. **On the car screen**, check the vehicle's own settings: Bluetooth on, Wi-Fi
   on, and Android Auto enabled for this phone. On Chevrolet Infotainment 3
   that is Settings → Bluetooth, Settings → Wi-Fi, and Settings → Apps →
   Android Auto. The head unit's projection access point does not come up until
   Wi-Fi is enabled there, and a projection access point that is not on the air
   looks exactly like a phone that cannot see it.
   *An OnStar data plan is not required* — projection uses the head unit's own
   access point, not the in-vehicle hotspot's internet.
4. **Make sure no other phone is already projecting.** A head unit that is
   busy will still hand over credentials and then refuse the session.
5. Press **Connect** in Headway. Android will show a prompt asking to let
   Headway join the car's network. **Tap the car on that prompt and leave it on
   screen** — do not switch back to Headway to see how it is going. That prompt
   is a separate activity, and covering it is what makes it unrecoverable.
   Android remembers the approval afterwards, so this is a once-per-car step,
   not a once-per-drive one.

If it still does not connect, export the log (**Diagnostics → Export the
session log**) and read the join lines. They now say what the
platform's own verdict was — `NOT_FOUND`, `AUTHENTICATION`, `ASSOCIATION`,
`IP_PROVISIONING` or `NO_RESPONSE` — and each one points somewhere different.

### Why the same car behaves differently from one attempt to the next

Because a connection is not one thing that works or does not. It is **five
gates in series**, each with an independent cause of failure, and you never
see gate *n+1* until gate *n* happens to pass. Every setting worth fiddling
with moves exactly one gate, which is why changing one can look like it fixed
everything and then look like it fixed nothing.

| # | Gate | Fails when | Moved by |
|---|------|-----------|----------|
| 1 | Bluetooth RFCOMM handshake | the head unit is not offering the AA Wireless service, or thinks a session is already running | the car's **connect-device-first** / priority setting; whether the car is already talking to another phone |
| 2 | The car's access point is on the air | projection Wi-Fi is off in the car, or its AP has not finished coming up | the car's Wi-Fi setting; how far the previous attempt got |
| 3 | Association | wrong passphrase, wrong BSSID, no 5 GHz | `pinBssid`, `hiddenSsid` |
| 4 | Getting an IP | the head unit's address table is full — see `IP_PROVISIONING` below | **DHCP vs static IP** |
| 5 | TLS and authentication | the phone's certificate is outside the head unit's validity window | the imported certificate, or the car's clock |

Read across the variables you have been changing:

- **Static IP versus DHCP** only touches gate 4. It cannot make a car that is
  not offering its access point start offering one, and it cannot do anything
  about the certificate — so "static IP got me further and then it still
  failed" is exactly what a fixed gate 4 in front of a stuck gate 5 looks
  like.
- **Already being on the car's Wi-Fi** skips gates 3 *and* 4 outright, which
  is why a manual join changes so much at once. It also changes something
  subtler: a network Headway requests is *local-only* — it carries no default
  route and every socket has to be bound to it explicitly — whereas a network
  you joined yourself is the phone's ordinary default network. Two quite
  different code paths reach the same head unit.
- **Bluetooth already being connected** changes who is mid-sequence when you
  press Connect. The head unit is the one that speaks first on RFCOMM
  (`docs/protocol-notes.md`, step 6), and it brings its access point up as
  part of that exchange. Arriving while the car is partway through — or after
  it has already given up — is not the same as arriving cold.
- **Connect-device-first** changes gate 1's *initiator*. With it on, the car
  pokes the phone over Bluetooth at ignition and waits for Google's Android
  Auto to answer by opening the RFCOMM channel (step 3). Headway is not
  Android Auto and does not answer that poke; it opens the channel itself when
  you press Connect. So the setting decides whether the car has already
  started, and possibly already abandoned, an attempt of its own before
  Headway arrives.

Practically: if you want repeatable results while debugging, hold gates 1–4
still — car Wi-Fi and Bluetooth on, no other phone paired in, joined by hand
with a static IP — and change one thing at a time. The log names the gate it
reached on every attempt.

### If the log says `IP_PROVISIONING`

This one has a specific cause and a specific fix. It means the phone got onto
the car's Wi-Fi — the passphrase was right and the radio accepted it — and then
the head unit never handed out an IP address.

There are two candidate causes and they have different fixes, so check the
Bluetooth one first — it is free.

**The head unit may not think a phone is present.** A head unit generally
completes projection bring-up, DHCP included, only for a phone it considers
connected. If the log line `Bluetooth profiles:` reads `headset=disconnected,
a2dp=disconnected`, connect the car for **Phone calls** and **Media audio** in
Bluetooth settings, and check **Android Auto is still enabled for this phone**
on the car screen. Disabling Android Auto in the *car* is not the same as not
using the Android Auto *app*; Headway takes the place of the app and still
needs the car's permission.

**First: does your log say `access_point_type=STATIC`?** If it does, the head
unit is telling you it assigns no addresses at all, and no amount of MAC or DHCP
tuning will get you one — a static IP is simply the correct configuration for
that vehicle. The 2021 Malibu does exactly this. Everything below applies to a
unit that advertises `DYNAMIC` and still withholds a lease.

**Otherwise it is likely the GrapheneOS car bug, which GrapheneOS has already
fixed for Google's Android Auto and cannot fix for anything else.**

GrapheneOS gives every network an app joins a **brand new MAC address on every
single connection**, so the car sees an unfamiliar device every time. It carries
a carve-out for exactly this, keyed on Google's Android Auto package
(`WifiConfiguration.java` L3400-L3405):

```java
if (android.app.compat.gms.GmsCompat.isAndroidAuto()) {
    // Per-connection MAC randomization doesn't work with some cars, see
    // https://github.com/GrapheneOS/os-issue-tracker/issues/4139
    macRandomizationSetting = RANDOMIZATION_PERSISTENT;
    mIsSendDhcpHostnameEnabled = true;
}
```

Note it is **two** settings, and Headway can reach neither from the network it
requests: the MAC comes from a configuration built in Headway's own process
(where the GrapheneOS default re-randomizes every connect), and the DHCP
hostname setter is a system API. Worse, a network an app *requests* is not a
*saved* network, so it has no Settings entry for you to fix either.

**The fix is to make it a saved network and set both by hand — once.**

1. **Diagnostics → Set up this car's Wi-Fi.** This hands the network name and
   password Headway learned over Bluetooth to Android's own "save this network"
   panel, so you never type them. (Joining by hand in Wi-Fi settings works just
   as well if you prefer.)
2. On that saved network, open the gear icon and set:
   - **Privacy → "Use per-network randomized MAC"**
   - **"Send device name to network" → on**
3. Press Connect.

Set the first, retry; if it still fails, set the second and retry. **Which one
fixes it is genuinely useful information** — it is the difference between the
car objecting to an unfamiliar MAC and the car objecting to a request with no
hostname, and nobody has established which yet. Please report it.

There is also `"suggestCarNetwork": true` in the quirk file, which makes Headway
register the car as a Wi-Fi *suggestion* rather than requesting it. That is the
only public API carrying a MAC preference, so it reaches the first setting
without a Settings trip — but Android then decides when to connect rather than
Headway, the first one needs you to accept a notification, and it does nothing
about the hostname. It is off by default for those reasons.

`BLOCKERS.md` B-006 has the full account with source citations.

**If neither toggle helps, the head unit's address table may be full** — though
be sceptical of that story if this phone has *never* received an address from
this car. Exhaustion explains a car that worked and then stopped, not one that
never worked. Note too that `IP_PROVISIONING` means "provisioning did not
finish", not "no offer arrived", so an address that arrived and was rejected
looks identical from here.

**You cannot inspect a head unit's address table.** It has no administration
page — the usual advice about clearing one assumes a home router, and none of
that applies to a car. So test it from the outside instead:

- **Join the car's Wi-Fi with a device that has never connected to it** — a
  laptop, another phone. If that device gets an address, the table is not full
  and this is not your problem.
- **Set a static IP** (below). If the session then comes up, leasing was the
  only thing broken.

Note also that DHCP leases expire, typically within a few hours on an embedded
server. A table that fills up during an afternoon of retries should recover on
its own overnight. If the failure persists across a night, exhaustion is
probably *not* the cause and the Bluetooth checks above are the better lead.

To clear it anyway, in increasing order of disruption:

1. **Turn the vehicle's Wi-Fi off and on** in the car's settings. This restarts
   the access point and usually its DHCP server with it. Try this first.
2. **Fully power the infotainment system down.** Switching the ignition off is
   often not enough — these units stay awake for a while. Turn the car off,
   open the driver's door, lock the car and leave it for ten minutes.
3. **Factory-reset the infotainment system** (Settings → System → Return to
   Factory Settings on Chevrolet Infotainment 3). This loses your pairings and
   settings, so it is a last resort.

Then **join the car's Wi-Fi by hand, once**, from Android's Wi-Fi settings, and
set **Privacy** to *Use per-network randomized MAC* (Settings → Network &
internet → Internet → the network → Privacy). That setting is per-network:
every other network keeps the default. From then on the phone presents one
stable address to that car, so the table stops filling.

**To tell the two apart:** connect real Android Auto, let it come fully up,
disconnect it, and immediately press Connect in Headway. If that joins where a
cold attempt does not, it is projection state rather than the address table,
and the Bluetooth checks above are the fix.

### If the car says the phone and vehicle calendars disagree

It does not mean your clocks are wrong. That message is a Chevrolet Infotainment
3 unit reporting a **certificate validity failure**, and in the log it shows up
as the session completing TLS and then

```
head unit rejected authentication: STATUS_AUTHENTICATION_FAILURE
```

The certificate every open-source Android Auto implementation carries — Headway
and AACS ship the identical one — expired on **2022-08-24**. It is signed by
Google's Automotive Link CA, so it cannot be reissued or re-dated by anyone
outside Google, and a self-signed replacement only helps if the head unit checks
dates without checking the chain. This unit returned
`STATUS_AUTHENTICATION_FAILURE` rather than `STATUS_CERTIFICATE_ERROR`, which
reads as "the chain was fine, the dates were not".

> **Answered on a real car (2026-08-13): the `internal` certificate works.** A
> 2021 Chevrolet Infotainment 3 unit refused the expired phone-role certificate
> and accepted `Android-Auto-Internal` on the next attempt — TLS established,
> authentication complete, service discovery done. Headway gets there on its own
> in two attempts; putting `"certificate": "internal"` in the quirk file skips
> the wasted first one. **You do not need to move the car's clock**, so Google's
> Android Auto keeps working too.

**Headway tries three certificates before you have to do anything.** The
expired phone-role certificate is not the only material signed by that same
Google Automotive Link CA sitting in the reference implementations — two others
are, and neither has expired:

| id | issued for | expires |
|----|-----------|---------|
| `phone` | the phone role, which is correct | 2022-08-24 |
| `internal` | a head unit (`Android-Auto-Internal`) | **2048** |
| `headunit` | a head unit (`JVC Kenwood`) | **2045** |

No phone implementation has ever presented a head-unit certificate, because the
role is wrong. But "wrong role" only matters if the car looks at the role. If it
checks the chain and the dates — which is what its
`STATUS_AUTHENTICATION_FAILURE` rather than `STATUS_CERTIFICATE_ERROR` points at
— then an unexpired sibling passes and the subject never comes up.

Nobody knows which, so Headway finds out: each authentication rejection advances
to the next certificate and reconnects. Two failed sessions, then either it is
connected or all three are refused. The log names the one in use and says why
it is worth a try, and if one is accepted it tells you the `"certificate"` value
to put in the quirk file so future connects start there.

**If all three are refused, there is no automatic fix, and no reference
implementation has one.** aa-proxy-rs, which is actively maintained and works
with real head units, does not bundle a certificate at all — it loads the pair
from a path the operator provides. Headway does the same, with a one-time
import:

**Diagnostics → Import a certificate and key.** Pick the PEM certificate, then
its PKCS#8 private key. Every session from then on uses them; there is nothing
to repeat. The screen shows which certificate is in use and when it expires, and
the session log says the same on every attempt.

If your key is in RSA rather than PKCS#8 form, convert it once:

```bash
openssl pkcs8 -topk8 -nocrypt -in phone.key -out phone_key.pem
```

The other option, if you have no certificate to import, is to set the **car's**
clock to a date inside the expired certificate's validity window — it ran from
2014-07-04 to 2022-08-24. The head unit judges validity against its own clock,
so this makes its check pass. Headway always presents its certificate rather
than letting the phone's TLS stack quietly withhold an expired one, so the head
unit gets to make that judgement.

**This breaks Google's Android Auto for as long as the clock is wrong, and the
two cannot both work.** Google's certificate is current, so a car whose clock
says 2016 sees it as *not yet valid* and refuses it — with the same "the phone
and vehicle calendars are set to different dates and times" screen, for the
mirror-image reason. That is why the protocol has a distinct
`STATUS_AUTHENTICATION_FAILURE_CERT_NOT_YET_VALID` (-23) next to
`..._CERT_EXPIRED` (-24). One car clock cannot satisfy an expired certificate
and a current one at the same time.

So treat the clock as a diagnostic, not a setting: use it to prove the
certificate is the only thing left in the way, then put it back. Importing
current material is the only route that leaves both Headway and Android Auto
working, and restoring the car's clock is the only route back to Android Auto.

### Or skip DHCP entirely with a static IP

If the head unit will not hand out an address, stop asking it for one. This
cannot be done through Headway — Android gives an app no IP configuration for a
network it requests — so it goes through the same manual-join path:

1. Join the car's Wi-Fi by hand from Android's Wi-Fi settings.
2. Open the saved network → **Advanced** → **IP settings** → **Static**.
3. Set an address on the head unit's subnet that is not the head unit itself.
   The log line `head unit offers 192.168.5.1:7001` names it, so
   `192.168.5.150` with **gateway** `192.168.5.1`, **prefix length** `24` and
   DNS `192.168.5.1` fits that car. A high address avoids colliding with
   whatever the unit hands out when it is working.
4. Press Connect. Headway adopts the connection you already have.

This is worth doing even if you think the address table is fine, because it
tells you something either way. If the session comes up, addressing was the
whole problem. If the phone is on the network and the AAP connect still fails,
the head unit is not ready to project and no amount of address wrangling will
change that — go back to the Bluetooth checks.

Headway notices when the phone is already on the car's network and uses it
directly, so after step 2 there is no approval prompt and no new MAC on any
future connection.

### Two knobs worth trying from the quirk file

**Diagnostics → Create the head unit quirk file** writes a JSON file you can
edit. If the join never succeeds:

- `"hiddenSsid": true` — for a head unit that does not broadcast its network
  name. It fails identically to a car that is not there.
- `"pinBssid": true` or `false` — whether to require the exact BSSID the head
  unit named. Left out, Headway alternates between the two on successive
  attempts, because both have been necessary on real hardware.
- `"certificate": "phone"`, `"internal"` or `"headunit"` — which of the bundled
  certificates to offer first. Left out, Headway starts at `phone` and advances
  on each rejection. Set it once the log has told you which one the car takes,
  to skip the failed sessions. It moves that one to the front rather than
  pinning it, so a stale value costs an attempt, not the connection.
- `"suggestCarNetwork": true` — register the car as a Wi-Fi *suggestion* with a
  per-network MAC instead of requesting the network. The only public API that
  can pin the MAC, so it is the in-app half of the fix for a car that will not
  hand out an address. Off by default: Android rather than Headway decides when
  to connect, the first one needs you to accept a notification (and refusing it
  once blocks Headway until you re-allow it in Settings → Apps → Special app
  access → Wi-Fi control), and it does nothing about the DHCP hostname.

## Installing and updating

Builds are published as GitHub releases. Once one is installed, **Check for
updates** at the bottom of the main screen fetches the newest and hands it to
Android's installer; you will need to allow Headway to install unknown apps, a
normal per-app setting.

That check is the only thing in Headway that touches the internet, and it runs
only when you press the button — no background poll, no check on launch, nothing
sent. CLAUDE.md's "no update checks" rule is about the app never depending on
connectivity or phoning home, and both still hold: Headway starts, connects to
the car and drives with no network at all.

### If an update says "App not installed"

Builds up to and including 19 were each signed with a throwaway key generated on
the CI runner that built them, so no two were signed alike, and Android refuses
to replace an app with one signed by a different key. Uninstall Headway once and
install build 20 or later; those share a stable key
(`signing/headway-dev.jks`), so updates work from then on.

That keystore is committed on purpose. It is public and protects nothing — the
point is that it is *stable*, not secret, exactly as the platform-wide Android
debug key is. A real distribution key belongs in CI secrets and is picked up
from `HEADWAY_KEYSTORE`, `HEADWAY_KEYSTORE_PASSWORD`, `HEADWAY_KEY_ALIAS` and
`HEADWAY_KEY_PASSWORD` without a code change.

## Licence

GPLv3. Headway derives its wire format from aasdk, which is GPLv3, so the whole
project is and must remain GPLv3. See [`LICENSE`](LICENSE).

## Safety

Video on a car screen while driving is your responsibility and is illegal in many
jurisdictions. Headway shows this notice on first run and offers an optional
parked-only mode for video apps. It is off by default — this is a user-freedom
project, and the choice is yours to make.

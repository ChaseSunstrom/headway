# CLAUDE.md — Headway

## Mission

Build **Headway**: an open-source Android app that replaces Google's Android Auto phone app entirely. It speaks the reverse-engineered Android Auto Protocol (AAP) directly to a factory head unit — wirelessly only — and casts *any* app on the phone to the car screen via screen mirroring, with touch input coming back from the car, car microphone audio handled by fully on-device voice recognition, and zero Google services, zero network dependency, zero root.

Target vehicle: 2021 Chevrolet Malibu (Chevrolet Infotainment 3, standard wireless Android Auto, 5 GHz Wi-Fi capable head unit). Treat it as a generic wireless AAP head unit — do not hardcode Malibu-specific behavior; make quirks configurable.

Target phone platform: GrapheneOS on Pixel hardware (Android 15+ AOSP baseline). Everything must work as a **completely unprivileged app**. No root, no system privileges, no Magisk, no ADB-granted permissions beyond what a normal user can grant in Settings, no sandboxed Google Play dependency.

**Do not stop until the Definition of Done at the bottom of this file is fully satisfied.** If you hit a blocker, document it in `BLOCKERS.md`, implement the best available workaround, and keep going. Never end a session by summarizing what "could" be done next — do it. Partial phases are not deliverables; a phase is finished when its acceptance test passes.

## Hard constraints

1. **Wireless only.** No USB/AOA transport in scope. Bluetooth is used exclusively for discovery and the wireless handshake; the AAP session itself runs over TCP on the head unit's 5 GHz Wi-Fi network. Do not build wired support "while you're at it."
2. **No privileged APIs.** If an approach requires `INJECT_EVENTS`, hidden APIs behind non-SDK restrictions, root, or a privileged/system app signature, it is out of bounds. Find the unprivileged path.
3. **Fully local.** No telemetry, no update checks, no cloud STT, no Google endpoints. The app must function on a phone in airplane mode + Wi-Fi/BT. The car's AP has no internet — the app must never assume connectivity.
4. **Do not guess protocol constants from memory.** Every message ID, port number, protobuf schema, channel ID, and handshake sequence must be extracted from the reference implementations listed below and recorded in `docs/protocol-notes.md` with a citation to the source file you derived it from.
5. **License:** GPLv3 for the whole project (aasdk is GPLv3; anything linking it is too). Put proper headers on every file.

## Reference implementations (read these before writing protocol code)

Clone all of these into `references/` (add to `.gitignore`) and study them first:

- `github.com/opencardev/aasdk` (+ maintained forks) — C++ AAP library. The canonical reverse-engineered protocol source: TLS handshake, framing, channel multiplexing, protobuf definitions. Primary source for message schemas.
- `github.com/tomasz-grobelny/AACS` — the only prior art implementing the **phone/server side** of AAP (ran on an SBC). Best architectural reference for the role Headway plays.
- `github.com/nisargjhaveri/WirelessAndroidAutoDongle` and `aa-proxy-rs` — these bridge wireless-phone ↔ wired-car, so they contain a working implementation of the **wireless Bluetooth handshake** (BT RFCOMM service, the Wi-Fi credentials exchange, TCP session bring-up). Primary source for the wireless flow.
- `github.com/opencardev/openauto` / Crankshaft — head unit emulator. This is your test harness's foundation.
- The Rust `android-auto` crate — cleanest modern reading of framing/TLS; head-unit role, so invert it.
- Google's Desktop Head Unit (DHU) from the Android SDK — supports wireless mode. Use it as a second, independent test target. Note: DHU may require the phone side to behave exactly like real AA; treat DHU compatibility as a stretch goal and the openauto-based harness as the required one.

Where references disagree, prefer aasdk's protobufs and note the discrepancy.

## Architecture

Single Android app (Kotlin) + one native library (C++ via JNI, embedding aasdk or a from-scratch port of its protocol layer — your choice; document the decision in `docs/adr/`). Suggested module layout:

```
app/                  Kotlin: UI, foreground service, permissions flow, settings
core-transport/       BT RFCOMM handshake, Wi-Fi join, TCP socket, TLS session
core-protocol/        AAP framing, channels, protobuf (generated), service discovery
core-video/           VirtualDisplay-or-MediaProjection capture → MediaCodec H.264 → video channel
core-input/           Car touch/rotary/button events → AccessibilityService gesture injection
core-audio/           Audio output channels; A2DP fallback logic; audio focus
core-voice/           AV-input (car mic) channel → on-device STT (Vosk or whisper.cpp) → command engine
headunit-emulator/    Desktop test harness (see Testing) — separate Gradle module or sibling CMake project
```

### Transport (Phase 1–2 focus)

Wireless AAP flow to implement, with exact details derived from WirelessAndroidAutoDongle/aa-proxy-rs:

1. User pairs phone with the car's Bluetooth normally (standard OS pairing — Headway does not reimplement pairing).
2. Headway registers/connects the AA wireless RFCOMM service against the car's BT device, performs the protobuf exchange in which the head unit supplies its Wi-Fi AP credentials (SSID/PSK/BSSID) and TCP endpoint.
3. Phone joins the car's 5 GHz AP using `WifiNetworkSpecifier` + `ConnectivityManager.requestNetwork`. **Critical Android detail:** the car AP has no internet, so Android will not route traffic to it by default — bind the app's sockets to that specific `Network` object (`network.socketFactory` / `bindProcessToNetwork` scoped to the session). Handle the OS tearing the network down when the screen locks; hold it with the foreground service.
4. Open TCP to the head unit, run the AAP version handshake, then the TLS handshake using the certificate/key material shipped in the open-source implementations. Session up.
5. Service discovery: advertise the channels Headway supports (control, sensors, video, input, media audio, speech audio, AV input/mic). Then run the channel state machines.

Reconnection is a first-class feature: the app must survive walking away from the car and returning, BT flapping, and the head unit rebooting, reconnecting automatically within 15 seconds of the car being available, without user interaction.

### Video

- Encode with `MediaCodec` H.264, resolution/fps/DPI negotiated from the head unit's advertised configs (the Malibu-class unit is 800×480-class; do not assume — negotiate).
- Source: `MediaProjection` capture of the phone screen (user consents once per session; use the Android 14+ "entire screen" flow). Screen-off casting: keep capture alive with the foreground service `mediaProjection` type; investigate rendering to a `VirtualDisplay` owned by the projection so the physical screen can dim. If true screen-off mirroring proves impossible unprivileged, document it in `BLOCKERS.md` and ship with keep-screen-on as the default behavior.
- Latency budget: end-to-end touch-to-photon under 250 ms on a Pixel 7-class device. Measure it; add a debug overlay showing encode fps and RTT.

### Input

- Car touch events arrive on the input channel in head-unit screen coordinates; transform to phone screen coordinates (letterboxing-aware) and inject via an `AccessibilityService` using `dispatchGesture`. Support tap, long-press, and drag/fling (build gestures from the touch event streams; do not fake flings as taps).
- Steering wheel/media/voice buttons arrive as key events on the input channel: map media keys to `MediaSession` transport controls (`onMediaButtonEvent` via `AudioManager`/`MediaSessionManager` where an unprivileged app can, otherwise into the command engine), and map the voice key to open a mic session (below).

### Audio

- Headway's own audio (voice replies, nav prompts if any, UI sounds) goes over the AAP speech/system audio channels.
- Third-party media audio: do **not** attempt playback capture as the primary path (`AudioPlaybackCapture` is opt-out-able and adds latency). Default strategy: instruct/steer media audio over the car's normal Bluetooth A2DP link, which coexists with the AAP session. Implement AAP media-audio channel support behind a settings toggle for apps that allow capture.
- Implement audio focus signaling on the AAP control channel correctly so the car ducks/resumes radio audio.

### Voice (the car mic — this is a headline feature, not an afterthought)

- Voice button press → open AV-input channel session → receive 16 kHz mono PCM from the car's cabin mic.
- Feed it to an on-device STT engine. Default: **Vosk** (small English model bundled, ~50 MB, permissively licensed); make the engine pluggable and add a whisper.cpp backend as a build flavor.
- Command engine (deterministic, grammar-based first; no LLM dependency): launch app by name ("open Maps", fuzzy-matched against installed app labels), media control ("pause", "next"), volume, "go home" (return Headway's launcher surface), and free-text search typed into the foreground app via the accessibility service.
- Everything offline. Recognition results and audio never leave the device; do not write raw audio to disk except behind an explicit debug flag.
- Known limitation to document in the README: Android does not allow presenting the car mic to *other* apps as the system microphone without privileged access. Voice is therefore a Headway feature, not a system-wide mic replacement.

### On-car UI

Headway presents a minimal launcher surface as the default cast content: dark theme, large touch targets, grid of user-pinned apps, clock, connection status, a voice button. Tapping an app launches it (on the mirrored screen). This must be usable in sunlight and with the car's touch digitizer (assume imprecise touches; minimum 48 dp targets scaled to the head unit density).

Safety: show a first-run notice that video content while driving is the user's responsibility and may be illegal in their jurisdiction; provide an optional "parked-only for video apps" toggle (off by default, user's choice — this is a user-freedom project, not a nanny).

## GrapheneOS specifics

- Request only: Bluetooth (connect/scan with `neverForLocation`), Nearby Wi-Fi devices, Notifications, Microphone (only if the phone-mic fallback is enabled), Accessibility (guided opt-in with a plain-language explanation), MediaProjection (per session).
- The app must behave correctly when GrapheneOS's Network permission is revoked for it — everything except the car link uses no network anyway; the car link uses the bound local network. Test this configuration.
- No Play Services calls anywhere, including transitive dependencies. Add a CI check that greps the merged dependency tree for `com.google.android.gms` and fails the build if found.

## Development phases — in order, each gated by its acceptance test

**Phase 0 — Test harness first.** Build `headunit-emulator`: a Linux desktop app (base it on openauto/aasdk) that emulates a *wireless* AA head unit: hosts a Wi-Fi AP (or, for CI, a loopback "fake transport" mode that skips real BT/Wi-Fi and exposes the same RFCOMM+TCP byte streams over local sockets), runs the handshake, renders received H.264, sends synthetic touch/key events, and streams a WAV file as fake car-mic audio. Every subsequent phase's acceptance test runs against this harness, automated in CI. *Accepted when:* CI runs a scripted session — handshake, receive 10 s of video, inject 5 touches, stream mic audio — against a stub Headway client, green.

**Phase 1 — Handshake.** BT RFCOMM exchange (real + fake transport), Wi-Fi join with bound network, TCP, AAP version + TLS handshake, service discovery. *Accepted when:* Headway on a real phone completes discovery against the emulator over actual BT + Wi-Fi, and in CI over fake transport, 20/20 consecutive attempts.

**Phase 2 — Video out.** Static test pattern, then MediaProjection mirroring, negotiated resolution, foreground-service lifecycle. *Accepted when:* emulator displays live phone screen at ≥25 fps for 10 minutes without a stall >1 s.

**Phase 3 — Input.** Touch transform + accessibility gesture injection, key events. *Accepted when:* scripted emulator touches operate a real third-party app (e.g., open an app from the launcher grid, scroll a list, type via drag on keyboard) hands-free.

**Phase 4 — Audio + focus.** Speech/system channels, focus signaling, A2DP coexistence logic. *Accepted when:* emulator receives a spoken TTS prompt over the speech channel while a music app plays over (simulated) A2DP, with correct duck/resume messages on the wire.

**Phase 5 — Voice.** AV-input channel, Vosk pipeline, command engine. *Accepted when:* WAV-injected "open calculator" through the emulator's fake mic launches the calculator on the phone with no network access, end-to-end under 2 s after end of speech.

**Phase 6 — Reconnection, polish, packaging.** Auto-reconnect, settings UI, launcher customization, per-head-unit quirk config file, F-Droid-compatible reproducible release build, README with full setup instructions for a GrapheneOS user and a "first connect to a real car" checklist + debug log capture (`adb`-free, in-app log export) so real-Malibu issues can be diagnosed from logs alone. *Accepted when:* all CI suites green, release APK builds reproducibly, docs complete.

Real-car validation is the one step you cannot perform yourself. Compensate: make the protocol layer log every frame (behind a debug flag, with TLS keys exportable in debug builds only) so a single drive's log from the user is enough to fix incompatibilities.

## Working rules for the agent

- Work phase by phase; within a phase, commit small and run the harness constantly. Keep `PROGRESS.md` updated with phase status and next action — but updating it is never a substitute for doing the next action.
- Write tests before or alongside protocol code: unit tests for framing/crypto with captured byte fixtures, integration tests against the emulator's fake transport.
- When the references conflict or a head unit behavior is unknown, implement the aasdk-documented behavior, make it configurable, and log loudly.
- Prefer boring technology. Kotlin + coroutines, CMake for native, protobuf-lite from checked-in `.proto` files generated at build time. No experimental toolchains.
- Every TODO left in code must have a matching entry in `BLOCKERS.md` or it doesn't get committed.
- Do not add features outside this spec (no CarPlay, no wired mode, no cloud anything, no account system).

## Definition of Done

- [ ] All six phase acceptance tests pass in CI, plus real-hardware Phase 1–5 runs against the emulator over genuine BT + 5 GHz Wi-Fi.
- [ ] Fresh GrapheneOS profile → install APK → follow README → connected to the emulator and mirroring within 10 minutes, no adb, no Play services.
- [ ] Voice command round-trip works fully offline (airplane mode + BT/Wi-Fi).
- [ ] App survives: screen lock, 30 min continuous session, BT toggle, Wi-Fi loss and recovery, emulator reboot — reconnecting automatically each time.
- [ ] `docs/protocol-notes.md` documents every constant with source citations; `docs/adr/` records major decisions; `BLOCKERS.md` is empty or contains only items with shipped workarounds.
- [ ] GPLv3 licensing complete, dependency license audit passes, no GMS dependencies, reproducible release build documented.

When every box is checked, produce `RELEASE-NOTES.md` and stop. Not before.

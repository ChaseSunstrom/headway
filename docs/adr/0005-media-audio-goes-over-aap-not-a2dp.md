# ADR 0005 — Media audio goes over AAP, not Bluetooth A2DP

**Status:** Accepted. **Reverses a decision recorded in `CLAUDE.md` L75**, on
evidence that did not exist when that line was written.

## Context

`CLAUDE.md` gives an explicit instruction:

> Third-party media audio: do **not** attempt playback capture as the primary
> path (`AudioPlaybackCapture` is opt-out-able and adds latency). Default
> strategy: instruct/steer media audio over the car's normal Bluetooth A2DP
> link, **which coexists with the AAP session**. Implement AAP media-audio
> channel support behind a settings toggle for apps that allow capture.

The reasoning is sound and the conclusion is wrong, because the premise —
coexistence — is false on the target vehicle.

Two independent sources say so.

**The driver.** Asked directly whether music from a phone app still comes out of
the car speakers while Headway is projecting: no. The car goes silent.

**A capture of real Android Auto against the same head unit**
(Gearhead 172662634, `myChevrolet` / `CC:88:26:BF:18:EB`). Google's own client
does not merely fail to use A2DP while projecting — it tears it down on purpose,
retries when it finds it still alive, and logs each step:

```text
L564   W CAR.BT.SVC.LITE: disabling A2dp route while in projection
L1374  W CAR.BT.SVC.LITE: A2DP playing while in projection. Trying disabling
L1464  W CAR.BT.SVC.LITE: A2DP playing while in projection. Trying disabling
L3136  ANDROID_AUTO_BLUETOOTH_A2DP_DISCONNECTED
```

and then streams third-party music over the AAP media channel instead, by name:

```text
L3241  onAudioFocusGrant … packageName: app.symfonik.music.player, clientUid: 10211
L3246  Sending AUDIO_FOCUS_GAIN to HU for app app.symfonik.music.player
L3249  … AUDIO_FOCUS_STATE_GAIN
L3252  enabling stream: MEDIA
L3264  bottom half starts streaming
```

getting the audio by capturing playback, which is exactly the mechanism
`CLAUDE.md` steers away from — one privilege level up:

```text
L725  CAR.AUDIO.Policy: Registering audio policy
L742  V android.media.AudioRecord: Will record from REMOTE_SUBMIX at full fixed volume
```

Telephony is the opposite case and confirms the shape of the rule: Gearhead
*keeps* `STATE_HFP_CONNECTED` (L3124) throughout. Voice calls stay on Bluetooth;
media does not.

## Decision

**Third-party media audio travels over the AAP media-audio channel by default**,
sourced from `AudioPlaybackCapture` on the `MediaProjection` Headway already
holds for video. The `mediaAudioOverAap` quirk survives with its default
flipped to `true`, so a head unit that genuinely keeps A2DP alive can be told to
leave music on Bluetooth.

Telephony stays on HFP, unchanged and for a now-evidenced reason.

## Consequences

- **The two stated objections are real and both survivable.** Latency: the same
  capture shows Google's privileged pipeline settling at 44 ms and later 52 ms
  (L3268, L3407), so the extra hop is inaudible for music, though it would
  matter for A/V sync. Opt-out: genuine. `android:allowAudioPlaybackCapture`
  defaults to true for apps targeting API 29 or later, but DRM-sensitive players
  disable it, and a player that opts out produces a perfectly healthy stream of
  digital silence. Headway logs the capture policy explicitly rather than going
  quiet, because those two failures are otherwise identical from the driver's
  seat.
- **Audio focus becomes load-bearing.** The capture shows the car answering
  `LOSS` before the phone asks and `STATE_GAIN` after, with Gearhead enabling
  the stream only then. Sending PCM without focus is sending into a muted sink.
  Headway therefore takes focus when music is playing and releases it after
  three seconds of silence — holding it mutes the car's own radio, and releasing
  on the first silent buffer would make the head unit switch source at every
  track change.
- **`AudioChannel.transmits` was already the gate.** It refuses media on the
  A2DP route and `requireTransmitting` throws out of `sendSetup`, so nothing
  had to be loosened — only the route had to be chosen correctly, and the quirk
  that was supposed to choose it turned out to be read by nothing at all.
- **The capture is a tap, not a redirect.**
  `AudioPlaybackCaptureConfiguration.Builder` exposes matching and exclusion
  filters and no mute. The audio still plays wherever it was playing. While the
  car has taken over the route that is inaudible; with the phone's speaker
  active the driver hears it twice.
- **Headway's own uid is excluded from the capture**, or its spoken replies
  would be captured and sent back underneath themselves.

## What still needs a real car to settle

Which of the driver's actual apps opt out of playback capture. `app.symfonik`
is the player in the Gearhead capture and is therefore the first to test.
Tracked in `BLOCKERS.md`.

## Sources

- Real Android Auto capture, Gearhead 172662634, against a 2021 Chevrolet
  Infotainment 3 head unit — line numbers cited inline above.
- `CLAUDE.md` L75, the instruction this reverses.
- `references/openauto/openauto/Service/ServiceFactory.cpp` L185-L186 — media
  configured as `(2, 16, 48000)`, corroborating the stereo/48 kHz shape read
  from the capture at L755.
- `android.media.AudioPlaybackCaptureConfiguration`,
  `android.media.AudioRecord.Builder.setAudioPlaybackCaptureConfig` — verified
  present in `/opt/android-sdk/platforms/android-35/android.jar`.

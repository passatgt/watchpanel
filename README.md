# WatchPanel

Turn an old Android tablet into a wall panel that shows a **live camera feed** and a
**web dashboard** at the same time.

Built for and tested on a Samsung Galaxy Tab A 2016 (SM-T280) running **Android 5.1.1**
— a 1.3 GHz Cortex-A7 with 1.5 GB RAM. It is deliberately built to work on hardware
that modern kiosk apps have left behind.

- **Camera pane** — live RTSP with hardware H.264 decode, sub-second latency, and
  automatic reconnect. Any RTSP source; nothing is vendor-specific.
- **Feed buttons** — switch between cameras. Hidden automatically with only one.
- **Web pane** — any URL: Home Assistant, Grafana, a weather page, or your own.
- **Presence dimming** — the front camera wakes the screen when someone approaches,
  using frame-differencing rather than an ML model.
- **Kiosk mode** — becomes the launcher, survives reboot, and can be switched off
  again from inside the app.
- **Portrait or landscape** — one layout tree, set to match your bracket, with the
  camera above/left of the web pane or below/right of it.
- **Tap the picture** to toggle sound. Muted by default; the state is announced
  briefly on screen, since there is no permanent speaker icon.

![WatchPanel on the wall](docs/wall.jpg)

*A Galaxy Tab A 2016 in portrait: dashboard on top, live camera below.*

## Build

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Builds out of the box with debug signing. For release keys, create
`keystore/signing.properties` (gitignored):

```properties
storeFile=keystore/my.jks
storePassword=...
keyAlias=...
keyPassword=...
```

## Why the code looks like this

| Constraint | Consequence |
|---|---|
| SC8830 has **no HEVC decoder** | libVLC, not ExoPlayer, so there is a software fallback. H.264 decodes in hardware via `OMX.sprd.h264.decoder`. |
| API 22 predates APK Signature Scheme v2 | `enableV1Signing true` is mandatory — a v2-only APK will not install. |
| `targetSdk 22` on purpose | Keeps install-time permissions; no runtime grant flow for the camera. |
| 1.3 GHz Cortex-A7 | Presence detection is luma frame-differencing, well under 1 ms/frame at 320×240. ML Kit would manage a few fps and pin a core. |
| No AppCompat | Native `Theme.Holo.NoActionBar.Fullscreen`; only libVLC's transitive `androidx.annotation` is pulled in. |

## Settings

Reached with the gear icon in the corner of the camera pane. Everything lives in
one JSON blob in SharedPreferences, so it survives app upgrades.

### Camera feeds

Name and URL per button; add and remove rows freely. Any URL libVLC can open
works — RTSP is what the app is shaped around, and what gives sub-second latency.
Reolink cameras use:

```
rtsp://user:pass@HOST:554/h264Preview_01_main   full resolution
rtsp://user:pass@HOST:554/h264Preview_01_sub    lower resolution
```

The button strip hides itself automatically when only one feed is configured.

### Dashboard

| Setting | Notes |
|---|---|
| **Web page URL** | Anything the WebView can render — Home Assistant, Grafana, your own page |
| **Auto-reload interval** | Seconds; `0` disables. Set `0` for Home Assistant, which updates over a WebSocket and only pays for the reload |
| **Ignore SSL certificate errors** | For sites whose chain the device's trust store cannot build. Enable only for a host you control — see *Notes for old devices* |

### Layout

| Setting | Notes |
|---|---|
| **Orientation** | Portrait, Landscape or Auto. A wall bracket has one orientation; Auto lets a knock rotate the display |
| **Camera position** | Top/left or bottom/right of the web pane |
| **Camera pane size** | Percent of the screen: height in portrait, width in landscape |
| **Scale video to fill the pane** | Crops the overflow so the picture covers the pane. Off letterboxes the whole frame. A 16:9 feed in a 4:3-ish pane loses noticeable width when on |
| **Video zoom** | `100` leaves scaling to libVLC. Higher zooms explicitly, relative to the source's native size — the fallback for streams that never report their geometry, where the automatic fit has nothing to compute from |

### Button colours

Ten swatches plus a hex field with a live preview, for the selected feed button
and the others. Label text flips between black and white automatically based on
the background's luma, so light colours stay readable.

### Screen & presence

| Setting | Notes |
|---|---|
| **Dim after** | Seconds of no presence before dimming; `0` keeps the screen on permanently |
| **Awake / dimmed brightness** | 0–100. Dimmed `0` is effectively off — see *How dimming works* |
| **Wake on front-camera motion** | The camera is opened only while dimmed, and released the moment the screen wakes |
| **Motion pixel threshold** | Per-pixel luma delta counting as changed. Lower is more sensitive |
| **Motion area trigger** | Fraction of sampled pixels (per mille) that must change. Raise it if sensor noise wakes the panel on its own |
| **Stop the stream while dimmed** | Saves heat and bandwidth on a 24/7 device; costs a second or two on wake |

### Stream tuning

| Setting | Notes |
|---|---|
| **Network caching** | RTSP jitter buffer in ms. Lower means less lag and more stutter |
| **RTSP over TCP** | Recommended. UDP drops frames on busy Wi-Fi |

### Kiosk

**Run as home screen** makes the app the launcher, disables Back, and sends you
to Android's home-app picker. Turn it off in the same place to get the normal
home screen back — the `HOME` filter lives on a toggleable activity-alias
precisely so this does not require a reinstall.

## How dimming works

The screen is never actually turned off. `screenBrightness` goes to ~0 behind a black
overlay, so waking is instantaneous — no display power-on latency, no lock screen. It
is an LCD, so there is no burn-in risk from staying on.

The front camera is opened **only while dimmed** — the only time the answer matters —
and released the moment the screen wakes.

## Notes for old devices

- **Trust store**: Android 5.1's root CAs are from 2015. Sites chaining to newer roots
  may fail; the app has an opt-in SSL override for a host you control.
- **WebView version matters far more than the OS version.** Lollipop can run WebView up
  to ~95, which is the difference between "no modern JS" and "Home Assistant works".
- **Battery**: a permanently-charged old tablet will swell eventually. A smart plug on
  a duty cycle is the usual mitigation.

## How this was built

Vibecoded with [Claude Code](https://claude.com/claude-code). Claude wrote effectively
all of the code across one long session; I specified what I wanted, designed the
dashboard in Sketch, ran every build on the actual tablet, and said when things were
wrong.

## Licence

MIT — see [LICENSE](LICENSE).

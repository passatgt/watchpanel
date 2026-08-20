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
- **Portrait or landscape** — one layout tree, set to match your bracket.

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

## How dimming works

The screen is never actually turned off. `screenBrightness` goes to ~0 behind a black
overlay, so waking is instantaneous — no display power-on latency, no lock screen. It
is an LCD, so there is no burn-in risk from staying on.

The front camera is opened **only while dimmed** — the only time the answer matters —
and released the moment the screen wakes.

## Kiosk mode

The `HOME` intent-filter sits on a disabled `<activity-alias>` toggled at runtime, so
kiosk mode can be turned **off** from the app rather than requiring a reinstall on a
wall-mounted tablet.

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

Much of what shaped the result was discovered in that loop rather than planned up front
— that the WebView mattered far more than the OS version, that libVLC reports no stream
geometry through a bare `SurfaceView`, that every Hungarian nameday dataset omits the
leap day, that Android 5.1's trust store predates half the modern root CAs. Most of the
comments in this codebase exist to explain a constraint that was found the hard way.

## Licence

MIT — see [LICENSE](LICENSE).

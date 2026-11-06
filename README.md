# Dual Video Pixel

Record the **back and front cameras at the same time** on a Pixel phone, as one composited video or as two separate files. A from-scratch Android app (Kotlin, Camera2 + OpenGL ES, no third-party dependencies) that reproduces the *Dual Video* feature of the Xiaomi camera app.

- Package: `com.kurulabs.dualvideopixel`
- Min SDK 30, target/compile SDK 36
- Tested on a **Pixel 6 Pro (Android 17)** and a **Pixel 5 (Android 14)**, see [Tested](#tested). Other devices must expose a concurrent back + front camera pair (`CameraManager.getConcurrentCameraIds`).

## Features

- Three layouts: **Split** (Bagi), **Stack** (Tumpuk), **PiP** (default; drag the small window, tap it to swap)
- Swap which camera is the main one
- **1 file**: one 9:16 MP4 with the composed layout. **2 files**: one MP4 per camera (`_back`, `_front`)
- Pause / resume without timestamp gaps
- Resolution **720p / 1080p**, frame rate **24 / 30 / 60** (settings chip, top right)
  - 60 fps = back camera at 60, front camera at 30 (its HAL maximum); the front picture is redrawn at 60
  - 24 fps = 30 fps stream with frames dropped on a fixed schedule (average exact, spacing uneven)
  - Only rates the device actually offers are shown
- Smooth zoom slider for the back camera (0.7x ultra-wide to 4x), and a separate **Tele** mode
  - Tele turns the front camera off and binds the stream to the telephoto lens so zooming out never falls back to the main lens (the HAL does not allow the tele lens while the front camera is open)
- Videos are saved to `Movies/DualVideo` through MediaStore; the last video appears as a thumbnail
- Follows the system language: English (default) and Indonesian (`values-in`). Strings live in `res/values*/strings.xml`

## Build

Requires JDK 17 and the Android SDK (platform 36).

```sh
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Grant the camera and microphone permissions on first launch.

## How it works

Two independent Camera2 devices (back, front) stream into SurfaceTextures. One GL thread composes them and draws into the preview surface and into the recorder input surfaces (`MediaRecorder` with persistent input surfaces). Camera timestamps are rebased to the recorder clock so audio and video stay in sync, including across pause and the Tele switch.

Source files are in `app/src/main/java/com/kurulabs/dualvideopixel/`: `DualCamera` (devices and sessions), `GlCompositor` (EGL, composition, frame pacing), `Recorders` (MediaRecorder, MediaStore), `Scene` (layouts and settings), `Ui` and `MainActivity` (views and controls).

## Origin and scope

The feature set was derived by analysing the public Xiaomi camera APK (`com.android.camera` 4.5.002570.0) with [REA](https://github.com/morluto/rea). Findings, what was observed versus inferred, and what was **not** reproduced are in [`docs/REA-FINDINGS.md`](docs/REA-FINDINGS.md). No Xiaomi code or assets are included; this is an independent implementation.

Not reproduced: Xiaomi vendor session modes, snapshot while recording, grid, remote camera, H.265, front-camera mirroring.

## Tested

| Device | Android | Checked |
|---|---|---|
| Pixel 6 Pro | 17 | Seen on screen in earlier builds: dual camera, Split / Stack / PiP preview, swap, pause/resume, 720p/1080p, 24/30/60 fps, zoom slider, Tele mode, English and Indonesian UI. Recorded output checked: 1 file in PiP (latest build, with audio), and 2 files in an earlier build. With the latest build only dual camera, Tele on/off and that 1-file PiP recording were repeated |
| Pixel 5 | 14 | Dual camera (PiP), 1-file recording at 1080p, ultra-wide below 1x (back stream bound to the physical lens), thermal warning |

Not tested on any other device. The recorded frames of Split and Stack in 1-file mode have not been checked. A Pixel 5 has no telephoto lens, so the Tele button does not appear there.

## Known limitations

- Portrait only
- Tele mode pauses the stream for about 0.5 to 1 s while the cameras are reopened, so a recording made across the switch has a short gap
- Tele mode at 24/60 fps, 2-file mode at 720p and at 24/60 fps, switching lens while recording, long recordings, landscape and TalkBack have not been tested
- At 1080p60 some frames are dropped (measured about 55 to 60 fps)
- On a hot phone (Pixel 5 at thermal status 2 or higher) the audio track can come out shorter than the video; the app shows a warning from status 2. Correlation measured, cause not proven

## License

MIT, see [`LICENSE`](LICENSE).

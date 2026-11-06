# Xiaomi camera dual video: what REA showed, what DualVideo reproduces

Scope: only the dual-camera (dual video) feature of `com.android.camera` 4.5.002570.0
(`com.android.camera.dualvideo.*`). Static analysis only; the APK was never executed.

- Target: `com.android.camera_4.5.002570.0-450025700_minAPI29(armeabi-v7a)(nodpi)_apkmirror.com.apk`,
  sha256 `7d722ba11f702157ad6fe2b09b7b1b16c43139eb78bb1ec2eead3d64c9cdff3b`, 5 dex files, 13706 classes.
- Tooling: `rea-agents` 6.8.0 CLI (`inspect-android-package`, `search-android-classes`,
  `inspect-android-class`, `inspect-android-method`) with the JADX provider
  `jadx-headless-mcp-0.7.1-all.jar` (sha256 `6e5eacf5…ff75`, matches REA's pinned release hash).
- Full evidence IDs per call: `rea-evidence-ids.txt`. Names are partly obfuscated
  (`OooO00o`, `Oooo0`, …); meaning below comes from signatures and bodies, not names.

## Observed (decompiled code)

| # | Finding | Evidence (prefix) | Reproduced in |
|---|---|---|---|
| 1 | Feature lives in `com.android.camera.dualvideo` (51 classes: module, recorder, render, remote, util). | `ev_c81e9c69` | – |
| 2 | Default config is two items: main back camera `1X` and the front camera. `sortId` makes the back camera MAIN and the front SUB. | `ev_851ba5d0`, `ev_1dd78811` | `DualCamera.plan()` |
| 3 | Two independent Camera2 devices (module's own + `getSubCamera2Device()`), each with its own session; operating mode is VIDEO_BEAUTY for front, vendor mode 32784/32772 for back. | `ev_5ca37ea8`, `ev_06f4fc88`, `ev_02c81671`, `ev_a24ca405` | `DualCamera` (vendor modes not reproducible) |
| 4 | Preview size front 1280x720, back 1920x1080 (1440x1080 on some UI styles); video size 1920x1080; AE fps range 30..30 (24..24 on some platforms). | `ev_65cee749`, `ev_f9e6cdcb` | `DualConfig` |
| 5 | Each camera feeds its own `SurfaceTexture` (`AuxRenderSource.createSurfaceTexture`) wrapped as an external GL texture; `RenderManager` composes them. | `ev_38c6f1a0`, `ev_ac46bffa`, `ev_d3c8cadc` | `GlCompositor` |
| 6 | Layout geometry (`RegionHelper.getRenderAreaFor`): FULL = whole canvas; UP_FULL / DOWN_FULL = top / bottom half, full width; UP / DOWN = centred half-width tiles with a 5 px seam; MINI = 0.3333 x 0.3333 of the canvas at a draggable position. | `ev_91828645`, `ev_5fef7ea4`, `ev_b2f3b8b0` | `SceneLayout.rectFor` |
| 7 | Grid mode (`PATCH_0..10`) tiles more than two cameras in 2 or 3 columns. | `ev_ff636eb1` | not reproduced (two cameras only) |
| 8 | `RecordType` is `MERGED` or `STANDALONE`. MERGED starts one recorder (MAIN id); STANDALONE starts two (MAIN + SUB, or MAIN + REMOTE). | `ev_f1a725c4`, `ev_eb755ad4`, `ev_1074e646` | `RecordType`, `MultiRecorderManager` |
| 9 | `MiRecorder`: `MediaRecorder`, video source SURFACE, audio MIC, `MediaCodec.createPersistentInputSurface()` as input, `CamcorderProfile.get(0, 6)` (1080p), size set as `(height, width)`, H.264 or H.265 per setting, output through a MediaStore `VideoFile`. | `ev_a116cc57`, `ev_7db946b6` | `MiRecorder` |
| 10 | Recording draw: one recorder gets the composed item list; two recorders each get their source drawn full-frame, each on its own `RenderHandler` EGL context. | `ev_61ccf988`, `ev_2519b3f2`, `ev_9bf62bb8` | `GlCompositor.render` (single context, deviation) |
| 11 | Stop runs all recorders in parallel (`Observable.zip`) and succeeds only if all do. `MultiRecorderManager` also exposes `pauseRecorder`/`resume` (bodies not decompiled). | `ev_289db94d`, `ev_700f5c99` | `MultiRecorderManager` |

## Inferred (not directly shown by the decompilation)

- Which `switch` case in `getRenderAreaFor` maps to which `LayoutType` was read from the rectangle
  geometry, not from the (unresolved) `$SwitchMap` table.
- Output canvas is portrait 1080x1920. `RenderUtil.OUTPUT_SIZE` could not be decompiled
  (`inspect_android_method` on `RenderUtil.<clinit>` failed); 1080x1920 follows from item 9.
- Mini window default position and clamp range: `PosData.mValidMiniRect` is set elsewhere, so the
  default (top-right) and clamp (whole canvas) are design choices.
- Draw order: the full-frame layout is drawn first so the mini window ends up on top.

## Unknown / not reproduced

- Vendor HAL operating modes (32784, 32772, VIDEO_BEAUTY) and any Xiaomi-only camera tags.
- Remote (second device) camera, setup wizard, snapshot during recording, grid mode, blur/animated
  transitions, face-beauty, karaoke/audio-monitor, H.265 selection.
- Runtime behaviour of the Xiaomi app (never executed).

## Verified on the Pixel 6 Pro (Android 17)

- Both cameras open at once: `dumpsys media.camera` shows Device 0 and Device 1 held by `com.kurulabs.dualvideopixel`.
- Preview (screenshots over adb): Split, Stack (two centred tiles), Swap (exchanges the two tiles), PiP, dragging the mini window, tapping the mini window to swap.
- A recorded MERGED frame (Split) shows the same composition as the preview. Stack/PiP were not compared frame-by-frame in recordings.
- MERGED: one MP4, 1080x1920 H.264 + AAC, composite of both cameras, pause/resume leaves no PTS gap.
- STANDALONE: two MP4s (`_back`, `_front`), each 1080x1920 H.264 + AAC, one camera per file.

## Deviations worth knowing

- Camera frame timestamps are `CLOCK_BOOTTIME`; a MediaRecorder surface input wants
  `CLOCK_MONOTONIC`. Writing raw timestamps produced a corrupt start time (2^31 ticks) and rebasing to
  zero made `stop()` fail with -1007. `GlCompositor` converts boottime to monotonic.
- The SurfaceTexture transform already carries the sensor rotation on this device; adding a
  rotation by `SENSOR_ORIENTATION` rotated the image sideways.
- Front image is not mirrored (`DualConfig.MIRROR_FRONT`).

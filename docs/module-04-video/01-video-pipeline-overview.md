# Module 04 — Lesson 01: Video Pipeline Overview

> **Module:** 04 — Video | **Level:** Intermediate | **Prerequisite:** [Module 02 — Preview](../module-02-preview/)

---

## How Video Recording Works in Camera2

Unlike still photography which captures one or a few discrete full-resolution frames, video recording streams continuous frames to a hardware video encoder at constant intervals (e.g., 30 fps or 60 fps).

In Camera2, this is achieved by delivering frames to multiple output surfaces simultaneously:
1. **Preview Surface**: The `SurfaceTexture` / `TextureView` on screen for user composition.
2. **Recording Surface**: The persistent input surface produced by `MediaRecorder.getSurface()` or `MediaCodec.createInputSurface()`.

```
                        ┌───────────────────────────────┐
                        │       Camera Device / HAL     │
                        └───────┬───────────────┬───────┘
                                │               │
                    Preview     │               │  Recording
                    Frames      ▼               ▼  Frames
                        ┌──────────────┐ ┌──────────────┐
                        │ TextureView  │ │ MediaRecorder│
                        │   Surface    │ │Input Surface │
                        └──────┬───────┘ └──────┬───────┘
                               │                │
                               ▼                ▼
                           User View      Hardware Encoder
                                           (H.264 / HEVC)
                                                │
                                                ▼
                                           MP4 Container
```

---

## Session Configuration for Video

In Camera2, video recording requires configuring a capture session with both the preview and recorder surfaces:

```kotlin
// Prepare MediaRecorder and get its input surface
mediaRecorder.prepare()
val recorderSurface = mediaRecorder.surface

// Configure session with both targets
val surfaces = listOf(previewSurface, recorderSurface)
cameraDevice.createCaptureSession(surfaces, sessionCallback, backgroundHandler)
```

Once the session is created, repeating requests are dispatched using the `TEMPLATE_RECORD` template:
```kotlin
val recordRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
    addTarget(previewSurface)
    addTarget(recorderSurface)
    // Enable video stabilization if supported
    set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
}
session.setRepeatingRequest(recordRequestBuilder.build(), captureCallback, backgroundHandler)
```

---

## The Video Recording Lifecycle in OpenKamera

Coordinating `MediaRecorder` state with Camera2 session state requires careful handling to prevent crashes or corrupted video files.

In OpenKamera, the `MediaRecorder` is owned by the legacy flow: [`Preview.kt`](../../app/src/main/java/com/hightechif/openkamera/preview/Preview.kt) configures, starts, pauses and stops it, and [`CameraController2.kt`](../../app/src/main/java/com/hightechif/openkamera/cameracontroller/CameraController2.kt) owns the capture session. [`Camera2VideoPipeline.kt`](../../app/src/main/java/com/hightechif/openkamera/cameracontroller/Camera2VideoPipeline.kt) is a small set of **hooks** that `Preview` calls around that lifecycle:
1. **Pre-prepare hook** (`initVideoRecorderPrePrepare`): plays the start cue and hands the recorder to the pipeline before `MediaRecorder.prepare()`.
2. **Post-prepare hook** (`initVideoRecorderPostPrepare`): builds the `TEMPLATE_RECORD` request and creates the video capture session that attaches `videoRecorderSurface` (`createVideoSession`).
3. **Video snapshot** (`createVideoSnapshotRequest`): builds the `TEMPLATE_VIDEO_SNAPSHOT` still request used while a recording is running.
4. **Start, pause/resume and stop** stay in `Preview`: it calls `mediaRecorder.start()`, the API 24+ pause/resume, and `mediaRecorder.stop()`, then restores `TEMPLATE_PREVIEW`.

---

## Next Steps

Explore codec selections, bitrate calculations, and slow-motion high-speed recording in [Lesson 02 — MediaRecorder & Codecs](./02-mediarecorder-and-codecs.md).

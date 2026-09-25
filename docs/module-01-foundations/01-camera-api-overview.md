# Module 01 — Lesson 01: Camera API Overview

> **Module:** 01 — Foundations | **Level:** Intermediate | **Prerequisite:** Basic Android knowledge

---

## The Two Camera APIs

Android has had two distinct camera APIs throughout its history. Understanding why both exist — and which one to use — is the starting point for any camera app.

### Camera1 (deprecated)

`android.hardware.Camera` (commonly called **Camera1**) was the original Android camera API, available from API 1. It follows a simple model:

- You open a `Camera` object by ID (front/back)
- You set parameters via a `Camera.Parameters` bundle
- You pass it a `SurfaceHolder` for preview
- You call `takePicture()` and receive a JPEG callback

**What it lacks:**
- No manual exposure control (ISO, shutter speed)
- No per-frame metadata (no way to read actual ISO or exposure time used)
- No RAW capture support
- No synchronous request/response model — parameters are applied globally and asynchronously
- Performance problems on modern hardware (low-level HAL1 abstraction)

Camera1 was **deprecated in API 21 (Android 5.0)** and is not recommended for new development. However, it still runs on all devices, which is why you may encounter it in legacy codebases.

### Camera2

`android.hardware.camera2` was introduced in **API 21** and represents a fundamentally different design philosophy:

- Every frame is controlled by an explicit **`CaptureRequest`** — a bundle of settings applied to exactly that frame
- Results come back as **`TotalCaptureResult`** — the actual settings the HAL used for that frame (which may differ from what you requested)
- Captures happen inside a **`CameraCaptureSession`** — a negotiated session with a fixed set of output surfaces
- The API exposes the camera as a **pipeline**, not a stateful object

This design unlocks:
- ✅ Per-frame manual ISO, shutter speed, aperture (on supported hardware)
- ✅ RAW capture (`ImageFormat.RAW_SENSOR`)
- ✅ Zero-shutter-lag (ZSL) pipelines
- ✅ High-speed capture sessions for slow motion
- ✅ Camera Extensions (night mode, portrait mode via vendor algorithms)

---

## Why OpenKamera Uses Camera2

OpenKamera uses Camera2 exclusively (via [`CameraController2.kt`](../../app/src/main/java/com/hightechif/openkamera/cameracontroller/CameraController2.kt)) for several reasons:

1. **Manual controls** — Features like manual ISO, shutter speed, and white balance temperature require Camera2's per-frame request model. Camera1 cannot expose these.

2. **RAW capture** — DNG output requires `ImageFormat.RAW_SENSOR`, only available in Camera2.

3. **Reactive metadata** — OpenKamera streams real-time frame metadata (actual ISO used, focus state, exposure time) into Kotlin flows. This requires `TotalCaptureResult` callbacks, a Camera2 concept.

4. **Future-proofing** — Android's continued camera investment (CameraX, Camera Extensions) is built on Camera2's HAL3 foundation.

> **Note on Camera1:** OpenKamera keeps the Camera1 controller inherited from Open Camera as an isolated **fallback** for devices whose Camera2 support is too weak to rely on (any camera at the `LEGACY` level). Camera2 is the default everywhere else. See [Choosing Camera1 or Camera2](#choosing-camera1-or-camera2-in-openkamera) below.

---

## Camera1 vs Camera2 at a Glance

| Feature              | Camera1           | Camera2                    |
|----------------------|-------------------|----------------------------|
| API Level introduced | 1                 | 21                         |
| Deprecated           | API 21            | Active                     |
| Manual ISO / shutter | ❌                 | ✅                          |
| Per-frame metadata   | ❌                 | ✅ (`TotalCaptureResult`)   |
| RAW capture          | ❌                 | ✅                          |
| Request model        | Global parameters | Per-frame `CaptureRequest` |
| ZSL support          | ❌                 | ✅                          |
| High-speed sessions  | ❌                 | ✅                          |

---

## Choosing Camera1 or Camera2 in OpenKamera

Android devices report how complete their Camera2 support is through a **hardware level**:

| Hardware level     | What it means                                                                                                                                      |
|--------------------|----------------------------------------------------------------------------------------------------------------------------------------------------|
| `FULL` / `LEVEL_3` | The complete Camera2 feature set: manual controls, per-frame metadata, RAW (device permitting)                                                     |
| `LIMITED`          | Camera2 works, with some features missing                                                                                                          |
| `LEGACY`           | Camera2 is only a compatibility layer on top of the old Camera1 driver. Manual controls and RAW are mostly unavailable and drivers are often buggy |

OpenKamera decides once, at start-up, which API to use:

```
 any camera with LIMITED or better support?
        │
        ├── no ──────────────────────────────────────────►  Camera1   (reason: no-LIMITED-camera)
        │
        └── yes ── has the user chosen an API in Settings?
                        │
                        ├── "Original camera API" ───────►  Camera1   (reason: user-preference)
                        ├── "Camera2 API" ───────────────►  Camera2   (reason: user-preference)
                        │
                        └── no choice stored ── is EVERY camera LIMITED or better?
                                                    │
                                                    ├── yes ────►  Camera2   (reason: default)
                                                    └── no  ────►  Camera1   (reason: legacy-camera-present)
```

- **Why not always use Camera2?** On a `LEGACY` camera, Camera2 adds a translation layer without adding features. Using Camera1 directly is simpler and more reliable there.
- **Why does one LEGACY camera decide for the whole phone?** Some phones have a capable back camera and a `LEGACY` front camera. The app uses one API for all cameras (switching APIs per camera would mean two camera clients), so by default it picks the one that is safe for every camera. Upstream Open Camera makes the same call. Users who want Camera2 on the capable camera can choose it in Settings.
- **Why is Camera2 the default when every camera supports it?** Manual controls, RAW, bracketing and the live metadata streams all depend on it.
- **What the user sees on Camera1:** a one-time message ("Using the original camera API (no manual controls or RAW)"), and a reduced set of controls.
- **The "Camera API" setting** appears only on devices where at least one camera supports Camera2. It shows the API currently in use, and changing it restarts the app. Just opening Settings never switches the API.
- The startup log records the decision, for example `API=Camera1 reason=legacy-camera-present`.

Where to read the code:
- The rule: [`CameraApiSelection.kt`](../../app/src/main/java/com/hightechif/openkamera/preferences/CameraApiSelection.kt)
- Where it is applied: `Preview.openCameraCore` in [`Preview.kt`](../../app/src/main/java/com/hightechif/openkamera/preview/Preview.kt) creates either `CameraController2` or [`CameraController1`](../../app/src/main/java/com/hightechif/openkamera/cameracontroller/CameraController1.kt)
- Camera enumeration for the fallback: [`CameraControllerManager1.kt`](../../app/src/main/java/com/hightechif/openkamera/cameracontroller/CameraControllerManager1.kt)

---

## What's Next

Now that you know *which* API and *why*, the next lesson explains the core objects in Camera2 and how they relate to each other.

👉 [Lesson 02 — Camera2 Session Model](./02-camera2-session-model.md)

---

*Part of the [OpenKamera Curriculum](../README.md)*

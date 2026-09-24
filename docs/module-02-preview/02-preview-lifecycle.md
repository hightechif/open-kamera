# Module 02 — Lesson 02: Preview Lifecycle

> **Module:** 02 — Preview | **Level:** Intermediate | **Prerequisite:** [Lesson 01 — Surface & SurfaceTexture](./01-surface-and-surfacetexture.md)

---

## Why Camera Lifecycle Is Hard

The camera is a shared, exclusive hardware resource. Only one app can hold it at a time. This means your app must:
- **Acquire** the camera when it becomes visible
- **Release** it immediately when it goes to the background
- **Handle** interruptions (phone calls, notifications, other apps)
- **Recover** gracefully from errors and disconnections

Doing this correctly requires tight coordination with Android's Activity lifecycle.

---

## The Full Preview Lifecycle

```
Activity created
      │
      ├─ TextureView ready? ──No──▶ Wait for SurfaceTextureListener.onSurfaceTextureAvailable()
      │
      │  (both Activity resumed AND surface available)
      ▼
openCamera()
      │
      │ (background thread — camera open is blocking)
      ▼
CameraDevice.StateCallback.onOpened()
      │
      ▼
createCaptureSession()
      │
      │ (HAL negotiates pipeline)
      ▼
CameraCaptureSession.StateCallback.onConfigured()
      │
      ▼
setRepeatingRequest(TEMPLATE_PREVIEW)
      │
      ▼
  ┌─────────────────────────────────────┐
  │         Preview running             │
  │  (HAL delivers frames continuously) │
  └─────────────────────────────────────┘
      │
      │ Activity pauses (home, phone call, etc.)
      ▼
closeCamera()
      │
      ├─ Stop repeating request
      ├─ Close CameraCaptureSession
      └─ Close CameraDevice
```

### The two-condition start

Notice the "both AND" in the diagram: the camera can only open after *both* the Activity has resumed *and* the `TextureView` surface is available. These two events don't happen in a fixed order — the surface might be ready before `onResume()`, or `onResume()` might fire before the surface is ready.

OpenKamera handles this with a simple flag check in [`Preview.kt`](../../app/src/main/java/com/hightechif/openkamera/preview/Preview.kt): `openCamera()` is called from both `onResume()` and `onSurfaceTextureAvailable()`, but only proceeds if both conditions are true.

---

## Thread Safety Requirements

Camera callbacks (open, close, session configured, capture results) arrive on **background threads** — specifically a `Handler` attached to a `HandlerThread` that the camera subsystem creates. You must never:

- Call Android UI methods from a camera callback (will crash)
- Block a camera callback with heavy work (will stall the camera pipeline)

OpenKamera creates a dedicated camera background thread and handler in `CameraController2`, and posts UI updates back to the main thread explicitly.

---

## What Happens During Close

Closing the camera must happen in a specific order:

```
1. stopRepeating()          ──▶ stop the preview frame stream
2. abortCaptures()          ──▶ cancel any in-flight single captures
3. captureSession.close()   ──▶ release the session (async)
4. cameraDevice.close()     ──▶ release the hardware (async)
5. imageReader.close()      ──▶ release image buffer memory
```

Skipping steps or doing them in the wrong order causes either resource leaks (the camera stays open and blocks other apps) or crashes (accessing released objects).

OpenKamera wraps this sequence in coroutines to ensure it completes asynchronously without blocking the UI thread — see [Lesson 03 — Coroutines for Camera Async Work](./03-coroutines-camera-async.md) for how.

---

## Handling Camera Errors

Camera hardware can fail at any time:
- **`onError(ERROR_CAMERA_DEVICE)`** — internal hardware error; recreate the session
- **`onDisconnected()`** — another app or the system took the camera; close gracefully
- **`onError(ERROR_CAMERA_IN_USE)`** — tried to open an already-open camera (logic error)
- **`onError(ERROR_MAX_CAMERAS_IN_USE)`** — system camera limit reached

OpenKamera maps these to a domain-level error state via the `engineStateFlow`, which the UI layer observes to show appropriate error messages.

---

## What's Next

The opening and closing described here all happen on background threads. The next lesson explains exactly how Kotlin Coroutines replaced the legacy `AsyncTask` to make this async work safe and structured.

👉 [Lesson 03 — Coroutines for Camera Async Work](./03-coroutines-camera-async.md)

---

*Part of the [OpenKamera Curriculum](../README.md)*

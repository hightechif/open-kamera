# Module 01 — Lesson 02: The Camera2 Session Model

> **Module:** 01 — Foundations | **Level:** Intermediate | **Prerequisite:** [Lesson 01 — Camera API Overview](./01-camera-api-overview.md)

---

## The Four Core Objects

Camera2 is built around four objects that form a pipeline. Understanding how they connect is the key to reading any Camera2 code.

```
CameraManager
     │
     │ openCamera()
     ▼
CameraDevice  ──────────────────────────────────────────────
     │                                                      │
     │ createCaptureSession()                               │
     ▼                                                      │
CameraCaptureSession                                        │
     │                                                      │
     │ setRepeatingRequest()   capture()                    │
     ▼                                                      │
CaptureRequest  ─────────────────────── built from ────────┘
     │           (submitted to session)
     │
     │  (HAL processes frame)
     ▼
TotalCaptureResult
     │
     └── Contains actual values used: ISO, exposure time,
         aperture, AF state, AWB state, etc.
```

---

## CameraManager

`CameraManager` is the system service that manages camera hardware. You obtain it once:

```kotlin
val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
```

Its main jobs:
- **List camera IDs** — `getCameraIdList()` returns strings like `"0"` (back), `"1"` (front)
- **Query capabilities** — `getCameraCharacteristics(id)` returns a `CameraCharacteristics` object describing what the camera hardware supports (zoom range, supported formats, focal lengths, etc.)
- **Open a camera** — `openCamera(id, callback, handler)` opens a connection to the hardware

`CameraCharacteristics` is read-only and represents the **static** capabilities of the camera — things that never change regardless of settings, like sensor size and supported output formats. OpenKamera reads characteristics at startup via [`Camera2Settings.kt`](../../app/src/main/java/com/hightechif/openkamera/cameracontroller/Camera2Settings.kt).

---

## CameraDevice

`CameraDevice` represents an open, exclusive connection to a physical camera. Only one app can hold this connection at a time — this is why camera apps must release the device when they go to the background.

```
CameraDevice states:
  opened ──────────────────────────── active
    │                                    │
    │  createCaptureSession()            │  close()
    ▼                                    ▼
  session creating ──────────────── disconnected
```

You don't interact with `CameraDevice` directly for captures — it's only used to create sessions. Once a session exists, all capture work goes through the session.

Key failure mode: if another app (or the system camera) opens the camera while you hold it, `CameraDevice.StateCallback.onDisconnected()` is called and your device reference is invalidated. OpenKamera handles this via [`Camera2SessionManager.kt`](../../app/src/main/java/com/hightechif/openkamera/cameracontroller/lifecycle/Camera2SessionManager.kt).

---

## CameraCaptureSession

A `CameraCaptureSession` is a **negotiated contract** between your app and the camera hardware. When you create a session, you declare a fixed set of **output surfaces** — the destinations where camera frames will be written:

```kotlin
// Example: session with two outputs — preview surface and JPEG image reader
val outputs = listOf(
    OutputConfiguration(previewSurface),
    OutputConfiguration(imageReaderSurface)
)
device.createCaptureSession(SessionConfiguration(..., outputs, ...))
```

The HAL negotiates the internal pipeline around these outputs. **You cannot add or remove surfaces after session creation** — you must create a new session if the surface set changes (e.g., switching from photo mode to video mode).

### Repeating vs. single captures

Sessions support two capture modes:

| Mode | Method | Purpose |
|---|---|---|
| **Repeating** | `setRepeatingRequest()` | Continuously delivers frames (used for preview) |
| **Single capture** | `capture()` | One-shot (used for still photo) |

The preview loop runs as a repeating request. When you take a photo, a still-capture request is submitted as a single capture that interrupts or supplements the repeating stream.

---

## CaptureRequest

A `CaptureRequest` is an immutable bundle of settings that governs **exactly one frame** (or the repeating stream). You build it from a template:

```kotlin
val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
builder.set(CaptureRequest.JPEG_QUALITY, 95)
builder.addTarget(imageReaderSurface)
val request = builder.build()
```

**Templates** are starting-point presets provided by the HAL:

| Template | Use case |
|---|---|
| `TEMPLATE_PREVIEW` | Repeating preview — optimized for low latency |
| `TEMPLATE_STILL_CAPTURE` | Single photo — optimized for quality |
| `TEMPLATE_VIDEO_RECORD` | Repeating during video — stable frame rate priority |
| `TEMPLATE_VIDEO_SNAPSHOT` | Still photo during active video recording |
| `TEMPLATE_MANUAL` | Full manual control (ISO, shutter speed set explicitly) |

OpenKamera chooses the correct template based on capture mode in [`Camera2PhotoPipeline.kt`](../../app/src/main/java/com/hightechif/openkamera/cameracontroller/Camera2PhotoPipeline.kt).

---

## TotalCaptureResult

`TotalCaptureResult` is what the camera HAL sends back after processing a frame. It contains the **actual values used** — which may differ from what you requested, because the HAL's auto-exposure (AE) and auto-focus (AF) algorithms make their own adjustments.

```
You requested:  AE_MODE = AUTO, ISO = (any)
HAL responded:  actual ISO = 400, actual exposure = 1/60s, AF state = FOCUSED_LOCKED
```

Key fields OpenKamera reads from `TotalCaptureResult`:

| Key | What it tells you |
|---|---|
| `CaptureResult.SENSOR_SENSITIVITY` | Actual ISO gain applied |
| `CaptureResult.SENSOR_EXPOSURE_TIME` | Actual shutter speed (nanoseconds) |
| `CaptureResult.LENS_APERTURE` | Actual aperture (f-number) |
| `CaptureResult.CONTROL_AF_STATE` | Current autofocus state machine state |
| `CaptureResult.CONTROL_AE_STATE` | Current auto-exposure convergence state |

These values flow into OpenKamera's reactive metadata streams. See [Module 05 — Reactive Metadata Flows](../module-05-advanced/02-reactive-metadata-flows.md) for how this is implemented.

---

## The Full Picture

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Camera2 Data Flow                            │
│                                                                     │
│  ┌──────────────┐   openCamera()    ┌─────────────────────────┐    │
│  │ CameraManager│ ────────────────▶ │      CameraDevice        │    │
│  └──────────────┘                   └────────────┬────────────┘    │
│                                                  │                  │
│                                   createSession()│                  │
│                                                  ▼                  │
│                                   ┌─────────────────────────┐      │
│  ┌─────────────────────────┐      │   CameraCaptureSession   │      │
│  │      CaptureRequest      │ ───▶ │  (preview + capture)     │      │
│  │  TEMPLATE_STILL_CAPTURE  │      └────────────┬────────────┘      │
│  │  + target surfaces       │                   │                   │
│  └─────────────────────────┘                   │ HAL processes     │
│                                                  ▼                  │
│                                   ┌─────────────────────────┐      │
│                                   │    TotalCaptureResult    │      │
│                                   │  actual ISO, exposure,   │      │
│                                   │  AF state, AE state...   │      │
│                                   └─────────────────────────┘      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## What's Next

With the session model understood, you're ready to look at the practical lifecycle — how preview opens, runs, and closes in response to Android Activity events.

👉 [Lesson 03 — Android Permissions & Scoped Storage](./03-android-permissions.md)

---

*Part of the [OpenKamera Curriculum](../README.md)*

# Module 02 — Lesson 01: Surface & SurfaceTexture

> **Module:** 02 — Preview | **Level:** Intermediate | **Prerequisite:** [Module 01 — Foundations](../module-01-foundations/)

---

## What is a Surface?

In Android, a **`Surface`** is a buffer queue that sits between a producer (something that generates pixels) and a consumer (something that displays or processes those pixels). Think of it as a shared memory channel for image data.

```
Producer                Surface / BufferQueue               Consumer
────────                ─────────────────────               ────────
Camera HAL  ──pixels──▶  [buffer] [buffer] [buffer]  ──▶  Screen display
                                                           (SurfaceFlinger)
```

The camera doesn't know or care what happens to the pixels it produces — it just writes frames into the buffer queue. The consumer dequeues buffers and does something with them (display them, encode them, process them).

**Why this matters:** Camera2 requires you to declare `Surface` targets before opening a capture session. Every surface you add becomes a parallel output — the HAL writes each frame to all registered targets simultaneously.

---

## SurfaceTexture and TextureView

`SurfaceTexture` is a special type of Surface consumer that captures camera frames as **OpenGL ES textures**. This enables GPU-accelerated rendering of the camera feed.

**`TextureView`** wraps a `SurfaceTexture` and integrates it into Android's regular View hierarchy:

```
Camera HAL
    │
    │ produces frames
    ▼
SurfaceTexture (OpenGL ES texture)
    │
    │ available via
    ▼
TextureView
    │
    │ drawn in
    ▼
View hierarchy → SurfaceFlinger → Screen
```

**Why OpenKamera uses `TextureView`:**
- It lives in the normal View hierarchy, so it respects `alpha`, `rotation`, `scaleX/Y` transformations
- Overlays (focus rings, grid lines, histogram) can be drawn on top of it as regular Views
- It supports the `SurfaceTextureListener` to know when the surface is ready

---

## SurfaceView: The Alternative

`SurfaceView` is the older approach. It creates a **separate window** beneath the View hierarchy, punch-holed through the app's window:

```
App window     SurfaceView window (separate, below)
───────────    ──────────────────────────────────────
[   hole   ]  [  Camera frames render directly here  ]
[  content ]
```

**Tradeoffs vs TextureView:**

| | TextureView | SurfaceView |
|---|---|---|
| View transformations | ✅ Supported | ❌ Not supported |
| Overlay support | ✅ Easy (just add views on top) | ⚠️ Complex (must be above the hole) |
| Performance | ⚠️ Slightly lower (GPU copy) | ✅ Lower latency (direct rendering) |
| Hardware acceleration | Required | Not required |
| Z-order | In the view tree | Always below other views |

OpenKamera uses `TextureView` because the overlay rendering system (histograms, grids, focus rings) requires normal view stacking. The small performance difference is acceptable for this use case.

---

## How Camera2 Uses the Surface

When creating a `CameraCaptureSession`, you provide one or more `Surface` objects as outputs. OpenKamera's preview setup creates a surface from the `TextureView`'s `SurfaceTexture`:

```kotlin
// From Preview.kt — simplified concept
val surfaceTexture = textureView.surfaceTexture
surfaceTexture.setDefaultBufferSize(previewWidth, previewHeight)
val previewSurface = Surface(surfaceTexture)

// This surface is registered as a session output
// Every preview frame the HAL produces is written here
```

The surface size matters: if you set the wrong dimensions, the preview will be stretched or cropped. OpenKamera calculates the optimal preview size by querying `CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP` for supported output sizes.

---

## What's Next

Now that you know what a surface is and how it connects to the camera, the next lesson covers the lifecycle — what happens when the camera opens, runs, and closes.

👉 [Lesson 02 — Preview Lifecycle](./02-preview-lifecycle.md)

---

*Part of the [OpenKamera Curriculum](../README.md)*

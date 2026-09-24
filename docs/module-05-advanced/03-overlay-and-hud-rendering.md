# Module 05 — Lesson 03: Overlay & HUD Rendering

> **Module:** 05 — Advanced | **Level:** Intermediate | **Prerequisite:** [Lesson 02 — Reactive Metadata Flows](./02-reactive-metadata-flows.md)

---

## The Layered Viewfinder Architecture

Professional camera applications overlay a wide variety of compositional and technical HUD elements on top of the live video stream:
- Composition grids (rule-of-thirds, golden ratio, crosshairs).
- Real-time live luminance/RGB histograms.
- Horizon level and pitch/roll compass angles.
- Focus peaking highlights and zebra exposure striping.
- Crop guides (1:1 square, 16:9, anamorphic aspect guides).

To maintain 60 fps rendering without stalling camera capture pipelines, OpenKamera uses a **two-layer rendering strategy**:

```
┌────────────────────────────────────────────────────────┐
│             Top Layer: Overlay Canvas View             │
│   (DrawPreview & Modular Renderers: Grids, HUD, etc.)  │
├────────────────────────────────────────────────────────┤
│           Bottom Layer: Hardware TextureView           │
│        (Continuous Camera2 Stream Render Target)       │
└────────────────────────────────────────────────────────┘
```

The camera preview streams directly into the underlying hardware texture buffer via GPU OpenGL compositor, completely independent of the UI Canvas drawing loop.

---

## Modular Renderers Architecture

Rather than having a single massive drawing method inside `onDraw(canvas)`, OpenKamera splits HUD rendering into focused, testable components in [`ui/renderers/`](../../app/src/main/java/com/hightechif/openkamera/ui/renderers):

| Renderer | Responsibility |
|---|---|
| [`GridOverlayRenderer.kt`](../../app/src/main/java/com/hightechif/openkamera/ui/renderers/GridOverlayRenderer.kt) | Draws compositional grid lines (3x3, golden ratio, phi). |
| [`HistogramOverlayRenderer.kt`](../../app/src/main/java/com/hightechif/openkamera/ui/renderers/HistogramOverlayRenderer.kt) | Draws live histogram curves over a dark translucent backdrop. |
| [`HorizonAngleOverlayRenderer.kt`](../../app/src/main/java/com/hightechif/openkamera/ui/renderers/HorizonAngleOverlayRenderer.kt) | Draws artificial horizon level lines reacting to gyro/accelerometer data. |
| [`TelemetryHudOverlayRenderer.kt`](../../app/src/main/java/com/hightechif/openkamera/ui/renderers/TelemetryHudOverlayRenderer.kt) | Draws text badges for ISO, exposure time, battery, and free storage space. |
| [`CropGuideOverlayRenderer.kt`](../../app/src/main/java/com/hightechif/openkamera/ui/renderers/CropGuideOverlayRenderer.kt) | Draws aspect ratio letterboxing crop masks. |

All renderers share state through [`DrawPreviewContext`](../../app/src/main/java/com/hightechif/openkamera/ui/renderers/DrawPreviewContext.kt).

---

## Non-Blocking Rendering Performance Rules

1. **Zero Allocations in `onDraw()`**: Never instantiate `Paint`, `Path`, `RectF`, or temporary objects inside the draw loop. Pre-allocate all graphic objects during renderer initialization.
2. **Dirty Region Invalidation**: Only invalidate (`invalidate()`) the view when sensor values or frame metadata actually change.
3. **Offload Heavy Computations**: Histograms and focus peaking bitmaps are calculated in background worker threads or RenderScript/Intrinsics, then handed to the renderer as immutable arrays.

---

## Next Steps

Explore how device-specific bugs and manufacturer quirks are managed in [Lesson 04 — Device Quirks & Compatibility](./04-device-quirks-and-compat.md).

# Module 02 — Lesson 04: Gesture & Tap-to-Focus

> **Module:** 02 — Preview | **Level:** Intermediate | **Prerequisite:** [Lesson 03 — Coroutines for Camera Async Work](./03-coroutines-camera-async.md)

---

## What Tap-to-Focus Actually Does

When a user taps the camera viewfinder, they're telling the camera: "focus on this area of the scene." Under the hood, this translates into a Camera2 concept called **metering regions** — rectangular areas of the sensor that the auto-focus (AF) and auto-exposure (AE) algorithms use as their input.

The challenge: the user taps in **screen pixels**, but Camera2 works in **sensor pixels**. The two coordinate spaces are completely different and require a careful mapping.

---

## Coordinate Space Mapping

```
Screen coordinate space          Camera2 active array space
────────────────────────         ─────────────────────────────────
(0,0) ─────────────────▶         (left, top) ──────────────────────▶
  │   Screen pixels               │   Sensor pixels
  │   (e.g., 1080 × 2340)        │   (e.g., 4032 × 3024)
  ▼                               ▼
(width, height)                  (right, bottom)
                                 from CameraCharacteristics
                                 SENSOR_INFO_ACTIVE_ARRAY_SIZE
```

The active array is the full sensor area. The preview only shows a subset of it — cropped and scaled to fill the screen. So a tap at screen position `(x, y)` maps to a different point in the active array depending on:

1. The preview's aspect ratio vs. the sensor's aspect ratio
2. The zoom level (the "crop region" — a sub-rectangle of the active array)
3. Any rotation between sensor orientation and screen orientation

OpenKamera handles this coordinate transformation in the gesture handling layer within [`preview/gesture/`](../../app/src/main/java/com/hightechif/openkamera/preview/gesture/).

---

## MeteringRectangle

Camera2 represents focus/exposure regions as `MeteringRectangle` objects:

```kotlin
MeteringRectangle(
    x,          // left edge in active array coordinates
    y,          // top edge in active array coordinates
    width,      // width of the metering region
    height,     // height of the metering region
    weight      // 0–1000: how much weight this region gets vs others
)
```

For tap-to-focus, OpenKamera creates a small rectangle centered on the mapped tap point. The size of this rectangle is a fraction of the sensor area — large enough to capture the subject, small enough to focus precisely.

---

## The AF Trigger Sequence

Once the metering regions are set, Camera2 needs to run an autofocus scan. This is not instant — AF takes time (tens to hundreds of milliseconds). The sequence:

```
1. Set AF_REGIONS on the repeating request builder
   (tells the AF algorithm where to look)

2. Set AE_REGIONS on the repeating request builder
   (tells the AE algorithm where to meter for exposure)

3. Submit a single capture with:
   CONTROL_AF_TRIGGER = AF_TRIGGER_START
   (kicks off the AF scan)

4. Watch TotalCaptureResult.CONTROL_AF_STATE in callbacks:
   - AF_STATE_ACTIVE_SCAN      → scan in progress
   - AF_STATE_FOCUSED_LOCKED   → focus achieved ✅
   - AF_STATE_NOT_FOCUSED_LOCKED → could not focus ❌

5. Submit a single capture with:
   CONTROL_AF_TRIGGER = AF_TRIGGER_CANCEL
   (optional: returns to continuous AF mode)
```

OpenKamera maps these raw `AF_STATE_*` integers to the domain `FocusState` enum (`IDLE`, `SCANNING`, `FOCUSED_LOCKED`, `NOT_FOCUSED_LOCKED`), which the UI consumes to animate the focus ring.

---

## Why AE Regions Matter Too

It might seem odd that tapping to focus also changes the exposure metering region. But this is intentional: when a user taps on a subject, they usually want both the focus *and* the exposure to be optimized for that subject.

Imagine tapping on a person's face in a backlit scene — you want the face to be in focus *and* correctly exposed (not silhouetted). Camera2 allows AF and AE to have separate regions, but in practice, they're usually set to the same point for tap-to-focus.

---

## What's Next

Module 03 goes deeper into what happens after the user presses the shutter — the complete photo capture pipeline.

👉 [Module 03 — Photography: Lesson 01 — Photo Capture Pipeline](../module-03-photography/01-photo-capture-pipeline.md)

---

*Part of the [OpenKamera Curriculum](../README.md)*

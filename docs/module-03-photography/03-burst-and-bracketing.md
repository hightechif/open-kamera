# Module 03 — Lesson 03: Burst & Exposure Bracketing

> **Module:** 03 — Photography | **Level:** Intermediate | **Prerequisite:** [Lesson 02 — Flash & Precapture](./02-flash-and-precapture.md)

---

## What is Burst Capture?

Burst capture is the capability to shoot multiple photos in rapid succession by holding the shutter button or selecting a burst mode.

In Camera2, burst capture can be executed in two ways:
1. **Single Burst Request (`captureBurst`)**: Submitting a list of `CaptureRequest` objects in a single JNI call to the HAL. The camera hardware processes each request sequentially as quickly as possible.
2. **Sequential Requests**: Disconnecting or pacing requests individually across frames to allow specific parameter adjustments between shots.

---

## Exposure Bracketing (AEB)

**Exposure Bracketing** (Auto Exposure Bracketing) is the technique of taking multiple consecutive shots of the exact same scene at different exposure values (EV).

Typically, a bracket consists of:
- **Under-exposed shot** (-EV): Preserves highlight details (e.g. bright sky, clouds, neon signs).
- **Nominal shot** (0 EV): Standard balanced exposure.
- **Over-exposed shot** (+EV): Preserves shadow details in dark areas.

```
       [ -2 EV ]                 [ 0 EV ]                 [ +2 EV ]
   (Under-exposed)           (Standard scene)          (Over-exposed)
  Highlights intact        Balanced mid-tones        Shadow details visible
```

These bracketed frames serve as the essential raw material for **High Dynamic Range (HDR)** tone mapping and multi-frame noise reduction.

---

## Controlling Exposure Compensation in Camera2

Exposure Value (EV) adjustment in Camera2 is controlled by `CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION`:

```kotlin
// Retrieve step size from CameraCharacteristics
val step = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0.333f

// Calculate index for desired EV step
val evIndex = (targetEv / step).roundToInt()
builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evIndex)
```

---

## Coordinating Burst State in OpenKamera

Because burst captures arrive asynchronously through `ImageReader.OnImageAvailableListener` and capture callbacks, tracking how many frames were requested versus received requires robust synchronization.

In OpenKamera, this is encapsulated in [`Camera2BurstCoordinator.kt`](file:///Users/ridhanfadhilah/Public/Fadhil/mobile/android/studio-lab/project-open-camera/OpenKamera/app/src/main/java/com/hightechif/openkamera/cameracontroller/Camera2BurstCoordinator.kt):

- `initBurst(nBurst, isSingleRequest)`: Prepares the counters before dispatching capture requests.
- `onJpegReceived()` / `onRawReceived()`: Increments completed count as images arrive from the HAL.
- `isBurstJpegComplete()` / `isBurstRawComplete()`: Verifies whether the burst has finished.
- `reset()`: Safely restores initial state.

---

## Next Steps

Learn how bracketed images are aligned and fused into a single high-dynamic-range photo in [Lesson 04 — HDR Image Processing](./04-hdr-image-processing.md).

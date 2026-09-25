# Module 03 — Lesson 02: Flash & Precapture

> **Module:** 03 — Photography | **Level:** Intermediate | **Prerequisite:** [Lesson 01 — Photo Capture Pipeline](./01-photo-capture-pipeline.md)

---

## Flash Modes in Camera2

Camera2 controls flash through a combination of two keys in `CaptureRequest.Builder`:
1. `CaptureRequest.CONTROL_AE_MODE` (Auto-Exposure Mode)
2. `CaptureRequest.FLASH_MODE` (Flash Hardware Unit Mode)

| Conceptual Flash Mode | `CONTROL_AE_MODE` | `FLASH_MODE` | Notes |
|---|---|---|---|
| **Off** | `CONTROL_AE_MODE_ON` | `FLASH_MODE_OFF` | Standard auto-exposure without flash. |
| **Auto** | `CONTROL_AE_MODE_ON_AUTO_FLASH` | `FLASH_MODE_OFF` | Hardware determines if flash is needed based on ambient light. |
| **Always On** | `CONTROL_AE_MODE_ON_ALWAYS_FLASH` | `FLASH_MODE_OFF` | Flash fires on every still capture. |
| **Torch** | `CONTROL_AE_MODE_ON` | `FLASH_MODE_TORCH` | Constant LED torch illumination (used in video or constant preview assist). |

---

## Why Precapture Metering is Necessary

When flash fires, scene brightness increases drastically. If the sensor takes a photo with the exposure settings used in darkness, the resulting picture will be completely blown out (overexposed white).

Therefore, Camera2 requires an **AE Precapture Sequence**:
1. The camera fires a quick low-power pre-flash strobe.
2. The image signal processor (ISP) calculates the exact exposure time and sensor sensitivity (ISO) required for the full-power flash strobe.
3. The precapture trigger signals when AE has converged (`CaptureResult.CONTROL_AE_STATE_CONVERGED` or `FLASH_REQUIRED`).
4. Once converged, the still capture is taken with optimal exposure parameters.

```
Preview (Dark scene) ──► AE Precapture Trigger ──► Pre-flash fires ──► AE Converged ──► Full Flash Capture
```

---

## Fake Precapture Routine

On some Android hardware or when using torch mode as an artificial flash assist, the standard Camera2 AE precapture trigger (`CONTROL_AE_PRECAPTURE_TRIGGER_START`) can fail to converge or cause camera HAL lockups.

OpenKamera implements a **Fake Precapture Routine**:
1. Turn on the LED torch explicitly (`FLASH_MODE_TORCH`).
2. Wait a specific number of frames or milliseconds for the sensor auto-exposure routine to naturally converge under the new lighting.
3. Fire the still capture.
4. Turn off torch mode and restore preview state.

This logic is coordinated inside [`Camera2PhotoPipeline.kt`](../../app/src/main/java/com/hightechif/openkamera/cameracontroller/Camera2PhotoPipeline.kt).

---

## Code Reference in OpenKamera

- [`Camera2PhotoPipeline.kt`](../../app/src/main/java/com/hightechif/openkamera/cameracontroller/Camera2PhotoPipeline.kt):
  - `runPrecapture()`: Dispatches `CONTROL_AE_PRECAPTURE_TRIGGER_START`.
  - `runFakePrecapture()`: Activates torch mode and initializes frame count tracking.
  - `onCaptureResultReceived()`: Checks `CaptureResult.CONTROL_AE_STATE` for convergence.

---

## Next Steps

Explore how multiple frames can be captured in sequence with varying exposure parameters in [Lesson 03 — Burst & Bracketing](./03-burst-and-bracketing.md).

# Module 05 — Lesson 04: Device Quirks & Hardware Compatibility

> **Module:** 05 — Advanced | **Level:** Intermediate | **Prerequisite:** [Lesson 03 — Overlay & HUD Rendering](./03-overlay-and-hud-rendering.md)

---

## What is a Device Quirk?

The Android Camera2 API specification is technically standardized, but underlying implementations are authored by diverse chipmakers (Qualcomm, MediaTek, Samsung Exynos, Google Tensor) and integrated by varied OEMs (Samsung, Xiaomi, Motorola, Sony, etc.).

A **device quirk** is an undocumented deviation from the Camera2 specification where a device:
- Misreports hardware capabilities in `CameraCharacteristics`.
- Fails to complete auto-exposure (AE) or auto-focus (AF) state machines as expected.
- Freezes or crashes the camera hardware daemon (`cameraserver`) when certain standard requests are issued.

Any production Android camera app must anticipate and work around these hardware anomalies.

---

## Real-World Quirk Examples in OpenKamera

OpenKamera isolates hardware compatibility logic in [`Camera2DeviceQuirks.kt`](../../app/src/main/java/com/hightechif/openkamera/cameracontroller/extension/Camera2DeviceQuirks.kt):

### 1. The Samsung Post-Capture AE Trigger Quirk
On several Samsung Galaxy models running early Android versions, issuing an explicit `CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL` after taking a photo causes subsequent preview frames to freeze with underexposed parameters.
- **Quirk Flag**: `postCapturePrecaptureTriggerCancelWorkaround`
- **Mitigation**: Instead of immediately canceling the trigger, the app avoids resetting the trigger explicitly, relying on a fresh repeating preview request to reset AE state.

### 2. Fake Flash / Precapture Requirement
On certain budget or older devices, calling `CONTROL_AE_PRECAPTURE_TRIGGER_START` under flash auto mode causes the HAL to wait indefinitely for `CONTROL_AE_STATE_CONVERGED`, hanging the shutter.
- **Quirk Flag**: `useFakePrecapture`
- **Mitigation**: The app manually enables torch mode for several frames before capturing, as covered in [Module 03 Lesson 02](../module-03-photography/02-flash-and-precapture.md).

### 3. Vendor Manual Focus Limitations
Certain devices report supporting manual focus (`CONTROL_AF_MODE_OFF`), but ignore `LENS_FOCUS_DISTANCE` unless the sensor is active in a specific preview mode.

---

## Detection & Branching Best Practices

When dealing with quirks:
1. **Never scatter `Build.MODEL` checks directly across business logic**:
   Centralize hardware detections into a dedicated quirks object (e.g. `Camera2DeviceQuirks`).
2. **Prefer feature detection over string matching**:
   Whenever possible, detect quirks by checking `CameraCharacteristics` keys or observing runtime HAL responses rather than hardcoding device brand strings.
3. **Provide user-facing escape hatches**:
   In advanced camera apps, provide preference toggles (e.g. "Use alternative flash method") allowing users on uncatalogued devices to bypass broken OEM paths.

---

## Next Steps

Learn how user settings and preferences are managed across clean architecture layers in [Lesson 05 — Settings Architecture](./05-settings-architecture.md).

# Module 03 — Lesson 01: Photo Capture Pipeline

> **Module:** 03 — Photography | **Level:** Intermediate | **Prerequisite:** [Module 02 — Preview](../module-02-preview/)

---

## High-Level Overview

Taking a still photo with Android Camera2 is significantly more intricate than calling `takePicture()` in Camera1. Camera2 treats the camera as a high-speed pipeline processing continuous stream requests.

Capturing a high-quality still picture involves three distinct phases:
1. **Precapture**: Lock exposure (AE), focus (AF), and white balance (AWB) while ensuring proper scene illumination (flash metering).
2. **Still Capture**: Dispatch high-resolution still capture requests (`TEMPLATE_STILL_CAPTURE`) to the `CameraCaptureSession`.
3. **Post-Capture Restore**: Unlock AE/AF locks and restore the repeating preview request (`TEMPLATE_PREVIEW`) to ready the camera for subsequent operations.

```
       User Taps Shutter
              │
              ▼
   CameraUiEvent.OnShutterClicked
              │
              ▼
        CameraViewModel  (owns intent)
              │  CameraCommand.TakePicture
              ▼
   MainActivity → legacy takePicture()  (executes)
              │
              ▼
┌───────────────────────────────┐
│     1. Precapture Phase       │
│  - Check flash / torch mode   │
│  - Trigger AE precapture      │
│  - Wait for AE/AF converge    │
└─────────────┬─────────────────┘
              │
              ▼
┌───────────────────────────────┐
│     2. Still Capture Phase    │
│  - Build STILL_CAPTURE req    │
│  - Target ImageReader surface │
│  - Dispatch capture / burst   │
└─────────────┬─────────────────┘
              │
              ▼
┌───────────────────────────────┐
│  3. Post-Capture Restore      │
│  - Cancel AE trigger          │
│  - Restore repeating preview  │
│  - Unlock AF/AE if required   │
└───────────────────────────────┘
```

---

## CaptureRequest Templates

Camera2 provides predefined templates via `CameraDevice.createCaptureRequest(templateType)`:

| Template | Typical Usage | Characteristics |
|---|---|---|
| `TEMPLATE_PREVIEW` | Live preview viewfinder | Prioritizes frame rate (e.g. 30fps) over maximum image quality. Minor post-processing. |
| `TEMPLATE_STILL_CAPTURE` | Photo capture | Prioritizes image quality (noise reduction, edge enhancement) over processing speed and frame rate. |
| `TEMPLATE_RECORD` | Video recording | Stable frame rate with video stabilization enabled. |

When transitioning from preview to still capture, the request switches from `TEMPLATE_PREVIEW` to `TEMPLATE_STILL_CAPTURE`.

---

## The Photo Pipeline State Machine

OpenKamera isolates this complex orchestration in [`Camera2PhotoPipeline.kt`](file:///Users/ridhanfadhilah/Public/Fadhil/mobile/android/studio-lab/project-open-camera/OpenKamera/app/src/main/java/com/hightechif/openkamera/cameracontroller/Camera2PhotoPipeline.kt).

The state machine manages:
- **`STATE_NORMAL`**: Streaming live preview frames.
- **`STATE_WAITING_AUTOFOCUS`**: Waiting for auto-focus algorithm to lock before taking a picture.
- **`STATE_WAITING_PRECAPTURE`**: Waiting for auto-exposure precapture metering sequence to complete.
- **`STATE_WAITING_FAKE_PRECAPTURE`**: Waiting for torch flash to warm up and AE to adapt on devices where standard precapture is unsupported or problematic.

```
[STATE_NORMAL]
     │
     │ user taps shutter
     ▼
[Check Focus / Flash]
     │
     ├── Auto-Focus needed? ────────► [STATE_WAITING_AUTOFOCUS]
     │                                           │ (AF locked)
     │                                           ▼
     ├── Flash Precapture needed? ──► [STATE_WAITING_PRECAPTURE]
     │                                           │ (AE converged)
     │                                           ▼
     └── Ready for Still Capture ───► [CaptureRequest dispatched]
                                                 │
                                                 ▼
                                     [Post-Capture Restore]
                                                 │
                                                 ▼
                                           [STATE_NORMAL]
```

---

## Code Reference & Implementation in OpenKamera

- Check out [`Camera2PhotoPipeline.kt`](file:///Users/ridhanfadhilah/Public/Fadhil/mobile/android/studio-lab/project-open-camera/OpenKamera/app/src/main/java/com/hightechif/openkamera/cameracontroller/Camera2PhotoPipeline.kt):
  - `initiate()`: Entry point invoked when user requests a photo capture.
  - `runPrecapture()`: Triggers AE metering before still capture.
  - `takePictureAfterPrecapture()`: Dispatches the still capture request to the camera hardware.
  - `restoreRepeating()`: Reinstates the repeating preview request and handles device quirks.

---

## Exercises & Next Steps

1. Review how `CaptureStateListener` allows the camera background thread to advance state without coupling directly to the UI thread.
2. Proceed to [Lesson 02 — Flash & Precapture](./02-flash-and-precapture.md) to dive deeper into flash trigger mechanics and fake precapture routines.

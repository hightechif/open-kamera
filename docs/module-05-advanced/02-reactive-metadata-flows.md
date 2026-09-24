# Module 05 — Lesson 02: Reactive Metadata Flows

> **Module:** 05 — Advanced | **Level:** Intermediate | **Prerequisite:** [Lesson 01 — Camera Engine Abstraction](./01-camera-engine-abstraction.md)

---

## From Framework Callbacks to Kotlin Flows

Camera2 delivers frame metadata through `CameraCaptureSession.CaptureCallback.onCaptureCompleted(session, request, result)`. 

In a typical 30 or 60 fps preview, this method is called 30 to 60 times per second on a background thread. Passing these raw `TotalCaptureResult` objects directly to UI views leads to thread-synchronization bottlenecks, GC pressure, and UI jank.

OpenKamera converts these high-frequency callback events into reactive Kotlin `StateFlow` and `SharedFlow` streams:

```
[Hardware HAL]
      │ 30-60 fps
      ▼
[CaptureCallback.onCaptureCompleted]
      │
      ▼
[Camera2MetadataCollector / Engine]
      │ Extracts relevant telemetry (ISO, Exposure Time, Focus Distance, AE State)
      ├───────────────────────┬───────────────────────┐
      ▼                       ▼                       ▼
frameMetadataFlow       focusStateFlow          histogramFlow
 (StateFlow)             (StateFlow)             (SharedFlow)
      │                       │                       │
      └───────────────────────┼───────────────────────┘
                              ▼
                      [CameraViewModel]
                              │
                              ▼
                      [UI Composables & HUD]
```

---

## The Metadata Pipeline in OpenKamera

Key reactive streams include:
- **`frameMetadataFlow`**: Emits sanitized snapshot models (`FrameMetadata`) containing current sensor sensitivity (ISO), exposure duration, aperture, and focal distance.
- **`focusStateFlow`**: Emits changes in auto-focus status (`SCANNING`, `LOCKED_FOCUSED`, `LOCKED_NOT_FOCUSED`), driving the AF reticle animations in the viewfinder.
- **`histogramFlow`**: Emits luminance or RGB channel distribution arrays computed from frame samples for real-time histogram display.

---

## ViewModel Consumption & Lifecycle Safety

In [`CameraViewModel.kt`](file:///Users/ridhanfadhilah/Public/Fadhil/mobile/android/studio-lab/project-open-camera/OpenKamera/app/src/main/java/com/hightechif/openkamera/ui/CameraViewModel.kt), these flows are consumed using structured coroutines:

```kotlin
viewModelScope.launch {
    engine.frameMetadataFlow
        .sample(100) // Downsample telemetry to 10 fps to save UI render cycles
        .collect { metadata ->
            _uiState.update { it.copy(currentIso = metadata.iso, shutterSpeed = metadata.shutterSpeed) }
        }
}
```

Using `StateFlow` and sampling operators prevents the UI from over-rendering while ensuring that current camera settings remain continuously accurate.

---

## Commands vs. Effects, and Legacy Feedback

`CameraViewModel` exposes two one-shot streams, and the difference matters:

| Stream | Purpose | If nobody is collecting |
|---|---|---|
| `uiEffect` (`CameraUiEffect`) | Cosmetic feedback: toast, haptic, navigation | May be dropped |
| `cameraCommands` (`CameraCommand`) | Instructions that change camera state (`TakePicture`, `PauseResumeVideo`, `RemoteButton`) | Must **not** be replayed later |

Both use `replay = 0`. A shutter press while the Activity is stopped is dropped rather than firing a photo when the user returns. `MainActivity` collects `cameraCommands` inside `repeatOnLifecycle(STARTED)`.

State flows back the other way. Legacy callbacks in `MyApplicationInterface` (`onCaptureStarted`, `onPictureCompleted`, `startedVideo`, `stoppedVideo`, pause/resume) call `onLegacyCaptureStarted()`, `onLegacyVideoStarted()` and friends on the ViewModel. `captureState`, `isRecording` and `isVideoPaused` therefore reflect what the camera is really doing, and the UI keeps observing plain `StateFlow`s.

---

## Next Steps

Learn how these real-time streams are drawn over the preview surface in [Lesson 03 — Overlay & HUD Rendering](./03-overlay-and-hud-rendering.md).

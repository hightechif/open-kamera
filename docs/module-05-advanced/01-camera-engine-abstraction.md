# Module 05 — Lesson 01: Camera Engine Abstraction

> **Module:** 05 — Advanced | **Level:** Intermediate | **Prerequisite:** [Module 01 — Foundations](../module-01-foundations/)

---

## The Danger of Direct Camera Coupling

In legacy Android camera applications, code from the UI layer (activities, views, dialogs) often directly touches framework objects like `CameraDevice`, `CameraCaptureSession`, or `CaptureRequest.Builder`.

This tightly coupled design causes significant engineering problems:
1. **Un-testable UI**: ViewModels and UI logic cannot be unit tested without mocking massive Android framework hierarchies.
2. **Brittle Architecture**: Upgrading camera APIs or introducing new capture backends (e.g. testing virtual mock sensors or CameraX) requires rewriting UI and business logic.
3. **Threading Bugs**: Camera callbacks running on background handler threads bleed directly into UI state management.

---

## The Clean Architecture Engine Boundary

To decouple the application, OpenKamera introduces an engine abstraction layer in [`domain/engine/`](../../app/src/main/java/com/hightechif/openkamera/domain/engine):

```
┌────────────────────────────────────────────────────────┐
│                   Presentation Layer                   │
│          (CameraViewModel, UI Components, HUD)         │
└───────────────────────────┬────────────────────────────┘
                            │ Calls Use Cases
                            ▼
┌────────────────────────────────────────────────────────┐
│                      Domain Layer                      │
│       (Use Cases: SetZoomUseCase, ToggleFlashUseCase) │
└───────────────────────────┬────────────────────────────┘
                            │ Depends on interface
                            ▼
┌────────────────────────────────────────────────────────┐
│                 ICameraEngine (Interface)              │
│       - startPreview()           - setFlashMode()      │
│       - setZoom()                - setExposure()       │
└───────────────────────────▲────────────────────────────┘
                            │ Implemented by
                            │
┌────────────────────────────────────────────────────────┐
│              Camera2EngineBridge (Adapter)             │
│        Delegates to CameraController2 / Preview        │
└────────────────────────────────────────────────────────┘
```

---

## Role of `Camera2EngineBridge`

The adapter [`Camera2EngineBridge.kt`](../../app/src/main/java/com/hightechif/openkamera/cameracontroller/Camera2EngineBridge.kt) fulfills the domain interface `ICameraEngine`:
- Converts domain requests (e.g. `setZoom(ratio)`) into low-level calls to `CameraController2`.
- Translates hardware exceptions into domain-friendly `CameraEngineException` objects.
- Exposes pure Kotlin coroutine suspending functions and `Flow` streams for state observations.

---

## Benefits of Domain Use Case Isolation

With `ICameraEngine`, business rules live in isolated domain use cases:
- `ToggleFlashUseCase`, `SetZoomUseCase`, `AdjustExposureUseCase`, `TapToFocusUseCase`, `SwitchCameraFacingUseCase`: each wraps one control on the engine and keeps the matching setting in sync.
- Unit tests can mock `ICameraEngine` in JVM test suites without spinning up Android emulators or real camera hardware.

---

## Strangler Fig in Practice: Intent vs. Execution

The domain boundary above is the *destination*. The live app is mid-migration (a "strangler fig": new code grows around the old until the old can be removed), so today capture is split in two roles:

```
            ┌────────────── intent ──────────────┐        ┌──── execution ────┐
 input ──► CameraUiEvent ──► CameraViewModel ──► CameraCommand ──► MainActivity ──► legacy takePicture()
                                  ▲                                                   │
                                  └───────── state feedback (onCaptureStarted, …) ◄───┘
```

- **The ViewModel owns intent.** Every shutter, key, remote and audio trigger becomes exactly one `CameraUiEvent`, which [`CameraViewModel.kt`](../../app/src/main/java/com/hightechif/openkamera/ui/CameraViewModel.kt) translates into a `CameraCommand`.
- **The legacy pipeline still executes.** `MainActivity` collects `cameraCommands` and calls the battle-tested `takePicture()` → `Preview` → `CameraController2` → `ImageSaver` path, which is what applies EXIF orientation, stamps, HDR/DRO/NR and save-location preferences.
- **The engine has no capture or recording methods.** `ICameraEngine` exposes only preview, zoom, focus, exposure and flash. Still capture and video recording are executed by the legacy pipeline, because a domain version could not yet match `ImageSaver`. Routing *intent* first and replacing *execution* later is safer than a rewrite.

### Sidebar: two callers, one camera

Before this change, one `KEYCODE_HEADSETHOOK` press called the ViewModel *and* the legacy `takePicture()`. The result was two JPEGs, one of them with `Orientation=1` (sideways in portrait). A strangler needs a **single entry point**: while two callers can reach the same camera, migrating either one is unsafe.

---

## The Bridge and the Camera1 Fallback

`Camera2EngineBridge` only ever wraps a `CameraController2`. When OpenKamera falls back to Camera1 (see [Choosing Camera1 or Camera2](../module-01-foundations/01-camera-api-overview.md#choosing-camera1-or-camera2-in-openkamera)), `Preview` never attaches a controller to the bridge. The bridge then degrades quietly:

- Every control (`setZoom`, `setManualFocus`, `unlockFocus`, `setExposureCompensation`, `setFlashMode`) returns without doing anything.
- `frameMetadataFlow`, `focusStateFlow` and the histogram keep their default values, because Camera1 has no `TotalCaptureResult`.
- Capture and recording are unaffected. They run through the legacy pipeline, which works with either controller.

This is a deliberate trade-off: the reactive layer is a Camera2 feature, and the fallback exists for devices where Camera2 is not worth using.

## Maintaining the Fallback

Keeping ~1,900 lines of Camera1 code alive is only safe if it cannot silently drift away from `CameraController2`. Two guards enforce this:

1. **The compiler.** `CameraController1` extends the abstract `CameraController`. Adding an abstract member to it breaks the build until Camera1 implements it.
2. **`Camera1ApiParityTest`.** For members with a default implementation in `CameraController` that `CameraController2` overrides, the test requires `CameraController1` to override them too, or the member must be listed in the test's allow-list **with a reason** (for example "Camera1 has no per-frame capture result"). A new Camera2-only behavior therefore fails the test until someone decides, on purpose, whether Camera1 needs it.

When you change `CameraController`, ask: does Camera1 need this too?

---

## What Was Removed and Why

Earlier versions of this repo contained a second, parallel implementation of the same ideas. None of it ran in the app, so it was deleted. (The Camera1 controller was briefly deleted too, then restored as an isolated fallback; see above.)

| Removed                                                                                                                                     | Why it was dead                                                                                         |
|---------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------|
| `Camera2EngineImpl`                                                                                                                         | A second `ICameraEngine`; only its own tests used it. `Camera2EngineBridge` is the real one.            |
| `CapturePhotoUseCase`, `RecordVideoUseCase`, `ProcessHdrUseCase`, `ProcessPanoramaUseCase`                                                  | A domain capture path that the ViewModel no longer called once shutter intents became `CameraCommand`s. |
| `IImageProcessor`, `ImageProcessorImpl`, `MediaProcessingWorker`                                                                            | Support code for those use cases.                                                                       |
| Engine methods `captureStillImage` and `start/pause/resume/stopVideoRecording`, and the matching `Camera2VideoPipeline` recording lifecycle | No caller left.                                                                                         |

**Why this matters for learners:** code that looks clean but never runs is the most misleading thing in a codebase. You read a tidy class, learn its design, and then discover the app does something else. Pruning keeps every remaining class one you can follow to a real caller. When a real parity migration happens, the domain path should be rebuilt against the legacy behavior rather than revived.

**Finding it again:** everything is in git history. The last commit that contains all of the above is `ec680bf`; for example `git show ec680bf:app/src/main/java/com/hightechif/openkamera/cameracontroller/Camera2EngineImpl.kt`.

---

## Next Steps

Discover how camera frame telemetry flows reactively to the presentation layer in [Lesson 02 — Reactive Metadata Flows](./02-reactive-metadata-flows.md).

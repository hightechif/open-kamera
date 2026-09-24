# Module 01 — Lesson 04: Project Architecture

> **Module:** 01 — Foundations | **Level:** Intermediate | **Prerequisite:** [Lesson 03 — Android Permissions](./03-android-permissions.md)

---

## Why Architecture Matters in Camera Apps

Camera apps are among the most complex Android apps to write correctly. They deal with:
- Hardware resources that must be opened and released precisely with lifecycle events
- Background threads for camera operations (never block the UI thread)
- Large data flows (image buffers, HAL metadata callbacks, sensor events)
- Complex state machines (focus states, flash states, capture phases)

Without a clear architecture, this complexity becomes unmanageable. OpenKamera applies **Clean Architecture** principles to keep each concern separated and testable.

---

## The Architectural Layers

```
┌───────────────────────────────────────────────────────────────────┐
│                          UI Layer                                 │
│  MainActivity, CameraViewModel, DrawPreview, PopupView, etc.      │
│  - Renders the camera viewfinder and controls                     │
│  - Reacts to StateFlow / SharedFlow from ViewModel                │
│  - Does NOT talk to Camera2 directly                              │
├───────────────────────────────────────────────────────────────────┤
│                        Domain Layer                               │
│  Use Cases, ICameraEngine, Domain Models, Repository Interfaces   │
│  - Pure business logic (no Android framework dependencies)        │
│  - Use cases: CapturePhotoUseCase, ZoomUseCase, etc.             │
│  - Interfaces: ICameraEngine, ISettingsRepository, etc.           │
├───────────────────────────────────────────────────────────────────┤
│                         Data Layer                                │
│  Camera2EngineBridge, CameraController2, SettingsRepositoryImpl   │
│  - Implements domain interfaces using real Android APIs           │
│  - Camera2, SharedPreferences, MediaStore, GPS                    │
│  - All Camera2 HAL interactions happen here                       │
└───────────────────────────────────────────────────────────────────┘
```

**The key rule:** Dependencies flow *inward* — the data layer depends on domain interfaces, the UI layer depends on the ViewModel, the ViewModel depends on use cases. The domain layer depends on *nothing* Android-specific.

This means domain use cases and models can be unit-tested on the JVM without a device or emulator.

---

## Package Map

```
com.hightechif.openkamera/
├── audio/            # Audio triggers (voice shutter, sound effects)
│                     # AudioTrigger detects sounds to trigger capture
│
├── cameracontroller/ # The Camera2 implementation layer (data layer)
│   ├── Camera2EngineBridge.kt    # ICameraEngine adapter for Camera2
│   ├── Camera2EngineImpl.kt      # Core Camera2 state management
│   ├── Camera2PhotoPipeline.kt   # Photo capture state machine
│   ├── Camera2VideoPipeline.kt   # Video recording orchestration
│   ├── Camera2BurstCoordinator.kt# Burst counter state
│   ├── CameraController.kt       # Abstract interface for Camera1/Camera2
│   ├── CameraController2.kt      # Full Camera2 HAL implementation
│   └── capabilities/             # Feature detection (zoom, focus, flash)
│
├── di/               # Hilt dependency injection modules
│                     # Binds interfaces to implementations
│
├── domain/           # Pure domain layer (no Android deps)
│   ├── engine/       # ICameraEngine, IAudioController interfaces
│   ├── interactor/   # Multi-use-case orchestrators
│   ├── model/        # Data classes: CaptureConfig, FlashMode, etc.
│   ├── repository/   # ISettingsRepository, ILocationRepository, etc.
│   └── usecase/      # CapturePhotoUseCase, ZoomUseCase, etc.
│
├── lifecycle/        # Camera and orientation lifecycle coordinators
├── preferences/      # SharedPreferences keys, Settings UI fragments
├── preview/          # Camera preview surface, video quality, gestures
├── processing/       # HDRProcessor, PanoramaProcessor, image functions
├── remotecontrol/    # Bluetooth remote shutter support
├── sensors/          # Accelerometer, compass, orientation
├── storage/          # ImageSaver, Exif, MediaStore, SAF
├── system/           # Permissions, display, system integrations
├── ui/               # CameraViewModel, DrawPreview, PopupView, overlays
└── utils/            # Math helpers, formatters, debug utilities
```

---

## Hilt Dependency Injection

OpenKamera uses **Hilt** (built on Dagger) for dependency injection. Hilt eliminates manual dependency wiring and makes the architecture auditable.

**How it works in practice:**

```kotlin
// In di/ module — bind the interface to the implementation
@Binds
abstract fun bindCameraEngine(impl: Camera2EngineBridge): ICameraEngine

// In the ViewModel — Hilt injects ICameraEngine automatically
@HiltViewModel
class CameraViewModel @Inject constructor(
    private val cameraEngine: ICameraEngine,
    private val capturePhotoUseCase: CapturePhotoUseCase,
    ...
)
```

The ViewModel never knows it's talking to `Camera2EngineBridge` — it only knows it has an `ICameraEngine`. This is what makes the domain layer testable: in tests, a fake `ICameraEngine` can be injected instead of the real Camera2 bridge.

The DI modules live in the [`di/`](../../app/src/main/java/com/hightechif/openkamera/di/) package.

---

## Reactive State with Kotlin Flows

OpenKamera uses **Kotlin Flows** (specifically `StateFlow` and `SharedFlow`) as the bridge between the camera subsystem and the UI:

```
Camera HAL callback
(background thread)
        │
        ▼
Camera2EngineBridge
  emits to flows
        │
        ▼
CameraViewModel
  collects flows, maps to UI state
        │
        ▼
Composable / View
  renders UI state
```

`StateFlow` is used for state that always has a current value (e.g., current flash mode, current zoom level). `SharedFlow` is used for events (e.g., "photo capture complete", "error occurred").

See [Module 05 — Reactive Metadata Flows](../module-05-advanced/02-reactive-metadata-flows.md) for the detailed implementation.

---

## What's Next

With the architecture understood, Module 02 dives into the camera preview — the live viewfinder that forms the foundation of the app's user experience.

👉 [Module 02 — Preview: Lesson 01 — Surface & SurfaceTexture](../module-02-preview/01-surface-and-surfacetexture.md)

---

*Part of the [OpenKamera Curriculum](../README.md)*

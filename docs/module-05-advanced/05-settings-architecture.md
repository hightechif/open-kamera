# Module 05 — Lesson 05: Settings & Preferences Architecture

> **Module:** 05 — Advanced | **Level:** Intermediate | **Prerequisite:** [Lesson 04 — Device Quirks & Hardware Compatibility](./04-device-quirks-and-compat.md)

---

## The Challenge of Camera Settings

A full-featured camera application manages hundreds of user preferences:
- Photo resolution, format (JPEG, RAW, JPEG+RAW), quality compression level.
- Video bitrates, frame rates, audio sources, video stabilization.
- On-screen HUD elements (grids, timers, histograms, angles).
- Hardware behavior (volume key actions, shutter sound, anti-banding, anti-shake).

If raw `SharedPreferences.getString("key", default)` calls are scattered across the codebase:
- Key names become typo-prone string literals.
- Types are unverified at compile-time.
- Changes cannot be observed reactively with Kotlin Flows.

---

## The OpenKamera Solution: Three-Tier Settings Architecture

OpenKamera organizes settings into three clean layers:

```
┌────────────────────────────────────────────────────────┐
│            1. Typed PreferenceKeys Constants           │
│           (preferences/PreferenceKeys.kt)              │
├────────────────────────────────────────────────────────┤
│          2. Clean Domain Repository Interfaces         │
│               (domain/repository/preferences/)         │
│   - PhotoPreferencesRepository                         │
│   - VideoPreferencesRepository                         │
│   - UiHudPreferencesRepository                         │
├────────────────────────────────────────────────────────┤
│          3. Implementation Layer & Hilt Injection      │
│        (preferences/SettingsRepositoryImpl.kt)         │
└────────────────────────────────────────────────────────┘
```

---

## 1. Centralized Keys in `PreferenceKeys.kt`

All preference strings and default values are centralized in [`PreferenceKeys.kt`](file:///Users/ridhanfadhilah/Public/Fadhil/mobile/android/studio-lab/project-open-camera/OpenKamera/app/src/main/java/com/hightechif/openkamera/preferences/PreferenceKeys.kt):

```kotlin
object PreferenceKeys {
    const val CameraIdPreferenceKey = "preference_camera_id"
    const val FlashValuePreferenceKey = "preference_flash"
    const val FocusModePreferenceKey = "preference_focus_mode"
    const val VideoBitratePreferenceKey = "preference_video_bitrate"
    const val RawPreferenceKey = "preference_raw"
    // ...
}
```

---

## 2. Domain Repositories

Rather than passing raw shared preferences to domain use cases, settings are exposed through typed domain repositories in [`domain/repository/preferences/`](file:///Users/ridhanfadhilah/Public/Fadhil/mobile/android/studio-lab/project-open-camera/OpenKamera/app/src/main/java/com/hightechif/openkamera/domain/repository/preferences/):

```kotlin
interface PhotoPreferencesRepository {
    fun getPhotoQuality(): Int
    fun isRawEnabled(): Boolean
    fun observeRawEnabled(): Flow<Boolean>
}
```

This ensures use cases only depend on clean abstractions and can easily be unit tested with fake repositories.

---

## 3. Dependency Injection with Hilt

Implementations like `SettingsRepositoryImpl` or specific sub-repositories are injected via Hilt into ViewModels and UseCases:

```kotlin
@HiltViewModel
class CameraViewModel @Inject constructor(
    private val cameraEngine: ICameraEngine,
    private val photoPreferences: PhotoPreferencesRepository,
    private val uiHudPreferences: UiHudPreferencesRepository
) : ViewModel()
```

---

## Conclusion & Curriculum Summary

Congratulations! You have completed the **OpenKamera Educational Curriculum**. You now understand:
- The fundamental Camera2 session model and pipeline flow.
- Preview lifecycle, surface handling, and coroutine concurrency.
- Still capture, flash precapture, burst, HDR processing, and RAW DNG saving.
- Video stream multi-surface encoding and audio sync.
- Clean architecture abstractions, reactive metadata flows, HUD overlays, hardware quirks, and typed settings.

Return to the [Curriculum Index](../README.md) to explore individual lessons or dive into the codebase.

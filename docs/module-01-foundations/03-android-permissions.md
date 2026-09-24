# Module 01 — Lesson 03: Android Permissions & Scoped Storage

> **Module:** 01 — Foundations | **Level:** Intermediate | **Prerequisite:** [Lesson 02 — Camera2 Session Model](./02-camera2-session-model.md)

---

## Permissions Camera Apps Need

Camera apps require explicit user consent to access hardware and storage. Android's permission model has evolved significantly across API levels, so it's important to understand both the permissions themselves and *when* they apply.

### CAMERA Permission

```xml
<uses-permission android:name="android.permission.CAMERA" />
```

This is a **dangerous permission** — it must be requested at runtime (API 23+). Without it, `CameraManager.openCamera()` will throw a `SecurityException`.

**Runtime request flow:**
```
App launched
     │
     ├─ Permission already granted? ──Yes──▶ Open camera
     │
     └─ Not granted
           │
           ▼
     ActivityCompat.requestPermissions(CAMERA)
           │
     User dialog appears
           │
     ├─ Granted ──▶ Open camera
     └─ Denied  ──▶ Show explanation or graceful degradation
```

### RECORD_AUDIO Permission

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

Required for video recording with audio. This is separate from `CAMERA` — a user can grant camera but deny audio, in which case video recording should fall back to mute.

### Storage Permissions

Storage permissions are where Android's permission model has changed the most. The right permissions to request depend on the API level your app is running on:

| API Level | Save to public gallery | Read user's media |
|---|---|---|
| ≤ 28 (Pie) | `WRITE_EXTERNAL_STORAGE` | `READ_EXTERNAL_STORAGE` |
| 29–32 | Neither needed (scoped storage) | Neither needed |
| 33+ (Tiramisu) | Neither needed | `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` |

---

## Scoped Storage (Android Q / API 29+)

Before Android Q (API 29), apps could write files directly to the filesystem using `File` paths. With **Scoped Storage**, this changed:

- Apps can freely write to their own **app-private directories** (`getExternalFilesDir()`)
- Writing to **shared public directories** (like DCIM or Pictures) requires going through `MediaStore` or the **Storage Access Framework (SAF)**

### Why this matters for camera apps

Camera apps typically save photos to `DCIM/` so other gallery apps can see them. Under scoped storage, this requires using `MediaStore.Images.Media.insert()` instead of a direct `File` write.

### How OpenKamera handles it

OpenKamera detects the API level at runtime via [`useScopedStorage()`](../../app/src/main/java/com/hightechif/openkamera/ActivityUtils.kt):

```kotlin
/** Whether to use codepaths that are compatible with scoped storage. */
fun useScopedStorage(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
```

When `useScopedStorage()` returns `true`, the storage layer uses `MediaStore`-based insertion. When `false` (Android 9 and below), it uses direct `File` paths. This branching lives in the `storage/` package.

---

## Storage Access Framework (SAF)

For scenarios where the user wants to save to a custom folder they choose, OpenKamera uses the **Storage Access Framework** — a system UI that lets users pick a directory, granting the app persistent access via a URI.

```
User taps "Choose save folder"
     │
     ▼
Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
     │
     ▼  (System picker UI)
     │
     └─▶ onActivityResult: Uri (persisted permission)
               │
               ▼
         ContentResolver writes to that Uri
```

SAF URIs can be persisted across app restarts with `takePersistableUriPermission()`. This is how OpenKamera remembers a user's chosen custom save folder between sessions.

---

## Permission Best Practices Applied in OpenKamera

1. **Request only what you need, when you need it** — `CAMERA` is requested when the camera screen opens, not at app launch.

2. **Handle denial gracefully** — If camera permission is denied, the app shows a rationale and doesn't crash.

3. **Never request `WRITE_EXTERNAL_STORAGE` on API 29+** — it's ignored anyway, and requesting it unnecessarily may flag the app in Play Store review.

4. **Check at runtime, not just at install time** — Users can revoke permissions after granting them (Settings > Apps). Always check before opening the camera.

---

## What's Next

With permissions understood, you're ready to look at how OpenKamera's codebase is structured — the packages, the architectural layers, and the dependency injection setup.

👉 [Lesson 04 — Project Architecture](./04-project-architecture.md)

---

*Part of the [OpenKamera Curriculum](../README.md)*

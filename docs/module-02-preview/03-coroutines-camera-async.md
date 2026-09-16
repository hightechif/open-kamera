# Module 02 — Lesson 03: Coroutines for Camera Async Work

> **Module:** 02 — Preview | **Level:** Intermediate | **Prerequisite:** [Lesson 02 — Preview Lifecycle](./02-preview-lifecycle.md)

---

## The Problem with AsyncTask

Before Kotlin Coroutines became the standard, Android developers used `AsyncTask` to run work off the main thread. Camera apps were heavy users of it — opening a camera, closing it, and processing images are all blocking operations.

`AsyncTask` had serious problems that made it particularly dangerous for camera lifecycle:

**1. No built-in lifecycle awareness**

`AsyncTask` kept running even if the Activity was destroyed. A camera `open` started in `onResume()` could complete and try to update a destroyed Activity's views — causing a crash.

**2. Memory leaks**

`AsyncTask` holds an implicit reference to the enclosing class (typically the Activity). If the task outlives the Activity, the Activity's memory can't be garbage collected.

**3. Poorly defined cancellation**

Cancelling an `AsyncTask` only set a flag. The task had to cooperate by checking `isCancelled()` — there was no way to stop a task that was blocked waiting for the camera hardware.

**4. Deprecated in API 30**

Google deprecated `AsyncTask` in Android 11, acknowledging these problems. OpenKamera replaced all `AsyncTask` usage with structured Kotlin Coroutines.

---

## How Coroutines Solve These Problems

Kotlin Coroutines are **structured** — they run within a scope that has a defined lifetime. When the scope is cancelled, all coroutines inside it are cancelled automatically.

```
Activity/Fragment
      │
      └── lifecycleScope  (cancelled when Activity destroys)
              │
              └── launch {
                      // camera open work here
                      // automatically cancelled when Activity dies
                  }
```

For OpenKamera's camera preview, the coroutine scope is managed by the [`Preview`](../../app/src/main/java/com/hightechif/openkamera/preview/Preview.kt) component, which ties the scope to the camera lifecycle.

---

## The Dispatcher Pattern

Coroutines run on **Dispatchers** — thread pool configurations for different types of work:

| Dispatcher | Thread | Use for |
|---|---|---|
| `Dispatchers.Main` | UI thread | View updates, state flows |
| `Dispatchers.IO` | Background thread pool | Blocking I/O (camera open/close, file operations) |
| `Dispatchers.Default` | CPU thread pool | Heavy computation (image processing) |

Camera operations are blocking I/O, so they use `Dispatchers.IO`:

```kotlin
// Camera open — runs on IO dispatcher, result delivered on Main
viewModelScope.launch {
    withContext(Dispatchers.IO) {
        cameraController.openCamera()   // blocking — waits for HAL
    }
    // Back on Main thread automatically
    onCameraOpened()                    // safe to update UI here
}
```

OpenKamera injects the dispatcher via Hilt (`@IoDispatcher`, `@DefaultDispatcher`) — this allows tests to inject `TestCoroutineDispatcher` and control execution timing.

---

## Structured Cancellation in Action

Here's why structured cancellation matters for camera lifecycle:

```
Scenario: User opens camera app, then immediately locks the phone

Timeline:
  t=0  onResume() → launch coroutine to open camera
  t=1  onPause()  → lifecycleScope cancelled
  t=2  Camera HAL sends "opened" callback

With AsyncTask:          With Coroutines:
  t=2: AsyncTask keeps   t=2: Coroutine is already
       running, tries to       cancelled; camera is
       access destroyed        closed safely before
       Activity → CRASH        it can be used → SAFE
```

When the coroutine is cancelled mid-await, OpenKamera's `openCamera()` implementation handles cleanup in a `try/finally` block:

```kotlin
try {
    val controller = openCameraOnIoThread()  // suspends here
    onCameraOpened(controller)
} catch (e: CancellationException) {
    controller?.close()   // release hardware if partially opened
    throw e               // re-throw so structured concurrency works
}
```

---

## The `suspendCancellableCoroutine` Bridge

Camera2's `openCamera()` is callback-based, not suspending. To bridge it into a coroutine, OpenKamera uses `suspendCancellableCoroutine`:

```kotlin
// Conceptual — bridges Camera2 callback to coroutine suspension
suspend fun openCameraDevice(id: String): CameraDevice =
    suspendCancellableCoroutine { continuation ->
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                continuation.resume(device)  // resumes the coroutine
            }
            override fun onError(device: CameraDevice, error: Int) {
                continuation.cancel(CameraException(error))
            }
        }, handler)

        continuation.invokeOnCancellation {
            // if coroutine is cancelled while waiting, close the device
        }
    }
```

This pattern is used throughout [`Camera2EngineBridge.kt`](../../app/src/main/java/com/hightechif/openkamera/cameracontroller/Camera2EngineBridge.kt) to turn Camera2's callbacks into clean suspending functions.

---

## What's Next

The preview is running. Now what happens when the user taps the screen to focus?

👉 [Lesson 04 — Gesture & Tap-to-Focus](./04-gesture-and-tap-to-focus.md)

---

*Part of the [OpenKamera Curriculum](../README.md)*

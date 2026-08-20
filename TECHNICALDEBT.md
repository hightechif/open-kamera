# Technical Debt: OpenKamera

## 1. Renderscript Deprecation
### Context
The project heavily relies on Android Renderscript (found in `app/src/main/rs/` such as `align_mtb.rs`) for performance-intensive image processing tasks (e.g., HDR alignment, Panorama stitching). 

### The Problem
Android Renderscript has been officially deprecated starting in Android 12 (API level 31). While it still functions on many devices, it will eventually be removed entirely, leading to broken core features (HDR and Panorama) on future Android versions.

### Recommended Path Forward
- **Short Term**: Keep the existing Renderscript implementations, but begin abstracting the Renderscript calls behind a standard interface (e.g., `ImageProcessorInterface`) to make them swappable.
- **Medium/Long Term**: Migrate Renderscript code to the official **RenderScript Toolkit** (a C++ replacement provided by Google), or rewrite the compute kernels using **Vulkan Compute Shaders** for optimal multi-platform performance.

## 2. Monolithic Classes
- `Preview.kt` (~450KB) and `CameraController2.kt` (~485KB) are extremely large and difficult to maintain. They act as "God Objects", managing state machines, rendering surfaces, and complex camera lifecycle events all at once.

## 3. Legacy Concurrency
- `ImageSaver.kt` and parts of the camera controllers still rely on raw Java `Thread` creation and `synchronized` blocks. This makes the code prone to deadlocks and ANRs. Moving towards structured concurrency with **Kotlin Coroutines** will improve safety and readability.

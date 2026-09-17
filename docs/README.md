# 📚 OpenKamera — Learning Curriculum

Welcome to the **OpenKamera educational documentation**. This curriculum is designed for **intermediate Android developers** who want to learn how camera applications work on Android — from the Camera2 API fundamentals all the way to advanced topics like reactive metadata streams and image processing algorithms.

> **Prerequisites:** Basic familiarity with Android development (Activities, Lifecycle) and Kotlin syntax. No prior camera development experience required.

The concepts taught here are grounded in real, production-quality code. Every document links to the actual source files implementing the concept — and key source files link back here.

---

## 🗺️ Curriculum Map

### [Module 00 — Photography Primer](./module-00-photography-primer/)

The optical, physical, and creative fundamentals. **Start here if you want to understand how cameras and manual controls actually work.**

| # | Document | What You'll Learn |
|---|---|---|
| 01 | [Light, Sensors, & Exposure](./module-00-photography-primer/01-light-sensors-and-exposure.md) | Photons to pixels, dynamic range, clipping, and reading histograms |
| 02 | [The Exposure Triangle](./module-00-photography-primer/02-the-exposure-triangle.md) | Aperture, shutter speed, ISO, and the smartphone fixed-aperture reality |
| 03 | [Shooting Modes & EV Compensation](./module-00-photography-primer/03-shooting-modes-and-ev-compensation.md) | Auto, Tv/S, Av/A, Manual, 18% gray metering, and +/- EV dials |
| 04 | [Focus, Sharpness, & Depth of Field](./module-00-photography-primer/04-focus-sharpness-and-depth-of-field.md) | Optical DoF vs computational bokeh, AF modes, and focus peaking |
| 05 | [Color Science & White Balance](./module-00-photography-primer/05-color-science-and-white-balance.md) | Kelvin color temperature, light spectra, and manual white balance |
| 06 | [Practical Field Cookbook](./module-00-photography-primer/06-practical-recipes-and-camera-craft.md) | Field recipes for action freezing, waterfalls, astrophotography, and sunsets |

---

### [Module 01 — Foundations](./module-01-foundations/)

The building blocks of Android Camera development. Start here after the primer.

| # | Document | What You'll Learn |
|---|---|---|
| 01 | [Camera API Overview](./module-01-foundations/01-camera-api-overview.md) | Camera1 vs Camera2, why Camera2 is the correct choice |
| 02 | [Camera2 Session Model](./module-01-foundations/02-camera2-session-model.md) | Device → Session → Request → Result lifecycle |
| 03 | [Android Permissions & Scoped Storage](./module-01-foundations/03-android-permissions.md) | Runtime permissions, scoped storage, and storage access |
| 04 | [Project Architecture](./module-01-foundations/04-project-architecture.md) | Clean architecture, Hilt DI, and the package map |

---

### [Module 02 — Preview](./module-02-preview/)

How the camera's live viewfinder works.

| # | Document | What You'll Learn |
|---|---|---|
| 01 | [Surface & SurfaceTexture](./module-02-preview/01-surface-and-surfacetexture.md) | What a preview surface is and how Camera2 renders to it |
| 02 | [Preview Lifecycle](./module-02-preview/02-preview-lifecycle.md) | Opening, showing, pausing, and closing the camera |
| 03 | [Coroutines for Camera Async Work](./module-02-preview/03-coroutines-camera-async.md) | Why AsyncTask was replaced and how coroutines work here |
| 04 | [Gesture & Tap-to-Focus](./module-02-preview/04-gesture-and-tap-to-focus.md) | How screen taps become Camera2 metering regions |

---

### [Module 03 — Photography](./module-03-photography/)

Still image capture, from shutter press to saved file.

| # | Document | What You'll Learn |
|---|---|---|
| 01 | [Photo Capture Pipeline](./module-03-photography/01-photo-capture-pipeline.md) | The three-phase state machine behind every photo |
| 02 | [Flash & Precapture Sequencing](./module-03-photography/02-flash-and-precapture.md) | Flash modes, AE convergence, and fake precapture |
| 03 | [Burst & Exposure Bracketing](./module-03-photography/03-burst-and-bracketing.md) | How burst mode and EV bracketing are orchestrated |
| 04 | [HDR Image Processing](./module-03-photography/04-hdr-image-processing.md) | Multi-exposure capture, alignment, tone mapping, blending |
| 05 | [RAW & DNG Capture](./module-03-photography/05-raw-and-dng-capture.md) | Bayer sensors, RAW buffers, and the DNG format |

---

### [Module 04 — Video](./module-04-video/)

Video recording: sessions, codecs, and audio.

| # | Document | What You'll Learn |
|---|---|---|
| 01 | [Video Pipeline Overview](./module-04-video/01-video-pipeline-overview.md) | How Camera2 and MediaRecorder work together |
| 02 | [MediaRecorder & Codecs](./module-04-video/02-mediarecorder-and-codecs.md) | Bitrate, profiles, H.264 vs H.265, and slow-motion |
| 03 | [Audio Sources & A/V Sync](./module-04-video/03-audio-sources-and-sync.md) | Microphone selection and audio-video synchronization |

---

### [Module 05 — Advanced](./module-05-advanced/)

Architecture patterns, reactive design, and compatibility handling.

| # | Document | What You'll Learn |
|---|---|---|
| 01 | [Camera Engine Abstraction](./module-05-advanced/01-camera-engine-abstraction.md) | The `ICameraEngine` interface and adapter pattern |
| 02 | [Reactive Metadata Flows](./module-05-advanced/02-reactive-metadata-flows.md) | TotalCaptureResult → StateFlow → UI pipeline |
| 03 | [Overlay & HUD Rendering](./module-05-advanced/03-overlay-and-hud-rendering.md) | How the histogram, focus peaking, and grid overlays are drawn |
| 04 | [Device Quirks & Compatibility](./module-05-advanced/04-device-quirks-and-compat.md) | Handling OEM deviations from the Camera2 spec |
| 05 | [Settings Architecture](./module-05-advanced/05-settings-architecture.md) | Typed preferences, repository pattern, and injection |

---

## 🔗 Cross-Reference Convention

This project uses a bidirectional cross-referencing convention between source code and documentation:

### KDoc → Docs (in source files)
When a class or function is the primary implementation of a concept covered in this curriculum, its KDoc block ends with:
```
📖 Learn more: `docs/module-03-photography/01-photo-capture-pipeline.md`
```

### Docs → Code (in markdown files)
When a document references a real implementation, it links to the source file using a repo-root-relative path:
```markdown
[Camera2PhotoPipeline.kt](../app/src/main/java/com/hightechif/openkamera/cameracontroller/Camera2PhotoPipeline.kt)
```

Both styles use **relative paths** so they work locally, on GitHub, and in any fork.

---

## 🤝 Contributing to Docs

If you add a new concept guide:
1. Add a numbered file to the appropriate module folder
2. List it in this `README.md` table
3. Add a `📖 **Learn more:**` link in the relevant source file's KDoc
4. Add a source file link in the new markdown doc

---

*This curriculum is part of the [OpenKamera](../README.md) project — a modern Kotlin port of Open Camera, used as a teaching resource for intermediate Android developers.*

# Module 04 — Lesson 03: Audio Sources & Sync

> **Module:** 04 — Video | **Level:** Intermediate | **Prerequisite:** [Lesson 02 — MediaRecorder & Video Codecs](./02-mediarecorder-and-codecs.md)

---

## Audio Sources in Android

When capturing video audio via `MediaRecorder`, Android offers multiple hardware audio sources:

| Audio Source | Constant | Characteristics |
|---|---|---|
| **Camcorder** | `MediaRecorder.AudioSource.CAMCORDER` | Optimized for video recording. Uses camera-facing directional microphones and enables system noise cancellation. |
| **Microphone** | `MediaRecorder.AudioSource.MIC` | Standard device microphone, tuned for voice calls or handheld speech. |
| **Unprocessed** | `MediaRecorder.AudioSource.UNPROCESSED` | Disables AGC (auto gain control), noise suppression, and echo cancellation for pure acoustic fidelity (Android 7.0+). |
| **Voice Recognition** | `MediaRecorder.AudioSource.VOICE_RECOGNITION` | Tuned to isolate speech patterns. |

In OpenKamera, audio source preferences can be customized by users depending on whether they use internal mics or external lavalier/USB microphones.

---

## Audio Channels: Mono vs Stereo

- **Mono (1 channel)**:
  - Default on many budget devices or basic camcorder profiles.
  - Highly robust, low bandwidth (~64-128 kbps AAC).
- **Stereo (2 channels)**:
  - Requires hardware support (multiple physical microphones placed across the chassis).
  - Provides spatial realism and separation (~192-320 kbps AAC).
  - Must fall back gracefully to mono if the device fails to configure stereo audio buffers.

---

## Audio/Video (A/V) Synchronization Mechanics

Maintaining synchronized audio and video over a long recording (preventing lip-sync drift) relies on **Presentation Time Stamps (PTS)**:
1. Every captured video frame receives a hardware timestamp from the camera HAL (in nanoseconds, relative to monotonic clock `System.nanoTime()`).
2. Audio sample buffers from `AudioRecord` receive corresponding timestamps based on audio hardware capture times.
3. The multiplexer (Muxer / `MediaRecorder`) interleaves video and audio frames into the output container (MP4), aligning packets by their PTS.

```
Monotonic Clock: 0ms ────────► 33ms ────────► 66ms ────────► 100ms
Video Frames:   [PTS: 0]      [PTS: 33]     [PTS: 66]     [PTS: 100]
Audio Packets:  [PTS: 0] [PTS: 21] [PTS: 42] [PTS: 63] [PTS: 84] [PTS: 105]
Container Mux:  [V0][A0][A1][V1][A2][A3][V2][A4][A5][V3]...
```

If the CPU or encoder becomes overwhelmed and drops frames, PTS ensures the video remains time-aligned rather than playing audio ahead of video.

---

## Code Reference in OpenKamera

- [`VideoProfile.kt`](file:///Users/ridhanfadhilah/Public/Fadhil/mobile/android/studio-lab/project-open-camera/OpenKamera/app/src/main/java/com/hightechif/openkamera/preview/VideoProfile.kt): Stores `audioSource`, `audioChannels`, `audioBitRate`, and `audioSampleRate`.
- [`Camera2VideoPipeline.kt`](file:///Users/ridhanfadhilah/Public/Fadhil/mobile/android/studio-lab/project-open-camera/OpenKamera/app/src/main/java/com/hightechif/openkamera/cameracontroller/Camera2VideoPipeline.kt): Configures `MediaRecorder` audio settings before recording begins.

---

## Next Steps

Head over to [Module 05 — Advanced Architecture & Ecosystem](../module-05-advanced/) to learn about reactive metadata flows, HUD rendering overlays, and clean architecture abstractions.

# Module 04 — Lesson 02: MediaRecorder & Video Codecs

> **Module:** 04 — Video | **Level:** Intermediate | **Prerequisite:** [Lesson 01 — Video Pipeline Overview](./01-video-pipeline-overview.md)

---

## CamcorderProfile vs Custom Configuration

Android provides `CamcorderProfile` as a baseline for device-tested presets (e.g. `QUALITY_1080P`, `QUALITY_4K`, `QUALITY_HIGH`). However, relying strictly on `CamcorderProfile` has severe limitations:
- It hardcodes specific bitrates and frame rates, preventing user customization.
- It cannot easily represent arbitrary aspect ratios (such as 1:1 square or 21:9 cinema).
- It lacks fine-grained overrides for modern codecs like HEVC / H.265.

OpenKamera uses a specialized [`VideoProfile`](file:///Users/ridhanfadhilah/Public/Fadhil/mobile/android/studio-lab/project-open-camera/OpenKamera/app/src/main/java/com/hightechif/openkamera/preview/VideoProfile.kt) class that can initialize from `CamcorderProfile`, while permitting manual user overrides:
- Video dimensions (`videoFrameWidth`, `videoFrameHeight`)
- Video codec (`H.264`, `H.265 / HEVC`)
- Video bitrate (e.g. 5 Mbps up to 100+ Mbps for 4K)
- Frame rate and capture rate (for slow-motion or timelapse)

---

## Codec Comparison: H.264 (AVC) vs H.265 (HEVC)

| Feature | H.264 / AVC | H.265 / HEVC |
|---|---|---|
| **Compression Efficiency** | Standard baseline | ~40-50% smaller file size at identical visual quality |
| **Hardware Encoding Support** | Supported on virtually all Android devices | Universal on Android 7.0+ mid/flagship devices |
| **Playback Compatibility** | Extremely high across older browsers and platforms | Excellent on modern devices; may require software decoding on older PCs |
| **Battery / Thermal Load** | Low encoding overhead | Higher thermal overhead during sustained 4K/60fps recording |

---

## Bitrate vs Quality Tradeoffs

Video bitrate measures the amount of data encoded per second (in bits per second):
$$\text{File Size} \approx \frac{\text{Bitrate (bps)} \times \text{Duration (seconds)}}{8}$$

- **Too Low Bitrate**: Compression artifacts appear during high motion (macroblocking, banding in gradients, blurriness).
- **Too High Bitrate**: Rapidly consumes device storage and may exceed hardware encoder bandwidth limits, resulting in dropped frames.

---

## High Frame Rate (HFR) & Slow Motion

Recording slow-motion video (e.g. 120fps or 240fps) requires specialized Camera2 handling:
1. **`CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO`**: Sensor must support high-speed streaming.
2. **`createConstrainedHighSpeedCaptureSession`**: High-speed mode operates under strict constraints (max 2 surfaces, specific resolution/fps combinations).
3. **Capture vs Playback Rate**:
   - `videoFrameRate = 30` (standard playback speed)
   - `videoCaptureRate = 120` (sensor captures 120 fps, creating a $4\times$ slow-motion playback effect)

---

## Next Steps

Learn how audio tracks are sampled, encoded, and synced with video in [Lesson 03 — Audio Sources & Sync](./03-audio-sources-and-sync.md).

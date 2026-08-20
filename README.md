# OpenKamera

[![Version](https://img.shields.io/badge/Base%20Version-Open%20Camera%20v1.56.2-blue.svg)](https://opencamera.org.uk/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-purple.svg?style=flat&logo=kotlin)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Platform-Android-green.svg?style=flat&logo=android)](https://developer.android.com)
[![Target SDK](https://img.shields.io/badge/Target%20SDK-36-blue.svg)](https://developer.android.com)
[![Min SDK](https://img.shields.io/badge/Min%20SDK-23-orange.svg)](https://developer.android.com)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)

> **Note:** **OpenKamera** is a modernized **Kotlin version** of the popular open-source [Open Camera](https://opencamera.org.uk/) application (based on **v1.56.2**) originally developed by **Mark Harman**. The Kotlin port, architecture modernization, and ongoing maintenance are developed by **Ridhan Fadhilah**.

---

## 📷 Overview

**OpenKamera** is a powerful, fully open-source camera application for Android smartphones and tablets. It translates the robust, feature-rich Java codebase of Open Camera (v1.56.2) into idiomatic Kotlin while targeting modern Android APIs and Gradle toolchains.

OpenKamera is designed with privacy and user control in mind: **no ads, no tracking, and no unnecessary permissions**.

---

## ✨ Features

### 🎯 Camera & Manual Controls (Camera2 API)
- **Manual Focus & Exposure**: Full control over focus distance, ISO, shutter speed (exposure time), and white balance temperature.
- **Exposure Compensation & Lock**: Lock exposure and white balance to maintain consistent lighting.
- **Focus Modes**: Auto, Continuous, Macro, Infinity, Manual, and Locked focus modes.
- **RAW Capture**: Support for RAW (DNG) image capture on supported hardware.

### 📸 Photography Modes
- **Auto-Level / Auto-Stabilize**: Keep photos perfectly aligned regardless of device tilt.
- **HDR (High Dynamic Range)**: Capture and blend multiple exposures for high-contrast scenes.
- **DRO (Dynamic Range Optimization)**: Enhance shadows and balance highlights in a single shot.
- **Panorama & Focus Bracketing**: Multi-shot stitching and deep depth-of-field capture.
- **Burst & Fast Burst Modes**: High-speed multi-frame capture with configurable delays.
- **Noise Reduction**: Advanced multi-frame noise reduction.

### 🎥 Video Recording
- **Resolution & Frame Rates**: Support up to 4K UHD, high frame rate (HFR), and slow motion.
- **Bitrate & Profiles**: Configurable video bitrates, frame rates, and color profiles.
- **Audio Options**: Choose audio sources (external mic, internal mic, uncalibrated), channels (mono/stereo), and sample rates.
- **Video Stabilization**: Digital video stabilization (EIS) support.

### 🛠️ Overlays, Tools & Accessibility
- **On-Screen Display**: Real-time histogram, zebra stripes (highlight clipping), and focus peaking.
- **Composition Guides**: 3x3 grids, golden ratio, diagonal guides, and custom crop overlays.
- **Angle & Compass**: Live orientation indicator, pitch/roll level, and compass direction.
- **Geotagging & Stamping**: GPS location tagging, timestamping, date stamping, and custom text stamps on photos.
- **Remote Controls**: Audio trigger (shout/whistle), timer, repeat mode, and Bluetooth remote control support.
- **UI Customization**: Optimized for both left-handed and right-handed use, immersive full-screen mode, and configurable quick controls.

---

## 🏗️ Architecture & Packages

The codebase is organized into modular packages under `com.hightechif.openkamera`:

```
com.hightechif.openkamera/
├── audio/            # Audio trigger, speech recognition, and sound effects
├── cameracontroller/ # Unified abstraction for Camera1 and Camera2 APIs
├── preferences/      # SharedPreferences management, keys, and settings UI
├── preview/          # Camera preview surface, rendering, and lifecycle
├── processing/       # Image processing, HDR alignment, and computations
├── remotecontrol/    # Bluetooth LE and remote shutter controls
├── sensors/          # Accelerometer, compass, and orientation sensors
├── storage/          # Storage Access Framework (SAF), Exif, and ImageSaver
├── system/           # Android system integrations, permissions, and services
├── ui/               # UI components, popup menus, and custom view overlays
├── utils/            # General helpers, math, and string formatters
├── MainActivity.kt   # Core application entrypoint and activity coordinator
└── TakePhoto.kt      # Lightweight shortcut widget activity
```

---

## 🛠️ Tech Stack & Requirements

- **Language:** [Kotlin](https://kotlinlang.org/) `2.0.21`
- **Build System:** Gradle with Version Catalogs (`gradle/libs.versions.toml`)
- **Android Gradle Plugin (AGP):** `8.9`
- **JDK / Toolchain:** Java 17
- **Target SDK:** 36 (Android 15 / 16 Preview)
- **Minimum SDK:** 23 (Android 6.0 Marshmallow)
- **Testing:** JUnit 4, Robolectric `4.14.1`, MockK `1.13.16`, AndroidX Test & Espresso

---

## 🚀 Getting Started

### Prerequisites
- [Android Studio Ladybug | 2024.2+](https://developer.android.com/studio) or newer
- JDK 17
- Android SDK Platform 36 and Build Tools

### Building from Source

Clone the repository and open the `OpenKamera` directory:

```bash
# Navigate to the OpenKamera directory
cd OpenKamera

# Build the debug APK
./gradlew assembleDebug

# Run unit tests
./gradlew testDebugUnitTest

# Install to a connected device
./gradlew installDebug
```

---

## 🧪 Testing

OpenKamera includes unit tests, Robolectric JVM tests, and instrumentation tests:

```bash
# Run JVM unit tests with Robolectric and MockK
./gradlew test

# Run Android connected device instrumentation tests
./gradlew connectedAndroidTest
```

---

## 👥 Authors, Credits & License

- **Original Author & Base Codebase:** Mark Harman — [Open Camera v1.56.2](https://opencamera.org.uk/)
- **Kotlin Version Developer (OpenKamera):** Ridhan Fadhilah
- **License:** Released under the terms of the [GNU General Public License v3.0 (GPLv3)](LICENSE).
- Third-party components, AndroidX libraries, and Google Material Design icons are licensed under their respective open-source licenses (see [LICENSE](LICENSE), `_docs/credits.html`, `androidx_LICENSE-2.0.txt`, and `google_material_design_icons_LICENSE-2.0.txt`).

# Android Project Guide

> This file provides guidance to AI agents when working with code in this repository. Keep project-specific setup here and link detailed engineering standards to the Android rule files.

Rule files live in `.claude/skills/android-guide/rules/` (invoke via `/android-guide`).

---

## Project Identity

```text
Application Name    : Open Kamera (OpenKamera)
Package Name        : com.hightechif.openkamera
Min SDK             : 23
Target SDK          : 36
Compile SDK         : 36
Version Name        : 1.56.2
Version Code        : 96
```

**Detected project type:** View-based (XML layouts under `res/layout/`, Hilt DI, Kotlin, coroutines + StateFlow), **single-module** (`:app` only). Native C++ code via CMake (`app/src/main/cpp`). Based on Open Camera (GPL-3.0).

---

## Build Flavors

No product flavors are declared. Only the default `debug` and `release` build types exist (`applicationId` = `com.hightechif.openkamera`, test app ID `com.hightechif.openkamera.test`).

---

## Technology Stack

| Layer             | Technology                                                 | Rule                                                      |
| ----------------- | ---------------------------------------------------------- | --------------------------------------------------------- |
| Project detection | View-based, single-module                                  | `rules/1-project-detection.md`                            |
| UI                | XML layouts, Camera2, custom views (`ui/`, `preview/`)     | `rules/3-view-based.md`                                   |
| DI                | Hilt 2.51.1 via KSP (`di/` package)                        | `rules/3-view-based.md`                                   |
| Async             | Kotlin Coroutines 1.9.0, StateFlow / SharedFlow            | `rules/8-code-quality.md`                                 |
| Local storage     | SharedPreferences (`preferences/`), SAF + Exif (`storage/`) | `rules/7-local-storage.md`                                |
| Code quality      | Kotlin 2.0.21, AGP 8.7.3, JDK 17, version catalog          | `rules/8-code-quality.md`                                 |
| Security          | Permissions, storage, sensitive files                      | `rules/9-security.md`                                     |
| Testing           | JUnit4, Robolectric, MockK, Turbine, Espresso              | `rules/10-unit-testing.md`                                |
| Review            | Code review checklist and Git safety                       | `rules/11-code-review.md`                                 |

Networking and mapping rules (`rules/5-networking.md`, `rules/6-mapping.md`) apply only if such layers are introduced; the app currently has no networking layer.

### Package layout (`app/src/main/java/com/hightechif/openkamera`)

`audio/`, `cameracontroller/` (Camera2 + `capabilities/`), `di/`, `domain/` (`engine`, `interactor`, `model`, `repository`, `usecase`), `lifecycle/`, `preferences/`, `preview/`, `processing/`, `remotecontrol/`, `sensors/`, `storage/`, `system/`, `ui/`, `utils/`, plus `MainActivity.kt`, `MainActivityLegacyGlue.kt`, `MyApplicationInterface.kt`, `TakePhoto.kt`.

---

## Mandatory Reading Order

1. Read `rules/1-project-detection.md`.
2. Read `rules/3-view-based.md` (matching architecture rule for this project).
3. Read the layer rule for the code being changed: mapping, local storage, code quality, security, or testing.
4. For reviews, read `rules/11-code-review.md` before writing findings.

| Topic                   | Rule                                     |
| ----------------------- | ---------------------------------------- |
| Project detection       | `rules/1-project-detection.md`           |
| View-based architecture | `rules/3-view-based.md`                  |
| Networking and API layer | `rules/5-networking.md`                 |
| Mapping                 | `rules/6-mapping.md`                     |
| Local storage           | `rules/7-local-storage.md`               |
| Code quality, Gradle, resources | `rules/8-code-quality.md`        |
| Security                | `rules/9-security.md`                    |
| Unit testing            | `rules/10-unit-testing.md`               |
| Code review and Git safety | `rules/11-code-review.md`             |

---

## Project Commands

```bash
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew testDebugUnitTest
./gradlew test
./gradlew connectedDebugAndroidTest
./gradlew installDebug
./gradlew lint
./gradlew clean

# Single instrumented test class
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.hightechif.openkamera.test.MainInstrumentedTest
```

---

## Agent Workflow

- Log every AI-made change to `ai.log` in the project root using `[YYYY-MM-DD HH:MM:SS] <brief description>`.
- Ask before changing ambiguous architecture, dependency, SDK, or module decisions.
- Never run `git push --force` / `--force-with-lease`; if a push is rejected, explain and ask.
- Do not run `git commit`, `git push`, or destructive file operations unless explicitly requested.
- Before creating a service, datasource, repository, database, mapper, UseCase, or shared component, check whether a matching implementation already exists.
- Preserve project-specific setup notes, environment variables, and credential instructions.
- A `graphify-out/` knowledge graph exists; consult it first for architecture questions.

---

## Important Local References

```text
gradle/libs.versions.toml
app/src/main/AndroidManifest.xml
app/build.gradle
build.gradle
settings.gradle
README.md
openspec/
```

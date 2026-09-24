# Module 03 — Lesson 05: RAW & DNG Capture

> **Module:** 03 — Photography | **Level:** Intermediate | **Prerequisite:** [Lesson 04 — HDR Image Processing](./04-hdr-image-processing.md)

---

## What is RAW Data?

Standard JPEG images undergo significant hardware processing inside the Android camera pipeline:
1. **Demosaicing**: Interpolating color channels.
2. **White Balance & Color Correction**: Hardcoding color temperatures.
3. **Gamma Curve & Tone Mapping**: Compressing 10/12/14-bit sensor data into 8-bit channels.
4. **Lossy Compression**: Discarding subtle gradients to minimize file size.

**RAW sensor capture** skips this processing and captures direct, linear sensor measurements before ISP processing.

---

## The Bayer Color Filter Array (CFA)

Camera sensors cannot measure color natively; they measure total photon count (light intensity). To capture color, a microscopic color filter array is placed directly above the photosites, most commonly in a **Bayer pattern**:

```
┌───┬───┐
│ R │ G │  <- Row 1 (Red, Green)
├───┼───┤
│ G │ B │  <- Row 2 (Green, Blue)
└───┴───┘
```

Notice there are twice as many Green (G) pixels as Red (R) or Blue (B). This mimics human visual sensitivity to green wavelengths.

---

## The DNG (Digital Negative) Format

RAW files cannot be displayed directly as RGB pixels until demosaiced. To allow standard photo editors (Lightroom, Photoshop, Snapseed) to open raw camera output, Adobe defined the open **DNG (Digital Negative)** specification.

In Android, the `DngCreator` utility handles wrapping raw pixel buffers into a compliant TIFF/DNG file:

```kotlin
val dngCreator = DngCreator(cameraCharacteristics, captureResult)
// Write raw image and sensor metadata directly to output stream
dngCreator.writeImage(outputStream, rawImage)
```

`DngCreator` writes:
- Linear 10/12/14-bit Bayer raw pixels.
- Sensor color matrices and calibration illuminants from `CameraCharacteristics`.
- Dynamic capture metadata (exposure time, ISO, focal length, GPS) from `CaptureResult`.

---

## Capturing RAW in Camera2

To capture RAW frames:
1. Check hardware support: Verify `CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES` contains `REQUEST_AVAILABLE_CAPABILITIES_RAW`.
2. Configure a secondary `ImageReader` with format `ImageFormat.RAW_SENSOR`:
   ```kotlin
   val rawReader = ImageReader.newInstance(rawWidth, rawHeight, ImageFormat.RAW_SENSOR, 2)
   ```
3. Include the `rawReader.surface` in the capture session configuration alongside the preview and JPEG surfaces.
4. When issuing a still capture request, add `rawReader.surface` as a target.

---

## Code Reference in OpenKamera

- See [`CameraController2.kt`](../../app/src/main/java/com/hightechif/openkamera/cameracontroller/CameraController2.kt):
  - RAW `ImageReader` instantiation with `ImageFormat.RAW_SENSOR`.
  - Concurrent dispatch of JPEG and RAW buffers.
  - Integration with `DngCreator` for saving `.dng` files.

---

## Exercises & Next Steps

Next, head to [Module 04 — Video](../module-04-video/) to explore hardware-accelerated video recording and audio synchronization.

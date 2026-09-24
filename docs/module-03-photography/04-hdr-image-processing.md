# Module 03 — Lesson 04: HDR Image Processing

> **Module:** 03 — Photography | **Level:** Intermediate | **Prerequisite:** [Lesson 03 — Burst & Exposure Bracketing](./03-burst-and-bracketing.md)

---

## What is High Dynamic Range (HDR)?

Standard camera sensors have a limited dynamic range compared to human eyes. When shooting high-contrast scenes (e.g. a portrait in front of a bright window), a standard exposure results in either a silhouette (underexposed subject) or a completely blown-out background (overexposed sky).

**HDR photography** solves this by fusing multiple bracketed photos into a single image that contains both deep shadows and highlight details.

```
[Under-exposed Frame]  ──┐
[Standard Frame]       ──┼──► [ Alignment ] ──► [ Fusion & Tone Mapping ] ──► [ Final HDR Image ]
[Over-exposed Frame]   ──┘
```

---

## Step 1: Multi-Frame Image Alignment

When shooting handheld, the camera moves slightly between frames. Direct blending would create severe ghosting and blur.

Before fusing frames, an **alignment algorithm** computes offset vectors $(dx, dy)$ between each secondary frame and the base reference frame:
- **MTB (Median Threshold Bitmap)**: Converts frames into binary 1-bit bitmaps using local luminance medians, making the alignment invariant to exposure differences.
- Computes pyramid levels (downsampled representations) to calculate coarse offsets first, then refines at higher resolutions.

---

## Step 2: Weighting & Blending

Pixels from different exposures are weighted according to their quality:
- Pixels close to 0 (crushed blacks) or 255 (clipped whites) have very low signal-to-noise or are saturated; their weights are near zero.
- Pixels in the well-exposed midtone range (around 128) receive maximum weight.

```
Weight
  ▲
1 ┤           .---.
  │          /     \
  │         /       \
  │        /         \
0 └───────'───────────'──► Luminance (0 - 255)
  0                 255
```

---

## Step 3: Tone Mapping

Once the high dynamic range luminance map is reconstructed, it must be compressed back into standard 8-bit sRGB display space while retaining local contrast.

Common tone-mapping techniques:
- **Global tone mapping**: Applies a single non-linear transfer curve (e.g. Reinhard tone reproduction) across the whole image.
- **Local tone mapping**: Adapts contrast locally using bilateral filtering or multi-scale decompositions to avoid "washed out" flat appearances.

---

## Implementation in OpenKamera

OpenKamera performs HDR processing via:
- [`HDRProcessor.kt`](../../app/src/main/java/com/hightechif/openkamera/processing/HDRProcessor.kt): Coordinates multi-frame acquisition, downsampling, alignment pyramids, and invokes rendering algorithms.
- [`JavaImageFunctionsHDR.kt`](../../app/src/main/java/com/hightechif/openkamera/processing/JavaImageFunctionsHDR.kt): Provides pure CPU fallback implementations for pixel blending, histogram analysis, and tone curve computations when GPU/RenderScript acceleration is unavailable or during background testing.

---

## Next Steps

Discover how uncompressed raw sensor data can be captured and preserved in [Lesson 05 — RAW & DNG Capture](./05-raw-and-dng-capture.md).

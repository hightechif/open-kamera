# Module 00 — Lesson 05: Color Science & White Balance

Have you ever taken a photo indoors under ordinary living room lamps, only to find everyone’s skin looking sickly orange or yellow? Or taken a picture in the snow on an overcast day, only to have the entire photo drenched in cold blue?

This happens because light is rarely pure white. Every light source emits electromagnetic radiation with a distinct spectral cast, a physical property known as **Color Temperature**.

---

## 1. The Kelvin Color Temperature Scale

In physics, color temperature is measured on the **Kelvin scale (K)**. It is based on the color of light emitted by an idealized theoretical black-body radiator as it is heated to incandescence:

```
                      THE KELVIN COLOR SCALE
 
 1800K           3200K           5500K           6500K           8500K+
 [ Amber/Warm ] ─ [ Yellow ] ─── [ Neutral ] ─── [ Light Blue ] ─ [ Deep Blue ]
   Candlelight      Tungsten /     Direct Noon     Overcast /      Deep Blue
   Campfire         Household      Sunlight        Cloudy Day      Sky Shade
                    Lightbulbs
```

* **Warm Light (Low Kelvin, 1800K - 3200K):** Dominated by longer wavelengths (reds, oranges, warm ambers). Think of candle flames, street lamps, and domestic incandescent bulbs.
* **Neutral Light (~5500K):** The benchmark for photographic balance: midday sun in a clear sky.
* **Cool Light (High Kelvin, 6500K - 9000K+):** Dominated by shorter wavelengths (blues and cyans). Think of open shade on a clear sunny day or heavy overcast clouds.

---

## 2. Why the Human Eye Adapts, but Cameras Do Not

The human brain possesses an incredible feature called **chromatic adaptation**. If you read a book under a warm 2700K bedside lamp, your visual cortex recalibrates within seconds: the white paper looks white to you. If you step outside into 7000K blue daylight, the paper still looks white.

A CMOS image sensor has no such biological intelligence. It measures raw physical photons:
* Under a 2700K lamp, the red and green photodiodes are flooded with photons, while blue photodiodes receive almost nothing.
* Under an open blue sky, the blue photodiodes overflow while reds are suppressed.

Unless corrected, the resulting photograph will faithfully record that severe orange or blue tint.

---

## 3. Auto White Balance (AWB) & Its Traps

To correct for this, cameras use an algorithm called **Auto White Balance (AWB)**. AWB searches the scene for what it assumes *should* be neutral white or neutral gray (such as clouds, a white shirt, or a concrete wall) and applies mathematical gain multipliers to the Red, Green, and Blue channels until those pixels equal neutral $R = G = B$.

While modern AWB is capable, it has fundamental creative flaws:

### The Sunset Disaster
During golden hour, the sun dips toward the horizon, bathing the landscape in magnificent 2500K deep amber and crimson light.
* **What AWB does:** The algorithm detects the intense orange cast across the entire frame and thinks: *"Error! The scene has an extreme warm color cast. I must pump up the blue channel and suppress the red channel to neutralize this!"*
* **The Result:** The camera neutralizes the magic of the golden hour, turning the rich, romantic sunset into sterile, cold daylight gray.

### Mixed Lighting Nightmares
If you shoot a subject lit by an indoor lamp (3000K) near an open window admitting blue twilight (7000K), AWB cannot choose. If it balances for the window, the lamp turns radioactive orange; if it balances for the lamp, the window turns neon blue.

---

## 4. Manual White Balance Presets & Custom Kelvin

When creative mood or consistency matters, photographers switch from Auto White Balance to explicit presets or manual Kelvin tuning:

| Mode / Preset | Typical Kelvin | What It Compensates For | Creative Mood |
| :--- | :--- | :--- | :--- |
| **Incandescent / Tungsten** | $\sim 3200\text{K}$ | Adds blue to cancel indoor yellow lamps | Cool, moody night scenes |
| **Fluorescent** | $\sim 4000\text{K}$ | Adds magenta to cancel greenish office tubes | Clean commercial look |
| **Daylight / Sunny** | $\sim 5500\text{K}$ | Neutral; accepts direct sun as baseline | Natural outdoor realism |
| **Cloudy** | $\sim 6500\text{K}$ | Adds amber to warm up cool overcast clouds | Warms up skin tones outdoors |
| **Shade** | $\sim 7500\text{K}$ | Adds intense warm tones to counteract blue shade | Golden, rich warmth |

> **Pro Tip:** Want to guarantee a warm, golden sunset? **Lock your White Balance to "Cloudy" ($6500\text{K}$) or "Shade" ($7500\text{K}$)**. This tells the camera: *"Expect cool blue light, so add warm amber tones!"* When applied to a sunset, it intensifies the fiery glow rather than washing it out.

---

## 5. In OpenKamera & Camera2

OpenKamera lets you override Auto White Balance directly through the White Balance settings menu.

In Camera2, this is governed by the `CONTROL_AWB_MODE` request key:

```kotlin
// Switching between Auto and Manual White Balance presets
captureRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT)
// Options: CONTROL_AWB_MODE_AUTO, CONTROL_AWB_MODE_DAYLIGHT, 
// CONTROL_AWB_MODE_CLOUDY_DAYLIGHT, CONTROL_AWB_MODE_SHADE, CONTROL_AWB_MODE_FLUORESCENT

// For fine-grained custom Kelvin tuning on supported devices:
captureRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
captureRequestBuilder.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
captureRequestBuilder.set(CaptureRequest.COLOR_CORRECTION_GAINS, rggbGains)
```

When you shoot in **RAW (DNG)** mode in OpenKamera, the White Balance setting is non-destructive! RAW files save the raw uncorrected sensor photons, storing your chosen Kelvin value as metadata tags that can be re-adjusted losslessly in editing software like Lightroom or RawTherapee.

* **Learn more in code:** [`PreferenceKeys.kt`](../../app/src/main/java/com/hightechif/openkamera/preferences/PreferenceKeys.kt) manages white balance preferences, and [`Camera2PhotoPipeline.kt`](../../app/src/main/java/com/hightechif/openkamera/cameracontroller/Camera2PhotoPipeline.kt) applies the selected AWB mode to capture requests.

---

## Summary Checklist
- [x] **Color Temperature** is measured in **Kelvin (K)**: low Kelvin ($2000\text{K}$) is warm/amber; high Kelvin ($8000\text{K}$) is cool/blue.
- [x] The human brain adapts to color temperature automatically; camera sensors record raw spectral physics.
- [x] **Auto White Balance (AWB)** attempts to neutralize color casts, which can accidentally erase warm sunsets.
- [x] To preserve sunset colors or create warm skin tones, lock White Balance to **Cloudy** or **Shade**.
- [x] Shooting in **RAW (DNG)** preserves full sensor data, allowing lossless post-capture white balance adjustments.

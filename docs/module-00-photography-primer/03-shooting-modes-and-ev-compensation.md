# Module 00 — Lesson 03: Shooting Modes & EV Compensation

When you hold a camera, you are engaged in a constant negotiation: **How much control should you hand over to the camera's computer, and how much should you keep for yourself?**

Camera manufacturers developed standard shooting modes to manage this balance. Understanding these modes unlocks the ability to shoot intentionally rather than hoping the camera guesses right.

---

## 1. The Standard Shooting Modes Spectrum

On the mode dial of a DSLR, or in the mode selectors of advanced camera apps, you will see four classic letters: **P**, **S** (or **Tv**), **A** (or **Av**), and **M**.

```
  AUTOMATION ◄─────────────────────────────────────────────► CREATIVE CONTROL
 
 ┌───────────────┐     ┌───────────────┐     ┌───────────────┐     ┌───────────────┐
 │   Auto / P    │     │ Shutter (Tv/S)│     │ Aperture (Av) │     │  Manual (M)   │
 │   (Program)   │     │ (Time Value)  │     │(Aperture Val) │     │               │
 ├───────────────┤     ├───────────────┤     ├───────────────┤     ├───────────────┤
 │ Camera sets:  │     │ You set:      │     │ You set:      │     │ You set:      │
 │ • Shutter     │     │ • Shutter     │     │ • Aperture    │     │ • Shutter     │
 │ • Aperture    │     │               │     │               │     │ • ISO         │
 │ • ISO         │     │ Camera sets:  │     │ Camera sets:  │     │ • Focus       │
 │               │     │ • ISO         │     │ • Shutter     │     │               │
 │               │     │ • (Aperture)  │     │ • ISO         │     │ Camera sets:  │
 │               │     │               │     │               │     │ • Nothing!    │
 └───────────────┘     └───────────────┘     └───────────────┘     └───────────────┘
```

### A. Program / Auto Mode (P)
* **What it does:** The camera measures ambient light and calculates a "safe" combination of shutter speed and ISO that avoids both motion blur and extreme noise.
* **When to use it:** Quick snapshots, street candid shots, documentary shooting where moments happen too quickly to adjust settings.
* **Why it falls short:** The camera has no artistic vision. It does not know if you want to freeze a waterfall or make it look silky; it just tries to achieve an average exposure.

### B. Shutter Priority Mode (S or Tv)
* **What it does:** You explicitly lock the shutter speed (e.g. $1/2000\text{s}$ or $1/4\text{s}$). The camera automatically adjusts ISO (and aperture on lenses that support it) to maintain proper exposure.
* **When to use it:**
  * **Sports & Action:** Lock to $1/1000\text{s}$ to guarantee crisp, freeze-frame photos.
  * **Panning Shots:** Lock to $1/30\text{s}$ while moving your camera along with a speeding car to blur the background into motion streaks while keeping the car sharp.
  * **Light Trails:** Lock to $2\text{s}$ or $4\text{s}$ on a tripod to record car headlights.

### C. Aperture Priority Mode (A or Av)
* **What it does:** You lock the lens aperture ($f$-number) to govern your Depth of Field (background blur). The camera calculates the necessary shutter speed and ISO.
* **The Smartphone Nuance:** Because smartphone cameras have fixed physical apertures (as explained in [Lesson 02](./02-the-exposure-triangle.md)), true optical Aperture Priority does not exist on mobile. Instead, smartphone "Portrait Modes" achieve this effect through computational stereoscopic depth mapping and synthetic Gaussian/bokeh rendering.

### D. Manual Mode (M / Pro)
* **What it does:** You take total manual control over Shutter Speed, ISO, White Balance, and Focus. The camera’s automated algorithms step aside.
* **When to use it:**
  * **Astrophotography & Night Skyscapes:** Auto-exposure fails completely in pitch darkness; you must lock $15\text{s}$ and ISO 3200 manually.
  * **Consistent Lighting Studio & Panoramas:** When stitching multiple photos together, you need every frame to have the exact same brightness.
  * **High-Contrast Creative Scenes:** Light painting, silhouettes, and stage concerts.

---

## 2. The 18% Middle Gray Metering Trap

To understand why camera modes fail, you must understand how a camera "sees" a scene.

A camera sensor does not have human intelligence. When the Auto-Exposure (AE) algorithm inspects the scene, it averages all the incoming light and attempts to calibrate the exposure so the average brightness equals **18% Middle Gray** (the perceived optical midpoint between pure black and pure white).

```
   Darkest Tone (0%)          18% Middle Gray           Brightest Tone (100%)
  ┌─────────────────────────────────┬─────────────────────────────────┐
  │ Pure Black                      │ Neutral Gray                    │ Pure White
  └─────────────────────────────────┴─────────────────────────────────┘
                                    ▲
                      Camera AE meter always aims HERE
```

This 18% assumption works well for average scenes (a person standing in front of green trees and brick walls). But it breaks down in high-contrast environments:

### The Snow Problem
When you point your camera at a pristine snow-covered mountain, the camera sees an overwhelming amount of bright white. The camera thinks: *"Whoa, this scene is way too bright! I must reduce the exposure until it averages 18% gray."*
* **The Result:** Your brilliant white snow turns into muddy, gloomy, dark gray slush.

### The Black Cat Problem
When you point your camera at a black cat on a dark black sofa, the camera thinks: *"This scene is way too dark! I must boost shutter speed and ISO until it averages 18% gray."*
* **The Result:** Your deep black scene is overexposed into a washed-out, noisy gray picture.

---

## 3. Exposure Compensation (+/- EV)

How do you fix this without switching to full manual mode? By using **Exposure Compensation** (often represented by the `+/-` icon on cameras).

Exposure Compensation allows you to tell the camera's auto-exposure system:
> *"Keep calculating the exposure automatically, but make the final target +1.0 stop brighter or -1.0 stop darker than your 18% gray formula."*

```
  -2.0 EV            -1.0 EV             0.0 EV            +1.0 EV            +2.0 EV
 ┌──────────────────────────────────────────────────────────────────────────────────┐
 │ Underexposed     Moody Shadows       Standard AE       Rich Whites        High-Key   │
 │ (Silhouettes)    (Concerts)          (Average scene)   (Beach / Snow)     (Fashion)  │
 └──────────────────────────────────────────────────────────────────────────────────┘
```

### When to Use EV Adjustments:
1. **Shooting on Snow or Bright Sand:** Dial to **$+1.0\text{ EV}$ to $+1.7\text{ EV}$** to restore the dazzling brilliance of the whites.
2. **Shooting Golden Hour Silhouettes:** Dial to **$-1.0\text{ EV}$ to $-1.5\text{ EV}$** to plunge the subject into deep black and saturate the orange sky.
3. **Stage Performances & Concerts:** Spotlight on an actor in a dark theater causes the camera to overexpose the face; dial to **$-1.0\text{ EV}$**.

---

## 4. In OpenKamera & Camera2

OpenKamera exposes Exposure Compensation right on the main preview screen via the exposure slider.

Under the hood, Camera2 does not adjust EV as a floating point number directly; it uses discrete integer steps (`steps`), defined by the device's hardware characteristics:

```kotlin
// 1. Read hardware compensation step size (e.g. 1/3 EV or 1/2 EV per step)
val stepRational = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)

// 2. Read hardware compensation range (e.g. -6 to +6 steps)
val range = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)

// 3. Set the desired compensation value on the capture request
captureRequestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, stepIndex)
```

When you slide the exposure bar in OpenKamera:
* The camera HAL dynamically scales either the auto-shutter duration or sensor gain while preserving automatic metering convergence.
* In full Manual mode, OpenKamera sets `CONTROL_AE_MODE` to `CONTROL_AE_MODE_OFF`, disengaging auto-metering entirely.

* **Learn more in code:** [`PreferenceKeys.kt`](../../app/src/main/java/com/hightechif/openkamera/preferences/PreferenceKeys.kt) stores exposure step preferences, and [`Camera2PhotoPipeline.kt`](../../app/src/main/java/com/hightechif/openkamera/cameracontroller/Camera2PhotoPipeline.kt) applies the compensation values to capture requests.

---

## Summary Checklist
- [x] **Auto (P)** balances speed and noise automatically, but lacks artistic intent.
- [x] **Shutter Priority (Tv/S)** is essential for motion control (sports, freezing splashes, light trails).
- [x] **Manual (M)** gives total command over shutter duration, ISO, and focus distance.
- [x] Camera light meters assume the world is **18% middle gray**, which leads to underexposed snow and overexposed dark scenes.
- [x] **Exposure Compensation (+/- EV)** overrides the camera's auto meter without needing to go into full manual mode.

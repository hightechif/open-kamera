# Module 00 — Lesson 01: Light, Sensors, & Exposure

Before diving into camera code or manual exposure parameters, we must understand the fundamental physical currency of photography: **light**.

A camera does not record "objects" or "people." A camera is a photon counter. Every photograph is created by capturing light bouncing off physical matter and converting that electromagnetic energy into digital numbers.

---

## 1. From Photons to Pixels

At the heart of every camera (whether a $5,000 full-frame mirrorless body or a tiny smartphone lens) is a **CMOS image sensor**.

```
   Incoming Light (Photons)
         ↓↓↓↓↓↓↓↓
   ┌──────────────────┐  <── Micro-lenses (Focus light into each pixel)
   │  R  │  G  │  B   │  <── Bayer Color Filter Array (Separates Red, Green, Blue)
   ├─────┼─────┼──────┤
   │  □  │  □  │  □   │  <── Photodiodes / Photosites (Silicon "buckets")
   └─────┴─────┴──────┘
         │
         ▼ [Analog Voltage]
   ┌──────────────────┐
   │       ADC        │  <── Analog-to-Digital Converter (e.g. 10-bit / 12-bit / 14-bit)
   └──────────────────┘
         │
         ▼ [Digital Numbers]
   Raw Sensor Output (0 - 4095 for 12-bit)
```

1. **Photons strike the photosite**: Each pixel on the sensor contains a silicon photodiode that acts like a microscopic bucket collecting raindrops. The longer the bucket is exposed to light (shutter speed), the more charge it accumulates.
2. **Charge conversion**: The photodiode converts the accumulated photons into an electrical voltage.
3. **Amplification (Gain/ISO)**: Before digitization, this faint analog signal can be amplified.
4. **Analog-to-Digital Converter (ADC)**: The voltage is measured and converted into discrete integer values (e.g., in a 10-bit sensor, values range from $0$ to $1023$; in an 8-bit JPEG, values range from $0$ to $255$).

---

## 2. Dynamic Range and the Problem of Clipping

The human eye is an astonishing optical instrument. Thanks to rapid micro-adjustments and continuous neural processing, your visual system can perceive a dynamic range of roughly **20 to 24 EV stops** (the ratio between the darkest shadows you can perceive and the brightest sunlight).

Camera sensors are much more limited. A high-end dedicated sensor achieves around **14 to 15 stops**, while a typical smartphone sensor natively achieves **10 to 12 stops** per raw exposure.

When a scene has more contrast than the sensor can capture, **clipping** occurs:

```
                     THE SENSOR EXPOSURE WINDOW
 
 Darkest Shadows                                            Brightest Highlights
 (e.g. Under bridge)                                        (e.g. Noon Sun / Sky)
 ─────────────────┬────────────────────────────────────────┬─────────────────
  CRUSHED SHADOWS │            USABLE EXPOSURE             │ BLOWN HIGHLIGHTS
   (Underflow)    │                 RANGE                  │   (Overflow)
 ─────────────────┼────────────────────────────────────────┼─────────────────
  Voltage = 0V    │ Linear photon-to-charge response       │ Bucket is full!
  Pixel Value = 0 │ Details preserved (textures, gradients)│ Pixel Value = 255
                  │                                        │
  Noise dominates │                                        │ Total loss of
  Cannot recover  │                                        │ color & detail
  shadow details  │                                        │ (Pure harsh white)
```

### Blown Highlights (Highlight Clipping)
When a photosite's light bucket overflows, every subsequent photon is lost. The pixel reaches maximum digital saturation ($255$ in 8-bit RGB).
* **The rule of digital photography:** *Blown highlights are unrecoverable.* If the sky in your photo turns solid #FFFFFF white, no slider or software filter can bring the clouds back—the information was never recorded.

### Crushed Shadows (Shadow Clipping)
When too few photons enter the photosite, the signal is weaker than the sensor's own electrical thermal noise floor.
* If you attempt to boost crushed shadows in editing, you will reveal ugly magenta/green digital chroma noise rather than clean detail.

---

## 3. Reading the Live Histogram

The human brain easily tricks itself when looking at a phone screen in bright sunlight. What looks bright on the screen might actually be underexposed. The only objective, scientific way to evaluate exposure is the **histogram**.

A histogram is a frequency graph showing the number of pixels at each brightness level from absolute black ($0$) to absolute white ($255$):

```
 Shadows                     Midtones                    Highlights
 (Dark tones)               (Skin, grass)               (Clouds, lamps)
  ┌──────────────────────────────────────────────────────────────┐
  │                                                              │
  │                     ██                                       │
  │                    ████                                      │
  │                   ███████                                    │
  │                  █████████                                   │
  │                █████████████                                 │
  │             ██████████████████                               │
  │           ███████████████████████                            │
  │       ██████████████████████████████                         │
  └───────┴──────────────────────────────────────────────┴───────┘
  0 (Pure Black)                                 255 (Pure White)
```

### The Three Diagnostic Shapes

1. **Underexposed (Left-skewed):**
   ```
   ████
   ██████
   ████████\_______________________________________
   0                                            255
   ```
   Most data is squashed against the left wall. The image will look murky, dark, and boosting it will introduce high noise.

2. **Overexposed (Right-skewed):**
   ```
   _______________________________________/████████
                                           ████████
   0                                            255
   ```
   Data is jammed against the far right wall. Highlights and skies are clipped to white.

3. **Balanced / High-Key / Low-Key:**
   A balanced histogram does not have to be a perfect bell curve—a night scene *should* have shadows on the left, and a snowy landscape *should* peak towards the right. The key rule is: **Avoid clipping at either extreme unless intentionally desired.**

---

## 4. In OpenKamera: Live Real-Time Histograms

OpenKamera implements real-time hardware-accelerated histogram evaluation right on top of the live preview viewfinder.

Rather than waiting until a picture is saved to check if highlights were clipped:
1. OpenKamera receives raw YUV preview frames from Camera2.
2. The luminance channel is sampled into 256 frequency bins.
3. The renderer draws the curve directly on the HUD canvas.

* **Learn more in code:** [`HistogramOverlayRenderer.kt`](../../app/src/main/java/com/hightechif/openkamera/ui/renderers/HistogramOverlayRenderer.kt) computes and renders the live tonal curve without stalling the camera preview stream.

---

## Summary Checklist
- [x] Cameras record **photons**, which are converted into charge and digitized.
- [x] **Dynamic Range** is the span between the darkest shadow and brightest highlight a sensor can record simultaneously.
- [x] **Blown highlights** (pure white $255$) lose all data and cannot be recovered; protect your highlights.
- [x] A **histogram** gives you an objective visual graph of exposure, regardless of ambient screen glare.

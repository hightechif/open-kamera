# Module 00 — Lesson 06: The Practical Field Cookbook

Theory is only useful when you can put it into practice. In this field guide, we connect all the concepts learned in Module 00—the Exposure Triangle, shooting modes, focus diopters, and color temperature—into concrete, step-by-step **camera recipes** you can execute using OpenKamera.

---

## 🍳 Recipe 1: Freezing High-Speed Action & Water Splashes

```
Goal: Freeze a sprinting pet, a speeding athlete, or splashing water droplets in mid-air with razor-sharp clarity.
```

```
          [ Fast Moving Subject ]
               \       /
                \     /  Incoming light sliced into 1/1000s
                 \   /
              ┌─────────┐
              │ 1/1000s │ <── Shutter freezes motion completely
              └─────────┘
```

### The Setup in OpenKamera:
1. **Shooting Mode:** Switch to **Manual Mode (M)**.
2. **Shutter Speed:** Set to **$1/1000\text{s}$** (or $1/2000\text{s}$ if outdoors in blazing sun).
   * *Why:* Anything slower than $1/500\text{s}$ will show motion blur on fast-moving limbs or water droplets.
3. **ISO:** Start at **Auto ISO** or adjust manually ($200 - 800$) until the live histogram is centered.
   * *Why:* Because $1/1000\text{s}$ lets in very little light, the sensor gain must compensate.
4. **Focus:** Set to **Continuous Focus** (`FOCUS_MODE_CONTINUOUS_PICTURE`) or tap-to-focus on the expected impact point.
5. **Burst Mode:** Enable **Burst Mode** in OpenKamera settings (e.g. 5 or 10 rapid shots) so you don't miss the peak millisecond of action.

* **Tip:** Good lighting is non-negotiable for high-speed action. Trying to freeze motion at $1/1000\text{s}$ indoors will force your ISO to extreme noisy levels ($6400+$).

---

## 🍳 Recipe 2: Silky Waterfalls & Dreamy Water Motion

```
Goal: Turn crashing, chaotic waterfall rapids or ocean waves into smooth, ethereal, misty ribbons while surrounding rocks remain tack-sharp.
```

```
           [ Moving Water ]                [ Immobile Rocks ]
               ~~~~~                             ▲▲▲▲▲
                 │                                 │
     (Blurs into smooth mist)           (Stays tack-sharp)
                 └──────────────┬──────────────────┘
                                │
                        [ 1 to 2 Seconds ] <── Long Exposure on a Tripod
```

### The Setup in OpenKamera:
1. **Equipment:** **A tripod or solid resting surface is MANDATORY.** You cannot handhold a 1-second exposure without camera shake destroying the picture.
2. **Shooting Mode:** **Manual Mode (M)**.
3. **Shutter Speed:** Set between **$1/2\text{s}$ and $2\text{s}$**.
4. **ISO:** Set to **Base ISO (ISO 50 or 100)**.
   * *Why:* A long shutter admits massive light; keeping ISO at minimum prevents overexposure.
5. **The Mobile Reality Check (ND Filter):**
   * On a DSLR, a photographer stops down to $f/16$ or $f/22$ to cut out daylight.
   * Because your smartphone has a **fixed $f/1.8$ aperture**, a 1-second exposure in broad daylight will completely blow out into solid white!
   * *Solution:* Shoot during heavy overcast clouds, deep dusk, or clip a physical **Neutral Density (ND) filter** (sunglasses for your lens) over your phone's camera.

---

## 🍳 Recipe 3: Night Astrophotography & The Milky Way

```
Goal: Capture thousands of brilliant stars, galactic dust lanes, and the night sky in crisp, noise-controlled detail.
```

```
         *   *  *  (Faint Starlight)  *   *   *
               \       │       /
                \      │      /
             ┌────────────────────┐
             │ 15s to 20s Shutter │ <── Ample time for faint photons to gather
             └────────────────────┘
                       │
             ┌────────────────────┐
             │   ISO 1600-3200    │ <── High sensitivity amplification
             └────────────────────┘
```

### The Setup in OpenKamera:
1. **Equipment:** A steady tripod and a clear, dark sky away from city light pollution.
2. **Shooting Mode:** **Manual Mode (M)**.
3. **Shutter Speed:** Set to **$15\text{s}$ to $20\text{s}$**.
   * *Why not 60s?* The Earth rotates! If your shutter stays open longer than ~20-25 seconds (the "500 Rule"), pin-point stars will blur into curved trails.
4. **ISO:** Set to **ISO 1600 or 3200**.
   * *Why:* Starlight is extremely faint; moderate sensor gain is necessary.
5. **Focus:** Switch to **Manual Focus** and slide focus distance to **Infinity ($\infty$, 0.0 Diopters)**.
   * *Warning:* Auto-focus cannot see stars in the dark and will hunt endlessly.
6. **Capture Delay / Timer:** Set OpenKamera's self-timer to **3 seconds**.
   * *Why:* When your finger taps the screen to take the shot, the microscopic vibration will blur the stars. A 3-second delay ensures the phone is completely motionless when the exposure starts.
7. **Format:** Enable **RAW (DNG)** capture in settings to capture uncompressed sensor dynamic range.

---

## 🍳 Recipe 4: Golden Hour & Dramatic Sunsets

```
Goal: Capture the rich, fiery crimson and amber of a sunset, turning your subject into an artistic ink-black silhouette.
```

```
               [ Brilliant Sunset Sky ]
                       │ (Bright)
                       ▼
            Camera Meter: "Too bright!"
                       │
         You dial EV: -1.0 to -1.5 EV
                       │
                       ▼
      ┌─────────────────────────────────┐
      │ Sky: Deep saturated orange/red  │
      │ Subject: Rich black silhouette  │
      └─────────────────────────────────┘
```

### The Setup in OpenKamera:
1. **Subject Positioning:** Place your subject directly between your camera and the setting sun (backlit).
2. **Metering Point:** Tap the screen on the glowing sky near the sun (not directly into the blinding solar disc).
3. **Exposure Compensation ($EV$):** Drag the exposure slider down to **$-1.0\text{ EV}$ to $-1.5\text{ EV}$**.
   * *Why:* This protects the fiery highlights from blowing out to white and plunges the backlit subject into clean, dramatic black.
4. **White Balance:** Switch from AWB to **"Cloudy" ($6500\text{K}$)** or **"Shade" ($7500\text{K}$)**.
   * *Why:* As learned in [Lesson 05](./05-color-science-and-white-balance.md), Auto White Balance will try to erase the orange glow. Cloudy WB locks in the rich warmth.

---

## 🍳 Recipe 5: Close-Up Macro & Focus Peaking

```
Goal: Photograph flowers, insects, coins, or jewelry with tack-sharp focus on the focal point and creamy, natural optical background blur.
```

```
      [ Phone Lens ] ─── 7cm ───► [ Flower Stamen ] ──────── 2m ────────► [ Background ]
                                    (Sharp focus)                       (Optical Blur / Bokeh)
```

### The Setup in OpenKamera:
1. **Subject Distance:** Position your phone close to the subject ($5\text{cm}$ to $10\text{cm}$), keeping the background as far behind the subject as possible.
   * *Why:* Getting physically close is the only way to achieve real optical bokeh on a smartphone's fixed aperture lens.
2. **Focus Mode:** Switch to **Manual Focus**.
3. **Focus Peaking HUD:** Enable Focus Peaking in OpenKamera's on-screen display settings.
4. **Tuning:** Gently drag the manual focus slider until the neon peaking outline shimmers across the exact detail you want sharp (e.g. the stamen of a flower or the eye of an insect).
5. **Shutter Stability:** Even gentle wind or hand tremors will throw a macro subject out of focus. Hold breath and take multiple shots or use burst mode.

---

## Field Summary Reference Card

| Scenario | Mode | Shutter Speed | ISO | Focus | White Balance |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Action / Sports** | Manual / Shutter | $1/1000\text{s}$ | Auto / 400 | Continuous AF | Auto (AWB) |
| **Silky Water** | Manual (Tripod) | $1\text{s} - 2\text{s}$ | Base (50/100) | Tap-to-Focus | Auto or Daylight |
| **Night Stars** | Manual (Tripod) | $15\text{s} - 20\text{s}$ | $1600 - 3200$ | Manual ($\infty$) | 4000K or Auto |
| **Sunset Silhouette**| Auto / Manual | Metered $-1.5\text{EV}$ | Base (100) | On Subject | Cloudy (6500K) |
| **Macro Close-up** | Manual Focus | $1/250\text{s}+$ | Base (100) | Peaking Assist| Daylight / Auto |

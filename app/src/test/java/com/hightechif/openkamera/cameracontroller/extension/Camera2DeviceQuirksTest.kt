/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.cameracontroller.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Camera2DeviceQuirksTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun quirks(manufacturer: String, model: String = "unknown") =
        Camera2DeviceQuirks(manufacturer = manufacturer, model = model)

    private fun samsungQuirks(model: String = "SM-G9300") =
        Camera2DeviceQuirks(manufacturer = "samsung", model = model)

    private fun nonSamsungQuirks() =
        Camera2DeviceQuirks(manufacturer = "Google", model = "Pixel 6 Pro")

    // ── isSamsung ─────────────────────────────────────────────────────────────

    @Test fun isSamsung_trueForLowercaseManufacturer() {
        assertTrue(quirks("samsung").isSamsung)
    }

    @Test fun isSamsung_trueForMixedCase() {
        assertTrue(quirks("Samsung").isSamsung)
        assertTrue(quirks("SAMSUNG").isSamsung)
    }

    @Test fun isSamsung_falseForGoogle() {
        assertFalse(nonSamsungQuirks().isSamsung)
    }

    // ── getMinTonemapPoints ───────────────────────────────────────────────────

    @Test fun getMinTonemapPoints_returns32ForSamsung() {
        assertEquals(32, samsungQuirks().getMinTonemapPoints())
    }

    @Test fun getMinTonemapPoints_returns64ForNonSamsung() {
        assertEquals(64, nonSamsungQuirks().getMinTonemapPoints())
    }

    // ── requiresFakePrecapture ────────────────────────────────────────────────

    @Test fun requiresFakePrecapture_trueForSamsungInBurstMode() {
        assertTrue(samsungQuirks().requiresFakePrecapture(isBurstMode = true))
    }

    @Test fun requiresFakePrecapture_falseForSamsungNotInBurstMode() {
        assertFalse(samsungQuirks().requiresFakePrecapture(isBurstMode = false))
    }

    @Test fun requiresFakePrecapture_falseForNonSamsungEvenInBurst() {
        assertFalse(nonSamsungQuirks().requiresFakePrecapture(isBurstMode = true))
    }

    @Test fun requiresFakePrecapture_falseForNonSamsungNotInBurst() {
        assertFalse(nonSamsungQuirks().requiresFakePrecapture(isBurstMode = false))
    }

    // ── supportsZslHint ───────────────────────────────────────────────────────

    @Test fun supportsZslHint_trueForSamsung() {
        assertTrue(samsungQuirks().supportsZslHint())
    }

    @Test fun supportsZslHint_trueForNonSamsung() {
        assertTrue(nonSamsungQuirks().supportsZslHint())
    }

    // ── requiresPostCaptureTrigger ────────────────────────────────────────────

    @Test fun requiresPostCaptureTrigger_trueForSamsungNonVideoMode() {
        assertTrue(samsungQuirks().requiresPostCaptureTrigger(previewIsVideoMode = false))
    }

    @Test fun requiresPostCaptureTrigger_falseForSamsungInVideoMode() {
        assertFalse(samsungQuirks().requiresPostCaptureTrigger(previewIsVideoMode = true))
    }

    @Test fun requiresPostCaptureTrigger_falseForNonSamsungNonVideoMode() {
        assertFalse(nonSamsungQuirks().requiresPostCaptureTrigger(previewIsVideoMode = false))
    }

    @Test fun requiresPostCaptureTrigger_trueWhenTestFlagOverrideOnNonSamsung() {
        assertTrue(
            nonSamsungQuirks().requiresPostCaptureTrigger(
                previewIsVideoMode = false,
                testForceRunPostCapture = true
            )
        )
    }

    @Test fun requiresPostCaptureTrigger_falseWhenTestFlagButVideoMode() {
        assertFalse(
            nonSamsungQuirks().requiresPostCaptureTrigger(
                previewIsVideoMode = true,
                testForceRunPostCapture = true
            )
        )
    }

    // ── allowsBurstNoiseReduction ─────────────────────────────────────────────

    @Test fun allowsBurstNoiseReduction_falseForSamsung() {
        assertFalse(samsungQuirks().allowsBurstNoiseReduction())
    }

    @Test fun allowsBurstNoiseReduction_trueForNonSamsung() {
        assertTrue(nonSamsungQuirks().allowsBurstNoiseReduction())
    }

    // ── usesAlternativeShutterSound ───────────────────────────────────────────

    @Test fun usesAlternativeShutterSound_trueForSamsung() {
        assertTrue(samsungQuirks().usesAlternativeShutterSound())
    }

    @Test fun usesAlternativeShutterSound_falseForNonSamsung() {
        assertFalse(nonSamsungQuirks().usesAlternativeShutterSound())
    }

    // ── isSamsungS7 model detection ───────────────────────────────────────────

    @Test fun isSamsungS7_trueForS7Model() {
        val q = Camera2DeviceQuirks(manufacturer = "samsung", model = "SM-G930F")
        assertTrue(q.isSamsungS7)
    }

    @Test fun isSamsungS7_falseForS10eModel() {
        val q = Camera2DeviceQuirks(manufacturer = "samsung", model = "SM-G970F")
        assertFalse(q.isSamsungS7)
    }

    // ── isNexus6 ─────────────────────────────────────────────────────────────

    @Test fun isNexus6_trueForNexus6() {
        val q = Camera2DeviceQuirks(manufacturer = "Motorola", model = "Nexus 6")
        assertTrue(q.isNexus6)
    }

    @Test fun isNexus6_falseForNexus5() {
        val q = Camera2DeviceQuirks(manufacturer = "LGE", model = "Nexus 5")
        assertFalse(q.isNexus6)
    }
}

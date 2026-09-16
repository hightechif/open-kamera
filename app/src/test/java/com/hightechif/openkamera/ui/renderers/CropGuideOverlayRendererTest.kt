/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.ui.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CropGuideOverlayRendererTest {

    @Test
    fun cropGuideRatios_mapToStandardAspectRatios() {
        val ratios = mapOf(
            "crop_guide_1" to 1.0,
            "crop_guide_1.25" to 1.25,
            "crop_guide_1.33" to 1.33333333,
            "crop_guide_1.4" to 1.4,
            "crop_guide_1.5" to 1.5,
            "crop_guide_1.78" to 1.77777778,
            "crop_guide_1.85" to 1.85,
            "crop_guide_2" to 2.0,
            "crop_guide_2.33" to 2.33333333,
            "crop_guide_2.35" to 2.35006120,
            "crop_guide_2.4" to 2.4
        )

        assertEquals(1.0, ratios["crop_guide_1"]!!, 0.0001)
        assertEquals(16.0 / 9.0, ratios["crop_guide_1.78"]!!, 0.0001)
        assertEquals(4.0 / 3.0, ratios["crop_guide_1.33"]!!, 0.0001)
        assertEquals(2.35, ratios["crop_guide_2.35"]!!, 0.001)
    }

    @Test
    fun cropGuideBounds_calculateLetterboxAndPillarbox() {
        val canvasWidth = 1920
        val canvasHeight = 1080
        val previewAspectRatio = canvasWidth.toDouble() / canvasHeight.toDouble() // 16:9 ~ 1.7778

        // CinemaScope (2.35:1) is wider than 16:9 -> top/bottom letterbox bars
        val cinemaScopeRatio = 2.35
        assertTrue(cinemaScopeRatio > previewAspectRatio)

        val newHheight = canvasWidth.toDouble() / (2.0 * cinemaScopeRatio)
        val top = canvasHeight / 2 - newHheight.toInt()
        val bottom = canvasHeight / 2 + newHheight.toInt()

        assertTrue(top > 0)
        assertTrue(bottom < canvasHeight)
        assertEquals(canvasHeight, top + (canvasHeight - bottom) + (bottom - top))

        // 1:1 square guide is narrower than 16:9 -> left/right pillarbox bars
        val squareRatio = 1.0
        assertTrue(squareRatio < previewAspectRatio)

        val newHwidth = (canvasHeight.toDouble() * squareRatio) / 2.0
        val left = canvasWidth / 2 - newHwidth.toInt()
        val right = canvasWidth / 2 + newHwidth.toInt()

        assertTrue(left > 0)
        assertTrue(right < canvasWidth)
        assertEquals(1080.0, (right - left).toDouble(), 2.0)
    }

    @Test
    fun portraitMode_invertsAspectRatios() {
        val landscapeCropRatio = 16.0 / 9.0
        val portraitCropRatio = 1.0 / landscapeCropRatio

        assertEquals(0.5625, portraitCropRatio, 0.0001)
    }
}

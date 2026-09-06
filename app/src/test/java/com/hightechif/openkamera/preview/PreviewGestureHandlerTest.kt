/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview

import com.hightechif.openkamera.preview.gesture.PreviewGestureHandler
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewGestureHandlerTest {

    @Test
    fun getFocusMeteringAreas_clampsToSensorBoundaries() {
        // Test center point
        val centerAreas = PreviewGestureHandler.getFocusMeteringAreas(0f, 0f)
        assertEquals(1, centerAreas.size)
        assertEquals(-100, centerAreas[0].rect.left)
        assertEquals(100, centerAreas[0].rect.right)
        assertEquals(-100, centerAreas[0].rect.top)
        assertEquals(100, centerAreas[0].rect.bottom)
        assertEquals(1000, centerAreas[0].weight)

        // Test extreme upper left corner point (-1000, -1000)
        val cornerAreas = PreviewGestureHandler.getFocusMeteringAreas(-1000f, -1000f)
        assertEquals(-1000, cornerAreas[0].rect.left)
        assertEquals(-900, cornerAreas[0].rect.right)
        assertEquals(-1000, cornerAreas[0].rect.top)
        assertEquals(-900, cornerAreas[0].rect.bottom)

        // Test extreme lower right corner point (+1000, +1000)
        val maxAreas = PreviewGestureHandler.getFocusMeteringAreas(1000f, 1000f)
        assertEquals(900, maxAreas[0].rect.left)
        assertEquals(1000, maxAreas[0].rect.right)
        assertEquals(900, maxAreas[0].rect.top)
        assertEquals(1000, maxAreas[0].rect.bottom)
    }

    @Test
    fun calculatePinchZoom_scalesAndClampsWithinLimits() {
        val currentZoom = 2.0f
        val zoomedIn = PreviewGestureHandler.calculatePinchZoom(currentZoom, 1.5f, 1.0f, 10.0f)
        assertEquals(3.0f, zoomedIn, 0.001f)

        val zoomedOut = PreviewGestureHandler.calculatePinchZoom(currentZoom, 0.25f, 1.0f, 10.0f)
        assertEquals(1.0f, zoomedOut, 0.001f) // Clamped to min 1.0

        val overZoomed = PreviewGestureHandler.calculatePinchZoom(currentZoom, 10.0f, 1.0f, 10.0f)
        assertEquals(10.0f, overZoomed, 0.001f) // Clamped to max 10.0
    }
}

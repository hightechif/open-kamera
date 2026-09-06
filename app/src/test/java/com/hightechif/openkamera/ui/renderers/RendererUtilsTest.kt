/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.ui.renderers

import org.junit.Assert.assertEquals
import org.junit.Test

class RendererUtilsTest {

    @Test
    fun formatLevelAngle_handlesZeroAndNearZero() {
        assertEquals("0.0", RendererUtils.formatLevelAngle(0.0))
        assertEquals("0.0", RendererUtils.formatLevelAngle(0.04))
        assertEquals("0.0", RendererUtils.formatLevelAngle(-0.04))
    }

    @Test
    fun formatLevelAngle_handlesPositiveAndNegativeValues() {
        assertEquals("12.3", RendererUtils.formatLevelAngle(12.34))
        assertEquals("-5.7", RendererUtils.formatLevelAngle(-5.67))
        assertEquals("45.0", RendererUtils.formatLevelAngle(45.0))
        assertEquals("-90.0", RendererUtils.formatLevelAngle(-90.0))
    }

    @Test
    fun getTimeStringFromSeconds_formatsMmSsCorrectly() {
        assertEquals("00:00", RendererUtils.getTimeStringFromSeconds(0L))
        assertEquals("00:45", RendererUtils.getTimeStringFromSeconds(45L))
        assertEquals("01:05", RendererUtils.getTimeStringFromSeconds(65L))
        assertEquals("59:59", RendererUtils.getTimeStringFromSeconds(3599L))
    }

    @Test
    fun getTimeStringFromSeconds_formatsHhMmSsCorrectly() {
        assertEquals("01:00:00", RendererUtils.getTimeStringFromSeconds(3600L))
        assertEquals("01:01:05", RendererUtils.getTimeStringFromSeconds(3665L))
        assertEquals("10:30:15", RendererUtils.getTimeStringFromSeconds(37815L))
    }

    @Test
    fun constants_haveExpectedValues() {
        assertEquals(1.0, RendererUtils.CLOSE_LEVEL_ANGLE, 0.001)
        assertEquals(100, RendererUtils.HISTOGRAM_WIDTH_DP)
        assertEquals(60, RendererUtils.HISTOGRAM_HEIGHT_DP)
        assertEquals(160, RendererUtils.CROP_SHADING_ALPHA)
    }
}

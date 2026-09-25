/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureModelsTest {

    @Test
    fun captureConfig_defaultValues_areValid() {
        val config = CaptureConfig()
        assertEquals(CaptureMode.PHOTO, config.captureMode)
        assertEquals(FlashMode.AUTO, config.flashMode)
        assertEquals(90, config.jpegQuality)
        assertFalse(config.enableRaw)
        assertTrue(config.burstExposures.isEmpty())
        assertEquals(0, config.rotationDegrees)
    }
}

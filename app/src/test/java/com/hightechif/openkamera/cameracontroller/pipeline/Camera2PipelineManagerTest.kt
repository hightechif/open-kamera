/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.cameracontroller.pipeline

import android.view.Surface
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Camera2PipelineManagerTest {

    @Test
    fun testInitialState() {
        val pipelineManager = Camera2PipelineManager()
        assertFalse(pipelineManager.isConfigured)
        assertFalse(pipelineManager.hasRawStream)
        assertTrue(pipelineManager.getAllActiveSurfaces().isEmpty())
    }

    @Test
    fun testActiveSurfacesAggregation() {
        val pipelineManager = Camera2PipelineManager()
        val mockPreviewSurface = mockk<Surface>(relaxed = true)
        val mockVideoSurface = mockk<Surface>(relaxed = true)

        pipelineManager.previewSurface = mockPreviewSurface
        pipelineManager.videoSurface = mockVideoSurface

        val surfaces = pipelineManager.getAllActiveSurfaces()
        assertEquals(2, surfaces.size)
        assertTrue(surfaces.contains(mockPreviewSurface))
        assertTrue(surfaces.contains(mockVideoSurface))

        pipelineManager.releaseSurfaces()
        assertTrue(pipelineManager.getAllActiveSurfaces().isEmpty())
    }
}

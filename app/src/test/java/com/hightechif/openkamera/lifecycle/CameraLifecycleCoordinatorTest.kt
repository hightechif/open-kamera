/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.lifecycle

import com.hightechif.openkamera.MainActivity
import com.hightechif.openkamera.MyApplicationInterface
import com.hightechif.openkamera.preview.Preview
import com.hightechif.openkamera.ui.DrawPreview
import com.hightechif.openkamera.ui.MainUI
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CameraLifecycleCoordinatorTest {

    private lateinit var mockActivity: MainActivity
    private lateinit var mockPreview: Preview
    private lateinit var mockAppInterface: MyApplicationInterface
    private lateinit var mockDrawPreview: DrawPreview
    private lateinit var mockMainUi: MainUI
    private lateinit var coordinator: CameraLifecycleCoordinator

    @Before
    fun setUp() {
        mockActivity = mockk(relaxed = true)
        mockPreview = mockk(relaxed = true)
        mockAppInterface = mockk(relaxed = true)
        mockDrawPreview = mockk(relaxed = true)
        mockMainUi = mockk(relaxed = true)

        every { mockActivity.preview } returns mockPreview
        every { mockActivity.applicationInterface } returns mockAppInterface
        every { mockActivity.mainUI } returns mockMainUi
        every { mockAppInterface.drawPreview } returns mockDrawPreview
        every { mockActivity.isCameraInBackground } returns false

        coordinator = CameraLifecycleCoordinator(mockActivity)
    }

    @Test
    fun onResume_resumesPreviewWhenNotInBackground() {
        coordinator.onResume()

        assertFalse(coordinator.isAppPaused)
        verify { mockDrawPreview.setCoverPreview(true) }
        verify { mockDrawPreview.clearDimPreview() }
        verify { mockPreview.onResume() }
    }

    @Test
    fun onPause_pausesPreviewAndClearsGhostImage() {
        coordinator.onPause()

        assertTrue(coordinator.isAppPaused)
        verify { mockMainUi.destroyPopup() }
        verify { mockAppInterface.clearLastImages() }
        verify { mockDrawPreview.clearGhostImage() }
        verify { mockPreview.onPause() }
    }
}

/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.lifecycle

import android.hardware.SensorEvent
import com.hightechif.openkamera.MainActivity
import com.hightechif.openkamera.preview.Preview
import com.hightechif.openkamera.ui.MainUI
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Test

class OrientationLifecycleManagerTest {

    private lateinit var mockActivity: MainActivity
    private lateinit var mockPreview: Preview
    private lateinit var mockMainUi: MainUI
    private lateinit var orientationManager: OrientationLifecycleManager

    @Before
    fun setUp() {
        mockActivity = mockk(relaxed = true)
        mockPreview = mockk(relaxed = true)
        mockMainUi = mockk(relaxed = true)

        every { mockActivity.preview } returns mockPreview
        every { mockActivity.mainUI } returns mockMainUi

        orientationManager = OrientationLifecycleManager(mockActivity)
    }

    @Test
    fun accelerometerListener_forwardsEventToPreview() {
        val mockEvent = mockk<SensorEvent>(relaxed = true)
        orientationManager.accelerometerListener.onSensorChanged(mockEvent)

        verify { mockPreview.onAccelerometerSensorChanged(mockEvent) }
    }
}

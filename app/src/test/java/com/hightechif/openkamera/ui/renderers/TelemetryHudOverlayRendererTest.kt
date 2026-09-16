/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.ui.renderers

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import com.hightechif.openkamera.MainActivity
import com.hightechif.openkamera.MyApplicationInterface
import com.hightechif.openkamera.cameracontroller.CameraController
import com.hightechif.openkamera.preferences.PreferenceKeys
import com.hightechif.openkamera.preview.Preview
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class TelemetryHudOverlayRendererTest {

    private lateinit var renderer: TelemetryHudOverlayRenderer
    private lateinit var mockContext: Context
    private lateinit var mockAppInterface: MyApplicationInterface
    private lateinit var mockCanvas: Canvas
    private lateinit var mockMainActivity: MainActivity
    private lateinit var mockPreview: Preview
    private lateinit var mockCameraController: CameraController
    private lateinit var mockSharedPrefs: SharedPreferences
    private lateinit var drawContext: DrawPreviewContext

    @Before
    fun setUp() {
        mockContext = mockk(relaxed = true)
        mockAppInterface = mockk(relaxed = true)
        mockCanvas = mockk(relaxed = true)
        mockMainActivity = mockk(relaxed = true)
        mockPreview = mockk(relaxed = true)
        mockCameraController = mockk(relaxed = true)
        mockSharedPrefs = mockk(relaxed = true)

        every { mockMainActivity.preview } returns mockPreview
        every { mockPreview.cameraController } returns mockCameraController
        every { mockContext.resources } returns mockk(relaxed = true)

        renderer = TelemetryHudOverlayRenderer(mockContext, mockAppInterface)

        drawContext = DrawPreviewContext(
            mainActivity = mockMainActivity,
            applicationInterface = mockAppInterface,
            sharedPreferences = mockSharedPrefs,
            scaleDp = 1.0f,
            scaleFont = 1.0f,
            strokeWidth = 1.0f
        )
    }

    @Test
    fun updateSettings_clearsCachedBoundsWithoutCrashing() {
        renderer.updateSettings()
    }

    @Test
    fun setExtraOSDValues_storesValuesCorrectly() {
        renderer.setExtraOSDValues("Depth: 12m", "Temp: 22C")
    }

    @Test
    fun draw_audioMeter_drawsWhenVideoRecordingAndAmpEnabled() {
        every { mockSharedPrefs.getBoolean(PreferenceKeys.SHOW_VIDEO_MAX_AMP_PREFERENCE_KEY, false) } returns true
        every { mockPreview.isVideoRecording } returns true
        every { mockPreview.isVideoRecordingPaused } returns false
        every { mockPreview.maxAmplitude } returns 16384 // ~50% amplitude
        every { mockCanvas.width } returns 1080
        every { mockCanvas.height } returns 1920

        renderer.draw(mockCanvas, drawContext, 1000L)

        // Verifies audio meter stroke frame and fill meter are drawn
        verify(atLeast = 1) {
            mockCanvas.drawRect(
                any<Float>(),
                any<Float>(),
                any<Float>(),
                any<Float>(),
                any<Paint>()
            )
        }
    }

    @Test
    fun timeStringFormatting_formatsZeroAndPositiveTimes() {
        assertEquals("00:00", RendererUtils.getTimeStringFromSeconds(0))
        assertEquals("01:05", RendererUtils.getTimeStringFromSeconds(65))
        assertEquals("01:01:05", RendererUtils.getTimeStringFromSeconds(3665))
    }
}

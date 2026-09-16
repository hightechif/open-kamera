/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.ui.renderers

import android.content.SharedPreferences
import android.graphics.Canvas
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

class HistogramOverlayRendererTest {

    private lateinit var renderer: HistogramOverlayRenderer
    private lateinit var mockCanvas: Canvas
    private lateinit var mockMainActivity: MainActivity
    private lateinit var mockPreview: Preview
    private lateinit var mockCameraController: CameraController
    private lateinit var mockSharedPrefs: SharedPreferences
    private lateinit var mockAppInterface: MyApplicationInterface
    private lateinit var drawContext: DrawPreviewContext

    @Before
    fun setUp() {
        renderer = HistogramOverlayRenderer()
        mockCanvas = mockk(relaxed = true)
        mockMainActivity = mockk(relaxed = true)
        mockPreview = mockk(relaxed = true)
        mockCameraController = mockk(relaxed = true)
        mockSharedPrefs = mockk(relaxed = true)
        mockAppInterface = mockk(relaxed = true)

        every { mockMainActivity.preview } returns mockPreview
        every { mockPreview.cameraController } returns mockCameraController

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
    fun draw_whenPreviewBitmapDisabled_doesNotDrawToCanvas() {
        every { mockPreview.isPreviewBitmapEnabled } returns false

        renderer.draw(mockCanvas, drawContext, 1000L)

        verify(exactly = 0) { mockCanvas.drawRect(any<android.graphics.Rect>(), any<android.graphics.Paint>()) }
        verify(exactly = 0) { mockCanvas.drawPath(any<android.graphics.Path>(), any<android.graphics.Paint>()) }
    }

    @Test
    fun draw_whenHistogramPrefOff_doesNotDrawToCanvas() {
        every { mockPreview.isPreviewBitmapEnabled } returns true
        every {
            mockSharedPrefs.getString(PreferenceKeys.HISTOGRAM_PREFERENCE_KEY, "preference_histogram_off")
        } returns "preference_histogram_off"

        renderer.draw(mockCanvas, drawContext, 1000L)

        verify(exactly = 0) { mockCanvas.drawRect(any<android.graphics.Rect>(), any<android.graphics.Paint>()) }
    }

    @Test
    fun draw_whenHistogramDataNull_doesNotDrawToCanvas() {
        every { mockPreview.isPreviewBitmapEnabled } returns true
        every {
            mockSharedPrefs.getString(PreferenceKeys.HISTOGRAM_PREFERENCE_KEY, "preference_histogram_off")
        } returns "preference_histogram_rgb"
        every { mockPreview.histogram } returns null

        renderer.draw(mockCanvas, drawContext, 1000L)

        verify(exactly = 0) { mockCanvas.drawRect(any<android.graphics.Rect>(), any<android.graphics.Paint>()) }
    }

    @Test
    fun draw_whenRgbHistogramActive_drawsBackgroundAndChannels() {
        every { mockPreview.isPreviewBitmapEnabled } returns true
        every { mockPreview.uIRotation } returns 0
        every {
            mockSharedPrefs.getString(PreferenceKeys.HISTOGRAM_PREFERENCE_KEY, "preference_histogram_off")
        } returns "preference_histogram_rgb"

        val rgbHistogram = IntArray(256 * 3) { index -> index % 50 }
        every { mockPreview.histogram } returns rgbHistogram

        renderer.draw(mockCanvas, drawContext, 1000L)

        verify(atLeast = 1) { mockCanvas.drawRect(any<android.graphics.Rect>(), any<android.graphics.Paint>()) }
        verify(atLeast = 3) { mockCanvas.drawPath(any<android.graphics.Path>(), any<android.graphics.Paint>()) }
    }

    @Test
    fun rendererUtils_computesHistogramDimensionsCorrectly() {
        val scaleDp = 2.0f
        val expectedWidth = (RendererUtils.HISTOGRAM_WIDTH_DP * scaleDp + 0.5f).toInt()
        val expectedHeight = (RendererUtils.HISTOGRAM_HEIGHT_DP * scaleDp + 0.5f).toInt()

        assertEquals(200, expectedWidth)
        assertEquals(120, expectedHeight)
    }
}

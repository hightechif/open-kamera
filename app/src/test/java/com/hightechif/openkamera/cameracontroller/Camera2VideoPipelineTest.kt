/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.cameracontroller

import android.media.MediaActionSound
import com.hightechif.openkamera.cameracontroller.dispatcher.Camera2StateCallbackDispatcher
import com.hightechif.openkamera.cameracontroller.lifecycle.Camera2SessionManager
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Camera2VideoPipelineTest {

    private lateinit var mockController: CameraController2
    private lateinit var mockSessionManager: Camera2SessionManager
    private lateinit var mockCameraSettings: Camera2Settings
    private lateinit var callbackDispatcher: Camera2StateCallbackDispatcher
    private lateinit var videoPipeline: Camera2VideoPipeline

    @Before
    fun setUp() {
        mockController = mockk(relaxed = true)
        mockSessionManager = mockk(relaxed = true)
        mockCameraSettings = mockk(relaxed = true)
        callbackDispatcher = Camera2StateCallbackDispatcher()

        videoPipeline = Camera2VideoPipeline(
            controller = mockController,
            sessionManager = mockSessionManager,
            cameraSettings = mockCameraSettings,
            callbackDispatcher = callbackDispatcher
        )
    }

    @Test
    fun testInitialState() {
        assertFalse(videoPipeline.previewIsVideoMode)
        assertFalse(videoPipeline.wantVideoHighSpeed)
        assertFalse(videoPipeline.isVideoHighSpeed)
        assertNull(videoPipeline.activeVideoRecorder)
        assertNull(videoPipeline.videoRecorderSurface)
    }

    @Test
    fun testInitVideoRecorderPrePrepare_playsCueSound() {
        videoPipeline.initVideoRecorderPrePrepare(null)

        verify { mockController.blockForExtensions() }
        verify { mockController.playSound(MediaActionSound.START_VIDEO_RECORDING) }
    }
}

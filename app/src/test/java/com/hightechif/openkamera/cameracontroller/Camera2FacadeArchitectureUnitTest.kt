/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.cameracontroller

import com.hightechif.openkamera.cameracontroller.burst.Camera2CaptureCoordinator
import com.hightechif.openkamera.cameracontroller.capabilities.Camera2CapabilitiesResolver
import com.hightechif.openkamera.cameracontroller.dispatcher.Camera2StateCallbackDispatcher
import com.hightechif.openkamera.cameracontroller.extension.Camera2DeviceQuirks
import com.hightechif.openkamera.cameracontroller.extension.Camera2VendorTagsExtension
import com.hightechif.openkamera.cameracontroller.focus.Camera2FocusMeteringCoordinator
import com.hightechif.openkamera.cameracontroller.lifecycle.Camera2SessionManager
import com.hightechif.openkamera.cameracontroller.pipeline.Camera2ImageReaderPipeline
import com.hightechif.openkamera.cameracontroller.request.Camera2RequestBuilderHelper
import com.hightechif.openkamera.cameracontroller.threading.Camera2ThreadManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Camera2FacadeArchitectureUnitTest {

    @Test
    fun testAllCoordinatorsInitialization() {
        val focusCoord = Camera2FocusMeteringCoordinator()
        val captureCoord = Camera2CaptureCoordinator(5)
        val sessionMgr = Camera2SessionManager()
        val pipeline = Camera2ImageReaderPipeline()
        val dispatcher = Camera2StateCallbackDispatcher()
        val threadMgr = Camera2ThreadManager("TestFacadeThread")
        val quirks = Camera2DeviceQuirks()

        assertNotNull(focusCoord)
        assertNotNull(captureCoord)
        assertNotNull(sessionMgr)
        assertNotNull(pipeline)
        assertNotNull(dispatcher)
        assertNotNull(threadMgr)
        assertNotNull(quirks)

        threadMgr.shutdownSafely()
    }

    @Test
    fun testPureResolversAndHelpersAvailability() {
        // Capabilities resolver pure methods
        val isSubset = Camera2CapabilitiesResolver.sizeSubset(
            cameraWidths = intArrayOf(1920),
            cameraHeights = intArrayOf(1080),
            altCameraWidths = intArrayOf(1920, 1280),
            altCameraHeights = intArrayOf(1080, 720)
        )
        assertTrue(isSubset)

        // Request builder tonemap math
        val tonemapValues = Camera2RequestBuilderHelper.computeTonemapCurveValues(
            profile = CameraController.TonemapProfile.TONEMAPPROFILE_GAMMA,
            logStrength = 0.0f,
            gamma = 2.2f
        )
        assertNotNull(tonemapValues)
        assertEquals(128, tonemapValues!!.size)

        // Vendor extension size checking
        val hasMatch = Camera2VendorTagsExtension.updatePictureSizesForExtension(
            pictureSizes = listOf(CameraController.Size(1920, 1080)),
            extensionPictureSizes = listOf(android.util.Size(1920, 1080)),
            extension = 1
        )
        assertTrue(hasMatch)
    }
}

package com.hightechif.openkamera.cameracontroller.focus

import android.graphics.Rect
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.MeteringRectangle
import com.hightechif.openkamera.cameracontroller.CameraController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Camera2FocusMeteringCoordinatorUnitTest {

    private lateinit var coordinator: Camera2FocusMeteringCoordinator

    @Before
    fun setUp() {
        coordinator = Camera2FocusMeteringCoordinator(autofocusTimeoutMs = 1000L)
    }

    @Test
    fun testFocusStateMappingFromAfState() {
        assertEquals(Camera2FocusMeteringState.Inactive, Camera2FocusMeteringState.fromAfState(null))
        assertEquals(Camera2FocusMeteringState.Inactive, Camera2FocusMeteringState.fromAfState(CaptureResult.CONTROL_AF_STATE_INACTIVE))
        assertEquals(Camera2FocusMeteringState.Scanning(isPassive = true), Camera2FocusMeteringState.fromAfState(CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN))
        assertEquals(Camera2FocusMeteringState.Scanning(isPassive = false), Camera2FocusMeteringState.fromAfState(CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN))
        assertEquals(Camera2FocusMeteringState.FocusedLocked(isPassive = true), Camera2FocusMeteringState.fromAfState(CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED))
        assertEquals(Camera2FocusMeteringState.FocusedLocked(isPassive = false), Camera2FocusMeteringState.fromAfState(CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED))
        assertEquals(Camera2FocusMeteringState.NotFocusedLocked, Camera2FocusMeteringState.fromAfState(CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED))
        assertEquals(Camera2FocusMeteringState.NotFocusedLocked, Camera2FocusMeteringState.fromAfState(CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED))
    }

    @Test
    fun testCoordinateConversionRoundTrip() {
        val cropRect = Rect(0, 0, 4000, 3000)
        val area = CameraController.Area(Rect(-500, -500, 500, 500), 800)

        val meteringRectangle = MeteringAreaConverter.convertAreaToMeteringRectangle(cropRect, area)
        assertEquals(800, meteringRectangle.meteringWeight)
        assertTrue(meteringRectangle.rect.left >= 0)
        assertTrue(meteringRectangle.rect.right <= 4000)

        val restoredArea = MeteringAreaConverter.convertMeteringRectangleToArea(cropRect, meteringRectangle)
        assertEquals(800, restoredArea.weight)
        assertTrue(kotlin.math.abs(restoredArea.rect.left - (-500)) <= 2)
        assertTrue(kotlin.math.abs(restoredArea.rect.top - (-500)) <= 2)
        assertTrue(kotlin.math.abs(restoredArea.rect.right - 500) <= 2)
        assertTrue(kotlin.math.abs(restoredArea.rect.bottom - 500) <= 2)
    }

    @Test
    fun testCalculateFocusAndMeteringAreas() {
        val sensorRect = Rect(0, 0, 1920, 1080)
        val areaList = listOf(CameraController.Area(Rect(-200, -200, 200, 200), 500))

        val (afRegions, aeRegions) = coordinator.calculateFocusAndMeteringAreas(
            areas = areaList,
            sensorRect = sensorRect,
            maxAfRegions = 1,
            maxAeRegions = 1
        )

        assertNotNull(afRegions)
        assertEquals(1, afRegions!!.size)
        assertNotNull(aeRegions)
        assertEquals(1, aeRegions!!.size)
    }

    @Test
    fun testCalculateFocusAndMeteringAreasWhenNotSupported() {
        val sensorRect = Rect(0, 0, 1920, 1080)
        val areaList = listOf(CameraController.Area(Rect(-200, -200, 200, 200), 500))

        val (afRegions, aeRegions) = coordinator.calculateFocusAndMeteringAreas(
            areas = areaList,
            sensorRect = sensorRect,
            maxAfRegions = 0,
            maxAeRegions = 0
        )

        assertNull(afRegions)
        assertNull(aeRegions)
    }

    @Test
    fun testCalculateClearFocusAndMeteringAreas() {
        val sensorRect = Rect(0, 0, 1920, 1080)
        val (afRegions, aeRegions) = coordinator.calculateClearFocusAndMeteringAreas(
            sensorRect = sensorRect,
            maxAfRegions = 1,
            maxAeRegions = 1
        )

        assertNotNull(afRegions)
        assertEquals(1, afRegions!!.size)
        assertEquals(0, afRegions[0].rect.left)
        assertEquals(0, afRegions[0].rect.top)
        assertEquals(1919, afRegions[0].rect.right)
        assertEquals(1079, afRegions[0].rect.bottom)
        assertEquals(0, afRegions[0].meteringWeight)
    }

    @Test
    fun testExtractAreasIgnoresDefaultFullScreenRegion() {
        val sensorRect = Rect(0, 0, 1000, 1000)
        val defaultRegion = arrayOf(MeteringRectangle(0, 0, 999, 999, 0))

        val extracted = coordinator.extractAreas(defaultRegion, sensorRect, maxRegions = 1)
        assertNull(extracted)
    }

    @Test
    fun testExtractAreasPreservesCustomRegion() {
        val sensorRect = Rect(0, 0, 1000, 1000)
        val customRegion = arrayOf(MeteringRectangle(200, 200, 800, 800, 600))

        val extracted = coordinator.extractAreas(customRegion, sensorRect, maxRegions = 1)
        assertNotNull(extracted)
        assertEquals(1, extracted!!.size)
        assertEquals(600, extracted[0].weight)
    }

    @Test
    fun testAutofocusTimeout() {
        coordinator.startAutofocusTracking(
            cb = object : CameraController.AutoFocusCallback {
                override fun onAutoFocus(success: Boolean) {}
            },
            captureFollowsAutofocusHint = true,
            currentTimeMs = 1000L
        )

        assertFalse(coordinator.isAutofocusTimedOut(currentTimeMs = 1500L))
        assertTrue(coordinator.isAutofocusTimedOut(currentTimeMs = 2100L))
    }

    @Test
    fun testContinuousFocusMoveTransitions() {
        var lastContinuousMoveState: Boolean? = null
        coordinator.setContinuousFocusMoveCallback(object : CameraController.ContinuousFocusMoveCallback {
            override fun onContinuousFocusMove(start: Boolean) {
                lastContinuousMoveState = start
            }
        })

        // Initial scan started
        val started = coordinator.evaluateContinuousFocusMove(CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN)
        assertEquals(true, started)
        assertEquals(true, lastContinuousMoveState)

        // Set last state to PASSIVE_SCAN
        coordinator.evaluateContinuousFocusMove(CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN)

        // Now focus locked (stopped scanning)
        val stopped = coordinator.evaluateContinuousFocusMove(CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED)
        assertEquals(false, stopped)
        assertEquals(false, lastContinuousMoveState)
    }

    @Test
    fun testWaitingAutofocusEvaluationSuccessfulLock() {
        var callbackSuccess: Boolean? = null
        val autoFocusCb = object : CameraController.AutoFocusCallback {
            override fun onAutoFocus(success: Boolean) {
                callbackSuccess = success
            }
        }
        coordinator.startAutofocusTracking(
            cb = autoFocusCb,
            captureFollowsAutofocusHint = true,
            currentTimeMs = 1000L
        )

        val result = coordinator.evaluateWaitingAutofocusResult(
            afState = CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
            currentTimeMs = 1200L
        )

        assertTrue(result is FocusEvaluationResult.NotifyAutofocus)
        val notifyResult = result as FocusEvaluationResult.NotifyAutofocus
        assertTrue(notifyResult.success)
        assertFalse(notifyResult.afStateNull)
        assertNull(coordinator.getAutofocusCallback())
    }

    @Test
    fun testWaitingAutofocusEvaluationTimeoutTriggersCallback() {
        var callbackSuccess: Boolean? = null
        val autoFocusCb = object : CameraController.AutoFocusCallback {
            override fun onAutoFocus(success: Boolean) {
                callbackSuccess = success
            }
        }
        coordinator.startAutofocusTracking(
            cb = autoFocusCb,
            captureFollowsAutofocusHint = false,
            currentTimeMs = 1000L
        )

        val result = coordinator.evaluateWaitingAutofocusResult(
            afState = CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN,
            currentTimeMs = 2500L // Exceeds timeout
        )

        assertTrue(result is FocusEvaluationResult.NotifyAutofocus)
        val notifyResult = result as FocusEvaluationResult.NotifyAutofocus
        assertFalse(notifyResult.success)
        assertNull(coordinator.getAutofocusCallback())
    }
}

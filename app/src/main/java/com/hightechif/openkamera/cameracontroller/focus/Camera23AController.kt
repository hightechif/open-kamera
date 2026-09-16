/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.cameracontroller.focus

import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import com.hightechif.openkamera.cameracontroller.CameraController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * High-level coordinator managing Auto Focus (AF), Auto Exposure (AE), and Auto White Balance (AWB)
 * state machines, precapture metering sequences, and flash state transitions.
 */
class Camera23AController(
    val focusMeteringCoordinator: Camera2FocusMeteringCoordinator = Camera2FocusMeteringCoordinator()
) {

    private val _isAePrecaptureRunning = MutableStateFlow(false)
    val isAePrecaptureRunning: StateFlow<Boolean> = _isAePrecaptureRunning.asStateFlow()

    var isFakeFlashActive: Boolean = false
    var isTorchActive: Boolean = false
    var currentAfMode: Int = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
    var currentAeMode: Int = CaptureRequest.CONTROL_AE_MODE_ON
    var currentAwbMode: Int = CaptureRequest.CONTROL_AWB_MODE_AUTO

    fun triggerAfScan(cb: CameraController.AutoFocusCallback, captureFollows: Boolean) {
        focusMeteringCoordinator.startAutofocusTracking(cb, captureFollows)
    }

    fun cancelAfScan(): CameraController.AutoFocusCallback? {
        return focusMeteringCoordinator.popAutofocusCallback()
    }

    fun startPrecapture() {
        _isAePrecaptureRunning.value = true
    }

    fun finishPrecapture() {
        _isAePrecaptureRunning.value = false
    }

    fun update3AState(
        result: CaptureResult,
        isContinuousPictureFocus: Boolean = true,
        doAfTriggerForContinuous: Boolean = false,
        useFakePrecaptureMode: Boolean = false
    ): FocusEvaluationResult {
        return focusMeteringCoordinator.processCaptureResult(
            result = result,
            isContinuousPictureFocus = isContinuousPictureFocus,
            doAfTriggerForContinuous = doAfTriggerForContinuous,
            useFakePrecaptureMode = useFakePrecaptureMode
        )
    }
}

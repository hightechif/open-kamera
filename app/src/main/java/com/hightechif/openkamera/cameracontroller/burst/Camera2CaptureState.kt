package com.hightechif.openkamera.cameracontroller.burst

import com.hightechif.openkamera.cameracontroller.CameraController.BurstType

/**
 * Sealed representation of capture and multi-burst state transitions.
 */
sealed class Camera2CaptureState {
    object Idle : Camera2CaptureState()
    data class Preparing(val burstType: BurstType, val targetCount: Int) : Camera2CaptureState()
    data class Capturing(val burstType: BurstType, val taken: Int, val total: Int) : Camera2CaptureState()
    data class Completed(val burstType: BurstType, val totalTaken: Int) : Camera2CaptureState()
    data class Cancelled(val burstType: BurstType, val reason: String) : Camera2CaptureState()
}

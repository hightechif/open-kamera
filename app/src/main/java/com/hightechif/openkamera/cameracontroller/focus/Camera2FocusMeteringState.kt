package com.hightechif.openkamera.cameracontroller.focus

import android.hardware.camera2.CaptureResult

/**
 * Sealed representation of camera AF/AE metering state transitions.
 */
sealed class Camera2FocusMeteringState {
    object Inactive : Camera2FocusMeteringState()
    data class Scanning(val isPassive: Boolean) : Camera2FocusMeteringState()
    data class FocusedLocked(val isPassive: Boolean) : Camera2FocusMeteringState()
    object NotFocusedLocked : Camera2FocusMeteringState()
    data class Custom(val rawState: Int) : Camera2FocusMeteringState()

    companion object {
        fun fromAfState(afState: Int?): Camera2FocusMeteringState {
            if (afState == null) return Inactive
            return when (afState) {
                CaptureResult.CONTROL_AF_STATE_INACTIVE -> Inactive
                CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN -> Scanning(isPassive = true)
                CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN -> Scanning(isPassive = false)
                CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED -> FocusedLocked(isPassive = true)
                CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> FocusedLocked(isPassive = false)
                CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED,
                CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED -> NotFocusedLocked
                else -> Custom(afState)
            }
        }

        fun fromCaptureResult(result: CaptureResult?): Camera2FocusMeteringState {
            val afState = result?.get(CaptureResult.CONTROL_AF_STATE)
            return fromAfState(afState)
        }
    }
}

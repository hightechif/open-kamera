package com.hightechif.openkamera.preview

/**
 * CameraCaptureStateMachine manages the high-level states of the camera (open, opening, closed, closing)
 * as well as capture lifecycle transitions for Preview.
 */
class CameraCaptureStateMachine {

    enum class CameraOpenState {
        CAMERAOPENSTATE_CLOSED,   // camera is closed or hasn't been opened
        CAMERAOPENSTATE_OPENING,  // camera is currently being opened asynchronously
        CAMERAOPENSTATE_OPENED,   // camera is open (or failed to open)
        CAMERAOPENSTATE_CLOSING   // camera is currently closing asynchronously
    }

    enum class CaptureState {
        STATE_IDLE,
        STATE_WAITING_AUTOFOCUS,
        STATE_WAITING_PRECAPTURE_START,
        STATE_WAITING_NON_PRECAPTURE,
        STATE_PICTURE_TAKEN
    }

    var openState: CameraOpenState = CameraOpenState.CAMERAOPENSTATE_CLOSED
        private set

    var captureState: CaptureState = CaptureState.STATE_IDLE
        private set

    fun setOpenState(state: CameraOpenState) {
        this.openState = state
    }

    fun setCaptureState(state: CaptureState) {
        this.captureState = state
    }

    val isOpened: Boolean
        get() = openState == CameraOpenState.CAMERAOPENSTATE_OPENED

    val isOpening: Boolean
        get() = openState == CameraOpenState.CAMERAOPENSTATE_OPENING

    val isClosing: Boolean
        get() = openState == CameraOpenState.CAMERAOPENSTATE_CLOSING

    val isClosed: Boolean
        get() = openState == CameraOpenState.CAMERAOPENSTATE_CLOSED

    fun reset() {
        openState = CameraOpenState.CAMERAOPENSTATE_CLOSED
        captureState = CaptureState.STATE_IDLE
    }
}

package com.hightechif.openkamera.cameracontroller.focus

import android.graphics.Rect
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.MeteringRectangle
import com.hightechif.openkamera.cameracontroller.CameraController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Result of evaluating autofocus state against active callbacks and timeout conditions.
 */
sealed class FocusEvaluationResult {
    object NoAction : FocusEvaluationResult()
    data class NotifyAutofocus(
        val callback: CameraController.AutoFocusCallback,
        val success: Boolean,
        val afStateNull: Boolean = false
    ) : FocusEvaluationResult()
    object PassiveScanning : FocusEvaluationResult()
}

/**
 * Coordinates 3A autofocus and autoexposure metering states, timeout tracking,
 * region calculations, and callback dispatching for Camera2.
 */
class Camera2FocusMeteringCoordinator(
    private var autofocusTimeoutMs: Long = 1000L
) {
    private var autofocusCb: CameraController.AutoFocusCallback? = null
    var autofocusTimeMs: Long = -1
        private set
    var captureFollowsAutofocusHint: Boolean = false
        private set

    var lastAfState: Int? = null
        private set
    var lastAeState: Int? = null
        private set

    private var continuousFocusMoveCallback: CameraController.ContinuousFocusMoveCallback? = null

    private val _focusStateFlow = MutableStateFlow<Camera2FocusMeteringState>(Camera2FocusMeteringState.Inactive)
    val focusStateFlow: StateFlow<Camera2FocusMeteringState> = _focusStateFlow.asStateFlow()

    fun setContinuousFocusMoveCallback(cb: CameraController.ContinuousFocusMoveCallback?) {
        this.continuousFocusMoveCallback = cb
    }

    fun getContinuousFocusMoveCallback(): CameraController.ContinuousFocusMoveCallback? = continuousFocusMoveCallback

    fun getAutofocusCallback(): CameraController.AutoFocusCallback? = autofocusCb

    fun setAutofocusTimeoutMs(timeoutMs: Long) {
        this.autofocusTimeoutMs = timeoutMs
    }

    fun startAutofocusTracking(
        cb: CameraController.AutoFocusCallback,
        captureFollowsAutofocusHint: Boolean,
        currentTimeMs: Long = System.currentTimeMillis()
    ) {
        this.autofocusCb = cb
        this.captureFollowsAutofocusHint = captureFollowsAutofocusHint
        this.autofocusTimeMs = currentTimeMs
    }

    fun resetAutofocusTracking() {
        this.autofocusCb = null
        this.autofocusTimeMs = -1
        this.captureFollowsAutofocusHint = false
    }

    fun popAutofocusCallback(): CameraController.AutoFocusCallback? {
        val cb = this.autofocusCb
        this.autofocusCb = null
        this.autofocusTimeMs = -1
        this.captureFollowsAutofocusHint = false
        return cb
    }

    fun setCaptureFollowsAutofocusHint(hint: Boolean) {
        this.captureFollowsAutofocusHint = hint
    }

    fun isAutofocusTimedOut(currentTimeMs: Long = System.currentTimeMillis()): Boolean {
        return autofocusTimeMs != -1L && currentTimeMs > autofocusTimeMs + autofocusTimeoutMs
    }

    /**
     * Evaluates continuous focus move transitions.
     * Returns true if focusing started, false if stopped, or null if state didn't change.
     */
    fun evaluateContinuousFocusMove(afState: Int?): Boolean? {
        if (afState == null) return null
        val previousState = lastAfState

        val result = if (afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN && afState != previousState) {
            continuousFocusMoveCallback?.onContinuousFocusMove(true)
            true
        } else if (previousState == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN && afState != previousState) {
            continuousFocusMoveCallback?.onContinuousFocusMove(false)
            false
        } else {
            null
        }

        this.lastAfState = afState
        return result
    }

    /**
     * Updates current AF/AE state and evaluates callback triggering conditions.
     */
    fun processCaptureResult(
        result: CaptureResult,
        isContinuousPictureFocus: Boolean,
        doAfTriggerForContinuous: Boolean,
        useFakePrecaptureMode: Boolean,
        currentTimeMs: Long = System.currentTimeMillis()
    ): FocusEvaluationResult {
        val afState = result.get(CaptureResult.CONTROL_AF_STATE)
        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)

        _focusStateFlow.value = Camera2FocusMeteringState.fromAfState(afState)
        evaluateContinuousFocusMove(afState)

        this.lastAfState = afState
        this.lastAeState = aeState

        val isTimeout = isAutofocusTimedOut(currentTimeMs)

        if (afState != null && afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN && !isTimeout) {
            return FocusEvaluationResult.PassiveScanning
        }

        val currentCb = autofocusCb
        if (currentCb != null && (!doAfTriggerForContinuous || useFakePrecaptureMode) && isContinuousPictureFocus) {
            val focusSuccess = afState != null && (
                afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED
            )
            val isNull = afState == null
            resetAutofocusTracking()
            return FocusEvaluationResult.NotifyAutofocus(currentCb, focusSuccess, afStateNull = isNull)
        }

        return FocusEvaluationResult.NoAction
    }

    /**
     * Evaluates autofocus state completion when waiting for active autofocus.
     */
    fun evaluateWaitingAutofocusResult(
        afState: Int?,
        currentTimeMs: Long = System.currentTimeMillis()
    ): FocusEvaluationResult {
        val currentCb = autofocusCb ?: return FocusEvaluationResult.NoAction
        val isTimeout = isAutofocusTimedOut(currentTimeMs)

        if (afState == null) {
            resetAutofocusTracking()
            return FocusEvaluationResult.NotifyAutofocus(currentCb, success = false, afStateNull = true)
        }

        if (afState != lastAfState || isTimeout) {
            if (isTimeout || afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                val focusSuccess = afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED
                resetAutofocusTracking()
                return FocusEvaluationResult.NotifyAutofocus(currentCb, success = focusSuccess, afStateNull = false)
            }
        }

        return FocusEvaluationResult.NoAction
    }

    /**
     * Calculates MeteringRectangles for focus and AE metering from a list of CameraController.Area.
     */
    fun calculateFocusAndMeteringAreas(
        areas: List<CameraController.Area>,
        sensorRect: Rect,
        maxAfRegions: Int,
        maxAeRegions: Int
    ): Pair<Array<MeteringRectangle>?, Array<MeteringRectangle>?> {
        val afRegions: Array<MeteringRectangle>? = if (maxAfRegions > 0) {
            Array(areas.size) { i ->
                MeteringAreaConverter.convertAreaToMeteringRectangle(sensorRect, areas[i])
            }
        } else {
            null
        }

        val aeRegions: Array<MeteringRectangle>? = if (maxAeRegions > 0) {
            Array(areas.size) { i ->
                MeteringAreaConverter.convertAreaToMeteringRectangle(sensorRect, areas[i])
            }
        } else {
            null
        }

        return Pair(afRegions, aeRegions)
    }

    /**
     * Calculates default reset MeteringRectangles for clearing focus and AE metering.
     */
    fun calculateClearFocusAndMeteringAreas(
        sensorRect: Rect,
        maxAfRegions: Int,
        maxAeRegions: Int
    ): Pair<Array<MeteringRectangle>?, Array<MeteringRectangle>?> {
        if (sensorRect.width() <= 0 || sensorRect.height() <= 0) {
            return Pair(null, null)
        }

        val defaultRect = MeteringAreaConverter.createDefaultMeteringRectangle(sensorRect)
        val afRegions = if (maxAfRegions > 0 && defaultRect != null) arrayOf(defaultRect) else null
        val aeRegions = if (maxAeRegions > 0 && defaultRect != null) arrayOf(defaultRect) else null

        return Pair(afRegions, aeRegions)
    }

    /**
     * Extracts CameraController.Area list from active MeteringRectangles.
     */
    fun extractAreas(
        meteringRectangles: Array<MeteringRectangle>?,
        sensorRect: Rect,
        maxRegions: Int
    ): List<CameraController.Area>? {
        if (maxRegions <= 0 || meteringRectangles == null) return null

        if (meteringRectangles.size == 1 &&
            meteringRectangles[0].rect.left == 0 &&
            meteringRectangles[0].rect.top == 0 &&
            meteringRectangles[0].rect.right == sensorRect.width() - 1 &&
            meteringRectangles[0].rect.bottom == sensorRect.height() - 1
        ) {
            // Default full-screen region is treated as null (consistent with CameraController1)
            return null
        }

        val areas = mutableListOf<CameraController.Area>()
        for (rect in meteringRectangles) {
            areas.add(MeteringAreaConverter.convertMeteringRectangleToArea(sensorRect, rect))
        }
        return areas
    }
}

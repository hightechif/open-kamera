package com.hightechif.openkamera.cameracontroller.burst

import com.hightechif.openkamera.cameracontroller.CameraController.BurstType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Coordinates burst capture configurations, continuous burst execution, exposure bracketing,
 * and focus bracketing operations for Camera2.
 */
class Camera2CaptureCoordinator(
    private val maxExpoBracketingNImages: Int = 63
) {
    var burstType: BurstType = BurstType.BURSTTYPE_NONE

    var expoBracketingNImages: Int = 3
        private set

    var expoBracketingStops: Double = 2.0
        private set

    var useExpoFastBurst: Boolean = true

    var dummyCaptureHack: Boolean = false

    var focusBracketingNImages: Int = 3

    var focusBracketingSourceDistance: Float = 0.0f

    var focusBracketingTargetDistance: Float = 0.0f

    var focusBracketingAddInfinity: Boolean = false

    var focusBracketingInProgress: Boolean = false

    var burstForNoiseReduction: Boolean = false
        private set

    var noiseReductionLowLight: Boolean = false
        private set

    var burstRequestedNImages: Int = 0

    var isContinuousBurstInProgress: Boolean = false

    var continuousBurstRequestedLastCapture: Boolean = false

    private val _captureStateFlow = MutableStateFlow<Camera2CaptureState>(Camera2CaptureState.Idle)
    val captureStateFlow: StateFlow<Camera2CaptureState> = _captureStateFlow.asStateFlow()

    fun setExpoBracketingNImages(nImages: Int) {
        if (nImages <= 1 || (nImages % 2) == 0) {
            throw IllegalArgumentException("n_images should be an odd number greater than 1")
        }
        this.expoBracketingNImages = if (nImages > maxExpoBracketingNImages) {
            maxExpoBracketingNImages
        } else {
            nImages
        }
    }

    fun setExpoBracketingStops(stops: Double) {
        if (stops <= 0.0) {
            throw IllegalArgumentException("stops should be positive")
        }
        this.expoBracketingStops = stops
    }

    fun setBurstForNoiseReduction(
        burstForNoiseReduction: Boolean,
        noiseReductionLowLight: Boolean
    ) {
        this.burstForNoiseReduction = burstForNoiseReduction
        this.noiseReductionLowLight = noiseReductionLowLight
    }

    val isCaptureFastBurst: Boolean
        get() = burstType !== BurstType.BURSTTYPE_NONE && burstType !== BurstType.BURSTTYPE_FOCUS

    fun isCapturingBurst(
        nBurstTaken: Int,
        nBurstTotal: Int,
        nBurst: Int,
        nBurstRaw: Int
    ): Boolean {
        if (burstType === BurstType.BURSTTYPE_NONE) return false
        if (burstType === BurstType.BURSTTYPE_CONTINUOUS) {
            return isContinuousBurstInProgress || nBurst > 0 || nBurstRaw > 0
        }
        val total = calculateBurstTotal(nBurstTotal)
        return total > 1 && nBurstTaken < total
    }

    fun calculateBurstTotal(nBurstTotal: Int): Int {
        if (burstType === BurstType.BURSTTYPE_CONTINUOUS) return 0
        return nBurstTotal
    }

    fun startContinuousBurst() {
        this.isContinuousBurstInProgress = true
        this.continuousBurstRequestedLastCapture = false
        _captureStateFlow.value = Camera2CaptureState.Capturing(
            burstType = BurstType.BURSTTYPE_CONTINUOUS,
            taken = 0,
            total = 0
        )
    }

    fun stopContinuousBurst() {
        this.isContinuousBurstInProgress = false
        _captureStateFlow.value = Camera2CaptureState.Completed(
            burstType = BurstType.BURSTTYPE_CONTINUOUS,
            totalTaken = 0
        )
    }

    fun startFocusBracketing() {
        this.focusBracketingInProgress = true
        _captureStateFlow.value = Camera2CaptureState.Preparing(
            burstType = BurstType.BURSTTYPE_FOCUS,
            targetCount = focusBracketingNImages + (if (focusBracketingAddInfinity) 1 else 0)
        )
    }

    fun stopFocusBracketing() {
        this.focusBracketingInProgress = false
        _captureStateFlow.value = Camera2CaptureState.Completed(
            burstType = BurstType.BURSTTYPE_FOCUS,
            totalTaken = 0
        )
    }

    fun generateFocusBracketingDistances(): List<Float> {
        val distances = FocusBracketingCalculator.setupFocusBracketingDistances(
            source = focusBracketingSourceDistance,
            target = focusBracketingTargetDistance,
            count = focusBracketingNImages
        )
        if (focusBracketingAddInfinity) {
            distances.add(0.0f)
        }
        return distances
    }

    fun resetCaptureState() {
        this.isContinuousBurstInProgress = false
        this.continuousBurstRequestedLastCapture = false
        this.focusBracketingInProgress = false
        _captureStateFlow.value = Camera2CaptureState.Idle
    }
}

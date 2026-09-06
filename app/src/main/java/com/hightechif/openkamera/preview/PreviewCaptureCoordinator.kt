/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview

/**
 * Coordinates photo capture execution, continuous burst sequencing,
 * exposure bracketing, focus bracketing, and panorama capture step state.
 */
class PreviewCaptureCoordinator(
    private val applicationInterface: ApplicationInterface,
    val stateMachine: CameraCaptureStateMachine = CameraCaptureStateMachine()
) {

    companion object {
        private const val TAG = "PreviewCaptureCoord"
    }

    var isTakingPhoto: Boolean = false
    var isTakingPhotoOnTimer: Boolean = false
    var burstCount: Int = 0
    var burstTotal: Int = 1

    /**
     * Computes exposure values (in EV stops or compensation steps) for exposure bracketing.
     * @param nImages Total number of bracketed shots (typically 3 or 5).
     * @param stops Difference in stops between brackets (e.g. 1.0, 2.0).
     */
    fun computeExposureBracketingValues(
        nImages: Int,
        stops: Double
    ): List<Double> {
        val values = mutableListOf<Double>()
        val half = nImages / 2
        val step = stops / half
        for (i in -half..half) {
            values.add(i * step)
        }
        return values
    }

    /**
     * Computes focus distance steps for focus bracketing from nearest to infinity.
     */
    fun computeFocusBracketingDistances(
        sourceDistance: Float,
        targetDistance: Float,
        nImages: Int,
        addInfinity: Boolean
    ): List<Float> {
        val distances = mutableListOf<Float>()
        if (nImages <= 1) {
            distances.add(sourceDistance)
            return distances
        }

        val step = (targetDistance - sourceDistance) / (nImages - 1)
        for (i in 0 until nImages) {
            distances.add(sourceDistance + i * step)
        }
        if (addInfinity && !distances.contains(0.0f)) {
            distances.add(0.0f) // 0.0f represents infinity in Camera2 diopters
        }
        return distances
    }

    fun startBurst(total: Int) {
        burstTotal = total
        burstCount = 0
        isTakingPhoto = true
    }

    fun onBurstPhotoTaken(): Boolean {
        burstCount++
        if (burstCount >= burstTotal) {
            isTakingPhoto = false
            return false // Burst complete
        }
        return true // Continue burst
    }

    fun reset() {
        isTakingPhoto = false
        isTakingPhotoOnTimer = false
        burstCount = 0
        burstTotal = 1
        stateMachine.reset()
    }
}

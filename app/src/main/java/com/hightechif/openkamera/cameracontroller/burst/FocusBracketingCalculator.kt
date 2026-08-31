package com.hightechif.openkamera.cameracontroller.burst

import kotlin.math.ln
import kotlin.math.max

/**
 * Pure mathematical calculation utilities for focus bracketing distance steps.
 */
object FocusBracketingCalculator {

    private const val MAX_FOCUS_BRACKET_DISTANCE_C = 0.1f // 10m

    /**
     * Sets up interpolated focus distances between [source] and [target] for [count] images.
     * Uses logarithmic distance interpolation to provide more sample shots at closer focus distances.
     */
    fun setupFocusBracketingDistances(
        source: Float,
        target: Float,
        count: Int
    ): MutableList<Float> {
        val focusDistances: MutableList<Float> = ArrayList()
        if (count <= 0) return focusDistances

        var focusDistanceS = source
        var focusDistanceE = target

        // Clamped to max distance to avoid taking reciprocal of 0
        focusDistanceS = max(focusDistanceS.toDouble(), MAX_FOCUS_BRACKET_DISTANCE_C.toDouble()).toFloat()
        focusDistanceE = max(focusDistanceE.toDouble(), MAX_FOCUS_BRACKET_DISTANCE_C.toDouble()).toFloat()

        // Interpolate linearly in real distance (1.0 / focusDistance)
        val realFocusDistanceS = 1.0f / focusDistanceS
        val realFocusDistanceE = 1.0f / focusDistanceE

        for (i in 0 until count) {
            val distance: Float
            when (i) {
                0 -> {
                    distance = source
                }
                count - 1 -> {
                    distance = target
                }
                else -> {
                    var value = i
                    if (realFocusDistanceS > realFocusDistanceE) {
                        value = count - 1 - i
                    }
                    var alpha = (1.0 - ln((count - value).toDouble()) / ln(count.toDouble())).toFloat()
                    if (realFocusDistanceS > realFocusDistanceE) {
                        alpha = 1.0f - alpha
                    }
                    val realDistance = (1.0f - alpha) * realFocusDistanceS + alpha * realFocusDistanceE
                    distance = 1.0f / realDistance
                }
            }
            focusDistances.add(distance)
        }
        return focusDistances
    }
}

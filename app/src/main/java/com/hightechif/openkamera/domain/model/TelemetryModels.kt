/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.domain.model

data class CameraFrameMetadata(
    val iso: Int? = null,
    val exposureTimeNs: Long? = null,
    val aperture: Float? = null,
    val focalLengthMm: Float? = null,
    val focusDistanceMeters: Float? = null,
    val whiteBalanceKelvin: Int? = null,
    val sensorSensitivity: Int? = null,
    val timestampNs: Long = 0L
)

data class HorizonAngle(
    val angleDegrees: Double = 0.0,
    val isLevel: Boolean = false
)

data class SensorOrientation(
    val horizonAngle: HorizonAngle = HorizonAngle(),
    val compassDegrees: Float = 0.0f,
    val pitchDegrees: Float = 0.0f,
    val rollDegrees: Float = 0.0f
)

data class HistogramData(
    val redChannels: IntArray = IntArray(0),
    val greenChannels: IntArray = IntArray(0),
    val blueChannels: IntArray = IntArray(0),
    val luminance: IntArray = IntArray(0)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HistogramData) return false
        return luminance.contentEquals(other.luminance)
    }

    override fun hashCode(): Int = luminance.contentHashCode()
}

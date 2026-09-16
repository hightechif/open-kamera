/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview.sensor

import android.hardware.SensorEvent
import android.hardware.SensorManager
import android.util.Log
import com.hightechif.openkamera.domain.model.HorizonAngle
import com.hightechif.openkamera.domain.model.SensorOrientation
import com.hightechif.openkamera.preview.ApplicationInterface
import com.hightechif.openkamera.utils.MyDebug
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Manages sensor fusion (accelerometer, gravity, magnetometer) for device level tracking,
 * horizon roll angle, pitch angle, compass geo-direction, and reactive orientation flows.
 */
class PreviewSensorManager(
    private val applicationInterface: ApplicationInterface
) {

    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private val deviceRotation = FloatArray(9)
    private val cameraRotation = FloatArray(9)
    private val deviceInclination = FloatArray(9)
    private val geoDirectionArray = FloatArray(3)
    private val newGeoDirection = FloatArray(3)

    var hasGravity: Boolean = false
        private set
    var hasGeomagnetic: Boolean = false
        private set
    var hasLevelAngle: Boolean = false
        private set
    var naturalLevelAngle: Double = 0.0
        private set
    var levelAngle: Double = 0.0
        private set
    var origLevelAngle: Double = 0.0
        private set
    var hasPitchAngle: Boolean = false
        private set
    var pitchAngle: Double = 0.0
        private set
    var hasGeoDirection: Boolean = false
        private set
    var currentOrientation: Int = 0

    var isTest: Boolean = false

    private val _sensorOrientationFlow = MutableStateFlow(SensorOrientation())
    val sensorOrientationFlow: StateFlow<SensorOrientation> = _sensorOrientationFlow.asStateFlow()

    fun onOrientationChanged(orientation: Int) {
        if (orientation == android.view.OrientationEventListener.ORIENTATION_UNKNOWN) return
        val diff = abs(orientation - currentOrientation)
        if (diff in 46..314) {
            currentOrientation = ((orientation + 45) / 90 * 90) % 360
            updateLevelAngles()
        }
    }

    fun onAccelerometerSensorChanged(event: SensorEvent) {
        hasGravity = true
        for (i in 0..2) {
            gravity[i] = SENSOR_ALPHA * gravity[i] + (1.0f - SENSOR_ALPHA) * event.values[i]
        }
        calculateGeoDirection()

        val x = gravity[0].toDouble()
        val y = gravity[1].toDouble()
        val z = gravity[2].toDouble()
        val mag = sqrt(x * x + y * y + z * z)

        hasPitchAngle = false
        if (mag > 1.0e-8) {
            hasPitchAngle = true
            pitchAngle = asin(-z / mag) * 180.0 / PI

            hasLevelAngle = true
            naturalLevelAngle = atan2(-x, y) * 180.0 / PI
            if (naturalLevelAngle < -0.0) {
                naturalLevelAngle += 360.0
            }
            updateLevelAngles()
        } else {
            if (MyDebug.LOG) Log.e(TAG, "accel sensor has zero mag: $mag")
            hasLevelAngle = false
        }
    }

    fun onMagneticSensorChanged(event: SensorEvent) {
        hasGeomagnetic = true
        for (i in 0..2) {
            geomagnetic[i] = SENSOR_ALPHA * geomagnetic[i] + (1.0f - SENSOR_ALPHA) * event.values[i]
        }
        calculateGeoDirection()
    }

    fun updateLevelAngles() {
        if (hasLevelAngle) {
            levelAngle = naturalLevelAngle
            val calibratedLevelAngle: Double = applicationInterface.getCalibratedLevelAngle()
            levelAngle -= calibratedLevelAngle
            origLevelAngle = levelAngle
            levelAngle -= currentOrientation.toDouble()
            if (levelAngle < -180.0) {
                levelAngle += 360.0
            } else if (levelAngle > 180.0) {
                levelAngle -= 360.0
            }

            val isLevel = abs(levelAngle) <= 1.0
            val compassDegrees = if (hasGeoDirection) geoDirection.toFloat() else 0.0f
            _sensorOrientationFlow.value = SensorOrientation(
                horizonAngle = HorizonAngle(angleDegrees = levelAngle, isLevel = isLevel),
                compassDegrees = compassDegrees
            )
        }
    }

    fun hasLevelAngleStable(): Boolean {
        if (!isTest && hasPitchAngle && abs(pitchAngle) > 70.0) {
            return false
        }
        return hasLevelAngle
    }

    val levelAngleUncalibrated: Double
        get() = naturalLevelAngle - currentOrientation

    val geoDirection: Double
        get() {
            var geo = Math.toDegrees(geoDirectionArray[0].toDouble())
            if (geo < 0.0) {
                geo += 360.0
            }
            return geo
        }

    fun getGeoDirectionArray(): FloatArray = geoDirectionArray

    private fun calculateGeoDirection() {
        if (!hasGravity || !hasGeomagnetic) return
        if (!SensorManager.getRotationMatrix(deviceRotation, deviceInclination, gravity, geomagnetic)) return

        SensorManager.remapCoordinateSystem(
            deviceRotation,
            SensorManager.AXIS_X,
            SensorManager.AXIS_Z,
            cameraRotation
        )
        val hasOldGeoDirection = hasGeoDirection
        hasGeoDirection = true
        SensorManager.getOrientation(cameraRotation, newGeoDirection)

        for (i in 0..2) {
            var oldCompass = Math.toDegrees(geoDirectionArray[i].toDouble()).toFloat()
            val newCompass = Math.toDegrees(newGeoDirection[i].toDouble()).toFloat()
            oldCompass = if (hasOldGeoDirection) {
                lowPassFilter(oldCompass, newCompass, 0.1f, 10.0f)
            } else {
                newCompass
            }
            geoDirectionArray[i] = Math.toRadians(oldCompass.toDouble()).toFloat()
        }
    }

    private fun lowPassFilter(oldValue: Float, newValue: Float, alpha: Float, maxDiff: Float): Float {
        var diff = newValue - oldValue
        while (diff > 180.0f) diff -= 360.0f
        while (diff < -180.0f) diff += 360.0f

        val effectiveAlpha = if (abs(diff) > maxDiff) 1.0f else alpha
        var result = oldValue + effectiveAlpha * diff
        while (result >= 360.0f) result -= 360.0f
        while (result < 0.0f) result += 360.0f
        return result
    }

    companion object {
        private const val TAG = "PreviewSensorManager"
        private const val SENSOR_ALPHA = 0.8f
    }
}

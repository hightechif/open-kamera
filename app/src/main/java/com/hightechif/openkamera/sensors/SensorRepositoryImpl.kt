/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.hightechif.openkamera.di.IoDispatcher
import com.hightechif.openkamera.domain.model.HorizonAngle
import com.hightechif.openkamera.domain.model.SensorOrientation
import com.hightechif.openkamera.domain.repository.ISensorRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

@Singleton
class SensorRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ISensorRepository, SensorEventListener {

    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val accelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magneticSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val gyroscope: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    override val hasGyroSensors: Boolean
        get() = gyroscope != null && accelerometer != null

    override val hasMagneticSensor: Boolean
        get() = magneticSensor != null

    private val gravityValues = FloatArray(3)
    private val geomagneticValues = FloatArray(3)
    private var hasGravity = false
    private var hasGeomagnetic = false

    private val rotationMatrix = FloatArray(9)
    private val cameraRotation = FloatArray(9)
    private val inclinationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    private var calibratedLevelAngle: Double = 0.0
    private var currentDeviceOrientation: Int = 0
    private var lastCompassDegrees: Float = 0.0f
    private var hasComputedCompass: Boolean = false

    private val _sensorOrientationFlow = MutableStateFlow(SensorOrientation())
    override val sensorOrientationFlow: Flow<SensorOrientation> =
        _sensorOrientationFlow.asStateFlow()

    private val _magneticAccuracyFlow = MutableStateFlow(SensorManager.SENSOR_STATUS_ACCURACY_HIGH)
    override val magneticAccuracyFlow: Flow<Int> = _magneticAccuracyFlow.asStateFlow()

    private var isListening = false

    override fun isSupported(): Boolean = accelerometer != null

    override fun setCalibratedLevelAngle(angle: Double) {
        calibratedLevelAngle = angle
    }

    override fun getCalibratedLevelAngle(): Double = calibratedLevelAngle

    override fun setDeviceOrientation(orientation: Int) {
        if (orientation == android.view.OrientationEventListener.ORIENTATION_UNKNOWN) return
        val diff = abs(orientation - currentDeviceOrientation)
        if (diff in 46..314) {
            currentDeviceOrientation = ((orientation + 45) / 90 * 90) % 360
        }
    }

    @Synchronized
    override fun startListening() {
        if (isListening || sensorManager == null) return
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        magneticSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        isListening = true
    }

    @Synchronized
    override fun stopListening() {
        if (!isListening || sensorManager == null) return
        sensorManager.unregisterListener(this)
        isListening = false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                hasGravity = applyLowPass(gravityValues, event.values, hasGravity)
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                hasGeomagnetic = applyLowPass(geomagneticValues, event.values, hasGeomagnetic)
            }

            Sensor.TYPE_GYROSCOPE -> {
                // Gyroscope updates available for orientation/fusion if needed
            }
        }

        if (hasGravity) {
            val x = gravityValues[0].toDouble()
            val y = gravityValues[1].toDouble()
            val z = gravityValues[2].toDouble()
            val mag = sqrt(x * x + y * y + z * z)

            if (mag > 1.0e-8) {
                val pitch = (asin((-z / mag).coerceIn(-1.0, 1.0)) * 180.0 / PI).toFloat()
                var naturalLevelAngle = atan2(-x, y) * 180.0 / PI
                if (naturalLevelAngle < 0.0) {
                    naturalLevelAngle += 360.0
                }

                var levelAngle = naturalLevelAngle - calibratedLevelAngle - currentDeviceOrientation.toDouble()
                while (levelAngle < -180.0) levelAngle += 360.0
                while (levelAngle > 180.0) levelAngle -= 360.0

                val isLevel = abs(levelAngle) <= 1.0 ||
                        abs(abs(levelAngle) - 90.0) <= 1.0 ||
                        abs(abs(levelAngle) - 180.0) <= 1.0

                var compassHeading = lastCompassDegrees
                var roll = 0.0f

                if (hasGeomagnetic) {
                    if (SensorManager.getRotationMatrix(
                            rotationMatrix,
                            inclinationMatrix,
                            gravityValues,
                            geomagneticValues
                        )
                    ) {
                        SensorManager.remapCoordinateSystem(
                            rotationMatrix,
                            SensorManager.AXIS_X,
                            SensorManager.AXIS_Z,
                            cameraRotation
                        )
                        SensorManager.getOrientation(cameraRotation, orientationAngles)
                        val rawCompass = ((Math.toDegrees(orientationAngles[0].toDouble()) + 360.0) % 360.0).toFloat()
                        compassHeading = if (hasComputedCompass) {
                            smoothCompass(lastCompassDegrees, rawCompass)
                        } else {
                            hasComputedCompass = true
                            rawCompass
                        }
                        lastCompassDegrees = compassHeading
                        roll = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()
                    }
                }

                _sensorOrientationFlow.value = SensorOrientation(
                    horizonAngle = HorizonAngle(angleDegrees = levelAngle, isLevel = isLevel),
                    compassDegrees = compassHeading,
                    pitchDegrees = pitch,
                    rollDegrees = roll
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) {
            _magneticAccuracyFlow.value = accuracy
        }
    }

    private fun applyLowPass(target: FloatArray, source: FloatArray, isInitialized: Boolean): Boolean {
        if (!isInitialized) {
            System.arraycopy(source, 0, target, 0, 3)
            return true
        }
        for (i in 0..2) {
            target[i] = SENSOR_ALPHA * target[i] + (1.0f - SENSOR_ALPHA) * source[i]
        }
        return true
    }

    private fun smoothCompass(oldValue: Float, newValue: Float, alpha: Float = 0.1f, maxDiff: Float = 10.0f): Float {
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
        private const val SENSOR_ALPHA = 0.8f
    }
}

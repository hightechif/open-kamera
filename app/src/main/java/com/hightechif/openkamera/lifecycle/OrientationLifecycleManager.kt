/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.lifecycle

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.hardware.display.DisplayManager.DisplayListener
import android.os.Build
import android.util.Log
import android.view.OrientationEventListener
import com.hightechif.openkamera.MainActivity
import com.hightechif.openkamera.sensors.MagneticSensor
import com.hightechif.openkamera.utils.MyDebug

/**
 * Coordinates physical device orientation tracking, display rotation change listening,
 * and accelerometer/magnetic sensor registration.
 */
class OrientationLifecycleManager(private val mainActivity: MainActivity) {

    private var orientationEventListener: OrientationEventListener? = null
    private var displayListener: DisplayListener? = null

    val accelerometerListener: SensorEventListener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

        override fun onSensorChanged(event: SensorEvent) {
            mainActivity.preview.onAccelerometerSensorChanged(event)
        }
    }

    fun initOrientationListener() {
        if (MainActivity.LOCK_TO_LANDSCAPE) {
            orientationEventListener = object : OrientationEventListener(mainActivity) {
                override fun onOrientationChanged(orientation: Int) {
                    mainActivity.mainUI.onOrientationChanged(orientation)
                }
            }
        }
    }

    fun onResume(sensorManager: SensorManager, accelerometerSensor: Sensor?, magneticSensor: MagneticSensor) {
        registerDisplayListener()
        if (accelerometerSensor != null) {
            sensorManager.registerListener(
                accelerometerListener,
                accelerometerSensor,
                SensorManager.SENSOR_DELAY_NORMAL
            )
        }
        magneticSensor.registerMagneticListener(sensorManager)
        orientationEventListener?.enable()
    }

    fun onPause(sensorManager: SensorManager, magneticSensor: MagneticSensor) {
        unregisterDisplayListener()
        sensorManager.unregisterListener(accelerometerListener)
        magneticSensor.unregisterMagneticListener(sensorManager)
        orientationEventListener?.disable()
    }

    private fun registerDisplayListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            if (displayListener == null) {
                displayListener = object : DisplayListener {
                    private var oldRotation = getDisplayRotation()

                    override fun onDisplayAdded(displayId: Int) {}
                    override fun onDisplayRemoved(displayId: Int) {}
                    override fun onDisplayChanged(displayId: Int) {
                        val newRotation = getDisplayRotation()
                        if (oldRotation != newRotation) {
                            if (MyDebug.LOG) Log.d(TAG, "display rotation changed from $oldRotation to $newRotation")
                            oldRotation = newRotation
                            mainActivity.mainUI.layoutUI()
                        }
                    }
                }
            }
            val displayManager = mainActivity.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            displayManager?.registerDisplayListener(displayListener, null)
        }
    }

    private fun unregisterDisplayListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && displayListener != null) {
            val displayManager = mainActivity.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            displayManager?.unregisterDisplayListener(displayListener)
        }
    }

    @Suppress("DEPRECATION")
    fun getDisplayRotation(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            mainActivity.display?.rotation ?: 0
        } else {
            mainActivity.windowManager.defaultDisplay.rotation
        }
    }

    companion object {
        private const val TAG = "OrientationLifecycle"
    }
}

/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.sensors

import android.app.AlertDialog
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.preference.PreferenceManager
import android.util.Log
import com.hightechif.openkamera.MainActivity
import com.hightechif.openkamera.R
import com.hightechif.openkamera.preferences.PreferenceKeys
import com.hightechif.openkamera.utils.MyDebug


/** Handles magnetic sensor.
 */
class MagneticSensor(private val mainActivity: MainActivity) {
    private var mSensorMagnetic: Sensor? = null

    var magneticAccuracy: Int = -1
        private set
    private var magneticAccuracyDialog: AlertDialog? = null

    private var magneticListenerIsRegistered = false

    fun initSensor(mSensorManager: SensorManager) {
        if (MyDebug.LOG) Log.d(TAG, "initSensor")
        if (mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null) {
            if (MyDebug.LOG) Log.d(TAG, "found magnetic sensor")
            mSensorMagnetic = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        } else {
            if (MyDebug.LOG) Log.d(TAG, "no support for magnetic sensor")
        }
    }


    /** Registers the magnetic sensor, only if it's required (by user preferences), and hasn't already
     * been registered.
     * If the magnetic sensor was previously registered, but is no longer required by user preferences,
     * then it is unregistered.
     */
    fun registerMagneticListener(mSensorManager: SensorManager) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
        if (!magneticListenerIsRegistered) {
            if (needsMagneticSensor(sharedPreferences)) {
                if (MyDebug.LOG) Log.d(TAG, "register magneticListener")
                mSensorManager.registerListener(
                    magneticListener,
                    mSensorMagnetic,
                    SensorManager.SENSOR_DELAY_NORMAL
                )
                magneticListenerIsRegistered = true
            } else {
                if (MyDebug.LOG) Log.d(TAG, "don't register magneticListener as not needed")
            }
        } else {
            if (needsMagneticSensor(sharedPreferences)) {
                if (MyDebug.LOG) Log.d(TAG, "magneticListener already registered")
            } else {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "magneticListener already registered but no longer needed"
                )
                mSensorManager.unregisterListener(magneticListener)
                magneticListenerIsRegistered = false
            }
        }
    }

    /** Unregisters the magnetic sensor, if it was registered.
     */
    fun unregisterMagneticListener(mSensorManager: SensorManager) {
        if (magneticListenerIsRegistered) {
            if (MyDebug.LOG) Log.d(TAG, "unregister magneticListener")
            mSensorManager.unregisterListener(magneticListener)
            magneticListenerIsRegistered = false
        } else {
            if (MyDebug.LOG) Log.d(TAG, "magneticListener wasn't registered")
        }
    }

    private val magneticListener: SensorEventListener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "magneticListener.onAccuracyChanged: $accuracy"
            )
            //accuracy = SensorManager.SENSOR_STATUS_ACCURACY_LOW; // test
            this@MagneticSensor.magneticAccuracy = accuracy
            setMagneticAccuracyDialogText() // update if a dialog is already open for this
            checkMagneticAccuracy()

            // test accuracy changing after dialog opened:
            /*Handler handler = new Handler();
			handler.postDelayed(new Runnable() {
				public void run() {
					MainActivity.this.magneticAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH;
					setMagneticAccuracyDialogText();
					checkMagneticAccuracy();
				}
			}, 5000);*/
        }

        override fun onSensorChanged(event: SensorEvent) {
            mainActivity.preview.onMagneticSensorChanged(event)
        }
    }

    private fun setMagneticAccuracyDialogText() {
        if (MyDebug.LOG) Log.d(TAG, "setMagneticAccuracyDialogText()")
        if (magneticAccuracyDialog != null) {
            var message = mainActivity.resources.getString(R.string.magnetic_accuracy_info) + " "
            message += when (magneticAccuracy) {
                SensorManager.SENSOR_STATUS_UNRELIABLE -> mainActivity.resources.getString(R.string.accuracy_unreliable)
                SensorManager.SENSOR_STATUS_ACCURACY_LOW -> mainActivity.resources.getString(R.string.accuracy_low)
                SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> mainActivity.resources.getString(R.string.accuracy_medium)
                SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> mainActivity.resources.getString(R.string.accuracy_high)
                else -> mainActivity.resources.getString(R.string.accuracy_unknown)
            }
            if (MyDebug.LOG) Log.d(TAG, "message: $message")
            magneticAccuracyDialog!!.setMessage(message)
        }
    }

    private var shownMagneticAccuracyDialog =
        false // whether the dialog for poor magnetic accuracy has been shown since application start

    /** Checks whether the user should be informed about poor magnetic sensor accuracy, and shows
     * the dialog if so.
     */
    fun checkMagneticAccuracy() {
        if (MyDebug.LOG) Log.d(TAG, "checkMagneticAccuracy(): $magneticAccuracy")
        if (magneticAccuracy != SensorManager.SENSOR_STATUS_UNRELIABLE && magneticAccuracy != SensorManager.SENSOR_STATUS_ACCURACY_LOW) {
            if (MyDebug.LOG) Log.d(TAG, "accuracy is good enough (or accuracy not yet known)")
        } else if (shownMagneticAccuracyDialog) {
            // if we've shown the dialog since application start, then don't show again even if the user didn't click to not show again
            if (MyDebug.LOG) Log.d(TAG, "already shown_magnetic_accuracy_dialog")
        } else if (mainActivity.preview.isTakingPhotoOrOnTimer || mainActivity.preview.isVideoRecording
        ) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "don't disturb whilst taking photo, on timer, or recording video"
            )
        } else if (mainActivity.isCameraInBackground) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "don't show magnetic accuracy dialog due to camera in background"
            )
            // don't want to show dialog if another is open, or in settings, etc
        } else {
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
            if (!needsMagneticSensor(sharedPreferences)) {
                if (MyDebug.LOG) Log.d(TAG, "don't need magnetic sensor")
                // note, we shouldn't set shownMagneticAccuracyDialog to true here, otherwise we won't pick up if the user enables one of these options
            } else if (sharedPreferences.contains(PreferenceKeys.MAGNETIC_ACCURACY_PREFERENCE_KEY)) {
                if (MyDebug.LOG) Log.d(TAG, "user selected to no longer show the dialog")
                shownMagneticAccuracyDialog =
                    true // also set this flag, so future calls to checkMagneticAccuracy() will exit without needing to get/read the SharedPreferences
            } else {
                if (MyDebug.LOG) Log.d(TAG, "show dialog for magnetic accuracy")
                shownMagneticAccuracyDialog = true
                magneticAccuracyDialog = mainActivity.mainUI.showInfoDialog(
                    R.string.magnetic_accuracy_title,
                    0,
                    PreferenceKeys.MAGNETIC_ACCURACY_PREFERENCE_KEY
                )
                setMagneticAccuracyDialogText()
            }
        }
    }

    /* Whether the user preferences indicate that we need the magnetic sensor to be enabled.
     */
    private fun needsMagneticSensor(sharedPreferences: SharedPreferences): Boolean {
        return mainActivity.applicationInterface.geodirectionPref ||
                sharedPreferences.getBoolean(PreferenceKeys.ADD_YPR_TO_COMMENTS, false) ||
                sharedPreferences.getBoolean(
                    PreferenceKeys.SHOW_GEO_DIRECTION_LINES_PREFERENCE_KEY,
                    false
                ) ||
                sharedPreferences.getBoolean(PreferenceKeys.SHOW_GEO_DIRECTION_PREFERENCE_KEY, false)
    }

    fun clearDialog() {
        this.magneticAccuracyDialog = null
    }

    companion object {
        private const val TAG = "MagneticSensor"
    }
}

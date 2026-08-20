package com.hightechif.openkamera.preferences

import android.app.AlertDialog
import android.os.Bundle
import android.preference.ListPreference
import android.preference.Preference
import android.preference.PreferenceGroup
import android.preference.PreferenceManager
import android.util.Log
import android.widget.Toast
import com.hightechif.openkamera.MainActivity
import com.hightechif.openkamera.R
import com.hightechif.openkamera.utils.MyDebug

class PreferenceSubCameraControlsMore : PreferenceSubScreen() {

    override fun onCreate(savedInstanceState: Bundle?) {
        if (MyDebug.LOG) Log.d(TAG, "onCreate")
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.preferences_sub_camera_controls_more)

        val bundle = arguments
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this.activity)

        val canDisableShutterSound = bundle.getBoolean("can_disable_shutter_sound")
        if (MyDebug.LOG) Log.d(TAG, "can_disable_shutter_sound: $canDisableShutterSound")
        if (!canDisableShutterSound) {
            val pref = findPreference("preference_shutter_sound")
            val pg = findPreference("preferences_root") as PreferenceGroup
            pg.removePreference(pref)
        }

        run {
            val pref = findPreference("preference_save_location")
            pref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                if (MyDebug.LOG) Log.d(TAG, "clicked save location")
                val mainActivity = this.activity as MainActivity
                if (mainActivity.storageUtils.isUsingSAF) {
                    mainActivity.openFolderChooserDialogSAF(true)
                    true
                } else if (MainActivity.useScopedStorage()) {
                    val alertDialog = mainActivity.createSaveFolderDialog()
                    val alert = alertDialog.create()
                    alert.setOnDismissListener {
                        if (MyDebug.LOG) Log.d(TAG, "save folder dialog dismissed")
                        dialogs.remove(alert)
                    }
                    alert.show()
                    dialogs.add(alert)
                    true
                } else {
                    val startFolder = mainActivity.storageUtils.imageFolder
                    val fragment = MyPreferenceFragment.SaveFolderChooserDialog()
                    fragment.setStartFolder(startFolder)
                    fragment.show(fragmentManager, "FOLDER_FRAGMENT")
                    true
                }
            }
        }

        run {
            val pref = findPreference("preference_using_saf")
            pref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                if (pref.key == "preference_using_saf") {
                    if (MyDebug.LOG) Log.d(TAG, "user clicked saf")
                    if (sharedPreferences.getBoolean(PreferenceKeys.USING_SAF_PREFERENCE_KEY, false)) {
                        if (MyDebug.LOG) Log.d(TAG, "saf is now enabled")
                        val mainActivity = this.activity as MainActivity
                        Toast.makeText(
                            mainActivity,
                            R.string.saf_select_save_location,
                            Toast.LENGTH_SHORT
                        ).show()
                        mainActivity.openFolderChooserDialogSAF(true)
                    } else {
                        if (MyDebug.LOG) Log.d(TAG, "saf is now disabled")
                    }
                }
                false
            }
        }

        run {
            val pref = findPreference("preference_calibrate_level")
            pref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                if (pref.key == "preference_calibrate_level") {
                    if (MyDebug.LOG) Log.d(TAG, "user clicked calibrate level option")
                    val alertDialog = AlertDialog.Builder(this.activity)
                    alertDialog.setTitle(activity.resources.getString(R.string.preference_calibrate_level))
                    alertDialog.setMessage(R.string.preference_calibrate_level_dialog)
                    alertDialog.setPositiveButton(R.string.preference_calibrate_level_calibrate) { _, _ ->
                        if (MyDebug.LOG) Log.d(TAG, "user clicked calibrate level")
                        val mainActivity = this.activity as MainActivity
                        if (mainActivity.preview.hasLevelAngleStable()) {
                            val currentLevelAngle = mainActivity.preview.levelAngleUncalibrated
                            val editor = sharedPreferences.edit()
                            editor.putFloat(
                                PreferenceKeys.CALIBRATED_LEVEL_ANGLE_PREFERENCE_KEY,
                                currentLevelAngle.toFloat()
                            )
                            editor.apply()
                            mainActivity.preview.updateLevelAngles()
                            Toast.makeText(
                                mainActivity,
                                R.string.preference_calibrate_level_calibrated,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    alertDialog.setNegativeButton(R.string.preference_calibrate_level_reset) { _, _ ->
                        if (MyDebug.LOG) Log.d(TAG, "user clicked reset calibration level")
                        val mainActivity = this.activity as MainActivity
                        val editor = sharedPreferences.edit()
                        editor.putFloat(PreferenceKeys.CALIBRATED_LEVEL_ANGLE_PREFERENCE_KEY, 0.0f)
                        editor.apply()
                        mainActivity.preview.updateLevelAngles()
                        Toast.makeText(
                            mainActivity,
                            R.string.preference_calibrate_level_calibration_reset,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    val alert = alertDialog.create()
                    alert.setOnDismissListener {
                        if (MyDebug.LOG) Log.d(TAG, "calibration dialog dismissed")
                        dialogs.remove(alert)
                    }
                    alert.show()
                    dialogs.add(alert)
                    false
                } else {
                    false
                }
            }
        }

        MyPreferenceFragment.setSummary(findPreference("preference_save_photo_prefix"))
        MyPreferenceFragment.setSummary(findPreference("preference_save_video_prefix"))

        setupDependencies()

        if (MyDebug.LOG) Log.d(TAG, "onCreate done")
    }

    private fun setupDependencies() {
        val pref = findPreference("preference_audio_control") as? ListPreference
        if (pref != null) {
            pref.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                val value = newValue.toString()
                setAudioNoiseControlSensitivityDependency(value)
                true
            }
            setAudioNoiseControlSensitivityDependency(pref.value)
        }
    }

    private fun setAudioNoiseControlSensitivityDependency(newValue: String?) {
        val dependent = findPreference("preference_audio_noise_control_sensitivity")
        if (dependent != null) {
            val enableDependent = "noise" == newValue
            if (MyDebug.LOG) Log.d(
                TAG,
                "clicked audio control: $newValue enable_dependent: $enableDependent"
            )
            dependent.isEnabled = enableDependent
        }
    }

    companion object {
        private const val TAG = "PfSubCameraControlsMore"
    }
}

/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.utils

import android.app.AlertDialog
import android.preference.PreferenceManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import com.hightechif.openkamera.MainActivity
import com.hightechif.openkamera.MyApplicationInterface
import com.hightechif.openkamera.R
import com.hightechif.openkamera.preferences.PreferenceKeys
import com.hightechif.openkamera.preview.ApplicationInterface

/**
 * This contains functionality related to the (mainly customisable) on-screen icons.
 * To add a new customisable on-screen icon:
 * - Add the button to addOnScreenIcons().
 * - If the icon image or content description should depend on something persistent (e.g., saved
 *   preference), then add to updateOnScreenIcons(), with a corresponding new update*Icon() method.
 * - Add to setVisibility() (with a corresponding new show*Icon() method).
 * - Add to checkDisableGUIIcons().
 * - Add a new clicked*() method, and call this from a corresponding onClick method in MainActivity.
 * - Adding the corresponding preference for whether to show the on-screen icon or not (in
 *   preferences_sub_gui.xml).
 */
class OnScreenIcons(private val mainActivity: MainActivity) {

    private val exposureLockToast = ToastBoxer()
    private val whiteBalanceLockToast = ToastBoxer()
    private val storeLocationToast = ToastBoxer()
    private val stampToast = ToastBoxer()
    private val faceDetectionToast = ToastBoxer()
    private val cycleLockOrientationToast = ToastBoxer()
    private val previewShotsToast = ToastBoxer()

    init {
        if (MyDebug.LOG) Log.d(TAG, "OnScreenIcons")
    }

    /** Adds the on-screen icons (whether enabled or not) to the supplied list. */
    fun addOnScreenIcons(buttons: MutableList<View>) {
        buttons.add(mainActivity.findViewById(R.id.exposure_lock))
        buttons.add(mainActivity.findViewById(R.id.white_balance_lock))
        buttons.add(mainActivity.findViewById(R.id.cycle_raw))
        buttons.add(mainActivity.findViewById(R.id.store_location))
        buttons.add(mainActivity.findViewById(R.id.text_stamp))
        buttons.add(mainActivity.findViewById(R.id.stamp))
        buttons.add(mainActivity.findViewById(R.id.focus_peaking))
        buttons.add(mainActivity.findViewById(R.id.auto_level))
        buttons.add(mainActivity.findViewById(R.id.cycle_flash))
        buttons.add(mainActivity.findViewById(R.id.face_detection))
        buttons.add(mainActivity.findViewById(R.id.audio_control))
        buttons.add(mainActivity.findViewById(R.id.cycle_lock_orientation))
        buttons.add(mainActivity.findViewById(R.id.preview_shots))
    }

    fun updateOnScreenIcons() {
        if (MyDebug.LOG) Log.d(TAG, "updateOnScreenIcons")
        updateExposureLockIcon()
        updateWhiteBalanceLockIcon()
        updateCycleRawIcon()
        updateStoreLocationIcon()
        updateTextStampIcon()
        updateStampIcon()
        updateFocusPeakingIcon()
        updateAutoLevelIcon()
        updateCycleFlashIcon()
        updateFaceDetectionIcon()
        updateCycleLockOrientationIcon()
        updatePreviewShotsIcon()
    }

    private fun updateExposureLockIcon() {
        val view = mainActivity.findViewById<ImageButton>(R.id.exposure_lock)
        val enabled = mainActivity.preview.isExposureLocked
        view.setImageResource(if (enabled) R.drawable.exposure_locked else R.drawable.exposure_unlocked)
        view.contentDescription = mainActivity.resources.getString(if (enabled) R.string.exposure_unlock else R.string.exposure_lock)
    }

    private fun updateWhiteBalanceLockIcon() {
        val view = mainActivity.findViewById<ImageButton>(R.id.white_balance_lock)
        val enabled = mainActivity.preview.isWhiteBalanceLocked
        view.setImageResource(if (enabled) R.drawable.white_balance_locked else R.drawable.white_balance_unlocked)
        view.contentDescription = mainActivity.resources.getString(if (enabled) R.string.white_balance_unlock else R.string.white_balance_lock)
    }

    private fun updateCycleRawIcon() {
        val raw_pref = mainActivity.applicationInterface.getRawPref()
        val view = mainActivity.findViewById<ImageButton>(R.id.cycle_raw)
        if (raw_pref == ApplicationInterface.RawPref.RAWPREF_JPEG_DNG) {
            if (mainActivity.applicationInterface.isRawOnly) {
                view.setImageResource(R.drawable.raw_only_icon)
            } else {
                view.setImageResource(R.drawable.raw_icon)
            }
        } else {
            view.setImageResource(R.drawable.raw_off_icon)
        }
    }

    private fun updateStoreLocationIcon() {
        val view = mainActivity.findViewById<ImageButton>(R.id.store_location)
        val enabled = mainActivity.applicationInterface.getGeotaggingPref()
        view.setImageResource(if (enabled) R.drawable.ic_gps_fixed_red_48dp else R.drawable.ic_gps_fixed_white_48dp)
        view.contentDescription = mainActivity.resources.getString(if (enabled) R.string.preference_location_disable else R.string.preference_location_enable)
    }

    private fun updateTextStampIcon() {
        val view = mainActivity.findViewById<ImageButton>(R.id.text_stamp)
        val enabled = mainActivity.applicationInterface.textStampPref.isNotEmpty()
        view.setImageResource(if (enabled) R.drawable.baseline_text_fields_red_48 else R.drawable.baseline_text_fields_white_48)
    }

    private fun updateStampIcon() {
        val view = mainActivity.findViewById<ImageButton>(R.id.stamp)
        val enabled = mainActivity.applicationInterface.stampPref == "preference_stamp_yes"
        view.setImageResource(if (enabled) R.drawable.ic_text_format_red_48dp else R.drawable.ic_text_format_white_48dp)
        view.contentDescription = mainActivity.resources.getString(if (enabled) R.string.stamp_disable else R.string.stamp_enable)
    }

    private fun updateFocusPeakingIcon() {
        val view = mainActivity.findViewById<ImageButton>(R.id.focus_peaking)
        val enabled = mainActivity.applicationInterface.focusPeakingPref
        view.setImageResource(if (enabled) R.drawable.key_visualizer_red else R.drawable.key_visualizer)
        view.contentDescription = mainActivity.resources.getString(if (enabled) R.string.focus_peaking_disable else R.string.focus_peaking_enable)
    }

    private fun updateAutoLevelIcon() {
        val view = mainActivity.findViewById<ImageButton>(R.id.auto_level)
        val enabled = mainActivity.applicationInterface.autoStabilisePref
        view.setImageResource(if (enabled) R.drawable.auto_stabilise_icon_red else R.drawable.auto_stabilise_icon)
        view.contentDescription = mainActivity.resources.getString(if (enabled) R.string.auto_level_disable else R.string.auto_level_enable)
    }

    private fun updateCycleFlashIcon() {
        val flash_value = mainActivity.preview.currentFlashValue
        val view = mainActivity.findViewById<ImageButton>(R.id.cycle_flash)
        if (flash_value != null) {
            when (flash_value) {
                "flash_off" -> view.setImageResource(R.drawable.flash_off)
                "flash_auto", "flash_frontscreen_auto" -> view.setImageResource(R.drawable.flash_auto)
                "flash_on", "flash_frontscreen_on" -> view.setImageResource(R.drawable.flash_on)
                "flash_torch", "flash_frontscreen_torch" -> view.setImageResource(R.drawable.baseline_highlight_white_48)
                "flash_red_eye" -> view.setImageResource(R.drawable.baseline_remove_red_eye_white_48)
                else -> {
                    Log.e(TAG, "unknown flash value $flash_value")
                    view.setImageResource(R.drawable.flash_off)
                }
            }
        } else {
            view.setImageResource(R.drawable.flash_off)
        }
    }

    private fun updateFaceDetectionIcon() {
        val view = mainActivity.findViewById<ImageButton>(R.id.face_detection)
        val enabled = mainActivity.applicationInterface.getFaceDetectionPref()
        view.setImageResource(if (enabled) R.drawable.ic_face_red_48dp else R.drawable.ic_face_white_48dp)
        view.contentDescription = mainActivity.resources.getString(if (enabled) R.string.face_detection_disable else R.string.face_detection_enable)
    }

    private fun updateCycleLockOrientationIcon() {
        val view = mainActivity.findViewById<ImageButton>(R.id.cycle_lock_orientation)
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
        val pref = sharedPreferences.getString(PreferenceKeys.LOCK_ORIENTATION_PREFERENCE_KEY, "none") ?: "none"

        when (pref) {
            "portrait" -> view.setImageResource(R.drawable.mobile_lock_portrait_48px_red)
            "landscape" -> view.setImageResource(R.drawable.mobile_lock_landscape_48px_red)
            "none" -> view.setImageResource(R.drawable.mobile_unlock_48px)
            else -> {
                Log.e(TAG, "unknown lock orientation $pref")
                view.setImageResource(R.drawable.mobile_unlock_48px)
            }
        }
    }

    private fun updatePreviewShotsIcon() {
        val view = mainActivity.findViewById<ImageButton>(R.id.preview_shots)
        val enabled = mainActivity.applicationInterface.getPreShotsPref(mainActivity.applicationInterface.photoMode)
        view.setImageResource(if (enabled) R.drawable.motion_photos_on_48px_red else R.drawable.motion_photos_on_48px)
        view.contentDescription = mainActivity.resources.getString(if (enabled) R.string.preview_shots_disable else R.string.preview_shots_enable)
    }

    /**
     * Sets the visibility flag for on-screen icons.
     * @param visibility Visibility flag.
     * @param visibility_video Visibility flag to use for icons that are still allowed when recording video
     */
    fun setVisibility(visibility: Int, visibility_video: Int) {
        val exposureLockButton = mainActivity.findViewById<View>(R.id.exposure_lock)
        val whiteBalanceLockButton = mainActivity.findViewById<View>(R.id.white_balance_lock)
        val cycleRawButton = mainActivity.findViewById<View>(R.id.cycle_raw)
        val storeLocationButton = mainActivity.findViewById<View>(R.id.store_location)
        val textStampButton = mainActivity.findViewById<View>(R.id.text_stamp)
        val stampButton = mainActivity.findViewById<View>(R.id.stamp)
        val focusPeakingButton = mainActivity.findViewById<View>(R.id.focus_peaking)
        val autoLevelButton = mainActivity.findViewById<View>(R.id.auto_level)
        val cycleFlashButton = mainActivity.findViewById<View>(R.id.cycle_flash)
        val faceDetectionButton = mainActivity.findViewById<View>(R.id.face_detection)
        val audioControlButton = mainActivity.findViewById<View>(R.id.audio_control)
        val cycleLockOrientationButton = mainActivity.findViewById<View>(R.id.cycle_lock_orientation)
        val previewShotsButton = mainActivity.findViewById<View>(R.id.preview_shots)

        if (showExposureLockIcon()) exposureLockButton.visibility = visibility_video
        if (showWhiteBalanceLockIcon()) whiteBalanceLockButton.visibility = visibility_video
        if (showCycleRawIcon()) cycleRawButton.visibility = visibility
        if (showStoreLocationIcon()) storeLocationButton.visibility = visibility
        if (showTextStampIcon()) textStampButton.visibility = visibility
        if (showStampIcon()) stampButton.visibility = visibility
        if (showFocusPeakingIcon()) focusPeakingButton.visibility = visibility
        if (showAutoLevelIcon()) autoLevelButton.visibility = visibility
        if (showCycleFlashIcon()) cycleFlashButton.visibility = visibility
        if (showFaceDetectionIcon()) faceDetectionButton.visibility = visibility
        if (showAudioControlIcon()) audioControlButton.visibility = visibility
        if (showCycleLockOrientationIcon()) cycleLockOrientationButton.visibility = visibility
        if (showPreviewShotsIcon()) previewShotsButton.visibility = visibility
    }

    /**
     * Disables the optional on-screen icons if either user doesn't want to enable them, or not
     * supported). Note that displaying icons is done via MainUI.showGUI.
     * @return Whether an icon's visibility was changed.
     */
    fun checkDisableGUIIcons(): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "checkDisableGUIIcons")
        var changed = false
        
        fun checkAndHide(buttonId: Int, condition: Boolean) {
            if (!condition) {
                val button = mainActivity.findViewById<View>(buttonId)
                if (button != null) {
                    changed = changed || (button.visibility != View.GONE)
                    button.visibility = View.GONE
                }
            }
        }

        checkAndHide(R.id.exposure, mainActivity.supportsExposureButton())
        checkAndHide(R.id.exposure_lock, showExposureLockIcon())
        checkAndHide(R.id.white_balance_lock, showWhiteBalanceLockIcon())
        checkAndHide(R.id.cycle_raw, showCycleRawIcon())
        checkAndHide(R.id.store_location, showStoreLocationIcon())
        checkAndHide(R.id.text_stamp, showTextStampIcon())
        checkAndHide(R.id.stamp, showStampIcon())
        checkAndHide(R.id.focus_peaking, showFocusPeakingIcon())
        checkAndHide(R.id.auto_level, showAutoLevelIcon())
        checkAndHide(R.id.cycle_flash, showCycleFlashIcon())
        checkAndHide(R.id.face_detection, showFaceDetectionIcon())
        checkAndHide(R.id.audio_control, showAudioControlIcon())
        checkAndHide(R.id.cycle_lock_orientation, showCycleLockOrientationIcon())
        checkAndHide(R.id.preview_shots, showPreviewShotsIcon())
        checkAndHide(R.id.switch_multi_camera, mainActivity.showSwitchMultiCamIcon())

        if (MyDebug.LOG) Log.d(TAG, "checkDisableGUIIcons: $changed")
        return changed
    }

    private fun showExposureLockIcon(): Boolean {
        if (!mainActivity.preview.supportsExposureLock()) return false
        if (mainActivity.applicationInterface.isCameraExtensionPref()) return false
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
        return sharedPreferences.getBoolean(PreferenceKeys.SHOW_EXPOSURE_LOCK_PREFERENCE_KEY, true)
    }

    private fun showWhiteBalanceLockIcon(): Boolean {
        if (!mainActivity.preview.supportsWhiteBalanceLock()) return false
        if (mainActivity.applicationInterface.isCameraExtensionPref()) return false
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
        return sharedPreferences.getBoolean(PreferenceKeys.SHOW_WHITE_BALANCE_LOCK_PREFERENCE_KEY, false)
    }

    private fun showCycleRawIcon(): Boolean {
        if (!mainActivity.preview.supportsRaw()) return false
        if (!mainActivity.applicationInterface.isRawAllowed(mainActivity.applicationInterface.photoMode)) return false
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
        return sharedPreferences.getBoolean(PreferenceKeys.SHOW_CYCLE_RAW_PREFERENCE_KEY, false)
    }

    private fun showStoreLocationIcon(): Boolean {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
        return sharedPreferences.getBoolean(PreferenceKeys.SHOW_STORE_LOCATION_PREFERENCE_KEY, false)
    }

    private fun showTextStampIcon(): Boolean {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
        return sharedPreferences.getBoolean(PreferenceKeys.SHOW_TEXT_STAMP_PREFERENCE_KEY, false)
    }

    private fun showStampIcon(): Boolean {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
        return sharedPreferences.getBoolean(PreferenceKeys.SHOW_STAMP_PREFERENCE_KEY, false)
    }

    private fun showFocusPeakingIcon(): Boolean {
        if (!mainActivity.supportsPreviewBitmaps()) return false
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
        return sharedPreferences.getBoolean(PreferenceKeys.SHOW_FOCUS_PEAKING_PREFERENCE_KEY, false)
    }

    fun showAutoLevelIcon(): Boolean {
        if (!mainActivity.supportsAutoStabilise()) return false
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
        return sharedPreferences.getBoolean(PreferenceKeys.SHOW_AUTO_LEVEL_PREFERENCE_KEY, false)
    }

    fun showCycleFlashIcon(): Boolean {
        if (!mainActivity.preview.supportsFlash()) return false
        if (mainActivity.preview.isVideo) return false
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
        return sharedPreferences.getBoolean(PreferenceKeys.SHOW_CYCLE_FLASH_PREFERENCE_KEY, false)
    }

    private fun showFaceDetectionIcon(): Boolean {
        if (!mainActivity.preview.supportsFaceDetection()) return false
        if (mainActivity.applicationInterface.isCameraExtensionPref()) return false
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
        return sharedPreferences.getBoolean(PreferenceKeys.SHOW_FACE_DETECTION_PREFERENCE_KEY, false)
    }

    fun showAudioControlIcon(): Boolean {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
        val audio_control = sharedPreferences.getString(PreferenceKeys.AUDIO_CONTROL_PREFERENCE_KEY, "none")
        if (audio_control == "noise") {
            return true
        }
        return false
    }

    private fun showCycleLockOrientationIcon(): Boolean {
        if (mainActivity.applicationInterface.photoMode == MyApplicationInterface.PhotoMode.Panorama) return false
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
        return sharedPreferences.getBoolean(PreferenceKeys.SHOW_CYCLE_LOCK_ORIENTATION_PREFERENCE_KEY, false)
    }

    private fun showPreviewShotsIcon(): Boolean {
        if (!mainActivity.supportsPreShots()) return false
        val photo_mode = mainActivity.applicationInterface.photoMode
        if (mainActivity.preview.isVideo || photo_mode == MyApplicationInterface.PhotoMode.ExpoBracketing ||
            photo_mode == MyApplicationInterface.PhotoMode.FocusBracketing || photo_mode == MyApplicationInterface.PhotoMode.Panorama) {
            return false
        }
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
        return sharedPreferences.getBoolean(PreferenceKeys.SHOW_PREVIEW_SHOTS_PREFERENCE_KEY, false)
    }

    fun clickedExposureLock() {
        if (MyDebug.LOG) Log.d(TAG, "clickedExposureLock")
        mainActivity.preview.toggleExposureLock()
        updateExposureLockIcon()
        mainActivity.preview.showToast(exposureLockToast, if (mainActivity.preview.isExposureLocked) R.string.exposure_locked else R.string.exposure_unlocked, true)
    }

    fun clickedWhiteBalanceLock() {
        if (MyDebug.LOG) Log.d(TAG, "clickedWhiteBalanceLock")
        mainActivity.preview.toggleWhiteBalanceLock()
        updateWhiteBalanceLockIcon()
        mainActivity.preview.showToast(whiteBalanceLockToast, if (mainActivity.preview.isWhiteBalanceLocked) R.string.white_balance_locked else R.string.white_balance_unlocked, true)
    }

    fun clickedCycleRaw() {
        if (MyDebug.LOG) Log.d(TAG, "clickedCycleRaw")

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
        var new_value: String? = null
        when (sharedPreferences.getString(PreferenceKeys.RAW_PREFERENCE_KEY, "preference_raw_no")) {
            "preference_raw_no" -> new_value = "preference_raw_yes"
            "preference_raw_yes" -> new_value = "preference_raw_only"
            "preference_raw_only" -> new_value = "preference_raw_no"
            else -> Log.e(TAG, "unrecognised raw preference")
        }
        if (new_value != null) {
            val editor = sharedPreferences.edit()
            editor.putString(PreferenceKeys.RAW_PREFERENCE_KEY, new_value)
            editor.apply()

            val isRaw = new_value != "preference_raw_no"
            mainActivity.cameraViewModel.onEvent(com.hightechif.openkamera.ui.CameraUiEvent.OnRawToggled(isRaw))

            updateCycleRawIcon()
            mainActivity.applicationInterface.drawPreview.updateSettings()
            mainActivity.preview.reOpenKamera()
        }
    }

    fun clickedStoreLocation() {
        if (MyDebug.LOG) Log.d(TAG, "clickedStoreLocation")
        var value = mainActivity.applicationInterface.getGeotaggingPref()
        value = !value

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
        val editor = sharedPreferences.edit()
        editor.putBoolean(PreferenceKeys.LOCATION_PREFERENCE_KEY, value)
        editor.apply()

        updateStoreLocationIcon()
        mainActivity.applicationInterface.drawPreview.updateSettings()
        mainActivity.initLocation()
        mainActivity.closePopup()

        val message = mainActivity.resources.getString(R.string.preference_location) + ": " + mainActivity.resources.getString(if (value) R.string.on else R.string.off)
        mainActivity.preview.showToast(storeLocationToast, message, true)
    }

    fun clickedTextStamp() {
        if (MyDebug.LOG) Log.d(TAG, "clickedTextStamp")
        mainActivity.closePopup()

        val alertDialog = AlertDialog.Builder(mainActivity)
        alertDialog.setTitle(R.string.preference_textstamp)

        val dialog_view = LayoutInflater.from(mainActivity).inflate(R.layout.alertdialog_edittext, null)
        val editText = dialog_view.findViewById<EditText>(R.id.edit_text)
        editText.hint = mainActivity.resources.getString(R.string.preference_textstamp)
        editText.setText(mainActivity.applicationInterface.textStampPref)
        alertDialog.setView(dialog_view)
        alertDialog.setPositiveButton(android.R.string.ok) { _, _ ->
            if (MyDebug.LOG) Log.d(TAG, "custom text stamp clicked okay")

            val custom_text = editText.text.toString()
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
            val editor = sharedPreferences.edit()
            editor.putString(PreferenceKeys.TEXT_STAMP_PREFERENCE_KEY, custom_text)
            editor.apply()

            updateTextStampIcon()
        }
        alertDialog.setNegativeButton(android.R.string.cancel, null)

        val alert = alertDialog.create()
        alert.setOnDismissListener {
            if (MyDebug.LOG) Log.d(TAG, "custom stamp text dialog dismissed")
            mainActivity.setWindowFlagsForCamera()
            mainActivity.showPreview(true)
        }

        mainActivity.showPreview(false)
        mainActivity.setWindowFlagsForSettings(true)
        mainActivity.showAlert(alert)
    }

    fun clickedStamp() {
        if (MyDebug.LOG) Log.d(TAG, "clickedStamp")

        mainActivity.closePopup()

        var value = mainActivity.applicationInterface.stampPref == "preference_stamp_yes"
        value = !value
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
        val editor = sharedPreferences.edit()
        editor.putString(PreferenceKeys.STAMP_PREFERENCE_KEY, if (value) "preference_stamp_yes" else "preference_stamp_no")
        editor.apply()

        updateStampIcon()
        mainActivity.applicationInterface.drawPreview.updateSettings()
        mainActivity.preview.showToast(stampToast, if (value) R.string.stamp_enabled else R.string.stamp_disabled, true)
    }

    fun clickedFocusPeaking() {
        if (MyDebug.LOG) Log.d(TAG, "clickedFocusPeaking")
        var value = mainActivity.applicationInterface.focusPeakingPref
        value = !value

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
        val editor = sharedPreferences.edit()
        editor.putString(PreferenceKeys.FOCUS_PEAKING_PREFERENCE_KEY, if (value) "preference_focus_peaking_on" else "preference_focus_peaking_off")
        editor.apply()

        updateFocusPeakingIcon()
        mainActivity.applicationInterface.drawPreview.updateSettings()
    }

    fun clickedAutoLevel() {
        if (MyDebug.LOG) Log.d(TAG, "clickedAutoLevel")
        var value = mainActivity.applicationInterface.autoStabilisePref
        value = !value

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
        val editor = sharedPreferences.edit()
        editor.putBoolean(PreferenceKeys.AUTO_STABILISE_PREFERENCE_KEY, value)
        editor.apply()

        var done_dialog = false
        if (value) {
            val done_auto_stabilise_info = sharedPreferences.contains(PreferenceKeys.AUTO_STABILISE_INFO_PREFERENCE_KEY)
            if (!done_auto_stabilise_info) {
                mainActivity.mainUI.showInfoDialog(R.string.preference_auto_stabilise, R.string.auto_stabilise_info, PreferenceKeys.AUTO_STABILISE_INFO_PREFERENCE_KEY)
                done_dialog = true
            }
        }

        if (!done_dialog) {
            val message = mainActivity.resources.getString(R.string.preference_auto_stabilise) + ": " + mainActivity.resources.getString(if (value) R.string.on else R.string.off)
            mainActivity.preview.showToast(mainActivity.changedAutoStabiliseToastBoxer, message, true)
        }

        updateAutoLevelIcon()
        mainActivity.applicationInterface.drawPreview.updateSettings()
        mainActivity.closePopup()
    }

    fun clickedCycleFlash() {
        if (MyDebug.LOG) Log.d(TAG, "clickedCycleFlash")
        mainActivity.preview.cycleFlash(true, true)
        updateCycleFlashIcon()
    }

    fun clickedFaceDetection() {
        if (MyDebug.LOG) Log.d(TAG, "clickedFaceDetection")
        mainActivity.closePopup()

        var value = mainActivity.applicationInterface.getFaceDetectionPref()
        value = !value
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
        val editor = sharedPreferences.edit()
        editor.putBoolean(PreferenceKeys.FACE_DETECTION_PREFERENCE_KEY, value)
        editor.apply()

        updateFaceDetectionIcon()
        mainActivity.preview.showToast(faceDetectionToast, if (value) R.string.face_detection_enabled else R.string.face_detection_disabled, true)
        mainActivity.reOpenKamera(true)
    }

    fun clickedAudioControl() {
        if (MyDebug.LOG) Log.d(TAG, "clickedAudioControl")
        if (!showAudioControlIcon()) {
            if (MyDebug.LOG) Log.e(TAG, "clickedAudioControl, but hasAudioControl returns false!")
            return
        }
        mainActivity.closePopup()
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
        val audio_control = sharedPreferences.getString(PreferenceKeys.AUDIO_CONTROL_PREFERENCE_KEY, "none")
        if (audio_control == "noise") {
            if (mainActivity.hasAudioListener()) {
                mainActivity.freeAudioListener(false)
            } else {
                mainActivity.startAudioListener()
            }
        }
    }

    fun clickedCycleLockOrientation() {
        if (MyDebug.LOG) Log.d(TAG, "clickedCycleLockOrientation")

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
        var new_value: String? = null

        when (sharedPreferences.getString(PreferenceKeys.LOCK_ORIENTATION_PREFERENCE_KEY, "none")) {
            "none" -> new_value = "portrait"
            "portrait" -> new_value = "landscape"
            "landscape" -> new_value = "none"
            else -> Log.e(TAG, "unrecognised lock orientation preference")
        }

        if (new_value != null) {
            val editor = sharedPreferences.edit()
            editor.putString(PreferenceKeys.LOCK_ORIENTATION_PREFERENCE_KEY, new_value)
            editor.apply()

            updateCycleLockOrientationIcon()
            
            val entries_array = mainActivity.resources.getStringArray(R.array.preference_lock_orientation_entries)
            val values_array = mainActivity.resources.getStringArray(R.array.preference_lock_orientation_values)
            val index = values_array.indexOf(new_value)
            if (index != -1) {
                mainActivity.preview.showToast(cycleLockOrientationToast, entries_array[index], true)
            }
        }
    }

    fun clickedPreviewShots() {
        if (MyDebug.LOG) Log.d(TAG, "clickedPreviewShots")

        var value = mainActivity.applicationInterface.getPreShotsPref(mainActivity.applicationInterface.photoMode)
        value = !value
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
        val editor = sharedPreferences.edit()
        editor.putString(PreferenceKeys.PRE_SHOTS_PREFERENCE_KEY, if (value) "preference_save_preshots_on" else "preference_save_preshots_off")
        editor.apply()

        updatePreviewShotsIcon()
        mainActivity.applicationInterface.drawPreview.updateSettings()
        mainActivity.preview.showToast(previewShotsToast, if (value) R.string.preview_shots_enabled else R.string.preview_shots_disabled, true)
    }

    companion object {
        private const val TAG = "OnScreenIcons"
    }
}

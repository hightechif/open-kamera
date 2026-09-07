/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.ui.activity

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.preference.PreferenceManager
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.edit
import com.hightechif.openkamera.MainActivity
import com.hightechif.openkamera.R
import com.hightechif.openkamera.cameracontroller.CameraController
import com.hightechif.openkamera.preferences.PreferenceKeys
import com.hightechif.openkamera.ui.CameraUiEvent
import com.hightechif.openkamera.utils.MyDebug

/**
 * Handles hardware button, volume key, camera shutter, and Bluetooth/media key events.
 */
class KeyEventHandler(private val mainActivity: MainActivity) {

    private var keydownVolumeUp = false
    private var keydownVolumeDown = false

    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "onKeyDown: $keyCode")
        if (mainActivity.isCameraInBackground) {
            if (MyDebug.LOG) Log.d(TAG, "camera is in background")
            return false
        }

        val handledByUi = mainActivity.mainUI.onKeyDown(keyCode, event)
        if (handledByUi) return true

        return handleKeyEventInternal(keyCode, event)
    }

    fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "onKeyUp: $keyCode")
        if (mainActivity.isCameraInBackground) {
            if (MyDebug.LOG) Log.d(TAG, "camera is in background")
            return false
        }

        mainActivity.mainUI.onKeyUp(keyCode, event)
        return handleKeyUpInternal(keyCode, event)
    }

    fun handleKeyEventInternal(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) keydownVolumeUp = true
                else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) keydownVolumeDown = true

                val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
                val rawVolumeKeys = sharedPreferences.getString(
                    PreferenceKeys.VOLUME_KEYS_PREFERENCE_KEY,
                    "volume_take_photo"
                )
                val volumeKeys = if (rawVolumeKeys.isNullOrEmpty()) "volume_take_photo" else rawVolumeKeys

                if (isMediaKey(keyCode) && volumeKeys != "volume_take_photo") {
                    val audioManager = mainActivity.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    if (audioManager != null && !audioManager.isWiredHeadsetOn) return false
                }

                if (isVolumeKey(keyCode)) {
                    mainActivity.cameraViewModel.onEvent(CameraUiEvent.OnVolumeKeyPressed(keyCode))
                } else if (isMediaKey(keyCode) && volumeKeys == "volume_take_photo") {
                    mainActivity.cameraViewModel.onEvent(CameraUiEvent.OnShutterKeyPressed)
                }
                return processVolumeKeyAction(volumeKeys, keyCode, event, sharedPreferences)
            }

            KeyEvent.KEYCODE_MENU -> {
                mainActivity.cameraViewModel.onEvent(CameraUiEvent.OnSettingsClicked)
                mainActivity.openSettings()
                return true
            }

            KeyEvent.KEYCODE_CAMERA -> {
                mainActivity.cameraViewModel.onEvent(CameraUiEvent.OnShutterKeyPressed)
                if (event.repeatCount == 0) {
                    mainActivity.takePicture(false)
                    return true
                }
                if (event.downTime == event.eventTime && !mainActivity.preview.isFocusWaiting) {
                    if (MyDebug.LOG) Log.d(TAG, "request focus due to focus key")
                    mainActivity.preview.requestAutoFocus()
                }
                return true
            }

            KeyEvent.KEYCODE_FOCUS -> {
                mainActivity.cameraViewModel.onEvent(CameraUiEvent.OnFocusKeyPressed)
                if (event.downTime == event.eventTime && !mainActivity.preview.isFocusWaiting) {
                    if (MyDebug.LOG) Log.d(TAG, "request focus due to focus key")
                    mainActivity.preview.requestAutoFocus()
                }
                return true
            }
        }
        return false
    }

    fun handleKeyUpInternal(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) keydownVolumeUp = false
        else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) keydownVolumeDown = false
        return false
    }

    private fun processVolumeKeyAction(
        volumeKeys: String,
        keyCode: Int,
        event: KeyEvent,
        sharedPreferences: android.content.SharedPreferences
    ): Boolean {
        when (volumeKeys) {
            "volume_take_photo" -> {
                var done = false
                if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                    mainActivity.preview.isVideoRecording
                ) {
                    done = true
                    mainActivity.pauseVideo()
                }
                if (!done) {
                    mainActivity.cameraViewModel.onEvent(CameraUiEvent.OnShutterKeyPressed)
                    mainActivity.takePicture(false)
                }
                return true
            }

            "volume_focus" -> {
                if (keydownVolumeUp && keydownVolumeDown) {
                    if (MyDebug.LOG) Log.d(TAG, "take photo rather than focus, as both volume keys are down")
                    mainActivity.takePicture(false)
                } else if (mainActivity.preview.currentFocusValue == "focus_mode_manual2") {
                    if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) mainActivity.changeFocusDistance(-1, false)
                    else mainActivity.changeFocusDistance(1, false)
                } else {
                    if (event.downTime == event.eventTime && !mainActivity.preview.isFocusWaiting) {
                        if (MyDebug.LOG) Log.d(TAG, "request focus due to volume key")
                        mainActivity.preview.requestAutoFocus()
                    }
                }
                return true
            }

            "volume_zoom" -> {
                if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) mainActivity.zoomIn()
                else mainActivity.zoomOut()
                return true
            }

            "volume_exposure" -> {
                if (mainActivity.preview.cameraController != null) {
                    val value = sharedPreferences.getString(
                        PreferenceKeys.ISO_PREFERENCE_KEY,
                        CameraController.ISO_DEFAULT
                    )
                    val manualIso = value != CameraController.ISO_DEFAULT
                    if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                        if (manualIso) mainActivity.changeISO(1) else mainActivity.changeExposure(1)
                    } else {
                        if (manualIso) mainActivity.changeISO(-1) else mainActivity.changeExposure(-1)
                    }
                }
                return true
            }

            "volume_auto_stabilise" -> {
                if (mainActivity.supportsAutoStabilise()) {
                    var autoStabilise = sharedPreferences.getBoolean(
                        PreferenceKeys.AUTO_STABILISE_PREFERENCE_KEY,
                        false
                    )
                    autoStabilise = !autoStabilise
                    sharedPreferences.edit {
                        putBoolean(PreferenceKeys.AUTO_STABILISE_PREFERENCE_KEY, autoStabilise)
                    }
                    val message = mainActivity.resources.getString(R.string.preference_auto_stabilise) + ": " +
                            mainActivity.resources.getString(if (autoStabilise) R.string.on else R.string.off)
                    mainActivity.preview.showToast(mainActivity.changedAutoStabiliseToastBoxer, message, true)
                    mainActivity.applicationInterface.drawPreview.updateSettings()
                    mainActivity.mainUI.destroyPopup()
                } else if (!mainActivity.deviceSupportsAutoStabilise()) {
                    mainActivity.preview.showToast(
                        mainActivity.changedAutoStabiliseToastBoxer,
                        R.string.auto_stabilise_not_supported
                    )
                }
                return true
            }

            "volume_really_nothing" -> return true
        }
        return false
    }

    fun isVolumeKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
    }

    fun isMediaKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_HEADSETHOOK ||
                keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS ||
                keyCode == KeyEvent.KEYCODE_MEDIA_NEXT ||
                keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
                keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE ||
                keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                keyCode == KeyEvent.KEYCODE_MEDIA_STOP
    }

    companion object {
        private const val TAG = "KeyEventHandler"
    }
}

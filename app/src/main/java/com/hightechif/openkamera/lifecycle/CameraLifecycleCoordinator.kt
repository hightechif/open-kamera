/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.lifecycle

import android.app.KeyguardManager
import android.content.Context
import android.util.Log
import com.hightechif.openkamera.MainActivity
import com.hightechif.openkamera.utils.MyDebug

/**
 * Coordinates Android Activity lifecycle events (onResume, onPause, onDestroy)
 * with camera hardware session availability and background states.
 */
class CameraLifecycleCoordinator(private val mainActivity: MainActivity) {

    var isAppPaused: Boolean = false
        private set

    fun onResume() {
        if (MyDebug.LOG) Log.d(TAG, "onResume")
        isAppPaused = false
        mainActivity.isAppPaused = false

        mainActivity.applicationInterface.drawPreview.setCoverPreview(true)
        mainActivity.applicationInterface.drawPreview.clearDimPreview()

        if (!mainActivity.isCameraInBackground) {
            mainActivity.preview.onResume()
        }
    }

    fun onPause() {
        if (MyDebug.LOG) Log.d(TAG, "onPause")
        isAppPaused = true
        mainActivity.isAppPaused = true

        mainActivity.mainUI.destroyPopup()
        mainActivity.applicationInterface.clearLastImages()
        mainActivity.applicationInterface.drawPreview.clearGhostImage()
        mainActivity.preview.onPause()
        mainActivity.applicationInterface.drawPreview.setCoverPreview(true)
    }

    fun onWindowFocusChanged(hasFocus: Boolean) {
        if (MyDebug.LOG) Log.d(TAG, "onWindowFocusChanged: $hasFocus")
        if (!mainActivity.isCameraInBackground && hasFocus) {
            mainActivity.initImmersiveMode()
        }
    }

    fun isDeviceLocked(): Boolean {
        val keyguardManager = mainActivity.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return keyguardManager != null && keyguardManager.inKeyguardRestrictedInputMode()
    }

    companion object {
        private const val TAG = "CameraLifecycleCoordinator"
    }
}

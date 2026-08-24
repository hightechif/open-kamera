/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera

import android.app.Application
import android.os.Process
import android.util.Log
import com.hightechif.openkamera.utils.MyDebug
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class OpenKameraApplication : Application() {
    override fun onCreate() {
        if (MyDebug.LOG) Log.d(TAG, "onCreate")
        super.onCreate()
        checkAppReplacingState()
    }

    private fun checkAppReplacingState() {
        if (MyDebug.LOG) Log.d(TAG, "checkAppReplacingState")
        if (resources == null) {
            Log.e(TAG, "app is replacing, kill")
            Process.killProcess(Process.myPid())
        }
    }

    companion object {
        private const val TAG = "OpenKameraApplication"
    }
}

package com.hightechif.openkamera

import android.app.Application
import android.os.Process
import android.util.Log
import com.hightechif.openkamera.utils.MyDebug

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

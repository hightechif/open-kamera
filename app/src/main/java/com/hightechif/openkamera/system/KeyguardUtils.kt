package com.hightechif.openkamera.system

import android.app.Activity
import android.app.KeyguardManager
import android.app.KeyguardManager.KeyguardDismissCallback
import android.content.Context
import android.os.Build
import android.util.Log
import com.hightechif.openkamera.utils.MyDebug


object KeyguardUtils {
    private const val TAG = "KeyguardUtils"

    fun requireKeyguard(activity: Activity, callback: Runnable) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val keyguardManager =
                activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (keyguardManager == null || !keyguardManager.isKeyguardLocked) {
                callback.run()
                return
            }
            keyguardManager.requestDismissKeyguard(activity, object : KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    if (MyDebug.LOG) Log.d(TAG, "onDismissSucceeded")
                    callback.run()
                    if (MyDebug.LOG) Log.d(TAG, "onDismissSucceeded: after callback run")
                }
            })
        } else {
            callback.run()
        }
    }
}

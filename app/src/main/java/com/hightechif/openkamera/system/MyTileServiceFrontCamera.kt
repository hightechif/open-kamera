/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.system

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.RequiresApi
import com.hightechif.openkamera.MainActivity
import com.hightechif.openkamera.utils.MyDebug


/** Provides service for quick settings tile.
 */
@RequiresApi(api = Build.VERSION_CODES.N)
class MyTileServiceFrontCamera : TileService() {
    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onTileAdded() {
        super.onTileAdded()
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
    }

    override fun onStartListening() {
        super.onStartListening()
    }

    override fun onStopListening() {
        super.onStopListening()
    }

    override fun onClick() {
        if (MyDebug.LOG) Log.d(TAG, "onClick")
        super.onClick()
        val intent = Intent(this, MainActivity::class.java)
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        intent.setAction(TILE_ID)
        // use startActivityAndCollapse() instead of startActivity() so that the notification panel doesn't remain pulled down
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // startActivityAndCollapse(Intent) throws UnsupportedOperationException on Android 14+
            // FLAG_IMMUTABLE needed for PendingIntents on Android 12+
            val pendingIntent =
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pendingIntent)
        } else {
            // still get warning for startActivityAndCollapse being deprecated, but startActivityAndCollapse(PendingIntent) requires Android 14+
            // and only seems possible to disable the warning for the function, not this statement
            startActivity(intent)
        }
    }

    companion object {
        private const val TAG = "MyTileServiceFrontCam"
        const val TILE_ID: String = "com.hightechif.openkamera.TILE_FRONT_CAMERA"
    }
}

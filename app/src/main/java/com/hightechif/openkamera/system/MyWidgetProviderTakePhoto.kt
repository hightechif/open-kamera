/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.system

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.RemoteViews
import com.hightechif.openkamera.R
import com.hightechif.openkamera.TakePhoto
import com.hightechif.openkamera.utils.MyDebug

/** Handles the Open Kamera "take photo" widget. This widget launches Open
 * Camera, and immediately takes a photo.
 */
class MyWidgetProviderTakePhoto : AppWidgetProvider() {
    // see http://developer.android.com/guide/topics/appwidgets/index.html
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        if (MyDebug.LOG) Log.d(TAG, "onUpdate")
        if (MyDebug.LOG) Log.d(TAG, "length = " + appWidgetIds.size)

        for (appWidgetId in appWidgetIds) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "appWidgetId: $appWidgetId"
            )

            val intent = Intent(context, TakePhoto::class.java)

            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags =
                flags or PendingIntent.FLAG_IMMUTABLE // needed for targetting Android 12+, but fine to set it all versions from Android 6 onwards

            val pendingIntent = PendingIntent.getActivity(context, 0, intent, flags)

            val remoteViews = RemoteViews(context.packageName, R.layout.widget_layout_take_photo)
            remoteViews.setOnClickPendingIntent(R.id.widget_take_photo, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, remoteViews)
        }
    } /*@Override
    public void onReceive(Context context, Intent intent) {
        if( MyDebug.LOG ) {
            Log.d(TAG, "onReceive " + intent);
        }
        if (intent.getAction().equals("com.hightechif.openkamera.LAUNCH_OPEN_CAMERA")) {
            if( MyDebug.LOG )
                Log.d(TAG, "Launching MainActivity");
            final Intent activity = new Intent(context, MainActivity.class);
            activity.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(activity);
            if( MyDebug.LOG )
                Log.d(TAG, "done");
        }
        super.onReceive(context, intent);
    }*/

    companion object {
        private const val TAG = "MyWidgetProviderTakePho"
    }
}
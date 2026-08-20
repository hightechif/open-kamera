package com.hightechif.openkamera.preferences

import android.os.Bundle
import android.util.Log
import com.hightechif.openkamera.R
import com.hightechif.openkamera.utils.MyDebug

class PreferenceSubLocation : PreferenceSubScreen() {

    override fun onCreate(savedInstanceState: Bundle?) {
        if (MyDebug.LOG) Log.d(TAG, "onCreate")
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.preferences_sub_location)

        if (MyDebug.LOG) Log.d(TAG, "onCreate done")
    }

    companion object {
        private const val TAG = "PreferenceSubLocation"
    }
}

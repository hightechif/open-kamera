package com.hightechif.openkamera

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.hightechif.openkamera.utils.MyDebug

/** Entry Activity for the "take photo" widget (see MyWidgetProviderTakePhoto).
 * This redirects to MainActivity, but uses an intent extra/bundle to pass the
 * "take photo" request.
 */
class TakePhoto : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        if (MyDebug.LOG) Log.d(TAG, "onCreate")
        super.onCreate(savedInstanceState)

        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        //intent.putExtra(TAKE_PHOTO, true);
        TAKE_PHOTO = true
        this.startActivity(intent)
        if (MyDebug.LOG) Log.d(TAG, "finish")
        this.finish()
    }

    override fun onResume() {
        if (MyDebug.LOG) Log.d(TAG, "onResume")
        super.onResume()
    }

    companion object {
        private const val TAG = "TakePhoto"

        // Usually passing data via intent is preferred to using statics - however here a static is better for security,
        // as we don't want other applications calling Open Kamera's MainActivity with a take photo intent!
        //public static final String TAKE_PHOTO = "com.hightechif.openkamera.TAKE_PHOTO";
        var TAKE_PHOTO: Boolean = false
    }
}

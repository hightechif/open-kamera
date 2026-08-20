package com.hightechif.openkamera.utils

import android.util.Log



/** Global constant to control logging, should always be set to false in
 * released versions.
 */
object MyDebug {
    const val LOG: Boolean = false

    /** Wrapper to print exceptions, should use instead of e.printStackTrace().
     */
    fun logStackTrace(tag: String?, msg: String?, tr: Throwable?) {
        if (LOG) {
            // don't log exceptions in releases
            Log.e(tag, msg, tr)
        }
    }
}

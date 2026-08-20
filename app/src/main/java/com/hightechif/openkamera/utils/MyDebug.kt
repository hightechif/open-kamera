/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
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

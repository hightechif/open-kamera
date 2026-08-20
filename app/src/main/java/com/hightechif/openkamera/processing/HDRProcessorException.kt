/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.processing

/** Exception for HDRProcessor class.
 */
class HDRProcessorException internal constructor(val code: Int) : Exception() {
    companion object {
        const val INVALID_N_IMAGES: Int = 0 // the supplied number of images is not supported
        const val UNEQUAL_SIZES: Int = 1 // images not of the same resolution
    }
}

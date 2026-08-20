/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview

/**
 * Interface Segregation Principle (ISP) compliant interface for Camera Preference settings.
 */
interface CameraSettingsInterface {
    fun getZoomPref(): Int
    fun getFlashPref(): String
    fun getFocusPref(): String
    fun getSceneModePref(): String
    fun getWhiteBalPref(): String
    fun getISOPref(): String
}

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

/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preferences

import com.hightechif.openkamera.domain.repository.preferences.CameraPreferencesRepository
import com.hightechif.openkamera.domain.repository.preferences.LocationPreferencesRepository
import com.hightechif.openkamera.domain.repository.preferences.PhotoPreferencesRepository
import com.hightechif.openkamera.domain.repository.preferences.UiHudPreferencesRepository
import com.hightechif.openkamera.domain.repository.preferences.VideoPreferencesRepository
import com.hightechif.openkamera.preview.ApplicationInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PreferencesRepositoriesUnitTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var photoRepo: PhotoPreferencesRepository
    private lateinit var videoRepo: VideoPreferencesRepository
    private lateinit var uiHudRepo: UiHudPreferencesRepository
    private lateinit var cameraRepo: CameraPreferencesRepository
    private lateinit var locationRepo: LocationPreferencesRepository

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        photoRepo = PhotoPreferencesRepository(fakePrefs)
        videoRepo = VideoPreferencesRepository(fakePrefs)
        uiHudRepo = UiHudPreferencesRepository(fakePrefs)
        cameraRepo = CameraPreferencesRepository(fakePrefs)
        locationRepo = LocationPreferencesRepository(fakePrefs)
    }

    @Test
    fun photoPreferences_imageQualityAndResolution() {
        assertEquals(PhotoPreferencesRepository.DEFAULT_IMAGE_QUALITY, photoRepo.getImageQualityPref())
        photoRepo.setImageQualityPref(95)
        assertEquals(95, photoRepo.getImageQualityPref())

        assertEquals("", photoRepo.getResolutionPref(0))
        photoRepo.setResolutionPref(0, null, "4000x3000")
        assertEquals("4000x3000", photoRepo.getResolutionPref(0))
    }

    @Test
    fun photoPreferences_rawAndBurstAndBracketing() {
        assertEquals(ApplicationInterface.RawPref.RAWPREF_JPEG_ONLY, photoRepo.getRawPref())
        photoRepo.setRawPref(ApplicationInterface.RawPref.RAWPREF_JPEG_DNG)
        assertEquals(ApplicationInterface.RawPref.RAWPREF_JPEG_DNG, photoRepo.getRawPref())

        assertEquals(PhotoPreferencesRepository.DEFAULT_EXPO_BRACKETING_N_IMAGES, photoRepo.getExpoBracketingNImagesPref())
        assertEquals(PhotoPreferencesRepository.DEFAULT_EXPO_BRACKETING_STOPS, photoRepo.getExpoBracketingStopsPref(), 0.001)
        assertEquals(PhotoPreferencesRepository.DEFAULT_FOCUS_BRACKETING_N_IMAGES, photoRepo.getFocusBracketingNImagesPref())
        assertFalse(photoRepo.getFocusBracketingAddInfinityPref())
        assertEquals(PhotoPreferencesRepository.DEFAULT_FAST_BURST_N_IMAGES, photoRepo.getFastBurstNImagesPref())

        assertEquals(ApplicationInterface.NRModePref.NRMODE_NORMAL, photoRepo.getNRModePref("default"))
        assertEquals(ApplicationInterface.NRModePref.NRMODE_LOW_LIGHT, photoRepo.getNRModePref("preference_nr_mode_low_light"))
    }

    @Test
    fun photoPreferences_stampingAndRepeat() {
        assertEquals("preference_stamp_no", photoRepo.getStampPref())
        assertEquals("preference_stamp_dateformat_default", photoRepo.getStampDateFormatPref())
        assertEquals("preference_stamp_timeformat_default", photoRepo.getStampTimeFormatPref())
        assertEquals("preference_stamp_gpsformat_default", photoRepo.getStampGPSFormatPref())
        assertEquals("", photoRepo.getStampCustomText())
        assertEquals(12, photoRepo.getStampFontSizePref())
        assertEquals("#ffffff", photoRepo.getStampFontColor())
        assertEquals("preference_stamp_style_shadowed", photoRepo.getStampStyle())
        assertEquals("1", photoRepo.getRepeatPref())
        assertEquals(0L, photoRepo.getRepeatIntervalPref())
    }

    @Test
    fun videoPreferences_qualityAndBitrateAndFps() {
        assertEquals("", videoRepo.getVideoQualityPref(0, null, false))
        videoRepo.setVideoQualityPref(0, null, false, "3840x2160")
        assertEquals("3840x2160", videoRepo.getVideoQualityPref(0, null, false))

        assertFalse(videoRepo.getVideoStabilizationPref())
        assertFalse(videoRepo.getForce4KPref(0))
        assertEquals(VideoPreferencesRepository.DEFAULT_VIDEO_OUTPUT_FORMAT, videoRepo.getRecordVideoOutputFormatPref())
        assertEquals(VideoPreferencesRepository.DEFAULT_VIDEO_BITRATE, videoRepo.getVideoBitratePref())
        assertEquals(VideoPreferencesRepository.DEFAULT_VIDEO_FPS, videoRepo.getVideoFPSPref(0))
        assertEquals(0L, videoRepo.getVideoMaxDurationPref())
        assertEquals(0, videoRepo.getVideoRestartTimesPref())
        assertNotNull(videoRepo.getVideoMaxFileSizePref())
    }

    @Test
    fun uiHudPreferences_gridAndAngleAndGestures() {
        assertEquals(UiHudPreferencesRepository.DEFAULT_PREVIEW_SIZE, uiHudRepo.getPreviewSizePref())
        assertEquals(UiHudPreferencesRepository.DEFAULT_LOCK_ORIENTATION, uiHudRepo.getLockOrientationPref())
        assertEquals(UiHudPreferencesRepository.DEFAULT_GRID, uiHudRepo.getShowGridPref())
        assertEquals(UiHudPreferencesRepository.DEFAULT_GHOST_IMAGE, uiHudRepo.getGhostImagePref())

        assertFalse(uiHudRepo.getTouchCapturePref())
        assertFalse(uiHudRepo.getDoubleTapCapturePref())
        assertFalse(uiHudRepo.getPausePreviewPref())
        assertTrue(uiHudRepo.getShowToastsPref())
        assertTrue(uiHudRepo.getShutterSoundPref())
        assertTrue(uiHudRepo.getStartupFocusPref())
        assertEquals(0L, uiHudRepo.getTimerPref())
        assertFalse(uiHudRepo.getShowAnglePref())
        assertFalse(uiHudRepo.getShowAngleLinePref())
        assertFalse(uiHudRepo.getShowPitchLinesPref())
    }

    @Test
    fun cameraPreferences_hardwareAndExposureAndFocus() {
        assertFalse(cameraRepo.useCamera2(supportsCamera2 = false))
        assertTrue(cameraRepo.useCamera2(supportsCamera2 = true))
        
        assertEquals(CameraPreferencesRepository.DEFAULT_FLASH_VALUE, cameraRepo.getFlashPref(0))
        cameraRepo.setFlashPref(0, "flash_torch")
        assertEquals("flash_torch", cameraRepo.getFlashPref(0))

        assertEquals(CameraPreferencesRepository.DEFAULT_FOCUS_VALUE, cameraRepo.getFocusPref(0, false))
        cameraRepo.setFocusPref(0, false, "focus_mode_continuous_picture")
        assertEquals("focus_mode_continuous_picture", cameraRepo.getFocusPref(0, false))

        assertEquals(CameraPreferencesRepository.DEFAULT_SCENE_MODE, cameraRepo.getSceneModePref())
        cameraRepo.setSceneModePref("night")
        assertEquals("night", cameraRepo.getSceneModePref())

        assertEquals(CameraPreferencesRepository.DEFAULT_COLOR_EFFECT, cameraRepo.getColorEffectPref())
        cameraRepo.setColorEffectPref("mono")
        assertEquals("mono", cameraRepo.getColorEffectPref())

        assertEquals(CameraPreferencesRepository.DEFAULT_WHITE_BALANCE, cameraRepo.getWhiteBalancePref())
        cameraRepo.setWhiteBalancePref("daylight")
        assertEquals("daylight", cameraRepo.getWhiteBalancePref())

        assertEquals(5000, cameraRepo.getWhiteBalanceTemperaturePref())
        cameraRepo.setWhiteBalanceTemperaturePref(6500)
        assertEquals(6500, cameraRepo.getWhiteBalanceTemperaturePref())

        assertEquals(CameraPreferencesRepository.DEFAULT_ANTI_BANDING, cameraRepo.getAntiBandingPref())
        cameraRepo.setAntiBandingPref("50hz")
        assertEquals("50hz", cameraRepo.getAntiBandingPref())

        assertEquals(CameraPreferencesRepository.DEFAULT_EDGE_MODE, cameraRepo.getEdgeModePref())
        cameraRepo.setEdgeModePref("high_quality")
        assertEquals("high_quality", cameraRepo.getEdgeModePref())
    }

    @Test
    fun locationPreferences_checksAndUpdates() {
        assertFalse(locationRepo.getGeotaggingPref())
        locationRepo.setGeotaggingPref(true)
        assertTrue(locationRepo.getGeotaggingPref())

        assertFalse(locationRepo.getGeodirectionPref())
        locationRepo.setGeodirectionPref(true)
        assertTrue(locationRepo.getGeodirectionPref())

        assertFalse(locationRepo.getRequireLocationPref())
        locationRepo.setRequireLocationPref(true)
        assertTrue(locationRepo.getRequireLocationPref())
    }
}

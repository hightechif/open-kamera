package com.hightechif.openkamera.preferences

/** Stores all of the string keys used for SharedPreferences.
 */
object PreferenceKeys {
    // must be static, to safely call from other Activities
    /** If this preference is set, no longer show the intro dialog.
     */
    const val FIRST_TIME_PREFERENCE_KEY: String = "done_first_time"

    /** This preference stores the version number seen by the user - used to show "What's New" dialog.
     */
    const val LATEST_VERSION_PREFERENCE_KEY: String = "latest_version"

    /** This preference stores whether to allow showing the "What's New" dialog.
     */
    const val SHOW_WHATS_NEW_PREFERENCE_KEY: String = "preference_show_whats_new"

    /** If this preference is set, no longer show the auto-stabilise info dialog.
     */
    const val AUTO_STABILISE_INFO_PREFERENCE_KEY: String = "done_auto_stabilise_info"

    /** If this preference is set, no longer show the HDR info dialog.
     */
    const val HDR_INFO_PREFERENCE_KEY: String = "done_hdr_info"

    /** If this preference is set, no longer show the Panorama info dialog.
     */
    const val PANORAMA_INFO_PREFERENCE_KEY: String = "done_panorama_info"

    /** If this preference is set, no longer show the raw info dialog.
     */
    const val RAW_INFO_PREFERENCE_KEY: String = "done_raw_info"

    /** If this preference is set, no longer show the dialog for poor magnetic accuracy
     */
    const val MAGNETIC_ACCURACY_PREFERENCE_KEY: String = "done_magnetic_accuracy"

    const val CAMERA_API_PREFERENCE_DEFAULT: String = "preference_camera_api_old"
    const val CAMERA_API_PREFERENCE_KEY: String = "preference_camera_api"

    private fun getCameraIDKey(cameraId: Int, cameraIdSPhysical: String?): String {
        return if (cameraIdSPhysical != null) cameraId.toString() + "_" + cameraIdSPhysical
        else cameraId.toString()
    }

    // don't set to be specific for physical cameras, as too confusing to have lots of different flash preferences
    // also in Preview, we don't save the flash back if not supported
    fun getFlashPreferenceKey(cameraId: Int): String {
        return "flash_value_$cameraId"
    }

    fun getFocusPreferenceKey(cameraId: Int, isVideo: Boolean): String {
        return "focus_value_" + cameraId + "_" + isVideo
    }

    const val FOCUS_ASSIST_PREFERENCE_KEY: String = "preference_focus_assist"

    fun getResolutionPreferenceKey(cameraId: Int, cameraIdSPhysical: String?): String {
        return "camera_resolution_" + getCameraIDKey(cameraId, cameraIdSPhysical)
    }

    fun getVideoQualityPreferenceKey(
        cameraId: Int,
        cameraIdSPhysical: String?,
        highSpeed: Boolean
    ): String {
        return "video_quality_" + getCameraIDKey(
            cameraId,
            cameraIdSPhysical
        ) + (if (highSpeed) "_highspeed" else "")
    }

    const val OPTIMISE_FOCUS_PREFERENCE_KEY: String = "preference_photo_optimise_focus"

    const val IMAGE_FORMAT_PREFERENCE_KEY: String = "preference_image_format"

    const val IS_VIDEO_PREFERENCE_KEY: String = "is_video"

    const val EXPOSURE_PREFERENCE_KEY: String = "preference_exposure"

    const val COLOR_EFFECT_PREFERENCE_KEY: String = "preference_color_effect"

    const val SCENE_MODE_PREFERENCE_KEY: String = "preference_scene_mode"

    const val WHITE_BALANCE_PREFERENCE_KEY: String = "preference_white_balance"

    const val WHITE_BALANCE_TEMPERATURE_PREFERENCE_KEY: String = "preference_white_balance_temperature"

    const val ANTI_BANDING_PREFERENCE_KEY: String = "preference_antibanding"

    const val EDGE_MODE_PREFERENCE_KEY: String = "preference_edge_mode"

    const val CAMERA_NOISE_REDUCTION_MODE_PREFERENCE_KEY: String =
        "preference_noise_reduction_mode" // n.b., this is for the Camera driver noise reduction mode, not Open Kamera's NR photo mode

    const val ISO_PREFERENCE_KEY: String = "preference_iso"

    const val EXPOSURE_TIME_PREFERENCE_KEY: String = "preference_exposure_time"

    const val RAW_PREFERENCE_KEY: String = "preference_raw"

    const val ALLOW_RAW_FOR_EXPO_BRACKETING_PREFERENCE_KEY: String = "preference_raw_expo_bracketing"

    const val ALLOW_RAW_FOR_FOCUS_BRACKETING_PREFERENCE_KEY: String = "preference_raw_focus_bracketing"

    const val PANORAMA_CROP_PREFERENCE_KEY: String = "preference_panorama_crop"

    const val PANORAMA_SAVE_EXPO_PREFERENCE_KEY: String = "preference_panorama_save"

    const val EXPO_BRACKETING_N_IMAGES_PREFERENCE_KEY: String = "preference_expo_bracketing_n_images"

    const val EXPO_BRACKETING_STOPS_PREFERENCE_KEY: String = "preference_expo_bracketing_stops"

    const val FOCUS_DISTANCE_PREFERENCE_KEY: String = "preference_focus_distance"

    const val FOCUS_BRACKETING_TARGET_DISTANCE_PREFERENCE_KEY: String =
        "preference_focus_bracketing_target_distance"

    const val FOCUS_BRACKETING_AUTO_SOURCE_DISTANCE_PREFERENCE_KEY: String =
        "preference_focus_bracketing_auto_source_distance"

    const val FOCUS_BRACKETING_N_IMAGES_PREFERENCE_KEY: String = "preference_focus_bracketing_n_images"

    const val FOCUS_BRACKETING_ADD_INFINITY_PREFERENCE_KEY: String =
        "preference_focus_bracketing_add_infinity"

    const val VOLUME_KEYS_PREFERENCE_KEY: String = "preference_volume_keys"

    const val AUDIO_CONTROL_PREFERENCE_KEY: String = "preference_audio_control"

    const val AUDIO_NOISE_CONTROL_SENSITIVITY_PREFERENCE_KEY: String =
        "preference_audio_noise_control_sensitivity"

    const val QUALITY_PREFERENCE_KEY: String = "preference_quality"

    const val AUTO_STABILISE_PREFERENCE_KEY: String = "preference_auto_stabilise"

    const val PHOTO_MODE_PREFERENCE_KEY: String = "preference_photo_mode"

    const val HDR_SAVE_EXPO_PREFERENCE_KEY: String = "preference_hdr_save_expo"

    const val HDR_TONEMAPPING_PREFERENCE_KEY: String = "preference_hdr_tonemapping"

    const val HDR_CONTRAST_ENHANCEMENT_PREFERENCE_KEY: String = "preference_hdr_contrast_enhancement"

    const val NR_SAVE_EXPO_PREFERENCE_KEY: String = "preference_nr_save"

    const val FAST_BURST_N_IMAGES_PREFERENCE_KEY: String = "preference_fast_burst_n_images"

    const val LOCATION_PREFERENCE_KEY: String = "preference_location"

    const val REMOVE_DEVICE_EXIF_PREFERENCE_KEY: String = "preference_remove_device_exif"

    const val GPS_DIRECTION_PREFERENCE_KEY: String = "preference_gps_direction"

    const val REQUIRE_LOCATION_PREFERENCE_KEY: String = "preference_require_location"

    const val EXIF_ARTIST_PREFERENCE_KEY: String = "preference_exif_artist"

    const val EXIF_COPYRIGHT_PREFERENCE_KEY: String = "preference_exif_copyright"

    const val STAMP_PREFERENCE_KEY: String = "preference_stamp"

    const val STAMP_DATE_FORMAT_PREFERENCE_KEY: String = "preference_stamp_dateformat"

    const val STAMP_TIME_FORMAT_PREFERENCE_KEY: String = "preference_stamp_timeformat"

    const val STAMP_GPS_FORMAT_PREFERENCE_KEY: String = "preference_stamp_gpsformat"

    //public static final String StampGeoAddressPreferenceKey = "preference_stamp_geo_address";
    const val UNITS_DISTANCE_PREFERENCE_KEY: String = "preference_units_distance"

    const val TEXT_STAMP_PREFERENCE_KEY: String = "preference_textstamp"

    const val STAMP_FONT_SIZE_PREFERENCE_KEY: String = "preference_stamp_fontsize"

    const val STAMP_FONT_COLOR_PREFERENCE_KEY: String = "preference_stamp_font_color"

    const val STAMP_STYLE_KEY: String = "preference_stamp_style"

    const val VIDEO_SUBTITLE_PREF: String = "preference_video_subtitle"

    const val FRONT_CAMERA_MIRROR_KEY: String = "preference_front_camera_mirror"

    const val ENABLE_REMOTE: String = "preference_enable_remote"

    const val REMOTE_NAME: String = "preference_remote_device_name"

    const val REMOTE_TYPE: String = "preference_remote_type"

    const val REMOTE_VIDEO_MODE: String = "preference_remote_video_mode"

    const val WATER_TYPE: String = "preference_water_type"

    //public static final String BackgroundPhotoSavingPreferenceKey = "preference_background_photo_saving";
    const val CAMERA2_FAKE_FLASH_PREFERENCE_KEY: String = "preference_camera2_fake_flash"

    const val CAMERA2_DUMMY_CAPTURE_HACK_PREFERENCE_KEY: String = "preference_camera2_dummy_capture_hack"

    const val CAMERA2_FAST_BURST_PREFERENCE_KEY: String = "preference_camera2_fast_burst"

    const val CAMERA2_PHOTO_VIDEO_RECORDING_PREFERENCE_KEY: String =
        "preference_camera2_photo_video_recording"

    const val UI_PLACEMENT_PREFERENCE_KEY: String = "preference_ui_placement"

    const val TOUCH_CAPTURE_PREFERENCE_KEY: String = "preference_touch_capture"

    const val PAUSE_PREVIEW_PREFERENCE_KEY: String = "preference_pause_preview"

    const val SHOW_TOASTS_PREFERENCE_KEY: String = "preference_show_toasts"

    const val THUMBNAIL_ANIMATION_PREFERENCE_KEY: String = "preference_thumbnail_animation"

    const val TAKE_PHOTO_BORDER_PREFERENCE_KEY: String = "preference_take_photo_border"

    const val DIM_WHEN_DISCONNECTED_PREFERENCE_KEY: String = "preference_remote_disconnect_screen_dim"

    const val ALLOW_HAPTIC_FEEDBACK_PREFERENCE_KEY: String = "preference_allow_haptic_feedback"

    const val SHOW_WHEN_LOCKED_PREFERENCE_KEY: String = "preference_show_when_locked"

    const val ALLOW_LONG_PRESS_PREFERENCE_KEY: String = "preference_allow_long_press"

    const val STARTUP_FOCUS_PREFERENCE_KEY: String = "preference_startup_focus"

    const val MULTI_CAM_BUTTON_PREFERENCE_KEY: String = "preference_multi_cam_button"

    const val KEEP_DISPLAY_ON_PREFERENCE_KEY: String = "preference_keep_display_on"

    const val MAX_BRIGHTNESS_PREFERENCE_KEY: String = "preference_max_brightness"

    const val USING_SAF_PREFERENCE_KEY: String = "preference_using_saf"

    const val SAVE_LOCATION_PREFERENCE_KEY: String = "preference_save_location"

    const val SAVE_LOCATION_SAF_PREFERENCE_KEY: String = "preference_save_location_saf"

    const val SAVE_LOCATION_HISTORY_BASE_PREFERENCE_KEY: String = "save_location_history"

    const val SAVE_LOCATION_HISTORY_SAF_BASE_PREFERENCE_KEY: String = "save_location_history_saf"

    const val SAVE_PHOTO_PREFIX_PREFERENCE_KEY: String = "preference_save_photo_prefix"

    const val SAVE_VIDEO_PREFIX_PREFERENCE_KEY: String = "preference_save_video_prefix"

    const val SAVE_ZULU_TIME_PREFERENCE_KEY: String = "preference_save_zulu_time"

    const val SAVE_INCLUDE_MILLISECONDS_PREFERENCE_KEY: String = "preference_save_include_milliseconds"

    const val SHOW_ZOOM_CONTROLS_PREFERENCE_KEY: String = "preference_show_zoom_controls"

    const val SHOW_ZOOM_SLIDER_CONTROLS_PREFERENCE_KEY: String = "preference_show_zoom_slider_controls"

    const val SHOW_TAKE_PHOTO_PREFERENCE_KEY: String = "preference_show_take_photo"

    const val SHOW_FACE_DETECTION_PREFERENCE_KEY: String = "preference_show_face_detection"

    const val SHOW_CYCLE_LOCK_ORIENTATION_PREFERENCE_KEY: String = "preference_show_cycle_lock_orientation"

    const val SHOW_PREVIEW_SHOTS_PREFERENCE_KEY: String = "preference_show_preview_shots"

    const val SHOW_CYCLE_FLASH_PREFERENCE_KEY: String = "preference_show_cycle_flash"

    const val SHOW_FOCUS_PEAKING_PREFERENCE_KEY: String = "preference_show_focus_peaking"

    const val SHOW_AUTO_LEVEL_PREFERENCE_KEY: String = "preference_show_auto_level"

    const val SHOW_STAMP_PREFERENCE_KEY: String = "preference_show_stamp"

    const val SHOW_TEXT_STAMP_PREFERENCE_KEY: String = "preference_show_textstamp"

    const val SHOW_STORE_LOCATION_PREFERENCE_KEY: String = "preference_show_store_location"

    const val SHOW_CYCLE_RAW_PREFERENCE_KEY: String = "preference_show_cycle_raw"

    const val SHOW_WHITE_BALANCE_LOCK_PREFERENCE_KEY: String = "preference_show_white_balance_lock"

    const val SHOW_EXPOSURE_LOCK_PREFERENCE_KEY: String = "preference_show_exposure_lock"

    const val SHOW_ZOOM_PREFERENCE_KEY: String = "preference_show_zoom"

    const val SHOW_ISO_PREFERENCE_KEY: String = "preference_show_iso"

    const val HISTOGRAM_PREFERENCE_KEY: String = "preference_histogram"

    const val ZEBRA_STRIPES_PREFERENCE_KEY: String = "preference_zebra_stripes"

    const val ZEBRA_STRIPES_FOREGROUND_COLOR_PREFERENCE_KEY: String =
        "preference_zebra_stripes_foreground_color"

    const val ZEBRA_STRIPES_BACKGROUND_COLOR_PREFERENCE_KEY: String =
        "preference_zebra_stripes_background_color"

    const val FOCUS_PEAKING_PREFERENCE_KEY: String = "preference_focus_peaking"

    const val FOCUS_PEAKING_COLOR_PREFERENCE_KEY: String = "preference_focus_peaking_color"

    const val PRE_SHOTS_PREFERENCE_KEY: String = "preference_save_preshots"

    const val SHOW_VIDEO_MAX_AMP_PREFERENCE_KEY: String = "preference_show_video_max_amp"

    const val SHOW_ANGLE_PREFERENCE_KEY: String = "preference_show_angle"

    const val SHOW_ANGLE_LINE_PREFERENCE_KEY: String = "preference_show_angle_line"

    const val SHOW_PITCH_LINES_PREFERENCE_KEY: String = "preference_show_pitch_lines"

    const val SHOW_GEO_DIRECTION_LINES_PREFERENCE_KEY: String = "preference_show_geo_direction_lines"

    const val SHOW_ANGLE_HIGHLIGHT_COLOR_PREFERENCE_KEY: String = "preference_angle_highlight_color"

    const val CALIBRATED_LEVEL_ANGLE_PREFERENCE_KEY: String = "preference_calibrate_level_angle"

    const val SHOW_GEO_DIRECTION_PREFERENCE_KEY: String = "preference_show_geo_direction"

    const val SHOW_FREE_MEMORY_PREFERENCE_KEY: String = "preference_free_memory"

    const val SHOW_TIME_PREFERENCE_KEY: String = "preference_show_time"

    const val SHOW_CAMERA_ID_PREFERENCE_KEY: String = "preference_show_camera_id"

    const val SHOW_BATTERY_PREFERENCE_KEY: String = "preference_show_battery"

    const val SHOW_GRID_PREFERENCE_KEY: String = "preference_grid"

    const val SHOW_CROP_GUIDE_PREFERENCE_KEY: String = "preference_crop_guide"

    const val FACE_DETECTION_PREFERENCE_KEY: String = "preference_face_detection"

    const val GHOST_IMAGE_PREFERENCE_KEY: String = "preference_ghost_image"

    const val GHOST_SELECTED_IMAGE_SAF_PREFERENCE_KEY: String = "preference_ghost_selected_image_saf"

    const val GHOST_IMAGE_ALPHA_PREFERENCE_KEY: String = "ghost_image_alpha"

    const val VIDEO_STABILIZATION_PREFERENCE_KEY: String = "preference_video_stabilization"

    const val FORCE_VIDEO_4_K_PREFERENCE_KEY: String = "preference_force_video_4k"

    const val VIDEO_FORMAT_PREFERENCE_KEY: String = "preference_video_output_format"

    const val VIDEO_BITRATE_PREFERENCE_KEY: String = "preference_video_bitrate"

    fun getVideoFPSPreferenceKey(cameraId: Int, cameraIdSPhysical: String?): String {
        // for cameraId==0 and cameraIdSPhysical==null, we return preferenceVideoFps instead of preferenceVideoFps0, for
        // backwards compatibility for people upgrading
        return "preference_video_fps" + (if (cameraId == 0 && cameraIdSPhysical == null) "" else ("_" + getCameraIDKey(
            cameraId,
            cameraIdSPhysical
        )))
    }

    fun getVideoCaptureRatePreferenceKey(cameraId: Int, cameraIdSPhysical: String?): String {
        return "preference_capture_rate_" + getCameraIDKey(cameraId, cameraIdSPhysical)
    }

    const val VIDEO_LOG_PREFERENCE_KEY: String = "preference_video_log"

    const val VIDEO_PROFILE_GAMMA_PREFERENCE_KEY: String = "preference_video_profile_gamma"

    const val VIDEO_MAX_DURATION_PREFERENCE_KEY: String = "preference_video_max_duration"

    const val VIDEO_RESTART_PREFERENCE_KEY: String = "preference_video_restart"

    const val VIDEO_MAX_FILE_SIZE_PREFERENCE_KEY: String = "preference_video_max_filesize"

    const val VIDEO_RESTART_MAX_FILE_SIZE_PREFERENCE_KEY: String = "preference_video_restart_max_filesize"

    const val VIDEO_FLASH_PREFERENCE_KEY: String = "preference_video_flash"

    const val VIDEO_LOW_POWER_CHECK_PREFERENCE_KEY: String = "preference_video_low_power_check"

    const val LOCK_VIDEO_PREFERENCE_KEY: String = "preference_lock_video"

    const val RECORD_AUDIO_PREFERENCE_KEY: String = "preference_record_audio"

    const val RECORD_AUDIO_CHANNELS_PREFERENCE_KEY: String = "preference_record_audio_channels"

    const val RECORD_AUDIO_SOURCE_PREFERENCE_KEY: String = "preference_record_audio_src"

    const val PREVIEW_SIZE_PREFERENCE_KEY: String = "preference_preview_size"

    const val ROTATE_PREVIEW_PREFERENCE_KEY: String = "preference_rotate_preview"

    const val LOCK_ORIENTATION_PREFERENCE_KEY: String = "preference_lock_orientation"

    const val TIMER_PREFERENCE_KEY: String = "preference_timer"

    const val TIMER_BEEP_PREFERENCE_KEY: String = "preference_timer_beep"

    const val TIMER_SPEAK_PREFERENCE_KEY: String = "preference_timer_speak"

    // note for historical reasons the preference refers to burst; the feature was renamed to
    // "repeat" in v1.43, but we still need to use the old string to avoid changing user settings
    // when people upgrade
    const val REPEAT_MODE_PREFERENCE_KEY: String = "preference_burst_mode"

    // see note about "repeat" vs "burst" under REPEAT_MODE_PREFERENCE_KEY
    const val REPEAT_INTERVAL_PREFERENCE_KEY: String = "preference_burst_interval"

    const val SHUTTER_SOUND_PREFERENCE_KEY: String = "preference_shutter_sound"

    const val IMMERSIVE_MODE_PREFERENCE_KEY: String = "preference_immersive_mode"
    const val ADD_YPR_TO_COMMENTS: String = "preference_comment_ypr"

    const val GALLERY_PREFERENCE_KEY: String = "preference_gallery"
}

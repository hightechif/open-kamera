/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.utils

import android.content.Context
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.hightechif.openkamera.storage.ImageSaver
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Methods related to handling exif tags. */
object ExifHandler {
    private const val TAG = "ExifHandler"

    /** Transfers device exif info. Should only be called if request.remove_device_exif == Request.RemoveDeviceExif.OFF. */
    private fun transferDeviceExif(exif: ExifInterface, exif_new: ExifInterface) {
        if (MyDebug.LOG) Log.d(TAG, "transferDeviceExif")
        if (MyDebug.LOG) Log.d(TAG, "read back EXIF data")

        val exif_aperture = exif.getAttribute(ExifInterface.TAG_F_NUMBER) // previously TAG_APERTURE
        val exif_exposure_time = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)
        val exif_flash = exif.getAttribute(ExifInterface.TAG_FLASH)
        val exif_focal_length = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)
        // leave TAG_IMAGE_WIDTH/TAG_IMAGE_LENGTH, as this may have changed!
        @Suppress("DEPRECATION")
        val exif_iso = exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS) // previously TAG_ISO
        val exif_make = exif.getAttribute(ExifInterface.TAG_MAKE)
        val exif_model = exif.getAttribute(ExifInterface.TAG_MODEL)
        // leave orientation - since we rotate bitmaps to account for orientation, we don't want to write it to the saved image!
        val exif_white_balance = exif.getAttribute(ExifInterface.TAG_WHITE_BALANCE)

        val exif_aperture_value = exif.getAttribute(ExifInterface.TAG_APERTURE_VALUE)
        val exif_brightness_value = exif.getAttribute(ExifInterface.TAG_BRIGHTNESS_VALUE)
        val exif_cfa_pattern = exif.getAttribute(ExifInterface.TAG_CFA_PATTERN)
        val exif_color_space = exif.getAttribute(ExifInterface.TAG_COLOR_SPACE)
        val exif_components_configuration = exif.getAttribute(ExifInterface.TAG_COMPONENTS_CONFIGURATION)
        val exif_compressed_bits_per_pixel = exif.getAttribute(ExifInterface.TAG_COMPRESSED_BITS_PER_PIXEL)
        val exif_compression = exif.getAttribute(ExifInterface.TAG_COMPRESSION)
        val exif_contrast = exif.getAttribute(ExifInterface.TAG_CONTRAST)
        val exif_device_setting_description = exif.getAttribute(ExifInterface.TAG_DEVICE_SETTING_DESCRIPTION)
        val exif_digital_zoom_ratio = exif.getAttribute(ExifInterface.TAG_DIGITAL_ZOOM_RATIO)
        val exif_exposure_bias_value = exif.getAttribute(ExifInterface.TAG_EXPOSURE_BIAS_VALUE)
        val exif_exposure_index = exif.getAttribute(ExifInterface.TAG_EXPOSURE_INDEX)
        val exif_exposure_mode = exif.getAttribute(ExifInterface.TAG_EXPOSURE_MODE)
        val exif_exposure_program = exif.getAttribute(ExifInterface.TAG_EXPOSURE_PROGRAM)
        val exif_flash_energy = exif.getAttribute(ExifInterface.TAG_FLASH_ENERGY)
        val exif_focal_length_in_35mm_film = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM)
        val exif_focal_plane_resolution_unit = exif.getAttribute(ExifInterface.TAG_FOCAL_PLANE_RESOLUTION_UNIT)
        val exif_focal_plane_x_resolution = exif.getAttribute(ExifInterface.TAG_FOCAL_PLANE_X_RESOLUTION)
        val exif_focal_plane_y_resolution = exif.getAttribute(ExifInterface.TAG_FOCAL_PLANE_Y_RESOLUTION)
        val exif_gain_control = exif.getAttribute(ExifInterface.TAG_GAIN_CONTROL)
        val exif_gps_area_information = exif.getAttribute(ExifInterface.TAG_GPS_AREA_INFORMATION)
        val exif_gps_differential = exif.getAttribute(ExifInterface.TAG_GPS_DIFFERENTIAL)
        val exif_gps_dop = exif.getAttribute(ExifInterface.TAG_GPS_DOP)
        val exif_gps_measure_mode = exif.getAttribute(ExifInterface.TAG_GPS_MEASURE_MODE)
        val exif_image_description = exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)
        val exif_light_source = exif.getAttribute(ExifInterface.TAG_LIGHT_SOURCE)
        val exif_maker_note = exif.getAttribute(ExifInterface.TAG_MAKER_NOTE)
        val exif_max_aperture_value = exif.getAttribute(ExifInterface.TAG_MAX_APERTURE_VALUE)
        val exif_metering_mode = exif.getAttribute(ExifInterface.TAG_METERING_MODE)
        val exif_oecf = exif.getAttribute(ExifInterface.TAG_OECF)
        val exif_photometric_interpretation = exif.getAttribute(ExifInterface.TAG_PHOTOMETRIC_INTERPRETATION)
        val exif_saturation = exif.getAttribute(ExifInterface.TAG_SATURATION)
        val exif_scene_capture_type = exif.getAttribute(ExifInterface.TAG_SCENE_CAPTURE_TYPE)
        val exif_scene_type = exif.getAttribute(ExifInterface.TAG_SCENE_TYPE)
        val exif_sensing_method = exif.getAttribute(ExifInterface.TAG_SENSING_METHOD)
        val exif_sharpness = exif.getAttribute(ExifInterface.TAG_SHARPNESS)
        val exif_shutter_speed_value = exif.getAttribute(ExifInterface.TAG_SHUTTER_SPEED_VALUE)
        val exif_software = exif.getAttribute(ExifInterface.TAG_SOFTWARE)
        val exif_user_comment = exif.getAttribute(ExifInterface.TAG_USER_COMMENT)

        val exif_photographic_sensitivity = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
        val exif_sensitivity_type = exif.getAttribute(ExifInterface.TAG_SENSITIVITY_TYPE)
        val exif_standard_output_sensitivity = exif.getAttribute(ExifInterface.TAG_STANDARD_OUTPUT_SENSITIVITY)
        val exif_recommended_exposure_index = exif.getAttribute(ExifInterface.TAG_RECOMMENDED_EXPOSURE_INDEX)
        val exif_iso_speed = exif.getAttribute(ExifInterface.TAG_ISO_SPEED)
        val exif_custom_rendered = exif.getAttribute(ExifInterface.TAG_CUSTOM_RENDERED)
        val exif_lens_specification = exif.getAttribute(ExifInterface.TAG_LENS_SPECIFICATION)
        val exif_lens_name = exif.getAttribute(ExifInterface.TAG_LENS_MAKE)
        val exif_lens_model = exif.getAttribute(ExifInterface.TAG_LENS_MODEL)

        if (MyDebug.LOG) Log.d(TAG, "now write new EXIF data")
        
        if (exif_aperture != null) exif_new.setAttribute(ExifInterface.TAG_F_NUMBER, exif_aperture)
        if (exif_exposure_time != null) exif_new.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, exif_exposure_time)
        if (exif_flash != null) exif_new.setAttribute(ExifInterface.TAG_FLASH, exif_flash)
        if (exif_focal_length != null) exif_new.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, exif_focal_length)
        if (exif_iso != null) {
            @Suppress("DEPRECATION")
            exif_new.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, exif_iso)
        }
        if (exif_make != null) exif_new.setAttribute(ExifInterface.TAG_MAKE, exif_make)
        if (exif_model != null) exif_new.setAttribute(ExifInterface.TAG_MODEL, exif_model)
        if (exif_white_balance != null) exif_new.setAttribute(ExifInterface.TAG_WHITE_BALANCE, exif_white_balance)

        if (exif_aperture_value != null) exif_new.setAttribute(ExifInterface.TAG_APERTURE_VALUE, exif_aperture_value)
        if (exif_brightness_value != null) exif_new.setAttribute(ExifInterface.TAG_BRIGHTNESS_VALUE, exif_brightness_value)
        if (exif_cfa_pattern != null) exif_new.setAttribute(ExifInterface.TAG_CFA_PATTERN, exif_cfa_pattern)
        if (exif_color_space != null) exif_new.setAttribute(ExifInterface.TAG_COLOR_SPACE, exif_color_space)
        if (exif_components_configuration != null) exif_new.setAttribute(ExifInterface.TAG_COMPONENTS_CONFIGURATION, exif_components_configuration)
        if (exif_compressed_bits_per_pixel != null) exif_new.setAttribute(ExifInterface.TAG_COMPRESSED_BITS_PER_PIXEL, exif_compressed_bits_per_pixel)
        if (exif_compression != null) exif_new.setAttribute(ExifInterface.TAG_COMPRESSION, exif_compression)
        if (exif_contrast != null) exif_new.setAttribute(ExifInterface.TAG_CONTRAST, exif_contrast)
        if (exif_device_setting_description != null) exif_new.setAttribute(ExifInterface.TAG_DEVICE_SETTING_DESCRIPTION, exif_device_setting_description)
        if (exif_digital_zoom_ratio != null) exif_new.setAttribute(ExifInterface.TAG_DIGITAL_ZOOM_RATIO, exif_digital_zoom_ratio)
        if (exif_exposure_bias_value != null) exif_new.setAttribute(ExifInterface.TAG_EXPOSURE_BIAS_VALUE, exif_exposure_bias_value)
        if (exif_exposure_index != null) exif_new.setAttribute(ExifInterface.TAG_EXPOSURE_INDEX, exif_exposure_index)
        if (exif_exposure_mode != null) exif_new.setAttribute(ExifInterface.TAG_EXPOSURE_MODE, exif_exposure_mode)
        if (exif_exposure_program != null) exif_new.setAttribute(ExifInterface.TAG_EXPOSURE_PROGRAM, exif_exposure_program)
        if (exif_flash_energy != null) exif_new.setAttribute(ExifInterface.TAG_FLASH_ENERGY, exif_flash_energy)
        if (exif_focal_length_in_35mm_film != null) exif_new.setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, exif_focal_length_in_35mm_film)
        if (exif_focal_plane_resolution_unit != null) exif_new.setAttribute(ExifInterface.TAG_FOCAL_PLANE_RESOLUTION_UNIT, exif_focal_plane_resolution_unit)
        if (exif_focal_plane_x_resolution != null) exif_new.setAttribute(ExifInterface.TAG_FOCAL_PLANE_X_RESOLUTION, exif_focal_plane_x_resolution)
        if (exif_focal_plane_y_resolution != null) exif_new.setAttribute(ExifInterface.TAG_FOCAL_PLANE_Y_RESOLUTION, exif_focal_plane_y_resolution)
        if (exif_gain_control != null) exif_new.setAttribute(ExifInterface.TAG_GAIN_CONTROL, exif_gain_control)
        if (exif_gps_area_information != null) exif_new.setAttribute(ExifInterface.TAG_GPS_AREA_INFORMATION, exif_gps_area_information)
        if (exif_gps_differential != null) exif_new.setAttribute(ExifInterface.TAG_GPS_DIFFERENTIAL, exif_gps_differential)
        if (exif_gps_dop != null) exif_new.setAttribute(ExifInterface.TAG_GPS_DOP, exif_gps_dop)
        if (exif_gps_measure_mode != null) exif_new.setAttribute(ExifInterface.TAG_GPS_MEASURE_MODE, exif_gps_measure_mode)
        if (exif_image_description != null) exif_new.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, exif_image_description)
        if (exif_light_source != null) exif_new.setAttribute(ExifInterface.TAG_LIGHT_SOURCE, exif_light_source)
        if (exif_maker_note != null) exif_new.setAttribute(ExifInterface.TAG_MAKER_NOTE, exif_maker_note)
        if (exif_max_aperture_value != null) exif_new.setAttribute(ExifInterface.TAG_MAX_APERTURE_VALUE, exif_max_aperture_value)
        if (exif_metering_mode != null) exif_new.setAttribute(ExifInterface.TAG_METERING_MODE, exif_metering_mode)
        if (exif_oecf != null) exif_new.setAttribute(ExifInterface.TAG_OECF, exif_oecf)
        if (exif_photometric_interpretation != null) exif_new.setAttribute(ExifInterface.TAG_PHOTOMETRIC_INTERPRETATION, exif_photometric_interpretation)
        if (exif_saturation != null) exif_new.setAttribute(ExifInterface.TAG_SATURATION, exif_saturation)
        if (exif_scene_capture_type != null) exif_new.setAttribute(ExifInterface.TAG_SCENE_CAPTURE_TYPE, exif_scene_capture_type)
        if (exif_scene_type != null) exif_new.setAttribute(ExifInterface.TAG_SCENE_TYPE, exif_scene_type)
        if (exif_sensing_method != null) exif_new.setAttribute(ExifInterface.TAG_SENSING_METHOD, exif_sensing_method)
        if (exif_sharpness != null) exif_new.setAttribute(ExifInterface.TAG_SHARPNESS, exif_sharpness)
        if (exif_shutter_speed_value != null) exif_new.setAttribute(ExifInterface.TAG_SHUTTER_SPEED_VALUE, exif_shutter_speed_value)
        if (exif_software != null) exif_new.setAttribute(ExifInterface.TAG_SOFTWARE, exif_software)
        if (exif_user_comment != null) exif_new.setAttribute(ExifInterface.TAG_USER_COMMENT, exif_user_comment)

        if (exif_photographic_sensitivity != null) exif_new.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, exif_photographic_sensitivity)
        if (exif_sensitivity_type != null) exif_new.setAttribute(ExifInterface.TAG_SENSITIVITY_TYPE, exif_sensitivity_type)
        if (exif_standard_output_sensitivity != null) exif_new.setAttribute(ExifInterface.TAG_STANDARD_OUTPUT_SENSITIVITY, exif_standard_output_sensitivity)
        if (exif_recommended_exposure_index != null) exif_new.setAttribute(ExifInterface.TAG_RECOMMENDED_EXPOSURE_INDEX, exif_recommended_exposure_index)
        if (exif_iso_speed != null) exif_new.setAttribute(ExifInterface.TAG_ISO_SPEED, exif_iso_speed)
        if (exif_custom_rendered != null) exif_new.setAttribute(ExifInterface.TAG_CUSTOM_RENDERED, exif_custom_rendered)
        if (exif_lens_specification != null) exif_new.setAttribute(ExifInterface.TAG_LENS_SPECIFICATION, exif_lens_specification)
        if (exif_lens_name != null) exif_new.setAttribute(ExifInterface.TAG_LENS_MAKE, exif_lens_name)
        if (exif_lens_model != null) exif_new.setAttribute(ExifInterface.TAG_LENS_MODEL, exif_lens_model)
    }

    /** Transfers device exif info related to date and time. */
    private fun transferDeviceExifDateTime(exif: ExifInterface, exif_new: ExifInterface) {
        if (MyDebug.LOG) Log.d(TAG, "transferDeviceExifDateTime")

        val exif_datetime = exif.getAttribute(ExifInterface.TAG_DATETIME)
        val exif_datetime_original = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
        val exif_datetime_digitized = exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
        val exif_subsec_time = exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME)
        val exif_subsec_time_orig = exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL)
        val exif_subsec_time_dig = exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_DIGITIZED)
        val exif_offset_time = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME)
        val exif_offset_time_orig = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
        val exif_offset_time_dig = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED)

        if (exif_datetime != null) exif_new.setAttribute(ExifInterface.TAG_DATETIME, exif_datetime)
        if (exif_datetime_original != null) exif_new.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exif_datetime_original)
        if (exif_datetime_digitized != null) exif_new.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, exif_datetime_digitized)
        if (exif_subsec_time != null) exif_new.setAttribute(ExifInterface.TAG_SUBSEC_TIME, exif_subsec_time)
        if (exif_subsec_time_orig != null) exif_new.setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, exif_subsec_time_orig)
        if (exif_subsec_time_dig != null) exif_new.setAttribute(ExifInterface.TAG_SUBSEC_TIME_DIGITIZED, exif_subsec_time_dig)
        if (exif_offset_time != null) exif_new.setAttribute(ExifInterface.TAG_OFFSET_TIME, exif_offset_time)
        if (exif_offset_time_orig != null) exif_new.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, exif_offset_time_orig)
        if (exif_offset_time_dig != null) exif_new.setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, exif_offset_time_dig)
    }

    /** Transfers device exif info related to gps location. */
    private fun transferDeviceExifGPS(exif: ExifInterface, exif_new: ExifInterface) {
        if (MyDebug.LOG) Log.d(TAG, "transferDeviceExifGPS")

        val exif_gps_processing_method = exif.getAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD)
        val exif_gps_latitude = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE)
        val exif_gps_latitude_ref = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF)
        val exif_gps_longitude = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE)
        val exif_gps_longitude_ref = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF)
        val exif_gps_altitude = exif.getAttribute(ExifInterface.TAG_GPS_ALTITUDE)
        val exif_gps_altitude_ref = exif.getAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF)
        val exif_gps_datestamp = exif.getAttribute(ExifInterface.TAG_GPS_DATESTAMP)
        val exif_gps_timestamp = exif.getAttribute(ExifInterface.TAG_GPS_TIMESTAMP)
        val exif_gps_speed = exif.getAttribute(ExifInterface.TAG_GPS_SPEED)
        val exif_gps_speed_ref = exif.getAttribute(ExifInterface.TAG_GPS_SPEED_REF)

        if (exif_gps_processing_method != null) exif_new.setAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD, exif_gps_processing_method)
        if (exif_gps_latitude != null) exif_new.setAttribute(ExifInterface.TAG_GPS_LATITUDE, exif_gps_latitude)
        if (exif_gps_latitude_ref != null) exif_new.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, exif_gps_latitude_ref)
        if (exif_gps_longitude != null) exif_new.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, exif_gps_longitude)
        if (exif_gps_longitude_ref != null) exif_new.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, exif_gps_longitude_ref)
        if (exif_gps_altitude != null) exif_new.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, exif_gps_altitude)
        if (exif_gps_altitude_ref != null) exif_new.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, exif_gps_altitude_ref)
        if (exif_gps_datestamp != null) exif_new.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, exif_gps_datestamp)
        if (exif_gps_timestamp != null) exif_new.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, exif_gps_timestamp)
        if (exif_gps_speed != null) exif_new.setAttribute(ExifInterface.TAG_GPS_SPEED, exif_gps_speed)
        if (exif_gps_speed_ref != null) exif_new.setAttribute(ExifInterface.TAG_GPS_SPEED_REF, exif_gps_speed_ref)
    }

    private fun removeExifTags(exif_new: ExifInterface, request: ImageSaver.Request) {
        if (MyDebug.LOG) Log.d(TAG, "removeExifTags")

        if (request.removeDeviceExif != ImageSaver.Request.RemoveDeviceExif.OFF) {
            if (MyDebug.LOG) Log.d(TAG, "remove exif tags")
            exif_new.setAttribute(ExifInterface.TAG_F_NUMBER, null)
            exif_new.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, null)
            exif_new.setAttribute(ExifInterface.TAG_FLASH, null)
            exif_new.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, null)
            exif_new.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, null)
            exif_new.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, null)
            @Suppress("DEPRECATION")
            exif_new.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, null)
            exif_new.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, null)
            exif_new.setAttribute(ExifInterface.TAG_MAKE, null)
            exif_new.setAttribute(ExifInterface.TAG_MODEL, null)
            exif_new.setAttribute(ExifInterface.TAG_WHITE_BALANCE, null)
            exif_new.setAttribute(ExifInterface.TAG_APERTURE_VALUE, null)
            exif_new.setAttribute(ExifInterface.TAG_BRIGHTNESS_VALUE, null)
            exif_new.setAttribute(ExifInterface.TAG_CFA_PATTERN, null)
            exif_new.setAttribute(ExifInterface.TAG_COLOR_SPACE, null)
            exif_new.setAttribute(ExifInterface.TAG_COMPONENTS_CONFIGURATION, null)
            exif_new.setAttribute(ExifInterface.TAG_COMPRESSED_BITS_PER_PIXEL, null)
            exif_new.setAttribute(ExifInterface.TAG_COMPRESSION, null)
            exif_new.setAttribute(ExifInterface.TAG_CONTRAST, null)
            exif_new.setAttribute(ExifInterface.TAG_DEVICE_SETTING_DESCRIPTION, null)
            exif_new.setAttribute(ExifInterface.TAG_DIGITAL_ZOOM_RATIO, null)
            exif_new.setAttribute(ExifInterface.TAG_EXPOSURE_BIAS_VALUE, null)
            exif_new.setAttribute(ExifInterface.TAG_EXPOSURE_INDEX, null)
            exif_new.setAttribute(ExifInterface.TAG_EXPOSURE_MODE, null)
            exif_new.setAttribute(ExifInterface.TAG_EXPOSURE_PROGRAM, null)
            exif_new.setAttribute(ExifInterface.TAG_FLASH_ENERGY, null)
            exif_new.setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, null)
            exif_new.setAttribute(ExifInterface.TAG_FOCAL_PLANE_RESOLUTION_UNIT, null)
            exif_new.setAttribute(ExifInterface.TAG_FOCAL_PLANE_X_RESOLUTION, null)
            exif_new.setAttribute(ExifInterface.TAG_FOCAL_PLANE_Y_RESOLUTION, null)
            exif_new.setAttribute(ExifInterface.TAG_GAIN_CONTROL, null)
            exif_new.setAttribute(ExifInterface.TAG_GPS_AREA_INFORMATION, null)
            exif_new.setAttribute(ExifInterface.TAG_GPS_DEST_BEARING, null)
            exif_new.setAttribute(ExifInterface.TAG_GPS_DEST_BEARING_REF, null)
            exif_new.setAttribute(ExifInterface.TAG_GPS_DEST_DISTANCE, null)
            exif_new.setAttribute(ExifInterface.TAG_GPS_DEST_DISTANCE_REF, null)
            exif_new.setAttribute(ExifInterface.TAG_GPS_DEST_LATITUDE, null)
            exif_new.setAttribute(ExifInterface.TAG_GPS_DEST_LATITUDE_REF, null)
            exif_new.setAttribute(ExifInterface.TAG_GPS_DEST_LONGITUDE, null)
            exif_new.setAttribute(ExifInterface.TAG_GPS_DEST_LONGITUDE_REF, null)
            exif_new.setAttribute(ExifInterface.TAG_GPS_DIFFERENTIAL, null)
            exif_new.setAttribute(ExifInterface.TAG_GPS_DOP, null)
            if (!request.storeGeoDirection) {
                exif_new.setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION, null)
                exif_new.setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION_REF, null)
            }
            exif_new.setAttribute(ExifInterface.TAG_GPS_MAP_DATUM, null)
            exif_new.setAttribute(ExifInterface.TAG_GPS_MEASURE_MODE, null)
            exif_new.setAttribute(ExifInterface.TAG_GPS_SATELLITES, null)
            exif_new.setAttribute(ExifInterface.TAG_GPS_STATUS, null)
            exif_new.setAttribute(ExifInterface.TAG_GPS_TRACK, null)
            exif_new.setAttribute(ExifInterface.TAG_GPS_TRACK_REF, null)
            exif_new.setAttribute(ExifInterface.TAG_GPS_VERSION_ID, null)
            exif_new.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, null)
            exif_new.setAttribute(ExifInterface.TAG_IMAGE_UNIQUE_ID, null)
            exif_new.setAttribute(ExifInterface.TAG_INTEROPERABILITY_INDEX, null)
            exif_new.setAttribute(ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT, null)
            exif_new.setAttribute(ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT_LENGTH, null)
            exif_new.setAttribute(ExifInterface.TAG_LIGHT_SOURCE, null)
            exif_new.setAttribute(ExifInterface.TAG_MAKER_NOTE, null)
            exif_new.setAttribute(ExifInterface.TAG_MAX_APERTURE_VALUE, null)
            exif_new.setAttribute(ExifInterface.TAG_METERING_MODE, null)
            exif_new.setAttribute(ExifInterface.TAG_OECF, null)
            exif_new.setAttribute(ExifInterface.TAG_PHOTOMETRIC_INTERPRETATION, null)
            exif_new.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, null)
            exif_new.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, null)
            exif_new.setAttribute(ExifInterface.TAG_PLANAR_CONFIGURATION, null)
            exif_new.setAttribute(ExifInterface.TAG_PRIMARY_CHROMATICITIES, null)
            exif_new.setAttribute(ExifInterface.TAG_REFERENCE_BLACK_WHITE, null)
            exif_new.setAttribute(ExifInterface.TAG_RESOLUTION_UNIT, null)
            exif_new.setAttribute(ExifInterface.TAG_ROWS_PER_STRIP, null)
            exif_new.setAttribute(ExifInterface.TAG_SAMPLES_PER_PIXEL, null)
            exif_new.setAttribute(ExifInterface.TAG_SATURATION, null)
            exif_new.setAttribute(ExifInterface.TAG_SCENE_CAPTURE_TYPE, null)
            exif_new.setAttribute(ExifInterface.TAG_SCENE_TYPE, null)
            exif_new.setAttribute(ExifInterface.TAG_SENSING_METHOD, null)
            exif_new.setAttribute(ExifInterface.TAG_SHARPNESS, null)
            exif_new.setAttribute(ExifInterface.TAG_SHUTTER_SPEED_VALUE, null)
            exif_new.setAttribute(ExifInterface.TAG_SOFTWARE, null)
            exif_new.setAttribute(ExifInterface.TAG_SPATIAL_FREQUENCY_RESPONSE, null)
            exif_new.setAttribute(ExifInterface.TAG_SPECTRAL_SENSITIVITY, null)
            exif_new.setAttribute(ExifInterface.TAG_STRIP_BYTE_COUNTS, null)
            exif_new.setAttribute(ExifInterface.TAG_STRIP_OFFSETS, null)
            exif_new.setAttribute(ExifInterface.TAG_SUBJECT_AREA, null)
            exif_new.setAttribute(ExifInterface.TAG_SUBJECT_DISTANCE, null)
            exif_new.setAttribute(ExifInterface.TAG_SUBJECT_DISTANCE_RANGE, null)
            exif_new.setAttribute(ExifInterface.TAG_SUBJECT_LOCATION, null)
            exif_new.setAttribute(ExifInterface.TAG_THUMBNAIL_IMAGE_WIDTH, null)
            exif_new.setAttribute(ExifInterface.TAG_THUMBNAIL_IMAGE_LENGTH, null)
            exif_new.setAttribute(ExifInterface.TAG_TRANSFER_FUNCTION, null)
            if (!request.storeYpr) {
                exif_new.setAttribute(ExifInterface.TAG_USER_COMMENT, null)
            }
            exif_new.setAttribute(ExifInterface.TAG_WHITE_POINT, null)
            exif_new.setAttribute(ExifInterface.TAG_X_RESOLUTION, null)
            exif_new.setAttribute(ExifInterface.TAG_Y_CB_CR_COEFFICIENTS, null)
            exif_new.setAttribute(ExifInterface.TAG_Y_CB_CR_POSITIONING, null)
            exif_new.setAttribute(ExifInterface.TAG_Y_CB_CR_SUB_SAMPLING, null)
            exif_new.setAttribute(ExifInterface.TAG_Y_RESOLUTION, null)
            if (request.customTagArtist.isNullOrEmpty()) {
                exif_new.setAttribute(ExifInterface.TAG_ARTIST, null)
            }
            if (request.customTagCopyright.isNullOrEmpty()) {
                exif_new.setAttribute(ExifInterface.TAG_COPYRIGHT, null)
            }

            exif_new.setAttribute(ExifInterface.TAG_BITS_PER_SAMPLE, null)
            exif_new.setAttribute(ExifInterface.TAG_EXIF_VERSION, null)
            exif_new.setAttribute(ExifInterface.TAG_FLASHPIX_VERSION, null)
            exif_new.setAttribute(ExifInterface.TAG_GAMMA, null)
            exif_new.setAttribute(ExifInterface.TAG_RELATED_SOUND_FILE, null)
            exif_new.setAttribute(ExifInterface.TAG_SENSITIVITY_TYPE, null)
            exif_new.setAttribute(ExifInterface.TAG_STANDARD_OUTPUT_SENSITIVITY, null)
            exif_new.setAttribute(ExifInterface.TAG_RECOMMENDED_EXPOSURE_INDEX, null)
            exif_new.setAttribute(ExifInterface.TAG_ISO_SPEED, null)
            exif_new.setAttribute(ExifInterface.TAG_ISO_SPEED_LATITUDE_YYY, null)
            exif_new.setAttribute(ExifInterface.TAG_ISO_SPEED_LATITUDE_ZZZ, null)
            exif_new.setAttribute(ExifInterface.TAG_FILE_SOURCE, null)
            exif_new.setAttribute(ExifInterface.TAG_CUSTOM_RENDERED, null)
            exif_new.setAttribute(ExifInterface.TAG_CAMERA_OWNER_NAME, null)
            exif_new.setAttribute(ExifInterface.TAG_BODY_SERIAL_NUMBER, null)
            exif_new.setAttribute(ExifInterface.TAG_LENS_SPECIFICATION, null)
            exif_new.setAttribute(ExifInterface.TAG_LENS_MAKE, null)
            exif_new.setAttribute(ExifInterface.TAG_LENS_MODEL, null)
            exif_new.setAttribute(ExifInterface.TAG_LENS_SERIAL_NUMBER, null)
            exif_new.setAttribute(ExifInterface.TAG_GPS_H_POSITIONING_ERROR, null)
            exif_new.setAttribute(ExifInterface.TAG_DNG_VERSION, null)
            exif_new.setAttribute(ExifInterface.TAG_DEFAULT_CROP_SIZE, null)
            exif_new.setAttribute(ExifInterface.TAG_ORF_THUMBNAIL_IMAGE, null)
            exif_new.setAttribute(ExifInterface.TAG_ORF_PREVIEW_IMAGE_START, null)
            exif_new.setAttribute(ExifInterface.TAG_ORF_PREVIEW_IMAGE_LENGTH, null)
            exif_new.setAttribute(ExifInterface.TAG_ORF_ASPECT_FRAME, null)
            exif_new.setAttribute(ExifInterface.TAG_RW2_SENSOR_BOTTOM_BORDER, null)
            exif_new.setAttribute(ExifInterface.TAG_RW2_SENSOR_LEFT_BORDER, null)
            exif_new.setAttribute(ExifInterface.TAG_RW2_SENSOR_RIGHT_BORDER, null)
            exif_new.setAttribute(ExifInterface.TAG_RW2_SENSOR_TOP_BORDER, null)
            exif_new.setAttribute(ExifInterface.TAG_RW2_ISO, null)
            exif_new.setAttribute(ExifInterface.TAG_RW2_JPG_FROM_RAW, null)
            exif_new.setAttribute(ExifInterface.TAG_XMP, null)
            exif_new.setAttribute(ExifInterface.TAG_NEW_SUBFILE_TYPE, null)
            exif_new.setAttribute(ExifInterface.TAG_SUBFILE_TYPE, null)

            if (request.removeDeviceExif != ImageSaver.Request.RemoveDeviceExif.KEEP_DATETIME) {
                if (MyDebug.LOG) Log.d(TAG, "remove datetime tags")
                exif_new.setAttribute(ExifInterface.TAG_DATETIME, null)
                exif_new.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, null)
                exif_new.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, null)
                exif_new.setAttribute(ExifInterface.TAG_SUBSEC_TIME, null)
                exif_new.setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, null)
                exif_new.setAttribute(ExifInterface.TAG_SUBSEC_TIME_DIGITIZED, null)
                exif_new.setAttribute(ExifInterface.TAG_OFFSET_TIME, null)
                exif_new.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, null)
                exif_new.setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, null)
            }

            if (!request.storeLocation) {
                if (MyDebug.LOG) Log.d(TAG, "remove gps tags")
                exif_new.setAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD, null)
                exif_new.setAttribute(ExifInterface.TAG_GPS_LATITUDE, null)
                exif_new.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, null)
                exif_new.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, null)
                exif_new.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, null)
                exif_new.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, null)
                exif_new.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, null)
                exif_new.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, null)
                exif_new.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, null)
                exif_new.setAttribute(ExifInterface.TAG_GPS_SPEED, null)
                exif_new.setAttribute(ExifInterface.TAG_GPS_SPEED_REF, null)
            }
        }
    }

    private fun setGPSDirectionExif(exif: ExifInterface, storeGeoDirection: Boolean, geo_direction: Double) {
        if (MyDebug.LOG) Log.d(TAG, "setGPSDirectionExif")
        if (storeGeoDirection) {
            var geo_angle = Math.toDegrees(geo_direction).toFloat()
            if (geo_angle < 0.0f) {
                geo_angle += 360.0f
            }
            if (MyDebug.LOG) Log.d(TAG, "save geo_angle: $geo_angle")
            val GPSImgDirection_string = "${Math.round(geo_angle * 100)}/100"
            if (MyDebug.LOG) Log.d(TAG, "GPSImgDirection_string: $GPSImgDirection_string")
            exif.setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION, GPSImgDirection_string)
            exif.setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION_REF, "M")
        }
    }

    private fun hasCustomExif(customTagArtist: String?, customTagCopyright: String?): Boolean {
        if (!customTagArtist.isNullOrEmpty()) return true
        if (!customTagCopyright.isNullOrEmpty()) return true
        return false
    }

    private fun setCustomExif(exif: ExifInterface, customTagArtist: String?, customTagCopyright: String?) {
        if (MyDebug.LOG) Log.d(TAG, "setCustomExif")
        if (!customTagArtist.isNullOrEmpty()) {
            if (MyDebug.LOG) Log.d(TAG, "apply TAG_ARTIST: $customTagArtist")
            exif.setAttribute(ExifInterface.TAG_ARTIST, customTagArtist)
        }
        if (!customTagCopyright.isNullOrEmpty()) {
            if (MyDebug.LOG) Log.d(TAG, "apply TAG_COPYRIGHT: $customTagCopyright")
            exif.setAttribute(ExifInterface.TAG_COPYRIGHT, customTagCopyright)
        }
    }

    private fun addDateTimeExif(exif: ExifInterface, current_date: Date?) {
        if (current_date == null) return
        if (MyDebug.LOG) Log.d(TAG, "addDateTimeExif")
        var exif_datetime = exif.getAttribute(ExifInterface.TAG_DATETIME)
        if (MyDebug.LOG) Log.d(TAG, "existing exif TAG_DATETIME: $exif_datetime")
        
        if (exif_datetime == null) {
            var date_fmt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
            date_fmt.timeZone = TimeZone.getDefault()
            exif_datetime = date_fmt.format(current_date)
            if (MyDebug.LOG) Log.d(TAG, "new TAG_DATETIME: $exif_datetime")

            exif.setAttribute(ExifInterface.TAG_DATETIME, exif_datetime)
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exif_datetime)
            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, exif_datetime)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                date_fmt = SimpleDateFormat("XXX", Locale.US)
                date_fmt.timeZone = TimeZone.getDefault()
                val timezone = date_fmt.format(current_date)
                if (MyDebug.LOG) Log.d(TAG, "timezone: $timezone")
                exif.setAttribute(ExifInterface.TAG_OFFSET_TIME, timezone)
                exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, timezone)
                exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, timezone)
            }
        }
    }

    private fun fixGPSTimestamp(exif: ExifInterface, current_date: Date?) {
        if (current_date == null) return
        if (MyDebug.LOG) {
            Log.d(TAG, "fixGPSTimestamp")
            Log.d(TAG, "current datestamp: ${exif.getAttribute(ExifInterface.TAG_GPS_DATESTAMP)}")
            Log.d(TAG, "current timestamp: ${exif.getAttribute(ExifInterface.TAG_GPS_TIMESTAMP)}")
            Log.d(TAG, "current datetime: ${exif.getAttribute(ExifInterface.TAG_DATETIME)}")
        }

        val date_fmt = SimpleDateFormat("yyyy:MM:dd", Locale.US)
        date_fmt.timeZone = TimeZone.getTimeZone("UTC")
        val datestamp = date_fmt.format(current_date)

        val time_fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        time_fmt.timeZone = TimeZone.getTimeZone("UTC")
        val timestamp = time_fmt.format(current_date)

        if (MyDebug.LOG) {
            Log.d(TAG, "datestamp: $datestamp")
            Log.d(TAG, "timestamp: $timestamp")
        }
        
        exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, datestamp)
        exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, timestamp)

        if (MyDebug.LOG) Log.d(TAG, "fixGPSTimestamp exit")
    }

    private fun needGPSExifFix(is_jpeg: Boolean, using_camera2: Boolean, storeLocation: Boolean): Boolean {
        if (is_jpeg && using_camera2) {
            return storeLocation
        }
        return false
    }

    private fun modifyExif(
        exif: ExifInterface, 
        removeDeviceExif: ImageSaver.Request.RemoveDeviceExif,
        is_jpeg: Boolean, 
        using_camera2: Boolean, 
        using_camera_extensions: Boolean, 
        current_date: Date?, 
        storeLocation: Boolean,
        location: Location?, 
        storeGeoDirection: Boolean,
        geo_direction: Double, 
        customTagArtist: String?,
        customTagCopyright: String?,
        level_angle: Double, 
        pitch_angle: Double, 
        store_ypr: Boolean
    ) {
        if (MyDebug.LOG) Log.d(TAG, "modifyExif")
        setGPSDirectionExif(exif, storeGeoDirection, geo_direction)
        
        if (store_ypr) {
            var geo_angle = Math.toDegrees(geo_direction).toFloat()
            if (geo_angle < 0.0f) {
                geo_angle += 360.0f
            }
            val encoding = "ASCII\u0000\u0000\u0000"
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, "${encoding}Yaw:$geo_angle,Pitch:$pitch_angle,Roll:$level_angle")
            if (MyDebug.LOG) Log.d(TAG, "save ypr: $geo_angle, $pitch_angle, $level_angle")
        }
        
        setCustomExif(exif, customTagArtist, customTagCopyright)
        
        if (using_camera_extensions) {
            addDateTimeExif(exif, current_date)
        } else if (needGPSExifFix(is_jpeg, using_camera2, storeLocation)) {
            fixGPSTimestamp(exif, current_date)
        }
    }

    private data class ExifInterfaceHolder(val pfd: ParcelFileDescriptor?, val exif: ExifInterface?) {
        fun close() {
            if (pfd != null) {
                try {
                    pfd.close()
                } catch (e: IOException) {
                    MyDebug.logStackTrace(TAG, "failed to close parcelfiledescriptor", e)
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun createExifInterface(context: Context, picFile: File?, saveUri: Uri?): ExifInterfaceHolder {
        var parcelFileDescriptor: ParcelFileDescriptor? = null
        var exif: ExifInterface? = null
        if (picFile != null) {
            if (MyDebug.LOG) Log.d(TAG, "write to picFile: $picFile")
            exif = ExifInterface(picFile.absolutePath)
        } else if (saveUri != null) {
            if (MyDebug.LOG) Log.d(TAG, "write direct to saveUri: $saveUri")
            parcelFileDescriptor = context.contentResolver.openFileDescriptor(saveUri, "rw")
            if (parcelFileDescriptor != null) {
                val fileDescriptor = parcelFileDescriptor.fileDescriptor
                exif = ExifInterface(fileDescriptor)
            } else {
                Log.e(TAG, "failed to create ParcelFileDescriptor for saveUri: $saveUri")
            }
        }
        return ExifInterfaceHolder(parcelFileDescriptor, exif)
    }

    @Throws(IOException::class)
    fun updateExif(context: Context, request: ImageSaver.Request, picFile: File?, saveUri: Uri?) {
        if (MyDebug.LOG) Log.d(TAG, "updateExif: $picFile")
        if (request.storeGeoDirection || request.storeYpr || hasCustomExif(request.customTagArtist, request.customTagCopyright) ||
            request.usingCameraExtensions ||
            needGPSExifFix(request.type == ImageSaver.Request.Type.JPEG, request.usingCamera2, request.storeLocation)
        ) {
            val time_s = System.currentTimeMillis()
            if (MyDebug.LOG) Log.d(TAG, "add additional exif info")
            try {
                val exif_holder = createExifInterface(context, picFile, saveUri)
                if (MyDebug.LOG) Log.d(TAG, "*** time after create exif: ${System.currentTimeMillis() - time_s}")
                try {
                    val exif = exif_holder.exif
                    if (exif != null) {
                        modifyExif(
                            exif, request.removeDeviceExif, request.type == ImageSaver.Request.Type.JPEG,
                            request.usingCamera2, request.usingCameraExtensions, request.currentDate,
                            request.storeLocation, request.location, request.storeGeoDirection,
                            request.geoDirection, request.customTagArtist, request.customTagCopyright,
                            request.levelAngle, request.pitchAngle, request.storeYpr
                        )

                        if (MyDebug.LOG) Log.d(TAG, "*** time after modifyExif: ${System.currentTimeMillis() - time_s}")
                        exif.saveAttributes()
                        if (MyDebug.LOG) Log.d(TAG, "*** time after saveAttributes: ${System.currentTimeMillis() - time_s}")
                    }
                } finally {
                    exif_holder.close()
                }
            } catch (e: NoClassDefFoundError) {
                MyDebug.logStackTrace(TAG, "exif orientation NoClassDefFoundError", e)
            }
            if (MyDebug.LOG) Log.d(TAG, "*** time to add additional exif info: ${System.currentTimeMillis() - time_s}")
        } else {
            if (MyDebug.LOG) Log.d(TAG, "no exif data to update for: $picFile")
        }
    }

    @Throws(IOException::class)
    private fun setExif(request: ImageSaver.Request, exif: ExifInterface, exif_new: ExifInterface) {
        if (MyDebug.LOG) Log.d(TAG, "setExif")

        if (request.removeDeviceExif == ImageSaver.Request.RemoveDeviceExif.OFF) {
            transferDeviceExif(exif, exif_new)
        }

        if (request.removeDeviceExif == ImageSaver.Request.RemoveDeviceExif.OFF || request.removeDeviceExif == ImageSaver.Request.RemoveDeviceExif.KEEP_DATETIME) {
            transferDeviceExifDateTime(exif, exif_new)
        }

        if (request.removeDeviceExif == ImageSaver.Request.RemoveDeviceExif.OFF || request.storeLocation) {
            transferDeviceExifGPS(exif, exif_new)
        }

        modifyExif(
            exif_new, request.removeDeviceExif, request.type == ImageSaver.Request.Type.JPEG,
            request.usingCamera2, request.usingCameraExtensions, request.currentDate,
            request.storeLocation, request.location, request.storeGeoDirection,
            request.geoDirection, request.customTagArtist, request.customTagCopyright,
            request.levelAngle, request.pitchAngle, request.storeYpr
        )

        removeExifTags(exif_new, request)
        exif_new.saveAttributes()
    }

    @Throws(IOException::class)
    fun setExifFromData(request: ImageSaver.Request, data: ByteArray, to_file: File) {
        if (MyDebug.LOG) {
            Log.d(TAG, "setExifFromData")
            Log.d(TAG, "to_file: $to_file")
        }
        var inputStream: InputStream? = null
        try {
            inputStream = ByteArrayInputStream(data)
            val exif = ExifInterface(inputStream)
            val exif_new = ExifInterface(to_file.absolutePath)
            setExif(request, exif, exif_new)
        } finally {
            inputStream?.close()
        }
    }

    @Throws(IOException::class)
    fun setExifFromData(request: ImageSaver.Request, data: ByteArray, to_file_descriptor: FileDescriptor) {
        if (MyDebug.LOG) {
            Log.d(TAG, "setExifFromData")
            Log.d(TAG, "to_file_descriptor: $to_file_descriptor")
        }
        var inputStream: InputStream? = null
        try {
            inputStream = ByteArrayInputStream(data)
            val exif = ExifInterface(inputStream)
            val exif_new = ExifInterface(to_file_descriptor)
            setExif(request, exif, exif_new)
        } finally {
            inputStream?.close()
        }
    }
}

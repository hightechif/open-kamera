/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preferences

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DialogFragment
import android.app.Fragment
import android.app.FragmentManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.DialogInterface
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Point
import android.os.Build
import android.os.Bundle
import android.preference.EditTextPreference
import android.preference.ListPreference
import android.preference.Preference
import android.preference.Preference.OnPreferenceChangeListener
import android.preference.Preference.OnPreferenceClickListener
import android.preference.PreferenceFragment
import android.preference.PreferenceGroup
import android.preference.PreferenceManager
import android.preference.TwoStatePreference
import android.text.Html
import android.text.SpannableString
import android.text.method.LinkMovementMethod
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.hightechif.openkamera.MainActivity
import com.hightechif.openkamera.R
import com.hightechif.openkamera.audio.*
import com.hightechif.openkamera.preferences.*
import com.hightechif.openkamera.processing.*
import com.hightechif.openkamera.sensors.*
import com.hightechif.openkamera.storage.*
import com.hightechif.openkamera.system.*
import com.hightechif.openkamera.ui.FolderChooserDialog
import com.hightechif.openkamera.ui.MyEditTextPreference
import com.hightechif.openkamera.utils.*

/** Fragment to handle the Settings UI. Note that originally this was a
 * PreferenceActivity rather than a PreferenceFragment which required all
 * communication to be via the bundle (since this replaced the MainActivity,
 * meaning we couldn't access data from that class. This no longer applies due
 * to now using a PreferenceFragment, but I've still kept with transferring
 * information via the bundle (for the most part, at least).
 * Also note that passing via a bundle may be necessary to avoid accessing the
 * preview, which can be null - see note about video resolutions below.
 * Also see https://stackoverflow.com/questions/14093438/after-the-rotate-oncreate-fragment-is-called-before-oncreate-fragmentactivi .
 * If the application is destroyed when in background when the user is viewing
 * the settings, then the application and its fragments will be recreated -
 * so reading from the bundle means the state is restored, where as trying
 * to read camera settings won't be possible as the camera won't yet be
 * reopened.
 */
class MyPreferenceFragment : PreferenceFragment(), OnSharedPreferenceChangeListener {
    private var edgeToEdgeMode = false

    private var cameraId = 0

    /* Any AlertDialogs we create should be added to dialogs, and removed when dismissed. Any dialogs still
     * opened when onDestroy() is called are closed.
     * Normally this shouldn't be needed - the settings is usually only closed by the user pressing Back,
     * which can only be done once any opened dialogs are also closed. But this is required if we want to
     * programmatically close the settings - this is done in MainActivity.onNewIntent(), so that if Open Kamera
     * is launched from the homescreen again when the settings was opened, we close the settings.
     * UPDATE: At the time of writing, we don't set android:launchMode="singleTask", so onNewIntent() is not called,
     * so this code isn't necessary - but there shouldn't be harm to leave it here for future use.
     */
    private val dialogs = HashSet<AlertDialog>()

    override fun onCreate(savedInstanceState: Bundle?) {
        if (MyDebug.LOG) Log.d(TAG, "onCreate")
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.preferences)

        val bundle = arguments
        this.edgeToEdgeMode = bundle.getBoolean("edge_to_edge_mode")
        this.cameraId = bundle.getInt("cameraId")
        if (MyDebug.LOG) Log.d(
            TAG,
            "cameraId: $cameraId"
        )
        val nCameras = bundle.getInt("nCameras")
        if (MyDebug.LOG) Log.d(
            TAG,
            "nCameras: $nCameras"
        )

        val cameraOpen = bundle.getBoolean("camera_open")
        if (MyDebug.LOG) Log.d(
            TAG,
            "camera_open: $cameraOpen"
        )

        val cameraApi = bundle.getString("camera_api")

        val photoModeString = bundle.getString("photo_mode_string")

        val usingAndroidL = bundle.getBoolean("using_android_l")
        if (MyDebug.LOG) Log.d(
            TAG,
            "using_android_l: $usingAndroidL"
        )

        val cameraOrientation = bundle.getInt("camera_orientation")
        if (MyDebug.LOG) Log.d(
            TAG,
            "camera_orientation: $cameraOrientation"
        )

        val minZoomFactor = bundle.getFloat("min_zoom_factor")
        val maxZoomFactor = bundle.getFloat("max_zoom_factor")

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this.activity)

        val supportsAutoStabilise = bundle.getBoolean("supports_auto_stabilise")
        if (MyDebug.LOG) Log.d(
            TAG,
            "supports_auto_stabilise: $supportsAutoStabilise"
        )

        /*if( !supportsAutoStabilise ) {
			Preference pref = findPreference("preference_auto_stabilise");
			PreferenceGroup pg = (PreferenceGroup)this.findPreference("preference_category_camera_effects");
        	pg.removePreference(pref);
		}*/

        //readFromBundle(bundle, "color_effects", Preview.getColorEffectPreferenceKey(), Camera.Parameters.EFFECT_NONE, "preference_category_camera_effects");
        //readFromBundle(bundle, "scene_modes", Preview.getSceneModePreferenceKey(), Camera.Parameters.SCENE_MODE_AUTO, "preference_category_camera_effects");
        //readFromBundle(bundle, "white_balances", Preview.getWhiteBalancePreferenceKey(), Camera.Parameters.WHITE_BALANCE_AUTO, "preference_category_camera_effects");
        //readFromBundle(bundle, "isos", Preview.getISOPreferenceKey(), "auto", "preference_category_camera_effects");
        //readFromBundle(bundle, "exposures", "preference_exposure", "0", "preference_category_camera_effects");
        val supportsFaceDetection = bundle.getBoolean("supports_face_detection")
        if (MyDebug.LOG) Log.d(
            TAG,
            "supports_face_detection: $supportsFaceDetection"
        )

        if (!supportsFaceDetection && (cameraOpen || sharedPreferences.getBoolean(
                PreferenceKeys.FACE_DETECTION_PREFERENCE_KEY,
                false
            ) == false)
        ) {
            // if camera not open, we'll think this setting isn't supported - but should only remove
            // this preference if it's set to the default (otherwise if user sets to a non-default
            // value that causes camera to not open, user won't be able to put it back to the
            // default!)
            val pref = findPreference(PreferenceKeys.FACE_DETECTION_PREFERENCE_KEY)
            val pg = findPreference("preference_category_camera_controls") as PreferenceGroup
            pg.removePreference(pref)
        }

        val previewWidth = bundle.getInt("preview_width")
        val previewHeight = bundle.getInt("preview_height")
        val previewWidths = bundle.getIntArray("preview_widths")
        val previewHeights = bundle.getIntArray("preview_heights")
        val videoWidths = bundle.getIntArray("video_widths")
        val videoHeights = bundle.getIntArray("video_heights")

        val resolutionWidth = bundle.getInt("resolution_width")
        val resolutionHeight = bundle.getInt("resolution_height")
        val widths = bundle.getIntArray("resolution_widths")
        val heights = bundle.getIntArray("resolution_heights")
        val supportsBurst = bundle.getBooleanArray("resolution_supports_burst")

        val supportsRaw = bundle.getBoolean("supports_raw")
        if (MyDebug.LOG) Log.d(
            TAG,
            "supports_raw: $supportsRaw"
        )

        val supportsHdr = bundle.getBoolean("supports_hdr")
        if (MyDebug.LOG) Log.d(
            TAG,
            "supports_hdr: $supportsHdr"
        )

        val supportsPanorama = bundle.getBoolean("supports_panorama")
        if (MyDebug.LOG) Log.d(
            TAG,
            "supports_panorama: $supportsPanorama"
        )

        val hasGyroSensors = bundle.getBoolean("has_gyro_sensors")
        if (MyDebug.LOG) Log.d(
            TAG,
            "has_gyro_sensors: $hasGyroSensors"
        )

        val supportsExpoBracketing = bundle.getBoolean("supports_expo_bracketing")
        if (MyDebug.LOG) Log.d(
            TAG,
            "supports_expo_bracketing: $supportsExpoBracketing"
        )

        val supportsExposureCompensation = bundle.getBoolean("supports_exposure_compensation")
        val exposureCompensationMin = bundle.getInt("exposure_compensation_min")
        val exposureCompensationMax = bundle.getInt("exposure_compensation_max")
        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "supports_exposure_compensation: $supportsExposureCompensation"
            )
            Log.d(
                TAG,
                "exposure_compensation_min: $exposureCompensationMin"
            )
            Log.d(
                TAG,
                "exposure_compensation_max: $exposureCompensationMax"
            )
        }

        val supportsIsoRange = bundle.getBoolean("supports_iso_range")
        val isoRangeMin = bundle.getInt("iso_range_min")
        val isoRangeMax = bundle.getInt("iso_range_max")
        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "supports_iso_range: $supportsIsoRange"
            )
            Log.d(TAG, "iso_range_min: $isoRangeMin")
            Log.d(TAG, "iso_range_max: $isoRangeMax")
        }

        val supportsExposureTime = bundle.getBoolean("supports_exposure_time")
        val exposureTimeMin = bundle.getLong("exposure_time_min")
        val exposureTimeMax = bundle.getLong("exposure_time_max")
        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "supports_exposure_time: $supportsExposureTime"
            )
            Log.d(
                TAG,
                "exposure_time_min: $exposureTimeMin"
            )
            Log.d(
                TAG,
                "exposure_time_max: $exposureTimeMax"
            )
        }

        val supportsWhiteBalanceTemperature =
            bundle.getBoolean("supports_white_balance_temperature")
        val whiteBalanceTemperatureMin = bundle.getInt("white_balance_temperature_min")
        val whiteBalanceTemperatureMax = bundle.getInt("white_balance_temperature_max")
        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "supports_white_balance_temperature: $supportsWhiteBalanceTemperature"
            )
            Log.d(
                TAG,
                "white_balance_temperature_min: $whiteBalanceTemperatureMin"
            )
            Log.d(
                TAG,
                "white_balance_temperature_max: $whiteBalanceTemperatureMax"
            )
        }

        val isMultiCam = bundle.getBoolean("is_multi_cam")
        if (MyDebug.LOG) Log.d(
            TAG,
            "is_multi_cam: $isMultiCam"
        )

        val videoQuality = bundle.getStringArray("video_quality")

        val currentVideoQuality = bundle.getString("current_video_quality")
        val videoFrameWidth = bundle.getInt("video_frame_width")
        val videoFrameHeight = bundle.getInt("video_frame_height")
        val videoBitRate = bundle.getInt("video_bit_rate")
        val videoFrameRate = bundle.getInt("video_frame_rate")
        val videoCaptureRate = bundle.getDouble("video_capture_rate")
        val videoHighSpeed = bundle.getBoolean("video_high_speed")
        val videoCaptureRateFactor = bundle.getFloat("video_capture_rate_factor")

        val supportsOpticalStabilization = bundle.getBoolean("supports_optical_stabilization")
        val opticalStabilizationEnabled = bundle.getBoolean("optical_stabilization_enabled")

        val supportsVideoStabilization = bundle.getBoolean("supports_video_stabilization")
        if (MyDebug.LOG) Log.d(
            TAG,
            "supports_video_stabilization: $supportsVideoStabilization"
        )

        val videoStabilizationEnabled = bundle.getBoolean("video_stabilization_enabled")

        val canDisableShutterSound = bundle.getBoolean("can_disable_shutter_sound")
        if (MyDebug.LOG) Log.d(
            TAG,
            "can_disable_shutter_sound: $canDisableShutterSound"
        )

        val tonemapMaxCurvePoints = bundle.getInt("tonemap_max_curve_points")
        val supportsTonemapCurve = bundle.getBoolean("supports_tonemap_curve")
        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "tonemap_max_curve_points: $tonemapMaxCurvePoints"
            )
            Log.d(
                TAG,
                "supports_tonemap_curve: $supportsTonemapCurve"
            )
        }

        val cameraViewAngleX = bundle.getFloat("camera_view_angle_x")
        val cameraViewAngleY = bundle.getFloat("camera_view_angle_y")
        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "camera_view_angle_x: $cameraViewAngleX"
            )
            Log.d(
                TAG,
                "camera_view_angle_y: $cameraViewAngleY"
            )
        }

        run {
            val cameraApiValues: MutableList<String> = ArrayList()
            val cameraApiEntries: MutableList<String> = ArrayList()

            // all devices support old api
            cameraApiValues.add("preference_camera_api_old")
            cameraApiEntries.add(activity.resources.getString(R.string.preference_camera_api_old))

            val supportsCamera2 = bundle.getBoolean("supports_camera2")
            if (MyDebug.LOG) Log.d(
                TAG,
                "supports_camera2: $supportsCamera2"
            )
            if (supportsCamera2) {
                cameraApiValues.add("preference_camera_api_camera2")
                cameraApiEntries.add(activity.resources.getString(R.string.preference_camera_api_camera2))
            }

            if (cameraApiValues.size == 1) {
                // if only supports 1 API, no point showing the preference
                cameraApiValues.clear()
                cameraApiEntries.clear()
            }

            readFromBundle(
                cameraApiValues.toTypedArray<String>(),
                cameraApiEntries.toTypedArray<String>(),
                "preference_camera_api",
                PreferenceKeys.CAMERA_API_PREFERENCE_DEFAULT,
                "preference_category_online"
            )
            if (cameraApiValues.size >= 2) {
                val pref = findPreference("preference_camera_api")
                pref.onPreferenceChangeListener =
                    OnPreferenceChangeListener { arg0, newValue ->
                        if (pref.key == "preference_camera_api") {
                            val listPref = pref as ListPreference
                            if (listPref.value == newValue) {
                                if (MyDebug.LOG) Log.d(TAG, "user selected same camera API")
                            } else {
                                if (MyDebug.LOG) Log.d(
                                    TAG,
                                    "user changed camera API - need to restart"
                                )
                                val mainActivity =
                                    this@MyPreferenceFragment.activity as MainActivity
                                mainActivity.restartOpenKamera()
                            }
                        }
                        true
                    }
            }
        }

        /*final boolean supportsCamera2 = bundle.getBoolean("supports_camera2");
        if( MyDebug.LOG )
            Log.d(TAG, "supportsCamera2: " + supportsCamera2);
        if( supportsCamera2 ) {
            final Preference pref = findPreference("preference_use_camera2");
            pref.setOnPreferenceClickListener(new OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference arg0) {
                    if( pref.getKey().equals("preference_use_camera2") ) {
                        if( MyDebug.LOG )
                            Log.d(TAG, "user clicked camera2 API - need to restart");
                        MainActivity mainActivity = (MainActivity)MyPreferenceFragment.this.getActivity();
                        main_activity.restartOpenKamera();
                        return false;
                    }
                    return false;
                }
            });
        }
        else {
            Preference pref = findPreference("preference_use_camera2");
            PreferenceGroup pg = (PreferenceGroup)this.findPreference("preference_category_online");
            pg.removePreference(pref);
        }*/
        run {
            val pref = findPreference("preference_online_help")
            pref.onPreferenceClickListener = OnPreferenceClickListener {
                if (pref.key == "preference_online_help") {
                    if (MyDebug.LOG) Log.d(TAG, "user clicked online help")
                    val mainActivity = this@MyPreferenceFragment.activity as MainActivity
                    mainActivity.launchOnlineHelp()
                    return@OnPreferenceClickListener false
                }
                false
            }
        }

        run {
            val pref = findPreference("preference_privacy_policy")
            pref.onPreferenceClickListener = OnPreferenceClickListener {
                if (pref.key == "preference_privacy_policy") {
                    if (MyDebug.LOG) Log.d(TAG, "user clicked privacy policy")

                    clickedPrivacyPolicy()
                }
                false
            }
        }

        /*{
            final Preference pref = findPreference("preference_donate");
            pref.setOnPreferenceClickListener(new OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference arg0) {
                    if( pref.getKey().equals("preference_donate") ) {
                        if( MyDebug.LOG )
                            Log.d(TAG, "user clicked to donate");
                        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(MainActivity.DonateLink));
                        startActivity(browserIntent);
                        return false;
                    }
                    return false;
                }
            });
        }*/
        run {
            val pref = findPreference("preference_about")
            pref.onPreferenceClickListener = object : OnPreferenceClickListener {
                override fun onPreferenceClick(arg0: Preference): Boolean {
                    if (pref.key == "preference_about") {
                        if (MyDebug.LOG) Log.d(TAG, "user clicked about")
                        val alertDialog = AlertDialog.Builder(this@MyPreferenceFragment.activity)
                        alertDialog.setTitle(R.string.preference_about)
                        val aboutString = StringBuilder()
                        var version: String? = "UNKNOWN_VERSION"
                        var versionCode = -1
                        try {
                            val pInfo =
                                this@MyPreferenceFragment.activity.packageManager.getPackageInfo(
                                    this@MyPreferenceFragment.activity.packageName, 0
                                )
                            version = pInfo.versionName
                            versionCode = pInfo.versionCode
                        } catch (e: PackageManager.NameNotFoundException) {
                            if (MyDebug.LOG) Log.d(
                                TAG,
                                "NameNotFoundException exception trying to get version number"
                            )
                            e.printStackTrace()
                        }
                        aboutString.append("Open Kamera v")
                        aboutString.append(version)
                        aboutString.append("\nCode: ")
                        aboutString.append(versionCode)
                        aboutString.append("\nPackage: ")
                        aboutString.append(this@MyPreferenceFragment.activity.packageName)
                        aboutString.append("\nAndroid API version: ")
                        aboutString.append(Build.VERSION.SDK_INT)
                        aboutString.append("\nDevice manufacturer: ")
                        aboutString.append(Build.MANUFACTURER)
                        aboutString.append("\nDevice model: ")
                        aboutString.append(Build.MODEL)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            // use non-deprecated equivalent of Display.getSize()
                            val windowMetrics =
                                this@MyPreferenceFragment.activity.windowManager.currentWindowMetrics
                            val windowInsets = windowMetrics.windowInsets
                            val insets =
                                windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout())
                            val insetsWidth = insets.right + insets.left
                            val insetsHeight = insets.top + insets.bottom
                            val bounds = windowMetrics.bounds
                            val displayX = bounds.width() - insetsWidth
                            val displayY = bounds.height() - insetsHeight
                            aboutString.append("\nDisplay size: ")
                            aboutString.append(displayX)
                            aboutString.append("x")
                            aboutString.append(displayY)
                        } else {
                            val displaySize = Point()
                            val display =
                                this@MyPreferenceFragment.activity.windowManager.defaultDisplay
                            display.getSize(displaySize)
                            aboutString.append("\nDisplay size: ")
                            aboutString.append(displaySize.x)
                            aboutString.append("x")
                            aboutString.append(displaySize.y)
                        }
                        aboutString.append("\nCurrent camera ID: ")
                        aboutString.append(cameraId)
                        aboutString.append("\nNo. of cameras: ")
                        aboutString.append(nCameras)
                        aboutString.append("\nMulti-camera?: ")
                        aboutString.append(isMultiCam)
                        aboutString.append("\nCamera API: ")
                        aboutString.append(cameraApi)
                        aboutString.append("\nCamera orientation: ")
                        aboutString.append(cameraOrientation)
                        aboutString.append("\nPhoto mode: ")
                        aboutString.append(photoModeString ?: "UNKNOWN")
                        run {
                            val lastVideoError =
                                sharedPreferences.getString("last_video_error", "")
                            if (lastVideoError!!.length > 0) {
                                aboutString.append("\nLast video error: ")
                                aboutString.append(lastVideoError)
                            }
                        }
                        aboutString.append("\nMin zoom factor: ")
                        aboutString.append(minZoomFactor)
                        aboutString.append("\nMax zoom factor: ")
                        aboutString.append(maxZoomFactor)
                        if (previewWidths != null && previewHeights != null) {
                            aboutString.append("\nPreview resolutions: ")
                            for (i in previewWidths.indices) {
                                if (i > 0) {
                                    aboutString.append(", ")
                                }
                                aboutString.append(previewWidths[i])
                                aboutString.append("x")
                                aboutString.append(previewHeights[i])
                            }
                        }
                        aboutString.append("\nPreview resolution: ")
                        aboutString.append(previewWidth)
                        aboutString.append("x")
                        aboutString.append(previewHeight)
                        if (widths != null && heights != null) {
                            aboutString.append("\nPhoto resolutions: ")
                            for (i in widths.indices) {
                                if (i > 0) {
                                    aboutString.append(", ")
                                }
                                aboutString.append(widths[i])
                                aboutString.append("x")
                                aboutString.append(heights[i])
                                if (supportsBurst != null && !supportsBurst[i]) {
                                    aboutString.append("[no burst]")
                                }
                            }
                        }
                        aboutString.append("\nPhoto resolution: ")
                        aboutString.append(resolutionWidth)
                        aboutString.append("x")
                        aboutString.append(resolutionHeight)
                        if (videoQuality != null) {
                            aboutString.append("\nVideo qualities: ")
                            for (i in videoQuality.indices) {
                                if (i > 0) {
                                    aboutString.append(", ")
                                }
                                aboutString.append(videoQuality[i])
                            }
                        }
                        if (videoWidths != null && videoHeights != null) {
                            aboutString.append("\nVideo resolutions: ")
                            for (i in videoWidths.indices) {
                                if (i > 0) {
                                    aboutString.append(", ")
                                }
                                aboutString.append(videoWidths[i])
                                aboutString.append("x")
                                aboutString.append(videoHeights[i])
                            }
                        }
                        aboutString.append("\nVideo quality: ")
                        aboutString.append(currentVideoQuality)
                        aboutString.append("\nVideo frame width: ")
                        aboutString.append(videoFrameWidth)
                        aboutString.append("\nVideo frame height: ")
                        aboutString.append(videoFrameHeight)
                        aboutString.append("\nVideo bit rate: ")
                        aboutString.append(videoBitRate)
                        aboutString.append("\nVideo frame rate: ")
                        aboutString.append(videoFrameRate)
                        aboutString.append("\nVideo capture rate: ")
                        aboutString.append(videoCaptureRate)
                        aboutString.append("\nVideo high speed: ")
                        aboutString.append(videoHighSpeed)
                        aboutString.append("\nVideo capture rate factor: ")
                        aboutString.append(videoCaptureRateFactor)
                        aboutString.append("\nAuto-level?: ")
                        aboutString.append(getString(if (supportsAutoStabilise) R.string.about_available else R.string.about_not_available))
                        aboutString.append("\nAuto-level enabled?: ")
                        aboutString.append(
                            sharedPreferences.getBoolean(
                                PreferenceKeys.AUTO_STABILISE_PREFERENCE_KEY,
                                false
                            )
                        )
                        aboutString.append("\nFace detection?: ")
                        aboutString.append(getString(if (supportsFaceDetection) R.string.about_available else R.string.about_not_available))
                        aboutString.append("\nRAW?: ")
                        aboutString.append(getString(if (supportsRaw) R.string.about_available else R.string.about_not_available))
                        aboutString.append("\nHDR?: ")
                        aboutString.append(getString(if (supportsHdr) R.string.about_available else R.string.about_not_available))
                        aboutString.append("\nPanorama?: ")
                        aboutString.append(getString(if (supportsPanorama) R.string.about_available else R.string.about_not_available))
                        aboutString.append("\nGyro sensors?: ")
                        aboutString.append(getString(if (hasGyroSensors) R.string.about_available else R.string.about_not_available))
                        aboutString.append("\nExpo?: ")
                        aboutString.append(getString(if (supportsExpoBracketing) R.string.about_available else R.string.about_not_available))
                        aboutString.append("\nExpo compensation?: ")
                        aboutString.append(getString(if (supportsExposureCompensation) R.string.about_available else R.string.about_not_available))
                        if (supportsExposureCompensation) {
                            aboutString.append("\nExposure compensation range: ")
                            aboutString.append(exposureCompensationMin)
                            aboutString.append(" to ")
                            aboutString.append(exposureCompensationMax)
                        }
                        aboutString.append("\nManual ISO?: ")
                        aboutString.append(getString(if (supportsIsoRange) R.string.about_available else R.string.about_not_available))
                        if (supportsIsoRange) {
                            aboutString.append("\nISO range: ")
                            aboutString.append(isoRangeMin)
                            aboutString.append(" to ")
                            aboutString.append(isoRangeMax)
                        }
                        aboutString.append("\nManual exposure?: ")
                        aboutString.append(getString(if (supportsExposureTime) R.string.about_available else R.string.about_not_available))
                        if (supportsExposureTime) {
                            aboutString.append("\nExposure range: ")
                            aboutString.append(exposureTimeMin)
                            aboutString.append(" to ")
                            aboutString.append(exposureTimeMax)
                        }
                        aboutString.append("\nManual WB?: ")
                        aboutString.append(getString(if (supportsWhiteBalanceTemperature) R.string.about_available else R.string.about_not_available))
                        if (supportsWhiteBalanceTemperature) {
                            aboutString.append("\nWB temperature: ")
                            aboutString.append(whiteBalanceTemperatureMin)
                            aboutString.append(" to ")
                            aboutString.append(whiteBalanceTemperatureMax)
                        }
                        aboutString.append("\nOptical stabilization?: ")
                        aboutString.append(getString(if (supportsOpticalStabilization) R.string.about_available else R.string.about_not_available))
                        aboutString.append("\nOptical stabilization enabled?: ")
                        aboutString.append(opticalStabilizationEnabled)
                        aboutString.append("\nVideo stabilization?: ")
                        aboutString.append(getString(if (supportsVideoStabilization) R.string.about_available else R.string.about_not_available))
                        aboutString.append("\nVideo stabilization enabled?: ")
                        aboutString.append(videoStabilizationEnabled)
                        aboutString.append("\nTonemap curve?: ")
                        aboutString.append(getString(if (supportsTonemapCurve) R.string.about_available else R.string.about_not_available))
                        aboutString.append("\nTonemap max curve points: ")
                        aboutString.append(tonemapMaxCurvePoints)
                        aboutString.append("\nCan disable shutter sound?: ")
                        aboutString.append(getString(if (canDisableShutterSound) R.string.about_available else R.string.about_not_available))

                        aboutString.append("\nCamera view angle: ").append(cameraViewAngleX)
                            .append(" , ").append(cameraViewAngleY)

                        aboutString.append("\nFlash modes: ")
                        val flashValues = bundle.getStringArray("flash_values")
                        if (flashValues != null && flashValues.size > 0) {
                            for (i in flashValues.indices) {
                                if (i > 0) {
                                    aboutString.append(", ")
                                }
                                aboutString.append(flashValues[i])
                            }
                        } else {
                            aboutString.append("None")
                        }
                        aboutString.append("\nFocus modes: ")
                        val focusValues = bundle.getStringArray("focus_values")
                        if (focusValues != null && focusValues.size > 0) {
                            for (i in focusValues.indices) {
                                if (i > 0) {
                                    aboutString.append(", ")
                                }
                                aboutString.append(focusValues[i])
                            }
                        } else {
                            aboutString.append("None")
                        }
                        aboutString.append("\nColor effects: ")
                        val colorEffectsValues = bundle.getStringArray("color_effects")
                        if (colorEffectsValues != null && colorEffectsValues.size > 0) {
                            for (i in colorEffectsValues.indices) {
                                if (i > 0) {
                                    aboutString.append(", ")
                                }
                                aboutString.append(colorEffectsValues[i])
                            }
                        } else {
                            aboutString.append("None")
                        }
                        aboutString.append("\nScene modes: ")
                        val sceneModesValues = bundle.getStringArray("scene_modes")
                        if (sceneModesValues != null && sceneModesValues.size > 0) {
                            for (i in sceneModesValues.indices) {
                                if (i > 0) {
                                    aboutString.append(", ")
                                }
                                aboutString.append(sceneModesValues[i])
                            }
                        } else {
                            aboutString.append("None")
                        }
                        aboutString.append("\nWhite balances: ")
                        val whiteBalancesValues = bundle.getStringArray("white_balances")
                        if (whiteBalancesValues != null && whiteBalancesValues.size > 0) {
                            for (i in whiteBalancesValues.indices) {
                                if (i > 0) {
                                    aboutString.append(", ")
                                }
                                aboutString.append(whiteBalancesValues[i])
                            }
                        } else {
                            aboutString.append("None")
                        }
                        if (!usingAndroidL) {
                            aboutString.append("\nISOs: ")
                            val isos = bundle.getStringArray("isos")
                            if (isos != null && isos.size > 0) {
                                for (i in isos.indices) {
                                    if (i > 0) {
                                        aboutString.append(", ")
                                    }
                                    aboutString.append(isos[i])
                                }
                            } else {
                                aboutString.append("None")
                            }
                            val isoKey = bundle.getString("iso_key")
                            if (isoKey != null) {
                                aboutString.append("\nISO key: ")
                                aboutString.append(isoKey)
                            }
                        }

                        val magneticAccuracy = bundle.getInt("magnetic_accuracy")
                        aboutString.append("\nMagnetic accuracy?: ")
                        aboutString.append(magneticAccuracy)

                        aboutString.append("\nUsing SAF?: ")
                        aboutString.append(
                            sharedPreferences.getBoolean(
                                PreferenceKeys.USING_SAF_PREFERENCE_KEY,
                                false
                            )
                        )
                        val saveLocation = sharedPreferences.getString(
                            PreferenceKeys.SAVE_LOCATION_PREFERENCE_KEY,
                            "OpenKamera"
                        )
                        aboutString.append("\nSave Location: ")
                        aboutString.append(saveLocation)
                        val saveLocationSaf = sharedPreferences.getString(
                            PreferenceKeys.SAVE_LOCATION_SAF_PREFERENCE_KEY,
                            ""
                        )
                        aboutString.append("\nSave Location SAF: ")
                        aboutString.append(saveLocationSaf)

                        aboutString.append("\nParameters: ")
                        val parametersString = bundle.getString("parameters_string")
                        if (parametersString != null) {
                            aboutString.append(parametersString)
                        } else {
                            aboutString.append("None")
                        }

                        val span = SpannableString(aboutString)

                        // clickable text is only supported if we call setMovementMethod on the TextView - which means we need to create
                        // our own for the AlertDialog!
                        @SuppressLint("InflateParams") // we add the view to the alert dialog in addTextViewForAlertDialog()
                        val dialogView = LayoutInflater.from(activity)
                            .inflate(R.layout.alertdialog_textview, null)
                        val textView = dialogView.findViewById<TextView>(R.id.text_view)

                        textView.text = span
                        textView.movementMethod = LinkMovementMethod.getInstance()
                        textView.setTextAppearance(activity, android.R.style.TextAppearance_Medium)
                        addTextViewForAlertDialog(alertDialog, textView)

                        //alertDialog.setMessage(aboutString);
                        alertDialog.setPositiveButton(android.R.string.ok, null)
                        alertDialog.setNegativeButton(
                            R.string.about_copy_to_clipboard
                        ) { dialog, id ->
                            if (MyDebug.LOG) Log.d(
                                TAG,
                                "user clicked copy to clipboard"
                            )
                            val clipboard =
                                activity.getSystemService(Activity.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("OpenKamera About", aboutString)
                            clipboard.setPrimaryClip(clip)
                        }
                        val alert = alertDialog.create()
                        // AlertDialog.Builder.setOnDismissListener() requires API level 17, so do it this way instead
                        alert.setOnDismissListener {
                            if (MyDebug.LOG) Log.d(
                                TAG,
                                "about dialog dismissed"
                            )
                            dialogs.remove(alert)
                        }
                        alert.show()
                        dialogs.add(alert)
                        return false
                    }
                    return false
                }
            }
        }

        setupDependencies()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (edgeToEdgeMode) {
            handleEdgeToEdge(view)
        }
    }

    /** Adds a TextView to an AlertDialog builder, placing it inside a scrollview and adding appropriate padding.
     */
    private fun addTextViewForAlertDialog(alertDialog: AlertDialog.Builder, textView: TextView) {
        val scale = activity.resources.displayMetrics.density
        val scrollView = ScrollView(activity)
        scrollView.addView(textView)
        // padding values from /sdk/platforms/android-18/data/res/layout/alert_dialog.xml
        textView.setPadding(
            (5 * scale + 0.5f).toInt(),
            (5 * scale + 0.5f).toInt(),
            (5 * scale + 0.5f).toInt(),
            (5 * scale + 0.5f).toInt()
        )
        scrollView.setPadding(
            (14 * scale + 0.5f).toInt(),
            (2 * scale + 0.5f).toInt(),
            (10 * scale + 0.5f).toInt(),
            (12 * scale + 0.5f).toInt()
        )
        alertDialog.setView(scrollView)
    }

    /** Programmatically set up dependencies for preference types (e.g., ListPreference) that don't
     * support this in xml (such as SwitchPreference and CheckBoxPreference), or where this depends
     * on the device (e.g., Android version).
     */
    private fun setupDependencies() {
    }

    /* The user clicked the privacy policy preference.
     */
    fun clickedPrivacyPolicy() {
        if (MyDebug.LOG) Log.d(TAG, "clickedPrivacyPolicy()")

        /*MainActivity mainActivity = (MainActivity)MyPreferenceFragment.this.getActivity();
        main_activity.launchOnlinePrivacyPolicy();*/
        val alertDialog = AlertDialog.Builder(this@MyPreferenceFragment.activity)
        alertDialog.setTitle(R.string.preference_privacy_policy)

        //SpannableString span = new SpannableString(getActivity().getResources().getString(R.string.preference_privacy_policy_text));
        //Linkify.addLinks(span, Linkify.WEB_URLS | Linkify.EMAIL_ADDRESSES);
        val privacyPolicyText =
            activity.resources.getString(R.string.preference_privacy_policy_text)
        val span = Html.fromHtml(privacyPolicyText)
        // clickable text is only supported if we call setMovementMethod on the TextView - which means we need to create
        // our own for the AlertDialog!
        @SuppressLint("InflateParams") // we add the view to the alert dialog in addTextViewForAlertDialog()
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.alertdialog_textview, null)
        val textView = dialogView.findViewById<TextView>(R.id.text_view)
        textView.text = span
        textView.movementMethod = LinkMovementMethod.getInstance()
        textView.setTextAppearance(activity, android.R.style.TextAppearance_Medium)
        addTextViewForAlertDialog(alertDialog, textView)

        //alertDialog.setMessage(R.string.preference_privacy_policy_text);
        alertDialog.setPositiveButton(android.R.string.ok, null)
        alertDialog.setNegativeButton(
            R.string.preference_privacy_policy_online
        ) { dialog, which ->
            if (MyDebug.LOG) Log.d(
                TAG,
                "online privacy policy"
            )
            val mainActivity = this@MyPreferenceFragment.activity as MainActivity
            mainActivity.launchOnlinePrivacyPolicy()
        }
        val alert = alertDialog.create()
        // AlertDialog.Builder.setOnDismissListener() requires API level 17, so do it this way instead
        alert.setOnDismissListener {
            if (MyDebug.LOG) Log.d(
                TAG,
                "reset dialog dismissed"
            )
            dialogs.remove(alert)
        }
        alert.show()
        dialogs.add(alert)
    }

    class SaveFolderChooserDialog : FolderChooserDialog() {
        override fun onDismiss(dialog: DialogInterface?) {
            if (MyDebug.LOG) Log.d(TAG, "FolderChooserDialog dismissed")
            // n.b., fragments have to be static (as they might be inserted into a new Activity - see http://stackoverflow.com/questions/15571010/fragment-inner-class-should-be-static),
            // so we access the MainActivity via the fragment's getActivity().
            val mainActivity = this.getActivity() as MainActivity?
            if (mainActivity != null) { // mainActivity may be null if this is being closed via MainActivity.onNewIntent()
                val newSaveLocation: String? = this.chosenFolder
                mainActivity.updateSaveFolder(newSaveLocation)
            }
            super.onDismiss(dialog)
        }
    }

    private fun readFromBundle(
        values: Array<String>,
        entries: Array<String>,
        preferenceKey: String,
        defaultValue: String,
        preferenceCategoryKey: String
    ) {
        readFromBundle(
            this,
            values,
            entries,
            preferenceKey,
            defaultValue,
            preferenceCategoryKey
        )
    }

    override fun onResume() {
        super.onResume()

        setBackground(this)

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this.activity)
        sharedPreferences.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        if (MyDebug.LOG) Log.d(TAG, "on_destroy")
        super.onDestroy()

        if (MyDebug.LOG) Log.d(
            TAG,
            "isRemoving?: $isRemoving"
        )

        if (isRemoving) {
            // if isRemoving()==true, then it means the fragment is being removed and we are returning to the activity
            // if isRemoving()==false, then it may be that the activity is being destroyed
            (activity as MainActivity).settingsClosing()
        }

        dismissDialogs(fragmentManager, dialogs)
    }

    /* So that manual changes to the checkbox/switch preferences, while the preferences are showing, show up;
     * in particular, needed for preferenceUsingSaf, when the user cancels the SAF dialog (see
     * MainActivity.onActivityResult).
     * Also programmatically sets summary (see setSummary).
     */
    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "onSharedPreferenceChanged: $key"
        )

        if (key == null) {
            // On Android 11+, when targetting Android 11+, this method is called with key==null
            // if preferences are cleared. Unclear if this happens here in practice, but return
            // just in case.
            return
        }

        val pref = findPreference(key)
        handleOnSharedPreferenceChanged(prefs, key, pref)
    }

    companion object {
        private const val TAG = "MyPreferenceFragment"

        fun handleEdgeToEdge(view: View) {
            ViewCompat.setOnApplyWindowInsetsListener(view) { v: View, windowInsets: WindowInsetsCompat ->
                //androidx.core.graphics.Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
                // don't need to avoid WindowInsetsCompat.Type.displayCutout(), as we already do this for the entire activity (see MainActivity's setOnApplyWindowInsetsListener)
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(insets.left, insets.top, insets.right, insets.bottom)
                WindowInsetsCompat.CONSUMED
            }
            view.requestApplyInsets()
        }

        /** Removes an entry and value pair from a ListPreference, if it exists.
         * @param pref The ListPreference to remove the supplied entry/value.
         * @param filterValue The value to remove from the list.
         */
        fun filterArrayEntry(pref: ListPreference, filterValue: String) {
            run {
                val entries = pref.entries ?: return
                val values = pref.entryValues ?: return

                // Zip entries and values together, filter them, then unzip back into two lists
                val (newEntries, newValues) = entries.zip(values)
                    .filter { (_, value) -> value != filterValue }
                    .unzip()

                // Convert the lists back to TypedArrays as required by ListPreference
                pref.entries = newEntries.toTypedArray()
                pref.entryValues = newValues.toTypedArray()
            }
        }

        fun readFromBundle(
            preferenceFragment: PreferenceFragment,
            values: Array<String>?,
            entries: Array<String>?,
            preferenceKey: String,
            defaultValue: String?,
            preferenceCategoryKey: String
        ) {
            if (MyDebug.LOG) {
                Log.d(TAG, "readFromBundle")
            }
            if (values != null && values.size > 0) {
                if (MyDebug.LOG) {
                    Log.d(TAG, "values:")
                    for (value in values) {
                        Log.d(TAG, value)
                    }
                }
                val lp = preferenceFragment.findPreference(preferenceKey) as ListPreference
                lp.entries = entries
                lp.entryValues = values
                val sharedPreferences =
                    PreferenceManager.getDefaultSharedPreferences(preferenceFragment.activity)
                val value = sharedPreferences.getString(preferenceKey, defaultValue)
                if (MyDebug.LOG) Log.d(TAG, "    value: " + values.contentToString())
                lp.value = value
            } else {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "remove preference $preferenceKey from category $preferenceCategoryKey"
                )
                val pref = preferenceFragment.findPreference(preferenceKey)
                val pg =
                    preferenceFragment.findPreference(preferenceCategoryKey) as PreferenceGroup
                pg.removePreference(pref)
            }
        }

        fun setBackground(fragment: Fragment) {
            // prevent fragment being transparent
            // note, setting color here only seems to affect the "main" preference fragment screen, and not sub-screens
            // note, on Galaxy Nexus Android 4.3 this sets to black rather than the dark grey that the background theme should be (and what the sub-screens use); works okay on Nexus 7 Android 5
            // we used to use a light theme for the PreferenceFragment, but mixing themes in same activity seems to cause problems (e.g., for EditTextPreference colors)
            val array = fragment.activity.theme.obtainStyledAttributes(
                intArrayOf(
                    android.R.attr.colorBackground
                )
            )
            val backgroundColor = array.getColor(0, Color.BLACK)
            /*if( MyDebug.LOG ) {
			int r = (backgroundColor >> 16) & 0xFF;
			int g = (backgroundColor >> 8) & 0xFF;
			int b = (backgroundColor >> 0) & 0xFF;
			Log.d(TAG, "backgroundColor: " + r + " , " + g + " , " + b);
		}*/
            fragment.view!!.setBackgroundColor(backgroundColor)
            array.recycle()
        }

        fun dismissDialogs(fragmentManager: FragmentManager, dialogs: HashSet<AlertDialog>) {
            // dismiss open dialogs - see comment for dialogs for why we do this
            for (dialog in dialogs) {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "dismiss dialog: $dialog"
                )
                dialog.dismiss()
            }
            // similarly dimiss any dialog fragments still opened
            val folderFragment = fragmentManager.findFragmentByTag("FOLDER_FRAGMENT")
            if (folderFragment != null) {
                val dialogFragment = folderFragment as DialogFragment
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "dismiss dialogFragment: $dialogFragment"
                )
                dialogFragment.dismissAllowingStateLoss()
            }
        }

        fun handleOnSharedPreferenceChanged(
            prefs: SharedPreferences,
            key: String,
            pref: Preference?
        ) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "handleOnSharedPreferenceChanged: $key"
            )

            if (pref == null) {
                // this can happen if the shared preference that changed is for a sub-screen i.e. a different fragment
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "handleOnSharedPreferenceChanged: preference doesn't belong to this fragment"
                )
                return
            }

            if (pref is TwoStatePreference) {
                pref.isChecked = prefs.getBoolean(key, true)
            } else if (pref is ListPreference) {
                pref.value = prefs.getString(key, "")
            }
            setSummary(pref)
        }

        /** Programmatically sets summaries as required.
         * Remember to call setSummary() from the constructor for any keys we set, to initialise the
         * summary.
         */
        fun setSummary(pref: Preference) {
            if (pref is EditTextPreference) {
                /* We have a runtime check for using EditTextPreference - we don't want these due to importance of
             * supporting the Google Play emoji policy (see comment in MyEditTextPreference.java) - and this
             * helps guard against the risk of accidentally adding more EditTextPreferences in future.
             * Once we've switched to using Android X Preference library, and hence safe to use EditTextPreference
             * again, this code can be removed.
             */
                throw RuntimeException("detected an EditTextPreference: " + pref.getKey() + " pref: " + pref)
            }

            if (pref is EditTextPreference || pref is MyEditTextPreference) {
                // %s only supported for ListPreference
                // we also display the usual summary if no preference value is set
                if (pref.key == "preference_exif_artist" ||
                    pref.key == "preference_exif_copyright" ||
                    pref.key == "preference_save_photo_prefix" ||
                    pref.key == "preference_save_video_prefix" ||
                    pref.key == "preference_textstamp"
                ) {
                    var defaultValue = ""
                    if (pref.key == "preference_save_photo_prefix") defaultValue = "IMG_"
                    else if (pref.key == "preference_save_video_prefix") defaultValue = "VID_"

                    val currentValue: String?
                    if (pref is EditTextPreference) {
                        currentValue = pref.text
                    } else {
                        val editTextPref: MyEditTextPreference = pref as MyEditTextPreference
                        currentValue = editTextPref.text
                    }

                    if (currentValue == defaultValue) {
                        when (pref.key) {
                            "preference_exif_artist" -> pref.setSummary(R.string.preference_exif_artist_summary)
                            "preference_exif_copyright" -> pref.setSummary(R.string.preference_exif_copyright_summary)
                            "preference_save_photo_prefix" -> pref.setSummary(R.string.preference_save_photo_prefix_summary)
                            "preference_save_video_prefix" -> pref.setSummary(R.string.preference_save_video_prefix_summary)
                            "preference_textstamp" -> pref.setSummary(R.string.preference_textstamp_summary)
                        }
                    } else {
                        // non-default value, so display the current value
                        pref.summary = currentValue
                    }
                }
            }
        }
    }
}
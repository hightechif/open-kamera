/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.camera2.CameraExtensionCharacteristics
import android.os.Handler
import android.preference.PreferenceManager
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView.ScaleType
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.edit
import com.hightechif.openkamera.MainActivity
import com.hightechif.openkamera.MyApplicationInterface.PhotoMode
import com.hightechif.openkamera.R
import com.hightechif.openkamera.cameracontroller.CameraController
import com.hightechif.openkamera.preferences.PreferenceKeys
import com.hightechif.openkamera.preview.Preview
import com.hightechif.openkamera.utils.MyDebug
import java.text.DecimalFormat
import java.util.Arrays
import java.util.Collections
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** This defines the UI for the "popup" button, that provides quick access to a
 * range of options.
 */
class PopupView(context: Context) : LinearLayout(context) {
    private val arrowButtonW: Int
    private val arrowButtonH: Int

    private var totalWidthDp: Int

    private var pictureSizeIndex = -1
    private var nrModeIndex = -1
    private var burstNImagesIndex = -1
    private var videoSizeIndex = -1
    private var videoCaptureRateIndex = -1
    private var timerIndex = -1
    private var repeatModeIndex = -1
    private var gridIndex = -1

    private val decimalFormat1dpForce0 = DecimalFormat("0.0")

    init {
        if (MyDebug.LOG) Log.d(TAG, "new PopupView: $this")

        val debugTime = System.nanoTime()
        if (MyDebug.LOG) Log.d(TAG, "PopupView time 1: " + (System.nanoTime() - debugTime))
        this.orientation = VERTICAL

        val scale = resources.displayMetrics.density

        arrowButtonW = (arrowButtonWDp * scale + 0.5f).toInt() // convert dps to pixels
        arrowButtonH = (arrowButtonHDp * scale + 0.5f).toInt() // convert dps to pixels

        val mainActivity: MainActivity = this.context as MainActivity

        var smallScreen = false
        totalWidthDp = 280
        val maxWidthDp: Int = mainActivity.mainUI.getMaxHeightDp(false)
        if (totalWidthDp > maxWidthDp) {
            totalWidthDp = maxWidthDp
            smallScreen = true
        }
        if (MyDebug.LOG) {
            Log.d(TAG, "max_width_dp: $maxWidthDp")
            Log.d(TAG, "total_width_dp: $totalWidthDp")
            Log.d(TAG, "small_screen: $smallScreen")
        }

        /*{
			int totalWidth = (int) (totalWidthDp * scale + 0.5f); // convert dps to pixels;
			if( MyDebug.LOG )
				Log.d(TAG, "totalWidth: " + totalWidth);
			ViewGroup.LayoutParams params = new LayoutParams(
					totalWidth,
					LayoutParams.WRAP_CONTENT);
			this.setLayoutParams(params);
		}*/
        val preview: Preview = mainActivity.preview
        val isCameraExtension: Boolean =
            mainActivity.applicationInterface.isCameraExtensionPref()
        if (MyDebug.LOG) Log.d(TAG, "PopupView time 2: " + (System.nanoTime() - debugTime))

        if (!mainActivity.mainUI.getOnScreenIcons().showCycleFlashIcon()) {
            var supportedFlashValues: List<String>? = preview.supportedFlashValues
            if (preview.isVideo && supportedFlashValues != null) {
                // filter flash modes we don't want to show
                val filter: MutableList<String> = ArrayList()
                for (flashValue in supportedFlashValues) {
                    if (Preview.isFlashSupportedForVideo(flashValue)) filter.add(flashValue)
                }
                supportedFlashValues = filter
            }
            if (supportedFlashValues != null && supportedFlashValues.size > 1) { // no point showing flash options if only one available!
                addButtonOptionsToPopup(
                    supportedFlashValues,
                    R.array.flash_icons,
                    R.array.flash_values,
                    resources.getString(R.string.flash_mode),
                    preview.currentFlashValue,
                    0,
                    "TEST_FLASH",
                    object : ButtonOptionsPopupListener() {
                        override fun onClick(option: String) {
                            if (MyDebug.LOG) Log.d(
                                TAG,
                                "clicked flash: $option"
                            )
                            preview.updateFlash(option)
                            mainActivity.mainUI.setPopupIcon()
                            mainActivity.mainUI
                                .destroyPopup() // need to recreate popup for new selection
                        }
                    })
            }
        }
        if (MyDebug.LOG) Log.d(TAG, "PopupView time 3: " + (System.nanoTime() - debugTime))

        //if( preview.isVideo && preview.isTakingPhoto() ) {
        if (preview.isVideo && preview.isVideoRecording) {
            // don't add any more options
        } else {
            // make a copy of getSupportedFocusValues() so we can modify it
            var supportedFocusValues: MutableList<String>? =
                preview.supportedFocusValues?.toMutableList()
            val photoMode: PhotoMode = mainActivity.applicationInterface.photoMode
            if (!preview.isVideo && photoMode == PhotoMode.FocusBracketing) {
                // don't show focus modes in focus bracketing mode (as we'll always run in manual focus mode)
                supportedFocusValues = null
            }
            if (supportedFocusValues != null) {
                supportedFocusValues = ArrayList(supportedFocusValues)
                // only show appropriate continuous focus mode
                if (preview.isVideo) {
                    supportedFocusValues.remove("focus_mode_continuous_picture")
                } else {
                    supportedFocusValues.remove("focus_mode_continuous_video")
                }
            }
            addButtonOptionsToPopup(
                supportedFocusValues,
                R.array.focus_mode_icons,
                R.array.focus_mode_values,
                resources.getString(R.string.focus_mode),
                preview.currentFocusValue,
                0,
                "TEST_FOCUS",
                object : ButtonOptionsPopupListener() {
                    override fun onClick(option: String) {
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "clicked focus: $option"
                        )
                        preview.updateFocus(option, false, true)
                        mainActivity.mainUI
                            .destroyPopup() // need to recreate popup for new selection
                    }
                })
            if (MyDebug.LOG) Log.d(TAG, "PopupView time 4: " + (System.nanoTime() - debugTime))

            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)

            //final boolean useExpandedMenu = true;
            val useExpandedMenu = false
            val photoModes: MutableList<String> = ArrayList()
            val photoModeValues: MutableList<PhotoMode> = ArrayList()
            photoModes.add(resources.getString(if (useExpandedMenu) R.string.photo_mode_standard_full else R.string.photo_mode_standard))
            photoModeValues.add(PhotoMode.Standard)
            if (mainActivity.supportsNoiseReduction()) {
                photoModes.add(resources.getString(if (useExpandedMenu) R.string.photo_mode_noise_reduction_full else R.string.photo_mode_noise_reduction))
                photoModeValues.add(PhotoMode.NoiseReduction)
            }
            if (mainActivity.supportsDRO()) {
                photoModes.add(resources.getString(R.string.photo_mode_dro))
                photoModeValues.add(PhotoMode.DRO)
            }
            if (mainActivity.supportsHDR()) {
                photoModes.add(resources.getString(R.string.photo_mode_hdr))
                photoModeValues.add(PhotoMode.HDR)
            }
            if (mainActivity.supportsPanorama()) {
                photoModes.add(resources.getString(if (useExpandedMenu) R.string.photo_mode_panorama_full else R.string.photo_mode_panorama))
                photoModeValues.add(PhotoMode.Panorama)
            }
            if (mainActivity.supportsFastBurst()) {
                photoModes.add(resources.getString(if (useExpandedMenu) R.string.photo_mode_fast_burst_full else R.string.photo_mode_fast_burst))
                photoModeValues.add(PhotoMode.FastBurst)
            }
            if (mainActivity.supportsExpoBracketing()) {
                photoModes.add(resources.getString(if (useExpandedMenu) R.string.photo_mode_expo_bracketing_full else R.string.photo_mode_expo_bracketing))
                photoModeValues.add(PhotoMode.ExpoBracketing)
            }
            if (mainActivity.supportsFocusBracketing()) {
                photoModes.add(resources.getString(if (useExpandedMenu) R.string.photo_mode_focus_bracketing_full else R.string.photo_mode_focus_bracketing))
                photoModeValues.add(PhotoMode.FocusBracketing)
            }
            if (mainActivity.supportsCameraExtension(CameraExtensionCharacteristics.EXTENSION_AUTOMATIC)) {
                photoModes.add(resources.getString(if (useExpandedMenu) R.string.photo_mode_x_auto_full else R.string.photo_mode_x_auto))
                photoModeValues.add(PhotoMode.X_Auto)
            }
            if (mainActivity.supportsCameraExtension(CameraExtensionCharacteristics.EXTENSION_HDR)) {
                photoModes.add(resources.getString(if (useExpandedMenu) R.string.photo_mode_x_hdr_full else R.string.photo_mode_x_hdr))
                photoModeValues.add(PhotoMode.X_HDR)
            }
            if (mainActivity.supportsCameraExtension(CameraExtensionCharacteristics.EXTENSION_NIGHT)) {
                photoModes.add(resources.getString(if (useExpandedMenu) R.string.photo_mode_x_night_full else R.string.photo_mode_x_night))
                photoModeValues.add(PhotoMode.X_Night)
            }
            if (mainActivity.supportsCameraExtension(CameraExtensionCharacteristics.EXTENSION_BOKEH)) {
                photoModes.add(resources.getString(if (useExpandedMenu) R.string.photo_mode_x_bokeh_full else R.string.photo_mode_x_bokeh))
                photoModeValues.add(PhotoMode.X_Bokeh)
            }
            if (mainActivity.supportsCameraExtension(CameraExtensionCharacteristics.EXTENSION_BEAUTY)) {
                photoModes.add(resources.getString(if (useExpandedMenu) R.string.photo_mode_x_beauty_full else R.string.photo_mode_x_beauty))
                photoModeValues.add(PhotoMode.X_Beauty)
            }
            if (preview.isVideo) {
                // only show photo modes when in photo mode, not video mode!
                // (photo modes not supported for photo snapshot whilst recording video)
            } else if (photoModes.size > 1) {
                var currentMode: String? = null
                var i = 0
                while (i < photoModes.size && currentMode == null) {
                    if (photoModeValues[i] == photoMode) {
                        currentMode = photoModes[i]
                    }
                    i++
                }
                if (currentMode == null) {
                    // applicationinterface should only report we're in a mode if it's supported, but just in case...
                    if (MyDebug.LOG) Log.e(
                        TAG,
                        "can't find current mode for mode: $photoMode"
                    )
                    currentMode = "" // this will mean no photo mode is highlighted in the UI
                }

                if (useExpandedMenu) {
                    addRadioOptionsToPopup(
                        sharedPreferences,
                        photoModes,
                        photoModes,
                        resources.getString(R.string.photo_mode),
                        null,
                        null,
                        currentMode,
                        "TEST_PHOTO_MODE",
                        object : RadioOptionsListener() {
                            override fun onClick(selectedValue: String) {
                                if (MyDebug.LOG) Log.d(
                                    TAG,
                                    "clicked photo mode: $selectedValue"
                                )

                                changePhotoMode(photoModes, photoModeValues, selectedValue)
                            }
                        })
                } else {
                    addTitleToPopup(resources.getString(R.string.photo_mode))
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "PopupView time 6: " + (System.nanoTime() - debugTime)
                    )

                    addButtonOptionsToPopup(
                        photoModes,
                        -1,
                        -1,
                        "",
                        currentMode,
                        4,
                        "TEST_PHOTO_MODE",
                        object : ButtonOptionsPopupListener() {
                            override fun onClick(option: String) {
                                if (MyDebug.LOG) Log.d(
                                    TAG,
                                    "clicked photo mode: $option"
                                )

                                changePhotoMode(photoModes, photoModeValues, option)
                            }
                        })
                }
            }
            if (MyDebug.LOG) Log.d(TAG, "PopupView time 7: " + (System.nanoTime() - debugTime))

            if (!preview.isVideo && photoMode == PhotoMode.NoiseReduction) {
                if (MyDebug.LOG) Log.d(TAG, "add noise reduction options")

                val nrModeValues = resources.getStringArray(R.array.preference_nr_mode_values)
                val nrModeEntries = resources.getStringArray(R.array.preference_nr_mode_entries)

                if (nrModeValues.size != nrModeEntries.size) {
                    Log.e(
                        TAG,
                        "preference_nr_mode_values and preference_nr_mode_entries are different lengths"
                    )
                    throw RuntimeException()
                }

                //String nrModeValue = sharedPreferences.getString(PreferenceKeys.NRModePreferenceKey, "preference_nr_mode_normal");
                val nrModeValue: String = mainActivity.applicationInterface.nRMode
                nrModeIndex = Arrays.asList(*nrModeValues).indexOf(nrModeValue)
                if (nrModeIndex == -1) {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "can't find nr_mode_value $nrModeValue in nr_mode_values!"
                    )
                    nrModeIndex = 0
                }
                addArrayOptionsToPopup(
                    Arrays.asList<String>(*nrModeEntries),
                    resources.getString(R.string.preference_nr_mode),
                    true,
                    true,
                    nrModeIndex,
                    false,
                    "NR_MODE",
                    object : ArrayOptionsPopupListener() {
                        private fun update() {
                            if (nrModeIndex == -1) return
                            val newNrModeValue = nrModeValues[nrModeIndex]
                            val sharedPreferences =
                                PreferenceManager.getDefaultSharedPreferences(mainActivity)
                            sharedPreferences.edit {
                                //editor.putString(PreferenceKeys.NRModePreferenceKey, newNrModeValue);
                                mainActivity.applicationInterface.nRMode = newNrModeValue
                            }
                            if (preview.cameraController != null) {
                                preview.setupBurstMode()
                            }
                        }

                        override fun onClickPrev(): Int {
                            if (nrModeIndex != -1 && nrModeIndex > 0) {
                                nrModeIndex--
                                update()
                                return nrModeIndex
                            }
                            return -1
                        }

                        override fun onClickNext(): Int {
                            if (nrModeIndex != -1 && nrModeIndex < nrModeValues.size - 1) {
                                nrModeIndex++
                                update()
                                return nrModeIndex
                            }
                            return -1
                        }
                    })
            }

            if (mainActivity.supportsAutoStabilise() && !mainActivity.mainUI
                    .showAutoLevelIcon()
            ) {
                // don't show auto-stabilise checkbox on popup if there's an on-screen icon
                val checkBox = CheckBox(mainActivity)
                checkBox.text = resources.getString(R.string.preference_auto_stabilise)
                checkBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, standardTextSizeDip)
                checkBox.setTextColor(Color.WHITE)
                run {
                    // align the checkbox a bit better
                    val params = LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        LayoutParams.MATCH_PARENT
                    )
                    val leftPadding = (10 * scale + 0.5f).toInt() // convert dps to pixels
                    params.setMargins(leftPadding, 0, 0, 0)
                    checkBox.layoutParams = params
                }

                val autoStabilise =
                    sharedPreferences.getBoolean(PreferenceKeys.AUTO_STABILISE_PREFERENCE_KEY, false)
                if (autoStabilise) checkBox.isChecked = autoStabilise
                checkBox.setOnCheckedChangeListener { buttonView, isChecked ->
                    mainActivity.mainUI.getOnScreenIcons().clickedAutoLevel()
                }

                this.addView(checkBox)
            }
            if (MyDebug.LOG) Log.d(TAG, "PopupView time 8: " + (System.nanoTime() - debugTime))

            if (!preview.isVideo && photoMode != PhotoMode.Panorama) {
                // Only show photo resolutions in photo mode - even if photo snapshots whilst recording video is supported, the
                // resolutions for that won't match what the user has requested for photo mode resolutions.
                // And Panorama mode chooses its own resolution.
                val pictureSizes: MutableList<CameraController.Size> =
                    ArrayList(preview.getSupportedPictureSizes(true))
                // take a copy so that we can reorder
                // pictureSizes is sorted high to low, but we want to order low to high
                pictureSizes.reverse()
                pictureSizeIndex = -1
                val currentPictureSize: CameraController.Size? = preview.currentPictureSize
                val pictureSizeStrings: MutableList<String> = ArrayList()
                for (i in pictureSizes.indices) {
                    val pictureSize: CameraController.Size? = pictureSizes[i]
                    if (pictureSize != null) {
                        //String sizeString = picture_size.width + " x " + picture_size.height;
                        val sizeString: String =
                            "${pictureSize.width}" + " x " + "${pictureSize.height}" + " (" + Preview.getMPString(
                                pictureSize.width,
                                pictureSize.height
                            ) + ")"
                        pictureSizeStrings.add(sizeString)
                        if (pictureSize.equals(currentPictureSize)) {
                            pictureSizeIndex = i
                        }
                    }
                }
                if (pictureSizeIndex == -1) {
                    Log.e(TAG, "couldn't find index of current picture size")
                } else {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "picture_size_index: $pictureSizeIndex"
                    )
                }
                addArrayOptionsToPopup(
                    pictureSizeStrings,
                    resources.getString(R.string.preference_resolution),
                    false,
                    false,
                    pictureSizeIndex,
                    false,
                    "PHOTO_RESOLUTIONS",
                    object : ArrayOptionsPopupListener() {
                        val handler: Handler = Handler()
                        val updateRunnable: Runnable = Runnable {
                            if (MyDebug.LOG) Log.d(TAG, "update settings due to resolution change")
                            mainActivity.updateForSettings(
                                true,
                                "",
                                true,
                                false
                            ) // keep the popupview open
                        }

                        private fun update() {
                            if (pictureSizeIndex == -1) return
                            val newSize: CameraController.Size =
                                pictureSizes[pictureSizeIndex] ?: return
                            val resolutionString: String =
                                "${newSize.width}" + " " + newSize.height
                            val sharedPreferences =
                                PreferenceManager.getDefaultSharedPreferences(mainActivity)
                            val editor = sharedPreferences.edit()
                            editor.putString(
                                PreferenceKeys.getResolutionPreferenceKey(
                                    preview.cameraId,
                                    mainActivity.applicationInterface
                                        .getCameraIdSPhysicalPref()
                                ), resolutionString
                            )
                            editor.apply()
                            mainActivity.settingsViewModel.setPhotoResolution(resolutionString)

                            // make it easier to scroll through the list of resolutions without a pause each time
                            // need a longer time for extension modes, due to the need to camera reopening (which will cause the
                            // popup menu to close)
                            val delayTime = (if (mainActivity.applicationInterface
                                    .isCameraExtensionPref()
                            ) 800 else 400).toLong()
                            handler.removeCallbacks(updateRunnable)
                            handler.postDelayed(updateRunnable, delayTime)
                        }

                        override fun onClickPrev(): Int {
                            if (pictureSizeIndex != -1 && pictureSizeIndex > 0) {
                                pictureSizeIndex--
                                update()
                                return pictureSizeIndex
                            }
                            return -1
                        }

                        override fun onClickNext(): Int {
                            if (pictureSizeIndex != -1 && pictureSizeIndex < pictureSizes.size - 1) {
                                pictureSizeIndex++
                                update()
                                return pictureSizeIndex
                            }
                            return -1
                        }
                    })
            }
            if (MyDebug.LOG) Log.d(TAG, "PopupView time 9: " + (System.nanoTime() - debugTime))

            if (preview.isVideo) {
                // only show video resolutions in video mode
                //final List<String> videoSizes = preview.getVideoQualityHander().getSupportedVideoQuality();
                //videoSizeIndex = preview.getVideoQualityHander().getCurrentVideoQualityIndex();
                var videoSizes: List<String> = preview.getSupportedVideoQuality(
                    mainActivity.applicationInterface.getVideoFPSPref()
                )
                if (videoSizes.size == 0) {
                    Log.e(TAG, "can't find any supported video sizes for current fps!")
                    // fall back to unfiltered list
                    videoSizes = preview.videoQualityHander.supportedVideoQuality
                }
                // take a copy so that we can reorder
                videoSizes = ArrayList(videoSizes)
                // videoSizes is sorted high to low, but we want to order low to high
                Collections.reverse(videoSizes)

                val videoSizesF = videoSizes
                videoSizeIndex =
                    videoSizes.size - 1 // default to largest (just in case current size not found??)
                for (i in videoSizes.indices) {
                    val videoSize = videoSizes[i]
                    if (videoSize == preview.videoQualityHander.currentVideoQuality) {
                        videoSizeIndex = i
                        break
                    }
                }
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "video_size_index:$videoSizeIndex"
                )
                val videoSizeStrings: MutableList<String> = ArrayList()
                for (videoSize in videoSizes) {
                    val qualityString: String =
                        preview.getCamcorderProfileDescriptionShort(videoSize)
                    videoSizeStrings.add(qualityString)
                }
                addArrayOptionsToPopup(
                    videoSizeStrings,
                    resources.getString(R.string.video_quality),
                    false,
                    false,
                    videoSizeIndex,
                    false,
                    "VIDEO_RESOLUTIONS",
                    object : ArrayOptionsPopupListener() {
                        val handler: Handler = Handler()
                        val updateRunnable: Runnable = Runnable {
                            if (MyDebug.LOG) Log.d(
                                TAG,
                                "update settings due to video resolution change"
                            )
                            mainActivity.updateForSettings(
                                true,
                                "",
                                true,
                                false
                            ) // keep the popupview open
                        }

                        private fun update() {
                            if (videoSizeIndex == -1) return
                            val quality = videoSizesF[videoSizeIndex]
                            val sharedPreferences =
                                PreferenceManager.getDefaultSharedPreferences(mainActivity)
                            val editor = sharedPreferences.edit()
                            editor.putString(
                                PreferenceKeys.getVideoQualityPreferenceKey(
                                    preview.cameraId,
                                    mainActivity.applicationInterface
                                        .getCameraIdSPhysicalPref(),
                                    mainActivity.applicationInterface.fpsIsHighSpeed()
                                ), quality
                            )
                            editor.apply()
                            mainActivity.settingsViewModel.setVideoQuality(quality)

                            // make it easier to scroll through the list of resolutions without a pause each time
                            handler.removeCallbacks(updateRunnable)
                            handler.postDelayed(updateRunnable, 400)
                        }

                        override fun onClickPrev(): Int {
                            if (videoSizeIndex != -1 && videoSizeIndex > 0) {
                                videoSizeIndex--
                                update()
                                return videoSizeIndex
                            }
                            return -1
                        }

                        override fun onClickNext(): Int {
                            if (videoSizeIndex != -1 && videoSizeIndex < videoSizesF.size - 1) {
                                videoSizeIndex++
                                update()
                                return videoSizeIndex
                            }
                            return -1
                        }
                    })
            }
            if (MyDebug.LOG) Log.d(TAG, "PopupView time 10: " + (System.nanoTime() - debugTime))

            // apertures probably not supported for camera extensions anyway
            if (preview.supportedApertures != null && !isCameraExtension) {
                if (MyDebug.LOG) Log.d(TAG, "add apertures")

                addTitleToPopup(resources.getString(R.string.aperture))

                val apertures: MutableList<Float> = ArrayList()
                val aperturesStrings: MutableList<String> = ArrayList()
                var currentAperture: Float =
                    mainActivity.applicationInterface.getAperturePref()
                val prefix = "F/"

                var foundDefault = false
                var currentApertureS = ""
                for (aperture in preview.supportedApertures) {
                    apertures.add(aperture)
                    val apertureString =
                        prefix + decimalFormat1dpForce0.format(aperture.toDouble())
                    aperturesStrings.add(apertureString)
                    if (currentAperture == aperture) {
                        foundDefault = true
                        currentApertureS = apertureString
                    }
                }

                if (!foundDefault) {
                    // read from Camera API
                    if (preview.cameraController != null && preview.cameraController!!.captureResultHasAperture()) {
                        currentAperture = preview.cameraController!!.captureResultAperture()
                        currentApertureS =
                            prefix + decimalFormat1dpForce0.format(currentAperture.toDouble())
                    }
                }

                addButtonOptionsToPopup(
                    aperturesStrings,
                    -1,
                    -1,
                    "",
                    currentApertureS,
                    0,
                    "TEST_APERTURE",
                    object : ButtonOptionsPopupListener() {
                        override fun onClick(option: String) {
                            if (MyDebug.LOG) Log.d(
                                TAG,
                                "clicked aperture: $option"
                            )
                            val index = aperturesStrings.indexOf(option)
                            if (index != -1) {
                                val newAperture = apertures[index]
                                if (MyDebug.LOG) Log.d(
                                    TAG,
                                    "new_aperture: $newAperture"
                                )
                                preview.showToast(
                                    null,
                                    resources.getString(R.string.aperture) + ": " + option,
                                    true
                                )
                                mainActivity.applicationInterface.setAperture(newAperture)
                                if (preview.cameraController != null) {
                                    preview.cameraController!!.setAperture(newAperture)
                                }
                            } else {
                                Log.e(TAG, "unknown aperture: $option")
                            }
                            mainActivity.mainUI
                                .destroyPopup() // need to recreate popup for new selection
                        }
                    })
            }

            if (!preview.isVideo && photoMode == PhotoMode.FastBurst) {
                if (MyDebug.LOG) Log.d(TAG, "add fast burst options")

                val allBurstModeValues =
                    resources.getStringArray(R.array.preference_fast_burst_n_images_values)
                val allBurstModeEntries =
                    resources.getStringArray(R.array.preference_fast_burst_n_images_entries)

                //String [] burstModeValues = new String[all_burst_mode_values.length];
                //String [] burstModeEntries = new String[all_burst_mode_entries.length];
                if (allBurstModeValues.size != allBurstModeEntries.size) {
                    Log.e(
                        TAG,
                        "preference_fast_burst_n_images_values and preference_fast_burst_n_images_entries are different lengths"
                    )
                    throw RuntimeException()
                }

                var maxBurstImages: Int =
                    mainActivity.applicationInterface.imageSaver.queueSize + 1
                maxBurstImages = max(
                    2.0,
                    maxBurstImages.toDouble()
                ).toInt() // make sure we at least allow the minimum of 2 burst images!
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "max_burst_images: $maxBurstImages"
                )

                // filter number of burst images - don't allow more than maxBurstImages
                val burstModeValuesL: MutableList<String> = ArrayList()
                val burstModeEntriesL: MutableList<String> = ArrayList()
                for (i in allBurstModeValues.indices) {
                    val nImages: Int
                    try {
                        nImages = allBurstModeValues[i].toInt()
                    } catch (e: NumberFormatException) {
                        Log.e(
                            TAG,
                            "failed to parse " + i + "th preference_fast_burst_n_images_values value: " + allBurstModeValues[i]
                        )
                        e.printStackTrace()
                        continue
                    }
                    if (nImages > maxBurstImages) {
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "n_images $nImages is more than max_burst_images: $maxBurstImages"
                        )
                        continue
                    }
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "n_images $nImages"
                    )
                    burstModeValuesL.add(allBurstModeValues[i])
                    burstModeEntriesL.add(allBurstModeEntries[i])
                }
                val burstModeValues = burstModeValuesL.toTypedArray<String>()
                val burstModeEntries = burstModeEntriesL.toTypedArray<String>()

                val burstModeValue =
                    sharedPreferences.getString(PreferenceKeys.FAST_BURST_N_IMAGES_PREFERENCE_KEY, "5")!!
                burstNImagesIndex = Arrays.asList(*burstModeValues).indexOf(burstModeValue)
                if (burstNImagesIndex == -1) {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "can't find burst_mode_value $burstModeValue in burst_mode_values!"
                    )
                    burstNImagesIndex = 0
                }
                addArrayOptionsToPopup(
                    Arrays.asList<String>(*burstModeEntries),
                    resources.getString(R.string.preference_fast_burst_n_images),
                    true,
                    false,
                    burstNImagesIndex,
                    false,
                    "FAST_BURST_N_IMAGES",
                    object : ArrayOptionsPopupListener() {
                        private fun update() {
                            if (burstNImagesIndex == -1) return
                            val newBurstModeValue = burstModeValues[burstNImagesIndex]
                            val sharedPreferences =
                                PreferenceManager.getDefaultSharedPreferences(mainActivity)
                            val editor = sharedPreferences.edit()
                            editor.putString(
                                PreferenceKeys.FAST_BURST_N_IMAGES_PREFERENCE_KEY,
                                newBurstModeValue
                            )
                            editor.apply()
                            if (preview.cameraController != null) {
                                preview.cameraController!!.setBurstNImages(
                                    mainActivity.applicationInterface.getBurstNImages()
                                )
                            }
                        }

                        override fun onClickPrev(): Int {
                            if (burstNImagesIndex != -1 && burstNImagesIndex > 0) {
                                burstNImagesIndex--
                                update()
                                return burstNImagesIndex
                            }
                            return -1
                        }

                        override fun onClickNext(): Int {
                            if (burstNImagesIndex != -1 && burstNImagesIndex < burstModeValues.size - 1) {
                                burstNImagesIndex++
                                update()
                                return burstNImagesIndex
                            }
                            return -1
                        }
                    })
            } else if (!preview.isVideo && photoMode == PhotoMode.FocusBracketing) {
                if (MyDebug.LOG) Log.d(TAG, "add focus bracketing options")

                val burstModeValues =
                    resources.getStringArray(R.array.preference_focus_bracketing_n_images_values)
                val burstModeEntries =
                    resources.getStringArray(R.array.preference_focus_bracketing_n_images_entries)

                if (burstModeValues.size != burstModeEntries.size) {
                    Log.e(
                        TAG,
                        "preference_focus_bracketing_n_images_values and preference_focus_bracketing_n_images_entries are different lengths"
                    )
                    throw RuntimeException()
                }

                val burstModeValue = sharedPreferences.getString(
                    PreferenceKeys.FOCUS_BRACKETING_N_IMAGES_PREFERENCE_KEY,
                    "3"
                )!!
                burstNImagesIndex = Arrays.asList(*burstModeValues).indexOf(burstModeValue)
                if (burstNImagesIndex == -1) {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "can't find burst_mode_value $burstModeValue in burst_mode_values!"
                    )
                    burstNImagesIndex = 0
                }
                addArrayOptionsToPopup(
                    Arrays.asList<String>(*burstModeEntries),
                    resources.getString(R.string.preference_focus_bracketing_n_images),
                    true,
                    false,
                    burstNImagesIndex,
                    false,
                    "FOCUS_BRACKETING_N_IMAGES",
                    object : ArrayOptionsPopupListener() {
                        private fun update() {
                            if (burstNImagesIndex == -1) return
                            val newBurstModeValue = burstModeValues[burstNImagesIndex]
                            val sharedPreferences =
                                PreferenceManager.getDefaultSharedPreferences(mainActivity)
                            val editor = sharedPreferences.edit()
                            editor.putString(
                                PreferenceKeys.FOCUS_BRACKETING_N_IMAGES_PREFERENCE_KEY,
                                newBurstModeValue
                            )
                            editor.apply()
                            if (preview.cameraController != null) {
                                preview.cameraController!!.setFocusBracketingNImages(
                                    mainActivity.applicationInterface
                                        .getFocusBracketingNImagesPref()
                                )
                            }
                        }

                        override fun onClickPrev(): Int {
                            if (burstNImagesIndex != -1 && burstNImagesIndex > 0) {
                                burstNImagesIndex--
                                update()
                                return burstNImagesIndex
                            }
                            return -1
                        }

                        override fun onClickNext(): Int {
                            if (burstNImagesIndex != -1 && burstNImagesIndex < burstModeValues.size - 1) {
                                burstNImagesIndex++
                                update()
                                return burstNImagesIndex
                            }
                            return -1
                        }
                    })

                addCheckBox(
                    context,
                    scale,
                    resources.getString(R.string.focus_bracketing_add_infinity),
                    sharedPreferences.getBoolean(
                        PreferenceKeys.FOCUS_BRACKETING_ADD_INFINITY_PREFERENCE_KEY,
                        false
                    )
                ) { buttonView, isChecked ->
                    val sharedPreferences =
                        PreferenceManager.getDefaultSharedPreferences(mainActivity)
                    val editor = sharedPreferences.edit()
                    editor.putBoolean(
                        PreferenceKeys.FOCUS_BRACKETING_ADD_INFINITY_PREFERENCE_KEY,
                        isChecked
                    )
                    editor.apply()
                    if (preview.cameraController != null) {
                        preview.cameraController!!.setFocusBracketingAddInfinity(
                            mainActivity.applicationInterface
                                .getFocusBracketingAddInfinityPref()
                        )
                    }
                }

                if (mainActivity.supportsFocusBracketingSourceAuto()) {
                    addCheckBox(
                        context,
                        scale,
                        resources.getString(R.string.focus_bracketing_auto_source_distance),
                        sharedPreferences.getBoolean(
                            PreferenceKeys.FOCUS_BRACKETING_AUTO_SOURCE_DISTANCE_PREFERENCE_KEY,
                            false
                        )
                    ) { buttonView, isChecked ->
                        mainActivity.applicationInterface
                            .setFocusBracketingSourceAutoPref(isChecked)
                        if (!isChecked) {
                            preview.setFocusDistance(
                                mainActivity.preview.cameraController!!.captureResultFocusDistance(),
                                false,
                                false
                            )
                        }
                    }
                }
            }

            if (preview.isVideo) {
                val captureRateValues: List<Float> =
                    mainActivity.applicationInterface.supportedVideoCaptureRates
                if (captureRateValues.size > 1) {
                    if (MyDebug.LOG) Log.d(TAG, "add slow motion / timelapse video options")
                    val captureRateValue = sharedPreferences.getFloat(
                        PreferenceKeys.getVideoCaptureRatePreferenceKey(
                            preview.cameraId,
                            mainActivity.applicationInterface.getCameraIdSPhysicalPref()
                        ), 1.0f
                    )
                    val captureRateStr: MutableList<String> = ArrayList()
                    var captureRateStdIndex = -1
                    for (i in captureRateValues.indices) {
                        val thisCaptureRate = captureRateValues[i]
                        if (abs((1.0f - thisCaptureRate).toDouble()) < 1.0e-5) {
                            captureRateStr.add(resources.getString(R.string.preference_video_capture_rate_normal))
                            captureRateStdIndex = i
                        } else {
                            captureRateStr.add(thisCaptureRate.toString() + "x")
                        }
                        if (abs((captureRateValue - thisCaptureRate).toDouble()) < 1.0e-5) {
                            videoCaptureRateIndex = i
                        }
                    }
                    if (videoCaptureRateIndex == -1) {
                        if (MyDebug.LOG) Log.d(TAG, "can't find video_capture_rate_index")
                        // default to no slow motion or timelapse
                        videoCaptureRateIndex = captureRateStdIndex
                        if (videoCaptureRateIndex == -1) {
                            Log.e(TAG, "can't find capture_rate_std_index")
                            videoCaptureRateIndex = 0
                        }
                    }
                    addArrayOptionsToPopup(
                        captureRateStr,
                        resources.getString(R.string.preference_video_capture_rate),
                        true,
                        false,
                        videoCaptureRateIndex,
                        false,
                        "VIDEOCAPTURERATE",
                        object : ArrayOptionsPopupListener() {
                            private var oldVideoCaptureRateIndex = videoCaptureRateIndex

                            val handler: Handler = Handler()
                            val updateRunnable: Runnable = Runnable {
                                if (MyDebug.LOG) Log.d(
                                    TAG,
                                    "update settings due to video capture rate change"
                                )
                                mainActivity.updateForSettings(
                                    true,
                                    "",
                                    true,
                                    false
                                ) // keep the popupview open
                            }

                            private fun update() {
                                if (videoCaptureRateIndex == -1) return
                                val newCaptureRateValue =
                                    captureRateValues[videoCaptureRateIndex]
                                val sharedPreferences =
                                    PreferenceManager.getDefaultSharedPreferences(mainActivity)
                                val editor = sharedPreferences.edit()
                                editor.putFloat(
                                    PreferenceKeys.getVideoCaptureRatePreferenceKey(
                                        preview.cameraId,
                                        mainActivity.applicationInterface
                                            .getCameraIdSPhysicalPref()
                                    ), newCaptureRateValue
                                )
                                editor.apply()

                                val oldCaptureRateValue =
                                    captureRateValues[oldVideoCaptureRateIndex]
                                val oldSlowMotion = (oldCaptureRateValue < 1.0f - 1.0e-5f)
                                val newSlowMotion = (newCaptureRateValue < 1.0f - 1.0e-5f)
                                // if changing to/from a slow motion mode, this will in general switch on/off high fps frame
                                // rates, which changes the available video resolutions, so we need to re-open the popup
                                val keepPopup = (oldSlowMotion == newSlowMotion)
                                // only display a toast if the popup is closing
                                //String toastMessage = getResources().getString(R.string.preference_video_capture_rate) + ": " + capture_rate_str.get(videoCaptureRateIndex);
                                var toastMessage = ""
                                if (!keepPopup) {
                                    toastMessage = if (newSlowMotion) """
     ${resources.getString(R.string.slow_motion_enabled)}
     ${resources.getString(R.string.preference_video_capture_rate)}: ${captureRateStr[videoCaptureRateIndex]}
     """.trimIndent()
                                    else resources.getString(R.string.slow_motion_disabled)
                                }
                                if (MyDebug.LOG) {
                                    Log.d(TAG, "update settings due to capture rate change")
                                    Log.d(
                                        TAG,
                                        "old_capture_rate_value: $oldCaptureRateValue"
                                    )
                                    Log.d(
                                        TAG,
                                        "new_capture_rate_value: $newCaptureRateValue"
                                    )
                                    Log.d(
                                        TAG,
                                        "old_slow_motion: $oldSlowMotion"
                                    )
                                    Log.d(
                                        TAG,
                                        "new_slow_motion: $newSlowMotion"
                                    )
                                    Log.d(
                                        TAG,
                                        "keep_popup: $keepPopup"
                                    )
                                    Log.d(
                                        TAG,
                                        "toast_message: $toastMessage"
                                    )
                                }
                                oldVideoCaptureRateIndex = videoCaptureRateIndex

                                if (keepPopup) {
                                    // make it easier to scroll through the list of capture rates without a pause each time
                                    handler.removeCallbacks(updateRunnable)
                                    handler.postDelayed(updateRunnable, 400)
                                } else {
                                    mainActivity.updateForSettings(
                                        true,
                                        toastMessage,
                                        keepPopup,
                                        false
                                    )
                                }
                            }

                            override fun onClickPrev(): Int {
                                if (videoCaptureRateIndex != -1 && videoCaptureRateIndex > 0) {
                                    videoCaptureRateIndex--
                                    update()
                                    return videoCaptureRateIndex
                                }
                                return -1
                            }

                            override fun onClickNext(): Int {
                                if (videoCaptureRateIndex != -1 && videoCaptureRateIndex < captureRateValues.size - 1) {
                                    videoCaptureRateIndex++
                                    update()
                                    return videoCaptureRateIndex
                                }
                                return -1
                            }
                        })
                }
            }

            if (photoMode !== PhotoMode.Panorama) {
                // timer not supported with panorama

                val timerValues = resources.getStringArray(R.array.preference_timer_values)
                val timerEntries = resources.getStringArray(R.array.preference_timer_entries)
                val timerValue =
                    sharedPreferences.getString(PreferenceKeys.TIMER_PREFERENCE_KEY, "0")!!
                timerIndex = Arrays.asList(*timerValues).indexOf(timerValue)
                if (timerIndex == -1) {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "can't find timer_value $timerValue in timer_values!"
                    )
                    timerIndex = 0
                }
                // titleInOptions should be false for small screens: e.g., problems with pt-rBR or pt-rPT on 4.5" screens or less, see https://sourceforge.net/p/OpenKamera/discussion/photography/thread/3aa940c636/
                addArrayOptionsToPopup(
                    Arrays.asList<String>(*timerEntries),
                    resources.getString(R.string.preference_timer),
                    !smallScreen,
                    false,
                    timerIndex,
                    false,
                    "TIMER",
                    object : ArrayOptionsPopupListener() {
                        private fun update() {
                            if (timerIndex == -1) return
                            val newTimerValue = timerValues[timerIndex]
                            val sharedPreferences =
                                PreferenceManager.getDefaultSharedPreferences(mainActivity)
                            val editor = sharedPreferences.edit()
                            editor.putString(PreferenceKeys.TIMER_PREFERENCE_KEY, newTimerValue)
                            editor.apply()
                            mainActivity.settingsViewModel.setTimerSeconds(newTimerValue.toIntOrNull() ?: 0)
                        }

                        override fun onClickPrev(): Int {
                            if (timerIndex != -1 && timerIndex > 0) {
                                timerIndex--
                                update()
                                return timerIndex
                            }
                            return -1
                        }

                        override fun onClickNext(): Int {
                            if (timerIndex != -1 && timerIndex < timerValues.size - 1) {
                                timerIndex++
                                update()
                                return timerIndex
                            }
                            return -1
                        }
                    })
            }
            if (MyDebug.LOG) Log.d(TAG, "PopupView time 11: " + (System.nanoTime() - debugTime))

            if (photoMode !== PhotoMode.Panorama) {
                // auto-repeat not supported with panorama

                val repeatModeValues =
                    resources.getStringArray(R.array.preference_burst_mode_values)
                val repeatModeEntries =
                    resources.getStringArray(R.array.preference_burst_mode_entries)
                val repeatModeValue =
                    sharedPreferences.getString(PreferenceKeys.REPEAT_MODE_PREFERENCE_KEY, "1")!!
                repeatModeIndex = Arrays.asList(*repeatModeValues).indexOf(repeatModeValue)
                if (repeatModeIndex == -1) {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "can't find repeat_mode_value $repeatModeValue in repeat_mode_values!"
                    )
                    repeatModeIndex = 0
                }
                // titleInOptions should be false for small screens: e.g., problems with pt-rBR or pt-rPT on 4.5" screens or less, see https://sourceforge.net/p/OpenKamera/discussion/photography/thread/3aa940c636/
                // set titleInOptionsFirstOnly to true, as displaying "Repeat: Unlimited" can be too long in some languages, e.g., Vietnamese (vi)
                addArrayOptionsToPopup(
                    Arrays.asList<String>(*repeatModeEntries),
                    resources.getString(R.string.preference_burst_mode),
                    !smallScreen,
                    true,
                    repeatModeIndex,
                    false,
                    "REPEAT_MODE",
                    object : ArrayOptionsPopupListener() {
                        private fun update() {
                            if (repeatModeIndex == -1) return
                            val newRepeatModeValue = repeatModeValues[repeatModeIndex]
                            val sharedPreferences =
                                PreferenceManager.getDefaultSharedPreferences(mainActivity)
                            val editor = sharedPreferences.edit()
                            editor.putString(
                                PreferenceKeys.REPEAT_MODE_PREFERENCE_KEY,
                                newRepeatModeValue
                            )
                            editor.apply()
                        }

                        override fun onClickPrev(): Int {
                            if (repeatModeIndex != -1 && repeatModeIndex > 0) {
                                repeatModeIndex--
                                update()
                                return repeatModeIndex
                            }
                            return -1
                        }

                        override fun onClickNext(): Int {
                            if (repeatModeIndex != -1 && repeatModeIndex < repeatModeValues.size - 1) {
                                repeatModeIndex++
                                update()
                                return repeatModeIndex
                            }
                            return -1
                        }
                    })
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "PopupView time 12: " + (System.nanoTime() - debugTime)
                )
            }

            val gridValues = resources.getStringArray(R.array.preference_grid_values)
            val gridEntries = resources.getStringArray(R.array.preference_grid_entries)
            val gridValue =
                sharedPreferences.getString(
                    PreferenceKeys.SHOW_GRID_PREFERENCE_KEY,
                    "preference_grid_none"
                )!!
            gridIndex = Arrays.asList(*gridValues).indexOf(gridValue)
            if (gridIndex == -1) {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "can't find grid_value $gridValue in grid_values!"
                )
                gridIndex = 0
            }
            addArrayOptionsToPopup(
                Arrays.asList<String>(*gridEntries),
                resources.getString(R.string.grid),
                true,
                true,
                gridIndex,
                true,
                "GRID",
                object : ArrayOptionsPopupListener() {
                    private fun update() {
                        if (gridIndex == -1) return
                        val newGridValue = gridValues[gridIndex]
                        val sharedPreferences =
                            PreferenceManager.getDefaultSharedPreferences(mainActivity)
                        val editor = sharedPreferences.edit()
                        editor.putString(PreferenceKeys.SHOW_GRID_PREFERENCE_KEY, newGridValue)
                        editor.apply()
                        mainActivity.cameraViewModel.onEvent(CameraUiEvent.OnGridTypeChanged(com.hightechif.openkamera.domain.model.GridType.fromKey(newGridValue)))
                        mainActivity.applicationInterface.drawPreview
                            .updateSettings() // because we cache the grid
                    }

                    override fun onClickPrev(): Int {
                        if (gridIndex != -1) {
                            gridIndex--
                            if (gridIndex < 0) gridIndex += gridValues.size
                            update()
                            return gridIndex
                        }
                        return -1
                    }

                    override fun onClickNext(): Int {
                        if (gridIndex != -1) {
                            gridIndex++
                            if (gridIndex >= gridValues.size) gridIndex -= gridValues.size
                            update()
                            return gridIndex
                        }
                        return -1
                    }
                })
            if (MyDebug.LOG) Log.d(TAG, "PopupView time 13: " + (System.nanoTime() - debugTime))

            // white balance modes, scene modes, color effects
            // all of these are only supported when not using extension mode
            // popup should only be opened if we have a camera controller, but check just to be safe
            if (preview.cameraController != null && !isCameraExtension) {
                val supportedWhiteBalances: List<String> = preview.supportedWhiteBalances
                var supportedWhiteBalancesEntries: MutableList<String>? = null
                if (supportedWhiteBalances != null) {
                    supportedWhiteBalancesEntries = ArrayList()
                    for (value in supportedWhiteBalances) {
                        val entry: String = mainActivity.mainUI.getEntryForWhiteBalance(value)
                        supportedWhiteBalancesEntries.add(entry)
                    }
                }
                addRadioOptionsToPopup(
                    sharedPreferences,
                    supportedWhiteBalancesEntries,
                    supportedWhiteBalances,
                    resources.getString(R.string.white_balance),
                    PreferenceKeys.WHITE_BALANCE_PREFERENCE_KEY,
                    CameraController.WHITE_BALANCE_DEFAULT,
                    null,
                    "TEST_WHITE_BALANCE",
                    object : RadioOptionsListener() {
                        override fun onClick(selectedValue: String) {
                            switchToWhiteBalance(selectedValue)
                        }
                    })
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "PopupView time 14: " + (System.nanoTime() - debugTime)
                )

                val supportedSceneModes: List<String> = preview.supportedSceneModes
                var supportedSceneModesEntries: MutableList<String>? = null
                if (supportedSceneModes.isNotEmpty()) {
                    supportedSceneModesEntries = ArrayList()
                    for (value in supportedSceneModes) {
                        val entry: String = mainActivity.mainUI.getEntryForSceneMode(value)
                        supportedSceneModesEntries.add(entry)
                    }
                }
                addRadioOptionsToPopup(
                    sharedPreferences,
                    supportedSceneModesEntries,
                    supportedSceneModes,
                    resources.getString(R.string.scene_mode),
                    PreferenceKeys.SCENE_MODE_PREFERENCE_KEY,
                    CameraController.SCENE_MODE_DEFAULT,
                    null,
                    "TEST_SCENE_MODE",
                    object : RadioOptionsListener() {
                        override fun onClick(selectedValue: String) {
                            if (preview.cameraController != null) {
                                if (preview.cameraController!!.sceneModeAffectsFunctionality()) {
                                    // need to call updateForSettings() and close the popup, as changing scene mode can change available camera features
                                    mainActivity.updateForSettings(
                                        true,
                                        resources.getString(R.string.scene_mode) + ": " + mainActivity.mainUI
                                            .getEntryForSceneMode(selectedValue)
                                    )
                                    mainActivity.closePopup()
                                } else {
                                    preview.cameraController!!.setSceneMode(selectedValue)
                                    // keep popup open
                                }
                            }
                        }
                    })
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "PopupView time 15: " + (System.nanoTime() - debugTime)
                )

                val supportedColorEffects: List<String> = preview.supportedColorEffects
                var supportedColorEffectsEntries: MutableList<String>? = null
                if (supportedColorEffects != null) {
                    supportedColorEffectsEntries = ArrayList()
                    for (value in supportedColorEffects) {
                        val entry: String = mainActivity.mainUI.getEntryForColorEffect(value)
                        supportedColorEffectsEntries.add(entry)
                    }
                }
                addRadioOptionsToPopup(
                    sharedPreferences,
                    supportedColorEffectsEntries,
                    supportedColorEffects,
                    resources.getString(R.string.color_effect),
                    PreferenceKeys.COLOR_EFFECT_PREFERENCE_KEY,
                    CameraController.COLOR_EFFECT_DEFAULT,
                    null,
                    "TEST_COLOR_EFFECT",
                    object : RadioOptionsListener() {
                        override fun onClick(selectedValue: String) {
                            if (preview.cameraController != null) {
                                preview.cameraController!!.setColorEffect(selectedValue)
                            }
                            // keep popup open
                        }
                    })
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "PopupView time 16: " + (System.nanoTime() - debugTime)
                )
            }
        }

        if (MyDebug.LOG) Log.d(TAG, "Overall PopupView time: " + (System.nanoTime() - debugTime))
    }

    val totalWidth: Int
        get() {
            val scale = resources.displayMetrics.density
            return (totalWidthDp * scale + 0.5f).toInt() // convert dps to pixels;
        }

    private fun changePhotoMode(
        photoModes: List<String>,
        photoModeValues: List<PhotoMode>,
        option: String
    ) {
        if (MyDebug.LOG) Log.d(TAG, "changePhotoMode: $option")

        val mainActivity: MainActivity = this.context as MainActivity
        var optionId = -1
        var i = 0
        while (i < photoModes.size && optionId == -1) {
            if (option == photoModes[i]) optionId = i
            i++
        }
        if (MyDebug.LOG) Log.d(TAG, "mode id: $optionId")
        if (optionId == -1) {
            if (MyDebug.LOG) Log.e(
                TAG,
                "unknown mode id: $optionId"
            )
        } else {
            val newPhotoMode: PhotoMode = photoModeValues[optionId]
            var toastMessage: String? = option
            when (newPhotoMode) {
                PhotoMode.Standard -> toastMessage =
                    resources.getString(R.string.photo_mode_standard_full)

                PhotoMode.ExpoBracketing -> toastMessage =
                    resources.getString(R.string.photo_mode_expo_bracketing_full)

                PhotoMode.FocusBracketing -> toastMessage =
                    resources.getString(R.string.photo_mode_focus_bracketing_full)

                PhotoMode.FastBurst -> toastMessage =
                    resources.getString(R.string.photo_mode_fast_burst_full)

                PhotoMode.NoiseReduction -> toastMessage =
                    resources.getString(R.string.photo_mode_noise_reduction_full)

                PhotoMode.Panorama -> toastMessage =
                    resources.getString(R.string.photo_mode_panorama_full)

                PhotoMode.X_Auto -> toastMessage =
                    resources.getString(R.string.photo_mode_x_auto_full)

                PhotoMode.X_HDR -> toastMessage =
                    resources.getString(R.string.photo_mode_x_hdr_full)

                PhotoMode.X_Night -> toastMessage =
                    resources.getString(R.string.photo_mode_x_night_full)

                PhotoMode.X_Bokeh -> toastMessage =
                    resources.getString(R.string.photo_mode_x_bokeh_full)

                PhotoMode.X_Beauty -> toastMessage =
                    resources.getString(R.string.photo_mode_x_beauty_full)

                else -> {}
            }
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
            sharedPreferences.edit {
                when (newPhotoMode) {
                    PhotoMode.Standard -> putString(
                        PreferenceKeys.PHOTO_MODE_PREFERENCE_KEY,
                        "preference_photo_mode_std"
                    )

                    PhotoMode.DRO -> putString(
                        PreferenceKeys.PHOTO_MODE_PREFERENCE_KEY,
                        "preference_photo_mode_dro"
                    )

                    PhotoMode.HDR -> putString(
                        PreferenceKeys.PHOTO_MODE_PREFERENCE_KEY,
                        "preference_photo_mode_hdr"
                    )

                    PhotoMode.ExpoBracketing -> putString(
                        PreferenceKeys.PHOTO_MODE_PREFERENCE_KEY,
                        "preference_photo_mode_expo_bracketing"
                    )

                    PhotoMode.FocusBracketing -> putString(
                        PreferenceKeys.PHOTO_MODE_PREFERENCE_KEY,
                        "preference_photo_mode_focus_bracketing"
                    )

                    PhotoMode.FastBurst -> putString(
                        PreferenceKeys.PHOTO_MODE_PREFERENCE_KEY,
                        "preference_photo_mode_fast_burst"
                    )

                    PhotoMode.NoiseReduction -> putString(
                        PreferenceKeys.PHOTO_MODE_PREFERENCE_KEY,
                        "preference_photo_mode_noise_reduction"
                    )

                    PhotoMode.Panorama -> putString(
                        PreferenceKeys.PHOTO_MODE_PREFERENCE_KEY,
                        "preference_photo_mode_panorama"
                    )

                    PhotoMode.X_Auto -> putString(
                        PreferenceKeys.PHOTO_MODE_PREFERENCE_KEY,
                        "preference_photo_mode_x_auto"
                    )

                    PhotoMode.X_HDR -> putString(
                        PreferenceKeys.PHOTO_MODE_PREFERENCE_KEY,
                        "preference_photo_mode_x_hdr"
                    )

                    PhotoMode.X_Night -> putString(
                        PreferenceKeys.PHOTO_MODE_PREFERENCE_KEY,
                        "preference_photo_mode_x_night"
                    )

                    PhotoMode.X_Bokeh -> putString(
                        PreferenceKeys.PHOTO_MODE_PREFERENCE_KEY,
                        "preference_photo_mode_x_bokeh"
                    )

                    PhotoMode.X_Beauty -> putString(
                        PreferenceKeys.PHOTO_MODE_PREFERENCE_KEY,
                        "preference_photo_mode_x_beauty"
                    )

                    else -> if (MyDebug.LOG) Log.e(
                        TAG,
                        "unknown new_photo_mode: $newPhotoMode"
                    )
                }
            }

            var doneDialog = false
            if (newPhotoMode == PhotoMode.HDR) {
                val doneHdrInfo = sharedPreferences.contains(PreferenceKeys.HDR_INFO_PREFERENCE_KEY)
                if (!doneHdrInfo) {
                    mainActivity.mainUI.showInfoDialog(
                        R.string.photo_mode_hdr,
                        R.string.hdr_info,
                        PreferenceKeys.HDR_INFO_PREFERENCE_KEY
                    )
                    doneDialog = true
                }
            } else if (newPhotoMode == PhotoMode.Panorama) {
                val donePanoramaInfo =
                    sharedPreferences.contains(PreferenceKeys.PANORAMA_INFO_PREFERENCE_KEY)
                if (!donePanoramaInfo) {
                    mainActivity.mainUI.showInfoDialog(
                        R.string.photo_mode_panorama_full,
                        R.string.panorama_info,
                        PreferenceKeys.PANORAMA_INFO_PREFERENCE_KEY
                    )
                    doneDialog = true
                }
            }

            if (doneDialog) {
                // no need to show toast
                toastMessage = null
            }

            mainActivity.applicationInterface.drawPreview
                .updateSettings() // because we cache the photomode
            mainActivity.updateForSettings(
                true,
                toastMessage,
                false,
                true
            ) // need to setup the camera again, as options may change (e.g., required burst mode, or whether RAW is allowed in this mode)
            mainActivity.mainUI.destroyPopup() // need to recreate popup for new selection
        }
    }

    fun switchToWhiteBalance(selectedValue: String) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "switchToWhiteBalance: $selectedValue"
        )
        val mainActivity: MainActivity = this.context as MainActivity
        val preview: Preview = mainActivity.preview
        var closePopup = false
        var temperature = -1
        if (selectedValue == "manual") {
            if (preview.cameraController != null) {
                val currentWhiteBalance: String? = preview.cameraController!!.whiteBalance
                if (currentWhiteBalance == null || currentWhiteBalance != "manual") {
                    // try to choose a default manual white balance temperature as close as possible to the current auto
                    if (MyDebug.LOG) Log.d(TAG, "changed to manual white balance")
                    closePopup = true
                    if (preview.cameraController!!.captureResultHasWhiteBalanceTemperature()) {
                        temperature =
                            preview.cameraController!!.captureResultWhiteBalanceTemperature()
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "default to manual white balance temperature: $temperature"
                        )
                        val sharedPreferences =
                            PreferenceManager.getDefaultSharedPreferences(mainActivity)
                        val editor = sharedPreferences.edit()
                        editor.putInt(
                            PreferenceKeys.WHITE_BALANCE_TEMPERATURE_PREFERENCE_KEY,
                            temperature
                        )
                        editor.apply()
                    }

                    // otherwise default to the saved value
                    if (!mainActivity.mainUI.isExposureUIOpen) {
                        // also open the exposure UI, to show the
                        mainActivity.mainUI.toggleExposureUI()
                    }
                }
            }
        }

        if (preview.cameraController != null) {
            preview.cameraController!!.setWhiteBalance(selectedValue)
            if (temperature > 0) {
                preview.cameraController!!.setWhiteBalanceTemperature(temperature)
                // also need to update the slider!
                mainActivity.setManualWBSeekbar()
            }
        }
        // keep popup open, unless switching to manual
        if (closePopup) {
            mainActivity.closePopup()
        }
        //main_activity.updateForSettings(getResources().getString(R.string.white_balance) + ": " + selectedValue);
        //main_activity.closePopup();
    }

    abstract class ButtonOptionsPopupListener {
        abstract fun onClick(option: String)
    }

    private fun addCheckBox(
        context: Context,
        scale: Float,
        text: CharSequence,
        checked: Boolean,
        listener: CompoundButton.OnCheckedChangeListener
    ) {
        @SuppressLint("InflateParams") val switchView: View =
            LayoutInflater.from(context).inflate(R.layout.popupview_switch, null)
        val checkBox = switchView.findViewById<SwitchCompat>(R.id.popupview_switch)
        checkBox.text = text
        run {
            // align the checkbox a bit better
            checkBox.gravity = Gravity.RIGHT
            val params = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
            )
            val rightPadding = (20 * scale + 0.5f).toInt() // convert dps to pixels
            params.setMargins(0, 0, rightPadding, 0)
            checkBox.layoutParams = params
        }
        if (checked) checkBox.isChecked = checked
        checkBox.setOnCheckedChangeListener(listener)
        this.addView(checkBox)
    }

    /** Creates UI for selecting an option for multiple possibilites, by placing buttons in one or
     * more rows.
     * @param maxButtonsPerRow If 0, then all buttons will be placed on the same row. Otherwise,
     * this is the number of buttons per row, multiple rows will be
     * created if necessary.
     */
    private fun addButtonOptionsToPopup(
        supportedOptions: List<String>?,
        iconsId: Int,
        valuesId: Int,
        prefixString: String,
        currentValue: String?,
        maxButtonsPerRow: Int,
        testKey: String,
        listener: ButtonOptionsPopupListener
    ) {
        if (MyDebug.LOG) Log.d(TAG, "addButtonOptionsToPopup")
        val mainActivity: MainActivity = this.context as MainActivity
        createButtonOptions(
            this,
            this.context,
            totalWidthDp,
            mainActivity.mainUI.testUIButtonsMap,
            supportedOptions,
            iconsId,
            valuesId,
            prefixString,
            true,
            currentValue,
            maxButtonsPerRow,
            testKey,
            listener
        )
    }

    private fun addTitleToPopup(title: String) {
        val debugTime = System.nanoTime()

        @SuppressLint("InflateParams") val view: View =
            LayoutInflater.from(this.context).inflate(R.layout.popupview_textview, null)
        val textView = view.findViewById<TextView>(R.id.text_view)

        textView.text = "$title:"
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, titleTextSizeDip)
        textView.setTypeface(null, Typeface.BOLD)
        //text_view.setBackgroundColor(Color.GRAY); // debug
        this.addView(textView)
        if (MyDebug.LOG) Log.d(TAG, "addTitleToPopup time: " + (System.nanoTime() - debugTime))
    }

    private abstract class RadioOptionsListener {
        /** Called when a radio option is selected.
         * @param selectedValue The entry in the supplied supportedOptionsValues list (received
         * by addRadioOptionsToPopup) that corresponds to the selected radio
         * option.
         */
        abstract fun onClick(selectedValue: String)
    }

    /** Adds a set of radio options to the popup menu.
     * @param sharedPreferences         The SharedPreferences.
     * @param supportedOptionsEntries The strings to display on the radio options.
     * @param supportedOptionsValues  A corresponding array of values. These aren't shown to the
     * user, but are the values that will be set in the
     * sharedPreferences, and passed to the listener.
     * @param title                     The text to display as a title for this radio group.
     * @param preferenceKey            The preference key to use for the values in the
     * sharedPreferences. May be null, in which case it's up to
     * the user to save the new preference via a listener.
     * @param defaultValue             The default value for the preferenceKey in the
     * sharedPreferences. Only needed if preferenceKey is
     * non-null.
     * @param currentOptionValue      If preferenceKey is null, this should be the currently
     * selected value. Otherwise, this is ignored.
     * @param testKey                  Used for testing, a tag to identify the RadioGroup that's
     * created.
     * @param listener                  If null, selecting an option will call
     * MainActivity.updateForSettings() and close the popup. If
     * not null, instead selecting an option will call the
     * listener.
     */
    private fun addRadioOptionsToPopup(
        sharedPreferences: SharedPreferences,
        supportedOptionsEntries: List<String>?,
        supportedOptionsValues: List<String>,
        title: String,
        preferenceKey: String?,
        defaultValue: String?,
        currentOptionValue: String?,
        testKey: String,
        listener: RadioOptionsListener
    ) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "addRadioOptionsToPopup: $title"
        )
        if (supportedOptionsEntries != null) {
            val mainActivity: MainActivity = this.context as MainActivity
            val debugTime = System.nanoTime()

            @SuppressLint("InflateParams") val buttonView: View = LayoutInflater.from(
                this.context
            ).inflate(R.layout.popupview_button, null)
            val button = buttonView.findViewById<Button>(R.id.button)

            button.setBackgroundColor(Color.TRANSPARENT) // workaround for Android 6 crash!
            button.text = "$title..."
            button.isAllCaps = false
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, titleTextSizeDip)
            this.addView(button)
            if (MyDebug.LOG) Log.d(
                TAG,
                "addRadioOptionsToPopup time 1: " + (System.nanoTime() - debugTime)
            )

            val rg = RadioGroup(this.context)
            rg.orientation = VERTICAL
            rg.visibility = GONE
            mainActivity.mainUI.testUIButtonsMap.put(testKey, rg)
            if (MyDebug.LOG) Log.d(
                TAG,
                "addRadioOptionsToPopup time 2: " + (System.nanoTime() - debugTime)
            )

            button.setOnClickListener(object : OnClickListener {
                private var opened = false
                private var created = false

                override fun onClick(view: View) {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "clicked to open radio buttons menu: $title"
                    )
                    if (opened) {
                        //rg.removeAllViews();
                        rg.visibility = GONE
                        val popupContainer: ScrollView =
                            mainActivity.findViewById(R.id.popup_container)
                        // need to invalidate/requestLayout so that the scrollview's scroll positions update - otherwise scrollBy below doesn't work properly, when the user reopens the radio buttons
                        popupContainer.invalidate()
                        popupContainer.requestLayout()
                    } else {
                        if (!created) {
                            addRadioOptionsToGroup(
                                rg,
                                sharedPreferences,
                                supportedOptionsEntries,
                                supportedOptionsValues,
                                title,
                                preferenceKey,
                                defaultValue,
                                currentOptionValue,
                                testKey,
                                listener
                            )
                            created = true
                        }
                        rg.visibility = VISIBLE
                        val popupContainer: ScrollView =
                            mainActivity.findViewById(R.id.popup_container)
                        popupContainer.viewTreeObserver.addOnGlobalLayoutListener(
                            object : OnGlobalLayoutListener {
                                override fun onGlobalLayout() {
                                    if (MyDebug.LOG) Log.d(TAG, "onGlobalLayout()")
                                    // stop listening - only want to call this once!
                                    popupContainer.viewTreeObserver.removeOnGlobalLayoutListener(
                                        this
                                    )

                                    // so that the user sees the options appear, if the button is at the bottom of the current scrollview position
                                    if (rg.childCount > 0) {
                                        val id = rg.checkedRadioButtonId
                                        if (id >= 0 && id < rg.childCount) {
                                            popupContainer.smoothScrollBy(
                                                0,
                                                rg.getChildAt(id).bottom
                                            )
                                        }
                                    }
                                }
                            }
                        )
                    }
                    opened = !opened
                }
            })

            this.addView(rg)
            if (MyDebug.LOG) Log.d(
                TAG,
                "addRadioOptionsToPopup time 5: " + (System.nanoTime() - debugTime)
            )
        }
    }

    private fun addRadioOptionsToGroup(
        rg: RadioGroup,
        sharedPreferences: SharedPreferences,
        supportedOptionsEntries: List<String>,
        supportedOptionsValues: List<String>,
        title: String,
        preferenceKey: String?,
        defaultValue: String?,
        currentOptionValue: String?,
        testKey: String,
        listener: RadioOptionsListener?
    ) {
        var currentOptionValue = currentOptionValue
        if (MyDebug.LOG) Log.d(
            TAG,
            "addRadioOptionsToGroup: $title"
        )
        if (preferenceKey != null) currentOptionValue =
            sharedPreferences.getString(preferenceKey, defaultValue)
        val debugTime = System.nanoTime()
        val mainActivity: MainActivity = this.context as MainActivity
        var count = 0
        for (i in supportedOptionsEntries.indices) {
            val supportedOptionEntry = supportedOptionsEntries[i]
            val supportedOptionValue = supportedOptionsValues[i]
            if (MyDebug.LOG) {
                Log.d(
                    TAG,
                    "supported_option_entry: $supportedOptionEntry"
                )
                Log.d(
                    TAG,
                    "supported_option_value: $supportedOptionValue"
                )
            }
            if (MyDebug.LOG) Log.d(
                TAG,
                "addRadioOptionsToGroup time 1: " + (System.nanoTime() - debugTime)
            )

            // Inflating from XML made opening the radio button sub-menus much slower on old devices (e.g., Galaxy Nexus),
            // however testing showed this is also just as slow if we programmatically create a new AppCompatRadioButton().
            // I.e., the slowdown is due to using AppCompatRadioButton (which AppCompat will automatically use if creating
            // a RadioButton from XML) rather than inflating from XML.
            // Whilst creating a new RadioButton() was faster, we can't do that anymore due to emoji policy!
            @SuppressLint("InflateParams") val view: View =
                LayoutInflater.from(this.context).inflate(R.layout.popupview_radiobutton, null)
            val button = view.findViewById<RadioButton>(R.id.popupview_radiobutton)

            if (MyDebug.LOG) Log.d(
                TAG,
                "addRadioOptionsToGroup time 2: " + (System.nanoTime() - debugTime)
            )

            button.id = count

            button.text = supportedOptionEntry
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, standardTextSizeDip)
            button.setTextColor(Color.WHITE)
            if (MyDebug.LOG) Log.d(
                TAG,
                "addRadioOptionsToGroup time 3: " + (System.nanoTime() - debugTime)
            )
            if (MyDebug.LOG) Log.d(
                TAG,
                "addRadioOptionsToGroup time 4: " + (System.nanoTime() - debugTime)
            )
            rg.addView(button)
            if (MyDebug.LOG) Log.d(
                TAG,
                "addRadioOptionsToGroup time 5: " + (System.nanoTime() - debugTime)
            )

            if (supportedOptionValue == currentOptionValue) {
                //button.setChecked(true);
                rg.check(count)
            }
            count++

            button.contentDescription = supportedOptionEntry
            if (MyDebug.LOG) Log.d(
                TAG,
                "addRadioOptionsToGroup time 6: " + (System.nanoTime() - debugTime)
            )
            button.setOnClickListener {
                if (MyDebug.LOG) {
                    Log.d(
                        TAG,
                        "clicked current_option entry: $supportedOptionEntry"
                    )
                    Log.d(
                        TAG,
                        "clicked current_option entry: $supportedOptionValue"
                    )
                }
                if (preferenceKey != null) {
                    val sharedPreferences =
                        PreferenceManager.getDefaultSharedPreferences(mainActivity)
                    val editor = sharedPreferences.edit()
                    editor.putString(preferenceKey, supportedOptionValue)
                    editor.apply()
                }
                if (listener != null) {
                    listener.onClick(supportedOptionValue)
                } else {
                    mainActivity.updateForSettings(true, "$title: $supportedOptionEntry")
                    mainActivity.closePopup()
                }
            }
            if (MyDebug.LOG) Log.d(
                TAG,
                "addRadioOptionsToGroup time 7: " + (System.nanoTime() - debugTime)
            )
            mainActivity.mainUI.testUIButtonsMap
                .put(testKey + "_" + supportedOptionValue, button)
            if (MyDebug.LOG) Log.d(
                TAG,
                "addRadioOptionsToGroup time 8: " + (System.nanoTime() - debugTime)
            )
        }
        if (MyDebug.LOG) Log.d(
            TAG,
            "addRadioOptionsToGroup time total: " + (System.nanoTime() - debugTime)
        )
    }

    private abstract class ArrayOptionsPopupListener {
        abstract fun onClickPrev(): Int
        abstract fun onClickNext(): Int
    }

    private fun setArrayOptionsText(
        supportedOptions: List<String>,
        title: String,
        textView: TextView,
        titleInOptions: Boolean,
        titleInOptionsFirstOnly: Boolean,
        currentIndex: Int
    ) {
        if (titleInOptions && !(currentIndex != 0 && titleInOptionsFirstOnly)) textView.text =
            title + ": " + supportedOptions[currentIndex]
        else textView.text = supportedOptions[currentIndex]
    }

    /** Adds a set of options to the popup menu, where there user can select one option out of an array of values, using previous or
     * next buttons to switch between them.
     * @param supportedOptions The strings for the array of values to choose from.
     * @param title Title to display.
     * @param titleInOptions Prepend the title to each of the values, rather than above the values.
     * @param titleInOptionsFirstOnly If titleInOptions is true, only prepend to the first option.
     * @param currentIndex Index in the supportedOptions array of the currently selected option.
     * @param cyclic Whether the user can cycle beyond the start/end, to wrap around.
     * @param testKey Used to keep track of the UI elements created, for testing.
     * @param listener Listener called when previous/next buttons are clicked (and hence the option
     * changed).
     */
    private fun addArrayOptionsToPopup(
        supportedOptions: List<String>?,
        title: String,
        titleInOptions: Boolean,
        titleInOptionsFirstOnly: Boolean,
        currentIndex: Int,
        cyclic: Boolean,
        testKey: String,
        listener: ArrayOptionsPopupListener
    ) {
        if (supportedOptions != null && currentIndex != -1) {
            if (!titleInOptions) {
                addTitleToPopup(title)
            }

            val mainActivity: MainActivity = this.context as MainActivity

            val debugTime = System.nanoTime()

            @SuppressLint("InflateParams") val ll2: View =
                LayoutInflater.from(this.context).inflate(R.layout.popupview_arrayoptions, null)
            val textView = ll2.findViewById<TextView>(R.id.text_view)
            val prevButton = ll2.findViewById<Button>(R.id.button_left)
            val nextButton = ll2.findViewById<Button>(R.id.button_right)

            setArrayOptionsText(
                supportedOptions,
                title,
                textView,
                titleInOptions,
                titleInOptionsFirstOnly,
                currentIndex
            )
            //text_view.setBackgroundColor(Color.GRAY); // debug
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, standardTextSizeDip)
            textView.isSingleLine =
                true // if text too long for the button, we'd rather not have wordwrap, even if it means cutting some text off
            val params = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, 1.0f)
            // Yuck! We want the arrowButtonW to be fairly large so that users can touch the arrow buttons easily, but if
            // the text is too much for the button size, we'd rather it extend into the arrow buttons (which the user won't see
            // anyway, since the button backgrounds are transparent).
            // Needed for OnePlus 3T and Nokia 8, for camera resolution
            params.setMargins(-arrowButtonW / 2, 0, -arrowButtonW / 2, 0)
            textView.layoutParams = params

            val scale = resources.displayMetrics.density
            val padding = (0 * scale + 0.5f).toInt() // convert dps to pixels
            prevButton.setBackgroundColor(Color.TRANSPARENT) // workaround for Android 6 crash!
            //ll2.addView(prevButton);
            prevButton.text = "<"
            prevButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, arrowTextSizeDip)
            prevButton.setTypeface(null, Typeface.BOLD)
            prevButton.setPadding(padding, padding, padding, padding)
            var vgParams = prevButton.layoutParams
            vgParams.width = arrowButtonW
            vgParams.height = arrowButtonH
            prevButton.layoutParams = vgParams
            prevButton.visibility = if (cyclic || currentIndex > 0) VISIBLE else INVISIBLE
            prevButton.contentDescription = resources.getString(R.string.previous) + " " + title
            mainActivity.mainUI.testUIButtonsMap.put(testKey + "_PREV", prevButton)

            //ll2.addView(textView);
            mainActivity.mainUI.testUIButtonsMap.put(testKey, textView)

            nextButton.setBackgroundColor(Color.TRANSPARENT) // workaround for Android 6 crash!
            //ll2.addView(nextButton);
            nextButton.text = ">"
            nextButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, arrowTextSizeDip)
            nextButton.setTypeface(null, Typeface.BOLD)
            nextButton.setPadding(padding, padding, padding, padding)
            vgParams = nextButton.layoutParams
            vgParams.width = arrowButtonW
            vgParams.height = arrowButtonH
            nextButton.layoutParams = vgParams
            nextButton.visibility =
                if (cyclic || currentIndex < supportedOptions.size - 1) VISIBLE else INVISIBLE
            nextButton.contentDescription = resources.getString(R.string.next) + " " + title
            mainActivity.mainUI.testUIButtonsMap.put(testKey + "_NEXT", nextButton)

            // test:
            /*prev_button.setText(prev_button.getContentDescription());
			prev_button.setAllCaps(false);
			next_button.setText(next_button.getContentDescription());
			next_button.setAllCaps(false);*/
            prevButton.setOnClickListener {
                val newIndex = listener.onClickPrev()
                if (newIndex != -1) {
                    setArrayOptionsText(
                        supportedOptions,
                        title,
                        textView,
                        titleInOptions,
                        titleInOptionsFirstOnly,
                        newIndex
                    )
                    prevButton.visibility =
                        if (cyclic || newIndex > 0) VISIBLE else INVISIBLE
                    nextButton.visibility =
                        if (cyclic || newIndex < supportedOptions.size - 1) VISIBLE else INVISIBLE
                }
            }
            nextButton.setOnClickListener {
                val newIndex = listener.onClickNext()
                if (newIndex != -1) {
                    setArrayOptionsText(
                        supportedOptions,
                        title,
                        textView,
                        titleInOptions,
                        titleInOptionsFirstOnly,
                        newIndex
                    )
                    prevButton.visibility =
                        if (cyclic || newIndex > 0) VISIBLE else INVISIBLE
                    nextButton.visibility =
                        if (cyclic || newIndex < supportedOptions.size - 1) VISIBLE else INVISIBLE
                }
            }

            this.addView(ll2)

            if (MyDebug.LOG) Log.d(
                TAG,
                "addArrayOptionsToPopup time: " + (System.nanoTime() - debugTime)
            )
        }
    }

    companion object {
        private const val TAG = "PopupView"
        const val ALPHA_BUTTON_SELECTED: Float = 1.0f
        const val ALPHA_BUTTON: Float = 0.54f // 0.36f tends to be hard to see in bright light

        private const val buttonTextSizeDip = 12.0f
        private const val titleTextSizeDip = 17.0f
        private const val standardTextSizeDip = 16.0f
        private const val arrowTextSizeDip = 16.0f
        private const val arrowButtonWDp = 60.0f
        private const val arrowButtonHDp =
            48.0f // should be at least 48.0 (Google Play's prelaunch warnings)

        fun getButtonOptionString(
            includePrefix: Boolean,
            prefixString: String,
            supportedOption: String
        ): String {
            return (if (includePrefix) prefixString + "\n" else "") + supportedOption
        }

        fun createButtonOptions(
            parent: ViewGroup,
            context: Context,
            totalWidthDp: Int,
            testUiButtons: MutableMap<String, View>?,
            supportedOptions: List<String>?,
            iconsId: Int,
            valuesId: Int,
            prefixString: String,
            includePrefix: Boolean,
            currentValue: String?,
            maxButtonsPerRow: Int,
            testKey: String,
            listener: ButtonOptionsPopupListener
        ): List<View> {
            if (MyDebug.LOG) Log.d(TAG, "createButtonOptions")
            val buttons: MutableList<View> = ArrayList()
            if (supportedOptions != null) {
                val debugTime = System.nanoTime()
                var ll2 = LinearLayout(context)
                ll2.orientation = HORIZONTAL
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "addButtonOptionsToPopup time 1: " + (System.nanoTime() - debugTime)
                )
                val icons = if (iconsId != -1) context.resources.obtainTypedArray(iconsId) else null
                val values =
                    if (valuesId != -1) context.resources.getStringArray(valuesId) else null
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "addButtonOptionsToPopup time 2: " + (System.nanoTime() - debugTime)
                )

                val scale = context.resources.displayMetrics.density
                val scaleFont = context.resources.displayMetrics.scaledDensity
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "addButtonOptionsToPopup time 2.04: " + (System.nanoTime() - debugTime)
                )
                var actualMaxPerRow = supportedOptions.size
                if (maxButtonsPerRow > 0) actualMaxPerRow =
                    min(actualMaxPerRow.toDouble(), maxButtonsPerRow.toDouble()).toInt()
                var buttonWidthDp = totalWidthDp / actualMaxPerRow
                var useScrollview = false
                val minButtonWidthDp =
                    48 // needs to be at least 48dp to avoid Google Play pre-launch accessibility report warnings
                if (buttonWidthDp < minButtonWidthDp && maxButtonsPerRow == 0) {
                    buttonWidthDp = minButtonWidthDp
                    useScrollview = true
                }
                var buttonWidth = (buttonWidthDp * scale + 0.5f).toInt() // convert dps to pixels
                if (MyDebug.LOG) {
                    Log.d(
                        TAG,
                        "actual_max_per_row: $actualMaxPerRow"
                    )
                    Log.d(TAG, "button_width_dp: $buttonWidthDp")
                    Log.d(TAG, "button_width: $buttonWidth")
                    Log.d(TAG, "use_scrollview: $useScrollview")
                }

                val onClickListener =
                    OnClickListener { v ->
                        val supportedOption = v.tag as String
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "clicked: $supportedOption"
                        )
                        listener.onClick(supportedOption)
                    }
                var currentView: View? = null
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "addButtonOptionsToPopup time 2.05: " + (System.nanoTime() - debugTime)
                )

                for (buttonIndx in supportedOptions.indices) {
                    val supportedOption = supportedOptions[buttonIndx]
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "addButtonOptionsToPopup time 2.06: " + (System.nanoTime() - debugTime)
                    )
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "button_indx = $buttonIndx"
                    )

                    if (maxButtonsPerRow > 0 && buttonIndx > 0 && buttonIndx % maxButtonsPerRow == 0) {
                        if (MyDebug.LOG) Log.d(TAG, "start a new row")
                        // add the previous row
                        // no need to handle useScrollview, as we don't support scrollviews with multiple rows
                        parent.addView(ll2)
                        ll2 = LinearLayout(context)
                        ll2.orientation = HORIZONTAL

                        val nRemaining = supportedOptions.size - buttonIndx
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "n_remaining: $nRemaining"
                        )
                        if (nRemaining <= maxButtonsPerRow) {
                            if (MyDebug.LOG) Log.d(TAG, "final row")
                            buttonWidthDp = totalWidthDp / nRemaining
                            buttonWidth =
                                (buttonWidthDp * scale + 0.5f).toInt() // convert dps to pixels
                        }
                    }

                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "supported_option: $supportedOption"
                    )
                    var resource = -1
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "addButtonOptionsToPopup time 2.08: " + (System.nanoTime() - debugTime)
                    )
                    if (icons != null && values != null) {
                        var index = -1
                        var i = 0
                        while (i < values.size && index == -1) {
                            if (values[i] == supportedOption) index = i
                            i++
                        }
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "index: $index"
                        )
                        if (index != -1) {
                            resource = icons.getResourceId(index, 0)
                        }
                    }
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "addButtonOptionsToPopup time 2.1: " + (System.nanoTime() - debugTime)
                    )
                    // hacks for ISO mode ISO_HJR (e.g., on Samsung S5)
                    // also some devices report e.g. "ISO100" etc
                    val buttonString = if (prefixString.length == 0) {
                        supportedOption
                    } else if (prefixString.equals(
                            "ISO",
                            ignoreCase = true
                        ) && supportedOption.length >= 4 && supportedOption.substring(0, 4)
                            .equals("ISO_", ignoreCase = true)
                    ) {
                        getButtonOptionString(
                            includePrefix,
                            prefixString,
                            supportedOption.substring(4)
                        )
                    } else if (prefixString.equals(
                            "ISO",
                            ignoreCase = true
                        ) && supportedOption.length >= 3 && supportedOption.substring(0, 3)
                            .equals("ISO", ignoreCase = true)
                    ) {
                        getButtonOptionString(
                            includePrefix,
                            prefixString,
                            supportedOption.substring(3)
                        )
                    } else {
                        getButtonOptionString(includePrefix, prefixString, supportedOption)
                    }
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "button_string: $buttonString"
                    )
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "addButtonOptionsToPopup time 2.105: " + (System.nanoTime() - debugTime)
                    )
                    val view: View
                    if (resource != -1) {
                        val imageButton = ImageButton(context)
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "addButtonOptionsToPopup time 2.11: " + (System.nanoTime() - debugTime)
                        )
                        view = imageButton
                        buttons.add(view)
                        ll2.addView(view)
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "addButtonOptionsToPopup time 2.12: " + (System.nanoTime() - debugTime)
                        )

                        //image_button.setImageResource(resource);
                        val mainActivity: MainActivity = context as MainActivity
                        val bm: Bitmap? = mainActivity.getPreloadedBitmap(resource)
                        if (bm != null) imageButton.setImageBitmap(bm)
                        else {
                            if (MyDebug.LOG) Log.d(
                                TAG,
                                "failed to find bitmap for resource $resource!"
                            )
                        }
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "addButtonOptionsToPopup time 2.13: " + (System.nanoTime() - debugTime)
                        )
                        imageButton.scaleType = ScaleType.FIT_CENTER
                        imageButton.setBackgroundColor(Color.TRANSPARENT)
                        val padding = (10 * scale + 0.5f).toInt() // convert dps to pixels
                        view.setPadding(padding, padding, padding, padding)
                    } else {
                        @SuppressLint("InflateParams") val buttonView: View =
                            LayoutInflater.from(context).inflate(R.layout.popupview_button, null)
                        val button = buttonView.findViewById<Button>(R.id.button)

                        button.setBackgroundColor(Color.TRANSPARENT) // workaround for Android 6 crash! Also looks nicer anyway...
                        view = button
                        buttons.add(view)
                        ll2.addView(view)

                        button.text = buttonString
                        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, buttonTextSizeDip)
                        button.setTextColor(Color.WHITE)
                        // need 0 padding so we have enough room to display text for ISO buttons, when there are 6 ISO settings
                        val padding = (0 * scale + 0.5f).toInt() // convert dps to pixels
                        view.setPadding(padding, padding, padding, padding)
                    }
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "addButtonOptionsToPopup time 2.2: " + (System.nanoTime() - debugTime)
                    )

                    val params = view.layoutParams
                    params.width = buttonWidth
                    // be careful of making the height too smaller, as harder to touch buttons; remember that this also affects the
                    // ISO buttons on exposure panel, and not just the main popup!
                    params.height =
                        (55 * (if (resource != -1) scale else scaleFont) + 0.5f).toInt() // convert dps to pixels
                    view.layoutParams = params

                    view.contentDescription = buttonString
                    if (supportedOption == currentValue) {
                        setButtonSelected(view, true)
                        currentView = view
                    } else {
                        setButtonSelected(view, false)
                    }
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "addButtonOptionsToPopup time 2.3: " + (System.nanoTime() - debugTime)
                    )
                    view.tag = supportedOption
                    view.setOnClickListener(onClickListener)
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "addButtonOptionsToPopup time 2.35: " + (System.nanoTime() - debugTime)
                    )
                    if (testUiButtons != null) testUiButtons[testKey + "_" + supportedOption] =
                        view
                    if (MyDebug.LOG) {
                        Log.d(
                            TAG,
                            "addButtonOptionsToPopup time 2.4: " + (System.nanoTime() - debugTime)
                        )
                        Log.d(
                            TAG,
                            "added to popup_buttons: " + testKey + "_" + supportedOption + " view: " + view
                        )
                        if (testUiButtons != null) Log.d(
                            TAG,
                            "test_ui_buttons is now: $testUiButtons"
                        )
                    }
                }
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "addButtonOptionsToPopup time 3: " + (System.nanoTime() - debugTime)
                )
                if (useScrollview) {
                    if (MyDebug.LOG) Log.d(TAG, "using scrollview")
                    val totalWidth =
                        (totalWidthDp * scale + 0.5f).toInt() // convert dps to pixels;
                    val scroll = HorizontalScrollView(context)
                    scroll.addView(ll2)
                    run {
                        val params: ViewGroup.LayoutParams = LayoutParams(
                            totalWidth,
                            LayoutParams.WRAP_CONTENT
                        )
                        scroll.layoutParams = params
                    }
                    parent.addView(scroll)
                    if (currentView != null) {
                        // scroll to the selected button
                        val finalCurrentView: View = currentView
                        val finalButtonWidth = buttonWidth
                        parent.viewTreeObserver.addOnGlobalLayoutListener { // scroll so selected button is centred
                            var jumpX =
                                finalCurrentView.left - (totalWidth - finalButtonWidth) / 2
                            // scrollTo should automatically clamp to the bounds of the view, but just in case
                            jumpX =
                                min(jumpX.toDouble(), (totalWidth - 1).toDouble())
                                    .toInt()
                            if (jumpX > 0) {
                                scroll.scrollTo(jumpX, 0)
                            }
                        }
                    }
                } else {
                    if (MyDebug.LOG) Log.d(TAG, "not using scrollview")
                    parent.addView(ll2)
                }
                icons?.recycle()
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "addButtonOptionsToPopup time 4: " + (System.nanoTime() - debugTime)
                )
            }
            return buttons
        }

        fun setButtonSelected(view: View, selected: Boolean) {
            view.alpha = if (selected) ALPHA_BUTTON_SELECTED else ALPHA_BUTTON
        }
    }
}

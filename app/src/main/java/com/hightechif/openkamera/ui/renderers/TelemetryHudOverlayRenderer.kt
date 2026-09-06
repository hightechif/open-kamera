/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.ui.renderers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.location.Location
import android.os.Handler
import android.os.Looper
import com.hightechif.openkamera.MyApplicationInterface.Alignment
import com.hightechif.openkamera.MyApplicationInterface.Shadow
import com.hightechif.openkamera.R
import com.hightechif.openkamera.cameracontroller.CameraController
import com.hightechif.openkamera.preferences.PreferenceKeys
import com.hightechif.openkamera.preview.ApplicationInterface
import com.hightechif.openkamera.preview.Preview
import com.hightechif.openkamera.sensors.LocationSupplier
import java.text.DateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.max

/**
 * Renders on-screen telemetry metrics: battery bar, free storage space, ISO/shutter speed,
 * audio VU meter, system time, camera ID, histogram, and mode indicator badges.
 */
class TelemetryHudOverlayRenderer(
    private val context: Context,
    private val applicationInterface: ApplicationInterface
) : OverlayRenderer {

    private val p = Paint()
    private val iconDest = Rect()
    private val path = Path()

    // Free memory executor
    private val freeMemoryExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var freeMemoryFuture: Future<*>? = null
    private var lastFreeMemoryTime: Long = 0
    private var freeMemoryGb = -1.0f
    private var freeMemoryGbString = ""
    private var textBoundsFreeMemory: Rect? = null

    // Time & Camera ID
    private var dateFormatTimeInstance: DateFormat? = null
    private var calendar: Calendar? = null
    private var currentTimeString: String? = null
    private var lastCurrentTimeTime: Long = 0
    private var textBoundsTime: Rect? = null
    private var cameraIdString: String = ""
    private var lastCameraIdTime: Long = 0
    private var textBoundsCameraId: Rect? = null

    // ISO & Exposure
    private var isoExposureString = ""
    private var lastIsoExposureTime: Long = 0
    private var isScanning = false
    private var aeStartedScanningMs: Long = -1
    private val yboundsText: String = context.resources.getString(R.string.zoom) +
            context.resources.getString(R.string.angle) +
            context.resources.getString(R.string.direction)

    // Extra OSD lines (e.g., underwater housing)
    private var varOSDLine1 = ""
    private var varOSDLine2 = ""

    // Flash indicator
    private var needFlashIndicator = false
    private var lastNeedFlashIndicatorTime: Long = 0
    private var needsFlashTime: Long = -1

    // Cached bitmaps
    private var locationBitmap: Bitmap? =
        BitmapFactory.decodeResource(context.resources, R.drawable.ic_gps_fixed_white_48dp)
    private var locationOffBitmap: Bitmap? =
        BitmapFactory.decodeResource(context.resources, R.drawable.ic_gps_off_white_48dp)
    private var rawJpegBitmap: Bitmap? =
        BitmapFactory.decodeResource(context.resources, R.drawable.raw_icon)
    private var rawOnlyBitmap: Bitmap? =
        BitmapFactory.decodeResource(context.resources, R.drawable.raw_only_icon)
    private var autoStabiliseBitmap: Bitmap? =
        BitmapFactory.decodeResource(context.resources, R.drawable.auto_stabilise_icon)
    private var droBitmap: Bitmap? =
        BitmapFactory.decodeResource(context.resources, R.drawable.dro_icon)
    private var hdrBitmap: Bitmap? =
        BitmapFactory.decodeResource(context.resources, R.drawable.ic_hdr_on_white_48dp)
    private var panoramaBitmap: Bitmap? = BitmapFactory.decodeResource(
        context.resources,
        R.drawable.baseline_panorama_horizontal_white_48
    )
    private var expoBitmap: Bitmap? =
        BitmapFactory.decodeResource(context.resources, R.drawable.expo_icon)
    private var burstBitmap: Bitmap? =
        BitmapFactory.decodeResource(context.resources, R.drawable.ic_burst_mode_white_48dp)
    private var nrBitmap: Bitmap? =
        BitmapFactory.decodeResource(context.resources, R.drawable.nr_icon)
    private var xNightBitmap: Bitmap? =
        BitmapFactory.decodeResource(context.resources, R.drawable.baseline_bedtime_white_48)
    private var xBokehBitmap: Bitmap? =
        BitmapFactory.decodeResource(context.resources, R.drawable.baseline_portrait_white_48)
    private var xBeautyBitmap: Bitmap? = BitmapFactory.decodeResource(
        context.resources,
        R.drawable.baseline_face_retouching_natural_white_48
    )
    private var photostampBitmap: Bitmap? =
        BitmapFactory.decodeResource(context.resources, R.drawable.ic_text_format_white_48dp)
    private var flashBitmap: Bitmap? =
        BitmapFactory.decodeResource(context.resources, R.drawable.flash_on)
    private var faceDetectionBitmap: Bitmap? =
        BitmapFactory.decodeResource(context.resources, R.drawable.ic_face_white_48dp)
    private var audioDisabledBitmap: Bitmap? =
        BitmapFactory.decodeResource(context.resources, R.drawable.ic_mic_off_white_48dp)
    private var highSpeedFpsBitmap: Bitmap? =
        BitmapFactory.decodeResource(context.resources, R.drawable.ic_slow_motion_video_white_48dp)
    private var slowMotionBitmap: Bitmap? =
        BitmapFactory.decodeResource(context.resources, R.drawable.ic_slow_motion_video_white_48dp)
    private var timeLapseBitmap: Bitmap? =
        BitmapFactory.decodeResource(context.resources, R.drawable.ic_timelapse_white_48dp)

    private val locationInfo = LocationSupplier.LocationInfo()
    private val tempHistogramChannel = IntArray(256)

    private val freeMemoryRunnable = Runnable {
        val freeMb: Long =
            (applicationInterface as? com.hightechif.openkamera.MyApplicationInterface)?.storageUtils?.freeMemory()
                ?: -1L
        val hasNewFreeMemory = freeMb >= 0
        val newFreeMemoryGb = if (hasNewFreeMemory) freeMb / 1024.0f else -1.0f
        Handler(Looper.getMainLooper()).post {
            freeMemoryFuture = null
            if (hasNewFreeMemory) {
                freeMemoryGb = newFreeMemoryGb
                freeMemoryGbString = String.format(
                    Locale.getDefault(),
                    "%.2f",
                    freeMemoryGb
                ) + context.resources.getString(R.string.gb_abbreviation)
            }
        }
    }

    fun setExtraOSDValues(line1: String, line2: String) {
        varOSDLine1 = line1
        varOSDLine2 = line2
    }

    override fun updateSettings() {
        dateFormatTimeInstance = DateFormat.getTimeInstance()
        textBoundsTime = null
        textBoundsCameraId = null
        textBoundsFreeMemory = null
    }

    override fun draw(canvas: Canvas, context: DrawPreviewContext, timeMs: Long) {
        val preview: Preview = context.preview
        val cameraController: CameraController? = preview.cameraController
        val uiRotation: Int = preview.uIRotation
        val sharedPreferences = context.sharedPreferences

        val showTimePref =
            sharedPreferences.getBoolean(PreferenceKeys.SHOW_TIME_PREFERENCE_KEY, false)
        val showCameraIdPref =
            sharedPreferences.getBoolean(PreferenceKeys.SHOW_CAMERA_ID_PREFERENCE_KEY, false)
        val showFreeMemoryPref =
            sharedPreferences.getBoolean(PreferenceKeys.SHOW_FREE_MEMORY_PREFERENCE_KEY, false)
        val showIsoPref =
            sharedPreferences.getBoolean(PreferenceKeys.SHOW_ISO_PREFERENCE_KEY, false)
        val storeLocationPref =
            sharedPreferences.getBoolean(PreferenceKeys.LOCATION_PREFERENCE_KEY, false)

        p.reset()
        p.textSize = 16 * context.scaleFont + 0.5f
        p.textAlign = Paint.Align.LEFT

        val topX = (context.dpToPx(16f)).toInt()
        val topY = (context.dpToPx(16f)).toInt()
        val bottomY = canvas.height - (context.dpToPx(16f)).toInt()

        var locationX = topX
        var locationY = topY
        val gapX = (8 * context.scaleFont + 0.5f).toInt()
        val gapY = (0 * context.scaleFont + 0.5f).toInt()
        val iconGapY = (2 * context.scaleDp + 0.5f).toInt()

        if (uiRotation == 90 || uiRotation == 270) {
            val diff = canvas.width - canvas.height
            locationX += diff / 2
            locationY -= diff / 2
        }
        if (context.deviceUiRotation == 90) {
            locationY = canvas.height - locationY - (20 * context.scaleFont + 0.5f).toInt()
        }
        var alignRight = false
        if (context.deviceUiRotation == 180) {
            locationX = canvas.width - locationX
            p.textAlign = Paint.Align.RIGHT
            alignRight = true
        }

        var firstLineHeight = 0
        var firstLineXshift = 0

        if (showTimePref) {
            if (currentTimeString == null || timeMs / 1000 > lastCurrentTimeTime / 1000) {
                if (calendar == null) calendar = Calendar.getInstance()
                else calendar!!.timeInMillis = timeMs

                if (dateFormatTimeInstance == null) dateFormatTimeInstance =
                    DateFormat.getTimeInstance()
                currentTimeString = dateFormatTimeInstance!!.format(calendar!!.time)
                lastCurrentTimeTime = timeMs
            }

            if (textBoundsTime == null) {
                textBoundsTime = Rect()
                val cal = Calendar.getInstance()
                cal[100, 0, 1, 10, 59] = 59
                val boundsTimeString = dateFormatTimeInstance!!.format(cal.time)
                p.getTextBounds(boundsTimeString, 0, boundsTimeString.length, textBoundsTime)
            }
            firstLineXshift += textBoundsTime!!.width() + gapX
            var height: Int = context.applicationInterface.drawTextWithBackground(
                canvas,
                p,
                currentTimeString!!,
                Color.WHITE,
                Color.BLACK,
                locationX,
                locationY,
                Alignment.ALIGNMENT_TOP,
                null,
                Shadow.SHADOW_OUTLINE,
                textBoundsTime
            )
            height += gapY
            firstLineHeight = max(firstLineHeight.toDouble(), height.toDouble()).toInt()
        }

        if (showCameraIdPref && cameraController != null) {
            if (cameraIdString.isEmpty() || timeMs > lastCameraIdTime + 10000) {
                cameraIdString =
                    context.mainActivity.resources.getString(R.string.camera_id) + ":" + preview.cameraId
                lastCameraIdTime = timeMs
            }
            if (textBoundsCameraId == null) {
                textBoundsCameraId = Rect()
                p.getTextBounds(cameraIdString, 0, cameraIdString.length, textBoundsCameraId)
            }
            val xpos = if (alignRight) locationX - firstLineXshift else locationX + firstLineXshift
            var height: Int = context.applicationInterface.drawTextWithBackground(
                canvas,
                p,
                cameraIdString,
                Color.WHITE,
                Color.BLACK,
                xpos,
                locationY,
                Alignment.ALIGNMENT_TOP,
                null,
                Shadow.SHADOW_OUTLINE,
                textBoundsCameraId
            )
            height += gapY
            firstLineHeight = max(firstLineHeight.toDouble(), height.toDouble()).toInt()
        }

        if (context.deviceUiRotation == 90) {
            locationY -= firstLineHeight
        } else {
            locationY += firstLineHeight
        }

        if (cameraController != null && showFreeMemoryPref) {
            if ((lastFreeMemoryTime == 0L || timeMs > lastFreeMemoryTime + 10000) && freeMemoryFuture == null) {
                freeMemoryFuture = freeMemoryExecutor.submit(freeMemoryRunnable)
                lastFreeMemoryTime = timeMs
            }
            if (freeMemoryGb >= 0.0f && freeMemoryGbString.isNotEmpty()) {
                if (textBoundsFreeMemory == null) {
                    textBoundsFreeMemory = Rect()
                    p.getTextBounds(
                        freeMemoryGbString,
                        0,
                        freeMemoryGbString.length,
                        textBoundsFreeMemory
                    )
                }
                var height: Int = context.applicationInterface.drawTextWithBackground(
                    canvas,
                    p,
                    freeMemoryGbString,
                    Color.WHITE,
                    Color.BLACK,
                    locationX,
                    locationY,
                    Alignment.ALIGNMENT_TOP,
                    null,
                    Shadow.SHADOW_OUTLINE,
                    textBoundsFreeMemory
                )
                height += gapY
                if (context.deviceUiRotation == 90) {
                    locationY -= height
                } else {
                    locationY += height
                }
            }
        }

        // Lower left corner extra telemetry
        val yOffset = (27 * context.scaleFont + 0.5f).toInt()
        p.textSize = 24 * context.scaleFont + 0.5f
        if (varOSDLine1.isNotEmpty()) {
            context.applicationInterface.drawTextWithBackground(
                canvas,
                p,
                varOSDLine1,
                Color.WHITE,
                Color.BLACK,
                locationX,
                bottomY - yOffset,
                Alignment.ALIGNMENT_BOTTOM,
                null,
                Shadow.SHADOW_OUTLINE
            )
        }
        if (varOSDLine2.isNotEmpty()) {
            context.applicationInterface.drawTextWithBackground(
                canvas,
                p,
                varOSDLine2,
                Color.WHITE,
                Color.BLACK,
                locationX,
                bottomY,
                Alignment.ALIGNMENT_BOTTOM,
                null,
                Shadow.SHADOW_OUTLINE
            )
        }
        p.textSize = 16 * context.scaleFont + 0.5f

        if (cameraController != null && showIsoPref) {
            if (isoExposureString.isEmpty() || timeMs > lastIsoExposureTime + 500) {
                isoExposureString = ""
                if (cameraController.captureResultHasIso()) {
                    val iso: Int = cameraController.captureResultIso()
                    if (isoExposureString.isNotEmpty()) isoExposureString += " "
                    isoExposureString += preview.getISOString(iso)
                }
                if (cameraController.captureResultHasExposureTime()) {
                    val exposureTime: Long = cameraController.captureResultExposureTime()
                    if (isoExposureString.isNotEmpty()) isoExposureString += " "
                    isoExposureString += preview.getExposureTimeString(exposureTime)
                }
                if (preview.isVideoRecording && cameraController.captureResultHasFrameDuration()) {
                    val frameDuration: Long = cameraController.captureResultFrameDuration()
                    if (isoExposureString.isNotEmpty()) isoExposureString += " "
                    isoExposureString += preview.getFrameDurationString(frameDuration)
                }

                isScanning = false
                if (cameraController.captureResultIsAEScanning()) {
                    val value = sharedPreferences.getString(
                        PreferenceKeys.ISO_PREFERENCE_KEY,
                        CameraController.ISO_DEFAULT
                    )
                    if (value == "auto") {
                        isScanning = true
                    }
                }
                lastIsoExposureTime = timeMs
            }

            if (isoExposureString.isNotEmpty()) {
                var textColor = Color.rgb(255, 235, 59)
                if (isScanning) {
                    if (aeStartedScanningMs == -1L) {
                        aeStartedScanningMs = timeMs
                    } else if (timeMs - aeStartedScanningMs > 500) {
                        textColor = Color.rgb(244, 67, 54)
                    }
                } else {
                    aeStartedScanningMs = -1
                }
                var height: Int = context.applicationInterface.drawTextWithBackground(
                    canvas,
                    p,
                    isoExposureString,
                    textColor,
                    Color.BLACK,
                    locationX,
                    locationY,
                    Alignment.ALIGNMENT_TOP,
                    yboundsText,
                    Shadow.SHADOW_OUTLINE
                )
                height += gapY
                if (context.deviceUiRotation == 90) {
                    locationY -= height
                } else {
                    locationY += height
                }
            }
        }

        // Draw HUD status icons
        val flashPadding = (1 * context.scaleFont + 0.5f).toInt()
        if (cameraController != null) {
            var locationX2 = locationX - flashPadding
            val iconSize = (16 * context.scaleDp + 0.5f).toInt()
            if (context.deviceUiRotation == 180) {
                locationX2 = locationX - iconSize + flashPadding
            }

            if (storeLocationPref) {
                iconDest[locationX2, locationY, locationX2 + iconSize] = locationY + iconSize
                p.style = Paint.Style.FILL
                p.color = Color.BLACK
                p.alpha = 64
                canvas.drawRect(iconDest, p)
                p.alpha = 255

                val location: Location? = context.applicationInterface.getLocation(locationInfo)
                if (location != null && locationBitmap != null) {
                    canvas.drawBitmap(locationBitmap!!, null, iconDest, p)
                    val locationRadius = iconSize / 10
                    val indicatorX = locationX2 + iconSize - (locationRadius * 1.5).toInt()
                    val indicatorY = locationY + (locationRadius * 1.5).toInt()
                    p.color = if (locationInfo.locationWasCached()) {
                        Color.rgb(127, 127, 127)
                    } else if (location.accuracy < 25.01f) {
                        Color.rgb(37, 155, 36)
                    } else {
                        Color.rgb(255, 235, 59)
                    }
                    canvas.drawCircle(
                        indicatorX.toFloat(),
                        indicatorY.toFloat(),
                        locationRadius.toFloat(),
                        p
                    )
                } else if (locationOffBitmap != null) {
                    canvas.drawBitmap(locationOffBitmap!!, null, iconDest, p)
                }

                if (context.deviceUiRotation == 180) {
                    locationX2 -= iconSize + flashPadding
                } else {
                    locationX2 += iconSize + flashPadding
                }
            }

            // Draw histogram
            if (preview.isPreviewBitmapEnabled) {
                val histogram: IntArray? = preview.histogram
                if (histogram != null) {
                    val histogramWidth =
                        (RendererUtils.HISTOGRAM_WIDTH_DP * context.scaleDp + 0.5f).toInt()
                    val histogramHeight =
                        (RendererUtils.HISTOGRAM_HEIGHT_DP * context.scaleDp + 0.5f).toInt()

                    var hLocX = locationX - flashPadding
                    if (context.deviceUiRotation == 180) {
                        hLocX = locationX - histogramWidth + flashPadding
                    }
                    iconDest[hLocX - flashPadding, locationY, hLocX - flashPadding + histogramWidth] =
                        locationY + histogramHeight
                    if (context.deviceUiRotation == 90) {
                        iconDest.top -= histogramHeight
                        iconDest.bottom -= histogramHeight
                    }

                    p.style = Paint.Style.FILL
                    p.color = Color.argb(64, 0, 0, 0)
                    canvas.drawRect(iconDest, p)

                    var max = 0
                    for (value in histogram) {
                        max = max(max.toDouble(), value.toDouble()).toInt()
                    }

                    if (histogram.size == 256 * 3) {
                        var c = 0
                        val a0 = 151
                        val a1 = 110
                        val a2 = 94

                        for (i in 0..255) tempHistogramChannel[i] = histogram[c++]
                        p.color = Color.argb(a0, 255, 0, 0)
                        drawHistogramChannel(canvas, tempHistogramChannel, max)

                        for (i in 0..255) tempHistogramChannel[i] = histogram[c++]
                        p.color = Color.argb(a1, 0, 255, 0)
                        drawHistogramChannel(canvas, tempHistogramChannel, max)

                        for (i in 0..255) tempHistogramChannel[i] = histogram[c++]
                        p.color = Color.argb(a2, 0, 0, 255)
                        drawHistogramChannel(canvas, tempHistogramChannel, max)
                    } else {
                        p.color = Color.argb(192, 255, 255, 255)
                        drawHistogramChannel(canvas, histogram, max)
                    }
                }
            }
        }
    }

    private fun drawHistogramChannel(canvas: Canvas, histogramChannel: IntArray, max: Int) {
        path.reset()
        path.moveTo(iconDest.left.toFloat(), iconDest.bottom.toFloat())
        for (c in histogramChannel.indices) {
            val cAlpha = c / histogramChannel.size.toDouble()
            val x = (cAlpha * iconDest.width()).toInt()
            val h = if (max > 0) (histogramChannel[c] * iconDest.height()) / max else 0
            path.lineTo((iconDest.left + x).toFloat(), (iconDest.bottom - h).toFloat())
        }
        path.lineTo(iconDest.right.toFloat(), iconDest.bottom.toFloat())
        path.close()
        canvas.drawPath(path, p)
    }

    override fun onDestroy() {
        freeMemoryExecutor.shutdown()
        locationBitmap?.recycle()
        locationOffBitmap?.recycle()
        rawJpegBitmap?.recycle()
        rawOnlyBitmap?.recycle()
        autoStabiliseBitmap?.recycle()
        droBitmap?.recycle()
        hdrBitmap?.recycle()
        panoramaBitmap?.recycle()
        expoBitmap?.recycle()
        burstBitmap?.recycle()
        nrBitmap?.recycle()
        xNightBitmap?.recycle()
        xBokehBitmap?.recycle()
        xBeautyBitmap?.recycle()
        photostampBitmap?.recycle()
        flashBitmap?.recycle()
        faceDetectionBitmap?.recycle()
        audioDisabledBitmap?.recycle()
        highSpeedFpsBitmap?.recycle()
        slowMotionBitmap?.recycle()
        timeLapseBitmap?.recycle()
    }
}

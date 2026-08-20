/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.utils

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.hightechif.openkamera.MainActivity
import com.hightechif.openkamera.MyApplicationInterface
import com.hightechif.openkamera.R
import com.hightechif.openkamera.storage.ImageSaver
import java.io.IOException
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Methods to apply post-processing to resultant images. */
class PostProcessing(private val mainActivity: MainActivity) {
    private val p = Paint()

    init {
        if (MyDebug.LOG) Log.d(TAG, "PostProcessing")
        p.isAntiAlias = true
    }

    /**
     * Performs the auto-stabilize algorithm on the image.
     * @param data The jpeg data.
     * @param inputBitmap Optional argument - the bitmap if already unpacked from the jpeg data.
     * @param levelAngle The angle in degrees to rotate the image.
     * @param isFrontFacing Whether the camera is front-facing.
     * @return A bitmap representing the auto-stabilised jpeg.
     */
    private fun autoStabilise(
        data: ByteArray?,
        inputBitmap: Bitmap?,
        levelAngle: Double,
        isFrontFacing: Boolean
    ): Bitmap? {
        var bitmap = inputBitmap
        var angle = levelAngle
        if (MyDebug.LOG) {
            Log.d(TAG, "autoStabilise")
            Log.d(TAG, "level_angle: $angle")
            Log.d(TAG, "is_front_facing: $isFrontFacing")
        }
        while (angle < -90) angle += 180.0
        while (angle > 90) angle -= 180.0

        if (MyDebug.LOG) Log.d(TAG, "auto stabilising... angle: $angle")

        if (bitmap == null && data != null) {
            if (MyDebug.LOG) Log.d(TAG, "need to decode bitmap to auto-stabilise")
            bitmap = ImageUtils.loadBitmapWithRotation(data, false)
            if (bitmap == null) {
                mainActivity.preview.showToast(null, R.string.failed_to_auto_stabilise)
                System.gc()
            }
        }

        if (bitmap != null) {
            val width = bitmap.width
            val height = bitmap.height
            if (MyDebug.LOG) {
                Log.d(TAG, "level_angle: $angle")
                Log.d(TAG, "decoded bitmap size $width, $height")
                Log.d(TAG, "bitmap size: ${width * height * 4}")
            }

            val matrix = Matrix()
            val levelAngleRadAbs = abs(Math.toRadians(angle))
            var w1 = width
            var h1 = height
            var w0 = w1 * cos(levelAngleRadAbs) + h1 * sin(levelAngleRadAbs)
            var h0 = w1 * sin(levelAngleRadAbs) + h1 * cos(levelAngleRadAbs)

            val origSize = (w1 * h1).toFloat()
            val rotatedSize = (w0 * h0).toFloat()
            var scale = sqrt(origSize / rotatedSize)

            if (mainActivity.testLowMemory) {
                if (MyDebug.LOG) {
                    Log.d(TAG, "TESTING LOW MEMORY")
                    Log.d(TAG, "scale was: $scale")
                }
                scale *= if (width * height >= 7500) 1.5f else 2.0f
            }

            if (MyDebug.LOG) {
                Log.d(TAG, "w0 = $w0 , h0 = $h0")
                Log.d(TAG, "w1 = $w1 , h1 = $h1")
                Log.d(TAG, "scale = sqrt $origSize / $rotatedSize = $scale")
            }

            matrix.postScale(scale, scale)
            w0 *= scale
            h0 *= scale
            w1 = (w1 * scale).toInt()
            h1 = (h1 * scale).toInt()

            if (MyDebug.LOG) {
                Log.d(TAG, "after scaling: w0 = $w0 , h0 = $h0")
                Log.d(TAG, "after scaling: w1 = $w1 , h1 = $h1")
            }

            if (isFrontFacing) {
                matrix.postRotate(-angle.toFloat())
            } else {
                matrix.postRotate(angle.toFloat())
            }

            var newBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
            if (newBitmap !== bitmap) {
                bitmap.recycle()
                bitmap = newBitmap
            }
            System.gc()

            if (MyDebug.LOG) {
                Log.d(TAG, "rotated and scaled bitmap size ${bitmap.width}, ${bitmap.height}")
                Log.d(TAG, "rotated and scaled bitmap size: ${bitmap.width * bitmap.height * 4}")
            }

            val crop = IntArray(2)
            if (autoStabiliseCrop(
                    crop,
                    levelAngleRadAbs,
                    w0,
                    h0,
                    w1,
                    h1,
                    bitmap.width,
                    bitmap.height
                )
            ) {
                val w2 = crop[0]
                val h2 = crop[1]
                val x0 = (bitmap.width - w2) / 2
                val y0 = (bitmap.height - h2) / 2
                if (MyDebug.LOG) Log.d(TAG, "x0 = $x0 , y0 = $y0")

                newBitmap = Bitmap.createBitmap(bitmap, x0, y0, w2, h2)
                if (newBitmap !== bitmap) {
                    bitmap.recycle()
                    bitmap = newBitmap
                }
                System.gc()
            }

            if (MyDebug.LOG) Log.d(TAG, "bitmap is mutable?: ${bitmap.isMutable}")
            if (!bitmap.isMutable) {
                if (bitmap.config != null) {
                    newBitmap = bitmap.copy(bitmap.config!!, true)
                    bitmap.recycle()
                    bitmap = newBitmap
                }
            }
        }
        return bitmap
    }

    private fun mirrorImage(data: ByteArray?, inputBitmap: Bitmap?): Bitmap? {
        var bitmap = inputBitmap
        if (MyDebug.LOG) Log.d(TAG, "mirrorImage")

        if (bitmap == null && data != null) {
            if (MyDebug.LOG) Log.d(TAG, "need to decode bitmap to mirror")
            bitmap = ImageUtils.loadBitmapWithRotation(data, false)
            if (bitmap == null) System.gc()
        }

        if (bitmap != null) {
            val matrix = Matrix()
            matrix.preScale(-1.0f, 1.0f)
            val width = bitmap.width
            val height = bitmap.height
            val newBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
            if (newBitmap !== bitmap) {
                bitmap.recycle()
                bitmap = newBitmap
            }
            if (MyDebug.LOG) Log.d(TAG, "bitmap is mutable?: ${bitmap.isMutable}")
        }
        return bitmap
    }

    @SuppressLint("InflateParams")
    private fun stampImage(
        request: ImageSaver.Request,
        data: ByteArray?,
        inputBitmap: Bitmap?
    ): Bitmap? {
        var bitmap = inputBitmap
        if (MyDebug.LOG) Log.d(TAG, "stampImage")

        val dateGeoStamp = request.preferenceStamp == "preference_stamp_yes"
        val textStamp = request.preferenceTextstamp?.isNotEmpty() == true

        if (dateGeoStamp || textStamp) {
            if (bitmap == null && data != null) {
                if (MyDebug.LOG) Log.d(TAG, "decode bitmap in order to stamp info")
                bitmap = ImageUtils.loadBitmapWithRotation(data, true)
                if (bitmap == null) {
                    mainActivity.preview.showToast(null, R.string.failed_to_stamp)
                    System.gc()
                }
            }

            if (bitmap != null) {
                if (MyDebug.LOG) {
                    Log.d(TAG, "stamp info to bitmap: $bitmap")
                    Log.d(TAG, "bitmap is mutable?: ${bitmap.isMutable}")
                }

                var stampString = ""
                val fontSize = request.fontSize
                val color = request.color
                val prefStyle = request.prefStyle
                if (MyDebug.LOG) Log.d(TAG, "prefStyle: $prefStyle")

                val width = bitmap.width
                val height = bitmap.height
                if (MyDebug.LOG) {
                    Log.d(TAG, "decoded bitmap size $width, $height")
                    Log.d(TAG, "bitmap size: ${width * height * 4}")
                }

                val canvas = Canvas(bitmap)
                p.color = Color.WHITE
                val smallestSize = min(width, height)
                val scale = smallestSize.toFloat() / (72.0f * 4.0f)
                val fontSizePixel = (fontSize * scale + 0.5f).toInt()

                if (MyDebug.LOG) {
                    Log.d(TAG, "scale: $scale")
                    Log.d(TAG, "font_size: $fontSize")
                    Log.d(TAG, "font_size_pixel: $fontSizePixel")
                }

                p.textSize = fontSizePixel.toFloat()
                val offsetX = (8 * scale + 0.5f).toInt()
                val offsetY = (8 * scale + 0.5f).toInt()
                val diffY = ((fontSize + 4) * scale + 0.5f).toInt()
                var yPos = height - offsetY
                p.textAlign = Paint.Align.RIGHT

                var drawShadowed = MyApplicationInterface.Shadow.SHADOW_NONE
                when (prefStyle) {
                    "preference_stamp_style_shadowed" -> drawShadowed =
                        MyApplicationInterface.Shadow.SHADOW_OUTLINE

                    "preference_stamp_style_plain" -> drawShadowed =
                        MyApplicationInterface.Shadow.SHADOW_NONE

                    "preference_stamp_style_background" -> drawShadowed =
                        MyApplicationInterface.Shadow.SHADOW_BACKGROUND
                }

                if (MyDebug.LOG) Log.d(TAG, "draw_shadowed: $drawShadowed")

                if (dateGeoStamp) {
                    if (MyDebug.LOG) Log.d(TAG, "stamp date")
                    // Note: need TextFormatter methods from original codebase
                    val dateStamp = TextFormatter.getDateString(
                        request.preferenceStampDateformat,
                        request.currentDate
                    )
                    val timeStamp = TextFormatter.getTimeString(
                        request.preferenceStampTimeformat,
                        request.currentDate
                    )

                    if (MyDebug.LOG) {
                        Log.d(TAG, "date_stamp: $dateStamp")
                        Log.d(TAG, "time_stamp: $timeStamp")
                    }

                    if (dateStamp.isNotEmpty() || timeStamp.isNotEmpty()) {
                        var datetimeStamp = ""
                        if (dateStamp.isNotEmpty()) datetimeStamp += dateStamp
                        if (timeStamp.isNotEmpty()) {
                            if (datetimeStamp.isNotEmpty()) datetimeStamp += " "
                            datetimeStamp += timeStamp
                        }
                        stampString =
                            if (stampString.isEmpty()) datetimeStamp else "$datetimeStamp\n$stampString"
                    }
                    yPos -= diffY

                    val gpsStamp = mainActivity.textFormatter.getGPSString(
                        request.preferenceStampGpsformat,
                        request.preferenceUnitsDistance,
                        request.storeLocation,
                        request.location,
                        request.storeGeoDirection,
                        request.geoDirection
                    )

                    if (gpsStamp.isNotEmpty()) {
                        if (MyDebug.LOG) Log.d(TAG, "display gps coords")
                        stampString =
                            if (stampString.isEmpty()) gpsStamp else "$gpsStamp\n$stampString"
                        yPos -= diffY
                    }
                }

                if (textStamp) {
                    if (MyDebug.LOG) Log.d(TAG, "stamp text")
                    stampString =
                        if (stampString.isEmpty()) request.preferenceTextstamp.orEmpty() else "${request.preferenceTextstamp}\n$stampString"
                    yPos -= diffY
                }

                if (stampString.isNotEmpty()) {
                    val stampView =
                        LayoutInflater.from(mainActivity).inflate(R.layout.stamp_image_text, null)
                    val layout = stampView.findViewById<LinearLayout>(R.id.layout)
                    val textview = stampView.findViewById<TextView>(R.id.text_view)

                    textview.visibility = View.VISIBLE
                    textview.setTextColor(color)
                    textview.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSizePixel.toFloat())
                    textview.text = stampString

                    if (drawShadowed == MyApplicationInterface.Shadow.SHADOW_OUTLINE) {
                        var shadowRadius = 1.0f * scale + 0.5f
                        shadowRadius = max(shadowRadius, 1.0f)
                        if (MyDebug.LOG) Log.d(TAG, "shadow_radius: $shadowRadius")
                        textview.setShadowLayer(shadowRadius, 0.0f, 0.0f, Color.BLACK)
                    } else if (drawShadowed == MyApplicationInterface.Shadow.SHADOW_BACKGROUND) {
                        textview.setBackgroundColor(Color.argb(64, 0, 0, 0))
                    }

                    textview.gravity = Gravity.END

                    layout.measure(canvas.width, canvas.height)
                    layout.layout(0, 0, canvas.width, canvas.height)
                    canvas.translate(
                        (width - offsetX - textview.measuredWidth).toFloat(),
                        (height - offsetY - textview.measuredHeight).toFloat()
                    )
                    layout.draw(canvas)
                }
            }
        }
        return bitmap
    }

    class PostProcessBitmapResult(val bitmap: Bitmap?)

    /** Performs post-processing on the data, or bitmap if non-null, for saveSingleImageNow. */
    @Throws(IOException::class)
    fun postProcessBitmap(
        request: ImageSaver.Request,
        data: ByteArray?,
        inputBitmap: Bitmap?,
        ignoreExifOrientation: Boolean
    ): PostProcessBitmapResult {
        var bitmap = inputBitmap
        if (MyDebug.LOG) Log.d(TAG, "postProcessBitmap")
        val timeS = System.currentTimeMillis()

        if (!ignoreExifOrientation) {
            if (bitmap != null) {
                if (MyDebug.LOG) Log.d(TAG, "rotate pre-existing bitmap for exif tags?")
                bitmap = ImageUtils.rotateForExif(bitmap, data)
            }
        }

        if (request.doAutoStabilise) {
            bitmap = autoStabilise(data, bitmap, request.levelAngle, request.isFrontFacing)
        }

        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "Save single image performance: time after auto-stabilise: ${System.currentTimeMillis() - timeS}"
            )
        }

        if (request.mirror) {
            bitmap = mirrorImage(data, bitmap)
        }

        if (request.imageFormat != ImageSaver.Request.ImageFormat.STD && bitmap == null && data != null) {
            if (MyDebug.LOG) Log.d(TAG, "need to decode bitmap to convert file format")
            bitmap = ImageUtils.loadBitmapWithRotation(data, true)
            if (bitmap == null) {
                System.gc()
                throw IOException()
            }
        }

        if (request.removeDeviceExif != ImageSaver.Request.RemoveDeviceExif.OFF && bitmap == null && data != null) {
            if (MyDebug.LOG) Log.d(TAG, "need to decode bitmap to strip exif tags")
            bitmap = ImageUtils.loadBitmapWithRotation(data, true)
            if (bitmap == null) {
                System.gc()
                throw IOException()
            }
        }

        bitmap = stampImage(request, data, bitmap)

        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "Save single image performance: time after photostamp: ${System.currentTimeMillis() - timeS}"
            )
        }

        return PostProcessBitmapResult(bitmap)
    }

    companion object {
        private const val TAG = "PostProcessing"

        @JvmStatic
        fun autoStabiliseCrop(
            result: IntArray,
            levelAngleRadAbs: Double,
            w0: Double,
            h0: Double,
            w1: Int,
            h1: Int,
            maxWidth: Int,
            maxHeight: Int
        ): Boolean {
            var ok = false
            result[0] = 0
            result[1] = 0

            val tanTheta = tan(levelAngleRadAbs)
            val sinTheta = sin(levelAngleRadAbs)
            val denom = h0 / w0 + tanTheta
            val altDenom = w0 / h0 + tanTheta

            if (denom < 1.0e-14) {
                if (MyDebug.LOG) Log.d(TAG, "zero denominator?!")
            } else if (altDenom < 1.0e-14) {
                if (MyDebug.LOG) Log.d(TAG, "zero alt denominator?!")
            } else {
                var w2 = ((h0 + 2.0 * h1 * sinTheta * tanTheta - w0 * tanTheta) / denom).toInt()
                var h2 = (w2 * h0 / w0).toInt()
                val altH2 =
                    ((w0 + 2.0 * w1 * sinTheta * tanTheta - h0 * tanTheta) / altDenom).toInt()
                val altW2 = (altH2 * w0 / h0).toInt()

                if (MyDebug.LOG) {
                    Log.d(TAG, "w2 = $w2 , h2 = $h2")
                    Log.d(TAG, "alt_w2 = $altW2 , alt_h2 = $altH2")
                }

                if (altW2 < w2) {
                    if (MyDebug.LOG) Log.d(TAG, "chose alt!")
                    w2 = altW2
                    h2 = altH2
                }

                if (w2 <= 0) w2 = 1 else if (w2 > maxWidth) w2 = maxWidth
                if (h2 <= 0) h2 = 1 else if (h2 > maxHeight) h2 = maxHeight

                ok = true
                result[0] = w2
                result[1] = h2
            }
            return ok
        }
    }
}

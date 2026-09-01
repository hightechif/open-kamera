package com.hightechif.openkamera.cameracontroller.pipeline

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.view.Surface
import com.hightechif.openkamera.utils.MyDebug
import android.util.Log

/**
 * Manages instantiation, lifecycle, and surface binding for JPEG and RAW (DngCreator) ImageReaders.
 */
class Camera2ImageReaderPipeline {

    companion object {
        private const val TAG = "ImageReaderPipeline"
    }

    var imageReaderJpeg: ImageReader? = null
        private set

    var imageReaderRaw: ImageReader? = null
        private set

    val jpegSurface: Surface?
        get() = imageReaderJpeg?.surface

    val rawSurface: Surface?
        get() = imageReaderRaw?.surface

    val hasRawStream: Boolean
        get() = imageReaderRaw != null

    val isConfigured: Boolean
        get() = imageReaderJpeg != null

    val jpegWidth: Int
        get() = imageReaderJpeg?.width ?: 0

    val jpegHeight: Int
        get() = imageReaderJpeg?.height ?: 0

    val jpegFormat: Int
        get() = imageReaderJpeg?.imageFormat ?: 0

    val rawWidth: Int
        get() = imageReaderRaw?.width ?: 0

    val rawHeight: Int
        get() = imageReaderRaw?.height ?: 0

    val rawFormat: Int
        get() = imageReaderRaw?.imageFormat ?: 0

    /**
     * Initializes JPEG and RAW ImageReaders according to [config].
     */
    fun createPipeline(
        config: ImageReaderConfig,
        jpegListener: ImageReader.OnImageAvailableListener?,
        rawListener: ImageReader.OnImageAvailableListener?,
        handler: Handler? = null
    ) {
        closePipeline()

        if (config.pictureWidth <= 0 || config.pictureHeight <= 0) {
            throw IllegalArgumentException("Invalid picture size: ${config.pictureWidth} x ${config.pictureHeight}")
        }

        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && config.isJpegR) {
            ImageFormat.JPEG_R
        } else {
            ImageFormat.JPEG
        }

        imageReaderJpeg = ImageReader.newInstance(
            config.pictureWidth,
            config.pictureHeight,
            format,
            2
        )

        jpegListener?.let {
            imageReaderJpeg?.setOnImageAvailableListener(it, handler)
        }

        if (config.wantRaw && config.rawSize != null && !config.isVideoMode) {
            imageReaderRaw = ImageReader.newInstance(
                config.rawSize.width,
                config.rawSize.height,
                ImageFormat.RAW_SENSOR,
                config.maxRawImages
            )
            rawListener?.let {
                imageReaderRaw?.setOnImageAvailableListener(it, handler)
            }
        }

        if (MyDebug.LOG) {
            Log.d(TAG, "Created ImageReader pipeline: JPEG=${imageReaderJpeg?.width}x${imageReaderJpeg?.height}, RAW=${imageReaderRaw?.width}x${imageReaderRaw?.height}")
        }
    }

    /**
     * Closes and releases all active ImageReaders.
     */
    fun closePipeline() {
        if (MyDebug.LOG) Log.d(TAG, "closePipeline()")
        try {
            imageReaderJpeg?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing JPEG ImageReader: ${e.message}")
        } finally {
            imageReaderJpeg = null
        }

        try {
            imageReaderRaw?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing RAW ImageReader: ${e.message}")
        } finally {
            imageReaderRaw = null
        }
    }
}

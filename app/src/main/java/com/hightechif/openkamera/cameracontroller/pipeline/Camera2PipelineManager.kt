/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.cameracontroller.pipeline

import android.media.ImageReader
import android.os.Handler
import android.util.Log
import android.view.Surface
import com.hightechif.openkamera.utils.MyDebug

/**
 * Manages the allocation, configuration, surface binding, and lifecycle of camera target surfaces
 * including Preview Surface, Video Recording Surface, and ImageReaders (JPEG, RAW DNG, YUV).
 */
class Camera2PipelineManager(
    private val imageReaderPipeline: Camera2ImageReaderPipeline = Camera2ImageReaderPipeline()
) {

    companion object {
        private const val TAG = "Camera2PipelineManager"
    }

    var previewSurface: Surface? = null
    var videoSurface: Surface? = null

    val jpegSurface: Surface?
        get() = imageReaderPipeline.jpegSurface

    val rawSurface: Surface?
        get() = imageReaderPipeline.rawSurface

    val hasRawStream: Boolean
        get() = imageReaderPipeline.hasRawStream

    val isConfigured: Boolean
        get() = imageReaderPipeline.isConfigured

    fun setupImageReaders(
        config: ImageReaderConfig,
        jpegListener: ImageReader.OnImageAvailableListener?,
        rawListener: ImageReader.OnImageAvailableListener?,
        handler: Handler? = null
    ) {
        if (MyDebug.LOG) Log.d(TAG, "setupImageReaders: ${config.pictureWidth}x${config.pictureHeight}, wantRaw=${config.wantRaw}")
        imageReaderPipeline.createPipeline(config, jpegListener, rawListener, handler)
    }

    /**
     * Gathers all non-null target surfaces required for CameraCaptureSession configuration.
     */
    fun getAllActiveSurfaces(): List<Surface> {
        val surfaces = mutableListOf<Surface>()
        previewSurface?.let { surfaces.add(it) }
        videoSurface?.let { surfaces.add(it) }
        jpegSurface?.let { surfaces.add(it) }
        rawSurface?.let { surfaces.add(it) }
        return surfaces
    }

    fun releaseSurfaces() {
        if (MyDebug.LOG) Log.d(TAG, "releaseSurfaces()")
        previewSurface = null
        videoSurface = null
        imageReaderPipeline.closePipeline()
    }
}

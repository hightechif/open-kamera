/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.utils

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.RequiresApi
import com.hightechif.openkamera.MainActivity
import com.hightechif.openkamera.cameracontroller.CameraController
import com.hightechif.openkamera.preview.ApplicationInterface
import com.hightechif.openkamera.preview.Preview
import com.hightechif.openkamera.storage.ImageSaver
import java.io.IOException
import kotlin.math.abs
import androidx.core.graphics.scale

/** Handles the saving of preview shots. */
object Preshots {
    private const val TAG = "Preshots"

    /**
     * Finds the supported video resolution that's the closest match to the supplied videoWidth
     * and videoHeight.
     */
    fun adjustResolutionForVideoCapabilities(
        videoWidth: Int,
        videoHeight: Int,
        supportedWidths: ImageSaver.IntRange,
        supportedHeights: ImageSaver.IntRange,
        widthAlignment: Int,
        heightAlignment: Int
    ): CameraController.Size {
        var w = videoWidth
        var h = videoHeight

        if (!supportedWidths.contains(w)) {
            val aspect = h.toDouble() / w.toDouble()
            w = supportedWidths.clamp(w)
            h = (aspect * w + 0.5).toInt()
            if (MyDebug.LOG) Log.d(TAG, "limit video (width) to: $w x $h")
        }
        if (!supportedHeights.contains(h)) {
            val aspect = h.toDouble() / w.toDouble()
            h = supportedHeights.clamp(h)
            w = (h / aspect + 0.5).toInt()
            if (MyDebug.LOG) Log.d(TAG, "limit video (height) to: $w x $h")
            // test width again
            if (!supportedWidths.contains(w)) {
                w = supportedWidths.clamp(w)
                if (MyDebug.LOG) Log.d(TAG, "can't find valid size that preserves aspect ratios! limit video (width) to: $w x $h")
            }
        }

        // Adjust for alignment - we could be cleverer and try to find an adjustment that preserves the aspect
        // ratio. But we'd hope that camera preview sizes already satisfy alignments - or if we had to adjust due to
        // being outside the supported widths or heights, then we should have clamped to something that already
        // satisfies the alignments
        var alignment = widthAlignment
        if (w % alignment != 0) {
            w += alignment - (w % alignment)
            if (MyDebug.LOG) Log.d(TAG, "adjust video width for alignment to: $w")
        }
        alignment = heightAlignment
        if (h % alignment != 0) {
            h += alignment - (h % alignment)
            if (MyDebug.LOG) Log.d(TAG, "adjust height for alignment to: $h")
        }
        return CameraController.Size(w, h)
    }

    private class MuxerInfo {
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var videoTrackIndex = -1
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Throws(IOException::class)
    private fun encodeVideoFrame(
        encoder: MediaCodec,
        muxerInfo: MuxerInfo,
        presentationTimeUs: Long,
        endOfStream: Boolean
    ) {
        val bufferInfo = MediaCodec.BufferInfo()
        if (endOfStream) {
            if (MyDebug.LOG) Log.d(TAG, "    signal end of stream")
            encoder.signalEndOfInputStream()
        }
        while (true) {
            if (MyDebug.LOG) Log.d(TAG, "    start of loop for saving pre-shot")
            val timeoutUs = 10000L
            val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
            if (MyDebug.LOG) Log.d(TAG, "    outputBufferIndex: $outputBufferIndex")
            if (outputBufferIndex >= 0) {
                bufferInfo.presentationTimeUs = presentationTimeUs
                val outputBuffer = encoder.getOutputBuffer(outputBufferIndex)
                if (outputBuffer == null) {
                    Log.e(TAG, "getOutputBuffer returned null")
                    throw IOException()
                }

                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    if (MyDebug.LOG) Log.d(TAG, "BUFFER_FLAG_CODEC_CONFIG")
                    bufferInfo.size = 0
                }

                if (bufferInfo.size != 0) {
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                    muxerInfo.muxer?.writeSampleData(muxerInfo.videoTrackIndex, outputBuffer, bufferInfo)
                }

                encoder.releaseOutputBuffer(outputBufferIndex, false)
                break
            } else {
                if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (MyDebug.LOG) Log.d(TAG, "    INFO_TRY_AGAIN_LATER")
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (MyDebug.LOG) Log.d(TAG, "    INFO_OUTPUT_FORMAT_CHANGED")
                    val newFormat = encoder.outputFormat
                    muxerInfo.videoTrackIndex = muxerInfo.muxer!!.addTrack(newFormat)
                    muxerInfo.muxer!!.start()
                    muxerInfo.muxerStarted = true
                }
            }
        }
    }

    /** Saves the preshot_bitmaps in the request as a video file. */
    @RequiresApi(api = Build.VERSION_CODES.O)
    fun savePreshotBitmaps(
        mainActivity: MainActivity,
        imageSaver: ImageSaver,
        request: ImageSaver.Request
    ) {
        if (MyDebug.LOG) Log.d(TAG, "savePreshotBitmaps")

        mainActivity.savingImage(true)

        val preshotBitmaps: MutableList<Bitmap?> = request.preshotBitmaps ?: return
        if (MyDebug.LOG) Log.d(TAG, "number of preshots: ${preshotBitmaps.size}")

        var method = ApplicationInterface.VideoMethod.FILE
        var videoUri: Uri? = null
        var videoFilename: String? = null
        var videoPfdSaf: ParcelFileDescriptor? = null
        var muxer: MediaMuxer? = null
        val muxerInfo = MuxerInfo()
        var encoder: MediaCodec? = null
        var savedPreshot = false
        try {
            // rotate if necessary
            // see comments in Preview.RefreshPreviewBitmapTask for update_preshot for why we need to rotate
            val rotationDegrees = mainActivity.preview.getDisplayRotationDegrees(false)
            if (MyDebug.LOG) Log.d(TAG, "rotation_degrees: $rotationDegrees")
            if (rotationDegrees != 0) {
                if (MyDebug.LOG) Log.d(TAG, "rotate preshots")
                val matrix = Matrix()
                matrix.postRotate(-rotationDegrees.toFloat())
                for (i in preshotBitmaps.indices) {
                    val bitmap = preshotBitmaps[i]
                    val newBitmap = Bitmap.createBitmap(bitmap!!, 0, 0, bitmap.width, bitmap.height, matrix, false)
                    bitmap.recycle()
                    preshotBitmaps[i] = newBitmap
                }
            }

            // resize if necessary - need to ensure we have supported dimensions for encoding to video
            val preshotWidth = preshotBitmaps[0]!!.width
            val preshotHeight = preshotBitmaps[0]!!.height
            // in some cases, the preview surface dimensions may not match the original camera preview dimensions
            // note that this alone isn't enough to guarantee being supported for video encoding, but makes sense to start with this value
            val previewSize = mainActivity.preview.currentPreviewSize
            var videoWidth = previewSize.width
            var videoHeight = previewSize.height
            if ((preshotWidth > preshotHeight) != (videoWidth > videoHeight)) {
                val dummy = videoHeight
                videoHeight = videoWidth
                videoWidth = dummy
            }
            if (MyDebug.LOG) {
                Log.d(TAG, "preshot: $preshotWidth x $preshotHeight")
                Log.d(TAG, "preview: $videoWidth x $videoHeight")
            }

            val timeS = System.currentTimeMillis()
            val mimeType = MediaFormat.MIMETYPE_VIDEO_AVC
            val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            var bestCodecInfo: MediaCodecInfo? = null
            var bestError = 0
            var bestOffset = 0

            val codecInfos = codecs.codecInfos
            for (codecInfo in codecInfos) {
                if (!codecInfo.isEncoder) continue

                var valid = false
                val types = codecInfo.supportedTypes
                for (type in types) {
                    if (type.equals(mimeType, ignoreCase = true)) {
                        valid = true
                        break
                    }
                }

                if (valid) {
                    val capabilities = codecInfo.getCapabilitiesForType(mimeType)
                    val videoCapabilities = capabilities.videoCapabilities
                    if (videoCapabilities != null) {
                        val errorW = abs(videoCapabilities.supportedWidths.clamp(videoWidth) - videoWidth)
                        val errorH = abs(videoCapabilities.supportedHeights.clamp(videoHeight) - videoHeight)
                        val error = errorW * errorH
                        val offsetW = videoWidth % videoCapabilities.widthAlignment
                        val offsetH = videoHeight % videoCapabilities.heightAlignment
                        val offset = offsetW * offsetH
                        if (MyDebug.LOG) {
                            Log.d(TAG, "video_capabilities:")
                            Log.d(TAG, "    width range: ${videoCapabilities.supportedWidths}")
                            Log.d(TAG, "    height range: ${videoCapabilities.supportedHeights}")
                            Log.d(TAG, "    width alignment: ${videoCapabilities.widthAlignment}")
                            Log.d(TAG, "    height alignment: ${videoCapabilities.heightAlignment}")
                            Log.d(TAG, "    error_w: $errorW")
                            Log.d(TAG, "    error_h: $errorH")
                            Log.d(TAG, "    offset_w: $offsetW")
                            Log.d(TAG, "    offset_h: $offsetH")
                        }
                        // prefer codec that's closest to supporting the width/height; among those, prefer codec with smallest adjustment needed for alignment
                        if (bestCodecInfo == null || error < bestError || offset < bestOffset) {
                            bestCodecInfo = codecInfo
                        }
                    }
                }
            }

            if (bestCodecInfo == null) {
                Log.e(TAG, "can't find a valid codecinfo")
                // don't fail - hope for the best that we might find an encoder below anyway
            } else {
                val capabilities = bestCodecInfo.getCapabilitiesForType(mimeType)
                val videoCapabilities = capabilities.videoCapabilities
                val supportedWidths = videoCapabilities.supportedWidths
                val supportedHeights = videoCapabilities.supportedHeights
                val widthAlignment = videoCapabilities.widthAlignment
                val heightAlignment = videoCapabilities.heightAlignment
                val adjustedSize = adjustResolutionForVideoCapabilities(
                    videoWidth, videoHeight,
                    ImageSaver.IntRange(supportedWidths),
                    ImageSaver.IntRange(supportedHeights),
                    widthAlignment, heightAlignment
                )
                videoWidth = adjustedSize.width
                videoHeight = adjustedSize.height
            }
            if (MyDebug.LOG) Log.d(TAG, "time for querying codec capabilities: ${System.currentTimeMillis() - timeS}")

            if (MyDebug.LOG) Log.d(TAG, "chosen video resolution: $videoWidth x $videoHeight")
            if (preshotWidth != videoWidth || preshotHeight != videoHeight) {
                if (MyDebug.LOG) Log.d(TAG, "resize preshot bitmaps to: $videoWidth x $videoHeight")
                for (i in preshotBitmaps.indices) {
                    val bitmap = preshotBitmaps[i]
                    if (bitmap != null) {
                        val newBitmap = bitmap.scale(videoWidth, videoHeight)
                        bitmap.recycle()
                        preshotBitmaps[i] = newBitmap
                    }
                }
            }

            // apply any post-processing
            val preshotRequest = request.copy()
            if (preshotRequest.isFrontFacing) {
                // we need to mirror for front camera (or not mirror, if the mirror flag was set)
                // for front camera, the preview will typically be mirrored, whilst saved photos are not mirrored, so we need to undo this to be
                // consistent with the main photo
                preshotRequest.mirror = !preshotRequest.mirror
            }

            for (i in preshotBitmaps.indices) {
                if (MyDebug.LOG) Log.d(TAG, "apply post-processing for preshot bitmap: $i")
                var bitmap = preshotBitmaps[i]

                val postProcessBitmapResult = imageSaver.getPostProcessing().postProcessBitmap(preshotRequest, null, bitmap, true)
                bitmap = postProcessBitmapResult.bitmap
                preshotBitmaps[i] = bitmap
            }

            if (MyDebug.LOG) Log.d(TAG, "convert preshot bitmaps to video")

            method = mainActivity.applicationInterface.createOutputVideoMethod()

            if (MyDebug.LOG) Log.d(TAG, "method? $method")
            val extension = "mp4"
            val muxerFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            if (method == ApplicationInterface.VideoMethod.FILE) {
                val videoFile = mainActivity.applicationInterface.createOutputVideoFile(true, extension, request.currentDate)
                videoFilename = videoFile.absolutePath
                if (MyDebug.LOG) Log.d(TAG, "save to: $videoFilename")
                muxer = MediaMuxer(videoFilename, muxerFormat)
            } else {
                val uri = when (method) {
                    ApplicationInterface.VideoMethod.SAF -> {
                        mainActivity.applicationInterface.createOutputVideoSAF(true, extension, request.currentDate)
                    }
                    ApplicationInterface.VideoMethod.MEDIASTORE -> {
                        mainActivity.applicationInterface.createOutputVideoMediaStore(
                            true,
                            extension,
                            request.currentDate
                        )
                    }
                    else -> {
                        mainActivity.applicationInterface.createOutputVideoUri()
                    }
                }
                if (MyDebug.LOG) Log.d(TAG, "save to: $uri")
                videoPfdSaf = mainActivity.contentResolver.openFileDescriptor(uri, "rw")
                videoUri = uri
                muxer = MediaMuxer(videoPfdSaf!!.fileDescriptor, muxerFormat)
            }
            muxerInfo.muxer = muxer

            if (MyDebug.LOG) {
                Log.d(TAG, "preshot width: $videoWidth")
                Log.d(TAG, "preshot height: $videoHeight")
            }
            val format = MediaFormat.createVideoFormat(mimeType, videoWidth, videoHeight)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_BIT_RATE, preshotBitmaps.size * 500000 * 8) // 500KB per frame
            format.setString(MediaFormat.KEY_FRAME_RATE, null) // format passed to MediaCodecList.findEncoderForFormat() must not specify a KEY_FRAME_RATE - so we set the KEY_FRAME_RATE later
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

            val encoderName = codecs.findEncoderForFormat(format)
            if (MyDebug.LOG) Log.d(TAG, "encoder_name: $encoderName")
            if (encoderName == null) {
                Log.e(TAG, "failed to find encoder")
                throw IOException()
            } else {
                encoder = MediaCodec.createByCodecName(encoderName)

                // now set KEY_FRAME_RATE (must be after findEncoderForFormat(), see note above)
                format.setInteger(MediaFormat.KEY_FRAME_RATE, 1000 / Preview.preshotIntervalMs)

                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val inputSurface = encoder.createInputSurface()
                encoder.start()

                if (request.storeLocation && request.location != null) {
                    muxer.setLocation(request.location.latitude.toFloat(), request.location.longitude.toFloat())
                }

                var presentationTimeUs: Long = 0
                for (i in preshotBitmaps.indices) {
                    val bitmap = preshotBitmaps[i]
                    if (MyDebug.LOG) Log.d(TAG, "save pre-shot: $i time: $presentationTimeUs")

                    val canvas = inputSurface.lockCanvas(null)
                    val xpos = (canvas.width - bitmap!!.width) / 2
                    val ypos = (canvas.height - bitmap.height) / 2
                    if (MyDebug.LOG) Log.d(TAG, "render at: $xpos , $ypos")
                    canvas.drawBitmap(bitmap, xpos.toFloat(), ypos.toFloat(), null)
                    inputSurface.unlockCanvasAndPost(canvas)

                    encodeVideoFrame(encoder, muxerInfo, presentationTimeUs, false)

                    preshotBitmaps[i] = null // so we know this bitmap is recycled
                    bitmap.recycle()
                    presentationTimeUs += (Preview.preshotIntervalMs * 1000).toLong()
                }

                encodeVideoFrame(encoder, muxerInfo, presentationTimeUs, true)
            }

            savedPreshot = true // success!
        } catch (e: IOException) {
            // ideally want to catch MediaCodec.CodecException, but then entire class would need to target
            // Android L - instead we catch its superclass IllegalStateException
            MyDebug.logStackTrace(TAG, "failed saving preshots video", e)
            // cleanup
            for (i in preshotBitmaps.indices) {
                if (MyDebug.LOG) Log.d(TAG, "recycle preshot bitmap: $i")
                val bitmap = preshotBitmaps[i]
                bitmap?.recycle()
            }
        } catch (e: IllegalStateException) {
            MyDebug.logStackTrace(TAG, "failed saving preshots video", e)
            // cleanup
            for (i in preshotBitmaps.indices) {
                if (MyDebug.LOG) Log.d(TAG, "recycle preshot bitmap: $i")
                val bitmap = preshotBitmaps[i]
                bitmap?.recycle()
            }
        } finally {
            if (encoder != null) {
                if (MyDebug.LOG) Log.d(TAG, "stop encoder")
                encoder.stop()
                if (MyDebug.LOG) Log.d(TAG, "release encoder")
                encoder.release()
            }
            if (muxer != null) {
                if (muxerInfo.muxerStarted) {
                    if (MyDebug.LOG) Log.d(TAG, "stop muxer")
                    muxer.stop()
                }
                if (MyDebug.LOG) Log.d(TAG, "release muxer")
                muxer.release()
            }
            try {
                if (videoPfdSaf != null) {
                    if (MyDebug.LOG) Log.d(TAG, "close video_pfd_saf: $videoPfdSaf")
                    videoPfdSaf.close()
                }
            } catch (e: IOException) {
                MyDebug.logStackTrace(TAG, "failed close resources", e)
            }
        }

        if (savedPreshot) {
            if (MyDebug.LOG) Log.d(TAG, "saved preshots successfully")
            mainActivity.applicationInterface.completeVideo(method, videoUri)
            mainActivity.applicationInterface.broadcastVideo(method, videoUri, videoFilename)
        }

        mainActivity.savingImage(false)
    }
}

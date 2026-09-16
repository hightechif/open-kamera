/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.storage

import com.hightechif.openkamera.di.DefaultDispatcher
import com.hightechif.openkamera.di.IoDispatcher
import com.hightechif.openkamera.domain.engine.IImageProcessor
import com.hightechif.openkamera.domain.model.CaptureConfig
import com.hightechif.openkamera.domain.model.PhotoResult
import com.hightechif.openkamera.domain.repository.IMediaRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

sealed interface MediaSaveTask {
    val id: String
    val config: CaptureConfig

    data class SaveJpeg(
        override val id: String,
        override val config: CaptureConfig,
        val jpegBytes: ByteArray,
        val customFilename: String? = null,
        val onComplete: ((Result<PhotoResult>) -> Unit)? = null
    ) : MediaSaveTask {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SaveJpeg) return false
            return id == other.id && jpegBytes.contentEquals(other.jpegBytes)
        }

        override fun hashCode(): Int = id.hashCode()
    }

    data class SaveRaw(
        override val id: String,
        override val config: CaptureConfig,
        val dngBytes: ByteArray,
        val customFilename: String? = null,
        val onComplete: ((Result<PhotoResult>) -> Unit)? = null
    ) : MediaSaveTask {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SaveRaw) return false
            return id == other.id && dngBytes.contentEquals(other.dngBytes)
        }

        override fun hashCode(): Int = id.hashCode()
    }

    data class ProcessDeferredHdr(
        override val id: String,
        override val config: CaptureConfig,
        val frames: List<ByteArray>,
        val customFilename: String? = null,
        val onComplete: ((Result<PhotoResult>) -> Unit)? = null
    ) : MediaSaveTask

    data class ProcessDeferredPanorama(
        override val id: String,
        override val config: CaptureConfig,
        val frames: List<ByteArray>,
        val customFilename: String? = null,
        val onComplete: ((Result<PhotoResult>) -> Unit)? = null
    ) : MediaSaveTask
}

@Singleton
class MediaProcessingWorker @Inject constructor(
    private val mediaRepository: IMediaRepository,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @param:DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    private val imageProcessor: IImageProcessor? = null,
    private val workerScope: CoroutineScope = CoroutineScope(ioDispatcher + SupervisorJob())
) {

    private val pendingCount = AtomicInteger(0)

    private val _pendingTasksCountFlow = MutableStateFlow(0)
    val pendingTasksCountFlow: StateFlow<Int> = _pendingTasksCountFlow.asStateFlow()

    private val _isProcessingFlow = MutableStateFlow(false)
    val isProcessingFlow: StateFlow<Boolean> = _isProcessingFlow.asStateFlow()

    fun submitTask(task: MediaSaveTask): Job {
        val count = pendingCount.incrementAndGet()
        _pendingTasksCountFlow.value = count

        return workerScope.launch(ioDispatcher) {
            _isProcessingFlow.value = true
            try {
                processTask(task)
            } finally {
                val remaining = pendingCount.decrementAndGet()
                _pendingTasksCountFlow.value = remaining.coerceAtLeast(0)
                if (remaining <= 0) {
                    _isProcessingFlow.value = false
                }
            }
        }
    }

    fun enqueueDeferredHdr(
        frames: List<ByteArray>,
        config: CaptureConfig,
        customFilename: String? = null,
        onComplete: ((Result<PhotoResult>) -> Unit)? = null
    ): Job {
        val id = "hdr_${System.currentTimeMillis()}"
        return submitTask(
            MediaSaveTask.ProcessDeferredHdr(
                id = id,
                config = config,
                frames = frames,
                customFilename = customFilename,
                onComplete = onComplete
            )
        )
    }

    fun enqueueDeferredPanorama(
        frames: List<ByteArray>,
        config: CaptureConfig,
        customFilename: String? = null,
        onComplete: ((Result<PhotoResult>) -> Unit)? = null
    ): Job {
        val id = "pano_${System.currentTimeMillis()}"
        return submitTask(
            MediaSaveTask.ProcessDeferredPanorama(
                id = id,
                config = config,
                frames = frames,
                customFilename = customFilename,
                onComplete = onComplete
            )
        )
    }

    private suspend fun processTask(task: MediaSaveTask) {
        when (task) {
            is MediaSaveTask.SaveJpeg -> {
                val result =
                    mediaRepository.savePhoto(task.jpegBytes, task.config, task.customFilename)
                task.onComplete?.invoke(result)
            }

            is MediaSaveTask.SaveRaw -> {
                val result =
                    mediaRepository.saveRawDng(task.dngBytes, task.config, task.customFilename)
                task.onComplete?.invoke(result)
            }

            is MediaSaveTask.ProcessDeferredHdr -> {
                val processor = imageProcessor
                val processResult = processor?.processHdr(task.frames)
                    ?: Result.failure(IllegalStateException("ImageProcessor not available for HDR processing"))
                val saveResult = if (processResult.isSuccess) {
                    mediaRepository.savePhoto(
                        processResult.getOrThrow(),
                        task.config,
                        task.customFilename
                    )
                } else {
                    Result.failure(
                        processResult.exceptionOrNull()
                            ?: IllegalStateException("HDR processing failed")
                    )
                }
                task.onComplete?.invoke(saveResult)
            }

            is MediaSaveTask.ProcessDeferredPanorama -> {
                val processor = imageProcessor
                val processResult = processor?.processPanorama(task.frames)
                    ?: Result.failure(IllegalStateException("ImageProcessor not available for Panorama processing"))
                val saveResult = if (processResult.isSuccess) {
                    mediaRepository.savePhoto(
                        processResult.getOrThrow(),
                        task.config,
                        task.customFilename
                    )
                } else {
                    Result.failure(
                        processResult.exceptionOrNull()
                            ?: IllegalStateException("Panorama processing failed")
                    )
                }
                task.onComplete?.invoke(saveResult)
            }
        }
    }

    val currentQueueSize: Int
        get() = pendingCount.get()
}

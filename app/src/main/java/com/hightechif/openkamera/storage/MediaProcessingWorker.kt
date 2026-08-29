/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.storage

import com.hightechif.openkamera.di.DefaultDispatcher
import com.hightechif.openkamera.di.IoDispatcher
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
}

@Singleton
class MediaProcessingWorker(
    private val mediaRepository: IMediaRepository,
    private val workerScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val defaultDispatcher: CoroutineDispatcher
) {
    @Inject
    constructor(
        mediaRepository: IMediaRepository,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        @DefaultDispatcher defaultDispatcher: CoroutineDispatcher
    ) : this(
        mediaRepository = mediaRepository,
        workerScope = CoroutineScope(ioDispatcher + SupervisorJob()),
        ioDispatcher = ioDispatcher,
        defaultDispatcher = defaultDispatcher
    )

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
        }
    }

    val currentQueueSize: Int
        get() = pendingCount.get()
}

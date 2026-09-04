/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.storage

import android.util.Log
import com.hightechif.openkamera.utils.MyDebug
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

/**
 * Modern, application-scoped Kotlin Coroutines pipeline for asynchronous photo saving,
 * bounded parallel burst processing, and reactive queue state tracking.
 */
class ImageSavePipeline(
    val queueCapacity: Int,
    private val maxConcurrentWorkers: Int = 2,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onQueueChanged: (() -> Unit)? = null,
    private val taskExecutor: (suspend (SaveTask) -> Boolean)? = null
) {
    companion object {
        private const val TAG = "ImageSavePipeline"

        const val QUEUE_COST_JPEG_C = 1
        const val QUEUE_COST_DNG_C = 70

        fun computeQueueSize(largeMemoryClass: Int): Int {
            var maxQueueSize = largeMemoryClass / 8
            if (maxQueueSize < 6) maxQueueSize = 6
            return maxQueueSize
        }
    }

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        if (throwable !is CancellationException) {
            Log.e(TAG, "Unhandled exception in ImageSavePipeline coroutine", throwable)
        }
    }

    private val pipelineScope = CoroutineScope(SupervisorJob() + defaultDispatcher + exceptionHandler)
    private val channel = Channel<SaveTask>(capacity = queueCapacity.coerceAtLeast(6))

    private val pendingCount = AtomicInteger(0)
    private val realImageCount = AtomicInteger(0)
    private val activeWorkerCount = AtomicInteger(0)

    private val _queueStateFlow = MutableStateFlow(
        ImageSaveQueueState(
            pendingCount = 0,
            realImageCount = 0,
            isProcessing = false,
            isBlocked = false,
            queueCapacity = queueCapacity
        )
    )
    val queueStateFlow: StateFlow<ImageSaveQueueState> = _queueStateFlow.asStateFlow()

    private val workerJobs = mutableListOf<Job>()
    private val concurrencySemaphore = Semaphore(maxConcurrentWorkers.coerceAtLeast(1))

    @Volatile
    var isDestroyed: Boolean = false
        private set

    init {
        if (MyDebug.LOG) Log.d(TAG, "Initializing ImageSavePipeline: capacity=$queueCapacity, maxWorkers=$maxConcurrentWorkers")
        startWorkers()
    }

    private fun startWorkers() {
        repeat(maxConcurrentWorkers.coerceAtLeast(1)) { workerIndex ->
            val job = pipelineScope.launch {
                for (task in channel) {
                    if (task is SaveTask.OnDestroy) {
                        if (MyDebug.LOG) Log.d(TAG, "Worker $workerIndex encountered OnDestroy task")
                        decrementTaskCounts(task)
                        break
                    }
                    processTask(workerIndex, task)
                }
            }
            workerJobs.add(job)
        }
    }

    private suspend fun processTask(workerIndex: Int, task: SaveTask) {
        concurrencySemaphore.withPermit {
            activeWorkerCount.incrementAndGet()
            updateState()
            try {
                if (MyDebug.LOG) Log.d(TAG, "Worker $workerIndex executing task: $task")
                taskExecutor?.invoke(task)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing task $task on worker $workerIndex", e)
            } finally {
                activeWorkerCount.decrementAndGet()
                decrementTaskCounts(task)
            }
        }
    }

    private fun decrementTaskCounts(task: SaveTask) {
        val remaining = pendingCount.decrementAndGet().coerceAtLeast(0)
        if (task.isRealImage) {
            realImageCount.decrementAndGet().coerceAtLeast(0)
        }
        updateState()
        onQueueChanged?.invoke()
    }

    private fun updateState() {
        val pending = pendingCount.get()
        val real = realImageCount.get()
        val active = activeWorkerCount.get()
        val blocked = queueWouldBlock(QUEUE_COST_JPEG_C)

        _queueStateFlow.update {
            it.copy(
                pendingCount = pending,
                realImageCount = real,
                isProcessing = active > 0 || pending > 0,
                isBlocked = blocked
            )
        }
    }

    /**
     * Submits a save task to the pipeline. Returns true if accepted, false if destroyed or full.
     */
    fun submit(task: SaveTask): Boolean {
        if (isDestroyed) {
            Log.w(TAG, "Cannot submit task: pipeline is destroyed")
            return false
        }
        pendingCount.incrementAndGet()
        if (task.isRealImage) {
            realImageCount.incrementAndGet()
        }
        updateState()

        val result = channel.trySend(task)
        if (!result.isSuccess) {
            Log.e(TAG, "Failed to send task to channel: $task, isFull=${result.isClosed}")
            decrementTaskCounts(task)
            return false
        }
        onQueueChanged?.invoke()
        return true
    }

    /**
     * Checks whether an incoming batch/photo of a given cost would exceed the capacity.
     */
    fun queueWouldBlock(photoCost: Int): Boolean {
        val current = pendingCount.get()
        if (current == 0) return false
        return (current + photoCost > queueCapacity + 1)
    }

    val currentPendingCount: Int get() = pendingCount.get()
    val currentRealImageCount: Int get() = realImageCount.get()

    /**
     * Suspends until all currently queued tasks are processed or timeout is reached.
     */
    suspend fun joinAllTasks(timeoutMillis: Long = 10000L): Boolean {
        return withTimeoutOrNull(timeoutMillis.milliseconds) {
            while (pendingCount.get() > 0 || activeWorkerCount.get() > 0) {
                delay(50.milliseconds)
            }
            true
        } ?: false
    }

    /**
     * Closes the pipeline and cancels background coroutines gracefully.
     */
    fun destroy() {
        if (isDestroyed) return
        isDestroyed = true
        if (MyDebug.LOG) Log.d(TAG, "Destroying ImageSavePipeline")
        channel.close()
        pipelineScope.launch {
            withTimeoutOrNull(3000L.milliseconds) {
                workerJobs.joinAll()
            }
            pipelineScope.cancel()
        }
    }
}

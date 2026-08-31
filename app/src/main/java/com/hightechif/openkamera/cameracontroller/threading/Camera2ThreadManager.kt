/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.cameracontroller.threading

import android.os.Handler
import android.os.HandlerThread
import com.hightechif.openkamera.utils.MyDebug
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor

/**
 * Manages dedicated background threads, coroutine scopes, and dispatchers for Camera2 operations.
 * Bridges legacy Camera2 Handler/Executor requirements with modern Kotlin Coroutines.
 */
class Camera2ThreadManager(
    threadName: String = "CameraBackground"
) {

    companion object {
        private const val TAG = "Camera2ThreadManager"
    }

    private val handlerThread: HandlerThread = HandlerThread(threadName).apply {
        start()
    }

    val handler: Handler = Handler(handlerThread.looper)

    val executor: Executor = Executor { command ->
        handler.post(command)
    }

    val dispatcher: CoroutineDispatcher = handler.asCoroutineDispatcher(threadName)

    val cameraScope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    val ioScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Launches a coroutine on the dedicated camera looper dispatcher.
     */
    fun launchCamera(block: suspend CoroutineScope.() -> Unit): Job {
        return cameraScope.launch(block = block)
    }

    /**
     * Launches a coroutine on Dispatchers.IO for heavy file operations/image processing.
     */
    fun launchIO(block: suspend CoroutineScope.() -> Unit): Job {
        return ioScope.launch(block = block)
    }

    /**
     * Launches a coroutine on Dispatchers.Main for UI updates.
     */
    fun launchMain(block: suspend CoroutineScope.() -> Unit): Job {
        return CoroutineScope(SupervisorJob() + Dispatchers.Main).launch(block = block)
    }

    /**
     * Executes a runnable on the handler.
     */
    fun post(runnable: Runnable): Boolean {
        return handler.post(runnable)
    }

    /**
     * Executes a suspending block on the camera dispatcher.
     */
    suspend fun <T> withCameraContext(block: suspend CoroutineScope.() -> T): T {
        return withContext(dispatcher, block)
    }

    /**
     * Executes a suspending block on Dispatchers.IO.
     */
    suspend fun <T> withIOContext(block: suspend CoroutineScope.() -> T): T {
        return withContext(Dispatchers.IO, block)
    }

    /**
     * Safely terminates all coroutine scopes, the handler thread, and joins with optional timeout.
     */
    fun shutdownSafely(joinTimeoutMs: Long = 1000L) {
        if (MyDebug.LOG) Log.d(TAG, "Shutting down Camera2ThreadManager safely")
        cameraScope.cancel()
        ioScope.cancel()

        handlerThread.quitSafely()
        try {
            handlerThread.join(joinTimeoutMs)
        } catch (e: InterruptedException) {
            if (MyDebug.LOG) Log.e(TAG, "Interrupted while joining thread", e)
            Thread.currentThread().interrupt()
        }
    }
}

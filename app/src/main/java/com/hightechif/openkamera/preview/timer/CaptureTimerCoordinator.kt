/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview.timer

import android.util.Log
import com.hightechif.openkamera.utils.MyDebug
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Modern Kotlin Coroutines capture timer and burst coordinator replacing java.util.Timer.
 */
class CaptureTimerCoordinator(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)
) {
    companion object {
        private const val TAG = "CaptureTimerCoord"
    }

    private val _countdownState = MutableStateFlow<CountdownState>(CountdownState.Idle)
    val countdownState: StateFlow<CountdownState> = _countdownState.asStateFlow()

    private var countdownJob: Job? = null

    // Burst scheduling state: -1 = infinite, 0 = done/none, > 0 = remaining photos
    var remainingRepeatPhotos: Int = 0
        private set

    val isBurstActive: Boolean
        get() = remainingRepeatPhotos == -1 || remainingRepeatPhotos > 0

    val isCountdownActive: Boolean
        get() = countdownJob?.isActive == true

    /**
     * Starts a non-blocking countdown timer with 1-second audio beep triggers and completion callback.
     */
    fun startCountdown(
        timerDelayMs: Long,
        onBeep: ((remainingMs: Long) -> Unit)? = null,
        onFinish: () -> Unit
    ) {
        cancelCountdown()
        if (timerDelayMs <= 0L) {
            _countdownState.value = CountdownState.Finished
            onFinish()
            return
        }

        if (MyDebug.LOG) Log.d(TAG, "startCountdown: $timerDelayMs ms")
        _countdownState.value = CountdownState.InProgress(timerDelayMs, timerDelayMs)

        countdownJob = scope.launch(dispatcher) {
            var remainingTime = timerDelayMs
            while (remainingTime > 0) {
                onBeep?.invoke(remainingTime)
                val sleepTime = remainingTime.coerceAtMost(1000L)
                delay(sleepTime.milliseconds)
                remainingTime -= sleepTime
                if (remainingTime > 0) {
                    _countdownState.value = CountdownState.InProgress(remainingTime, timerDelayMs)
                }
            }

            _countdownState.value = CountdownState.Finished
            if (MyDebug.LOG) Log.d(TAG, "countdown finished")
            onFinish()
        }
    }

    /**
     * Cancels any active countdown timer.
     */
    fun cancelCountdown() {
        if (countdownJob?.isActive == true) {
            if (MyDebug.LOG) Log.d(TAG, "cancelCountdown")
            countdownJob?.cancel()
            countdownJob = null
            _countdownState.value = CountdownState.Cancelled
        }
    }

    /**
     * Sets up a repeat burst configuration.
     */
    fun setupBurst(config: BurstScheduleConfig) {
        remainingRepeatPhotos = when {
            config.isBurstInfinite -> -1
            config.totalPhotos > 1 -> config.totalPhotos - 1
            else -> 0
        }
        if (MyDebug.LOG) Log.d(TAG, "setupBurst: remaining=$remainingRepeatPhotos")
    }

    fun hasNextBurst(): Boolean {
        return remainingRepeatPhotos == -1 || remainingRepeatPhotos > 0
    }

    /**
     * Consumes one repeat burst shot from the queue.
     */
    fun consumeNextBurst(): Boolean {
        return when {
            remainingRepeatPhotos == -1 -> true
            remainingRepeatPhotos > 0 -> {
                remainingRepeatPhotos--
                true
            }
            else -> false
        }
    }

    /**
     * Resets / cancels the burst schedule.
     */
    fun cancelBurst() {
        if (MyDebug.LOG) Log.d(TAG, "cancelBurst")
        remainingRepeatPhotos = 0
    }

    /**
     * Cancels all timers and resets state.
     */
    fun reset() {
        cancelCountdown()
        cancelBurst()
        _countdownState.value = CountdownState.Idle
    }

    fun destroy() {
        reset()
    }
}

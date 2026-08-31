/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview.timer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureTimerCoordinatorUnitTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var coordinator: CaptureTimerCoordinator

    @Before
    fun setUp() {
        coordinator = CaptureTimerCoordinator(
            dispatcher = testDispatcher,
            scope = testScope
        )
    }

    @Test
    fun testCountdown_CompletesAndTriggersCallbacks() = testScope.runTest {
        var finished = false
        val beeps = mutableListOf<Long>()

        coordinator.startCountdown(
            timerDelayMs = 3000L,
            onBeep = { beeps.add(it) },
            onFinish = { finished = true }
        )

        assertTrue(coordinator.isCountdownActive)
        assertTrue(coordinator.countdownState.value is CountdownState.InProgress)

        // Advance 1 second
        advanceTimeBy(1000L.milliseconds)
        runCurrent()
        assertEquals(2, beeps.size) // initial tick + 1 second tick
        assertFalse(finished)

        // Advance remainder (2 more seconds)
        advanceTimeBy(2000L.milliseconds)
        runCurrent()
        assertEquals(3, beeps.size)
        assertTrue(finished)
        assertFalse(coordinator.isCountdownActive)
        assertEquals(CountdownState.Finished, coordinator.countdownState.value)
    }

    @Test
    fun testCountdown_CancelStopsExecution() = testScope.runTest {
        var finished = false

        coordinator.startCountdown(
            timerDelayMs = 5000L,
            onFinish = { finished = true }
        )

        advanceTimeBy(1000L.milliseconds)
        runCurrent()
        assertTrue(coordinator.isCountdownActive)

        coordinator.cancelCountdown()
        assertFalse(coordinator.isCountdownActive)
        assertEquals(CountdownState.Cancelled, coordinator.countdownState.value)

        advanceTimeBy(5000L.milliseconds)
        runCurrent()
        assertFalse(finished)
    }

    @Test
    fun testBurst_FiniteSchedule() {
        val config = BurstScheduleConfig(totalPhotos = 3)
        coordinator.setupBurst(config)

        assertEquals(2, coordinator.remainingRepeatPhotos)
        assertTrue(coordinator.hasNextBurst())

        assertTrue(coordinator.consumeNextBurst())
        assertEquals(1, coordinator.remainingRepeatPhotos)

        assertTrue(coordinator.consumeNextBurst())
        assertEquals(0, coordinator.remainingRepeatPhotos)

        assertFalse(coordinator.hasNextBurst())
        assertFalse(coordinator.consumeNextBurst())
    }

    @Test
    fun testBurst_InfiniteSchedule() {
        val config = BurstScheduleConfig(isBurstInfinite = true)
        coordinator.setupBurst(config)

        assertEquals(-1, coordinator.remainingRepeatPhotos)
        assertTrue(coordinator.hasNextBurst())
        assertTrue(coordinator.consumeNextBurst())
        assertEquals(-1, coordinator.remainingRepeatPhotos)

        coordinator.cancelBurst()
        assertEquals(0, coordinator.remainingRepeatPhotos)
        assertFalse(coordinator.hasNextBurst())
    }
}

/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.cameracontroller.threading

import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Camera2ThreadManagerUnitTest {

    @Test
    fun testInitializationAndLooper() {
        val threadManager = Camera2ThreadManager("TestCameraThread")
        try {
            assertNotNull(threadManager.handler)
            assertNotNull(threadManager.executor)
            assertNotNull(threadManager.dispatcher)
            assertNotNull(threadManager.cameraScope)
            assertNotNull(threadManager.ioScope)
            assertTrue(threadManager.cameraScope.isActive)
            assertTrue(threadManager.ioScope.isActive)
        } finally {
            threadManager.shutdownSafely()
        }
    }

    @Test
    fun testPostRunnable() {
        val threadManager = Camera2ThreadManager("TestCameraThread")
        val latch = CountDownLatch(1)
        val executed = AtomicBoolean(false)

        try {
            threadManager.post {
                executed.set(true)
                latch.countDown()
            }
            val completed = latch.await(2, TimeUnit.SECONDS)
            assertTrue(completed)
            assertTrue(executed.get())
        } finally {
            threadManager.shutdownSafely()
        }
    }

    @Test
    fun testLaunchCameraCoroutine() = runBlocking {
        val threadManager = Camera2ThreadManager("TestCameraThread")
        try {
            val result = threadManager.withCameraContext {
                "ExecutedOnCameraDispatcher"
            }
            assertEquals("ExecutedOnCameraDispatcher", result)
        } finally {
            threadManager.shutdownSafely()
        }
    }

    @Test
    fun testLaunchIOCoroutine() = runBlocking {
        val threadManager = Camera2ThreadManager("TestCameraThread")
        try {
            val result = threadManager.withIOContext {
                42 * 2
            }
            assertEquals(84, result)
        } finally {
            threadManager.shutdownSafely()
        }
    }

    @Test
    fun testShutdownSafely() {
        val threadManager = Camera2ThreadManager("TestCameraThread")
        assertTrue(threadManager.cameraScope.isActive)
        assertTrue(threadManager.ioScope.isActive)

        threadManager.shutdownSafely()

        assertFalse(threadManager.cameraScope.isActive)
        assertFalse(threadManager.ioScope.isActive)
    }
}

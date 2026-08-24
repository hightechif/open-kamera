/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.di

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DispatchersModuleTest {

    @Test
    fun provideIoDispatcher_returnsDispatchersIO() {
        val dispatcher = DispatchersModule.provideIoDispatcher()
        assertEquals(Dispatchers.IO, dispatcher)
    }

    @Test
    fun provideDefaultDispatcher_returnsDispatchersDefault() {
        val dispatcher = DispatchersModule.provideDefaultDispatcher()
        assertEquals(Dispatchers.Default, dispatcher)
    }

    @Test
    fun provideMainDispatcher_returnsDispatchersMain() {
        val dispatcher = DispatchersModule.provideMainDispatcher()
        assertEquals(Dispatchers.Main, dispatcher)
    }

    @Test
    fun coroutineTest_executesCleanly() = runTest {
        val result = 40 + 2
        assertEquals(42, result)
    }
}

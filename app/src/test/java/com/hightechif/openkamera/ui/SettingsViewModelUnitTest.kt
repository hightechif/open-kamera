/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.ui

import com.hightechif.openkamera.domain.model.CaptureMode
import com.hightechif.openkamera.domain.model.FlashMode
import com.hightechif.openkamera.domain.model.GridType
import com.hightechif.openkamera.domain.repository.ISettingsRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelUnitTest {

    private val testDispatcher = StandardTestDispatcher()
    private val mockSettingsRepository = mockk<ISettingsRepository>(relaxed = true)

    private val flashModeFlow = MutableStateFlow(FlashMode.AUTO)
    private val gridTypeFlow = MutableStateFlow(GridType.NONE)
    private val captureModeFlow = MutableStateFlow(CaptureMode.PHOTO)
    private val isRawEnabledFlow = MutableStateFlow(false)
    private val timerSecondsFlow = MutableStateFlow(0)
    private val showHorizonLevelFlow = MutableStateFlow(true)

    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        every { mockSettingsRepository.flashModeFlow } returns flashModeFlow
        every { mockSettingsRepository.gridTypeFlow } returns gridTypeFlow
        every { mockSettingsRepository.captureModeFlow } returns captureModeFlow
        every { mockSettingsRepository.isRawEnabledFlow } returns isRawEnabledFlow
        every { mockSettingsRepository.timerSecondsFlow } returns timerSecondsFlow
        every { mockSettingsRepository.showHorizonLevelFlow } returns showHorizonLevelFlow

        viewModel = SettingsViewModel(mockSettingsRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun setFlashMode_callsRepository() = runTest(testDispatcher) {
        viewModel.setFlashMode(FlashMode.TORCH)
        advanceUntilIdle()
        coVerify { mockSettingsRepository.setFlashMode(FlashMode.TORCH) }
    }

    @Test
    fun setGridType_callsRepository() = runTest(testDispatcher) {
        viewModel.setGridType(GridType.GOLDEN_SPIRAL)
        advanceUntilIdle()
        coVerify { mockSettingsRepository.setGridType(GridType.GOLDEN_SPIRAL) }
    }

    @Test
    fun setRawEnabled_callsRepository() = runTest(testDispatcher) {
        viewModel.setRawEnabled(true)
        advanceUntilIdle()
        coVerify { mockSettingsRepository.setRawEnabled(true) }
    }

    @Test
    fun setTimerSeconds_callsRepository() = runTest(testDispatcher) {
        viewModel.setTimerSeconds(10)
        advanceUntilIdle()
        coVerify { mockSettingsRepository.setTimerSeconds(10) }
    }

    @Test
    fun setHorizonLevelEnabled_callsRepository() = runTest(testDispatcher) {
        viewModel.setHorizonLevelEnabled(false)
        advanceUntilIdle()
        coVerify { mockSettingsRepository.setHorizonLevelEnabled(false) }
    }

    @Test
    fun setPhotoResolution_callsRepository() = runTest(testDispatcher) {
        viewModel.setPhotoResolution("1920x1080")
        advanceUntilIdle()
        coVerify { mockSettingsRepository.setStringPreference("preference_resolution", "1920x1080") }
    }

    @Test
    fun setVideoQuality_callsRepository() = runTest(testDispatcher) {
        viewModel.setVideoQuality("1080p")
        advanceUntilIdle()
        coVerify { mockSettingsRepository.setStringPreference("preference_video_quality", "1080p") }
    }
}

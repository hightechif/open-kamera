/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.domain.usecase

import com.hightechif.openkamera.domain.engine.ICameraEngine
import com.hightechif.openkamera.domain.model.CameraFacing
import com.hightechif.openkamera.domain.model.FlashMode
import com.hightechif.openkamera.domain.repository.ISettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DomainUseCasesUnitTest {

    private val testDispatcher = StandardTestDispatcher()
    private val mockCameraEngine = mockk<ICameraEngine>(relaxed = true)
    private val mockSettingsRepository = mockk<ISettingsRepository>(relaxed = true)

    @Before
    fun setUp() {
        every { mockSettingsRepository.getBooleanPreference(any(), any()) } returns false
        every { mockSettingsRepository.isRawEnabled() } returns false
    }

    @Test
    fun toggleFlashUseCase_cyclesFlashModes() = runTest(testDispatcher) {
        val useCase = ToggleFlashUseCase(
            cameraEngine = mockCameraEngine,
            settingsRepository = mockSettingsRepository
        )

        every { mockSettingsRepository.getFlashMode() } returns FlashMode.AUTO

        val nextMode = useCase()
        assertEquals(FlashMode.ON, nextMode)
        coVerify { mockCameraEngine.setFlashMode(FlashMode.ON) }
        verify { mockSettingsRepository.setFlashMode(FlashMode.ON) }
    }

    @Test
    fun switchCameraFacingUseCase_switchesFacingSuccessfully() = runTest(testDispatcher) {
        val useCase = SwitchCameraFacingUseCase(
            cameraEngine = mockCameraEngine,
            settingsRepository = mockSettingsRepository
        )

        every { mockSettingsRepository.getStringPreference("preference_camera_facing", any()) } returns CameraFacing.BACK.name
        coEvery { mockCameraEngine.openCamera(CameraFacing.FRONT) } returns Result.success(Unit)

        val result = useCase()
        assertTrue(result.isSuccess)
        assertEquals(CameraFacing.FRONT, result.getOrNull())
        verify { mockSettingsRepository.setStringPreference("preference_camera_facing", CameraFacing.FRONT.name) }
    }
}

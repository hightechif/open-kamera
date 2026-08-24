/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hightechif.openkamera.domain.model.CaptureMode
import com.hightechif.openkamera.domain.model.FlashMode
import com.hightechif.openkamera.domain.model.GridType
import com.hightechif.openkamera.domain.repository.ISettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: ISettingsRepository
) : ViewModel() {

    val flashMode: StateFlow<FlashMode> = settingsRepository.flashModeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FlashMode.AUTO)

    val gridType: StateFlow<GridType> = settingsRepository.gridTypeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GridType.NONE)

    val captureMode: StateFlow<CaptureMode> = settingsRepository.captureModeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CaptureMode.PHOTO)

    val isRawEnabled: StateFlow<Boolean> = settingsRepository.isRawEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val timerSeconds: StateFlow<Int> = settingsRepository.timerSecondsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val showHorizonLevel: StateFlow<Boolean> = settingsRepository.showHorizonLevelFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setFlashMode(flashMode: FlashMode) {
        viewModelScope.launch {
            settingsRepository.setFlashMode(flashMode)
        }
    }

    fun setGridType(gridType: GridType) {
        viewModelScope.launch {
            settingsRepository.setGridType(gridType)
        }
    }

    fun setCaptureMode(mode: CaptureMode) {
        viewModelScope.launch {
            settingsRepository.setCaptureMode(mode)
        }
    }

    fun setRawEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setRawEnabled(enabled)
        }
    }

    fun setTimerSeconds(seconds: Int) {
        viewModelScope.launch {
            settingsRepository.setTimerSeconds(seconds)
        }
    }

    fun setHorizonLevelEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setHorizonLevelEnabled(enabled)
        }
    }

    fun setPhotoResolution(resolution: String) {
        viewModelScope.launch {
            settingsRepository.setStringPreference("preference_resolution", resolution)
        }
    }

    fun setVideoQuality(quality: String) {
        viewModelScope.launch {
            settingsRepository.setStringPreference("preference_video_quality", quality)
        }
    }
}

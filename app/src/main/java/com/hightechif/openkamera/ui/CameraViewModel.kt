/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.ui

import android.graphics.PointF
import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hightechif.openkamera.domain.engine.CaptureProgress
import com.hightechif.openkamera.domain.engine.ICameraEngine
import com.hightechif.openkamera.domain.engine.IRemoteInputManager
import com.hightechif.openkamera.domain.engine.RemoteButton
import com.hightechif.openkamera.domain.model.CaptureMode
import com.hightechif.openkamera.domain.model.FlashMode
import com.hightechif.openkamera.domain.model.GridType
import com.hightechif.openkamera.domain.repository.ILocationRepository
import com.hightechif.openkamera.domain.repository.IMediaRepository
import com.hightechif.openkamera.domain.repository.ISensorRepository
import com.hightechif.openkamera.domain.repository.ISettingsRepository
import com.hightechif.openkamera.domain.usecase.AdjustExposureUseCase
import com.hightechif.openkamera.domain.usecase.GetCameraCapabilitiesUseCase
import com.hightechif.openkamera.domain.usecase.SetZoomUseCase
import com.hightechif.openkamera.domain.usecase.SwitchCameraFacingUseCase
import com.hightechif.openkamera.domain.usecase.TapToFocusUseCase
import com.hightechif.openkamera.domain.usecase.ToggleFlashUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

/**
 * ViewModel managing reactive UI state, user actions, and hardware telemetry consumption.
 *
 * 📖 Learn more: `docs/module-05-advanced/02-reactive-metadata-flows.md`
 */
@HiltViewModel
class CameraViewModel @Inject constructor(
    private val cameraEngine: ICameraEngine,
    private val adjustExposureUseCase: AdjustExposureUseCase,
    private val toggleFlashUseCase: ToggleFlashUseCase,
    private val setZoomUseCase: SetZoomUseCase,
    private val tapToFocusUseCase: TapToFocusUseCase,
    private val switchCameraFacingUseCase: SwitchCameraFacingUseCase,
    private val getCameraCapabilitiesUseCase: GetCameraCapabilitiesUseCase,
    private val settingsRepository: ISettingsRepository,
    private val mediaRepository: IMediaRepository,
    private val sensorRepository: ISensorRepository,
    private val locationRepository: ILocationRepository? = null,
    private val remoteInputManager: IRemoteInputManager? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private val _captureState = MutableStateFlow<CaptureProgress>(CaptureProgress.Idle)
    val captureState: StateFlow<CaptureProgress> = _captureState.asStateFlow()

    private var recordingTimerJob: Job? = null

    private val _uiEffect = MutableSharedFlow<CameraUiEffect>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val uiEffect: SharedFlow<CameraUiEffect> = _uiEffect.asSharedFlow()

    /**
     * Intents for the legacy camera pipeline to execute. `replay = 0`: a command issued while
     * the Activity is not STARTED is dropped, never replayed on return.
     */
    private val _cameraCommands = MutableSharedFlow<CameraCommand>(
        replay = 0,
        extraBufferCapacity = 8
    )
    val cameraCommands: SharedFlow<CameraCommand> = _cameraCommands.asSharedFlow()

    init {
        observeRepositories()
    }

    private fun observeRepositories() {
        viewModelScope.launch {
            mediaRepository.latestMediaThumbnailFlow.collectLatest { uri ->
                _uiState.update { it.copy(latestThumbnailUri = uri) }
            }
        }

        viewModelScope.launch {
            sensorRepository.sensorOrientationFlow.collectLatest { orientation ->
                _uiState.update {
                    it.copy(
                        horizonAngle = orientation.horizonAngle,
                        compassDegrees = orientation.compassDegrees
                    )
                }
            }
        }

        locationRepository?.let { locRepo ->
            viewModelScope.launch {
                locRepo.currentLocationFlow.collectLatest { loc ->
                    _uiState.update { it.copy(location = loc) }
                }
            }
        }

        viewModelScope.launch {
            adjustExposureUseCase.exposureCompensationFlow.collectLatest { exposure ->
                _uiState.update { it.copy(exposureCompensation = exposure) }
            }
        }

        viewModelScope.launch {
            setZoomUseCase.currentZoomRatio.collectLatest { zoom ->
                _uiState.update { it.copy(zoomRatio = zoom) }
            }
        }

        viewModelScope.launch {
            setZoomUseCase.maxZoomRatio.collectLatest { maxZoom ->
                _uiState.update { it.copy(maxZoomRatio = maxZoom) }
            }
        }

        viewModelScope.launch {
            settingsRepository.flashModeFlow.collectLatest { flash ->
                _uiState.update { it.copy(flashMode = flash) }
            }
        }

        viewModelScope.launch {
            settingsRepository.gridTypeFlow.collectLatest { grid ->
                _uiState.update { it.copy(gridType = grid) }
            }
        }

        viewModelScope.launch {
            settingsRepository.captureModeFlow.collectLatest { mode ->
                _uiState.update { it.copy(captureMode = mode) }
            }
        }

        viewModelScope.launch {
            settingsRepository.isRawEnabledFlow.collectLatest { isRaw ->
                _uiState.update { it.copy(isRawEnabled = isRaw) }
            }
        }

        viewModelScope.launch {
            settingsRepository.timerSecondsFlow.collectLatest { timer ->
                _uiState.update { it.copy(timerSecondsRemaining = timer) }
            }
        }

        remoteInputManager?.let { remoteManager ->
            remoteManager.startListening()
            viewModelScope.launch {
                remoteManager.remoteInputEventFlow.collectLatest { button ->
                    if (button == RemoteButton.SHUTTER) {
                        onEvent(CameraUiEvent.OnRemoteCaptureTriggered)
                    } else {
                        onEvent(CameraUiEvent.OnRemoteButton(button))
                    }
                }
            }
        }
    }

    fun attachSurface(surface: Surface) {
        viewModelScope.launch {
            cameraEngine.attachPreviewSurface(surface)
        }
    }

    fun detachSurface() {
        viewModelScope.launch {
            cameraEngine.detachPreviewSurface()
        }
    }

    fun toggleVideoRecording() {
        emitCommand(CameraCommand.TakePicture())
    }

    fun toggleRecording() {
        toggleVideoRecording()
    }

    fun switchCameraFacing() {
        handleSwitchCameraClicked()
    }

    fun pauseVideoRecording() {
        _cameraCommands.tryEmit(CameraCommand.PauseResumeVideo)
    }

    fun resumeVideoRecording() {
        _cameraCommands.tryEmit(CameraCommand.PauseResumeVideo)
    }

    fun setZoom(ratio: Float) {
        handleZoomChanged(ratio)
    }

    fun tapToFocus(point: PointF) {
        handleTapToFocus(CameraUiEvent.OnTapToFocus(point))
    }

    fun adjustExposure(step: Int) {
        handleExposureStepChanged(step)
    }

    fun toggleFlash() {
        handleFlashToggleClicked()
    }

    fun onEvent(event: CameraUiEvent) {
        when (event) {
            is CameraUiEvent.OnShutterClicked,
            is CameraUiEvent.OnShutterKeyPressed,
            is CameraUiEvent.OnAudioTrigger -> emitCommand(CameraCommand.TakePicture())
            is CameraUiEvent.OnVideoSnapshotClicked ->
                emitCommand(CameraCommand.TakePicture(photoSnapshot = true))
            is CameraUiEvent.OnContinuousBurstRequested ->
                emitCommand(CameraCommand.TakePicture(continuousFastBurst = true))
            is CameraUiEvent.OnRemoteCaptureTriggered -> emitCommand(CameraCommand.RemoteShutter)
            is CameraUiEvent.OnRemoteButton -> emitCommand(
                if (event.button == RemoteButton.SHUTTER) CameraCommand.RemoteShutter
                else CameraCommand.RemoteButton(event.button)
            )
            is CameraUiEvent.OnFocusKeyPressed -> {
                // Focus key triggers autofocus or focus lock
            }
            is CameraUiEvent.OnVolumeKeyPressed -> {
                // Volume key action dispatched based on configured preference
            }
            is CameraUiEvent.OnRecordVideoClicked -> emitCommand(CameraCommand.TakePicture())
            is CameraUiEvent.OnPauseVideoRecordingClicked,
            is CameraUiEvent.OnResumeVideoRecordingClicked -> emitCommand(CameraCommand.PauseResumeVideo)
            is CameraUiEvent.OnSwitchCameraClicked -> handleSwitchCameraClicked()
            is CameraUiEvent.OnFlashModeToggleClicked -> handleFlashToggleClicked()
            is CameraUiEvent.OnZoomChanged -> handleZoomChanged(event.ratio)
            is CameraUiEvent.OnTapToFocus -> handleTapToFocus(event)
            is CameraUiEvent.OnExposureStepChanged -> handleExposureStepChanged(event.step)
            is CameraUiEvent.OnCaptureModeSelected -> handleCaptureModeSelected(event.mode)
            is CameraUiEvent.OnGridTypeChanged -> handleGridTypeChanged(event.gridType)
            is CameraUiEvent.OnRawToggled -> handleRawToggled(event.enabled)
            is CameraUiEvent.OnGalleryThumbnailClicked -> handleGalleryThumbnailClicked()
            is CameraUiEvent.OnSettingsClicked -> {
                _uiEffect.tryEmit(CameraUiEffect.OpenSettings)
            }
        }
    }

    /**
     * The ViewModel does not gate commands: legacy `takePicturePressed` owns the busy semantics
     * (cancel timer, stop video, finish panorama).
     */
    private fun emitCommand(command: CameraCommand) {
        _cameraCommands.tryEmit(command)
    }

    // --- Legacy pipeline callbacks: the source of truth for capture/recording state ---

    fun onLegacyCaptureStarted() {
        _uiState.update { it.copy(isCapturing = true) }
        _captureState.value = CaptureProgress.Starting
    }

    fun onLegacyCaptureCompleted() {
        _uiState.update { it.copy(isCapturing = false) }
        _captureState.value = CaptureProgress.Idle
    }

    fun onLegacyVideoStarted() {
        recordingTimerJob?.cancel()
        _uiState.update {
            it.copy(isRecording = true, isVideoPaused = false, recordingDurationSeconds = 0L)
        }
        recordingTimerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000.milliseconds)
                if (!_uiState.value.isVideoPaused) {
                    _uiState.update { state ->
                        state.copy(recordingDurationSeconds = state.recordingDurationSeconds + 1)
                    }
                }
            }
        }
    }

    fun onLegacyVideoStopped() {
        recordingTimerJob?.cancel()
        _uiState.update {
            it.copy(isRecording = false, isVideoPaused = false, recordingDurationSeconds = 0L)
        }
    }

    fun onLegacyVideoPaused(paused: Boolean) {
        _uiState.update { it.copy(isVideoPaused = paused) }
    }

    private fun handleSwitchCameraClicked() {
        viewModelScope.launch {
            val result = switchCameraFacingUseCase()
            if (result.isSuccess) {
                val newFacing = result.getOrThrow()
                _uiState.update { it.copy(facing = newFacing) }
            } else {
                _uiEffect.tryEmit(CameraUiEffect.ShowToast("Failed to switch camera"))
            }
        }
    }

    private fun handleFlashToggleClicked() {
        viewModelScope.launch {
            if (_uiState.value.isRecording) {
                val targetMode = if (_uiState.value.flashMode == FlashMode.TORCH) FlashMode.OFF else FlashMode.TORCH
                val nextMode = toggleFlashUseCase(targetMode)
                _uiState.update { it.copy(flashMode = nextMode) }
            } else {
                val nextMode = toggleFlashUseCase()
                _uiState.update { it.copy(flashMode = nextMode) }
            }
        }
    }

    private fun handleZoomChanged(ratio: Float) {
        viewModelScope.launch {
            setZoomUseCase(ratio)
        }
    }

    private fun handleTapToFocus(event: CameraUiEvent.OnTapToFocus) {
        viewModelScope.launch {
            tapToFocusUseCase.focusAtPoint(event.point)
        }
    }

    private fun handleExposureStepChanged(step: Int) {
        viewModelScope.launch {
            adjustExposureUseCase(step)
        }
    }

    private fun handleCaptureModeSelected(mode: CaptureMode) {
        viewModelScope.launch {
            settingsRepository.setCaptureMode(mode)
            _uiState.update { it.copy(captureMode = mode) }
        }
    }

    private fun handleGridTypeChanged(gridType: GridType) {
        viewModelScope.launch {
            settingsRepository.setGridType(gridType)
            _uiState.update { it.copy(gridType = gridType) }
        }
    }

    private fun handleRawToggled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setRawEnabled(enabled)
            _uiState.update { it.copy(isRawEnabled = enabled) }
        }
    }

    private fun handleGalleryThumbnailClicked() {
        val uri = _uiState.value.latestThumbnailUri
        _uiEffect.tryEmit(CameraUiEffect.NavigateToGallery(uri))
    }
}

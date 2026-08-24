/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.di

import com.hightechif.openkamera.audio.AudioControllerImpl
import com.hightechif.openkamera.domain.engine.IAudioController
import com.hightechif.openkamera.domain.engine.IRemoteInputManager
import com.hightechif.openkamera.domain.repository.ILocationRepository
import com.hightechif.openkamera.domain.repository.ISensorRepository
import com.hightechif.openkamera.remotecontrol.RemoteInputManagerImpl
import com.hightechif.openkamera.sensors.LocationRepositoryImpl
import com.hightechif.openkamera.sensors.SensorRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ServiceModule {

    @Binds
    @Singleton
    abstract fun bindSensorRepository(
        impl: SensorRepositoryImpl
    ): ISensorRepository

    @Binds
    @Singleton
    abstract fun bindLocationRepository(
        impl: LocationRepositoryImpl
    ): ILocationRepository

    @Binds
    @Singleton
    abstract fun bindAudioController(
        impl: AudioControllerImpl
    ): IAudioController

    @Binds
    @Singleton
    abstract fun bindRemoteInputManager(
        impl: RemoteInputManagerImpl
    ): IRemoteInputManager
}

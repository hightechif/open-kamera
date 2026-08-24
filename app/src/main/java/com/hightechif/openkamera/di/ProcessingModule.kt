/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.di

import com.hightechif.openkamera.domain.engine.IImageProcessor
import com.hightechif.openkamera.processing.ImageProcessorImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ProcessingModule {

    @Binds
    @Singleton
    abstract fun bindImageProcessor(
        impl: ImageProcessorImpl
    ): IImageProcessor
}

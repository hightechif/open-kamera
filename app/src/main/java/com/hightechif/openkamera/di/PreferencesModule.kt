/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.di

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import com.hightechif.openkamera.domain.repository.preferences.CameraPreferencesRepository
import com.hightechif.openkamera.domain.repository.preferences.LocationPreferencesRepository
import com.hightechif.openkamera.domain.repository.preferences.PhotoPreferencesRepository
import com.hightechif.openkamera.domain.repository.preferences.UiHudPreferencesRepository
import com.hightechif.openkamera.domain.repository.preferences.VideoPreferencesRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PreferencesModule {

    @Provides
    @Singleton
    fun provideSharedPreferences(@ApplicationContext context: Context): SharedPreferences {
        @Suppress("DEPRECATION")
        return PreferenceManager.getDefaultSharedPreferences(context)
    }

    @Provides
    @Singleton
    fun providePhotoPreferencesRepository(sharedPreferences: SharedPreferences): PhotoPreferencesRepository {
        return PhotoPreferencesRepository(sharedPreferences)
    }

    @Provides
    @Singleton
    fun provideVideoPreferencesRepository(sharedPreferences: SharedPreferences): VideoPreferencesRepository {
        return VideoPreferencesRepository(sharedPreferences)
    }

    @Provides
    @Singleton
    fun provideUiHudPreferencesRepository(sharedPreferences: SharedPreferences): UiHudPreferencesRepository {
        return UiHudPreferencesRepository(sharedPreferences)
    }

    @Provides
    @Singleton
    fun provideCameraPreferencesRepository(sharedPreferences: SharedPreferences): CameraPreferencesRepository {
        return CameraPreferencesRepository(sharedPreferences)
    }

    @Provides
    @Singleton
    fun provideLocationPreferencesRepository(sharedPreferences: SharedPreferences): LocationPreferencesRepository {
        return LocationPreferencesRepository(sharedPreferences)
    }
}

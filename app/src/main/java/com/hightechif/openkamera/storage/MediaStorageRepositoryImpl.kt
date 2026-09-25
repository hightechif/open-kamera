/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.storage

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.hightechif.openkamera.di.IoDispatcher
import com.hightechif.openkamera.domain.repository.IMediaRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaStorageRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : IMediaRepository {

    private val _latestMediaThumbnailFlow = MutableStateFlow<Uri?>(null)
    override val latestMediaThumbnailFlow: Flow<Uri?> = _latestMediaThumbnailFlow.asStateFlow()

    override suspend fun getLatestMediaUri(): Uri? = withContext(ioDispatcher) {
        _latestMediaThumbnailFlow.value ?: queryLatestMediaStoreMediaUri()
    }

    private fun queryLatestMediaStoreMediaUri(): Uri? {
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN)
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        val collectionUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        try {
            context.contentResolver.query(collectionUri, projection, null, null, sortOrder)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                        val id = cursor.getLong(idColumn)
                        val uri = ContentUris.withAppendedId(collectionUri, id)
                        _latestMediaThumbnailFlow.value = uri
                        return uri
                    }
                }
        } catch (_: Exception) {
            // Permission or querying failure
        }
        return null
    }
}

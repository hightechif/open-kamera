/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.storage

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.preference.PreferenceManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.MediaStore.Images.ImageColumns
import android.provider.MediaStore.Video
import android.provider.MediaStore.Video.VideoColumns
import android.provider.OpenableColumns
import android.system.Os
import android.util.Log
import androidx.core.content.ContextCompat
import com.hightechif.openkamera.MainActivity
import com.hightechif.openkamera.MyApplicationInterface
import com.hightechif.openkamera.preferences.PreferenceKeys
import com.hightechif.openkamera.utils.MyDebug
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.Volatile

//import android.content.ContentValues;
//import android.location.Location;

/** Provides access to the filesystem. Supports both standard and Storage
 * Access Framework.
 */
class StorageUtils internal constructor(
    private val context: Context,
    private val applicationInterface: MyApplicationInterface
) {
    var lastMediaScanned: Uri? = null // mediastore uri
        private set
    var lastMediaScannedIsRaw: Boolean = false
        private set
    var lastMediaScannedHasNoExifDateTime: Boolean = false
        private set
    var lastMediaScannedCheckUri: Uri? = null
        private set

    // for testing:
    @Volatile
    var failedToScan: Boolean = false

    fun clearLastMediaScanned() {
        if (MyDebug.LOG) Log.d(TAG, "clearLastMediaScanned")
        lastMediaScanned = null
        lastMediaScannedIsRaw = false
        lastMediaScannedHasNoExifDateTime = false
        lastMediaScannedCheckUri = null
    }

    fun setLastMediaScanned(
        uri: Uri?,
        isRaw: Boolean,
        hasnoexifdatetime: Boolean,
        checkUri: Uri?
    ) {
        lastMediaScanned = uri
        lastMediaScannedIsRaw = isRaw
        lastMediaScannedHasNoExifDateTime = hasnoexifdatetime
        if (hasnoexifdatetime) lastMediaScannedCheckUri = checkUri
        else lastMediaScannedCheckUri = null
        if (MyDebug.LOG) {
            Log.d(TAG, "set last_media_scanned to " + lastMediaScanned)
            Log.d(TAG, "    last_media_scanned_is_raw: " + lastMediaScannedIsRaw)
            Log.d(
                TAG,
                "    last_media_scanned_hasnoexifdatetime: " + lastMediaScannedHasNoExifDateTime
            )
            Log.d(
                TAG,
                "    last_media_scanned_check_uri: $checkUri"
            )
        }
    }

    /** Sends the intents to announce the new file to other Android applications. E.g., cloud storage applications like
     * OwnCloud use this to listen for new photos/videos to automatically upload.
     * Note that on Android 7 onwards, these broadcasts are deprecated and won't have any effect - see:
     * https://developer.android.com/reference/android/hardware/Camera.html#ACTION_NEW_PICTURE
     * Listeners like OwnCloud should instead be using
     * https://developer.android.com/reference/android/app/job/JobInfo.Builder.html#addTriggerContentUri(android.app.job.JobInfo.TriggerContentUri)
     * See https://github.com/owncloud/android/issues/1675 for OwnCloud's discussion on this.
     */
    fun announceUri(uri: Uri, isNewPicture: Boolean, isNewVideo: Boolean) {
        if (MyDebug.LOG) Log.d(TAG, "announceUri: $uri")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "broadcasts deprecated on Android 7 onwards, so don't send them"
            )
            // see note above; the intents won't be delivered, so might as well save the trouble of trying to send them
        } else if (isNewPicture) {
            // note, we reference the string directly rather than via Camera.ACTION_NEW_PICTURE, as the latter class is now deprecated - but we still need to broadcast the string for other apps
            context.sendBroadcast(Intent("android.hardware.action.NEW_PICTURE", uri))
            // for compatibility with some apps - apparently this is what used to be broadcast on Android?
            context.sendBroadcast(Intent("com.android.camera.NEW_PICTURE", uri))

            if (MyDebug.LOG)  // this code only used for debugging/logging
            {
                @SuppressLint("InlinedApi") val CONTENT_PROJECTION// complains this constant only available on API 29 (even though it was available on older versions, but looks like it was moved?)
                        = arrayOf(
                    MediaStore.Images.Media.DATA,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.MIME_TYPE,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.DATE_TAKEN,
                    MediaStore.Images.Media.DATE_ADDED
                )
                val c = context.contentResolver.query(uri, CONTENT_PROJECTION, null, null, null)
                if (c == null) {
                    if (MyDebug.LOG) Log.e(
                        TAG,
                        "Couldn't resolve given uri [1]: $uri"
                    )
                } else if (!c.moveToFirst()) {
                    if (MyDebug.LOG) Log.e(
                        TAG,
                        "Couldn't resolve given uri [2]: $uri"
                    )
                } else {
                    val filePath =
                        c.getString(c.getColumnIndexOrThrow(MediaStore.Images.Media.DATA))
                    val fileName =
                        c.getString(c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
                    val mimeType =
                        c.getString(c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE))
                    @SuppressLint("InlinedApi") val dateTaken// complains this constant only available on API 29 (even though it was available on older versions, but looks like it was moved?)
                            = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN))
                    val dateAdded =
                        c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED))
                    Log.d(TAG, "file_path: $filePath")
                    Log.d(TAG, "file_name: $fileName")
                    Log.d(TAG, "mime_type: $mimeType")
                    Log.d(TAG, "date_taken: $dateTaken")
                    Log.d(TAG, "date_added: $dateAdded")
                    c.close()
                }
            }
            /*{
 				// hack: problem on Camera2 API (at least on Nexus 6) that if geotagging is enabled, then the resultant image has incorrect Exif TAG_GPS_DATESTAMP (GPSDateStamp) set (tends to be around 2038 - possibly a driver bug of casting long to int?)
 				// whilst we don't yet correct for that bug, the more immediate problem is that it also messes up the DATE_TAKEN field in the media store, which messes up Gallery apps
 				// so for now, we correct it based on the DATE_ADDED value.
    	        String[] CONTENT_PROJECTION = { Images.Media.DATE_ADDED };
    	        Cursor c = context.getContentResolver().query(uri, CONTENT_PROJECTION, null, null, null);
    	        if( c == null ) {
		 			if( MyDebug.LOG )
		 				Log.e(TAG, "Couldn't resolve given uri [1]: " + uri);
    	        }
    	        else if( !c.moveToFirst() ) {
		 			if( MyDebug.LOG )
		 				Log.e(TAG, "Couldn't resolve given uri [2]: " + uri);
    	        }
    	        else {
        	        long dateAdded = c.getLong(c.getColumnIndex(Images.Media.DATE_ADDED));
		 			if( MyDebug.LOG )
		 				Log.e(TAG, "replace dateTaken with dateAdded: " + dateAdded);
					ContentValues values = new ContentValues();
					values.put(Images.Media.DATE_TAKEN, dateAdded*1000);
					context.getContentResolver().update(uri, values, null, null);
        	        c.close();
    	        }
 			}*/
        } else if (isNewVideo) {
            context.sendBroadcast(Intent("android.hardware.action.NEW_VIDEO", uri))

            /*String[] CONTENT_PROJECTION = { Video.Media.DURATION };
	        Cursor c = context.getContentResolver().query(uri, CONTENT_PROJECTION, null, null, null);
	        if( c == null ) {
	 			if( MyDebug.LOG )
	 				Log.e(TAG, "Couldn't resolve given uri [1]: " + uri);
	        }
	        else if( !c.moveToFirst() ) {
	 			if( MyDebug.LOG )
	 				Log.e(TAG, "Couldn't resolve given uri [2]: " + uri);
	        }
	        else {
    	        long duration = c.getLong(c.getColumnIndex(Video.Media.DURATION));
	 			if( MyDebug.LOG )
	 				Log.e(TAG, "replace duration: " + duration);
				ContentValues values = new ContentValues();
				values.put(Video.Media.DURATION, 1000);
				context.getContentResolver().update(uri, values, null, null);
    	        c.close();
	        }*/
        }
    }

    /*public Uri broadcastFileRaw(File file, Date currentDate, Location location) {
		if( MyDebug.LOG )
			Log.d(TAG, "broadcastFileRaw: " + file.getAbsolutePath());
        ContentValues values = new ContentValues();
        values.put(ImageColumns.TITLE, file.getName().substring(0, file.getName().lastIndexOf(".")));
        values.put(ImageColumns.DISPLAY_NAME, file.getName());
        values.put(ImageColumns.DATE_TAKEN, current_date.getTime());
        values.put(ImageColumns.MIME_TYPE, "image/dng");
        //values.put(ImageColumns.MIME_TYPE, "image/jpeg");
        if( location != null ) {
            values.put(ImageColumns.LATITUDE, location.getLatitude());
            values.put(ImageColumns.LONGITUDE, location.getLongitude());
        }
        // leave ORIENTATION for now - this doesn't seem to get inserted for JPEGs anyway (via MediaScannerConnection.scanFile())
        values.put(ImageColumns.DATA, file.getAbsolutePath());
        //values.put(ImageColumns.DATA, "/storage/emulated/0/DCIM/OpenKamera/blah.dng");
        Uri uri = null;
        try {
    		uri = context.getContentResolver().insert(Images.Media.EXTERNAL_CONTENT_URI, values);
 			if( MyDebug.LOG )
 				Log.d(TAG, "inserted media uri: " + uri);
    		context.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri));
        }
        catch (Throwable th) {
	        // This can happen when the external volume is already mounted, but
	        // MediaScanner has not notify MediaProvider to add that volume.
	        // The picture is still safe and MediaScanner will find it and
	        // insert it into MediaProvider. The only problem is that the user
	        // cannot click the thumbnail to review the picture.
	        Log.e(TAG, "Failed to write MediaStore" + th);
	    }
        return uri;
	}*/
    /** Sends a "broadcast" for the new file. This is necessary so that Android recognises the new file without needing a reboot:
     * - So that they show up when connected to a PC using MTP.
     * - For JPEGs, so that they show up in gallery applications.
     * - This also calls announceUri() on the resultant Uri for the new file.
     * - Note this should also be called after deleting a file.
     * - Note that for DNG files, MediaScannerConnection.scanFile() doesn't result in the files being shown in gallery applications.
     * This may well be intentional, since most gallery applications won't read DNG files anyway. But it's still important to
     * call this function for DNGs, so that they show up on MTP.
     */
    fun broadcastFile(
        file: File,
        isNewPicture: Boolean,
        isNewVideo: Boolean,
        setLastScanned: Boolean,
        hasnoexifdatetime: Boolean,
        safUri: Uri?
    ) {
        if (MyDebug.LOG) {
            Log.d(TAG, "broadcastFile: " + file.absolutePath)
            Log.d(TAG, "saf_uri: $safUri")
        }
        // note that the new method means that the new folder shows up as a file when connected to a PC via MTP (at least tested on Windows 8)
        if (file.isDirectory) {
            //this.sendBroadcast(new Intent(Intent.ACTION_MEDIA_MOUNTED, Uri.fromFile(file)));
            // ACTION_MEDIA_MOUNTED no longer allowed on Android 4.4! Gives: SecurityException: Permission Denial: not allowed to send broadcast android.intent.action.MEDIA_MOUNTED
            // note that we don't actually need to broadcast anything, the folder and contents appear straight away (both in Gallery on device, and on a PC when connecting via MTP)
            // also note that we definitely don't want to broadcast ACTION_MEDIA_SCANNER_SCAN_FILE or use scanFile() for folders, as this means the folder shows up as a file on a PC via MTP (and isn't fixed by rebooting!)
        } else {
            // both of these work fine, but using MediaScannerConnection.scanFile() seems to be preferred over sending an intent
            //context.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(file)));
            failedToScan = true // set to true until scanned okay
            if (MyDebug.LOG) Log.d(TAG, "failed_to_scan set to true")
            MediaScannerConnection.scanFile(
                context, arrayOf(file.absolutePath), null
            ) { path, uri ->
                var uri = uri
                failedToScan = false
                if (MyDebug.LOG) {
                    Log.d(TAG, "Scanned $path:")
                    Log.d(TAG, "-> uri=$uri")
                }
                if (safUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Prefer using MediaStore.getMediaUri() to get the mediastore URI from a SAF URI.
                    // Fixes bug on Pixel 6 Pro with SAF where the URI recieved by onScanCompleted() is
                    // of the form =content://media/externalPrimary/images/media/123456, when this is not
                    // recognised by gallery apps (causes strange bug where clicking on gallery icon opens
                    // contacts!) The correct URI is returned by MediaStore.getMediaUri(), and (for
                    // Pixel 6 Pro at least) is of the form content://media/externalPrimary/file/123456.
                    try {
                        val mediaUriFromSafUri = MediaStore.getMediaUri(context, safUri)
                        if (mediaUriFromSafUri != null) {
                            uri = mediaUriFromSafUri
                            if (MyDebug.LOG) {
                                Log.d(
                                    TAG,
                                    "prefer getMediaUri from SAF: $uri"
                                )
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                if (setLastScanned) {
                    val isRaw = filenameIsRaw(file.name)
                    setLastMediaScanned(uri, isRaw, hasnoexifdatetime, safUri ?: uri)
                }
                announceUri(uri, isNewPicture, isNewVideo)
                applicationInterface.scannedFile(file, uri)

                // If called from video intent, if not using scoped-storage, we'll have saved using File API (even if user preference is SAF), see
                // MyApplicationInterface.createOutputVideoMethod().
                // It seems caller apps seem to prefer the content:// Uri rather than one based on a File
                // update for Android 7: seems that passing file uris is now restricted anyway, see https://code.google.com/p/android/issues/detail?id=203555
                // So we pass the uri back to the caller here.
                val activity = context as Activity
                val action = activity.intent.action
                if (!MainActivity.useScopedStorage() && MediaStore.ACTION_VIDEO_CAPTURE == action) {
                    applicationInterface.finishVideoIntent(uri)
                }
            }
        }
    }

    /** Wrapper for broadcastFile, when we only have a Uri (e.g., for SAF)
     */
    fun broadcastUri(
        uri: Uri,
        isNewPicture: Boolean,
        isNewVideo: Boolean,
        setLastScanned: Boolean,
        hasnoexifdatetime: Boolean,
        imageCaptureIntent: Boolean
    ) {
        if (MyDebug.LOG) Log.d(TAG, "broadcastUri: $uri")
        /* We still need to broadcastFile for SAF for various reasons:
            1. To call storageUtils.announceUri() to broadcast NEW_PICTURE etc.
               Whilst in theory we could do this directly, it seems external apps that use such broadcasts typically
               won't know what to do with a SAF based Uri (e.g, Owncloud crashes!) so better to broadcast the Uri
               corresponding to the real file, if it exists.
            2. Whilst the new file seems to be known by external apps such as Gallery without having to call media
               scanner, I've had reports this doesn't happen when saving to external SD cards. So better to explicitly
               scan.
            3. If setLastScanned==true, it means we get the media uri which can be used to set the thumbnail uri
               (see setLastMediaScanned()). This is particularly important when using SAF with scoped storage, as
               getting the latest media via SAF APIs is (if not cached) very slow! N.B., most gallery apps need a
               mediastore uri, not the SAF uri.
        */
        val realFile = getFileFromDocumentUriSAF(uri, false)
        if (MyDebug.LOG) Log.d(TAG, "real_file: $realFile")
        if (realFile != null) {
            if (MyDebug.LOG) Log.d(TAG, "broadcast file")
            //Uri mediaUri = broadcastFileRaw(realFile, currentDate, location);
            //announceUri(mediaUri, isNewPicture, isNewVideo);
            broadcastFile(
                realFile,
                isNewPicture,
                isNewVideo,
                setLastScanned,
                hasnoexifdatetime,
                uri
            )
        } else if (!imageCaptureIntent) {
            if (MyDebug.LOG) Log.d(TAG, "announce SAF uri")
            // shouldn't do this for an image capture intent - e.g., causes crash when calling from Google Keep
            announceUri(uri, isNewPicture, isNewVideo)
        }
    }

    val isUsingSAF: Boolean
        get() {
            run {
                val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
                if (sharedPreferences.getBoolean(PreferenceKeys.USING_SAF_PREFERENCE_KEY, false)) {
                    return true
                }
            }
            return false
        }

    val saveLocation: String
        // only valid if !isUsingSAF()
        get() {
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
            return sharedPreferences.getString(
                PreferenceKeys.SAVE_LOCATION_PREFERENCE_KEY,
                "OpenKamera"
            )!!
        }

    val saveLocationSAF: String
        // only valid if isUsingSAF()
        get() {
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
            return sharedPreferences.getString(PreferenceKeys.SAVE_LOCATION_SAF_PREFERENCE_KEY, "")!!
        }

    val treeUriSAF: Uri
        // only valid if isUsingSAF()
        get() {
            val folderName = saveLocationSAF
            return Uri.parse(folderName)
        }

    val settingsFolder: File
        get() = File(context.getExternalFilesDir(null), "backups")

    val imageFolderPath: String?
        /** Valid whether or not isUsingSAF().
         * Returns the absolute path (in File format) of the image save folder.
         * Only use this for needing e.g. human-readable strings for UI.
         * This should not be used to create a File - instead, use getImageFolder().
         * Note that if isUsingSAF(), this may return null - it can't be assumed that there is a
         * File corresponding to the SAF Uri.
         */
        get() {
            val file = imageFolder
            return file?.absolutePath
        }

    val imageFolder: File
        /** Valid whether or not isUsingSAF().
         * But note that if isUsingSAF(), this may return null - it can't be assumed that there is a
         * File corresponding to the SAF Uri.
         */
        get() {
            val file: File
            if (isUsingSAF) {
                val uri = treeUriSAF
                /*if( MyDebug.LOG )
                     Log.d(TAG, "uri: " + uri);*/
                file = getFileFromDocumentUriSAF(uri, true) ?: File("")
            } else {
                val folderName = saveLocation
                file = getImageFolder(folderName)
            }
            return file
        }

    val saveRelativeFolder: String
        // only valid if !isUsingSAF()
        get() {
            val folderName = saveLocation
            return getSaveRelativeFolder(folderName)
        }

    /** Only valid if isUsingSAF()
     * Returns the absolute path (in File format) of the SAF folder.
     * Only use this for needing e.g. human-readable strings for UI.
     * This should not be used to create a File - instead, use getFileFromDocumentUriSAF().
     */
    fun getFilePathFromDocumentUriSAF(uri: Uri, isFolder: Boolean): String? {
        val file = getFileFromDocumentUriSAF(uri, isFolder)
        return file?.absolutePath
    }

    /** Only valid if isUsingSAF()
     * This function should only be used as a last resort - we shouldn't generally assume that a Uri represents an actual File, or that
     * the File can be obtained anyway.
     * However this is needed for a workaround to the fact that deleting a document file doesn't remove it from MediaStore.
     * See:
     * http://stackoverflow.com/questions/21605493/storage-access-framework-does-not-update-mediascanner-mtp
     * http://stackoverflow.com/questions/20067508/get-real-path-from-uri-android-kitkat-new-storage-access-framework/
     * Note that when using Android Q's scoped storage, the returned File will be inaccessible. However we still sometimes call this,
     * e.g., to scan with mediascanner or get a human readable string for the path.
     * Also note that this will return null for media store Uris with Android Q's scoped storage: https://developer.android.com/preview/privacy/scoped-storage
     * "The DATA column is redacted for each file in the media store."
     */
    fun getFileFromDocumentUriSAF(uri: Uri, isFolder: Boolean): File? {
        if (MyDebug.LOG) {
            Log.d(TAG, "getFileFromDocumentUriSAF: $uri")
            Log.d(TAG, "is_folder?: $isFolder")
        }
        val authority = uri.authority
        if (MyDebug.LOG) {
            Log.d(TAG, "authority: $authority")
            Log.d(TAG, "scheme: " + uri.scheme)
            Log.d(TAG, "fragment: " + uri.fragment)
            Log.d(TAG, "path: " + uri.path)
            Log.d(TAG, "last path segment: " + uri.lastPathSegment)
        }
        var file: File? = null
        if ("com.android.externalstorage.documents" == authority) {
            val id =
                if (isFolder) DocumentsContract.getTreeDocumentId(uri) else DocumentsContract.getDocumentId(
                    uri
                )
            if (MyDebug.LOG) Log.d(TAG, "id: $id")
            val split = id.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            if (split.size >= 1) {
                val type = split[0]
                val path = if (split.size >= 2) split[1] else ""
                /*if( MyDebug.LOG ) {
					Log.d(TAG, "type: " + type);
					Log.d(TAG, "path: " + path);
				}*/
                val storagePoints = File("/storage").listFiles()

                if ("primary".equals(type, ignoreCase = true)) {
                    val externalStorage = Environment.getExternalStorageDirectory()
                    file = File(externalStorage, path)
                }
                var i = 0
                while (storagePoints != null && i < storagePoints.size && file == null) {
                    val externalFile = File(storagePoints[i], path)
                    if (externalFile.exists()) {
                        file = externalFile
                    }
                    i++
                }
                if (file == null) {
                    // just in case?
                    file = File(path)
                }
            }
        } else if ("com.android.providers.downloads.documents" == authority) {
            if (!isFolder) {
                val id = DocumentsContract.getDocumentId(uri)
                if (id.startsWith("raw:")) {
                    // unclear if this is needed for Open Kamera, but on Vibrance HDR
                    // on some devices (at least on a Chromebook), I've had reports of id being of the form
                    // "raw:/storage/emulated/0/Download/..."
                    val filename = id.replaceFirst("raw:".toRegex(), "")
                    file = File(filename)
                } else {
                    try {
                        val contentUri = ContentUris.withAppendedId(
                            Uri.parse("content://downloads/public_downloads"),
                            id.toLong()
                        )

                        val filename = getDataColumn(contentUri, null, null)
                        if (filename != null) file = File(filename)
                    } catch (e: NumberFormatException) {
                        // have had crashes from Google Play from Long.parseLong(id)
                        Log.e(TAG, "failed to parse id: $id")
                        e.printStackTrace()
                    }
                }
            } else {
                if (MyDebug.LOG) Log.d(TAG, "downloads uri not supported for folders")
                // This codepath can be reproduced by enabling SAF and selecting Downloads.
                // DocumentsContract.getDocumentId() throws IllegalArgumentException for
                // this (content://com.android.providers.downloads.documents/tree/downloads).
                // If we use DocumentsContract.getTreeDocumentId() for folders, it returns
                // "downloads" - not clear how to parse this!
            }
        } else if ("com.android.providers.media.documents" == authority) {
            val docId = DocumentsContract.getDocumentId(uri)
            val split = docId.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            val type = split[0]

            var contentUri: Uri? = null
            when (type) {
                "image" -> contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                "video" -> contentUri = Video.Media.EXTERNAL_CONTENT_URI
                "audio" -> contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }

            val selection = "_id=?"
            val selectionArgs = arrayOf(
                split[1]
            )

            val filename = getDataColumn(contentUri!!, selection, selectionArgs)
            if (filename != null) file = File(filename)
        }

        if (MyDebug.LOG) {
            if (file != null) Log.d(TAG, "file: " + file.absolutePath)
            else Log.d(TAG, "failed to find file")
        }
        return file
    }

    private fun getDataColumn(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?
    ): String? {
        val column = ImageColumns.DATA
        val projection = arrayOf(
            column
        )

        var cursor: Cursor? = null
        try {
            cursor =
                context.contentResolver.query(
                    uri, projection, selection, selectionArgs,
                    null
                )
            if (cursor != null && cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndexOrThrow(column)
                return cursor.getString(columnIndex)
            }
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
        } catch (e: SecurityException) {
            // have received crashes from Google Play for this
            e.printStackTrace()
        } finally {
            cursor?.close()
        }
        return null
    }

    /** Returns the filename (but not full path) for a Uri.
     * See https://developer.android.com/guide/topics/providers/document-provider.html and
     * http://stackoverflow.com/questions/5568874/how-to-extract-the-file-name-from-uri-returned-from-intent-action-get-content .
     */
    fun getFileName(uri: Uri): String {
        if (MyDebug.LOG) {
            Log.d(TAG, "getFileName: $uri")
            Log.d(TAG, "uri has path: " + uri.path)
        }
        var result: String? = null
        if (uri.scheme != null && uri.scheme == "content") {
            var cursor: Cursor? = null
            try {
                cursor = context.contentResolver.query(uri, null, null, null, null)
                if (cursor != null && cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                    result = cursor.getString(columnIndex)
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "found name from database: $result"
                    )
                }
            } catch (e: Exception) {
                if (MyDebug.LOG) Log.e(TAG, "Exception trying to find filename")
                e.printStackTrace()
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            if (MyDebug.LOG) Log.d(TAG, "resort to checking the uri's path")
            result = uri.path
            val cut = result!!.lastIndexOf('/')
            if (cut != -1) {
                result = result.substring(cut + 1)
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "found name from path: $result"
                )
            }
        }
        return result
    }

    fun createMediaFilename(
        type: Int,
        suffix: String,
        count: Int,
        extension: String,
        currentDate: Date?
    ): String {
        var index = ""
        if (count > 0) {
            index = "_$count" // try to find a unique filename
        }
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val useZuluTime =
            sharedPreferences.getString(PreferenceKeys.SAVE_ZULU_TIME_PREFERENCE_KEY, "local") == "zulu"
        val includeMilliseconds =
            sharedPreferences.getBoolean(PreferenceKeys.SAVE_INCLUDE_MILLISECONDS_PREFERENCE_KEY, false)
        var dateFormatPattern = "yyyyMMdd_HHmmss"
        if (includeMilliseconds) {
            dateFormatPattern += ".SSS"
        }
        val timeStamp: String
        if (useZuluTime) {
            val fmt = SimpleDateFormat("$dateFormatPattern'Z'", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            timeStamp = fmt.format(currentDate)
        } else {
            timeStamp = SimpleDateFormat(dateFormatPattern, Locale.US).format(currentDate)
        }
        val mediaFilename: String
        when (type) {
            MEDIA_TYPE_GYRO_INFO, MEDIA_TYPE_PRESHOT, MEDIA_TYPE_IMAGE -> {
                val prefix =
                    sharedPreferences.getString(
                        PreferenceKeys.SAVE_PHOTO_PREFIX_PREFERENCE_KEY,
                        "IMG_"
                    )!!
                mediaFilename = prefix + timeStamp + suffix + index + extension
            }

            MEDIA_TYPE_VIDEO -> {
                val prefix =
                    sharedPreferences.getString(
                        PreferenceKeys.SAVE_VIDEO_PREFIX_PREFERENCE_KEY,
                        "VID_"
                    )!!
                mediaFilename = prefix + timeStamp + suffix + index + extension
            }

            MEDIA_TYPE_PREFS -> {
                // good to use a prefix that sorts before IMG_ and VID_: annoyingly when using SAF, it doesn't seem possible to
                // only show the xml files, and it always defaults to sorting alphabetically...
                val prefix = "BACKUP_OC_"
                mediaFilename = prefix + timeStamp + suffix + index + extension
            }

            else -> {
                // throw exception as this is a programming error
                if (MyDebug.LOG) Log.e(
                    TAG,
                    "unknown type: $type"
                )
                throw RuntimeException()
            }
        }
        return mediaFilename
    }

    // only valid if !isUsingSAF()
    @Throws(IOException::class)
    fun createOutputMediaFile(
        type: Int,
        suffix: String,
        extension: String,
        currentDate: Date?
    ): File {
        val mediaStorageDir = imageFolder
        return createOutputMediaFile(mediaStorageDir!!, type, suffix, extension, currentDate)
    }

    /** Create the folder if it does not exist.
     */
    @Throws(IOException::class)
    fun createFolderIfRequired(folder: File) {
        if (!folder.exists()) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "create directory: $folder"
            )
            if (!folder.mkdirs()) {
                Log.e(TAG, "failed to create directory")
                throw IOException()
            }
            broadcastFile(folder, false, false, false, false, null)
        }
    }

    // only valid if !isUsingSAF
    @SuppressLint("SimpleDateFormat")
    @Throws(IOException::class)
    fun createOutputMediaFile(
        mediaStorageDir: File,
        type: Int,
        suffix: String,
        extension: String,
        currentDate: Date?
    ): File {
        createFolderIfRequired(mediaStorageDir)

        // Create a media file name
        var mediaFile: File? = null
        for (count in 0..99) {
            /*final boolean useBurstFolder = true;
        	if( useBurstFolder ) {
				String burstFolderName = createMediaFilename(type, "", count, "", currentDate);
				File burstFolder = new File(mediaStorageDir.getPath() + File.separator + burstFolderName);
				if( !burstFolder.exists() ) {
					if( !burstFolder.mkdirs() ) {
						if( MyDebug.LOG )
							Log.e(TAG, "failed to create burst sub-directory");
						throw new IOException();
					}
					broadcastFile(burstFolder, false, false, false);
				}

				SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
				String prefix = sharedPreferences.getString(PreferenceKeys.getSavePhotoPrefixPreferenceKey(), "IMG_");
				//String mediaFilename = prefix + suffix + "." + extension;
				String suffixAlt = suffix.substring(1);
				String mediaFilename = suffixAlt + prefix + suffixAlt + "BURST" + "." + extension;
				mediaFile = new File(burstFolder.getPath() + File.separator + mediaFilename);
			}
			else*/
            run {
                val mediaFilename = createMediaFilename(
                    type, suffix, count,
                    ".$extension", currentDate
                )
                mediaFile = File(mediaStorageDir.path + File.separator + mediaFilename)
            }
            if (!mediaFile!!.exists()) {
                break
            }
        }

        if (MyDebug.LOG) {
            Log.d(TAG, "getOutputMediaFile returns: $mediaFile")
        }
        if (mediaFile == null) throw IOException()
        return mediaFile!!
    }

    // only valid if isUsingSAF()
    @Throws(IOException::class)
    fun createOutputFileSAF(filename: String, mimeType: String): Uri {
        try {
            val treeUri = treeUriSAF
            if (MyDebug.LOG) Log.d(TAG, "treeUri: $treeUri")
            val docUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            )
            if (MyDebug.LOG) Log.d(TAG, "docUri: $docUri")
            // note that DocumentsContract.createDocument will automatically append to the filename if it already exists
            val fileUri = DocumentsContract.createDocument(
                context.contentResolver,
                docUri,
                mimeType,
                filename
            )
            if (MyDebug.LOG) Log.d(
                TAG,
                "returned fileUri: $fileUri"
            )
            /*if( true )
				throw new SecurityException(); // test*/
            if (fileUri == null) throw IOException()
            return fileUri
        } catch (e: IllegalArgumentException) {
            // DocumentsContract.getTreeDocumentId throws this if URI is invalid
            if (MyDebug.LOG) Log.e(
                TAG,
                "createOutputMediaFileSAF failed with IllegalArgumentException"
            )
            e.printStackTrace()
            throw IOException()
        } catch (e: IllegalStateException) {
            // Have reports of this from Google Play for DocumentsContract.createDocument - better to fail gracefully and tell user rather than crash!
            if (MyDebug.LOG) Log.e(
                TAG,
                "createOutputMediaFileSAF failed with IllegalStateException"
            )
            e.printStackTrace()
            throw IOException()
        } catch (e: NullPointerException) {
            // Have reports of this from Google Play for DocumentsContract.createDocument - better to fail gracefully and tell user rather than crash!
            if (MyDebug.LOG) Log.e(TAG, "createOutputMediaFileSAF failed with NullPointerException")
            e.printStackTrace()
            throw IOException()
        } catch (e: SecurityException) {
            // Have reports of this from Google Play - better to fail gracefully and tell user rather than crash!
            if (MyDebug.LOG) Log.e(TAG, "createOutputMediaFileSAF failed with SecurityException")
            e.printStackTrace()
            throw IOException()
        }
    }

    /** Return the mime type corresponding to the supplied extension. Supports images only, not video.
     */
    fun getImageMimeType(extension: String): String {
        val mimeType = when (extension) {
            "dng" -> "image/dng"
            "webp" -> "image/webp"
            "png" -> "image/png"
            else -> "image/jpeg"
        }
        return mimeType
    }

    /** Return the mime type corresponding to the supplied extension. Supports video only, not images.
     */
    fun getVideoMimeType(extension: String?): String {
        val mimeType = when (extension) {
            "3gp" -> "video/3gpp"
            "webm" -> "video/webm"
            else -> "video/mp4"
        }
        return mimeType
    }

    // only valid if isUsingSAF()
    @Throws(IOException::class)
    fun createOutputMediaFileSAF(
        type: Int,
        suffix: String,
        extension: String,
        currentDate: Date?
    ): Uri {
        val mimeType = when (type) {
            MEDIA_TYPE_IMAGE -> getImageMimeType(extension)
            MEDIA_TYPE_PRESHOT, MEDIA_TYPE_VIDEO -> getVideoMimeType(extension)
            MEDIA_TYPE_PREFS, MEDIA_TYPE_GYRO_INFO -> "text/xml"
            else -> {
                // throw exception as this is a programming error
                if (MyDebug.LOG) Log.e(
                    TAG,
                    "unknown type: $type"
                )
                throw RuntimeException()
            }
        }
        // note that DocumentsContract.createDocument will automatically append to the filename if it already exists
        val mediaFilename = createMediaFilename(
            type, suffix, 0,
            ".$extension", currentDate
        )
        return createOutputFileSAF(mediaFilename, mimeType)
    }

    class Media(// whether uri is from mediastore
        val mediastore: Boolean, // for mediastore==true only
        val id: Long,
        val video: Boolean,
        val uri: Uri,
        val date: Long, // for mediastore==true, video==false only
        val orientation: Int,
        filename: String?
    ) {
        // this should correspond to DISPLAY_NAME (so available with scoped storage) - so this includes file extension, but not full path
        val filename: String = filename!!

        /** Returns a mediastore uri. If this Media object was not created by a mediastore uri, then
         * this will try to convert using MediaStore.getMediaUri(), but if this fails the function
         * will return null.
         */
        fun getMediaStoreUri(context: Context): Uri? {
            if (this.mediastore) return this.uri
            else {
                try {
                    // should only have allowed mediastore==null when using scoped storage
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        return MediaStore.getMediaUri(context, this.uri)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                return null
            }
        }
    }

    private enum class UriType {
        MEDIASTORE_IMAGES,
        MEDIASTORE_VIDEOS
    }

    private fun getLatestMediaCore(baseUri: Uri, bucketId: String?, uriType: UriType): Media? {
        if (MyDebug.LOG) {
            Log.d(TAG, "getLatestMediaCore")
            Log.d(TAG, "baseUri: $baseUri")
            Log.d(TAG, "bucket_id: $bucketId")
            Log.d(TAG, "uri_type: $uriType")
        }
        var media: Media? = null

        val columnIdC = 0
        val columnDateTakenC = 1
        /*final int columnDataC = 2; // full path and filename, including extension
        final int columnNameC = 3; // filename (without path), including extension
        final int columnOrientationC = 4; // for images only*/
        val columnNameC = 2 // filename (without path), including extension
        val columnOrientationC = 3 // for mediastore images only
        val projection = when (uriType) {
            UriType.MEDIASTORE_IMAGES -> arrayOf(
                ImageColumns._ID,
                ImageColumns.DATE_TAKEN,
                ImageColumns.DISPLAY_NAME,
                ImageColumns.ORIENTATION
            )

            UriType.MEDIASTORE_VIDEOS -> arrayOf(
                VideoColumns._ID,
                VideoColumns.DATE_TAKEN,
                VideoColumns.DISPLAY_NAME
            )

            else -> throw RuntimeException("unknown uri_type: $uriType")
        }
        // for images, we need to search for JPEG/etc and RAW, to support RAW only mode (even if we're not currently in that mode, it may be that previously the user did take photos in RAW only mode)
        // if updating this code for supported mime types, remember to also update getLatestMediaSAF()
        /*String selection = video ? "" : ImageColumns.MIME_TYPE + "='image/jpeg' OR " +
                ImageColumns.MIME_TYPE + "='image/webp' OR " +
                ImageColumns.MIME_TYPE + "='image/png' OR " +
                ImageColumns.MIME_TYPE + "='image/x-adobe-dng'";*/
        var selection = ""
        when (uriType) {
            UriType.MEDIASTORE_IMAGES -> {
                if (bucketId != null) selection = ImageColumns.BUCKET_ID + " = " + bucketId
                val and = selection.length > 0
                if (and) selection += " AND ( "
                selection += ImageColumns.MIME_TYPE + "='image/jpeg' OR " +
                        ImageColumns.MIME_TYPE + "='image/webp' OR " +
                        ImageColumns.MIME_TYPE + "='image/png' OR " +
                        ImageColumns.MIME_TYPE + "='image/x-adobe-dng'"
                if (and) selection += " )"
            }

            UriType.MEDIASTORE_VIDEOS -> if (bucketId != null) selection =
                VideoColumns.BUCKET_ID + " = " + bucketId

            else -> throw RuntimeException("unknown uri_type: $uriType")
        }
        if (MyDebug.LOG) Log.d(TAG, "selection: $selection")
        val order = when (uriType) {
            UriType.MEDIASTORE_IMAGES -> ImageColumns.DATE_TAKEN + " DESC," + ImageColumns._ID + " DESC"
            UriType.MEDIASTORE_VIDEOS ->
                VideoColumns.DATE_TAKEN + " DESC," + VideoColumns._ID + " DESC"

            else -> throw RuntimeException("unknown uri_type: $uriType")
        }
        var cursor: Cursor? = null

        // we know we only want the most recent image - however we may need to scan forward if we find a RAW, to see if there's
        // an equivalent non-RAW image
        // request 3, just in case
        val queryUri = baseUri.buildUpon().appendQueryParameter("limit", "3").build()
        if (MyDebug.LOG) Log.d(TAG, "queryUri: $queryUri")

        try {
            cursor = context.contentResolver.query(queryUri, projection, selection, null, order)
            if (cursor != null && cursor.moveToFirst()) {
                if (MyDebug.LOG) Log.d(TAG, "found: " + cursor.count)

                // now sorted in order of date - so just pick the most recent one

                /*
                // now sorted in order of date - scan to most recent one in the Open Kamera save folder
                boolean found = false;
                //File saveFolder = getImageFolder(); // may be null if using SAF
                String saveFolderString = saveFolder == null ? null : save_folder.getAbsolutePath() + File.separator;
                if( MyDebug.LOG )
                    Log.d(TAG, "saveFolderString: " + saveFolderString);
                do {
                    String path = cursor.getString(columnDataC);
                    if( MyDebug.LOG )
                        Log.d(TAG, "path: " + path);
                    // path may be null on Android 4.4!: http://stackoverflow.com/questions/3401579/get-filename-and-path-from-uri-from-mediastore
                    if( saveFolderString == null || (path != null && path.contains(saveFolderString) ) ) {
                        if( MyDebug.LOG )
                            Log.d(TAG, "found most recent in Open Kamera folder");
                        // we filter files with dates in future, in case there exists an image in the folder with incorrect datestamp set to the future
                        // we allow up to 2 days in future, to avoid risk of issues to do with timezone etc
                        long date = cursor.getLong(columnDateTakenC);
                        long currentTime = System.currentTimeMillis();
                        if( date > currentTime + 172800000 ) {
                            if( MyDebug.LOG )
                                Log.d(TAG, "skip date in the future!");
                        }
                        else {
                            found = true;
                            break;
                        }
                    }
                }
                while( cursor.moveToNext() );

                if( !found ) {
                    if( MyDebug.LOG )
                        Log.d(TAG, "can't find suitable in Open Kamera folder, so just go with most recent");
                    cursor.moveToFirst();
                }
                */
                run {
                    // make sure we prefer JPEG/etc (non RAW) if there's a JPEG/etc version of this image
                    // this is because we want to support RAW only and JPEG+RAW modes
                    val filename = cursor.getString(columnNameC)
                    if (MyDebug.LOG) {
                        Log.d(TAG, "filename: $filename")
                    }
                    // in theory now that we use DISPLAY_NAME instead of DATA (for path), this should always be non-null, but check just in case
                    if (filename != null && filenameIsRaw(filename)) {
                        if (MyDebug.LOG) Log.d(TAG, "try to find a non-RAW version of the DNG")
                        val dngPos = cursor.position
                        var foundNonRaw = false
                        val filenameWithoutExt = filenameWithoutExtension(filename)
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "filename_without_ext: $filenameWithoutExt"
                        )
                        while (cursor.moveToNext()) {
                            val nextFilename = cursor.getString(columnNameC)
                            if (MyDebug.LOG) Log.d(
                                TAG,
                                "next_filename: $nextFilename"
                            )
                            if (nextFilename == null) {
                                if (MyDebug.LOG) Log.d(TAG, "done scanning, couldn't find filename")
                                break
                            }
                            val nextFilenameWithoutExt = filenameWithoutExtension(nextFilename)
                            if (MyDebug.LOG) Log.d(
                                TAG,
                                "next_filename_without_ext: $nextFilenameWithoutExt"
                            )
                            if (filenameWithoutExt != nextFilenameWithoutExt) {
                                // no point scanning any further as sorted by date - and we don't want to read through the entire set!
                                if (MyDebug.LOG) Log.d(TAG, "done scanning")
                                break
                            }
                            // so we've found another file with matching filename - is it a JPEG/etc?
                            // we've already restricted the query to the image types we're interested in, so
                            // only need to check that it isn't another DNG (which would be strange, as it
                            // would mean a duplicate filename, but check just in case!)
                            if (filenameIsRaw(nextFilename)) {
                                if (MyDebug.LOG) Log.d(TAG, "found another dng!")
                            } else {
                                if (MyDebug.LOG) Log.d(TAG, "found equivalent non-dng")
                                foundNonRaw = true
                                break
                            }
                        }
                        if (!foundNonRaw) {
                            if (MyDebug.LOG) Log.d(TAG, "can't find equivalent jpeg/etc")
                            cursor.moveToPosition(dngPos)
                        }
                    } else if (filename != null) {
                        // in cases where a HDR/NR/PANO photo was saved with base images, we should prefer the HDR image
                        val filenameWithoutExt = filenameWithoutExtension(filename).uppercase()
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "filename_without_ext: $filenameWithoutExt"
                        )
                        val filenameSpecialBase = filenameIsSpecial(filenameWithoutExt)
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "filename_special_base: $filenameSpecialBase"
                        )
                        if (filenameSpecialBase == null) {
                            var filenameBase: String? = null
                            // assume that base saved images are at most _XX
                            if (filenameWithoutExt.length >= 3 && filenameWithoutExt[filenameWithoutExt.length - 2] == '_') {
                                filenameBase = filenameWithoutExt.substring(
                                    0,
                                    filenameWithoutExt.length - 2
                                )
                            } else if (filenameWithoutExt.length >= 4 && filenameWithoutExt[filenameWithoutExt.length - 3] == '_') {
                                filenameBase = filenameWithoutExt.substring(
                                    0,
                                    filenameWithoutExt.length - 3
                                )
                            }
                            if (MyDebug.LOG) Log.d(
                                TAG,
                                "filename_base: $filenameBase"
                            )
                            if (filenameBase != null) {
                                val lastPos = cursor.position
                                var foundSpecial = false
                                var scanCount = 0
                                while (cursor.moveToNext()) {
                                    val nextFilename = cursor.getString(columnNameC)
                                    if (MyDebug.LOG) Log.d(
                                        TAG,
                                        "next_filename: $nextFilename"
                                    )
                                    if (nextFilename == null) {
                                        if (MyDebug.LOG) Log.d(
                                            TAG,
                                            "done scanning, couldn't find filename"
                                        )
                                        break
                                    }
                                    val nextFilenameWithoutExt =
                                        filenameWithoutExtension(nextFilename).uppercase()
                                    if (MyDebug.LOG) Log.d(
                                        TAG,
                                        "next_filename_without_ext: $nextFilenameWithoutExt"
                                    )
                                    val nextFilenameSpecialBase =
                                        filenameIsSpecial(nextFilenameWithoutExt)
                                    if (MyDebug.LOG) Log.d(
                                        TAG,
                                        "next_filename_special_base: $nextFilenameSpecialBase"
                                    )
                                    if (nextFilenameSpecialBase != null) {
                                        // found a special filename - is it the same base?
                                        if (filenameBase == nextFilenameSpecialBase) {
                                            // found a match
                                            if (MyDebug.LOG) Log.d(TAG, "found equivalent special")
                                            foundSpecial = true
                                            break
                                        } else {
                                            // found special, but doesn't match, so no point scanning further
                                            if (MyDebug.LOG) Log.d(TAG, "found another special")
                                            break
                                        }
                                    } else if (!nextFilenameWithoutExt.startsWith(filenameBase)) {
                                        if (MyDebug.LOG) Log.d(
                                            TAG,
                                            "no longer matches filename_base"
                                        )
                                        break
                                    } else if (scanCount++ > 10) {
                                        if (MyDebug.LOG) Log.d(TAG, "give up scanning")
                                        break
                                    }
                                }
                                if (!foundSpecial) {
                                    if (MyDebug.LOG) Log.d(TAG, "can't find equivalent non-special")
                                    cursor.moveToPosition(lastPos)
                                }
                            }
                        }
                    }
                }

                val id = cursor.getLong(columnIdC)
                val date = cursor.getLong(columnDateTakenC)
                val orientation =
                    if (uriType == UriType.MEDIASTORE_IMAGES) cursor.getInt(columnOrientationC) else 0
                val uri = ContentUris.withAppendedId(baseUri, id)
                val filename = cursor.getString(columnNameC)
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "found most recent uri for $uriType: $uri"
                )
                val video = when (uriType) {
                    UriType.MEDIASTORE_IMAGES -> false
                    UriType.MEDIASTORE_VIDEOS -> true
                    else -> throw RuntimeException("unknown uri_type: $uriType")
                }
                if (MyDebug.LOG) Log.d(TAG, "video: $video")

                media = Media(true, id, video, uri, date, orientation, filename)

                if (MyDebug.LOG) {
                    // debug
                    if (cursor.moveToFirst()) {
                        do {
                            val thisId = cursor.getLong(columnIdC)
                            val thisDate = cursor.getLong(columnDateTakenC)
                            val thisUri = ContentUris.withAppendedId(baseUri, thisId)
                            val thisFilename = cursor.getString(columnNameC)
                            Log.d(
                                TAG,
                                "Date: $thisDate ID: $thisId Name: $thisFilename Uri: $thisUri"
                            )
                        } while (cursor.moveToNext())
                    }
                }
            } else {
                if (MyDebug.LOG) Log.d(TAG, "mediastore returned no media")
            }
        } catch (e: Exception) {
            // have had exceptions such as SQLiteException, NullPointerException reported on Google Play from within getContentResolver().query() call
            if (MyDebug.LOG) Log.e(TAG, "Exception trying to find latest media")
            e.printStackTrace()
        } finally {
            cursor?.close()
        }

        if (MyDebug.LOG) Log.d(
            TAG,
            "return latest media: $media"
        )
        return media
    }

    /** Used when using Storage Access Framework AND scoped storage.
     * This is because with scoped storage, we don't request READ_EXTERNAL_STORAGE (as
     * recommended). It's meant to be the case that applications should still be able to see files
     * that they own - but whilst this is true when images are saved using mediastore API, this is
     * NOT true when saving with Storage Access Framework - they don't show up in mediastore
     * queries (even though they've definitely been added to the mediastore). So instead we read
     * using the SAF uri, and if we need the media uri (e.g., to pass to Gallery application), use
     * Media.getMediaStoreUri(). What a mess!
     */
    private fun getLatestMediaSAF(treeUri: Uri): Media? {
        if (MyDebug.LOG) Log.d(
            TAG,
            "getLatestMediaSAF: $treeUri"
        )

        var media: Media? = null

        val baseUri: Uri
        try {
            val parentDocUri = DocumentsContract.getTreeDocumentId(treeUri)
            baseUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocUri)
        } catch (e: Exception) {
            // DocumentsContract.getTreeDocumentId throws IllegalArgumentException if the uri is
            // invalid. Unclear if this can happen in practice - this happens in test
            // testSaveFolderHistorySAF() but only because we test a dummy invalid SAF uri. But
            // seems no harm catching it in case this can happen (e.g., especially if restoring
            // backed up preferences from a different device?) Better to just show nothing in the
            // thumbnail, rather than crashing!
            // N.B., we catch Exception is otherwise compiler complains IllegalArgumentException
            // isn't ever thrown - even though it is!?
            Log.e(TAG, "Exception using treeUri: $treeUri")
            return media
        }
        if (MyDebug.LOG) Log.d(TAG, "baseUri: $baseUri")

        val columnIdC = 0
        val columnDateC = 1
        val columnNameC = 2 // filename (without path), including extension
        val columnMimeC = 3
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )

        // Note, it appears that when querying DocumentsContract, basic query functionality like selection, ordering, are ignored(!).
        // See: https://stackoverflow.com/questions/52770188/how-to-filter-the-results-of-a-query-with-buildchilddocumentsuriusingtree
        // https://stackoverflow.com/questions/56263620/contentresolver-query-on-documentcontract-lists-all-files-disregarding-selection
        // So, we have to do it ourselves.
        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(baseUri, projection, null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                if (MyDebug.LOG) Log.d(TAG, "found: " + cursor.count)

                var latestUri: Uri? = null
                var latestDate: Long = 0
                var latestFilename: String? = null
                var latestIsVideo = false

                // as well as scanning for the most recent image, we also keep track of the most recent non-RAW image,
                // in case we want to prefer that when the most recent
                var nonrawLatestUri: Uri? = null
                var nonrawLatestDate: Long = 0
                var nonrawLatestFilename: String? = null

                do {
                    val thisDate = cursor.getLong(columnDateC)

                    val docId = cursor.getString(columnIdC)
                    val thisUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    val thisMimeType = cursor.getString(columnMimeC)

                    // if updating this code for allowed mime types, also update corresponding code in getLatestMediaCore()
                    val isAllowed: Boolean
                    val thisIsVideo: Boolean
                    when (thisMimeType) {
                        "image/jpeg", "image/webp", "image/png", "image/x-adobe-dng" -> {
                            isAllowed = true
                            thisIsVideo = false
                        }

                        "video/3gpp", "video/webm", "video/mp4" -> {
                            // n.b., perhaps we should just allow video/*, but we should still disallow .SRT files!
                            isAllowed = true
                            thisIsVideo = true
                        }

                        else -> {
                            // skip unwanted file format
                            isAllowed = false
                            thisIsVideo = false
                        }
                    }
                    if (!isAllowed) {
                        continue
                    }

                    val thisFilename = cursor.getString(columnNameC)
                    if (thisFilename != null && thisFilename.length > 0 && thisFilename[0] == '.') {
                        // skip hidden file
                        continue
                    }

                    /*if( MyDebug.LOG ) {
                        Log.d(TAG, "Date: " + thisDate + " docId: " + docId + " Name: " + thisFilename + " Uri: " + thisUri);
                    }*/
                    if (latestUri == null || thisDate > latestDate) {
                        latestUri = thisUri
                        latestDate = thisDate
                        latestFilename = thisFilename
                        latestIsVideo = thisIsVideo
                    }
                    if (!thisIsVideo && !filenameIsRaw(thisFilename)) {
                        if (nonrawLatestUri == null || thisDate > nonrawLatestDate) {
                            nonrawLatestUri = thisUri
                            nonrawLatestDate = thisDate
                            nonrawLatestFilename = thisFilename
                        }
                    }
                } while (cursor.moveToNext())

                if (latestUri == null) {
                    if (MyDebug.LOG) Log.e(TAG, "couldn't find latest uri")
                } else {
                    if (MyDebug.LOG) {
                        Log.d(TAG, "latest_uri: $latestUri")
                        Log.d(
                            TAG,
                            "nonraw_latest_uri: $nonrawLatestUri"
                        )
                    }

                    if (!latestIsVideo && filenameIsRaw(latestFilename!!) && nonrawLatestUri != null) {
                        // prefer non-RAW to RAW? check filenames without extensions match
                        val filenameWithoutExt = filenameWithoutExtension(
                            latestFilename
                        )
                        val nextFilenameWithoutExt = filenameWithoutExtension(
                            nonrawLatestFilename!!
                        )
                        if (MyDebug.LOG) {
                            Log.d(
                                TAG,
                                "filename_without_ext: $filenameWithoutExt"
                            )
                            Log.d(
                                TAG,
                                "next_filename_without_ext: $nextFilenameWithoutExt"
                            )
                        }
                        if (filenameWithoutExt == nextFilenameWithoutExt) {
                            if (MyDebug.LOG) Log.d(TAG, "prefer non-RAW to RAW")
                            latestUri = nonrawLatestUri
                            latestDate = nonrawLatestDate
                            latestFilename = nonrawLatestFilename
                            // video is unchanged
                        }
                    }

                    media = Media(
                        false,
                        0,
                        latestIsVideo,
                        latestUri,
                        latestDate,
                        0,
                        latestFilename
                    )
                }

                /*if( MyDebug.LOG ) {
                    // debug
                    if( cursor.moveToFirst() ) {
                        do {
                            long thisId = cursor.getLong(columnIdC);
                            long thisDate = cursor.getLong(columnDateTakenC);
                            Uri thisUri = ContentUris.withAppendedId(baseUri, thisId);
                            String thisFilename = cursor.getString(columnNameC);
                            Log.d(TAG, "Date: " + thisDate + " ID: " + thisId + " Name: " + thisFilename + " Uri: " + thisUri);
                        }
                        while( cursor.moveToNext() );
                    }
                }*/
            } else {
                if (MyDebug.LOG) Log.d(TAG, "mediastore returned no media")
            }
        } catch (e: Exception) {
            if (MyDebug.LOG) Log.e(TAG, "Exception trying to find latest media")
            e.printStackTrace()
        } finally {
            cursor?.close()
        }

        if (MyDebug.LOG) Log.d(
            TAG,
            "return latest media: $media"
        )
        return media
    }

    private fun getLatestMedia(uriType: UriType): Media? {
        if (MyDebug.LOG) Log.d(TAG, "getLatestMedia: $uriType")
        if (!MainActivity.useScopedStorage() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // needed for Android 6, in case users deny storage permission, otherwise we get java.lang.SecurityException from ContentResolver.query()
            // see https://developer.android.com/training/permissions/requesting.html
            // we now request storage permission before opening the camera, but keep this here just in case
            // we restrict check to Android 6 or later just in case, see note in LocationSupplier.setupLocationListener()
            // update for scoped storage: here we should no longer need READ_EXTERNAL_STORAGE (which we won't have), instead we'll only be able to see
            // media created by Open Kamera, which is fine
            if (MyDebug.LOG) Log.e(TAG, "don't have READ_EXTERNAL_STORAGE permission")
            return null
        }

        val saveFolder = imageFolderPath // may be null if using SAF
        if (MyDebug.LOG) Log.d(TAG, "save_folder: $saveFolder")
        var bucketId: String? = null
        if (saveFolder != null) {
            bucketId = saveFolder.lowercase(Locale.getDefault()).hashCode().toString()
        }
        if (MyDebug.LOG) Log.d(TAG, "bucket_id: $bucketId")
        val baseUri = when (uriType) {
            UriType.MEDIASTORE_IMAGES -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            UriType.MEDIASTORE_VIDEOS -> Video.Media.EXTERNAL_CONTENT_URI
            else -> throw RuntimeException("unknown uri_type: $uriType")
        }

        if (MyDebug.LOG) Log.d(TAG, "baseUri: $baseUri")
        var media = getLatestMediaCore(baseUri, bucketId, uriType)
        if (media == null && bucketId != null) {
            if (MyDebug.LOG) Log.d(TAG, "fall back to checking any folder")
            media = getLatestMediaCore(baseUri, null, uriType)
        }

        return media
    }

    val latestMedia: Media?
        get() {
            if (MainActivity.useScopedStorage() && this.isUsingSAF) {
                val treeUri = this.treeUriSAF
                return getLatestMediaSAF(treeUri)
            }

            val imageMedia = getLatestMedia(UriType.MEDIASTORE_IMAGES)
            val videoMedia = getLatestMedia(UriType.MEDIASTORE_VIDEOS)
            var media: Media? = null
            if (imageMedia != null && videoMedia == null) {
                if (MyDebug.LOG) Log.d(TAG, "only found images")
                media = imageMedia
            } else if (imageMedia == null && videoMedia != null) {
                if (MyDebug.LOG) Log.d(TAG, "only found videos")
                media = videoMedia
            } else if (imageMedia != null && videoMedia != null) {
                if (MyDebug.LOG) {
                    Log.d(TAG, "found images and videos")
                    Log.d(
                        TAG,
                        "latest image date: " + imageMedia.date + " : " + Date(imageMedia.date)
                    )
                    Log.d(
                        TAG,
                        "latest video date: " + videoMedia.date + " : " + Date(videoMedia.date)
                    )
                }
                if (imageMedia.date >= videoMedia.date) {
                    if (MyDebug.LOG) Log.d(TAG, "latest image is newer")
                    media = imageMedia
                } else {
                    if (MyDebug.LOG) Log.d(TAG, "latest video is newer")
                    media = videoMedia

                    // but in cases of using preview shots, sometimes the video ends up with a new date (even if only by 1s), so
                    // to be sure check filenames, and prefer image if so
                    var imageFilenameWithoutExt =
                        filenameWithoutExtension(imageMedia.filename).uppercase()
                    var videoFilenameWithoutExt =
                        filenameWithoutExtension(videoMedia.filename).uppercase()
                    // exclude _HDR extension etc, as these are only used for the image, not the preview video
                    run {
                        val filenameSpecialBase = filenameIsSpecial(imageFilenameWithoutExt)
                        if (filenameSpecialBase != null) imageFilenameWithoutExt =
                            filenameSpecialBase
                    }
                    run {
                        val filenameSpecialBase = filenameIsSpecial(videoFilenameWithoutExt)
                        if (filenameSpecialBase != null) videoFilenameWithoutExt =
                            filenameSpecialBase
                    }
                    if (MyDebug.LOG) {
                        Log.d(
                            TAG,
                            "image_filename_without_ext: $imageFilenameWithoutExt"
                        )
                        Log.d(
                            TAG,
                            "video_filename_without_ext: $videoFilenameWithoutExt"
                        )
                    }
                    if (imageFilenameWithoutExt == videoFilenameWithoutExt) {
                        if (MyDebug.LOG) Log.d(TAG, "but prefer image due to identical filenames")
                        media = imageMedia
                    }
                }
            }
            if (MyDebug.LOG) Log.d(
                TAG,
                "return latest media: $media"
            )
            return media
        }

    // only valid if isUsingSAF()
    private fun freeMemorySAF(): Long {
        val treeUri = applicationInterface.storageUtils.treeUriSAF
        var pfd: ParcelFileDescriptor? = null
        if (MyDebug.LOG) Log.d(TAG, "treeUri: $treeUri")
        try {
            val docUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            )
            if (MyDebug.LOG) Log.d(TAG, "docUri: $docUri")
            pfd = context.contentResolver.openFileDescriptor(docUri, "r")
            if (pfd == null) { // just in case
                Log.e(TAG, "pfd is null!")
                throw FileNotFoundException()
            }
            if (MyDebug.LOG) Log.d(TAG, "read direct from SAF uri")
            val statFs = Os.fstatvfs(pfd.fileDescriptor)
            val blocks = statFs.f_bavail
            val size = statFs.f_bsize
            return (blocks * size) / 1048576
        } catch (e: IllegalArgumentException) {
            // IllegalArgumentException can be thrown by DocumentsContract.getTreeDocumentId or getContentResolver().openFileDescriptor
            e.printStackTrace()
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
        } catch (e: Exception) {
            // We actually just want to catch ErrnoException here, but that isn't available pre-Android 5, and trying to catch ErrnoException
            // means we crash on pre-Android 5 with java.lang.VerifyError when trying to create the StorageUtils class!
            // One solution might be to move this method to a separate class that's only created on Android 5+, but this is a quick fix for
            // now.
            e.printStackTrace()
        } finally {
            try {
                pfd?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        return -1
    }

    /** Return free memory in MB, or -1 if this was unable to be found.
     * Be careful of calling this on main UI thread, as this can be slow when SAF is enabled.
     */
    fun freeMemory(): Long { // return free memory in MB
        if (MyDebug.LOG) Log.d(TAG, "freeMemory")
        if (applicationInterface.storageUtils.isUsingSAF) {
            // if we fail for SAF, don't fall back to the methods below, as this may be incorrect (especially for external SD card)
            return freeMemorySAF()
        }
        // n.b., StatFs still seems to work with Android 10's scoped storage... (and there doesn't seem to be an official non-File based equivalent)
        try {
            val folder = imageFolder
            requireNotNull(folder)
            val statFs = StatFs(folder.absolutePath)
            val blocks = statFs.availableBlocksLong
            val size = statFs.blockSizeLong
            return (blocks * size) / 1048576
        } catch (e: IllegalArgumentException) {
            // this can happen if folder doesn't exist, or don't have read access
            // if the save folder is a subfolder of DCIM, we can just use that instead
            try {
                if (!isUsingSAF) {
                    // getSaveLocation() only valid if !isUsingSAF()
                    val folderName = saveLocation
                    if (!saveFolderIsFull(folderName)) {
                        val folder = baseFolder
                        val statFs = StatFs(folder.absolutePath)
                        val blocks = statFs.availableBlocksLong
                        val size = statFs.blockSizeLong
                        return (blocks * size) / 1048576
                    }
                }
            } catch (e2: IllegalArgumentException) {
                // just in case
            }
        }
        return -1
    }

    companion object {
        private const val TAG = "StorageUtils"

        const val MEDIA_TYPE_IMAGE: Int = 1
        const val MEDIA_TYPE_VIDEO: Int = 2
        const val MEDIA_TYPE_PREFS: Int = 3
        const val MEDIA_TYPE_GYRO_INFO: Int = 4
        const val MEDIA_TYPE_PRESHOT: Int =
            5 // filetype is a video, but we have separate enum to support a different prefix

        // If lastMediaScannedHasnoexifdatetime==true, it means that the last media saved had the
        // option to strip exif tags. Therefore we should do more to remember the last media scanned,
        // as we otherwise won't be able to find it again.
        // lastMediaScannedCheckUri is only non-null if lastMediaScannedHasnoexifdatetime==true.
        // It stores a uri that can be used to test if the media still exists. In practice this will be
        // the lastMediaScanned uri, except for SAF images, when it'll be a SAF uri.
        private val RELATIVE_FOLDER_BASE: String? = Environment.DIRECTORY_DCIM

        // only valid if !isUsingSAF()
        // returns a form for use with RELATIVE_PATH (scoped storage)
        private fun getSaveRelativeFolder(folderName: String): String {
            var folderName = folderName
            if (folderName.length > 0 && folderName.lastIndexOf('/') == folderName.length - 1) {
                // ignore final '/' character
                folderName = folderName.substring(0, folderName.length - 1)
            }
            return RELATIVE_FOLDER_BASE + File.separator + folderName
        }

        val baseFolder: File
            get() {
                val baseFolder =
                    Environment.getExternalStoragePublicDirectory(RELATIVE_FOLDER_BASE)
                return baseFolder
            }

        /** Whether the save photo/video location is in a form that represents a full path, or a
         * sub-folder in DCIM/.
         */
        fun saveFolderIsFull(folderName: String): Boolean {
            return folderName.startsWith("/")
        }

        // only valid if !isUsingSAF()
        private fun getImageFolder(folderName: String): File {
            var folderName = folderName
            if (folderName.length > 0 && folderName.lastIndexOf('/') == folderName.length - 1) {
                // ignore final '/' character
                folderName = folderName.substring(0, folderName.length - 1)
            }
            val file = if (saveFolderIsFull(folderName)) {
                File(folderName)
            } else {
                File(baseFolder, folderName)
            }
            return file
        }

        fun filenameIsRaw(filename: String): Boolean {
            return filename.lowercase().endsWith(".dng")
        }

        private fun filenameWithoutExtension(filename: String): String {
            var filenameWithoutExt = filename.lowercase()
            if (filenameWithoutExt.indexOf(".") > 0) filenameWithoutExt =
                filenameWithoutExt.substring(0, filenameWithoutExt.lastIndexOf("."))
            return filenameWithoutExt
        }

        /** If the filename is for a "special" type HDR, NR or PANO, then return the filename without the
         * part of the filename e.g. "_HDR" onwards; else return null.
         * Received filename should not include an extension.
         */
        private fun filenameIsSpecial(filenameWithoutExt: String): String? {
            if (filenameWithoutExt.endsWith(ImageSaver.hdrSuffix)) {
                return filenameWithoutExt.substring(
                    0,
                    filenameWithoutExt.length - ImageSaver.hdrSuffix.length
                )
            }
            if (filenameWithoutExt.endsWith(ImageSaver.nrSuffix)) {
                return filenameWithoutExt.substring(
                    0,
                    filenameWithoutExt.length - ImageSaver.nrSuffix.length
                )
            }
            if (filenameWithoutExt.endsWith(ImageSaver.panoSuffix)) {
                return filenameWithoutExt.substring(
                    0,
                    filenameWithoutExt.length - ImageSaver.panoSuffix.length
                )
            }
            return null
        }
    }
}

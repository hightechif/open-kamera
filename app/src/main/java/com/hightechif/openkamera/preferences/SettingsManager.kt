package com.hightechif.openkamera.preferences

import android.content.pm.PackageManager
import android.net.Uri
import android.preference.PreferenceManager
import android.util.Log
import android.util.Xml
import com.hightechif.openkamera.MainActivity
import com.hightechif.openkamera.R
import com.hightechif.openkamera.storage.StorageUtils
import com.hightechif.openkamera.utils.MyDebug
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.StringWriter
import java.nio.charset.Charset


/** Code for options for saving and restoring settings.
 */
class SettingsManager internal constructor(private val mainActivity: MainActivity) {
    fun loadSettings(file: String): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "loadSettings: $file")
        val inputStream: InputStream
        try {
            inputStream = FileInputStream(file)
        } catch (e: FileNotFoundException) {
            Log.e(TAG, "failed to load: $file")
            e.printStackTrace()
            mainActivity.preview.showToast(null, R.string.restore_settings_failed)
            return false
        }
        return loadSettings(inputStream)
    }

    fun loadSettings(uri: Uri): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "loadSettings: $uri")
        val inputStream: InputStream?
        try {
            inputStream = mainActivity.contentResolver.openInputStream(uri)
        } catch (e: FileNotFoundException) {
            Log.e(TAG, "failed to load: $uri")
            e.printStackTrace()
            mainActivity.preview.showToast(null, R.string.restore_settings_failed)
            return false
        }
        return loadSettings(inputStream!!)
    }

    /** Loads all settings from the supplied inputStream. If successful, Open Kamera will restart.
     * The supplied inputStream will be closed.
     * @return Whether the operation was succesful.
     */
    private fun loadSettings(inputStream: InputStream): Boolean {
        if (MyDebug.LOG) Log.d(
            TAG,
            "loadSettings: $inputStream"
        )
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(inputStream, null)
            parser.nextTag()

            parser.require(XmlPullParser.START_TAG, null, docTag)

            /*if( true )
            	throw new IOException(); // test*/
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
            val editor = sharedPreferences.edit()
            editor.clear()

            while (parser.next() != XmlPullParser.END_TAG) {
                if (parser.eventType != XmlPullParser.START_TAG) {
                    continue
                }
                val name = parser.name
                val key = parser.getAttributeValue(null, "key")
                if (MyDebug.LOG) {
                    Log.d(TAG, "name: $name")
                    Log.d(TAG, "    key: $key")
                    Log.d(TAG, "    value: " + parser.getAttributeValue(null, "value"))
                }

                when (name) {
                    booleanTag -> editor.putBoolean(
                        key,
                        parser.getAttributeValue(null, "value").toBoolean()
                    )

                    floatTag -> editor.putFloat(
                        key,
                        parser.getAttributeValue(null, "value").toFloat()
                    )

                    intTag -> editor.putInt(key, parser.getAttributeValue(null, "value").toInt())
                    longTag -> editor.putLong(
                        key,
                        parser.getAttributeValue(null, "value").toLong()
                    )

                    stringTag -> editor.putString(key, parser.getAttributeValue(null, "value"))
                    else -> {}
                }

                skipXml(parser)
            }

            // even though we're restoring from settings, we don't want the first time or what's new dialog showing up again!
            // important to do this after reading from xml, so that the keys aren't overwritten
            editor.putBoolean(PreferenceKeys.FIRST_TIME_PREFERENCE_KEY, true)
            try {
                val pInfo =
                    mainActivity.packageManager.getPackageInfo(mainActivity.packageName, 0)
                val versionCode = pInfo.versionCode
                editor.putInt(PreferenceKeys.LATEST_VERSION_PREFERENCE_KEY, versionCode)
            } catch (e: PackageManager.NameNotFoundException) {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "NameNotFoundException exception trying to get version number"
                )
                e.printStackTrace()
            }

            editor.apply()
            if (!mainActivity.isTest) {
                // restarting seems to cause problems for test code (e.g., see testSettingsSaveLoad - even if that test is fine, it risks affecting subsequent tests)
                mainActivity.restartOpenKamera()
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            mainActivity.preview.showToast(null, R.string.restore_settings_failed)
            return false
        } finally {
            try {
                inputStream.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    fun saveSettings(filename: String) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "saveSettings: $filename"
        )
        var outputStream: OutputStream? = null
        try {
            val storageUtils: StorageUtils = mainActivity.storageUtils
            /*OutputStream outputStream;
            Uri uri = null;
            File file = null;
            if( storageUtils.isUsingSAF() ) {
                uri = storageUtils.createOutputMediaFileSAF(StorageUtils.MEDIA_TYPE_PREFS, "", "xml", new Date());
                outputStream = main_activity.getContentResolver().openOutputStream(uri);
            }
            else {
                file = storageUtils.createOutputMediaFile(StorageUtils.MEDIA_TYPE_PREFS, "", "xml", new Date());
                main_activity.testSaveSettingsFile = file.getAbsolutePath();
                outputStream = new FileOutputStream(file);
            }*/
            val settingsFolder: File = storageUtils.settingsFolder
            // in theory the folder should have been created when choosing a name, but just in case...
            storageUtils.createFolderIfRequired(settingsFolder)
            val file = File(settingsFolder.path + File.separator + filename)
            mainActivity.testSaveSettingsFile = file.absolutePath
            outputStream = FileOutputStream(file)

            val xmlSerializer = Xml.newSerializer()

            val writer = StringWriter()
            xmlSerializer.setOutput(writer)
            xmlSerializer.startDocument("UTF-8", true)
            xmlSerializer.startTag(null, docTag)

            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
            val map = sharedPreferences.all
            for ((key, value1) in map) {
                val value = value1!!
                if (key != null) {
                    var tagType: String? = null
                    if (value is Boolean) {
                        tagType = booleanTag
                    } else if (value is Float) {
                        tagType = floatTag
                    } else if (value is Int) {
                        tagType = intTag
                    } else if (value is Long) {
                        tagType = longTag
                    } else if (value is String) {
                        tagType = stringTag
                    } else {
                        Log.e(
                            TAG,
                            "unknown value type: $value"
                        )
                    }

                    if (tagType != null) {
                        xmlSerializer.startTag(null, tagType)
                        xmlSerializer.attribute(null, "key", key)
                        xmlSerializer.attribute(null, "value", value.toString())
                        xmlSerializer.endTag(null, tagType)
                    }
                }
            }
            xmlSerializer.endTag(null, docTag)
            xmlSerializer.endDocument()
            xmlSerializer.flush()
            val dataWrite = writer.toString()
            /*if( true )
            	throw new IOException(); // test*/
            outputStream.write(dataWrite.toByteArray(Charset.forName("UTF-8")))

            mainActivity.preview.showToast(null, R.string.saved_settings)
            /*if( uri != null ) {
                storageUtils.broadcastUri(uri, false, false, false);
            }
            else*/
            run {
                storageUtils.broadcastFile(file, false, false, false, false, null)
            }
        } catch (e: IOException) {
            e.printStackTrace()
            mainActivity.preview.showToast(null, R.string.save_settings_failed)
        } finally {
            if (outputStream != null) {
                try {
                    outputStream.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }
    }

    companion object {
        private const val TAG = "SettingsManager"

        private const val docTag = "open_camera_prefs"
        private const val booleanTag = "boolean"
        private const val floatTag = "float"
        private const val intTag = "int"
        private const val longTag = "long"
        private const val stringTag = "string"

        @Throws(XmlPullParserException::class, IOException::class)
        private fun skipXml(parser: XmlPullParser) {
            check(parser.eventType == XmlPullParser.START_TAG)
            var depth = 1
            while (depth != 0) {
                when (parser.next()) {
                    XmlPullParser.END_TAG -> depth--
                    XmlPullParser.START_TAG -> depth++
                }
            }
        }
    }
}

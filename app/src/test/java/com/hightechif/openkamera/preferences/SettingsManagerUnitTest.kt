package com.hightechif.openkamera.preferences

import android.content.Context
import android.preference.PreferenceManager
import com.hightechif.openkamera.MainActivity
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class SettingsManagerUnitTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().clear().commit()
    }

    @Test
    fun testDefaultPreferenceValues() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        assertEquals("", prefs.getString(PreferenceKeys.getFlashPreferenceKey(0), ""))
        assertEquals("", prefs.getString(PreferenceKeys.getFocusPreferenceKey(0, false), ""))
        assertEquals(0, prefs.getInt(PreferenceKeys.LATEST_VERSION_PREFERENCE_KEY, 0))
    }

    @Test
    fun testPreferenceReadWrite() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val flashKey = PreferenceKeys.getFlashPreferenceKey(0)
        val locationKey = PreferenceKeys.LOCATION_PREFERENCE_KEY

        prefs.edit()
            .putString(flashKey, "flash_auto")
            .putBoolean(locationKey, true)
            .putInt("test_int_key", 42)
            .putFloat("test_float_key", 3.14f)
            .putLong("test_long_key", 100000L)
            .commit()

        assertEquals("flash_auto", prefs.getString(flashKey, ""))
        assertTrue(prefs.getBoolean(locationKey, false))
        assertEquals(42, prefs.getInt("test_int_key", 0))
        assertEquals(3.14f, prefs.getFloat("test_float_key", 0f), 0.001f)
        assertEquals(100000L, prefs.getLong("test_long_key", 0L))
    }

    @Test
    fun testXmlPreferenceParsing() {
        val xmlContent = """
            <open_camera_prefs>
                <boolean key="preference_location" value="true" />
                <string key="preference_flash" value="flash_on" />
                <int key="test_sample_rate" value="44100" />
                <float key="test_zoom" value="2.5" />
                <long key="test_exposure_time" value="20000000" />
            </open_camera_prefs>
        """.trimIndent()

        val inputStream = ByteArrayInputStream(xmlContent.toByteArray(StandardCharsets.UTF_8))
        val mainActivity = mockk<MainActivity>(relaxed = true)
        every { mainActivity.applicationContext } returns context
        every { mainActivity.packageName } returns "com.hightechif.openkamera"
        every { mainActivity.isTest } returns true

        val parser = android.util.Xml.newPullParser()
        parser.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(inputStream, null)
        parser.nextTag()
        assertEquals("open_camera_prefs", parser.name)

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val editor = prefs.edit()

        while (parser.next() != org.xmlpull.v1.XmlPullParser.END_TAG) {
            if (parser.eventType != org.xmlpull.v1.XmlPullParser.START_TAG) continue
            val name = parser.name
            val key = parser.getAttributeValue(null, "key")
            val value = parser.getAttributeValue(null, "value")

            when (name) {
                "boolean" -> editor.putBoolean(key, value.toBoolean())
                "string" -> editor.putString(key, value)
                "int" -> editor.putInt(key, value.toInt())
                "float" -> editor.putFloat(key, value.toFloat())
                "long" -> editor.putLong(key, value.toLong())
            }
            parser.next()
        }
        editor.commit()

        assertTrue(prefs.getBoolean(PreferenceKeys.LOCATION_PREFERENCE_KEY, false))
        assertEquals("flash_on", prefs.getString("preference_flash", ""))
        assertEquals(44100, prefs.getInt("test_sample_rate", 0))
        assertEquals(2.5f, prefs.getFloat("test_zoom", 0f), 0.01f)
        assertEquals(20000000L, prefs.getLong("test_exposure_time", 0L))
    }
}

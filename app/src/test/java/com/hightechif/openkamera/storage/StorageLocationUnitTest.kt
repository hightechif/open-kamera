package com.hightechif.openkamera.storage

import com.hightechif.openkamera.utils.SaveLocationHandler
import org.junit.Assert.assertEquals
import org.junit.Test

class StorageLocationUnitTest {

    @Test
    fun testProcessUserSaveLocation() {
        assertEquals("OpenKamera", SaveLocationHandler.processUserSaveLocation("OpenKamera"))
        assertEquals("OpenKamera", SaveLocationHandler.processUserSaveLocation("OpenKamera/"))
        assertEquals("", SaveLocationHandler.processUserSaveLocation(""))
        assertEquals("", SaveLocationHandler.processUserSaveLocation("/"))
        assertEquals("blah_a/blah_b", SaveLocationHandler.processUserSaveLocation("blah_a/blah_b"))
        assertEquals("blah_a/blah_b", SaveLocationHandler.processUserSaveLocation("blah_a/blah_b/"))
        assertEquals("blah_a/blah_b", SaveLocationHandler.processUserSaveLocation("blah_a//blah_b"))
        assertEquals("blah_a/blah_b", SaveLocationHandler.processUserSaveLocation("blah_a///blah_b"))
        assertEquals("blah_a/blah_b/blah_c", SaveLocationHandler.processUserSaveLocation("blah_a///blah_b/blah_c//"))
        assertEquals("OpenKamera", SaveLocationHandler.processUserSaveLocation("/OpenKamera"))
        assertEquals("OpenKamera", SaveLocationHandler.processUserSaveLocation("//OpenKamera"))
        assertEquals("OpenKamera", SaveLocationHandler.processUserSaveLocation("///OpenKamera"))
        assertEquals("blah_a/blah_b/blah_c", SaveLocationHandler.processUserSaveLocation("/blah_a///blah_b/blah_c//"))
    }

    @Test
    fun testCheckSaveLocation() {
        val dcimPath = "/storage/emulated/0/DCIM"

        var res = SaveLocationHandler.checkSaveLocation("")
        assertEquals(SaveLocationHandler.CheckSaveLocationResult(true, null), res)

        res = SaveLocationHandler.checkSaveLocation("OpenKamera")
        assertEquals(SaveLocationHandler.CheckSaveLocationResult(true, null), res)

        res = SaveLocationHandler.checkSaveLocation("blah_a/blah_b")
        assertEquals(SaveLocationHandler.CheckSaveLocationResult(true, null), res)

        res = SaveLocationHandler.checkSaveLocation("OpenKamera/")
        assertEquals(SaveLocationHandler.CheckSaveLocationResult(true, null), res)

        res = SaveLocationHandler.checkSaveLocation("blah_a/blah_b/")
        assertEquals(SaveLocationHandler.CheckSaveLocationResult(true, null), res)

        res = SaveLocationHandler.checkSaveLocation(
            "/storage/emulated/0/DCIM/OpenKamera/subfolder/",
            dcimPath
        )
        assertEquals(
            SaveLocationHandler.CheckSaveLocationResult(
                false,
                "OpenKamera/subfolder/"
            ), res
        )

        res = SaveLocationHandler.checkSaveLocation(
            "/storage/emulated/0/DCIM/OpenKamera/subfolder",
            dcimPath
        )
        assertEquals(
            SaveLocationHandler.CheckSaveLocationResult(
                false,
                "OpenKamera/subfolder"
            ), res
        )

        res = SaveLocationHandler.checkSaveLocation("/storage/emulated/0/DCIM/OpenKamera/", dcimPath)
        assertEquals(SaveLocationHandler.CheckSaveLocationResult(false, "OpenKamera/"), res)

        res = SaveLocationHandler.checkSaveLocation("/storage/emulated/0/DCIM/OpenKamera", dcimPath)
        assertEquals(SaveLocationHandler.CheckSaveLocationResult(false, "OpenKamera"), res)

        res = SaveLocationHandler.checkSaveLocation("/storage/emulated/0/DCIM/", dcimPath)
        assertEquals(SaveLocationHandler.CheckSaveLocationResult(false, ""), res)

        res = SaveLocationHandler.checkSaveLocation("/storage/emulated/0/DCIM", dcimPath)
        assertEquals(SaveLocationHandler.CheckSaveLocationResult(false, ""), res)

        res = SaveLocationHandler.checkSaveLocation("/storage/emulated/0/Pictures", dcimPath)
        assertEquals(SaveLocationHandler.CheckSaveLocationResult(false, null), res)

        res = SaveLocationHandler.checkSaveLocation("/storage/emulated/0", dcimPath)
        assertEquals(SaveLocationHandler.CheckSaveLocationResult(false, null), res)
    }
}

/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.utils

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

class TextFormatterUnitTest {

    @Test
    @Throws(ParseException::class)
    fun testDateString() {
        val sdf = SimpleDateFormat("yyyy/MM/dd", Locale.US)
        val date1 = sdf.parse("2017/01/31")
        assertEquals("", TextFormatter.getDateString("preference_stamp_dateformat_none", date1))
        assertEquals(
            "2017-01-31",
            TextFormatter.getDateString("preference_stamp_dateformat_yyyymmdd", date1)
        )
        assertEquals(
            "31/01/2017",
            TextFormatter.getDateString("preference_stamp_dateformat_ddmmyyyy", date1)
        )
        assertEquals(
            "01/31/2017",
            TextFormatter.getDateString("preference_stamp_dateformat_mmddyyyy", date1)
        )
    }

    @Test
    @Throws(ParseException::class)
    fun testTimeString() {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.US)
        // case-insensitive checks as at one point text formatter changed from upper case to lower case for AM/PM
        val time1 = sdf.parse("00:00:00")
        assertEquals("", TextFormatter.getTimeString("preference_stamp_timeformat_none", time1))
        assertEquals(
            "12:00:00 am",
            TextFormatter.getTimeString("preference_stamp_timeformat_12hour", time1).lowercase(Locale.US)
        )
        assertEquals(
            "00:00:00",
            TextFormatter.getTimeString("preference_stamp_timeformat_24hour", time1)
        )

        val time2 = sdf.parse("08:15:43")
        assertEquals("", TextFormatter.getTimeString("preference_stamp_timeformat_none", time2))
        assertEquals(
            "08:15:43 am",
            TextFormatter.getTimeString("preference_stamp_timeformat_12hour", time2).lowercase(Locale.US)
        )
        assertEquals(
            "08:15:43",
            TextFormatter.getTimeString("preference_stamp_timeformat_24hour", time2)
        )

        val time3 = sdf.parse("12:00:00")
        assertEquals("", TextFormatter.getTimeString("preference_stamp_timeformat_none", time3))
        assertEquals(
            "12:00:00 pm",
            TextFormatter.getTimeString("preference_stamp_timeformat_12hour", time3).lowercase(Locale.US)
        )
        assertEquals(
            "12:00:00",
            TextFormatter.getTimeString("preference_stamp_timeformat_24hour", time3)
        )

        val time4 = sdf.parse("13:53:06")
        assertEquals("", TextFormatter.getTimeString("preference_stamp_timeformat_none", time4))
        assertEquals(
            "01:53:06 pm",
            TextFormatter.getTimeString("preference_stamp_timeformat_12hour", time4).lowercase(Locale.US)
        )
        assertEquals(
            "13:53:06",
            TextFormatter.getTimeString("preference_stamp_timeformat_24hour", time4)
        )
    }

    @Test
    fun testFormatTime() {
        assertEquals("00:00:00,952", TextFormatter.formatTimeMS(952))
        assertEquals("00:00:01,092", TextFormatter.formatTimeMS(1092))
        assertEquals("00:00:37,301", TextFormatter.formatTimeMS(37301))
        assertEquals("00:05:06,921", TextFormatter.formatTimeMS(306921))
        assertEquals("01:29:51,002", TextFormatter.formatTimeMS(5391002))
        assertEquals("25:46:56,837", TextFormatter.formatTimeMS(92816837))
        assertEquals("220:13:36,000", TextFormatter.formatTimeMS(792816000))
    }
}

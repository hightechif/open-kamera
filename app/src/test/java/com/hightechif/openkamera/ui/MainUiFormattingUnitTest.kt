package com.hightechif.openkamera.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MainUiFormattingUnitTest {

    @Test
    fun testFormatLevelAngle() {
        assertEquals("0.1", DrawPreview.formatLevelAngle(0.1))
        assertEquals("1.2", DrawPreview.formatLevelAngle(1.21))
        assertEquals("1.3", DrawPreview.formatLevelAngle(1.29))
        assertEquals("0.0", DrawPreview.formatLevelAngle(0.0))
        assertEquals("0.0", DrawPreview.formatLevelAngle(-0.0))
        assertEquals("0.0", DrawPreview.formatLevelAngle(-0.0001))
        assertEquals("-0.1", DrawPreview.formatLevelAngle(-0.1))
        assertEquals("-10.7", DrawPreview.formatLevelAngle(-10.6753))
    }

    @Test
    fun testISOButtonStrings() {
        val isoButtonValues = intArrayOf(50, 100, 200, 400, 800, 1600, 3200, 6400)
        for (currentIso in 1..10000) {
            var index = -1
            run {
                var i = 0
                while (i < isoButtonValues.size && index == -1) {
                    if (isoButtonValues[i] == currentIso) {
                        index = i
                    }
                    i++
                }
            }
            // should only match the same button!
            for (i in isoButtonValues.indices) {
                val buttonText: String = PopupView.getButtonOptionString(
                    false,
                    "ISO",
                    MainUI.ISOToButtonText(isoButtonValues[i])
                )
                assertEquals(i == index, MainUI.ISOTextEquals(buttonText, currentIso.toString()))
            }
        }
    }
}

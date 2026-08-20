/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrMathUnitTest {

    private class Float4(val r: Float, val g: Float, val b: Float, val a: Float) {
        override fun equals(other: Any?): Boolean {
            if (other !is Float4) return false
            return this.r == other.r && this.g == other.g && this.b == other.b && this.a == other.a
        }

        override fun hashCode(): Int {
            return (531 * r + 227 * g + b * 31 + a).toInt()
        }
    }

    /**
     * Finds median of the supplied values, sorting by the alpha component.
     */
    private fun findMedian(p0: Float4, p1: Float4, p2: Float4, p3: Float4, p4: Float4): Float4 {
        var p0 = p0
        var p1 = p1
        var p2 = p2
        var p3 = p3
        var p4 = p4
        if (p0.a > p1.a) {
            val tempP = p0
            p0 = p1
            p1 = tempP
        }
        if (p3.a > p4.a) {
            val tempP = p3
            p3 = p4
            p4 = tempP
        }
        if (p0.a > p3.a) {
            var tempP = p0
            p0 = p3
            p3 = tempP

            tempP = p1
            p1 = p4
            p4 = tempP
        }
        if (p1.a > p2.a) {
            if (p2.a > p3.a) {
                if (p2.a > p4.a) {
                    p2 = p4
                }
            } else {
                p2 = if (p1.a > p3.a) {
                    p3
                } else {
                    p1
                }
            }
        } else {
            if (p1.a > p3.a) {
                p2 = if (p1.a > p4.a) {
                    p4
                } else {
                    p1
                }
            } else {
                if (p2.a > p3.a) {
                    p2 = p3
                }
            }
        }
        return p2
    }

    @Test
    fun testMedian() {
        val m0 = findMedian(
            Float4(127f, 0f, 64f, 127f),
            Float4(49f, 49f, 49f, 49f),
            Float4(0f, 0f, 0f, 0f),
            Float4(120f, 120f, 121f, 121f),
            Float4(0f, 51f, 53f, 53f)
        )
        assertEquals(Float4(0f, 51f, 53f, 53f), m0)

        val m1 = findMedian(
            Float4(127f, 0f, 64f, 127f),
            Float4(49f, 49f, 71f, 71f),
            Float4(120f, 120f, 121f, 121f),
            Float4(127f, 151f, 64f, 151f),
            Float4(0f, 51f, 53f, 53f)
        )
        assertEquals(Float4(120f, 120f, 121f, 121f), m1)

        val m2 = findMedian(
            Float4(127f, 0f, 64f, 127f),
            Float4(49f, 49f, 71f, 71f),
            Float4(49f, 49f, 71f, 71f),
            Float4(120f, 120f, 121f, 121f),
            Float4(0f, 51f, 53f, 53f)
        )
        assertEquals(Float4(49f, 49f, 71f, 71f), m2)

        val m3 = findMedian(
            Float4(127f, 0f, 64f, 127f),
            Float4(49f, 149f, 71f, 149f),
            Float4(120f, 120f, 121f, 121f),
            Float4(27f, 51f, 64f, 64f),
            Float4(0f, 51f, 53f, 53f)
        )
        assertEquals(Float4(120f, 120f, 121f, 121f), m3)

        val m4 = findMedian(
            Float4(127f, 0f, 64f, 127f),
            Float4(49f, 149f, 71f, 149f),
            Float4(120f, 120f, 121f, 121f),
            Float4(27f, 51f, 64f, 64f),
            Float4(0f, 51f, 153f, 153f)
        )
        assertEquals(Float4(127f, 0f, 64f, 127f), m4)

        val m5 = findMedian(
            Float4(130f, 0f, 64f, 130f),
            Float4(49f, 149f, 71f, 149f),
            Float4(120f, 120f, 121f, 121f),
            Float4(127f, 51f, 64f, 127f),
            Float4(0f, 51f, 153f, 153f)
        )
        assertEquals(Float4(130f, 0f, 64f, 130f), m5)

        val m6 = findMedian(
            Float4(130f, 0f, 64f, 130f),
            Float4(49f, 49f, 71f, 71f),
            Float4(120f, 120f, 121f, 121f),
            Float4(27f, 51f, 64f, 64f),
            Float4(0f, 0f, 0f, 0f)
        )
        assertEquals(Float4(49f, 49f, 71f, 71f), m6)

        val m7 = findMedian(
            Float4(130f, 0f, 64f, 130f),
            Float4(49f, 49f, 71f, 71f),
            Float4(120f, 120f, 121f, 121f),
            Float4(27f, 51f, 64f, 64f),
            Float4(0f, 100f, 0f, 100f)
        )
        assertEquals(Float4(0f, 100f, 0f, 100f), m7)

        val m8 = findMedian(
            Float4(130f, 0f, 64f, 130f),
            Float4(49f, 49f, 71f, 71f),
            Float4(120f, 181f, 121f, 181f),
            Float4(27f, 51f, 164f, 164f),
            Float4(0f, 100f, 0f, 100f)
        )
        assertEquals(Float4(130f, 0f, 64f, 130f), m8)
    }

    @Test
    fun testBrightenFactors() {
        var brightenFactors: HDRProcessor.BrightenFactors =
            HDRProcessor.computeBrightenFactors(true, 1600, 1000000000L / 12, 42, 170)
        assertEquals(1.5f, brightenFactors.gain, 1.0e-5f)
        assertEquals(8.0f, brightenFactors.lowX, 0.1f)
        assertEquals(255.5f, brightenFactors.midX, 1.0e-5f)
        assertEquals(1.0f, brightenFactors.gamma, 1.0e-5f)

        brightenFactors =
            HDRProcessor.computeBrightenFactors(true, 1600, 1000000000L / 12, 42, 171)
        assertEquals(1.5f, brightenFactors.gain, 1.0e-5f)
        assertEquals(8.0f, brightenFactors.lowX, 0.1f)
        assertEquals(136.0f, brightenFactors.midX, 0.5f)
        assertEquals(1.0f, brightenFactors.gamma, 0.5f)
    }

    @Test
    fun sortLuminanceInfo() {
        var luminanceInfosSorted: List<HDRProcessor.LuminanceInfo>

        var luminanceInfos: MutableList<HDRProcessor.LuminanceInfo> = mutableListOf(
            HDRProcessor.LuminanceInfo(0, 64, 255, false),
            HDRProcessor.LuminanceInfo(16, 80, 255, false),
            HDRProcessor.LuminanceInfo(33, 116, 255, false)
        )
        luminanceInfosSorted = luminanceInfos.sorted()
        assertEquals(luminanceInfos, luminanceInfosSorted)

        luminanceInfos.clear()
        luminanceInfos = mutableListOf(
            HDRProcessor.LuminanceInfo(16, 80, 255, false),
            HDRProcessor.LuminanceInfo(0, 64, 255, false),
            HDRProcessor.LuminanceInfo(33, 116, 255, false)
        )
        luminanceInfosSorted = luminanceInfos.sorted()
        assertEquals(luminanceInfos.size.toLong(), luminanceInfosSorted.size.toLong())
        assertEquals(luminanceInfos[1], luminanceInfosSorted[0])
        assertEquals(luminanceInfos[0], luminanceInfosSorted[1])
        assertEquals(luminanceInfos[2], luminanceInfosSorted[2])

        luminanceInfos.clear()
        luminanceInfos.add(HDRProcessor.LuminanceInfo(33, 116, 255, false))
        luminanceInfos.add(HDRProcessor.LuminanceInfo(0, 64, 255, false))
        luminanceInfos.add(HDRProcessor.LuminanceInfo(16, 80, 255, false))
        luminanceInfosSorted = luminanceInfos.sorted()
        assertEquals(luminanceInfos.size.toLong(), luminanceInfosSorted.size.toLong())
        assertEquals(luminanceInfos[1], luminanceInfosSorted[0])
        assertEquals(luminanceInfos[2], luminanceInfosSorted[1])
        assertEquals(luminanceInfos[0], luminanceInfosSorted[2])

        // case that requires using min value as well as median value
        luminanceInfos.clear()
        luminanceInfos.add(HDRProcessor.LuminanceInfo(93, 255, 255, false))
        luminanceInfos.add(HDRProcessor.LuminanceInfo(68, 255, 255, false))
        luminanceInfos.add(HDRProcessor.LuminanceInfo(17, 193, 255, false))
        luminanceInfosSorted = luminanceInfos.sorted()
        assertEquals(luminanceInfos.size.toLong(), luminanceInfosSorted.size.toLong())
        assertEquals(luminanceInfos[2], luminanceInfosSorted[0])
        assertEquals(luminanceInfos[1], luminanceInfosSorted[1])
        assertEquals(luminanceInfos[0], luminanceInfosSorted[2])

        // case that should never use min value
        luminanceInfos.clear()
        luminanceInfos.add(HDRProcessor.LuminanceInfo(60, 255, 255, false))
        luminanceInfos.add(HDRProcessor.LuminanceInfo(70, 240, 255, false))
        luminanceInfos.add(HDRProcessor.LuminanceInfo(80, 95, 255, false))
        luminanceInfosSorted = luminanceInfos.sorted()
        assertEquals(luminanceInfos.size.toLong(), luminanceInfosSorted.size.toLong())
        assertEquals(luminanceInfos[2], luminanceInfosSorted[0])
        assertEquals(luminanceInfos[1], luminanceInfosSorted[1])
        assertEquals(luminanceInfos[0], luminanceInfosSorted[2])

        // case that requires using hi value as well as median and min values
        luminanceInfos.clear()
        luminanceInfos.add(HDRProcessor.LuminanceInfo(17, 31, 100, false))
        luminanceInfos.add(HDRProcessor.LuminanceInfo(34, 127, 255, false))
        luminanceInfos.add(HDRProcessor.LuminanceInfo(93, 255, 255, false))
        luminanceInfos.add(HDRProcessor.LuminanceInfo(68, 255, 255, false))
        luminanceInfos.add(HDRProcessor.LuminanceInfo(0, 0, 90, false))
        luminanceInfos.add(HDRProcessor.LuminanceInfo(0, 0, 80, false))
        luminanceInfosSorted = luminanceInfos.sorted()
        assertEquals(luminanceInfos.size.toLong(), luminanceInfosSorted.size.toLong())
        assertEquals(luminanceInfos[5], luminanceInfosSorted[0])
        assertEquals(luminanceInfos[4], luminanceInfosSorted[1])
        assertEquals(luminanceInfos[0], luminanceInfosSorted[2])
        assertEquals(luminanceInfos[1], luminanceInfosSorted[3])
        assertEquals(luminanceInfos[3], luminanceInfosSorted[4])
        assertEquals(luminanceInfos[2], luminanceInfosSorted[5])
    }

    @Test
    fun testNRSceneIsLowLight() {
        // Galaxy S10e:
        assertFalse(HDRProcessor.sceneIsLowLight(1000, 1000000000L / 25))
        assertFalse(HDRProcessor.sceneIsLowLight(1600, 1000000000L / 25))
        assertTrue(HDRProcessor.sceneIsLowLight(3200, 1000000000L / 17))
        assertTrue(HDRProcessor.sceneIsLowLight(800, 1000000000L / 5))
        assertTrue(HDRProcessor.sceneIsLowLight(400, 1000000000L / 5))

        // Nokia 8:
        assertFalse(HDRProcessor.sceneIsLowLight(800, 1000000000L / 14))
        assertFalse(HDRProcessor.sceneIsLowLight(752, 1000000000L / 10))
        assertFalse(HDRProcessor.sceneIsLowLight(1044, 1000000000L / 10))
        assertTrue(HDRProcessor.sceneIsLowLight(1505, 1000000000L / 10))
        assertTrue(HDRProcessor.sceneIsLowLight(1551, 1000000000L / 10))
        assertTrue(HDRProcessor.sceneIsLowLight(1600, 1000000000L / 3))
        assertTrue(HDRProcessor.sceneIsLowLight(1600, 1000000000L / 11))

        // Nexus 6:
        assertFalse(HDRProcessor.sceneIsLowLight(749, 1000000000L / 12))
        assertFalse(HDRProcessor.sceneIsLowLight(1000, 1000000000L / 12))
        assertTrue(HDRProcessor.sceneIsLowLight(1196, 1000000000L / 12))

        // misc:
        assertTrue(HDRProcessor.sceneIsLowLight(1600, 1000000000L / 17))
    }
}

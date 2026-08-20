package com.hightechif.openkamera.preview

import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewFpsUnitTest {

    @Test
    fun testBestPreviewFps() {
        val list0: MutableList<IntArray> = ArrayList()
        list0.add(intArrayOf(15000, 15000))
        list0.add(intArrayOf(15000, 30000))
        list0.add(intArrayOf(7000, 30000))
        list0.add(intArrayOf(30000, 30000))
        val bestFps0: IntArray = Preview.chooseBestPreviewFps(list0)
        assertTrue(bestFps0[0] == 7000 && bestFps0[1] == 30000)

        val list1: MutableList<IntArray> = ArrayList()
        list1.add(intArrayOf(15000, 15000))
        list1.add(intArrayOf(7000, 60000))
        list1.add(intArrayOf(15000, 30000))
        list1.add(intArrayOf(7000, 30000))
        list1.add(intArrayOf(30000, 30000))
        val bestFps1: IntArray = Preview.chooseBestPreviewFps(list1)
        assertTrue(bestFps1[0] == 7000 && bestFps1[1] == 60000)

        val list2: MutableList<IntArray> = ArrayList()
        list2.add(intArrayOf(15000, 15000))
        list2.add(intArrayOf(7000, 15000))
        list2.add(intArrayOf(7000, 10000))
        list2.add(intArrayOf(8000, 19000))
        val bestFps2: IntArray = Preview.chooseBestPreviewFps(list2)
        assertTrue(bestFps2[0] == 8000 && bestFps2[1] == 19000)
    }

    @Test
    fun testMatchPreviewFpsToVideo() {
        val list0: MutableList<IntArray> = ArrayList()
        list0.add(intArrayOf(15000, 15000))
        list0.add(intArrayOf(15000, 30000))
        list0.add(intArrayOf(7000, 30000))
        list0.add(intArrayOf(30000, 30000))
        val bestFps0: IntArray = Preview.matchPreviewFpsToVideo(list0, 30000)
        assertTrue(bestFps0[0] == 30000 && bestFps0[1] == 30000)

        val list1: MutableList<IntArray> = ArrayList()
        list1.add(intArrayOf(15000, 15000))
        list1.add(intArrayOf(7000, 60000))
        list1.add(intArrayOf(15000, 30000))
        list1.add(intArrayOf(7000, 30000))
        list1.add(intArrayOf(30000, 30000))
        val bestFps1: IntArray = Preview.matchPreviewFpsToVideo(list1, 15000)
        assertTrue(bestFps1[0] == 15000 && bestFps1[1] == 15000)

        val list2: MutableList<IntArray> = ArrayList()
        list2.add(intArrayOf(15000, 15000))
        list2.add(intArrayOf(7000, 15000))
        list2.add(intArrayOf(7000, 10000))
        list2.add(intArrayOf(8000, 19000))
        val bestFps2: IntArray = Preview.matchPreviewFpsToVideo(list2, 7000)
        assertTrue(bestFps2[0] == 7000 && bestFps2[1] == 10000)
    }
}

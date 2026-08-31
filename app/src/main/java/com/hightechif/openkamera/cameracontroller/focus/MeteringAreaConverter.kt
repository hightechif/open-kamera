package com.hightechif.openkamera.cameracontroller.focus

import android.graphics.Rect
import android.hardware.camera2.params.Face as Camera2Face
import android.hardware.camera2.params.MeteringRectangle
import com.hightechif.openkamera.cameracontroller.CameraController
import kotlin.math.max
import kotlin.math.min

/**
 * Pure coordinate and area conversion utilities between OpenCamera coordinate space [-1000, 1000]
 * and Android Camera2 sensor crop region coordinate space.
 */
object MeteringAreaConverter {

    fun convertRectToCamera2(cropRect: Rect, rect: Rect): Rect {
        // CameraController.Area is always [-1000, -1000] to [1000, 1000] for the viewable region
        // but for CameraController2, we must convert to be relative to the crop region
        val leftF = (rect.left + 1000) / 2000.0
        val topF = (rect.top + 1000) / 2000.0
        val rightF = (rect.right + 1000) / 2000.0
        val bottomF = (rect.bottom + 1000) / 2000.0
        var left = (cropRect.left + leftF * (cropRect.width() - 1)).toInt()
        var right = (cropRect.left + rightF * (cropRect.width() - 1)).toInt()
        var top = (cropRect.top + topF * (cropRect.height() - 1)).toInt()
        var bottom = (cropRect.top + bottomF * (cropRect.height() - 1)).toInt()
        left = max(left.toDouble(), cropRect.left.toDouble()).toInt()
        right = max(right.toDouble(), cropRect.left.toDouble()).toInt()
        top = max(top.toDouble(), cropRect.top.toDouble()).toInt()
        bottom = max(bottom.toDouble(), cropRect.top.toDouble()).toInt()
        left = min(left.toDouble(), cropRect.right.toDouble()).toInt()
        right = min(right.toDouble(), cropRect.right.toDouble()).toInt()
        top = min(top.toDouble(), cropRect.bottom.toDouble()).toInt()
        bottom = min(bottom.toDouble(), cropRect.bottom.toDouble()).toInt()

        return Rect(left, top, right, bottom)
    }

    fun convertAreaToMeteringRectangle(sensorRect: Rect, area: CameraController.Area): MeteringRectangle {
        val camera2Rect = convertRectToCamera2(sensorRect, area.rect)
        return MeteringRectangle(camera2Rect, area.weight)
    }

    fun convertRectFromCamera2(cropRect: Rect, camera2Rect: Rect): Rect {
        // inverse of convertRectToCamera2()
        val leftF = (camera2Rect.left - cropRect.left) / (cropRect.width() - 1).toDouble()
        val topF = (camera2Rect.top - cropRect.top) / (cropRect.height() - 1).toDouble()
        val rightF = (camera2Rect.right - cropRect.left) / (cropRect.width() - 1).toDouble()
        val bottomF = (camera2Rect.bottom - cropRect.top) / (cropRect.height() - 1).toDouble()
        var left = (leftF * 2000).toInt() - 1000
        var right = (rightF * 2000).toInt() - 1000
        var top = (topF * 2000).toInt() - 1000
        var bottom = (bottomF * 2000).toInt() - 1000

        left = max(left.toDouble(), -1000.0).toInt()
        right = max(right.toDouble(), -1000.0).toInt()
        top = max(top.toDouble(), -1000.0).toInt()
        bottom = max(bottom.toDouble(), -1000.0).toInt()
        left = min(left.toDouble(), 1000.0).toInt()
        right = min(right.toDouble(), 1000.0).toInt()
        top = min(top.toDouble(), 1000.0).toInt()
        bottom = min(bottom.toDouble(), 1000.0).toInt()

        return Rect(left, top, right, bottom)
    }

    fun convertMeteringRectangleToArea(
        sensorRect: Rect,
        meteringRectangle: MeteringRectangle
    ): CameraController.Area {
        val areaRect = convertRectFromCamera2(sensorRect, meteringRectangle.rect)
        return CameraController.Area(areaRect, meteringRectangle.meteringWeight)
    }

    fun convertFromCameraFace(
        sensorRect: Rect,
        camera2Face: Camera2Face
    ): CameraController.Face {
        val areaRect = convertRectFromCamera2(sensorRect, camera2Face.bounds)
        return CameraController.Face(camera2Face.score, areaRect)
    }

    fun createDefaultMeteringRectangle(sensorRect: Rect): MeteringRectangle? {
        if (sensorRect.width() <= 0 || sensorRect.height() <= 0) return null
        return MeteringRectangle(0, 0, sensorRect.width() - 1, sensorRect.height() - 1, 0)
    }
}

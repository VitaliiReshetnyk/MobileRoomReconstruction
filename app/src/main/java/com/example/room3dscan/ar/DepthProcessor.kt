package com.example.room3dscan.ar

import com.google.ar.core.Camera
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import kotlin.math.tan

object DepthProcessor {

    /**
     * Returns dense XYZ points in camera coordinates (meters),
     * or null if depth not available.
     */
    fun extractDepthPoints(frame: Frame): FloatArray? {
        return try {
            val depthImage = frame.acquireDepthImage()
            val width = depthImage.width
            val height = depthImage.height
            val buffer = depthImage.planes[0].buffer
            val rowStride = depthImage.planes[0].rowStride
            val pixelStride = depthImage.planes[0].pixelStride

            val camera = frame.camera
            val intrinsics = camera.imageIntrinsics

            val fx = intrinsics.focalLength[0]
            val fy = intrinsics.focalLength[1]
            val cx = intrinsics.principalPoint[0]
            val cy = intrinsics.principalPoint[1]

            val points = ArrayList<Float>(width * height / 2)

            var y = 0
            while (y < height) {
                var x = 0
                while (x < width) {
                    val idx = y * rowStride + x * pixelStride
                    val depthMm = buffer.getShort(idx).toInt() and 0xFFFF
                    if (depthMm == 0) {
                        x += 4
                        continue
                    }

                    val z = depthMm / 1000f
                    val px = (x - cx) * z / fx
                    val py = (y - cy) * z / fy

                    points.add(px)
                    points.add(py)
                    points.add(-z)

                    x += 4   // downsample
                }
                y += 4
            }

            depthImage.close()
            points.toFloatArray()
        } catch (e: NotYetAvailableException) {
            null
        }
    }
}

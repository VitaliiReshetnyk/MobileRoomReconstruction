package com.example.room3dscan.ar

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import java.io.ByteArrayOutputStream

object ArFrameCapture {

    fun yuv420888ToJpegBytes(image: Image, jpegQuality: Int = 90): ByteArray {
        require(image.format == ImageFormat.YUV_420_888)

        val nv21 = yuv420888ToNv21(image)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)

        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), jpegQuality, out)
        return out.toByteArray()
    }

    private fun yuv420888ToNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val ySize = width * height
        val uvSize = width * height / 2
        val out = ByteArray(ySize + uvSize)

        // Y
        fillPlane(out, 0, yPlane, width, height)

        // UV (NV21 = VU interleaved)
        val chromaHeight = height / 2
        val chromaWidth = width / 2

        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixStride = uPlane.pixelStride
        val vPixStride = vPlane.pixelStride

        var outPos = ySize
        for (row in 0 until chromaHeight) {
            val uRowStart = row * uRowStride
            val vRowStart = row * vRowStride
            for (col in 0 until chromaWidth) {
                val uIndex = uRowStart + col * uPixStride
                val vIndex = vRowStart + col * vPixStride
                out[outPos++] = vBuf.get(vIndex)
                out[outPos++] = uBuf.get(uIndex)
            }
        }
        return out
    }

    private fun fillPlane(out: ByteArray, outOffset: Int, plane: Image.Plane, width: Int, height: Int) {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        var outPos = outOffset
        val row = ByteArray(rowStride)

        for (r in 0 until height) {
            val rowStart = r * rowStride
            buffer.position(rowStart)
            buffer.get(row, 0, minOf(rowStride, buffer.remaining()))

            if (pixelStride == 1) {
                System.arraycopy(row, 0, out, outPos, width)
                outPos += width
            } else {
                var col = 0
                while (col < width) {
                    out[outPos++] = row[col * pixelStride]
                    col++
                }
            }
        }
    }
}

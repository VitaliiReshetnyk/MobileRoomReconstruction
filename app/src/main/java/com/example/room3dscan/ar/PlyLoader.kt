package com.example.room3dscan.ar

import java.io.File

object PlyLoader {

    data class PlyPointCloud(
        val xyz: FloatArray,   // xyzxyz...
        val rgb: FloatArray,   // rgb rgb ... (0..1 floats)
        val minX: Float, val maxX: Float,
        val minY: Float, val maxY: Float,
        val minZ: Float, val maxZ: Float,
        val centerX: Float, val centerY: Float, val centerZ: Float
    )

    fun loadAsciiPly(file: File): PlyPointCloud {
        val lines = file.readLines()
        var i = 0
        var vertexCount = 0
        var hasColor = false

        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("element vertex")) vertexCount = line.split(" ").last().toInt()
            if (line.startsWith("property uchar red")) hasColor = true
            if (line == "end_header") { i++; break }
            i++
        }

        val xyz = FloatArray(vertexCount * 3)
        val rgb = FloatArray(vertexCount * 3)

        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY

        var idx = 0
        var v = 0
        while (v < vertexCount && i < lines.size) {
            val parts = lines[i].trim().split(Regex("\\s+"))
            if (parts.size >= 3) {
                val x = parts[0].toFloat()
                val y = parts[1].toFloat()
                val z = parts[2].toFloat()

                xyz[idx] = x
                xyz[idx + 1] = y
                xyz[idx + 2] = z

                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (z < minZ) minZ = z
                if (z > maxZ) maxZ = z

                if (hasColor && parts.size >= 6) {
                    val r = parts[3].toInt().coerceIn(0, 255) / 255f
                    val g = parts[4].toInt().coerceIn(0, 255) / 255f
                    val b = parts[5].toInt().coerceIn(0, 255) / 255f
                    rgb[idx] = r
                    rgb[idx + 1] = g
                    rgb[idx + 2] = b
                } else {
                    rgb[idx] = 1f
                    rgb[idx + 1] = 1f
                    rgb[idx + 2] = 1f
                }

                idx += 3
            }
            v++
            i++
        }

        val cx = (minX + maxX) * 0.5f
        val cy = (minY + maxY) * 0.5f
        val cz = (minZ + maxZ) * 0.5f

        return PlyPointCloud(xyz, rgb, minX, maxX, minY, maxY, minZ, maxZ, cx, cy, cz)
    }
}

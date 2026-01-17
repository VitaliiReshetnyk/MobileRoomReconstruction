package com.example.room3dscan.ar

import kotlin.math.abs

object MeshColorizer {

    // Colorize by nearest point in XZ (fast + stable)
    fun colorizeByNearestPointXZ(
        meshVertices: FloatArray,
        cloudXyz: FloatArray,
        cloudRgb: FloatArray,
        maxDistMeters: Float = 0.25f,
        sampleStep: Int = 2
    ): FloatArray {

        val out = FloatArray(meshVertices.size)

        val maxD2 = maxDistMeters * maxDistMeters

        // pre-fill with neutral
        var i = 0
        while (i < out.size) {
            out[i] = 0.7f
            out[i + 1] = 0.7f
            out[i + 2] = 0.7f
            i += 3
        }

        var mv = 0
        while (mv < meshVertices.size) {
            val mx = meshVertices[mv]
            val my = meshVertices[mv + 1]
            val mz = meshVertices[mv + 2]

            var best = -1
            var bestD2 = Float.POSITIVE_INFINITY

            // nearest in XZ, but keep some Y consistency (helps walls)
            var ci = 0
            while (ci < cloudXyz.size) {
                val cx = cloudXyz[ci]
                val cy = cloudXyz[ci + 1]
                val cz = cloudXyz[ci + 2]

                val dx = cx - mx
                val dz = cz - mz
                val d2 = dx * dx + dz * dz

                if (d2 < bestD2 && d2 <= maxD2) {
                    // mild Y gate (ignore points too far in height)
                    if (abs(cy - my) < 0.6f) {
                        bestD2 = d2
                        best = ci
                    }
                }
                ci += 3 * sampleStep
            }

            if (best >= 0 && best + 2 < cloudRgb.size) {
                out[mv]     = cloudRgb[best]
                out[mv + 1] = cloudRgb[best + 1]
                out[mv + 2] = cloudRgb[best + 2]
            }

            mv += 3
        }

        return out
    }

    // Paint floor/ceiling as constant pleasant colors (stable)
    fun overrideHorizontalSurfaces(
        colors: FloatArray,
        meshVertices: FloatArray,
        floorY: Float = 0f,
        ceilY: Float = 2.7f,
        eps: Float = 0.02f
    ) {
        var i = 0
        while (i < meshVertices.size) {
            val y = meshVertices[i + 1]
            if (abs(y - floorY) < eps) {
                // floor: darker
                colors[i] = 0.35f
                colors[i + 1] = 0.35f
                colors[i + 2] = 0.35f
            } else if (abs(y - ceilY) < eps) {
                // ceiling: brighter
                colors[i] = 0.85f
                colors[i + 1] = 0.85f
                colors[i + 2] = 0.85f
            }
            i += 3
        }
    }
}

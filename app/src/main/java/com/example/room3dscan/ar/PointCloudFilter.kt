package com.example.room3dscan.ar

import kotlin.math.floor
import kotlin.math.sqrt

object PointCloudFilter {

    data class Params(
        val minZ: Float = 0.25f,   // meters
        val maxZ: Float = 6.0f,    // meters
        val voxelSize: Float = 0.02f // 2 cm
    )

    fun filterAndDownsample(pointsXyz: FloatArray, p: Params = Params()): FloatArray {
        // 1) filter by Z-range in camera coords (we used -z in DepthProcessor)
        val tmp = FloatArray(pointsXyz.size)
        var t = 0
        var i = 0
        while (i < pointsXyz.size) {
            val x = pointsXyz[i]
            val y = pointsXyz[i + 1]
            val z = pointsXyz[i + 2] // note: z is negative forward (DepthProcessor used -z)
            val dist = -z
            if (dist >= p.minZ && dist <= p.maxZ) {
                tmp[t] = x
                tmp[t + 1] = y
                tmp[t + 2] = z
                t += 3
            }
            i += 3
        }
        val filtered = tmp.copyOfRange(0, t)
        if (filtered.isEmpty()) return filtered

        // 2) voxel grid: average points inside voxel
        val voxel = p.voxelSize
        val map = HashMap<Long, Acc>(filtered.size / 9)

        var j = 0
        while (j < filtered.size) {
            val x = filtered[j]
            val y = filtered[j + 1]
            val z = filtered[j + 2]

            val vx = floor(x / voxel).toInt()
            val vy = floor(y / voxel).toInt()
            val vz = floor(z / voxel).toInt()

            val key = pack(vx, vy, vz)
            val acc = map.getOrPut(key) { Acc() }
            acc.sx += x
            acc.sy += y
            acc.sz += z
            acc.c += 1
            j += 3
        }

        val out = FloatArray(map.size * 3)
        var k = 0
        for (acc in map.values) {
            val inv = 1f / acc.c.toFloat()
            out[k] = acc.sx * inv
            out[k + 1] = acc.sy * inv
            out[k + 2] = acc.sz * inv
            k += 3
        }
        return out
    }

    private data class Acc(var sx: Float = 0f, var sy: Float = 0f, var sz: Float = 0f, var c: Int = 0)

    private fun pack(x: Int, y: Int, z: Int): Long {
        // pack 3 signed 21-bit ints into 63-bit key
        fun to21(v: Int): Long = (v + 1_000_000).toLong() and 0x1FFFFF
        val xx = to21(x)
        val yy = to21(y)
        val zz = to21(z)
        return (xx shl 42) or (yy shl 21) or zz
    }
}

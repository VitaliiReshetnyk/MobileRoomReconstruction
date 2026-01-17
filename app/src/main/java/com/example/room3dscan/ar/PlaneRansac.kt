package com.example.room3dscan.ar

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

object PlaneRansac {

    data class Result(
        val inlierMask: BooleanArray,
        val inlierCount: Int
    )

    /**
     * Finds a dominant "floor-like" plane:
     * - horizontal-ish (normal close to world Y axis)
     * - located lower than the cloud center (heuristic)
     */
    fun findFloorPlane(
        xyz: FloatArray,
        centerY: Float,
        iterations: Int = 250,
        inlierThreshMeters: Float = 0.03f,
        minNormalY: Float = 0.85f
    ): Result? {
        val n = xyz.size / 3
        if (n < 200) return null

        var bestCount = 0
        var bestA = 0f
        var bestB = 0f
        var bestC = 0f
        var bestD = 0f

        val rnd = Random(7)

        fun getPoint(idx: Int): FloatArray {
            val i = idx * 3
            return floatArrayOf(xyz[i], xyz[i + 1], xyz[i + 2])
        }

        fun cross(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): FloatArray {
            return floatArrayOf(
                ay * bz - az * by,
                az * bx - ax * bz,
                ax * by - ay * bx
            )
        }

        fun norm(x: Float, y: Float, z: Float): Float {
            return sqrt(x * x + y * y + z * z)
        }

        repeat(iterations) {
            val i1 = rnd.nextInt(n)
            val i2 = rnd.nextInt(n)
            val i3 = rnd.nextInt(n)
            if (i1 == i2 || i1 == i3 || i2 == i3) return@repeat

            val p1 = getPoint(i1)
            val p2 = getPoint(i2)
            val p3 = getPoint(i3)

            val v1x = p2[0] - p1[0]
            val v1y = p2[1] - p1[1]
            val v1z = p2[2] - p1[2]

            val v2x = p3[0] - p1[0]
            val v2y = p3[1] - p1[1]
            val v2z = p3[2] - p1[2]

            val cr = cross(v1x, v1y, v1z, v2x, v2y, v2z)
            var a = cr[0]
            var b = cr[1]
            var c = cr[2]

            val nn = norm(a, b, c)
            if (nn < 1e-6f) return@repeat

            // normalize
            a /= nn; b /= nn; c /= nn

            // want horizontal-ish: normal close to Y axis
            if (abs(b) < minNormalY) return@repeat

            val d = -(a * p1[0] + b * p1[1] + c * p1[2])

            // heuristic: floor should be below centerY
            // plane y at origin roughly: y = -(a*x + c*z + d)/b ; take x=z=0 => y0 = -d/b
            val y0 = -d / b
            if (y0 > centerY) return@repeat

            var cnt = 0
            val thr = inlierThreshMeters
            var idx = 0
            while (idx < xyz.size) {
                val x = xyz[idx]
                val y = xyz[idx + 1]
                val z = xyz[idx + 2]
                val dist = abs(a * x + b * y + c * z + d) // since (a,b,c) normalized
                if (dist < thr) cnt++
                idx += 3
            }

            if (cnt > bestCount) {
                bestCount = cnt
                bestA = a; bestB = b; bestC = c; bestD = d
            }
        }

        if (bestCount < maxOf(500, (xyz.size / 3) / 30)) return null

        val mask = BooleanArray(xyz.size / 3)
        var inl = 0
        val thr = inlierThreshMeters
        var i = 0
        var p = 0
        while (i < xyz.size) {
            val x = xyz[i]
            val y = xyz[i + 1]
            val z = xyz[i + 2]
            val dist = abs(bestA * x + bestB * y + bestC * z + bestD)
            val ok = dist < thr
            mask[p] = ok
            if (ok) inl++
            i += 3
            p++
        }

        return Result(mask, inl)
    }
}

package com.example.room3dscan.ar

import kotlin.math.abs

object EarClippingTriangulator {

    data class P2(val x: Float, val y: Float)

    fun triangulate(points: List<P2>): IntArray {
        if (points.size < 3) return IntArray(0)

        val n = points.size
        val idx = MutableList(n) { it }

        // Ensure CCW orientation
        if (signedArea(points) < 0f) {
            idx.reverse()
        }

        val tris = ArrayList<Int>()
        var guard = 0

        while (idx.size > 3 && guard < 10000) {
            var earFound = false

            for (i in idx.indices) {
                val i0 = idx[(i - 1 + idx.size) % idx.size]
                val i1 = idx[i]
                val i2 = idx[(i + 1) % idx.size]

                val a = points[i0]
                val b = points[i1]
                val c = points[i2]

                if (!isConvex(a, b, c)) continue

                var contains = false
                for (j in idx.indices) {
                    val ij = idx[j]
                    if (ij == i0 || ij == i1 || ij == i2) continue
                    if (pointInTriangle(points[ij], a, b, c)) {
                        contains = true
                        break
                    }
                }
                if (contains) continue

                // ear
                tris.add(i0)
                tris.add(i1)
                tris.add(i2)
                idx.removeAt(i)
                earFound = true
                break
            }

            if (!earFound) {
                // Degenerate/self-intersecting contour: stop to avoid infinite loop
                break
            }
            guard++
        }

        if (idx.size == 3) {
            tris.add(idx[0]); tris.add(idx[1]); tris.add(idx[2])
        }

        return tris.toIntArray()
    }

    private fun signedArea(p: List<P2>): Float {
        var a = 0f
        for (i in p.indices) {
            val j = (i + 1) % p.size
            a += p[i].x * p[j].y - p[j].x * p[i].y
        }
        return 0.5f * a
    }

    private fun isConvex(a: P2, b: P2, c: P2): Boolean {
        val cross = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
        return cross > 1e-6f
    }

    private fun pointInTriangle(p: P2, a: P2, b: P2, c: P2): Boolean {
        // Barycentric technique
        val v0x = c.x - a.x
        val v0y = c.y - a.y
        val v1x = b.x - a.x
        val v1y = b.y - a.y
        val v2x = p.x - a.x
        val v2y = p.y - a.y

        val den = v0x * v1y - v1x * v0y
        if (abs(den) < 1e-8f) return false

        val u = (v2x * v1y - v1x * v2y) / den
        val v = (v0x * v2y - v2x * v0y) / den

        return (u >= 0f) && (v >= 0f) && (u + v <= 1f)
    }
}

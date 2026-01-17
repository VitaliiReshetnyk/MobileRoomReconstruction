package com.example.room3dscan.ar

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

object Orthogonalizer {

    // Pull edges to nearest axis (0/90 degrees) in pixel space.
    fun orthogonalize(poly: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        if (poly.size < 3) return poly

        // 1) estimate dominant axis angle from all edges (weighted by length)
        var sumCos = 0.0
        var sumSin = 0.0

        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            val dx = (b.first - a.first).toDouble()
            val dy = (b.second - a.second).toDouble()
            val len2 = dx * dx + dy * dy
            if (len2 < 1.0) continue

            val ang = atan2(dy, dx) // [-pi..pi]
            // map angle to [0..pi/2) by folding, because orthogonal directions repeat
            val folded = foldToQuarterPi(ang)
            val w = kotlin.math.sqrt(len2)

            sumCos += cos(folded) * w
            sumSin += sin(folded) * w
        }

        val base = atan2(sumSin, sumCos) // base axis direction in [0..pi/2)

        // 2) build new points by snapping each edge to nearest axis of {base, base+90deg}
        val out = ArrayList<Pair<Int, Int>>(poly.size)

        var curX = poly[0].first.toDouble()
        var curY = poly[0].second.toDouble()
        out.add(poly[0])

        for (i in 0 until poly.size) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            val dx = (b.first - a.first).toDouble()
            val dy = (b.second - a.second).toDouble()

            // project edge direction onto the 2 axes
            val (ax1x, ax1y) = axis(base)
            val (ax2x, ax2y) = axis(base + Math.PI / 2.0)

            val p1 = dx * ax1x + dy * ax1y
            val p2 = dx * ax2x + dy * ax2y

            val useAxis1 = abs(p1) >= abs(p2)

            if (useAxis1) {
                curX += ax1x * p1
                curY += ax1y * p1
            } else {
                curX += ax2x * p2
                curY += ax2y * p2
            }

            out.add(Pair(curX.roundToInt(), curY.roundToInt()))
        }

        // remove last (duplicate closure point)
        if (out.size > 1 && out.first() == out.last()) out.removeAt(out.lastIndex)

        return out
    }

    private fun axis(angle: Double): Pair<Double, Double> {
        val c = cos(angle)
        val s = sin(angle)
        return Pair(c, s)
    }

    // Fold any angle to [0..pi/2)
    private fun foldToQuarterPi(a: Double): Double {
        var x = a
        while (x < 0) x += Math.PI
        while (x >= Math.PI) x -= Math.PI
        // now [0..pi)
        if (x >= Math.PI / 2.0) x -= Math.PI / 2.0
        // now [0..pi/2)
        return x
    }
}

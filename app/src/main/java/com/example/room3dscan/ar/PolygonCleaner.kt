package com.example.room3dscan.ar

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object PolygonCleaner {

    fun clean(
        poly: List<Pair<Int, Int>>,
        minEdgePx: Int = 6,
        rdpEpsPx: Float = 3.0f,
        collinearDeg: Float = 8.0f
    ): List<Pair<Int, Int>> {
        if (poly.size < 3) return poly

        var p = removeConsecutiveDuplicates(poly)
        p = removeShortEdges(p, minEdgePx)
        p = rdpClosed(p, rdpEpsPx)
        p = removeAlmostCollinear(p, collinearDeg)
        p = removeShortEdges(p, minEdgePx)

        return p
    }

    private fun removeConsecutiveDuplicates(poly: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>(poly.size)
        for (i in poly.indices) {
            val cur = poly[i]
            if (out.isEmpty() || out.last() != cur) out.add(cur)
        }
        if (out.size > 1 && out.first() == out.last()) out.removeAt(out.lastIndex)
        return out
    }

    private fun dist2(a: Pair<Int, Int>, b: Pair<Int, Int>): Int {
        val dx = a.first - b.first
        val dy = a.second - b.second
        return dx * dx + dy * dy
    }

    private fun removeShortEdges(poly: List<Pair<Int, Int>>, minEdgePx: Int): List<Pair<Int, Int>> {
        if (poly.size < 3) return poly
        val min2 = minEdgePx * minEdgePx
        val out = ArrayList<Pair<Int, Int>>(poly.size)

        for (i in poly.indices) {
            val prev = poly[(i - 1 + poly.size) % poly.size]
            val cur = poly[i]
            val next = poly[(i + 1) % poly.size]

            if (dist2(prev, cur) < min2 && dist2(cur, next) < min2) {
                continue
            }
            out.add(cur)
        }

        return if (out.size >= 3) out else poly
    }

    // ===== RDP for CLOSED polygon =====
    private fun rdpClosed(poly: List<Pair<Int, Int>>, eps: Float): List<Pair<Int, Int>> {
        if (poly.size < 4) return poly

        // Treat as open by duplicating first at end
        val open = ArrayList<Pair<Int, Int>>(poly.size + 1)
        open.addAll(poly)
        open.add(poly[0])

        val simplifiedOpen = rdpOpen(open, eps)

        // Remove last duplicate closing point
        val out = simplifiedOpen.toMutableList()
        if (out.size > 1 && out.first() == out.last()) out.removeAt(out.lastIndex)

        return if (out.size >= 3) out else poly
    }

    private fun rdpOpen(points: List<Pair<Int, Int>>, eps: Float): List<Pair<Int, Int>> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size) { false }
        keep[0] = true
        keep[points.lastIndex] = true
        rdpRec(points, 0, points.lastIndex, eps, keep)
        val out = ArrayList<Pair<Int, Int>>()
        for (i in points.indices) if (keep[i]) out.add(points[i])
        return out
    }

    private fun rdpRec(
        pts: List<Pair<Int, Int>>,
        a: Int,
        b: Int,
        eps: Float,
        keep: BooleanArray
    ) {
        if (b <= a + 1) return

        val ax = pts[a].first.toFloat()
        val ay = pts[a].second.toFloat()
        val bx = pts[b].first.toFloat()
        val by = pts[b].second.toFloat()

        val vx = bx - ax
        val vy = by - ay
        val vLen2 = vx * vx + vy * vy

        var maxD = -1f
        var idx = -1

        for (i in a + 1 until b) {
            val px = pts[i].first.toFloat()
            val py = pts[i].second.toFloat()

            val t = if (vLen2 > 1e-6f) ((px - ax) * vx + (py - ay) * vy) / vLen2 else 0f
            val tt = max(0f, min(1f, t))
            val projx = ax + tt * vx
            val projy = ay + tt * vy

            val dx = px - projx
            val dy = py - projy
            val d = sqrt(dx * dx + dy * dy)

            if (d > maxD) {
                maxD = d
                idx = i
            }
        }

        if (maxD > eps && idx != -1) {
            keep[idx] = true
            rdpRec(pts, a, idx, eps, keep)
            rdpRec(pts, idx, b, eps, keep)
        }
    }

    // Remove vertices where angle is ~180deg (collinear)
    private fun removeAlmostCollinear(poly: List<Pair<Int, Int>>, collinearDeg: Float): List<Pair<Int, Int>> {
        if (poly.size < 3) return poly
        val out = ArrayList<Pair<Int, Int>>(poly.size)
        val thr = collinearDeg

        for (i in poly.indices) {
            val prev = poly[(i - 1 + poly.size) % poly.size]
            val cur = poly[i]
            val next = poly[(i + 1) % poly.size]

            val v1x = (prev.first - cur.first).toFloat()
            val v1y = (prev.second - cur.second).toFloat()
            val v2x = (next.first - cur.first).toFloat()
            val v2y = (next.second - cur.second).toFloat()

            val l1 = sqrt(v1x * v1x + v1y * v1y)
            val l2 = sqrt(v2x * v2x + v2y * v2y)
            if (l1 < 1e-6f || l2 < 1e-6f) {
                continue
            }

            val dot = (v1x * v2x + v1y * v2y) / (l1 * l2)
            val clamped = max(-1f, min(1f, dot))
            val ang = acos(clamped) * (180f / Math.PI.toFloat())

            // collinear if angle near 180
            if (abs(180f - ang) < thr) {
                continue
            }
            out.add(cur)
        }

        return if (out.size >= 3) out else poly
    }
}

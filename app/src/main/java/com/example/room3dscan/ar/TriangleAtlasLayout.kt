package com.example.room3dscan.ar

import kotlin.math.ceil
import kotlin.math.sqrt

object TriangleAtlasLayout {

    data class Layout(
        val atlasW: Int,
        val atlasH: Int,
        val cellSize: Int,
        val cols: Int,
        val rows: Int
    )

    fun computeLayout(triCount: Int, cellSize: Int = 64): Layout {
        val cols = ceil(sqrt(triCount.toDouble())).toInt().coerceAtLeast(1)
        val rows = ceil(triCount.toDouble() / cols.toDouble()).toInt().coerceAtLeast(1)
        val atlasW = cols * cellSize
        val atlasH = rows * cellSize
        return Layout(atlasW, atlasH, cellSize, cols, rows)
    }

    /**
     * UV mapping rule:
     * Each triangle gets one right-triangle cell in the atlas:
     *  A -> (0,0), B -> (1,0), C -> (0,1) inside its cell.
     *
     * triVertices are unindexed: 9 floats per triangle => 3 vertices.
     * output uv has 2 floats per vertex.
     */
    fun buildUvForUnindexedTriangles(triVertices: FloatArray, layout: Layout): FloatArray {
        val triCount = triVertices.size / 9
        val uv = FloatArray((triVertices.size / 3) * 2)

        val invW = 1f / layout.atlasW.toFloat()
        val invH = 1f / layout.atlasH.toFloat()
        val cs = layout.cellSize.toFloat()

        var tri = 0
        var vBase = 0
        while (tri < triCount) {
            val cellX = tri % layout.cols
            val cellY = tri / layout.cols

            val px = cellX * layout.cellSize
            val py = cellY * layout.cellSize

            // A -> (0,0)
            setUv(uv, vBase,     (px + 0f) * invW, (py + 0f) * invH)
            // B -> (1,0)
            setUv(uv, vBase + 1, (px + (cs - 1f)) * invW, (py + 0f) * invH)
            // C -> (0,1)
            setUv(uv, vBase + 2, (px + 0f) * invW, (py + (cs - 1f)) * invH)

            tri++
            vBase += 3
        }

        return uv
    }

    private fun setUv(uv: FloatArray, vertexIndex: Int, u: Float, v: Float) {
        val i = vertexIndex * 2
        uv[i] = u
        uv[i + 1] = v
    }
}

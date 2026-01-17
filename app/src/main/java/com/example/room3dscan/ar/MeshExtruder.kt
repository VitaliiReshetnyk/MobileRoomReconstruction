package com.example.room3dscan.ar

import java.io.File
import kotlin.math.max
import kotlin.math.sqrt

object MeshExtruder {

    data class MeshData(
        val vertices: FloatArray, // xyzxyz...
        val faces: IntArray,      // triangle indices
        val uvs: FloatArray       // uvuv... (2 floats per vertex)
    )

    fun buildExtrudedMesh(
        polygonPx: List<Pair<Int, Int>>,
        w: Int,
        h: Int,
        minX: Float,
        minZ: Float,
        rangeX: Float,
        rangeZ: Float,
        heightMeters: Float = 2.7f,
        wallStepMeters: Float = 0.10f
    ): MeshData {

        if (polygonPx.size < 3) return MeshData(FloatArray(0), IntArray(0), FloatArray(0))

        fun pxToWorldX(px: Int): Float = minX + (px.toFloat() / w.toFloat()) * rangeX
        fun pxToWorldZ(py: Int): Float = minZ + (py.toFloat() / h.toFloat()) * rangeZ

        // world polygon in XZ
        val polyWorld = polygonPx.map {
            EarClippingTriangulator.P2(pxToWorldX(it.first), pxToWorldZ(it.second))
        }

        // perimeter lengths for continuous wall U
        val edgeLen = FloatArray(polyWorld.size)
        var perimeter = 0f
        for (i in polyWorld.indices) {
            val a = polyWorld[i]
            val b = polyWorld[(i + 1) % polyWorld.size]
            val dx = b.x - a.x
            val dz = b.y - a.y
            val len = sqrt(dx * dx + dz * dz).coerceAtLeast(1e-6f)
            edgeLen[i] = len
            perimeter += len
        }
        perimeter = perimeter.coerceAtLeast(1e-6f)

        val verts = ArrayList<Float>(polygonPx.size * 6 * 2)
        val uvs = ArrayList<Float>(polygonPx.size * 4 * 2)
        val faces = ArrayList<Int>(polygonPx.size * 6)

        // ===== 1) WALLS =====
        var accLen = 0f
        for (i in polyWorld.indices) {
            val a = polyWorld[i]
            val b = polyWorld[(i + 1) % polyWorld.size]

            val ax = a.x
            val az = a.y
            val bx = b.x
            val bz = b.y

            val dx = bx - ax
            val dz = bz - az
            val len = edgeLen[i]

            val steps = max(1, (len / wallStepMeters).toInt())

            for (s in 0 until steps) {
                val t0 = s.toFloat() / steps.toFloat()
                val t1 = (s + 1).toFloat() / steps.toFloat()

                val x0 = ax + dx * t0
                val z0 = az + dz * t0
                val x1 = ax + dx * t1
                val z1 = az + dz * t1

                val u0 = (accLen + len * t0) / perimeter
                val u1 = (accLen + len * t1) / perimeter

                val base = verts.size / 3

                // bottom-left
                verts.add(x0); verts.add(0f);           verts.add(z0)
                uvs.add(u0);   uvs.add(0f)

                // top-left
                verts.add(x0); verts.add(heightMeters);  verts.add(z0)
                uvs.add(u0);   uvs.add(1f)

                // top-right
                verts.add(x1); verts.add(heightMeters);  verts.add(z1)
                uvs.add(u1);   uvs.add(1f)

                // bottom-right
                verts.add(x1); verts.add(0f);            verts.add(z1)
                uvs.add(u1);   uvs.add(0f)

                // two triangles
                faces.add(base + 0); faces.add(base + 2); faces.add(base + 1)
                faces.add(base + 0); faces.add(base + 3); faces.add(base + 2)
            }

            accLen += len
        }

        // ===== 2) FLOOR + CEILING =====
        val floorBase = verts.size / 3

        // add floor vertices
        for (p in polyWorld) {
            verts.add(p.x); verts.add(0f); verts.add(p.y)

            val u = ((p.x - minX) / rangeX).coerceIn(0f, 1f)
            val v = ((p.y - minZ) / rangeZ).coerceIn(0f, 1f)
            uvs.add(u); uvs.add(v)
        }

        val floorTris = EarClippingTriangulator.triangulate(polyWorld)

        var k = 0
        while (k + 2 < floorTris.size) {
            faces.add(floorBase + floorTris[k])
            faces.add(floorBase + floorTris[k + 1])
            faces.add(floorBase + floorTris[k + 2])
            k += 3
        }

        // add ceiling vertices (duplicate polygon at height)
        val ceilBase = verts.size / 3
        for (p in polyWorld) {
            verts.add(p.x); verts.add(heightMeters); verts.add(p.y)

            val u = ((p.x - minX) / rangeX).coerceIn(0f, 1f)
            val v = ((p.y - minZ) / rangeZ).coerceIn(0f, 1f)
            uvs.add(u); uvs.add(v)
        }

        // ceiling faces (reverse winding)
        k = 0
        while (k + 2 < floorTris.size) {
            val aIdx = floorTris[k]
            val bIdx = floorTris[k + 1]
            val cIdx = floorTris[k + 2]
            faces.add(ceilBase + aIdx)
            faces.add(ceilBase + cIdx)
            faces.add(ceilBase + bIdx)
            k += 3
        }

        return MeshData(
            vertices = verts.toFloatArray(),
            faces = faces.toIntArray(),
            uvs = uvs.toFloatArray()
        )
    }

    fun saveAsAsciiPly(mesh: MeshData, out: File) {
        out.parentFile?.mkdirs()
        val vCount = mesh.vertices.size / 3
        val fCount = mesh.faces.size / 3

        val sb = StringBuilder()
        sb.appendLine("ply")
        sb.appendLine("format ascii 1.0")
        sb.appendLine("element vertex $vCount")
        sb.appendLine("property float x")
        sb.appendLine("property float y")
        sb.appendLine("property float z")
        sb.appendLine("element face $fCount")
        sb.appendLine("property list uchar int vertex_indices")
        sb.appendLine("end_header")

        var i = 0
        while (i < mesh.vertices.size) {
            sb.append(mesh.vertices[i]).append(" ")
                .append(mesh.vertices[i + 1]).append(" ")
                .append(mesh.vertices[i + 2]).append("\n")
            i += 3
        }

        i = 0
        while (i < mesh.faces.size) {
            sb.append("3 ")
                .append(mesh.faces[i]).append(" ")
                .append(mesh.faces[i + 1]).append(" ")
                .append(mesh.faces[i + 2]).append("\n")
            i += 3
        }

        out.writeText(sb.toString())
    }
}

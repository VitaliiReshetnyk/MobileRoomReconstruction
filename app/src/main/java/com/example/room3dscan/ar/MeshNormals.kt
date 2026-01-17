package com.example.room3dscan.ar

import kotlin.math.sqrt

object MeshNormals {

    fun computeVertexNormals(vertices: FloatArray, faces: IntArray): FloatArray {
        val vCount = vertices.size / 3
        val normals = FloatArray(vertices.size)

        var i = 0
        while (i < faces.size) {
            val i0 = faces[i] * 3
            val i1 = faces[i + 1] * 3
            val i2 = faces[i + 2] * 3

            val x0 = vertices[i0];     val y0 = vertices[i0 + 1]; val z0 = vertices[i0 + 2]
            val x1 = vertices[i1];     val y1 = vertices[i1 + 1]; val z1 = vertices[i1 + 2]
            val x2 = vertices[i2];     val y2 = vertices[i2 + 1]; val z2 = vertices[i2 + 2]

            val ux = x1 - x0
            val uy = y1 - y0
            val uz = z1 - z0

            val vx = x2 - x0
            val vy = y2 - y0
            val vz = z2 - z0

            // n = u x v
            val nx = uy * vz - uz * vy
            val ny = uz * vx - ux * vz
            val nz = ux * vy - uy * vx

            normals[i0] += nx; normals[i0 + 1] += ny; normals[i0 + 2] += nz
            normals[i1] += nx; normals[i1 + 1] += ny; normals[i1 + 2] += nz
            normals[i2] += nx; normals[i2 + 1] += ny; normals[i2 + 2] += nz

            i += 3
        }

        // normalize
        var v = 0
        while (v < normals.size) {
            val nx = normals[v]
            val ny = normals[v + 1]
            val nz = normals[v + 2]
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len > 1e-8f) {
                normals[v] = nx / len
                normals[v + 1] = ny / len
                normals[v + 2] = nz / len
            } else {
                normals[v] = 0f
                normals[v + 1] = 1f
                normals[v + 2] = 0f
            }
            v += 3
        }

        return normals
    }
}

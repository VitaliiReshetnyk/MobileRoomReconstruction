package com.example.room3dscan.ar

object MeshExpand {

    fun expandToUnindexedTriangles(vertices: FloatArray, faces: IntArray): FloatArray {
        val triVerts = FloatArray((faces.size / 3) * 9)
        var o = 0
        var i = 0
        while (i < faces.size) {
            val ia = faces[i] * 3
            val ib = faces[i + 1] * 3
            val ic = faces[i + 2] * 3

            triVerts[o] = vertices[ia];     triVerts[o + 1] = vertices[ia + 1]; triVerts[o + 2] = vertices[ia + 2]
            triVerts[o + 3] = vertices[ib]; triVerts[o + 4] = vertices[ib + 1]; triVerts[o + 5] = vertices[ib + 2]
            triVerts[o + 6] = vertices[ic]; triVerts[o + 7] = vertices[ic + 1]; triVerts[o + 8] = vertices[ic + 2]

            o += 9
            i += 3
        }
        return triVerts
    }

    fun expandToUnindexedUvs(uvs: FloatArray, faces: IntArray): FloatArray {
        val triUvs = FloatArray((faces.size / 3) * 6)
        var o = 0
        var i = 0
        while (i < faces.size) {
            val ia = faces[i] * 2
            val ib = faces[i + 1] * 2
            val ic = faces[i + 2] * 2

            triUvs[o] = uvs[ia];     triUvs[o + 1] = uvs[ia + 1]
            triUvs[o + 2] = uvs[ib]; triUvs[o + 3] = uvs[ib + 1]
            triUvs[o + 4] = uvs[ic]; triUvs[o + 5] = uvs[ic + 1]

            o += 6
            i += 3
        }
        return triUvs
    }
}

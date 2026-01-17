package com.example.room3dscan.ar

import java.io.File

object MeshPlyLoader {

    data class MeshData(
        val vertices: FloatArray,
        val faces: IntArray
    )

    fun loadAsciiMeshPly(file: File): MeshData {
        val lines = file.readLines()
        var i = 0

        var vertexCount = 0
        var faceCount = 0

        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("element vertex")) vertexCount = line.split(" ").last().toInt()
            if (line.startsWith("element face")) faceCount = line.split(" ").last().toInt()
            if (line == "end_header") { i++; break }
            i++
        }

        val verts = FloatArray(vertexCount * 3)
        var v = 0
        var vi = 0
        while (v < vertexCount && i < lines.size) {
            val parts = lines[i].trim().split(Regex("\\s+"))
            if (parts.size >= 3) {
                verts[vi] = parts[0].toFloat()
                verts[vi + 1] = parts[1].toFloat()
                verts[vi + 2] = parts[2].toFloat()
                vi += 3
            }
            v++
            i++
        }

        val faces = IntArray(faceCount * 3)
        var f = 0
        var fi = 0
        while (f < faceCount && i < lines.size) {
            val parts = lines[i].trim().split(Regex("\\s+"))
            if (parts.size >= 4) {
                val n = parts[0].toInt()
                if (n == 3) {
                    faces[fi] = parts[1].toInt()
                    faces[fi + 1] = parts[2].toInt()
                    faces[fi + 2] = parts[3].toInt()
                    fi += 3
                } else {
                    // якщо раптом не трикутник — пропускаємо
                }
            }
            f++
            i++
        }

        return MeshData(verts, faces.copyOfRange(0, fi))
    }
}

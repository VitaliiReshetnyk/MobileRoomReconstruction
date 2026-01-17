package com.example.room3dscan.ar

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class MeshViewerRenderer(
    private val statusCallback: (String) -> Unit
) : GLSurfaceView.Renderer {

    // ===== Shaders: Color =====
    private var program = 0
    private var aPos = 0
    private var aCol = 0
    private var aNor = 0
    private var uMvp = 0
    private var uLightDir = 0


    // ===== Shaders: Texture =====
    private var programTex = 0
    private var aPosTex = 0
    private var aNorTex = 0
    private var aUv = 0
    private var uMvpTex = 0
    private var uLightDirTex = 0
    private var uTex = 0

    // ===== Buffers =====
    private var vbo: FloatBuffer? = null
    private var cbo: FloatBuffer? = null
    private var nbo: FloatBuffer? = null
    private var ibo: IntBuffer? = null
    private var uvbo: FloatBuffer? = null

    private var indexCount = 0
    private var vertexCount = 0
    private var useIndexed = true
    private var useTexture = false
    private var frameDebugCounter = 0

    @Volatile private var pendingAtlas: Bitmap? = null
    @Volatile private var pendingUpload = false

    // ===== GL texture =====
    private var textureId = 0

    // ===== Camera/orbit =====
    private var cx = 0f
    private var cy = 0f
    private var cz = 0f
    private var radius = 2.5f
    private var yaw = 0f
    private var pitch = 0f
    private var zoom = 1.0f

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)

    // ---------- Public API ----------

    fun setMesh(vertices: FloatArray, faces: IntArray, colorsRgb01: FloatArray? = null) {
        useIndexed = true
        useTexture = false
        uvbo = null
        frameDebugCounter = 0

        vbo = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(vertices); position(0)
        }

        val normals = MeshNormals.computeVertexNormals(vertices, faces)
        nbo = ByteBuffer.allocateDirect(normals.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(normals); position(0)
        }

        val cols = if (colorsRgb01 != null && colorsRgb01.size == vertices.size) {
            colorsRgb01
        } else {
            FloatArray(vertices.size).also { arr ->
                var i = 0
                while (i < arr.size) {
                    arr[i] = 0.7f; arr[i + 1] = 0.7f; arr[i + 2] = 0.7f
                    i += 3
                }
            }
        }

        cbo = ByteBuffer.allocateDirect(cols.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(cols); position(0)
        }

        ibo = ByteBuffer.allocateDirect(faces.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().apply {
            put(faces); position(0)
        }

        indexCount = faces.size
        vertexCount = vertices.size / 3

        computeBoundsFromVertices(vertices)
        statusCallback("Mesh loaded (indexed): ${vertices.size / 3} verts, ${faces.size / 3} tris")
    }

    fun setMeshUnindexedColors(triVertices: FloatArray, triColorsRgb01: FloatArray) {
        useIndexed = false
        useTexture = false
        uvbo = null
        frameDebugCounter = 0

        vbo = ByteBuffer.allocateDirect(triVertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(triVertices); position(0)
        }
        cbo = ByteBuffer.allocateDirect(triColorsRgb01.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(triColorsRgb01); position(0)
        }

        val normals = computeFlatNormalsUnindexed(triVertices)
        nbo = ByteBuffer.allocateDirect(normals.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(normals); position(0)
        }

        ibo = null
        indexCount = 0
        vertexCount = triVertices.size / 3

        computeBoundsFromVertices(triVertices)
        statusCallback("Mesh loaded (unindexed colors): verts=$vertexCount tris=${triVertices.size / 9}")
    }

    fun setMeshUnindexedTextured(triVertices: FloatArray, uvs: FloatArray, atlas: Bitmap) {
        require(uvs.size == (triVertices.size / 3) * 2) { "UV size mismatch" }

        useIndexed = false
        useTexture = true
        frameDebugCounter = 0

        vbo = ByteBuffer.allocateDirect(triVertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(triVertices); position(0)
        }

        uvbo = ByteBuffer.allocateDirect(uvs.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(uvs); position(0)
        }

        val normals = computeFlatNormalsUnindexed(triVertices)
        nbo = ByteBuffer.allocateDirect(normals.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(normals); position(0)
        }

        // keep non-null
        cbo = ByteBuffer.allocateDirect(triVertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            val def = FloatArray(triVertices.size)
            var i = 0
            while (i < def.size) {
                def[i] = 1f; def[i + 1] = 1f; def[i + 2] = 1f
                i += 3
            }
            put(def); position(0)
        }

        ibo = null
        indexCount = 0
        vertexCount = triVertices.size / 3

        computeBoundsFromVertices(triVertices)

        pendingAtlas = atlas
        pendingUpload = true

        statusCallback("Mesh staged (textured): verts=$vertexCount tris=${triVertices.size / 9} atlas=${atlas.width}x${atlas.height}")
    }

    fun resetView() {
        yaw = 0f
        pitch = 0f
        zoom = 1f
    }

    fun addYawPitch(dYaw: Float, dPitch: Float) {
        yaw += dYaw
        pitch = (pitch + dPitch).coerceIn(-1.2f, 1.2f)
    }

    fun addZoom(d: Float) {
        zoom = (zoom + d).coerceIn(0.3f, 3.0f)
    }

    // ---------- Renderer ----------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        // ===== Program 1: Vertex Color =====
        val vsColor = """
            attribute vec3 aPos;
            attribute vec3 aCol;
            attribute vec3 aNor;
            varying vec3 vCol;
            varying vec3 vNor;
            uniform mat4 uMvp;
            void main() {
              vCol = aCol;
              vNor = aNor;
              gl_Position = uMvp * vec4(aPos, 1.0);
            }
        """.trimIndent()

        val fsColor = """
            precision mediump float;
            varying vec3 vCol;
            varying vec3 vNor;
            uniform vec3 uLightDir;
            void main() {
              vec3 n = normalize(vNor);
              float ndl = max(dot(n, normalize(uLightDir)), 0.0);
              float ambient = 0.55;
              float diff = 0.45 * ndl;
              vec3 col = vCol * (ambient + diff);
              gl_FragColor = vec4(col, 1.0);
            }
        """.trimIndent()

        program = linkProgram(
            compileShader(GLES20.GL_VERTEX_SHADER, vsColor),
            compileShader(GLES20.GL_FRAGMENT_SHADER, fsColor)
        )
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aCol = GLES20.glGetAttribLocation(program, "aCol")
        aNor = GLES20.glGetAttribLocation(program, "aNor")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uLightDir = GLES20.glGetUniformLocation(program, "uLightDir")

        // ===== Program 2: Textured =====
        val vsTex = """
            attribute vec3 aPos;
            attribute vec3 aNor;
            attribute vec2 aUv;
            varying vec2 vUv;
            varying vec3 vNor;
            uniform mat4 uMvp;
            void main() {
              vUv = aUv;
              vNor = aNor;
              gl_Position = uMvp * vec4(aPos, 1.0);
            }
        """.trimIndent()

        val fsTex = """
            precision mediump float;
            varying vec2 vUv;
            varying vec3 vNor;
            uniform sampler2D uTex;
            uniform vec3 uLightDir;
            void main() {
              vec3 albedo = texture2D(uTex, vUv).rgb;
              vec3 n = normalize(vNor);
              float ndl = max(dot(n, normalize(uLightDir)), 0.0);
              float ambient = 0.55;
              float diff = 0.45 * ndl;
              vec3 col = albedo * (ambient + diff);
              gl_FragColor = vec4(col, 1.0);
            }
        """.trimIndent()

        programTex = linkProgram(
            compileShader(GLES20.GL_VERTEX_SHADER, vsTex),
            compileShader(GLES20.GL_FRAGMENT_SHADER, fsTex)
        )

        aPosTex = GLES20.glGetAttribLocation(programTex, "aPos")
        aNorTex = GLES20.glGetAttribLocation(programTex, "aNor")
        aUv = GLES20.glGetAttribLocation(programTex, "aUv")
        uMvpTex = GLES20.glGetUniformLocation(programTex, "uMvp")
        uLightDirTex = GLES20.glGetUniformLocation(programTex, "uLightDir")
        uTex = GLES20.glGetUniformLocation(programTex, "uTex")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / height.toFloat()
        Matrix.perspectiveM(proj, 0, 60f, aspect, 0.01f, 1000f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val vb = vbo ?: return
        val nb = nbo ?: return

        // Upload texture on GL thread
        if (pendingUpload) {
            val bmp = pendingAtlas
            if (bmp != null) {
                uploadTexture(bmp)
                pendingUpload = false
                statusCallback("GL UPLOAD DONE: texId=$textureId")
            }
        }

        if (vertexCount <= 0) return

        val dist = radius * (2.0f / zoom)
        val ex = cx + dist * cos(pitch) * sin(yaw)
        val ey = cy + dist * sin(pitch)
        val ez = cz + dist * cos(pitch) * cos(yaw)

        Matrix.setLookAtM(view, 0, ex, ey, ez, cx, cy, cz, 0f, 1f, 0f)
        Matrix.setIdentityM(model, 0)

        val vp = FloatArray(16)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)

        val lightX = -0.2f
        val lightY = 1.0f
        val lightZ = 0.3f

        if (frameDebugCounter == 0) {
            statusCallback("RENDER MODE: useTexture=$useTexture texId=$textureId uvbo=${uvbo != null} verts=$vertexCount")
        }
        frameDebugCounter = (frameDebugCounter + 1) % 60

        if (useTexture && textureId != 0 && uvbo != null) {
            val ub = uvbo ?: return

            GLES20.glUseProgram(programTex)
            GLES20.glUniformMatrix4fv(uMvpTex, 1, false, mvp, 0)
            GLES20.glUniform3f(uLightDirTex, lightX, lightY, lightZ)

            vb.position(0)
            GLES20.glEnableVertexAttribArray(aPosTex)
            GLES20.glVertexAttribPointer(aPosTex, 3, GLES20.GL_FLOAT, false, 0, vb)

            nb.position(0)
            GLES20.glEnableVertexAttribArray(aNorTex)
            GLES20.glVertexAttribPointer(aNorTex, 3, GLES20.GL_FLOAT, false, 0, nb)

            ub.position(0)
            GLES20.glEnableVertexAttribArray(aUv)
            GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 0, ub)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glUniform1i(uTex, 0)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)

            GLES20.glDisableVertexAttribArray(aPosTex)
            GLES20.glDisableVertexAttribArray(aNorTex)
            GLES20.glDisableVertexAttribArray(aUv)
            return
        }

        // fallback: vertex colors
        val cb = cbo ?: return

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniform3f(uLightDir, lightX, lightY, lightZ)

        vb.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, vb)

        cb.position(0)
        GLES20.glEnableVertexAttribArray(aCol)
        GLES20.glVertexAttribPointer(aCol, 3, GLES20.GL_FLOAT, false, 0, cb)

        nb.position(0)
        GLES20.glEnableVertexAttribArray(aNor)
        GLES20.glVertexAttribPointer(aNor, 3, GLES20.GL_FLOAT, false, 0, nb)

        if (useIndexed) {
            val ib = ibo ?: return
            if (indexCount <= 0) return
            ib.position(0)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_INT, ib)
        } else {
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)
        }

        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aCol)
        GLES20.glDisableVertexAttribArray(aNor)
    }

    // ---------- Helpers ----------

    private fun uploadTexture(bmp: Bitmap) {
        if (textureId == 0) {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            textureId = ids[0]
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)

        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
    }

    private fun computeBoundsFromVertices(vertices: FloatArray) {
        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY

        var i = 0
        while (i < vertices.size) {
            val x = vertices[i]
            val y = vertices[i + 1]
            val z = vertices[i + 2]
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            if (z < minZ) minZ = z
            if (z > maxZ) maxZ = z
            i += 3
        }

        cx = (minX + maxX) * 0.5f
        cy = (minY + maxY) * 0.5f
        cz = (minZ + maxZ) * 0.5f

        val dx = (maxX - minX)
        val dy = (maxY - minY)
        val dz = (maxZ - minZ)
        radius = maxOf(dx, dy, dz).coerceAtLeast(1f) * 1.2f
    }

    private fun computeFlatNormalsUnindexed(triVertices: FloatArray): FloatArray {
        val out = FloatArray(triVertices.size)
        var i = 0
        while (i + 8 < triVertices.size) {
            val ax = triVertices[i];     val ay = triVertices[i + 1]; val az = triVertices[i + 2]
            val bx = triVertices[i + 3]; val by = triVertices[i + 4]; val bz = triVertices[i + 5]
            val cx = triVertices[i + 6]; val cy = triVertices[i + 7]; val cz = triVertices[i + 8]

            val abx = bx - ax; val aby = by - ay; val abz = bz - az
            val acx = cx - ax; val acy = cy - ay; val acz = cz - az

            var nx = aby * acz - abz * acy
            var ny = abz * acx - abx * acz
            var nz = abx * acy - aby * acx

            val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-12f)
            nx /= len; ny /= len; nz /= len

            out[i] = nx;     out[i + 1] = ny; out[i + 2] = nz
            out[i + 3] = nx; out[i + 4] = ny; out[i + 5] = nz
            out[i + 6] = nx; out[i + 7] = ny; out[i + 8] = nz

            i += 9
        }
        return out
    }

    private fun compileShader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        return s
    }

    private fun linkProgram(vs: Int, fs: Int): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        return p
    }
}

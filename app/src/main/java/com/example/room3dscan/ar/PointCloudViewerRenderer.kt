package com.example.room3dscan.ar

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max

class PointCloudViewerRenderer(
    private val onStats: (count: Int) -> Unit
) : GLSurfaceView.Renderer {

    private var program = 0
    private var aPos = 0
    private var aCol = 0
    private var uMvp = 0

    private var posBuf: FloatBuffer? = null
    private var colBuf: FloatBuffer? = null
    private var pointCount = 0

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)

    private var yaw = 0f
    private var pitch = 0f
    private var dist = 2.5f

    fun setPointCloud(cloud: PlyLoader.PlyPointCloud) {
        posBuf = ByteBuffer.allocateDirect(cloud.xyz.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(cloud.xyz); position(0) }

        colBuf = ByteBuffer.allocateDirect(cloud.rgb.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(cloud.rgb); position(0) }

        pointCount = cloud.xyz.size / 3

        val dx = cloud.maxX - cloud.minX
        val dy = cloud.maxY - cloud.minY
        val dz = cloud.maxZ - cloud.minZ
        dist = max(1.5f, max(dx, max(dy, dz)) * 1.8f)

        yaw = 0f
        pitch = 0f

        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, -cloud.centerX, -cloud.centerY, -cloud.centerZ)
    }

    fun resetView() {
        yaw = 0f
        pitch = 0f
    }

    fun addYawPitch(dYaw: Float, dPitch: Float) {
        yaw += dYaw
        pitch = (pitch + dPitch).coerceIn(-1.2f, 1.2f)
    }

    fun addZoom(d: Float) {
        dist = (dist + d).coerceIn(0.5f, 30f)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        val vs = """
            uniform mat4 u_MVP;
            attribute vec3 a_Position;
            attribute vec3 a_Color;
            varying vec3 v_Color;
            void main() {
              gl_Position = u_MVP * vec4(a_Position, 1.0);
              gl_PointSize = 3.0;
              v_Color = a_Color;
            }
        """.trimIndent()

        val fs = """
            precision mediump float;
            varying vec3 v_Color;
            void main() {
              gl_FragColor = vec4(v_Color, 1.0);
            }
        """.trimIndent()

        val vShader = compileShader(GLES20.GL_VERTEX_SHADER, vs)
        val fShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fs)

        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vShader)
        GLES20.glAttachShader(program, fShader)
        GLES20.glLinkProgram(program)

        aPos = GLES20.glGetAttribLocation(program, "a_Position")
        aCol = GLES20.glGetAttribLocation(program, "a_Color")
        uMvp = GLES20.glGetUniformLocation(program, "u_MVP")

        Matrix.setIdentityM(model, 0)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / height.toFloat()
        Matrix.perspectiveM(proj, 0, 60f, aspect, 0.01f, 200f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val p = posBuf ?: run { onStats(0); return }
        val c = colBuf ?: run { onStats(0); return }

        val eyeX = (dist * kotlin.math.cos(pitch) * kotlin.math.sin(yaw)).toFloat()
        val eyeY = (dist * kotlin.math.sin(pitch)).toFloat()
        val eyeZ = (dist * kotlin.math.cos(pitch) * kotlin.math.cos(yaw)).toFloat()

        Matrix.setLookAtM(view, 0, eyeX, eyeY, eyeZ, 0f, 0f, 0f, 0f, 1f, 0f)

        val vp = FloatArray(16)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)

        p.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, p)

        c.position(0)
        GLES20.glEnableVertexAttribArray(aCol)
        GLES20.glVertexAttribPointer(aCol, 3, GLES20.GL_FLOAT, false, 0, c)

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, pointCount)

        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aCol)

        onStats(pointCount)
    }

    private fun compileShader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        return s
    }
}

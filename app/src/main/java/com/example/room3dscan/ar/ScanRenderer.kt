package com.example.room3dscan.ar

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ScanRenderer(
    private val getDisplayRotation: () -> Int,
    private val statusCallback: (status: String, points: Int) -> Unit,
    private val onFrameData: (poseTwc: FloatArray, pointCloudXyz: FloatArray) -> Unit
) : GLSurfaceView.Renderer {
    private var frameCallback: ((com.google.ar.core.Frame) -> Unit)? = null
    fun setFrameCallback(cb: ((com.google.ar.core.Frame) -> Unit)?) { frameCallback = cb }
    private var session: Session? = null

    private var viewportWidth = 1
    private var viewportHeight = 1
    private var lastRotation = -1
    private var lastViewportW = -1
    private var lastViewportH = -1

    private val poseTwc = FloatArray(16)

    // ===== Background (camera) =====
    private var cameraTexId = -1
    private var bgProgram = 0
    private var bgPosLoc = 0
    private var bgUvLoc = 0
    private var bgTexLoc = 0

    // Fullscreen quad positions in NDC (-1..1)
    private val quadPosNdc = floatArrayOf(
        -1f, -1f,
        1f, -1f,
        -1f,  1f,
        1f,  1f
    )

    // Same points as above, but as a float array for transformCoordinates2d input
    private val quadNdcCoords = floatArrayOf(
        -1f, -1f,
        1f, -1f,
        -1f,  1f,
        1f,  1f
    )

    // Transformed UV in TEXTURE_NORMALIZED [0..1]
    private val quadUvTransformed = FloatArray(8)

    private lateinit var quadPosBuf: FloatBuffer
    private lateinit var quadUvBuf: FloatBuffer

    // ===== Points overlay (debug) =====
    private var ptProgram = 0
    private var ptPosLoc = 0
    private var ptMvpLoc = 0
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)

    private var pointBuf: FloatBuffer? = null
    private var pointCount = 0

    fun setSession(s: Session) {
        session = s
        if (cameraTexId != -1) {
            try { s.setCameraTextureNames(intArrayOf(cameraTexId)) } catch (_: Exception) {}
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        quadPosBuf = ByteBuffer.allocateDirect(quadPosNdc.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(quadPosNdc); position(0) }

        quadUvBuf = ByteBuffer.allocateDirect(quadUvTransformed.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        cameraTexId = createExternalTexture()

        bgProgram = linkProgram(
            """
            attribute vec2 aPos;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() {
              gl_Position = vec4(aPos, 0.0, 1.0);
              vUv = aUv;
            }
            """.trimIndent(),
            """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTex;
            varying vec2 vUv;
            void main() {
              gl_FragColor = texture2D(uTex, vUv);
            }
            """.trimIndent()
        )
        bgPosLoc = GLES20.glGetAttribLocation(bgProgram, "aPos")
        bgUvLoc = GLES20.glGetAttribLocation(bgProgram, "aUv")
        bgTexLoc = GLES20.glGetUniformLocation(bgProgram, "uTex")

        ptProgram = linkProgram(
            """
            uniform mat4 uMVP;
            attribute vec3 aPos;
            void main() {
              gl_Position = uMVP * vec4(aPos, 1.0);
              gl_PointSize = 4.0;
            }
            """.trimIndent(),
            """
            precision mediump float;
            void main() {
              gl_FragColor = vec4(1.0,1.0,1.0,1.0);
            }
            """.trimIndent()
        )
        ptPosLoc = GLES20.glGetAttribLocation(ptProgram, "aPos")
        ptMvpLoc = GLES20.glGetUniformLocation(ptProgram, "uMVP")

        try { session?.setCameraTextureNames(intArrayOf(cameraTexId)) } catch (_: Exception) {}
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        GLES20.glViewport(0, 0, width, height)

        val aspect = width.toFloat() / height.toFloat()
        Matrix.perspectiveM(proj, 0, 60f, aspect, 0.01f, 200f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val s = session ?: run {
            statusCallback("No ARCore session", 0)
            return
        }

        // Keep ARCore display geometry synced (rotation OR viewport size change)
        val rot = getDisplayRotation()
        if (rot != lastRotation || viewportWidth != lastViewportW || viewportHeight != lastViewportH) {
            lastRotation = rot
            lastViewportW = viewportWidth
            lastViewportH = viewportHeight
            try { s.setDisplayGeometry(rot, viewportWidth, viewportHeight) } catch (_: Exception) {}
        }

        val frame: Frame = try {
            s.update()
        } catch (_: Exception) {
            statusCallback("Session update failed", 0)
            return
        }
        frameCallback?.invoke(frame)

        // Correct UV transform: NDC -> TEXTURE_NORMALIZED
        try {
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                quadNdcCoords,
                Coordinates2d.TEXTURE_NORMALIZED,
                quadUvTransformed
            )
        } catch (_: Exception) {
            // fallback: default UV (may be wrong on some devices)
            // Map NDC to UV roughly:
            // (-1,-1)->(0,1), (1,-1)->(1,1), (-1,1)->(0,0), (1,1)->(1,0)
            quadUvTransformed[0] = 0f; quadUvTransformed[1] = 1f
            quadUvTransformed[2] = 1f; quadUvTransformed[3] = 1f
            quadUvTransformed[4] = 0f; quadUvTransformed[5] = 0f
            quadUvTransformed[6] = 1f; quadUvTransformed[7] = 0f
        }

        drawBackground()

        val camera = frame.camera
        val state = camera.trackingState
        val failure = camera.trackingFailureReason

        val statusText = when (state) {
            TrackingState.TRACKING -> "TRACKING"
            TrackingState.PAUSED -> "PAUSED (${failureToText(failure)})"
            TrackingState.STOPPED -> "STOPPED"
            else -> "$state"
        }

        if (state != TrackingState.TRACKING) {
            pointCount = 0
            pointBuf = null
            statusCallback(statusText, 0)
            return
        }

        camera.pose.toMatrix(poseTwc, 0)

        val pc = try { frame.acquirePointCloud() } catch (_: Exception) {
            statusCallback("$statusText (PointCloud unavailable)", 0)
            return
        }

        pc.use { pointCloud ->
            val fb = pointCloud.points // x,y,z,confidence
            val floats = fb.remaining()
            val outCount = (floats / 4)
            val pts = FloatArray(outCount * 3)

            var out = 0
            var idx = 0
            while (idx + 3 < floats) {
                val x = fb.get()
                val y = fb.get()
                val z = fb.get()
                fb.get()
                pts[out] = x
                pts[out + 1] = y
                pts[out + 2] = z
                out += 3
                idx += 4
            }

            pointCount = out / 3
            pointBuf = ByteBuffer.allocateDirect(out * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply { put(pts, 0, out); position(0) }

            onFrameData(poseTwc.copyOf(), pts.copyOf(out))
        }

        drawPoints()

        statusCallback(statusText, pointCount)
    }

    private fun drawBackground() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(bgProgram)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexId)
        GLES20.glUniform1i(bgTexLoc, 0)

        quadPosBuf.position(0)
        GLES20.glEnableVertexAttribArray(bgPosLoc)
        GLES20.glVertexAttribPointer(bgPosLoc, 2, GLES20.GL_FLOAT, false, 0, quadPosBuf)

        quadUvBuf.clear()
        quadUvBuf.put(quadUvTransformed)
        quadUvBuf.position(0)
        GLES20.glEnableVertexAttribArray(bgUvLoc)
        GLES20.glVertexAttribPointer(bgUvLoc, 2, GLES20.GL_FLOAT, false, 0, quadUvBuf)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(bgPosLoc)
        GLES20.glDisableVertexAttribArray(bgUvLoc)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    private fun drawPoints() {
        val buf = pointBuf ?: return
        if (pointCount <= 0) return

        // Debug-only view
        Matrix.setLookAtM(view, 0, 0f, 1.6f, 3.0f, 0f, 1.0f, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(ptProgram)
        GLES20.glUniformMatrix4fv(ptMvpLoc, 1, false, mvp, 0)

        buf.position(0)
        GLES20.glEnableVertexAttribArray(ptPosLoc)
        GLES20.glVertexAttribPointer(ptPosLoc, 3, GLES20.GL_FLOAT, false, 0, buf)

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, pointCount)

        GLES20.glDisableVertexAttribArray(ptPosLoc)
    }

    private fun createExternalTexture(): Int {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        return tex[0]
    }

    private fun linkProgram(vs: String, fs: String): Int {
        val v = compileShader(GLES20.GL_VERTEX_SHADER, vs)
        val f = compileShader(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        return p
    }

    private fun compileShader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        return s
    }

    private fun failureToText(r: TrackingFailureReason): String {
        return when (r) {
            TrackingFailureReason.NONE -> "NONE"
            TrackingFailureReason.BAD_STATE -> "BAD_STATE"
            TrackingFailureReason.INSUFFICIENT_LIGHT -> "INSUFFICIENT_LIGHT"
            TrackingFailureReason.EXCESSIVE_MOTION -> "EXCESSIVE_MOTION"
            TrackingFailureReason.INSUFFICIENT_FEATURES -> "INSUFFICIENT_FEATURES"
            TrackingFailureReason.CAMERA_UNAVAILABLE -> "CAMERA_UNAVAILABLE"
            else -> r.name
        }
    }
}

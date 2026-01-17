package com.example.room3dscan.ar

import android.content.res.AssetManager
import android.opengl.GLES11Ext
import android.opengl.GLES20
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class SimpleBackgroundRenderer {

    var textureId: Int = -1
        private set

    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uTexture = 0

    private lateinit var quadVertices: FloatBuffer
    private lateinit var quadTexCoords: FloatBuffer

    // IMPORTANT: FloatBuffers for ARCore UV transform
    private lateinit var quadTexCoordsIn: FloatBuffer
    private lateinit var quadTexCoordsOut: FloatBuffer

    fun createOnGlThread(assets: AssetManager) {

        // 1) External OES texture
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

        // 2) Fullscreen quad
        quadVertices = floatBufferOf(
            -1f, -1f,
            1f, -1f,
            -1f,  1f,
            1f,  1f
        )

        quadTexCoordsIn = floatBufferOf(
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f
        )

        quadTexCoordsOut = ByteBuffer
            .allocateDirect(8 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        quadTexCoords = quadTexCoordsOut

        // 3) Shaders
        val vs = readAssetText(assets, "shaders/background.vert")
        val fs = readAssetText(assets, "shaders/background.frag")

        val vShader = compileShader(GLES20.GL_VERTEX_SHADER, vs)
        val fShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fs)

        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vShader)
        GLES20.glAttachShader(program, fShader)
        GLES20.glLinkProgram(program)

        aPosition = GLES20.glGetAttribLocation(program, "a_Position")
        aTexCoord = GLES20.glGetAttribLocation(program, "a_TexCoord")
        uTexture = GLES20.glGetUniformLocation(program, "sTexture")
    }

    fun updateUv(frame: com.google.ar.core.Frame) {
        quadTexCoordsIn.position(0)
        quadTexCoordsOut.position(0)
        frame.transformDisplayUvCoords(quadTexCoordsIn, quadTexCoordsOut)
        quadTexCoordsOut.position(0)
    }

    fun draw() {
        if (program == 0) return

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(uTexture, 0)

        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, quadVertices)

        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, quadTexCoords)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    private fun floatBufferOf(vararg data: Float): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(data)
                position(0)
            }

    private fun readAssetText(assets: AssetManager, path: String): String =
        assets.open(path).bufferedReader().use { it.readText() }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }
}

package com.example.room3dscan.ar

import android.content.Context
import android.graphics.BitmapFactory
import android.os.SystemClock
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class KeyframeRecorder(private val context: Context) {

    private var outDir: File? = null
    private var framesDir: File? = null
    private var posesCsv: File? = null

    private var running = false
    private var frameIndex = 0

    // capture throttles
    private var lastCaptureMs = 0L
    private var lastPoseCw: FloatArray? = null

    var minIntervalMs: Long = 500      // 2 fps
    var minTranslationM: Float = 0.20f // capture when moved
    var minRotationDeg: Float = 10f    // capture when rotated

    fun start(mapDir: File) {
        outDir = mapDir
        framesDir = File(mapDir, "frames").apply { mkdirs() }
        posesCsv = File(mapDir, "poses.csv").apply {
            writeText("file,timeNs,w,h,fx,fy,cx,cy,m00,m01,m02,m03,m10,m11,m12,m13,m20,m21,m22,m23,m30,m31,m32,m33\n")
        }

        running = true
        frameIndex = 0
        lastCaptureMs = 0L
        lastPoseCw = null
    }

    fun stop() {
        running = false
    }

    fun isRunning(): Boolean = running

    fun onArFrame(frame: Frame) {
        if (!running) return

        val cam = frame.camera
        if (cam.trackingState != TrackingState.TRACKING) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastCaptureMs < minIntervalMs) return

        val pose = cam.pose
        val poseCw = FloatArray(16)
        pose.toMatrix(poseCw, 0)

        if (lastPoseCw != null) {
            val (t, rDeg) = motionDelta(lastPoseCw!!, poseCw)
            if (t < minTranslationM && rDeg < minRotationDeg) return
        }

        // Acquire camera image (YUV) and save jpg
        val image = try {
            frame.acquireCameraImage()
        } catch (_: Exception) {
            return
        }

        val jpgBytes = try {
            ArFrameCapture.yuv420888ToJpegBytes(image, 90)
        } finally {
            image.close()
        }

        val fName = String.format(Locale.US, "f_%05d.jpg", frameIndex++)
        val f = File(framesDir!!, fName)
        FileOutputStream(f).use { it.write(jpgBytes) }

        // Intrinsics
        val intr = cam.imageIntrinsics
        val fx = intr.focalLength[0]
        val fy = intr.focalLength[1]
        val cx = intr.principalPoint[0]
        val cy = intr.principalPoint[1]
        val w = intr.imageDimensions[0]
        val h = intr.imageDimensions[1]

        // Append CSV row
        val row = buildString {
            append(fName).append(',')
            append(frame.timestamp).append(',')
            append(w).append(',').append(h).append(',')
            append(fx).append(',').append(fy).append(',').append(cx).append(',').append(cy).append(',')
            for (i in 0 until 16) {
                append(poseCw[i])
                if (i != 15) append(',')
            }
            append('\n')
        }
        posesCsv!!.appendText(row)

        lastCaptureMs = now
        lastPoseCw = poseCw
    }

    // returns translation meters and rotation degrees between two camera->world matrices
    private fun motionDelta(aCw: FloatArray, bCw: FloatArray): Pair<Float, Float> {
        val ax = aCw[12]; val ay = aCw[13]; val az = aCw[14]
        val bx = bCw[12]; val by = bCw[13]; val bz = bCw[14]
        val dx = bx - ax
        val dy = by - ay
        val dz = bz - az
        val t = sqrt(dx*dx + dy*dy + dz*dz)

        // rotation: compare forward vectors (camera -Z in camera space => third column of R in world?)
        // In ARCore pose matrix, columns 0..2 represent X,Y,Z axes in world.
        val afx = -aCw[8]; val afy = -aCw[9]; val afz = -aCw[10]
        val bfx = -bCw[8]; val bfy = -bCw[9]; val bfz = -bCw[10]
        val adot = clamp(afx*bfx + afy*bfy + afz*bfz, -1f, 1f)
        val r = acos(adot) * 57.29578f

        return Pair(t, r)
    }

    private fun clamp(v: Float, lo: Float, hi: Float): Float = max(lo, min(hi, v))
}

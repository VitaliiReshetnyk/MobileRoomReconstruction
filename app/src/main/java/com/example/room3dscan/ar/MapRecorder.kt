package com.example.room3dscan.ar

import java.io.File
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

class MapRecorder {

    var useWorldCoordinates: Boolean = true

    var minMoveMeters: Float = 0.25f
    var minTurnRad: Float = 0.20f
    var worldVoxelSize: Float = 0.03f

    private val trajectory = ArrayList<Float>(20_000)

    private var running = false

    private var hasLastKF = false
    private var lastTx = 0f
    private var lastTy = 0f
    private var lastTz = 0f
    private var lastYaw = 0f

    // voxel -> accumulated point + accumulated color
    private val vox = HashMap<Long, Acc>(500_000)

    fun start() {
        running = true
        trajectory.clear()
        vox.clear()
        hasLastKF = false
    }

    fun stop() {
        running = false
    }

    fun addFrame(poseTwc: FloatArray, pointCloudXyz: FloatArray) {
        if (!running) return
        if (!useWorldCoordinates) return

        val tx = poseTwc[12]
        val ty = poseTwc[13]
        val tz = poseTwc[14]

        val yaw = kotlin.math.atan2(poseTwc[4], poseTwc[0])

        if (!hasLastKF) {
            hasLastKF = true
            lastTx = tx; lastTy = ty; lastTz = tz
            lastYaw = yaw
            appendTrajectory(0f, tx, ty, tz)
        } else {
            val dx = tx - lastTx
            val dy = ty - lastTy
            val dz = tz - lastTz
            val move = sqrt(dx * dx + dy * dy + dz * dz)
            val turn = abs(angleDiff(yaw, lastYaw))

            if (move < minMoveMeters && turn < minTurnRad) return

            lastTx = tx; lastTy = ty; lastTz = tz
            lastYaw = yaw
            appendTrajectory(0f, tx, ty, tz)
        }

        val n = pointCloudXyz.size / 3
        val step = maxOf(1, n / 2500)

        var i = 0
        while (i < n) {
            val idx = i * 3
            val cx = pointCloudXyz[idx]
            val cy = pointCloudXyz[idx + 1]
            val cz = pointCloudXyz[idx + 2]

            val wx = poseTwc[0] * cx + poseTwc[4] * cy + poseTwc[8]  * cz + poseTwc[12]
            val wy = poseTwc[1] * cx + poseTwc[5] * cy + poseTwc[9]  * cz + poseTwc[13]
            val wz = poseTwc[2] * cx + poseTwc[6] * cy + poseTwc[10] * cz + poseTwc[14]

            // Color by height (Y): map [-1.5..1.5] meters to [0..255]
            val t = ((wy + 1.5f) / 3.0f).coerceIn(0f, 1f)
            val r = (255f * t).toInt()
            val g = (255f * (1f - kotlin.math.abs(t - 0.5f) * 2f)).toInt()
            val b = (255f * (1f - t)).toInt()

            val key = voxelKey(wx, wy, wz, worldVoxelSize)
            val acc = vox.getOrPut(key) { Acc() }
            acc.sx += wx; acc.sy += wy; acc.sz += wz
            acc.sr += r; acc.sg += g; acc.sb += b
            acc.c += 1

            i += step
        }
    }

    fun savePly(file: File) {
        val n = vox.size
        val header = buildString {
            append("ply\n")
            append("format ascii 1.0\n")
            append("element vertex $n\n")
            append("property float x\n")
            append("property float y\n")
            append("property float z\n")
            append("property uchar red\n")
            append("property uchar green\n")
            append("property uchar blue\n")
            append("end_header\n")
        }
        file.writeText(header)

        val sb = StringBuilder(min(2_000_000, n * 32))
        for (acc in vox.values) {
            val inv = 1f / acc.c.toFloat()
            val x = acc.sx * inv
            val y = acc.sy * inv
            val z = acc.sz * inv
            val rr = (acc.sr / acc.c).coerceIn(0, 255)
            val gg = (acc.sg / acc.c).coerceIn(0, 255)
            val bb = (acc.sb / acc.c).coerceIn(0, 255)

            sb.append(x).append(' ')
                .append(y).append(' ')
                .append(z).append(' ')
                .append(rr).append(' ')
                .append(gg).append(' ')
                .append(bb).append('\n')

            if (sb.length > 2_000_000) {
                file.appendText(sb.toString())
                sb.setLength(0)
            }
        }
        if (sb.isNotEmpty()) file.appendText(sb.toString())
    }

    fun saveTrajectoryCsv(file: File) {
        val sb = StringBuilder()
        sb.append("t,tx,ty,tz,qx,qy,qz,qw\n")
        var i = 0
        while (i < trajectory.size) {
            sb.append(trajectory[i]).append(',')
            sb.append(trajectory[i + 1]).append(',')
            sb.append(trajectory[i + 2]).append(',')
            sb.append(trajectory[i + 3]).append(',')
            sb.append(trajectory[i + 4]).append(',')
            sb.append(trajectory[i + 5]).append(',')
            sb.append(trajectory[i + 6]).append(',')
            sb.append(trajectory[i + 7]).append('\n')
            i += 8
        }
        file.writeText(sb.toString())
    }

    private fun appendTrajectory(t: Float, tx: Float, ty: Float, tz: Float) {
        trajectory.add(t)
        trajectory.add(tx); trajectory.add(ty); trajectory.add(tz)
        trajectory.add(0f); trajectory.add(0f); trajectory.add(0f); trajectory.add(1f)
    }

    private data class Acc(
        var sx: Float = 0f, var sy: Float = 0f, var sz: Float = 0f,
        var sr: Int = 0, var sg: Int = 0, var sb: Int = 0,
        var c: Int = 0
    )

    private fun voxelKey(x: Float, y: Float, z: Float, voxel: Float): Long {
        val vx = kotlin.math.floor(x / voxel).toInt()
        val vy = kotlin.math.floor(y / voxel).toInt()
        val vz = kotlin.math.floor(z / voxel).toInt()
        return pack(vx, vy, vz)
    }

    private fun pack(x: Int, y: Int, z: Int): Long {
        fun to21(v: Int): Long = (v + 1_000_000).toLong() and 0x1FFFFF
        val xx = to21(x)
        val yy = to21(y)
        val zz = to21(z)
        return (xx shl 42) or (yy shl 21) or zz
    }

    private fun angleDiff(a: Float, b: Float): Float {
        var d = a - b
        while (d > Math.PI) d -= (2.0 * Math.PI).toFloat()
        while (d < -Math.PI) d += (2.0 * Math.PI).toFloat()
        return d
    }
}

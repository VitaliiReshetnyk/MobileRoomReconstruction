package com.example.room3dscan.ar

import android.graphics.Bitmap
import kotlin.math.max
import org.opencv.android.OpenCVLoader
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

object FloorplanGenerator {

    data class Result(
        val bitmap: Bitmap,
        val width: Int,
        val height: Int,
        val occupied: Int,

        // NEW: polygon in image pixels (x0,y0,x1,y1,...). Empty if not found.
        val polygonPx: IntArray = intArrayOf(),

        // NEW: mapping metadata to convert pixels -> meters in XZ plane
        val minX: Float = 0f,
        val minZ: Float = 0f,
        val rangeX: Float = 1f,
        val rangeZ: Float = 1f
    )

    fun generate(xyz: FloatArray): Result {
        val n = xyz.size / 3
        require(n > 50) { "Not enough points" }

        // ---------- floor band by Y: lowest 20%..40% ----------
        val ys = FloatArray(n)
        var i = 0
        var p = 0
        while (i < xyz.size) { ys[p++] = xyz[i + 1]; i += 3 }
        ys.sort()
        val yLow = ys[(n * 0.20f).toInt().coerceIn(0, n - 1)]
        val yHigh = ys[(n * 0.40f).toInt().coerceIn(0, n - 1)]

        // ---------- bounds XZ from band (fallback all) ----------
        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY

        i = 0
        var bandCount = 0
        while (i < xyz.size) {
            val x = xyz[i]
            val y = xyz[i + 1]
            val z = xyz[i + 2]
            if (y in yLow..yHigh) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (z < minZ) minZ = z
                if (z > maxZ) maxZ = z
                bandCount++
            }
            i += 3
        }
        if (bandCount < 120) {
            i = 0
            while (i < xyz.size) {
                val x = xyz[i]
                val z = xyz[i + 2]
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (z < minZ) minZ = z
                if (z > maxZ) maxZ = z
                i += 3
            }
        }

        val rangeX = max(0.5f, maxX - minX)
        val rangeZ = max(0.5f, maxZ - minZ)

        val metersPerPixel = 0.02f
        val w = (rangeX / metersPerPixel).toInt().coerceIn(240, 1600)
        val h = (rangeZ / metersPerPixel).toInt().coerceIn(240, 1600)

        val occ = BooleanArray(w * h)

        fun mark(x: Float, z: Float) {
            val u = ((x - minX) / rangeX * (w - 1)).toInt().coerceIn(0, w - 1)
            val v = ((z - minZ) / rangeZ * (h - 1)).toInt().coerceIn(0, h - 1)
            occ[(h - 1 - v) * w + u] = true
        }

        // ---------- Fill occupancy ----------
        i = 0
        while (i < xyz.size) {
            val x = xyz[i]
            val y = xyz[i + 1]
            val z = xyz[i + 2]
            if (bandCount >= 120) {
                if (y in yLow..yHigh) mark(x, z)
            } else {
                mark(x, z)
            }
            i += 3
        }

        // ---------- Densify (closing) ----------
        val dil = dilate(occ, w, h, radius = 3)
        val clo = erode(dil, w, h, radius = 3)
        val vis = dilate(clo, w, h, radius = 1)

        // ---------- Top-K union (apartment-friendly) ----------
        val mask = keepTopKComponents(vis, w, h, k = 8, minSize = 120)

        // ---------- Render base ----------
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFF000000.toInt())

        val fillColor = 0xFF2A2A2A.toInt()
        var occupied = 0
        for (idx in mask.indices) {
            if (mask[idx]) {
                occupied++
                bmp.setPixel(idx % w, idx / w, fillColor)
            }
        }

        // ---------- OpenCV contours + approxPolyDP ----------
        OpenCVLoader.initDebug()

        val binary = Mat(h, w, CvType.CV_8UC1)
        val rowBuf = ByteArray(w)

        for (yy in 0 until h) {
            val base = yy * w
            for (xx in 0 until w) {
                rowBuf[xx] = if (mask[base + xx]) 255.toByte() else 0.toByte()
            }
            binary.put(yy, 0, rowBuf)
        }

        Imgproc.GaussianBlur(binary, binary, Size(3.0, 3.0), 0.0)
        Imgproc.threshold(binary, binary, 80.0, 255.0, Imgproc.THRESH_BINARY)

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()

        Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        // raw contour in yellow
        val yellow = 0xFFFFD54F.toInt()
        for (c in contours) {
            val pts = c.toArray()
            for (pt in pts) {
                val x = pt.x.toInt().coerceIn(0, w - 1)
                val y = pt.y.toInt().coerceIn(0, h - 1)
                bmp.setPixel(x, y, yellow)
            }
        }

        var polygonPx = intArrayOf()

        if (contours.isNotEmpty()) {
            var bestIdx = 0
            var bestArea = 0.0
            for (k in contours.indices) {
                val area = Imgproc.contourArea(contours[k])
                if (area > bestArea) {
                    bestArea = area
                    bestIdx = k
                }
            }

            val best = contours[bestIdx]
            val best2f = MatOfPoint2f(*best.toArray())
            val peri = Imgproc.arcLength(best2f, true)

            val approx2f = MatOfPoint2f()
            Imgproc.approxPolyDP(best2f, approx2f, 0.02 * peri, true)

            val poly = approx2f.toArray()
            if (poly.isNotEmpty()) {
                val arr = IntArray(poly.size * 2)
                for (k in poly.indices) {
                    arr[2 * k] = poly[k].x.toInt()
                    arr[2 * k + 1] = poly[k].y.toInt()
                }
                polygonPx = arr
            }

            // draw polygon edges in green
            val green = 0xFF66FF66.toInt()
            if (poly.size >= 3) {
                for (k in poly.indices) {
                    val a = poly[k]
                    val b = poly[(k + 1) % poly.size]
                    drawLine(bmp, a.x.toInt(), a.y.toInt(), b.x.toInt(), b.y.toInt(), green)
                }
            }

            best2f.release()
            approx2f.release()
        }

        binary.release()
        hierarchy.release()
        for (c in contours) c.release()

        return Result(
            bitmap = bmp,
            width = w,
            height = h,
            occupied = occupied,
            polygonPx = polygonPx,
            minX = minX,
            minZ = minZ,
            rangeX = rangeX,
            rangeZ = rangeZ
        )
    }

    private data class Comp(val size: Int, val pixels: IntArray)

    private fun keepTopKComponents(src: BooleanArray, w: Int, h: Int, k: Int, minSize: Int): BooleanArray {
        val visited = BooleanArray(src.size)
        val q = IntArray(src.size)
        val comp = IntArray(src.size)
        val comps = ArrayList<Comp>(64)

        fun push(idx: Int, tail: Int): Int { q[tail] = idx; return tail + 1 }

        for (start in src.indices) {
            if (!src[start] || visited[start]) continue
            var head = 0
            var tail = 0
            var compSize = 0
            visited[start] = true
            tail = push(start, tail)

            while (head < tail) {
                val idx = q[head++]
                comp[compSize++] = idx
                val x = idx % w
                val y = idx / w

                val n1 = idx - 1
                val n2 = idx + 1
                val n3 = idx - w
                val n4 = idx + w

                if (x > 0 && src[n1] && !visited[n1]) { visited[n1] = true; tail = push(n1, tail) }
                if (x < w - 1 && src[n2] && !visited[n2]) { visited[n2] = true; tail = push(n2, tail) }
                if (y > 0 && src[n3] && !visited[n3]) { visited[n3] = true; tail = push(n3, tail) }
                if (y < h - 1 && src[n4] && !visited[n4]) { visited[n4] = true; tail = push(n4, tail) }
            }

            if (compSize >= minSize) {
                comps.add(Comp(compSize, comp.copyOfRange(0, compSize)))
            }
        }

        comps.sortByDescending { it.size }
        val out = BooleanArray(src.size)
        val kk = k.coerceAtMost(comps.size)
        for (i in 0 until kk) {
            val pix = comps[i].pixels
            for (j in pix.indices) out[pix[j]] = true
        }
        return out
    }

    private fun dilate(src: BooleanArray, w: Int, h: Int, radius: Int): BooleanArray {
        val out = BooleanArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var found = false
                loop@ for (dy in -radius..radius) {
                    val yy = y + dy
                    if (yy !in 0 until h) continue
                    for (dx in -radius..radius) {
                        val xx = x + dx
                        if (xx !in 0 until w) continue
                        if (src[yy * w + xx]) { found = true; break@loop }
                    }
                }
                out[y * w + x] = found
            }
        }
        return out
    }

    private fun erode(src: BooleanArray, w: Int, h: Int, radius: Int): BooleanArray {
        val out = BooleanArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var ok = true
                loop@ for (dy in -radius..radius) {
                    val yy = y + dy
                    if (yy !in 0 until h) { ok = false; break }
                    for (dx in -radius..radius) {
                        val xx = x + dx
                        if (xx !in 0 until w) { ok = false; break@loop }
                        if (!src[yy * w + xx]) { ok = false; break@loop }
                    }
                }
                out[y * w + x] = ok
            }
        }
        return out
    }

    private fun drawLine(bmp: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        var x = x0
        var y = y0
        val dx = kotlin.math.abs(x1 - x0)
        val dy = -kotlin.math.abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        while (true) {
            if (x in 0 until bmp.width && y in 0 until bmp.height) {
                bmp.setPixel(x, y, color)
                if (x + 1 in 0 until bmp.width) bmp.setPixel(x + 1, y, color)
                if (y + 1 in 0 until bmp.height) bmp.setPixel(x, y + 1, color)
            }
            if (x == x1 && y == y1) break
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x += sx }
            if (e2 <= dx) { err += dx; y += sy }
        }
    }
}

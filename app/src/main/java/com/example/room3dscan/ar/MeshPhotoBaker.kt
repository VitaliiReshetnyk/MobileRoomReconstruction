package com.example.room3dscan.ar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

import android.graphics.Color
import kotlin.math.sqrt
import kotlin.math.min
import kotlin.math.max

object MeshPhotoBaker {
    data class FrameGain(
        val file: String,
        val gain: Float
    )


    data class FramePose(
        val file: String,
        val timeNs: Long,
        val w: Int,
        val h: Int,
        val fx: Float,
        val fy: Float,
        val cx: Float,
        val cy: Float,
        val poseCw: FloatArray // 4x4 column-major (ARCore Pose.toMatrix)
    )

    private data class FrameImg(
        val pose: FramePose,
        val bmp: Bitmap,
        val rotDeg: Int // 0/90/180/270 from EXIF
    )

    fun bakeVertexColorsFromFrames(
        meshVertices: FloatArray,
        framesDir: File,
        posesCsv: File,
        maxFramesToUse: Int = 40,
        downscaleMaxW: Int = 640,
        minZ: Float = 0.25f,
        floorY: Float = 0f,
        ceilY: Float = 2.7f,
        epsY: Float = 0.03f,
        log: (String) -> Unit = {}
    ): FloatArray {
        val fpScaledIntrinsics = HashMap<String, Int>()

        fun L(s: String) {
            log(s)
            Log.d("MeshPhotoBaker", s)
        }

        L("START framesDir=${framesDir.absolutePath} exists=${framesDir.exists()} poses=${posesCsv.exists()} verts=${meshVertices.size / 3}")

        val framesAll = loadPoses(posesCsv)
        if (framesAll.isEmpty()) {
            L("ABORT: poses.csv empty or unreadable")
            return solid(meshVertices.size, 0.7f)
        }

        val picked = pickEvenly(framesAll, maxFramesToUse)

        // cache bitmaps
        val bmpCache = HashMap<String, Bitmap>()
        fun loadBmp(name: String): Bitmap? {
            bmpCache[name]?.let { return it }
            val f = File(framesDir, name)
            if (!f.exists()) return null

            val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, opt)
            val inW = opt.outWidth
            val inH = opt.outHeight
            if (inW <= 0 || inH <= 0) return null

            val scale = max(1, inW / downscaleMaxW)

            val opt2 = BitmapFactory.Options().apply {
                inSampleSize = scale
            }
            val bmp = BitmapFactory.decodeFile(f.absolutePath, opt2) ?: return null

// 🔥 КРИТИЧНО: масштабування intrinsics
            fpScaledIntrinsics[name] = scale
            bmpCache[name] = bmp
            return bmp
        }

        // build FrameImg list (bmp + exif rot)
        val frameImgs = ArrayList<FrameImg>(picked.size)
        var missing = 0
        for (fp in picked) {
            val bmp = loadBmp(fp.file)
            if (bmp == null) {
                missing++
                continue
            }
            val rot = readExifRotationDegrees(File(framesDir, fp.file))
            frameImgs.add(FrameImg(fp, bmp, rot))
        }

        L("framesPicked=${picked.size} framesLoaded=${frameImgs.size} missingFrames=$missing")

        val colors = solid(meshVertices.size, 0.7f)

        // auto choose zSign (+1 => zCam = pc[2], -1 => zCam = -pc[2])
        val zSign = chooseBestZSign(meshVertices, frameImgs, minZ, floorY, ceilY, epsY)
        L("chosenZSign=$zSign (zCam = zSign * pc[2])")

        var paintedVerts = 0
        var hitsInFront = 0
        var hitsInBounds = 0
        var rejectsInFront = 0
        var rejectsBounds = 0

        var vi = 0
        while (vi < meshVertices.size) {
            val x = meshVertices[vi]
            val y = meshVertices[vi + 1]
            val z = meshVertices[vi + 2]

            var bestScore = -1f
            var bestR = 0.7f; var bestG = 0.7f; var bestB = 0.7f
            val debugPaintIfAnyHit = false
            var hadAnyInBounds = false

            for (fi in frameImgs) {
                val fp = fi.pose
                val bmp = fi.bmp

                val pc = worldToCamera(x, y, z, fp.poseCw)
                val xCam = pc[0]
                val yCam = pc[1]
                val zCam = zSign * pc[2]

                if (zCam <= minZ) {
                    rejectsInFront++
                    continue
                }
                hitsInFront++

                // project in ORIGINAL sensor coords (fp.w x fp.h)
                val s = fpScaledIntrinsics[fp.file] ?: 1

                val fx = fp.fx / s
                val fy = fp.fy / s
                val cx = fp.cx / s
                val cy = fp.cy / s

                val u0 = fx * (xCam / zCam) + cx
                val v0 = fy * (yCam / zCam) + cy




// try EXIF rotation first, then fallbacks
                var su = 0f
                var sv = 0f
                var ok = false

                val rotCandidates = intArrayOf(fi.rotDeg, 0, 90, 180, 270)
                for (rd in rotCandidates) {
                    val wScaled = (fp.w / s).coerceAtLeast(1)
                    val hScaled = (fp.h / s).coerceAtLeast(1)
                    val uv = mapUvToBitmap(u0, v0, wScaled, hScaled, bmp.width, bmp.height, rd)

                    val u = uv.first
                    val v = uv.second
                    if (u >= 2f && v >= 2f && u <= bmp.width - 3f && v <= bmp.height - 3f) {
                        su = u
                        sv = v
                        ok = true
                        break
                    }
                }

                if (!ok) {
                    rejectsBounds++
                    continue
                }
                hitsInBounds++



                hadAnyInBounds = true

                val score = 1f / zCam
                if (score > bestScore) {
                    val rgb = sampleBilinearRgb01(bmp, su, sv)
                    var r = rgb[0]
                    var g = rgb[1]
                    var b = rgb[2]


                    // 2) (опційно) трошки підсилюємо насиченість/яскравість
                    val mean = (r + g + b) / 3f
                    val sat = 1.4f      // 1.0..2.0
                    r = (mean + (r - mean) * sat).coerceIn(0f, 1f)
                    g = (mean + (g - mean) * sat).coerceIn(0f, 1f)
                    b = (mean + (b - mean) * sat).coerceIn(0f, 1f)

                    val bright = 1.1f   // 1.0..1.3
                    r = (r * bright).coerceIn(0f, 1f)
                    g = (g * bright).coerceIn(0f, 1f)
                    b = (b * bright).coerceIn(0f, 1f)

                    // 3) записуємо у best*
                    bestR = r
                    bestG = g
                    bestB = b
                    bestScore = score
                }

            }

            if (bestScore > 0f) paintedVerts++

            if (debugPaintIfAnyHit) {
                if (hadAnyInBounds) { bestR = 0.1f; bestG = 1.0f; bestB = 0.1f } // зелений якщо хоч раз попало в кадр
                else { bestR = 1.0f; bestG = 0.1f; bestB = 0.1f }              // червоний якщо ніколи не попало
            }

            colors[vi] = bestR
            colors[vi + 1] = bestG
            colors[vi + 2] = bestB
            vi += 3
        }

        // cleanup bitmaps
        for (b in bmpCache.values) b.recycle()
        bmpCache.clear()
// ---- COLORFULNESS CHECK (ВАЖЛИВО) ----
        var colorful = 0
        var jj = 0
        while (jj < colors.size) {
            val r = colors[jj]
            val g = colors[jj + 1]
            val b = colors[jj + 2]
            val chroma = maxOf(r, g, b) - minOf(r, g, b) // 0..1
            if (chroma > 0.10f) colorful++
            jj += 3
        }
        Log.d(
            "MeshPhotoBaker",
            "colorfulVerts=$colorful/${colors.size / 3}"
        )
// ------------------------------------

        // ОЦЕ ГОЛОВНЕ: повний фінальний лог
        L(
            "FINISH paintedVerts=$paintedVerts/${meshVertices.size / 3} " +
                    "hitsInFront=$hitsInFront rejectsInFront=$rejectsInFront " +
                    "hitsInBounds=$hitsInBounds rejectsBounds=$rejectsBounds " +
                    "framesLoaded=${frameImgs.size}"
        )

        return colors
    }
    fun bakeTriangleColorsFromFrames(
        triVertices: FloatArray,          // unindexed triangles: 9 floats per tri
        framesDir: File,
        posesCsv: File,
        maxFramesToUse: Int = 120,
        downscaleMaxW: Int = 960,
        minZ: Float = 0.20f,
        topK: Int = 10,
        minSamplesPerFrame: Int = 2,
        edgeMarginPx: Float = 12f,
        minFacing: Float = 0.10f,
        log: (String) -> Unit = {}
    ): FloatArray {

        val fpScaledIntrinsics = HashMap<String, Int>()

        fun L(s: String) {
            log(s)
            Log.d("MeshPhotoBaker", s)
        }

        val triCount = triVertices.size / 9
        L("START TRI_BAKE_COVERAGE framesDir=${framesDir.absolutePath} poses=${posesCsv.exists()} tris=$triCount")

        val framesAll = loadPoses(posesCsv)
        if (framesAll.isEmpty()) {
            L("ABORT: poses.csv empty/unreadable")
            return solid(triVertices.size, 0.7f)
        }

        val picked = pickEvenly(framesAll, maxFramesToUse)

        // cache bitmaps
        val bmpCache = HashMap<String, Bitmap>()
        fun loadBmp(name: String): Bitmap? {
            bmpCache[name]?.let { return it }
            val f = File(framesDir, name)
            if (!f.exists()) return null

            val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, opt)
            val inW = opt.outWidth
            val inH = opt.outHeight
            if (inW <= 0 || inH <= 0) return null

            val scale = max(1, inW / downscaleMaxW)
            val opt2 = BitmapFactory.Options().apply { inSampleSize = scale }
            val bmp = BitmapFactory.decodeFile(f.absolutePath, opt2) ?: return null

            fpScaledIntrinsics[name] = scale
            bmpCache[name] = bmp
            return bmp
        }

        // build FrameImg list
        val frameImgs = ArrayList<FrameImg>(picked.size)
        var missing = 0
        for (fp in picked) {
            val bmp = loadBmp(fp.file)
            if (bmp == null) { missing++; continue }
            val rot = readExifRotationDegrees(File(framesDir, fp.file))
            frameImgs.add(FrameImg(fp, bmp, rot))
        }

        L("framesPicked=${picked.size} framesLoaded=${frameImgs.size} missingFrames=$missing")

        val colors = solid(triVertices.size, 0.7f)

        val zSign = chooseBestZSign(triVertices, frameImgs, minZ, 0f, 0f, 0f)
        L("chosenZSign=$zSign")

        // barycentric samples inside triangle (stable)
        val bary = arrayOf(
            floatArrayOf(1f/3f, 1f/3f, 1f/3f),
            floatArrayOf(0.60f, 0.20f, 0.20f),
            floatArrayOf(0.20f, 0.60f, 0.20f),
            floatArrayOf(0.20f, 0.20f, 0.60f),
            floatArrayOf(0.45f, 0.45f, 0.10f),
            floatArrayOf(0.45f, 0.10f, 0.45f),
            floatArrayOf(0.10f, 0.45f, 0.45f)
        )

        data class Candidate(val w: Float, val r: Float, val g: Float, val b: Float)

        fun triNormal(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float): FloatArray {
            val abx = bx - ax; val aby = by - ay; val abz = bz - az
            val acx = cx - ax; val acy = cy - ay; val acz = cz - az
            var nx = aby * acz - abz * acy
            var ny = abz * acx - abx * acz
            var nz = abx * acy - aby * acx
            val len = sqrt(nx*nx + ny*ny + nz*nz).coerceAtLeast(1e-12f)
            nx /= len; ny /= len; nz /= len
            return floatArrayOf(nx, ny, nz)
        }

        fun dot(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Float = ax*bx + ay*by + az*bz

        fun edgeWeight(u: Float, v: Float, w: Int, h: Int, margin: Float): Float {
            val du = min(u, (w - 1).toFloat() - u)
            val dv = min(v, (h - 1).toFloat() - v)
            val d = min(du, dv)
            if (d <= 0f) return 0f
            val t = (d / margin).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t) // smoothstep
        }

        fun medianOf(values: FloatArray, n: Int): Float {
            var i = 1
            while (i < n) {
                val key = values[i]
                var j = i - 1
                while (j >= 0 && values[j] > key) {
                    values[j + 1] = values[j]
                    j--
                }
                values[j + 1] = key
                i++
            }
            return if (n % 2 == 1) values[n/2] else 0.5f * (values[n/2 - 1] + values[n/2])
        }

        // ====== Coverage strategy ======
        // Pass 1: strict-ish
        // Pass 2: relaxed if nothing found
        data class PassCfg(
            val minFacing: Float,
            val edgeMarginPx: Float,
            val minSamples: Int,
            val softPenalty: Float // penalty for "soft" (low-sample) candidates
        )

        val pass1 = PassCfg(
            minFacing = minFacing,
            edgeMarginPx = edgeMarginPx,
            minSamples = minSamplesPerFrame,
            softPenalty = 0.35f
        )
        val pass2 = PassCfg(
            minFacing = 0.02f,
            edgeMarginPx = (edgeMarginPx * 0.5f).coerceAtLeast(6f),
            minSamples = 1,
            softPenalty = 0.55f
        )

        fun sampleTriangleColorFromFrames(
            ax: Float, ay: Float, az: Float,
            bx: Float, by: Float, bz: Float,
            cx: Float, cy: Float, cz: Float,
            nx: Float, ny: Float, nz: Float,
            cfg: PassCfg
        ): Candidate? {

            val candidates = ArrayList<Candidate>(topK)

            // centroid for facing
            val mx = (ax + bx + cx) / 3f
            val my = (ay + by + cy) / 3f
            val mz = (az + bz + cz) / 3f

            for (fi in frameImgs) {
                val fp = fi.pose
                val bmp = fi.bmp

                // camera position from poseCw (camera->world)
                val camX = fp.poseCw[12]
                val camY = fp.poseCw[13]
                val camZ = fp.poseCw[14]

                var vx = camX - mx
                var vy = camY - my
                var vz = camZ - mz
                val vlen = sqrt(vx*vx + vy*vy + vz*vz).coerceAtLeast(1e-12f)
                vx /= vlen; vy /= vlen; vz /= vlen

                val facing = dot(nx, ny, nz, vx, vy, vz)
                if (facing < cfg.minFacing) continue

                val s = fpScaledIntrinsics[fp.file] ?: 1
                val fx = fp.fx / s
                val fy = fp.fy / s
                val cx0 = fp.cx / s
                val cy0 = fp.cy / s

                val sampleR = FloatArray(bary.size)
                val sampleG = FloatArray(bary.size)
                val sampleB = FloatArray(bary.size)
                var got = 0
                var edgeWAccum = 0f
                var bestZ = 1e9f

                for (bi in bary.indices) {
                    val wa = bary[bi][0]
                    val wb = bary[bi][1]
                    val wc = bary[bi][2]

                    val x = wa * ax + wb * bx + wc * cx
                    val y = wa * ay + wb * by + wc * cy
                    val z = wa * az + wb * bz + wc * cz

                    val pc = worldToCamera(x, y, z, fp.poseCw)
                    val xCam = pc[0]
                    val yCam = pc[1]
                    val zCam = zSign * pc[2]

                    if (zCam <= minZ) continue
                    if (zCam < bestZ) bestZ = zCam

                    val u0 = fx * (xCam / zCam) + cx0
                    val v0 = fy * (yCam / zCam) + cy0

                    var su = 0f
                    var sv = 0f
                    var ok = false

                    val rotCandidates = intArrayOf(fi.rotDeg, 0, 90, 180, 270)
                    for (rd in rotCandidates) {
                        val wScaled = (fp.w / s).coerceAtLeast(1)
                        val hScaled = (fp.h / s).coerceAtLeast(1)
                        val uv = mapUvToBitmap(u0, v0, wScaled, hScaled, bmp.width, bmp.height, rd)

                        val u = uv.first
                        val v = uv.second
                        if (u >= 2f && v >= 2f && u <= bmp.width - 3f && v <= bmp.height - 3f) {
                            su = u
                            sv = v
                            ok = true
                            break
                        }
                    }
                    if (!ok) continue

                    val eW = edgeWeight(su, sv, bmp.width, bmp.height, cfg.edgeMarginPx)
                    if (eW <= 0f) continue

                    val rgb  = sampleBilinearRgb01(bmp, su, sv)
                    sampleR[got] = rgb[0]
                    sampleG[got] = rgb[1]
                    sampleB[got] = rgb[2]
                    edgeWAccum += eW
                    got++
                    if (got >= bary.size) break
                }

                if (got <= 0) continue

                val rVal = medianOf(sampleR, got)
                val gVal = medianOf(sampleG, got)
                val bVal = medianOf(sampleB, got)

                // base weights
                val wDist = 1f / bestZ.coerceAtLeast(minZ)
                val wAngle = (facing.coerceIn(0f, 1f))
                val wAngle2 = wAngle * wAngle
                val wEdge = (edgeWAccum / got.toFloat()).coerceIn(0f, 1f)

                var w = wDist * wAngle2 * wEdge

                // soft candidate: allow low sample count, but penalize
                if (got < cfg.minSamples) {
                    w *= cfg.softPenalty
                }

                if (w <= 0f) continue

                // keep topK by weight
                if (candidates.size < topK) {
                    candidates.add(Candidate(w, rVal, gVal, bVal))
                } else {
                    var minIdx = 0
                    var minW = candidates[0].w
                    var ii = 1
                    while (ii < candidates.size) {
                        val ww = candidates[ii].w
                        if (ww < minW) { minW = ww; minIdx = ii }
                        ii++
                    }
                    if (w > minW) candidates[minIdx] = Candidate(w, rVal, gVal, bVal)
                }
            }

            if (candidates.isEmpty()) return null

            var sumW = 0f
            var rr = 0f
            var gg = 0f
            var bb = 0f
            var i = 0
            while (i < candidates.size) {
                val c = candidates[i]
                sumW += c.w
                rr += c.r * c.w
                gg += c.g * c.w
                bb += c.b * c.w
                i++
            }
            if (sumW <= 0f) return null

            return Candidate(sumW, (rr / sumW).coerceIn(0f, 1f), (gg / sumW).coerceIn(0f, 1f), (bb / sumW).coerceIn(0f, 1f))
        }

        // last-resort fallback: centroid + best frame by z only (no facing/edge)
        fun fallbackCentroidBestZ(
            mx: Float, my: Float, mz: Float
        ): Candidate? {
            var bestScore = -1f
            var bestR = 0.7f
            var bestG = 0.7f
            var bestB = 0.7f

            for (fi in frameImgs) {
                val fp = fi.pose
                val bmp = fi.bmp

                val pc = worldToCamera(mx, my, mz, fp.poseCw)
                val xCam = pc[0]
                val yCam = pc[1]
                val zCam = zSign * pc[2]
                if (zCam <= minZ) continue

                val s = fpScaledIntrinsics[fp.file] ?: 1
                val fx = fp.fx / s
                val fy = fp.fy / s
                val cx0 = fp.cx / s
                val cy0 = fp.cy / s

                val u0 = fx * (xCam / zCam) + cx0
                val v0 = fy * (yCam / zCam) + cy0

                var su = 0f
                var sv = 0f
                var ok = false

                val rotCandidates = intArrayOf(fi.rotDeg, 0, 90, 180, 270)
                for (rd in rotCandidates) {
                    val wScaled = (fp.w / s).coerceAtLeast(1)
                    val hScaled = (fp.h / s).coerceAtLeast(1)
                    val uv = mapUvToBitmap(u0, v0, wScaled, hScaled, bmp.width, bmp.height, rd)

                    val u = uv.first
                    val v = uv.second
                    if (u >= 1f && v >= 1f && u <= bmp.width - 2f && v <= bmp.height - 2f) {
                        su = u
                        sv = v
                        ok = true
                        break
                    }
                }
                if (!ok) continue

                val score = 1f / zCam
                if (score > bestScore) {
                    val rgb = sampleBilinearRgb01(bmp, su, sv)
                    bestR = rgb[0]
                    bestG = rgb[1]
                    bestB = rgb[2]
                    bestScore = score
                }
            }

            return if (bestScore > 0f) Candidate(bestScore, bestR, bestG, bestB) else null
        }

        var paintedTris = 0
        var relaxedTris = 0
        var fallbackTris = 0
        var defaultTris = 0

        var vi = 0
        var t = 0
        while (t < triCount && vi + 8 < triVertices.size) {

            val ax = triVertices[vi];     val ay = triVertices[vi + 1]; val az = triVertices[vi + 2]
            val bx = triVertices[vi + 3]; val by = triVertices[vi + 4]; val bz = triVertices[vi + 5]
            val cx = triVertices[vi + 6]; val cy = triVertices[vi + 7]; val cz = triVertices[vi + 8]

            val n = triNormal(ax, ay, az, bx, by, bz, cx, cy, cz)
            val nx = n[0]; val ny = n[1]; val nz = n[2]

            // pass 1
            var cand = sampleTriangleColorFromFrames(ax, ay, az, bx, by, bz, cx, cy, cz, nx, ny, nz, pass1)
            var usedMode = 1

            // pass 2 relax
            if (cand == null) {
                cand = sampleTriangleColorFromFrames(ax, ay, az, bx, by, bz, cx, cy, cz, nx, ny, nz, pass2)
                if (cand != null) { usedMode = 2; relaxedTris++ }
            }

            // last fallback (centroid best-z)
            if (cand == null) {
                val mx = (ax + bx + cx) / 3f
                val my = (ay + by + cy) / 3f
                val mz = (az + bz + cz) / 3f
                cand = fallbackCentroidBestZ(mx, my, mz)
                if (cand != null) { usedMode = 3; fallbackTris++ }
            }

            if (cand != null) {
                val r = cand.r
                val g = cand.g
                val b = cand.b

                colors[vi] = r;     colors[vi + 1] = g; colors[vi + 2] = b
                colors[vi + 3] = r; colors[vi + 4] = g; colors[vi + 5] = b
                colors[vi + 6] = r; colors[vi + 7] = g; colors[vi + 8] = b

                paintedTris++
            } else {
                defaultTris++
                // leave default 0.7
            }

            vi += 9
            t++
        }

        for (b in bmpCache.values) {
            try { b.recycle() } catch (_: Throwable) {}
        }
        bmpCache.clear()

        val cov = if (triCount > 0) 100f * paintedTris.toFloat() / triCount.toFloat() else 0f
        L("FINISH TRI_BAKE_COVERAGE painted=$paintedTris/$triCount (${String.format("%.1f", cov)}%) relaxed=$relaxedTris fallback=$fallbackTris default=$defaultTris framesLoaded=${frameImgs.size}")

        return colors
    }

    fun bakeTriangleAtlasTextureFromFrames(
        triVertices: FloatArray,     // unindexed 9 floats per tri
        framesDir: File,
        posesCsv: File,
        atlasW: Int,
        atlasH: Int,
        cellSize: Int,
        cols: Int,
        maxFramesToUse: Int = 120,
        downscaleMaxW: Int = 960,
        minZ: Float = 0.20f,
        topKFramesPerTri: Int = 6,
        minFacing: Float = 0.05f,
        edgeMarginPx: Float = 10f,
        log: (String) -> Unit = {}
    ): Bitmap {
        val bmp = Bitmap.createBitmap(atlasW, atlasH, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFF777777.toInt()) // debug gray background

        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint().apply {
            isFilterBitmap = false
        }

        val triCount = triVertices.size / 9

        for (t in 0 until triCount) {

            // --- 1. Triangle centroid in world ---
            val i = t * 9
            val cx = (triVertices[i] + triVertices[i + 3] + triVertices[i + 6]) / 3f
            val cy = (triVertices[i + 1] + triVertices[i + 4] + triVertices[i + 7]) / 3f
            val cz = (triVertices[i + 2] + triVertices[i + 5] + triVertices[i + 8]) / 3f

            // --- 2. Sample color exactly like before (reuse existing function!) ---
            val rgb = sampleColorAtWorldPointFromFrames(
                x = cx, y = cy, z = cz,
                framesDir = framesDir,
                posesCsv = posesCsv,
                maxFramesToUse = maxFramesToUse,
                downscaleMaxW = downscaleMaxW,
                minZ = minZ,
                topK = topKFramesPerTri,
                edgeMarginPx = edgeMarginPx
            ) ?: continue


            val r = (rgb[0] * 255f).toInt().coerceIn(0, 255)
            val g = (rgb[1] * 255f).toInt().coerceIn(0, 255)
            val b = (rgb[2] * 255f).toInt().coerceIn(0, 255)

            paint.color = android.graphics.Color.rgb(r, g, b)

            // --- 3. Fill atlas cell ---
            val col = t % cols
            val row = t / cols
            val x0 = col * cellSize
            val y0 = row * cellSize

            canvas.drawRect(
                x0.toFloat(),
                y0.toFloat(),
                (x0 + cellSize).toFloat(),
                (y0 + cellSize).toFloat(),
                paint
            )
        }

        log("ATLAS FILLED (triangle-constant colors)")
        return bmp

    }
    fun bakeUvTextureFromFramesGouraud(
        triVertices: FloatArray,   // 9 floats per tri
        triUvs: FloatArray,        // 6 floats per tri (u,v per vertex), normalized 0..1
        framesDir: File,
        posesCsv: File,
        texW: Int = 2048,
        texH: Int = 1024,
        maxFramesToUse: Int = 140,
        downscaleMaxW: Int = 1280,
        minZ: Float = 0.20f,
        topK: Int = 8,
        edgeMarginPx: Float = 12f,
        log: (String) -> Unit = {}
    ): Bitmap {

        fun L(s: String) {
            log(s)
            Log.d("MeshPhotoBaker", s)
        }

        val framesAll = loadPoses(posesCsv)
        if (framesAll.isEmpty()) {
            L("UV_BAKE ABORT: poses empty")
            return Bitmap.createBitmap(texW, texH, Bitmap.Config.ARGB_8888).apply {
                eraseColor(0xFF777777.toInt())
            }
        }

        val picked = pickEvenly(framesAll, maxFramesToUse)

        val fpScaledIntrinsics = HashMap<String, Int>()
        val bmpCache = HashMap<String, Bitmap>()

        fun loadBmp(name: String): Bitmap? {
            bmpCache[name]?.let { return it }
            val f = File(framesDir, name)
            if (!f.exists()) return null

            val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, opt)
            val inW = opt.outWidth
            val inH = opt.outHeight
            if (inW <= 0 || inH <= 0) return null

            val scale = max(1, inW / downscaleMaxW)
            val opt2 = BitmapFactory.Options().apply { inSampleSize = scale }
            val bmp = BitmapFactory.decodeFile(f.absolutePath, opt2) ?: return null

            fpScaledIntrinsics[name] = scale
            bmpCache[name] = bmp
            return bmp
        }

        // FrameImg list
        val frameImgs = ArrayList<FrameImg>(picked.size)
        var missing = 0
        for (fp in picked) {
            val bmp = loadBmp(fp.file)
            if (bmp == null) { missing++; continue }
            val rot = readExifRotationDegrees(File(framesDir, fp.file))
            frameImgs.add(FrameImg(fp, bmp, rot))
        }

        if (frameImgs.isEmpty()) {
            L("UV_BAKE ABORT: no frames loaded (missing=$missing)")
            return Bitmap.createBitmap(texW, texH, Bitmap.Config.ARGB_8888).apply {
                eraseColor(0xFF777777.toInt())
            }
        }

        // ---- Exposure normalization (simple per-frame gain) ----
        fun luma(r: Float, g: Float, b: Float): Float = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val frameGains = HashMap<String, Float>(frameImgs.size)

        run {
            val refLumas = ArrayList<Float>(frameImgs.size)

            // compute reference median luma from center samples
            for (fi in frameImgs) {
                val bmp = fi.bmp
                var sum = 0f
                var cnt = 0
                val sx0 = (bmp.width * 0.35f).toInt()
                val sx1 = (bmp.width * 0.65f).toInt()
                val sy0 = (bmp.height * 0.35f).toInt()
                val sy1 = (bmp.height * 0.65f).toInt()

                var y = sy0
                while (y < sy1) {
                    var x = sx0
                    while (x < sx1) {
                        val rgb = sampleBilinearRgb01(bmp, x.toFloat(), y.toFloat())
                        val r = rgb[0]
                        val g = rgb[1]
                        val b = rgb[2]
                        sum += luma(r, g, b)
                        cnt++
                        x += 24
                    }
                    y += 24
                }
                if (cnt > 0) refLumas.add((sum / cnt).coerceIn(0.01f, 1f))
            }

            if (refLumas.isEmpty()) {
                for (fi in frameImgs) frameGains[fi.pose.file] = 1f
            } else {
                refLumas.sort()
                val ref = refLumas[refLumas.size / 2]

                for (fi in frameImgs) {
                    val bmp = fi.bmp
                    var sum = 0f
                    var cnt = 0
                    val sx0 = (bmp.width * 0.35f).toInt()
                    val sx1 = (bmp.width * 0.65f).toInt()
                    val sy0 = (bmp.height * 0.35f).toInt()
                    val sy1 = (bmp.height * 0.65f).toInt()

                    var y = sy0
                    while (y < sy1) {
                        var x = sx0
                        while (x < sx1) {
                            val rgb = sampleBilinearRgb01(bmp, x.toFloat(), y.toFloat())
                            val r = rgb[0]
                            val g = rgb[1]
                            val b = rgb[2]
                            sum += luma(r, g, b)
                            cnt++
                            x += 24
                        }
                        y += 24
                    }

                    val cur = if (cnt > 0) (sum / cnt).coerceIn(0.01f, 1f) else ref
                    val gain = (ref / cur).coerceIn(0.6f, 1.6f)
                    frameGains[fi.pose.file] = gain
                }
            }
        }

        // choose zSign based on vertices
        val zSign = chooseBestZSign(triVertices, frameImgs, minZ, 0f, 2.7f, 0.03f)
        L("UV_BAKE: frames=${frameImgs.size} missing=$missing zSign=$zSign tex=${texW}x${texH}")

        fun edgeWeight(u: Float, v: Float, w: Int, h: Int, margin: Float): Float {
            val du = kotlin.math.min(u, (w - 1).toFloat() - u)
            val dv = kotlin.math.min(v, (h - 1).toFloat() - v)
            val d = kotlin.math.min(du, dv)
            if (d <= 0f) return 0f
            val t = (d / margin).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        fun sampleColorFast(wx: Float, wy: Float, wz: Float): FloatArray? {
            data class Cand(val w: Float, val r: Float, val g: Float, val b: Float)
            val best = ArrayList<Cand>(topK)

            for (fi in frameImgs) {
                val fp = fi.pose
                val bmp = fi.bmp

                val s = fpScaledIntrinsics[fp.file] ?: scaleOf(fp, bmp)
                val fx = fp.fx / s
                val fy = fp.fy / s
                val cx0 = fp.cx / s
                val cy0 = fp.cy / s

                val pc = worldToCamera(wx, wy, wz, fp.poseCw)
                val zCam = zSign * pc[2]
                if (zCam <= minZ) continue

                val u0 = fx * (pc[0] / zCam) + cx0
                val v0 = fy * (pc[1] / zCam) + cy0

                var su = 0f
                var sv = 0f
                var ok = false

                val rotCandidates = intArrayOf(fi.rotDeg, 0, 90, 180, 270)
                for (rd in rotCandidates) {
                    val wScaled = (fp.w / s).coerceAtLeast(1)
                    val hScaled = (fp.h / s).coerceAtLeast(1)
                    val (uu, vv) = mapUvToBitmap(u0, v0, wScaled, hScaled, bmp.width, bmp.height, rd)
                    if (uu >= 2f && vv >= 2f && uu <= bmp.width - 3f && vv <= bmp.height - 3f) {
                        su = uu; sv = vv; ok = true; break
                    }
                }
                if (!ok) continue

                val ew = edgeWeight(su, sv, bmp.width, bmp.height, edgeMarginPx)
                if (ew <= 0f) continue

                val rgb = sampleBilinearRgb01(bmp, su, sv) // FloatArray[3]
                var r = rgb[0]
                var g = rgb[1]
                var b = rgb[2]

                val gain = frameGains[fp.file] ?: 1f
                r = (r * gain).coerceIn(0f, 1f)
                g = (g * gain).coerceIn(0f, 1f)
                b = (b * gain).coerceIn(0f, 1f)

                val cxImg = bmp.width * 0.5f
                val cyImg = bmp.height * 0.5f
                val dx = (su - cxImg) / cxImg
                val dy = (sv - cyImg) / cyImg
                val center = (1f - (dx * dx + dy * dy)).coerceIn(0f, 1f)

                val w = (1f / zCam) * ew * (0.25f + 0.75f * center)

                if (best.size < topK) best.add(Cand(w, r, g, b))
                else {
                    var minI = 0
                    var minW = best[0].w
                    for (i in 1 until best.size) if (best[i].w < minW) { minW = best[i].w; minI = i }
                    if (w > minW) best[minI] = Cand(w, r, g, b)
                }
            }

            if (best.isEmpty()) return null

            var sumW = 0f
            var rr = 0f
            var gg = 0f
            var bb = 0f
            for (c in best) {
                sumW += c.w
                rr += c.r * c.w
                gg += c.g * c.w
                bb += c.b * c.w
            }
            if (sumW <= 0f) return null

            return floatArrayOf(
                (rr / sumW).coerceIn(0f, 1f),
                (gg / sumW).coerceIn(0f, 1f),
                (bb / sumW).coerceIn(0f, 1f)
            )
        }

        val out = IntArray(texW * texH) { 0xFF3A3A3A.toInt() }

        fun toPixU(u: Float) = (u.coerceIn(0f, 1f) * (texW - 1)).toFloat()
        fun toPixV(v: Float) = ((1f - v.coerceIn(0f, 1f)) * (texH - 1)).toFloat()

        fun edge(a: Float, b: Float, c: Float, d: Float, x: Float, y: Float): Float {
            return (x - a) * (d - b) - (y - b) * (c - a)
        }

        val triCount = triVertices.size / 9
        for (t in 0 until triCount) {
            val vi = t * 9
            val ui = t * 6

            val ax = triVertices[vi];     val ay = triVertices[vi + 1]; val az = triVertices[vi + 2]
            val bx = triVertices[vi + 3]; val by = triVertices[vi + 4]; val bz = triVertices[vi + 5]
            val cx = triVertices[vi + 6]; val cy = triVertices[vi + 7]; val cz = triVertices[vi + 8]

            val au = toPixU(triUvs[ui]);     val av = toPixV(triUvs[ui + 1])
            val bu = toPixU(triUvs[ui + 2]); val bv = toPixV(triUvs[ui + 3])
            val cu = toPixU(triUvs[ui + 4]); val cv = toPixV(triUvs[ui + 5])

            // vertex colors from frames
            val ca = sampleColorFast(ax, ay, az)
            val cb = sampleColorFast(bx, by, bz)
            val cc = sampleColorFast(cx, cy, cz)
            if (ca == null && cb == null && cc == null) continue

            val ra = ca?.get(0) ?: (cb?.get(0) ?: cc!![0])
            val ga = ca?.get(1) ?: (cb?.get(1) ?: cc!![1])
            val ba = ca?.get(2) ?: (cb?.get(2) ?: cc!![2])

            val rb = cb?.get(0) ?: ra
            val gb = cb?.get(1) ?: ga
            val bb2 = cb?.get(2) ?: ba

            val rc = cc?.get(0) ?: ra
            val gc = cc?.get(1) ?: ga
            val bc = cc?.get(2) ?: ba

            val minX = kotlin.math.floor(kotlin.math.min(au, kotlin.math.min(bu, cu))).toInt().coerceIn(0, texW - 1)
            val maxX = kotlin.math.ceil(kotlin.math.max(au, kotlin.math.max(bu, cu))).toInt().coerceIn(0, texW - 1)
            val minY = kotlin.math.floor(kotlin.math.min(av, kotlin.math.min(bv, cv))).toInt().coerceIn(0, texH - 1)
            val maxY = kotlin.math.ceil(kotlin.math.max(av, kotlin.math.max(bv, cv))).toInt().coerceIn(0, texH - 1)

            val area = edge(au, av, bu, bv, cu, cv)
            if (kotlin.math.abs(area) < 1e-6f) continue
            val invArea = 1f / area

            var y = minY
            while (y <= maxY) {
                var x = minX
                while (x <= maxX) {
                    val px = x.toFloat() + 0.5f
                    val py = y.toFloat() + 0.5f

                    val w0 = edge(bu, bv, cu, cv, px, py) * invArea
                    val w1 = edge(cu, cv, au, av, px, py) * invArea
                    val w2 = 1f - w0 - w1

                    if (w0 >= -0.0005f && w1 >= -0.0005f && w2 >= -0.0005f) {
                        val r = (ra * w0 + rb * w1 + rc * w2).coerceIn(0f, 1f)
                        val g = (ga * w0 + gb * w1 + gc * w2).coerceIn(0f, 1f)
                        val b = (ba * w0 + bb2 * w1 + bc * w2).coerceIn(0f, 1f)

                        val ir = (r * 255f).toInt().coerceIn(0, 255)
                        val ig = (g * 255f).toInt().coerceIn(0, 255)
                        val ib = (b * 255f).toInt().coerceIn(0, 255)

                        out[y * texW + x] = (0xFF shl 24) or (ir shl 16) or (ig shl 8) or ib
                    }
                    x++
                }
                y++
            }

            if (t % 80 == 0) L("UV_BAKE tri=$t/$triCount")
        }

        for (b in bmpCache.values) {
            try { b.recycle() } catch (_: Throwable) {}
        }
        bmpCache.clear()

        val bmpOut = Bitmap.createBitmap(texW, texH, Bitmap.Config.ARGB_8888)
        bmpOut.setPixels(out, 0, texW, 0, 0, texW, texH)
        L("UV_BAKE DONE tex=${texW}x${texH}")
        return bmpOut
    }



    fun sampleColorAtWorldPointFromFrames(
        x: Float, y: Float, z: Float,
        framesDir: File,
        posesCsv: File,
        maxFramesToUse: Int = 120,
        downscaleMaxW: Int = 960,
        minZ: Float = 0.20f,
        topK: Int = 8,
        edgeMarginPx: Float = 10f
    ): FloatArray? {

        val framesAll = loadPoses(posesCsv)
        if (framesAll.isEmpty()) return null

        val picked = pickEvenly(framesAll, maxFramesToUse)

        val fpScaledIntrinsics = HashMap<String, Int>()
        val bmpCache = HashMap<String, Bitmap>()

        fun loadBmp(name: String): Bitmap? {
            bmpCache[name]?.let { return it }
            val f = File(framesDir, name)
            if (!f.exists()) return null

            val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, opt)
            val inW = opt.outWidth
            val inH = opt.outHeight
            if (inW <= 0 || inH <= 0) return null

            val scale = max(1, inW / downscaleMaxW)
            val opt2 = BitmapFactory.Options().apply { inSampleSize = scale }
            val bmp = BitmapFactory.decodeFile(f.absolutePath, opt2) ?: return null

            fpScaledIntrinsics[name] = scale
            bmpCache[name] = bmp
            return bmp
        }

        val frameImgs = ArrayList<FrameImg>(picked.size)
        for (fp in picked) {
            val bmp = loadBmp(fp.file) ?: continue
            val rot = readExifRotationDegrees(File(framesDir, fp.file))
            frameImgs.add(FrameImg(fp, bmp, rot))
        }
        if (frameImgs.isEmpty()) return null

        fun edgeWeight(u: Float, v: Float, w: Int, h: Int, margin: Float): Float {
            val du = min(u, (w - 1).toFloat() - u)
            val dv = min(v, (h - 1).toFloat() - v)
            val d = min(du, dv)
            if (d <= 0f) return 0f
            val t = (d / margin).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t) // smoothstep
        }

        // choose zSign by checking which sign yields more valid projections for this point
        fun countValid(zSign: Float): Int {
            var ok = 0
            for (fi in frameImgs) {
                val fp = fi.pose
                val bmp = fi.bmp

                val pc = worldToCamera(x, y, z, fp.poseCw)
                val zCam = zSign * pc[2]
                if (zCam <= minZ) continue

                val s = fpScaledIntrinsics[fp.file] ?: 1
                val fx = fp.fx / s
                val fy = fp.fy / s
                val cx0 = fp.cx / s
                val cy0 = fp.cy / s

                val u0 = fx * (pc[0] / zCam) + cx0
                val v0 = fy * (pc[1] / zCam) + cy0

                val wScaled = (fp.w / s).coerceAtLeast(1)
                val hScaled = (fp.h / s).coerceAtLeast(1)

                val rotCandidates = intArrayOf(fi.rotDeg, 0, 90, 180, 270)
                var inside = false
                for (rd in rotCandidates) {
                    val wScaled = (fp.w / s).coerceAtLeast(1)
                    val hScaled = (fp.h / s).coerceAtLeast(1)
                    val (su, sv) = mapUvToBitmap(u0, v0, wScaled, hScaled, bmp.width, bmp.height, rd)
                    if (su >= 2f && sv >= 2f && su <= bmp.width - 3f && sv <= bmp.height - 3f) {
                        inside = true
                        break
                    }
                }

                if (inside) ok++
            }
            return ok
        }


        val zSign = if (countValid(+1f) >= countValid(-1f)) +1f else -1f

        data class Cand(val w: Float, val r: Float, val g: Float, val b: Float)

        val cands = ArrayList<Cand>(topK)

        for (fi in frameImgs) {
            val fp = fi.pose
            val bmp = fi.bmp

            val pc = worldToCamera(x, y, z, fp.poseCw)
            val zCam = zSign * pc[2]
            if (zCam <= minZ) continue

            val s = fpScaledIntrinsics[fp.file] ?: 1
            val fx = fp.fx / s
            val fy = fp.fy / s
            val cx0 = fp.cx / s
            val cy0 = fp.cy / s

            val u0 = fx * (pc[0] / zCam) + cx0
            val v0 = fy * (pc[1] / zCam) + cy0

            var su = 0f
            var sv = 0f
            var ok = false

            val rotCandidates = intArrayOf(fi.rotDeg, 0, 90, 180, 270)
            for (rd in rotCandidates) {
                val wScaled = (fp.w / s).coerceAtLeast(1)
                val hScaled = (fp.h / s).coerceAtLeast(1)
                val uv = mapUvToBitmap(u0, v0, wScaled, hScaled, bmp.width, bmp.height, rd)

                val u = uv.first
                val v = uv.second
                if (u >= 2f && v >= 2f && u <= bmp.width - 3f && v <= bmp.height - 3f) {
                    su = u
                    sv = v
                    ok = true
                    break
                }
            }
            if (!ok) continue

            val eW = edgeWeight(su, sv, bmp.width, bmp.height, edgeMarginPx)
            if (eW <= 0f) continue

            val rgb = sampleBilinearRgb01(bmp, su, sv)
            var r = rgb[0]
            var g = rgb[1]
            var b = rgb[2]

            val wDist = 1f / zCam
            val w = wDist * eW

            if (cands.size < topK) {
                cands.add(Cand(w, r, g, b))
            } else {
                var minI = 0
                var minW = cands[0].w
                var j = 1
                while (j < cands.size) {
                    val ww = cands[j].w
                    if (ww < minW) { minW = ww; minI = j }
                    j++
                }
                if (w > minW) cands[minI] = Cand(w, r, g, b)
            }
        }

        // recycle
        for (b in bmpCache.values) {
            try { b.recycle() } catch (_: Throwable) {}
        }
        bmpCache.clear()

        if (cands.isEmpty()) return null

        var sumW = 0f
        var rr = 0f
        var gg = 0f
        var bb = 0f
        for (c in cands) {
            sumW += c.w
            rr += c.r * c.w
            gg += c.g * c.w
            bb += c.b * c.w
        }
        if (sumW <= 0f) return null

        return floatArrayOf(
            (rr / sumW).coerceIn(0f, 1f),
            (gg / sumW).coerceIn(0f, 1f),
            (bb / sumW).coerceIn(0f, 1f)
        )
    }




    // ---------------- helpers ----------------

    private fun pickEvenly(frames: List<FramePose>, maxN: Int): List<FramePose> {
        if (frames.size <= maxN) return frames
        val step = frames.size.toFloat() / maxN.toFloat()
        val out = ArrayList<FramePose>(maxN)
        var t = 0f
        while (out.size < maxN) {
            out.add(frames[min(frames.size - 1, t.toInt())])
            t += step
        }
        return out
    }

    private fun solid(n: Int, v: Float): FloatArray {
        val a = FloatArray(n)
        var i = 0
        while (i < n) {
            a[i] = v; a[i + 1] = v; a[i + 2] = v
            i += 3
        }
        return a
    }

    private fun readExifRotationDegrees(file: File): Int {
        return try {
            val exif = ExifInterface(file.absolutePath)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (_: Throwable) {
            0
        }
    }

    private fun mapUvToBitmap(
        u0: Float, v0: Float,
        w: Int, h: Int,      // ВАЖЛИВО: це РОЗМІР КАДРУ ПІСЛЯ downscale (wScaled/hScaled)
        bmpW: Int, bmpH: Int,
        rotDeg: Int
    ): Pair<Float, Float> {

        fun scale(u: Float, v: Float, ww: Int, hh: Int): Pair<Float, Float> {
            val sx = bmpW.toFloat() / ww.toFloat()
            val sy = bmpH.toFloat() / hh.toFloat()
            return Pair(u * sx, v * sy)
        }

        return when (rotDeg) {
            0 -> scale(u0, v0, w, h)

            90 -> { // 90 CW
                val u1 = (h - 1).toFloat() - v0
                val v1 = u0
                scale(u1, v1, h, w) // ww=h, hh=w
            }

            180 -> {
                val u1 = (w - 1).toFloat() - u0
                val v1 = (h - 1).toFloat() - v0
                scale(u1, v1, w, h)
            }

            270 -> { // 270 CW
                val u1 = v0
                val v1 = (w - 1).toFloat() - u0
                scale(u1, v1, h, w) // ww=h, hh=w
            }

            else -> scale(u0, v0, w, h)
        }
    }



    private fun chooseBestZSign(
        meshVertices: FloatArray,
        frames: List<FrameImg>,
        minZ: Float,
        floorY: Float,
        ceilY: Float,
        epsY: Float
    ): Float {
        if (frames.isEmpty()) return -1f

        val testFrames = frames.take(min(5, frames.size))
        val maxVerts = min(120, meshVertices.size / 3)

        fun score(sign: Float): Int {
            var good = 0
            var v = 0
            var cnt = 0
            while (v < meshVertices.size && cnt < maxVerts) {
                val x = meshVertices[v]
                val y = meshVertices[v + 1]
                val z = meshVertices[v + 2]
                v += 3
                cnt++

                for (fi in testFrames) {
                    val fp = fi.pose
                    val bmp = fi.bmp

                    val s = scaleOf(fp, bmp)
                    val fx = fp.fx / s
                    val fy = fp.fy / s
                    val cx = fp.cx / s
                    val cy = fp.cy / s
                    val wScaled = (fp.w / s).coerceAtLeast(1)
                    val hScaled = (fp.h / s).coerceAtLeast(1)

                    val pc = worldToCamera(x, y, z, fp.poseCw)
                    val zCam = sign * pc[2]
                    if (zCam <= minZ) continue

                    val u0 = fx * (pc[0] / zCam) + cx
                    val v0 = fy * (pc[1] / zCam) + cy

                    val rotCandidates = intArrayOf(fi.rotDeg, 0, 90, 180, 270)
                    var inside = false
                    for (rd in rotCandidates) {
                        val wScaled = (fp.w / s).coerceAtLeast(1)
                        val hScaled = (fp.h / s).coerceAtLeast(1)
                        val (su, sv) = mapUvToBitmap(u0, v0, wScaled, hScaled, bmp.width, bmp.height, rd)

                        if (su >= 2f && sv >= 2f && su <= bmp.width - 3f && sv <= bmp.height - 3f) {
                            inside = true
                            break
                        }
                    }
                    if (inside) { good++; break }
                }
            }
            return good
        }

        val sPlus = score(+1f)
        val sMinus = score(-1f)
        Log.d("MeshPhotoBaker", "zSign test: +1 => $sPlus hits, -1 => $sMinus hits")

        if (sPlus == 0 && sMinus == 0) return -1f
        return if (sPlus >= sMinus) +1f else -1f
    }

    private fun scaleOf(fp: FramePose, bmp: Bitmap): Int {
        // оцінюємо inSampleSize, який реально використали при decode
        val sW = max(1, (fp.w + bmp.width - 1) / bmp.width)
        val sH = max(1, (fp.h + bmp.height - 1) / bmp.height)
        return max(1, max(sW, sH))
    }

    private fun loadPoses(csv: File): List<FramePose> {
        if (!csv.exists()) return emptyList()
        val lines = csv.readLines()
        if (lines.size <= 1) return emptyList()

        val out = ArrayList<FramePose>(lines.size - 1)
        for (k in 1 until lines.size) {
            val p = lines[k].split(',')
            if (p.size < 8 + 16) continue

            val file = p[0].trim()
            val timeNs = p[1].toLong()
            val w = p[2].toInt()
            val h = p[3].toInt()
            val fx = p[4].toFloat()
            val fy = p[5].toFloat()
            val cx = p[6].toFloat()
            val cy = p[7].toFloat()

            val m = FloatArray(16)
            var i = 0
            while (i < 16) {
                m[i] = p[8 + i].toFloat()
                i++
            }
            out.add(FramePose(file, timeNs, w, h, fx, fy, cx, cy, m))
        }
        return out
    }

    private fun sampleColorFromFrames(
        wx: Float, wy: Float, wz: Float,
        frames: List<FrameImg>,
        fpScaledIntrinsics: Map<String, Int>,
        zSign: Float,
        minZ: Float,
        topK: Int,
        edgeMarginPx: Float
    ): FloatArray? {

        // topK weighted blend (linear in "display" space; швидко і практично)
        var wSum = 0f
        var rSum = 0f
        var gSum = 0f
        var bSum = 0f

        var used = 0

        for (fi in frames) {
            val fp = fi.pose
            val bmp = fi.bmp

            val pc = worldToCamera(wx, wy, wz, fp.poseCw)
            val xCam = pc[0]
            val yCam = pc[1]
            val zCam = zSign * pc[2]
            if (zCam <= minZ) continue

            val s = fpScaledIntrinsics[fp.file] ?: 1
            val fx = fp.fx / s
            val fy = fp.fy / s
            val cx = fp.cx / s
            val cy = fp.cy / s

            val u0 = fx * (xCam / zCam) + cx
            val v0 = fy * (yCam / zCam) + cy

            val wScaled = (fp.w / s).coerceAtLeast(1)
            val hScaled = (fp.h / s).coerceAtLeast(1)

            val rotCandidates = intArrayOf(fi.rotDeg, 0, 90, 180, 270)
            var su = 0f
            var sv = 0f
            var ok = false

            for (rd in rotCandidates) {
                val wScaled = (fp.w / s).coerceAtLeast(1)
                val hScaled = (fp.h / s).coerceAtLeast(1)
                val uv = mapUvToBitmap(u0, v0, wScaled, hScaled, bmp.width, bmp.height, rd)

                val uu = uv.first
                val vv = uv.second
                if (uu >= edgeMarginPx && vv >= edgeMarginPx &&
                    uu <= bmp.width - 1f - edgeMarginPx && vv <= bmp.height - 1f - edgeMarginPx
                ) {
                    su = uu
                    sv = vv
                    ok = true
                    break
                }
            }
            if (!ok) continue

            val rgb = sampleBilinearRgb01(bmp, su, sv)
            var r = rgb[0]
            var g = rgb[1]
            var b = rgb[2]

            // weight: ближче = сильніше (це вже прибирає частину смуг)
            val w = (1f / zCam).coerceIn(0f, 5f)

            rSum += r * w
            gSum += g * w
            bSum += b * w
            wSum += w

            used++
            if (used >= topK) break
        }

        if (wSum <= 1e-6f) return null
        return floatArrayOf(rSum / wSum, gSum / wSum, bSum / wSum)
    }

    private fun worldToCamera(wx: Float, wy: Float, wz: Float, poseCw: FloatArray): FloatArray {
        // poseCw: column-major camera->world
        val r00 = poseCw[0];  val r01 = poseCw[4];  val r02 = poseCw[8]
        val r10 = poseCw[1];  val r11 = poseCw[5];  val r12 = poseCw[9]
        val r20 = poseCw[2];  val r21 = poseCw[6];  val r22 = poseCw[10]

        val tx = poseCw[12]
        val ty = poseCw[13]
        val tz = poseCw[14]

        val px = wx - tx
        val py = wy - ty
        val pz = wz - tz

        // Pc = R^T*(Pw - t)
        val xCam = r00 * px + r10 * py + r20 * pz
        val yCam = r01 * px + r11 * py + r21 * pz
        val zCam = r02 * px + r12 * py + r22 * pz

        return floatArrayOf(xCam, yCam, zCam)
    }
    private fun clamp01(x: Float): Float = when {
        x < 0f -> 0f
        x > 1f -> 1f
        else -> x
    }

    private fun sampleBilinearRgb01(bmp: Bitmap, x: Float, y: Float): FloatArray {
        val w = bmp.width
        val h = bmp.height
        if (w <= 1 || h <= 1) {
            val p = bmp.getPixel(0, 0)
            return floatArrayOf(((p shr 16) and 255) / 255f, ((p shr 8) and 255) / 255f, (p and 255) / 255f)
        }

        val xf = x.coerceIn(0f, (w - 1).toFloat())
        val yf = y.coerceIn(0f, (h - 1).toFloat())

        val x0 = xf.toInt().coerceIn(0, w - 1)
        val y0 = yf.toInt().coerceIn(0, h - 1)
        val x1 = (x0 + 1).coerceIn(0, w - 1)
        val y1 = (y0 + 1).coerceIn(0, h - 1)

        val tx = xf - x0
        val ty = yf - y0

        fun rgb(p: Int): FloatArray {
            return floatArrayOf(((p shr 16) and 255) / 255f, ((p shr 8) and 255) / 255f, (p and 255) / 255f)
        }

        val c00 = rgb(bmp.getPixel(x0, y0))
        val c10 = rgb(bmp.getPixel(x1, y0))
        val c01 = rgb(bmp.getPixel(x0, y1))
        val c11 = rgb(bmp.getPixel(x1, y1))

        val r0 = c00[0] * (1f - tx) + c10[0] * tx
        val g0 = c00[1] * (1f - tx) + c10[1] * tx
        val b0 = c00[2] * (1f - tx) + c10[2] * tx

        val r1 = c01[0] * (1f - tx) + c11[0] * tx
        val g1 = c01[1] * (1f - tx) + c11[1] * tx
        val b1 = c01[2] * (1f - tx) + c11[2] * tx

        return floatArrayOf(
            clamp01(r0 * (1f - ty) + r1 * ty),
            clamp01(g0 * (1f - ty) + g1 * ty),
            clamp01(b0 * (1f - ty) + b1 * ty)
        )
    }

    fun bakeUvTextureFromFramesBestFrame(
        triVertices: FloatArray,   // 9 floats per tri
        triUvs: FloatArray,        // 6 floats per tri (u,v per vertex) 0..1
        framesDir: File,
        posesCsv: File,
        texW: Int = 2048,
        texH: Int = 1024,
        maxFramesToUse: Int = 140,
        downscaleMaxW: Int = 1280,
        minZ: Float = 0.20f,
        topKFrames: Int = 8,
        edgeMarginPx: Float = 14f,
        log: (String) -> Unit = {}
    ): Bitmap {

        fun L(s: String) { log(s); Log.d("MeshPhotoBaker", s) }

        val framesAll = loadPoses(posesCsv)
        if (framesAll.isEmpty()) {
            L("UV_BESTFRAME ABORT: poses empty")
            return Bitmap.createBitmap(texW, texH, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF777777.toInt()) }
        }

        val picked = pickEvenly(framesAll, maxFramesToUse)

        val fpScaledIntrinsics = HashMap<String, Int>()
        val bmpCache = HashMap<String, Bitmap>()

        fun loadBmp(name: String): Bitmap? {
            bmpCache[name]?.let { return it }
            val f = File(framesDir, name)
            if (!f.exists()) return null

            val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, opt)
            val inW = opt.outWidth
            val inH = opt.outHeight
            if (inW <= 0 || inH <= 0) return null

            val scale = max(1, inW / downscaleMaxW)
            val opt2 = BitmapFactory.Options().apply { inSampleSize = scale }
            val bmp = BitmapFactory.decodeFile(f.absolutePath, opt2) ?: return null

            fpScaledIntrinsics[name] = scale
            bmpCache[name] = bmp
            return bmp
        }

        val frameImgs = ArrayList<FrameImg>(picked.size)
        var missing = 0
        for (fp in picked) {
            val bmp = loadBmp(fp.file)
            if (bmp == null) { missing++; continue }
            val rot = readExifRotationDegrees(File(framesDir, fp.file))
            frameImgs.add(FrameImg(fp, bmp, rot))
        }

        if (frameImgs.isEmpty()) {
            L("UV_BESTFRAME ABORT: no frames loaded (missing=$missing)")
            return Bitmap.createBitmap(texW, texH, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF777777.toInt()) }
        }

        val zSign = chooseBestZSign(triVertices, frameImgs, minZ, 0f, 2.7f, 0.03f)
        L("UV_BESTFRAME: frames=${frameImgs.size} missing=$missing zSign=$zSign tex=${texW}x${texH}")

        fun edge(a: Float, b: Float, c: Float, d: Float, x: Float, y: Float): Float {
            return (x - a) * (d - b) - (y - b) * (c - a)
        }

        fun toPixU(u: Float) = (u.coerceIn(0f, 1f) * (texW - 1)).toFloat()
        fun toPixV(v: Float) = ((1f - v.coerceIn(0f, 1f)) * (texH - 1)).toFloat()

        // output texture
        val out = IntArray(texW * texH) { 0xFF2F2F2F.toInt() }

        data class FrameScore(val fi: FrameImg, val score: Float)
        fun triNormal(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float): FloatArray {
            val abx = bx - ax; val aby = by - ay; val abz = bz - az
            val acx = cx - ax; val acy = cy - ay; val acz = cz - az
            var nx = aby * acz - abz * acy
            var ny = abz * acx - abx * acz
            var nz = abx * acy - aby * acx
            val len = kotlin.math.sqrt(nx*nx + ny*ny + nz*nz).coerceAtLeast(1e-12f)
            nx /= len; ny /= len; nz /= len
            return floatArrayOf(nx, ny, nz)
        }
        fun dot(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Float = ax*bx + ay*by + az*bz

        fun pickTopFramesForPoint(wx: Float, wy: Float, wz: Float): List<FrameImg> {
            val tmp = ArrayList<FrameScore>(frameImgs.size)
            for (fi in frameImgs) {
                val fp = fi.pose
                val bmp = fi.bmp

                val pc = worldToCamera(wx, wy, wz, fp.poseCw)
                val zCam = zSign * pc[2]
                if (zCam <= minZ) continue

                val s = fpScaledIntrinsics[fp.file] ?: 1
                val fx = fp.fx / s
                val fy = fp.fy / s
                val cx0 = fp.cx / s
                val cy0 = fp.cy / s

                val u0 = fx * (pc[0] / zCam) + cx0
                val v0 = fy * (pc[1] / zCam) + cy0

                val wScaled = (fp.w / s).coerceAtLeast(1)
                val hScaled = (fp.h / s).coerceAtLeast(1)

                // try EXIF + fallbacks
                var ok = false
                val rotCandidates = intArrayOf(fi.rotDeg, 0, 90, 180, 270)
                for (rd in rotCandidates) {
                    val (su, sv) = mapUvToBitmap(u0, v0, wScaled, hScaled, bmp.width, bmp.height, rd)
                    if (su >= edgeMarginPx && sv >= edgeMarginPx &&
                        su <= bmp.width - 1f - edgeMarginPx && sv <= bmp.height - 1f - edgeMarginPx
                    ) {
                        ok = true
                        break
                    }
                }
                if (!ok) continue

                // ближче = краще
                val sc = (1f / zCam).coerceIn(0f, 10f)
                tmp.add(FrameScore(fi, sc))
            }

            if (tmp.isEmpty()) return emptyList()
            tmp.sortByDescending { it.score }
            val n = min(topKFrames, tmp.size)
            val outF = ArrayList<FrameImg>(n)
            for (i in 0 until n) outF.add(tmp[i].fi)
            return outF
        }

        fun trySampleFromFrames(wx: Float, wy: Float, wz: Float, frames: List<FrameImg>): Int? {
            for (fi in frames) {
                val fp = fi.pose
                val bmp = fi.bmp

                val pc = worldToCamera(wx, wy, wz, fp.poseCw)
                val zCam = zSign * pc[2]
                if (zCam <= minZ) continue

                val s = fpScaledIntrinsics[fp.file] ?: 1
                val fx = fp.fx / s
                val fy = fp.fy / s
                val cx0 = fp.cx / s
                val cy0 = fp.cy / s

                val u0 = fx * (pc[0] / zCam) + cx0
                val v0 = fy * (pc[1] / zCam) + cy0

                val wScaled = (fp.w / s).coerceAtLeast(1)
                val hScaled = (fp.h / s).coerceAtLeast(1)

                var su = 0f
                var sv = 0f
                var ok = false

                val rotCandidates = intArrayOf(fi.rotDeg, 0, 90, 180, 270)
                for (rd in rotCandidates) {
                    val (uu, vv) = mapUvToBitmap(u0, v0, wScaled, hScaled, bmp.width, bmp.height, rd)
                    if (uu >= edgeMarginPx && vv >= edgeMarginPx &&
                        uu <= bmp.width - 1f - edgeMarginPx && vv <= bmp.height - 1f - edgeMarginPx
                    ) {
                        su = uu
                        sv = vv
                        ok = true
                        break
                    }
                }
                if (!ok) continue

                val rgb = sampleBilinearRgb01(bmp, su, sv)
                val r = (rgb[0] * 255f).toInt().coerceIn(0, 255)
                val g = (rgb[1] * 255f).toInt().coerceIn(0, 255)
                val b = (rgb[2] * 255f).toInt().coerceIn(0, 255)
                return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            return null
        }

        val triCount = triVertices.size / 9
        for (t in 0 until triCount) {
            val vi = t * 9
            val ui = t * 6

            val ax = triVertices[vi];     val ay = triVertices[vi + 1]; val az = triVertices[vi + 2]
            val bx = triVertices[vi + 3]; val by = triVertices[vi + 4]; val bz = triVertices[vi + 5]
            val cx = triVertices[vi + 6]; val cy = triVertices[vi + 7]; val cz = triVertices[vi + 8]

            val au = toPixU(triUvs[ui]);     val av = toPixV(triUvs[ui + 1])
            val bu = toPixU(triUvs[ui + 2]); val bv = toPixV(triUvs[ui + 3])
            val cu = toPixU(triUvs[ui + 4]); val cv = toPixV(triUvs[ui + 5])

            val minX = kotlin.math.floor(kotlin.math.min(au, kotlin.math.min(bu, cu))).toInt().coerceIn(0, texW - 1)
            val maxX = kotlin.math.ceil(kotlin.math.max(au, kotlin.math.max(bu, cu))).toInt().coerceIn(0, texW - 1)
            val minY = kotlin.math.floor(kotlin.math.min(av, kotlin.math.min(bv, cv))).toInt().coerceIn(0, texH - 1)
            val maxY = kotlin.math.ceil(kotlin.math.max(av, kotlin.math.max(bv, cv))).toInt().coerceIn(0, texH - 1)

            val area = edge(au, av, bu, bv, cu, cv)
            if (kotlin.math.abs(area) < 1e-6f) continue
            val invArea = 1f / area

            // pick best frames using centroid
            val mx = (ax + bx + cx) / 3f
            val my = (ay + by + cy) / 3f
            val mz = (az + bz + cz) / 3f

            val n = triNormal(ax, ay, az, bx, by, bz, cx, cy, cz)
            val nx = n[0]; val ny = n[1]; val nz = n[2]

            val topFrames = pickTopFramesForPoint(mx, my, mz).filter { fi ->
                val fp = fi.pose
                val camX = fp.poseCw[12]
                val camY = fp.poseCw[13]
                val camZ = fp.poseCw[14]
                var vx = camX - mx
                var vy = camY - my
                var vz = camZ - mz
                val len = kotlin.math.sqrt(vx*vx + vy*vy + vz*vz).coerceAtLeast(1e-12f)
                vx /= len; vy /= len; vz /= len
                val facing = dot(nx, ny, nz, vx, vy, vz)
                facing > 0.12f
            }

            if (topFrames.isEmpty()) continue

            var y = minY
            while (y <= maxY) {
                var x = minX
                while (x <= maxX) {
                    val px = x.toFloat() + 0.5f
                    val py = y.toFloat() + 0.5f

                    val w0 = edge(bu, bv, cu, cv, px, py) * invArea
                    val w1 = edge(cu, cv, au, av, px, py) * invArea
                    val w2 = 1f - w0 - w1

                    if (w0 >= -0.0005f && w1 >= -0.0005f && w2 >= -0.0005f) {
                        // world point of this texel via barycentric weights
                        val wx = ax * w0 + bx * w1 + cx * w2
                        val wy = ay * w0 + by * w1 + cy * w2
                        val wz = az * w0 + bz * w1 + cz * w2

                        val pix = trySampleFromFrames(wx, wy, wz, topFrames)
                        if (pix != null) out[y * texW + x] = pix
                    }
                    x++
                }
                y++
            }

            if (t % 60 == 0) L("UV_BESTFRAME tri=$t/$triCount")
        }

        for (b in bmpCache.values) { try { b.recycle() } catch (_: Throwable) {} }
        bmpCache.clear()

        val bmpOut = Bitmap.createBitmap(texW, texH, Bitmap.Config.ARGB_8888)
        bmpOut.setPixels(out, 0, texW, 0, 0, texW, texH)
        L("UV_BESTFRAME DONE tex=${texW}x${texH}")
        return bmpOut
    }


}

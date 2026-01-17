package com.example.room3dscan
import com.example.room3dscan.ar.MeshExpand

import android.os.Bundle
import android.view.MotionEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.room3dscan.ar.FloorplanGenerator
import com.example.room3dscan.ar.MeshExtruder
import com.example.room3dscan.ar.MeshPlyLoader
import com.example.room3dscan.ar.MeshViewerRenderer
import com.example.room3dscan.ar.PlyLoader
import com.example.room3dscan.databinding.ActivityViewerBinding
import java.io.File
import kotlin.math.abs



class ViewerActivity : AppCompatActivity() {

    private lateinit var b: ActivityViewerBinding
    private lateinit var renderer: MeshViewerRenderer

    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    private var loadedCloud: PlyLoader.PlyPointCloud? = null
    private var loadedMapDir: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(b.root)

        renderer = MeshViewerRenderer { msg ->
            runOnUiThread {
                // не затираємо, якщо вже є наш дебаг у статусі
                val cur = b.txtViewerStatus.text?.toString() ?: ""
                val isOurDebug = cur.contains("firstPoseFile=") || cur.contains("colors range=")
                if (!isOurDebug) b.txtViewerStatus.text = msg
            }
        }


        b.glViewer.preserveEGLContextOnPause = true
        b.glViewer.setEGLContextClientVersion(2)
        b.glViewer.setRenderer(renderer)
        b.glViewer.renderMode = android.opengl.GLSurfaceView.RENDERMODE_CONTINUOUSLY

        b.glViewer.post {
            b.glViewer.holder.setFixedSize(b.glViewer.width, b.glViewer.height)
        }

        b.btnLoadLatest.setOnClickListener { loadLatestMap() }
        b.btnResetView.setOnClickListener { renderer.resetView() }
        b.btnExportMesh.setOnClickListener { exportMeshAndLoad() }

        b.glViewer.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    dragging = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return@setOnTouchListener true
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    lastX = event.x
                    lastY = event.y

                    renderer.addYawPitch(dx * 0.005f, -dy * 0.005f)
                    if (abs(dy) > abs(dx) * 1.2f) renderer.addZoom(dy * 0.01f)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun loadLatestMap() {
        val mapsDir = File(getExternalFilesDir(null), "maps")
        if (!mapsDir.exists()) {
            b.txtViewerStatus.text = "No maps folder"
            return
        }

        val latest = mapsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.maxByOrNull { it.lastModified() }

        if (latest == null) {
            b.txtViewerStatus.text = "No saved maps"
            return
        }

        val cloudPly = File(latest, "cloud.ply")
        if (!cloudPly.exists()) {
            b.txtViewerStatus.text = "No cloud.ply in latest map"
            return
        }

        loadedCloud = PlyLoader.loadAsciiPly(cloudPly)
        val c = loadedCloud!!
        var minR = 1f; var maxR = 0f
        var minG = 1f; var maxG = 0f
        var minB = 1f; var maxB = 0f
        var i = 0
        while (i < c.rgb.size) {
            val r = c.rgb[i]
            val g = c.rgb[i + 1]
            val b = c.rgb[i + 2]
            if (r < minR) minR = r
            if (r > maxR) maxR = r
            if (g < minG) minG = g
            if (g > maxG) maxG = g
            if (b < minB) minB = b
            if (b > maxB) maxB = b
            i += 3
        }
        b.txtViewerStatus.text = "RGB range R[$minR..$maxR] G[$minG..$maxG] B[$minB..$maxB]"

        loadedMapDir = latest

        //b.txtViewerStatus.text = "Loaded: cloud.ply (${loadedCloud!!.xyz.size / 3} pts)"

        val meshPly = File(latest, "apartment_mesh_colored.ply")

        if (meshPly.exists()) {
            // якщо є colored ply — все одно пере-бейкаємо кольори (бо loader не читає RGB)
            Toast.makeText(this, "Mesh found. Press Export 3D mesh to colorize.", Toast.LENGTH_LONG).show()
            loadMesh(meshPly) // просто показати геометрію
        } else {
            Toast.makeText(this, "Mesh not found yet. Press Export 3D mesh.", Toast.LENGTH_LONG).show()
        }

    }

    private fun exportMeshAndLoad() {
        val cloud = loadedCloud
        val dir = loadedMapDir
        if (cloud == null || dir == null) {
            Toast.makeText(this, "Load a map first", Toast.LENGTH_SHORT).show()
            return
        }

        val fp = FloorplanGenerator.generate(cloud.xyz)
        if (fp.polygonPx.isEmpty()) {
            Toast.makeText(this, "No polygon found yet (scan more)", Toast.LENGTH_LONG).show()
            return
        }

        val polygonList = ArrayList<Pair<Int, Int>>(fp.polygonPx.size / 2)
        var i = 0
        while (i + 1 < fp.polygonPx.size) {
            polygonList.add(Pair(fp.polygonPx[i], fp.polygonPx[i + 1]))
            i += 2
        }

        val cleanedPolygon = com.example.room3dscan.ar.PolygonCleaner.clean(
            poly = polygonList,
            minEdgePx = 6,
            rdpEpsPx = 3.0f,
            collinearDeg = 8.0f
        )

        val orthoPolygon = com.example.room3dscan.ar.Orthogonalizer.orthogonalize(cleanedPolygon)

        val finalPolygon = com.example.room3dscan.ar.PolygonCleaner.clean(
            poly = orthoPolygon,
            minEdgePx = 6,
            rdpEpsPx = 2.0f,
            collinearDeg = 5.0f
        )

        val mesh = MeshExtruder.buildExtrudedMesh(
            polygonPx = finalPolygon,
            w = fp.width,
            h = fp.height,
            minX = fp.minX,
            minZ = fp.minZ,
            rangeX = fp.rangeX,
            rangeZ = fp.rangeZ,
            heightMeters = 2.7f,
            wallStepMeters = 0.03f
        )

        val framesDir = File(dir, "frames")
        val posesCsv = File(dir, "poses.csv")

        b.txtViewerStatus.text =
            "Baking texture...\nframes=${framesDir.exists()} poses=${posesCsv.exists()}"

        Thread {
            val triVerts = MeshExpand.expandToUnindexedTriangles(mesh.vertices, mesh.faces)
            val triUvs = MeshExpand.expandToUnindexedUvs(mesh.uvs, mesh.faces)

            val texW = 4096
            val texH = 2048

            val texBmp = com.example.room3dscan.ar.MeshPhotoBaker.bakeUvTextureFromFramesBestFrame(
                triVertices = triVerts,
                triUvs = triUvs,
                framesDir = framesDir,
                posesCsv = posesCsv,
                texW = texW,
                texH = texH,
                maxFramesToUse = 140,
                downscaleMaxW = 1920,
                minZ = 0.20f,
                topKFrames = 8,
                edgeMarginPx = 40f,
                log = { s ->
                    runOnUiThread {
                        val cur = b.txtViewerStatus.text?.toString() ?: ""
                        b.txtViewerStatus.text = (cur + "\n" + s).takeLast(1600)
                    }
                }
            )

            runOnUiThread {
                renderer.setMeshUnindexedTextured(triVerts, triUvs, texBmp)
                b.txtViewerStatus.text = "TEXTURE DONE: tris=${triVerts.size / 9}, tex=${texW}x${texH}"
            }
        }.start()
    }






    private fun loadMesh(meshFile: File) {
        val md = MeshPlyLoader.loadAsciiMeshPly(meshFile)
        //renderer.setMesh(md.vertices, md.faces)
    }
    private fun saveUnindexedColoredPly(
        triVerts: FloatArray,
        triColors: FloatArray,
        outFile: File
    ) {
        // triVerts.size == triColors.size, both are 3 floats per vertex, unindexed
        val vCount = triVerts.size / 3
        val fCount = triVerts.size / 9

        val sb = StringBuilder()
        sb.appendLine("ply")
        sb.appendLine("format ascii 1.0")
        sb.appendLine("element vertex $vCount")
        sb.appendLine("property float x")
        sb.appendLine("property float y")
        sb.appendLine("property float z")
        sb.appendLine("property uchar red")
        sb.appendLine("property uchar green")
        sb.appendLine("property uchar blue")
        sb.appendLine("element face $fCount")
        sb.appendLine("property list uchar int vertex_indices")
        sb.appendLine("end_header")

        var i = 0
        while (i < triVerts.size) {
            val x = triVerts[i]
            val y = triVerts[i + 1]
            val z = triVerts[i + 2]

            val r = (triColors[i].coerceIn(0f, 1f) * 255f).toInt()
            val g = (triColors[i + 1].coerceIn(0f, 1f) * 255f).toInt()
            val b = (triColors[i + 2].coerceIn(0f, 1f) * 255f).toInt()

            sb.append(x).append(' ')
                .append(y).append(' ')
                .append(z).append(' ')
                .append(r).append(' ')
                .append(g).append(' ')
                .append(b).append('\n')

            i += 3
        }

        // faces: sequential triplets (0,1,2), (3,4,5), ...
        var f = 0
        var base = 0
        while (f < fCount) {
            sb.append("3 ")
                .append(base).append(' ')
                .append(base + 1).append(' ')
                .append(base + 2).append('\n')
            base += 3
            f++
        }

        outFile.writeText(sb.toString())
    }

}

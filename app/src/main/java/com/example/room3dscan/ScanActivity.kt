package com.example.room3dscan

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.room3dscan.ar.MapRecorder
import com.example.room3dscan.ar.ScanRenderer
import com.example.room3dscan.databinding.ActivityScanBinding
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Session
import java.io.File
import org.opencv.android.OpenCVLoader


class ScanActivity : AppCompatActivity() {

    private lateinit var b: ActivityScanBinding

    private var session: Session? = null
    private var renderer: ScanRenderer? = null
    private var recorder: MapRecorder? = null
    private var keyframes: com.example.room3dscan.ar.KeyframeRecorder? = null
    private var currentMapDir: File? = null


    private var isRecording = false

    private val RC_CAMERA = 10

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityScanBinding.inflate(layoutInflater)
        setContentView(b.root)
        OpenCVLoader.initDebug()

        b.btnStart.setOnClickListener {
            if (session == null) {
                Toast.makeText(this, "ARCore not running", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // створюємо папку мапи ОДРАЗУ на старті запису
            val outDir = File(getExternalFilesDir(null), "maps/${System.currentTimeMillis()}")
            outDir.mkdirs()
            currentMapDir = outDir

            recorder?.start()
            keyframes?.start(outDir)

            isRecording = true
            b.btnStart.isEnabled = false
            b.btnStop.isEnabled = true
            b.btnSave.isEnabled = false
        }

        b.btnStop.setOnClickListener {
            isRecording = false
            recorder?.stop()
            keyframes?.stop()

            b.btnStart.isEnabled = true
            b.btnStop.isEnabled = false
            b.btnSave.isEnabled = true
        }

        b.btnSave.setOnClickListener {
            val rec = recorder ?: return@setOnClickListener
            val outDir = currentMapDir ?: run {
                Toast.makeText(this, "Nothing to save (press Start first)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val plyFile = File(outDir, "cloud.ply")
            val trajFile = File(outDir, "trajectory.csv")
            rec.savePly(plyFile)
            rec.saveTrajectoryCsv(trajFile)

            Toast.makeText(this, "Saved: ${outDir.absolutePath}", Toast.LENGTH_LONG).show()
        }


        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), RC_CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()

        if (!hasCameraPermission()) return

        // 1) Check ARCore availability
        val availability = ArCoreApk.getInstance().checkAvailability(this)
        if (!availability.isSupported) {
            b.txtStatus.text = "Status: ARCore not supported on this device"
            disableAll()
            return
        }

        // 2) Request install if needed
        try {
            val installStatus = ArCoreApk.getInstance().requestInstall(this, true)
            if (installStatus == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                b.txtStatus.text = "Status: Installing ARCore..."
                disableAll()
                return
            }
        } catch (e: Exception) {
            b.txtStatus.text = "Status: ARCore install failed"
            disableAll()
            return
        }

        // 3) Create session
        if (session == null) {
            try {
                session = Session(this).apply {
                    val config = com.google.ar.core.Config(this)
                    if (this.isDepthModeSupported(com.google.ar.core.Config.DepthMode.AUTOMATIC)) {
                        config.depthMode = com.google.ar.core.Config.DepthMode.AUTOMATIC
                    }
                    configure(config)
                }
            } catch (e: Exception) {
                b.txtStatus.text = "Status: Failed to create ARCore session"
                disableAll()
                return
            }
        }

        // 4) Setup renderer + recorder
        if (renderer == null) {
            recorder = MapRecorder().apply {
                useWorldCoordinates = true
            }
            keyframes = com.example.room3dscan.ar.KeyframeRecorder(this)
            renderer = ScanRenderer(
                getDisplayRotation = { this.display?.rotation ?: 0 },
                statusCallback = { status, points ->
                    runOnUiThread {
                        b.txtStatus.text = "Status: $status"
                        b.txtPoints.text = "Points: $points"
                    }
                },
                onFrameData = { poseTwc, pointCloudXyz ->
                    if (isRecording) {
                        recorder?.addFrame(poseTwc, pointCloudXyz)
                    }
                }
            )
            renderer?.setFrameCallback { frame ->
                if (isRecording) keyframes?.onArFrame(frame)
            }

            b.glSurfaceView.preserveEGLContextOnPause = true
            b.glSurfaceView.setEGLContextClientVersion(2)
            b.glSurfaceView.setRenderer(renderer)
            b.glSurfaceView.renderMode = android.opengl.GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        renderer?.setSession(session!!)


        session?.resume()
        b.glSurfaceView.onResume()

        b.txtStatus.text = "Status: Ready (press Start)"
        b.btnStart.isEnabled = true
    }

    override fun onPause() {
        super.onPause()
        b.glSurfaceView.onPause()
        session?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        session?.close()
        session = null
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun disableAll() {
        b.btnStart.isEnabled = false
        b.btnStop.isEnabled = false
        b.btnSave.isEnabled = false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RC_CAMERA) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                onResume()
            } else {
                b.txtStatus.text = "Status: Camera permission denied"
                disableAll()
            }
        }
    }
}

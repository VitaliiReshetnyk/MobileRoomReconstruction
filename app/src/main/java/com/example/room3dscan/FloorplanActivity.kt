package com.example.room3dscan

import android.graphics.Bitmap
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.room3dscan.ar.FloorplanGenerator
import com.example.room3dscan.ar.PlyLoader
import com.example.room3dscan.databinding.ActivityFloorplanBinding
import java.io.File

class FloorplanActivity : AppCompatActivity() {

    private lateinit var b: ActivityFloorplanBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityFloorplanBinding.inflate(layoutInflater)
        setContentView(b.root)

        val plyPath = intent.getStringExtra("plyPath")
        if (plyPath.isNullOrBlank()) {
            b.txtFloorplanInfo.text = "No plyPath passed"
            return
        }

        val ply = File(plyPath)
        if (!ply.exists()) {
            b.txtFloorplanInfo.text = "PLY not found"
            return
        }

        val cloud = PlyLoader.loadAsciiPly(ply)
        val result = FloorplanGenerator.generate(cloud.xyz)

        b.imgFloorplan.setImageBitmap(result.bitmap)
        b.txtFloorplanInfo.text = "Floorplan: ${result.width}x${result.height}, occupied: ${result.occupied}"
    }
}

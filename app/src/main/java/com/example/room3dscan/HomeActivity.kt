package com.example.room3dscan

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.room3dscan.databinding.ActivityHomeBinding

class HomeActivity : AppCompatActivity() {

    private lateinit var b: ActivityHomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnScan.setOnClickListener {
            startActivity(Intent(this, ScanActivity::class.java))
        }

        b.btnView.setOnClickListener {
            startActivity(Intent(this, ViewerActivity::class.java))
        }

        b.btnRender360.setOnClickListener {
            startActivity(Intent(this, Render360Activity::class.java))
        }
    }
}

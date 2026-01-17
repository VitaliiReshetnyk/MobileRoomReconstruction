package com.example.room3dscan

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.room3dscan.databinding.ActivityRender360Binding

class Render360Activity : AppCompatActivity() {

    private lateinit var b: ActivityRender360Binding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityRender360Binding.inflate(layoutInflater)
        setContentView(b.root)
    }
}

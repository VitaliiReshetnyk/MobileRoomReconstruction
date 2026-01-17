package com.example.room3dscan.ar

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import android.view.WindowManager

class DisplayRotationHelper(private val activity: Activity) : DisplayManager.DisplayListener {

    private var viewportWidth = 0
    private var viewportHeight = 0
    private var viewportChanged = false
    private val display: Display =
        (activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay

    fun onResume() {
        (activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .registerDisplayListener(this, null)
    }

    fun onPause() {
        (activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .unregisterDisplayListener(this)
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        viewportChanged = true
    }

    fun updateSessionIfNeeded(session: com.google.ar.core.Session) {
        if (viewportChanged) {
            val rotation = display.rotation
            session.setDisplayGeometry(rotation, viewportWidth, viewportHeight)
            viewportChanged = false
        }
    }

    override fun onDisplayAdded(displayId: Int) {}
    override fun onDisplayRemoved(displayId: Int) {}

    override fun onDisplayChanged(displayId: Int) {
        viewportChanged = true
    }

    companion object {
        fun rotationToDegrees(rotation: Int): Int = when (rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }
}

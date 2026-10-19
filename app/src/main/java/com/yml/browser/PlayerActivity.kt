package com.yml.browser

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.VideoView

class PlayerActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        // FLAG_SECURE: blocks screenshots / screen recording and hides it in recents
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        val v = VideoView(this)
        val mc = MediaController(this); mc.setAnchorView(v); v.setMediaController(mc)
        val fl = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(v, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        }
        setContentView(fl)
        v.setVideoPath(intent.getStringExtra("p"))
        v.setOnPreparedListener { it.start() }
    }
}

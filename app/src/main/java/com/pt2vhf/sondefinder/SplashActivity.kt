package com.pt2vhf.sondefinder

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/** Opening screen with the app title and www.PT2VHF.com, then the main screen. */
class SplashActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val next = Runnable {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        ui.postDelayed(next, 1800L)
    }

    override fun onDestroy() {
        ui.removeCallbacks(next)
        super.onDestroy()
    }
}

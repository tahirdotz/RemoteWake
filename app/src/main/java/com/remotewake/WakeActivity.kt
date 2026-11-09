package com.remotewake

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

/**
 * Shown on Phone A, over the lock screen, when Phone B wakes it. It keeps the screen on for a
 * minute so you have time to connect with a remote-control app, then lets the phone sleep again.
 * It does NOT unlock the phone: "Unlock" brings up your pattern as usual.
 */
class WakeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_wake)

        findViewById<TextView>(R.id.wake_time).text = getString(
            R.string.wake_time,
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()),
        )
        findViewById<Button>(R.id.unlock).setOnClickListener {
            getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this, null)
        }
        findViewById<Button>(R.id.close).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        Notifications.cancelWake(this)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ finish() }, KEEP_ON_MS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Woken again while already showing: start the minute again.
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ finish() }, KEEP_ON_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val KEEP_ON_MS = 60_000L

        fun intent(context: Context): Intent =
            Intent(context, WakeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }
}

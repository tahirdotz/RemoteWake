package com.remotewake

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast

/**
 * Phone B: fullscreen view of Phone A's screen. Touch events are sent to Phone A
 * through the relay, and Phone A replays them via the accessibility service.
 */
class ViewerActivity : Activity() {

    private lateinit var imageView: ImageView
    private lateinit var statusText: TextView
    private var connection: ControlConnection? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)

        imageView = findViewById(R.id.screen_view)
        statusText = findViewById(R.id.viewer_status)

        // Fullscreen immersive
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val prefs = Prefs(this)
        val relayUrl = prefs.controlRelay
        val code = prefs.remoteCode

        if (relayUrl == null || code == null) {
            statusText.text = getString(R.string.viewer_needs_setup)
            return
        }

        statusText.text = getString(R.string.viewer_connecting)

        connection = ControlConnection(relayUrl, code, "viewer")
        connection!!.connect(object : ControlConnection.Listener {
            override fun onConnected() {
                statusText.visibility = View.GONE
                Toast.makeText(this@ViewerActivity, R.string.viewer_connected, Toast.LENGTH_SHORT).show()
            }

            override fun onDisconnected(reason: String) {
                statusText.visibility = View.VISIBLE
                statusText.text = getString(R.string.viewer_disconnected, reason)
            }

            override fun onFrame(jpeg: ByteArray) {
                val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
                runOnUiThread {
                    if (!isDestroyed) {
                        imageView.setImageBitmap(bitmap)
                        if (statusText.visibility == View.VISIBLE) statusText.visibility = View.GONE
                    }
                }
            }

            override fun onCommand(text: String) { /* viewer doesn't receive commands */ }
        })

        // Send touches to Phone A, with coordinates normalised to 0–1 so screen-size differences
        // between Phone A and Phone B don't matter.
        imageView.isClickable = true
        imageView.setOnTouchListener { v, event ->
            val conn = connection ?: return@setOnTouchListener true

            // Map touch position to the image's coordinates (not the ImageView's).
            val drawable = imageView.drawable ?: return@setOnTouchListener true
            val imageWidth = drawable.intrinsicWidth.toFloat()
            val imageHeight = drawable.intrinsicHeight.toFloat()

            val viewWidth = v.width.toFloat()
            val viewHeight = v.height.toFloat()
            val imageAspect = imageWidth / imageHeight
            val viewAspect = viewWidth / viewHeight

            val (drawWidth, drawHeight) = if (imageAspect > viewAspect) {
                viewWidth to viewWidth / imageAspect
            } else {
                viewHeight * imageAspect to viewHeight
            }

            val offsetX = (viewWidth - drawWidth) / 2
            val offsetY = (viewHeight - drawHeight) / 2

            val x = ((event.x - offsetX) / drawWidth).coerceIn(0f, 1f)
            val y = ((event.y - offsetY) / drawHeight).coerceIn(0f, 1f)

            val type = when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> "d"
                MotionEvent.ACTION_MOVE -> "m"
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> "u"
                else -> return@setOnTouchListener true
            }

            val cmd = "{\"t\":\"$type\",\"x\":$x,\"y\":$y}"
            Log.d(TAG, "Touch $type at ($x,$y) → $cmd")
            conn.sendCommand(cmd)
            true
        }
    }

    override fun onDestroy() {
        connection?.disconnect()
        connection = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Viewer"
    }
}

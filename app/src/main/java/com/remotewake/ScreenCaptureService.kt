package com.remotewake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Runs on Phone A. Captures the screen, compresses each frame to JPEG and sends it to
 * Phone B through the relay. Also receives touch commands from Phone B and passes them
 * to [TouchAccessibilityService].
 *
 * Approve screen sharing once before leaving the office. This service keeps running
 * and auto-reconnects to the relay if the connection drops — no second approval needed.
 */
class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var connection: ControlConnection? = null
    private val captureThread = HandlerThread("capture").apply { start() }
    private val captureHandler = Handler(captureThread.looper)
    private val mainHandler = Handler(android.os.Looper.getMainLooper())

    private var lastFrameTime = 0L
    private val frameIntervalMs: Long = 100 // 10 FPS

    private var captureStarted = false
    private var retryCount = 0
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must go foreground before getMediaProjection().
        startForeground(
            NOTIFICATION_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )

        if (intent?.action == ACTION_STOP) {
            stopping = true
            stopSelf()
            return START_NOT_STICKY
        }

        // If we already have a projection running, ignore duplicate starts.
        if (mediaProjection != null) return START_STICKY

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val resultData: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        if (resultData == null || resultCode == 0) {
            stopSelf()
            return START_NOT_STICKY
        }

        val mpManager = getSystemService(MediaProjectionManager::class.java)
        mediaProjection = mpManager.getMediaProjection(resultCode, resultData)
        if (mediaProjection == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        connectToRelay()

        // START_STICKY: if the system kills the service, restart it (projection stays valid).
        return START_STICKY
    }

    private fun connectToRelay() {
        val prefs = Prefs(this)
        val relayUrl = prefs.controlRelay ?: return
        val code = prefs.targetCode ?: return

        connection?.disconnect()
        connection = ControlConnection(relayUrl, code, "source")
        SharingState.update(SharingState.Status.CONNECTING)

        connection!!.connect(object : ControlConnection.Listener {
            override fun onConnected() {
                Log.i(TAG, "Relay connected")
                retryCount = 0
                SharingState.update(SharingState.Status.SHARING)
                if (!captureStarted) startCapture()
            }

            override fun onDisconnected(reason: String) {
                Log.i(TAG, "Relay disconnected: $reason")
                if (stopping) return
                // Auto-reconnect with exponential backoff. MediaProjection stays alive.
                SharingState.update(SharingState.Status.CONNECTING)
                val delay = RETRY_DELAYS[retryCount.coerceAtMost(RETRY_DELAYS.size - 1)]
                retryCount++
                Log.i(TAG, "Reconnecting in ${delay / 1000}s (attempt $retryCount)")
                mainHandler.postDelayed({ if (!stopping) connectToRelay() }, delay)
            }

            override fun onFrame(jpeg: ByteArray) { /* source doesn't receive frames */ }

            override fun onCommand(text: String) {
                handleRemoteInput(text)
            }
        })
    }

    override fun onDestroy() {
        stopping = true
        mainHandler.removeCallbacksAndMessages(null)
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        connection?.disconnect()
        captureThread.quitSafely()
        SharingState.update(SharingState.Status.STOPPED)
        super.onDestroy()
    }

    private fun startCapture() {
        if (captureStarted) return
        captureStarted = true

        val wm = getSystemService(WindowManager::class.java)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        // Capture at half resolution to keep bandwidth under control.
        val scale = SCALE_FACTOR
        val width = (metrics.widthPixels * scale).toInt().let { it - it % 2 } // even
        val height = (metrics.heightPixels * scale).toInt().let { it - it % 2 }
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader!!.setOnImageAvailableListener({ reader ->
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameTime < frameIntervalMs) {
                reader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            lastFrameTime = now

            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val bitmap = imageToBitmap(image, width, height)
                if (bitmap != null) {
                    val stream = ByteArrayOutputStream(width * height / 10)
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
                    bitmap.recycle()
                    connection?.sendFrame(stream.toByteArray())
                }
            } finally {
                image.close()
            }
        }, captureHandler)

        // Android 14+ requires a callback registered before createVirtualDisplay.
        mediaProjection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection stopped by system")
                stopSelf()
            }
        }, captureHandler)

        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "RemoteWake",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, captureHandler,
        )
    }

    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width

        val bitmapWidth = width + rowPadding / pixelStride
        val bitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)

        return if (bitmapWidth != width) {
            Bitmap.createBitmap(bitmap, 0, 0, width, height).also { bitmap.recycle() }
        } else {
            bitmap
        }
    }

    /** Phone B sent a touch or key event — replay it on this phone's screen. */
    private fun handleRemoteInput(text: String) {
        val touch = TouchAccessibilityService.instance ?: return
        try {
            val json = JSONObject(text)
            val type = json.optString("t")
            val screenWidth = resources.displayMetrics.widthPixels.toFloat()
            val screenHeight = resources.displayMetrics.heightPixels.toFloat()
            val x = (json.optDouble("x") * screenWidth).toFloat()
            val y = (json.optDouble("y") * screenHeight).toFloat()

            when (type) {
                "d" -> touch.onTouchDown(x, y)
                "m" -> touch.onTouchMove(x, y)
                "u" -> touch.onTouchUp(x, y)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bad touch command: $text", e)
        }
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_sharing), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) },
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 3, Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_power)
            .setContentTitle(getString(R.string.notification_sharing_title))
            .setContentText(getString(R.string.notification_sharing_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build())
            .build()
    }

    companion object {
        private const val TAG = "ScreenCapture"
        private const val CHANNEL = "screen_sharing"
        const val NOTIFICATION_ID = 3
        const val ACTION_STOP = "com.remotewake.STOP_SHARING"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        private const val SCALE_FACTOR = 0.5f
        private const val JPEG_QUALITY = 35
        private val RETRY_DELAYS = longArrayOf(2_000, 5_000, 10_000, 30_000, 60_000)

        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }
    }
}

package com.remotewake

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

/**
 * WebSocket connection to the self-hosted relay. Used by both phones:
 * - Phone A connects as "source" and sends screen frames, receives touch commands.
 * - Phone B connects as "viewer" and receives screen frames, sends touch commands.
 *
 * All data goes through YOUR relay server and nowhere else.
 */
class ControlConnection(
    private val relayUrl: String,
    private val roomCode: String,
    private val role: String,
) {
    interface Listener {
        fun onConnected()
        fun onDisconnected(reason: String)
        fun onFrame(jpeg: ByteArray)
        fun onCommand(text: String)
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket: no read timeout
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var ws: WebSocket? = null
    @Volatile private var closed = false

    fun connect(listener: Listener) {
        closed = false
        val url = "${relayUrl.trimEnd('/')}/$roomCode/$role"
        Log.i(TAG, "Connecting to $url")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "RemoteWake/1.0")
            .build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Connected as $role")
                main.post { listener.onConnected() }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Binary = screen frame (JPEG)
                listener.onFrame(bytes.toByteArray())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Text = touch command or control message
                listener.onCommand(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Connection closed: $reason")
                main.post { if (!closed) listener.onDisconnected(reason) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Connection failed", t)
                main.post { if (!closed) listener.onDisconnected(t.message ?: "Connection failed") }
            }
        })
    }

    /** Send a JPEG screen frame (source → viewer). */
    fun sendFrame(jpeg: ByteArray): Boolean {
        val socket = ws ?: return false
        // Drop the frame if the send queue is too large (back-pressure).
        if (socket.queueSize() > MAX_QUEUE_BYTES) return false
        return socket.send(ByteString.of(*jpeg))
    }

    /** Send a text command (touch event, key, etc.). */
    fun sendCommand(text: String): Boolean = ws?.send(text) ?: false

    fun disconnect() {
        closed = true
        ws?.close(1000, "User disconnected")
        ws = null
    }

    companion object {
        private const val TAG = "ControlConnection"
        private const val MAX_QUEUE_BYTES = 512 * 1024 // 512 KB
    }
}

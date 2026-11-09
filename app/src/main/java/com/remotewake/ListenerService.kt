package com.remotewake

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.net.HttpURLConnection
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Runs on Phone A. Keeps one HTTPS connection open to the relay and acts on the commands
 * Phone B sends: WAKE turns the screen on, PING just answers. Every command gets a reply
 * with the battery level and whether the screen is on, so Phone B can show what happened.
 *
 * It is a foreground service (the "Listening for Phone B" notification), which is what lets it
 * keep running with the screen off. With battery optimisation off it also keeps its network
 * connection in Doze.
 */
class ListenerService : Service() {
    private lateinit var prefs: Prefs
    private var worker: Worker? = null
    private val replies = Executors.newSingleThreadExecutor()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        Notifications.createChannels(this)
        watchNetwork()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always go to the foreground first: Android requires it after startForegroundService(),
        // even when the service is about to stop.
        goForeground()

        if (intent?.action == ACTION_STOP) prefs.listening = false
        val code = prefs.targetCode
        if (!prefs.listening || prefs.role != Role.TARGET || code == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Start, or restart if the pairing code or relay changed.
        val current = worker
        if (current == null || !current.isAlive || current.code != code || current.server != prefs.server) {
            current?.shutdown()
            worker = Worker(code, prefs.server).also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        worker?.shutdown()
        worker = null
        networkCallback?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        replies.shutdown()
        ListenerState.update(ListenerState.Status.STOPPED)
        super.onDestroy()
    }

    private fun goForeground() {
        val notification = Notifications.listening(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(Notifications.ID_LISTENING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifications.ID_LISTENING, notification)
        }
    }

    /** When the phone moves to another network (Wi-Fi to mobile data, say), reconnect at once. */
    private fun watchNetwork() {
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var lastNetwork: Network? = null

            override fun onAvailable(network: Network) {
                val previous = lastNetwork
                lastNetwork = network
                if (previous != null && previous != network) worker?.dropConnection()
                worker?.retryNow()
            }
        }
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(callback)
        networkCallback = callback
    }

    private fun handle(event: Relay.Event, code: String) {
        // A reconnect asks for messages from the last one's second onwards, so that one comes again.
        if (event.id.isEmpty() || event.id == prefs.lastEventId || event.time < prefs.lastEventTime) return
        prefs.lastEventId = event.id
        prefs.lastEventTime = event.time

        val message = Protocol.decodeCommand(event.message) ?: return
        if (!Protocol.isFresh(event.time, nowSeconds())) {
            Log.i(TAG, "Ignoring old ${message.command} sent at ${event.time}")
            return
        }
        Log.i(TAG, "Received ${message.command} ${message.id}")
        when (message.command) {
            Protocol.Command.WAKE -> {
                Waker.wake(this)
                // Give the screen a moment to come on before reporting whether it did.
                reply(code, message.id, Protocol.Reply.Kind.AWAKE, delayMs = 1_500)
            }
            Protocol.Command.PING -> reply(code, message.id, Protocol.Reply.Kind.PONG, delayMs = 0)
        }
    }

    private fun reply(code: String, id: String, kind: Protocol.Reply.Kind, delayMs: Long) {
        val server = prefs.server
        replies.execute {
            try {
                if (delayMs > 0) Thread.sleep(delayMs)
                val reply = Protocol.Reply(
                    kind = kind,
                    id = id,
                    batteryPercent = DeviceStatus.batteryPercent(this),
                    charging = DeviceStatus.isCharging(this),
                    screenOn = DeviceStatus.isScreenOn(this),
                )
                Relay(server).publish(Protocol.replyTopic(code), Protocol.encodeReply(reply))
            } catch (e: Exception) {
                Log.w(TAG, "Could not send reply", e)
            }
        }
    }

    /** One connection loop for one pairing code and relay. Reconnects with a growing pause on failure. */
    private inner class Worker(val code: String, val server: String) : Thread("relay-listener") {
        @Volatile private var running = true
        @Volatile private var connection: HttpURLConnection? = null
        /** Holds a token when the pause before reconnecting should end early. */
        private val retrySignal = LinkedBlockingQueue<Unit>(1)

        override fun run() {
            val relay = Relay(server)
            val topic = Protocol.commandTopic(code)
            var pauseMs = MIN_PAUSE_MS
            while (running) {
                ListenerState.update(ListenerState.Status.CONNECTING)
                // Pick up commands sent during a short outage, but never ones too old to matter.
                val since = maxOf(prefs.lastEventTime, nowSeconds() - Protocol.MAX_COMMAND_AGE_SECONDS)
                try {
                    relay.stream(
                        topic = topic,
                        since = since,
                        onConnection = { connection = it },
                        onOpen = {
                            pauseMs = MIN_PAUSE_MS
                            if (running) ListenerState.update(ListenerState.Status.CONNECTED)
                        },
                        onEvent = { if (running) handle(it, code) },
                    )
                    if (running) ListenerState.update(ListenerState.Status.RETRYING, getString(R.string.relay_closed))
                } catch (e: Exception) {
                    if (!running) break
                    Log.w(TAG, "Relay connection failed", e)
                    ListenerState.update(ListenerState.Status.RETRYING, e.message ?: e.javaClass.simpleName)
                } finally {
                    connection = null
                }
                if (!running) break
                retrySignal.clear()
                retrySignal.poll(pauseMs, TimeUnit.MILLISECONDS)
                pauseMs = (pauseMs * 2).coerceAtMost(MAX_PAUSE_MS)
            }
        }

        fun retryNow() {
            retrySignal.offer(Unit)
        }

        /** Closes the current connection (off the main thread: closing a TLS socket does network I/O). */
        fun dropConnection() {
            val open = connection ?: return
            Thread { runCatching { open.disconnect() } }.start()
        }

        fun shutdown() {
            running = false
            dropConnection()
            retryNow()
        }
    }

    companion object {
        const val ACTION_STOP = "com.remotewake.STOP"
        private const val TAG = "RemoteWake"
        private const val MIN_PAUSE_MS = 2_000L
        private const val MAX_PAUSE_MS = 60_000L

        private fun nowSeconds() = System.currentTimeMillis() / 1000

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, ListenerService::class.java))
            } catch (e: Exception) {
                // For example ForegroundServiceStartNotAllowedException if started from the background.
                Log.w(TAG, "Could not start listener", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ListenerService::class.java))
        }
    }
}

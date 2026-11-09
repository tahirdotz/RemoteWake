package com.remotewake

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * A small client for an ntfy server (https://github.com/binwiederhier/ntfy), which both phones
 * use as a relay. It works through any Wi-Fi, mobile data, NAT or firewall, because both phones
 * only ever make outgoing HTTPS requests. The public server https://ntfy.sh needs no account;
 * you can also run your own server and enter its address in the app.
 */
class Relay(private val server: String) {

    /** A message the relay delivered. [time] is in Unix seconds. */
    data class Event(val id: String, val time: Long, val message: String)

    fun publish(topic: String, body: String) {
        val connection = open("$server/$topic")
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Relay answered HTTP $code")
        } finally {
            connection.disconnect()
        }
    }

    /** Messages on [topic] sent at or after [sinceSeconds], without waiting for new ones. */
    fun poll(topic: String, sinceSeconds: Long): List<Event> {
        val connection = open("$server/$topic/json?poll=1&since=$sinceSeconds")
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Relay answered HTTP $code")
            return connection.inputStream.bufferedReader().useLines { lines ->
                lines.mapNotNull(::parseEvent).toList()
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Keeps a connection to [topic] open and calls [onEvent] for every message, starting from
     * [since] (a Unix time in seconds). Returns or throws when the connection ends; the caller
     * reconnects. [onConnection] receives the connection so another thread can close it.
     */
    fun stream(
        topic: String,
        since: Long,
        onConnection: (HttpURLConnection) -> Unit,
        onOpen: () -> Unit,
        onEvent: (Event) -> Unit,
    ) {
        val connection = open("$server/$topic/json?since=$since")
        // The relay sends a keepalive every 45 seconds or so; silence for longer means the link is dead.
        connection.readTimeout = STREAM_READ_TIMEOUT_MS
        onConnection(connection)
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Relay answered HTTP $code")
            connection.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val json = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    when (json.optString("event")) {
                        "open" -> onOpen()
                        "message" -> parseEvent(line)?.let(onEvent)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseEvent(line: String): Event? {
        val json = runCatching { JSONObject(line) }.getOrNull() ?: return null
        if (json.optString("event") != "message") return null
        return Event(json.optString("id"), json.optLong("time"), json.optString("message"))
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            setRequestProperty("User-Agent", "RemoteWake/1.0 (Android)")
        }

    companion object {
        const val DEFAULT_SERVER = "https://ntfy.sh"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 20_000
        private const val STREAM_READ_TIMEOUT_MS = 100_000
    }
}

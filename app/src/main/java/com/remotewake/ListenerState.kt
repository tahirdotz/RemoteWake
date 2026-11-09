package com.remotewake

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/** How Phone A's connection to the relay is doing, for its screen. Observers are called on the main thread. */
object ListenerState {
    enum class Status { STOPPED, CONNECTING, CONNECTED, RETRYING }

    @Volatile var status: Status = Status.STOPPED
        private set

    /** For RETRYING: why the last connection failed. */
    @Volatile var detail: String = ""
        private set

    private val observers = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun update(status: Status, detail: String = "") {
        this.status = status
        this.detail = detail
        notifyObservers()
    }

    fun notifyObservers() {
        main.post { observers.forEach { it() } }
    }

    fun observe(observer: () -> Unit) {
        observers += observer
    }

    fun remove(observer: () -> Unit) {
        observers -= observer
    }
}

package com.remotewake

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/** Phone A's screen-sharing state, for its screen. Observers are called on the main thread. */
object SharingState {
    enum class Status { STOPPED, CONNECTING, SHARING }

    @Volatile var status: Status = Status.STOPPED
        private set

    private val observers = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun update(status: Status) {
        this.status = status
        main.post { observers.forEach { it() } }
    }

    fun observe(observer: () -> Unit) { observers += observer }
    fun remove(observer: () -> Unit) { observers -= observer }
}

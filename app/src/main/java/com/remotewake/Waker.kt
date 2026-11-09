package com.remotewake

import android.content.Context
import android.os.PowerManager

/** Turns Phone A's screen on. The lock screen (your pattern) stays: this only lights the screen up. */
object Waker {
    private const val SCREEN_ON_MS = 10_000L

    fun wake(context: Context) {
        val power = context.getSystemService(PowerManager::class.java)
        // 1. The direct way. Deprecated, and newer Android versions may ignore it for background apps,
        //    which is why step 2 exists. When it does work, the screen comes on immediately.
        @Suppress("DEPRECATION")
        power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "RemoteWake:screen",
        ).acquire(SCREEN_ON_MS)

        // 2. The supported way: a full-screen notification opens WakeActivity, which asks to turn the screen on.
        Notifications.showWake(context)
        Prefs(context).lastWokenAt = System.currentTimeMillis()
        ListenerState.notifyObservers()
    }
}

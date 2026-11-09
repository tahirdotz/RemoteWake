package com.remotewake

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts Phone A's listener again after a reboot or app update.
 * Note: after a reboot Android only sends this once the phone has been unlocked one time.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = Prefs(context)
        if (prefs.role == Role.TARGET && prefs.listening && prefs.targetCode != null) {
            ListenerService.start(context)
        }
    }
}

package com.remotewake

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager

/** What Phone A reports back to Phone B. */
object DeviceStatus {
    fun batteryPercent(context: Context): Int =
        context.getSystemService(BatteryManager::class.java)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            .takeIf { it in 0..100 } ?: -1

    fun isCharging(context: Context): Boolean =
        context.getSystemService(BatteryManager::class.java).isCharging

    fun isScreenOn(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isInteractive
}

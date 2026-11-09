package com.remotewake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object Notifications {
    private const val CHANNEL_LISTENING = "listening"
    private const val CHANNEL_WAKE = "wake"
    const val ID_LISTENING = 1
    const val ID_WAKE = 2

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_LISTENING,
                context.getString(R.string.channel_listening),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) },
        )
        // High importance is what lets the full-screen intent switch the screen on.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_WAKE,
                context.getString(R.string.channel_wake),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    fun listening(context: Context): Notification {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            context, 1,
            Intent(context, ListenerService::class.java).setAction(ListenerService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, CHANNEL_LISTENING)
            .setSmallIcon(R.drawable.ic_stat_power)
            .setContentTitle(context.getString(R.string.notification_listening_title))
            .setContentText(context.getString(R.string.notification_listening_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.stop), stop).build())
            .build()
    }

    /** Posting this with the screen off makes Android open [WakeActivity], which turns the screen on. */
    fun showWake(context: Context) {
        val intent = WakeActivity.intent(context)
        val pending = PendingIntent.getActivity(
            context, 2, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL_WAKE)
            .setSmallIcon(R.drawable.ic_stat_power)
            .setContentTitle(context.getString(R.string.notification_wake_title))
            .setContentText(context.getString(R.string.notification_wake_text))
            .setCategory(Notification.CATEGORY_ALARM)
            .setFullScreenIntent(pending, true)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID_WAKE, notification)
    }

    fun cancelWake(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID_WAKE)
    }
}

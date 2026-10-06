package com.pledgex.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * One local reminder per pledge day, so a day is not lost to forgetting: an hour before
 * the check-in window closes (steps, screen), or when it opens (wake-up). Real-day
 * pledges only; a demo day is shorter than the reminder.
 */
object Reminders {
    private const val CHANNEL = "pledge_day"
    private const val REQUEST = 7

    fun schedule(context: Context, atMs: Long, title: String, text: String) {
        if (atMs <= System.currentTimeMillis()) return cancel(context)
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, intent(context, title, text))
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(intent(context, "", ""))
    }

    private fun intent(context: Context, title: String, text: String) = PendingIntent.getBroadcast(
        context, REQUEST,
        Intent(context, ReminderReceiver::class.java).putExtra("title", title).putExtra("text", text),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun show(context: Context, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Pledge reminders", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(1, notification)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Reminders.show(context, intent.getStringExtra("title").orEmpty(), intent.getStringExtra("text").orEmpty())
    }
}

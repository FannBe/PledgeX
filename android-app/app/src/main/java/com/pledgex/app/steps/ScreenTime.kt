package com.pledgex.app.steps

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings

/**
 * Minutes the screen showed an app in a time range, from Android's usage statistics.
 * Needs the special "Usage access" permission, which only the person can grant in
 * Settings. Like steps, the number comes from the phone and the program trusts it.
 */
class ScreenTime(private val context: Context) {
    private val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    fun hasAccess(): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun accessIntent() = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Foreground minutes between `fromMs` and `toMs` (device clock), across all apps. */
    fun minutes(fromMs: Long, toMs: Long): Long {
        if (toMs <= fromMs || !hasAccess()) return 0
        val events = usage.queryEvents(fromMs, toMs)
        val open = HashMap<String, Long>()
        var total = 0L
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> open[event.packageName + event.className] = event.timeStamp
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                    open.remove(event.packageName + event.className)?.let { total += event.timeStamp - it }
                }
            }
        }
        // Still on screen at the end of the range.
        open.values.forEach { total += toMs - it }
        return total / 60_000
    }
}

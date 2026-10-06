package com.pledgex.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews

/** What the home-screen widget shows; written by the app whenever it changes. */
data class WidgetState(
    val title: String = "PledgeX",
    val day: String = "",
    val value: String = "No active pledge",
    /** 0..1000 */
    val progress: Int = 0,
    val status: String = "Tap to start a pledge",
    /** Device-clock millis when today's window closes, or 0 for no countdown. */
    val deadlineMs: Long = 0,
    val action: String = "OPEN",
    val done: Boolean = false,
)

/**
 * A 4x2 home-screen widget: today's habit, the value against the goal, and a live
 * countdown (a Chronometer, so it ticks without waking the app) to the window closing.
 * Tapping it opens the app; checking in stays a deliberate tap inside it.
 */
class PledgeWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, views(context, load(context))) }
    }

    companion object {
        private const val PREFS = "widget"

        /** Asks the launcher to pin the widget; false when the launcher can't. */
        fun requestPin(context: Context): Boolean {
            val manager = AppWidgetManager.getInstance(context)
            if (!manager.isRequestPinAppWidgetSupported) return false
            return manager.requestPinAppWidget(ComponentName(context, PledgeWidget::class.java), null, null)
        }

        fun push(context: Context, state: WidgetState) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val encoded = listOf(state.title, state.day, state.value, state.progress, state.status, state.deadlineMs, state.action, state.done).joinToString("\u0001")
            if (prefs.getString("state", null) == encoded) return
            prefs.edit().putString("state", encoded).apply()
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PledgeWidget::class.java))
            if (ids.isNotEmpty()) ids.forEach { manager.updateAppWidget(it, views(context, state)) }
        }

        private fun load(context: Context): WidgetState {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("state", null) ?: return WidgetState()
            val p = raw.split("\u0001")
            if (p.size < 8) return WidgetState()
            return WidgetState(p[0], p[1], p[2], p[3].toIntOrNull() ?: 0, p[4], p[5].toLongOrNull() ?: 0, p[6], p[7].toBoolean())
        }

        private fun views(context: Context, s: WidgetState): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_pledge)
            v.setTextViewText(R.id.widget_title, s.title)
            v.setTextViewText(R.id.widget_day, s.day)
            v.setTextViewText(R.id.widget_value, s.value)
            v.setProgressBar(R.id.widget_progress, 1000, s.progress, false)
            v.setTextViewText(R.id.widget_status, s.status)
            v.setTextViewText(R.id.widget_action, s.action)
            val left = s.deadlineMs - System.currentTimeMillis()
            if (s.deadlineMs > 0 && left > 0 && !s.done) {
                v.setViewVisibility(R.id.widget_timer, View.VISIBLE)
                v.setChronometer(R.id.widget_timer, SystemClock.elapsedRealtime() + left, null, true)
                v.setChronometerCountDown(R.id.widget_timer, true)
            } else {
                v.setChronometer(R.id.widget_timer, SystemClock.elapsedRealtime(), null, false)
                v.setViewVisibility(R.id.widget_timer, View.GONE)
            }
            val open = PendingIntent.getActivity(
                context, 3, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            v.setOnClickPendingIntent(R.id.widget_root, open)
            v.setOnClickPendingIntent(R.id.widget_action, open)
            return v
        }
    }
}

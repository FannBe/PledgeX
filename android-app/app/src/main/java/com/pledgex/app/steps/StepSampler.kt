package com.pledgex.app.steps

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper

/**
 * Reads the hardware step counter every half hour while a real steps pledge runs, even
 * with the app closed, and folds it into the pledge's baseline. Without it the baseline
 * of a new day was the last value the app happened to see, possibly hours before.
 *
 * The pledge it serves is stored here by the app (address, chain start, day length,
 * days, chain-clock offset). It survives a reboot through BOOT_COMPLETED.
 */
object StepSampler {
    private const val PREFS = "step_sampler"
    private const val INTERVAL_MS = 30 * 60 * 1000L

    fun follow(context: Context, address: String, start: Long, daySec: Long, totalDays: Int, clockOffset: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("address", address).putLong("start", start).putLong("daySec", daySec)
            .putInt("days", totalDays).putLong("offset", clockOffset).apply()
        schedule(context)
    }

    fun stop(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        context.getSystemService(AlarmManager::class.java).cancel(intent(context))
    }

    fun schedule(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("address", null) == null) return
        context.getSystemService(AlarmManager::class.java).setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            android.os.SystemClock.elapsedRealtime() + INTERVAL_MS, INTERVAL_MS, intent(context),
        )
    }

    private fun intent(context: Context) = PendingIntent.getBroadcast(
        context, 11, Intent(context, StepSampleReceiver::class.java), PendingIntent.FLAG_IMMUTABLE,
    )

    /** One reading of the counter, then the baseline update; stops itself once the pledge is over. */
    fun sample(context: Context, done: () -> Unit) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val address = prefs.getString("address", null) ?: return done()
        val start = prefs.getLong("start", 0)
        val daySec = prefs.getLong("daySec", 86_400).coerceAtLeast(60)
        val days = prefs.getInt("days", 0)
        val now = System.currentTimeMillis() / 1000 + prefs.getLong("offset", 0)
        if (now >= start + days * daySec) { stop(context); return done() }
        if (now < start) return done()
        val day = ((now - start) / daySec).toInt()
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return done()
        val handler = Handler(Looper.getMainLooper())
        val listener = object : SensorEventListener {
            var finished = false
            override fun onSensorChanged(event: SensorEvent) {
                if (finished) return
                finished = true
                manager.unregisterListener(this)
                val stepsPrefs = context.getSharedPreferences("steps", Context.MODE_PRIVATE)
                val (next, _) = advance(StepCounter.load(stepsPrefs, address), day, event.values[0].toLong(), StepCounter.bootCount(context))
                StepCounter.save(stepsPrefs, address, next)
                done()
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (!manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)) return done()
        // The counter usually answers at once; never hold the broadcast past a few seconds.
        handler.postDelayed({ if (!listener.finished) { listener.finished = true; manager.unregisterListener(listener); done() } }, 8_000)
    }
}

class StepSampleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            StepSampler.schedule(context)
        }
        val pending = goAsync()
        StepSampler.sample(context.applicationContext) { pending.finish() }
    }
}

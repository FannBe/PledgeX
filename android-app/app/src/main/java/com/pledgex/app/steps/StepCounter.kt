package com.pledgex.app.steps

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What is remembered per pledge to turn the hardware counter (steps since boot) into
 * steps inside one day window: the counter at the start of that day, the steps carried
 * over a reboot, and the last value seen.
 */
data class Baseline(val day: Int = -1, val base: Long = -1, val carried: Long = 0, val last: Long = -1, val boot: Int = -1)

/**
 * The counting rule, pure so it can be tested. Given the stored baseline, the current
 * day and a new raw `value` (and the boot count), returns the updated baseline and the
 * steps walked today (without simulated ones).
 *
 * - A new day starts from the last value seen before it, which is exact when that value
 *   was read close to the boundary: the background sampler reads it every half hour.
 * - A reboot restarts the counter at zero: today's steps so far are carried over.
 */
fun advance(b: Baseline, day: Int, value: Long, boot: Int): Pair<Baseline, Long> {
    val rebooted = (b.boot != -1 && boot != -1 && boot != b.boot) || (b.last >= 0 && value < b.last)
    var base = b.base
    var carried = b.carried
    if (rebooted && b.day == day && base >= 0) {
        carried += (b.last - base).coerceAtLeast(0)
        base = 0
    }
    if (b.day != day) {
        base = if (b.last >= 0 && !rebooted) b.last else value
        carried = 0
    }
    if (base < 0) base = value
    val next = Baseline(day, base, carried, value, boot)
    return next to (carried + (value - base).coerceAtLeast(0))
}

/**
 * Steps walked inside one day window of a pledge, from the hardware step counter.
 *
 * TYPE_STEP_COUNTER counts since boot and keeps counting while the app is closed. The
 * baseline is kept per pledge (see [advance]); [StepSampler] refreshes it every half
 * hour in the background so a day boundary or a reboot does not lose steps.
 *
 * Demo pledges (minute-long days) may add simulated steps; they are stored apart and the
 * screen labels them as simulated. The program never sees the difference: the step
 * count is reported by the phone, which is the honest limit of this design.
 */
class StepCounter(private val context: Context) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val prefs = context.getSharedPreferences("steps", Context.MODE_PRIVATE)
    private val _reading = MutableStateFlow<Long?>(null)
    /** The raw counter (steps since boot), or null before the first event. */
    val reading: StateFlow<Long?> = _reading
    private var listening = false

    val available get() = sensor != null

    fun start() {
        if (listening || sensor == null) return
        listening = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() {
        if (!listening) return
        manager.unregisterListener(this)
        listening = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        _reading.value = event.values[0].toLong()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** Today's steps for `pledge` on `day`, folding the latest raw `value` into its baseline. */
    fun stepsFor(pledge: String, day: Int, value: Long?): Long {
        val stored = load(prefs, pledge)
        if (value == null) {
            val walked = if (stored.day == day && stored.base >= 0 && stored.last >= 0) stored.carried + (stored.last - stored.base).coerceAtLeast(0) else 0
            return walked + demoSteps(pledge, day)
        }
        val (next, walked) = advance(stored, day, value, bootCount(context))
        save(prefs, pledge, next)
        return walked + demoSteps(pledge, day)
    }

    fun demoSteps(pledge: String, day: Int) = prefs.getLong("p_${pledge}_demo_$day", 0)

    fun addDemoSteps(pledge: String, day: Int, steps: Long) {
        prefs.edit().putLong("p_${pledge}_demo_$day", demoSteps(pledge, day) + steps).apply()
    }

    companion object {
        fun bootCount(context: Context): Int =
            runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1)

        fun load(prefs: android.content.SharedPreferences, pledge: String): Baseline {
            val k = "p_$pledge"
            return Baseline(
                prefs.getInt("${k}_day", -1), prefs.getLong("${k}_base", -1), prefs.getLong("${k}_carried", 0),
                prefs.getLong("${k}_last", -1), prefs.getInt("${k}_boot", -1),
            )
        }

        fun save(prefs: android.content.SharedPreferences, pledge: String, b: Baseline) {
            val k = "p_$pledge"
            prefs.edit().putInt("${k}_day", b.day).putLong("${k}_base", b.base).putLong("${k}_carried", b.carried)
                .putLong("${k}_last", b.last).putInt("${k}_boot", b.boot).apply()
        }
    }
}

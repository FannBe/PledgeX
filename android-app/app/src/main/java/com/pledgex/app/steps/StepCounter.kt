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
 * Steps walked inside one day window of a pledge, from the hardware step counter.
 *
 * TYPE_STEP_COUNTER counts since boot, keeps counting while the app is closed, and
 * restarts at 0 after a reboot. So the app stores, per pledge, the counter value at
 * the start of the current day (the "baseline") and the steps carried over a reboot.
 * The baseline for a new day is the last value seen before it began, which is exact
 * when the app was opened near the day boundary and otherwise counts a few late
 * steps of the previous day into the new one.
 *
 * Demo pledges (minute-long days) may add simulated steps; they are stored apart and
 * the screen labels them as simulated. The program never sees the difference: the
 * step count is reported by the phone, which is the honest limit of this design.
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

    private fun bootCount(): Int =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1)

    /**
     * Today's steps for pledge `pledge` on day `day`, updating the stored baseline with
     * the latest raw `value`. Call on every new reading and when the day changes.
     */
    fun stepsFor(pledge: String, day: Int, value: Long?): Long {
        val k = "p_$pledge"
        val storedDay = prefs.getInt("${k}_day", -1)
        var baseline = prefs.getLong("${k}_base", -1)
        var carried = prefs.getLong("${k}_carried", 0)
        val last = prefs.getLong("${k}_last", -1)
        val lastBoot = prefs.getInt("${k}_boot", -1)
        val boot = bootCount()
        val edit = prefs.edit()

        if (value != null) {
            // A reboot restarts the counter: keep what today had, count again from zero.
            val rebooted = (lastBoot != -1 && boot != -1 && boot != lastBoot) || (last >= 0 && value < last)
            if (rebooted && storedDay == day && baseline >= 0) {
                carried += (last - baseline).coerceAtLeast(0)
                baseline = 0
            }
            if (storedDay != day) {
                baseline = if (last >= 0 && !rebooted) last else value
                carried = 0
            }
            if (baseline < 0) baseline = value
            edit.putInt("${k}_day", day).putLong("${k}_base", baseline).putLong("${k}_carried", carried)
                .putLong("${k}_last", value).putInt("${k}_boot", boot).apply()
        } else if (storedDay != day) {
            return demoSteps(pledge, day)
        }
        val walked = if (value != null && baseline >= 0) (value - baseline).coerceAtLeast(0) else 0
        return carried + walked + demoSteps(pledge, day)
    }

    fun demoSteps(pledge: String, day: Int) = prefs.getLong("p_${pledge}_demo_$day", 0)

    fun addDemoSteps(pledge: String, day: Int, steps: Long) {
        prefs.edit().putLong("p_${pledge}_demo_$day", demoSteps(pledge, day) + steps).apply()
    }
}

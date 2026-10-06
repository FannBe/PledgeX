package com.pledgex.app

import com.pledgex.app.chain.Kind
import java.util.Calendar

/** A pledge to create: what it measures, its target and schedule, and the stake. */
data class HabitSpec(
    val title: String,
    val kind: Int,
    /** Steps (minimum) or screen minutes (maximum); unused for wake-up. */
    val target: Int,
    val days: Int,
    val stake: Long,
    val demo: Boolean,
) {
    val daySec get() = if (demo) DEMO_DAY_SEC else 86_400L

    /** Seconds of each day in which the chain accepts a check-in (0 = all day). */
    val windowSec: Int
        get() = when (kind) {
            Kind.WAKE -> if (demo) 40 else 3_600 // 05:00–06:00
            Kind.SCREEN -> if (demo) 40 else 7_200 // 22:00–24:00
            else -> 0
        }

    /**
     * Chain time of day 0, or 0 for "now". Real wake-up and screen pledges follow the
     * phone's local calendar: wake days start at 05:00, screen days at midnight.
     */
    fun startAt(chainOffset: Long): Long {
        if (demo || kind == Kind.STEPS) return 0
        val cal = Calendar.getInstance()
        val hour = if (kind == Kind.WAKE) 5 else 0
        val next = (cal.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (!after(cal)) add(Calendar.DAY_OF_MONTH, 1)
        }
        return next.timeInMillis / 1000 + chainOffset
    }

    val rule: String
        get() = when (kind) {
            Kind.WAKE -> if (demo) "Check in within the first 40 s of each 2-minute day" else "Check in between 05:00 and 06:00 every day"
            Kind.SCREEN -> "Under ${target.fmt()} min of screen time" + if (demo) ", check in in the last 40 s of each day" else ", check in 22:00–24:00"
            else -> "${target.fmt()} steps a day"
        }

    companion object {
        const val DEMO_DAY_SEC = 120L
    }
}

/** The catalog on the Explore tab: every one of these the program can enforce. */
data class Preset(val spec: HabitSpec, val subtitle: String, val source: String)

val PRESETS = listOf(
    Preset(HabitSpec("10K Steps Daily", Kind.STEPS, 10_000, 7, 1_000, demo = false), "Walk 10,000 steps every day", "Hardware step counter"),
    Preset(HabitSpec("5K Steps Starter", Kind.STEPS, 5_000, 3, 500, demo = false), "Three days to start the habit", "Hardware step counter"),
    Preset(HabitSpec("The 6:00 AM Club", Kind.WAKE, 0, 7, 2_500, demo = false), "Check in between 05:00 and 06:00", "Solana's clock — checked on chain"),
    Preset(HabitSpec("Screen Detox (<2h)", Kind.SCREEN, 120, 7, 1_500, demo = false), "Under 2 hours of screen time a day", "Android usage statistics"),
)

fun kindLabel(kind: Int) = when (kind) {
    Kind.WAKE -> "Wake-up"
    Kind.SCREEN -> "Screen detox"
    else -> "Steps"
}

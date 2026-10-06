package com.pledgex.app

import com.pledgex.app.chain.Commitment
import com.pledgex.app.chain.Kind
import com.pledgex.app.chain.PledgeError
import com.pledgex.app.chain.PledgeProgram
import com.pledgex.app.steps.Baseline
import com.pledgex.app.steps.advance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** The rules the screen and the money depend on, without a device or a chain. */
class LogicTest {
    // ---- steps -------------------------------------------------------------------------

    @Test
    fun firstReadingStartsTheDayAtZero() {
        val (b, steps) = advance(Baseline(), day = 0, value = 12_000, boot = 5)
        assertEquals(0L, steps)
        assertEquals(12_000L, b.base)
    }

    @Test
    fun stepsAreCountedFromTheDaysBaseline() {
        val (b0, _) = advance(Baseline(), 0, 12_000, 5)
        val (_, steps) = advance(b0, 0, 15_500, 5)
        assertEquals(3_500L, steps)
    }

    @Test
    fun aNewDayStartsFromTheLastValueSeen() {
        // Last sample of day 0 at 20,000; at day 1's first reading the counter is 20,400.
        val (b0, _) = advance(Baseline(), 0, 12_000, 5)
        val (b1, _) = advance(b0, 0, 20_000, 5)
        val (b2, steps) = advance(b1, 1, 20_400, 5)
        assertEquals(400L, steps)
        assertEquals(20_000L, b2.base)
        assertEquals(1, b2.day)
    }

    @Test
    fun aRebootKeepsTodaysSteps() {
        // 3,000 walked, reboot (counter back near zero), 500 more.
        val (b0, _) = advance(Baseline(), 0, 10_000, 5)
        val (b1, _) = advance(b0, 0, 13_000, 5)
        val (_, steps) = advance(b1, 0, 500, 6)
        assertEquals(3_500L, steps)
    }

    @Test
    fun aRebootIsSeenEvenWithoutABootCount() {
        val (b0, _) = advance(Baseline(), 0, 10_000, -1)
        val (b1, _) = advance(b0, 0, 11_000, -1)
        val (_, steps) = advance(b1, 0, 200, -1)
        assertEquals(1_200L, steps)
    }

    @Test
    fun aRebootOverNightStartsTheNewDayClean() {
        val (b0, _) = advance(Baseline(), 0, 10_000, 5)
        val (b1, steps) = advance(b0, 1, 300, 6)
        assertEquals(0L, steps)
        assertEquals(300L, b1.base)
    }

    // ---- the money ---------------------------------------------------------------------

    private fun pledge(total: Long, days: Int, kept: Int, bitmap: Long = 0, kind: Int = Kind.STEPS, window: Int = 0) =
        Commitment("c", "o", "s", 10_000, days, kept, 86_400, 1_000_000, total, false, bitmap, 1, kind, window)

    @Test
    fun refundAndBurnAddUpToTheStakeExactly() {
        val c = pledge(1_000 * PledgeProgram.UNIT, 3, 1)
        assertEquals(333_333_333_333L, c.refund) // the program floors
        assertEquals(c.totalAmount, c.refund + c.burn)
    }

    @Test
    fun aPerfectPledgeBurnsNothing() {
        val c = pledge(2_500 * PledgeProgram.UNIT, 7, 7)
        assertEquals(c.totalAmount, c.refund)
        assertEquals(0L, c.burn)
    }

    @Test
    fun theScreenRoundsSoBothHalvesAddUp() {
        val c = pledge(1_000 * PledgeProgram.UNIT, 3, 2)
        assertEquals("666.67", formatSkr(c.refund))
        assertEquals("333.33", formatSkr(c.burn))
    }

    // ---- days and windows --------------------------------------------------------------

    @Test
    fun dayIndexFollowsChainTimeAndStopsAtTheEnd() {
        val c = pledge(UNIT, 3, 0)
        assertEquals(0, c.dayAt(c.start - 50))
        assertEquals(0, c.dayAt(c.start + 10))
        assertEquals(1, c.dayAt(c.start + 86_400))
        assertEquals(3, c.dayAt(c.end + 5))
    }

    @Test
    fun wakeWindowIsTheFirstPartOfTheDayAndScreenTheLast() {
        val wake = pledge(UNIT, 3, 0, kind = Kind.WAKE, window = 3_600)
        assertEquals(wake.dayStart(1), wake.openFrom(1))
        assertEquals(wake.dayStart(1) + 3_600, wake.openUntil(1))
        val screen = pledge(UNIT, 3, 0, kind = Kind.SCREEN, window = 7_200)
        assertEquals(screen.dayEnd(1) - 7_200, screen.openFrom(1))
        assertEquals(screen.dayEnd(1), screen.openUntil(1))
    }

    @Test
    fun bitmapMarksClockedDays() {
        val c = pledge(UNIT, 5, 2, bitmap = 0b10101)
        assertTrue(c.clockedIn(0)); assertTrue(!c.clockedIn(1)); assertTrue(c.clockedIn(4))
    }

    @Test
    fun aRealWakePledgeStartsAtTheNextFiveAm() {
        val spec = HabitSpec("x", Kind.WAKE, 0, 7, 1_000, demo = false)
        val start = spec.startAt(chainOffset = 0)
        val cal = Calendar.getInstance().apply { timeInMillis = start * 1000 }
        assertEquals(5, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
        assertTrue(start > System.currentTimeMillis() / 1000)
        assertTrue(start <= System.currentTimeMillis() / 1000 + 86_400)
        assertEquals(3_600, spec.windowSec)
    }

    @Test
    fun demoAndStepsPledgesStartNow() {
        assertEquals(0L, HabitSpec("x", Kind.WAKE, 0, 3, 1_000, demo = true).startAt(0))
        assertEquals(0L, HabitSpec("x", Kind.STEPS, 8_000, 7, 1_000, demo = false).startAt(0))
    }

    // ---- errors ------------------------------------------------------------------------

    @Test
    fun programErrorsMapFromLandedAndSimulatedFailures() {
        assertEquals(PledgeError.TargetNotMet, PledgeError.from("""{"InstructionError":[2,{"Custom":6000}]}"""))
        assertEquals(PledgeError.LimitExceeded, PledgeError.from("custom program error: 0x177e"))
        assertEquals(PledgeError.FaucetBalanceTooHigh, PledgeError.from("Error Code: FaucetBalanceTooHigh"))
    }

    private companion object { const val UNIT = PledgeProgram.UNIT }
}

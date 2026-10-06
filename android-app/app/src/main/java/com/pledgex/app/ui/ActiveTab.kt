package com.pledgex.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.pledgex.app.ShareCard
import com.pledgex.app.UiState
import com.pledgex.app.chain.Commitment
import com.pledgex.app.chain.Kind
import com.pledgex.app.fmt
import com.pledgex.app.formatSkr
import com.pledgex.app.kindLabel

@Composable
fun ActiveTab(s: UiState, a: Actions) {
    s.lastResult?.let { ResultCard(s, a) }
    val c = s.commitment
    when {
        !s.loaded -> GlassCard { Body("Reading your pledge from Solana devnet…") }
        c == null -> NoPledge(s, a)
        !c.started(s.chainNow) -> NotStarted(s, c)
        s.chainNow >= c.end -> SettleCard(s, c, a)
        else -> {
            DeadlineBanner(s, c)
            StatsRow(s, c)
            HabitHero(s, c, a)
            StakeCard(s, c, a)
        }
    }
}

/** Streaks and the all-time count: what makes tomorrow's clock-in worth keeping. */
@Composable
private fun StatsRow(s: UiState, c: Commitment) {
    val streak = c.currentStreak(s.chainNow)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile("Current streak", "$streak", if (streak > 0) Mint else TextLo, if (streak == 1) "day" else "days", Modifier.weight(1f))
        StatTile("Best streak", "${maxOf(s.profile?.bestStreak ?: 0, streak)}", Gold, "real pledges", Modifier.weight(1f))
        StatTile("Days kept", "${(s.profile?.kept ?: 0) + c.completedDays}", Cyan, "all time", Modifier.weight(1f))
    }
}

@Composable
private fun NoPledge(s: UiState, a: Actions) {
    FundingCard(s, a)
    GlassCard(glow = GlassBorder) {
        Overline("No active pledge", Mint)
        Heading("Put something on the line", 20)
        Body("Pick a habit from the catalog: steps, the 6 AM Club or a screen detox. Or let the Judge Lab walk you through a six-minute demo pledge with real transactions.")
        GradientButton("Browse habits") { a.goTo(Tab.Explore) }
        GhostButton("Open the Judge Lab", color = Cyan) { a.openJudgeLab() }
    }
}

@Composable
private fun NotStarted(s: UiState, c: Commitment) {
    GlassCard(glow = HeroBorder) {
        Overline(kindLabel(c.kind), Gold)
        Heading("Your pledge starts soon", 20)
        Body("Day 1 opens on Solana's clock. ${formatSkr(c.totalAmount)} test SKR is already in escrow.")
        Mono("starts in " + clock(c.start - s.chainNow), Gold, 20)
    }
}

/** "Daily window deadline": what burns if today is missed, and when today's window closes. */
@Composable
private fun DeadlineBanner(s: UiState, c: Commitment) {
    val day = c.dayAt(s.chainNow)
    if (c.clockedIn(day)) return
    val opens = c.openFrom(day)
    val closes = c.openUntil(day)
    val waiting = s.chainNow < opens
    val missedWindow = s.chainNow >= closes
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFF1A0B0B))
            .border(1.dp, Burn.copy(alpha = 0.35f), RoundedCornerShape(18.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlatIcon(if (waiting) Glyph.Hourglass else Glyph.Flame, Burn, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        missedWindow -> "Today's window has closed"
                        waiting -> "Today's window opens soon"
                        else -> "Daily window deadline"
                    },
                    color = Burn, fontWeight = FontWeight.SemiBold,
                )
            }
            Text("If missed: ${formatSkr(c.dailyStake)} test SKR burned", color = TextLo, fontSize = 13.sp)
        }
        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Burn.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 6.dp)) {
            Mono(
                when {
                    missedWindow -> "next: " + clock(c.dayEnd(day) - s.chainNow)
                    waiting -> "opens " + clock(opens - s.chainNow)
                    else -> clock(closes - s.chainNow)
                },
                Burn, 14,
            )
        }
    }
}

@Composable
private fun HabitHero(s: UiState, c: Commitment, a: Actions) {
    val context = LocalContext.current
    val day = c.dayAt(s.chainNow)
    val done = c.clockedIn(day)
    val open = s.chainNow >= c.openFrom(day) && s.chainNow < c.openUntil(day)
    val stepPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { a.vm.setStepPermission(it) }
    val (progress, big, small) = when (c.kind) {
        Kind.WAKE -> Triple(if (done) 1f else 0f, if (done) "UP" else "—", if (done) "clocked in on time" else "window ${clock(c.windowSec.toLong())}")
        Kind.SCREEN -> Triple(
            (s.todaySteps.toFloat() / c.targetSteps).coerceIn(0f, 1f),
            "${s.todaySteps}m", "of ${c.targetSteps} min limit",
        )
        else -> Triple((s.todaySteps.toFloat() / c.targetSteps).coerceIn(0f, 1f), s.todaySteps.fmt(), "of ${c.targetSteps.fmt()} steps")
    }
    val goalMet = when (c.kind) {
        Kind.WAKE -> open
        Kind.SCREEN -> s.todaySteps <= c.targetSteps && s.screenAccess
        else -> s.todaySteps >= c.targetSteps
    }

    if (c.kind == Kind.STEPS && s.hasStepSensor && !s.stepPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        GlassCard(glow = Brush.horizontalGradient(listOf(Amber, Amber.copy(alpha = 0.4f)))) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlatIcon(Glyph.Walk, Amber, Modifier.size(22.dp)); Spacer(Modifier.width(10.dp))
                Text("Step counting is off", color = Amber, fontWeight = FontWeight.Bold)
            }
            Body("Android only shares your steps with apps you allow. Without it the counter stays at zero.")
            GradientButton("Allow step counting") { stepPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION) }
        }
    }
    GlassCard(glow = HeroBorder, padding = 20.dp) {
        Text("HABIT COMMITMENT", color = TextHi, fontSize = 15.sp, letterSpacing = 2.sp, fontFamily = Grotesk,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        BoxWithConstraints {
            val wide = maxWidth > 560.dp
            val ring = @Composable {
                Box(
                    Modifier.size(210.dp).semantics(mergeDescendants = true) {
                        contentDescription = when (c.kind) {
                            Kind.WAKE -> if (done) "Today kept: up on time" else "Wake-up window today"
                            Kind.SCREEN -> "${s.todaySteps} of ${c.targetSteps} minutes of screen time today"
                            else -> "${s.todaySteps} of ${c.targetSteps} steps today"
                        }
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Ring(progress, Modifier.size(210.dp), done)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (c.kind == Kind.STEPS) "${(progress * 100).toInt()}%" else big, color = TextHi, fontSize = 42.sp, fontFamily = Grotesk, fontWeight = FontWeight.Bold)
                        Text(if (done) "COMPLETE" else "TODAY", color = TextLo, fontSize = 12.sp, letterSpacing = 2.sp)
                        Mono(if (c.kind == Kind.STEPS) "$big $small" else small, Mint, 13)
                    }
                }
            }
            val info = @Composable {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Overline("Daily habit · Day ${day + 1} of ${c.totalDays}")
                            Text(habitTitle(c), color = TextHi, fontSize = 18.sp, fontFamily = Grotesk)
                        }
                        if (c.isDemo) Tag("DEMO · 2-MIN DAYS", Amber) else Tag("${c.completedDays}/${c.totalDays} KEPT", Gold)
                    }
                    Row {
                        Column(Modifier.weight(1f)) {
                            Overline("Staked escrow")
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(7.dp).clip(CircleShape).background(Cyan)); Spacer(Modifier.width(6.dp))
                                Text("${formatSkr(c.totalAmount)} SKR", color = TextHi, fontSize = 18.sp, fontFamily = Grotesk)
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Overline("Day ends in")
                            Mono(clock(c.dayEnd(day) - s.chainNow), Mint, 18)
                        }
                    }
                    StatusBox(s, c, done, goalMet, open)
                }
            }
            if (wide) Row(verticalAlignment = Alignment.CenterVertically) { ring(); Spacer(Modifier.width(24.dp)); Box(Modifier.weight(1f)) { info() } }
            else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) { ring(); info() }
        }
        when {
            done -> GradientButton("Day ${day + 1} recorded ✓", enabled = false) {}
            goalMet && open -> GradientButton("Clock in day ${day + 1}", busy = "clockin" in s.pending) { a.vm.clockIn(a.sender) }
            else -> GradientButton(blockedLabel(s, c, day, open), enabled = false) {}
        }
        if (c.isDemo && c.kind == Kind.STEPS && !done) GhostButton("+1,000 simulated steps (demo only)", color = Amber) { a.vm.addDemoSteps(1_000) }
        if (c.kind == Kind.STEPS && !c.isDemo && !s.hasStepSensor) Body("This device has no step counter, so it cannot count steps for a real pledge.", Amber)

        if (c.kind == Kind.SCREEN && !s.screenAccess) {
            Body("Screen time needs Android's \"Usage access\" for PledgeX.", Amber)
            GhostButton("Open Usage access settings") { context.startActivity(a.vm.screenAccessIntent()) }
        }
    }
}

private fun habitTitle(c: Commitment) = when (c.kind) {
    Kind.WAKE -> if (c.isDemo) "6 AM Club (demo)" else "The 6:00 AM Club"
    Kind.SCREEN -> "Screen detox · under ${c.targetSteps} min"
    else -> "${c.targetSteps.fmt()} steps daily"
}

private fun blockedLabel(s: UiState, c: Commitment, day: Int, open: Boolean): String = when {
    s.chainNow < c.openFrom(day) -> "Window opens in ${clock(c.openFrom(day) - s.chainNow)}"
    !open -> "Today's window closed"
    c.kind == Kind.SCREEN && !s.screenAccess -> "Allow usage access first"
    c.kind == Kind.SCREEN -> "Over today's limit"
    else -> "${(c.targetSteps - s.todaySteps).fmt()} steps to go"
}

@Composable
private fun StatusBox(s: UiState, c: Commitment, done: Boolean, goalMet: Boolean, open: Boolean) {
    val (line, sub, color) = when {
        done -> Triple("✓ Day recorded on Solana", "Signed by this phone's session key: it can clock in and nothing else", Mint)
        goalMet && open -> Triple("✓ Goal met · ready to clock in", if (c.kind == Kind.WAKE) "Solana's clock checks you're inside the window" else "One tap, no wallet screen", Mint)
        c.kind == Kind.WAKE -> Triple("Window: first ${clock(c.windowSec.toLong())} of the day", "Only Solana's clock decides, so it can't be faked", Gold)
        c.kind == Kind.SCREEN -> Triple("Clock in near the end of the day", "Last ${clock(c.windowSec.toLong())} of each day, if you stayed under the limit", Purple)
        else -> Triple("Walk to reach today's goal", if (s.demoStepsToday > 0) "Includes ${s.demoStepsToday.fmt()} simulated steps" else "Counted by the phone's hardware step counter", TextLo)
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Obsidian).border(1.dp, Hairline, RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(line, color = color, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(sub, color = TextLo, fontSize = 12.sp)
    }
}

@Composable
private fun StakeCard(s: UiState, c: Commitment, a: Actions) {
    val today = c.dayAt(s.chainNow)
    val open = openUrl()
    GlassCard {
        Overline("Your stake")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            for (d in 0 until c.totalDays) {
                val color = when {
                    c.clockedIn(d) -> Mint
                    d < today -> Burn
                    d == today -> Purple
                    else -> CardHigh
                }
                Box(Modifier.weight(1f).height(10.dp).clip(CircleShape).background(color))
            }
        }
        val missed = (0 until today).count { !c.clockedIn(it) }
        KeyValue("Days kept", "${c.completedDays} of ${c.totalDays}", Mint)
        KeyValue("Missed so far", if (missed == 0) "none" else "$missed · ${formatSkr(c.dailyStake * missed)} will burn", if (missed == 0) TextHi else Burn)
        KeyValue("Back to you if you keep every day left", "${formatSkr(c.dailyStake * (c.totalDays - missed))} SKR")
        // The phone key pays the fee of every one-tap clock-in; say how long it lasts.
        s.sessionActionsLeft?.let { left ->
            KeyValue("One-tap clock-ins left on this phone", if (left > 999) "999+" else "$left", if (left < 20) Amber else TextHi)
            if (left < 20) GhostButton("Refill the phone key (one approval)", busy = "refill" in s.pending, color = Amber) { a.vm.refillSessionKey(a.sender) }
        }
        TextButton(onClick = { open(explorerAddress(c.address)) }) { Text("View the escrow account on Solana Explorer ↗", color = Cyan, fontSize = 13.sp) }
    }
}

@Composable
private fun SettleCard(s: UiState, c: Commitment, a: Actions) {
    GlassCard(glow = GlassBorder) {
        Overline("Pledge finished", Mint)
        Heading("You kept ${c.completedDays} of ${c.totalDays} days", 20)
        KeyValue("Back to your wallet", "${formatSkr(c.refund)} SKR", Mint)
        KeyValue("Burned forever", "${formatSkr(c.burn)} SKR", Burn)
        Body("Settling closes the escrow: the program sends the kept days back and burns the missed ones. Nobody receives the burned part, not even PledgeX.")
        GradientButton("Settle & claim", busy = "settle" in s.pending) { a.vm.settle(a.sender) }
    }
}

@Composable
private fun ResultCard(s: UiState, a: Actions) {
    val r = s.lastResult ?: return
    val context = LocalContext.current
    val open = openUrl()
    GlassCard(glow = HeroBorder) {
        Overline(if (r.completed == r.total) "Every day kept" else "Pledge settled", Mint)
        Row {
            StatTile("Returned", formatSkr(r.refund), Mint, "test SKR", Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            StatTile("Burned", formatSkr(r.burn), Burn, "test SKR", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("Share", Modifier.weight(1f)) { ShareCard.share(context, r) }
            GhostButton("Explorer ↗", Modifier.weight(1f), color = Cyan) { open(explorerTx(r.signature)) }
            GhostButton("New pledge", Modifier.weight(1f), color = Mint) { a.vm.dismissResult(); a.goTo(Tab.Explore) }
        }
    }
}

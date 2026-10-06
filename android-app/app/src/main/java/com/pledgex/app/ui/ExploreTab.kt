package com.pledgex.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pledgex.app.HabitSpec
import com.pledgex.app.PRESETS
import com.pledgex.app.UiState
import com.pledgex.app.chain.Kind
import com.pledgex.app.chain.PledgeProgram
import com.pledgex.app.fmt

fun kindGlyph(kind: Int) = when (kind) { Kind.WAKE -> Glyph.Alarm; Kind.SCREEN -> Glyph.PhoneOff; else -> Glyph.Walk }
fun kindColor(kind: Int) = when (kind) { Kind.WAKE -> Amber; Kind.SCREEN -> Purple; else -> Mint }

@Composable
fun ExploreTab(s: UiState, a: Actions) {
    var editing by remember { mutableStateOf<HabitSpec?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Heading("Commitment Catalog")
            Body("Habits the program can hold you to. Pick one to configure.")
        }
        Pill("+ Custom", color = TextHi) { editing = HabitSpec("Custom pledge", Kind.STEPS, 8_000, 7, 1_000, demo = false) }
    }
    FundingCard(s, a)

    GlassCard(glow = androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Purple.copy(alpha = 0.6f), Purple.copy(alpha = 0.2f)))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Overline("The philosophy of PledgeX", Purple); Spacer(Modifier.weight(1f)); Tag("Mountain goat", Purple)
        }
        Body("The mountain goat climbs sheer rock with steady footing and stubborn endurance. PledgeX asks the same of a habit: a small daily step, with something real on the line.")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Tag("Zero excuses", TextLo); Tag("Non-custodial escrow", TextLo); Tag("Burn, not profit", TextLo) }
    }

    GlassCard(padding = 0.dp) {
        PRESETS.forEachIndexed { i, p ->
            if (i > 0) HorizontalDivider(color = Hairline)
            val active = s.commitment?.let { it.kind == p.spec.kind && (it.kind != Kind.STEPS || it.targetSteps == p.spec.target) } == true
            Row(
                Modifier.fillMaxWidth().clickable(enabled = s.commitment == null) { editing = p.spec }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconTile(kindGlyph(p.spec.kind), kindColor(p.spec.kind))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.spec.title, color = TextHi, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("${p.source} · ${p.spec.days} days · stake ${p.spec.stake.fmt()} SKR", color = TextLo, fontSize = 12.sp)
                }
                when {
                    active -> Pill("ACTIVE", color = Mint)
                    s.commitment != null -> Text("one at a time", color = TextDim, fontSize = 11.sp)
                    else -> Pill("ACTIVATE", color = Cyan) { editing = p.spec }
                }
            }
        }
    }
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) { FlatIcon(Glyph.Info, Cyan, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Body("Activating locks the stake on chain. You see every term before your wallet signs.", TextLo, 13) }
    }

    GlassCard {
        Overline("How each habit is checked", Cyan)
        Pipeline("Steps", "The phone's hardware step counter. The phone reports the number; the program trusts it.", Mint)
        Pipeline("6 AM Club", "Solana's own clock. The program only accepts a check-in in the first hour of the day, so this one can't be faked.", Amber)
        Pipeline("Screen detox", "Android usage statistics. The phone reports minutes; check-in opens in the last two hours of the day.", Purple)
    }
    GlassCard {
        Overline("Escrow guarantees", Mint)
        Pipeline("Non-custodial vault", "The stake sits in an account of the program. No team key can move it.", Mint)
        Pipeline("Burn, not profit", "Missed days are destroyed by the token program's burn. Nobody receives them.", Burn)
        Pipeline("Anyone can settle", "After the last day anyone may trigger settle, and the refund only ever goes to you.", Cyan)
        Pipeline("A key that can only check in", "Daily check-ins are signed by a key on this phone, allowed to check in and nothing else.", Purple)
    }

    editing?.let { spec -> Configure(s, spec, onCancel = { editing = null }) { a.create(it); editing = null; a.goTo(Tab.Active) } }
}

@Composable
private fun Pipeline(title: String, text: String, color: Color) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface1).border(1.dp, Hairline, RoundedCornerShape(14.dp)).padding(12.dp),
    ) {
        Text(title.uppercase(), color = color, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp)
        Text(text, color = TextLo, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

/** Every term of a pledge, editable, before anything is signed. */
@Composable
private fun Configure(s: UiState, initial: HabitSpec, onCancel: () -> Unit, onLock: (HabitSpec) -> Unit) {
    var kind by remember { mutableIntStateOf(initial.kind) }
    var target by remember { mutableIntStateOf(initial.target) }
    var days by remember { mutableIntStateOf(initial.days) }
    var stake by remember { mutableLongStateOf(initial.stake) }
    var demo by remember { mutableStateOf(false) }
    val custom = initial.title == "Custom pledge"
    val title = if (custom) when (kind) { Kind.WAKE -> "The 6:00 AM Club"; Kind.SCREEN -> "Screen detox"; else -> "Steps" } else initial.title
    val spec = HabitSpec(title, kind, if (kind == Kind.WAKE) 0 else target, days, stake, demo)
    val enough = (s.skr ?: 0) >= stake * PledgeProgram.UNIT && !s.lowSol
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Surface1,
        title = { Text(title, fontFamily = Grotesk) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (custom) Choice("Habit", listOf(Kind.STEPS, Kind.WAKE, Kind.SCREEN), kind, { com.pledgex.app.kindLabel(it) }) {
                    kind = it; target = if (it == Kind.SCREEN) 120 else 8_000
                }
                when (kind) {
                    Kind.STEPS -> Choice("Daily steps", listOf(3_000, 5_000, 8_000, 10_000), target, { it.fmt() }) { target = it }
                    Kind.SCREEN -> Choice("Screen time limit", listOf(60, 120, 180), target, { "${it / 60} h" }) { target = it }
                }
                Choice("Days", listOf(3, 7, 14), days, { "$it" }) { days = it }
                Choice("Stake (test SKR)", listOf(500L, 1_000L, 2_500L), stake, { it.fmt() }) { stake = it }
                Choice("Day length", listOf(false, true), demo, { if (it) "Demo · 2 min" else "Real · 24 h" }) { demo = it }
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Obsidian).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(spec.rule, color = TextHi, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("Each missed day burns %,.2f test SKR; every kept day comes back when you settle.".format(stake.toDouble() / days), color = TextLo, fontSize = 12.sp)
                    if (demo) Text("Demo: the whole pledge ends in ${days * 2} minutes.", color = Amber, fontSize = 12.sp)
                    else if (kind != Kind.STEPS) Text(if (kind == Kind.WAKE) "Day 1 starts at the next 05:00." else "Day 1 starts at the next midnight.", color = Gold, fontSize = 12.sp)
                }
                if (!enough) Text("Get devnet SOL and test SKR first (Explore or Active tab).", color = Burn, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onLock(spec) }, enabled = enough) { Text("Lock ${stake.fmt()} SKR", color = if (enough) Mint else TextDim) } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel", color = TextLo) } },
    )
}

@Composable
fun <T> Choice(label: String, options: List<T>, selected: T, text: (T) -> String, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = TextLo, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { option ->
                val on = option == selected
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                        .background(if (on) Purple.copy(alpha = 0.22f) else CardHigh)
                        .border(1.dp, if (on) Purple else Hairline, RoundedCornerShape(12.dp))
                        .clickable { onSelect(option) }.padding(vertical = 10.dp, horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(text(option), color = if (on) TextHi else TextLo, fontSize = 13.sp, textAlign = TextAlign.Center) }
            }
        }
    }
}

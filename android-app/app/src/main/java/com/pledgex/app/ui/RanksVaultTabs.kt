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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pledgex.app.UiState
import com.pledgex.app.chain.Kind
import com.pledgex.app.chain.Profile
import com.pledgex.app.formatSkr

// ---- Ranks ---------------------------------------------------------------------------------

@Composable
fun RanksTab(s: UiState, a: Actions) {
    LaunchedEffect(Unit) { a.vm.loadRanks() }
    val r = s.ranks
    val open = openUrl()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Heading("PledgeX Ranks")
            Body("Every number here is read from the program on Solana devnet.")
        }
        if ("ranks" in s.pending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Mint)
        else Pill("↻ Refresh", color = TextHi) { a.vm.loadRanks(force = true) }
    }
    if (r == null) {
        GlassCard { Body(if ("ranks" in s.pending) "Reading every profile and pledge from the program…" else "Couldn't read the ranks yet. Pull Refresh.") }
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile("Active staked", formatSkr(r.activeStaked), Mint, "${r.activePledges} open pledges", Modifier.weight(1f))
        StatTile("Burned forever", formatSkr(r.totalBurned), Burn, "missed days", Modifier.weight(1f))
        StatTile("Success rate", r.successRate?.let { "%.1f%%".format(it * 100) } ?: "—", Cyan, "days kept", Modifier.weight(1f))
    }
    Overline("Leaderboard · by real days kept")
    if (r.profiles.isEmpty()) GlassCard { Body("Nobody has a record yet. Settle a pledge to be the first.") }
    else GlassCard(padding = 0.dp) {
        r.profiles.take(20).forEachIndexed { i, p ->
            if (i > 0) HorizontalDivider(color = Hairline)
            LeaderRow(i + 1, p, p.authority == s.wallet) { open(explorerAddress(p.address)) }
        }
    }
    val mine = r.profiles.indexOfFirst { it.authority == s.wallet }
    GlassCard(glow = Brush.horizontalGradient(listOf(Mint.copy(alpha = 0.6f), Mint.copy(alpha = 0.15f)))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Overline("Your standing", Mint); Spacer(Modifier.weight(1f))
            Text(if (mine >= 0) "Rank #${mine + 1} of ${r.profiles.size}" else "Unranked", color = Mint, fontWeight = FontWeight.SemiBold)
        }
        Text(short(s.wallet!!), color = TextHi, fontFamily = Grotesk, fontSize = 16.sp)
        val p = s.profile
        Body(if (p == null) "Lock and settle a pledge to get on the board." else "${p.kept} days kept · ${p.perfect} perfect pledges · best streak ${p.bestStreak} days")
    }
    Overline("Live on-chain activity", Mint)
    GlassCard(padding = 0.dp) {
        if (r.activity.isEmpty()) Box(Modifier.padding(16.dp)) { Body("No recent transactions.") }
        r.activity.forEachIndexed { i, act ->
            if (i > 0) HorizontalDivider(color = Hairline)
            Row(Modifier.fillMaxWidth().clickable { open(explorerTx(act.signature)) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${if (act.wallet == s.wallet || act.wallet == s.sessionAddress) "You" else short(act.wallet)}  ${act.action}",
                        color = if (act.action.startsWith("settled")) Gold else TextHi, fontSize = 14.sp,
                    )
                    Text(ago(act.time, s.chainNow) + " · " + act.signature.take(10) + "…", color = TextDim, fontSize = 11.sp)
                }
                Text("↗", color = Cyan)
            }
        }
    }
}

@Composable
private fun LeaderRow(rank: Int, p: Profile, me: Boolean, onClick: () -> Unit) {
    val medal = when (rank) { 1 -> Gold; 2 -> Color(0xFFB0B0B8); 3 -> Amber; else -> TextDim }
    Row(
        Modifier.fillMaxWidth().background(if (me) Mint.copy(alpha = 0.06f) else Color.Transparent).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(medal.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
            Text("$rank", color = medal, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(short(p.authority) + if (me) "  (you)" else "", color = TextHi, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("${p.realDaysKept} real days · ${p.kept - p.realDaysKept} demo · ${p.perfect} perfect · ${formatSkr(p.burned)} burned", color = TextLo, fontSize = 12.sp)
        }
        Pill("${p.bestStreak}d STREAK", color = if (p.bestStreak >= 7) Gold else Cyan)
    }
}

// ---- Vault ---------------------------------------------------------------------------------

private data class Badge(val glyph: Glyph, val title: String, val how: String, val tier: String, val color: Color, val earned: (Profile) -> Boolean)

private val BADGES = listOf(
    Badge(Glyph.Lock, "First Pledge", "Lock your first pledge", "Common", TextLo) { it.started >= 1 },
    Badge(Glyph.Walk, "10K Pioneer", "Keep every day of a steps pledge", "Rare", Mint) { it.perfectIn(Kind.STEPS) },
    Badge(Glyph.Alarm, "6 AM Club Hero", "Keep every day of a 6 AM Club pledge", "Epic", Amber) { it.perfectIn(Kind.WAKE) },
    Badge(Glyph.PhoneOff, "Digital Detox", "Keep every day of a screen detox", "Epic", Purple) { it.perfectIn(Kind.SCREEN) },
    Badge(Glyph.Flame, "Week Streak", "Keep 7 days in a row", "Legendary", Gold) { it.bestStreak >= 7 },
    Badge(Glyph.Shield, "Iron Will", "Finish 3 pledges without missing a day", "Mythic", Cyan) { it.perfect >= 3 },
)

@Composable
fun VaultTab(s: UiState, a: Actions) {
    val open = openUrl()
    val c = s.commitment
    val p = s.profile
    GlassCard(glow = GlassBorder) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Pill("NON-CUSTODIAL ESCROW · DEVNET", dot = Mint, color = Mint) }
        Text(short(s.wallet!!), color = TextHi, fontSize = 20.sp, fontFamily = Grotesk, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Text(
            if (c != null) "${formatSkr(c.totalAmount)} SKR locked in escrow" else "Nothing locked right now",
            color = Mint, fontSize = 15.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
        )
        Text(
            "Wallet: %s SKR · %.3f SOL".format(formatSkr(s.skr ?: 0), (s.lamports ?: 0) / 1e9),
            color = TextLo, fontSize = 13.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (c != null && s.chainNow >= c.end) GradientButton("Settle & claim", Modifier.weight(1f), busy = "settle" in s.pending) { a.vm.settle(a.sender) }
            else if (c != null) GhostButton("Ends in ${clock(c.end - s.chainNow)}", Modifier.weight(1f), enabled = false) {}
            GhostButton("Explorer ↗", Modifier.weight(1f), color = Cyan) { open(explorerAddress(c?.address ?: s.wallet)) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile("Total returned", formatSkr(p?.returned ?: 0), Mint, "${p?.kept ?: 0} days kept", Modifier.weight(1f))
        StatTile("Total burned", formatSkr(p?.burned ?: 0), Burn, "${p?.missed ?: 0} days missed", Modifier.weight(1f))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Overline("Soulbound proof-of-discipline badges"); Spacer(Modifier.weight(1f))
        Text("${BADGES.count { b -> p != null && b.earned(p) }}/${BADGES.size}", color = Mint, fontSize = 12.sp)
    }
    BADGES.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { b -> BadgeCard(b, p != null && b.earned(p), Modifier.weight(1f)) }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
    GlassCard {
        Body("Badges are read from your Profile: an account of the PledgeX program that settle updates. It can't be sold or transferred, which is what makes it soulbound. Only real pledges (days of an hour or more) earn badges and rank; demo pledges move the money but can't be farmed for them.", TextLo, 13)
        if (p != null) TextButton(onClick = { open(explorerAddress(p.address)) }) { Text("View your Profile account ↗", color = Cyan, fontSize = 13.sp) }
    }
    Overline("Immutable audit trail")
    if (s.history.isEmpty()) GlassCard { Body("Your transactions appear here, each with a Solana Explorer link.") }
    else GlassCard(padding = 0.dp) {
        s.history.take(15).forEachIndexed { i, tx ->
            if (i > 0) HorizontalDivider(color = Hairline)
            Row(Modifier.fillMaxWidth().clickable { open(tx.explorerUrl) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tx.kind, color = TextHi, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(tx.note, color = TextLo, fontSize = 12.sp)
                }
                Text("Confirmed ✓", color = Mint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Mint.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun BadgeCard(b: Badge, earned: Boolean, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(Card)
            .border(1.dp, if (earned) b.color.copy(alpha = 0.6f) else Hairline, RoundedCornerShape(20.dp)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.alpha(if (earned) 1f else 0.35f)) { IconTile(b.glyph, if (earned) b.color else TextDim, 50.dp) }
        Text(b.title, color = if (earned) TextHi else TextLo, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(b.how, color = TextDim, fontSize = 11.sp, textAlign = TextAlign.Center)
        Text(if (earned) "${b.tier} · earned" else "Locked", color = if (earned) b.color else TextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

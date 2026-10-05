package com.pledgex.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pledgex.app.PledgeViewModel
import com.pledgex.app.R
import com.pledgex.app.SettleResult
import com.pledgex.app.TxRecord
import com.pledgex.app.UiState
import com.pledgex.app.WalletKind
import com.pledgex.app.chain.Commitment
import com.pledgex.app.chain.PledgeProgram
import com.pledgex.app.fmt
import com.pledgex.app.formatSkr
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import java.text.DateFormat
import java.util.Date

@Composable
fun PledgeApp(vm: PledgeViewModel, sender: ActivityResultSender) {
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(s.message) {
        s.message?.let { snackbar.showSnackbar(it); vm.messageShown() }
    }
    var showHelp by rememberSaveable { mutableStateOf(false) }

    Scaffold(containerColor = Ink, snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (s.wallet == null) {
                    Welcome(s, onConnect = { vm.connect(sender) }, onDemo = { vm.useDemoWallet() }, onHelp = { showHelp = true })
                } else {
                    WalletBar(s, onHelp = { showHelp = true }, onDisconnect = vm::disconnect)
                    s.error?.let { ErrorCard(it, vm::dismissError) }
                    s.lastResult?.let { ResultCard(it, vm::dismissResult) }
                    Funding(s, onSol = { vm.airdrop() }, onSkr = { vm.getTestSkr(sender) })
                    val c = s.commitment
                    when {
                        !s.loaded -> Loading()
                        c == null -> CreatePledge(s) { target, days, demo, stake -> vm.createPledge(sender, target, days, demo, stake) }
                        s.chainNow >= c.end -> SettleCard(s, c) { vm.settle(sender) }
                        else -> TodayCard(s, c, onClockIn = { vm.clockIn(sender) }, onDemoSteps = { vm.addDemoSteps(1_000) },
                            onPermission = vm::setStepPermission)
                    }
                    History(s.history)
                    Footnote()
                }
            }
        }
    }
    if (showHelp) HowItWorks { showHelp = false }
}

// ---- building blocks -------------------------------------------------------------------

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).border(1.dp, Line, RoundedCornerShape(20.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun Title(text: String) = Text(text, color = TextHi, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)

@Composable
private fun Body(text: String, color: Color = TextLo) = Text(text, color = color, fontSize = 14.sp, lineHeight = 20.sp)

@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, busy: Boolean = false, color: Color = Purple, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled && !busy, modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = if (color == Teal) Ink else Color.White,
            disabledContainerColor = CardHigh, disabledContentColor = TextLo),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = TextHi)
        else Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

@Composable
private fun SecondaryButton(text: String, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled && !busy, modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(14.dp)) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else Text(text, color = TextHi)
    }
}

@Composable
private fun <T> Choice(label: String, options: List<T>, selected: T, text: (T) -> String, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = TextLo, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                val on = option == selected
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                        .background(if (on) Purple.copy(alpha = 0.22f) else CardHigh)
                        .border(1.dp, if (on) Purple else Line, RoundedCornerShape(12.dp))
                        .clickable { onSelect(option) }.padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(text(option), color = if (on) TextHi else TextLo, fontSize = 13.sp, textAlign = TextAlign.Center) }
            }
        }
    }
}

private fun short(address: String) = "${address.take(4)}…${address.takeLast(4)}"

private fun clock(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun openUrl(): (String) -> Unit {
    val context = LocalContext.current
    return { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

// ---- screens ---------------------------------------------------------------------------

@Composable
private fun Welcome(s: UiState, onConnect: () -> Unit, onDemo: () -> Unit, onHelp: () -> Unit) {
    Spacer(Modifier.height(24.dp))
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Image(painterResource(R.drawable.app_logo), contentDescription = null, modifier = Modifier.size(132.dp))
        Text("PledgeX", color = TextHi, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text("Stake on your daily steps.\nWalk, and it comes back. Skip a day, and that day's stake burns.",
            color = TextLo, fontSize = 15.sp, textAlign = TextAlign.Center, lineHeight = 22.sp)
    }
    Spacer(Modifier.height(12.dp))
    s.error?.let { ErrorCard(it) {} }
    Panel {
        Title("Connect a wallet")
        Body("Phantom or Solflare on this phone. PledgeX runs on Solana devnet with test tokens — in Phantom turn on Settings → Developer Settings → Testnet Mode (Solana Devnet).")
        PrimaryButton("Connect wallet", busy = "connect" in s.pending, onClick = onConnect)
    }
    Panel {
        Title("No wallet app?")
        Body("Use a demo wallet kept on this phone. It gets devnet SOL and test SKR in a tap, and every transaction is real and visible on Solana Explorer.")
        SecondaryButton("Use a demo wallet", busy = "airdrop" in s.pending, onClick = onDemo)
    }
    TextButton(onClick = onHelp, modifier = Modifier.fillMaxWidth()) { Text("How PledgeX works", color = Teal) }
}

@Composable
private fun WalletBar(s: UiState, onHelp: () -> Unit, onDisconnect: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.app_logo), contentDescription = null, modifier = Modifier.size(36.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).clickable { clipboard.setText(AnnotatedString(s.wallet!!)) }) {
            Text(
                (if (s.walletKind == WalletKind.Demo) "Demo wallet " else "Wallet ") + short(s.wallet!!),
                color = TextHi, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
            )
            val sol = s.lamports?.let { "%.3f SOL".format(it / 1e9) } ?: "… SOL"
            val skr = s.skr?.let { "${formatSkr(it)} test SKR" } ?: "… test SKR"
            Text("$sol · $skr · devnet", color = TextLo, fontSize = 12.sp)
        }
        Box {
            TextButton(onClick = { menu = true }) { Text("•••", color = TextLo) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Copy address") }, onClick = { clipboard.setText(AnnotatedString(s.wallet!!)); menu = false })
                DropdownMenuItem(text = { Text("How it works") }, onClick = { onHelp(); menu = false })
                DropdownMenuItem(text = { Text("Disconnect") }, onClick = { onDisconnect(); menu = false })
            }
        }
    }
}

@Composable
private fun ErrorCard(text: String, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Danger.copy(alpha = 0.14f))
            .border(1.dp, Danger.copy(alpha = 0.5f), RoundedCornerShape(14.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = TextHi, fontSize = 14.sp, modifier = Modifier.weight(1f), lineHeight = 20.sp)
        TextButton(onClick = onDismiss) { Text("OK", color = Danger) }
    }
}

@Composable
private fun Loading() = Panel {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Body("Reading your pledge from Solana devnet…")
    }
}

@Composable
private fun Funding(s: UiState, onSol: () -> Unit, onSkr: () -> Unit) {
    val needSol = s.lowSol
    val needSkr = s.commitment == null && s.skr != null && s.skr < PledgeProgram.FAUCET_CAP
    if (!needSol && !needSkr) return
    val open = openUrl()
    Panel {
        Title("Get test funds")
        if (needSol) {
            Body("Fees and account deposits are paid in devnet SOL (deposits come back when you settle). About 0.01 SOL is enough.")
            PrimaryButton("Get devnet SOL", busy = "airdrop" in s.pending, color = Teal, onClick = onSol)
            TextButton(onClick = { open("https://faucet.solana.com") }) { Text("Faucet busy? Use faucet.solana.com", color = TextLo, fontSize = 13.sp) }
        }
        if (needSkr) {
            Body("Stakes are in test SKR, minted by the PledgeX program itself: 10,000 per tap while you hold under 5,000.")
            PrimaryButton("Get 10,000 test SKR", enabled = !needSol, busy = "faucet" in s.pending, onClick = onSkr)
        }
    }
}

@Composable
private fun CreatePledge(s: UiState, onCreate: (Int, Int, Boolean, Long) -> Unit) {
    var target by rememberSaveable { mutableIntStateOf(8_000) }
    var days by rememberSaveable { mutableIntStateOf(7) }
    var stake by rememberSaveable { mutableLongStateOf(1_000L) }
    var demo by rememberSaveable { mutableStateOf(true) }
    var confirm by remember { mutableStateOf(false) }
    Panel {
        Title("New pledge")
        Choice("Daily step goal", listOf(3_000, 5_000, 8_000, 10_000), target, { it.fmt() }) { target = it }
        Choice("Days", listOf(3, 7, 14), days, { "$it days" }) { days = it }
        Choice("Stake (test SKR)", listOf(500L, 1_000L, 2_500L), stake, { it.fmt() }) { stake = it }
        Choice("Day length", listOf(false, true), demo, { if (it) "Demo: 2 minutes" else "Real: 24 hours" }) { demo = it }
        val perDay = stake.toDouble() / days
        Body(
            (if (demo) "Demo mode: each day lasts 2 minutes on chain, so the whole pledge ends in ${days * 2} minutes. " else "") +
                "Each missed day burns %,.2f test SKR. Every day you clock in comes back when you settle.".format(perDay),
        )
        val enough = (s.skr ?: 0) >= stake * PledgeProgram.UNIT && !s.lowSol
        PrimaryButton(
            if (enough) "Lock ${stake.fmt()} test SKR" else "Get test funds first",
            enabled = enough, busy = "create" in s.pending,
        ) { confirm = true }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Lock ${stake.fmt()} test SKR?") },
            text = {
                Text(
                    "${target.fmt()} steps a day for $days ${if (demo) "demo days (2 min each)" else "days"}. " +
                        "The stake moves into an escrow account of the PledgeX program. Nobody can take it out early — " +
                        "after the last day it is split: clocked-in days back to you, missed days burned.",
                )
            },
            confirmButton = { TextButton(onClick = { confirm = false; onCreate(target, days, demo, stake) }) { Text("Lock stake") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TodayCard(s: UiState, c: Commitment, onClockIn: () -> Unit, onDemoSteps: () -> Unit, onPermission: (Boolean) -> Unit) {
    val day = c.dayAt(s.chainNow)
    val done = c.clockedIn(day)
    val left = c.dayEnd(day) - s.chainNow
    val progress = (s.todaySteps.toFloat() / c.targetSteps).coerceIn(0f, 1f)
    val reached = s.todaySteps >= c.targetSteps
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onPermission(it) }

    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Title("Day ${day + 1} of ${c.totalDays}")
            Spacer(Modifier.weight(1f))
            if (c.isDemo) Tag("DEMO · 2-min days", Amber)
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
            Ring(progress, if (done) Teal else Purple, Modifier.size(196.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(s.todaySteps.fmt(), color = TextHi, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text("of ${c.targetSteps.fmt()} steps", color = TextLo, fontSize = 13.sp)
                if (s.demoStepsToday > 0) Text("incl. ${s.demoStepsToday.fmt()} simulated", color = Amber, fontSize = 12.sp)
            }
        }
        Text("Today's window closes in ${clock(left)} (Solana time)", color = if (left < 600 && !done && !c.isDemo) Amber else TextLo,
            fontSize = 13.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)

        when {
            done -> PrimaryButton("Day ${day + 1} recorded ✓", enabled = false) {}
            reached -> PrimaryButton("Clock in day ${day + 1}", busy = "clockin" in s.pending, color = Teal, onClick = onClockIn)
            else -> PrimaryButton("${(c.targetSteps - s.todaySteps).fmt()} steps to go", enabled = false) {}
        }
        if (!done && reached) Body("Signed by this phone's PledgeX key — no wallet screen. That key can only clock in; it cannot move your stake.")

        if (c.isDemo && !done) SecondaryButton("Add 1,000 simulated steps (demo only)", onClick = onDemoSteps)
        if (!c.isDemo && !s.hasStepSensor) Body("This device has no step counter, so it cannot count steps for a real pledge. Use a phone, or try a demo pledge.", Amber)
        if (s.hasStepSensor && !s.stepPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            SecondaryButton("Allow step counting") { permission.launch(Manifest.permission.ACTIVITY_RECOGNITION) }
        }
    }
    DaysPanel(s, c)
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 4.dp))
}

@Composable
private fun Ring(progress: Float, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val stroke = 14.dp.toPx()
        val inset = stroke / 2
        val arc = Size(size.width - stroke, size.height - stroke)
        drawArc(Line, -90f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
        drawArc(color, -90f, 360f * progress, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

@Composable
private fun DaysPanel(s: UiState, c: Commitment) {
    val today = c.dayAt(s.chainNow)
    Panel {
        Title("Your stake")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            for (d in 0 until c.totalDays) {
                val color = when {
                    c.clockedIn(d) -> Teal
                    d < today -> Danger
                    d == today -> Purple
                    else -> Line
                }
                Box(Modifier.weight(1f).height(10.dp).clip(CircleShape).background(color))
            }
        }
        val missed = (0 until today).count { !c.clockedIn(it) }
        StakeRow("Staked", "${formatSkr(c.totalAmount)} test SKR")
        StakeRow("Days kept", "${c.completedDays} of ${c.totalDays}")
        StakeRow("Missed so far", if (missed == 0) "none" else "$missed (${formatSkr(c.dailyStake * missed)} will burn)")
        StakeRow("At stake today", "${formatSkr(c.dailyStake)} test SKR")
        val open = openUrl()
        TextButton(onClick = { open("https://explorer.solana.com/address/${c.address}?cluster=devnet") }) {
            Text("View the escrow account on Solana Explorer", color = Teal, fontSize = 13.sp)
        }
    }
}

@Composable
private fun StakeRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = TextLo, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = TextHi, fontSize = 14.sp)
    }
}

@Composable
private fun SettleCard(s: UiState, c: Commitment, onSettle: () -> Unit) {
    Panel {
        Title("Pledge finished")
        Body("You clocked in ${c.completedDays} of ${c.totalDays} days.")
        StakeRow("Back to your wallet", "${formatSkr(c.refund)} test SKR")
        StakeRow("Burned forever", "${formatSkr(c.burn)} test SKR")
        Body("Settling closes the escrow: the program sends the kept days back to you and burns the missed ones. Nobody — not even PledgeX — receives the burned part.")
        PrimaryButton("Settle now", busy = "settle" in s.pending, color = Teal, onClick = onSettle)
    }
}

@Composable
private fun ResultCard(r: SettleResult, onDismiss: () -> Unit) {
    val open = openUrl()
    Panel {
        Title(if (r.completed == r.total) "Every day kept 🎉" else "Pledge settled")
        StakeRow("Returned", "${formatSkr(r.refund)} test SKR")
        StakeRow("Burned", "${formatSkr(r.burn)} test SKR")
        StakeRow("Days kept", "${r.completed} of ${r.total}")
        Row {
            TextButton(onClick = { open("https://explorer.solana.com/tx/${r.signature}?cluster=devnet") }) { Text("See it on Explorer", color = Teal) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("New pledge", color = TextHi) }
        }
    }
}

@Composable
private fun History(items: List<TxRecord>) {
    if (items.isEmpty()) return
    val open = openUrl()
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    Panel {
        Title("On-chain activity")
        items.take(12).forEach { tx ->
            Row(Modifier.fillMaxWidth().clickable { open(tx.explorerUrl) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tx.kind, color = TextHi, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(tx.note, color = TextLo, fontSize = 12.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(fmt.format(Date(tx.time * 1000)), color = TextLo, fontSize = 11.sp)
                    Text(short(tx.signature) + " ↗", color = Teal, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun Footnote() {
    Text(
        "Solana devnet · test SKR has no value · program 68c1…FRcd",
        color = TextLo.copy(alpha = 0.6f), fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun HowItWorks(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Got it") } },
        title = { Text("How PledgeX works") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1. Pick a daily step goal, a number of days and a stake. The stake moves into an escrow account owned by the PledgeX program on Solana.")
                Text("2. Each day, once your phone's step counter reaches the goal, tap Clock in. A key kept on this phone signs it — it is allowed to clock in and nothing else.")
                Text("3. After the last day, settle: the days you clocked in come back to you, the missed days are burned. Nobody receives them.")
                Text("Honest limits: the step count comes from your phone and the program trusts it. This is a devnet demo with test tokens.", color = TextLo)
            }
        },
    )
}

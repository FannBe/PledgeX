package com.pledgex.app.ui

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pledgex.app.HabitSpec
import com.pledgex.app.PledgeViewModel
import com.pledgex.app.R
import com.pledgex.app.UiState
import com.pledgex.app.WalletKind
import com.pledgex.app.chain.Kind
import com.pledgex.app.chain.PledgeProgram
import com.pledgex.app.formatSkr
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender

enum class Tab(val label: String, val glyph: Glyph) { Active("Active", Glyph.Bolt), Explore("Explore", Glyph.Compass), Ranks("Ranks", Glyph.Bars), Vault("Vault", Glyph.Shield) }

/** Everything a screen can ask the app to do. */
class Actions(
    val vm: PledgeViewModel,
    val sender: ActivityResultSender,
    val goTo: (Tab) -> Unit,
    val openJudgeLab: () -> Unit,
    val askNotifications: () -> Unit,
) {
    fun create(spec: HabitSpec) { askNotifications(); vm.createPledge(sender, spec) }
}

@Composable
fun PledgeApp(vm: PledgeViewModel, sender: ActivityResultSender, askNotifications: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(s.message) { s.message?.let { snackbar.showSnackbar(it); vm.messageShown() } }
    var tab by rememberSaveable { mutableStateOf(Tab.Active) }
    var judgeLab by rememberSaveable { mutableStateOf(false) }
    var help by rememberSaveable { mutableStateOf(false) }
    val actions = Actions(vm, sender, { tab = it }, { judgeLab = true }, askNotifications)

    if (!s.onboarded && s.wallet == null) { Onboarding(vm::finishOnboarding); return }
    Box(Modifier.fillMaxSize().cyberBackground()) {
        if (s.wallet == null) {
            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { Column(Modifier.widthIn(max = 640.dp).padding(16.dp)) { Welcome(s, vm, sender) { help = true } } }
        } else {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Column(Modifier.widthIn(max = 900.dp)) {
                        Header(s, onJudgeLab = { judgeLab = true }, onHelp = { help = true }, onDisconnect = vm::disconnect)
                        DevnetStrip(s)
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Column(
                        Modifier.widthIn(max = 900.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        s.error?.let { ErrorBanner(it, vm::dismissError) }
                        when (tab) {
                            Tab.Active -> ActiveTab(s, actions)
                            Tab.Explore -> ExploreTab(s, actions)
                            Tab.Ranks -> RanksTab(s, actions)
                            Tab.Vault -> VaultTab(s, actions)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
                BottomNav(tab) { tab = it; if (it == Tab.Ranks) vm.loadRanks() }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp)) { data ->
        Row(
            Modifier.padding(horizontal = 16.dp).widthIn(max = 640.dp).fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)).background(Brush.horizontalGradient(listOf(Color(0xFF0E2A22), Color(0xFF14102A))))
                .border(1.5.dp, SolanaGradient, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FlatIcon(Glyph.Check, Mint, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(data.visuals.message, color = TextHi, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
    }
    Confetti(s.celebrate)
    if (judgeLab && s.wallet != null) JudgeLab(s, actions) { judgeLab = false }
    if (help) HowItWorks { help = false }
}

@Composable
private fun Header(s: UiState, onJudgeLab: () -> Unit, onHelp: () -> Unit, onDisconnect: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.app_logo), null, Modifier.size(40.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = TextHi)) { append("SEEKER ") }
                    withStyle(SpanStyle(color = Mint, fontWeight = FontWeight.Bold)) { append("PledgeX") }
                },
                fontFamily = Grotesk, fontSize = 18.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(Mint))
                Spacer(Modifier.width(5.dp))
                Text("Escrow on Solana · Devnet", color = TextLo, fontSize = 11.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                Pill(short(s.wallet!!), dot = Mint, color = Mint) { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    Text(
                        (if (s.walletKind == WalletKind.Demo) "Demo wallet on this phone" else "Connected wallet"),
                        color = TextLo, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                    DropdownMenuItem(text = { Text("Copy address") }, onClick = { clipboard.setText(AnnotatedString(s.wallet)); menu = false })
                    DropdownMenuItem(text = { Text("How it works") }, onClick = { onHelp(); menu = false })
                    DropdownMenuItem(text = { Text("Add widget to home screen") }, onClick = { com.pledgex.app.PledgeWidget.requestPin(context); menu = false })
                    DropdownMenuItem(text = { Text("Disconnect", color = Burn) }, onClick = { onDisconnect(); menu = false })
                }
            }
            Pill("JUDGE LAB", color = Cyan, onClick = onJudgeLab)
        }
    }
}

@Composable
private fun DevnetStrip(s: UiState) {
    val open = openUrl()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Obsidian)
            .border(1.dp, Brush.horizontalGradient(listOf(Color(0x6614F195), Color(0x6600F0FF))), RoundedCornerShape(12.dp))
            .clickable { open(explorerAddress(PledgeProgram.PROGRAM_ID.base58())) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(if (s.slot != null) Mint else Amber))
        Spacer(Modifier.width(8.dp))
        Text(if (s.slot != null) "SOLANA DEVNET: ACTIVE" else "CONNECTING…", color = Mint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        Spacer(Modifier.weight(1f))
        s.slot?.let { Text("SLOT %,d".format(it), color = TextLo, fontSize = 10.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
        Spacer(Modifier.width(10.dp))
        Text("EXPLORER ↗", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BottomNav(current: Tab, onSelect: (Tab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xFF0B0F1E), Color(0xFF05060B))))
            .border(1.dp, NeonEdge, androidx.compose.ui.graphics.RectangleShape).navigationBarsPadding().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Tab.entries.forEach { t ->
            val on = t == current
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { onSelect(t) }.padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                FlatIcon(t.glyph, if (on) Mint else TextLo, Modifier.size(24.dp))
                Spacer(Modifier.height(3.dp))
                Text(t.label, color = if (on) Mint else TextLo, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

@Composable
fun ErrorBanner(text: String, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Burn.copy(alpha = 0.12f))
            .border(1.dp, Burn.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = TextHi, fontSize = 14.sp, modifier = Modifier.weight(1f), lineHeight = 20.sp)
        TextButton(onClick = onDismiss) { Text("OK", color = Burn) }
    }
}

@Composable
private fun Welcome(s: UiState, vm: PledgeViewModel, sender: ActivityResultSender, onHelp: () -> Unit) {
    Spacer(Modifier.height(28.dp))
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Image(painterResource(R.drawable.app_logo), null, Modifier.size(150.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = TextHi)) { append("Pledge") }
                withStyle(SpanStyle(brush = SolanaGradient)) { append("X") }
            },
            fontFamily = Grotesk, fontSize = 40.sp, fontWeight = FontWeight.Bold,
        )
        Text(
            "Stake on your daily habits.\nKeep the day and it comes back. Miss it and that day's stake burns.",
            color = TextLo, fontSize = 15.sp, textAlign = TextAlign.Center, lineHeight = 22.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tag("STEPS", Mint); Tag("6 AM CLUB", Gold); Tag("SCREEN DETOX", Purple)
        }
    }
    Spacer(Modifier.height(20.dp))
    s.error?.let { ErrorBanner(it, vm::dismissError); Spacer(Modifier.height(12.dp)) }
    GlassCard(glow = GlassBorder) {
        Heading("Connect a wallet", 18)
        Body("Phantom or Solflare on this phone. PledgeX runs on Solana devnet with test tokens. In Phantom, turn on Settings → Developer Settings → Testnet Mode.")
        GradientButton("Connect wallet", busy = "connect" in s.pending) { vm.connect(sender) }
    }
    Spacer(Modifier.height(12.dp))
    GlassCard {
        Heading("No wallet app?", 18)
        Body("Use a demo wallet kept on this phone. It gets devnet SOL and test SKR in a tap, and every transaction is real and on Solana Explorer.")
        GhostButton("Use a demo wallet", busy = "airdrop" in s.pending || "sponsor" in s.pending) { vm.useDemoWallet() }
    }
    TextButton(onClick = onHelp, modifier = Modifier.fillMaxWidth()) { Text("How PledgeX works", color = Mint) }
}

/** The judges' guided tour: real transactions, short days, every step checked off from chain state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JudgeLab(s: UiState, a: Actions, onClose: () -> Unit) {
    val vm = a.vm
    val c = s.commitment
    val r = s.lastResult
    // Each step is read from the current pledge (or the one just settled), never from
    // older history, and a step only counts once every step before it does.
    val raw = listOf(
        (s.lamports ?: 0) >= 5_000_000 || c != null,
        (s.skr ?: 0) >= 1_000 * PledgeProgram.UNIT || c != null,
        c != null || r != null,
        // A day checked in, or a pledge already over (then only settling is left).
        c?.let { it.completedDays > 0 || s.chainNow >= it.end } ?: (r != null),
        c == null && r != null,
    )
    val done = raw.runningReduce { prev, cur -> prev && cur }
    val current = done.indexOfFirst { !it }
    val sheet = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet, containerColor = Surface1) {
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Heading("Judge Lab", 22); Spacer(Modifier.weight(1f)); Tag("REAL DEVNET TXS", Mint)
            }
            Body("The whole life of a pledge in about six minutes: demo pledges use 2-minute days. Nothing here is simulated except the steps you add, which are labelled.")
            LabStep(1, "Fund the wallet", done[0], current == 0, "Devnet SOL for fees and deposits") {
                GradientButton("Get devnet SOL", busy = "airdrop" in s.pending || "sponsor" in s.pending) { vm.airdrop() }
            }
            LabStep(2, "Get test SKR", done[1], current == 1, "Minted by the program's own faucet") {
                GradientButton("Get 10,000 test SKR", busy = "faucet" in s.pending) { vm.getTestSkr(a.sender) }
            }
            LabStep(3, "Lock a 3-day demo pledge", done[2], current == 2, "3,000 steps a day, 1,000 test SKR, 2-minute days") {
                GradientButton("Lock demo pledge", busy = "create" in s.pending) {
                    vm.dismissResult(); a.create(HabitSpec("Demo · 3K steps", Kind.STEPS, 3_000, 3, 1_000, demo = true)); onClose()
                }
            }
            LabStep(4, "Check in day 1", done[3], current == 3, "Walk, or add simulated steps on the Active tab, then Check in. No wallet screen: the phone's session key signs.") {
                GradientButton("Go to Active") { a.goTo(Tab.Active); onClose() }
            }
            LabStep(5, "Miss day 2, keep day 3, settle", done[4], current == 4, "After six minutes, Settle: kept days come back, missed days burn. Then check Vault and Ranks.") {
                GradientButton("Go to Active") { a.goTo(Tab.Active); onClose() }
            }
            if (current == -1) GhostButton("Start over with a new demo pledge", color = Mint) {
                vm.dismissResult(); a.create(HabitSpec("Demo · 3K steps", Kind.STEPS, 3_000, 3, 1_000, demo = true)); onClose()
            }
            Body("Also try the 6 AM Club in demo: check-in is accepted only in the first 40 s of each 2-minute day, and Solana's clock decides.", TextLo, 13)
            GhostButton("Lock a demo 6 AM Club pledge", enabled = c == null, color = Gold) {
                a.create(HabitSpec("Demo · 6 AM Club", Kind.WAKE, 0, 3, 1_000, demo = true)); onClose()
            }
        }
    }
}

@Composable
private fun LabStep(n: Int, title: String, done: Boolean, current: Boolean, detail: String, action: @Composable () -> Unit) {
    GlassCard(
        Modifier.alpha(if (done || current) 1f else 0.45f),
        glow = when {
            done -> Brush.horizontalGradient(listOf(Mint.copy(alpha = 0.6f), Mint.copy(alpha = 0.2f)))
            current -> SolanaGradient
            else -> null
        },
        padding = 14.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(if (done) Mint else CardHigh),
                contentAlignment = Alignment.Center,
            ) { Text(if (done) "✓" else "$n", color = if (done) OnGradient else TextHi, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = TextHi, fontWeight = FontWeight.SemiBold)
                Text(detail, color = TextLo, fontSize = 12.sp)
            }
        }
        if (current) action()
    }
}

@Composable
fun HowItWorks(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Surface1,
        confirmButton = { TextButton(onClick = onClose) { Text("Got it", color = Mint) } },
        title = { Text("How PledgeX works", fontFamily = Grotesk) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1. Pick a habit, a number of days and a stake. The stake moves into an escrow account of the PledgeX program on Solana.")
                Text("2. Each day you keep the habit, tap Check in. A key kept on this phone signs it; it can check in and nothing else.")
                Text("3. After the last day, settle: kept days come back to you, missed days are burned. Nobody receives them, not even us.")
                Text("Honest limits: steps and screen time are reported by your phone and the program trusts them. The 6 AM Club is checked by Solana's own clock. Devnet only, test tokens.", color = TextLo)
            }
        },
    )
}

/** Shared by Active and Explore: devnet SOL and test SKR before a pledge. */
@Composable
fun FundingCard(s: UiState, a: Actions) {
    val needSol = s.lowSol && s.commitment == null
    val needSkr = s.commitment == null && s.skr != null && s.skr < PledgeProgram.FAUCET_CAP
    if (!needSol && !needSkr) return
    val open = openUrl()
    GlassCard(glow = GlassBorder) {
        Overline("Get test funds", Cyan)
        if (needSol) {
            Body("Fees and account deposits are paid in devnet SOL (deposits come back when you settle). About 0.01 SOL is enough.")
            GradientButton("Get devnet SOL", busy = "airdrop" in s.pending || "sponsor" in s.pending, brush = MintCyanGradient) { a.vm.airdrop() }
            TextButton(onClick = { open("https://faucet.solana.com") }) { Text("Faucet busy? faucet.solana.com ↗", color = TextLo, fontSize = 13.sp) }
        }
        if (needSkr) {
            Body("Stakes are in test SKR, minted by the PledgeX program itself: 10,000 per tap while you hold under 5,000.")
            GradientButton("Get 10,000 test SKR", enabled = !needSol, busy = "faucet" in s.pending) { a.vm.getTestSkr(a.sender) }
        }
        Text("Balance: %.3f SOL · %s test SKR".format((s.lamports ?: 0) / 1e9, formatSkr(s.skr ?: 0)), color = TextDim, fontSize = 12.sp)
    }
}

/** The cyber backdrop: obsidian black, a purple and a mint glow, and a faint neon grid. */
fun Modifier.cyberBackground() = this.drawBehind {
    drawRect(Void)
    drawRect(androidx.compose.ui.graphics.Brush.radialGradient(listOf(Purple.copy(alpha = 0.22f), Color.Transparent),
        center = androidx.compose.ui.geometry.Offset(0f, 0f), radius = size.maxDimension * 0.6f))
    drawRect(androidx.compose.ui.graphics.Brush.radialGradient(listOf(Mint.copy(alpha = 0.12f), Color.Transparent),
        center = androidx.compose.ui.geometry.Offset(size.width, size.height), radius = size.maxDimension * 0.6f))
    val step = 36.dp.toPx()
    val grid = Cyan.copy(alpha = 0.035f)
    var x = 0f
    while (x < size.width) { drawLine(grid, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height)); x += step }
    var y = 0f
    while (y < size.height) { drawLine(grid, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y)); y += step }
}

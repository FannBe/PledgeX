package com.pledgex.app

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.funkatronics.encoders.Base58
import com.pledgex.app.chain.Commitment
import com.pledgex.app.chain.LocalKey
import com.pledgex.app.chain.MessageCompiler
import com.pledgex.app.chain.PledgeError
import com.pledgex.app.chain.PledgeProgram
import com.pledgex.app.chain.SolanaRpc
import com.pledgex.app.chain.signLocally
import com.pledgex.app.steps.StepCounter
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.Solana
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.Transaction
import com.solana.transaction.TransactionInstruction
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class WalletKind { Mwa, Demo }

data class TxRecord(val kind: String, val signature: String, val time: Long, val note: String) {
    val explorerUrl get() = "https://explorer.solana.com/tx/$signature?cluster=devnet"
}

data class SettleResult(val refund: Long, val burn: Long, val completed: Int, val total: Int, val signature: String)

data class UiState(
    val wallet: String? = null,
    val walletKind: WalletKind? = null,
    val lamports: Long? = null,
    val skr: Long? = null,
    val sessionAddress: String = "",
    val sessionLamports: Long? = null,
    val commitment: Commitment? = null,
    /** True once the chain has been read for this wallet, so "no pledge" is a fact. */
    val loaded: Boolean = false,
    /** Chain unix time, ticking every second from the last measured offset. */
    val chainNow: Long = System.currentTimeMillis() / 1000,
    val todaySteps: Long = 0,
    val demoStepsToday: Long = 0,
    val hasStepSensor: Boolean = false,
    val stepPermission: Boolean = true,
    val pending: Set<String> = emptySet(),
    val message: String? = null,
    val error: String? = null,
    val history: List<TxRecord> = emptyList(),
    val lastResult: SettleResult? = null,
) {
    val lowSol get() = lamports != null && lamports < MIN_SOL_TO_PLEDGE
    val busy get() = pending.isNotEmpty()
}

/** Enough SOL for a pledge: two accounts' rent (~0.0045, returned at settle), fees and the session key's float. */
const val MIN_SOL_TO_PLEDGE = 10_000_000L
/** Lamports the session key receives with a pledge: about 500 clock-ins and settles worth of fees. */
const val SESSION_FLOAT = 3_000_000L
const val SESSION_MIN = 1_000_000L

private class AccountSwitched(val account: String) : Exception("wallet switched to $account")
/** The person came back from the wallet without an answer: they cancelled or backed out. */
private class WalletDismissed : java.util.concurrent.CancellationException("left the wallet without answering")
private class NotEnough(message: String) : Exception(message)

class PledgeViewModel(app: Application) : AndroidViewModel(app) {
    private val rpc = SolanaRpc(BuildConfig.RPC_URL)
    private val prefs = app.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val session = LocalKey.load(app, "session")
    private val steps = StepCounter(app)
    private val mwa = MobileWalletAdapter(
        connectionIdentity = ConnectionIdentity(
            // Our own page; the identity icon MUST be relative to it, or the
            // wallet adapter refuses the request before the wallet even opens.
            identityUri = Uri.parse("https://fannbe.github.io/PledgeX/"),
            iconUri = Uri.parse("icon.png"),
            identityName = "PledgeX",
        ),
    ).apply { blockchain = Solana.Devnet }

    private val _state = MutableStateFlow(
        UiState(
            wallet = prefs.getString("wallet", null),
            walletKind = prefs.getString("walletKind", null)?.let { runCatching { WalletKind.valueOf(it) }.getOrNull() },
            sessionAddress = session.address,
            hasStepSensor = steps.available,
        ),
    )
    val state: StateFlow<UiState> = _state

    private var clockOffset = prefs.getLong("clockOffset", 0)
    private var poller: Job? = null
    private var walletRequest: Job? = null

    init {
        _state.update { it.copy(history = loadHistory(it.wallet)) }
        viewModelScope.launch { steps.reading.collect { recomputeSteps() } }
    }

    // ---- lifecycle -------------------------------------------------------------------

    fun start() {
        steps.start()
        if (poller?.isActive == true) return
        poller = viewModelScope.launch {
            var tick = 0
            while (isActive) {
                if (tick % POLL_EVERY_S == 0) runCatching { refresh() }.onFailure { Log.w(TAG, "refresh", it) }
                _state.update { it.copy(chainNow = System.currentTimeMillis() / 1000 + clockOffset) }
                recomputeSteps()
                tick++
                delay(1000)
            }
        }
    }

    fun stop() {
        poller?.cancel()
        steps.stop()
    }

    /** Back from the wallet without an answer: after a short grace, drop the request. */
    fun onForeground() {
        val waiting = walletRequest ?: return
        viewModelScope.launch {
            delay(WALLET_GRACE_MS)
            if (walletRequest === waiting && waiting.isActive) waiting.cancel(WalletDismissed())
        }
    }

    fun setStepPermission(granted: Boolean) {
        _state.update { it.copy(stepPermission = granted) }
        if (granted) { steps.stop(); steps.start() }
    }

    // ---- reading the chain -----------------------------------------------------------

    private suspend fun refresh() {
        val chain = rpc.chainTime()
        clockOffset = chain - System.currentTimeMillis() / 1000
        prefs.edit().putLong("clockOffset", clockOffset).apply()
        val sessionLamports = rpc.lamports(session.address)
        _state.update { it.copy(chainNow = chain, sessionLamports = sessionLamports) }

        val wallet = _state.value.wallet ?: return
        val owner = SolanaPublicKey.from(wallet)
        val lamports = rpc.lamports(wallet)
        val skr = rpc.tokenBalance(PledgeProgram.tokenAccount(owner).base58()) ?: 0
        val commitment = findCommitment(wallet)
        if (_state.value.wallet != wallet) return // switched while reading
        _state.update { it.copy(lamports = lamports, skr = skr, commitment = commitment, loaded = true) }
    }

    /** The wallet's open pledge: the remembered address, else a search of the program's accounts. */
    private suspend fun findCommitment(wallet: String): Commitment? {
        val key = "commitment_$wallet"
        prefs.getString(key, null)?.let { address ->
            val data = rpc.accountData(address)
            if (data != null) return Commitment.decode(address, data).takeUnless { it.settled }
            prefs.edit().remove(key).apply()
        }
        val found = rpc.programAccounts(
            PledgeProgram.PROGRAM_ID.base58(), PledgeProgram.COMMITMENT_LEN, 8, wallet,
        ).map { (address, data) -> Commitment.decode(address, data) }
            .filter { !it.settled }
            .maxByOrNull { it.start } ?: return null
        prefs.edit().putString(key, found.address).apply()
        return found
    }

    private fun recomputeSteps() {
        val s = _state.value
        val c = s.commitment ?: return _state.update { it.copy(todaySteps = 0, demoStepsToday = 0) }
        val day = c.dayAt(s.chainNow)
        val total = steps.stepsFor(c.address, day, steps.reading.value)
        _state.update { it.copy(todaySteps = total, demoStepsToday = steps.demoSteps(c.address, day)) }
    }

    // ---- wallet ------------------------------------------------------------------------

    fun connect(sender: ActivityResultSender) = viewModelScope.launch {
        _state.update { it.copy(pending = it.pending + "connect", error = null) }
        walletRequest = coroutineContext[Job]
        val result = try {
            mwa.connect(sender)
        } catch (e: java.util.concurrent.CancellationException) {
            TransactionResult.Failure<Unit>("wallet dismissed", e)
        } finally {
            walletRequest = null
        }
        when (result) {
            is TransactionResult.Success -> {
                val key = Base58.encodeToString(result.authResult.accounts.first().publicKey)
                setWallet(key, WalletKind.Mwa)
            }
            is TransactionResult.NoWalletFound -> _state.update {
                it.copy(error = "No Solana wallet app found. Install Phantom or Solflare, or use a demo wallet.")
            }
            is TransactionResult.Failure -> _state.update { it.copy(error = friendly(result.e)) }
        }
        _state.update { it.copy(pending = it.pending - "connect") }
    }

    /** A wallet kept on this phone, for trying PledgeX without a wallet app. */
    fun useDemoWallet() = viewModelScope.launch {
        val key = LocalKey.load(getApplication(), DEMO_SLOT)
        setWallet(key.address, WalletKind.Demo)
        if ((_state.value.lamports ?: 0) < MIN_SOL_TO_PLEDGE) airdrop().join()
    }

    private suspend fun setWallet(address: String, kind: WalletKind) {
        prefs.edit().putString("wallet", address).putString("walletKind", kind.name).apply()
        _state.update {
            UiState(
                wallet = address, walletKind = kind, sessionAddress = session.address,
                hasStepSensor = steps.available, stepPermission = it.stepPermission,
                chainNow = it.chainNow, sessionLamports = it.sessionLamports, history = loadHistory(address),
            )
        }
        runCatching { refresh() }.onFailure { Log.w(TAG, "refresh", it) }
    }

    fun disconnect() {
        prefs.edit().remove("wallet").remove("walletKind").apply()
        _state.update { UiState(sessionAddress = session.address, hasStepSensor = steps.available, chainNow = it.chainNow) }
    }

    // ---- actions -----------------------------------------------------------------------

    /** Devnet SOL from the RPC's faucet. */
    fun airdrop() = viewModelScope.launch {
        val owner = _state.value.wallet ?: return@launch
        _state.update { it.copy(pending = it.pending + "airdrop", error = null) }
        try {
            val sig = rpc.requestAirdrop(owner, AIRDROP_LAMPORTS)
            var landed = false
            repeat(40) {
                if (!landed && rpc.isConfirmed(sig)) landed = true
                if (!landed) delay(1000)
            }
            if (landed) {
                record("Devnet SOL", sig, "+0.5 SOL from the devnet faucet")
                _state.update { it.copy(message = "0.5 devnet SOL added.") }
                refresh()
            } else {
                _state.update { it.copy(error = "The devnet faucet did not confirm in time. Try again, or use faucet.solana.com.") }
            }
        } catch (e: Exception) {
            Log.w(TAG, "airdrop failed", e)
            _state.update { it.copy(error = "The devnet SOL faucet is busy or limited. Copy your address and use faucet.solana.com, then come back.") }
        }
        _state.update { it.copy(pending = it.pending - "airdrop") }
    }

    /** 10,000 test SKR minted by the program itself. */
    fun getTestSkr(sender: ActivityResultSender) = viewModelScope.launch {
        val owner = ownerKey() ?: return@launch
        val sig = submitAsOwner(sender, "faucet") { PledgeProgram.faucet(owner) } ?: return@launch
        record("Test SKR", sig, "+10,000 test SKR from the program faucet")
        done("10,000 test SKR added.")
    }

    fun createPledge(sender: ActivityResultSender, targetSteps: Int, totalDays: Int, demo: Boolean, stakeWhole: Long) =
        viewModelScope.launch {
            val s = _state.value
            val owner = ownerKey() ?: return@launch
            val amount = stakeWhole * PledgeProgram.UNIT
            if ((s.lamports ?: 0) < MIN_SOL_TO_PLEDGE) return@launch fail("You need about 0.01 devnet SOL for the fees. Tap \"Get devnet SOL\" first.")
            if ((s.skr ?: 0) < amount) return@launch fail("Not enough test SKR for this stake. Tap \"Get test SKR\" first.")
            val id = System.currentTimeMillis()
            val daySec = if (demo) DEMO_DAY_SEC else PledgeProgram.REAL_DAY_SEC
            val sig = submitAsOwner(sender, "create") {
                buildList {
                    add(PledgeProgram.createCommitment(owner, session.publicKey, id, targetSteps, totalDays, daySec, amount))
                    // The session key pays its own clock-in fees; top it up with the stake.
                    if ((rpc.lamports(session.address)) < SESSION_MIN) {
                        add(PledgeProgram.transferSol(owner, session.publicKey, SESSION_FLOAT))
                    }
                }
            } ?: return@launch
            prefs.edit().putString("commitment_${owner.base58()}", PledgeProgram.commitment(owner, id).base58()).apply()
            record("Pledge", sig, "Staked ${formatSkr(amount)} test SKR · $totalDays days × ${targetSteps.fmt()} steps")
            _state.update { it.copy(lastResult = null) }
            done("Pledge locked on chain. Day 1 starts now.")
        }

    fun clockIn(sender: ActivityResultSender) = viewModelScope.launch {
        val s = _state.value
        val c = s.commitment ?: return@launch
        val day = c.dayAt(s.chainNow)
        if (day >= c.totalDays || c.clockedIn(day)) return@launch
        if (s.todaySteps < c.targetSteps) return@launch fail("${s.todaySteps.fmt()} of ${c.targetSteps.fmt()} steps so far — keep walking.")
        val stepsReported = s.todaySteps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val sig = if (c.clockInAuthority == session.address && (s.sessionLamports ?: 0) >= SESSION_FEE_RESERVE) {
            // No wallet screen: the device key the program accepts for clock-in signs alone.
            submitLocal("clockin", session, listOf(session)) {
                listOf(PledgeProgram.clockIn(session.publicKey, SolanaPublicKey.from(c.address), day, stepsReported))
            }
        } else {
            val owner = ownerKey() ?: return@launch
            submitAsOwner(sender, "clockin") {
                listOf(PledgeProgram.clockIn(owner, SolanaPublicKey.from(c.address), day, stepsReported))
            }
        } ?: return@launch
        record("Clock-in", sig, "Day ${day + 1} of ${c.totalDays} · ${stepsReported.toLong().fmt()} steps")
        done("Day ${day + 1} recorded on chain.")
    }

    fun settle(sender: ActivityResultSender) = viewModelScope.launch {
        val s = _state.value
        val c = s.commitment ?: return@launch
        val owner = SolanaPublicKey.from(c.authority)
        val commitment = SolanaPublicKey.from(c.address)
        // Anyone may settle and the refund only goes to the owner, so the device key
        // can pay the fee and spare the wallet screen.
        val sig = if ((s.sessionLamports ?: 0) >= SESSION_FEE_RESERVE) {
            submitLocal("settle", session, listOf(session)) { listOf(PledgeProgram.settle(session.publicKey, owner, commitment)) }
        } else {
            val me = ownerKey() ?: return@launch
            submitAsOwner(sender, "settle") { listOf(PledgeProgram.settle(me, owner, commitment)) }
        } ?: return@launch
        prefs.edit().remove("commitment_${c.authority}").apply()
        val result = SettleResult(c.refund, c.burn, c.completedDays, c.totalDays, sig)
        record("Settle", sig, "Returned ${formatSkr(c.refund)} · burned ${formatSkr(c.burn)} test SKR")
        _state.update { it.copy(lastResult = result, commitment = null) }
        done("Settled. ${formatSkr(c.refund)} test SKR is back in your wallet.")
    }

    /** Demo pledges only: simulated steps, shown as simulated, for trying the flow anywhere. */
    fun addDemoSteps(amount: Long) {
        val s = _state.value
        val c = s.commitment ?: return
        if (!c.isDemo) return
        steps.addDemoSteps(c.address, c.dayAt(s.chainNow), amount)
        recomputeSteps()
    }

    fun dismissResult() = _state.update { it.copy(lastResult = null) }
    fun messageShown() = _state.update { it.copy(message = null) }
    fun dismissError() = _state.update { it.copy(error = null) }

    // ---- sending -----------------------------------------------------------------------

    private fun ownerKey(): SolanaPublicKey? = _state.value.wallet?.let(SolanaPublicKey::from)

    private fun fail(text: String) = _state.update { it.copy(error = text) }

    private suspend fun done(text: String) {
        _state.update { it.copy(message = text) }
        runCatching { refresh() }
    }

    /** Signed by the owner: the demo key on this phone, or the wallet app through MWA. */
    private suspend fun submitAsOwner(
        sender: ActivityResultSender,
        pendingKey: String,
        build: suspend () -> List<TransactionInstruction>,
    ): String? {
        if (_state.value.walletKind == WalletKind.Demo) {
            val key = LocalKey.load(getApplication(), DEMO_SLOT)
            return submitLocal(pendingKey, key, listOf(key), build)
        }
        return submit(pendingKey) {
            val instructions = PledgeProgram.computeBudget() + build()
            val payer = ownerKey() ?: error("no wallet")
            // Phantom refuses `reauthorize` on its legacy protocol; with no saved token
            // every session opens with a plain authorize, in one trip.
            mwa.authToken = null
            var blockhash = ""
            walletRequest = kotlin.coroutines.coroutineContext[Job]
            val result = try {
                mwa.transact(sender) { auth ->
                    val account = Base58.encodeToString(auth.accounts.first().publicKey)
                    if (account != payer.base58()) throw AccountSwitched(account)
                    // Taken once the wallet is open, so it cannot expire on the approval screen.
                    blockhash = rpc.latestBlockhash().first
                    val message = MessageCompiler.compile(payer, instructions, blockhash)
                    // The wallet only signs; the app broadcasts through its own RPC.
                    signTransactions(arrayOf(Transaction(message).serialize()))
                }
            } finally {
                walletRequest = null
            }
            val signed = when (result) {
                is TransactionResult.Success -> result.payload.signedPayloads.first()
                is TransactionResult.NoWalletFound -> throw IllegalStateException("No Solana wallet found")
                is TransactionResult.Failure -> throw result.e
            }
            Triple(signed, signed.copyOfRange(1, 65), blockhash)
        }
    }

    /** Signed on this phone by local keys; `payer` pays the fee. */
    private suspend fun submitLocal(
        pendingKey: String,
        payer: LocalKey,
        signers: List<LocalKey>,
        build: suspend () -> List<TransactionInstruction>,
    ): String? = submit(pendingKey) {
        val instructions = PledgeProgram.computeBudget() + build()
        val blockhash = rpc.latestBlockhash().first
        val (signed, signature) = signLocally(payer, signers, instructions, blockhash)
        Triple(signed, signature, blockhash)
    }

    /**
     * Broadcasts what `sign` produced and waits for confirmation, resending the same
     * bytes until it lands or its blockhash expires. Returns the signature, or null
     * after showing why it failed.
     */
    private suspend fun submit(pendingKey: String, sign: suspend () -> Triple<ByteArray, ByteArray, String>): String? {
        _state.update { it.copy(pending = it.pending + pendingKey, message = null, error = null) }
        try {
            val (signed, sigBytes, blockhash) = sign()
            val sig = Base58.encodeToString(sigBytes)
            Log.i(TAG, "$pendingKey signed: ${android.util.Base64.encodeToString(signed, android.util.Base64.NO_WRAP)}")
            rpc.sendTransaction(signed)
            while (true) {
                repeat(4) {
                    if (rpc.isConfirmed(sig)) return sig.also { _state.update { s -> s.copy(pending = s.pending - pendingKey) } }
                    delay(2000)
                }
                if (!rpc.isBlockhashValid(blockhash)) break
                runCatching { rpc.sendTransaction(signed) }
            }
            if (rpc.isConfirmed(sig)) return sig.also { _state.update { s -> s.copy(pending = s.pending - pendingKey) } }
            _state.update { it.copy(error = "The transaction expired before it landed. Nothing was charged — try again.") }
        } catch (e: Exception) {
            Log.w(TAG, "$pendingKey failed", e)
            _state.update { it.copy(error = friendly(e)) }
        }
        _state.update { it.copy(pending = it.pending - pendingKey) }
        return null
    }

    /** Every failure in words a person can act on; the raw error goes to the log. */
    private fun friendly(e: Throwable): String {
        if (e is AccountSwitched) {
            viewModelScope.launch { setWallet(e.account, WalletKind.Mwa) }
            return "Your wallet is on account ${e.account.take(4)}…${e.account.takeLast(4)}. PledgeX switched to it — try again."
        }
        val chain = generateSequence(e) { it.cause }.toList()
        val text = chain.joinToString(" ") { "${it.javaClass.name} ${it.message.orEmpty()}" }
        val lower = text.lowercase()
        PledgeError.from(text)?.let { return it.userMessage }
        return when {
            chain.any { it is WalletDismissed } -> "Cancelled in the wallet — nothing was signed or sent."
            chain.any { it is java.util.concurrent.CancellationException } -> "The wallet closed without signing, so nothing was sent."
            chain.any { it is java.util.concurrent.TimeoutException } -> "The wallet didn't answer in time. Nothing was sent."
            "authorization request failed" in lower || "incorrect mode" in lower ->
                "The wallet refused. In Phantom turn on Settings → Developer Settings → Testnet Mode and pick Solana Devnet, then try again."
            "insufficient" in lower || "0x1" in lower && "transfer" in lower ->
                "Not enough devnet SOL for this. Tap \"Get devnet SOL\" and try again."
            "blockhash not found" in lower -> "The approval took too long and the transaction expired. Nothing was sent — try again."
            "429" in lower || "too many requests" in lower -> "Solana devnet is busy. Wait a few seconds and try again."
            "simulation failed" in lower -> "Solana turned this transaction down, so nothing was sent or charged."
            chain.any { it is java.io.IOException } -> "No connection to Solana devnet. Check the internet and try again."
            else -> "Something went wrong and nothing was sent. Try again."
        }
    }

    // ---- history -----------------------------------------------------------------------

    private fun record(kind: String, signature: String, note: String) {
        val wallet = _state.value.wallet ?: return
        val entry = TxRecord(kind, signature, System.currentTimeMillis() / 1000, note)
        val list = (listOf(entry) + _state.value.history).take(MAX_HISTORY)
        prefs.edit().putString("history_$wallet", list.joinToString("\n") { "${it.kind}\t${it.signature}\t${it.time}\t${it.note}" }).apply()
        _state.update { it.copy(history = list) }
    }

    private fun loadHistory(wallet: String?): List<TxRecord> {
        wallet ?: return emptyList()
        return prefs.getString("history_$wallet", null).orEmpty().lines().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 4) null else TxRecord(p[0], p[1], p[2].toLongOrNull() ?: 0, p[3])
        }
    }

    private companion object {
        const val TAG = "PledgeX"
        const val DEMO_SLOT = "demo_wallet"
        const val POLL_EVERY_S = 6
        const val WALLET_GRACE_MS = 6_000L
        const val AIRDROP_LAMPORTS = 500_000_000L
        /** A demo day: two minutes, so a 3-day pledge runs its whole life in six. */
        const val DEMO_DAY_SEC = 120L
        const val SESSION_FEE_RESERVE = 950_000L
        const val MAX_HISTORY = 30
    }
}

fun formatSkr(amount: Long): String {
    val whole = amount / PledgeProgram.UNIT
    val frac = (amount % PledgeProgram.UNIT) / 10_000_000 // two decimals
    return if (frac == 0L) "%,d".format(whole) else "%,d.%02d".format(whole, frac)
}

fun Long.fmt(): String = "%,d".format(this)
fun Int.fmt(): String = "%,d".format(this)

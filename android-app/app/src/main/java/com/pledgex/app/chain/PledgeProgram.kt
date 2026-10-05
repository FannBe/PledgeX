package com.pledgex.app.chain

import com.solana.publickey.ProgramDerivedAddress
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.AccountMeta
import com.solana.transaction.TransactionInstruction
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * The on-chain pledge program: addresses, the Commitment layout and the instructions
 * the app sends. Mirrors anchor-program/programs/pledge/src and cli/lib.mjs, whose
 * e2e run against devnet is what proves these bytes.
 */
object PledgeProgram {
    val PROGRAM_ID = SolanaPublicKey.from("68c1eNdHAfNWJhCWhtqumiqzYyFcDNLkfgwKwdFwFRcd")
    val SKR_MINT = SolanaPublicKey.from("F4L7W4qgFAuU2iyg5ePBENXTHeZyTPor4tJMfhUHqfgQ")
    val SYSTEM_PROGRAM = SolanaPublicKey.from("11111111111111111111111111111111")
    val TOKEN_PROGRAM = SolanaPublicKey.from("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA")
    val ASSOCIATED_TOKEN_PROGRAM = SolanaPublicKey.from("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL")
    val RENT_SYSVAR = SolanaPublicKey.from("SysvarRent111111111111111111111111111111111")
    private val COMPUTE_BUDGET = SolanaPublicKey.from("ComputeBudget111111111111111111111111111111")

    const val DECIMALS = 9
    const val UNIT = 1_000_000_000L
    /** Commitment account size (state.rs Commitment::LEN). */
    const val COMMITMENT_LEN = 209
    /** A wallet below this holds too little test SKR and may use the faucet. */
    const val FAUCET_CAP = 5_000 * UNIT
    const val FAUCET_AMOUNT = 10_000 * UNIT
    const val MIN_DAY_SEC = 60L
    const val REAL_DAY_SEC = 86_400L

    /** Anchor's discriminator: the first 8 bytes of sha256("<namespace>:<name>"). */
    fun discriminator(namespace: String, name: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest("$namespace:$name".toByteArray()).copyOf(8)

    private fun le(size: Int, put: ByteBuffer.() -> Unit): ByteArray =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(put).array()

    private suspend fun pda(vararg seeds: ByteArray): SolanaPublicKey =
        ProgramDerivedAddress.find(seeds.toList(), PROGRAM_ID).getOrThrow()

    suspend fun commitment(owner: SolanaPublicKey, id: Long) =
        pda("commitment".toByteArray(), owner.bytes, le(8) { putLong(id) })
    suspend fun vault(commitment: SolanaPublicKey) = pda("vault".toByteArray(), commitment.bytes)
    suspend fun faucetAuthority() = pda("faucet".toByteArray())

    suspend fun tokenAccount(owner: SolanaPublicKey): SolanaPublicKey =
        ProgramDerivedAddress.find(listOf(owner.bytes, TOKEN_PROGRAM.bytes, SKR_MINT.bytes), ASSOCIATED_TOKEN_PROGRAM).getOrThrow()

    private fun w(key: SolanaPublicKey, signer: Boolean = false) = AccountMeta(key, signer, true)
    private fun r(key: SolanaPublicKey, signer: Boolean = false) = AccountMeta(key, signer, false)

    fun computeBudget(units: Int = 200_000, microLamportsPerUnit: Long = 1_000) = listOf(
        TransactionInstruction(COMPUTE_BUDGET, emptyList(), byteArrayOf(2) + le(4) { putInt(units) }),
        TransactionInstruction(COMPUTE_BUDGET, emptyList(), byteArrayOf(3) + le(8) { putLong(microLamportsPerUnit) }),
    )

    fun transferSol(from: SolanaPublicKey, to: SolanaPublicKey, lamports: Long) = TransactionInstruction(
        SYSTEM_PROGRAM, listOf(w(from, true), w(to)), le(12) { putInt(2); putLong(lamports) },
    )

    /** Creates the owner's test-SKR account if missing, then mints 10,000 from the program faucet. */
    suspend fun faucet(owner: SolanaPublicKey): List<TransactionInstruction> {
        val ata = tokenAccount(owner)
        return listOf(
            TransactionInstruction(
                ASSOCIATED_TOKEN_PROGRAM,
                listOf(w(owner, true), w(ata), r(owner), r(SKR_MINT), r(SYSTEM_PROGRAM), r(TOKEN_PROGRAM)),
                byteArrayOf(1), // CreateIdempotent
            ),
            TransactionInstruction(
                PROGRAM_ID,
                listOf(r(owner, true), w(SKR_MINT), r(faucetAuthority()), w(ata), r(TOKEN_PROGRAM)),
                discriminator("global", "faucet"),
            ),
        )
    }

    suspend fun createCommitment(
        owner: SolanaPublicKey,
        session: SolanaPublicKey?,
        id: Long,
        targetSteps: Int,
        totalDays: Int,
        daySec: Long,
        amount: Long,
    ): TransactionInstruction {
        val commitment = commitment(owner, id)
        return TransactionInstruction(
            PROGRAM_ID,
            listOf(
                w(owner, true), r(session ?: SYSTEM_PROGRAM), w(commitment), w(vault(commitment)),
                w(tokenAccount(owner)), r(SKR_MINT), r(TOKEN_PROGRAM), r(SYSTEM_PROGRAM), r(RENT_SYSVAR),
            ),
            discriminator("global", "create_commitment") + le(8 + 4 + 1 + 8 + 8) {
                putLong(id); putInt(targetSteps); put(totalDays.toByte()); putLong(daySec); putLong(amount)
            },
        )
    }

    fun clockIn(signer: SolanaPublicKey, commitment: SolanaPublicKey, dayIndex: Int, steps: Int) = TransactionInstruction(
        PROGRAM_ID,
        listOf(w(signer, true), w(commitment)),
        discriminator("global", "clock_in") + le(5) { put(dayIndex.toByte()); putInt(steps) },
    )

    suspend fun settle(caller: SolanaPublicKey, owner: SolanaPublicKey, commitment: SolanaPublicKey) = TransactionInstruction(
        PROGRAM_ID,
        listOf(
            r(caller, true), w(owner), w(commitment), w(vault(commitment)),
            w(tokenAccount(owner)), w(SKR_MINT), r(TOKEN_PROGRAM),
        ),
        discriminator("global", "settle"),
    )
}

/** The Commitment account, decoded. Times are chain unix seconds. */
data class Commitment(
    val address: String,
    val authority: String,
    val clockInAuthority: String,
    val targetSteps: Int,
    val totalDays: Int,
    val completedDays: Int,
    val daySec: Long,
    val start: Long,
    val totalAmount: Long,
    val settled: Boolean,
    val bitmap: Long,
    val id: Long,
) {
    val end get() = start + totalDays * daySec
    val isDemo get() = daySec < PledgeProgram.REAL_DAY_SEC
    /** The day index at chain time `now`; equals totalDays once the pledge has ended. */
    fun dayAt(now: Long): Int = ((now - start).coerceAtLeast(0) / daySec).toInt().coerceAtMost(totalDays)
    fun dayStart(day: Int) = start + day * daySec
    fun dayEnd(day: Int) = start + (day + 1) * daySec
    fun clockedIn(day: Int) = day in 0 until 64 && (bitmap ushr day) and 1L == 1L
    val dailyStake get() = totalAmount / totalDays
    /** What settle would return now: total x completed / total days, the program's own rounding. */
    val refund get() = (totalAmount.toBigInteger() * completedDays.toBigInteger() / totalDays.toBigInteger()).toLong()
    val burn get() = totalAmount - refund

    companion object {
        fun decode(address: String, data: ByteArray): Commitment {
            val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val disc = ByteArray(8).also { b.get(it) }
            require(disc.contentEquals(PledgeProgram.discriminator("account", "Commitment"))) { "not a Commitment" }
            fun key() = SolanaPublicKey(ByteArray(32).also { b.get(it) }).base58()
            val authority = key()
            val clockIn = key()
            key(); key() // mint, vault
            val target = b.int
            val totalDays = b.get().toInt() and 0xff
            val completed = b.get().toInt() and 0xff
            val daySec = b.long
            val start = b.long
            val total = b.long
            val settled = b.get().toInt() != 0
            val bitmap = b.long
            b.get(); b.get() // bumps
            val id = b.long
            return Commitment(address, authority, clockIn, target, totalDays, completed, daySec, start, total, settled, bitmap, id)
        }
    }
}

/**
 * The program's PledgeError, in declaration order: Anchor numbers custom errors from
 * 6000, and that number is all a failed transaction reports.
 */
enum class PledgeError(val userMessage: String) {
    TargetNotMet("Today's steps are below your target, so the chain refused the clock-in."),
    DayAlreadyClockedIn("Today is already clocked in."),
    InvalidDayWindow("That day's window has closed on chain. Clock in during the day itself."),
    CommitmentNotEnded("The pledge has not ended yet; settle opens after the last day."),
    AlreadySettled("This pledge is already settled."),
    UnauthorizedClockIn("This device's key is not allowed to clock in for this pledge."),
    InvalidTotalDays("Choose between 1 and 64 days."),
    InvalidDuration("A day must last between 1 minute and 7 days."),
    MathOverflow("The amount is too large."),
    InvalidAmount("The stake must be more than zero."),
    InvalidTarget("The step target must be more than zero."),
    FaucetBalanceTooHigh("You already hold 5,000 test SKR or more; the faucet refills below that.");

    companion object {
        private const val FIRST_CODE = 6000
        private val custom = Regex("\"Custom\"\\s*:\\s*(\\d+)")
        private val hex = Regex("custom program error: 0x([0-9a-fA-F]+)")

        fun from(message: String): PledgeError? {
            val code = custom.find(message)?.groupValues?.get(1)?.toIntOrNull()
                ?: hex.find(message)?.groupValues?.get(1)?.toIntOrNull(16)
            if (code != null) return entries.getOrNull(code - FIRST_CODE)
            return entries.firstOrNull { message.contains(it.name) }
        }
    }
}

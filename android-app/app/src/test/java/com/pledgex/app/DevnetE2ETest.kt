package com.pledgex.app

import com.pledgex.app.chain.Commitment
import com.pledgex.app.chain.Kind
import com.pledgex.app.chain.Profile
import com.pledgex.app.chain.LocalKey
import com.pledgex.app.chain.PledgeProgram
import com.pledgex.app.chain.signLocally
import com.funkatronics.encoders.Base58
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.TransactionInstruction
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.Base64

/**
 * The app's transaction path against the LIVE devnet program: PledgeProgram's
 * instruction bytes, MessageCompiler, signLocally and Commitment.decode — the code the
 * phone runs for the demo wallet and the session key. Off unless run with -Pe2e.
 * Funds a fresh owner from keys/deployer.json.
 */
class DevnetE2ETest {
    private val root = File(System.getProperty("pledgex.root") ?: "..")
    private val rpcUrl = File(root, "keys/rpc-url.txt").takeIf { it.exists() }?.readText()?.trim() ?: "https://api.devnet.solana.com"
    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    private fun rpc(method: String, params: String): JsonElement {
        val body = """{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}""".toRequestBody("application/json".toMediaType())
        repeat(5) { attempt ->
            runCatching {
                http.newCall(Request.Builder().url(rpcUrl).post(body).build()).execute().use { r ->
                    val obj = json.parseToJsonElement(r.body!!.string()).jsonObject
                    obj["error"]?.let { error(it.toString()) }
                    return obj["result"] ?: JsonNull
                }
            }.onFailure { if (attempt == 4 || "error" in (it.message ?: "") && "code" in (it.message ?: "")) throw it }
            Thread.sleep(1000)
        }
        error("unreachable")
    }

    private fun blockhash() = rpc("getLatestBlockhash", """[{"commitment":"confirmed"}]""")
        .jsonObject["value"]!!.jsonObject["blockhash"]!!.jsonPrimitive.content

    private fun send(payer: LocalKey, signers: List<LocalKey>, ixs: List<TransactionInstruction>): String {
        val (signed, sig) = signLocally(payer, signers, PledgeProgram.computeBudget() + ixs, blockhash())
        rpc("sendTransaction", """["${Base64.getEncoder().encodeToString(signed)}",{"encoding":"base64","preflightCommitment":"confirmed"}]""")
        val signature = Base58.encodeToString(sig)
        repeat(60) {
            val status = rpc("getSignatureStatuses", """[["$signature"]]""").jsonObject["value"]!!.jsonArray[0]
            if (status !is JsonNull) {
                val err = status.jsonObject["err"]
                check(err == null || err is JsonNull) { "$signature failed: $err" }
                if (status.jsonObject["confirmationStatus"]?.jsonPrimitive?.content in setOf("confirmed", "finalized")) return signature
            }
            Thread.sleep(1000)
        }
        error("$signature not confirmed")
    }

    private fun account(address: String): ByteArray? {
        val v = rpc("getAccountInfo", """["$address",{"encoding":"base64","commitment":"confirmed"}]""").jsonObject["value"]!!
        if (v is JsonNull) return null
        return Base64.getDecoder().decode(v.jsonObject["data"]!!.jsonArray[0].jsonPrimitive.content)
    }

    private fun skr(owner: LocalKey): Long = runBlocking {
        val data = account(PledgeProgram.tokenAccount(owner.publicKey).base58()) ?: return@runBlocking 0L
        ByteBuffer.wrap(data, 64, 8).order(ByteOrder.LITTLE_ENDIAN).long
    }

    private fun chainTime(): Long = ByteBuffer.wrap(account("SysvarC1ock11111111111111111111111111111111")!!, 32, 8)
        .order(ByteOrder.LITTLE_ENDIAN).long

    private fun fresh() = LocalKey.fromSeed(ByteArray(32).also { SecureRandom().nextBytes(it) })

    @Test
    fun fullPledgeOnDevnet() = runBlocking {
        assumeTrue(System.getProperty("pledgex.e2e") == "true")
        val deployerBytes = Json.parseToJsonElement(File(root, "keys/deployer.json").readText()).jsonArray.map { it.jsonPrimitive.content.toInt().toByte() }
        val deployer = LocalKey.fromSeed(deployerBytes.take(32).toByteArray())
        val owner = fresh()
        val session = fresh()
        println("owner ${owner.address}  session ${session.address}")

        println("fund   " + send(deployer, listOf(deployer), listOf(PledgeProgram.transferSol(deployer.publicKey, owner.publicKey, 30_000_000))))
        println("faucet " + send(owner, listOf(owner), PledgeProgram.faucet(owner.publicKey)))
        assertEquals(10_000 * PledgeProgram.UNIT, skr(owner))

        val id = System.currentTimeMillis()
        val stake = 3_000 * PledgeProgram.UNIT
        println("create " + send(owner, listOf(owner), listOf(
            // A one-day wake-up pledge: clock-in only in the first 30 s, on Solana's clock.
            PledgeProgram.createCommitment(owner.publicKey, session.publicKey, id, 0, 1, 60, stake, Kind.WAKE, 0, 30),
            PledgeProgram.transferSol(owner.publicKey, session.publicKey, SESSION_FLOAT),
        )))
        val address = PledgeProgram.commitment(owner.publicKey, id).base58()
        var c = Commitment.decode(address, account(address)!!)
        assertEquals(owner.address, c.authority)
        assertEquals(session.address, c.clockInAuthority)
        assertEquals(stake, c.totalAmount)
        assertEquals(id, c.id)
        assertEquals(Kind.WAKE, c.kind)
        assertEquals(30, c.windowSec)
        assertEquals(7_000 * PledgeProgram.UNIT, skr(owner))

        val day = c.dayAt(chainTime())
        println("clock  " + send(session, listOf(session),
            listOf(PledgeProgram.clockIn(session.publicKey, SolanaPublicKey.from(address), day, 0))))
        c = Commitment.decode(address, account(address)!!)
        assertEquals(1, c.completedDays)
        assertEquals(true, c.clockedIn(0))

        while (chainTime() < c.end + 2) Thread.sleep(3000)
        println("settle " + send(session, listOf(session),
            listOf(PledgeProgram.settle(session.publicKey, owner.publicKey, SolanaPublicKey.from(address)))))
        assertNull(account(address))
        assertEquals(10_000 * PledgeProgram.UNIT, skr(owner))
        val profileAddress = PledgeProgram.profile(owner.publicKey).base58()
        val p = Profile.decode(profileAddress, account(profileAddress)!!)
        assertEquals(owner.address, p.authority)
        assertEquals(1, p.settled)
        // A 60-second day is a demo day: it returns the money but earns no badge or rank.
        assertEquals(0, p.perfect)
        assertEquals(false, p.perfectIn(Kind.WAKE))
        assertEquals(0, p.realDaysKept)
        assertEquals(stake, p.returned)
        println("profile ${p.started} started, ${p.perfect} perfect, kinds ${p.perfectKinds}")
    }
}

package com.pledgex.app.chain

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.Transaction
import com.solana.transaction.TransactionInstruction
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * An Ed25519 keypair that lives on this phone. Its 32-byte seed is stored encrypted
 * with an AES key that never leaves the Android Keystore, so a copy of the app's
 * files alone does not reveal it.
 *
 * Two are used: the SESSION key, which the program allows to clock in (and nothing
 * else — it cannot move the stake), and an optional DEMO wallet for people without
 * a Solana wallet app. Both are devnet-only conveniences.
 */
class LocalKey private constructor(seed: ByteArray) {
    private val private = Ed25519PrivateKeyParameters(seed, 0)
    val publicKey = SolanaPublicKey(private.generatePublicKey().encoded)
    val address: String get() = publicKey.base58()

    fun sign(message: ByteArray): ByteArray = Ed25519Signer().run {
        init(true, private)
        update(message, 0, message.size)
        generateSignature()
    }

    companion object {
        private const val PREFS = "local_keys"
        private const val KEYSTORE_ALIAS = "pledgex_seed_wrap"

        /** A key from a known seed (tests, and importing a keypair file). */
        fun fromSeed(seed: ByteArray) = LocalKey(seed.copyOf(32))

        /** The key stored under `slot`, created on first use. */
        fun load(context: Context, slot: String): LocalKey {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.getString(slot, null)?.let { stored ->
                runCatching { return LocalKey(unwrap(stored)) }
            }
            val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
            prefs.edit().putString(slot, wrap(seed)).apply()
            return LocalKey(seed)
        }

        fun exists(context: Context, slot: String) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(slot)

        private fun wrappingKey(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(KEYSTORE_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return generator.generateKey()
        }

        private fun wrap(seed: ByteArray): String {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrappingKey()) }
            val out = cipher.iv + cipher.doFinal(seed)
            return Base64.encodeToString(out, Base64.NO_WRAP)
        }

        private fun unwrap(stored: String): ByteArray {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, bytes, 0, 12))
            return cipher.doFinal(bytes, 12, bytes.size - 12)
        }
    }
}

/**
 * Builds a legacy transaction with `payer` paying and signs it with every key in
 * `signers` (payer included). Returns the wire bytes and the first signature.
 */
fun signLocally(
    payer: LocalKey,
    signers: List<LocalKey>,
    instructions: List<TransactionInstruction>,
    blockhash: String,
): Pair<ByteArray, ByteArray> {
    val message = MessageCompiler.compile(payer.publicKey, instructions, blockhash)
    // An unsigned transaction is [count][count x 64 zero bytes][message].
    val unsigned = Transaction(message).serialize()
    val count = unsigned[0].toInt()
    val messageBytes = unsigned.copyOfRange(1 + 64 * count, unsigned.size)
    val keys = message.accounts.take(count)
    val signed = unsigned.copyOf()
    keys.forEachIndexed { i, key ->
        val signer = signers.firstOrNull { it.publicKey.base58() == key.base58() }
            ?: error("missing signature for ${key.base58()}")
        signer.sign(messageBytes).copyInto(signed, 1 + 64 * i)
    }
    return signed to signed.copyOfRange(1, 65)
}

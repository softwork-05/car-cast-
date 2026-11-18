package com.carcast.mirror.engine.airplay

import android.content.Context
import android.util.Base64
import android.util.Log
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Receiver identity and legacy AirPlay pair-verify support.
 *
 * IMPORTANT: This is NOT a FairPlay implementation and does NOT decrypt AirPlay media.
 * The identity must be initialized before starting mDNS or the RTSP listener.
 */
class AirPlayCrypto private constructor(identitySeed: ByteArray) {
    companion object {
        private const val TAG = "CarCast_AirPlayCrypto"
        private const val PREF_FILE = "carcast_airplay_identity"
        private const val PREF_SEED = "identity_ed25519_seed_v1"
        private val lock = Any()
        @Volatile private var configured: AirPlayCrypto? = null

        val instance: AirPlayCrypto
            get() = configured ?: error("Call AirPlayCrypto.initialize(context) before advertising AirPlay")

        /** Preserve the same identity across application restarts and APK updates. */
        fun initialize(context: Context) {
            synchronized(lock) {
                if (configured != null) return
                val prefs = context.applicationContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                val stored = prefs.getString(PREF_SEED, null)
                val decoded = try {
                    stored?.let { Base64.decode(it, Base64.NO_WRAP) }
                } catch (_: IllegalArgumentException) { null }
                val seed = if (decoded?.size == 32) decoded else ByteArray(32).apply {
                    SecureRandom().nextBytes(this)
                    check(prefs.edit().putString(PREF_SEED, Base64.encodeToString(this, Base64.NO_WRAP)).commit()) {
                        "Could not persist AirPlay identity"
                    }
                }
                configured = AirPlayCrypto(seed)
                // The identity seed resides in app-private storage. For production, review backup
                // and at-rest encryption requirements; do not log/export the private seed.
                Log.i(TAG, "Persistent AirPlay receiver identity loaded")
            }
        }

        /** Deterministic constructor used by JVM crypto round-trip tests. */
        fun forTesting(seed: ByteArray): AirPlayCrypto {
            require(seed.size == 32)
            return AirPlayCrypto(seed.copyOf())
        }
    }

    val serverEd25519Priv: Ed25519PrivateKeyParameters = Ed25519PrivateKeyParameters(identitySeed, 0)
    val serverEd25519Pub: Ed25519PublicKeyParameters = serverEd25519Priv.generatePublicKey()
    val publicKeyBytes: ByteArray = serverEd25519Pub.encoded
    val publicKeyHex: String = publicKeyBytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** Create a distinct verification state for EACH client TCP session. */
    fun newPairVerifySession(): PairVerifySession = PairVerifySession()

    inner class PairVerifySession {
        private var verifyState: VerifyState? = null

        fun handleStage1(payload: ByteArray): ByteArray? {
            if (payload.size != 68 || payload[0] != 1.toByte() ||
                payload[1] != 0.toByte() || payload[2] != 0.toByte() || payload[3] != 0.toByte()
            ) {
                Log.w(TAG, "Rejected malformed pair-verify Stage 1")
                return null
            }
            verifyState = null
            return try {
                val clientXBytes = payload.copyOfRange(4, 36)
                val clientEdBytes = payload.copyOfRange(36, 68)
                val clientXKey = X25519PublicKeyParameters(clientXBytes, 0)
                val generator = X25519KeyPairGenerator()
                generator.init(X25519KeyGenerationParameters(SecureRandom()))
                val pair = generator.generateKeyPair()
                val serverPrivate = pair.private as X25519PrivateKeyParameters
                val serverPublic = (pair.public as X25519PublicKeyParameters).encoded
                val sharedSecret = ByteArray(32)
                serverPrivate.generateSecret(clientXKey, sharedSecret, 0)
                val aesKey = derive("Pair-Verify-AES-Key", sharedSecret)
                val aesIv = derive("Pair-Verify-AES-IV", sharedSecret)
                sharedSecret.fill(0)

                val signature = sign(serverPublic + clientXBytes)
                val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(aesIv))
                val encryptedSignature = cipher.doFinal(signature)
                verifyState = VerifyState(clientXBytes, clientEdBytes, serverPublic, aesKey, aesIv)
                serverPublic + encryptedSignature
            } catch (e: Exception) {
                verifyState = null
                Log.w(TAG, "Pair-verify Stage 1 failed: ${e.javaClass.simpleName}")
                null
            }
        }

        fun handleStage2(payload: ByteArray): Boolean {
            val state = verifyState ?: return false
            verifyState = null // One attempt per Stage 1; never reuse a completed session.
            if (payload.size != 68 || payload.sliceArray(0..3).any { it != 0.toByte() }) return false
            return try {
                val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(state.aesKey, "AES"), IvParameterSpec(state.aesIv))
                val decryptedSignature = cipher.doFinal(payload.copyOfRange(4, 68))
                val verifier = Ed25519Signer()
                verifier.init(false, Ed25519PublicKeyParameters(state.clientEdPublic, 0))
                val message = state.clientXPublic + state.serverXPublic
                verifier.update(message, 0, message.size)
                verifier.verifySignature(decryptedSignature)
            } catch (e: Exception) {
                Log.w(TAG, "Pair-verify Stage 2 failed: ${e.javaClass.simpleName}")
                false
            } finally {
                state.aesKey.fill(0)
                state.aesIv.fill(0)
            }
        }

        private fun sign(message: ByteArray): ByteArray {
            val signer = Ed25519Signer()
            signer.init(true, serverEd25519Priv)
            signer.update(message, 0, message.size)
            return signer.generateSignature()
        }

        private fun derive(label: String, secret: ByteArray): ByteArray {
            val digest = MessageDigest.getInstance("SHA-512")
            digest.update(label.toByteArray(StandardCharsets.UTF_8))
            digest.update(secret)
            return digest.digest().copyOfRange(0, 16)
        }
    }

    private data class VerifyState(
        val clientXPublic: ByteArray,
        val clientEdPublic: ByteArray,
        val serverXPublic: ByteArray,
        val aesKey: ByteArray,
        val aesIv: ByteArray
    )
}

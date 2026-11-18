package com.carcast.mirror

import com.carcast.mirror.engine.airplay.AirPlayCrypto
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.*
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AirPlayPairVerifyTest {
    @Test fun fullRoundTripAndSessionIsolation() {
        val crypto = AirPlayCrypto.forTesting(ByteArray(32) { it.toByte() })
        val random = SecureRandom()
        val xgen = X25519KeyPairGenerator().apply { init(X25519KeyGenerationParameters(random)) }
        val xpair = xgen.generateKeyPair()
        val clientXPriv = xpair.private as X25519PrivateKeyParameters
        val clientXPub = (xpair.public as X25519PublicKeyParameters).encoded
        val edgen = Ed25519KeyPairGenerator().apply { init(Ed25519KeyGenerationParameters(random)) }
        val edpair = edgen.generateKeyPair()
        val clientEdPriv = edpair.private as Ed25519PrivateKeyParameters
        val clientEdPub = (edpair.public as Ed25519PublicKeyParameters).encoded

        val session = crypto.newPairVerifySession()
        val stage1 = session.handleStage1(byteArrayOf(1, 0, 0, 0) + clientXPub + clientEdPub)
        assertNotNull(stage1)
        assertEquals(96, stage1!!.size)
        val serverXPub = stage1.copyOfRange(0, 32)

        val secret = ByteArray(32)
        clientXPriv.generateSecret(X25519PublicKeyParameters(serverXPub, 0), secret, 0)
        fun derive(label: String): ByteArray = MessageDigest.getInstance("SHA-512")
            .digest(label.toByteArray(Charsets.UTF_8) + secret).copyOfRange(0, 16)
        val key = derive("Pair-Verify-AES-Key")
        val iv = derive("Pair-Verify-AES-IV")
        fun aes(mode: Int): Cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }

        val serverSignature = aes(Cipher.DECRYPT_MODE).doFinal(stage1.copyOfRange(32, 96))
        val checker = Ed25519Signer()
        checker.init(false, crypto.serverEd25519Pub)
        val challenge = serverXPub + clientXPub
        checker.update(challenge, 0, challenge.size)
        assertTrue("Server must prove possession of identity key", checker.verifySignature(serverSignature))

        val signer = Ed25519Signer()
        signer.init(true, clientEdPriv)
        val clientChallenge = clientXPub + serverXPub
        signer.update(clientChallenge, 0, clientChallenge.size)
        val stage2 = byteArrayOf(0, 0, 0, 0) + aes(Cipher.ENCRYPT_MODE).doFinal(signer.generateSignature())
        assertTrue(session.handleStage2(stage2))
        assertFalse("Stage 2 replay must fail", session.handleStage2(stage2))
        assertFalse("No shared mutable state across clients", crypto.newPairVerifySession().handleStage2(stage2))
    }
}

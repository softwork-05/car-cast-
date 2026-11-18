package com.carcast.mirror

import com.carcast.mirror.engine.airplay.AirPlayCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayCryptoTest {

    @Test
    fun testEd25519KeyAndPairVerifyStage1() {
        val crypto = AirPlayCrypto.forTesting(ByteArray(32) { (it + 1).toByte() })
        assertNotNull(crypto.serverEd25519Priv)
        assertNotNull(crypto.serverEd25519Pub)
        assertEquals(32, crypto.publicKeyBytes.size)
        assertEquals(64, crypto.publicKeyHex.length)

        // Mock 68-byte client pair-verify stage 1 packet from iOS
        val clientPacket = ByteArray(68)
        clientPacket[0] = 1 // Opcode 0x01
        for (i in 4 until 36) clientPacket[i] = (i and 0xFF).toByte() // Mock client X25519 pub
        for (i in 36 until 68) clientPacket[i] = ((i * 2) and 0xFF).toByte() // Mock client Ed25519 pub

        val session = crypto.newPairVerifySession()
        val response = session.handleStage1(clientPacket)
        assertNotNull(response)
        assertEquals(96, response!!.size) // 32 bytes Server X25519 + 64 bytes Ed25519 signature
    }

    @Test
    fun testBinaryPropertyListGeneration() {
        val crypto = AirPlayCrypto.forTesting(ByteArray(32) { (it + 1).toByte() })
        val dict = com.dd.plist.NSDictionary().apply {
            put("deviceid", "A4:C1:38:E1:B9:20")
            put("features", com.dd.plist.NSNumber(1384054502L))
            put("model", "AppleTV3,2")
            put("protovers", "1.0")
            put("srcvers", "220.68")
            put("statusFlags", com.dd.plist.NSNumber(68))
            put("pi", "b08ab08a-02c0-4820-9426-0e1371800a72")
            put("pk", com.dd.plist.NSData(crypto.publicKeyBytes))
        }

        val bplist = com.dd.plist.BinaryPropertyListWriter.writeToArray(dict)
        assertNotNull(bplist)
        assertTrue(bplist.size > 20)
        // Verify bplist00 magic bytes
        val magic = String(bplist, 0, 8, java.nio.charset.StandardCharsets.US_ASCII)
        assertEquals("bplist00", magic)
    }
}

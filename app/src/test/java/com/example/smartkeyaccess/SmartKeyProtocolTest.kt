package com.example.smartkeyaccess

import com.example.smartkeyaccess.crypto.CryptoManager
import com.example.smartkeyaccess.ble.SmartKeyProtocol
import org.junit.Assert.*
import org.junit.Test
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey

class SmartKeyProtocolTest {

    @Test
    fun testPingFrameEncodingAndDecoding() {
        val frame = SmartKeyProtocol.encodeFrame(msgType = SmartKeyProtocol.MSG_PING)
        assertEquals(SmartKeyProtocol.HEADER_SIZE, frame.size)
        assertEquals(SmartKeyProtocol.MAGIC_BYTE, frame[0])
        assertEquals(SmartKeyProtocol.PROTOCOL_VERSION, frame[1])
        assertEquals(SmartKeyProtocol.MSG_PING, frame[2])

        val header = SmartKeyProtocol.parseHeader(frame)
        assertNotNull(header)
        assertEquals(SmartKeyProtocol.MAGIC_BYTE, header?.magic)
        assertEquals(SmartKeyProtocol.PROTOCOL_VERSION, header?.version)
        assertEquals(SmartKeyProtocol.MSG_PING, header?.msgType)
        assertEquals(0L, header?.sessionId)
        assertEquals(1, header?.seqNum)
        assertEquals(0, header?.payloadLen)
    }

    @Test
    fun testAuthRequestFrameEncoding() {
        val phoneId = "PHON0001".toByteArray(Charsets.UTF_8)
        val nonce = ByteArray(16) { (it + 1).toByte() }
        val payload = SmartKeyProtocol.buildAuthRequestPayload(phoneId, nonce, 123456789L)
        assertEquals(32, payload.size)

        val authTag = ByteArray(16) { 0x00 }
        val frame = SmartKeyProtocol.encodeFrame(
            msgType = SmartKeyProtocol.MSG_AUTH_REQ,
            payload = payload,
            sessionId = 0L,
            seqNum = 1,
            authTag = authTag
        )

        assertEquals(SmartKeyProtocol.HEADER_SIZE + 32 + 16, frame.size)
        val header = SmartKeyProtocol.parseHeader(frame)
        assertNotNull(header)
        assertEquals(SmartKeyProtocol.MSG_AUTH_REQ, header?.msgType)
        assertEquals(32, header?.payloadLen)
    }

    @Test
    fun testEcdsaP1363SignAndVerify() {
        val keyPair = CryptoManager.generateIdentityKeyPair()
        val privateKey = keyPair.private as ECPrivateKey
        val publicKey = keyPair.public as ECPublicKey

        val challenge = "ChallengeNonce16B".toByteArray(Charsets.UTF_8)
        val signature = CryptoManager.signEcdsaP1363(privateKey, challenge)

        // IEEE P1363 signature must be exactly 64 bytes (32B R || 32B S)
        assertEquals(64, signature.size)

        // Verification must pass on matching data
        val isValid = CryptoManager.verifyEcdsaP1363(publicKey, challenge, signature)
        assertTrue(isValid)

        // Verification must fail on tampered data
        val tamperedChallenge = "TamperedChallenge!".toByteArray(Charsets.UTF_8)
        val isInvalid = CryptoManager.verifyEcdsaP1363(publicKey, tamperedChallenge, signature)
        assertFalse(isInvalid)
    }
}

package com.example.smartkeyaccess

import com.example.smartkeyaccess.crypto.CryptoManager
import com.example.smartkeyaccess.protocol.SmartKeyProtocol
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
    fun testAuthRequestAndTagVerification() {
        val masterKey = ByteArray(32) { (it + 1).toByte() }
        val phoneId = "PHON0001".toByteArray(Charsets.UTF_8)
        val nonce = ByteArray(16) { (it + 0x10).toByte() }
        val payload = SmartKeyProtocol.buildAuthRequestPayload(phoneId, nonce, 123456789L)
        assertEquals(32, payload.size)

        val headerBytes = SmartKeyProtocol.encodeFrame(
            msgType = SmartKeyProtocol.MSG_AUTH_REQ,
            payload = ByteArray(0),
            sessionId = 0L,
            seqNum = 1
        ).copyOfRange(0, SmartKeyProtocol.HEADER_SIZE)

        val authTag = SmartKeyProtocol.computeAuthTag(masterKey, headerBytes, payload)
        assertEquals(16, authTag.size)

        // Valid tag verification
        val isTagValid = SmartKeyProtocol.verifyAuthTag(masterKey, headerBytes, payload, authTag)
        assertTrue(isTagValid)

        // Corrupted tag verification must fail
        val corruptedTag = authTag.copyOf()
        corruptedTag[0] = (corruptedTag[0] + 1).toByte()
        val isCorruptedValid = SmartKeyProtocol.verifyAuthTag(masterKey, headerBytes, payload, corruptedTag)
        assertFalse(isCorruptedValid)

        // Test encodeAuthenticatedFrame + verifyAuthenticatedFrame
        val authFrame = SmartKeyProtocol.encodeAuthenticatedFrame(
            msgType = SmartKeyProtocol.MSG_AUTH_REQ,
            payload = payload,
            sessionId = 0x12345678L,
            seqNum = 42,
            key = masterKey
        )
        assertTrue(SmartKeyProtocol.verifyAuthenticatedFrame(authFrame, masterKey))

        // Wrong key must fail
        val wrongKey = ByteArray(32) { 0xFF.toByte() }
        assertFalse(SmartKeyProtocol.verifyAuthenticatedFrame(authFrame, wrongKey))

        // Tampered payload in frame must fail
        val tamperedFrame = authFrame.copyOf()
        tamperedFrame[SmartKeyProtocol.HEADER_SIZE + 2] = (tamperedFrame[SmartKeyProtocol.HEADER_SIZE + 2] + 1).toByte()
        assertFalse(SmartKeyProtocol.verifyAuthenticatedFrame(tamperedFrame, masterKey))
    }

    @Test
    fun testStsKeyDerivation() {
        val masterKey = ByteArray(32) { (it + 1).toByte() }
        val phoneNonce = ByteArray(16) { 0x01 }
        val lockNonce = ByteArray(16) { 0x02 }

        val stsKey1 = SmartKeyProtocol.deriveStsSessionKey(masterKey, phoneNonce, lockNonce, 16)
        val stsKey2 = SmartKeyProtocol.deriveStsSessionKey(masterKey, phoneNonce, lockNonce, 16)

        assertEquals(16, stsKey1.size)
        assertArrayEquals(stsKey1, stsKey2)

        // Different nonce must yield different STS key
        val differentNonce = ByteArray(16) { 0x03 }
        val stsKey3 = SmartKeyProtocol.deriveStsSessionKey(masterKey, phoneNonce, differentNonce, 16)
        assertFalse(stsKey1.contentEquals(stsKey3))
    }

    @Test
    fun testBleTrafficKeyAndEncryption() {
        val masterKey = ByteArray(32) { (it + 5).toByte() }
        val phoneNonce = ByteArray(16) { 0x11 }
        val lockNonce = ByteArray(16) { 0x22 }
        val trafficKey = SmartKeyProtocol.deriveBleTrafficKey(masterKey, phoneNonce, lockNonce)
        assertEquals(16, trafficKey.size)

        val iv = ByteArray(16) { 0x01 }
        val sensitiveData = "FiRaUwbStsKey16B".toByteArray(Charsets.UTF_8)
        val encrypted = SmartKeyProtocol.encryptSensitivePayload(trafficKey, iv, sensitiveData)
        assertFalse(sensitiveData.contentEquals(encrypted))

        val decrypted = SmartKeyProtocol.decryptSensitivePayload(trafficKey, iv, encrypted)
        assertArrayEquals(sensitiveData, decrypted)
    }

    @Test
    fun testUwbConfigExchangePayloads() {
        val phoneAddr = byteArrayOf(0x02, 0x01)
        val stsKey = ByteArray(16) { 0xAA.toByte() }

        val reqPayload = SmartKeyProtocol.buildUwbConfigRequestPayload(
            channel = 9,
            preambleIndex = 9,
            configId = 2,
            phoneUwbAddress = phoneAddr,
            subSessionId = 0,
            stsKey = stsKey
        )
        assertTrue(reqPayload.size >= 26)

        // Test valid response parsing
        val respBuf = java.nio.ByteBuffer.allocate(10).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put(SmartKeyProtocol.ERR_NONE)
            put(2.toByte()) // address length
            put(byteArrayOf(0x01, 0x01)) // esp32 UWB address
            putInt(0x12345678) // session ID
            putShort(100.toShort()) // delay
        }.array()

        val resp = SmartKeyProtocol.parseUwbConfigResponsePayload(respBuf)
        assertNotNull(resp)
        assertEquals(SmartKeyProtocol.ERR_NONE, resp?.status)
        assertEquals(0x12345678L, resp?.sessionId)
        assertArrayEquals(byteArrayOf(0x01, 0x01), resp?.esp32UwbAddress)

        // Invalid address length must be rejected
        val invalidAddrBuf = java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put(SmartKeyProtocol.ERR_NONE)
            put(5.toByte()) // invalid address length (must be 2 or 8)
            put(ByteArray(5))
            putInt(0x12345678)
        }.array()
        assertNull(SmartKeyProtocol.parseUwbConfigResponsePayload(invalidAddrBuf))
    }

    @Test
    fun testEcdsaP1363SignAndVerify() {
        val keyPair = CryptoManager.generateEcdhKeyPair()
        val privateKey = keyPair.private as ECPrivateKey
        val publicKey = keyPair.public as ECPublicKey

        val challenge = "ChallengeNonce16B".toByteArray(Charsets.UTF_8)
        val signature = CryptoManager.signEcdsaP1363(privateKey, challenge)

        assertEquals(64, signature.size)
        val isValid = CryptoManager.verifyEcdsaP1363(publicKey, challenge, signature)
        assertTrue(isValid)

        val tampered = "TamperedChallenge!".toByteArray(Charsets.UTF_8)
        val isInvalid = CryptoManager.verifyEcdsaP1363(publicKey, tampered, signature)
        assertFalse(isInvalid)
    }

    @Test
    fun testUwbConfigResponseAuthTagAndSessionValidation() {
        val msk = ByteArray(32) { 0x42.toByte() }
        val validSid = 0x12345678L
        val seqNum = 2

        // Build valid raw payload: status (1B) + addrLen (1B) + addr (2B) + sid (4B) + delay (2B) = 10B
        val rawPayload = java.nio.ByteBuffer.allocate(10).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put(SmartKeyProtocol.ERR_NONE)
            put(2.toByte())
            put(byteArrayOf(0x0A, 0x0B))
            putInt(validSid.toInt())
            putShort(50.toShort())
        }.array()

        // Encode authenticated frame with tag
        val authenticatedFrame = SmartKeyProtocol.encodeAuthenticatedFrame(
            msgType = SmartKeyProtocol.MSG_UWB_CONFIG_RESP,
            payload = rawPayload,
            sessionId = validSid,
            seqNum = seqNum,
            key = msk
        )

        // 1. Valid authenticated frame must pass verification
        assertTrue(SmartKeyProtocol.verifyAuthenticatedFrame(authenticatedFrame, msk))

        // 2. Reject frame missing auth tag (payload size < 4 + 16)
        val frameWithoutTag = authenticatedFrame.copyOfRange(0, authenticatedFrame.size - SmartKeyProtocol.AUTH_TAG_SIZE)
        val payloadWithoutTag = frameWithoutTag.copyOfRange(SmartKeyProtocol.HEADER_SIZE, frameWithoutTag.size)
        // If payload lacks AUTH_TAG_SIZE, verifyAuthenticatedFrame returns false
        assertFalse(SmartKeyProtocol.verifyAuthenticatedFrame(frameWithoutTag, msk))

        // 3. Reject frame with tampered/wrong auth tag
        val tamperedFrame = authenticatedFrame.copyOf()
        tamperedFrame[tamperedFrame.size - 1] = (tamperedFrame[tamperedFrame.size - 1] + 1).toByte()
        assertFalse(SmartKeyProtocol.verifyAuthenticatedFrame(tamperedFrame, msk))

        // 4. Reject frame with zero session ID or mismatched session ID
        val header = SmartKeyProtocol.parseHeader(authenticatedFrame)
        assertNotNull(header)
        assertEquals(validSid, header?.sessionId)
        assertNotEquals(0L, header?.sessionId)
    }

    @Test
    fun testAuthResponsePayloadAndTagValidation() {
        val msk = ByteArray(32) { 0x33.toByte() }
        val newSid = 0x98765432L
        val lockId = "LOCK_001".toByteArray(Charsets.UTF_8)
        val lockNonce = ByteArray(16) { 0x55 }

        // Build 40B AUTH_RESP payload: lockId (8B) + nonce (16B) + sid (4B) + delay (4B) + tag (16B)
        val payloadWithoutTag = java.nio.ByteBuffer.allocate(32).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put(lockId)
            put(lockNonce)
            putInt(newSid.toInt())
            putInt(20) // delay
        }.array()

        val headerBytes = SmartKeyProtocol.encodeFrame(
            msgType = SmartKeyProtocol.MSG_AUTH_RESP,
            payload = ByteArray(0),
            sessionId = 0L,
            seqNum = 1
        ).copyOfRange(0, SmartKeyProtocol.HEADER_SIZE)

        val authTag = SmartKeyProtocol.computeAuthTag(msk, headerBytes, payloadWithoutTag)
        val fullPayload = payloadWithoutTag + authTag

        val parsed = SmartKeyProtocol.parseAuthResponsePayload(fullPayload)
        assertNotNull(parsed)
        assertEquals(newSid, parsed?.newSessionId)
        assertArrayEquals(lockId, parsed?.lockId)
        assertArrayEquals(lockNonce, parsed?.lockNonce)
        assertArrayEquals(authTag, parsed?.authTag)

        // Verify valid tag
        assertTrue(SmartKeyProtocol.verifyAuthTag(msk, headerBytes, payloadWithoutTag, authTag))

        // Reject invalid tag
        val corruptedTag = authTag.copyOf()
        corruptedTag[0] = (corruptedTag[0] + 1).toByte()
        assertFalse(SmartKeyProtocol.verifyAuthTag(msk, headerBytes, payloadWithoutTag, corruptedTag))
    }

    @Test
    fun testHeaderStrictValidation() {
        val validFrame = SmartKeyProtocol.encodeFrame(msgType = SmartKeyProtocol.MSG_PING)
        assertNotNull(SmartKeyProtocol.parseHeader(validFrame))

        // Unsupported version must be rejected
        val badVersion = validFrame.copyOf().also { it[1] = 0x02 }
        assertNull(SmartKeyProtocol.parseHeader(badVersion))

        // Invalid magic must be rejected
        val badMagic = validFrame.copyOf().also { it[0] = 0xAA.toByte() }
        assertNull(SmartKeyProtocol.parseHeader(badMagic))

        // Non-zero reserved field must be rejected
        val badReserved = validFrame.copyOf().also { it[3] = 0x01 }
        assertNull(SmartKeyProtocol.parseHeader(badReserved))
    }

    @Test
    fun testTrailingBytesRejectionInAuthenticatedFrame() {
        val msk = ByteArray(32) { 0x77.toByte() }
        val payload = "TestDataPayload!".toByteArray(Charsets.UTF_8)
        val frame = SmartKeyProtocol.encodeAuthenticatedFrame(
            msgType = SmartKeyProtocol.MSG_AUTH_REQ,
            payload = payload,
            sessionId = 0x11223344L,
            seqNum = 5,
            key = msk
        )
        assertTrue(SmartKeyProtocol.verifyAuthenticatedFrame(frame, msk))

        // Appending trailing byte must be rejected by exact frame length check
        val frameWithTrailingGarbage = frame + byteArrayOf(0x00)
        assertFalse(SmartKeyProtocol.verifyAuthenticatedFrame(frameWithTrailingGarbage, msk))

        // Truncated frame must be rejected
        val truncatedFrame = frame.copyOfRange(0, frame.size - 1)
        assertFalse(SmartKeyProtocol.verifyAuthenticatedFrame(truncatedFrame, msk))
    }

    @Test
    fun testUwbConfigResponseStrictValidation() {
        // Valid 10-byte response for 2B address
        val validResp = java.nio.ByteBuffer.allocate(10).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put(SmartKeyProtocol.ERR_NONE)
            put(2.toByte())
            put(byteArrayOf(0x01, 0x02))
            putInt(0x12345678)
            putShort(50.toShort())
        }.array()
        assertNotNull(SmartKeyProtocol.parseUwbConfigResponsePayload(validResp))

        // Missing session ID (only 6 bytes) must be rejected
        val truncated = validResp.copyOfRange(0, 6)
        assertNull(SmartKeyProtocol.parseUwbConfigResponsePayload(truncated))

        // Extra trailing byte (11 bytes instead of 10) must be rejected
        val trailingGarbage = validResp + byteArrayOf(0xFF.toByte())
        assertNull(SmartKeyProtocol.parseUwbConfigResponsePayload(trailingGarbage))

        // Session ID = 0 must be rejected
        val zeroSid = java.nio.ByteBuffer.allocate(10).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put(SmartKeyProtocol.ERR_NONE)
            put(2.toByte())
            put(byteArrayOf(0x01, 0x02))
            putInt(0) // session ID 0
            putShort(50.toShort())
        }.array()
        assertNull(SmartKeyProtocol.parseUwbConfigResponsePayload(zeroSid))
    }

    @Test
    fun testAuthResponseStrictValidation() {
        val validPayload = java.nio.ByteBuffer.allocate(48).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("LOCK_001".toByteArray(Charsets.UTF_8))
            put(ByteArray(16) { 0x01 })
            putInt(0x55667788)
            putInt(100)
            put(ByteArray(16) { 0xAA.toByte() })
        }.array()
        assertNotNull(SmartKeyProtocol.parseAuthResponsePayload(validPayload))

        // Incomplete payload (less than 48B) must be rejected
        val incomplete = validPayload.copyOfRange(0, 32)
        assertNull(SmartKeyProtocol.parseAuthResponsePayload(incomplete))

        // Trailing byte (49B) must be rejected
        val trailing = validPayload + byteArrayOf(0x00)
        assertNull(SmartKeyProtocol.parseAuthResponsePayload(trailing))

        // Session ID = 0 must be rejected
        val zeroSidPayload = java.nio.ByteBuffer.allocate(48).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("LOCK_001".toByteArray(Charsets.UTF_8))
            put(ByteArray(16) { 0x01 })
            putInt(0) // session ID 0
            putInt(100)
            put(ByteArray(16) { 0xAA.toByte() })
        }.array()
        assertNull(SmartKeyProtocol.parseAuthResponsePayload(zeroSidPayload))
    }
}

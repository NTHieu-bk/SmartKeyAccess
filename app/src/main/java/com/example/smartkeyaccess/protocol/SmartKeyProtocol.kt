package com.example.smartkeyaccess.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// Application protocol framing for Smart Key messages (aligned with firmware smartkey_protocol.h)
object SmartKeyProtocol {

    const val MAGIC_BYTE: Byte = 0xA5.toByte()
    const val PROTOCOL_VERSION: Byte = 0x01
    const val HEADER_SIZE = 12
    const val AUTH_TAG_SIZE = 16
    const val DEVICE_ID_SIZE = 8
    const val NONCE_SIZE = 16

    // Message types
    const val MSG_PING: Byte = 0x01
    const val MSG_PONG: Byte = 0x02
    const val MSG_AUTH_REQ: Byte = 0x10
    const val MSG_AUTH_RESP: Byte = 0x11
    const val MSG_UWB_CONFIG_REQ: Byte = 0x20
    const val MSG_UWB_CONFIG_RESP: Byte = 0x21
    const val MSG_STATUS_IND: Byte = 0x30
    const val MSG_ERROR_IND: Byte = 0x3F

    // Error codes
    const val ERR_NONE: Byte = 0x00
    const val ERR_INVALID_MAGIC: Byte = 0x01
    const val ERR_UNSUPPORTED_VERSION: Byte = 0x02
    const val ERR_UNKNOWN_MSG_TYPE: Byte = 0x03
    const val ERR_STALE_NONCE: Byte = 0x04
    const val ERR_AUTH_TAG_MISMATCH: Byte = 0x05
    const val ERR_SESSION_INVALID: Byte = 0x06
    const val ERR_STS_CONFIG_FAIL: Byte = 0x07
    const val ERR_UWB_RANGING_TIMEOUT: Byte = 0x08
    const val ERR_POLICY_REJECT_DISTANCE: Byte = 0x09
    const val ERR_POLICY_REJECT_QUALITY: Byte = 0x0A
    const val ERR_POLICY_FAIL_CLOSED: Byte = 0x0B
    const val ERR_INTERNAL_FAULT: Byte = 0xFF.toByte()

    data class Header(
        val magic: Byte,
        val version: Byte,
        val msgType: Byte,
        val reserved: Byte,
        val sessionId: Long,
        val seqNum: Int,
        val payloadLen: Int
    )

    data class AuthResponse(
        val lockId: ByteArray,
        val lockNonce: ByteArray,
        val newSessionId: Long,
        val timestampMs: Long,
        val authTag: ByteArray
    )

    data class UwbConfigResponse(
        val status: Byte,
        val esp32UwbAddress: ByteArray,
        val sessionId: Long,
        val syncDelayMs: Short
    ) {
        // Backward compatibility property
        val anchorId: Byte
            get() = if (esp32UwbAddress.isNotEmpty()) esp32UwbAddress.last() else 0x00
    }

    // Encode protocol frame: Header (12B) + Payload + optional Auth Tag (16B)
    fun encodeFrame(
        msgType: Byte,
        payload: ByteArray = ByteArray(0),
        sessionId: Long = 0L,
        seqNum: Int = 1,
        authTag: ByteArray? = null
    ): ByteArray {
        val totalLen = HEADER_SIZE + payload.size + (authTag?.size ?: 0)
        val buffer = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN)

        buffer.put(MAGIC_BYTE)
        buffer.put(PROTOCOL_VERSION)
        buffer.put(msgType)
        buffer.put(0x00.toByte())
        buffer.putInt((sessionId and 0xFFFFFFFFL).toInt())
        buffer.putShort((seqNum and 0xFFFF).toShort())
        buffer.putShort((payload.size and 0xFFFF).toShort())

        if (payload.isNotEmpty()) buffer.put(payload)
        authTag?.let { buffer.put(it) }

        return buffer.array()
    }

    // Encode authenticated frame ensuring header fields (payloadLen, seqNum, sessionId) match HMAC input exactly
    fun encodeAuthenticatedFrame(
        msgType: Byte,
        payload: ByteArray = ByteArray(0),
        sessionId: Long = 0L,
        seqNum: Int = 1,
        key: ByteArray
    ): ByteArray {
        val headerBytes = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(MAGIC_BYTE)
            put(PROTOCOL_VERSION)
            put(msgType)
            put(0x00.toByte())
            putInt((sessionId and 0xFFFFFFFFL).toInt())
            putShort((seqNum and 0xFFFF).toShort())
            putShort((payload.size and 0xFFFF).toShort())
        }.array()

        val authTag = computeAuthTag(key, headerBytes, payload)
        return encodeFrame(
            msgType = msgType,
            payload = payload,
            sessionId = sessionId,
            seqNum = seqNum,
            authTag = authTag
        )
    }

    // Parse 12-byte header. Returns null if data is too short, magic != 0xA5, unsupported version, or reserved != 0
    fun parseHeader(data: ByteArray): Header? {
        if (data.size < HEADER_SIZE) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        val magic = buf.get()
        if (magic != MAGIC_BYTE) return null

        val version = buf.get()
        if (version != PROTOCOL_VERSION) return null

        val msgType = buf.get()
        val reserved = buf.get()
        if (reserved != 0.toByte()) return null

        val sessionId = buf.int.toLong() and 0xFFFFFFFFL
        val seqNum = buf.short.toInt() and 0xFFFF
        val payloadLen = buf.short.toInt() and 0xFFFF

        return Header(magic, version, msgType, reserved, sessionId, seqNum, payloadLen)
    }

    // Compute HMAC-SHA256 authentication tag (16 bytes) across header and payload
    fun computeAuthTag(key: ByteArray, headerBytes: ByteArray, payloadBytes: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update(headerBytes)
        mac.update(payloadBytes)
        return mac.doFinal().copyOf(AUTH_TAG_SIZE)
    }

    // Verify auth tag in constant-time
    fun verifyAuthTag(key: ByteArray, headerBytes: ByteArray, payloadBytes: ByteArray, expectedTag: ByteArray): Boolean {
        if (expectedTag.size != AUTH_TAG_SIZE) return false
        val computed = computeAuthTag(key, headerBytes, payloadBytes)
        return MessageDigest.isEqual(computed, expectedTag)
    }

    // Verify complete authenticated frame (Header + Payload + 16B Tag)
    // Strictly enforces exact frame length to reject truncated frames or trailing garbage bytes
    fun verifyAuthenticatedFrame(frame: ByteArray, key: ByteArray): Boolean {
        if (frame.size < HEADER_SIZE + AUTH_TAG_SIZE) return false
        val headerBytes = frame.copyOfRange(0, HEADER_SIZE)
        val header = parseHeader(headerBytes) ?: return false
        val expectedPayloadLen = header.payloadLen
        if (frame.size != HEADER_SIZE + expectedPayloadLen + AUTH_TAG_SIZE) return false

        val payloadBytes = frame.copyOfRange(HEADER_SIZE, HEADER_SIZE + expectedPayloadLen)
        val tagBytes = frame.copyOfRange(HEADER_SIZE + expectedPayloadLen, HEADER_SIZE + expectedPayloadLen + AUTH_TAG_SIZE)
        return verifyAuthTag(key, headerBytes, payloadBytes, tagBytes)
    }

    // Derive cryptographically secure UWB STS session key from shared secret and exchanged nonces
    fun deriveStsSessionKey(
        sharedSecret: ByteArray,
        phoneNonce: ByteArray,
        lockNonce: ByteArray,
        keyLength: Int = 16
    ): ByteArray {
        val salt = phoneNonce + lockNonce
        val prkMac = Mac.getInstance("HmacSHA256")
        prkMac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = prkMac.doFinal(sharedSecret)

        val expandMac = Mac.getInstance("HmacSHA256")
        expandMac.init(SecretKeySpec(prk, "HmacSHA256"))
        expandMac.update("SmartKey-UWB-STS-v1".toByteArray(Charsets.UTF_8))
        expandMac.update(0x01.toByte())
        val okm = expandMac.doFinal()
        return okm.copyOf(keyLength)
    }

    // Derive a dedicated BLE traffic encryption key to protect sensitive payload fields
    fun deriveBleTrafficKey(
        sharedSecret: ByteArray,
        phoneNonce: ByteArray,
        lockNonce: ByteArray
    ): ByteArray {
        val salt = phoneNonce + lockNonce
        val prkMac = Mac.getInstance("HmacSHA256")
        prkMac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = prkMac.doFinal(sharedSecret)

        val expandMac = Mac.getInstance("HmacSHA256")
        expandMac.init(SecretKeySpec(prk, "HmacSHA256"))
        expandMac.update("SmartKey-BLE-ENC-v1".toByteArray(Charsets.UTF_8))
        expandMac.update(0x01.toByte())
        val okm = expandMac.doFinal()
        return okm.copyOf(16)
    }

    // Derive a dedicated HMAC authentication key (32 bytes) for START UWB verification
    fun deriveHmacKey(
        sharedSecret: ByteArray,
        phoneNonce: ByteArray,
        lockNonce: ByteArray
    ): ByteArray {
        val salt = phoneNonce + lockNonce
        val prkMac = Mac.getInstance("HmacSHA256")
        prkMac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = prkMac.doFinal(sharedSecret)

        val expandMac = Mac.getInstance("HmacSHA256")
        expandMac.init(SecretKeySpec(prk, "HmacSHA256"))
        expandMac.update("smartkey/hmac".toByteArray(Charsets.UTF_8))
        expandMac.update(0x01.toByte())
        val okm = expandMac.doFinal()
        return okm.copyOf(32)
    }

    // Encrypt sensitive key material using AES-128-CTR with deterministic nonce
    fun encryptSensitivePayload(key: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv.copyOf(16))
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        return cipher.doFinal(plaintext)
    }

    // Decrypt sensitive key material using AES-128-CTR with deterministic nonce
    fun decryptSensitivePayload(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv.copyOf(16))
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        return cipher.doFinal(ciphertext)
    }

    // Build AUTH_REQ payload: phone_id (8B) + phone_nonce (16B) + timestamp_ms (8B)
    fun buildAuthRequestPayload(
        phoneId: ByteArray,
        phoneNonce: ByteArray,
        timestampMs: Long = System.currentTimeMillis()
    ): ByteArray {
        val buf = ByteBuffer.allocate(DEVICE_ID_SIZE + NONCE_SIZE + 8).order(ByteOrder.LITTLE_ENDIAN)

        val cleanPhoneId = ByteArray(DEVICE_ID_SIZE)
        System.arraycopy(phoneId, 0, cleanPhoneId, 0, minOf(phoneId.size, DEVICE_ID_SIZE))
        buf.put(cleanPhoneId)

        val cleanNonce = ByteArray(NONCE_SIZE)
        System.arraycopy(phoneNonce, 0, cleanNonce, 0, minOf(phoneNonce.size, NONCE_SIZE))
        buf.put(cleanNonce)

        buf.putLong(timestampMs)
        return buf.array()
    }

    // Parse AUTH_RESP payload: lock_id (8B) + lock_nonce (16B) + new_session_id (4B) + timestamp_ms (4B) + tag (16B) = 48B
    fun parseAuthResponsePayload(payloadAndTag: ByteArray): AuthResponse? {
        val expectedSize = DEVICE_ID_SIZE + NONCE_SIZE + 4 + 4 + AUTH_TAG_SIZE
        if (payloadAndTag.size != expectedSize) return null
        val buf = ByteBuffer.wrap(payloadAndTag).order(ByteOrder.LITTLE_ENDIAN)

        val lockId = ByteArray(DEVICE_ID_SIZE)
        buf.get(lockId)

        val lockNonce = ByteArray(NONCE_SIZE)
        buf.get(lockNonce)

        val newSessionId = buf.int.toLong() and 0xFFFFFFFFL
        if (newSessionId == 0L) return null

        val timestampMs = buf.int.toLong() and 0xFFFFFFFFL

        val tag = ByteArray(AUTH_TAG_SIZE)
        buf.get(tag)

        return AuthResponse(lockId, lockNonce, newSessionId, timestampMs, tag)
    }

    // Build UWB_CONFIG_REQ payload with local phone UWB address
    fun buildUwbConfigRequestPayload(
        channel: Int,
        preambleIndex: Int,
        configId: Int,
        phoneUwbAddress: ByteArray,
        subSessionId: Int = 0,
        stsKey: ByteArray,
        roundPeriodMs: Short = 100
    ): ByteArray {
        val addrLen = if (phoneUwbAddress.size == 8) 8 else 2
        val totalSize = 1 + 1 + 1 + 1 + addrLen + 4 + stsKey.size + 2
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        buf.put(channel.toByte())
        buf.put(preambleIndex.toByte())
        buf.put(configId.toByte())
        buf.put(addrLen.toByte())
        if (phoneUwbAddress.size >= addrLen) {
            buf.put(phoneUwbAddress, 0, addrLen)
        } else {
            buf.put(phoneUwbAddress)
            for (i in 0 until (addrLen - phoneUwbAddress.size)) buf.put(0.toByte())
        }
        buf.putInt(subSessionId)
        buf.put(stsKey)
        buf.putShort(roundPeriodMs)

        return buf.array()
    }

    // Parse UWB_CONFIG_RESP payload: status (1B) + addrLen (1B) + esp32Addr (2B or 8B) + sessionId (4B) + syncDelayMs (2B)
    // Enforces exact payload size and non-zero session ID
    fun parseUwbConfigResponsePayload(payload: ByteArray): UwbConfigResponse? {
        if (payload.size < 10) return null
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

        val status = buf.get()
        val addrLen = buf.get().toInt() and 0xFF
        if (addrLen != 2 && addrLen != 8) return null
        if (payload.size != 1 + 1 + addrLen + 4 + 2) return null

        val esp32Addr = ByteArray(addrLen)
        buf.get(esp32Addr)

        val sessionId = buf.int.toLong() and 0xFFFFFFFFL
        if (sessionId == 0L) return null

        val syncDelay = buf.short

        return UwbConfigResponse(status, esp32Addr, sessionId, syncDelay)
    }

    fun getErrorMessage(errorCode: Byte): String = when (errorCode) {
        ERR_NONE -> "No error"
        ERR_INVALID_MAGIC -> "Invalid magic byte (not 0xA5)"
        ERR_UNSUPPORTED_VERSION -> "Unsupported protocol version"
        ERR_UNKNOWN_MSG_TYPE -> "Unknown message type"
        ERR_STALE_NONCE -> "Stale nonce (replay?)"
        ERR_AUTH_TAG_MISMATCH -> "Auth tag mismatch"
        ERR_SESSION_INVALID -> "Session invalid or expired"
        ERR_STS_CONFIG_FAIL -> "UWB STS config failed"
        ERR_UWB_RANGING_TIMEOUT -> "UWB ranging timeout"
        ERR_POLICY_REJECT_DISTANCE -> "Rejected: distance > 1.6m"
        ERR_POLICY_REJECT_QUALITY -> "Rejected: poor signal / NLOS"
        ERR_POLICY_FAIL_CLOSED -> "Fail-closed: system error"
        ERR_INTERNAL_FAULT -> "Internal hardware fault"
        else -> "Unknown error code: 0x%02X".format(errorCode)
    }
}

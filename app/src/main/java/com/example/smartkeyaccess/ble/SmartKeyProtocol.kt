package com.example.smartkeyaccess.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary frame for the later AUTH / UWB_CONFIG messages (protocol/smartkey_protocol.h).
 * Not used by the current JSON test, keep it so we do not rewrite it later.
 *
 * Header 12 bytes, little-endian:
 * [0] magic 0xA5 | [1] version | [2] msg_type | [3] reserved
 * [4..7] session_id | [8..9] seq_num | [10..11] payload_len
 * then payload (N bytes) and optional 16-byte auth tag.
 */
object SmartKeyProtocol {

    const val MAGIC_BYTE: Byte = 0xA5.toByte()
    const val PROTOCOL_VERSION: Byte = 0x01
    const val HEADER_SIZE = 12
    const val AUTH_TAG_SIZE = 16
    const val DEVICE_ID_SIZE = 8
    const val NONCE_SIZE = 16

    // smartkey_msg_type_t
    const val MSG_PING: Byte = 0x01
    const val MSG_PONG: Byte = 0x02
    const val MSG_AUTH_REQ: Byte = 0x10
    const val MSG_AUTH_RESP: Byte = 0x11
    const val MSG_UWB_CONFIG_REQ: Byte = 0x20
    const val MSG_UWB_CONFIG_RESP: Byte = 0x21
    const val MSG_STATUS_IND: Byte = 0x30
    const val MSG_ERROR_IND: Byte = 0x3F

    // smartkey_error_t
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
        val anchorId: Byte,
        val syncDelayMs: Short
    )

    /**
     * Header + payload + optional auth tag.
     */
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
        // uint32 / uint16 on ESP32 -> mask so Kotlin's signed types do not break the value
        buffer.putInt((sessionId and 0xFFFFFFFFL).toInt())
        buffer.putShort((seqNum and 0xFFFF).toShort())
        buffer.putShort((payload.size and 0xFFFF).toShort())

        if (payload.isNotEmpty()) buffer.put(payload)
        authTag?.let { buffer.put(it) }

        return buffer.array()
    }

    /**
     * Returns null if the data is shorter than 12 bytes or magic is not 0xA5
     * (so JSON / plain text from TX will not be parsed as a frame).
     */
    fun parseHeader(data: ByteArray): Header? {
        if (data.size < HEADER_SIZE) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        val magic = buf.get()
        if (magic != MAGIC_BYTE) return null

        val version = buf.get()
        val msgType = buf.get()
        val reserved = buf.get()
        val sessionId = buf.int.toLong() and 0xFFFFFFFFL
        val seqNum = buf.short.toInt() and 0xFFFF
        val payloadLen = buf.short.toInt() and 0xFFFF

        return Header(magic, version, msgType, reserved, sessionId, seqNum, payloadLen)
    }

    /**
     * AUTH_REQ payload (32B): phone_id 8B | phone_nonce 16B | timestamp_ms uint64.
     * Shorter id/nonce are zero padded, longer ones are cut.
     */
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

    /**
     * AUTH_RESP: lock_id 8B | lock_nonce 16B | new_session_id u32 | timestamp_ms u32 | tag 16B.
     * Note: timestamp here is uint32 (ESP32 millis()), not uint64 like AUTH_REQ.
     */
    fun parseAuthResponsePayload(payloadAndTag: ByteArray): AuthResponse? {
        if (payloadAndTag.size < 32) return null
        val buf = ByteBuffer.wrap(payloadAndTag).order(ByteOrder.LITTLE_ENDIAN)

        val lockId = ByteArray(DEVICE_ID_SIZE)
        buf.get(lockId)

        val lockNonce = ByteArray(NONCE_SIZE)
        buf.get(lockNonce)

        val newSessionId = buf.int.toLong() and 0xFFFFFFFFL
        val timestampMs = buf.int.toLong() and 0xFFFFFFFFL

        val tag = if (payloadAndTag.size >= 32 + AUTH_TAG_SIZE) {
            ByteArray(AUTH_TAG_SIZE).also { buf.get(it) }
        } else ByteArray(0)

        return AuthResponse(lockId, lockNonce, newSessionId, timestampMs, tag)
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

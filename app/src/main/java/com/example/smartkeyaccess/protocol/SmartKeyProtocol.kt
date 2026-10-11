package com.example.smartkeyaccess.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary protocol codec and constants for Smart Key Access over BLE.
 * Conforms strictly to protocol/smartkey_protocol.h and ESP32 firmware framing.
 *
 * Header Layout (12 bytes, Little-Endian):
 * [0]    : Magic Byte (0xA5)
 * [1]    : Protocol Version (0x01)
 * [2]    : Message Type (smartkey_msg_type_t)
 * [3]    : Reserved (0x00)
 * [4..7] : Session ID (uint32, 4 bytes)
 * [8..9] : Sequence Number (uint16, 2 bytes)
 * [10..11]: Payload Length (uint16, 2 bytes)
 * Followed by:
 * [12..12+N-1]: Payload (N bytes)
 * [12+N..12+N+15]: Auth Tag (Optional / 16 bytes for authenticated packets)
 */
object SmartKeyProtocol {

    const val MAGIC_BYTE: Byte = 0xA5.toByte()
    const val PROTOCOL_VERSION: Byte = 0x01
    const val HEADER_SIZE = 12
    const val AUTH_TAG_SIZE = 16
    const val DEVICE_ID_SIZE = 8
    const val NONCE_SIZE = 16

    // Message Types (smartkey_msg_type_t)
    const val MSG_PING: Byte = 0x01
    const val MSG_PONG: Byte = 0x02
    const val MSG_AUTH_REQ: Byte = 0x10
    const val MSG_AUTH_RESP: Byte = 0x11
    const val MSG_UWB_CONFIG_REQ: Byte = 0x20
    const val MSG_UWB_CONFIG_RESP: Byte = 0x21
    const val MSG_STATUS_IND: Byte = 0x30
    const val MSG_ERROR_IND: Byte = 0x3F

    // Error Codes (smartkey_error_t)
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
     * Encodes a binary frame with 12-byte header, optional payload, and optional authentication tag.
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

        // 12-byte Little-Endian Header
        buffer.put(MAGIC_BYTE)
        buffer.put(PROTOCOL_VERSION)
        buffer.put(msgType)
        buffer.put(0x00.toByte()) // Reserved
        buffer.putInt((sessionId and 0xFFFFFFFFL).toInt())
        buffer.putShort((seqNum and 0xFFFF).toShort())
        buffer.putShort((payload.size and 0xFFFF).toShort())

        // Payload
        if (payload.isNotEmpty()) {
            buffer.put(payload)
        }

        // Authentication Tag
        authTag?.let {
            buffer.put(it)
        }

        return buffer.array()
    }

    /**
     * Parses the 12-byte Little-Endian header from raw bytes.
     * Returns null if bytes are insufficient or magic byte mismatches.
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
     * Constructs payload for MSG_AUTH_REQ (32 bytes):
     * - phone_id: 8 bytes
     * - phone_nonce: 16 bytes
     * - timestamp_ms: uint64 (8 bytes, little-endian)
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
     * Parses payload and tag for MSG_AUTH_RESP:
     * - lock_id: 8 bytes
     * - lock_nonce: 16 bytes
     * - new_session_id: 4 bytes (uint32)
     * - timestamp_ms: 4 bytes (uint32 on ESP32 millis())
     * - auth_tag: 16 bytes (HMAC-SHA256 truncated)
     * Total payload_auth_resp_t = 32 bytes + 16 bytes tag = 48 bytes.
     */
    fun parseAuthResponsePayload(payloadAndTag: ByteArray): AuthResponse? {
        // payload_auth_resp_t (32B) + auth_tag (16B) = 48B
        if (payloadAndTag.size < 32) return null
        val buf = ByteBuffer.wrap(payloadAndTag).order(ByteOrder.LITTLE_ENDIAN)

        val lockId = ByteArray(DEVICE_ID_SIZE)
        buf.get(lockId)

        val lockNonce = ByteArray(NONCE_SIZE)
        buf.get(lockNonce)

        val newSessionId = buf.int.toLong() and 0xFFFFFFFFL
        val timestampMs = buf.int.toLong() and 0xFFFFFFFFL

        val tag = if (payloadAndTag.size >= 32 + AUTH_TAG_SIZE) {
            val t = ByteArray(AUTH_TAG_SIZE)
            buf.get(t)
            t
        } else ByteArray(0)

        return AuthResponse(lockId, lockNonce, newSessionId, timestampMs, tag)
    }

    fun getErrorMessage(errorCode: Byte): String = when (errorCode) {
        ERR_NONE -> "No error"
        ERR_INVALID_MAGIC -> "Invalid magic byte (not 0xA5)"
        ERR_UNSUPPORTED_VERSION -> "Unsupported protocol version"
        ERR_UNKNOWN_MSG_TYPE -> "Unknown message type"
        ERR_STALE_NONCE -> "Replay attack detected: Stale nonce"
        ERR_AUTH_TAG_MISMATCH -> "Authentication tag mismatch (tampering)"
        ERR_SESSION_INVALID -> "Session invalid or expired"
        ERR_STS_CONFIG_FAIL -> "UWB STS hardware configuration failed"
        ERR_UWB_RANGING_TIMEOUT -> "UWB ranging pulse timeout"
        ERR_POLICY_REJECT_DISTANCE -> "Access denied: Distance > 1.6m"
        ERR_POLICY_REJECT_QUALITY -> "Access denied: Signal quality poor / NLOS"
        ERR_POLICY_FAIL_CLOSED -> "Fail-closed triggered: System error"
        ERR_INTERNAL_FAULT -> "Internal hardware fault"
        else -> "Unknown error code: 0x%02X".format(errorCode)
    }
}

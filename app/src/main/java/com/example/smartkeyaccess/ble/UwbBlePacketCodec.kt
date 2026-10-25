package com.example.smartkeyaccess.ble

import com.example.smartkeyaccess.crypto.AesGcm
import com.example.smartkeyaccess.crypto.CryptoManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

// Wire-format codec for UWB BLE packet exchange between Phone and ESP32
// Implements 14B/42B UWB Config, 30B MAC ESP32 decryption, and START UWB HMAC verification
object UwbBlePacketCodec {

    const val PLAINTEXT_CONFIG_SIZE = 14
    const val PACKET_CONFIG_SIZE = 42
    const val PACKET_ESP32_MAC_SIZE = 30
    const val UWB_MAC_SIZE = 2

    // AAD strings matching firmware contract
    val AAD_UWB_CONFIG: ByteArray = "UWB CONFIG FORM PHONE".toByteArray(Charsets.UTF_8)
    val AAD_MAC_ESP32: ByteArray = "MAC ESP32".toByteArray(Charsets.UTF_8)

    // Hardcoded start sign for START UWB HMAC verification
    val START_SIGN: ByteArray = byteArrayOf(0x35, 0xBB.toByte(), 0xAF.toByte(), 0x36)

    // Pack 14-byte plaintext in Little-Endian:
    // [sessionId 4B LE] + [MACphone 2B] + [channel 4B LE] + [preambleIndex 4B LE]
    fun encodeUwbConfigPlaintext(
        sessionId: Int,
        phoneMac: ByteArray,
        channel: Int = 9,
        preambleIndex: Int = 9
    ): ByteArray {
        require(sessionId != 0) {
            "Session ID must be non-zero"
        }
        require(phoneMac.size == UWB_MAC_SIZE) {
            "Phone UWB MAC must be exactly 2 bytes (got ${phoneMac.size} bytes). Dummy fallbacks are rejected."
        }

        val buffer = ByteBuffer.allocate(PLAINTEXT_CONFIG_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(sessionId)
        buffer.put(phoneMac)
        buffer.putInt(channel)
        buffer.putInt(preambleIndex)
        return buffer.array()
    }

    // Encrypt 14B plaintext using AES-128-GCM into 42B wire packet:
    // [Nonce 12B] + [Ciphertext 14B] + [Tag 16B]
    fun encryptUwbConfigPacket(
        key: ByteArray,
        plaintext14B: ByteArray
    ): ByteArray {
        require(plaintext14B.size == PLAINTEXT_CONFIG_SIZE) {
            "Plaintext must be exactly 14 bytes (got ${plaintext14B.size})"
        }
        val nonce = AesGcm.generateNonce()
        val encResult = AesGcm.encrypt(
            key = key,
            nonce = nonce,
            plaintext = plaintext14B,
            aad = AAD_UWB_CONFIG
        )

        val packet = ByteArray(PACKET_CONFIG_SIZE)
        System.arraycopy(nonce, 0, packet, 0, 12)
        System.arraycopy(encResult.ciphertext, 0, packet, 12, PLAINTEXT_CONFIG_SIZE)
        System.arraycopy(encResult.tag, 0, packet, 12 + PLAINTEXT_CONFIG_SIZE, 16)
        return packet
    }

    // Decrypt 30B packet from ESP32 containing 2B UWB MAC address:
    // [Nonce 12B] + [Ciphertext 2B] + [Tag 16B]
    // Strictly verifies GCM authentication tag with AAD "MAC ESP32" per README. No empty AAD fallback.
    fun decryptEsp32Mac(
        key: ByteArray,
        packet30B: ByteArray
    ): ByteArray? {
        if (packet30B.size != PACKET_ESP32_MAC_SIZE) return null

        val nonce = packet30B.copyOfRange(0, 12)
        val ciphertext = packet30B.copyOfRange(12, 14)
        val tag = packet30B.copyOfRange(14, 30)

        return try {
            AesGcm.decrypt(
                key = key,
                nonce = nonce,
                ciphertext = ciphertext,
                tag = tag,
                aad = AAD_MAC_ESP32
            )
        } catch (_: Exception) {
            null
        }
    }

    // Verify incoming START UWB HMAC-SHA256 from ESP32 against expected key and start sign.
    // Rejects plaintext strings and static unauthenticated bytes.
    fun verifyStartHmac(
        hmacKey: ByteArray,
        receivedHmac: ByteArray,
        sign: ByteArray = START_SIGN
    ): Boolean {
        if (receivedHmac.size != 32 && receivedHmac.size != 16) return false

        val computed = try {
            CryptoManager.computeHmacSha256(hmacKey, sign)
        } catch (_: Exception) {
            return false
        }

        return if (receivedHmac.size == 32) {
            MessageDigest.isEqual(computed, receivedHmac)
        } else {
            // Truncated 16B HMAC tag
            MessageDigest.isEqual(computed.copyOf(16), receivedHmac)
        }
    }

    // Helper to compute expected START UWB HMAC-SHA256 for testing
    fun computeStartHmac(
        hmacKey: ByteArray,
        sign: ByteArray = START_SIGN
    ): ByteArray {
        return CryptoManager.computeHmacSha256(hmacKey, sign)
    }
}

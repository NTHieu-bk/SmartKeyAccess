package com.example.smartkeyaccess.mock

import com.example.smartkeyaccess.crypto.CryptoManager

// Dữ liệu mẫu dùng để test khi chưa có thẻ thật hoặc test offline
object MockDataProvider {

    // VID mặc định trên firmware ESP32 (8 bytes)
    val FIRMWARE_CAR_ID: ByteArray = byteArrayOf(
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08
    )

    // MSK mặc định trên firmware ESP32 (32 bytes: 0x01 .. 0x20)
    val FIRMWARE_MSK_32B: ByteArray = byteArrayOf(
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
        0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10,
        0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18,
        0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F, 0x20
    )

    // VID dạng ASCII để test hiển thị
    val ASCII_CAR_ID: ByteArray = "VID_0001".toByteArray(Charsets.UTF_8)

    // MSK 16 byte test cục bộ
    val SAMPLE_MSK_16B: ByteArray = byteArrayOf(
        0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17,
        0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F
    )

    // Nonce test 16 bytes
    val SAMPLE_NONCE_16B: ByteArray = byteArrayOf(
        0xA0.toByte(), 0xA1.toByte(), 0xA2.toByte(), 0xA3.toByte(),
        0xA4.toByte(), 0xA5.toByte(), 0xA6.toByte(), 0xA7.toByte(),
        0xA8.toByte(), 0xA9.toByte(), 0xAA.toByte(), 0xAB.toByte(),
        0xAC.toByte(), 0xAD.toByte(), 0xAE.toByte(), 0xAF.toByte()
    )

    data class ProvisionedCard(
        val vehicleId: ByteArray,
        val masterSecretKey: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as ProvisionedCard
            return vehicleId.contentEquals(other.vehicleId) &&
                    masterSecretKey.contentEquals(other.masterSecretKey)
        }

        override fun hashCode(): Int {
            var result = vehicleId.contentHashCode()
            result = 31 * result + masterSecretKey.contentHashCode()
            return result
        }
    }

    // Tạo thẻ mẫu khớp firmware ESP32
    fun createMockMasterCard(useFirmwareSync: Boolean = true): ProvisionedCard {
        return if (useFirmwareSync) {
            ProvisionedCard(FIRMWARE_CAR_ID, FIRMWARE_MSK_32B)
        } else {
            ProvisionedCard(ASCII_CAR_ID, SAMPLE_MSK_16B)
        }
    }

    // Tính HMAC test: Tag = HMAC-SHA256(MSK, VID || Nonce)
    fun computeMockChallengeResponse(card: ProvisionedCard, nonce: ByteArray = SAMPLE_NONCE_16B): ByteArray {
        val payload = card.vehicleId + nonce
        return CryptoManager.computeHmacSha256(card.masterSecretKey, payload)
    }
}

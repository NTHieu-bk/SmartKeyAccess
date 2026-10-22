package com.example.smartkeyaccess.uwb

import androidx.core.uwb.RangingParameters
import androidx.core.uwb.UwbComplexChannel
import androidx.core.uwb.UwbDevice
import java.util.Locale

// Configuration from the authenticated BLE handshake, NOT from hardcoded demo values.
// Both peers must use matching session ID, FiRa configuration, channel and STS key.
// ByteArray fields are mutable; callers should not mutate them after creating this config.
data class UwbConfig(
    val peerAddress: ByteArray,
    val channel: Int = 9,
    val preambleIndex: Int = 9,
    val sessionId: Int,
    val subSessionId: Int = 0,
    val configId: Int = RangingParameters.CONFIG_PROVISIONED_UNICAST_DS_TWR,
    val sessionKeyInfo: ByteArray? = null,
    val subSessionKeyInfo: ByteArray? = null,
    val updateRateType: Int = RangingParameters.RANGING_UPDATE_RATE_FREQUENT
) {
    init {
        require(peerAddress.size == 2 || peerAddress.size == 8) {
            "Invalid UWB peer address: must be 2 bytes (short) or 8 bytes (extended), got ${peerAddress.size} bytes."
        }
    }

    fun isValid(): Boolean {
        if (peerAddress.size != 2 && peerAddress.size != 8) return false
        if (channel !in listOf(5, 6, 8, 9, 10, 12, 13, 14)) return false
        if (preambleIndex !in 9..32) return false
        if (sessionId == 0) return false
        if (subSessionId != 0 || subSessionKeyInfo != null) return false // Unicast modes only.
        if (updateRateType !in setOf(
                RangingParameters.RANGING_UPDATE_RATE_AUTOMATIC,
                RangingParameters.RANGING_UPDATE_RATE_FREQUENT,
                RangingParameters.RANGING_UPDATE_RATE_INFREQUENT
            )) return false

        // AndroidX UWB 1.0: Static STS = exactly 8B, Provisioned STS = exactly 16B.
        // Reject invalid keys here rather than failing later inside RangingParameters.
        val validKey = when (configId) {
            RangingParameters.CONFIG_UNICAST_DS_TWR -> sessionKeyInfo?.size == 8
            RangingParameters.CONFIG_PROVISIONED_UNICAST_DS_TWR -> sessionKeyInfo?.size == 16
            else -> false
        }
        if (!validKey) return false

        return true
    }

    val peerAddressHex: String
        get() = peerAddress.joinToString(":") { String.format(Locale.US, "%02X", it) }

    fun toUwbComplexChannel(): UwbComplexChannel = UwbComplexChannel(channel, preambleIndex)

    fun toUwbDevice(): UwbDevice = UwbDevice.createForAddress(peerAddress)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UwbConfig) return false

        if (!peerAddress.contentEquals(other.peerAddress)) return false
        if (channel != other.channel) return false
        if (preambleIndex != other.preambleIndex) return false
        if (sessionId != other.sessionId) return false
        if (subSessionId != other.subSessionId) return false
        if (configId != other.configId) return false
        if (sessionKeyInfo != null) {
            if (other.sessionKeyInfo == null || !sessionKeyInfo.contentEquals(other.sessionKeyInfo)) return false
        } else if (other.sessionKeyInfo != null) return false
        if (subSessionKeyInfo != null) {
            if (other.subSessionKeyInfo == null || !subSessionKeyInfo.contentEquals(other.subSessionKeyInfo)) return false
        } else if (other.subSessionKeyInfo != null) return false
        if (updateRateType != other.updateRateType) return false

        return true
    }

    override fun hashCode(): Int {
        var result = peerAddress.contentHashCode()
        result = 31 * result + channel
        result = 31 * result + preambleIndex
        result = 31 * result + sessionId
        result = 31 * result + subSessionId
        result = 31 * result + configId
        result = 31 * result + (sessionKeyInfo?.contentHashCode() ?: 0)
        result = 31 * result + (subSessionKeyInfo?.contentHashCode() ?: 0)
        result = 31 * result + updateRateType
        return result
    }

    companion object {
        fun createFromSession(
            sessionId: Long,
            peerAddressBytes: ByteArray,
            channel: Int = 9,
            preambleIndex: Int = 9,
            stsKey: ByteArray?,
            configId: Int = RangingParameters.CONFIG_PROVISIONED_UNICAST_DS_TWR
        ): UwbConfig {
            require(sessionId in 1L..0xFFFFFFFFL) {
                "Session ID must be an unsigned non-zero 32-bit value."
            }
            require(peerAddressBytes.size == 2 || peerAddressBytes.size == 8) {
                "Invalid UWB peer address: must be 2 or 8 bytes, got ${peerAddressBytes.size} bytes."
            }
            if (configId == RangingParameters.CONFIG_PROVISIONED_UNICAST_DS_TWR) {
                require(stsKey?.size == 16) {
                    "Provisioned STS requires exactly 16 bytes in AndroidX UWB 1.0."
                }
            } else if (configId == RangingParameters.CONFIG_UNICAST_DS_TWR) {
                require(stsKey?.size == 8) { "Static STS requires exactly 8 bytes." }
            } else {
                require(false) { "Unsupported UWB unicast configuration ID: $configId" }
            }
            val result = UwbConfig(
                peerAddress = peerAddressBytes,
                channel = channel,
                preambleIndex = preambleIndex,
                sessionId = (sessionId and 0xFFFFFFFFL).toInt(),
                configId = configId,
                sessionKeyInfo = stsKey?.copyOf()
            )
            require(result.isValid()) { "Invalid or unsupported UWB ranging configuration" }
            return result
        }
    }
}

// Backward compatibility alias
typealias Esp32UwbConfig = UwbConfig

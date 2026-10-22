package com.example.smartkeyaccess

import androidx.core.uwb.RangingParameters
import com.example.smartkeyaccess.uwb.UwbConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UwbConfigTest {

    @Test
    fun testProvisionedStsConfigValidation() {
        val validProvisioned = UwbConfig(
            peerAddress = byteArrayOf(0x01, 0x02),
            channel = 9,
            preambleIndex = 9,
            sessionId = 0x12345678,
            configId = RangingParameters.CONFIG_PROVISIONED_UNICAST_DS_TWR,
            sessionKeyInfo = ByteArray(16)
        )
        assertTrue(validProvisioned.isValid())
        assertEquals("01:02", validProvisioned.peerAddressHex)

        // Invalid key length for Provisioned STS (e.g. 8 bytes or 10 bytes)
        val invalidKeyLen = validProvisioned.copy(sessionKeyInfo = ByteArray(8))
        assertFalse(invalidKeyLen.isValid())

        val invalidKeyLen10 = validProvisioned.copy(sessionKeyInfo = ByteArray(10))
        assertFalse(invalidKeyLen10.isValid())

        // 32-byte key is valid for Provisioned STS
        val valid32Key = validProvisioned.copy(sessionKeyInfo = ByteArray(32))
        assertTrue(valid32Key.isValid())
    }

    @Test
    fun testStaticStsConfigValidation() {
        val validStatic = UwbConfig(
            peerAddress = byteArrayOf(0x01, 0x02),
            channel = 9,
            preambleIndex = 9,
            sessionId = 0x12345678,
            configId = RangingParameters.CONFIG_UNICAST_DS_TWR,
            sessionKeyInfo = ByteArray(8)
        )
        assertTrue(validStatic.isValid())

        // 16-byte key must be rejected for Static STS
        val invalidStaticKey = validStatic.copy(sessionKeyInfo = ByteArray(16))
        assertFalse(invalidStaticKey.isValid())
    }

    @Test
    fun testCreateFromSessionProvisionedEnforcement() {
        // Valid 16-byte key produces valid Provisioned STS config
        val config16 = UwbConfig.createFromSession(
            sessionId = 0x12345678L,
            peerAddressBytes = byteArrayOf(0x01, 0x02),
            stsKey = ByteArray(16)
        )
        assertEquals(RangingParameters.CONFIG_PROVISIONED_UNICAST_DS_TWR, config16.configId)
        assertTrue(config16.isValid())

        // 8-byte key without specifying configId must be rejected (no silent fallback to Static STS)
        try {
            UwbConfig.createFromSession(
                sessionId = 0x12345678L,
                peerAddressBytes = byteArrayOf(0x01, 0x02),
                stsKey = ByteArray(8)
            )
            org.junit.Assert.fail("Expected IllegalArgumentException when key is not 16B/32B for Provisioned STS")
        } catch (_: IllegalArgumentException) {}

        // Explicit Static STS config creation
        val config8 = UwbConfig.createFromSession(
            sessionId = 0x12345678L,
            peerAddressBytes = byteArrayOf(0x01, 0x02),
            stsKey = ByteArray(8),
            configId = RangingParameters.CONFIG_UNICAST_DS_TWR
        )
        assertEquals(RangingParameters.CONFIG_UNICAST_DS_TWR, config8.configId)
        assertTrue(config8.isValid())
    }

    @Test(expected = IllegalArgumentException::class)
    fun testRejectInvalidPeerAddressLength() {
        UwbConfig(
            peerAddress = byteArrayOf(0x01, 0x02, 0x03),
            sessionId = 0x12345678
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun testRejectZeroSessionIdInFactory() {
        UwbConfig.createFromSession(
            sessionId = 0L,
            peerAddressBytes = byteArrayOf(0x01, 0x02),
            stsKey = ByteArray(16)
        )
    }

    @Test
    fun testProvisionedStsCapabilityEnforcement() {
        // Device with only Static STS must not be readyToRange for Provisioned STS
        val staticOnlyReport = com.example.smartkeyaccess.uwb.UwbCapabilityReport(
            phoneUwbCapable = true,
            hardwareSupported = true,
            permissionGranted = true,
            serviceAvailable = true,
            firaChannelsSupported = true,
            firaDsTwrSupported = true,
            firaProvisionedDsTwrSupported = false
        )
        assertFalse(staticOnlyReport.readyToRange)

        // Device with Provisioned STS capability must be readyToRange
        val provisionedReport = staticOnlyReport.copy(firaProvisionedDsTwrSupported = true)
        assertTrue(provisionedReport.readyToRange)
    }
}

package com.example.smartkeyaccess.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import com.example.smartkeyaccess.crypto.CryptoManager
import com.example.smartkeyaccess.mock.MockDataProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyPair
import java.security.interfaces.ECPublicKey

// Shared bridge forwarding NFC HCE protocol lifecycle events to Jetpack Compose UI
object NfcHceBridge {
    private val _eventFlow = MutableStateFlow("NFC HCE Standby (Listening for PN532)")
    val eventFlow: StateFlow<String> = _eventFlow.asStateFlow()

    private val _provisionedVehicleId = MutableStateFlow<ByteArray?>(null)
    val provisionedVehicleId: StateFlow<ByteArray?> = _provisionedVehicleId.asStateFlow()

    fun updateEvent(event: String) {
        _eventFlow.value = event
    }

    fun updateVehicleId(vid: ByteArray) {
        _provisionedVehicleId.value = vid
    }
}

// Host Card Emulation service handling Kiệt's 7 APDU commands from PN532 firmware
class SmartKeyApduService : HostApduService() {

    companion object {
        // Status words conforming to ISO/IEC 7816-4
        private val SW_SUCCESS = byteArrayOf(0x90.toByte(), 0x00.toByte())
        private val SW_INS_NOT_SUPPORTED = byteArrayOf(0x6D.toByte(), 0x00.toByte())
        private val SW_WRONG_LENGTH = byteArrayOf(0x67.toByte(), 0x00.toByte())
        private val SW_UNKNOWN_ERROR = byteArrayOf(0x6F.toByte(), 0x00.toByte())

        // Application Identifier: F0 53 4D 41 52 54 4B 45 59 (0xF0 + "SMARTKEY")
        private val TARGET_AID = CryptoManager.fromHex("F0534D4152544B4559")

        // APDU Instruction bytes matching ESP32 firmware (nfc.cpp)
        private const val INS_SELECT: Byte = 0xA4.toByte()
        private const val INS_SEND_DATA: Byte = 0x10.toByte() // ESP32 -> Phone
        private const val INS_RECE_DATA: Byte = 0x20.toByte() // Phone -> ESP32

        // Parameter 1 (P1) data type tags defined in ESP32 global_variable.h
        private const val TAG_VID_NONCE: Byte = 0x01.toByte()
        private const val TAG_KEY: Byte = 0x02.toByte()
        private const val TAG_NONCE_OR_SIG: Byte = 0x03.toByte()
    }

    private var cachedHmac = ByteArray(32)
    private var ephemeralKeyPair: KeyPair? = null
    private var receivedEspKey: ByteArray? = null

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null || commandApdu.size < 4) {
            return SW_WRONG_LENGTH
        }

        val ins = commandApdu[1]
        val p1 = commandApdu[2]

        // 1. SELECT AID Handshake: 00 A4 04 00 09 F0 53 4D 41 52 54 4B 45 59 00
        if (ins == INS_SELECT) {
            val isAidMatch = commandApdu.size >= 5 + TARGET_AID.size &&
                    commandApdu.copyOfRange(5, 5 + TARGET_AID.size).contentEquals(TARGET_AID)

            return if (isAidMatch) {
                // Initialize fresh ephemeral ECDH key pair for this provisioning session
                ephemeralKeyPair = CryptoManager.generateEcdhKeyPair()
                NfcHceBridge.updateEvent("NFC HCE: Handshake OK with PN532 (AID Select)")
                SW_SUCCESS
            } else {
                SW_UNKNOWN_ERROR
            }
        }

        // 2. ESP32 SEND DATA (INS = 0x10)
        if (ins == INS_SEND_DATA) {
            val lc = if (commandApdu.size > 4) commandApdu[4].toInt() and 0xFF else 0
            val payload = if (commandApdu.size >= 5 + lc) commandApdu.copyOfRange(5, 5 + lc) else byteArrayOf()

            return when (p1) {
                TAG_VID_NONCE -> {
                    // Extract Vehicle ID (first 8 bytes) if present in payload
                    if (payload.size >= 8) {
                        val vid = payload.copyOfRange(0, 8)
                        NfcHceBridge.updateVehicleId(vid)
                    }
                    val msk = MockDataProvider.FIRMWARE_MSK_32B
                    cachedHmac = CryptoManager.computeHmacSha256(msk, payload)
                    NfcHceBridge.updateEvent("NFC: Received VID + Nonce (${payload.size}B). Computed HMAC.")
                    SW_SUCCESS
                }
                TAG_KEY -> {
                    // Payload: Public key exported by ESP32 Mbed TLS
                    receivedEspKey = payload
                    NfcHceBridge.updateEvent("NFC: Received ESP32 Public Key (${payload.size}B).")
                    SW_SUCCESS
                }
                TAG_NONCE_OR_SIG -> {
                    // Payload: Challenge nonce for digital signature step
                    NfcHceBridge.updateEvent("NFC: Received Signature Nonce (${payload.size}B).")
                    SW_SUCCESS
                }
                else -> SW_INS_NOT_SUPPORTED
            }
        }

        // 3. ESP32 RECEIVE DATA (INS = 0x20)
        if (ins == INS_RECE_DATA) {
            return when (p1) {
                TAG_VID_NONCE -> {
                    // Kiệt calls RECE_HASH (P1 = 0x01): return 32-byte HMAC digest
                    NfcHceBridge.updateEvent("NFC: Sent HMAC-SHA256 (32B) to ESP32.")
                    cachedHmac + SW_SUCCESS
                }
                TAG_KEY -> {
                    // Kiệt calls RECE_KEY (P1 = 0x02): return phone public key
                    val keyPair = ephemeralKeyPair ?: CryptoManager.generateEcdhKeyPair().also { ephemeralKeyPair = it }
                    val pubKey = keyPair.public as ECPublicKey

                    // Adjust wire length to fit ESP32 buffer: 32B affine X or full 66B TLS point
                    val le = if (commandApdu.size >= 5) commandApdu[4].toInt() and 0xFF else 0
                    val keyBytes = if (le == 32) {
                        val rawX = pubKey.w.affineX.toByteArray()
                        val key32 = ByteArray(32)
                        if (rawX.size >= 32) {
                            System.arraycopy(rawX, rawX.size - 32, key32, 0, 32)
                        } else {
                            System.arraycopy(rawX, 0, key32, 32 - rawX.size, rawX.size)
                        }
                        key32
                    } else {
                        CryptoManager.encodeTlsEcPoint(pubKey)
                    }

                    NfcHceBridge.updateEvent("NFC: Sent Public Key (${keyBytes.size}B) to ESP32.")
                    keyBytes + SW_SUCCESS
                }
                TAG_NONCE_OR_SIG -> {
                    // Kiệt calls RECE_SIGNATURE (P1 = 0x03): return 32-byte cryptographic token proof
                    val signatureProof = CryptoManager.computeSha256(cachedHmac)
                    NfcHceBridge.updateEvent("NFC: Sent Signature Proof (32B). Provisioning Complete!")
                    signatureProof + SW_SUCCESS
                }
                else -> SW_INS_NOT_SUPPORTED
            }
        }

        return SW_INS_NOT_SUPPORTED
    }

    override fun onDeactivated(reason: Int) {
        val reasonStr = when (reason) {
            DEACTIVATION_LINK_LOSS -> "Link Loss"
            DEACTIVATION_DESELECTED -> "Deselected"
            else -> "Reason $reason"
        }
        NfcHceBridge.updateEvent("NFC HCE: Deactivated ($reasonStr)")
    }
}

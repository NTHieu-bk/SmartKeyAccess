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

// Bridge between background HCE service and UI state flow
object NfcHceBridge {
    private val _eventFlow = MutableStateFlow("NFC HCE: Ready for PN532...")
    val eventFlow: StateFlow<String> = _eventFlow.asStateFlow()

    private val _provisionedVehicleId = MutableStateFlow<ByteArray?>(null)
    val provisionedVehicleId: StateFlow<ByteArray?> = _provisionedVehicleId.asStateFlow()

    private val _provisionedMsk = MutableStateFlow<ByteArray?>(null)
    val provisionedMsk: StateFlow<ByteArray?> = _provisionedMsk.asStateFlow()

    // Stable identity key pair persisted inside AndroidKeyStore
    val phoneIdentityKeyPair: KeyPair by lazy { CryptoManager.getOrCreateIdentityKeyPair() }

    fun updateEvent(event: String) {
        _eventFlow.value = event
    }

    fun updateVehicleId(vid: ByteArray) {
        _provisionedVehicleId.value = vid
    }

    fun updateProvisionedCard(vid: ByteArray, msk: ByteArray) {
        _provisionedVehicleId.value = vid
        _provisionedMsk.value = msk
    }
}

// HostApduService: Phone emulates an NFC smart card responding to PN532 APDU commands
class SmartKeyApduService : HostApduService() {

    companion object {
        private val SW_SUCCESS = byteArrayOf(0x90.toByte(), 0x00.toByte())
        private val SW_INS_NOT_SUPPORTED = byteArrayOf(0x6D.toByte(), 0x00.toByte())
        private val SW_WRONG_LENGTH = byteArrayOf(0x67.toByte(), 0x00.toByte())
        private val SW_UNKNOWN_ERROR = byteArrayOf(0x6F.toByte(), 0x00.toByte())
        private val SW_CONDITIONS_NOT_SATISFIED = byteArrayOf(0x69.toByte(), 0x85.toByte())

        // Registered AID: F0 + "SMARTKEY" (9 bytes)
        private val TARGET_AID = CryptoManager.fromHex("F0534D4152544B4559")

        // Instruction codes matching ESP32 firmware
        private const val INS_SELECT: Byte = 0xA4.toByte()
        private const val INS_SEND_DATA: Byte = 0x10.toByte() // ESP32 -> Phone
        private const val INS_RECE_DATA: Byte = 0x20.toByte() // Phone -> ESP32

        // P1 parameter tags
        private const val TAG_VID_NONCE: Byte = 0x01.toByte()
        private const val TAG_KEY: Byte = 0x02.toByte()
        private const val TAG_NONCE_OR_SIG: Byte = 0x03.toByte()
    }

    private var cachedHmac: ByteArray? = null
    private var cachedChallengeNonce: ByteArray? = null
    private var cachedSignature: ByteArray? = null
    private var receivedEspKey: ByteArray? = null

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null || commandApdu.size < 4) {
            return SW_WRONG_LENGTH
        }

        val ins = commandApdu[1]
        val p1 = commandApdu[2]

        // SELECT AID command: 00 A4 04 00 09 F0 53 4D 41 52 54 4B 45 59 00
        if (ins == INS_SELECT) {
            val isAidMatch = commandApdu.size >= 5 + TARGET_AID.size &&
                    commandApdu.copyOfRange(5, 5 + TARGET_AID.size).contentEquals(TARGET_AID)

            return if (isAidMatch) {
                cachedHmac = null
                cachedChallengeNonce = null
                cachedSignature = null
                receivedEspKey = null

                NfcHceBridge.updateEvent("NFC HCE: AID selected (Handshake OK)")
                SW_SUCCESS
            } else {
                SW_UNKNOWN_ERROR
            }
        }

        // ESP32 sends data to Phone (INS = 0x10)
        if (ins == INS_SEND_DATA) {
            val lc = if (commandApdu.size > 4) commandApdu[4].toInt() and 0xFF else 0
            val payload = if (commandApdu.size >= 5 + lc) commandApdu.copyOfRange(5, 5 + lc) else byteArrayOf()

            return when (p1) {
                TAG_VID_NONCE -> {
                    if (payload.size >= 8) {
                        val vid = payload.copyOfRange(0, 8)
                        NfcHceBridge.updateVehicleId(vid)
                    }
                    val msk = NfcHceBridge.provisionedMsk.value ?: MockDataProvider.FIRMWARE_MSK_32B
                    cachedHmac = CryptoManager.computeHmacSha256(msk, payload)
                    NfcHceBridge.updateEvent("NFC: Received VID + Nonce (${payload.size}B), HMAC computed")
                    SW_SUCCESS
                }
                TAG_KEY -> {
                    receivedEspKey = payload
                    NfcHceBridge.updateEvent("NFC: Received ESP32 Public Key (${payload.size}B)")
                    SW_SUCCESS
                }
                TAG_NONCE_OR_SIG -> {
                    if (payload.isEmpty()) return SW_WRONG_LENGTH

                    cachedChallengeNonce = payload.copyOf()
                    // Phone signs challenge nonce using its private key
                    val privateKey = NfcHceBridge.phoneIdentityKeyPair.private
                    cachedSignature = CryptoManager.signEcdsaP1363(privateKey, payload)

                    NfcHceBridge.updateEvent("NFC: Received Challenge Nonce, signed ECDSA P1363 (64B)")
                    SW_SUCCESS
                }
                else -> SW_INS_NOT_SUPPORTED
            }
        }

        // ESP32 requests data from Phone (INS = 0x20)
        if (ins == INS_RECE_DATA) {
            return when (p1) {
                TAG_VID_NONCE -> {
                    val hmac = cachedHmac ?: return SW_CONDITIONS_NOT_SATISFIED
                    NfcHceBridge.updateEvent("NFC: Sent HMAC-SHA256 (32B) to ESP32")
                    hmac + SW_SUCCESS
                }
                TAG_KEY -> {
                    val pubKey = NfcHceBridge.phoneIdentityKeyPair.public as ECPublicKey
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

                    NfcHceBridge.updateEvent("NFC: Sent ID_PUBLIC (${keyBytes.size}B) to ESP32")
                    keyBytes + SW_SUCCESS
                }
                TAG_NONCE_OR_SIG -> {
                    // Return 64B ECDSA signature (R || S) for ESP32 to verify signature
                    val signature = cachedSignature ?: return SW_CONDITIONS_NOT_SATISFIED
                    NfcHceBridge.updateEvent("NFC: Sent ECDSA signature (64B) for ESP32 verification")
                    signature + SW_SUCCESS
                }
                else -> SW_INS_NOT_SUPPORTED
            }
        }

        return SW_INS_NOT_SUPPORTED
    }

    override fun onDeactivated(reason: Int) {
        cachedHmac = null
        cachedChallengeNonce = null
        cachedSignature = null
        receivedEspKey = null

        val reasonStr = if (reason == DEACTIVATION_LINK_LOSS) "Link loss" else "Deselected"
        NfcHceBridge.updateEvent("NFC HCE: Deactivated ($reasonStr)")
    }
}

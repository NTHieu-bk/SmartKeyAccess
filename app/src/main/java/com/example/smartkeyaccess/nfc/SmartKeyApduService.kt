package com.example.smartkeyaccess.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import com.example.smartkeyaccess.crypto.CryptoManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyPair
import java.security.interfaces.ECPublicKey

/** Shared provisioning data between NFC Reader and Phone HCE. */
object NfcHceBridge {
    private val _eventFlow = MutableStateFlow("NFC HCE: Waiting for PN532")
    val eventFlow: StateFlow<String> = _eventFlow.asStateFlow()

    private val _provisionedVehicleId = MutableStateFlow<ByteArray?>(null)
    val provisionedVehicleId: StateFlow<ByteArray?> = _provisionedVehicleId.asStateFlow()

    private val _provisionedMsk = MutableStateFlow<ByteArray?>(null)
    val provisionedMsk: StateFlow<ByteArray?> = _provisionedMsk.asStateFlow()

    // Long-term Phone identity signing key, NOT a per-session ECDH key.
    val phoneIdentityKeyPair: KeyPair by lazy { CryptoManager.getOrCreateIdentityKeyPair() }

    fun updateEvent(event: String) {
        _eventFlow.value = event
    }

    /** The only production entry point for new trusted Card VID/MSK material. */
    fun updateProvisionedCard(vid: ByteArray, msk: ByteArray) {
        require(vid.size == 8) { "VID must be 8 bytes" }
        require(msk.size == 32) { "MSK must be 32 bytes" }
        _provisionedVehicleId.value = vid.copyOf()
        _provisionedMsk.value = msk.copyOf()
    }

    /** For legacy UI use ONLY: does not change the authenticated/provisioned VID. */
    @Deprecated("Do not overwrite a trusted card VID with a VID sent by ESP32")
    fun updateVehicleId(@Suppress("UNUSED_PARAMETER") vid: ByteArray) {
        updateEvent("NFC: updateVehicleId ignored (use updateProvisionedCard)")
    }
}


//NFC-HCE APDU command handler
class SmartKeyApduService : HostApduService() {
    companion object {
        private val SW_SUCCESS = byteArrayOf(0x90.toByte(), 0x00.toByte())
        private val SW_INS_NOT_SUPPORTED = byteArrayOf(0x6D.toByte(), 0x00.toByte())
        private val SW_WRONG_LENGTH = byteArrayOf(0x67.toByte(), 0x00.toByte())
        private val SW_WRONG_DATA = byteArrayOf(0x6A.toByte(), 0x80.toByte())
        private val SW_FILE_NOT_FOUND = byteArrayOf(0x6A.toByte(), 0x82.toByte())
        private val SW_UNKNOWN_ERROR = byteArrayOf(0x6F.toByte(), 0x00.toByte())
        private val SW_CONDITIONS_NOT_SATISFIED = byteArrayOf(0x69.toByte(), 0x85.toByte())

        private val TARGET_AID = CryptoManager.fromHex("F0534D4152544B4559")

        private const val INS_SELECT: Byte = 0xA4.toByte()
        private const val INS_SEND_DATA: Byte = 0x10.toByte() // ESP32 -> Phone
        private const val INS_RECE_DATA: Byte = 0x20.toByte() // ESP32 <- Phone

        private const val TAG_VID_NONCE: Byte = 0x01.toByte()
        private const val TAG_KEY: Byte = 0x02.toByte()
        private const val TAG_NONCE_OR_SIG: Byte = 0x03.toByte()

        private const val VID_SIZE = 8
        private const val MSK_SIZE = 32
        private const val VID_NONCE_SIZE = 24 // VID(8) || nonce(16)
        private const val SIGN_NONCE_SIZE = 24 // Proposed by latest team diagram; confirm!

        /**
         * The old APDU source signs the RAW nonce. The latest Kiá»‡t diagram
         * proposes signing HMAC-SHA256(MSK, signingNonce). Until firmware
         * explicitly freezes it, keep RAW_NONCE for compatibility with old code.
         * Change BOTH sides together, never silently switch here.
         */
        private enum class SigningMode { RAW_NONCE, HMAC_MSK_NONCE }
        private val SIGNING_MODE = SigningMode.RAW_NONCE
    }

    private var selected = false
    private var cachedHmac: ByteArray? = null
    private var cachedSignature: ByteArray? = null
    private var receivedEspKey: ECPublicKey? = null

    private fun clearSession() {
        selected = false
        cachedHmac = null
        cachedSignature = null
        receivedEspKey = null
    }

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null || commandApdu.size < 4) return SW_WRONG_LENGTH
        if (commandApdu[0] != 0x00.toByte()) return SW_INS_NOT_SUPPORTED

        val ins = commandApdu[1]
        val p1 = commandApdu[2]

        if (ins == INS_SELECT) {
            clearSession()
            // Short SELECT by AID: 00 A4 04 00 Lc AID [Le]
            if (p1 != 0x04.toByte() || commandApdu[3] != 0x00.toByte()) {
                return SW_WRONG_DATA
            }
            if (commandApdu.size != 5 + TARGET_AID.size &&
                commandApdu.size != 6 + TARGET_AID.size) return SW_WRONG_LENGTH
            if ((commandApdu[4].toInt() and 0xFF) != TARGET_AID.size) return SW_WRONG_LENGTH
            if (!commandApdu.copyOfRange(5, 5 + TARGET_AID.size).contentEquals(TARGET_AID)) {
                return SW_FILE_NOT_FOUND
            }
            selected = true
            NfcHceBridge.updateEvent("NFC HCE: AID selected")
            return SW_SUCCESS
        }

        if (!selected) return SW_CONDITIONS_NOT_SATISFIED
        if (commandApdu[3] != 0x00.toByte()) return SW_WRONG_DATA

        return when (ins) {
            INS_SEND_DATA -> receiveFromEsp32(p1, commandApdu)
            INS_RECE_DATA -> sendToEsp32(p1, commandApdu)
            else -> SW_INS_NOT_SUPPORTED
        }
    }

    /** Case 3 short APDU [CLA INS P1 P2 Lc data] (optional trailing Le). */
    private fun receiveFromEsp32(tag: Byte, apdu: ByteArray): ByteArray {
        if (apdu.size < 5) return SW_WRONG_LENGTH
        val lc = apdu[4].toInt() and 0xFF
        if (apdu.size != 5 + lc && apdu.size != 6 + lc) return SW_WRONG_LENGTH
        val payload = apdu.copyOfRange(5, 5 + lc)

        return when (tag) {
            TAG_VID_NONCE -> {
                cachedHmac = null
                cachedSignature = null
                receivedEspKey = null
                if (payload.size != VID_NONCE_SIZE) return SW_WRONG_LENGTH

                val trustedVid = NfcHceBridge.provisionedVehicleId.value
                val msk = NfcHceBridge.provisionedMsk.value
                if (trustedVid?.size != VID_SIZE || msk?.size != MSK_SIZE) {
                    NfcHceBridge.updateEvent("NFC: Rejected (physical Master Card not provisioned)")
                    return SW_CONDITIONS_NOT_SATISFIED
                }
                val vidEsp = payload.copyOfRange(0, VID_SIZE)
                if (!vidEsp.contentEquals(trustedVid)) {
                    NfcHceBridge.updateEvent("NFC: Rejected (VID mismatch)")
                    return SW_CONDITIONS_NOT_SATISFIED
                }
                // Proposed current first transcript: HMAC(MSK, VID || nonce).
                // Never replace trusted VID with VID provided by ESP32.
                cachedHmac = try {
                    CryptoManager.computeHmacSha256(msk, payload)
                } catch (_: Exception) {
                    return SW_UNKNOWN_ERROR
                }
                NfcHceBridge.updateEvent("NFC: VID matched, HMAC-SHA256 prepared (32B)")
                SW_SUCCESS
            }
            TAG_KEY -> {
                if (cachedHmac == null) return SW_CONDITIONS_NOT_SATISFIED
                if (payload.size != 65) return SW_WRONG_LENGTH
                // Parse/validate the point, but this alone does not authenticate ESP32.
                receivedEspKey = try {
                    CryptoManager.decodeUncompressedEcPoint(payload)
                } catch (_: Exception) {
                    NfcHceBridge.updateEvent("NFC: Invalid ESP32 P-256 point")
                    return SW_WRONG_DATA
                }
                cachedSignature = null
                NfcHceBridge.updateEvent("NFC: Received ESP32 public key (65B, not yet trusted)")
                SW_SUCCESS
            }
            TAG_NONCE_OR_SIG -> {
                cachedSignature = null
                if (payload.size != SIGN_NONCE_SIZE) return SW_WRONG_LENGTH
                if (cachedHmac == null || receivedEspKey == null) {
                    return SW_CONDITIONS_NOT_SATISFIED
                }
                val msk = NfcHceBridge.provisionedMsk.value
                    ?: return SW_CONDITIONS_NOT_SATISFIED
                if (msk.size != MSK_SIZE) return SW_CONDITIONS_NOT_SATISFIED

                val signInput = when (SIGNING_MODE) {
                    SigningMode.RAW_NONCE -> payload
                    SigningMode.HMAC_MSK_NONCE -> CryptoManager.computeHmacSha256(msk, payload)
                }
                cachedSignature = try {
                    CryptoManager.signEcdsaP1363(
                        NfcHceBridge.phoneIdentityKeyPair.private, signInput
                    ).also { require(it.size == 64) }
                } catch (_: Exception) {
                    NfcHceBridge.updateEvent("NFC: Phone identity signing failed")
                    return SW_UNKNOWN_ERROR
                }
                NfcHceBridge.updateEvent("NFC: ECDSA P1363 signature prepared (64B)")
                SW_SUCCESS
            }
            else -> SW_INS_NOT_SUPPORTED
        }
    }

    /** Case 2 short APDU [CLA INS P1 P2 (Le)]; Le=0 means up to 256 bytes. */
    private fun sendToEsp32(tag: Byte, apdu: ByteArray): ByteArray {
        if (apdu.size != 4 && apdu.size != 5) return SW_WRONG_LENGTH
        val le = if (apdu.size == 5) (apdu[4].toInt() and 0xFF).let { if (it == 0) 256 else it }
                 else 256

        return when (tag) {
            TAG_VID_NONCE -> {
                val hmac = cachedHmac ?: return SW_CONDITIONS_NOT_SATISFIED
                if (le < hmac.size) return SW_WRONG_LENGTH
                NfcHceBridge.updateEvent("NFC: Sent HMAC-SHA256 (32B)")
                hmac + SW_SUCCESS
            }
            TAG_KEY -> {
                if (cachedHmac == null) return SW_CONDITIONS_NOT_SATISFIED
                if (le < 65) return SW_WRONG_LENGTH
                val rawPoint = try {
                    val publicKey = NfcHceBridge.phoneIdentityKeyPair.public as ECPublicKey
                    CryptoManager.encodeUncompressedEcPoint(publicKey)
                } catch (_: Exception) {
                    NfcHceBridge.updateEvent("NFC: Failed to load identity public key")
                    return SW_UNKNOWN_ERROR
                }
                if (le < rawPoint.size) return SW_WRONG_LENGTH
                NfcHceBridge.updateEvent("NFC: Sent Phone identity public key (65B)")
                rawPoint + SW_SUCCESS
            }
            TAG_NONCE_OR_SIG -> {
                val signature = cachedSignature ?: return SW_CONDITIONS_NOT_SATISFIED
                if (le < signature.size) return SW_WRONG_LENGTH
                NfcHceBridge.updateEvent("NFC: Sent ECDSA P1363 signature (64B)")
                signature + SW_SUCCESS
            }
            else -> SW_INS_NOT_SUPPORTED
        }
    }

    override fun onDeactivated(reason: Int) {
        clearSession()
        val description = when (reason) {
            DEACTIVATION_LINK_LOSS -> "Link loss"
            DEACTIVATION_DESELECTED -> "Deselected"
            else -> "Reason $reason"
        }
        NfcHceBridge.updateEvent("NFC HCE: Deactivated ($description)")
    }
}

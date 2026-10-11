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

/*
 * Small bridge between the background HostApduService and MainActivity/Compose.
 *
 * HostApduService is created and called by Android when PN532 sends APDU commands, so it is not safe to
 * assume MainActivity is the object directly handling the NFC transaction. I keep only simple StateFlow
 * values here so the UI can observe what happened (AID selected, HMAC sent, signature sent, etc.).
 */
object NfcHceBridge {
    private val _eventFlow = MutableStateFlow("NFC HCE Standby (Listening for PN532)")
    val eventFlow: StateFlow<String> = _eventFlow.asStateFlow()

    private val _provisionedVehicleId = MutableStateFlow<ByteArray?>(null)
    val provisionedVehicleId: StateFlow<ByteArray?> = _provisionedVehicleId.asStateFlow()

    /*
     * Long-term Phone Identity key pair used for ECDSA enrollment.
     * CryptoManager stores this key under AndroidKeyStore, therefore the key does not silently change every
     * time the app process restarts. This is important because ESP32 can store ID_PUBLIC in NVS.
     */
    val phoneIdentityKeyPair: KeyPair by lazy { CryptoManager.getOrCreateIdentityKeyPair() }

    fun updateEvent(event: String) {
        _eventFlow.value = event
    }

    fun updateVehicleId(vid: ByteArray) {
        _provisionedVehicleId.value = vid
    }
}

/*
 * Android HCE side of the NFC protocol.
 *
 * Mental model used in this project:
 *   ESP32 + PN532 = reader / initiator
 *   Android phone = HCE card / responder
 *
 * Therefore the phone does not randomly push an NFC packet by itself. PN532 first sends a command APDU,
 * Android calls processCommandApdu(), and this function returns the response APDU. The INS/P1 values below
 * intentionally follow Kiệt's existing firmware contract so the two sides can be tested without rewriting
 * APDU commands that already worked before.
 */
class SmartKeyApduService : HostApduService() {

    companion object {
        // Status words conforming to ISO/IEC 7816-4
        private val SW_SUCCESS = byteArrayOf(0x90.toByte(), 0x00.toByte())
        private val SW_INS_NOT_SUPPORTED = byteArrayOf(0x6D.toByte(), 0x00.toByte())
        private val SW_WRONG_LENGTH = byteArrayOf(0x67.toByte(), 0x00.toByte())
        private val SW_UNKNOWN_ERROR = byteArrayOf(0x6F.toByte(), 0x00.toByte())
        // 69 85 = command cannot be executed because the expected previous protocol step has not happened.
        private val SW_CONDITIONS_NOT_SATISFIED = byteArrayOf(0x69.toByte(), 0x85.toByte())

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

    /*
     * These values belong to ONE NFC exchange only. They are deliberately kept separate from the long-term
     * identity key in AndroidKeyStore. SELECT AID resets them so a new NFC tap cannot accidentally reuse an
     * HMAC/signature from the previous tap.
     */
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

        // 1. SELECT AID Handshake: 00 A4 04 00 09 F0 53 4D 41 52 54 4B 45 59 00
        if (ins == INS_SELECT) {
            val isAidMatch = commandApdu.size >= 5 + TARGET_AID.size &&
                    commandApdu.copyOfRange(5, 5 + TARGET_AID.size).contentEquals(TARGET_AID)

            return if (isAidMatch) {
                // A SELECT starts a fresh logical NFC session for this simple project protocol.
                // Clearing cached values prevents stale data from being returned if ESP32 sends commands
                // in the wrong order during a later test. The persistent identity key is NOT cleared.
                cachedHmac = null
                cachedChallengeNonce = null
                cachedSignature = null
                receivedEspKey = null

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
                    /*
                     * ESP32 public-key bytes are received and cached here because Kiệt's APDU table already
                     * contains this step. In the current NFC milestone we do not run ECDH immediately with it;
                     * the key is kept for the later secure-session integration once both sides freeze the exact
                     * public-key wire format.
                     */
                    receivedEspKey = payload
                    NfcHceBridge.updateEvent("NFC: Received ESP32 Public Key (${payload.size}B).")
                    SW_SUCCESS
                }
                TAG_NONCE_OR_SIG -> {
                    /*
                     * Digital-signature challenge from ESP32. The project flow is:
                     *   ESP32 sends fresh nonce -> Phone signs nonce with ID_PRIVATE -> ESP32 later requests
                     *   RECE_SIGNATURE and verifies it with the stored PHONE ID_PUBLIC.
                     *
                     * We sign as soon as the challenge arrives and cache the 64-byte P1363 signature. This is
                     * useful during real NFC testing because if ESP32 repeats the RECEIVE command after a short
                     * communication hiccup, the phone can return the same response for the same challenge.
                     */
                    if (payload.isEmpty()) {
                        return SW_WRONG_LENGTH
                    }

                    cachedChallengeNonce = payload.copyOf()
                    val privateKey = NfcHceBridge.phoneIdentityKeyPair.private
                    cachedSignature = CryptoManager.signEcdsaP1363(privateKey, payload)

                    NfcHceBridge.updateEvent(
                        "NFC: Received Signature Challenge (${payload.size}B) and prepared ECDSA signature."
                    )
                    SW_SUCCESS
                }
                else -> SW_INS_NOT_SUPPORTED
            }
        }

        // 3. ESP32 RECEIVE DATA (INS = 0x20)
        if (ins == INS_RECE_DATA) {
            return when (p1) {
                TAG_VID_NONCE -> {
                    /*
                     * Kiệt calls RECE_HASH (P1 = 0x01). We only return a hash if SEND_VID_NONCE happened in
                     * this NFC session. Returning 32 zero bytes here would make a wrong command order look like
                     * a valid protocol response, which is very confusing while debugging hardware.
                     */
                    val hmac = cachedHmac ?: return SW_CONDITIONS_NOT_SATISFIED
                    NfcHceBridge.updateEvent("NFC: Sent HMAC-SHA256 (32B) to ESP32.")
                    hmac + SW_SUCCESS
                }
                TAG_KEY -> {
                    // Kiệt calls RECE_KEY (P1 = 0x02): return phone ID_PUBLIC key
                    val pubKey = NfcHceBridge.phoneIdentityKeyPair.public as ECPublicKey

                    /*
                     * Compatibility note with Kiệt's current APDU table:
                     * RECE_KEY currently asks for a 32-byte value, so this branch preserves the old behavior
                     * and returns the 32-byte affine-X coordinate. The complete uncompressed P-256 point needs
                     * X and Y (65B ANSI or 66B mbedTLS/TLS ECPoint format), therefore before the final ESP32
                     * ECDSA verification test the group still has to confirm exactly how Kiệt reconstructs or
                     * stores PHONE ID_PUBLIC.
                     *
                     * I do NOT silently change this working APDU contract here because the existing firmware
                     * commands are being used as the current integration baseline. If Le is not 32, the app
                     * returns the full 66-byte format already supported by CryptoManager.
                     */
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

                    NfcHceBridge.updateEvent("NFC: Sent ID_PUBLIC (${keyBytes.size}B) to ESP32.")
                    keyBytes + SW_SUCCESS
                }
                TAG_NONCE_OR_SIG -> {
                    /*
                     * Kiệt calls RECE_SIGNATURE (P1 = 0x03). The phone is the signer in this flow; ESP32 is
                     * the verifier. We return the cached IEEE P1363 signature R||S (64 bytes for P-256).
                     *
                     * If no challenge was received first, fail closed instead of signing a fake/default nonce.
                     * This makes command-order bugs visible immediately during the PN532 test.
                     */
                    val signature = cachedSignature ?: return SW_CONDITIONS_NOT_SATISFIED
                    NfcHceBridge.updateEvent(
                        "NFC: Sent ECDSA Signature (${signature.size}B) to ESP32 for verification."
                    )
                    signature + SW_SUCCESS
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
        // Session-only values must not leak into the next physical NFC tap. The Phone Identity key remains
        // in AndroidKeyStore and is intentionally NOT deleted here.
        cachedHmac = null
        cachedChallengeNonce = null
        cachedSignature = null
        receivedEspKey = null

        NfcHceBridge.updateEvent("NFC HCE: Deactivated ($reasonStr)")
    }
}

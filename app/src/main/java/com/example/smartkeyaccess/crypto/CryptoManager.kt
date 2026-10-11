package com.example.smartkeyaccess.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Locale
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object CryptoManager {

    private const val EC_CURVE_NAME = "secp256r1"
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val HASH_ALGORITHM = "SHA-256"
    private const val ECDH_ALGORITHM = "ECDH"

    /*
     * Alias used for the long-term Phone Identity key stored in AndroidKeyStore.
     *
     * This key is intentionally different from the temporary ECDH key generated for one BLE session.
     * Identity key  -> stays across app/process restarts and is used for ECDSA signatures.
     * ECDH key      -> temporary session key pair used only to derive a shared secret later.
     *
     * Keeping the two purposes separate avoids the confusion we had earlier in the project where a newly
     * generated ECDH public key was accidentally treated as the permanent identity of the phone.
     */
    private const val PHONE_IDENTITY_KEY_ALIAS = "smartkey_phone_identity_p256"

    // Domain separation string for HKDF (RFC 5869). Matches 'info' on ESP32 firmware.
    val DEFAULT_HKDF_INFO: ByteArray = "SmartKey-UWB-AES128-v1".toByteArray(Charsets.UTF_8)

    // New MessageDigest instance per call for thread-safety across coroutines and BLE callbacks
    fun computeSha256(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance(HASH_ALGORITHM)
        return digest.digest(data)
    }

    // Tag = HMAC(Key = MSK, Message = VehicleID || Nonce) for challenge-response without exposing MSK
    fun computeHmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        val keySpec = SecretKeySpec(key, HMAC_ALGORITHM)
        mac.init(keySpec)
        return mac.doFinal(data)
    }

    /**
     * Generates a TEMPORARY ECDH P-256 key pair.
     *
     * This helper is for the later BLE secure-session flow. The returned key pair is not saved to
     * AndroidKeyStore and should not be treated as the phone's permanent identity. Normally both the phone
     * and ESP32 create a fresh ECDH key pair, exchange the public keys, calculate the same shared secret,
     * then run HKDF to derive K_BLE / K_UWB (or whatever final key contract the group agrees on).
     */
    fun generateEcdhKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        val ecSpec = ECGenParameterSpec(EC_CURVE_NAME)
        kpg.initialize(ecSpec)
        return kpg.generateKeyPair()
    }

    // 66-byte TLS ECPoint format matching mbedtls_ecdh_make_public(): 0x41 || 0x04 || 32B X || 32B Y
    fun encodeTlsEcPoint(publicKey: ECPublicKey): ByteArray {
        val xBytes = toUnsigned32Bytes(publicKey.w.affineX)
        val yBytes = toUnsigned32Bytes(publicKey.w.affineY)

        val result = ByteArray(66)
        result[0] = 0x41.toByte()
        result[1] = 0x04.toByte()
        System.arraycopy(xBytes, 0, result, 2, 32)
        System.arraycopy(yBytes, 0, result, 34, 32)
        return result
    }

    // Supports 66B (ESP32 TLS header 0x41 0x04) and 65B (ANSI X9.62 uncompressed 0x04)
    fun decodeTlsEcPoint(bytes: ByteArray): ECPublicKey {
        val offset = when {
            bytes.size == 66 && bytes[0] == 0x41.toByte() && bytes[1] == 0x04.toByte() -> 2
            bytes.size == 65 && bytes[0] == 0x04.toByte() -> 1
            else -> throw IllegalArgumentException(
                "Invalid public key wire format: length = ${bytes.size} bytes. " +
                        "Expected 66 bytes (TLS ECPoint) or 65 bytes (ANSI X9.62 uncompressed)."
            )
        }

        val xBytes = bytes.copyOfRange(offset, offset + 32)
        val yBytes = bytes.copyOfRange(offset + 32, offset + 64)

        val x = BigInteger(1, xBytes)
        val y = BigInteger(1, yBytes)
        val point = ECPoint(x, y)

        val params = getP256ParameterSpec()
        val keySpec = ECPublicKeySpec(point, params)
        val keyFactory = KeyFactory.getInstance("EC")
        return keyFactory.generatePublic(keySpec) as ECPublicKey
    }

    // Raw ECDH shared secret (must be passed through HKDF before using as cipher key)
    fun computeSharedSecret(privateKey: ECPrivateKey, peerPublicKey: ECPublicKey): ByteArray {
        val keyAgreement = KeyAgreement.getInstance(ECDH_ALGORITHM)
        keyAgreement.init(privateKey)
        keyAgreement.doPhase(peerPublicKey, true)
        return keyAgreement.generateSecret()
    }

    // Derives symmetric key via HKDF-SHA256 (RFC 5869). Computes block T(1) for AES-128 (max 32B).
    fun deriveSessionKey(
        sharedSecret: ByteArray,
        info: ByteArray = DEFAULT_HKDF_INFO,
        keyLength: Int = 16
    ): ByteArray {
        require(keyLength in 1..32) {
            "Single-block HKDF-Expand only supports output up to 32 bytes (requested: $keyLength bytes)."
        }

        val zeroSalt = ByteArray(32)
        val prk = computeHmacSha256(zeroSalt, sharedSecret)

        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(prk, HMAC_ALGORITHM))
        mac.update(info)
        mac.update(0x01.toByte())
        val t1 = mac.doFinal()

        return t1.copyOf(keyLength)
    }

    fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { String.format(Locale.US, "%02X", it) }

    fun fromHex(hex: String): ByteArray {
        val cleanHex = hex.replace(" ", "")
        require(cleanHex.length % 2 == 0) { "Hexadecimal string must have an even number of characters." }

        val result = ByteArray(cleanHex.length / 2)
        for (i in result.indices) {
            val index = i * 2
            result[i] = cleanHex.substring(index, index + 2).toInt(16).toByte()
        }
        return result
    }

    // Normalize to 32 bytes: strip leading 0x00 sign byte from BigInteger or pad with zeros
    private fun toUnsigned32Bytes(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        val result = ByteArray(32)
        when {
            raw.size == 32 -> System.arraycopy(raw, 0, result, 0, 32)
            raw.size > 32 -> {
                System.arraycopy(raw, raw.size - 32, result, 0, 32)
            }
            else -> {
                System.arraycopy(raw, 0, result, 32 - raw.size, raw.size)
            }
        }
        return result
    }

    private fun getP256ParameterSpec(): ECParameterSpec {
        val params = AlgorithmParameters.getInstance("EC")
        params.init(ECGenParameterSpec(EC_CURVE_NAME))
        return params.getParameterSpec(ECParameterSpec::class.java)
    }

    /**
     * Returns the long-term Phone Identity key pair used by the NFC provisioning/signature flow.
     *
     * Why AndroidKeyStore is used here instead of generateEcdhKeyPair():
     * - ESP32 may store the phone ID_PUBLIC in NVS after enrollment.
     * - If Android generated a new identity key every time the app process restarted, ESP32 would still hold
     *   the old public key and every later signature verification would fail.
     * - AndroidKeyStore gives us a stable key alias and keeps the private key inside the platform keystore.
     *
     * This function first checks whether the alias already exists. If yes, it reconstructs a KeyPair object
     * from the existing private key + certificate public key. If not, it generates a new NIST P-256 key that
     * is allowed to sign/verify SHA-256 ECDSA data.
     */
    fun getOrCreateIdentityKeyPair(): KeyPair {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        if (!keyStore.containsAlias(PHONE_IDENTITY_KEY_ALIAS)) {
            val generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                "AndroidKeyStore"
            )

            val spec = KeyGenParameterSpec.Builder(
                PHONE_IDENTITY_KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec(EC_CURVE_NAME))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()

            generator.initialize(spec)
            generator.generateKeyPair()
        }

        val privateKey = (keyStore.getKey(PHONE_IDENTITY_KEY_ALIAS, null) as? java.security.PrivateKey)
            ?: error("Identity private key was not found in AndroidKeyStore")
        val publicKey = keyStore.getCertificate(PHONE_IDENTITY_KEY_ALIAS)?.publicKey
            ?: error("Identity public key certificate was not found in AndroidKeyStore")

        return KeyPair(publicKey, privateKey)
    }

    /**
     * Compatibility wrapper kept so older project code does not break immediately.
     * New code should call getOrCreateIdentityKeyPair() because this is NOT a fresh ephemeral key anymore.
     */
    fun generateIdentityKeyPair(): KeyPair = getOrCreateIdentityKeyPair()

    /**
     * Signs arbitrary challenge bytes with SHA-256 + ECDSA and returns IEEE P1363 format.
     *
     * For P-256, P1363 is always 64 bytes: 32-byte R followed by 32-byte S. That fixed length is convenient
     * for the ESP32 side because it avoids ASN.1 DER's variable-length INTEGER encoding.
     *
     * In the NFC flow the data parameter is the fresh challenge nonce generated by ESP32. The phone signs
     * that nonce using its long-term identity private key, while ESP32 verifies the result using ID_PUBLIC.
     */
    fun signEcdsaP1363(privateKey: java.security.PrivateKey, data: ByteArray): ByteArray {
        return try {
            val sig = java.security.Signature.getInstance("SHA256withECDSAinP1363Format")
            sig.initSign(privateKey)
            sig.update(data)
            sig.sign()
        } catch (_: Exception) {
            // Fallback for runtimes lacking direct P1363 algorithm name: sign with DER then convert
            val sig = java.security.Signature.getInstance("SHA256withECDSA")
            sig.initSign(privateKey)
            sig.update(data)
            derToP1363(sig.sign())
        }
    }

    /**
     * Verifies an IEEE P1363 ECDSA signature. Mobile does not currently need this in the normal NFC path
     * because the ESP32 is the verifier there, but keeping the helper makes local test vectors easier and
     * lets us verify signatures in unit/integration tests before testing with the PN532 hardware.
     */
    fun verifyEcdsaP1363(publicKey: ECPublicKey, data: ByteArray, signatureP1363: ByteArray): Boolean {
        if (signatureP1363.size != 64) return false
        return try {
            val sig = java.security.Signature.getInstance("SHA256withECDSAinP1363Format")
            sig.initVerify(publicKey)
            sig.update(data)
            sig.verify(signatureP1363)
        } catch (_: Exception) {
            try {
                val sig = java.security.Signature.getInstance("SHA256withECDSA")
                sig.initVerify(publicKey)
                sig.update(data)
                sig.verify(p1363ToDer(signatureP1363))
            } catch (_: Exception) {
                false
            }
        }
    }

    // Convert ASN.1 DER-encoded ECDSA signature to fixed 64-byte P1363 format (32B R || 32B S)
    fun derToP1363(der: ByteArray): ByteArray {
        var offset = 2 // Skip 0x30 and total length
        if (der.size > 1 && (der[1].toInt() and 0x80) != 0) {
            val lenBytes = der[1].toInt() and 0x7F
            offset = 2 + lenBytes
        }

        require(der.size > offset + 2 && der[offset] == 0x02.toByte()) { "Expected INTEGER tag for R" }
        val rLen = der[offset + 1].toInt() and 0xFF
        val rBytes = der.copyOfRange(offset + 2, offset + 2 + rLen)
        offset += 2 + rLen

        require(der.size > offset + 2 && der[offset] == 0x02.toByte()) { "Expected INTEGER tag for S" }
        val sLen = der[offset + 1].toInt() and 0xFF
        val sBytes = der.copyOfRange(offset + 2, offset + 2 + sLen)

        val rBig = BigInteger(1, rBytes)
        val sBig = BigInteger(1, sBytes)

        val result = ByteArray(64)
        System.arraycopy(toUnsigned32Bytes(rBig), 0, result, 0, 32)
        System.arraycopy(toUnsigned32Bytes(sBig), 0, result, 32, 32)
        return result
    }

    // Convert 64-byte P1363 format (32B R || 32B S) to ASN.1 DER format
    fun p1363ToDer(p1363: ByteArray): ByteArray {
        require(p1363.size == 64) { "P1363 format must be exactly 64 bytes" }
        val r = BigInteger(1, p1363.copyOfRange(0, 32))
        val s = BigInteger(1, p1363.copyOfRange(32, 64))

        val rBytes = r.toByteArray()
        val sBytes = s.toByteArray()

        val totalLen = 2 + rBytes.size + 2 + sBytes.size
        val result = ByteArray(2 + totalLen)
        result[0] = 0x30.toByte()
        result[1] = totalLen.toByte()

        var idx = 2
        result[idx++] = 0x02.toByte()
        result[idx++] = rBytes.size.toByte()
        System.arraycopy(rBytes, 0, result, idx, rBytes.size)
        idx += rBytes.size

        result[idx++] = 0x02.toByte()
        result[idx++] = sBytes.size.toByte()
        System.arraycopy(sBytes, 0, result, idx, sBytes.size)

        return result
    }

    /*
     * TODO for the later secure BLE/UWB milestone:
     * add AES-128-GCM encrypt/decrypt only after the group freezes the exact wire contract with Kiệt
     * (key source, IV/nonce size, authentication tag size, message layout and failure behavior).
     * I intentionally do not add a guessed AES-GCM format here because both Android and ESP32 must use
     * exactly the same bytes or the tag verification will fail even when both AES implementations are correct.
     */
}



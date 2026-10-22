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

/**
 * Shared cryptographic utilities for the Smart Key Android application.
 *
 * Responsibilities:
 * - SHA-256 and HMAC-SHA256.
 * - Ephemeral P-256 ECDH, key encoding/decoding, and HKDF key derivation.
 * - Persistent Android Keystore identity and ECDSA P1363 signatures.
 *
 * AES-128-GCM encrypt/decrypt lives exclusively in [AesGcm].
 * This object does not construct BLE packets or decide whether a vehicle unlocks.
 */
object CryptoManager {

    private const val EC_CURVE_NAME = "secp256r1"
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val HASH_ALGORITHM = "SHA-256"
    private const val ECDH_ALGORITHM = "ECDH"

    // Persistent phone identity key alias stored securely in AndroidKeyStore
    private const val PHONE_IDENTITY_KEY_ALIAS = "smartkey_phone_identity_p256"

    val DEFAULT_HKDF_INFO: ByteArray = "SmartKey-UWB-AES128-v1".toByteArray(Charsets.UTF_8)

    /** Hash arbitrary bytes with SHA-256 (32B output); no secret key is involved. */
    fun computeSha256(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance(HASH_ALGORITHM)
        return digest.digest(data)
    }

    /**
     * Compute a 32-byte HMAC-SHA256 authentication code.
     * [key] can be the provisioned MSK or another agreed MAC/session key.
     * The caller must agree with ESP32 on the exact input bytes and key.
     */
    fun computeHmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        val keySpec = SecretKeySpec(key, HMAC_ALGORITHM)
        mac.init(keySpec)
        return mac.doFinal(data)
    }

    /**
     * Create a fresh ephemeral P-256 ECDH key pair for the current session.
     * Keep the private key local; exchange only the encoded public key.
     */
    fun generateEcdhKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        val ecSpec = ECGenParameterSpec(EC_CURVE_NAME)
        kpg.initialize(ecSpec)
        return kpg.generateKeyPair()
    }

    /**
     * SEC1 P-256 raw uncompressed public key, 65 bytes: 0x04 || X(32) || Y(32).
     * Use this exact encoding for NFC TAG_KEY once the ESP32 contract confirms 65B.
     * The same encoding can represent a P-256 signing or ECDH public key; it
     * does NOT mean the two key pairs have the same lifecycle.
     */
    fun encodeUncompressedEcPoint(publicKey: ECPublicKey): ByteArray {
        val xBytes = toUnsigned32Bytes(publicKey.w.affineX)
        val yBytes = toUnsigned32Bytes(publicKey.w.affineY)
        return byteArrayOf(0x04.toByte()) + xBytes + yBytes
    }

    fun decodeUncompressedEcPoint(bytes: ByteArray): ECPublicKey {
        require(bytes.size == 65 && bytes[0] == 0x04.toByte()) {
            "Raw uncompressed P-256 point must be exactly 65B and begin with 0x04"
        }
        return decodeTlsEcPoint(bytes)
    }

    // 66-byte TLS ECPoint format matching ESP32 mbedTLS: 0x41 0x04 || 32B X || 32B Y
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

    // Decode 66-byte TLS or 65-byte uncompressed EC public key
    fun decodeTlsEcPoint(bytes: ByteArray): ECPublicKey {
        val offset = when {
            bytes.size == 66 && bytes[0] == 0x41.toByte() && bytes[1] == 0x04.toByte() -> 2
            bytes.size == 65 && bytes[0] == 0x04.toByte() -> 1
            else -> throw IllegalArgumentException(
                "Invalid public key length: ${bytes.size}B (expected 66B or 65B)"
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

    /**
     * Calculate an ECDH shared secret from our private key and a peer public key.
     * Both peers should derive matching secret bytes using matching EC parameters.
     * The shared secret must never be transmitted; authenticate the peer key first.
     */
    fun computeSharedSecret(privateKey: ECPrivateKey, peerPublicKey: ECPublicKey): ByteArray {
        val keyAgreement = KeyAgreement.getInstance(ECDH_ALGORITHM)
        keyAgreement.init(privateKey)
        keyAgreement.doPhase(peerPublicKey, true)
        return keyAgreement.generateSecret()
    }

    /**
     * Existing HKDF-SHA256 extract/expand helper (returns up to 32 bytes).
     *
     * Uses an all-zero 32-byte salt and one HKDF-Expand block T(1):
     *   PRK = HMAC(zeroSalt, sharedSecret)
     *   T(1) = HMAC(PRK, info || 0x01)
     *
     * IMPORTANT: This is the ORIGINAL behavior, preserved to avoid a protocol change.
     * It does NOT derive 3 independent BLE/UWB/MAC keys in one call.
     * Agree on salt, info, key separation, and output layout with ESP32 before use.
     */
    fun deriveSessionKey(
        sharedSecret: ByteArray,
        info: ByteArray = DEFAULT_HKDF_INFO,
        keyLength: Int = 16
    ): ByteArray {
        require(keyLength in 1..32) { "Key length must be between 1 and 32 bytes" }

        val zeroSalt = ByteArray(32)
        val prk = computeHmacSha256(zeroSalt, sharedSecret)

        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(prk, HMAC_ALGORITHM))
        mac.update(info)
        mac.update(0x01.toByte())
        val t1 = mac.doFinal()

        return t1.copyOf(keyLength)
    }

    /** Format bytes as uppercase hex for diagnostics; do not log keys or secrets. */
    fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { String.format(Locale.US, "%02X", it) }

    /** Parse an even-length hexadecimal string into bytes. */
    fun fromHex(hex: String): ByteArray {
        val cleanHex = hex.replace(" ", "")
        require(cleanHex.length % 2 == 0) { "Hex string must have an even length" }

        val result = ByteArray(cleanHex.length / 2)
        for (i in result.indices) {
            val index = i * 2
            result[i] = cleanHex.substring(index, index + 2).toInt(16).toByte()
        }
        return result
    }

    private fun toUnsigned32Bytes(value: BigInteger): ByteArray {
        require(value.signum() >= 0 && value.bitLength() <= 256) {
            "P-256 coordinate or signature integer is outside unsigned 256-bit range"
        }
        val raw = value.toByteArray()
        return ByteArray(32).also { out ->
            val bytes = if (raw.size == 33 && raw[0] == 0.toByte()) raw.copyOfRange(1, 33) else raw
            require(bytes.size <= 32)
            System.arraycopy(bytes, 0, out, 32 - bytes.size, bytes.size)
        }
    }

    private fun getP256ParameterSpec(): ECParameterSpec {
        val params = AlgorithmParameters.getInstance("EC")
        params.init(ECGenParameterSpec(EC_CURVE_NAME))
        return params.getParameterSpec(ECParameterSpec::class.java)
    }

    /**
     * Persistent signing identity. Fail CLOSED when AndroidKeyStore is unavailable.
     * Never silently generate a replacement in-memory identity on failures:
     * otherwise ESP32 may have enrolled a different public key.
     * Host JVM tests should test crypto primitives with generateEcdhKeyPair().
     */
    fun getOrCreateIdentityKeyPair(): KeyPair {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!keyStore.containsAlias(PHONE_IDENTITY_KEY_ALIAS)) {
            val generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore"
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
        val privateKey = keyStore.getKey(PHONE_IDENTITY_KEY_ALIAS, null)
            as? java.security.PrivateKey ?: error("AndroidKeyStore identity private key missing")
        val publicKey = keyStore.getCertificate(PHONE_IDENTITY_KEY_ALIAS)?.publicKey
            as? ECPublicKey ?: error("AndroidKeyStore identity public key missing")
        return KeyPair(publicKey, privateKey)
    }

    fun generateIdentityKeyPair(): KeyPair = getOrCreateIdentityKeyPair()

    /**
     * ECDSA P-256 signature, IEEE P1363 wire encoding: 32B r || 32B s (64B).
     * SHA256withECDSA hashes [data] internally; it does NOT compute HMAC by itself.
     * If the protocol requires signing an HMAC output, compute the HMAC separately.
     */
    fun signEcdsaP1363(privateKey: java.security.PrivateKey, data: ByteArray): ByteArray {
        return try {
            val sig = java.security.Signature.getInstance("SHA256withECDSAinP1363Format")
            sig.initSign(privateKey)
            sig.update(data)
            sig.sign()
        } catch (_: Exception) {
            val sig = java.security.Signature.getInstance("SHA256withECDSA")
            sig.initSign(privateKey)
            sig.update(data)
            derToP1363(sig.sign())
        }
    }

    /**
     * Verify 64-byte P1363 ECDSA signature against [data] (hashed internally).
     * Returns false on invalid signatures and supported-provider failures.
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

    // Convert DER signature to IEEE P1363 (32B R || 32B S)
    fun derToP1363(der: ByteArray): ByteArray {
        var offset = 2
        if (der.size > 1 && (der[1].toInt() and 0x80) != 0) {
            val lenBytes = der[1].toInt() and 0x7F
            offset = 2 + lenBytes
        }

        require(der.size > offset + 2 && der[offset] == 0x02.toByte()) { "Invalid R integer tag" }
        val rLen = der[offset + 1].toInt() and 0xFF
        val rBytes = der.copyOfRange(offset + 2, offset + 2 + rLen)
        offset += 2 + rLen

        require(der.size > offset + 2 && der[offset] == 0x02.toByte()) { "Invalid S integer tag" }
        val sLen = der[offset + 1].toInt() and 0xFF
        val sBytes = der.copyOfRange(offset + 2, offset + 2 + sLen)

        val rBig = BigInteger(1, rBytes)
        val sBig = BigInteger(1, sBytes)

        val result = ByteArray(64)
        System.arraycopy(toUnsigned32Bytes(rBig), 0, result, 0, 32)
        System.arraycopy(toUnsigned32Bytes(sBig), 0, result, 32, 32)
        return result
    }

    // Convert IEEE P1363 signature back to ASN.1 DER format
    fun p1363ToDer(p1363: ByteArray): ByteArray {
        require(p1363.size == 64) { "P1363 signature length must be exactly 64 bytes" }
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
}

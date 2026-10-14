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
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoManager {

    private const val EC_CURVE_NAME = "secp256r1"
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val HASH_ALGORITHM = "SHA-256"
    private const val ECDH_ALGORITHM = "ECDH"

    // Persistent phone identity key alias stored securely in AndroidKeyStore
    private const val PHONE_IDENTITY_KEY_ALIAS = "smartkey_phone_identity_p256"

    val DEFAULT_HKDF_INFO: ByteArray = "SmartKey-UWB-AES128-v1".toByteArray(Charsets.UTF_8)

    // Compute SHA-256 digest
    fun computeSha256(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance(HASH_ALGORITHM)
        return digest.digest(data)
    }

    // Compute HMAC-SHA256 with MSK
    fun computeHmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        val keySpec = SecretKeySpec(key, HMAC_ALGORITHM)
        mac.init(keySpec)
        return mac.doFinal(data)
    }

    // Ephemeral ECDH key pair for BLE session
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

    // Compute ECDH shared secret
    fun computeSharedSecret(privateKey: ECPrivateKey, peerPublicKey: ECPublicKey): ByteArray {
        val keyAgreement = KeyAgreement.getInstance(ECDH_ALGORITHM)
        keyAgreement.init(privateKey)
        keyAgreement.doPhase(peerPublicKey, true)
        return keyAgreement.generateSecret()
    }

    // Derive symmetric session key via HKDF-SHA256 (default 16 bytes for AES-128)
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

    /**
     * AES-128-GCM primitive: returns ciphertext || 16B authentication tag.
     * Caller MUST use a new unique 12-byte IV for each encryption under a key.
     * Wire framing / IV transport / AAD must be agreed with ESP32 separately.
     */
    fun encryptAes128Gcm(
        key: ByteArray, iv: ByteArray, plaintext: ByteArray, aad: ByteArray = byteArrayOf()
    ): ByteArray {
        require(key.size == 16) { "AES-128 key must be 16B" }
        require(iv.size == 12) { "GCM IV must be 12B" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    /** Throws on bad tag / IV / key, rather than returning unauthenticated plaintext. */
    fun decryptAes128Gcm(
        key: ByteArray, iv: ByteArray, ciphertextAndTag: ByteArray,
        aad: ByteArray = byteArrayOf()
    ): ByteArray {
        require(key.size == 16) { "AES-128 key must be 16B" }
        require(iv.size == 12) { "GCM IV must be 12B" }
        require(ciphertextAndTag.size >= 16) { "Missing 16B GCM tag" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(ciphertextAndTag)
    }

    fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { String.format(Locale.US, "%02X", it) }

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

    // Sign using ECDSA in IEEE P1363 format (exactly 64 bytes: 32B R || 32B S)
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

    // Verify ECDSA signature in IEEE P1363 format
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

package com.example.smartkeyaccess.crypto

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
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

    // Ephemeral key pair on NIST P-256 (secp256r1) supporting forward secrecy when discarded after session
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

    // TODO(Hieu): In Milestone M4, add encryptAesGcm and decryptAesGcm once ESP32 firmware completes the AES-GCM module.
}



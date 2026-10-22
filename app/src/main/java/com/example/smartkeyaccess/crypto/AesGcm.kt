package com.example.smartkeyaccess.crypto

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-128-GCM authenticated encryption for Smart Key BLE application data.
 *
 * Wire-level inputs:
 *  - AES key: 16B (derived session key, never the raw Master Secret Key).
 *  - nonce / IV: 12B; MUST NOT repeat under the same key.
 *  - GCM tag: 16B (128 bits).
 *  - AAD: optional unencrypted metadata, but authenticated by the GCM tag.
 *
 * ESP32 must agree on key derivation, IV allocation, packet encoding and AAD.
 * This class does not create BLE packets, track sequence numbers or reject replays.
 */
object AesGcm {

    const val KEY_SIZE = 16
    const val NONCE_SIZE = 12
    const val TAG_SIZE = 16

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private val secureRandom = SecureRandom()

    /** Wire-friendly result; the nonce must be sent/stored separately. */
    data class EncryptResult(
        val ciphertext: ByteArray,
        val tag: ByteArray
    )

    /**
     * Generate a random 12B nonce for a single encryption operation.
     *
     * Randomness alone does not guarantee uniqueness across repeated sessions.
     * The protocol must enforce nonce uniqueness for every message under a key.
     */
    fun generateNonce(): ByteArray {
        val nonce = ByteArray(NONCE_SIZE)
        secureRandom.nextBytes(nonce)
        return nonce
    }

    /**
     * Encrypt [plaintext] and authenticate it together with optional [aad].
     *
     * Inputs: [key] 16B, [nonce] 12B, plaintext of any length, AAD optional.
     * Output: separate ciphertext and 16B authentication tag.
     * Throws for malformed key/nonce or provider-level encryption errors.
     * Do NOT use the same key + nonce for two distinct encryptions.
     */
    fun encrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray = byteArrayOf()
    ): EncryptResult {

        require(key.size == KEY_SIZE) {
            "Key must be 16 bytes"
        }

        require(nonce.size == NONCE_SIZE) {
            "Nonce must be 12 bytes"
        }

        val secretKey = SecretKeySpec(key, "AES")

        val gcmSpec = GCMParameterSpec(
            TAG_SIZE * 8,
            nonce
        )

        val cipher = Cipher.getInstance(TRANSFORMATION)

        cipher.init(
            Cipher.ENCRYPT_MODE,
            secretKey,
            gcmSpec
        )

        // AEAD requires providing all AAD before ciphertext/plaintext processing.
        if (aad.isNotEmpty()) cipher.updateAAD(aad)

        // Java/JCA GCM returns ciphertext || 16-byte authentication tag.
        val encrypted = cipher.doFinal(plaintext)

        val ciphertextLength = encrypted.size - TAG_SIZE

        val ciphertext = encrypted.copyOfRange(
            0,
            ciphertextLength
        )

        val tag = encrypted.copyOfRange(
            ciphertextLength,
            encrypted.size
        )

        return EncryptResult(
            ciphertext = ciphertext,
            tag = tag
        )
    }

    /**
     * Authenticate and decrypt encrypted data from ESP32.
     *
     * Inputs: same session key, nonce, AAD used by sender + ciphertext and tag.
     * Returns plaintext when GCM authentication succeeds; null on a bad tag.
     * Incorrect lengths throw IllegalArgumentException.
     * Other crypto/provider failures are NOT silently converted to null.
     *
     * Caller must reject null; never process unauthenticated bytes as JSON.
     * A valid GCM tag does not itself prove message freshness / prevent replay.
     */
    fun decrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        tag: ByteArray,
        aad: ByteArray = byteArrayOf()
    ): ByteArray? {

        require(key.size == KEY_SIZE) {
            "Key must be 16 bytes"
        }

        require(nonce.size == NONCE_SIZE) {
            "Nonce must be 12 bytes"
        }

        require(tag.size == TAG_SIZE) {
            "Tag must be 16 bytes"
        }

        val secretKey = SecretKeySpec(key, "AES")

        val gcmSpec = GCMParameterSpec(
            TAG_SIZE * 8,
            nonce
        )

        val cipher = Cipher.getInstance(TRANSFORMATION)

        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey,
            gcmSpec
        )

        // Authenticate AAD (e.g. agreed session/header fields) without encrypting it.
        if (aad.isNotEmpty()) cipher.updateAAD(aad)

        // Java/JCA GCM expects ciphertext || tag as input to doFinal().
        val encrypted = ciphertext + tag

        return try {
            cipher.doFinal(encrypted)
        } catch (_: AEADBadTagException) {
            // Fail closed: no plaintext is returned for an invalid authentication tag.
            null
        }
    }
}
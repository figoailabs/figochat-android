package com.figolabs.figochat

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM encrypt/decrypt util. Every instance makes its own random 256-bit key, held only in memory,
 * and every [encrypt] call uses a fresh random 12-byte IV. Output is `<iv base64>:<ciphertext+tag base64>`.
 *
 * The SDK uses it to keep the realtime settings returned by GET /api/v1/realtime/config encrypted while
 * they sit in memory; they are decrypted only at the moment a thread channel is subscribed.
 */
class FigoCrypto {
    private val random = SecureRandom()
    private val key = SecretKeySpec(ByteArray(32).also(random::nextBytes), "AES")

    fun encrypt(plainText: String): String {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val sealed = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return "${b64.encodeToString(iv)}:${b64.encodeToString(sealed)}"
    }

    fun decrypt(cipherText: String): String {
        val separator = cipherText.indexOf(':')
        require(separator > 0 && separator < cipherText.length - 1) { "FigoCrypto: value is not in \"<iv>:<ciphertext>\" form." }
        val iv = b64d.decode(cipherText.substring(0, separator))
        val sealed = b64d.decode(cipherText.substring(separator + 1))
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(sealed), Charsets.UTF_8)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        val b64: Base64.Encoder = Base64.getEncoder()
        val b64d: Base64.Decoder = Base64.getDecoder()
    }
}

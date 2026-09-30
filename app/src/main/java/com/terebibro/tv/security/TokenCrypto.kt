package com.terebibro.tv.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Pure-JVM token and hashing helpers. No Android framework classes, so the
 * logic is directly unit-testable.
 */
object TokenCrypto {

    private const val TOKEN_BYTES = 32

    private val secureRandom = SecureRandom()

    /** 32 random bytes, base64url without padding -> 43 characters. */
    fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** Lowercase hex SHA-256 of the UTF-8 representation of [value]. */
    fun sha256Hex(value: String): String =
        toHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))

    /**
     * Constant-time string comparison. Comparison is done over the raw bytes
     * with [MessageDigest.isEqual], which does not short-circuit on the first
     * differing byte.
     */
    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    /** True when sha256([token]) equals [expectedSha256Hex], compared in constant time. */
    fun verifyToken(token: String, expectedSha256Hex: String): Boolean =
        constantTimeEquals(sha256Hex(token), expectedSha256Hex)

    fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}

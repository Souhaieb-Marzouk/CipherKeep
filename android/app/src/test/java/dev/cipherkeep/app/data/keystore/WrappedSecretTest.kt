package dev.cipherkeep.app.data.keystore

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WrappedSecretTest {
    @Test
    fun encodesAndDecodes() {
        val wrapped = WrappedSecret(
            WrappedSecret.VERSION,
            ByteArray(12) { it.toByte() },
            ByteArray(32) { (it + 40).toByte() },
        )
        val decoded = WrappedSecret.decode(wrapped.encode())
        assertEquals(wrapped.version, decoded.version)
        assertArrayEquals(wrapped.iv, decoded.iv)
        assertArrayEquals(wrapped.ciphertext, decoded.ciphertext)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsShortCiphertext() {
        WrappedSecret.decode(
            java.util.Base64.getEncoder().encodeToString(ByteArray(16)),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownVersion() {
        val encoded = java.util.Base64.getEncoder().encodeToString(
            ByteArray(1 + 12 + 16).also { it[0] = 9 },
        )
        WrappedSecret.decode(encoded)
    }
}

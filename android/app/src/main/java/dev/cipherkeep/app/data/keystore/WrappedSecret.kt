package dev.cipherkeep.app.data.keystore

import java.util.Base64

/**
 * AES-GCM ciphertext plus its nonce. Encoded as `version || iv || ciphertext`.
 * The wrapped value is safe to persist in DataStore; it is useless without the
 * non-exportable Android Keystore key.
 */
data class WrappedSecret(
    val version: Byte,
    val iv: ByteArray,
    val ciphertext: ByteArray,
) {
    fun encode(): String {
        require(iv.size == IV_LEN) { "GCM IV must be 12 bytes" }
        require(ciphertext.size >= TAG_LEN) { "GCM ciphertext must include the tag" }
        val out = ByteArray(1 + IV_LEN + ciphertext.size)
        out[0] = version
        iv.copyInto(out, 1)
        ciphertext.copyInto(out, 1 + IV_LEN)
        return Base64.getEncoder().encodeToString(out)
    }

    companion object {
        const val VERSION: Byte = 1
        const val IV_LEN = 12
        const val TAG_LEN = 16

        fun decode(encoded: String): WrappedSecret {
            val bytes = Base64.getDecoder().decode(encoded)
            require(bytes.size >= 1 + IV_LEN + TAG_LEN) { "wrapped secret is too short" }
            require(bytes[0] == VERSION) { "unsupported wrapped-secret version" }
            val iv = bytes.copyOfRange(1, 1 + IV_LEN)
            val ciphertext = bytes.copyOfRange(1 + IV_LEN, bytes.size)
            return WrappedSecret(VERSION, iv, ciphertext)
        }
    }
}

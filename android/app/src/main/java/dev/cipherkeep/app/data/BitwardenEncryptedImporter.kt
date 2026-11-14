package dev.cipherkeep.app.data

import android.util.Base64
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Decrypts Bitwarden password-protected encrypted JSON exports.
 *
 * Only the PBKDF2-HMAC-SHA256 KDF is supported; Bitwarden's optional Argon2id
 * KDF is rejected rather than guessed.
 */
object BitwardenEncryptedImporter {
    fun looksEncrypted(bytes: ByteArray): Boolean = runCatching {
        val json = JSONObject(bytes.decodeToString())
        json.optBoolean("encrypted") && json.optBoolean("passwordProtected") &&
            json.has("data") && json.has("salt")
    }.getOrDefault(false)

    fun decrypt(bytes: ByteArray, password: String): ByteArray {
        val json = JSONObject(bytes.decodeToString())
        require(json.optBoolean("encrypted") && json.optBoolean("passwordProtected"))
        val kdfType = json.optInt("kdfType", 0)
        require(kdfType == 0) { "Bitwarden Argon2id exports are not supported yet" }
        val iterations = json.optInt("kdfIterations", 600_000)
        require(iterations in 1..10_000_000)
        val saltText = json.getString("salt")
        val salt = runCatching { Base64.decode(saltText, Base64.DEFAULT) }
            .getOrElse { saltText.toByteArray() }

        val passwordChars = password.toCharArray()
        val master = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec(passwordChars, salt, iterations, 256))
                .encoded
        } finally {
            passwordChars.fill('\u0000')
        }
        val encKey = hkdfExpand(master, "enc".toByteArray())
        val macKey = hkdfExpand(master, "mac".toByteArray())
        master.fill(0)

        val parts = json.getString("data").split("|")
        require(parts.size == 3 && parts[0] == "2") { "unsupported Bitwarden cipher" }
        val iv = Base64.decode(parts[1], Base64.DEFAULT)
        val ciphertext = Base64.decode(parts[2], Base64.DEFAULT)
        val mac = Base64.decode(parts[2], Base64.DEFAULT)
        val computed = hmac(macKey, iv + ciphertext)
        require(constantTimeEquals(computed, mac)) { "Bitwarden MAC verification failed" }
        macKey.fill(0)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(encKey, "AES"), IvParameterSpec(iv))
        return try {
            cipher.doFinal(ciphertext)
        } finally {
            encKey.fill(0)
        }
    }

    private fun hkdfExpand(key: ByteArray, info: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update(info)
        mac.update(1)
        return mac.doFinal()
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean {
        if (left.size != right.size) return false
        var difference = 0
        for (index in left.indices) difference = difference or (left[index].toInt() xor right[index].toInt())
        return difference == 0
    }
}

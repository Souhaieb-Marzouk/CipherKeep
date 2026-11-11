package dev.cipherkeep.app.data.keystore

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Non-exportable AES-256-GCM key in Android Keystore.
 *
 * StrongBox is attempted first on API 28+ and accepted only after a probe
 * encrypt/decrypt succeeds. Otherwise the key lives in the TEE. The selected
 * storage class is returned so the caller can record it non-secretly.
 */
class KeystoreManager(
    private val alias: String = DEFAULT_ALIAS,
) {
    enum class Storage {
        STRONGBOX,
        TEE,
    }

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun ensureKey(): Storage {
        if (keyStore.containsAlias(alias)) {
            return Storage.TEE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val key = generate(strongBox = true)
                probe(key)
                return Storage.STRONGBOX
            } catch (_: Exception) {
                deleteKey()
            }
        }
        val key = generate(strongBox = false)
        probe(key)
        return Storage.TEE
    }

    fun wrap(plaintext: ByteArray): WrappedSecret {
        val key = existingKey() ?: error("Keystore key missing")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(plaintext)
        return WrappedSecret(WrappedSecret.VERSION, cipher.iv, ciphertext)
    }

    fun unwrap(wrapped: WrappedSecret): ByteArray {
        val key = existingKey() ?: throw KeystoreUnavailable()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(128, wrapped.iv),
        )
        return cipher.doFinal(wrapped.ciphertext)
    }

    fun deleteKey() {
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
        }
    }

    private fun existingKey(): SecretKey? =
        keyStore.getKey(alias, null) as? SecretKey

    private fun generate(strongBox: Boolean): SecretKey {
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // Biometric gating is handled by the app. The database key must remain
            // usable for the current unlocked-foreground lifecycle until then.
            .setUserAuthenticationRequired(false)
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // The wrapped database key is unavailable while the device is locked.
            builder.setUnlockedDeviceRequired(true)
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(builder.build())
        return generator.generateKey()
    }

    private fun probe(key: SecretKey) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(PROBE)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        check(cipher.doFinal(ciphertext).contentEquals(PROBE)) { "Keystore probe failed" }
    }

    companion object {
        const val DEFAULT_ALIAS = "cipherkeep.db.v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private val PROBE = byteArrayOf(0x43, 0x4B, 0x01)
    }
}

class KeystoreUnavailable : Exception("Keystore key is unavailable")

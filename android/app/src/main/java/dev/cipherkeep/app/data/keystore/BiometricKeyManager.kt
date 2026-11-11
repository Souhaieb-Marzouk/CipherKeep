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
 * AES-256-GCM key that requires biometric/device-credential authentication for
 * every use. The cipher is passed to BiometricPrompt as a CryptoObject; after
 * success the framework lets the pending `doFinal` complete.
 */
class BiometricKeyManager(
    private val alias: String = DEFAULT_ALIAS,
) {
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun ensureKey(): SecretKey {
        existingKey()?.let { return it }
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG or
                    KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
        } else {
            // Auth-per-use. BiometricPrompt supplies the user authentication.
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(builder.build())
        return generator.generateKey()
    }

    /**
     * Create an encryption cipher. Auth-per-use may throw
     * UserNotAuthenticatedException during init; the cipher is still handed to
     * BiometricPrompt and completes after authentication.
     */
    fun encryptCipher(): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            cipher.init(Cipher.ENCRYPT_MODE, ensureKey())
        } catch (_: android.security.keystore.UserNotAuthenticatedException) {
            // Expected until BiometricPrompt authenticates the key.
        }
        return cipher
    }

    /** Create a decrypt cipher for a stored IV, with the same auth contract. */
    fun decryptCipher(iv: ByteArray): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            cipher.init(
                Cipher.DECRYPT_MODE,
                ensureKey(),
                GCMParameterSpec(128, iv),
            )
        } catch (_: android.security.keystore.UserNotAuthenticatedException) {
            // Expected until BiometricPrompt authenticates the key.
        }
        return cipher
    }

    fun deleteKey() {
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
        }
    }

    private fun existingKey(): SecretKey? = keyStore.getKey(alias, null) as? SecretKey

    companion object {
        const val DEFAULT_ALIAS = "cipherkeep.biometric.v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

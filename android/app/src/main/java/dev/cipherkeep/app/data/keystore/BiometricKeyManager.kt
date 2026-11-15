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
 * AES-256-GCM key unlocked by a successful biometric or device-credential
 * authentication, usable for a short window afterwards.
 *
 * A time-bound key is used instead of an auth-per-use key on purpose: on several
 * devices an auth-per-use cipher cannot be initialised once the screen has been
 * locked, so the prompt would succeed and `doFinal` would fail on an
 * uninitialised cipher. Callers must authenticate first, then call [encrypt] or
 * [decrypt] immediately.
 */
class BiometricKeyManager(
    private val alias: String = DEFAULT_ALIAS,
) {
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun ensureKey(): SecretKey {
        existingKey()?.let { return it }
        deleteLegacyKey()
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
                AUTH_VALIDITY_SECONDS,
                KeyProperties.AUTH_BIOMETRIC_STRONG or
                    KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_SECONDS)
        }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(builder.build())
        return generator.generateKey()
    }

    /** Wrap [master]. Call only after BiometricPrompt reported success. */
    fun encrypt(master: ByteArray): WrappedSecret {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, ensureKey())
        val ciphertext = cipher.doFinal(master)
        return WrappedSecret(WrappedSecret.VERSION, cipher.iv, ciphertext)
    }

    /** Unwrap [wrapped]. Call only after BiometricPrompt reported success. */
    fun decrypt(wrapped: WrappedSecret): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            ensureKey(),
            GCMParameterSpec(TAG_BITS, wrapped.iv),
        )
        return cipher.doFinal(wrapped.ciphertext)
    }

    fun deleteKey() {
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
        }
    }

    private fun deleteLegacyKey() {
        runCatching {
            if (keyStore.containsAlias(LEGACY_ALIAS)) {
                keyStore.deleteEntry(LEGACY_ALIAS)
            }
        }
    }

    private fun existingKey(): SecretKey? = keyStore.getKey(alias, null) as? SecretKey

    companion object {
        const val DEFAULT_ALIAS = "cipherkeep.biometric.v2"
        private const val LEGACY_ALIAS = "cipherkeep.biometric.v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val AUTH_VALIDITY_SECONDS = 60
    }
}

package dev.cipherkeep.app.data.keystore

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.biometricDataStore by preferencesDataStore(name = "cipherkeep.biometric")

/** Stores the master key only as auth-bound Keystore ciphertext. */
class BiometricStore(private val context: Context) {
    suspend fun load(): WrappedSecret? {
        val encoded = context.biometricDataStore.data.first()[WRAPPED_MASTER] ?: return null
        return try {
            WrappedSecret.decode(encoded)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    suspend fun save(wrapped: WrappedSecret) {
        context.biometricDataStore.edit { it[WRAPPED_MASTER] = wrapped.encode() }
    }

    suspend fun clear() {
        context.biometricDataStore.edit { it.clear() }
    }

    companion object {
        private val WRAPPED_MASTER = stringPreferencesKey("wrapped_master")
    }
}

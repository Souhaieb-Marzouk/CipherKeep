package dev.cipherkeep.app.data.keystore

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.biometricDataStore by preferencesDataStore(name = "cipherkeep.biometric")

/**
 * Stores the master key only as Keystore ciphertext.
 *
 * [FORMAT] distinguishes wraps made with the time-bound key from the legacy
 * auth-per-use wrap, which cannot be opened reliably after a screen lock. A
 * legacy wrap is reported through [hasLegacySecret] so the UI can ask the user
 * to enable biometric unlock again.
 */
class BiometricStore(private val context: Context) {
    suspend fun load(): WrappedSecret? {
        val prefs = context.biometricDataStore.data.first()
        if ((prefs[FORMAT] ?: 0) != CURRENT_FORMAT) return null
        val encoded = prefs[WRAPPED_MASTER] ?: return null
        return try {
            WrappedSecret.decode(encoded)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    suspend fun save(wrapped: WrappedSecret) {
        context.biometricDataStore.edit {
            it[WRAPPED_MASTER] = wrapped.encode()
            it[FORMAT] = CURRENT_FORMAT
        }
    }

    /** True when an old wrap exists that must be recreated by the user. */
    suspend fun hasLegacySecret(): Boolean {
        val prefs = context.biometricDataStore.data.first()
        return prefs[WRAPPED_MASTER] != null && (prefs[FORMAT] ?: 0) != CURRENT_FORMAT
    }

    suspend fun clear() {
        context.biometricDataStore.edit { it.clear() }
    }

    companion object {
        private val WRAPPED_MASTER = stringPreferencesKey("wrapped_master")
        private val FORMAT = intPreferencesKey("wrapped_format")
        private const val CURRENT_FORMAT = 2
    }
}

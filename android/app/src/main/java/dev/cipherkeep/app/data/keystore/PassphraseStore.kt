package dev.cipherkeep.app.data.keystore

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.security.SecureRandom

private val Context.keyDataStore by preferencesDataStore(name = "cipherkeep.keys")

/**
 * Generates and persists the 32-byte SQLCipher passphrase.
 *
 * Only the Keystore-wrapped ciphertext is written to DataStore. A wrapped value
 * that cannot be opened is surfaced as [PassphraseUnavailable]; it is never
 * silently replaced, because doing so would look like data loss recovery while
 * actually orphaning the existing database.
 */
class PassphraseStore(
    private val context: Context,
    private val keystore: KeystoreManager = KeystoreManager(),
) {
    suspend fun getOrCreate(): ByteArray {
        val prefs = context.keyDataStore.data.first()
        val wrapped = prefs[WRAPPED_PASSPHRASE]
        if (wrapped != null) {
            return try {
                keystore.unwrap(WrappedSecret.decode(wrapped))
            } catch (error: Exception) {
                throw PassphraseUnavailable(error)
            }
        }

        val storage = keystore.ensureKey()
        val passphrase = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val encoded = keystore.wrap(passphrase).encode()
        try {
            context.keyDataStore.edit { prefs ->
                prefs[WRAPPED_PASSPHRASE] = encoded
                prefs[KEY_STORAGE] = storage.name
            }
        } catch (error: Exception) {
            passphrase.fill(0)
            throw error
        }
        return passphrase
    }

    suspend fun recordedStorage(): String? =
        context.keyDataStore.data.first()[KEY_STORAGE]

    suspend fun reset() {
        context.keyDataStore.edit { it.clear() }
        keystore.deleteKey()
    }

    companion object {
        private val WRAPPED_PASSPHRASE = stringPreferencesKey("wrapped_db_passphrase")
        private val KEY_STORAGE = stringPreferencesKey("key_storage")
    }
}

class PassphraseUnavailable(cause: Throwable) :
    Exception("vault database key is unavailable", cause)

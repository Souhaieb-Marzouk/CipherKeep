package dev.cipherkeep.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.util.Base64

private val Context.vaultDataStore by preferencesDataStore(name = "cipherkeep.vault")

/** Persists only the public vault header and recovery envelope. */
class VaultBlobStore(private val context: Context) {
    suspend fun load(): ByteArray? {
        val encoded = context.vaultDataStore.data.first()[VAULT_BLOB] ?: return null
        return try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    suspend fun save(blob: ByteArray) {
        val encoded = Base64.getEncoder().encodeToString(blob)
        context.vaultDataStore.edit { it[VAULT_BLOB] = encoded }
    }

    suspend fun clear() {
        context.vaultDataStore.edit { it.clear() }
    }

    companion object {
        private val VAULT_BLOB = stringPreferencesKey("vault_blob")
    }
}

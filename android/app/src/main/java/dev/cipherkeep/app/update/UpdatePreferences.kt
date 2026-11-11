package dev.cipherkeep.app.update

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.updateDataStore by preferencesDataStore(name = "cipherkeep.updates")

/** Cache contract used by the checker and its JVM tests. */
interface UpdateCache {
    suspend fun etag(): String?
    suspend fun cachedBody(): String?
    suspend fun save(etag: String, body: String)
}

/** Non-secret update settings and ETag/body cache. */
class UpdatePreferences(private val context: Context) : UpdateCache {
    suspend fun dailyEnabled(): Boolean =
        context.updateDataStore.data.first()[DAILY] ?: false

    suspend fun setDailyEnabled(enabled: Boolean) {
        context.updateDataStore.edit { it[DAILY] = enabled }
    }

    override suspend fun etag(): String? = context.updateDataStore.data.first()[ETAG]

    override suspend fun cachedBody(): String? = context.updateDataStore.data.first()[CACHED_BODY]

    override suspend fun save(etag: String, body: String) {
        context.updateDataStore.edit {
            it[ETAG] = etag
            it[CACHED_BODY] = body
        }
    }

    companion object {
        private val DAILY = booleanPreferencesKey("daily_check")
        private val ETAG = stringPreferencesKey("release_etag")
        private val CACHED_BODY = stringPreferencesKey("release_body")
    }
}

package dev.cipherkeep.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.unlockDataStore by preferencesDataStore(name = "cipherkeep.unlock")

/** Shared delay policy for failed master-password attempts. */
object UnlockBackoffPolicy {
    const val FREE_ATTEMPTS = 5
    const val BASE_DELAY_SECONDS = 30L
    const val MAX_DELAY_SECONDS = 3_600L

    fun delaySeconds(failedAttempts: Int): Long {
        if (failedAttempts < FREE_ATTEMPTS) return 0
        val exponent = (failedAttempts - FREE_ATTEMPTS).coerceIn(0, 7)
        return (BASE_DELAY_SECONDS shl exponent).coerceAtMost(MAX_DELAY_SECONDS)
    }
}

data class UnlockBackoffState(val failedAttempts: Int, val nextAttemptAt: Long) {
    fun remaining(now: Long): Long = (nextAttemptAt - now).coerceAtLeast(0)
}

/** Persisted across process death; a force-stop must not reset the delay. */
class UnlockBackoffStore(private val context: Context) {
    suspend fun read(): UnlockBackoffState {
        val prefs = context.unlockDataStore.data.first()
        return UnlockBackoffState(
            failedAttempts = prefs[FAILED] ?: 0,
            nextAttemptAt = prefs[NEXT_AT] ?: 0,
        )
    }

    suspend fun recordFailure(now: Long): UnlockBackoffState {
        val current = read()
        val failed = current.failedAttempts + 1
        val delay = UnlockBackoffPolicy.delaySeconds(failed)
        val next = if (delay == 0L) 0L else now + delay
        context.unlockDataStore.edit {
            it[FAILED] = failed
            it[NEXT_AT] = next
        }
        return UnlockBackoffState(failed, next)
    }

    suspend fun clear() {
        context.unlockDataStore.edit {
            it.remove(FAILED)
            it.remove(NEXT_AT)
        }
    }

    private companion object {
        val FAILED = intPreferencesKey("failed_attempts")
        val NEXT_AT = longPreferencesKey("next_attempt_at")
    }
}

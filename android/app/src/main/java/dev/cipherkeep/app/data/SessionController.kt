package dev.cipherkeep.app.data

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.cipherkeep.core.FfiException
import dev.cipherkeep.core.FfiVaultSession
import dev.cipherkeep.core.numericCode
import dev.cipherkeep.app.data.keystore.BiometricStore
import dev.cipherkeep.app.data.keystore.WrappedSecret
import dev.cipherkeep.app.data.db.VaultRepository
import org.json.JSONObject
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface VaultUiState {
    data object Loading : VaultUiState
    data object Setup : VaultUiState
    data class Recovery(
        val shares: List<String>,
        val index: Int = 0,
        val afterRecovery: Boolean = false,
    ) : VaultUiState
    data class Locked(
        val error: String? = null,
        val retryAfterSeconds: Long = 0,
    ) : VaultUiState
    data object LockWarning : VaultUiState
    data object Unlocked : VaultUiState
}

/**
 * Owns the shared vault session and the persisted vault blob. All Argon2id work runs
 * on Dispatchers.IO; the controller never stores the master password.
 */
class SessionController(context: Context) {
    private val blobs = VaultBlobStore(context.applicationContext)
    private val biometrics = BiometricStore(context.applicationContext)
    private val storage = VaultStorage(context.applicationContext)
    private var repository: VaultRepository? = null
    private var session: FfiVaultSession? = null
    private var blob: ByteArray? = null

    var state: VaultUiState by mutableStateOf(VaultUiState.Loading)
        private set

    var items: List<VaultItem> by mutableStateOf(emptyList())
        private set

    private var backgroundedAt: Long? = null

    suspend fun bootstrap() {
        blob = blobs.load()
        if (blob == null) {
            state = VaultUiState.Setup
            return
        }
        session = withContext(Dispatchers.IO) {
            runCatching { FfiVaultSession.open(blob!!) }.getOrNull()
        }
        state = if (session == null) {
            VaultUiState.Locked("Vault metadata could not be opened.")
        } else {
            VaultUiState.Locked()
        }
    }

    suspend fun create(password: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = password.encodeToByteArray()
            try {
                val created = FfiVaultSession.create(bytes)
                val encoded = created.blob()
                blobs.save(encoded)
                session?.close()
                session = created
                blob = encoded
                biometrics.clear()
                openStorage()
                loadItems()
                state = VaultUiState.Recovery(created.recoveryShares())
            } finally {
                bytes.fill(0)
            }
        }
    }

    suspend fun unlock(password: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val locked = session ?: run {
                val loaded = blob ?: error("no vault")
                FfiVaultSession.open(loaded).also { session = it }
            }
            val bytes = password.encodeToByteArray()
            try {
                locked.unlockAgain(bytes)
                openStorage()
                loadItems()
                state = VaultUiState.Unlocked
            } finally {
                bytes.fill(0)
            }
        }.recoverCatching { error ->
            val code = (error as? FfiException)?.numericCode() ?: 10
            val retry = session?.retryAfter()?.toLong() ?: 0L
            state = VaultUiState.Locked(
                error = when (code) {
                    3 -> "Vault could not be opened. Check your master password."
                    8 -> "Too many attempts. Wait $retry seconds before trying again."
                    11 -> "The password does not meet the minimum policy."
                    else -> "Vault could not be opened."
                },
                retryAfterSeconds = retry,
            )
            throw error
        }
    }

    suspend fun recover(
        first: String,
        second: String,
        newPassword: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val current = blob ?: error("no vault")
            val bytes = newPassword.encodeToByteArray()
            try {
                val recovered = FfiVaultSession.recover(current, first.trim(), second.trim(), bytes)
                val encoded = recovered.blob()
                blobs.save(encoded)
                session?.close()
                session = recovered
                blob = encoded
                biometrics.clear()
                openStorage()
                loadItems()
                state = VaultUiState.Recovery(
                    shares = recovered.recoveryShares(),
                    afterRecovery = true,
                )
            } finally {
                bytes.fill(0)
            }
        }
    }

    suspend fun biometricWrapped(): WrappedSecret? = biometrics.load()

    suspend fun saveBiometricWrapped(wrapped: WrappedSecret) {
        biometrics.save(wrapped)
    }

    suspend fun clearBiometric() {
        biometrics.clear()
    }

    /** Copy the master key for an immediate auth-bound Keystore wrap. */
    suspend fun biometricMasterKey(): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { session?.biometricMasterKey() }.getOrNull()
    }

    /** Complete biometric unlock with the master key unwrapped by Keystore. */
    suspend fun unlockWithMasterKey(masterKey: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val current = blob ?: error("no vault")
            val unlocked = FfiVaultSession.unlockWithMasterKey(current, masterKey)
            session?.close()
            session = unlocked
            openStorage()
            loadItems()
            state = VaultUiState.Unlocked
        }
    }

    private suspend fun openStorage() {
        repository = runCatching { storage.repository() }.getOrNull()
    }

    private suspend fun loadItems() {
        val repo = repository ?: return
        val active = session ?: return
        items = runCatching {
            repo.list().mapNotNull { entity ->
                runCatching {
                    val json = JSONObject(active.decryptItem(entity.itemId, entity.ciphertext).decodeToString())
                    VaultItem(
                        id = entity.itemId,
                        title = json.optString("title"),
                        username = json.optString("username"),
                        password = json.optString("password"),
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    suspend fun saveItem(title: String, username: String, password: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val active = session ?: error("vault is locked")
                val repo = repository ?: error("storage is unavailable")
                val id = ByteArray(16).also { SecureRandom().nextBytes(it) }
                val payload = JSONObject()
                    .put("title", title)
                    .put("username", username)
                    .put("password", password)
                    .toString()
                    .encodeToByteArray()
                val ciphertext = active.encryptItem(id, payload)
                payload.fill(0)
                repo.save(
                    itemId = id,
                    ciphertext = ciphertext,
                    lamport = 1,
                    tombstone = false,
                    schemaVersion = 1,
                    updatedAt = System.currentTimeMillis(),
                )
                loadItems()
            }
        }

    suspend fun deleteItem(item: VaultItem): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            repository?.delete(item.id)
            loadItems()
        }
    }

    fun lock() {
        session?.lock()
        items = emptyList()
        backgroundedAt = null
        state = VaultUiState.Locked()
    }

    /** Called from the lifecycle observer when the activity stops. */
    fun onBackground(now: Long = SystemClock.elapsedRealtime()) {
        backgroundedAt = now
    }

    /** Lock if the app was backgrounded for at least the 60-second timeout. */
    fun onForeground(now: Long = SystemClock.elapsedRealtime()) {
        val since = backgroundedAt ?: return
        backgroundedAt = null
        if (state == VaultUiState.Unlocked && now - since >= AUTO_LOCK_MILLIS) {
            state = VaultUiState.LockWarning
        }
    }

    /** Keep the session after the user acknowledges the idle warning. */
    fun stayUnlocked() {
        backgroundedAt = null
        state = VaultUiState.Unlocked
    }

    fun finishRecovery() {
        state = VaultUiState.Unlocked
    }

    fun nextShare() {
        val current = state as? VaultUiState.Recovery ?: return
        state = if (current.index + 1 >= current.shares.size) {
            VaultUiState.Unlocked
        } else {
            current.copy(index = current.index + 1)
        }
    }

    fun close() {
        session?.close()
        session = null
    }

    companion object {
        const val AUTO_LOCK_MILLIS = 60_000L
    }
}

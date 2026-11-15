package dev.cipherkeep.app.data

import android.content.Context
import android.util.Base64
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.cipherkeep.core.FfiException
import dev.cipherkeep.core.FfiVaultSession
import dev.cipherkeep.core.mergeSyncRecordsV2
import dev.cipherkeep.core.resolveSyncConflict
import dev.cipherkeep.core.numericCode
import dev.cipherkeep.app.data.keystore.BiometricStore
import dev.cipherkeep.app.data.keystore.WrappedSecret
import dev.cipherkeep.app.data.alias.AliasRecord
import dev.cipherkeep.app.data.emergency.EmergencyContactInput
import dev.cipherkeep.app.data.db.VaultRepository
import org.json.JSONArray
import org.json.JSONObject
import dev.cipherkeep.core.exportPortable
import dev.cipherkeep.core.importPortable
import dev.cipherkeep.core.totpCode
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Result of one password-manager import, including per-record failures. */
data class ImportSummary(
    val imported: Int,
    val skipped: Int,
    val failed: Int,
    val total: Int,
)

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
    data object Travel : VaultUiState
    data object LockWarning : VaultUiState
    data class StorageError(val message: String) : VaultUiState
    data object Unlocked : VaultUiState
}

/**
 * Owns the shared vault session and the persisted vault blob. All Argon2id work runs
 * on Dispatchers.IO; the controller never stores the master password.
 */
private val FOLDER_META_ID = byteArrayOf(
    0x43, 0x4b, 0x46, 0x4f, 0x4c, 0x44, 0x45, 0x52,
    0x4d, 0x45, 0x54, 0x41, 0x30, 0x30, 0x30, 0x31,
)

class SessionController(context: Context) {
    private val appContext = context.applicationContext
    private val blobs = VaultBlobStore(context.applicationContext)
    private val biometrics = BiometricStore(context.applicationContext)
    private val storage = VaultStorage(context.applicationContext)
    private val backoff = UnlockBackoffStore(context.applicationContext)
    private val travel = TravelModeStore(context.applicationContext)
    private val rotationJournal = RotationJournal(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var autoLockJob: Job? = null
    private var repository: VaultRepository? = null
    private var session: FfiVaultSession? = null
    private var blob: ByteArray? = null

    var state: VaultUiState by mutableStateOf(VaultUiState.Loading)
        private set

    var items: List<VaultItem> by mutableStateOf(emptyList())
        private set

    private var backgroundedAt: Long? = null

    // The master password is kept only while the vault is unlocked so a join
    // can rebuild the vault without asking the user to type it again. It is
    // cleared on every lock, travel-mode entry and close.
    private var sessionPassword: CharArray? = null

    private fun rememberPassword(password: String) {
        sessionPassword?.fill('\u0000')
        sessionPassword = password.toCharArray()
    }

    private fun clearPassword() {
        sessionPassword?.fill('\u0000')
        sessionPassword = null
    }

    private fun currentPassword(): CharArray? =
        sessionPassword?.takeIf { it.isNotEmpty() }

    /** Persisted per-device sync actor id. */
    private fun deviceActor(): ByteArray {
        val prefs = appContext.getSharedPreferences("sync", Context.MODE_PRIVATE)
        val stored = prefs.getString("device_actor", null)
        if (stored != null && stored.length == 32) return hexBytes(stored)
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString("device_actor", bytes.joinToString("") { "%02x".format(it) })
            .apply()
        return bytes
    }

    /** Configured auto-lock timeout in milliseconds. */
    fun lockTimeoutMillis(): Long =
        appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getLong(LOCK_TIMEOUT_KEY, AUTO_LOCK_MILLIS)

    /** Change the auto-lock timeout and re-arm the running timer. */
    fun setLockTimeoutMillis(value: Long) {
        val clamped = value.coerceIn(MIN_LOCK_TIMEOUT_MILLIS, MAX_LOCK_TIMEOUT_MILLIS)
        appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putLong(LOCK_TIMEOUT_KEY, clamped)
            .apply()
        recordActivity()
    }

    suspend fun bootstrap() {
        blob = blobs.load()
        if (blob == null) {
            state = VaultUiState.Setup
            return
        }
        rotationJournal.load()?.let { journal ->
            runCatching {
                val repo = storage.repository()
                journal.items.forEach { entity ->
                    repo.save(
                        itemId = entity.itemId,
                        ciphertext = entity.ciphertext,
                        lamport = entity.lamport,
                        tombstone = entity.tombstone,
                        schemaVersion = entity.schemaVersion,
                        updatedAt = entity.updatedAt,
                    )
                }
                if (journal.blob.isNotEmpty()) {
                    blobs.save(journal.blob)
                    blob = journal.blob
                }
                rotationJournal.clear()
            }.onFailure {
                state = VaultUiState.StorageError("Rotation recovery failed; keep the app data intact.")
                return
            }
        }
        if (travel.enabled()) {
            state = VaultUiState.Travel
            return
        }
        session = withContext(Dispatchers.IO) {
            runCatching { FfiVaultSession.open(blob!!) }.getOrNull()
        }
        val guard = withContext(Dispatchers.IO) { backoff.read() }
        val retry = guard.remaining(System.currentTimeMillis() / 1000)
        state = if (session == null) {
            VaultUiState.Locked("Vault metadata could not be opened.", retry)
        } else {
            VaultUiState.Locked(retryAfterSeconds = retry)
        }
    }

    suspend fun joinFamilyVault(
        password: String,
        vaultId: String,
        vaultKeyHex: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = password.encodeToByteArray()
            try {
                val joined = FfiVaultSession.createShared(bytes, vaultId, vaultKeyHex)
                val encoded = joined.blob()
                blobs.save(encoded)
                session?.close()
                session = joined
                blob = encoded
                travel.clear()
                if (!openStorage()) error("storage unavailable")
                // A joined vault replaces the local identity, so stale items from
                // the previous vault must not be merged into the new one.
                repository?.list()?.forEach { entity -> repository?.delete(entity.itemId) }
                // A joined vault is a new device identity: never keep an actor id
                // cloned from another installation, and drop stale conflicts.
                appContext.getSharedPreferences("sync", Context.MODE_PRIVATE)
                    .edit()
                    .remove("device_actor")
                    .putString("conflict_inputs", "[]")
                    .apply()
                loadItems()
                state = VaultUiState.Unlocked
                recordActivity()
            } finally {
                bytes.fill(0)
            }
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
                rememberPassword(password)
                appContext.getSharedPreferences("sync", Context.MODE_PRIVATE)
                    .edit()
                    .remove("device_actor")
                    .putString("conflict_inputs", "[]")
                    .apply()
                biometrics.clear()
                backoff.clear()
                if (!openStorage()) {
                    state = VaultUiState.StorageError("The encrypted vault database could not be opened.")
                    return@runCatching
                }
                loadItems()
                state = VaultUiState.Recovery(created.recoveryShares())
                recordActivity()
            } finally {
                bytes.fill(0)
            }
        }
    }

    suspend fun unlock(password: String): Result<Unit> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis() / 1000
        val guard = backoff.read()
        val persistedRetry = guard.remaining(now)
        if (persistedRetry > 0) {
            state = VaultUiState.Locked(
                error = "Too many attempts. Wait $persistedRetry seconds before trying again.",
                retryAfterSeconds = persistedRetry,
            )
            return@withContext Result.failure(IllegalStateException("unlock delayed"))
        }
        runCatching {
            val locked = session ?: run {
                val loaded = blob ?: error("no vault")
                FfiVaultSession.open(loaded).also { session = it }
            }
            val bytes = password.encodeToByteArray()
            try {
                locked.unlockAgain(bytes)
                rememberPassword(password)
                backoff.clear()
                if (!openStorage()) {
                    state = VaultUiState.StorageError("The encrypted vault database could not be opened.")
                    return@runCatching
                }
                travel.clear()
                loadItems()
                state = VaultUiState.Unlocked
                recordActivity()
            } finally {
                bytes.fill(0)
            }
        }.recoverCatching { error ->
            val code = (error as? FfiException)?.numericCode() ?: 10
            val retry = if (code == 3) {
                backoff.recordFailure(now).remaining(now)
            } else {
                session?.retryAfter()?.toLong() ?: 0L
            }
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
                rememberPassword(newPassword)
                biometrics.clear()
                backoff.clear()
                if (!openStorage()) {
                    state = VaultUiState.StorageError("The encrypted vault database could not be opened.")
                    return@runCatching
                }
                loadItems()
                state = VaultUiState.Recovery(
                    shares = recovered.recoveryShares(),
                    afterRecovery = true,
                )
                recordActivity()
            } finally {
                bytes.fill(0)
            }
        }
    }

    suspend fun biometricWrapped(): WrappedSecret? = biometrics.load()

    /** True when a pre-2.2.7 biometric wrap must be recreated by the user. */
    suspend fun hasLegacyBiometric(): Boolean = biometrics.hasLegacySecret()

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
            backoff.clear()
            if (!openStorage()) {
                state = VaultUiState.StorageError("The encrypted vault database could not be opened.")
                return@runCatching
            }
            loadItems()
            state = VaultUiState.Unlocked
            recordActivity()
        }
    }

    private suspend fun openStorage(): Boolean {
        repository = runCatching { storage.repository() }.getOrNull()
        return repository != null
    }

    private suspend fun loadItems() {
        if (travel.enabled()) {
            items = emptyList()
            return
        }
        val repo = repository ?: return
        val active = session ?: return
        items = runCatching {
            repo.list().mapNotNull { entity ->
                if (entity.itemId.contentEquals(FOLDER_META_ID) || entity.tombstone) {
                    return@mapNotNull null
                }
                runCatching {
                    val plaintext = runCatching {
                        entity.syncRecord?.let { active.applySyncRecord(it) }
                    }.getOrNull()
                        ?: active.decryptItem(entity.itemId, entity.ciphertext)
                    val json = JSONObject(plaintext.decodeToString())

                    VaultItem(
                        id = entity.itemId,
                        title = json.optString("title"),
                        username = json.optString("username"),
                        password = json.optString("password"),
                        type = json.optString("type", "login"),
                        totpUri = json.optString("totpUri"),
                        url = json.optString("url"),
                        notes = json.optString("notes"),
                        cardholder = json.optString("cardholder"),
                        cardNumber = json.optString("cardNumber"),
                        cardExpiry = json.optString("cardExpiry"),
                        cardCvv = json.optString("cardCvv"),
                        fullName = json.optString("fullName"),
                        email = json.optString("email"),
                        phone = json.optString("phone"),
                        address = json.optString("address"),
                        company = json.optString("company"),
                        jobTitle = json.optString("jobTitle"),
                        folder = json.optString("folder"),
                        tags = json.optJSONArray("tags")?.let { array ->
                            (0 until array.length()).mapNotNull { index ->
                                array.optString(index).takeIf { it.isNotBlank() }
                            }
                        } ?: emptyList(),
                        favorite = json.optBoolean("favorite"),
                        autofillAllowed = json.optBoolean("autofillAllowed"),
                        updatedAt = entity.updatedAt,
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    suspend fun saveItem(
        title: String,
        username: String,
        password: String,
        url: String = "",
        notes: String = "",
        folder: String = "",
        tags: String = "",
        favorite: Boolean = false,
        autofillAllowed: Boolean = true,
        totpUri: String = "",
        reload: Boolean = true,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(title.isNotBlank())
                require(
                    url.isBlank() ||
                        runCatching { java.net.URI(url).host }.getOrNull() != null,
                ) { "invalid URL" }
                val active = session ?: error("vault is locked")
                val repo = repository ?: error("storage is unavailable")
                val id = ByteArray(16).also { SecureRandom().nextBytes(it) }
                val payload = JSONObject()
                    .put("v", 1)
                    .put("type", "login")
                    .put("title", title)
                    .put("username", username)
                    .put("password", password)
                    .put("url", url)
                    .put("notes", notes)
                    .put("folder", folder)
                    .put(
                        "tags",
                        org.json.JSONArray(
                            tags.split(',').map(String::trim).filter(String::isNotEmpty),
                        ),
                    )
                    .put("favorite", favorite)
                    .put("autofillAllowed", autofillAllowed && DisplayPreferences(appContext).autofillDefault())
                    .put("totpUri", totpUri)
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
                if (reload) loadItems()
            }
        }

    suspend fun saveTypedItem(
        type: String,
        title: String,
        fields: Map<String, String>,
        folder: String = "",
        tags: String = "",
        favorite: Boolean = false,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(title.isNotBlank())
                require(type in setOf("note", "card", "identity", "login"))
                val active = session ?: error("vault is locked")
                val repo = repository ?: error("storage is unavailable")
                val id = ByteArray(16).also { SecureRandom().nextBytes(it) }
                val payload = JSONObject()
                    .put("v", 1)
                    .put("type", type)
                    .put("title", title)
                    .put("username", "")
                    .put("password", "")
                    .put("url", "")
                    .put("notes", fields["notes"].orEmpty())
                    .put("folder", folder)
                    .put("tags", org.json.JSONArray(tags.split(',').map(String::trim).filter(String::isNotEmpty)))
                    .put("favorite", favorite)
                    .put("autofillAllowed", DisplayPreferences(appContext).autofillDefault())
                fields.forEach { (key, value) -> payload.put(key, value) }
                val bytes = payload.toString().encodeToByteArray()
                val ciphertext = active.encryptItem(id, bytes)
                bytes.fill(0)
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

    fun recoveryShares(): List<String> = session?.recoveryShares() ?: emptyList()

    suspend fun saveEmergencyContacts(
        contacts: List<EmergencyContactInput>,
        policyId: String,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(contacts.isNotEmpty() && contacts.size <= 2 && policyId.isNotBlank())
                val active = session ?: error("vault is locked")
                val repo = repository ?: error("storage is unavailable")
                val id = ByteArray(16).also { SecureRandom().nextBytes(it) }
                val payload = JSONObject()
                    .put("v", 1)
                    .put("type", "emergency")
                    .put("title", "Emergency access contacts")
                    .put("policyId", policyId)
                    .put(
                        "contacts",
                        JSONArray().apply {
                            contacts.forEach {
                                put(JSONObject().put("id", it.id).put("address", it.address))
                            }
                        },
                    )
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

    suspend fun saveAlias(record: AliasRecord): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(record.address.contains('@'))
                val active = session ?: error("vault is locked")
                val repo = repository ?: error("storage is unavailable")
                val id = ByteArray(16).also { SecureRandom().nextBytes(it) }
                val payload = aliasPayload(record).encodeToByteArray()
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

    suspend fun setAliasEnabled(address: String, enabled: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val active = session ?: error("vault is locked")
                val repo = repository ?: error("storage is unavailable")
                val entity = repo.list().firstOrNull { candidate ->
                    val json = runCatching {
                        JSONObject(active.decryptItem(candidate.itemId, candidate.ciphertext).decodeToString())
                    }.getOrNull()
                    json?.optString("type") == "alias" && json.optString("username") == address
                } ?: error("alias not found")
                val json = JSONObject(active.decryptItem(entity.itemId, entity.ciphertext).decodeToString())
                json.put("enabled", enabled)
                val payload = json.toString().encodeToByteArray()
                val ciphertext = active.encryptItem(entity.itemId, payload)
                payload.fill(0)
                repo.save(
                    itemId = entity.itemId,
                    ciphertext = ciphertext,
                    lamport = maxOf(entity.lamport + 1, 2),
                    tombstone = false,
                    schemaVersion = entity.schemaVersion,
                    updatedAt = System.currentTimeMillis(),
                )
                loadItems()
            }
        }

    suspend fun deleteAliasItem(address: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val active = session ?: error("vault is locked")
                val repo = repository ?: error("storage is unavailable")
                val entity = repo.list().firstOrNull { candidate ->
                    val json = runCatching {
                        JSONObject(active.decryptItem(candidate.itemId, candidate.ciphertext).decodeToString())
                    }.getOrNull()
                    json?.optString("type") == "alias" && json.optString("username") == address
                } ?: error("alias not found")
                repo.save(
                    itemId = entity.itemId,
                    ciphertext = ByteArray(0),
                    lamport = maxOf(entity.lamport + 1, 2),
                    tombstone = true,
                    schemaVersion = entity.schemaVersion,
                    updatedAt = System.currentTimeMillis(),
                )
                loadItems()
            }
        }

    private fun aliasPayload(record: AliasRecord): String =
        JSONObject()
            .put("v", 1)
            .put("type", "alias")
            .put("title", record.label)
            .put("username", record.address)
            .put("password", "")
            .put("provider", record.provider)
            .put("providerId", record.providerId)
            .put("enabled", record.enabled)
            .put("url", "")
            .put("notes", "")
            .put("folder", "")
            .put("tags", JSONArray())
            .put("favorite", false)
            .toString()

    /**
     * Import a plaintext password-manager export.
     *
     * [onProgress] is called after every parsed record with the number processed
     * so the UI can show a determinate progress bar and live failure count.
     */
    suspend fun importPlaintext(
        bytes: ByteArray,
        mergeDuplicates: Boolean = false,
        onProgress: (done: Int, total: Int, imported: Int, failed: Int) -> Unit = { _, _, _, _ -> },
    ): Result<ImportSummary> =
        withContext(Dispatchers.IO) {
            runCatching {
                val active = session ?: error("vault is locked")
                if (active.isLocked()) error("vault is locked")
                val report = JSONObject(dev.cipherkeep.core.importPlaintextJson(bytes, mergeDuplicates))
                val items = report.getJSONArray("items")
                val total = items.length()
                var imported = 0
                var failed = 0
                onProgress(0, total, 0, 0)
                for (index in 0 until total) {
                    val item = items.getJSONObject(index)
                    val title = item.optString("title").trim()
                    if (title.isEmpty()) {
                        failed += 1
                    } else {
                        val rawNotes = item.optString("notes")
                        val totp = Regex("TOTP:\\s*(otpauth://\\S+)")
                            .find(rawNotes)?.groupValues?.get(1).orEmpty()
                        val notes = if (totp.isEmpty()) {
                            rawNotes
                        } else {
                            rawNotes.replace("TOTP: $totp", "").trim()
                        }
                        val result = runCatching {
                            saveItem(
                                title = title.take(512),
                                username = item.optString("username").take(4_096),
                                password = item.optString("password").take(65_536),
                                url = importUrl(item.optString("url")),
                                notes = notes.take(65_536),
                                folder = item.optString("folder").take(64),
                                totpUri = totp.take(4_096),
                                reload = false,
                            ).getOrThrow()
                        }
                        if (result.isSuccess) imported += 1 else failed += 1
                    }
                    onProgress(index + 1, total, imported, failed)
                }
                loadItems()
                ImportSummary(
                    imported = imported,
                    skipped = report.optInt("skippedDuplicates", 0),
                    failed = failed,
                    total = total,
                )
            }
        }

    /** Import a KeePass `.kdbx` database with the same progress contract. */
    suspend fun importKdbx(
        bytes: ByteArray,
        password: String,
        onProgress: (done: Int, total: Int, imported: Int, failed: Int) -> Unit = { _, _, _, _ -> },
    ): Result<ImportSummary> =
        withContext(Dispatchers.IO) {
            runCatching {
                val active = session ?: error("vault is locked")
                if (active.isLocked()) error("vault is locked")
                val report = JSONObject(dev.cipherkeep.core.importKdbxJson(bytes, password))
                val items = report.getJSONArray("items")
                val total = items.length()
                var imported = 0
                var failed = 0
                onProgress(0, total, 0, 0)
                for (index in 0 until total) {
                    val item = items.getJSONObject(index)
                    val title = item.optString("title").trim()
                    if (title.isEmpty()) {
                        failed += 1
                    } else {
                        val result = runCatching {
                            saveItem(
                                title = title.take(512),
                                username = item.optString("username").take(4_096),
                                password = item.optString("password").take(65_536),
                                url = importUrl(item.optString("url")),
                                notes = item.optString("notes").take(65_536),
                                folder = item.optString("folder").take(64),
                                reload = false,
                            ).getOrThrow()
                        }
                        if (result.isSuccess) imported += 1 else failed += 1
                    }
                    onProgress(index + 1, total, imported, failed)
                }
                loadItems()
                ImportSummary(
                    imported = imported,
                    skipped = report.optInt("skippedDuplicates", 0),
                    failed = failed,
                    total = total,
                )
            }
        }

    private fun importUrl(raw: String): String {
        val candidate = raw.take(2_048).split(',', limit = 2).first().trim()
        if (candidate.isEmpty()) return ""
        return if (runCatching { java.net.URI(candidate).host }.getOrNull() != null) candidate else ""
    }

    suspend fun saveTotpItem(title: String, account: String, uri: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val active = session ?: error("vault is locked")
                val repo = repository ?: error("storage is unavailable")
                // Validate before writing an unusable encrypted TOTP item.
                totpCode(uri, (System.currentTimeMillis() / 1000).toULong())
                val id = ByteArray(16).also { SecureRandom().nextBytes(it) }
                val payload = JSONObject()
                    .put("v", 1)
                    .put("type", "totp")
                    .put("title", title)
                    .put("username", account)
                    .put("totpUri", uri)
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

    fun syncNamespace(): String {
        val active = session ?: error("vault is locked")
        return active.vaultId().joinToString("") { "%02x".format(it) }
    }

    /**
     * Merge with the relay.
     *
     * @param push when false the merged state is applied locally only, which is
     *   the "receive changes" action; when true the merged state is uploaded as
     *   well, which is the "send changes" action.
     */
    suspend fun syncNow(
        endpoint: String,
        token: String,
        namespace: String,
        push: Boolean = true,
    ): SyncOutcome =
        withContext(Dispatchers.IO) {
            if (travel.enabled()) error("Travel Mode is active")
            val active = session ?: error("vault is locked")
            val repo = repository ?: error("storage is unavailable")
            if (!Regex("^[a-f0-9]{32}$").matches(namespace)) error("invalid sync namespace")
            // Tombstoned items stay in the local set while they carry a record so
            // their deletion keeps being pushed; live items always participate.
            val entities = repo.list().filter {
                !it.itemId.contentEquals(FOLDER_META_ID) &&
                    (it.syncRecord != null || !it.tombstone)
            }
            val actor = deviceActor()
            // Reuse the stored canonical record when the item has not changed
            // since the last sync. Re-exporting would re-seal every field with
            // a fresh nonce and make an unchanged item look like a concurrent
            // edit on every sync.
            val local = entities.map { entity ->
                val stored = entity.syncRecord?.decodeToString()
                if (stored != null) {
                    stored
                } else {
                    val plaintext = active.decryptItem(entity.itemId, entity.ciphertext)
                    val sealed = active.encryptItem(entity.itemId, plaintext)
                    plaintext.fill(0)
                    val record = active.exportSyncRecord(
                        entity.itemId,
                        sealed,
                        actor,
                        entity.updatedAt.coerceAtLeast(1).toULong(),
                        maxOf(entity.lamport, 1).toULong(),
                    )
                    repo.save(
                        itemId = entity.itemId,
                        ciphertext = sealed,
                        lamport = entity.lamport,
                        tombstone = false,
                        schemaVersion = entity.schemaVersion,
                        updatedAt = entity.updatedAt,
                        syncRecord = record,
                    )
                    record.decodeToString()
                }
            }
            val client = RelaySyncClient(endpoint, token)
            val remoteBytes = client.getManifest(namespace)
            val remote = if (remoteBytes == null) {
                emptyList()
            } else {
                val parsed = JSONObject(remoteBytes.decodeToString())
                val array = parsed.optJSONArray("records") ?: JSONArray()
                (0 until array.length()).mapNotNull { index ->
                    array.optString(index).takeIf { it.isNotEmpty() }
                }
            }
            var merged = runCatching {
                JSONObject(mergeSyncRecordsV2(local, remote))
            }.getOrElse { failure ->
                // One malformed record must not make the whole relay unusable.
                // Drop records that do not look like a v2 wrapper and retry.
                val safeLocal = local.filter(::isV2Record)
                val safeRemote = remote.filter(::isV2Record)
                runCatching { JSONObject(mergeSyncRecordsV2(safeLocal, safeRemote)) }
                    .getOrElse {
                        throw IllegalStateException(
                            "merge failed: ${failure.message ?: "malformed records"}",
                        )
                    }
            }
            var records = merged.optJSONArray("records") ?: JSONArray()
            var conflicts = merged.optJSONArray("conflicts") ?: JSONArray()
            val firstApply = runCatching { persistMergedRecords(active, repo, records) }
            if (firstApply.isFailure) {
                // Records written by older builds carry a random item key. Merging
                // them with local records mixes wrapped keys, so every field of the
                // merged record becomes unreadable and the whole sync aborts.
                // Re-seal those remote records under the derived item key once and
                // retry; this also breaks the loop where the relay kept serving the
                // same unreadable manifest.
                val repairedLocal = local.mapNotNull { normalizeRemoteRecord(active, it) }
                val repairedRemote = remote.mapNotNull { normalizeRemoteRecord(active, it) }
                if (repairedLocal.isEmpty() && repairedRemote.isEmpty()) {
                    throw firstApply.exceptionOrNull() ?: IllegalStateException("sync failed")
                }
                merged = JSONObject(
                    mergeSyncRecordsV2(
                        repairedLocal.ifEmpty { local },
                        repairedRemote,
                    ),
                )
                // Persist the repaired merge, not the manifest that just failed.
                records = merged.optJSONArray("records") ?: JSONArray()
                conflicts = JSONArray()
                persistMergedRecords(active, repo, records)
            }
            val inputs = JSONArray()
            for (index in 0 until conflicts.length()) {
                val conflict = conflicts.getJSONObject(index)
                val item = conflict.optString("item")
                val field = conflict.optInt("field", -1)
                if (item.isEmpty() || field < 0) continue
                val localRaw = local.firstOrNull { raw ->
                    runCatching { JSONObject(raw).optString("item") == item }.getOrDefault(false)
                }
                val remoteRaw = remote.firstOrNull { raw ->
                    runCatching { JSONObject(raw).optString("item") == item }.getOrDefault(false)
                }
                if (localRaw != null && remoteRaw != null) {
                    inputs.put(
                        JSONObject()
                            .put("item", item)
                            .put("field", field)
                            .put("local", localRaw)
                            .put("remote", remoteRaw),
                    )
                }
            }
            appContext.getSharedPreferences("sync", Context.MODE_PRIVATE)
                .edit()
                .putString("conflict_inputs", inputs.toString())
                .apply()
            val payload = JSONObject()
                .put("v", 1)
                .put("records", records)
                .toString()
                .encodeToByteArray()
            if (push) {
                client.putManifest(namespace, payload, null)
            }
            loadItems()
            SyncOutcome(records.length(), conflicts.length())
        }

    suspend fun syncLocalFolder(namespace: String): SyncOutcome = withContext(Dispatchers.IO) {
        val active = session ?: error("vault is locked")
        val repo = repository ?: error("storage is unavailable")
        val uri = appContext.getSharedPreferences("sync", Context.MODE_PRIVATE)
            .getString("local_folder", null) ?: error("no sync folder")
        val folder = AndroidSyncFolder(appContext, Uri.parse(uri))
        val actor = deviceActor()
        val entities = repo.list().filter { !it.tombstone && !it.itemId.contentEquals(FOLDER_META_ID) }
        val local = entities.map { entity ->
            val stored = entity.syncRecord?.decodeToString()
            if (stored != null) {
                stored
            } else {
                val plaintext = active.decryptItem(entity.itemId, entity.ciphertext)
                val sealed = active.encryptItem(entity.itemId, plaintext)
                plaintext.fill(0)
                val record = active.exportSyncRecord(
                    entity.itemId,
                    sealed,
                    actor,
                    entity.updatedAt.coerceAtLeast(1).toULong(),
                    maxOf(entity.lamport, 1).toULong(),
                )
                repo.save(
                    itemId = entity.itemId,
                    ciphertext = sealed,
                    lamport = entity.lamport,
                    tombstone = false,
                    schemaVersion = entity.schemaVersion,
                    updatedAt = entity.updatedAt,
                    syncRecord = record,
                )
                record.decodeToString()
            }
        }
        val remote = mutableListOf<String>()
        for (name in folder.listManifests()) {
            val bytes = folder.read(name) ?: continue
            val parsed = JSONObject(bytes.decodeToString())
            val array = parsed.optJSONArray("records") ?: continue
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotEmpty() }?.let(remote::add)
            }
        }
        val merged = JSONObject(mergeSyncRecordsV2(local, remote))
        val records = merged.optJSONArray("records") ?: JSONArray()
        for (index in 0 until records.length()) {
            val raw = records.getString(index)
            val idHex = JSONObject(raw).getString("item")
            val legacy = active.applySyncRecord(raw.encodeToByteArray())
            val id = ByteArray(16) { offset -> idHex.substring(offset * 2, offset * 2 + 2).toInt(16).toByte() }
            val ciphertext = active.encryptItem(id, legacy)
            legacy.fill(0)
            val previous = repo.get(id)
            repo.save(
                itemId = id,
                ciphertext = ciphertext,
                lamport = previous?.lamport ?: 1L,
                tombstone = false,
                schemaVersion = previous?.schemaVersion ?: 1,
                updatedAt = System.currentTimeMillis(),
                syncRecord = raw.encodeToByteArray(),
            )
        }
        val payload = JSONObject().put("v", 1).put("records", records).toString().encodeToByteArray()
        require(folder.write(namespace, "manifest-$namespace.json", payload)) { "folder write failed" }
        loadItems()
        SyncOutcome(records.length(), merged.optJSONArray("conflicts")?.length() ?: 0)
    }

    fun webauthnSeal(prfOutput: ByteArray): String {
        val active = session ?: error("vault is locked")
        return active.webauthnSeal(prfOutput)
    }

    suspend fun webauthnOpen(sealedHex: String, prfOutput: ByteArray): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val active = session ?: error("vault is locked")
                active.webauthnOpen(sealedHex, prfOutput)
                if (!openStorage()) error("storage unavailable")
                loadItems()
                state = VaultUiState.Unlocked
                recordActivity()
            }
        }

    fun pairingCode(): String? = runCatching {
        val active = session ?: return@runCatching null
        val endpoint = appContext.getSharedPreferences("sync", Context.MODE_PRIVATE)
            .getString("relay_endpoint", DEFAULT_RELAY_ENDPOINT)
            .orEmpty()
        val token = SyncCredentialStore(appContext).load().orEmpty()
        val json = JSONObject()
            .put("v", 1)
            .put("vaultId", active.vaultId().joinToString("") { "%02x".format(it) })
            .put("vaultKey", active.familyVaultKey())
            .put("endpoint", endpoint)
            .put("token", token)
        PAIRING_PREFIX + Base64.encodeToString(
            json.toString().toByteArray(),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
    }.getOrNull()

    /** Decode a `CKPAIR1:` code into its vault identity and relay settings. */
    private fun decodePairingCode(code: String): JSONObject {
        val trimmed = code.trim()
        require(trimmed.startsWith(PAIRING_PREFIX)) { "Pairing code must start with $PAIRING_PREFIX" }
        val encoded = trimmed.removePrefix(PAIRING_PREFIX).replace('-', '+').replace('_', '/')
        val padded = encoded + "=".repeat((4 - encoded.length % 4) % 4)
        return JSONObject(String(Base64.decode(padded, Base64.NO_WRAP), Charsets.UTF_8))
    }

    /**
     * Join the vault described by a pairing code, persist its relay settings and
     * pull remote records once so the second device starts with the same items.
     *
     * The local master password is reused, so the user keeps the password they
     * chose when they first created their vault. It is available only while the
     * session is unlocked with that password.
     */
    suspend fun joinWithPairingCode(code: String): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val password = currentPassword()?.concatToString()
                    ?: error("Unlock with your master password before joining another vault.")
                val payload = decodePairingCode(code)
                val vaultId = payload.optString("vaultId")
                val vaultKey = payload.optString("vaultKey")
                require(vaultId.length == 32 && vaultKey.length == 64) {
                    "Pairing code is incomplete."
                }
                joinFamilyVault(password, vaultId, vaultKey).getOrThrow()
                val endpoint = payload.optString("endpoint").ifBlank { DEFAULT_RELAY_ENDPOINT }
                val token = payload.optString("token")
                appContext.getSharedPreferences("sync", Context.MODE_PRIVATE)
                    .edit()
                    .putString("relay_endpoint", endpoint)
                    .apply()
                var records = 0
                if (token.isNotBlank()) {
                    SyncCredentialStore(appContext).save(token)
                    records = AndroidSyncEngine(appContext, this@SessionController)
                        .sync(endpoint, token)
                        .getOrThrow()
                        .records
                    loadItems()
                }
                records
            }
        }

    fun familyVaultKeyHex(): String {
        val active = session ?: error("vault is locked")
        return active.familyVaultKey()
    }

    suspend fun rotateFamilyKey(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val active = session ?: error("vault is locked")
            val repo = repository ?: error("storage is unavailable")
            val entities = repo.list()
            rotationJournal.save(blob, entities)
            val request = JSONArray()
            entities.forEach { entity ->
                request.put(
                    JSONObject()
                        .put("id", entity.itemId.joinToString("") { "%02x".format(it) })
                        .put("blob", entity.ciphertext.joinToString("") { "%02x".format(it) }),
                )
            }
            val rotated = JSONObject(active.rotateFamilyKey(request.toString()))
            val nextBlob = hexBytes(rotated.getString("blob"))
            blobs.save(nextBlob)
            blob = nextBlob
            val items = rotated.getJSONArray("items")
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                val id = hexBytes(item.getString("id"))
                val entity = entities.firstOrNull { it.itemId.contentEquals(id) } ?: continue
                repo.save(
                    itemId = entity.itemId,
                    ciphertext = hexBytes(item.getString("blob")),
                    lamport = maxOf(entity.lamport + 1, 2),
                    tombstone = entity.tombstone,
                    schemaVersion = entity.schemaVersion,
                    updatedAt = System.currentTimeMillis(),
                )
            }
            loadItems()
            rotationJournal.clear()
        }.onFailure {
            runCatching {
                val repo = repository
                rotationJournal.load()?.let { journal ->
                    if (repo != null) {
                        journal.items.forEach { entity ->
                            repo.save(
                                itemId = entity.itemId,
                                ciphertext = entity.ciphertext,
                                lamport = entity.lamport,
                                tombstone = entity.tombstone,
                                schemaVersion = entity.schemaVersion,
                                updatedAt = entity.updatedAt,
                            )
                        }
                    }
                    if (journal.blob.isNotEmpty()) {
                        blobs.save(journal.blob)
                        blob = journal.blob
                    }
                    rotationJournal.clear()
                }
            }
            session?.close()
            session = null
            items = emptyList()
            storage.closeBlocking()
            state = VaultUiState.Locked("Rotation failed; reopen the vault.")
        }
    }

    private fun hexBytes(value: String): ByteArray =
        ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }

    /** Highest Lamport counter carried by a v2 sync record. */
    private fun recordMaxCounter(raw: String): Long {        val record = runCatching { JSONObject(raw).optJSONObject("record") }.getOrNull() ?: return 0L
        var max = maxOf(
            record.optLong("itemCounter", 0L),
            record.optLong("typeCounter", 0L),
            record.optLong("deletedCounter", 0L),
        )
        val fields = record.optJSONArray("fields") ?: return max
        for (index in 0 until fields.length()) {
            max = maxOf(max, fields.optJSONObject(index)?.optLong("counter", 0L) ?: 0L)
        }
        return max
    }

    /** True when [raw] is a v2 sync envelope with a 16-byte item id. */
    private fun isV2Record(raw: String): Boolean = runCatching {
        val wrapper = JSONObject(raw)
        wrapper.optString("item").length == 32 && wrapper.optJSONObject("record") != null
    }.getOrDefault(false)

    /** Turn a stored v2 record into a tombstone that wins the next merge. */
    private fun tombstoneRecord(recordJson: String, actor: ByteArray): String? = runCatching {
        val wrapper = JSONObject(recordJson)
        val record = wrapper.optJSONObject("record") ?: return@runCatching null
        val next = recordMaxCounter(recordJson) + 1
        val actorHex = actor.joinToString("") { "%02x".format(it) }
        record.put("deleted", true)
        record.put("deletedCounter", next)
        record.put("deletedActor", actorHex)
        record.put("itemCounter", maxOf(record.optLong("itemCounter", 0L), next))
        record.put("itemActor", actorHex)
        wrapper.put("record", record)
        wrapper.toString()
    }.getOrNull()

    private suspend fun persistMergedRecords(
        active: FfiVaultSession,
        repo: VaultRepository,
        records: JSONArray,
    ) {
        for (index in 0 until records.length()) {
            val raw = records.getString(index)
            if (!isV2Record(raw)) continue
            val wrapper = JSONObject(raw)
            val id = hexBytes(wrapper.getString("item"))
            val previous = repo.get(id)
            try {
                if (wrapper.optJSONObject("record")?.optBoolean("deleted") == true) {
                    // Keep the tombstone row so this deletion keeps winning merges.
                    repo.save(
                        itemId = id,
                        ciphertext = previous?.ciphertext ?: ByteArray(0),
                        lamport = previous?.lamport ?: 1L,
                        tombstone = true,
                        schemaVersion = previous?.schemaVersion ?: 1,
                        updatedAt = System.currentTimeMillis(),
                        syncRecord = raw.encodeToByteArray(),
                    )
                    continue
                }
                val legacy = active.applySyncRecord(raw.encodeToByteArray())
                val ciphertext = active.encryptItem(id, legacy)
                legacy.fill(0)
                repo.save(
                    itemId = id,
                    ciphertext = ciphertext,
                    lamport = previous?.lamport ?: 1L,
                    tombstone = false,
                    schemaVersion = previous?.schemaVersion ?: 1,
                    updatedAt = System.currentTimeMillis(),
                    syncRecord = raw.encodeToByteArray(),
                )
            } catch (failure: Exception) {
                // A single unreadable record must not discard the rest of the
                // manifest; keep the local copy and continue with the others.
                android.util.Log.w("CipherKeepSync", "record apply failed", failure)
            }
        }
        if (records.length() > 0 &&
            (0 until records.length()).none { isV2Record(records.optString(it)) }
        ) {
            error("no valid record in manifest")
        }
    }

    /**
     * Re-seal one remote record under the derived item key while keeping its
     * original actor and creation time.
     *
     * Records written by older builds carry a random item key. Merging them with
     * local records mixed two wrapped keys and made every field of the merged
     * record unreadable, which aborted the sync and left the relay serving the
     * same manifest forever. This is used only as a one-time repair.
     */
    private fun normalizeRemoteRecord(active: FfiVaultSession, raw: String): String? = runCatching {
        val wrapper = JSONObject(raw)
        val idHex = wrapper.optString("item")
        if (idHex.length != 32) return@runCatching null
        val record = wrapper.optJSONObject("record") ?: return@runCatching null
        if (record.optBoolean("deleted")) return@runCatching raw
        val actorHex = record.optString("typeActor").ifBlank { record.optString("itemActor") }
        if (actorHex.length != 32) return@runCatching null
        val legacy = active.applySyncRecord(raw.encodeToByteArray())
        val id = hexBytes(idHex)
        val sealed = active.encryptItem(id, legacy)
        legacy.fill(0)
        active.exportSyncRecord(
            id,
            sealed,
            hexBytes(actorHex),
            record.optLong("created", System.currentTimeMillis()).coerceAtLeast(1L).toULong(),
            (recordMaxCounter(raw) + 1).toULong(),
        ).decodeToString()
    }.getOrNull()

    /** Copy one conflicted field from the chosen record's legacy JSON. */
    private fun copyLegacyField(fieldId: Int, target: JSONObject, source: JSONObject) {
        val key = when (fieldId) {
            1 -> "title"
            2 -> "notes"
            10 -> "username"
            11 -> "password"
            12 -> "url"
            13 -> "totpUri"
            0x8001 -> "folder"
            0x8002 -> "tags"
            0x8003 -> "favorite"
            0x8004 -> "autofillAllowed"
            else -> return
        }
        if (source.has(key)) target.put(key, source.get(key)) else target.remove(key)
    }

    suspend fun resolveConflict(item: String, field: Int, keepRight: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val active = session ?: error("vault is locked")
                val repo = repository ?: error("storage is unavailable")
                val prefs = appContext.getSharedPreferences("sync", Context.MODE_PRIVATE)
                val inputs = JSONArray(prefs.getString("conflict_inputs", "[]") ?: "[]")
                var selected: JSONObject? = null
                val remaining = JSONArray()
                for (index in 0 until inputs.length()) {
                    val entry = inputs.getJSONObject(index)
                    if (selected == null && entry.optString("item") == item && entry.optInt("field") == field) {
                        selected = entry
                    } else {
                        remaining.put(entry)
                    }
                }
                val entry = selected ?: error("conflict not found")
                val localRaw = entry.getString("local")
                val remoteRaw = entry.getString("remote")
                val chosenRaw = if (keepRight) remoteRaw else localRaw
                val merged = JSONObject(active.applySyncRecord(localRaw.encodeToByteArray()).decodeToString())
                val chosen = JSONObject(active.applySyncRecord(chosenRaw.encodeToByteArray()).decodeToString())
                copyLegacyField(field, merged, chosen)
                val payload = merged.toString().encodeToByteArray()
                val id = hexBytes(entry.getString("item"))
                val ciphertext = active.encryptItem(id, payload)
                payload.fill(0)
                // Re-export above both sides so the choice wins the next merge
                // instead of being reported again.
                val counter = maxOf(recordMaxCounter(localRaw), recordMaxCounter(remoteRaw)) + 1
                val created = JSONObject(localRaw)
                    .optJSONObject("record")
                    ?.optLong("created", System.currentTimeMillis())
                    ?: System.currentTimeMillis()
                val record = active.exportSyncRecord(
                    id,
                    ciphertext,
                    deviceActor(),
                    created.coerceAtLeast(1L).toULong(),
                    counter.toULong(),
                )
                val previous = repo.get(id)
                repo.save(
                    itemId = id,
                    ciphertext = ciphertext,
                    lamport = previous?.lamport ?: 1L,
                    tombstone = false,
                    schemaVersion = previous?.schemaVersion ?: 1,
                    updatedAt = System.currentTimeMillis(),
                    syncRecord = record,
                )
                prefs.edit().putString("conflict_inputs", remaining.toString()).apply()
                loadItems()
            }
        }

    suspend fun conflictItems(): List<Pair<String, Int>> =
        JSONArray(
            appContext.getSharedPreferences("sync", Context.MODE_PRIVATE)
                .getString("conflict_inputs", "[]") ?: "[]",
        ).let { inputs ->
            (0 until inputs.length()).mapNotNull { index ->
                val entry = inputs.getJSONObject(index)
                val item = entry.optString("item")
                val field = entry.optInt("field", -1)
                if (item.isEmpty() || field < 0) null else item to field
            }
        }

    suspend fun conflictCount(): Int =
        JSONArray(
            appContext.getSharedPreferences("sync", Context.MODE_PRIVATE)
                .getString("conflict_inputs", "[]") ?: "[]",
        ).length()

    suspend fun exportSyncRecordLength(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val active = session ?: error("vault is locked")
            val repo = repository ?: error("storage is unavailable")
            val entity = repo.list().firstOrNull {
                !it.tombstone && !it.itemId.contentEquals(FOLDER_META_ID)
            } ?: error("no item to migrate")
            val bytes = active.exportSyncRecord(
                entity.itemId,
                entity.ciphertext,
                ByteArray(16) { 1 },
                System.currentTimeMillis().toULong(),
                1uL,
            )
            bytes.size
        }
    }

    suspend fun exportBackup(password: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val array = JSONArray()
            items.forEach { item ->
                array.put(
                    JSONObject()
                        .put("type", "login")
                        .put("title", item.title)
                        .put("username", item.username)
                        .put("password", item.password)
                        .put("totpUri", item.totpUri)
                        .put("url", item.url)
                        .put("notes", item.notes)
                        .put("folder", item.folder)
                        .put("tags", JSONArray(item.tags))
                        .put("favorite", item.favorite)
                        .put("autofillAllowed", item.autofillAllowed),
                )
            }
            val payload = JSONObject().put("v", 1).put("items", array).toString().encodeToByteArray()
            val exported = exportPortable(payload, password)
            payload.fill(0)
            exported
        }
    }

    suspend fun importBackup(bytes: ByteArray, password: String): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val plaintext = importPortable(bytes, password)
                val json = JSONObject(plaintext.decodeToString())
                plaintext.fill(0)
                val array = json.getJSONArray("items")
                if (array.length() > 5_000) {
                    error("backup item limit exceeded")
                }
                var imported = 0
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val type = item.optString("type")
                    if (type != "login" && type != "totp") {
                        continue
                    }
                    val itemTitle = item.optString("title")
                    if (itemTitle.isBlank() || itemTitle.length > 512 ||
                        item.optString("username").length > 4_096 ||
                        item.optString("password").length > 65_536 ||
                        item.optString("url").length > 2_048 ||
                        item.optString("notes").length > 65_536 ||
                        item.optString("folder").length > 64 ||
                        item.optString("totpUri").length > 4_096
                    ) {
                        continue
                    }
                    val title = item.optString("title")
                    if (title.isBlank()) {
                        continue
                    }
                    val tags = item.optJSONArray("tags")?.let { values ->
                        (0 until values.length()).joinToString(",") { values.optString(it) }
                    }.orEmpty()
                    if (item.optString("type") == "totp" && item.optString("totpUri").isNotEmpty()) {
                        saveTotpItem(title, item.optString("username"), item.optString("totpUri")).getOrThrow()
                    } else {
                        saveItem(
                            title,
                            item.optString("username"),
                            item.optString("password"),
                            item.optString("url"),
                            item.optString("notes"),
                            item.optString("folder"),
                            tags,
                            item.optBoolean("favorite"),
                            item.optBoolean("autofillAllowed"),
                        ).getOrThrow()
                    }
                    imported += 1
                }
                imported
            }
        }

    suspend fun folderNames(): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val active = session ?: error("vault is locked")
            val repo = repository ?: error("storage is unavailable")
            val names = readFolderNames(active, repo)
            (names + items.map { it.folder }.filter { it.isNotBlank() })
                .distinct()
                .sorted()
        }
    }

    suspend fun createFolder(name: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(name.isNotBlank() && name.length <= 64)
            val active = session ?: error("vault is locked")
            val repo = repository ?: error("storage is unavailable")
            val names = readFolderNames(active, repo).toMutableSet()
            names.add(name.trim())
            writeFolderNames(active, repo, names)
            loadItems()
        }
    }

    suspend fun renameFolder(oldName: String, newName: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(newName.isNotBlank() && newName.length <= 64)
                val active = session ?: error("vault is locked")
                val repo = repository ?: error("storage is unavailable")
                val target = newName.trim()
                val names = readFolderNames(active, repo).toMutableSet()
                names.remove(oldName)
                names.add(target)
                val changed = rewriteFolder(oldName, target)
                try {
                    writeFolderNames(active, repo, names)
                } catch (error: Throwable) {
                    restoreItems(repo, changed)
                    throw error
                }
                loadItems()
            }
        }

    suspend fun deleteFolder(name: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val active = session ?: error("vault is locked")
            val repo = repository ?: error("storage is unavailable")
            val names = readFolderNames(active, repo).toMutableSet()
            names.remove(name)
            val changed = rewriteFolder(name, "")
            try {
                writeFolderNames(active, repo, names)
            } catch (error: Throwable) {
                restoreItems(repo, changed)
                throw error
            }
            loadItems()
        }
    }

    private suspend fun readFolderNames(active: FfiVaultSession, repo: VaultRepository): List<String> {
        val entity = repo.get(FOLDER_META_ID) ?: return emptyList()
        return runCatching {
            val json = JSONObject(active.decryptItem(entity.itemId, entity.ciphertext).decodeToString())
            val array = json.optJSONArray("folders") ?: return emptyList()
            (0 until array.length()).mapNotNull { index ->
                array.optString(index).takeIf { it.isNotBlank() }
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun writeFolderNames(
        active: FfiVaultSession,
        repo: VaultRepository,
        names: Set<String>,
    ) {
        val payload = JSONObject()
            .put("v", 1)
            .put("type", "folder-list")
            .put("folders", JSONArray(names.sorted()))
            .toString()
            .encodeToByteArray()
        val ciphertext = active.encryptItem(FOLDER_META_ID, payload)
        payload.fill(0)
        val previous = repo.get(FOLDER_META_ID)
        repo.save(
            itemId = FOLDER_META_ID,
            ciphertext = ciphertext,
            lamport = maxOf((previous?.lamport ?: 0) + 1, 2),
            tombstone = false,
            schemaVersion = 1,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private suspend fun rewriteFolder(
        oldName: String,
        newName: String,
    ): List<dev.cipherkeep.app.data.db.ItemEntity> {
        val active = session ?: error("vault is locked")
        val repo = repository ?: error("storage is unavailable")
        val changed = mutableListOf<dev.cipherkeep.app.data.db.ItemEntity>()
        try {
            repo.list().forEach { entity ->
                if (entity.tombstone || entity.itemId.contentEquals(FOLDER_META_ID)) {
                    return@forEach
                }
                val plaintext = active.decryptItem(entity.itemId, entity.ciphertext)
                val json = JSONObject(plaintext.decodeToString())
                plaintext.fill(0)
                if (json.optString("folder") != oldName) {
                    return@forEach
                }
                json.put("folder", newName)
                val payload = json.toString().encodeToByteArray()
                val ciphertext = active.encryptItem(entity.itemId, payload)
                payload.fill(0)
                repo.save(
                    itemId = entity.itemId,
                    ciphertext = ciphertext,
                    lamport = maxOf(entity.lamport + 1, 2),
                    tombstone = false,
                    schemaVersion = entity.schemaVersion,
                    updatedAt = System.currentTimeMillis(),
                )
                changed.add(entity)
            }
        } catch (error: Throwable) {
            changed.forEach { original ->
                repo.save(
                    itemId = original.itemId,
                    ciphertext = original.ciphertext,
                    lamport = original.lamport,
                    tombstone = original.tombstone,
                    schemaVersion = original.schemaVersion,
                    updatedAt = original.updatedAt,
                )
            }
            throw error
        }
        return changed
    }

    private suspend fun restoreItems(
        repo: VaultRepository,
        items: List<dev.cipherkeep.app.data.db.ItemEntity>,
    ) {
        items.forEach { original ->
            repo.save(
                itemId = original.itemId,
                ciphertext = original.ciphertext,
                lamport = original.lamport,
                tombstone = original.tombstone,
                schemaVersion = original.schemaVersion,
                updatedAt = original.updatedAt,
            )
        }
    }

    suspend fun updateItem(
        item: VaultItem,
        title: String,
        username: String,
        password: String,
        url: String,
        notes: String,
        folder: String,
        tags: String,
        favorite: Boolean,
        autofillAllowed: Boolean = item.autofillAllowed,
        totpUri: String = item.totpUri,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(title.isNotBlank())
            require(
                url.isBlank() ||
                    runCatching { java.net.URI(url).host }.getOrNull() != null,
            ) { "invalid URL" }
            val active = session ?: error("vault is locked")
            val repo = repository ?: error("storage is unavailable")
            val previous = repo.get(item.id) ?: error("item not found")
            val payload = JSONObject()
                .put("v", 1)
                .put("type", "login")
                .put("title", title)
                .put("username", username)
                .put("password", password)
                .put("totpUri", item.totpUri)
                .put("url", url)
                .put("notes", notes)
                .put("folder", folder)
                .put(
                    "tags",
                    JSONArray(tags.split(',').map(String::trim).filter(String::isNotEmpty)),
                )
                .put("favorite", favorite)
                .put("autofillAllowed", autofillAllowed)
                .toString()
                .encodeToByteArray()
            val ciphertext = active.encryptItem(item.id, payload)
            payload.fill(0)
            repo.save(
                itemId = item.id,
                ciphertext = ciphertext,
                lamport = maxOf(previous.lamport + 1, 2),
                tombstone = false,
                schemaVersion = previous.schemaVersion,
                updatedAt = System.currentTimeMillis(),
            )
            loadItems()
        }
    }

    suspend fun setFavorite(item: VaultItem, favorite: Boolean): Result<Unit> =
        mutateItem(item) { it.put("favorite", favorite) }

    suspend fun moveItem(item: VaultItem, folder: String): Result<Unit> =
        mutateItem(item) { it.put("folder", folder.take(64)) }

    suspend fun duplicateItem(item: VaultItem): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val active = session ?: error("vault is locked")
            val repo = repository ?: error("storage is unavailable")
            val source = repo.get(item.id) ?: error("item not found")
            val json = JSONObject(active.decryptItem(source.itemId, source.ciphertext).decodeToString())
            json.put("title", (json.optString("title") + " copy").take(512))
            json.put("favorite", false)
            val id = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val payload = json.toString().encodeToByteArray()
            val ciphertext = active.encryptItem(id, payload)
            payload.fill(0)
            repo.save(id, ciphertext, 1, false, 1, System.currentTimeMillis())
            loadItems()
        }
    }

    private suspend fun mutateItem(item: VaultItem, change: (JSONObject) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val active = session ?: error("vault is locked")
                val repo = repository ?: error("storage is unavailable")
                val entity = repo.get(item.id) ?: error("item not found")
                val json = JSONObject(active.decryptItem(entity.itemId, entity.ciphertext).decodeToString())
                change(json)
                val payload = json.toString().encodeToByteArray()
                val ciphertext = active.encryptItem(item.id, payload)
                payload.fill(0)
                repo.save(
                    itemId = item.id,
                    ciphertext = ciphertext,
                    lamport = maxOf(entity.lamport + 1, 2),
                    tombstone = false,
                    schemaVersion = entity.schemaVersion,
                    updatedAt = System.currentTimeMillis(),
                )
                loadItems()
            }
        }

    /**
     * Delete items locally and keep a CRDT tombstone for every synced item, so
     * the deletion propagates on the next push instead of being resurrected by
     * the next pull from another device.
     */
    suspend fun deleteItems(targets: List<VaultItem>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val repo = repository ?: error("storage is unavailable")
            val actor = deviceActor()
            targets.forEach { target ->
                val entity = repo.get(target.id)
                val record = entity?.syncRecord?.decodeToString()
                if (entity != null && record != null) {
                    val tombstone = tombstoneRecord(record, actor) ?: record
                    repo.save(
                        itemId = target.id,
                        ciphertext = entity.ciphertext,
                        lamport = maxOf(entity.lamport + 1, 2),
                        tombstone = true,
                        schemaVersion = entity.schemaVersion,
                        updatedAt = System.currentTimeMillis(),
                        syncRecord = tombstone.encodeToByteArray(),
                    )
                } else {
                    repo.delete(target.id)
                }
            }
            loadItems()
        }
    }

    suspend fun deleteItem(item: VaultItem): Result<Unit> = deleteItems(listOf(item))

    /** Hide the vault without deleting it; the session key is locked. */
    fun enterTravelMode() {
        if (state != VaultUiState.Unlocked) return
        travel.enable()
        autoLockJob?.cancel()
        autoLockJob = null
        session?.lock()
        clearPassword()
        items = emptyList()
        backgroundedAt = null
        storage.closeBlocking()
        state = VaultUiState.Travel
    }

    fun lock(message: String? = null) {
        if (blob == null || state == VaultUiState.Setup) return
        autoLockJob?.cancel()
        autoLockJob = null
        session?.lock()
        clearPassword()
        items = emptyList()
        dev.cipherkeep.app.data.hibp.BreachCache.clear()
        backgroundedAt = null
        storage.closeBlocking()
        state = VaultUiState.Locked(message)
    }

    /** Reset the foreground inactivity deadline after real user input. */
    fun recordActivity() {
        if (state != VaultUiState.Unlocked) return
        val timeout = lockTimeoutMillis()
        autoLockJob?.cancel()
        autoLockJob = scope.launch {
            delay(timeout)
            lock("Session locked after inactivity.")
        }
    }

    /** Called from the lifecycle observer when the activity stops. */
    fun onBackground(now: Long = SystemClock.elapsedRealtime()) {
        if (state != VaultUiState.Unlocked && state != VaultUiState.LockWarning) return
        backgroundedAt = now
        val timeout = lockTimeoutMillis()
        autoLockJob?.cancel()
        autoLockJob = scope.launch {
            delay(timeout)
            lock("Session locked after inactivity.")
        }
    }

    /** Lock if the app was backgrounded for at least the configured timeout. */
    fun onForeground(now: Long = SystemClock.elapsedRealtime()) {
        val since = backgroundedAt ?: return
        val elapsed = now - since
        backgroundedAt = null
        val timeout = lockTimeoutMillis()
        val warningWindow = (timeout / 6).coerceIn(2_000L, 15_000L)
        if (state == VaultUiState.Unlocked && elapsed >= timeout) {
            lock("Session locked after inactivity.")
        } else if (state == VaultUiState.Unlocked && elapsed >= timeout - warningWindow) {
            state = VaultUiState.LockWarning
        } else if (state == VaultUiState.Unlocked) {
            autoLockJob?.cancel()
            autoLockJob = null
            recordActivity()
        }
    }

    /** Keep the session after the user acknowledges the idle warning. */
    fun stayUnlocked() {
        backgroundedAt = null
        state = VaultUiState.Unlocked
        recordActivity()
    }

    fun finishRecovery() {
        state = VaultUiState.Unlocked
        recordActivity()
    }

    fun nextShare() {
        val current = state as? VaultUiState.Recovery ?: return
        state = if (current.index + 1 >= current.shares.size) {
            VaultUiState.Unlocked
        } else {
            current.copy(index = current.index + 1)
        }
        if (state == VaultUiState.Unlocked) recordActivity()
    }

    fun close() {
        autoLockJob?.cancel()
        session?.close()
        session = null
        clearPassword()
        storage.closeBlocking()
        scope.cancel()
    }

    companion object {
        const val AUTO_LOCK_MILLIS = 60_000L
        const val MIN_LOCK_TIMEOUT_MILLIS = 15_000L
        const val MAX_LOCK_TIMEOUT_MILLIS = 30 * 60_000L
        const val PAIRING_PREFIX = "CKPAIR1:"
        const val DEFAULT_RELAY_ENDPOINT = "https://cipherkeep-relay.marzouk-souhaieb.workers.dev"
        const val MIN_JOIN_PASSWORD_LENGTH = 12
        private const val LOCK_TIMEOUT_KEY = "auto_lock_millis"
    }
}

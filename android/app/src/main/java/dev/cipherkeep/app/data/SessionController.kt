package dev.cipherkeep.app.data

import android.content.Context
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
                if (entity.itemId.contentEquals(FOLDER_META_ID)) {
                    return@mapNotNull null
                }
                runCatching {
                    val plaintext = runCatching {
                        entity.syncRecord?.let { active.applySyncRecord(it) }
                    }.getOrNull()
                        ?: active.decryptItem(entity.itemId, entity.ciphertext)
                    val json = JSONObject(plaintext.decodeToString())
                    val exported = runCatching {
                        active.exportSyncRecord(
                            entity.itemId,
                            entity.ciphertext,
                            ByteArray(16) { 1 },
                            System.currentTimeMillis().toULong(),
                            1uL,
                        )
                    }.getOrNull()
                    if (exported != null && !exported.contentEquals(entity.syncRecord)) {
                        repo.save(
                            itemId = entity.itemId,
                            ciphertext = entity.ciphertext,
                            lamport = entity.lamport,
                            tombstone = entity.tombstone,
                            schemaVersion = entity.schemaVersion,
                            updatedAt = entity.updatedAt,
                            syncRecord = exported,
                        )
                    }
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
                loadItems()
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

    suspend fun importPlaintext(bytes: ByteArray, mergeDuplicates: Boolean = false): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val active = session ?: error("vault is locked")
                if (active.isLocked()) error("vault is locked")
                val report = JSONObject(dev.cipherkeep.core.importPlaintextJson(bytes, mergeDuplicates))
                val items = report.getJSONArray("items")
                var imported = 0
                for (index in 0 until items.length()) {
                    val item = items.getJSONObject(index)
                    val title = item.optString("title").trim()
                    if (title.isEmpty()) continue
                    val rawNotes = item.optString("notes")
                    val totp = Regex("TOTP:\\s*(otpauth://\\S+)").find(rawNotes)?.groupValues?.get(1).orEmpty()
                    val notes = if (totp.isEmpty()) rawNotes else rawNotes.replace("TOTP: $totp", "").trim()
                    saveItem(
                        title = title.take(512),
                        username = item.optString("username").take(4_096),
                        password = item.optString("password").take(65_536),
                        url = importUrl(item.optString("url")),
                        notes = notes.take(65_536),
                        folder = item.optString("folder").take(64),
                        totpUri = totp.take(4_096),
                    ).getOrThrow()
                    imported += 1
                }
                imported
            }
        }

    suspend fun importKdbx(bytes: ByteArray, password: String): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val active = session ?: error("vault is locked")
                if (active.isLocked()) error("vault is locked")
                val report = JSONObject(dev.cipherkeep.core.importKdbxJson(bytes, password))
                val items = report.getJSONArray("items")
                var imported = 0
                for (index in 0 until items.length()) {
                    val item = items.getJSONObject(index)
                    val title = item.optString("title").trim()
                    if (title.isEmpty()) continue
                    saveItem(
                        title = title.take(512),
                        username = item.optString("username").take(4_096),
                        password = item.optString("password").take(65_536),
                        url = importUrl(item.optString("url")),
                        notes = item.optString("notes").take(65_536),
                        folder = item.optString("folder").take(64),
                    ).getOrThrow()
                    imported += 1
                }
                imported
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

    suspend fun syncNow(endpoint: String, token: String, namespace: String): SyncOutcome =
        withContext(Dispatchers.IO) {
            if (travel.enabled()) error("Travel Mode is active")
            val active = session ?: error("vault is locked")
            val repo = repository ?: error("storage is unavailable")
            if (!Regex("^[a-f0-9]{32}$").matches(namespace)) error("invalid sync namespace")
            val entities = repo.list().filter {
                !it.tombstone && !it.itemId.contentEquals(FOLDER_META_ID)
            }
            val actor = ByteArray(16) { 1 }
            val created = System.currentTimeMillis().toULong()
            val local = entities.map { entity ->
                active.exportSyncRecord(
                    entity.itemId,
                    entity.ciphertext,
                    actor,
                    created,
                    1uL,
                ).decodeToString()
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
            val merged = JSONObject(mergeSyncRecordsV2(local, remote))
            val records = merged.optJSONArray("records") ?: JSONArray()
            val conflicts = merged.optJSONArray("conflicts") ?: JSONArray()
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
            for (index in 0 until records.length()) {
                val raw = records.getString(index)
                val wrapper = JSONObject(raw)
                val idHex = wrapper.getString("item")
                val legacy = active.applySyncRecord(raw.encodeToByteArray())
                val id = ByteArray(16) { offset ->
                    idHex.substring(offset * 2, offset * 2 + 2).toInt(16).toByte()
                }
                val ciphertext = active.encryptItem(id, legacy)
                legacy.fill(0)
                val previous = repo.get(id)
                repo.save(
                    itemId = id,
                    ciphertext = ciphertext,
                    lamport = maxOf((previous?.lamport ?: 0) + 1, 2),
                    tombstone = false,
                    schemaVersion = 1,
                    updatedAt = System.currentTimeMillis(),
                )
            }
            val payload = JSONObject()
                .put("v", 1)
                .put("records", records)
                .toString()
                .encodeToByteArray()
            client.putManifest(namespace, payload, null)
            loadItems()
            SyncOutcome(records.length(), conflicts.length())
        }

    suspend fun syncLocalFolder(namespace: String): SyncOutcome = withContext(Dispatchers.IO) {
        val active = session ?: error("vault is locked")
        val repo = repository ?: error("storage is unavailable")
        val uri = appContext.getSharedPreferences("sync", Context.MODE_PRIVATE)
            .getString("local_folder", null) ?: error("no sync folder")
        val folder = AndroidSyncFolder(appContext, Uri.parse(uri))
        val actor = ByteArray(16) { 1 }
        val created = System.currentTimeMillis().toULong()
        val entities = repo.list().filter { !it.tombstone && !it.itemId.contentEquals(FOLDER_META_ID) }
        val local = entities.map { entity ->
            active.exportSyncRecord(entity.itemId, entity.ciphertext, actor, created, 1uL).decodeToString()
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
                lamport = maxOf((previous?.lamport ?: 0) + 1, 2),
                tombstone = false,
                schemaVersion = 1,
                updatedAt = System.currentTimeMillis(),
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
                val resolved = resolveSyncConflict(
                    entry.getString("local"),
                    entry.getString("remote"),
                    field.toUShort(),
                    keepRight,
                )
                val wrapper = JSONObject(resolved)
                val idHex = wrapper.getString("item")
                val legacy = active.applySyncRecord(resolved.encodeToByteArray())
                val id = ByteArray(16) { offset ->
                    idHex.substring(offset * 2, offset * 2 + 2).toInt(16).toByte()
                }
                val ciphertext = active.encryptItem(id, legacy)
                legacy.fill(0)
                val previous = repo.get(id)
                repo.save(
                    itemId = id,
                    ciphertext = ciphertext,
                    lamport = maxOf((previous?.lamport ?: 0) + 1, 2),
                    tombstone = false,
                    schemaVersion = 1,
                    updatedAt = System.currentTimeMillis(),
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

    suspend fun deleteItem(item: VaultItem): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            repository?.delete(item.id)
            loadItems()
        }
    }

    /** Hide the vault without deleting it; the session key is locked. */
    fun enterTravelMode() {
        if (state != VaultUiState.Unlocked) return
        travel.enable()
        autoLockJob?.cancel()
        autoLockJob = null
        session?.lock()
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
        items = emptyList()
        dev.cipherkeep.app.data.hibp.BreachCache.clear()
        backgroundedAt = null
        storage.closeBlocking()
        state = VaultUiState.Locked(message)
    }

    /** Reset the foreground inactivity deadline after real user input. */
    fun recordActivity() {
        if (state != VaultUiState.Unlocked) return
        autoLockJob?.cancel()
        autoLockJob = scope.launch {
            delay(AUTO_LOCK_MILLIS)
            lock("Session locked after inactivity.")
        }
    }

    /** Called from the lifecycle observer when the activity stops. */
    fun onBackground(now: Long = SystemClock.elapsedRealtime()) {
        if (state != VaultUiState.Unlocked && state != VaultUiState.LockWarning) return
        backgroundedAt = now
        autoLockJob?.cancel()
        autoLockJob = scope.launch {
            delay(AUTO_LOCK_MILLIS)
            lock("Session locked after inactivity.")
        }
    }

    /** Lock if the app was backgrounded for at least the 60-second timeout. */
    fun onForeground(now: Long = SystemClock.elapsedRealtime()) {
        val since = backgroundedAt ?: return
        val elapsed = now - since
        backgroundedAt = null
        if (state == VaultUiState.Unlocked && elapsed >= AUTO_LOCK_MILLIS) {
            lock("Session locked after inactivity.")
        } else if (state == VaultUiState.Unlocked && elapsed >= AUTO_LOCK_MILLIS - 5_000) {
            state = VaultUiState.LockWarning
        } else if (state == VaultUiState.Unlocked) {
            autoLockJob?.cancel()
            autoLockJob = null
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
        storage.closeBlocking()
        scope.cancel()
    }

    companion object {
        const val AUTO_LOCK_MILLIS = 60_000L
    }
}

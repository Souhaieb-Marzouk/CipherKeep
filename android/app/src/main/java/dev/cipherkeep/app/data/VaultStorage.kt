package dev.cipherkeep.app.data

import android.content.Context
import dev.cipherkeep.app.data.db.VaultDatabase
import dev.cipherkeep.app.data.db.VaultRepository
import dev.cipherkeep.app.data.keystore.PassphraseStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the encrypted database lifecycle. The unlocked session asks for
 * a repository only after the master password has been verified.
 */
class VaultStorage(
    context: Context,
    private val passphrases: PassphraseStore = PassphraseStore(context.applicationContext),
) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private var database: VaultDatabase? = null

    suspend fun openDatabase(): VaultDatabase = mutex.withLock {
        database ?: run {
            val passphrase = passphrases.getOrCreate()
            try {
                VaultDatabase.open(appContext, passphrase).also { database = it }
            } finally {
                passphrase.fill(0)
            }
        }
    }

    suspend fun repository(): VaultRepository = VaultRepository(openDatabase())

    suspend fun close() = mutex.withLock {
        database?.close()
        database = null
    }
}

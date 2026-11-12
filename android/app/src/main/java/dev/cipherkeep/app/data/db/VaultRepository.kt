package dev.cipherkeep.app.data.db

import androidx.room.withTransaction

/**
 * Atomic item persistence. Ciphertext is supplied by the vault session layer;
 * this repository never sees a plaintext field value.
 */
class VaultRepository(private val database: VaultDatabase) {
    suspend fun save(
        itemId: ByteArray,
        ciphertext: ByteArray,
        lamport: Long,
        tombstone: Boolean,
        schemaVersion: Int,
        updatedAt: Long,
        syncRecord: ByteArray? = null,
    ) {
        require(itemId.size == 16) { "item id must be 16 bytes" }
        require(tombstone || ciphertext.isNotEmpty()) { "live item must have ciphertext" }
        require(lamport > 0) { "lamport must advance" }
        database.withTransaction {
            database.itemDao().upsert(
                ItemEntity(
                    itemId = itemId,
                    ciphertext = ciphertext,
                    lamport = lamport,
                    tombstone = tombstone,
                    schemaVersion = schemaVersion,
                    updatedAt = updatedAt,
                    syncRecord = syncRecord,
                ),
            )
        }
    }

    suspend fun get(itemId: ByteArray): ItemEntity? = database.itemDao().get(itemId)

    suspend fun list(): List<ItemEntity> = database.itemDao().list()

    suspend fun delete(itemId: ByteArray) {
        database.withTransaction { database.itemDao().delete(itemId) }
    }

    suspend fun count(): Int = database.itemDao().count()
}

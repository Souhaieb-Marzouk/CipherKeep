package dev.cipherkeep.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Stored CRDT item.
 *
 * Every user-controlled value lives inside [ciphertext]. The table deliberately
 * has no title, URL, username or other plaintext column.
 */
@Entity(tableName = "items")
data class ItemEntity(
    @PrimaryKey
    @ColumnInfo(name = "item_id")
    val itemId: ByteArray,
    @ColumnInfo(name = "ciphertext")
    val ciphertext: ByteArray,
    @ColumnInfo(name = "lamport")
    val lamport: Long,
    @ColumnInfo(name = "tombstone")
    val tombstone: Boolean,
    @ColumnInfo(name = "schema_version")
    val schemaVersion: Int,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "sync_record")
    val syncRecord: ByteArray? = null,
)

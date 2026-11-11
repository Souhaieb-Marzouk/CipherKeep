package dev.cipherkeep.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ItemEntity)

    @Query("SELECT * FROM items WHERE item_id = :itemId")
    suspend fun get(itemId: ByteArray): ItemEntity?

    @Query("SELECT * FROM items ORDER BY updated_at DESC")
    suspend fun list(): List<ItemEntity>

    @Query("DELETE FROM items WHERE item_id = :itemId")
    suspend fun delete(itemId: ByteArray)

    @Query("SELECT COUNT(*) FROM items")
    suspend fun count(): Int
}

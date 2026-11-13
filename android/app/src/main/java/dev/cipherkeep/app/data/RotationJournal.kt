package dev.cipherkeep.app.data

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Crash-safe snapshot taken before a family-key rotation.
 *
 * The journal contains only already-encrypted blobs and ciphertexts. It is
 * deleted after both the vault blob and every item are durably updated.
 */
class RotationJournal(context: Context) {
    private val file = File(context.applicationContext.filesDir, "rotation-journal.json")

    fun save(blob: ByteArray?, entities: List<dev.cipherkeep.app.data.db.ItemEntity>) {
        val json = JSONObject()
        json.put("blob", Base64.encodeToString(blob ?: ByteArray(0), Base64.NO_WRAP))
        val items = JSONArray()
        entities.forEach { entity ->
            items.put(
                JSONObject()
                    .put("id", Base64.encodeToString(entity.itemId, Base64.NO_WRAP))
                    .put("ciphertext", Base64.encodeToString(entity.ciphertext, Base64.NO_WRAP))
                    .put("lamport", entity.lamport)
                    .put("tombstone", entity.tombstone)
                    .put("schemaVersion", entity.schemaVersion)
                    .put("updatedAt", entity.updatedAt),
            )
        }
        json.put("items", items)
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(json.toString())
        check(temporary.renameTo(file)) { "journal rename failed" }
    }

    fun load(): Snapshot? = runCatching {
        if (!file.exists()) return null
        val json = JSONObject(file.readText())
        val items = json.getJSONArray("items")
        val entities = (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            dev.cipherkeep.app.data.db.ItemEntity(
                itemId = Base64.decode(item.getString("id"), Base64.NO_WRAP),
                ciphertext = Base64.decode(item.getString("ciphertext"), Base64.NO_WRAP),
                lamport = item.getLong("lamport"),
                tombstone = item.getBoolean("tombstone"),
                schemaVersion = item.getInt("schemaVersion"),
                updatedAt = item.getLong("updatedAt"),
            )
        }
        Snapshot(
            blob = Base64.decode(json.getString("blob"), Base64.NO_WRAP),
            items = entities,
        )
    }.getOrNull()

    fun clear() {
        file.delete()
        File(file.parentFile, "${file.name}.tmp").delete()
    }

    data class Snapshot(val blob: ByteArray, val items: List<dev.cipherkeep.app.data.db.ItemEntity>)
}

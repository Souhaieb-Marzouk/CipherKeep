package dev.cipherkeep.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class SyncOutcome(val records: Int, val conflicts: Int)

/** Android V2 relay sync orchestration. */
class AndroidSyncEngine(private val context: Context, private val controller: SessionController) {
    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)

    suspend fun sync(endpoint: String, token: String): Result<SyncOutcome> =
        withContext(Dispatchers.IO) {
            runCatching {
                controller.syncNow(endpoint, token, namespace())
            }
        }

    fun namespace(): String = controller.syncNamespace()

}

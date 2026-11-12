package dev.cipherkeep.app.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Holds the live controller for scheduled sync while the process is alive. */
object SyncRuntime {
    @Volatile
    var controller: SessionController? = null
}

/** Periodic relay sync. It never runs while the vault is locked. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val controller = SyncRuntime.controller ?: return Result.retry()
        if (controller.state != VaultUiState.Unlocked) return Result.retry()
        val endpoint = applicationContext
            .getSharedPreferences("sync", Context.MODE_PRIVATE)
            .getString("relay_endpoint", null)
            ?: return Result.failure()
        val token = SyncCredentialStore(applicationContext).load() ?: return Result.failure()
        return AndroidSyncEngine(applicationContext, controller)
            .sync(endpoint, token)
            .fold({ Result.success() }, { Result.retry() })
    }
}

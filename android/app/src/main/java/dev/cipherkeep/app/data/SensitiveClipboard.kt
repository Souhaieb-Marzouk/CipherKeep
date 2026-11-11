package dev.cipherkeep.app.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Copies a secret with the platform sensitive-clip flag and clears it after
 * 30 seconds if the clip is still ours.
 */
class SensitiveClipboard(
    context: Context,
    private val scope: CoroutineScope,
    private val timeoutMillis: Long = 30_000,
) {
    private val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    fun copy(label: String, secret: String, onTick: (Int) -> Unit = {}) {
        val token = UUID.randomUUID().toString()
        val clip = ClipData.newPlainText(label, secret)
        clip.description.extras = PersistableBundle().apply {
            putBoolean(EXTRA_IS_SENSITIVE, true)
            putString(EXTRA_CIPHERKEEP_TOKEN, token)
        }
        manager.setPrimaryClip(clip)
        scope.launch {
            val seconds = (timeoutMillis / 1_000).toInt()
            for (remaining in seconds downTo 1) {
                onTick(remaining)
                delay(1_000)
            }
            onTick(0)
        }
        clearScope.launch {
            delay(timeoutMillis)
            val description = manager.primaryClipDescription
            if (description?.label == label &&
                description.extras?.getString(EXTRA_CIPHERKEEP_TOKEN) == token
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    manager.clearPrimaryClip()
                } else {
                    manager.setPrimaryClip(ClipData.newPlainText(label, ""))
                }
            }
        }
    }

    companion object {
        // ClipDescription.EXTRA_IS_SENSITIVE is API 33; the platform reads this
        // string key on older versions as well.
        private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
        private const val EXTRA_CIPHERKEEP_TOKEN = "dev.cipherkeep.clipboard.token"

        /** Clear only a clip CipherKeep marked; safe to call from onStop. */
        fun clearIfOwned(context: Context) {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (manager.primaryClipDescription?.extras?.containsKey(EXTRA_CIPHERKEEP_TOKEN) != true) {
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                manager.clearPrimaryClip()
            } else {
                manager.setPrimaryClip(ClipData.newPlainText("CipherKeep password", ""))
            }
        }

        // Process-wide clearing must not be cancelled when the item screen leaves.
        private val clearScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

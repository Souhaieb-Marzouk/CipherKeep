package dev.cipherkeep.app.autofill

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.cipherkeep.app.MainActivity
import dev.cipherkeep.app.data.SessionProvider
import dev.cipherkeep.app.data.VaultUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Login values captured by the autofill save request. */
data class PendingLogin(
    val title: String,
    val username: String,
    val password: String,
    val url: String,
)

/** In-memory hand-off between the save notification and its action receiver. */
object AutofillSaveStore {
    private val pending = ConcurrentHashMap<Int, PendingLogin>()
    private val counter = AtomicInteger(1000)

    fun put(login: PendingLogin): Int {
        val token = counter.incrementAndGet()
        pending[token] = login
        return token
    }

    fun take(token: Int): PendingLogin? = pending.remove(token)
}

/**
 * Asks the user, through a notification, whether a manually entered login should
 * be stored. The user stays in the app they just logged into; saving happens in
 * the background when they tap "Save".
 */
object AutofillSaveNotifier {
    const val ACTION_SAVE = "dev.cipherkeep.app.autofill.SAVE_LOGIN"
    const val ACTION_DISMISS = "dev.cipherkeep.app.autofill.DISMISS_LOGIN"
    const val EXTRA_TOKEN = "save_token"
    private const val CHANNEL_ID = "autofill_save"

    fun request(context: Context, login: PendingLogin) {
        if (login.password.isBlank()) return
        val controller = SessionProvider.get(context)
        val alreadySaved = controller.items.any {
            it.username == login.username && it.password == login.password
        }
        if (alreadySaved) return
        if (!notificationsAllowed(context)) {
            // Without notification permission there is no way to ask; store the
            // login only when the vault is already unlocked.
            if (controller.state is VaultUiState.Unlocked) {
                CoroutineScope(Dispatchers.IO).launch { save(context, login) }
            }
            return
        }
        val token = AutofillSaveStore.put(login)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(manager)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val save = PendingIntent.getBroadcast(
            context,
            token,
            Intent(context, AutofillSaveReceiver::class.java)
                .setAction(ACTION_SAVE)
                .putExtra(EXTRA_TOKEN, token),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val dismiss = PendingIntent.getBroadcast(
            context,
            token + 1,
            Intent(context, AutofillSaveReceiver::class.java)
                .setAction(ACTION_DISMISS)
                .putExtra(EXTRA_TOKEN, token),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle("Save this login to CipherKeep?")
            .setContentText(login.title)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Save", save).build())
            .addAction(Notification.Action.Builder(null, "Not now", dismiss).build())
            .build()
        manager.notify(token, notification)
    }

    suspend fun save(context: Context, login: PendingLogin) {
        val controller = SessionProvider.get(context)
        if (controller.state !is VaultUiState.Unlocked) return
        controller.saveItem(
            title = login.title.ifBlank { "Saved login" },
            username = login.username,
            password = login.password,
            url = login.url,
        )
    }

    fun notificationsAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Save logins",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Asks whether a login typed in another app should be saved."
            },
        )
    }
}

class AutofillSaveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.getIntExtra(AutofillSaveNotifier.EXTRA_TOKEN, -1)
        if (token <= 0) return
        val manager = context.getSystemService(NotificationManager::class.java)
        when (intent.action) {
            AutofillSaveNotifier.ACTION_SAVE -> {
                val login = AutofillSaveStore.take(token) ?: return
                manager?.cancel(token)
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        AutofillSaveNotifier.save(context, login)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
            AutofillSaveNotifier.ACTION_DISMISS -> {
                AutofillSaveStore.take(token)
                manager?.cancel(token)
            }
        }
    }
}

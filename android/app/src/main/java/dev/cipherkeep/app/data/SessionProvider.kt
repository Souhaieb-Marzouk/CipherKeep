package dev.cipherkeep.app.data

import android.content.Context

/**
 * Process-wide [SessionController]. The autofill service, the unlock activity and
 * the main UI must share one instance so unlocking from autofill also unlocks
 * the vault the user sees when they later open CipherKeep.
 */
object SessionProvider {
    @Volatile
    private var instance: SessionController? = null

    fun get(context: Context): SessionController =
        instance ?: synchronized(this) {
            instance ?: SessionController(context.applicationContext).also {
                instance = it
                dev.cipherkeep.app.autofill.AutofillRegistry.controller = it
            }
        }
}

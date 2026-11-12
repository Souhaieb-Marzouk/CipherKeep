package dev.cipherkeep.app.autofill

import dev.cipherkeep.app.data.SessionController
import dev.cipherkeep.app.data.VaultItem
import dev.cipherkeep.app.data.VaultUiState

/** Process-local pointer used only by the autofill service. */
object AutofillRegistry {
    @Volatile
    var controller: SessionController? = null

    fun unlockedItems(): List<VaultItem> {
        val active = controller ?: return emptyList()
        return if (active.state is VaultUiState.Unlocked) active.items else emptyList()
    }
}

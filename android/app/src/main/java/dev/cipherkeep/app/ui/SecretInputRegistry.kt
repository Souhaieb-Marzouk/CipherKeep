package dev.cipherkeep.app.ui

/**
 * Clears password text fields when the app leaves the foreground. Compose
 * TextField state is String-backed and cannot be zeroized, so this is a
 * best-effort lifetime reduction, not a memory-zeroization guarantee.
 */
object SecretInputRegistry {
    private val resets = mutableSetOf<() -> Unit>()

    fun register(reset: () -> Unit) {
        resets.add(reset)
    }

    fun unregister(reset: () -> Unit) {
        resets.remove(reset)
    }

    fun clearAll() {
        resets.toList().forEach { it() }
    }
}

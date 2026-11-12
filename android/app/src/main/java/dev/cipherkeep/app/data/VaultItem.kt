package dev.cipherkeep.app.data

/** Plaintext item view model material; never persisted outside the encrypted store. */
data class VaultItem(
    val id: ByteArray,
    val title: String,
    val username: String,
    val password: String,
    val type: String = "login",
    val totpUri: String = "",
)

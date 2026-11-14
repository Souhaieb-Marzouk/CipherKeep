package dev.cipherkeep.app.data

/** Plaintext item view model material; never persisted outside the encrypted store. */
data class VaultItem(
    val id: ByteArray,
    val title: String,
    val username: String,
    val password: String,
    val type: String = "login",
    val totpUri: String = "",
    val url: String = "",
    val notes: String = "",
    val cardholder: String = "",
    val cardNumber: String = "",
    val cardExpiry: String = "",
    val cardCvv: String = "",
    val fullName: String = "",
    val email: String = "",
    val phone: String = "",
    val address: String = "",
    val company: String = "",
    val jobTitle: String = "",
    val folder: String = "",
    val tags: List<String> = emptyList(),
    val favorite: Boolean = false,
    val autofillAllowed: Boolean = false,
    val updatedAt: Long = 0,
)

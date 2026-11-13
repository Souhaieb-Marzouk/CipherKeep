package dev.cipherkeep.app.data.alias

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Provider configuration. The token is encrypted with an Android Keystore key. */
data class AliasCredentials(
    val provider: String,
    val baseUrl: String,
    val token: String,
) {
    fun adapter(): AliasProviderAdapter = when (provider) {
        "cloudflare" -> CloudflareAliasProvider(baseUrl, token)
        "simplelogin" -> SimpleLoginAliasProvider(baseUrl, token)
        else -> error("unsupported provider")
    }
}

class AliasCredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("alias_credentials", Context.MODE_PRIVATE)

    fun save(credentials: AliasCredentials) {
        require(credentials.provider == "cloudflare" || credentials.provider == "simplelogin")
        val plaintext = JSONObject()
            .put("provider", credentials.provider)
            .put("baseUrl", credentials.baseUrl)
            .put("token", credentials.token)
            .toString()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plaintext.encodeToByteArray())
        prefs.edit()
            .putString("provider", Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun load(): AliasCredentials? = runCatching {
        val blob = prefs.getString("provider", null) ?: return null
        val bytes = Base64.decode(blob, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(128, bytes.copyOfRange(0, 12)),
        )
        val json = JSONObject(
            cipher.doFinal(bytes.copyOfRange(12, bytes.size)).decodeToString(),
        )
        AliasCredentials(
            provider = json.getString("provider"),
            baseUrl = json.getString("baseUrl"),
            token = json.getString("token"),
        )
    }.getOrNull()

    fun clear() {
        prefs.edit().remove("provider").apply()
        keyStore().deleteEntry(ALIAS)
    }

    private fun key(): SecretKey {
        val store = keyStore()
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private companion object {
        const val ALIAS = "cipherkeep-alias-token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

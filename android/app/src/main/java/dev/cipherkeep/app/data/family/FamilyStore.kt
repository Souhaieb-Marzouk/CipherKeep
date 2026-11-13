package dev.cipherkeep.app.data.family

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

/** Keystore-encrypted owner identity secret, member envelopes and audit. */
class FamilyStore(context: Context) {
    private val prefs = context.getSharedPreferences("family_sharing", Context.MODE_PRIVATE)

    fun load(): JSONObject? = runCatching {
        val blob = prefs.getString("state", null) ?: return null
        val bytes = Base64.decode(blob, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        JSONObject(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).decodeToString())
    }.getOrNull()

    fun save(state: JSONObject) {
        state.optJSONObject("owner")?.let { owner ->
            state.put(
                "owner",
                JSONObject()
                    .put("public", owner.optString("public"))
                    .put("memberId", owner.optString("memberId"))
                    .put("fingerprint", owner.optString("fingerprint"))
                    .put("role", owner.optString("role")),
            )
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(state.toString().encodeToByteArray())
        prefs.edit().putString("state", Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)).apply()
    }

    fun clear() {
        prefs.edit().remove("state").apply()
        keyStore().deleteEntry(ALIAS)
    }

    private fun key(): SecretKey {
        val store = keyStore()
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private companion object {
        const val ALIAS = "cipherkeep-family-state"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

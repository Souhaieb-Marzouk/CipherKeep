package dev.cipherkeep.app.data.webauthn

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class WebAuthnEnrollment(
    val credentialId: String,
    val salt: String,
    val sealed: String,
)

/** WebAuthn PRF enrollment using Android CredentialManager. */
class WebAuthnPrfManager(private val context: Context) {
    private val manager = CredentialManager.create(context)

    suspend fun enroll(): Result<Pair<WebAuthnEnrollment, ByteArray>> = runCatching {
        val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val userId = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val request = JSONObject()
            .put("challenge", b64(challenge))
            .put("rp", JSONObject().put("name", "CipherKeep"))
            .put(
                "user",
                JSONObject()
                    .put("id", b64(userId))
                    .put("name", "owner")
                    .put("displayName", "CipherKeep owner"),
            )
            .put("pubKeyCredParams", JSONArray().put(JSONObject().put("type", "public-key").put("alg", -7)))
            .put("authenticatorSelection", JSONObject().put("userVerification", "required"))
            .put("extensions", JSONObject().put("prf", JSONObject().put("eval", JSONObject().put("first", b64(salt)))))
        val response = manager.createCredential(
            context,
            CreatePublicKeyCredentialRequest(request.toString()),
        ) as CreatePublicKeyCredentialResponse
        val registration = JSONObject(response.registrationResponseJson)
        val prf = registration
            .optJSONObject("clientExtensionResults")
            ?.optJSONObject("prf")
            ?.optJSONObject("results")
            ?.optString("first")
            .orEmpty()
        require(prf.isNotEmpty()) { "authenticator did not return PRF" }
        val credentialId = registration.optString("rawId")
        require(credentialId.isNotEmpty()) { "no credential id" }
        val enrollment = WebAuthnEnrollment(credentialId, b64(salt), "")
        enrollment to unb64(prf)
    }

    suspend fun assert(enrollment: WebAuthnEnrollment): Result<ByteArray> = runCatching {
        val request = JSONObject()
            .put("challenge", b64(ByteArray(32).also { SecureRandom().nextBytes(it) }))
            .put(
                "allowCredentials",
                JSONArray().put(JSONObject().put("type", "public-key").put("id", enrollment.credentialId)),
            )
            .put("userVerification", "required")
            .put(
                "extensions",
                JSONObject().put("prf", JSONObject().put("eval", JSONObject().put("first", enrollment.salt))),
            )
        val response = manager.getCredential(
            context,
            GetCredentialRequest.Builder()
                .addCredentialOption(GetPublicKeyCredentialOption(request.toString()))
                .build(),
        )
        val credential = response.credential as PublicKeyCredential
        val assertion = JSONObject(credential.authenticationResponseJson)
        val prf = assertion
            .optJSONObject("clientExtensionResults")
            ?.optJSONObject("prf")
            ?.optJSONObject("results")
            ?.optString("first")
            .orEmpty()
        require(prf.isNotEmpty()) { "authenticator did not return PRF" }
        unb64(prf)
    }

    private fun b64(value: ByteArray): String =
        Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun unb64(value: String): ByteArray =
        Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}

/** Keystore-encrypted PRF enrollment record. */
class WebAuthnStore(context: Context) {
    private val prefs = context.getSharedPreferences("webauthn", Context.MODE_PRIVATE)

    fun save(enrollment: WebAuthnEnrollment) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val plaintext = JSONObject()
            .put("credentialId", enrollment.credentialId)
            .put("salt", enrollment.salt)
            .put("sealed", enrollment.sealed)
            .toString()
        val encrypted = cipher.doFinal(plaintext.toByteArray())
        prefs.edit().putString("record", Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)).apply()
    }

    fun load(): WebAuthnEnrollment? = runCatching {
        val blob = prefs.getString("record", null) ?: return null
        val bytes = Base64.decode(blob, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val json = JSONObject(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).decodeToString())
        WebAuthnEnrollment(json.getString("credentialId"), json.getString("salt"), json.getString("sealed"))
    }.getOrNull()

    fun clear() {
        prefs.edit().remove("record").apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
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

    private companion object {
        const val ALIAS = "cipherkeep-webauthn"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

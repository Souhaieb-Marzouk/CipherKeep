package dev.cipherkeep.app.data.emergency

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class EmergencyContactInput(val id: String, val address: String)
data class EmergencyShareInput(val contactId: String, val ciphertext: String)
data class EmergencyStatus(
    val id: String,
    val state: String,
    val lastCheckIn: Long,
    val inactivitySecs: Long,
    val graceSecs: Long,
)
data class EmergencyConfig(val cronUrl: String, val token: String, val policyId: String)

class EmergencyCronClient(baseUrl: String, private val token: String) {
    private val base: String = run {
        val url = URL(baseUrl)
        require(url.protocol == "https") { "cron must use HTTPS" }
        require(url.userInfo == null) { "cron URL must not contain credentials" }
        url.toString().trimEnd('/')
    }

    init {
        require(token.length >= 32) { "cron token too short" }
    }

    private fun open(path: String, method: String): HttpURLConnection =
        (URL("$base$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
        }

    private fun body(connection: HttpURLConnection): JSONObject {
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("cron request failed (${connection.responseCode})")
        }
        val bytes = connection.inputStream.use { it.readBytes() }
        require(bytes.size <= 65_536) { "cron response too large" }
        return JSONObject(bytes.decodeToString())
    }

    fun create(
        contacts: List<EmergencyContactInput>,
        shares: List<EmergencyShareInput>,
        inactivitySecs: Long,
        reminderLeadSecs: Long,
        graceSecs: Long,
    ): EmergencyStatus {
        require(contacts.size in 1..2 && shares.size == contacts.size)
        val connection = open("/policies", "POST")
        return try {
            connection.doOutput = true
            val body = JSONObject()
                .put("inactivitySecs", inactivitySecs)
                .put("reminderLeadSecs", reminderLeadSecs)
                .put("graceSecs", graceSecs)
                .put(
                    "contacts",
                    JSONArray().apply {
                        contacts.forEach { put(JSONObject().put("id", it.id).put("address", it.address)) }
                    },
                )
                .put(
                    "shares",
                    JSONArray().apply {
                        shares.forEach { put(JSONObject().put("contactId", it.contactId).put("ciphertext", it.ciphertext)) }
                    },
                )
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            parse(body(connection))
        } finally {
            connection.disconnect()
        }
    }

    fun status(id: String): EmergencyStatus = request("/policies/${encode(id)}", "GET")
    fun checkIn(id: String): EmergencyStatus = request("/policies/${encode(id)}/checkin", "POST")
    fun cancel(id: String): EmergencyStatus = request("/policies/${encode(id)}/cancel", "POST")

    private fun request(path: String, method: String): EmergencyStatus {
        val connection = open(path, method)
        return try { parse(body(connection)) } finally { connection.disconnect() }
    }

    private fun parse(json: JSONObject): EmergencyStatus {
        val id = json.getString("id")
        require(id.isNotEmpty()) { "cron returned no policy id" }
        return EmergencyStatus(
            id = id,
            state = json.optString("state", "armed"),
            lastCheckIn = json.optLong("lastCheckIn"),
            inactivitySecs = json.optLong("inactivitySecs"),
            graceSecs = json.optLong("graceSecs"),
        )
    }

    companion object {
        private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
    }
}

/** Keystore-encrypted cron URL, token and policy id. */
class EmergencyConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("emergency_config", Context.MODE_PRIVATE)

    fun save(config: EmergencyConfig) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val plaintext = JSONObject()
            .put("cronUrl", config.cronUrl)
            .put("token", config.token)
            .put("policyId", config.policyId)
            .toString()
        val encrypted = cipher.doFinal(plaintext.toByteArray())
        prefs.edit().putString("config", Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)).apply()
    }

    fun load(): EmergencyConfig? = runCatching {
        val blob = prefs.getString("config", null) ?: return null
        val bytes = Base64.decode(blob, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val json = JSONObject(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).decodeToString())
        EmergencyConfig(
            cronUrl = json.getString("cronUrl"),
            token = json.getString("token"),
            policyId = json.getString("policyId"),
        )
    }.getOrNull()

    fun clear() {
        prefs.edit().remove("config").apply()
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
        const val ALIAS = "cipherkeep-emergency-token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

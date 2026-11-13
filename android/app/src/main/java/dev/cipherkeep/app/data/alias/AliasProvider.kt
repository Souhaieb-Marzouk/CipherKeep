package dev.cipherkeep.app.data.alias

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A provider-side alias mapping. Never contains a provider credential. */
data class AliasRecord(
    val address: String,
    val provider: String,
    val providerId: String,
    val label: String,
    val enabled: Boolean,
)

/** Provider operations used by the alias UI. */
interface AliasProviderAdapter {
    fun create(label: String): AliasRecord
    fun disable(address: String)
    fun delete(address: String)
    fun list(): List<AliasRecord>
}

private const val MAX_RESPONSE = 1_048_576

private fun requireHttps(value: String): String {
    val url = URL(value)
    require(url.protocol == "https") { "provider must use HTTPS" }
    require(url.userInfo == null) { "provider URL must not contain credentials" }
    return url.toString().trimEnd('/')
}

private fun validateLabel(label: String): String {
    val trimmed = label.trim()
    require(trimmed.isNotEmpty() && trimmed.length <= 128) { "invalid label" }
    return trimmed
}

private fun bounded(response: HttpURLConnection): String {
    if (response.responseCode !in 200..299) {
        throw IllegalStateException("provider request failed (${response.responseCode})")
    }
    val bytes = response.inputStream.use { it.readBytes() }
    require(bytes.size <= MAX_RESPONSE) { "provider response too large" }
    return bytes.decodeToString()
}

private fun parse(value: JSONObject, provider: String): AliasRecord {
    val address = value.optString("address").ifEmpty { value.optString("email") }
    val id = value.optString("providerId").ifEmpty { value.optString("id") }
    val label = value.optString("label").ifEmpty { value.optString("note") }
    require(address.isNotEmpty() && id.isNotEmpty() && label.isNotEmpty()) {
        "provider returned an incomplete alias"
    }
    return AliasRecord(address, provider, id, label, value.optBoolean("enabled", true))
}

/** Cloudflare Email Worker fixture adapter. */
class CloudflareAliasProvider(baseUrl: String, private val token: String) : AliasProviderAdapter {
    private val base = requireHttps(baseUrl)

    init {
        require(token.length >= 32) { "provider token too short" }
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

    override fun create(label: String): AliasRecord {
        val connection = open("/aliases", "POST")
        return try {
            connection.doOutput = true
            connection.outputStream.use {
                it.write(JSONObject().put("label", validateLabel(label)).toString().toByteArray())
            }
            parse(JSONObject(bounded(connection)), "cloudflare")
        } finally {
            connection.disconnect()
        }
    }

    override fun disable(address: String) {
        val connection = open("/aliases/${java.net.URLEncoder.encode(address, "UTF-8")}/disable", "POST")
        try { bounded(connection) } finally { connection.disconnect() }
    }

    override fun delete(address: String) {
        val connection = open("/aliases/${java.net.URLEncoder.encode(address, "UTF-8")}/delete", "POST")
        try { bounded(connection) } finally { connection.disconnect() }
    }

    override fun list(): List<AliasRecord> {
        val connection = open("/aliases", "GET")
        return try {
            val array = JSONArray(bounded(connection))
            (0 until array.length()).map { parse(array.getJSONObject(it), "cloudflare") }
        } finally {
            connection.disconnect()
        }
    }
}

/** Self-hosted SimpleLogin adapter. */
class SimpleLoginAliasProvider(baseUrl: String, private val token: String) : AliasProviderAdapter {
    private val base = requireHttps(baseUrl)

    init {
        require(token.length >= 20) { "provider token too short" }
    }

    private fun open(path: String, method: String): HttpURLConnection =
        (URL("$base$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = false
            setRequestProperty("Authentication", token)
            setRequestProperty("Content-Type", "application/json")
        }

    override fun create(label: String): AliasRecord {
        val connection = open("/api/alias/create", "POST")
        return try {
            connection.doOutput = true
            val slug = validateLabel(label).lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(24)
            connection.outputStream.use {
                it.write(
                    JSONObject()
                        .put("alias_prefix", slug.ifEmpty { "alias" })
                        .put("note", label.trim())
                        .toString()
                        .toByteArray(),
                )
            }
            val value = JSONObject(bounded(connection))
            parse(value.getJSONObject("alias"), "simplelogin")
        } finally {
            connection.disconnect()
        }
    }

    override fun disable(address: String) {
        val found = list().firstOrNull { it.address == address }
            ?: error("alias not found")
        if (!found.enabled) return
        val connection = open("/api/alias/toggle/${java.net.URLEncoder.encode(found.providerId, "UTF-8")}", "POST")
        try { bounded(connection) } finally { connection.disconnect() }
    }

    override fun delete(address: String) {
        val found = list().firstOrNull { it.address == address }
            ?: error("alias not found")
        val connection = open("/api/alias/${java.net.URLEncoder.encode(found.providerId, "UTF-8")}", "DELETE")
        try { bounded(connection) } finally { connection.disconnect() }
    }

    override fun list(): List<AliasRecord> {
        val connection = open("/api/aliases", "GET")
        return try {
            val array = JSONObject(bounded(connection)).getJSONArray("aliases")
            (0 until array.length()).map { parse(array.getJSONObject(it), "simplelogin") }
        } finally {
            connection.disconnect()
        }
    }
}

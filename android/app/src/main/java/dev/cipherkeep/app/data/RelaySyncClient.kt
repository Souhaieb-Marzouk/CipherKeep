package dev.cipherkeep.app.data

import java.net.HttpURLConnection
import java.net.URL

/** Minimal opaque relay client. Never parses payload plaintext. */
class RelaySyncClient(
    private val endpoint: String,
    private val token: String,
) {
    init {
        require(endpoint.startsWith("https://")) { "relay requires HTTPS" }
        require(token.length >= 32) { "relay token too short" }
    }

    fun getManifest(vaultId: String): ByteArray? {
        validate(vaultId)
        val connection = open("$endpoint/v1/$vaultId/manifest", "GET")
        return try {
            when (connection.responseCode) {
                200 -> connection.inputStream.use { it.readBytes().also { bytes -> require(bytes.size <= MAX_BYTES) } }
                404 -> null
                else -> error("relay GET failed: ${connection.responseCode}")
            }
        } finally {
            connection.disconnect()
        }
    }

    fun putManifest(vaultId: String, bytes: ByteArray, etag: String?): String? {
        validate(vaultId)
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "invalid manifest size" }
        val connection = open("$endpoint/v1/$vaultId/manifest", "PUT", etag)
        return try {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.outputStream.use { it.write(bytes) }
            if (connection.responseCode !in 200..299) {
                error("relay PUT failed: ${connection.responseCode}")
            }
            connection.getHeaderField("ETag")
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, method: String, etag: String? = null): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer $token")
            if (etag != null) setRequestProperty("If-Match", etag)
        }

    private fun validate(vaultId: String) {
        require(Regex("^[a-f0-9]{32}$").matches(vaultId)) { "invalid vault id" }
    }

    private companion object {
        const val MAX_BYTES = 64 * 1024 * 1024
    }
}

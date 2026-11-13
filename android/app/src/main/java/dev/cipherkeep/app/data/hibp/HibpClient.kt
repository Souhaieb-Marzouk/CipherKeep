package dev.cipherkeep.app.data.hibp

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

/** Opt-in flag, off by default. */
class HibpPreference(context: Context) {
    private val prefs = context.getSharedPreferences("hibp", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) = prefs.edit().putBoolean("enabled", value).apply()
}

/**
 * Pwned Passwords range client.
 *
 * Sends only the five-character SHA-1 prefix. Responses are cached in memory
 * only; disabling opt-in must discard the cache.
 */
class HibpClient {
    private val cache = mutableMapOf<String, String>()
    private var lastRequestAt = 0L

    fun clear() {
        cache.clear()
        lastRequestAt = 0L
    }

    fun check(password: String): Result<Long> = runCatching {
        val (prefix, suffix) = hashPassword(password)
        val body = cache[prefix] ?: fetch(prefix).also { cache[prefix] = it }
        parse(body, suffix)
    }

    private fun fetch(prefix: String): String {
        require(prefix.length == 5 && prefix.all { it in "0123456789ABCDEF" })
        val wait = 1_500 - (System.currentTimeMillis() - lastRequestAt)
        if (wait > 0) Thread.sleep(wait)
        lastRequestAt = System.currentTimeMillis()
        val connection = (URL("https://api.pwnedpasswords.com/range/$prefix").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = false
            setRequestProperty("Add-Padding", "true")
            setRequestProperty("User-Agent", "CipherKeep-HIBP/1")
        }
        return try {
            if (connection.responseCode != 200) error("HIBP request failed")
            val bytes = connection.inputStream.use { it.readBytes() }
            require(bytes.size in 1..MAX_RESPONSE) { "HIBP response size" }
            bytes.decodeToString()
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(body: String, suffix: String): Long {
        require(body.length <= MAX_RESPONSE)
        return body.lineSequence().firstNotNullOfOrNull { line ->
            val parts = line.split(':', limit = 2)
            if (parts.size != 2) return@firstNotNullOfOrNull null
            val candidate = parts[0]
            if (candidate.length != 35) return@firstNotNullOfOrNull null
            if (!candidate.equals(suffix, ignoreCase = true)) return@firstNotNullOfOrNull null
            parts[1].trim().toLongOrNull()
        } ?: 0L
    }

    private fun hashPassword(password: String): Pair<String, String> {
        val digest = java.security.MessageDigest.getInstance("SHA-1").digest(password.toByteArray())
        val hex = digest.joinToString("") { "%02X".format(it) }
        return hex.substring(0, 5) to hex.substring(5)
    }

    private companion object {
        const val MAX_RESPONSE = 4 * 1024 * 1024
    }
}

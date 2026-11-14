package dev.cipherkeep.app.update

import android.content.Context
import dev.cipherkeep.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed interface UpdateOutcome {
    data class Available(
        val currentVersion: String,
        val latestVersion: String,
        val publishedAt: String,
        val sizeBytes: Long,
        val notes: String,
        val releaseUrl: String,
        val apkUrl: String? = null,
        val apkName: String = "",
        val apkSizeBytes: Long = 0L,
    ) : UpdateOutcome

    data class UpToDate(val currentVersion: String, val latestVersion: String) : UpdateOutcome
    data object RateLimited : UpdateOutcome
    data object NoRelease : UpdateOutcome
    data class Failed(val message: String) : UpdateOutcome
}

/**
 * Manual GitHub Releases checker. It sends no identifiers or query parameters,
 * keeps an ETag/body cache and never downloads or installs an asset.
 */
class UpdateChecker internal constructor(
    private val currentVersion: String,
    private val client: OkHttpClient,
    private val cache: UpdateCache,
    private val url: String,
) {
    constructor(
        context: Context,
        client: OkHttpClient = defaultClient(),
    ) : this(
        BuildConfig.VERSION_NAME,
        client,
        UpdatePreferences(context.applicationContext),
        LATEST_RELEASE,
    )

    suspend fun check(): UpdateOutcome = withContext(Dispatchers.IO) {
        val etag = cache.etag()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "CipherKeep-Android/$currentVersion")
            .apply { if (etag != null) header("If-None-Match", etag) }
            .build()

        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 304 -> {
                        val cached = cache.cachedBody()
                            ?: return@withContext UpdateOutcome.Failed("No cached release metadata.")
                        parse(cached, fromCache = true)
                    }
                    response.code == 404 ->
                        UpdateOutcome.NoRelease
                    response.code == 403 || response.code == 429 ->
                        UpdateOutcome.RateLimited
                    !response.isSuccessful ->
                        UpdateOutcome.Failed("GitHub returned HTTP ${response.code}. Try again later.")
                    else -> {
                        val body = response.body?.string()
                            ?: return@withContext UpdateOutcome.Failed("GitHub returned an empty response.")
                        val responseEtag = response.header("ETag")
                        if (responseEtag != null) {
                            cache.save(responseEtag, body)
                        }
                        parse(body, fromCache = false)
                    }
                }
            }
        } catch (_: Exception) {
            UpdateOutcome.Failed("Could not reach GitHub. Check your connection and try again.")
        }
    }

    private fun parse(raw: String, fromCache: Boolean): UpdateOutcome {
        return try {
            val json = JSONObject(raw)
            val tag = json.optString("tag_name")
            val latest = UpdateVersion.parse(tag)
                ?: return UpdateOutcome.Failed("The release tag is not a valid version.")
            val current = UpdateVersion.parse(currentVersion)
                ?: return UpdateOutcome.Failed("The installed version is invalid.")
            val notes = ReleaseNotes.sanitize(json.optString("body"))
            val url = json.optString("html_url")
            val published = json.optString("published_at")
            var totalSize = 0L
            var apkUrl: String? = null
            var apkName = ""
            var apkSize = 0L
            json.optJSONArray("assets")?.let { assets ->
                for (index in 0 until assets.length()) {
                    val asset = assets.optJSONObject(index) ?: continue
                    val size = asset.optLong("size", 0L)
                    totalSize += size
                    val name = asset.optString("name")
                    val download = asset.optString("browser_download_url")
                    if (name.endsWith(".apk", ignoreCase = true) && download.startsWith("https://")) {
                        apkUrl = download
                        apkName = name
                        apkSize = size
                    }
                }
            }

            if (latest > current) {
                UpdateOutcome.Available(
                    currentVersion = currentVersion,
                    latestVersion = tag,
                    publishedAt = published,
                    sizeBytes = totalSize,
                    notes = notes,
                    releaseUrl = url,
                    apkUrl = apkUrl,
                    apkName = apkName,
                    apkSizeBytes = apkSize,
                )
            } else {
                UpdateOutcome.UpToDate(currentVersion, tag.ifEmpty { "unknown" })
            }
        } catch (_: Exception) {
            UpdateOutcome.Failed(
                if (fromCache) "Cached release metadata is unreadable." else "GitHub returned malformed release metadata.",
            )
        }
    }

    companion object {
        const val LATEST_RELEASE =
            "https://api.github.com/repos/Souhaieb-Marzouk/CipherKeep/releases/latest"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}

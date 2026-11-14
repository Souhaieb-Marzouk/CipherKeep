package dev.cipherkeep.app.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Downloads a release APK, verifies its package identity, and opens the installer. */
object UpdateInstaller {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun download(context: Context, update: UpdateOutcome.Available): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = update.apkUrl ?: error("This release has no APK asset.")
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val target = File(dir, update.apkName.ifBlank { "cipherkeep-${update.latestVersion}.apk" })
                val request = Request.Builder().url(url).header("User-Agent", "CipherKeep-Android").build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("Download failed (HTTP ${response.code}).")
                    val body = response.body ?: error("Download returned no data.")
                    val length = body.contentLength()
                    if (length > 250L * 1024 * 1024) error("Update file is too large.")
                    body.byteStream().use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > 250L * 1024 * 1024) error("Update file is too large.")
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }
                verifyArchive(context, target, update)
                target
            }
        }

    fun install(context: Context, apk: File): Result<Unit> = runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun verifyArchive(context: Context, apk: File, update: UpdateOutcome.Available) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: error("Downloaded file is not an APK.")
        require(archive.packageName == context.packageName) { "Downloaded APK belongs to another app." }
        require(archive.longVersionCode >= context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode) {
            "Downloaded APK is older than the installed app."
        }
        val archiveCerts = certificates(archive)
        val currentCerts = certificates(pm.getPackageInfo(context.packageName, flags))
        require(archiveCerts.isNotEmpty() && archiveCerts == currentCerts) {
            "Downloaded APK signature does not match the installed app."
        }
        require(update.apkSizeBytes <= 0 || apk.length() == update.apkSizeBytes) {
            "Downloaded APK size does not match the release metadata."
        }
    }

    @Suppress("DEPRECATION")
    private fun certificates(info: android.content.pm.PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners ?: emptyArray()
        } else {
            info.signatures ?: emptyArray()
        }
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.toSet()
    }
}

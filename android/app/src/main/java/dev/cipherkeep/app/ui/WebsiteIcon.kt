package dev.cipherkeep.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.cipherkeep.app.data.VaultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

private val faviconClient = OkHttpClient.Builder()
    .connectTimeout(4, TimeUnit.SECONDS)
    .readTimeout(4, TimeUnit.SECONDS)
    .build()

private suspend fun loadWebsiteIcon(context: Context, url: String): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val host = URI(url).host?.lowercase() ?: return@runCatching null
        if (!host.matches(Regex("[a-z0-9.-]+"))) return@runCatching null
        val dir = File(context.cacheDir, "website-icons").apply { mkdirs() }
        val file = File(dir, host.replace('.', '_') + ".png")
        if (file.exists()) {
            BitmapFactory.decodeFile(file.path)?.let { return@runCatching it }
        }
        val candidates = listOf(
            "https://$host/favicon.ico",
            "https://icons.duckduckgo.com/ip3/$host.ico",
        )
        for (candidate in candidates) {
            val request = Request.Builder().url(candidate).build()
            faviconClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use
                val body = response.body ?: return@use
                if (body.contentLength() > 512 * 1024) return@use
                val bytes = body.bytes()
                if (bytes.size > 512 * 1024) return@use
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@use
                file.outputStream().use { it.write(bytes) }
                return@runCatching bitmap
            }
        }
        null
    }.getOrNull()
}

@Composable
fun WebsiteIcon(item: VaultItem, glyph: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(item.url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.url) {
        if (item.url.isNotBlank()) bitmap = loadWebsiteIcon(context, item.url)
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(24.dp),
            )
        } else {
            Text(glyph, style = MaterialTheme.typography.titleMedium)
        }
    }
}

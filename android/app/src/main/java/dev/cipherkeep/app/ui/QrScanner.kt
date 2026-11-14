package dev.cipherkeep.app.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private suspend fun decodeQr(file: File): String? = withContext(Dispatchers.IO) {
    runCatching {
        val bitmap = BitmapFactory.decodeFile(file.path) ?: return@runCatching null
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }.getOrNull()
}

@Composable
fun rememberQrScanner(onText: (String) -> Unit, onError: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val file = remember { File(context.cacheDir, "qr/scan.jpg") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (!ok) return@rememberLauncherForActivityResult
        scope.launch {
            val text = decodeQr(file)
            if (text.isNullOrBlank()) onError("No QR code found. Try again.") else onText(text)
        }
    }
    return {
        runCatching {
            file.parentFile?.mkdirs()
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            launcher.launch(uri)
        }.onFailure { onError("Camera app is unavailable.") }
    }
}

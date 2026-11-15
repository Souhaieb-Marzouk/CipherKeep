package dev.cipherkeep.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Renders [content] as a QR code so another phone can scan it with Scan QR. */
@Composable
fun QrCodeImage(content: String, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, content) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val size = 720
                val hints = mapOf(
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                    EncodeHintType.MARGIN to 1,
                    EncodeHintType.CHARACTER_SET to "UTF-8",
                )
                val matrix = QRCodeWriter().encode(
                    content,
                    BarcodeFormat.QR_CODE,
                    size,
                    size,
                    hints,
                )
                val pixels = IntArray(size * size)
                for (y in 0 until size) {
                    val row = y * size
                    for (x in 0 until size) {
                        pixels[row + x] =
                            if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                    }
                }
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
                bitmap.asImageBitmap()
            }.getOrNull()
        }
    }
    bitmap?.let {
        Image(
            bitmap = it,
            contentDescription = "Pairing QR code",
            modifier = modifier,
            contentScale = ContentScale.Fit,
        )
    }
}

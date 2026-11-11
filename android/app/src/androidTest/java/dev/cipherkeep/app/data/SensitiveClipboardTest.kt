package dev.cipherkeep.app.data

import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SensitiveClipboardTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    @After
    fun clear() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            manager.clearPrimaryClip()
        }
    }

    @Test
    fun marksSensitiveAndClearsAfterTimeout() = runBlocking(Dispatchers.Main) {
        val clipboard = SensitiveClipboard(context, this, timeoutMillis = 2_000)
        clipboard.copy("CipherKeep test", "test-secret") { }

        val clip = manager.primaryClip
        assertTrue(clip != null)
        assertEquals(
            true,
            clip?.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE"),
        )

        delay(2_500)
        val after = manager.primaryClip
        assertTrue(after == null || after.getItemAt(0).text.isNullOrEmpty())
    }
}

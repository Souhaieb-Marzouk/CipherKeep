package dev.cipherkeep.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhysicalSeedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun seedVaultWithTotpAndUrlItems() = runBlocking {
        val controller = SessionController(context)
        controller.bootstrap()
        controller.create("Q7!mZ2#vL9@x").getOrThrow()
        controller.saveItem(
            title = "GitHub Test",
            username = "alice@example.com",
            password = "CorrectHorse#Battery9",
            url = "https://github.com",
            notes = "test item",
            folder = "Work",
            tags = "test",
            favorite = true,
            autofillAllowed = true,
            totpUri = "otpauth://totp/Test:alice?secret=JBSWY3DPEHPK3PXP&issuer=Test",
        ).getOrThrow()
        controller.saveItem(
            title = "Proton Test",
            username = "bob@example.com",
            password = "Another#Password8",
            url = "https://proton.me",
            notes = "",
            folder = "Personal",
            tags = "",
            favorite = false,
            autofillAllowed = true,
            totpUri = "",
        ).getOrThrow()
    }
}

package dev.cipherkeep.app.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdatePreferencesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun dailyTogglePersists() = runBlocking {
        val prefs = UpdatePreferences(context)
        prefs.setDailyEnabled(false)
        assertFalse(prefs.dailyEnabled())
        prefs.setDailyEnabled(true)
        assertTrue(prefs.dailyEnabled())
        prefs.setDailyEnabled(false)
        assertFalse(prefs.dailyEnabled())
    }
}

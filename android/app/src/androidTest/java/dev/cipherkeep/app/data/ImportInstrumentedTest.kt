package dev.cipherkeep.app.data

import android.content.Context
import dev.cipherkeep.app.data.db.VaultDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = runBlocking {
        VaultDatabase.deleteFiles(context)
        val controller = SessionController(context)
        controller.bootstrap()
        controller.create("Q7!mZ2#vL9@x").getOrThrow()
    }

    @Test
    fun protonCsvImportsOneLogin() = runBlocking {
        val csv = """
            type,name,url,email,username,password,note,totp,vault
            login,Proton Login,https://proton.me,alice@example.com,,secret,hello,,Personal
        """.trimIndent().encodeToByteArray()
        val controller = SessionController(context)
        controller.bootstrap()
        controller.unlock("Q7!mZ2#vL9@x").getOrThrow()
        assertEquals(1, controller.importPlaintext(csv).getOrThrow())
    }

    @Test
    fun bitwardenJsonImportsOneLogin() = runBlocking {
        val json = """
            {"folders":[{"id":"f1","name":"Work"}],"items":[
              {"type":1,"name":"GitHub","notes":"deploy","folderId":"f1",
               "login":{"username":"alice","password":"secret","uris":[{"uri":"https://github.com"}]}}
            ]}
        """.trimIndent().encodeToByteArray()
        val controller = SessionController(context)
        controller.bootstrap()
        controller.unlock("Q7!mZ2#vL9@x").getOrThrow()
        assertEquals(1, controller.importPlaintext(json).getOrThrow())
    }
}

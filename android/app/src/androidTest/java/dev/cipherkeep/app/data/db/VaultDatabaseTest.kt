package dev.cipherkeep.app.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VaultDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val passphrase = ByteArray(32) { 0x11 }
    private val itemId = ByteArray(16) { 0x22 }

    @After
    fun cleanUp() {
        VaultDatabase.deleteFiles(context)
    }

    @Test
    fun reopenWithSamePassphrasePreservesItems() = runBlocking {
        VaultDatabase.deleteFiles(context)
        var db = VaultDatabase.open(context, passphrase)
        db.itemDao().upsert(
            ItemEntity(
                itemId = itemId,
                ciphertext = byteArrayOf(1, 2, 3, 4),
                lamport = 3,
                tombstone = false,
                schemaVersion = 1,
                updatedAt = 1_700_000_000_000,
            ),
        )
        db.close()

        db = VaultDatabase.open(context, passphrase)
        val loaded = db.itemDao().get(itemId)
        assertEquals(1, db.itemDao().count())
        assertEquals(3, loaded?.lamport)
        db.close()
    }

    @Test
    fun wrongPassphraseFails() = runBlocking {
        VaultDatabase.deleteFiles(context)
        val db = VaultDatabase.open(context, passphrase)
        db.itemDao().upsert(
            ItemEntity(itemId, byteArrayOf(1), 1, false, 1, 1),
        )
        db.close()

        val wrong = ByteArray(32) { 0x33 }
        assertThrows(Exception::class.java) {
            VaultDatabase.open(context, wrong).also { it.openHelper.writableDatabase }
        }
    }
}

package dev.cipherkeep.app.data.keystore

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class KeystoreManagerTest {
    private val alias = "cipherkeep.test.${UUID.randomUUID()}"
    private val manager = KeystoreManager(alias)

    @After
    fun cleanUp() {
        manager.deleteKey()
    }

    @Test
    fun keyIsNonExportableAndRoundTrips() {
        val storage = manager.ensureKey()
        assertTrue(storage == KeystoreManager.Storage.STRONGBOX || storage == KeystoreManager.Storage.TEE)

        val secret = ByteArray(32) { it.toByte() }
        val wrapped = manager.wrap(secret)
        assertNotEquals(0, wrapped.ciphertext.size)
        assertArrayEquals(secret, manager.unwrap(wrapped))
    }

    @Test
    fun wrongKeyMaterialCannotUnwrap() {
        manager.ensureKey()
        val wrapped = manager.wrap(ByteArray(32) { 1 })
        wrapped.ciphertext[0] = (wrapped.ciphertext[0].toInt() xor 1).toByte()
        assertThrows(Exception::class.java) {
            manager.unwrap(wrapped)
        }
    }
}

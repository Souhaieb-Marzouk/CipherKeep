package dev.cipherkeep.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Host-JVM test for the shared session object. */
class SessionBindingTest {
    @Test
    fun createLockAndUnlock() {
        assumeTrue(System.getProperty("jna.library.path") != null)
        val password = "correct horse battery staple".toByteArray()
        FfiVaultSession.create(password).use { created ->
            assertFalse(created.isLocked())
            assertEquals(3, created.recoveryShares().size)
            val blob = created.blob()

            FfiVaultSession.open(blob).use { locked ->
                assertTrue(locked.isLocked())
                locked.unlockAgain(password)
                assertFalse(locked.isLocked())
                locked.lock()
                assertTrue(locked.isLocked())
            }
        }
        password.fill(0)
    }

    @Test
    fun itemCrudAndLockedFailure() {
        assumeTrue(System.getProperty("jna.library.path") != null)
        val password = "correct horse battery staple".toByteArray()
        FfiVaultSession.create(password).use { created ->
            val itemId = ByteArray(16) { 7 }
            val ciphertext = created.encryptItem(itemId, "payload".toByteArray())
            assertEquals("payload", created.decryptItem(itemId, ciphertext).decodeToString())
            created.saveItem(itemId, "payload".toByteArray())
            assertEquals(1uL, created.itemCount())
            assertEquals(1, created.listItems().size)
            assertEquals("payload", created.getItem(itemId).decodeToString())
            created.deleteItem(itemId)
            assertEquals(0uL, created.itemCount())

            created.saveItem(itemId, "payload".toByteArray())
            created.lock()
            val error = assertThrows(FfiException::class.java) { created.getItem(itemId) }
            assertEquals(5, error.numericCode())
        }
        password.fill(0)
    }

    @Test
    fun biometricMasterKeyUnlocksTheSameBlob() {
        assumeTrue(System.getProperty("jna.library.path") != null)
        val password = "correct horse battery staple".toByteArray()
        FfiVaultSession.create(password).use { created ->
            val master = created.biometricMasterKey()
            assertEquals(32, master.size)
            val blob = created.blob()
            FfiVaultSession.unlockWithMasterKey(blob, master).use { unlocked ->
                assertFalse(unlocked.isLocked())
            }
            master.fill(0)
        }
        password.fill(0)
    }
}

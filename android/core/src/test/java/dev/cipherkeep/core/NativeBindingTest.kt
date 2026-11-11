package dev.cipherkeep.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Host-JVM smoke test for the generated UniFFI bindings.
 *
 * Set `CK_FFI_HOST_DIR` to the directory containing the host
 * `cipherkeep_ffi.dll`/`.so`/`.dylib` before running `:core:testDebugUnitTest`.
 * The Android JNI libraries are built separately for each ABI.
 */
class NativeBindingTest {
    @Test
    fun coreVersionLoadsTheNativeLibrary() {
        assumeTrue(System.getProperty("jna.library.path") != null)
        assertEquals("0.0.0", coreVersion())
    }

    @Test
    fun generateKeyAndFieldRoundTrip() {
        assumeTrue(System.getProperty("jna.library.path") != null)
        val key = generateKey()
        assertEquals(32, key.size)

        val itemId = ByteArray(16) { 7 }
        val envelope = sealField(key, itemId, 10u, 4uL, false, "secret".toByteArray())
        val plaintext = openField(key, itemId, 10u, 4uL, false, envelope)
        assertEquals("secret", plaintext.decodeToString())
        assertTrue(envelope.isNotEmpty())
    }
}

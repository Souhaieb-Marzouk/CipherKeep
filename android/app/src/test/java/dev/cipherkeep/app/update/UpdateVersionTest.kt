package dev.cipherkeep.app.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionTest {
    @Test
    fun comparesNewerEqualAndOlder() {
        assertTrue(UpdateVersion.isNewer("0.1.0", "0.2.0"))
        assertTrue(UpdateVersion.isNewer("1.9.9", "1.10.0"))
        assertFalse(UpdateVersion.isNewer("1.2.3", "1.2.3"))
        assertFalse(UpdateVersion.isNewer("2.0.0", "1.9.9"))
    }

    @Test
    fun acceptsVPrefixAndPrereleaseSuffix() {
        assertTrue(UpdateVersion.isNewer("v0.1.0", "v0.1.1-rc.1"))
        assertTrue(UpdateVersion.isNewer("0.1.0-shell", "0.1.1"))
    }

    @Test
    fun rejectsMalformedTags() {
        assertNull(UpdateVersion.parse("release-1"))
        assertNull(UpdateVersion.parse("1.2"))
        assertFalse(UpdateVersion.isNewer("0.1.0", "not-a-version"))
    }
}

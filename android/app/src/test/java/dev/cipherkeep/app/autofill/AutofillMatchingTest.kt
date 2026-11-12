package dev.cipherkeep.app.autofill

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutofillMatchingTest {
    @Test
    fun exactOriginMatchesCaseInsensitively() {
        assertTrue(originMatches("https://example.com/login", "example.com"))
        assertTrue(originMatches("https://EXAMPLE.com", "example.com"))
    }

    @Test
    fun subdomainsAndEmptyOriginsDoNotMatch() {
        assertFalse(originMatches("https://login.example.com", "example.com"))
        assertFalse(originMatches("https://example.com", "login.example.com"))
        assertFalse(originMatches("https://example.com", ""))
        assertFalse(originMatches("", "example.com"))
        assertFalse(originMatches("not a url", "example.com"))
    }
}

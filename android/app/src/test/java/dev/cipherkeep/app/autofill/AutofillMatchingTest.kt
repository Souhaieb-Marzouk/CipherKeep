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
    fun subdomainsOfTheSameRegistrableDomainMatch() {
        assertTrue(originMatches("https://login.example.com", "example.com"))
        assertTrue(originMatches("https://example.com", "login.example.com"))
        assertTrue(originMatches("https://sso.crunchyroll.com", "crunchyroll.com"))
        assertTrue(originMatches("https://chat.z.ai", "z.ai"))
        assertTrue(originMatches("https://www.example.com", "example.com"))
        assertTrue(originMatches("https://user.example.com:8443/path", "example.com"))
    }

    @Test
    fun differentSitesAndEmptyOriginsDoNotMatch() {
        assertFalse(originMatches("https://example.com", "example.org"))
        assertFalse(originMatches("https://evil-example.com", "example.com"))
        assertFalse(originMatches("https://example.com.evil.org", "example.com"))
        assertFalse(originMatches("https://evil.co.uk", "co.uk"))
        assertFalse(originMatches("https://example.com", ""))
        assertFalse(originMatches("", "example.com"))
        assertFalse(originMatches("not a url", "example.com"))
    }
}

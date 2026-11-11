package dev.cipherkeep.app.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesTest {
    @Test
    fun stripsScriptsAndTags() {
        val notes = ReleaseNotes.sanitize(
            "<h2>Changes</h2><script>alert('x')</script><p>Fixed <b>lock</b> flow.</p>",
        )
        assertFalse(notes.contains("<script>"))
        assertFalse(notes.contains("<h2>"))
        assertTrue(notes.contains("Changes"))
        assertTrue(notes.contains("Fixed lock flow."))
    }

    @Test
    fun keepsOnlyAllowlistedLinks() {
        val notes = ReleaseNotes.sanitize(
            "<a href=\"https://github.com/Souhaieb-Marzouk/CipherKeep/releases\">Release</a> " +
                "<a href=\"https://evil.example/x\">Bad</a>",
        )
        assertTrue(notes.contains("Release (https://github.com/Souhaieb-Marzouk/CipherKeep/releases)"))
        assertFalse(notes.contains("evil.example"))
    }

    @Test
    fun decodesEntitiesWithoutRenderingHtml() {
        val notes = ReleaseNotes.sanitize("&lt;b&gt;not markup&lt;/b&gt; &amp; text")
        assertTrue(notes.contains("<b>not markup</b>"))
    }
}

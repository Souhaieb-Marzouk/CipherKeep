package dev.cipherkeep.app.update

/** Converts untrusted GitHub release markdown/HTML into plain display text. */
object ReleaseNotes {
    private const val MAX_LENGTH = 20_000
    private const val ALLOWED_PREFIX = "https://github.com/Souhaieb-Marzouk/CipherKeep"

    fun sanitize(raw: String): String {
        var text = raw
        text = text.replace(Regex("""<script[\s\S]*?</script>""", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("""<style[\s\S]*?</style>""", RegexOption.IGNORE_CASE), "")
        text = text.replace(
            Regex("""<a\s+[^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
        ) { match ->
            val url = match.groupValues[1]
            val label = match.groupValues[2].replace(Regex("<[^>]+>"), "")
            if (url.startsWith(ALLOWED_PREFIX)) "$label ($url)" else label
        }
        text = text.replace(Regex("<[^>]+>"), "")
        text = text
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
        return text.trim().take(MAX_LENGTH)
    }
}

package dev.cipherkeep.app.update

/** Small, strict semantic-version comparator for release tags. */
data class Version(val major: Int, val minor: Int, val patch: Int) {
    operator fun compareTo(other: Version): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
}

object UpdateVersion {
    private val PATTERN = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:[-+].*)?$""")

    fun parse(raw: String): Version? {
        val match = PATTERN.matchEntire(raw.trim()) ?: return null
        return Version(
            major = match.groupValues[1].toIntOrNull() ?: return null,
            minor = match.groupValues[2].toIntOrNull() ?: return null,
            patch = match.groupValues[3].toIntOrNull() ?: return null,
        )
    }

    fun isNewer(current: String, latest: String): Boolean {
        val currentVersion = parse(current) ?: return false
        val latestVersion = parse(latest) ?: return false
        return latestVersion > currentVersion
    }
}

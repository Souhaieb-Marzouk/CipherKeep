package dev.cipherkeep.app.autofill

import android.content.Context

/**
 * Explicit per-origin overrides for phishing warnings. Origins are not secrets;
 * they are stored locally and can be cleared from the Autofill Inspector later.
 */
class PhishingOverrideStore(context: Context) {
    private val prefs = context.getSharedPreferences("phishing_overrides", Context.MODE_PRIVATE)

    fun isAllowed(host: String): Boolean =
        prefs.getStringSet("origins", emptySet())?.contains(host.lowercase()) == true

    fun allow(host: String) {
        val current = prefs.getStringSet("origins", emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(host.lowercase())
        prefs.edit().putStringSet("origins", current).apply()
    }

    fun clear() {
        prefs.edit().remove("origins").apply()
    }
}

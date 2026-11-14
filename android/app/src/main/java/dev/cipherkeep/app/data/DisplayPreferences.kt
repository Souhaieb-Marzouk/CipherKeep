package dev.cipherkeep.app.data

import android.content.Context

class DisplayPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("display", Context.MODE_PRIVATE)

    fun autofillDefault(): Boolean = prefs.getBoolean("autofill_default", true)

    fun setAutofillDefault(enabled: Boolean) = prefs.edit().putBoolean("autofill_default", enabled).apply()

    fun screenshotsAllowed(): Boolean = prefs.getBoolean("screenshots_allowed", false)

    fun setScreenshotsAllowed(enabled: Boolean) = prefs.edit().putBoolean("screenshots_allowed", enabled).apply()

    fun showUsernames(): Boolean = prefs.getBoolean("show_username", true)

    fun setShowUsernames(enabled: Boolean) = prefs.edit().putBoolean("show_username", enabled).apply()
}

package dev.cipherkeep.app.autofill

import android.app.assist.AssistStructure
import android.os.Build
import android.view.View
import android.view.autofill.AutofillId

/** A parsed login form. Values are never read here. */
data class LoginForm(
    val usernameId: AutofillId?,
    val passwordId: AutofillId?,
    val webDomain: String?,
    val packageName: String?,
    val otpId: AutofillId? = null,
)

/** Exact-origin matching: no subdomain fallback and no empty-origin fill. */
fun originMatches(itemUrl: String, webDomain: String?): Boolean {
    if (webDomain.isNullOrBlank() || itemUrl.isBlank()) {
        return false
    }
    val domain = webDomain.trim().lowercase().removeSuffix(".")
    if (domain.isBlank()) return false
    val host = runCatching { java.net.URI(itemUrl).host }.getOrNull()
        ?: itemUrl.substringAfter("://", itemUrl)
            .substringBefore('/')
            .substringBefore(':')
            .substringBefore('?')
            .trim()
            .lowercase()
    if (host.isBlank()) return false
    return host.removePrefix("www.") == domain.removePrefix("www.")
}

/** Parse visible, enabled username/password fields and the top-level web origin. */
fun parseLoginForm(structure: AssistStructure): LoginForm {
    var usernameId: AutofillId? = null
    var passwordId: AutofillId? = null
    var otpId: AutofillId? = null
    var domain: String? = null
    val packageName = structure.activityComponent?.packageName
    for (index in 0 until structure.windowNodeCount) {
        val root = structure.getWindowNodeAt(index).rootViewNode
        if (domain.isNullOrBlank() && !root.webDomain.isNullOrBlank()) {
            domain = root.webDomain
        }
        walk(root) { node ->
            if (domain.isNullOrBlank() && !node.webDomain.isNullOrBlank()) {
                domain = node.webDomain
            }
            val hints = node.autofillHints?.joinToString(" ").orEmpty().lowercase()
            val identity = "${node.idEntry.orEmpty()} ${node.hint.orEmpty()}".lowercase()
            val password = node.inputType and 0x00000080 != 0 ||
                hints.contains("password") ||
                identity.contains("password") ||
                identity.contains("passwd")
            val otp = !password && (
                hints.contains("one-time-code") ||
                    hints.contains("onetimecode") ||
                    identity.contains("otp") ||
                    identity.contains("totp") ||
                    identity.contains("2fa") ||
                    identity.contains("verification") ||
                    identity.contains("auth code") ||
                    identity.contains("security code") ||
                    (identity.contains("code") && node.inputType and 0x00000002 != 0)
                )
            val username = !password && !otp && (
                hints.contains("username") ||
                    hints.contains("email") ||
                    identity.contains("user") ||
                    identity.contains("email") ||
                    identity.contains("login")
                )
            val autofillable = Build.VERSION.SDK_INT < 28 ||
                (node.importantForAutofill != View.IMPORTANT_FOR_AUTOFILL_NO &&
                    node.importantForAutofill != View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS)
            if (node.autofillId != null && autofillable &&
                node.visibility == View.VISIBLE && node.isEnabled
            ) {
                if (password && passwordId == null) {
                    passwordId = node.autofillId
                } else if (otp && otpId == null) {
                    otpId = node.autofillId
                } else if (username && usernameId == null) {
                    usernameId = node.autofillId
                }
            }
        }
    }
    return LoginForm(usernameId, passwordId, domain, packageName, otpId)
}

private fun walk(node: AssistStructure.ViewNode, visit: (AssistStructure.ViewNode) -> Unit) {
    visit(node)
    for (index in 0 until node.childCount) {
        walk(node.getChildAt(index), visit)
    }
}

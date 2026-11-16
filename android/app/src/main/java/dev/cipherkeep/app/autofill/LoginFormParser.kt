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
    val otpIds: List<AutofillId> = emptyList(),
)

/** Registrable domain: subdomains of the same site match, different sites do not. */
fun registrableDomain(host: String): String {
    val labels = host.trim().lowercase().removeSuffix(".").removePrefix("www.").split('.')
    if (labels.size <= 2) return labels.joinToString(".")
    val lastTwo = labels.takeLast(2).joinToString(".")
    return if (lastTwo in SECOND_LEVEL_SUFFIXES) {
        labels.takeLast(3).joinToString(".")
    } else {
        lastTwo
    }
}

private val SECOND_LEVEL_SUFFIXES = setOf(
    "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk",
    "com.au", "net.au", "org.au", "edu.au", "gov.au",
    "co.jp", "or.jp", "ne.jp", "co.nz", "net.nz", "org.nz",
    "com.br", "com.mx", "com.ar", "com.co", "com.pe",
    "co.in", "net.in", "org.in", "co.za", "com.sg", "com.tr",
    "com.cn", "com.hk", "com.tw", "co.kr", "or.kr", "com.my",
    "co.il", "com.pl", "com.ua", "co.id", "com.ph", "com.vn",
)

/** Exact-origin matching at registrable-domain level: no cross-site fill. */
fun originMatches(itemUrl: String, webDomain: String?): Boolean {
    if (webDomain.isNullOrBlank() || itemUrl.isBlank()) {
        return false
    }
    val target = registrableDomain(webDomain)
    if (target.isBlank()) return false
    val host = runCatching { java.net.URI(itemUrl).host }.getOrNull()
        ?: itemUrl.substringAfter("://", itemUrl)
            .substringBefore('/')
            .substringBefore(':')
            .substringBefore('?')
            .trim()
            .lowercase()
    if (host.isBlank()) return false
    return registrableDomain(host) == target
}

/** Parse visible, enabled username/password fields and the top-level web origin. */
fun parseLoginForm(structure: AssistStructure): LoginForm {
    var usernameId: AutofillId? = null
    var passwordId: AutofillId? = null
    var otpId: AutofillId? = null
    val splitOtp = mutableListOf<AutofillId>()
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
            val maxLength = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) node.maxTextLength else -1
            val identity = "${node.idEntry.orEmpty()} ${node.hint.orEmpty()}".lowercase()
            val password = node.inputType and 0x00000080 != 0 ||
                hints.contains("password") ||
                identity.contains("password") ||
                identity.contains("passwd")
            val numericCode = node.inputType and 0x00000002 != 0 &&
                (identity.contains("code") || identity.contains("pin") ||
                    identity.contains("token") || hints.contains("smsotp"))
            val otp = !password && (
                hints.contains("one-time-code") ||
                    hints.contains("onetimecode") ||
                    hints.contains("smsotp") ||
                    identity.contains("otp") ||
                    identity.contains("totp") ||
                    identity.contains("2fa") ||
                    identity.contains("verification") ||
                    identity.contains("auth code") ||
                    identity.contains("security code") ||
                    numericCode
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
            val looksLikeField = !hints.isBlank() ||
                node.className?.contains("EditText") == true
            if (looksLikeField) {
                android.util.Log.i(
                    "CipherKeepAutofillFields",
                    "id=${node.idEntry} hint=${node.hint} hints=$hints type=${node.inputType} max=$maxLength class=${node.className} pw=$password otp=$otp user=$username",
                )
            }
            if (node.autofillId != null && autofillable &&
                node.visibility == View.VISIBLE && node.isEnabled
            ) {
                val splitBox = !password &&
                    node.inputType and 0x00000002 != 0 &&
                    maxLength in 1..2
                if (splitBox) {
                    node.autofillId?.let { splitOtp.add(it) }
                } else if (password && passwordId == null) {
                    passwordId = node.autofillId
                } else if (otp && otpId == null) {
                    otpId = node.autofillId
                } else if (username && usernameId == null) {
                    usernameId = node.autofillId
                }
            }
        }
    }
    val otpIds = if (splitOtp.size >= 4) splitOtp.take(8) else listOfNotNull(otpId)
    android.util.Log.i(
        "CipherKeepAutofillFields",
        "form pkg=$packageName domain=$domain split=${splitOtp.size} otp=${otpIds.size}",
    )
    return LoginForm(usernameId, passwordId, domain, packageName, otpIds)
}

private fun walk(node: AssistStructure.ViewNode, visit: (AssistStructure.ViewNode) -> Unit) {
    visit(node)
    for (index in 0 until node.childCount) {
        walk(node.getChildAt(index), visit)
    }
}

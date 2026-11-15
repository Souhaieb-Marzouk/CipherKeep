package dev.cipherkeep.app.autofill

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Intent
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.view.View
import dev.cipherkeep.core.phishingCheck
import org.json.JSONObject

/**
 * Tap-to-fill login service. It never submits a form and never reads page values.
 *
 * Web pages are matched by registrable domain, browsers without a domain fall
 * back to recent logins, and native apps are matched from their package name and
 * application label. When the vault is locked the popup offers an unlock sheet
 * that returns the datasets without opening the CipherKeep main window.
 */
class CipherKeepAutofillService : AutofillService() {
    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback,
    ) {
        val structure: AssistStructure? = request.fillContexts.lastOrNull()?.structure
        if (structure == null) {
            callback.onSuccess(null)
            return
        }
        val form = parseLoginForm(structure)
        android.util.Log.i(
            "CipherKeepAutofill",
            "fill pkg=${form.packageName} domain=${form.webDomain} user=${form.usernameId != null} pass=${form.passwordId != null} otp=${form.otpIds.size}",
        )
        if (form.passwordId == null && form.otpIds.isEmpty()) {
            callback.onSuccess(null)
            return
        }
        if (!AutofillRegistry.isUnlocked()) {
            callback.onSuccess(unlockResponse(form))
            return
        }
        val domain = form.webDomain?.takeIf { it.isNotBlank() }
        if (domain != null && form.passwordId != null) {
            val risk = runCatching {
                JSONObject(phishingCheck("https://$domain"))
            }.getOrNull()
            if (risk?.optBoolean("requiresConfirmation") == true &&
                !PhishingOverrideStore(this).isAllowed(domain)
            ) {
                val review = Intent(this, PhishingWarningActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(PhishingWarningActivity.EXTRA_HOST, domain)
                    putExtra(PhishingWarningActivity.EXTRA_DECODED, risk.optString("decodedHost"))
                    putExtra(
                        PhishingWarningActivity.EXTRA_REASONS,
                        risk.optJSONArray("reasons")?.toString().orEmpty(),
                    )
                }
                startActivity(review)
                callback.onSuccess(null)
                return
            }
        }
        val candidates = AutofillRegistry.unlockedItems()
        val matches = AutofillFlow.matchItems(this, candidates, form)
        android.util.Log.i(
            "CipherKeepAutofill",
            "match candidates=${candidates.size} matched=${matches.size}",
        )
        if (matches.isEmpty()) {
            val info = saveInfo(form)
            if (info != null) {
                callback.onSuccess(FillResponse.Builder().setSaveInfo(info).build())
            } else {
                callback.onSuccess(null)
            }
            return
        }
        // Re-check the lock state immediately before releasing plaintext datasets.
        if (!AutofillRegistry.isUnlocked()) {
            callback.onSuccess(null)
            return
        }
        val response = AutofillFlow.buildResponse(
            this,
            matches,
            form,
            System.currentTimeMillis() / 1000,
            saveInfo(form),
        )
        android.util.Log.i("CipherKeepAutofill", "datasets=${matches.size}")
        callback.onSuccess(response)
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        // The app finished a login form: offer to store manually entered values.
        requestSave(request)
        callback.onSuccess()
    }

    private fun saveInfo(form: LoginForm): SaveInfo? {
        val passwordId = form.passwordId ?: return null
        val builder = SaveInfo.Builder(
            SaveInfo.SAVE_DATA_TYPE_USERNAME or SaveInfo.SAVE_DATA_TYPE_PASSWORD,
            arrayOf(passwordId),
        )
        form.usernameId?.let { builder.setOptionalIds(arrayOf(it)) }
        return builder.build()
    }

    private fun requestSave(request: SaveRequest) {
        var username = ""
        var password = ""
        var domain: String? = null
        var packageName: String? = null
        request.fillContexts.forEach { context ->
            val structure = context.structure ?: return@forEach
            if (packageName == null) packageName = structure.activityComponent?.packageName
            for (index in 0 until structure.windowNodeCount) {
                val root = structure.getWindowNodeAt(index).rootViewNode
                if (domain.isNullOrBlank() && !root.webDomain.isNullOrBlank()) {
                    domain = root.webDomain
                }
                walk(root) { node ->
                    if (domain.isNullOrBlank() && !node.webDomain.isNullOrBlank()) {
                        domain = node.webDomain
                    }
                    if (node.autofillId == null) return@walk
                    val text = node.text?.toString()?.trim().orEmpty()
                    if (text.isEmpty()) return@walk
                    val hints = node.autofillHints?.joinToString(" ").orEmpty().lowercase()
                    val identity = "${node.idEntry.orEmpty()} ${node.hint.orEmpty()}".lowercase()
                    val isPassword = node.inputType and 0x00000080 != 0 ||
                        hints.contains("password") ||
                        identity.contains("password") ||
                        identity.contains("passwd")
                    val isUser = hints.contains("username") ||
                        hints.contains("email") ||
                        identity.contains("user") ||
                        identity.contains("email") ||
                        identity.contains("login")
                    if (isPassword && password.isEmpty()) {
                        password = text
                    } else if (isUser && username.isEmpty()) {
                        username = text
                    }
                }
            }
        }
        if (password.isBlank()) return
        val title = domain?.takeIf { it.isNotBlank() }
            ?: packageName?.let { pkg ->
                runCatching {
                    val info = packageManager.getApplicationInfo(pkg, 0)
                    packageManager.getApplicationLabel(info).toString()
                }.getOrNull()
            }
            ?: "Saved login"
        val url = domain?.takeIf { it.isNotBlank() }?.let { "https://$it" }.orEmpty()
        AutofillSaveNotifier.request(this, PendingLogin(title, username, password, url))
    }

    private fun walk(node: AssistStructure.ViewNode, visit: (AssistStructure.ViewNode) -> Unit) {
        if (node.autofillId != null || node.childCount > 0) visit(node)
        for (index in 0 until node.childCount) walk(node.getChildAt(index), visit)
    }

    private fun unlockResponse(form: LoginForm): FillResponse {
        val launch = Intent(this, AutofillUnlockActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            putExtra(AutofillUnlockActivity.EXTRA_PACKAGE, form.packageName)
            putExtra(AutofillUnlockActivity.EXTRA_DOMAIN, form.webDomain)
            putExtra(AutofillUnlockActivity.EXTRA_USER_ID, form.usernameId)
            putExtra(AutofillUnlockActivity.EXTRA_PASSWORD_ID, form.passwordId)
            putParcelableArrayListExtra(
                AutofillUnlockActivity.EXTRA_OTP_IDS,
                ArrayList(form.otpIds),
            )
        }
        val pending = PendingIntent.getActivity(
            this,
            form.hashCode(),
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val presentation = AutofillFlow.presentation(
            this,
            "Unlock CipherKeep",
            "Tap to unlock and fill this login",
        )
        return FillResponse.Builder()
            .setAuthentication(
                (listOfNotNull(form.usernameId, form.passwordId) + form.otpIds).toTypedArray(),
                pending.intentSender,
                presentation,
            )
            .build()
    }
}

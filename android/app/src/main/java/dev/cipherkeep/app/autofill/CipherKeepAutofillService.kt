package dev.cipherkeep.app.autofill

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Intent
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.view.View
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import dev.cipherkeep.app.R
import dev.cipherkeep.app.data.VaultItem
import dev.cipherkeep.core.phishingCheck
import dev.cipherkeep.core.totpCode
import org.json.JSONObject

/**
 * Tap-to-fill login service. It never submits a form and never reads page values.
 *
 * Web pages are matched by exact origin; native apps are matched by package and
 * application-label tokens. When the vault is locked the service offers a single
 * "Unlock CipherKeep" suggestion instead of silently returning nothing. One
 * dataset is emitted per matching item so the user can choose between them, and
 * a matching TOTP field is filled with the current code.
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
        if (form.passwordId == null && form.otpId == null) {
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
        val matches = matchItems(AutofillRegistry.unlockedItems(), form)
        if (matches.isEmpty()) {
            callback.onSuccess(null)
            return
        }
        val now = System.currentTimeMillis() / 1000
        val response = FillResponse.Builder()
        matches.forEach { item ->
            buildDataset(item, form, now)?.let(response::addDataset)
        }
        // Re-check the lock state immediately before releasing plaintext datasets.
        if (!AutofillRegistry.isUnlocked()) {
            callback.onSuccess(null)
            return
        }
        callback.onSuccess(response.build())
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        // Saving is explicit in the vault UI; autofill never persists page values.
        callback.onSuccess()
    }

    private fun buildDataset(item: VaultItem, form: LoginForm, now: Long): Dataset? {
        val presentation = presentationFor(item.title, subtitleFor(item))
        val builder = Dataset.Builder(presentation)
        var filled = false
        form.usernameId?.let { id ->
            if (item.username.isNotBlank()) {
                builder.setValue(id, AutofillValue.forText(item.username), presentation)
                filled = true
            }
        }
        form.passwordId?.let { id ->
            if (item.password.isNotEmpty()) {
                builder.setValue(id, AutofillValue.forText(item.password), presentation)
                filled = true
            }
        }
        form.otpId?.let { id ->
            if (item.totpUri.isNotBlank()) {
                val code = runCatching {
                    totpCode(item.totpUri, now.toULong())?.code
                }.getOrNull()
                if (!code.isNullOrBlank()) {
                    builder.setValue(id, AutofillValue.forText(code), presentation)
                    filled = true
                }
            }
        }
        return if (filled) builder.build() else null
    }

    private fun presentationFor(title: String, subtitle: String): RemoteViews =
        RemoteViews(packageName, R.layout.autofill_item).apply {
            setTextViewText(R.id.autofill_title, title)
            if (subtitle.isBlank()) {
                setViewVisibility(R.id.autofill_subtitle, View.GONE)
            } else {
                setTextViewText(R.id.autofill_subtitle, subtitle)
                setViewVisibility(R.id.autofill_subtitle, View.VISIBLE)
            }
        }

    private fun subtitleFor(item: VaultItem): String {
        if (item.username.isNotBlank()) return item.username
        val host = runCatching { java.net.URI(item.url).host }.getOrNull()
        return host ?: "Saved login"
    }

    private fun unlockResponse(form: LoginForm): FillResponse {
        val launch = Intent(this, dev.cipherkeep.app.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val presentation = presentationFor("Unlock CipherKeep", "Tap to open CipherKeep and autofill")
        return FillResponse.Builder()
            .setAuthentication(
                listOfNotNull(form.usernameId, form.passwordId, form.otpId).toTypedArray(),
                pending.intentSender,
                presentation,
            )
            .build()
    }

    private fun matchItems(all: List<VaultItem>, form: LoginForm): List<VaultItem> {
        val domain = form.webDomain?.takeIf { it.isNotBlank() }
        if (domain != null) {
            return all.filter {
                it.autofillAllowed && hasFillableValue(it) && originMatches(it.url, domain)
            }.take(5)
        }
        val packageName = form.packageName
        if (packageName != null && packageName in BROWSER_PACKAGES) {
            // Browsers occasionally deliver no web domain. Offer recent saved
            // logins so the user can still pick one; nothing is filled silently.
            return all.filter {
                it.autofillAllowed && hasFillableValue(it) && it.url.isNotBlank()
            }.sortedByDescending { it.updatedAt }.take(5)
        }
        val tokens = appTokens(packageName)
        if (tokens.isEmpty()) return emptyList()
        return all.asSequence()
            .filter { it.autofillAllowed && hasFillableValue(it) }
            .map { it to appScore(it, tokens) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(5)
            .map { it.first }
            .toList()
    }

    private fun hasFillableValue(item: VaultItem): Boolean =
        item.password.isNotEmpty() || item.totpUri.isNotBlank()

    private fun appTokens(packageName: String?): Set<String> {
        if (packageName.isNullOrBlank()) return emptySet()
        val raw = mutableListOf(packageName)
        runCatching {
            val info = packageManager.getApplicationInfo(packageName, 0)
            raw += packageManager.getApplicationLabel(info).toString()
        }
        return raw
            .flatMap { it.lowercase().split(Regex("[^a-z0-9]+")) }
            .filter { it.length >= 3 && it !in IGNORED_TOKENS }
            .toSet()
    }

    private fun appScore(item: VaultItem, tokens: Set<String>): Int {
        val title = item.title.lowercase()
        val url = item.url.lowercase()
        val username = item.username.lowercase()
        val notes = item.notes.lowercase()
        var score = 0
        tokens.forEach { token ->
            if (title.contains(token)) score += 3
            if (url.contains(token)) score += 2
            if (username.contains(token)) score += 1
            if (notes.contains(token)) score += 1
        }
        return score
    }

    companion object {
        private val IGNORED_TOKENS = setOf(
            "com", "org", "net", "android", "app", "apps", "www", "the", "inc", "llc", "mobile",
        )

        private val BROWSER_PACKAGES = setOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary",
            "org.mozilla.firefox",
            "org.mozilla.firefox_beta",
            "org.mozilla.fenix",
            "com.microsoft.emmx",
            "com.brave.browser",
            "com.opera.browser",
            "com.opera.mini.native",
            "com.sec.android.app.sbrowser",
            "com.duckduckgo.mobile.android",
            "com.vivaldi.browser",
            "org.chromium.chrome",
        )
    }
}

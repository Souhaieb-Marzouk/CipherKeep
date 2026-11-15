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
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import dev.cipherkeep.app.data.VaultItem
import dev.cipherkeep.core.phishingCheck
import org.json.JSONObject

/**
 * Tap-to-fill login service. It never submits a form and never reads page values.
 *
 * Web pages are matched by exact origin; native apps are matched by package and
 * application-label tokens because they carry no web domain. When the vault is
 * locked the service offers a single "Unlock CipherKeep" suggestion instead of
 * silently returning nothing.
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
        val passwordId = form.passwordId
        if (passwordId == null) {
            callback.onSuccess(null)
            return
        }
        if (!AutofillRegistry.isUnlocked()) {
            callback.onSuccess(unlockResponse(form, passwordId))
            return
        }
        val domain = form.webDomain?.takeIf { it.isNotBlank() }
        if (domain != null) {
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
        val items = matchItems(AutofillRegistry.unlockedItems(), form)
        if (items.isEmpty()) {
            callback.onSuccess(null)
            return
        }
        val response = FillResponse.Builder()
        items.forEach { item ->
            val presentation = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply {
                setTextViewText(android.R.id.text1, item.title)
            }
            val dataset = Dataset.Builder(presentation).apply {
                form.usernameId?.let { id ->
                    setValue(id, AutofillValue.forText(item.username), presentation)
                }
                setValue(passwordId, AutofillValue.forText(item.password), presentation)
            }.build()
            response.addDataset(dataset)
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

    private fun unlockResponse(form: LoginForm, passwordId: AutofillId): FillResponse {
        val launch = Intent(this, dev.cipherkeep.app.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val presentation = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply {
            setTextViewText(android.R.id.text1, "Unlock CipherKeep to autofill")
        }
        return FillResponse.Builder()
            .setAuthentication(
                listOfNotNull(form.usernameId, passwordId).toTypedArray(),
                pending.intentSender,
                presentation,
            )
            .build()
    }

    private fun matchItems(all: List<VaultItem>, form: LoginForm): List<VaultItem> {
        val domain = form.webDomain?.takeIf { it.isNotBlank() }
        if (domain != null) {
            return all.filter {
                it.autofillAllowed && it.password.isNotEmpty() && originMatches(it.url, domain)
            }.take(5)
        }
        val tokens = appTokens(form.packageName)
        if (tokens.isEmpty()) return emptyList()
        return all.asSequence()
            .filter { it.autofillAllowed && it.password.isNotEmpty() }
            .map { it to appScore(it, tokens) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(5)
            .map { it.first }
            .toList()
    }

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
    }
}

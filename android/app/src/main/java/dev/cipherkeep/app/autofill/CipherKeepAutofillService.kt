package dev.cipherkeep.app.autofill

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
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import dev.cipherkeep.core.phishingCheck
import org.json.JSONObject

/** Exact-origin, tap-to-fill login service. It never submits a form. */
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
        if (form.passwordId == null || form.webDomain.isNullOrBlank()) {
            callback.onSuccess(null)
            return
        }
        val risk = runCatching {
            JSONObject(phishingCheck("https://${form.webDomain}"))
        }.getOrNull()
        if (risk?.optBoolean("requiresConfirmation") == true &&
            !PhishingOverrideStore(this).isAllowed(form.webDomain)
        ) {
            val review = Intent(this, PhishingWarningActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(PhishingWarningActivity.EXTRA_HOST, form.webDomain)
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
        val items = AutofillRegistry.unlockedItems()
            .filter { it.autofillAllowed && originMatches(it.url, form.webDomain) && it.password.isNotEmpty() }
            .take(5)
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
                setValue(form.passwordId, AutofillValue.forText(item.password), presentation)
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
}

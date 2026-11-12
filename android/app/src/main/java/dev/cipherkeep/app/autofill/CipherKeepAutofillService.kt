package dev.cipherkeep.app.autofill

import android.app.assist.AssistStructure
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
        val items = AutofillRegistry.unlockedItems()
            .filter { originMatches(it.url, form.webDomain) && it.password.isNotEmpty() }
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
        callback.onSuccess(response.build())
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        // Saving is explicit in the vault UI; autofill never persists page values.
        callback.onSuccess()
    }
}

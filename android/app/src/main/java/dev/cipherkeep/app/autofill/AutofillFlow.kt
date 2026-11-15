package dev.cipherkeep.app.autofill

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import android.view.View
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import dev.cipherkeep.app.R
import dev.cipherkeep.app.data.VaultItem
import dev.cipherkeep.core.totpCode

/**
 * Generic matching and dataset construction shared by the autofill service and
 * the unlock activity. Nothing here is specific to one application:
 *
 * - web pages are matched by registrable domain,
 * - browsers that hide the domain fall back to recent saved logins,
 * - native apps are matched from their package name and application label.
 */
object AutofillFlow {
    fun matchItems(context: Context, all: List<VaultItem>, form: LoginForm): List<VaultItem> {
        val domain = form.webDomain?.takeIf { it.isNotBlank() }
        if (domain != null) {
            return all.filter {
                it.autofillAllowed && hasFillableValue(it) && originMatches(it.url, domain)
            }.take(5)
        }
        if (isBrowser(context, form.packageName)) {
            return all.filter {
                it.autofillAllowed && hasFillableValue(it) && it.url.isNotBlank()
            }.sortedByDescending { it.updatedAt }.take(5)
        }
        val tokens = appTokens(context, form.packageName)
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

    fun buildResponse(
        context: Context,
        items: List<VaultItem>,
        form: LoginForm,
        now: Long,
    ): FillResponse {
        val response = FillResponse.Builder()
        items.forEach { item -> buildDataset(context, item, form, now)?.let(response::addDataset) }
        return response.build()
    }

    fun buildDataset(
        context: Context,
        item: VaultItem,
        form: LoginForm,
        now: Long,
    ): Dataset? {
        val presentation = presentation(context, item.title, subtitle(item))
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
        if (form.otpIds.isNotEmpty() && item.totpUri.isNotBlank()) {
            val code = runCatching {
                totpCode(item.totpUri, now.toULong())?.code
            }.getOrNull()
            if (!code.isNullOrBlank()) {
                if (form.otpIds.size == 1) {
                    builder.setValue(form.otpIds[0], AutofillValue.forText(code), presentation)
                    filled = true
                } else if (code.length >= form.otpIds.size) {
                    form.otpIds.forEachIndexed { index, id ->
                        builder.setValue(
                            id,
                            AutofillValue.forText(code[index].toString()),
                            presentation,
                        )
                    }
                    filled = true
                }
            }
        }
        return if (filled) builder.build() else null
    }

    fun presentation(context: Context, title: String, subtitle: String): RemoteViews =
        RemoteViews(context.packageName, R.layout.autofill_item).apply {
            setTextViewText(R.id.autofill_title, title)
            if (subtitle.isBlank()) {
                setViewVisibility(R.id.autofill_subtitle, View.GONE)
            } else {
                setTextViewText(R.id.autofill_subtitle, subtitle)
                setViewVisibility(R.id.autofill_subtitle, View.VISIBLE)
            }
        }

    /** True when [packageName] is an installed browser, detected generically. */
    fun isBrowser(context: Context, packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val handlers = runCatching {
            context.packageManager.queryIntentActivities(intent, 0)
        }.getOrDefault(emptyList())
        return handlers.any { it.activityInfo?.packageName == packageName }
    }

    private fun subtitle(item: VaultItem): String {
        if (item.username.isNotBlank()) return item.username
        val host = runCatching { java.net.URI(item.url).host }.getOrNull()
        return host ?: "Saved login"
    }

    private fun hasFillableValue(item: VaultItem): Boolean =
        item.password.isNotEmpty() || item.totpUri.isNotBlank()

    private fun appTokens(context: Context, packageName: String?): Set<String> {
        if (packageName.isNullOrBlank()) return emptySet()
        val raw = mutableListOf(packageName)
        runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            raw += context.packageManager.getApplicationLabel(info).toString()
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

    private val IGNORED_TOKENS = setOf(
        "com", "org", "net", "android", "app", "apps", "www", "the", "inc", "llc", "mobile",
    )
}

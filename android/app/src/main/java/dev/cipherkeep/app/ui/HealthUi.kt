package dev.cipherkeep.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.cipherkeep.app.data.SessionController
import dev.cipherkeep.core.healthReport
import org.json.JSONArray
import org.json.JSONObject
import dev.cipherkeep.app.ui.theme.CKDimens

@Composable
fun HealthSection(controller: SessionController) {
    var filter by remember { mutableStateOf("All") }
    val report = remember(controller.items) {
        runCatching {
            val values = JSONArray()
            controller.items.forEach { item ->
                values.put(
                    JSONObject()
                        .put("itemId", item.id.joinToString("") { "%02x".format(it) })
                        .put("password", item.password)
                        .put("updatedAt", item.updatedAt / 1000)
                        .put("hasTotp", item.totpUri.isNotEmpty())
                        .put(
                            "breached",
                            dev.cipherkeep.app.data.hibp.BreachCache.count(
                                item.id.joinToString("") { byte -> "%02x".format(byte) },
                            ),
                        ),
                )
            }
            JSONObject(
                healthReport(
                    values.toString(),
                    (System.currentTimeMillis() / 1000).toULong(),
                    (90L * 86_400).toULong(),
                ),
            )
        }.getOrNull()
    }
    Column(verticalArrangement = Arrangement.spacedBy(CKDimens.Space2)) {
        Text("Password health", style = MaterialTheme.typography.titleMedium)
        if (report == null) {
            Text("Health analysis unavailable.", style = MaterialTheme.typography.labelSmall)
            return@Column
        }
        Text(
            "Weak ${report.optInt("weak")} · Reused ${report.optInt("reused")} · Old ${report.optInt("old")} · Breached ${report.optInt("breached")} · No TOTP ${report.optInt("missingTotp")}",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(CKDimens.Space2)) {
            listOf("All", "Weak", "Reused", "Old", "Breached", "NoTOTP").forEach { value ->
                OutlinedButton(onClick = { filter = value }) {
                    Text(if (filter == value) "✓ $value" else value, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        val items = report.optJSONArray("items") ?: JSONArray()
        for (index in 0 until items.length()) {
            val entry = items.getJSONObject(index)
            val reasons = entry.optJSONArray("reasons")?.let { array ->
                (0 until array.length()).map { array.optString(it) }
            } ?: emptyList()
            if (filter != "All" && reasons.none { it.equals(filter, ignoreCase = true) || (filter == "NoTOTP" && it == "MissingTotp") }) continue
            val item = controller.items.firstOrNull {
                it.id.joinToString("") { byte -> "%02x".format(byte) } == entry.optString("itemId")
            } ?: continue
            Text(
                "${item.title} — score ${entry.optInt("score")} — ${reasons.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text("No analysis result is persisted; it clears when the vault locks.", style = MaterialTheme.typography.labelSmall)
    }
}

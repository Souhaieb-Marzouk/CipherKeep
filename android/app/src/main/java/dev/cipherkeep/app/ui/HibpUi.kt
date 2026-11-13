package dev.cipherkeep.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.cipherkeep.app.data.SessionController
import dev.cipherkeep.app.data.hibp.HibpClient
import dev.cipherkeep.app.data.hibp.HibpPreference
import dev.cipherkeep.app.ui.theme.CKDimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HibpSection(context: android.content.Context, controller: SessionController) {
    val preference = remember { HibpPreference(context) }
    val client = remember { HibpClient() }
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(preference.enabled) }
    var confirming by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(CKDimens.Space2)) {
        Text("Breach check", style = MaterialTheme.typography.titleMedium)
        Text(
            "Off by default. When enabled, the app sends only the first 5 SHA-1 characters of a password to api.pwnedpasswords.com. The full hash never leaves the device. No background checks.",
            style = MaterialTheme.typography.labelSmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    preference.enabled = it
                    if (!it) {
                        client.clear()
                        message = "Breach checking disabled; cached ranges discarded."
                    }
                },
            )
            Text("Enable opt-in breach checking")
        }
        OutlinedButton(
            enabled = enabled,
            onClick = { confirming = true },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Scan vault passwords") }
        message?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Send password prefixes?") },
            text = {
                Text(
                    "This sends one 5-character SHA-1 prefix per stored password to api.pwnedpasswords.com. " +
                        "No password, full hash, username or vault identifier is sent. Continue?",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    scope.launch {
                        message = "Checking ${controller.items.size} item(s)…"
                        val result = withContext(Dispatchers.IO) {
                            var pwned = 0
                            var checked = 0
                            for (item in controller.items) {
                                if (item.password.isEmpty()) continue
                                checked += 1
                                runCatching { client.check(item.password).getOrThrow() }
                                    .onSuccess { if (it > 0) pwned += 1 }
                            }
                            checked to pwned
                        }
                        message = "Checked ${result.first} password(s); ${result.second} appear in known breaches."
                    }
                }) { Text("Check") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            },
        )
    }
}

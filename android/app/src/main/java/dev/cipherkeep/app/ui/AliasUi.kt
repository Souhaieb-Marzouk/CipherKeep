package dev.cipherkeep.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.cipherkeep.app.data.SensitiveClipboard
import dev.cipherkeep.app.data.SessionController
import dev.cipherkeep.app.data.alias.AliasCredentialStore
import dev.cipherkeep.app.data.alias.AliasCredentials
import dev.cipherkeep.app.data.alias.AliasProviderAdapter
import dev.cipherkeep.app.data.alias.AliasRecord
import dev.cipherkeep.app.ui.theme.CKDimens
import kotlinx.coroutines.launch

@Composable
fun AliasSection(context: android.content.Context, controller: SessionController) {
    val store = remember { AliasCredentialStore(context) }
    val scope = rememberCoroutineScope()
    val clipboard = remember { SensitiveClipboard(context, scope) }
    var provider by remember { mutableStateOf("cloudflare") }
    var baseUrl by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf<AliasCredentials?>(null) }
    var label by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var aliases by remember { mutableStateOf<List<AliasRecord>>(emptyList()) }
    var adapter by remember { mutableStateOf<AliasProviderAdapter?>(null) }

    fun refresh(current: AliasProviderAdapter?) {
        if (current == null) return
        scope.launch {
            aliases = runCatching { current.list() }.getOrElse {
                message = "Provider list failed."
                emptyList()
            }
        }
    }

    LaunchedEffect(Unit) {
        saved = store.load()
        saved?.let {
            provider = it.provider
            baseUrl = it.baseUrl
            val active = it.adapter()
            adapter = active
            refresh(active)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(CKDimens.Space2)) {
        Text("Email aliases", style = MaterialTheme.typography.titleMedium)
        Text(
            "Optional. Aliases come from a provider you control; CipherKeep never proxies mail.",
            style = MaterialTheme.typography.labelSmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(CKDimens.Space2)) {
            OutlinedButton(onClick = { provider = "cloudflare" }) {
                Text(if (provider == "cloudflare") "✓ Cloudflare" else "Cloudflare")
            }
            OutlinedButton(onClick = { provider = "simplelogin" }) {
                Text(if (provider == "simplelogin") "✓ SimpleLogin" else "SimpleLogin")
            }
        }
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Provider HTTPS URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text(if (saved == null) "Provider token" else "New token (blank keeps saved)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = {
                val effective = token.ifBlank { saved?.token ?: "" }
                if (baseUrl.isBlank() || effective.length < 20) {
                    message = "Provider URL and token are required."
                } else {
                    runCatching {
                        val next = AliasCredentials(provider, baseUrl.trim(), effective)
                        next.adapter().list()
                        store.save(next)
                        saved = next
                        token = ""
                        val created = next.adapter()
                        adapter = created
                        refresh(created)
                        message = "Provider saved in Keystore-backed storage."
                    }.onFailure { message = "Provider configuration rejected." }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save provider") }
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("Site name for new alias") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = {
                val current = adapter
                if (current == null) {
                    message = "Configure a provider first."
                } else {
                    scope.launch {
                        runCatching { current.create(label) }
                            .onSuccess { record ->
                                controller.saveAlias(record).getOrThrow()
                                label = ""
                                message = "Alias created and stored in the vault."
                                refresh(current)
                            }
                            .onFailure { message = "Alias creation failed." }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Create alias") }
        if (aliases.isEmpty()) {
            Text("No aliases returned by the configured provider.", style = MaterialTheme.typography.labelSmall)
        }
        aliases.forEach { record ->
            Column {
                Text(record.address, style = MaterialTheme.typography.bodySmall)
                Text(
                    "${record.label} · ${record.provider} · ${if (record.enabled) "enabled" else "disabled"}",
                    style = MaterialTheme.typography.labelSmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(CKDimens.Space2)) {
                    TextButton(onClick = {
                        clipboard.copy("Alias", record.address)
                        message = "Alias copied; clipboard clears after 30 seconds."
                    }) { Text("Copy") }
                    TextButton(
                        enabled = record.enabled,
                        onClick = {
                            scope.launch {
                                runCatching {
                                    val current = adapter ?: error("no provider")
                                    current.disable(record.address)
                                    controller.setAliasEnabled(record.address, false).getOrThrow()
                                    refresh(current)
                                }.onFailure { message = "Disable failed." }
                            }
                        },
                    ) { Text("Disable") }
                    TextButton(onClick = {
                        scope.launch {
                            runCatching {
                                val current = adapter ?: error("no provider")
                                current.delete(record.address)
                                controller.deleteAliasItem(record.address).getOrThrow()
                                refresh(current)
                            }.onFailure { message = "Delete failed." }
                        }
                    }) { Text("Delete") }
                }
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
    }
}

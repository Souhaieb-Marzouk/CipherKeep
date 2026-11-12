package dev.cipherkeep.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.cipherkeep.app.ui.theme.CKDimens

/** Persisted Android SAF folder used by the local-folder sync adapter. */
@Composable
fun SyncFolderSection(context: Context) {
    val prefs = remember { context.getSharedPreferences("sync", Context.MODE_PRIVATE) }
    var folder by remember { mutableStateOf(prefs.getString("local_folder", null)) }
    var probe by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            prefs.edit().putString("local_folder", uri.toString()).apply()
            folder = uri.toString()
        }
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier.padding(CKDimens.Space4),
            verticalArrangement = Arrangement.spacedBy(CKDimens.Space2),
        ) {
            Text("Local-folder sync", style = MaterialTheme.typography.titleMedium)
            Text(
                text = folder?.let { "Folder: $it" } ?: "No folder selected. Sync stays offline until one is chosen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { picker.launch(null) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (folder == null) "Choose sync folder" else "Change sync folder") }
            if (folder != null) {
                OutlinedButton(
                    onClick = {
                        probe = if (
                            dev.cipherkeep.app.data.AndroidSyncFolder(context, Uri.parse(folder))
                                .probe()
                        ) "Folder write/delete verified." else "Folder is not writable."
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Verify folder access") }
            }
            probe?.let {
                Text(it, style = MaterialTheme.typography.labelSmall)
            }
            Text(
                text = "The adapter writes only the sealed manifest and opaque blobs. Android grants access to this folder only.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Conflict resolution surface for merged items. */
data class SyncConflict(
    val itemTitle: String,
    val field: String,
    val left: String,
    val right: String,
)

@Composable
fun SyncConflictDialog(
    conflict: SyncConflict,
    onKeepLocal: () -> Unit,
    onKeepRemote: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Resolve sync conflict") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(CKDimens.Space2)) {
                Text("${conflict.itemTitle} · ${conflict.field}")
                Text("Local: ${conflict.left}", style = MaterialTheme.typography.bodySmall)
                Text("Remote: ${conflict.right}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Keeping one side does not discard the other item's unrelated fields.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onKeepLocal) { Text("Keep local") } },
        dismissButton = {
            TextButton(onClick = onKeepRemote) { Text("Keep remote") }
        },
    )
}


/** Relay sync controls. The token is session-only and never persisted. */
@Composable
fun RelaySyncSection(controller: dev.cipherkeep.app.data.SessionController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var endpoint by remember { mutableStateOf("https://cipherkeep-relay.marzouk-souhaieb.workers.dev") }
    var token by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier.padding(CKDimens.Space4),
            verticalArrangement = Arrangement.spacedBy(CKDimens.Space2),
        ) {
            Text("Relay sync", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = endpoint,
                onValueChange = { endpoint = it },
                label = { Text("HTTPS relay endpoint") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Relay token (session only)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = {
                    scope.launch {
                        message = dev.cipherkeep.app.data.AndroidSyncEngine(context, controller)
                            .sync(endpoint.trim(), token)
                            .fold(
                                { "Synced ${it.records} record(s); conflicts ${it.conflicts}." },
                                { "Sync failed." },
                            )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Sync now") }
            message?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            Text(
                "The token is not saved. Use a test vault until conflict resolution is complete.",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

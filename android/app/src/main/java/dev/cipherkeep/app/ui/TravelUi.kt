package dev.cipherkeep.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.cipherkeep.app.data.SessionController
import dev.cipherkeep.app.ui.theme.CKDimens
import kotlinx.coroutines.launch

@Composable
fun TravelScreen(controller: SessionController) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier.fillMaxSize().padding(CKDimens.Space4),
        verticalArrangement = Arrangement.spacedBy(CKDimens.Space2),
    ) {
        Text("Travel Mode", style = MaterialTheme.typography.headlineSmall)
        Text(
            "This vault is hidden. It is not deleted, but this is not plausible deniability: local metadata can reveal that a vault exists.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Master password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                scope.launch {
                    controller.unlock(password).onFailure { error = "Could not restore the vault." }
                    password = ""
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Restore vault") }
        error?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
fun TravelModeSection(controller: SessionController) {
    Column(verticalArrangement = Arrangement.spacedBy(CKDimens.Space2)) {
        Text("Travel Mode", style = MaterialTheme.typography.titleMedium)
        Text(
            "Hide this vault from lists, search and autofill. Restore needs the master password. Not plausible deniability.",
            style = MaterialTheme.typography.labelSmall,
        )
        OutlinedButton(
            onClick = { controller.enterTravelMode() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Enable Travel Mode") }
    }
}

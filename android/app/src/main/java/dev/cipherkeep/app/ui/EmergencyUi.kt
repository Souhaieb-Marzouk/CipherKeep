package dev.cipherkeep.app.ui

import android.util.Base64
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.cipherkeep.app.data.SessionController
import dev.cipherkeep.app.data.emergency.EmergencyConfig
import dev.cipherkeep.app.data.emergency.EmergencyConfigStore
import dev.cipherkeep.app.data.emergency.EmergencyContactInput
import dev.cipherkeep.app.data.emergency.EmergencyCronClient
import dev.cipherkeep.app.data.emergency.EmergencyShareInput
import dev.cipherkeep.app.ui.theme.CKDimens
import kotlinx.coroutines.launch

@Composable
fun EmergencySection(context: android.content.Context, controller: SessionController) {
    val store = remember { EmergencyConfigStore(context) }
    val scope = rememberCoroutineScope()
    var cronUrl by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var contact1 by remember { mutableStateOf("") }
    var contact2 by remember { mutableStateOf("") }
    var days by remember { mutableStateOf("30") }
    var grace by remember { mutableStateOf("60") }
    var saved by remember { mutableStateOf<EmergencyConfig?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var shares by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        saved = store.load()
        saved?.let { cronUrl = it.cronUrl }
    }

    fun render(state: dev.cipherkeep.app.data.emergency.EmergencyStatus) {
        status = "Policy ${state.state}; release deadline ${state.lastCheckIn + state.inactivitySecs + state.graceSecs}"
    }

    Column(verticalArrangement = Arrangement.spacedBy(CKDimens.Space2)) {
        Text("Emergency access", style = MaterialTheme.typography.titleMedium)
        Text(
            "Optional dead-man's-switch. The cron holds one Shamir share and cannot decrypt alone. Coercion is not solved.",
            style = MaterialTheme.typography.labelSmall,
        )
        OutlinedTextField(cronUrl, { cronUrl = it }, label = { Text("Cron HTTPS URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            token,
            { token = it },
            label = { Text(if (saved == null) "Cron token" else "New token (blank keeps saved)") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(contact1, { contact1 = it }, label = { Text("Contact 1 email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(contact2, { contact2 = it }, label = { Text("Contact 2 email (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(CKDimens.Space2)) {
            OutlinedTextField(days, { days = it }, label = { Text("Inactivity days") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(grace, { grace = it }, label = { Text("Grace minutes") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        OutlinedButton(
            onClick = {
                scope.launch {
                    runCatching {
                        val contacts = listOf(contact1, contact2)
                            .map(String::trim)
                            .filter(String::isNotEmpty)
                            .mapIndexed { index, address -> EmergencyContactInput("contact-${index + 1}", address) }
                        val recovery = controller.recoveryShares()
                        require(contacts.isNotEmpty() && recovery.size == 3)
                        val effective = token.ifBlank { saved?.token ?: "" }
                        val client = EmergencyCronClient(cronUrl.trim(), effective)
                        val policy = client.create(
                            contacts,
                            contacts.map {
                                EmergencyShareInput(it.id, Base64.encodeToString(recovery[2].toByteArray(), Base64.NO_WRAP))
                            },
                            days.toLongOrNull()?.times(86_400) ?: error("invalid days"),
                            86_400,
                            grace.toLongOrNull()?.times(60) ?: error("invalid grace"),
                        )
                        store.save(EmergencyConfig(cronUrl.trim(), effective, policy.id))
                        saved = store.load()
                        controller.saveEmergencyContacts(contacts, policy.id).getOrThrow()
                        render(policy)
                        shares = "Give these shares to the contacts offline:\n" +
                            contacts.mapIndexed { index, contact ->
                                "${contact.id}: ${recovery[index]}"
                            }.joinToString("\n")
                    }.onFailure { status = "Emergency arm failed." }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Arm emergency access") }
        Row(horizontalArrangement = Arrangement.spacedBy(CKDimens.Space2)) {
            OutlinedButton(onClick = {
                scope.launch {
                    runCatching {
                        val config = store.load() ?: error("not armed")
                        render(EmergencyCronClient(config.cronUrl, config.token).checkIn(config.policyId))
                    }.onFailure { status = "Check-in failed." }
                }
            }) { Text("Check in") }
            OutlinedButton(onClick = {
                scope.launch {
                    runCatching {
                        val config = store.load() ?: error("not armed")
                        render(EmergencyCronClient(config.cronUrl, config.token).cancel(config.policyId))
                    }.onFailure { status = "Cancel failed." }
                }
            }) { Text("Cancel") }
        }
        status?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        shares?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
    }
}
